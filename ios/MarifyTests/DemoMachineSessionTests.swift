import XCTest
@testable import Marify

/// The real core (kiracore) against the real machine-side protocol over a
/// software TPM (kiratest), with the BLE link replaced by `DemoLink`: every
/// verdict state end to end, as PROTOCOL-BLE.md §10.6 intends.
final class DemoMachineSessionTests: XCTestCase {
    private var demo: DemoMachineSupport!
    private var keys: SoftwareAnchorKeys!
    private var records: MemoryRecords!
    /// (code on the phone, code on the machine's screen) of every matching boot.
    private var codesSeen: [(String, String)] = []
    private var bindShown: Event.NeedAnchorKey?

    override func setUpWithError() throws {
        demo = DemoMachineSupport()
        keys = SoftwareAnchorKeys()
        records = MemoryRecords()
        codesSeen = []
    }

    private func makeRunner(_ mode: SessionMode, mtu: Int) -> SessionRunner {
        let demo = self.demo!
        let records = self.records!
        return SessionRunner(
            mode: mode,
            connect: { mode.isEnrol ? try demo.enrolLink(mtu: mtu) : try demo.attestLink(mtu: mtu) },
            newSession: {
                mode.isEnrol
                    ? try KiraCore.newEnrolSession()
                    : try KiraCore.newAttestSession(recordJSON: records.record)
            },
            keys: keys,
            records: records,
            coreProtocol: KiraCore.protocolVersion,
            coreSchema: KiraCore.schemaVersion
        )
    }

    /// Runs one session; `react` plays the user for each state change. The
    /// person always compares the code with the machine's screen and
    /// continues a matching boot; other verdicts are the test's.
    private func session(_ mode: SessionMode, mtu: Int = 517,
                         react: @escaping @Sendable (SessionRunner, SessionState) async -> Void = { _, _ in }) async throws -> SessionState {
        let runner = makeRunner(mode, mtu: mtu)
        let demo = self.demo!
        let seen = CodesSeen()
        let s = try await runSession(runner) { r, st in
            if st.phase == .deciding && !st.decisionSent, let v = st.verdict?.verdict, v.state == Verdict.match {
                seen.add(v.code, demo.screenCode())
                r.decide(.continue)
            }
            await react(r, st)
        }
        codesSeen += seen.list
        return s
    }

    private func enrol(mtu: Int = 517) async throws {
        let sas = Box<String>()
        let bind = Box<Event.NeedAnchorKey>()
        let s = try await session(.enrol, mtu: mtu) { r, st in
            if st.phase == .confirmBind, bind.value == nil {
                bind.value = st.bind
                r.confirmBind(true)
            }
            if st.phase == .confirmSas, sas.value == nil {
                sas.value = st.sas
                r.confirmSas(true)
            }
        }
        bindShown = bind.value
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(demo.outcome(timeoutMs: 5000), "enrolled")
        XCTAssertEqual(demo.lastSas(), sas.value)
        XCTAssertEqual(s.machineName, "Thinkpad-X1")
        XCTAssertEqual(try KiraCore.recordSummary(records.record).friendlyName, "Thinkpad-X1")
        XCTAssertEqual(keys.requests.map(\.purpose), [Event.purposeEnrolAccept])
        // The demo machine's software TPM has no EK certificate.
        XCTAssertEqual(bindShown?.ekVerifiedBy, "")
        XCTAssertEqual(bindShown?.ekNote, "the TPM has no vendor certificate for its endorsement key")
        XCTAssertEqual(try KiraCore.recordSummary(records.record).ekVerifiedBy, "")
    }

    private func attestMode() throws -> SessionMode {
        let s = try KiraCore.recordSummary(records.record)
        return .attest(deviceId: s.deviceId, machineName: s.friendlyName, lastAttested: s.lastAttested, returnsSalt: s.factorKeptAt != nil)
    }

    func testEnrolThenMatch() async throws { try await enrolThenMatch(mtu: 517) }
    func testEnrolThenMatchAtMinimumMtu() async throws { try await enrolThenMatch(mtu: 23) }

    private func enrolThenMatch(mtu: Int) async throws {
        try await enrol(mtu: mtu)
        demo.nextBoot()
        let s = try await session(try attestMode(), mtu: mtu)
        let v = try XCTUnwrap(s.verdict?.verdict)
        XCTAssertEqual(v.state, Verdict.match)
        // The machine's TPM released its boot key: the phone shows the code
        // the machine shows, and nothing was signed before the decision.
        XCTAssertEqual(v.bootKey, Verdict.bootKeyProved)
        XCTAssertEqual(v.code.count, 9)
        XCTAssertEqual(codesSeen.map(\.0), [v.code])
        XCTAssertEqual(codesSeen.map(\.1), [v.code])
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(s.receiptAck?.result, ReceiptResult.accepted)
        XCTAssertEqual(demo.outcome(timeoutMs: 5000), "receipt: verdict=ok authentic=true")
        XCTAssertNotNil(try KiraCore.recordSummary(records.record).lastAttested)
    }

