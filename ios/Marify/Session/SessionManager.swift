import CoreBluetooth
import Foundation

/// The simulated machine of debug builds; `NoDemo` in release builds.
protocol DemoSupport: Sendable {
    var available: Bool { get }
    func enrolLink() -> @Sendable () async throws -> any Link
    func attestLink() -> @Sendable () async throws -> any Link
    func owns(recordJSON: String) -> Bool
    func nextBoot()
    func changePcr(_ index: Int)
    func rollbackResetCount()
    /// What the demo machine's screen shows: the last boot code.
    func screenCode() -> String
    func refuseBootKey(_ refuse: Bool)
}

struct NoDemo: DemoSupport {
    var available: Bool { false }
    func enrolLink() -> @Sendable () async throws -> any Link { { throw LinkError.failed("no demo machine") } }
    func attestLink() -> @Sendable () async throws -> any Link { { throw LinkError.failed("no demo machine") } }
    func owns(recordJSON: String) -> Bool { false }
    func nextBoot() {}
    func changePcr(_ index: Int) {}
    func rollbackResetCount() {}
    func screenCode() -> String { "" }
    func refuseBootKey(_ refuse: Bool) {}
}

/// Owns the one active session of the app. Sessions run in their own task
/// and continue while the app is in the background (the `bluetooth-central`
/// background mode keeps the connection), so switching apps briefly does
/// not cut the machine off.
@MainActor
@Observable
final class SessionManager {
    private let central: BleCentral
    private let store: MachineStore
    private let keys: AnchorKeyProvider
    let demo: DemoSupport

    private(set) var active: SessionRunner?
    private var task: Task<Void, Never>?
    private var retryAction: (() -> Void)?
    private var demoSession = false

    init(central: BleCentral, store: MachineStore, keys: AnchorKeyProvider, demo: DemoSupport) {
        self.central = central
        self.store = store
        self.keys = keys
        self.demo = demo
    }

    private struct Sink: RecordSink {
        let store: MachineStore
        func saveEnrolled(recordJSON: String, keyStorage: KeyStorage) async throws {
            try await store.saveEnrolled(recordJSON: recordJSON, keyStorage: keyStorage)
        }
        func replace(recordJSON: String) async throws { try await store.replace(recordJSON: recordJSON) }
    }

    func startEnrol(_ adv: Advertisement) {
        retryAction = nil
        // The same enrolling machine keeps its identifier until enrolment ends.
        let finder = MachineFinder(central: central, name: "the machine") { $0.isEnrol && $0.address == adv.address }
        start(.enrol, connect: connectVia(finder, first: adv.peripheral), onEnd: { finder.stop() })
    }

    func startDemoEnrol() {
        retryAction = nil
        start(.enrol, connect: demo.enrolLink())
    }

    /// Attests `machine`. With `adv` the first attempt goes to that
    /// peripheral; otherwise, and on every retry, the machine's next
    /// advertisement is awaited (it uses a new address per boot).
    func startAttest(_ machine: Machine, adv: Advertisement? = nil) {
        retryAction = { [weak self] in self?.startAttest(machine) }
        let mode = SessionMode.attest(deviceId: machine.deviceId, machineName: machine.name, lastAttested: machine.summary.lastAttested, returnsSalt: machine.summary.factorKeptAt != nil)
        if demo.owns(recordJSON: machine.recordJSON) {
            demoSession = true
            start(mode, connect: demo.attestLink(), recordJSON: machine.recordJSON)
            return
        }
        demoSession = false
        let record = machine.recordJSON
        let finder = MachineFinder(central: central, name: machine.name) {
            $0.isAttest && KiraCore.matchAdvertisement($0.serviceData, recordJSON: record)
        }
        start(mode, connect: connectVia(finder, first: adv?.peripheral), recordJSON: record, onEnd: { finder.stop() })
    }

