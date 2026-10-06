import Foundation
@testable import Marify

/// A blocking TCP line/byte client on POSIX sockets, enough for the test
/// machine's two ports; the simulator shares the Mac's loopback.
final class TcpSocket: @unchecked Sendable {
    private let fd: Int32
    private let lock = NSLock()

    init(host: String, port: Int) throws {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let ai = res else {
            throw LinkError.notReachable("cannot resolve \(host)")
        }
        defer { freeaddrinfo(res) }
        let s = socket(ai.pointee.ai_family, ai.pointee.ai_socktype, ai.pointee.ai_protocol)
        guard s >= 0 else { throw LinkError.notReachable("socket failed") }
        guard connect(s, ai.pointee.ai_addr, ai.pointee.ai_addrlen) == 0 else {
            close(s)
            throw LinkError.notReachable("the test machine refused the connection")
        }
        var one: Int32 = 1
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        fd = s
    }

    func readFully(_ n: Int) throws -> Data {
        var out = Data(capacity: n)
        var buf = [UInt8](repeating: 0, count: 4096)
        while out.count < n {
            let got = read(fd, &buf, min(buf.count, n - out.count))
            if got <= 0 { throw LinkError.failed("connection closed") }
            out.append(contentsOf: buf[0..<got])
        }
        return out
    }

    func readLine() throws -> String {
        var line = [UInt8]()
        var b: UInt8 = 0
        while true {
            let got = read(fd, &b, 1)
            if got <= 0 { throw LinkError.failed("connection closed") }
            if b == 10 { break }
            line.append(b)
        }
        return String(decoding: line, as: UTF8.self)
    }

    func writeAll(_ data: Data) throws {
        lock.lock(); defer { lock.unlock() }
        var sent = 0
        try data.withUnsafeBytes { (p: UnsafeRawBufferPointer) in
            while sent < data.count {
                let n = write(fd, p.baseAddress! + sent, data.count - sent)
                if n <= 0 { throw LinkError.failed("write failed") }
                sent += n
            }
        }
    }

    func shutdown() { close(fd) }
}

/// Remote control of the swtpm-backed tpm2-kira machine served by
/// `TestAppMachine` (tpm2-kira cmd/attest_app_test.go), the counterpart of
/// kiratest's DemoMachine for a real TPM 2.0 implementation.
final class SwtpmMachine: @unchecked Sendable {
    private let host: String
    private let port: Int
    private let control: TcpSocket
    private let lock = NSLock()

    init(host: String, port: Int) throws {
        self.host = host
        self.port = port
        control = try TcpSocket(host: host, port: port)
    }

    /// Sends one command; a reply starting with "error" fails it unless `raw`.
    private func command(_ line: String, raw: Bool = false) throws -> String {
        lock.lock(); defer { lock.unlock() }
        try control.writeAll(Data((line + "\n").utf8))
        let reply = try control.readLine()
        if !raw && reply.hasPrefix("error") { throw LinkError.failed("\(line): \(reply)") }
        return reply
    }

    /// Serve one enrolment on the next `link`.
    func startEnrolment(mtu: Int) throws { _ = try command("enrol \(mtu)") }
    /// Serve one attestation on the next `link`.
    func startAttestation(mtu: Int) throws { _ = try command("attest \(mtu)") }
    /// Orderly TPM shutdown and restart: reset counter +1, PCRs cleared.
    func reboot() throws { _ = try command("reboot") }
    func extendPcr(_ index: Int) throws { _ = try command("extend \(index)") }
    /// Saves the TPM state (with a reboot).
    func snapshot() throws { _ = try command("snapshot") }
    /// Restores the saved TPM state: a replayed, older TPM.
    func rollback() throws { _ = try command("rollback") }
    /// The machine's verdict on the phone's key attestation at the last enrolment (JSON).
    func phoneVerdict() throws -> String { try command("phone") }
    /// The code the machine's console showed at the last enrolment.
    func lastSas() throws -> String { try command("sas") }
    /// The machine's outcome of the last session ("enrolled", "receipt: …",
    /// "error: …" when the machine ended it), or "" after `timeoutMs`.
    func outcome(timeoutMs: Int = 10_000) throws -> String { try command("outcome \(timeoutMs)", raw: true) }

    func link(mtu: Int) throws -> TcpLink { try TcpLink.connect(host: host, port: port + 1, mtu: mtu) }

    func close() { control.shutdown() }
}

/// A `Link` to the swtpm test machine over TCP, standing in for GATT: the
/// machine sends the 8-byte INFO value first, then fragments travel both
/// ways, each prefixed by a big-endian u16 length. Test sources only.
final class TcpLink: Link, @unchecked Sendable {
    private let socket: TcpSocket
    let maxFragment: Int
    let info: InfoValue
    private let pipe = LinkEventPipe()
    private let lock = NSLock()
    private var closed = false

    private init(socket: TcpSocket, maxFragment: Int, info: InfoValue) {
        self.socket = socket
        self.maxFragment = maxFragment
        self.info = info
        let t = Thread { [weak self] in
            while true {
                guard let self else { return }
                guard let header = try? socket.readFully(2) else { break }
                let n = Int(header[0]) << 8 | Int(header[1])
                guard let f = try? socket.readFully(n) else { break }
                self.pipe.continuation.yield(.notification(f))
            }
            guard let self else { return }
            if !self.isClosed { self.pipe.continuation.yield(.lost("machine closed the connection")) }
            self.pipe.continuation.finish()
        }
        t.name = "marify-tcp-rx"
        t.start()
    }

    private var isClosed: Bool { lock.lock(); defer { lock.unlock() }; return closed }

    var events: AsyncStream<LinkEvent> { pipe.stream }

    func write(_ fragment: Data) async throws {
        if isClosed { throw LinkError.failed("link closed") }
        if fragment.count > maxFragment { throw LinkError.failed("fragment exceeds MTU") }
        var frame = Data([UInt8(fragment.count >> 8), UInt8(fragment.count & 0xff)])
        frame.append(fragment)
        try socket.writeAll(frame)
    }

    func disconnect() async {
        lock.lock()
        if closed { lock.unlock(); return }
        closed = true
        lock.unlock()
        socket.shutdown()
        pipe.continuation.finish()
    }

    /// Connects like GattLink: a machine that is not serving (connection
    /// refused, or closed before INFO) is `.notReachable`.
    static func connect(host: String, port: Int, mtu: Int) throws -> TcpLink {
        let s = try TcpSocket(host: host, port: port)
        let info: Data
        do {
            info = try s.readFully(InfoValue.size)
        } catch {
            s.shutdown()
            throw LinkError.notReachable("the test machine is not waiting for a phone")
        }
        return TcpLink(socket: s, maxFragment: mtu - 3, info: try InfoValue.parse(info))
    }
}
