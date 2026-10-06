import XCTest
@testable import Marify

/// The app's side of the PROTOCOL-BLE.md §10 contract, with a scripted core
/// and an in-memory link: fragments are written in order even when a step
/// fails, inputs are handled in arrival order, refusals are told apart from
/// radio failures, and connecting retries until the user cancels.
final class SessionRunnerTests: XCTestCase {

    private func step(_ frags: [UInt8]..., events: [Event] = [], err: String = "") -> StepResult {
        StepResult(fragments: frags.map { Data($0) }, events: events, err: err)
    }

    /// A core whose answers are scripted; records every call.
    final class FakeCore: CoreSession, @unchecked Sendable {
        var calls: [String] = []
        var isFinished = false
        var isSucceeded = false
        var onStart: () -> StepResult = { .empty }
        var onNotify: (Data) -> StepResult = { _ in .empty }
        var onSignature: (Data) -> StepResult = { _ in .empty }
        var onDecide: (Decision, String) -> StepResult = { _, _ in .empty }
        var onAbort: ((String) -> StepResult)?
        var attestationSent: Data?

        func setMaxFragment(_ n: Int) { calls.append("setMaxFragment(\(n))") }
        func start() -> StepResult { calls.append("start"); return onStart() }
        func onNotification(_ value: Data) -> StepResult { calls.append("notify(\([UInt8](value)))"); return onNotify(value) }
        func confirmSas(_ match: Bool) -> StepResult { calls.append("confirmSas(\(match))"); return .empty }
        func provideAnchorKey(spkiDer: Data) -> StepResult { calls.append("anchorKey"); return .empty }
        func provideAnchorKeyAttested(spkiDer: Data, attestation: Data) -> StepResult {
            calls.append("anchorKey"); attestationSent = attestation; return .empty
        }
        func pendingTbs() -> Data { Data([1, 2, 3]) }
        func provideSignature(der: Data) -> StepResult { calls.append("signature"); return onSignature(der) }
        func decide(_ decision: Decision, confirmName: String) -> StepResult { calls.append("decide(\(decision))"); return onDecide(decision, confirmName) }
        func requestEventlog() -> StepResult { calls.append("requestEventlog"); return .empty }
        func eventlog() -> Data? { nil }
        func abort(_ reason: String) -> StepResult {
            calls.append("abort(\(reason))")
            if let onAbort { return onAbort(reason) }
            isFinished = true
            return StepResult(fragments: [Data([0x7f])], events: [.error(code: 10, message: reason)], err: reason)
        }
        func finished() -> Bool { isFinished }
        func succeeded() -> Bool { isSucceeded }
        func recordJSON() -> String { "" }
    }

    final class FakeLink: Link, @unchecked Sendable {
        let maxFragment = 182
        let info: InfoValue
        private let pipe = LinkEventPipe()
        private let lock = NSLock()
        private var _written: [[UInt8]] = []
        private var _disconnected = false

        init(mode: Int = InfoValue.modeAttest) { info = InfoValue(protocolVersion: 1, mode: mode, schema: 1, capabilities: 0) }

        var events: AsyncStream<LinkEvent> { pipe.stream }
        var written: [[UInt8]] { lock.lock(); defer { lock.unlock() }; return _written }
        var disconnected: Bool { lock.lock(); defer { lock.unlock() }; return _disconnected }

        func write(_ fragment: Data) async throws {
            lock.lock(); defer { lock.unlock() }
            precondition(!_disconnected, "write after disconnect")
            _written.append([UInt8](fragment))
        }

        func disconnect() async {
            lock.lock(); _disconnected = true; lock.unlock()
            pipe.continuation.finish()
        }

        func notify(_ b: UInt8...) { pipe.continuation.yield(.notification(Data(b))) }
        func lose(_ reason: String) { pipe.continuation.yield(.lost(reason)) }
    }

    final class FakeKeys: AnchorKeyProvider, @unchecked Sendable {
        var requests: [SignRequest] = []
        var biometricOnly: Bool?
        var challenge: Data?
        var signResult: () throws -> Data = { Data([0x30]) }

        func create(deviceId: String, biometricOnly: Bool, attestationChallenge: Data?) async throws -> CreatedAnchorKey {
            self.biometricOnly = biometricOnly
            challenge = attestationChallenge
            return CreatedAnchorKey(spkiDer: Data(count: 91), storage: .secureEnclave, attestation: Data([0x30, 0x00]))
        }
        func sign(deviceId: String, tbs: Data, request: SignRequest) async throws -> Data {
            requests.append(request)
            return try signResult()
        }
        func delete(deviceId: String) {}
        func details(deviceId: String) -> AnchorKeyDetails? { nil }
    }