    /// First `first` if given, then whatever `finder` sees, one attempt per call.
    private func connectVia(_ finder: MachineFinder, first: CBPeripheral?) -> @Sendable () async throws -> any Link {
        let central = self.central
        let box = FirstBox(first)
        return {
            let peripheral: CBPeripheral
            if let p = box.take() {
                peripheral = p
            } else {
                peripheral = try await finder.find(windowMs: SessionRunner.retryIntervalMs)
            }
            return try await central.connect(peripheral)
        }
    }

    var canRetry: Bool { retryAction != nil }
    func retry() { retryAction?() }

    /// Leaves the session: aborts it in the protocol, or stops connecting.
    func cancel() {
        guard let r = active else { return }
        let s = r.model.value
        if s.finished { return }
        let t = task
        // While it is still connecting the runner reads no input, so an
        // abort request would wait unread: cancel the task instead.
        if s.phase == .connecting || s.unreachable != nil {
            t?.cancel()
            return
        }
        r.cancel()
        // A stalled link must not keep the session alive either.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard let self, let t, self.task == t else { return }
            if !r.model.value.finished { t.cancel() }
        }
    }

    /// Forgets a finished session.
    func dismiss() {
        guard let r = active, r.model.value.finished else { return }
        active = nil
    }

    /// What the machine's screen shows for the running session, where that
    /// screen is simulated: the demo machine's boot code. Nil for a real
    /// machine, whose screen the person looks at.
    func demoScreenCode() -> String? {
        guard demoSession else { return nil }
        let c = demo.screenCode()
        return c.isEmpty ? nil : c
    }

    private func start(
        _ mode: SessionMode,
        connect: @escaping @Sendable () async throws -> any Link,
        recordJSON: String? = nil,
        onEnd: @escaping @Sendable () -> Void = {}
    ) {
        if let previous = active, !previous.model.value.finished { return }
        let runner = SessionRunner(
            mode: mode,
            connect: connect,
            newSession: {
                // The phone's identity towards the machine is in its record;
                // an enrolment makes a new one (one per machine).
                if let recordJSON {
                    return try KiraCore.newAttestSession(recordJSON: recordJSON)
                }
                return try KiraCore.newEnrolSession()
            },
            keys: keys,
            records: Sink(store: store),
            coreProtocol: KiraCore.protocolVersion,
            coreSchema: KiraCore.schemaVersion
        )
        active = runner
        task = Task.detached {
            await runner.run()
            onEnd()
        }
    }
}

/// Hands out a value once.
private final class FirstBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: CBPeripheral?
    init(_ v: CBPeripheral?) { value = v }
    func take() -> CBPeripheral? {
        lock.lock(); defer { lock.unlock() }
        let v = value
        value = nil
        return v
    }
}

/// Looks for one machine's advertisements with a scan that runs only while
/// an attempt waits for one; the scan stops before connecting.
final class MachineFinder: @unchecked Sendable {
    private let central: BleCentral
    private let name: String
    private let matches: @Sendable (Advertisement) -> Bool
    private let lock = NSLock()
    private var scanTask: Task<Void, Never>?

    init(central: BleCentral, name: String, matches: @escaping @Sendable (Advertisement) -> Bool) {
        self.central = central
        self.name = name
        self.matches = matches
    }

    func find(windowMs: Int64) async throws -> CBPeripheral {
        guard central.isPoweredOn else { throw LinkError.bluetoothOff }
        let found = Gate<Advertisement>()
        let matches = self.matches
        let central = self.central
        let t = Task {
            do {
                for try await adv in central.scan() where matches(adv) {
                    found.complete(adv)
                    break
                }
            } catch {
                found.fail(error)
            }
        }
        lock.lock(); scanTask = t; lock.unlock()
        defer { t.cancel() }
        do {
            let adv = try await withTimeout(seconds: Double(windowMs) / 1000) { try await found.wait() }
            return adv.peripheral
        } catch is TimeoutError {
            throw LinkError.notReachable("\(name) was not seen nearby")
        }
    }

    func stop() {
        lock.lock(); let t = scanTask; scanTask = nil; lock.unlock()
        t?.cancel()
    }
}
