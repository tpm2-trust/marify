import XCTest
@testable import Marify

/// The app's session code and the real Go core against a tpm2-kira machine
/// on a software TPM (swtpm): real EK and AK, real TPM2_Quote, real
/// ActivateCredential, the attestation blob written to NV with PolicySigned.
///
/// Isolated from the regular suite: it runs only when the environment
/// variable `MARIFY_SWTPM` names the control port of the machine, which
/// scripts/test-swtpm.sh starts.
final class SwtpmMachineSessionTests: XCTestCase {
    private var machine: SwtpmMachine!
    private var keys: SoftwareAnchorKeys!
    private var records: MemoryRecords!
    private var noise = Data()
    private var bindShown: Event.NeedAnchorKey?

    override func setUpWithError() throws {
        guard let port = ProcessInfo.processInfo.environment["MARIFY_SWTPM"].flatMap(Int.init) else {
            throw XCTSkip("swtpm machine not running (scripts/test-swtpm.sh)")
        }
        machine = try SwtpmMachine(host: "127.0.0.1", port: port)
        keys = SoftwareAnchorKeys()
        records = MemoryRecords()
        noise = try KiraCore.generateNoiseKey()
        // Every test starts from a fresh boot: reset counter advanced, PCRs clear.
        try machine.reboot()
    }

    override func tearDown() {
        machine?.close()
    }

    private func session(_ mode: SessionMode, mtu: Int = 517, startServing: Bool = true,
                         react: @escaping @Sendable (SessionRunner, SessionState) async -> Void = { _, _ in }) async throws -> SessionState {
        let machine = self.machine!
        let records = self.records!
        let noise = self.noise
        let runner = SessionRunner(
            mode: mode,
            connect: {
                if startServing {
                    if mode.isEnrol { try machine.startEnrolment(mtu: mtu) } else { try machine.startAttestation(mtu: mtu) }
                }
                return try machine.link(mtu: mtu)
            },
            newSession: {
                mode.isEnrol
                    ? try KiraCore.newEnrolSession(configJSON: testConfig, noisePriv: noise)
                    : try KiraCore.newAttestSession(configJSON: testConfig, noisePriv: noise, recordJSON: records.record)
            },
            keys: keys,
            records: records,
            coreProtocol: KiraCore.protocolVersion,
            coreSchema: KiraCore.schemaVersion
        )
        return try await runSession(runner, timeoutSeconds: 120) { r, st in
            // Compares the code with the machine's screen and continues a
            // matching boot; other verdicts are the test's.
            if st.phase == .deciding, !st.decisionSent, st.verdict?.verdict.state == Verdict.match {
                r.decide(.continue)
            }
            await react(r, st)
        }
    }

    private func attestMode() throws -> SessionMode {
        let s = try KiraCore.recordSummary(records.record)
        return .attest(deviceId: s.deviceId, machineName: s.friendlyName, lastAttested: s.lastAttested, returnsSalt: s.factorKeptAt != nil)
    }

    private func enrol(mtu: Int = 517) async throws {
        let shown = Box<String>()
        let bind = Box<Event.NeedAnchorKey>()
        let s = try await session(.enrol, mtu: mtu) { r, st in
            if st.phase == .confirmBind, bind.value == nil {
                bind.value = st.bind
                r.confirmBind(true)
            }
            if st.phase == .confirmSas, shown.value == nil {
                shown.value = st.sas
                r.confirmSas(true)
            }
        }
        bindShown = bind.value
        XCTAssertEqual(s.outcome, .succeeded, "\(String(describing: s.outcome))")
        XCTAssertEqual(try machine.outcome(), "enrolled")
        XCTAssertEqual(try machine.lastSas(), shown.value)
        XCTAssertEqual(try KiraCore.recordSummary(records.record).friendlyName, "swtpm-box")
        // swtpm's EK has no vendor certificate: shown as not verified.
        XCTAssertEqual(bindShown?.ekVerifiedBy, "")
        XCTAssertFalse(bindShown?.ekNote.isEmpty ?? true)
        // The machine judged the phone's key: the test's software key has no
        // attestation, which the machine reports (and the test machine accepts).
        let verdict = try machine.phoneVerdict()
        XCTAssertTrue(verdict.contains("the phone sent no key attestation"), verdict)
    }

    func testEnrolThenMatch() async throws { try await enrolThenMatch(mtu: 517) }
    func testEnrolThenMatchAtMinimumMtu() async throws { try await enrolThenMatch(mtu: 23) }

    private func enrolThenMatch(mtu: Int) async throws {
        try await enrol(mtu: mtu)
        try machine.reboot()
        let s = try await session(try attestMode(), mtu: mtu)
        XCTAssertEqual(s.verdict?.verdict.state, Verdict.match, "\(String(describing: s.verdict?.verdict.reasons))")
        // A real TPM key under the slot's policy answered the phone's challenge.
        XCTAssertEqual(s.verdict?.verdict.bootKey, Verdict.bootKeyProved)
        XCTAssertEqual(s.verdict?.verdict.code.count, 9)
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(s.receiptAck?.result, ReceiptResult.accepted)
        XCTAssertEqual(try machine.outcome(), "receipt: verdict=ok authentic=true")
        XCTAssertEqual(keys.requests.map(\.purpose), [Event.purposeEnrolAccept, Event.purposeReceipt])
    }

