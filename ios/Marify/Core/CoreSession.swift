import Foundation
import Kiracore

/// The result of one call into the core: fragments to write to RX in order,
/// events to act on, and a non-empty `err` if the call failed. A failing step
/// still carries fragments (the Error message for the machine): write them.
struct StepResult: Sendable {
    var fragments: [Data]
    var events: [Event]
    var err: String

    static let empty = StepResult(fragments: [], events: [], err: "")
}

/// One protocol session (PROTOCOL-BLE.md §10.2). Implemented by
/// `GoCoreSession` over Kiracore's Session; tests substitute a fake to check
/// the app's side of the contract. Called only from the SessionRunner actor.
protocol CoreSession: AnyObject {
    func setMaxFragment(_ n: Int)
    func start() -> StepResult
    func onNotification(_ value: Data) -> StepResult
    func confirmSas(_ match: Bool) -> StepResult
    func provideAnchorKey(spkiDer: Data) -> StepResult
    /// `attestation`: the key's certificate chain, concatenated DER, leaf first; empty if none.
    func provideAnchorKeyAttested(spkiDer: Data, attestation: Data) -> StepResult
    func pendingTbs() -> Data
    func provideSignature(der: Data) -> StepResult
    func decide(_ decision: Decision, confirmName: String) -> StepResult
    func requestEventlog() -> StepResult
    func eventlog() -> Data?
    func abort(_ reason: String) -> StepResult
    func finished() -> Bool
    func succeeded() -> Bool
    func recordJSON() -> String
}

final class GoCoreSession: CoreSession {
    private let s: KiracoreSession

    init(_ s: KiracoreSession) { self.s = s }

    func setMaxFragment(_ n: Int) { try? s.setMaxFragment(n) }
    func start() -> StepResult { s.start().toResult() }
    func onNotification(_ value: Data) -> StepResult { s.onNotification(value).toResult() }
    func confirmSas(_ match: Bool) -> StepResult { s.confirmSAS(match).toResult() }
    func provideAnchorKey(spkiDer: Data) -> StepResult { s.provideAnchorKey(spkiDer).toResult() }
    func provideAnchorKeyAttested(spkiDer: Data, attestation: Data) -> StepResult {
        s.provideAnchorKeyAttested(spkiDer, attestationDER: attestation).toResult()
    }
    func pendingTbs() -> Data { s.pendingTBS() ?? Data() }
    func provideSignature(der: Data) -> StepResult { s.provideSignature(der).toResult() }
    func decide(_ decision: Decision, confirmName: String) -> StepResult {
        s.decide(decision.rawValue, confirmName: confirmName).toResult()
    }
    func requestEventlog() -> StepResult { s.requestEventlog().toResult() }
    func eventlog() -> Data? { s.eventlog() }
    func abort(_ reason: String) -> StepResult { s.abort(reason).toResult() }
    func finished() -> Bool { s.finished() }
    func succeeded() -> Bool { s.succeeded() }
    func recordJSON() -> String { s.recordJSON() }
}

extension Optional where Wrapped == KiracoreStep {
    /// gomobile returns nil only when the Go side returned a nil *Step, which
    /// the core never does; treated as an empty step rather than a crash.
    func toResult() -> StepResult {
        guard let st = self else { return .empty }
        var frags: [Data] = []
        for i in 0..<st.fragmentCount() { frags.append(st.fragment(i) ?? Data()) }
        var events: [Event] = []
        for i in 0..<st.eventCount() {
            let text = st.event(i)
            if let e = try? Event.parse(text) {
                events.append(e)
            } else {
                events.append(.unknown(type: "unparseable"))
            }
        }
        return StepResult(fragments: frags, events: events, err: st.err())
    }
}