    /// A TPM that refuses the key: no code, and the verdict says so; the boot still matches.
    func testMatchWithoutBootKey() async throws {
        try await enrol()
        demo.nextBoot()
        demo.refuseBootKey(true)
        let s = try await session(try attestMode())
        XCTAssertEqual(s.verdict?.verdict.state, Verdict.match)
        XCTAssertEqual(s.verdict?.verdict.bootKey, Verdict.bootKeyRefused)
        XCTAssertEqual(s.verdict?.verdict.code, "")
        XCTAssertEqual(demo.screenCode(), "")
        XCTAssertEqual(s.outcome, .succeeded)
        demo.refuseBootKey(false)
    }

    func testChangedWithEventlogApproveAndRememberThenMatch() async throws {
        try await enrol()
        demo.changePcr(4)
        let asked = Flag()
        let decided = Flag()
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding, !asked.value {
                asked.value = true
                XCTAssertTrue(st.verdict?.eventlogAvailable == true)
                r.requestEventlog()
            }
            if st.eventlog?.complete == true, !decided.value {
                decided.value = true
                r.decide(.approveRemember)
            }
        }
        XCTAssertEqual(s.verdict?.verdict.state, Verdict.changed)
        XCTAssertTrue(s.verdict?.verdict.pcrDiff.contains { $0.index == 4 } == true)
        XCTAssertTrue(s.eventlog?.complete == true)
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(demo.outcome(timeoutMs: 5000), "receipt: verdict=approved authentic=true")
        XCTAssertEqual(try KiraCore.recordSummary(records.record).profiles.count, 2)

        // The remembered profile now matches.
        demo.nextBoot()
        let again = try await session(try attestMode())
        XCTAssertEqual(again.verdict?.verdict.state, Verdict.match)
        XCTAssertEqual(demo.outcome(timeoutMs: 5000), "receipt: verdict=ok authentic=true")
    }

    func testFailedThenReject() async throws {
        try await enrol()
        demo.nextBoot()
        demo.nextBoot()
        demo.rollbackResetCount()
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding, !st.decisionSent { r.decide(.reject) }
        }
        let v = try XCTUnwrap(s.verdict?.verdict)
        XCTAssertEqual(v.state, Verdict.failed)
        XCTAssertTrue(v.reasons.contains { $0.code == "reset_count_decreased" && $0.hard })
        XCTAssertEqual(s.receiptAck?.result, ReceiptResult.rejectNoted)
        XCTAssertTrue(demo.outcome(timeoutMs: 5000).hasPrefix("receipt: verdict=reject"))
        // A rejected boot is not signed: no unlock prompt beyond enrolment.
        XCTAssertEqual(keys.requests.count, 1)
    }

    func testFailedApproveNeedsTheTypedName() async throws {
        try await enrol()
        demo.nextBoot()
        demo.rollbackResetCount()
        let step = Counter()
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding, step.value == 0 {
                _ = step.next()
                r.decide(.approveOnce, confirmName: "not the name")
            } else if st.phase == .deciding, step.value == 1, st.notice != nil {
                _ = step.next()
                r.decide(.approveOnce, confirmName: "Thinkpad-X1")
            }
        }
        XCTAssertEqual(step.value, 2)
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(demo.outcome(timeoutMs: 5000), "receipt: verdict=approved authentic=true")
    }

    func testUserAbortsMidSession() async throws {
        try await enrol()
        demo.changePcr(7)
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding { r.cancel() }
        }
        XCTAssertEqual(s.outcome?.failureKind, .cancelled)
        XCTAssertTrue(demo.outcome(timeoutMs: 5000).hasPrefix("error:"))
    }

    func testUnlockCancelAbortsAndSendsNothingSigned() async throws {
        try await enrol()
        demo.nextBoot()
        keys.cancelNext = true
        let s = try await session(try attestMode())
        XCTAssertEqual(s.outcome?.failureKind, .authCancelled)
        XCTAssertTrue(demo.outcome(timeoutMs: 5000).hasPrefix("error:"))
    }

    func testSasMismatchStoresNothing() async throws {
        let s = try await session(.enrol) { r, st in
            if st.phase == .confirmSas { r.confirmSas(false) }
        }
        XCTAssertEqual(s.outcome?.failureKind, .sasMismatch)
        XCTAssertEqual(records.record, "")
        XCTAssertTrue(demo.outcome(timeoutMs: 5000).hasPrefix("error:"))
    }

    func testAdvertisementMatchesOnlyItsOwnRecord() async throws {
        try await enrol()
        XCTAssertTrue(KiraCore.matchAdvertisement(demo.serviceData(), recordJSON: records.record))
        let other = DemoMachineSupport()
        XCTAssertFalse(KiraCore.matchAdvertisement(other.serviceData(), recordJSON: records.record))
        XCTAssertEqual(KiraCore.advertisementFlags(demo.serviceData()), KiraCore.advFlagAttest)
        XCTAssertTrue(demo.owns(recordJSON: records.record))
        XCTAssertFalse(other.owns(recordJSON: records.record))
    }
}

final class Box<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T?
    var value: T? {
        get { lock.lock(); defer { lock.unlock() }; return v }
        set { lock.lock(); v = newValue; lock.unlock() }
    }
}

final class CodesSeen: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var list: [(String, String)] = []
    func add(_ phone: String, _ screen: String) { lock.lock(); list.append((phone, screen)); lock.unlock() }
}