    func testChangedWithEventlogApproveAndRememberThenMatch() async throws {
        try await enrol()
        try machine.extendPcr(4)
        let asked = Flag()
        let decided = Flag()
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding, !asked.value {
                asked.value = true
                r.requestEventlog()
            }
            if st.eventlog?.complete == true, !decided.value {
                decided.value = true
                r.decide(.approveRemember)
            }
        }
        let v = try XCTUnwrap(s.verdict?.verdict)
        XCTAssertEqual(v.state, Verdict.changed)
        XCTAssertEqual(v.pcrDiff.map(\.index), [4])
        XCTAssertTrue(s.verdict?.eventlogAvailable == true)
        XCTAssertEqual(s.eventlog?.total, 48_000)
        XCTAssertEqual(try machine.outcome(), "receipt: verdict=approved authentic=true")
        XCTAssertEqual(try KiraCore.recordSummary(records.record).profiles.count, 2)

        // The same change after the next boot matches the remembered profile.
        try machine.reboot()
        try machine.extendPcr(4)
        let again = try await session(try attestMode())
        XCTAssertEqual(again.verdict?.verdict.state, Verdict.match)
        XCTAssertTrue(again.verdict?.verdict.profile.hasPrefix("approved") == true)
        XCTAssertEqual(try machine.outcome(), "receipt: verdict=ok authentic=true")
    }

    func testReplayedTpmStateFailsAndIsRejected() async throws {
        try await enrol()
        try machine.snapshot()          // TPM state saved here
        try machine.reboot()
        let first = try await session(try attestMode())
        XCTAssertEqual(first.verdict?.verdict.state, Verdict.match)
        XCTAssertEqual(try machine.outcome(), "receipt: verdict=ok authentic=true")

        try machine.rollback()          // the older TPM state comes back
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding, !st.decisionSent { r.decide(.reject) }
        }
        let v = try XCTUnwrap(s.verdict?.verdict)
        XCTAssertEqual(v.state, Verdict.failed)
        XCTAssertTrue(v.reasons.contains { $0.code == "reset_count_decreased" && $0.hard }, "\(v.reasons)")
        XCTAssertEqual(s.receiptAck?.result, ReceiptResult.rejectNoted)
        XCTAssertTrue(try machine.outcome().hasPrefix("receipt: verdict=reject"))
        // The reject went out unsigned: no prompt beyond enrolment and the match.
        XCTAssertEqual(keys.requests.count, 2)
    }

    func testUserAbortsMidSession() async throws {
        try await enrol()
        try machine.extendPcr(7)
        let s = try await session(try attestMode()) { r, st in
            if st.phase == .deciding { r.cancel() }
        }
        XCTAssertEqual(s.outcome?.failureKind, .cancelled)
        // The machine was told: the Error message from Abort reached it.
        XCTAssertEqual(try machine.outcome(), "error: peer aborted (code 10): cancelled")
    }

    func testRetriesEveryFiveSecondsUntilTheMachineWaits() async throws {
        try await enrol()
        try machine.reboot()
        let machine = self.machine!
        // The machine only starts waiting for a phone 7 s after the app began.
        let late = Task.detached {
            try await Task.sleep(nanoseconds: 7_000_000_000)
            try machine.startAttestation(mtu: 517)
        }
        let most = Counter()
        let started = Date()
        let s = try await session(try attestMode(), startServing: false) { _, st in
            if let u = st.unreachable { while most.value < u.attempt { _ = most.next() } }
        }
        _ = try await late.value
        let seconds = Date().timeIntervalSince(started)
        XCTAssertEqual(s.verdict?.verdict.state, Verdict.match)
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(try machine.outcome(), "receipt: verdict=ok authentic=true")
        // Attempts at 0 s and 5 s fail; the one at 10 s reaches the machine.
        XCTAssertEqual(most.value, 2)
        XCTAssertTrue((9.5...20).contains(seconds), "took \(seconds)s")
    }

    func testStrangerPhoneGetsNoAnswer() async throws {
        try await enrol()
        try machine.reboot()
        // Another phone that copied the record but not the enrolled Noise key.
        noise = try KiraCore.generateNoiseKey()
        let s = try await session(try attestMode())
        // The machine refuses the IK handshake; after one silent retry the
        // app says so instead of "connection lost" (TODO-SEC.md S1).
        XCTAssertEqual(s.outcome?.failureKind, .machineRefused, "\(String(describing: s.outcome))")
        XCTAssertNil(s.verdict)
        XCTAssertEqual(try machine.outcome(), "error: attest: initiator is not an enrolled verifier")
    }
}
