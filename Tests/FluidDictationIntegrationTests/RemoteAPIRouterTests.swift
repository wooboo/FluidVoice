@testable import FluidVoice_Debug
import Foundation
import XCTest

@MainActor
final class RemoteAPIRouterTests: XCTestCase {
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
