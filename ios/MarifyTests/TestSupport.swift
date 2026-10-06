import CryptoKit
import Foundation
@testable import Marify

/// A P-256 key in memory with the anchor key's contract: DER ECDSA over
/// SHA-256 of the message, SPKI DER public key. Records every signing
/// request; can cancel the next one like a dismissed unlock prompt.
final class SoftwareAnchorKeys: AnchorKeyProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var keys: [String: P256.Signing.PrivateKey] = [:]
    private(set) var requests: [SignRequest] = []
    var cancelNext = false

    func create(deviceId: String, biometricOnly: Bool, attestationChallenge: Data?) async throws -> CreatedAnchorKey {
        let k = P256.Signing.PrivateKey()
        lock.lock(); keys[deviceId] = k; lock.unlock()
        return CreatedAnchorKey(spkiDer: k.publicKey.derRepresentation, storage: .software, attestation: nil)
    }

    func sign(deviceId: String, tbs: Data, request: SignRequest) async throws -> Data {
        lock.lock()
        requests.append(request)
        let cancel = cancelNext
        cancelNext = false
        let k = keys[deviceId]
        lock.unlock()
        if cancel { throw AnchorKeyError.signingCancelled("cancelled") }
        guard let k else { throw AnchorKeyError.keyInvalidated("no key") }
        return try k.signature(for: tbs).derRepresentation
    }

    func delete(deviceId: String) {
        lock.lock(); keys[deviceId] = nil; lock.unlock()
    }

    func details(deviceId: String) -> AnchorKeyDetails? {
        lock.lock(); defer { lock.unlock() }
        return keys[deviceId] == nil ? nil : AnchorKeyDetails(storage: .software, biometricOnly: false)
    }
}

/// Keeps the latest record in memory.
final class MemoryRecords: RecordSink, @unchecked Sendable {
    private let lock = NSLock()
    private var _record = ""
    private(set) var saved: [String] = []
    private(set) var replaced: [String] = []

    var record: String {
        lock.lock(); defer { lock.unlock() }
        return _record
    }

    func saveEnrolled(recordJSON: String, keyStorage: KeyStorage) async throws {
        lock.lock(); _record = recordJSON; saved.append(recordJSON); lock.unlock()
    }

    func replace(recordJSON: String) async throws {
        lock.lock(); _record = recordJSON; replaced.append(recordJSON); lock.unlock()
    }
}

let testConfig = """
{"verifier_id":"instrumented-test","verifier_name":"Test phone","policy_id":"default","receipt_ttl_seconds":300}
"""

/// Runs one session to its end while `react` plays the person on every
/// state change; returns the final state.
func runSession(
    _ runner: SessionRunner,
    timeoutSeconds: Double = 30,
    react: @escaping @Sendable (SessionRunner, SessionState) async -> Void
) async throws -> SessionState {
    let states = await runner.states()
    let person = Task {
        for await st in states { await react(runner, st) }
    }
    try await withTimeout(seconds: timeoutSeconds) { await runner.run() }
    person.cancel()
    return await runner.state
}

extension Outcome {
    var failureKind: FailureKind? {
        if case .failed(let kind, _, _) = self { kind } else { nil }
    }
}
