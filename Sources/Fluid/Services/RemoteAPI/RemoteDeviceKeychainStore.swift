import Foundation
import Security

@MainActor
final class RemoteDeviceKeychainStore: RemoteDeviceStore {
    private let service = "com.fluidvoice.remote-devices"
    private let account = "paired-devices"

    func upsert(_ device: RemoteDevice) throws {
        var devices = try self.load()
        devices[device.id] = device
        try self.save(devices)
    }

    func device(matchingCredentialHash hash: Data) throws -> RemoteDevice? {
        try self.load().values.first { $0.credentialHash == hash }
    }

    func allDevices() throws -> [RemoteDevice] {
        try self.load().values.filter { $0.revokedAt == nil }.sorted { $0.pairedAt < $1.pairedAt }
    }

    func revoke(deviceID: String) throws {
        var devices = try self.load()
        guard var device = devices[deviceID] else { return }
        device.revokedAt = Date()
        devices[deviceID] = device
        try self.save(devices)
    }

    private func load() throws -> [String: RemoteDevice] {
        var query = self.query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [:] }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainServiceError.unhandled(status)
        }
        return try JSONDecoder().decode([String: RemoteDevice].self, from: data)
    }

    private func save(_ devices: [String: RemoteDevice]) throws {
        let data = try JSONEncoder().encode(devices)
        var attributes = self.query
        attributes[kSecValueData as String] = data
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecSuccess { return }
        guard status == errSecDuplicateItem else { throw KeychainServiceError.unhandled(status) }
        let updateStatus = SecItemUpdate(
            self.query as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        guard updateStatus == errSecSuccess else { throw KeychainServiceError.unhandled(updateStatus) }
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: self.service,
            kSecAttrAccount as String: self.account,
        ]
    }
}
