import CoreBluetooth
import Foundation

/// One tpm2-kira advertisement: the peripheral and its 13-byte service data.
struct Advertisement: @unchecked Sendable {
    let peripheral: CBPeripheral
    let serviceData: Data
    let flags: Int
    let rssi: Int
    let seenAtMs: Int64

    var isEnrol: Bool { flags >= 0 && flags & KiraCore.advFlagEnrol != 0 }
    var isAttest: Bool { flags >= 0 && flags & KiraCore.advFlagAttest != 0 }
    /// iOS hides the Bluetooth address; the identifier is stable for one
    /// peripheral while the phone sees it, which is what the lists need.
    var address: String { peripheral.identifier.uuidString }
}

/// The app's one CBCentralManager: scanning filtered by the tpm2-kira
/// service UUID only (PROTOCOL-BLE.md §13.4), and connections. Which scan
/// results are shown is decided by the caller with `KiraCore.matchAdvertisement`
/// (§2.3). No pairing, no bonding, nothing is retained across launches.
final class BleCentral: NSObject, @unchecked Sendable {
    private let queue = DispatchQueue(label: "io.wiora.marify.ble")
    private var manager: CBCentralManager!
    private let lock = NSLock()

    private var scanners: [UUID: AsyncThrowingStream<Advertisement, Error>.Continuation] = [:]
    private var links: [UUID: GattLink] = [:]
    private var stateWaiters: [Gate<Void>] = []
    /// Called on state changes; the onboarding screen observes it.
    var onStateChange: (@Sendable (CBManagerState) -> Void)?

    override init() {
        super.init()
        // Instantiating the manager asks for the Bluetooth permission; the
        // onboarding screen creates the container at the right moment.
        manager = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
    }

    var state: CBManagerState { manager.state }
    var isPoweredOn: Bool { manager.state == .poweredOn }
    static var authorization: CBManagerAuthorization { CBCentralManager.authorization }

    /// Waits until the manager has left `.unknown`/`.resetting` (the state
    /// right after creation), for at most a few seconds.
    func waitForKnownState() async {
        if manager.state != .unknown && manager.state != .resetting { return }
        let gate = Gate<Void>()
        lock.lock(); stateWaiters.append(gate); lock.unlock()
        _ = try? await withTimeout(seconds: 3) { try await gate.wait() }
    }

    // MARK: scanning

    /// Advertisements of tpm2-kira machines while the stream is consumed;
    /// duplicates are delivered so that "last seen" stays current. Fails at
    /// once when Bluetooth is off.
    func scan() -> AsyncThrowingStream<Advertisement, Error> {
        AsyncThrowingStream(bufferingPolicy: .unbounded) { continuation in
            let id = UUID()
            guard isPoweredOn else {
                continuation.finish(throwing: LinkError.bluetoothOff)
                return
            }
            lock.lock()
            scanners[id] = continuation
            let first = scanners.count == 1
            lock.unlock()
            if first { startScan() }
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.scanners[id] = nil
                let last = self.scanners.isEmpty
                self.lock.unlock()
                if last { self.queue.async { self.manager.stopScan() } }
            }
        }
    }

    private func startScan() {
        queue.async {
            self.manager.scanForPeripherals(
                withServices: [KiraCore.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
    }

    private func failScanners(_ error: LinkError) {
        lock.lock()
        let all = scanners.values
        scanners.removeAll()
        lock.unlock()
        for c in all { c.finish(throwing: error) }
    }

    // MARK: connections

    /// Connects, discovers the service, reads INFO and enables TX
    /// notifications (PROTOCOL-BLE.md §3.3). A peripheral that cannot be
    /// connected is `.notReachable`.
    func connect(_ peripheral: CBPeripheral) async throws -> GattLink {
        guard isPoweredOn else { throw LinkError.bluetoothOff }
        let link = GattLink(peripheral: peripheral, central: self)
        lock.lock(); links[peripheral.identifier] = link; lock.unlock()
        do {
            try await link.setUp()
            return link
        } catch {
            await link.disconnect()
            throw error
        }
    }

    func connectPeripheral(_ p: CBPeripheral) {
        queue.async { self.manager.connect(p, options: nil) }
    }

    func cancelConnection(_ p: CBPeripheral) {
        queue.async { self.manager.cancelPeripheralConnection(p) }
    }

    func forget(_ p: CBPeripheral) {
        lock.lock(); links[p.identifier] = nil; lock.unlock()
    }

    private func link(for p: CBPeripheral) -> GattLink? {
        lock.lock(); defer { lock.unlock() }
        return links[p.identifier]
    }
}

extension BleCentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        debugLog("MarifyBle", "central state \(central.state.rawValue)")
        lock.lock()
        let waiters = stateWaiters
        stateWaiters.removeAll()
        lock.unlock()
        for w in waiters { w.complete(()) }
        onStateChange?(central.state)
        if central.state == .poweredOn {
            lock.lock(); let scanning = !scanners.isEmpty; lock.unlock()
            if scanning { startScan() }
        } else if central.state != .unknown && central.state != .resetting {
            failScanners(.bluetoothOff)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let services = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let data = services[KiraCore.serviceUUID]
        else { return }
        let adv = Advertisement(
            peripheral: peripheral,
            serviceData: data,
            flags: KiraCore.advertisementFlags(data),
            rssi: RSSI.intValue,
            seenAtMs: monotonicMs()
        )
        lock.lock(); let all = Array(scanners.values); lock.unlock()
        for c in all { c.yield(adv) }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        link(for: peripheral)?.didConnect()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        link(for: peripheral)?.didFailToConnect(error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        link(for: peripheral)?.didDisconnect(error)
        forget(peripheral)
    }
}
