import CoreBluetooth
import Foundation

/// One GATT connection to a tpm2-kira machine (PROTOCOL-BLE.md §3.3).
///
/// No bonding, no encrypted characteristics: the link is an untrusted pipe
/// and the Noise session inside the core provides all security (§3.6).
/// Pairing is never initiated here; the machine refuses it anyway.
///
/// Fragments go out as writes with response: each waits for the machine's
/// Write Response, which paces the phone to what the machine's controller
/// can take (an Intel 7265 lost the link on a burst of unacknowledged
/// writes). Fragment size is MTU − 3 like Android's, taken from the
/// without-response limit, so no fragment needs a long write.
final class GattLink: NSObject, Link, @unchecked Sendable {
    private let peripheral: CBPeripheral
    private unowned let central: BleCentral
    private let pipe = LinkEventPipe()
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?
    private var infoChar: CBCharacteristic?
    private var infoValue = InfoValue(protocolVersion: 0, mode: 0, schema: 0, capabilities: 0)
    private var fragmentSize = 20
    private let lock = NSLock()
    private var closed = false
    private var established = false

    // One pending GATT operation during setup; each completes exactly one of these.
    private let connected = Gate<Void>()
    private let discovered = Gate<Void>()
    private let characteristics = Gate<Void>()
    private let infoRead = Gate<Data>()
    private let notifying = Gate<Void>()
    private let disconnected = Gate<Void>()
    private var pendingWrite: Gate<Void>?

    private static let stepTimeout = 15.0
    private static let tag = "MarifyGatt"

    init(peripheral: CBPeripheral, central: BleCentral) {
        self.peripheral = peripheral
        self.central = central
        super.init()
        peripheral.delegate = self
    }

    var maxFragment: Int { fragmentSize }
    var info: InfoValue { infoValue }
    var events: AsyncStream<LinkEvent> { pipe.stream }

    func setUp() async throws {
        central.connectPeripheral(peripheral)
        do {
            try await withTimeout(seconds: Self.stepTimeout) { try await self.connected.wait() }
        } catch is TimeoutError {
            throw LinkError.notReachable("the Bluetooth connection could not be established")
        }
        // Fragment size as Android: ATT MTU − 3, never a long write.
        fragmentSize = max(20, peripheral.maximumWriteValueLength(for: .withoutResponse))
        debugLog(Self.tag, "connected, fragment size \(fragmentSize)")

        peripheral.discoverServices([KiraCore.serviceUUID])
        try await step(discovered, "service discovery failed")
        guard let service = peripheral.services?.first(where: { $0.uuid == KiraCore.serviceUUID }) else {
            throw LinkError.failed("the device does not offer the tpm2-kira service")
        }
        peripheral.discoverCharacteristics([KiraCore.rxUUID, KiraCore.txUUID, KiraCore.infoUUID], for: service)
        try await step(characteristics, "characteristic discovery failed")
        let chars = service.characteristics ?? []
        rx = chars.first { $0.uuid == KiraCore.rxUUID }
        tx = chars.first { $0.uuid == KiraCore.txUUID }
        infoChar = chars.first { $0.uuid == KiraCore.infoUUID }
        guard let rx, let tx, let infoChar else { throw LinkError.failed("the tpm2-kira service is incomplete") }
        _ = rx

        peripheral.readValue(for: infoChar)
        let raw: Data
        do {
            raw = try await withTimeout(seconds: Self.stepTimeout) { try await self.infoRead.wait() }
        } catch is TimeoutError {
            throw LinkError.failed("the machine did not answer in time")
        }
        infoValue = try InfoValue.parse(raw)

        peripheral.setNotifyValue(true, for: tx)
        try await step(notifying, "enabling notifications failed")
        lock.lock(); established = true; lock.unlock()
    }

    private func step(_ gate: Gate<Void>, _ what: String) async throws {
        do {
            try await withTimeout(seconds: Self.stepTimeout) { try await gate.wait() }
        } catch is TimeoutError {
            throw LinkError.failed("the machine did not answer in time")
        } catch let e as LinkError {
            throw e
        } catch {
            throw LinkError.failed("\(what): \(error.localizedDescription)")
        }
    }

