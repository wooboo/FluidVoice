@testable import FluidVoice_Debug
import Foundation
import Network
import XCTest

@MainActor
final class RemoteAPIRouterTests: XCTestCase {
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

    func testNotesRejectMissingCredential() async throws {
        let router = RemoteAPIRouter(
            pairing: RemotePairingCoordinator(store: InMemoryRemoteDeviceStore()),
            dictate: { _ in XCTFail("Inference must not run"); return .init(rawText: "", finalText: "") },
            captureNote: { _ in XCTFail("Capture must not run"); return .fixture },
            listNotes: { XCTFail("Listing must not run"); return [] }
        )

        let listResponse = await router.route(.init(method: "GET", path: "/remote/v1/notes"))
        let captureResponse = await router.route(.init(
            method: "POST",
            path: "/remote/v1/notes",
            body: Data("audio".utf8)
        ))

        XCTAssertEqual(listResponse.status, 401)
        XCTAssertEqual(captureResponse.status, 401)
    }

    func testPairedDeviceCanListNotes() async throws {
        let pairing = pairedCoordinator()
        let note = RemoteAPI.SmartNoteResponse(
            id: "note-1",
            createdAt: "2026-07-11T10:30:00Z",
            title: "Release plan",
            category: "Work",
            tags: ["release", "android"],
            body: "Ship Smart Notes.",
            isAIEnhanced: true
        )
        let router = RemoteAPIRouter(
            pairing: pairing,
            dictate: { _ in .init(rawText: "", finalText: "") },
            captureNote: { _ in .fixture },
            listNotes: { [note] }
        )

        let response = await router.route(.init(
            method: "GET",
            path: "/remote/v1/notes",
            headers: ["authorization": "Bearer phone-token"]
        ))

        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(try response.decode(RemoteAPI.SmartNotesListResponse.self).notes, [note])
    }

    func testPairedDeviceCanCaptureSmartNote() async throws {
        let pairing = pairedCoordinator()
        let router = RemoteAPIRouter(
            pairing: pairing,
            dictate: { _ in .init(rawText: "", finalText: "") },
            captureNote: { input in
                XCTAssertEqual(input.audio, Data("audio".utf8))
                XCTAssertEqual(input.audioFileExtension, "m4a")
                XCTAssertFalse(input.wantsEnhancement)
                return .fixture
            },
            listNotes: { [] }
        )

        let response = await router.route(.init(
            method: "POST",
            path: "/remote/v1/notes",
            headers: [
                "authorization": "Bearer phone-token",
                "content-type": "audio/mp4",
                "x-fluidvoice-enhance": "false",
            ],
            body: Data("audio".utf8)
        ))

        XCTAssertEqual(response.status, 201)
        XCTAssertEqual(try response.decode(RemoteAPI.SmartNoteCaptureResponse.self), .fixture)
    }

    func testPairedDeviceCanDeleteSmartNote() async throws {
        let pairing = pairedCoordinator()
        let noteID = UUID(uuidString: "6A1AF9AF-EE38-49AA-8E51-23E573A62002")!
        var deletedID: UUID?
        let router = RemoteAPIRouter(
            pairing: pairing,
            dictate: { _ in .init(rawText: "", finalText: "") },
            deleteNote: { deletedID = $0 }
        )

        let response = await router.route(.init(
            method: "DELETE",
            path: "/remote/v1/notes/\(noteID.uuidString.lowercased())",
            headers: ["authorization": "Bearer phone-token"]
        ))

        XCTAssertEqual(response.status, 204)
        XCTAssertTrue(response.body.isEmpty)
        XCTAssertEqual(deletedID, noteID)
    }

    func testDeleteNoteRejectsMissingCredentialAndInvalidID() async throws {
        let pairing = pairedCoordinator()
        let router = RemoteAPIRouter(
            pairing: pairing,
            dictate: { _ in .init(rawText: "", finalText: "") },
            deleteNote: { _ in XCTFail("Delete must not run") }
        )

        let unauthorized = await router.route(.init(
            method: "DELETE",
            path: "/remote/v1/notes/6A1AF9AF-EE38-49AA-8E51-23E573A62002"
        ))
        let invalid = await router.route(.init(
            method: "DELETE",
            path: "/remote/v1/notes/not-a-uuid",
            headers: ["authorization": "Bearer phone-token"]
        ))

        XCTAssertEqual(unauthorized.status, 401)
        XCTAssertEqual(invalid.status, 400)
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

@MainActor
private func pairedCoordinator() -> RemotePairingCoordinator {
    let pairing = RemotePairingCoordinator(store: InMemoryRemoteDeviceStore(), randomToken: { "phone-token" })
    pairing.begin(secret: "pair-me")
    _ = try! pairing.complete(.init(deviceID: "phone-1", deviceName: "Pixel", pairingSecret: "pair-me"))
    return pairing
}

private extension RemoteAPI.SmartNoteCaptureResponse {
    static let fixture = Self(
        note: .init(
            id: "note-1",
            createdAt: "2026-07-11T10:30:00Z",
            title: "Release plan",
            category: nil,
            tags: [],
            body: "Ship Smart Notes.",
            isAIEnhanced: false
        ),
        rawText: "Ship Smart Notes.",
        enhancementError: nil
    )
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
