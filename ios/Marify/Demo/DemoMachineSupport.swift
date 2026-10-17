#if DEBUG
import Foundation
import Kiracore

/// A `Link` to a kiratest DemoMachine: RX writes go to `DemoMachine.write`,
/// notifications come from `DemoMachine.nextNotification`. Debug builds only.
final class DemoLink: Link, @unchecked Sendable {
    private let demo: KiratestDemoMachine
    let maxFragment: Int
    let info: InfoValue
    private let pipe = LinkEventPipe()
    private let lock = NSLock()
    private var closed = false
    private var pump: Thread?

    /// Fragments the app wrote, for tests.
    private(set) var written: [Data] = []

    init(demo: KiratestDemoMachine, mtu: Int, mode: Int) {
        self.demo = demo
        maxFragment = mtu - 3
        info = InfoValue(protocolVersion: 1, mode: mode, schema: 1, capabilities: 1)
        let t = Thread { [weak self] in
            while let self, !self.isClosed {
                guard let n = demo.nextNotification(100) else { continue }
                self.pipe.continuation.yield(.notification(n))
            }
        }
        t.name = "marify-demo-rx"
        pump = t
        t.start()
    }

    private var isClosed: Bool {
        lock.lock(); defer { lock.unlock() }
        return closed
    }

    var events: AsyncStream<LinkEvent> { pipe.stream }

    func write(_ fragment: Data) async throws {
        if isClosed { throw LinkError.failed("link closed") }
        if fragment.count > maxFragment { throw LinkError.failed("fragment exceeds MTU") }
        lock.lock(); written.append(fragment); lock.unlock()
        demo.write(fragment)
    }

    func disconnect() async {
        lock.lock()
        if closed { lock.unlock(); return }
        closed = true
        lock.unlock()
        pipe.continuation.finish()
    }

    /// Starts the demo machine's side of a session and returns the app's link to it.
    static func enrol(_ demo: KiratestDemoMachine, mtu: Int = 517) throws -> DemoLink {
        try demo.startEnrolment(mtu)
        return DemoLink(demo: demo, mtu: mtu, mode: InfoValue.modeEnrol)
    }

    static func attest(_ demo: KiratestDemoMachine, mtu: Int = 517) throws -> DemoLink {
        try demo.startAttestation(mtu)
        return DemoLink(demo: demo, mtu: mtu, mode: InfoValue.modeAttest)
    }
}

/// One kiratest demo machine per process ("Thinkpad-X1"). Its identity lives
/// in memory: after the app restarts, a demo machine enrolled earlier no
/// longer exists and its record should be deleted.
final class DemoMachineSupport: DemoSupport, @unchecked Sendable {
    private let lock = NSLock()
    private var machine: KiratestDemoMachine?

    var available: Bool { true }

    private func demo() -> KiratestDemoMachine {
        lock.lock(); defer { lock.unlock() }
        if let machine { return machine }
        var err: NSError?
        guard let m = KiratestNewDemoMachine(&err) else { fatalError("demo machine: \(err?.localizedDescription ?? "?")") }
        machine = m
        return m
    }

    func enrolLink() -> @Sendable () async throws -> any Link {
        let d = demo()
        return { try DemoLink.enrol(d) }
    }

    func attestLink() -> @Sendable () async throws -> any Link {
        let d = demo()
        return { try DemoLink.attest(d) }
    }

    /// Links with a chosen MTU, for tests.
    func enrolLink(mtu: Int) throws -> DemoLink { try DemoLink.enrol(demo(), mtu: mtu) }
    func attestLink(mtu: Int) throws -> DemoLink { try DemoLink.attest(demo(), mtu: mtu) }

    func owns(recordJSON: String) -> Bool {
        guard let data = demo().serviceData() else { return false }
        return KiraCore.matchAdvertisement(data, recordJSON: recordJSON)
    }

    func nextBoot() { demo().nextBoot() }
    func changePcr(_ index: Int) { demo().changePCR(index) }
    func rollbackResetCount() { demo().rollbackResetCount() }
    func screenCode() -> String { demo().lastBootCode() }
    func refuseBootKey(_ refuse: Bool) { demo().refuseBootKey(refuse) }

    /// The demo machine's outcome of the last session, for tests.
    func outcome(timeoutMs: Int = 10_000) -> String { demo().outcome(timeoutMs) }
    func lastSas() -> String { demo().lastSAS() }
    func serviceData() -> Data { demo().serviceData() ?? Data() }
}

enum DemoFactory {
    static func create() -> DemoSupport { DemoMachineSupport() }
}
#else
enum DemoFactory {
    static func create() -> DemoSupport { NoDemo() }
}
#endif
