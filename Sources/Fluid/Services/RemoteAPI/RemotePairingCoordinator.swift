import CryptoKit
import Foundation
import Security

struct RemoteDevice: Codable, Equatable, Identifiable {
    let id: String
    var name: String
    let credentialHash: Data
    let pairedAt: Date
    var lastSeenAt: Date?
    var revokedAt: Date?
}

@MainActor
protocol RemoteDeviceStore: AnyObject {
    func upsert(_ device: RemoteDevice) throws
    func device(matchingCredentialHash hash: Data) throws -> RemoteDevice?
}

@MainActor
final class InMemoryRemoteDeviceStore: RemoteDeviceStore {
    private var devices: [String: RemoteDevice] = [:]

    func upsert(_ device: RemoteDevice) {
        self.devices[device.id] = device
    }

    func device(matchingCredentialHash hash: Data) -> RemoteDevice? {
        self.devices.values.first { $0.credentialHash == hash }
    }

    func device(id: String) -> RemoteDevice? { self.devices[id] }
}

enum RemotePairingError: Error {
    case invalidOrExpiredSecret
    case invalidDevice
}

@MainActor
final class RemotePairingCoordinator {
    private struct ActiveSecret {
        let hash: Data
        let expiresAt: Date
    }

    private let store: RemoteDeviceStore
    private let randomToken: () -> String
    private let now: () -> Date
    private var activeSecret: ActiveSecret?

    init(
        store: RemoteDeviceStore,
        randomToken: @escaping () -> String = RemotePairingCoordinator.secureToken,
        now: @escaping () -> Date = Date.init
    ) {
        self.store = store
        self.randomToken = randomToken
        self.now = now
    }

    func begin(secret: String = RemotePairingCoordinator.secureToken()) -> String {
        self.activeSecret = ActiveSecret(hash: Self.hash(secret), expiresAt: self.now().addingTimeInterval(300))
        return secret
    }

    func cancel() {
        self.activeSecret = nil
    }

    func complete(_ request: RemoteAPI.PairRequest) throws -> RemoteAPI.PairResponse {
        guard !request.deviceID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !request.deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw RemotePairingError.invalidDevice
        }
        guard let activeSecret = self.activeSecret,
              self.now() <= activeSecret.expiresAt,
              Self.hash(request.pairingSecret) == activeSecret.hash
        else {
            throw RemotePairingError.invalidOrExpiredSecret
        }

        self.activeSecret = nil
        let credential = self.randomToken()
        try self.store.upsert(RemoteDevice(
            id: request.deviceID,
            name: request.deviceName,
            credentialHash: Self.hash(credential),
            pairedAt: self.now(),
            lastSeenAt: nil,
            revokedAt: nil
        ))
        return RemoteAPI.PairResponse(credential: credential)
    }

    func authorizes(_ credential: String) -> Bool {
        guard var device = try? self.store.device(matchingCredentialHash: Self.hash(credential)),
              device.revokedAt == nil
        else { return false }
        device.lastSeenAt = self.now()
        do {
            try self.store.upsert(device)
            return true
        } catch {
            return false
        }
    }

    private static func hash(_ value: String) -> Data {
        Data(SHA256.hash(data: Data(value.utf8)))
    }

    private nonisolated static func secureToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "Secure random generation failed")
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