    private let deviceId = String(repeating: "a0", count: 16)

    private func runner(
        _ core: FakeCore, _ link: FakeLink, keys: FakeKeys = FakeKeys(), records: MemoryRecords = MemoryRecords(),
        mode: SessionMode? = nil,
        connect: (@Sendable () async throws -> any Link)? = nil
    ) -> SessionRunner {
        SessionRunner(
            mode: mode ?? .attest(deviceId: deviceId, machineName: "Thinkpad-X1", lastAttested: nil),
            connect: connect ?? { link },
            newSession: { core },
            keys: keys,
            records: records,
            coreProtocol: 1,
            coreSchema: 1,
            retryIntervalMs: 50
        )
    }

    private func settle() async { try? await Task.sleep(nanoseconds: 150_000_000) }

    func testFailingStepFragmentsAreWrittenBeforeDisconnect() async throws {
        let core = FakeCore()
        let link = FakeLink()
        core.onStart = { [unowned core] in
            core.isFinished = true
            return self.step([1], [2], [3], events: [.error(code: 1, message: "boom")], err: "boom")
        }
        let r = runner(core, link)
        await r.run()
        XCTAssertEqual(link.written, [[1], [2], [3]])
        XCTAssertTrue(link.disconnected)
        let s = await r.state
        XCTAssertEqual(s.outcome, .failed(kind: .protocolFailure, code: 1, message: "boom"))
        XCTAssertEqual(core.calls.first, "setMaxFragment(182)")
    }

    func testRecoverableErrorStillWritesFragmentsAndContinues() async throws {
        let core = FakeCore()
        let link = FakeLink()
        core.onStart = { self.step([9]) }
        core.onNotify = { _ in self.step([8], err: "transient") }
        let r = runner(core, link)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        link.notify(5)
        let s = try await withTimeout(seconds: 5) { try await r.waitFor { $0.notice == "transient" } }
        XCTAssertEqual(link.written, [[9], [8]])
        XCTAssertFalse(s.finished)
        r.cancel()
        await job.value
        let final = await r.state
        XCTAssertEqual(final.outcome?.failureKind, .cancelled)
    }