    func write(_ fragment: Data) async throws {
        lock.lock()
        if closed {
            lock.unlock()
            throw LinkError.failed("link closed")
        }
        guard let rx else {
            lock.unlock()
            throw LinkError.failed("not set up")
        }
        let gate = Gate<Void>()
        pendingWrite = gate
        lock.unlock()
        debugLog(Self.tag, "tx fragment \(GattLink.fragInfo(fragment))")
        peripheral.writeValue(fragment, for: rx, type: .withResponse)
        do {
            try await withTimeout(seconds: 10) { try await gate.wait() }
        } catch is TimeoutError {
            throw LinkError.failed("RX write not acknowledged")
        } catch let e as LinkError {
            throw e
        } catch {
            throw LinkError.failed("RX write failed: \(error.localizedDescription)")
        }
    }

    func disconnect() async {
        lock.lock()
        if closed { lock.unlock(); return }
        closed = true
        lock.unlock()
        central.cancelConnection(peripheral)
        // Give the stack a moment to report the disconnect.
        _ = try? await withTimeout(seconds: 2) { try await self.disconnected.wait() }
        central.forget(peripheral)
        pipe.continuation.finish()
    }

    // MARK: callbacks from the central

    func didConnect() { connected.complete(()) }

    func didFailToConnect(_ error: Error?) {
        connected.fail(LinkError.notReachable("the Bluetooth connection could not be established"))
    }

    func didDisconnect(_ error: Error?) {
        let reason = "disconnected" + (error.map { " (\($0.localizedDescription))" } ?? "")
        debugLog(Self.tag, reason)
        let failure = LinkError.failed(reason)
        connected.fail(failure)
        discovered.fail(failure)
        characteristics.fail(failure)
        infoRead.fail(failure)
        notifying.fail(failure)
        lock.lock()
        let w = pendingWrite
        pendingWrite = nil
        let announce = established && !closed
        lock.unlock()
        w?.fail(failure)
        if announce { pipe.continuation.yield(.lost(reason)) }
        pipe.continuation.finish()
        disconnected.complete(())
    }

    /// Fragment header (u16le total ‖ u8 flags ‖ u16le seq) for the debug log.
    static func fragInfo(_ f: Data) -> String {
        if f.count < 5 { return "of \(f.count) bytes (short)" }
        let b = [UInt8](f.prefix(5))
        let total = Int(b[0]) | Int(b[1]) << 8
        let seq = Int(b[3]) | Int(b[4]) << 8
        let start = b[2] & 1 != 0 ? " start" : ""
        return "seq \(seq)\(start), record \(total) bytes, \(f.count) bytes"
    }
}

extension GattLink: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { discovered.fail(LinkError.failed("service discovery failed: \(error.localizedDescription)")) } else { discovered.complete(()) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { characteristics.fail(LinkError.failed("characteristic discovery failed: \(error.localizedDescription)")) } else { characteristics.complete(()) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == KiraCore.infoUUID {
            if let error {
                infoRead.fail(LinkError.failed("INFO read failed: \(error.localizedDescription)"))
            } else {
                infoRead.complete(characteristic.value ?? Data())
            }
        } else if characteristic.uuid == KiraCore.txUUID, error == nil {
            let v = characteristic.value ?? Data()
            debugLog(Self.tag, "rx fragment \(GattLink.fragInfo(v))")
            pipe.continuation.yield(.notification(v))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == KiraCore.txUUID else { return }
        if let error { notifying.fail(LinkError.failed("enabling notifications failed: \(error.localizedDescription)")) } else { notifying.complete(()) }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        lock.lock()
        let w = pendingWrite
        pendingWrite = nil
        lock.unlock()
        if let error { w?.fail(LinkError.failed("RX write failed (\(error.localizedDescription))")) } else { w?.complete(()) }
    }
}
