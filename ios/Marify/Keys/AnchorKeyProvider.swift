import Foundation

/// Where an anchor key lives (shown in machine details).
enum KeyStorage: String, Sendable, Codable {
    case secureEnclave = "secure_enclave"
    case software
}

/// `attestation`: the key's attestation certificate chain (concatenated DER,
/// leaf first) for the machine to check. iOS has no key attestation for
/// Secure Enclave keys, so it is nil here; the machine reports that.
struct CreatedAnchorKey: Sendable {
    var spkiDer: Data
    var storage: KeyStorage
    var attestation: Data? = nil
}

/// What a signature is for; selects the unlock prompt's text.
struct SignRequest: Sendable {
    var machineName: String
    var purpose: String
    var verdictCode: Int
}

/// Facts about a stored anchor key, read back from the keychain.
struct AnchorKeyDetails: Sendable, Equatable {
    var storage: KeyStorage
    /// Only Face ID / Touch ID unlocks it, never the passcode.
    var biometricOnly: Bool
}

enum AnchorKeyError: Error, Sendable {
    /// The user dismissed the unlock prompt (biometric or passcode).
    case signingCancelled(String)
    /// The key was invalidated (biometrics changed, or the passcode removed):
    /// the machine must be enrolled again.
    case keyInvalidated(String)
    /// Any other keychain or Secure Enclave failure.
    case failure(String)
}

/// The per-machine anchor key (PROTOCOL-BLE.md §11.2): ECDSA P-256,
/// non-exportable, in the Secure Enclave, a fresh unlock for every
/// signature. Tests substitute a software implementation.
protocol AnchorKeyProvider: Sendable {
    /// Creates (or replaces) the key for this machine; returns SPKI DER (91
    /// bytes). With `biometricOnly` the passcode does not unlock it.
    func create(deviceId: String, biometricOnly: Bool, attestationChallenge: Data?) async throws -> CreatedAnchorKey

    /// DER ECDSA over SHA-256(tbs): the platform hashes, no raw r‖s.
    func sign(deviceId: String, tbs: Data, request: SignRequest) async throws -> Data

    func delete(deviceId: String)

    func details(deviceId: String) -> AnchorKeyDetails?
}
