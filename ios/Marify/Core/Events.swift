import Foundation

/// One event from the core (PROTOCOL-BLE.md §10.3). The app reacts to events;
/// it never looks inside protocol messages.
enum Event: Sendable, Equatable {
    case sas(String)
    /// The machine passed the checks and waits for this phone's key. Carries
    /// what the phone learnt about it, for the user to decide on binding.
    case needAnchorKey(NeedAnchorKey)
    case needSignature(purpose: String, verdictCode: Int)
    case enrolled(recordJSON: String)
    case hello(deviceId: String, friendlyName: String)
    case verdict(VerdictEvent)
    case eventlog(Eventlog)
    case receiptAck(result: Int, message: String)
    /// What the machine made of the remote salt this phone released (PROTOCOL-BLE.md §7.3.9).
    case releaseAck(status: Int, message: String)
    case recordUpdated(recordJSON: String)
    case done
    case error(code: Int, message: String)
    /// An event type this app does not know (a newer core). Logged, not acted on.
    case unknown(type: String)

    static let purposeEnrolAccept = "enrol_accept"
    static let purposeReceipt = "receipt"

    struct NeedAnchorKey: Sendable, Equatable {
        var deviceId: String
        var friendlyName: String
        /// TPM vendor whose certificate vouched for the EK; empty if not verified.
        var ekVerifiedBy: String = ""
        var ekNote: String = ""
        var initrdCoverage: String = Event.initrdUnknown
        var initrdPcrs: [Int] = []
        /// Goes into the anchor key's attestation where the platform has one.
        var attestationChallenge: Data? = nil
    }

    static let initrdCovered = "covered"
    static let initrdNotCovered = "not_covered"
    static let initrdNone = "no_initrd"
    static let initrdUnknown = "unknown"

    struct VerdictEvent: Sendable, Equatable {
        var verdict: Verdict
        var needsDecision: Bool
        var eventlogAvailable: Bool
        /// The machine asks this phone to keep a new remote salt; kept when the verdict is accepted.
        var factorOffered: Bool = false
    }

    struct Eventlog: Sendable, Equatable {
        var received: Int64
        var total: Int64
        var complete: Bool
    }

    enum ParseError: Error { case notAnObject, missingRecord, missingVerdict }

    /// Parses one event's JSON as the core produced it.
    static func parse(_ text: String) throws -> Event {
        guard let data = text.data(using: .utf8),
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw ParseError.notAnObject }
        func str(_ k: String) -> String { o[k] as? String ?? "" }
        func int(_ k: String) -> Int { (o[k] as? NSNumber)?.intValue ?? 0 }
        func int64(_ k: String) -> Int64 { (o[k] as? NSNumber)?.int64Value ?? 0 }
        func bool(_ k: String) -> Bool { (o[k] as? Bool) ?? false }
        // The record is opaque: re-serialise the core's object unchanged.
        func record() throws -> String {
            guard let r = o["record"] as? [String: Any] else { throw ParseError.missingRecord }
            let d = try JSONSerialization.data(withJSONObject: r, options: [.sortedKeys])
            return String(decoding: d, as: UTF8.self)
        }
        let type = str("type")
        switch type {
        case "sas": return .sas(str("sas"))
        case "need_anchor_key":
            let coverage = str("initrd_coverage")
            return .needAnchorKey(NeedAnchorKey(
                deviceId: str("device_id"),
                friendlyName: str("friendly_name"),
                ekVerifiedBy: str("ek_verified_by"),
                ekNote: str("ek_note"),
                initrdCoverage: coverage.isEmpty ? initrdUnknown : coverage,
                initrdPcrs: (o["initrd_pcrs"] as? [Any])?.compactMap { ($0 as? NSNumber)?.intValue } ?? [],
                attestationChallenge: Data(hex: str("attestation_challenge"))
            ))
        case "need_signature": return .needSignature(purpose: str("purpose"), verdictCode: int("verdict_code"))
        case "enrolled": return .enrolled(recordJSON: try record())
        case "hello": return .hello(deviceId: str("device_id"), friendlyName: str("friendly_name"))
        case "verdict":
            guard let v = o["verdict"] as? [String: Any] else { throw ParseError.missingVerdict }
            return .verdict(VerdictEvent(
                verdict: Verdict(object: v),
                needsDecision: bool("needs_decision"),
                eventlogAvailable: bool("eventlog_available"),
                factorOffered: bool("factor_offered")
            ))
        case "eventlog": return .eventlog(Eventlog(received: int64("received"), total: int64("total"), complete: bool("complete")))
        case "receipt_ack": return .receiptAck(result: int("result"), message: str("message"))
        case "release_ack": return .releaseAck(status: int("result"), message: str("message"))
        case "record_updated": return .recordUpdated(recordJSON: try record())
        case "done": return .done
        case "error": return .error(code: int("code"), message: str("message"))
        default: return .unknown(type: type)
        }
    }

    /// A short name for the debug log.
    var name: String {
        switch self {
        case .sas: "sas"
        case .needAnchorKey: "need_anchor_key"
        case .needSignature: "need_signature"
        case .enrolled: "enrolled"
        case .hello: "hello"
        case .verdict: "verdict"
        case .eventlog: "eventlog"
        case .receiptAck: "receipt_ack"
        case .releaseAck: "release_ack"
        case .recordUpdated: "record_updated"
        case .done: "done"
        case .error: "error"
        case .unknown(let t): "unknown(\(t))"
        }
    }
}

