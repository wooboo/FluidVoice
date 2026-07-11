import CryptoKit
import Foundation
import Security

struct RemoteTLSIdentity {
    let identity: SecIdentity
    let certificateDER: Data

    var fingerprint: String {
        SHA256.hash(data: self.certificateDER).map { String(format: "%02x", $0) }.joined()
    }
}

enum RemoteTLSIdentityStore {
    private static let keyTag = Data("com.fluidvoice.remote-tls-key".utf8)
    private static let certificateService = "com.fluidvoice.remote-tls"
    private static let certificateAccount = "server-certificate"

    static func loadOrCreate() throws -> RemoteTLSIdentity {
        if let key = try self.loadKey(), let certificateDER = try self.loadCertificate(),
           let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData),
           let identity = SecIdentityCreate(nil, certificate, key)
        {
            return RemoteTLSIdentity(identity: identity, certificateDER: certificateDER)
        }

        self.deleteStoredIdentity()
        let key = try self.createKey()
        let certificateDER = try SelfSignedCertificate.make(privateKey: key, commonName: "FluidVoice Remote")
        guard let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData),
              let identity = SecIdentityCreate(nil, certificate, key)
        else {
            self.deleteStoredIdentity()
            throw NSError(domain: "RemoteTLSIdentity", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create TLS identity."])
        }
        try self.saveCertificate(certificateDER)
        return RemoteTLSIdentity(identity: identity, certificateDER: certificateDER)
    }

    private static func createKey() throws -> SecKey {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: self.keyTag,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw error?.takeRetainedValue() ?? NSError(domain: "RemoteTLSIdentity", code: -2)
        }
        return key
    }

    private static func loadKey() throws -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: self.keyTag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainServiceError.unhandled(status) }
        guard let result else { return nil }
        return (result as! SecKey)
    }

    private static func loadCertificate() throws -> Data? {
        var query = self.certificateQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainServiceError.unhandled(status) }
        return result as? Data
    }

    private static func saveCertificate(_ data: Data) throws {
        var query = self.certificateQuery
        query[kSecValueData as String] = data
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainServiceError.unhandled(status) }
    }

    private static func deleteStoredIdentity() {
        SecItemDelete([
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: self.keyTag,
        ] as CFDictionary)
        SecItemDelete(self.certificateQuery as CFDictionary)
    }

    private static var certificateQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: self.certificateService,
            kSecAttrAccount as String: self.certificateAccount,
        ]
    }
}

private enum SelfSignedCertificate {
    static func make(privateKey: SecKey, commonName: String) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let publicBytes = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?
        else {
            throw NSError(domain: "RemoteTLSIdentity", code: -3)
        }

        let signatureAlgorithm = DER.sequence(DER.oid([1, 2, 840, 10045, 4, 3, 2]))
        let name = DER.sequence(DER.set(DER.sequence(
            DER.oid([2, 5, 4, 3]),
            DER.tag(0x0C, Data(commonName.utf8))
        )))
        let subjectPublicKeyInfo = DER.sequence(
            DER.sequence(
                DER.oid([1, 2, 840, 10045, 2, 1]),
                DER.oid([1, 2, 840, 10045, 3, 1, 7])
            ),
            DER.bitString(publicBytes)
        )
        var serial = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial) == errSecSuccess else {
            throw NSError(domain: "RemoteTLSIdentity", code: -4)
        }
        serial[0] &= 0x7F
        let now = Date().addingTimeInterval(-300)
        let expires = Calendar(identifier: .gregorian).date(byAdding: .year, value: 10, to: now)!
        let extensions = DER.explicit(3, DER.sequence(
            DER.sequence(
                DER.oid([2, 5, 29, 19]),
                DER.boolean(true),
                DER.octetString(DER.sequence())
            ),
            DER.sequence(
                DER.oid([2, 5, 29, 15]),
                DER.boolean(true),
                DER.octetString(DER.bitString(Data([0x80]), unusedBits: 7))
            ),
            DER.sequence(
                DER.oid([2, 5, 29, 37]),
                DER.octetString(DER.sequence(DER.oid([1, 3, 6, 1, 5, 5, 7, 3, 1])))
            )
        ))
        let tbs = DER.sequence(
            DER.explicit(0, DER.integer(Data([2]))),
            DER.integer(Data(serial)),
            signatureAlgorithm,
            name,
            DER.sequence(DER.generalizedTime(now), DER.generalizedTime(expires)),
            name,
            subjectPublicKeyInfo,
            extensions
        )
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            tbs as CFData,
            &error
        ) as Data? else {
            throw error?.takeRetainedValue() ?? NSError(domain: "RemoteTLSIdentity", code: -5)
        }
        return DER.sequence(tbs, signatureAlgorithm, DER.bitString(signature))
    }
}

private enum DER {
    static func sequence(_ values: Data...) -> Data { self.tag(0x30, values.reduce(Data(), +)) }
    static func set(_ value: Data) -> Data { self.tag(0x31, value) }
    static func integer(_ value: Data) -> Data { self.tag(0x02, value.first.map { $0 & 0x80 == 0 ? value : Data([0]) + value } ?? Data([0])) }
    static func boolean(_ value: Bool) -> Data { self.tag(0x01, Data([value ? 0xFF : 0])) }
    static func octetString(_ value: Data) -> Data { self.tag(0x04, value) }
    static func bitString(_ value: Data, unusedBits: UInt8 = 0) -> Data { self.tag(0x03, Data([unusedBits]) + value) }
    static func explicit(_ number: UInt8, _ value: Data) -> Data { self.tag(0xA0 | number, value) }

    static func generalizedTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMddHHmmss'Z'"
        return self.tag(0x18, Data(formatter.string(from: date).utf8))
    }

    static func oid(_ components: [UInt64]) -> Data {
        precondition(components.count >= 2)
        var bytes = [UInt8(components[0] * 40 + components[1])]
        for value in components.dropFirst(2) {
            var encoded = [UInt8(value & 0x7F)]
            var remaining = value >> 7
            while remaining > 0 {
                encoded.insert(UInt8(remaining & 0x7F) | 0x80, at: 0)
                remaining >>= 7
            }
            bytes.append(contentsOf: encoded)
        }
        return self.tag(0x06, Data(bytes))
    }

    static func tag(_ tag: UInt8, _ value: Data) -> Data {
        Data([tag]) + self.length(value.count) + value
    }

    private static func length(_ count: Int) -> Data {
        if count < 128 { return Data([UInt8(count)]) }
        var value = count
        var bytes: [UInt8] = []
        while value > 0 {
            bytes.insert(UInt8(value & 0xFF), at: 0)
            value >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }
}
