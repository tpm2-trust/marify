import CryptoKit
import Foundation
import LocalAuthentication

/// Anchor keys in the Secure Enclave (CryptoKit): P-256, non-exportable,
/// each use gated by the key's access control. Every signature evaluates a
/// fresh `LAContext` first, so a phone that is merely unlocked cannot sign:
/// the person authenticates again, by Face ID / Touch ID or, unless the key
/// was made biometric-only, by the passcode. A changed biometric set
/// invalidates the key (`.biometryCurrentSet`); the machine must then be
/// enrolled again.
///
/// The key's opaque, enclave-bound representation is a keychain item (this
/// device only). The simulator has no Secure Enclave: debug builds fall back
/// to a software key there, release builds refuse.
final class SecureEnclaveAnchorKeys: AnchorKeyProvider, @unchecked Sendable {
    private static let verdictApproved = 2

    private static func account(_ deviceId: String) -> String { "anchor." + deviceId }
    private static func metaAccount(_ deviceId: String) -> String { "anchor-meta." + deviceId }

    /// What is stored next to the key: where it lives and how it unlocks.
    private struct Meta: Codable {
        var storage: KeyStorage
        var biometricOnly: Bool
    }

    func create(deviceId: String, biometricOnly: Bool, attestationChallenge: Data?) async throws -> CreatedAnchorKey {
        delete(deviceId: deviceId)
        _ = attestationChallenge // iOS has no key attestation for these keys.
        if SecureEnclave.isAvailable {
            var flags: SecAccessControlCreateFlags = [.privateKeyUsage, .biometryCurrentSet]
            if !biometricOnly { flags.formUnion([.or, .devicePasscode]) }
            var error: Unmanaged<CFError>?
            guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, flags, &error) else {
                throw AnchorKeyError.failure("access control: \(error?.takeRetainedValue().localizedDescription ?? "?")")
            }
            let key: SecureEnclave.P256.Signing.PrivateKey
            do {
                key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
            } catch {
                throw AnchorKeyError.failure("Secure Enclave key creation: \(error)")
            }
            try store(deviceId, key.dataRepresentation, Meta(storage: .secureEnclave, biometricOnly: biometricOnly))
            return CreatedAnchorKey(spkiDer: key.publicKey.derRepresentation, storage: .secureEnclave, attestation: nil)
        }
        #if DEBUG
        // Simulator: a software key, for development only.
        let key = P256.Signing.PrivateKey()
        try store(deviceId, key.rawRepresentation, Meta(storage: .software, biometricOnly: biometricOnly))
        return CreatedAnchorKey(spkiDer: key.publicKey.derRepresentation, storage: .software, attestation: nil)
        #else
        throw AnchorKeyError.failure("this phone has no Secure Enclave")
        #endif
    }

    private func store(_ deviceId: String, _ key: Data, _ meta: Meta) throws {
        do {
            try Keychain.write(Self.account(deviceId), key)
            try Keychain.write(Self.metaAccount(deviceId), try JSONEncoder().encode(meta))
        } catch {
            delete(deviceId: deviceId)
            throw AnchorKeyError.failure("keychain: \(error)")
        }
    }

    private func load(_ deviceId: String) -> (key: Data, meta: Meta)? {
        guard let key = try? Keychain.read(Self.account(deviceId)),
              let metaData = try? Keychain.read(Self.metaAccount(deviceId)),
              let meta = try? JSONDecoder().decode(Meta.self, from: metaData)
        else { return nil }
        return (key, meta)
    }

    func details(deviceId: String) -> AnchorKeyDetails? {
        guard let stored = load(deviceId) else { return nil }
        return AnchorKeyDetails(storage: stored.meta.storage, biometricOnly: stored.meta.biometricOnly)
    }

    func sign(deviceId: String, tbs: Data, request: SignRequest) async throws -> Data {
        guard let stored = load(deviceId) else {
            throw AnchorKeyError.keyInvalidated("no anchor key for this machine on this phone")
        }
        let keyData = stored.key
        let meta = stored.meta
        if meta.storage == .software {
            #if DEBUG
            let key = try P256.Signing.PrivateKey(rawRepresentation: keyData)
            return try key.signature(for: tbs).derRepresentation
            #else
            throw AnchorKeyError.keyInvalidated("the key is not in the Secure Enclave")
            #endif
        }
        // A new context for every signature: the person authenticates again.
        let context = LAContext()
        context.localizedCancelTitle = String(localized: "Cancel")
        let policy: LAPolicy = meta.biometricOnly ? .deviceOwnerAuthenticationWithBiometrics : .deviceOwnerAuthentication
        do {
            let ok = try await context.evaluatePolicy(policy, localizedReason: Self.reason(request))
            if !ok { throw AnchorKeyError.signingCancelled("authentication failed") }
        } catch let e as LAError {
            throw Self.classify(e)
        } catch let e as AnchorKeyError {
            throw e
        } catch {
            throw AnchorKeyError.signingCancelled("\(error)")
        }
        let key: SecureEnclave.P256.Signing.PrivateKey
        do {
            key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: keyData, authenticationContext: context)
        } catch {
            throw AnchorKeyError.keyInvalidated("the Secure Enclave no longer accepts this key: \(error)")
        }
        do {
            // CryptoKit hashes with SHA-256; the DER form is what the core expects.
            return try key.signature(for: tbs).derRepresentation
        } catch {
            // The access control no longer holds although the person just
            // authenticated: the biometric set changed since the key was made.
            throw AnchorKeyError.keyInvalidated("\(error)")
        }
    }

    private static func classify(_ e: LAError) -> AnchorKeyError {
        switch e.code {
        case .userCancel, .appCancel, .systemCancel, .userFallback, .authenticationFailed, .biometryLockout:
            return .signingCancelled(e.localizedDescription)
        case .biometryNotEnrolled, .biometryNotAvailable, .passcodeNotSet:
            return .keyInvalidated(e.localizedDescription)
        default:
            return .failure(e.localizedDescription)
        }
    }

    private static func reason(_ r: SignRequest) -> String {
        if r.purpose == Event.purposeEnrolAccept {
            return String(localized: "Bind \(r.machineName) to this phone")
        }
        if r.verdictCode == verdictApproved {
            return String(localized: "Approve the changed boot of \(r.machineName)")
        }
        return String(localized: "Confirm \(r.machineName) booted unchanged")
    }

    func delete(deviceId: String) {
        Keychain.delete(Self.account(deviceId))
        Keychain.delete(Self.metaAccount(deviceId))
    }
}
