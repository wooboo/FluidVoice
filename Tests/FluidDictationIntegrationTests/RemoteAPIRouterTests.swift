@testable import FluidVoice_Debug
import Foundation
import Network
import XCTest

@MainActor
final class RemoteAPIRouterTests: XCTestCase {
    func testRemoteAPIServerClosesConnectionAfterMalformedRequest() async throws {
        let server = self.makeTestServer()
        defer { server.stop() }
        try await self.start(server)

        let connection = try await self.connectTLS(to: server.port)
        defer { connection.cancel() }
        try await self.send(Data("BROKEN\r\n\r\n".utf8), over: connection)
        let response = try await self.receiveHeaders(from: connection)

        XCTAssertTrue(response.contains("HTTP/1.1 400 Bad Request"))
        XCTAssertTrue(response.localizedCaseInsensitiveContains("Connection: close"))
        await self.assertConnectionCloses(connection)
    }

    func testRemoteAPIServerClosesConnectionAndDropsOversizedBuffer() async throws {
        let server = self.makeTestServer(maxRequestBytes: 256)
        defer { server.stop() }
        try await self.start(server)

        let connection = try await self.connectTLS(to: server.port)
        defer { connection.cancel() }
        var request = Data("POST /remote/v1/dictate HTTP/1.1\r\nContent-Length: 220\r\n\r\n".utf8)
        request.append(Data(repeating: 0x41, count: 220))
        XCTAssertGreaterThan(request.count, 256)
        try await self.send(request, over: connection)
        let response = try await self.receiveHeaders(from: connection)

        XCTAssertTrue(response.contains("HTTP/1.1 413 Payload Too Large"))
        XCTAssertTrue(response.localizedCaseInsensitiveContains("Connection: close"))
        await self.assertConnectionCloses(connection)
    }

    func testRemoteAPIServerRetriesAfterStartupPortConflict() async throws {
        let port = UInt16.random(in: 49_152 ... 60_000)
        let blocker = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
        let blockerReady = self.expectation(description: "Blocking listener is ready")
        blocker.newConnectionHandler = { $0.cancel() }
        blocker.stateUpdateHandler = { state in
            if case .ready = state { blockerReady.fulfill() }
        }
        blocker.start(queue: DispatchQueue(label: "fluidvoice.remote-api-test-blocker"))
        await self.fulfillment(of: [blockerReady], timeout: 2)

        let server = RemoteAPIServer(
            port: port,
            retryDelayNanoseconds: 50_000_000,
            isEnabled: { true }
        )
        defer {
            server.stop()
            blocker.cancel()
        }

        server.start()
        try await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertFalse(server.isRunning)

        blocker.cancel()
        let deadline = Date().addingTimeInterval(2)
        while !server.isRunning, Date() < deadline {
            try await Task.sleep(nanoseconds: 50_000_000)
        }

        XCTAssertTrue(server.isRunning, "Server should retry after the occupied port is released")
    }

    func testDictateRejectsMissingCredential() async throws {
        let router = RemoteAPIRouter(
            pairing: RemotePairingCoordinator(store: InMemoryRemoteDeviceStore()),
            dictate: { _ in XCTFail("Inference must not run"); return .init(rawText: "", finalText: "") }
        )

        let response = await router.route(.init(method: "POST", path: "/remote/v1/dictate", body: Data("audio".utf8)))

        XCTAssertEqual(response.status, 401)
    }

    private func makeTestServer(maxRequestBytes: Int = RemoteAPI.maxRequestBytes) -> RemoteAPIServer {
        RemoteAPIServer(
            port: UInt16.random(in: 49_152 ... 60_000),
            retryDelayNanoseconds: 50_000_000,
            maxRequestBytes: maxRequestBytes,
            isEnabled: { true }
        )
    }

    private func start(_ server: RemoteAPIServer) async throws {
        server.start()
        let deadline = Date().addingTimeInterval(2)
        while !server.isRunning, Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(server.isRunning, "Test server should start")
    }

