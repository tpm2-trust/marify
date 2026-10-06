import Foundation

/// One connection to a machine: an untrusted, ordered byte pipe
/// (PROTOCOL-BLE.md §3). `GattLink` implements it over BLE; tests and the
/// debug demo machine implement it in memory.
protocol Link: AnyObject, Sendable {
    /// Largest RX write: ATT MTU − 3.
    var maxFragment: Int { get }

    /// The INFO characteristic read after discovery.
    var info: InfoValue { get }

    /// TX notifications in arrival order, never dropped, followed by at most
    /// one `.lost` when the connection ends. Consumed once.
    var events: AsyncStream<LinkEvent> { get }

    /// Writes one fragment to RX and returns once the machine has taken it.
    func write(_ fragment: Data) async throws

    /// Ends the connection. Idempotent.
    func disconnect() async
}

enum LinkEvent: Sendable {
    case notification(Data)
    case lost(String)
}

/// A link failure. `kind` selects what the session makes of it.
struct LinkError: Error, Sendable, CustomStringConvertible {
    enum Kind: Sendable {
        /// The connection was set up but failed, or the write failed.
        case failed
        /// The machine could not be reached at all: not seen advertising, or
        /// the connection could not be established. Nothing about the
        /// machine's integrity follows from this; the session retries.
        case notReachable
        /// Bluetooth is switched off on this phone (or not permitted).
        case bluetoothOff
    }

    var kind: Kind
    var description: String

    static func failed(_ message: String) -> LinkError { LinkError(kind: .failed, description: message) }
    static func notReachable(_ message: String) -> LinkError { LinkError(kind: .notReachable, description: message) }
    static let bluetoothOff = LinkError(kind: .bluetoothOff, description: "Bluetooth is off on this phone")
}

/// The INFO characteristic (PROTOCOL-BLE.md §3.2):
/// `u8 protocol ‖ u8 mode ‖ u16le schema ‖ u32le capabilities`.
///
/// This is the GATT layer the app owns; nothing above it is parsed in Swift.
struct InfoValue: Sendable, Equatable {
    var protocolVersion: Int
    var mode: Int
    var schema: Int
    var capabilities: UInt32

    static let size = 8
    static let modeEnrol = 1
    static let modeAttest = 2

    enum Compatibility: Equatable {
        case ok
        /// The machine speaks a protocol or schema this app does not.
        case updateNeeded(appTooOld: Bool)
        /// The machine is in the other mode (enrolment vs. attestation).
        case wrongMode(actual: Int)
    }

    func check(expectedMode: Int, coreProtocol: Int, coreSchema: Int) -> Compatibility {
        if protocolVersion != coreProtocol { return .updateNeeded(appTooOld: protocolVersion > coreProtocol) }
        if schema > coreSchema { return .updateNeeded(appTooOld: true) }
        if mode != expectedMode { return .wrongMode(actual: mode) }
        return .ok
    }

    static func parse(_ b: Data) throws -> InfoValue {
        guard b.count >= size else { throw LinkError.failed("INFO value has \(b.count) bytes, expected \(size)") }
        let u = [UInt8](b.prefix(size))
        return InfoValue(
            protocolVersion: Int(u[0]),
            mode: Int(u[1]),
            schema: Int(u[2]) | Int(u[3]) << 8,
            capabilities: UInt32(u[4]) | UInt32(u[5]) << 8 | UInt32(u[6]) << 16 | UInt32(u[7]) << 24
        )
    }
}

/// A stream with a buffer that never drops, plus the continuation to feed it.
/// Tests and in-memory links use it; `GattLink` too.
struct LinkEventPipe: Sendable {
    let stream: AsyncStream<LinkEvent>
    let continuation: AsyncStream<LinkEvent>.Continuation

    init() {
        var c: AsyncStream<LinkEvent>.Continuation!
        stream = AsyncStream(bufferingPolicy: .unbounded) { c = $0 }
        continuation = c
    }
}
