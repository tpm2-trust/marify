import Foundation

/// One enrolled machine: the core's record, verbatim, plus what the app knows about its key.
struct Machine: Sendable, Identifiable, Equatable {
    var recordJSON: String
    var summary: RecordSummary
    var keyStorage: KeyStorage
    /// Reserved for salt release (PLAN-PHONESALT.md): an opaque per-machine blob.
    var factorBlob: String? = nil

    var id: String { summary.deviceId }
    var deviceId: String { summary.deviceId }
    var name: String { summary.friendlyName }
}

/// Machine records (PROTOCOL-BLE.md §10.5): one file per machine under
/// Application Support/machines, with complete data protection (encrypted
/// under a key the device releases only while unlocked) and excluded from
/// backups. The record JSON is stored exactly as the core produced it and
/// never edited; display fields come from Kiracore.recordSummary.
@MainActor
@Observable
final class MachineStore {
    private(set) var machines: [Machine] = []
    private var loaded = false
    private let dir: URL

    private struct Envelope: Codable {
        var record: String
        var keyStorage: KeyStorage
        var factorBlob: String?

        enum CodingKeys: String, CodingKey {
            case record
            case keyStorage = "key_storage"
            case factorBlob = "factor_blob"
        }
    }

    private static let suffix = ".machine"
    private static let tag = "MarifyMachines"

    init(directory: URL? = nil) {
        if let directory {
            dir = directory
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            dir = base.appendingPathComponent("machines", isDirectory: true)
        }
    }

    /// Reads the stored machines once. The files cannot be read while the
    /// phone is locked; then nothing is marked loaded and the next call (on
    /// becoming active) tries again.
    func loadIfNeeded() {
        if loaded { return }
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var d = dir
            try d.setResourceValues(values)
        } catch {
            debugLog(Self.tag, "cannot prepare the machines directory: \(error)")
        }
        guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        var list: [Machine] = []
        var locked = false
        for f in files where f.lastPathComponent.hasSuffix(Self.suffix) {
            do {
                list.append(try read(f))
            } catch let e as NSError where e.domain == NSCocoaErrorDomain && e.code == NSFileReadNoPermissionError {
                locked = true
            } catch {
                debugLog(Self.tag, "cannot read \(f.lastPathComponent): \(error)")
            }
        }
        if !locked {
            machines = list.sorted { $0.name.lowercased() < $1.name.lowercased() }
            loaded = true
        }
    }

    func get(_ deviceId: String) -> Machine? { machines.first { $0.deviceId == deviceId } }

    /// The enrolled machine that produced this advertisement, if any (§2.3).
    func matchAdvertisement(_ serviceData: Data) -> Machine? {
        machines.first { KiraCore.matchAdvertisement(serviceData, recordJSON: $0.recordJSON) }
    }

    /// Stores the record from an `enrolled` event.
    func saveEnrolled(recordJSON: String, keyStorage: KeyStorage) throws {
        try put(Envelope(record: recordJSON, keyStorage: keyStorage, factorBlob: nil))
    }

    /// Replaces the record after `record_updated`, keeping what the app knows about the key.
    func replace(recordJSON: String) throws {
        let id = try KiraCore.recordSummary(recordJSON).deviceId
        let old = get(id)
        try put(Envelope(record: recordJSON, keyStorage: old?.keyStorage ?? .software, factorBlob: old?.factorBlob))
    }

    func delete(_ deviceId: String) throws {
        try? FileManager.default.removeItem(at: try file(deviceId))
        machines.removeAll { $0.deviceId == deviceId }
    }

    private func put(_ env: Envelope) throws {
        let summary = try KiraCore.recordSummary(env.record)
        let f = try file(summary.deviceId)
        let data = try JSONEncoder().encode(env)
        try data.write(to: f, options: [.atomic, .completeFileProtection])
        let m = Machine(recordJSON: env.record, summary: summary, keyStorage: env.keyStorage, factorBlob: env.factorBlob)
        machines.removeAll { $0.deviceId == m.deviceId }
        machines.append(m)
        machines.sort { $0.name.lowercased() < $1.name.lowercased() }
    }

    private func read(_ f: URL) throws -> Machine {
        let id = String(f.lastPathComponent.dropLast(Self.suffix.count))
        let env = try JSONDecoder().decode(Envelope.self, from: try Data(contentsOf: f))
        let summary = try KiraCore.recordSummary(env.record)
        guard summary.deviceId == id else { throw KiraCore.CoreError(description: "record does not belong to its file") }
        return Machine(recordJSON: env.record, summary: summary, keyStorage: env.keyStorage, factorBlob: env.factorBlob)
    }

    private func file(_ deviceId: String) throws -> URL {
        guard deviceId.count == 32, deviceId.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else {
            throw KiraCore.CoreError(description: "bad device id")
        }
        return dir.appendingPathComponent(deviceId + Self.suffix)
    }
}