    func testCancelWritesAbortFragmentsThenDisconnects() async throws {
        let core = FakeCore()
        let link = FakeLink()
        let r = runner(core, link)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        r.cancel()
        await job.value
        XCTAssertEqual(link.written, [[0x7f]])
        XCTAssertTrue(link.disconnected)
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .cancelled)
    }

    func testNotificationsAreFedInOrder() async throws {
        let core = FakeCore()
        let link = FakeLink()
        let r = runner(core, link)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        for i in 1...50 { link.notify(UInt8(i)) }
        await settle()
        let fed = core.calls.filter { $0.hasPrefix("notify") }
        XCTAssertEqual(fed, (1...50).map { "notify([\($0)])" })
        r.cancel()
        await job.value
    }

    func testMatchContinuesSignsAndFinishes() async throws {
        let core = FakeCore()
        let link = FakeLink()
        let keys = FakeKeys()
        let records = MemoryRecords()
        let verdict = Event.VerdictEvent(verdict: { var v = Verdict(); v.ok = true; v.state = Verdict.match; return v }(), needsDecision: true, eventlogAvailable: false)
        core.onNotify = { [unowned core] v in
            switch v.first {
            case 1: return self.step(events: [.hello(deviceId: self.deviceId, friendlyName: "Thinkpad-X1")])
            case 2: return self.step(events: [.verdict(verdict)])
            default:
                core.isFinished = true; core.isSucceeded = true
                return self.step(events: [.receiptAck(result: 1, message: ""), .recordUpdated(recordJSON: #"{"r":2}"#), .done])
            }
        }
        core.onDecide = { d, _ in
            XCTAssertEqual(d, .continue)
            return self.step(events: [.needSignature(purpose: Event.purposeReceipt, verdictCode: 1)])
        }
        core.onSignature = { der in
            XCTAssertEqual(der, Data([0x30]))
            return self.step([0x55])
        }
        let r = runner(core, link, keys: keys, records: records)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        link.notify(1)
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .verifying } }
        link.notify(2)
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .deciding } }
        r.decide(.continue)
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .delivering } }
        link.notify(3)
        await job.value
        let s = await r.state
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(s.receiptAck?.result, 1)
        XCTAssertEqual(keys.requests.map(\.purpose), [Event.purposeReceipt])
        XCTAssertEqual(keys.requests.first?.machineName, "Thinkpad-X1")
        XCTAssertEqual(records.replaced, [#"{"r":2}"#])
        XCTAssertEqual(link.written, [[0x55]])
    }

    func testUnlockCancelAbortsTheSession() async throws {
        let core = FakeCore()
        let link = FakeLink()
        let keys = FakeKeys()
        keys.signResult = { throw AnchorKeyError.signingCancelled("cancelled") }
        core.onNotify = { _ in self.step(events: [.hello(deviceId: self.deviceId, friendlyName: "x"), .needSignature(purpose: Event.purposeReceipt, verdictCode: 1)]) }
        let r = runner(core, link, keys: keys)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        link.notify(1)
        await job.value
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .authCancelled)
        XCTAssertEqual(link.written, [[0x7f]])
    }

    func testDecliningToBindAbortsWithoutAKey() async throws {
        let core = FakeCore()
        let link = FakeLink(mode: InfoValue.modeEnrol)
        let keys = FakeKeys()
        core.onNotify = { _ in self.step(events: [.needAnchorKey(Event.NeedAnchorKey(deviceId: self.deviceId, friendlyName: "Thinkpad-X1"))]) }
        let r = runner(core, link, keys: keys, mode: .enrol)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        link.notify(1)
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .confirmBind } }
        r.confirmBind(false)
        await job.value
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .cancelled)
        XCTAssertNil(keys.biometricOnly)
        XCTAssertFalse(core.calls.contains("anchorKey"))
    }

    func testEnrolmentStoresTheRecordWithKeyStorage() async throws {
        let core = FakeCore()
        let link = FakeLink(mode: InfoValue.modeEnrol)
        let records = MemoryRecords()
        let keys = FakeKeys()
        core.onNotify = { [unowned core] v in
            switch v.first {
            case 1: return self.step(events: [.sas("123456")])
            case 2: return self.step(events: [.needAnchorKey(Event.NeedAnchorKey(deviceId: self.deviceId, friendlyName: "Thinkpad-X1", attestationChallenge: Data([7, 7])))])
            default:
                core.isFinished = true; core.isSucceeded = true
                return self.step([0x7e], events: [.enrolled(recordJSON: #"{"r":1}"#), .done])
            }
        }
        let r = runner(core, link, keys: keys, records: records, mode: .enrol)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        link.notify(1)
        let sas = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .confirmSas } }
        XCTAssertEqual(sas.sas, "123456")
        r.confirmSas(true)
        await settle()
        link.notify(2)
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .confirmBind } }
        r.confirmBind(true, biometricOnly: true)
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .enrolling && $0.bind != nil && keys.biometricOnly != nil } }
        link.notify(3)
        await job.value
        let s = await r.state
        XCTAssertEqual(s.outcome, .succeeded)
        XCTAssertEqual(records.saved, [#"{"r":1}"#])
        XCTAssertEqual(keys.biometricOnly, true)
        XCTAssertEqual(keys.challenge, Data([7, 7]))
        XCTAssertEqual(core.attestationSent, Data([0x30, 0x00]))
        XCTAssertEqual(s.machineName, "Thinkpad-X1")
    }

    // MARK: refusals (TODO-SEC.md S1)

    final class Connections: @unchecked Sendable {
        var links: [FakeLink] = []
        var cores: [FakeCore] = []
        let behave: (Int, FakeCore, FakeLink) -> Void
        init(_ behave: @escaping (Int, FakeCore, FakeLink) -> Void) { self.behave = behave }
        func connect() -> any Link { let l = FakeLink(); links.append(l); return l }
        func core() -> CoreSession {
            let c = FakeCore()
            cores.append(c)
            behave(cores.count, c, links.last!)
            return c
        }
    }

    private func refusalRunner(_ c: Connections) -> SessionRunner {
        SessionRunner(
            mode: .attest(deviceId: deviceId, machineName: "Thinkpad-X1", lastAttested: nil),
            connect: { c.connect() },
            newSession: { c.core() },
            keys: FakeKeys(), records: MemoryRecords(),
            coreProtocol: 1, coreSchema: 1, retryIntervalMs: 50
        )
    }

    func testConnectionDroppedBeforeHelloTwiceIsARefusal() async throws {
        let c = Connections { _, _, link in link.lose("machine closed the connection") }
        let r = refusalRunner(c)
        await r.run()
        XCTAssertEqual(c.links.count, 2)   // one silent retry
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .machineRefused)
        XCTAssertTrue(c.links.allSatisfy(\.disconnected))
    }

    func testHandshakeRejectedByTheCoreTwiceIsARefusal() async throws {
        let c = Connections { _, core, link in
            core.onNotify = { [unowned core] _ in
                core.isFinished = true
                return StepResult(fragments: [], events: [.error(code: 1, message: "handshake failed: chacha20poly1305: message authentication failed")], err: "x")
            }
            link.notify(1)
        }
        let r = refusalRunner(c)
        await r.run()
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .machineRefused)
        XCTAssertEqual(c.cores.count, 2)
    }

    func testOneDroppedHandshakeIsRetriedSilently() async throws {
        let c = Connections { index, core, link in
            if index == 1 {
                link.lose("radio")
            } else {
                core.onNotify = { _ in StepResult(fragments: [], events: [.hello(deviceId: "a0", friendlyName: "Thinkpad-X1")], err: "") }
                link.notify(1)
            }
        }
        let r = refusalRunner(c)
        let job = Task { await r.run() }
        let s = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .verifying } }
        XCTAssertNil(s.outcome)
        XCTAssertEqual(c.links.count, 2)
        job.cancel()
        await job.value
    }

    func testLocalCoreErrorAtStartIsNotARefusal() async throws {
        let c = Connections { _, core, _ in
            core.onStart = { [unowned core] in
                core.isFinished = true
                return StepResult(fragments: [], events: [.error(code: 1, message: "boom")], err: "boom")
            }
        }
        let r = refusalRunner(c)
        await r.run()
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .protocolFailure)
        XCTAssertEqual(c.links.count, 1)
    }

    func testEnrolmentIsNeverCalledARefusal() async throws {
        let link = FakeLink(mode: InfoValue.modeEnrol)
        let r = runner(FakeCore(), link, mode: .enrol)
        let job = Task { await r.run() }
        _ = try await r.waitFor { $0.phase == .securing }
        link.lose("gone")
        await job.value
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .linkLost)
    }

    func testWrongModeIsRefusedBeforeStarting() async throws {
        let core = FakeCore()
        let r = runner(core, FakeLink(mode: InfoValue.modeEnrol))
        await r.run()
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .wrongMode)
        XCTAssertTrue(core.calls.isEmpty)
    }

    // MARK: connecting

    func testRetriesUntilTheMachineIsReachable() async throws {
        let link = FakeLink()
        let counter = Counter()
        let r = runner(FakeCore(), link, connect: {
            if counter.next() < 3 { throw LinkError.notReachable("Thinkpad-X1 was not seen nearby") }
            return link
        })
        let job = Task { await r.run() }
        let u = try await withTimeout(seconds: 5) { try await r.waitFor { $0.unreachable?.attempt == 2 } }
        XCTAssertEqual(u.phase, .connecting)
        let s = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .securing } }
        XCTAssertNil(s.unreachable)
        XCTAssertEqual(counter.value, 3)
        r.cancel()
        await job.value
    }

    func testBluetoothOffIsWaitedOut() async throws {
        let link = FakeLink()
        let on = Flag()
        let r = runner(FakeCore(), link, connect: { if !on.value { throw LinkError.bluetoothOff }; return link })
        let job = Task { await r.run() }
        let u = try await withTimeout(seconds: 5) { try await r.waitFor { $0.unreachable != nil } }
        XCTAssertEqual(u.unreachable?.bluetoothOff, true)
        on.value = true
        let s = try await withTimeout(seconds: 5) { try await r.waitFor { $0.phase == .securing } }
        XCTAssertNil(s.unreachable)
        r.cancel()
        await job.value
    }

    func testOtherConnectionErrorsAreNotRetried() async throws {
        let counter = Counter()
        let r = runner(FakeCore(), FakeLink(), connect: { _ = counter.next(); throw LinkError.failed("TX has no CCCD") })
        await r.run()
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .connect)
        XCTAssertEqual(counter.value, 1)
    }

    func testCancellingWhileUnreachableEndsTheSession() async throws {
        let r = runner(FakeCore(), FakeLink(), connect: { throw LinkError.notReachable("not seen") })
        let job = Task { await r.run() }
        _ = try await withTimeout(seconds: 5) { try await r.waitFor { ($0.unreachable?.attempt ?? 0) >= 2 } }
        job.cancel()
        await job.value
        let s = await r.state
        XCTAssertEqual(s.outcome?.failureKind, .cancelled)
        XCTAssertTrue(s.finished)
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return n }
    func next() -> Int { lock.lock(); defer { lock.unlock() }; n += 1; return n }
}

final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var v = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return v }
        set { lock.lock(); v = newValue; lock.unlock() }
    }
}
