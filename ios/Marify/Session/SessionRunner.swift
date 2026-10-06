import Foundation

/// Where enrolment and attestation results are persisted.
protocol RecordSink: Sendable {
    func saveEnrolled(recordJSON: String, keyStorage: KeyStorage) async throws
    func replace(recordJSON: String) async throws
}

/// Drives one core `CoreSession` over one `Link` (PROTOCOL-BLE.md §10).
///
/// Every input — a notification, a button press, an unlock result — goes
/// through one inbox and is handled by this actor in arrival order. The
/// runner never decides trust: it writes the core's fragments, shows the
/// core's events and passes the user's answers back.
actor SessionRunner {
    private enum Input: Sendable {
        case fromLink(LinkEvent, linkId: ObjectIdentifier)
        case confirmSas(Bool)
        case confirmBind(Bool, biometricOnly: Bool)
        case decide(Decision, confirmName: String)
        case requestEventlog
        case abort(reason: String, kind: FailureKind)
        case signed(Data)
        case signFailed(FailureKind, reason: String)
    }

    static let retryIntervalMs: Int64 = 5_000
    private static let tag = "MarifySession"

    nonisolated let model: SessionStateModel
    private let mode: SessionMode
    private let connect: @Sendable () async throws -> any Link
    private let newSession: @Sendable () throws -> CoreSession
    private let keys: AnchorKeyProvider
    private let records: RecordSink
    private let coreProtocol: Int
    private let coreSchema: Int
    private let retryIntervalMs: Int64
    private let clock: @Sendable () -> Int64

    private let inbox: AsyncStream<Input>
    private let inboxWriter: AsyncStream<Input>.Continuation

    private var current: SessionState
    private var waiters: [(predicate: @Sendable (SessionState) -> Bool, gate: Gate<SessionState>)] = []
    private var observers: [AsyncStream<SessionState>.Continuation] = []

    private var link: (any Link)?
    private var core: CoreSession?
    private var ended = false
    private var failureKind: FailureKind?
    private var deviceId: String?
    private var machineName = ""
    private var keyStorage = KeyStorage.software

    // Attestation only: whether the machine identified itself (Hello) in the
    // current connection. A machine that advertises as enrolled but ends the
    // session before that has refused this phone (TODO-SEC.md S1).
    private var helloSeen = false
    // Whether the input being handled came from the machine (a notification).
    private var handlingMachineInput = false
    private var refusals = 0
    private var retryAfterRefusal = false
    private var pumpTask: Task<Void, Never>?
    private var signTask: Task<Void, Never>?

    init(
        mode: SessionMode,
        connect: @escaping @Sendable () async throws -> any Link,
        newSession: @escaping @Sendable () throws -> CoreSession,
        keys: AnchorKeyProvider,
        records: RecordSink,
        coreProtocol: Int,
        coreSchema: Int,
        retryIntervalMs: Int64 = SessionRunner.retryIntervalMs,
        clock: @escaping @Sendable () -> Int64 = { monotonicMs() }
    ) {
        self.mode = mode
        self.connect = connect
        self.newSession = newSession
        self.keys = keys
        self.records = records
        self.coreProtocol = coreProtocol
        self.coreSchema = coreSchema
        self.retryIntervalMs = retryIntervalMs
        self.clock = clock
        let initial = SessionState(mode: mode)
        current = initial
        model = SessionStateModel(initial)
        if case .attest(let id, let name, _) = mode {
            deviceId = id
            machineName = name
        }
        var w: AsyncStream<Input>.Continuation!
        inbox = AsyncStream(bufferingPolicy: .unbounded) { w = $0 }
        inboxWriter = w
    }

    // MARK: user actions (any thread)

    nonisolated func confirmSas(_ match: Bool) { inboxWriter.yield(.confirmSas(match)) }
    nonisolated func confirmBind(_ bind: Bool, biometricOnly: Bool = false) {
        inboxWriter.yield(.confirmBind(bind, biometricOnly: biometricOnly))
    }
    nonisolated func decide(_ decision: Decision, confirmName: String = "") {
        inboxWriter.yield(.decide(decision, confirmName: confirmName))
    }
    nonisolated func requestEventlog() { inboxWriter.yield(.requestEventlog) }
    nonisolated func cancel(reason: String = "cancelled") { inboxWriter.yield(.abort(reason: reason, kind: .cancelled)) }

    /// The state as the actor sees it right now.
    var state: SessionState { current }

    /// Waits until the state satisfies `predicate` (tests, and the manager).
    func waitFor(_ predicate: @escaping @Sendable (SessionState) -> Bool) async throws -> SessionState {
        if predicate(current) { return current }
        let gate = Gate<SessionState>()
        waiters.append((predicate, gate))
        return try await gate.wait()
    }

    /// Every state from now on, starting with the current one (tests, and
    /// anything that plays the person).
    func states() -> AsyncStream<SessionState> {
        let s = current
        return AsyncStream(bufferingPolicy: .unbounded) { c in
            c.yield(s)
            observers.append(c)
        }
    }

    private func update(_ change: (inout SessionState) -> Void) async {
        change(&current)
        let s = current
        for o in observers { o.yield(s) }
        if s.finished { for o in observers { o.finish() }; observers.removeAll() }
        let fired = waiters.filter { $0.predicate(s) }
        waiters.removeAll { w in fired.contains { $0.gate === w.gate } }
        for w in fired { w.gate.complete(s) }
        await MainActor.run { model.set(s) }
    }

    // MARK: the loop

    /// Runs the session to its end. Cancelling the task aborts the session.
    func run() async {
        debugLog(Self.tag, "session starts: \(mode.isEnrol ? "enrol" : "attest")")
        repeat {
            retryAfterRefusal = false
            let proceed = await connectAndRun()
            if !proceed { return }
        } while retryAfterRefusal
    }

    /// One connection: connect, check INFO, run the protocol until the
    /// session ends. Returns false when connecting itself ended the session.
    private func connectAndRun() async -> Bool {
        guard let l = await connectWithRetry() else { return false }
        link = l
        await update { $0.unreachable = nil }
        defer { Task { await l.disconnect() } }

        let expected = mode.isEnrol ? InfoValue.modeEnrol : InfoValue.modeAttest
        switch l.info.check(expectedMode: expected, coreProtocol: coreProtocol, coreSchema: coreSchema) {
        case .ok: break
        case .updateNeeded(let appTooOld):
            let who = appTooOld ? "this app" : "tpm2-kira on the machine"
            await end(.failed(kind: .updateNeeded, code: 0, message: "please update \(who)"))
            return true
        case .wrongMode(let actual):
            await end(.failed(kind: .wrongMode, code: actual, message: "the machine is in the other mode"))
            return true
        }
        let c: CoreSession
        do {
            c = try newSession()
        } catch {
            await end(.failed(kind: .protocolFailure, code: 0, message: "cannot start the session: \(error)"))
            return true
        }
        core = c
        c.setMaxFragment(l.maxFragment)
        let linkId = ObjectIdentifier(l as AnyObject)
        let writer = inboxWriter
        pumpTask = Task.detached {
            for await ev in l.events { writer.yield(.fromLink(ev, linkId: linkId)) }
            writer.yield(.fromLink(.lost("connection closed"), linkId: linkId))
        }
        await update { $0.phase = .securing }
        await process(c.start())
        var iterator = inbox.makeAsyncIterator()
        while !ended {
            if Task.isCancelled {
                await cancelledByCaller()
                break
            }
            guard let input = await iterator.next() else { break }
            await handle(input)
        }
        pumpTask?.cancel()
        pumpTask = nil
        if Task.isCancelled && current.outcome == nil {
            await cancelledByCaller()
        }
        if retryAfterRefusal {
            // Start over on a new connection and a new core session.
            ended = false
            failureKind = nil
            helloSeen = false
            await update { $0.phase = .connecting; $0.notice = nil }
        }
        return true
    }

    private func cancelledByCaller() async {
        if let c = core, !c.finished() {
            failureKind = .cancelled
            await process(c.abort("cancelled"))
        }
        retryAfterRefusal = false
        if !ended || current.outcome == nil {
            ended = false
            await end(.failed(kind: .cancelled, code: 0, message: "cancelled"))
        }
    }

    /// Calls `connect` until a link comes up. While the machine is not
    /// reachable, or Bluetooth is off, it keeps trying every retry interval
    /// (counted from the start of the previous attempt) for as long as the
    /// user waits; cancelling ends it. Any other failure ends the session at
    /// once. Returns nil when the session ended.
    private func connectWithRetry() async -> (any Link)? {
        let start = clock()
        var attempt = 0
        while true {
            if Task.isCancelled {
                await end(.failed(kind: .cancelled, code: 0, message: "cancelled"))
                return nil
            }
            attempt += 1
            let began = clock()
            do {
                return try await connect()
            } catch is CancellationError {
                await end(.failed(kind: .cancelled, code: 0, message: "cancelled"))
                return nil
            } catch let e as LinkError where e.kind == .notReachable || e.kind == .bluetoothOff {
                debugLog(Self.tag, "machine not reachable (attempt \(attempt)): \(e.description)")
                await update {
                    $0.unreachable = Unreachable(attempt: attempt, reason: e.description, waitedMs: clock() - start, bluetoothOff: e.kind == .bluetoothOff)
                }
                let wait = max(0, retryIntervalMs - (clock() - began))
                do {
                    try await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
                } catch {
                    await end(.failed(kind: .cancelled, code: 0, message: "cancelled"))
                    return nil
                }
            } catch {
                await end(.failed(kind: .connect, code: 0, message: "\(error)"))
                return nil
            }
        }
    }

    private func handle(_ input: Input) async {
        guard let core else { return }
        switch input {
        case .fromLink(let ev, let linkId):
            // Events of an earlier connection (before a retry) are stale.
            guard let link, ObjectIdentifier(link as AnyObject) == linkId else { return }
            switch ev {
            case .notification(let value):
                handlingMachineInput = true
                await process(core.onNotification(value))
                handlingMachineInput = false
            case .lost(let reason):
                // Nothing can be written any more; end the core's session too.
                _ = core.abort("connection lost")
                await end(.failed(kind: .linkLost, code: 0, message: reason))
            }
        case .confirmSas(let match):
            debugLog(Self.tag, "input confirmSas (phase \(current.phase))")
            guard current.phase == .confirmSas else { return }
            if !match { failureKind = .sasMismatch }
            await update { $0.phase = .enrolling }
            await process(core.confirmSas(match))
        case .confirmBind(let bind, let biometricOnly):
            debugLog(Self.tag, "input confirmBind (phase \(current.phase))")
            guard current.phase == .confirmBind else { return }
            if !bind {
                failureKind = .cancelled
                await process(core.abort("enrolment declined on the phone"))
                return
            }
            await update { $0.phase = .enrolling }
            let step: StepResult
            do {
                guard let id = deviceId else { throw AnchorKeyError.failure("no device id") }
                let key = try await keys.create(deviceId: id, biometricOnly: biometricOnly, attestationChallenge: current.bind?.attestationChallenge)
                keyStorage = key.storage
                // The machine judges the attestation; the phone only passes it on.
                step = core.provideAnchorKeyAttested(spkiDer: key.spkiDer, attestation: key.attestation ?? Data())
            } catch {
                debugLog(Self.tag, "anchor key creation failed: \(error)")
                failureKind = .keyError
                step = core.abort("could not create the anchor key: \(error)")
            }
            await process(step)
        case .decide(let decision, let confirmName):
            debugLog(Self.tag, "input decide (phase \(current.phase))")
            guard current.phase == .deciding else { return }
            await update { $0.notice = nil }
            let step = core.decide(decision, confirmName: confirmName)
            if step.err.isEmpty {
                await update {
                    $0.decisionSent = true
                    $0.phase = decision == .reject ? .delivering : .signing
                }
            }
            await process(step)
        case .requestEventlog:
            guard !current.eventlogRequested else { return }
            await update { $0.eventlogRequested = true }
            await process(core.requestEventlog())
        case .abort(let reason, let kind):
            debugLog(Self.tag, "input abort (phase \(current.phase))")
            failureKind = kind
            await process(core.abort(reason))
        case .signed(let der):
            let step = core.provideSignature(der: der)
            if !step.err.isEmpty && !core.finished() {
                // The core checked our signature and refused it; retrying
                // with the same key cannot help.
                _ = await write(step)
                failureKind = .keyError
                await process(core.abort("signature rejected: \(step.err)"))
                return
            }
            if mode.isAttest { await update { $0.phase = .delivering } }
            await process(step)
        case .signFailed(let kind, let reason):
            failureKind = kind
            await process(core.abort(reason))
        }
    }

    /// Writes every fragment of `first` — always, even when its err is set —
    /// then acts on its events; steps produced while doing so are processed
    /// after it, in order.
    private func process(_ first: StepResult) async {
        guard let core else { return }
        var queue = [first]
        while !queue.isEmpty {
            let step = queue.removeFirst()
            if await !write(step) { return }
            for e in step.events { await onEvent(e, &queue) }
            if !step.err.isEmpty && !core.finished() {
                let err = step.err
                await update { $0.notice = err }
            }
        }
        if core.finished() && !ended {
            await end(core.succeeded() ? .succeeded : .failed(kind: failureKind ?? .protocolFailure, code: 0, message: "session ended"))
        }
    }

    /// Returns false if the link failed.
    private func write(_ step: StepResult) async -> Bool {
        guard let link, let core else { return false }
        for f in step.fragments {
            do {
                try await link.write(f)
            } catch is CancellationError {
                if !core.finished() { _ = core.abort("cancelled") }
                await end(.failed(kind: .cancelled, code: 0, message: "cancelled"))
                return false
            } catch {
                if !core.finished() { _ = core.abort("connection lost") }
                await end(.failed(kind: .linkLost, code: 0, message: "\(error)"))
                return false
            }
        }
        return true
    }

    private func onEvent(_ e: Event, _ queue: inout [StepResult]) async {
        debugLog(Self.tag, "core event \(e.name) (phase \(current.phase))")
        guard let core else { return }
        switch e {
        case .sas(let sas):
            await update { $0.phase = .confirmSas; $0.sas = sas }
        case .needAnchorKey(let n):
            // The user decides on binding with what is now known about the
            // machine (TODO-SEC.md S3, S5); the key is made after that.
            deviceId = n.deviceId
            machineName = n.friendlyName
            await update { $0.machineName = n.friendlyName; $0.deviceId = n.deviceId; $0.bind = n; $0.phase = .confirmBind }
        case .needSignature(let purpose, let verdictCode):
            await update { $0.phase = .signing }
            let tbs = core.pendingTbs()
            guard let id = deviceId else {
                failureKind = .protocolFailure
                queue.append(core.abort("signature requested before the machine identified itself"))
                return
            }
            let req = SignRequest(machineName: machineName, purpose: purpose, verdictCode: verdictCode)
            let keys = self.keys
            let writer = inboxWriter
            signTask = Task.detached {
                writer.yield(await SessionRunner.sign(keys, id, tbs, req))
            }
        case .enrolled(let json):
            do {
                try await records.saveEnrolled(recordJSON: json, keyStorage: keyStorage)
            } catch {
                debugLog(Self.tag, "storing the machine record failed: \(error)")
                failureKind = .storage
                await update { $0.notice = "could not store the machine: \(error)" }
            }
        case .hello(_, let name):
            helloSeen = true
            machineName = name
            await update { $0.phase = .verifying; $0.machineName = name }
        case .verdict(let v):
            await update { $0.verdict = v; $0.phase = v.needsDecision ? .deciding : .signing }
        case .eventlog(let l):
            await update { $0.eventlog = l }
        case .receiptAck(let result, let message):
            await update { $0.receiptAck = (result, message) }
        case .recordUpdated(let json):
            do {
                try await records.replace(recordJSON: json)
            } catch {
                debugLog(Self.tag, "updating the machine record failed: \(error)")
                await update { $0.notice = "could not update the stored machine: \(error)" }
            }
        case .done:
            if failureKind == .storage {
                await end(.failed(kind: .storage, code: 0, message: current.notice ?? "storage failure"))
            } else {
                await end(.succeeded)
            }
        case .error(let code, let message):
            await end(.failed(kind: failureKind ?? .protocolFailure, code: code, message: message))
        case .unknown(let type):
            debugLog(Self.tag, "ignoring event \(type)")
        }
    }

    private static func sign(_ keys: AnchorKeyProvider, _ deviceId: String, _ tbs: Data, _ req: SignRequest) async -> Input {
        do {
            return .signed(try await keys.sign(deviceId: deviceId, tbs: tbs, request: req))
        } catch AnchorKeyError.signingCancelled {
            return .signFailed(.authCancelled, reason: "authentication cancelled")
        } catch AnchorKeyError.keyInvalidated {
            return .signFailed(.keyInvalidated, reason: "anchor key invalidated")
        } catch {
            debugLog(tag, "signing failed: \(error)")
            return .signFailed(.keyError, reason: "signing failed")
        }
    }

    private func end(_ outcome: Outcome) async {
        if ended { return }
        debugLog(Self.tag, "session ends in phase \(current.phase): \(outcome)")
        ended = true
        var final = outcome
        if refusedByMachine(outcome) {
            refusals += 1
            if refusals == 1 {
                // Once could be the radio: try a fresh connection silently.
                debugLog(Self.tag, "session ended before the machine identified itself; retrying once")
                retryAfterRefusal = true
                if let link { await link.disconnect() }
                return
            }
            if case .failed(_, let code, let message) = outcome {
                final = .failed(kind: .machineRefused, code: code, message: message)
            }
        }
        await update { $0.phase = .finished; $0.outcome = final }
        if let link { await link.disconnect() }
    }

    /// An attestation that ended before the machine's Hello, for a reason
    /// other than the user: the machine advertised as enrolled but did not
    /// accept this phone's handshake. Decided by phase, never by message
    /// content.
    private func refusedByMachine(_ o: Outcome) -> Bool {
        guard mode.isAttest, !helloSeen, current.phase == .securing,
              case .failed(let kind, _, _) = o
        else { return false }
        return kind == .linkLost || (kind == .protocolFailure && handlingMachineInput)
    }
}
