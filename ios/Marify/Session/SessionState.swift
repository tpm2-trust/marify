import Foundation

/// What a session is for.
enum SessionMode: Sendable, Equatable {
    case enrol
    /// `lastAttested`: last_attested of the record before this session, for "unchanged since".
    case attest(deviceId: String, machineName: String, lastAttested: String?)

    var isEnrol: Bool { if case .enrol = self { true } else { false } }
    var isAttest: Bool { !isEnrol }
}

enum Phase: Sendable {
    case connecting
    case securing       // Noise handshake
    case confirmSas     // enrolment: compare the six digits
    case confirmBind    // enrolment: what is known about the machine; bind or not
    case enrolling      // enrolment: offer, credential activation, binding
    case verifying      // attestation: waiting for evidence
    case deciding       // the user decides
    case signing        // unlock prompt for the anchor key
    case delivering     // receipt sent, waiting for the machine's answer
    case finished
}

/// Why a session ended without success; selects the message the user sees.
enum FailureKind: Sendable {
    /// The machine advertised as enrolled but refused this phone's secure
    /// connection, twice: its enrolment data was changed or removed, or
    /// another machine imitates it (TODO-SEC.md S1).
    case machineRefused
    /// Connected, but setting up the session (GATT, INFO) failed.
    case connect
    case updateNeeded
    case wrongMode
    case linkLost
    case cancelled
    case sasMismatch
    case authCancelled
    case keyInvalidated
    case keyError
    case storage
    case protocolFailure
}

enum Outcome: Sendable, Equatable {
    case succeeded
    case failed(kind: FailureKind, code: Int, message: String)

    var isSuccess: Bool { self == .succeeded }
}

/// The machine has not been reached in `attempt` attempts so far (or
/// Bluetooth is off); the runner keeps looking until the user cancels.
struct Unreachable: Sendable, Equatable {
    var attempt: Int
    var reason: String
    var waitedMs: Int64
    var bluetoothOff = false
}

struct SessionState: Sendable, Equatable {
    var mode: SessionMode
    var phase: Phase = .connecting
    var unreachable: Unreachable?
    var machineName: String?
    var deviceId: String?
    var sas: String?
    /// Enrolment: the machine's checks, shown before the user binds it.
    var bind: Event.NeedAnchorKey?
    var verdict: Event.VerdictEvent?
    var decisionSent = false
    var eventlogRequested = false
    var eventlog: Event.Eventlog?
    var receiptAck: (result: Int, message: String)?
    /// A recoverable error from the core (the session continues).
    var notice: String?
    var outcome: Outcome?

    init(mode: SessionMode) {
        self.mode = mode
        if case .attest(let id, let name, _) = mode {
            deviceId = id
            machineName = name
        }
    }

    var finished: Bool { outcome != nil }

    var lastAttested: String? {
        if case .attest(_, _, let last) = mode { last } else { nil }
    }

    static func == (a: SessionState, b: SessionState) -> Bool {
        a.mode == b.mode && a.phase == b.phase && a.unreachable == b.unreachable &&
            a.machineName == b.machineName && a.deviceId == b.deviceId && a.sas == b.sas &&
            a.bind == b.bind && a.verdict == b.verdict && a.decisionSent == b.decisionSent &&
            a.eventlogRequested == b.eventlogRequested && a.eventlog == b.eventlog &&
            a.receiptAck?.result == b.receiptAck?.result && a.receiptAck?.message == b.receiptAck?.message &&
            a.notice == b.notice && a.outcome == b.outcome
    }
}

/// The session's state for SwiftUI: the runner publishes every change here.
@MainActor
@Observable
final class SessionStateModel {
    private(set) var value: SessionState

    // Nonisolated: the runner's init, off the main actor, creates it with a
    // Sendable value.
    nonisolated init(_ initial: SessionState) { value = initial }

    func set(_ s: SessionState) { value = s }
}