/// The verdict object (PROTOCOL-BLE.md §10.4), displayed as the core produced it.
struct Verdict: Sendable, Equatable {
    var ok = false
    var state = ""
    var profile = ""
    var reasons: [Reason] = []
    var warnings: [Warning] = []
    var pcrDiff: [PcrDiff] = []
    var diffAgainst = ""
    var explanation = ""
    var resetCount: Int64 = 0
    var restartCount: Int64 = 0
    var clockSafe = false
    var firmwareVersion: Int64 = 0
    /// What the machine's TPM made of the boot challenge (PROTOCOL-BLE.md §7.6).
    var bootKey = Verdict.bootKeyUnused
    /// The code the machine's screen must show, "ABCD-EFGH"; only with `bootKeyProved`.
    var code = ""
    /// TPM Name (hex) of the machine's signing key whose approval the TPM enforced.
    var signingKey = ""

    static let match = "match"
    static let changed = "changed"
    static let failed = "failed"

    static let bootKeyProved = "proved"
    static let bootKeyRefused = "refused"
    static let bootKeyInvalid = "invalid"
    static let bootKeyFailed = "failed"
    static let bootKeyUnused = "unused"

    struct Reason: Sendable, Equatable {
        var code: String
        var detail = ""
        var hard = false
    }

    struct Warning: Sendable, Equatable {
        var code: String
        var detail = ""
    }

    struct PcrDiff: Sendable, Equatable {
        var index: Int
        var expected = ""
        var actual = ""
        var description = ""
    }

    init() {}

    init(object o: [String: Any]) {
        func str(_ k: String) -> String { o[k] as? String ?? "" }
        func int64(_ k: String) -> Int64 { (o[k] as? NSNumber)?.int64Value ?? 0 }
        func bool(_ k: String) -> Bool { (o[k] as? Bool) ?? false }
        ok = bool("ok")
        state = str("state")
        profile = str("profile")
        reasons = (o["reasons"] as? [[String: Any]] ?? []).map {
            Reason(code: $0["code"] as? String ?? "", detail: $0["detail"] as? String ?? "", hard: $0["hard"] as? Bool ?? false)
        }
        warnings = (o["warnings"] as? [[String: Any]] ?? []).map {
            Warning(code: $0["code"] as? String ?? "", detail: $0["detail"] as? String ?? "")
        }
        pcrDiff = (o["pcr_diff"] as? [[String: Any]] ?? []).map {
            PcrDiff(
                index: ($0["index"] as? NSNumber)?.intValue ?? 0,
                expected: $0["expected"] as? String ?? "",
                actual: $0["actual"] as? String ?? "",
                description: $0["description"] as? String ?? ""
            )
        }
        diffAgainst = str("diff_against")
        explanation = str("explanation")
        resetCount = int64("reset_count")
        restartCount = int64("restart_count")
        clockSafe = bool("clock_safe")
        firmwareVersion = int64("firmware_version")
        let bk = str("boot_key")
        bootKey = bk.isEmpty ? Verdict.bootKeyUnused : bk
        code = str("code")
        signingKey = str("signing_key")
    }

    static func parse(_ text: String) throws -> Verdict {
        guard let data = text.data(using: .utf8),
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw Event.ParseError.notAnObject }
        return Verdict(object: o)
    }
}

/// Display fields of a machine record, from Kiracore.recordSummary.
struct RecordSummary: Sendable, Equatable {
    var deviceId: String
    var friendlyName: String
    var enrolledAt: String
    var lastAttested: String?
    var slot: Int = 0
    var profiles: [Profile] = []
    /// TPM vendor whose certificate vouched for the EK at enrolment; empty if not verified.
    var ekVerifiedBy: String = ""
    /// When this phone kept the machine's remote salt; nil when it keeps none.
    var factorKeptAt: String?

    struct Profile: Sendable, Equatable {
        var name: String
        var validFrom = ""
        var addedBy = ""
        var usesLeft: Int64?
        var validUntil: String?
    }

    static func parse(_ text: String) throws -> RecordSummary {
        guard let data = text.data(using: .utf8),
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw Event.ParseError.notAnObject }
        func str(_ k: String) -> String { o[k] as? String ?? "" }
        return RecordSummary(
            deviceId: str("device_id"),
            friendlyName: str("friendly_name"),
            enrolledAt: str("enrolled_at"),
            lastAttested: o["last_attested"] as? String,
            slot: (o["slot"] as? NSNumber)?.intValue ?? 0,
            profiles: (o["profiles"] as? [[String: Any]] ?? []).map {
                Profile(
                    name: $0["name"] as? String ?? "",
                    validFrom: $0["valid_from"] as? String ?? "",
                    addedBy: $0["added_by"] as? String ?? "",
                    usesLeft: ($0["uses_left"] as? NSNumber)?.int64Value,
                    validUntil: $0["valid_until"] as? String
                )
            },
            ekVerifiedBy: str("ek_verified_by"),
            factorKeptAt: o["factor_kept_at"] as? String
        )
    }
}

/// Decisions for `CoreSession.decide` (values equal Kiracore.Decision*).
enum Decision: Int, Sendable {
    case approveOnce = 1
    case approveRemember = 2
    case reject = 3
    /// A matching boot: the person has compared the code and wants the receipt signed.
    case `continue` = 4
}

/// Answers in a receipt_ack event (PROTOCOL-BLE.md §7.3.8).
enum ReceiptResult {
    static let accepted = 1
    static let badSignature = 2
    static let bindingMismatch = 3
    static let rejectNoted = 4
    static let malformed = 5
}

extension Data {
    /// Bytes from a hex string; nil when empty or malformed.
    init?(hex: String) {
        let chars = Array(hex.utf8)
        guard !chars.isEmpty, chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = Data.nibble(chars[i]), let lo = Data.nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        self = out
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 48...57: c - 48
        case 97...102: c - 87
        case 65...70: c - 55
        default: nil
        }
    }

    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
