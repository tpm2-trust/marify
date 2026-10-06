import Foundation
import UIKit

/// This installation's verifier identity (PROTOCOL-BLE.md §11.1).
struct VerifierIdentity: Sendable {
    let noisePriv: Data
    let verifierId: String
    let verifierName: String

    /// Config JSON for Kiracore.new*Session (§10.1).
    func configJSON() -> String {
        let o: [String: Any] = [
            "verifier_id": verifierId,
            "verifier_name": verifierName,
            "policy_id": "default",
            "receipt_ttl_seconds": 300,
        ]
        let d = try! JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])
        return String(decoding: d, as: UTF8.self)
    }
}

/// The static Noise key, created once per installation with
/// Kiracore.generateNoiseKey and kept in the keychain (this device only,
/// readable while unlocked), next to the verifier id.
final class NoiseKeyStore: @unchecked Sendable {
    private let lock = NSLock()
    private var cached: VerifierIdentity?

    private static let noiseAccount = "noise-key"
    private static let idAccount = "verifier-id"

    func identity() throws -> VerifierIdentity {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        let id = try loadOrCreate()
        cached = id
        return id
    }

    private func loadOrCreate() throws -> VerifierIdentity {
        // The device's user-chosen name is not available to apps; the model is.
        let name = UIDevice.current.model
        if let priv = try Keychain.read(Self.noiseAccount),
           let idData = try Keychain.read(Self.idAccount),
           let id = String(data: idData, encoding: .utf8) {
            return VerifierIdentity(noisePriv: priv, verifierId: id, verifierName: name)
        }
        let priv = try KiraCore.generateNoiseKey()
        var random = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, random.count, &random) == errSecSuccess else {
            throw Keychain.KeychainError(status: errSecInternalError)
        }
        let newId = Data(random).hexString
        try Keychain.write(Self.noiseAccount, priv)
        try Keychain.write(Self.idAccount, Data(newId.utf8))
        return VerifierIdentity(noisePriv: priv, verifierId: newId, verifierName: name)
    }
}