    private func connectTLS(to port: UInt16) async throws -> NWConnection {
        let tls = NWProtocolTLS.Options()
        let verifyQueue = DispatchQueue(label: "fluidvoice.remote-api-test-verify")
        sec_protocol_options_set_verify_block(
            tls.securityProtocolOptions,
            { _, _, complete in complete(true) },
            verifyQueue
        )
        let connection = NWConnection(
            host: "127.0.0.1",
            port: NWEndpoint.Port(rawValue: port)!,
            using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        )
        let queue = DispatchQueue(label: "fluidvoice.remote-api-test-client")
        return try await withCheckedThrowingContinuation { continuation in
            var completed = false
            connection.stateUpdateHandler = { state in
                guard !completed else { return }
                switch state {
                case .ready:
                    completed = true
                    continuation.resume(returning: connection)
                case let .failed(error):
                    completed = true
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            connection.start(queue: queue)
        }
    }

    private func send(_ data: Data, over connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            })
        }
    }

    private func receiveHeaders(from connection: NWConnection) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            var buffer = Data()
            var receiveNext: (() -> Void)!
            receiveNext = {
                connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { data, _, complete, error in
                    if let data { buffer.append(data) }
                    if let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) {
                        continuation.resume(returning: String(decoding: buffer[..<headerEnd.upperBound], as: UTF8.self))
                    } else if let error {
                        continuation.resume(throwing: error)
                    } else if complete {
                        continuation.resume(throwing: URLError(.cannotParseResponse))
                    } else {
                        receiveNext()
                    }
                }
            }
            receiveNext()
        }
    }

    private func assertConnectionCloses(_ connection: NWConnection) async {
        let closed = self.expectation(description: "Server closes the connection")
        var receiveNext: (() -> Void)!
        receiveNext = {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { _, _, complete, error in
                if complete || error != nil {
                    closed.fulfill()
                } else {
                    receiveNext()
                }
            }
        }
        receiveNext()
        await self.fulfillment(of: [closed], timeout: 1)
    }

    func testPairingSecretCanBeExchangedOnceForDeviceCredential() async throws {
        let store = InMemoryRemoteDeviceStore()
        let pairing = RemotePairingCoordinator(
            store: store,
            randomToken: { "issued-device-token" }
        )
        pairing.begin(secret: "one-time-secret")
        let router = RemoteAPIRouter(pairing: pairing, dictate: { _ in .init(rawText: "hello", finalText: "Hello.") })
        let request = RemoteAPI.PairRequest(deviceID: "phone-1", deviceName: "Pixel", pairingSecret: "one-time-secret")

        let first = await router.route(.json(method: "POST", path: "/remote/v1/pair", value: request))
        let second = await router.route(.json(method: "POST", path: "/remote/v1/pair", value: request))

        XCTAssertEqual(first.status, 201)
        XCTAssertEqual(try first.decode(RemoteAPI.PairResponse.self).credential, "issued-device-token")
        XCTAssertEqual(second.status, 403)
    }

    func testPairedDeviceCanDictateWithItsCredential() async throws {
        let store = InMemoryRemoteDeviceStore()
        let pairing = RemotePairingCoordinator(store: store, randomToken: { "phone-token" })
        pairing.begin(secret: "pair-me")
        _ = try pairing.complete(.init(deviceID: "phone-1", deviceName: "Pixel", pairingSecret: "pair-me"))
        let router = RemoteAPIRouter(pairing: pairing, dictate: { input in
            XCTAssertEqual(input.audio, Data("audio".utf8))
            XCTAssertEqual(input.audioFileExtension, "wav")
            XCTAssertFalse(input.wantsEnhancement)
            XCTAssertFalse(input.requestID.isEmpty)
            return .init(rawText: "hello", finalText: "Hello.")
        })

        let response = await router.route(.init(
            method: "POST",
            path: "/remote/v1/dictate",
            headers: [
                "authorization": "Bearer phone-token",
                "x-fluidvoice-enhance": "false",
            ],
            body: Data("audio".utf8)
        ))

        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(try response.decode(RemoteAPI.DictateResponse.self).finalText, "Hello.")
    }

    func testPairedDeviceCanPreconnectWithoutRunningInference() async throws {
        let store = InMemoryRemoteDeviceStore()
        let pairing = RemotePairingCoordinator(store: store, randomToken: { "phone-token" })
        pairing.begin(secret: "pair-me")
        _ = try pairing.complete(.init(deviceID: "phone-1", deviceName: "Pixel", pairingSecret: "pair-me"))
        let router = RemoteAPIRouter(
            pairing: pairing,
            dictate: { _ in XCTFail("Preconnect must not run inference"); return .init(rawText: "", finalText: "") }
        )

        let response = await router.route(.init(
            method: "POST",
            path: "/remote/v1/preconnect",
            headers: ["authorization": "Bearer phone-token"]
        ))

        XCTAssertEqual(response.status, 204)
        XCTAssertTrue(response.body.isEmpty)
    }

    func testPreconnectRejectsMissingCredential() async throws {
        let router = RemoteAPIRouter(
            pairing: RemotePairingCoordinator(store: InMemoryRemoteDeviceStore()),
            dictate: { _ in XCTFail("Preconnect must not run inference"); return .init(rawText: "", finalText: "") }
        )

        let response = await router.route(.init(method: "POST", path: "/remote/v1/preconnect"))

        XCTAssertEqual(response.status, 401)
    }

    func testPairingSecretExpiresAfterFiveMinutes() throws {
        var now = Date(timeIntervalSince1970: 1_000)
        let pairing = RemotePairingCoordinator(
            store: InMemoryRemoteDeviceStore(),
            randomToken: { "token" },
            now: { now }
        )
        pairing.begin(secret: "short-lived")
        now.addTimeInterval(301)

        XCTAssertThrowsError(try pairing.complete(.init(
            deviceID: "phone-1",
            deviceName: "Pixel",
            pairingSecret: "short-lived"
        )))
    }
}

private extension RemoteAPI.Request {
    static func json<T: Encodable>(method: String, path: String, value: T) -> Self {
        .init(
            method: method,
            path: path,
            headers: ["content-type": "application/json"],
            body: try! JSONEncoder().encode(value)
        )
    }
}

private extension RemoteAPI.Response {
    func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: self.body)
    }
}
