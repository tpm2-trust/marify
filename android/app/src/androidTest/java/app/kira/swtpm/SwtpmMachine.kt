package app.kira.swtpm

import java.io.Closeable
import java.net.Socket

/**
 * Remote control of the swtpm-backed tpm2-kira machine served by
 * `TestAppMachine` (tpm2-kira cmd/attest_app_test.go), the counterpart of
 * kiratest's DemoMachine for a real TPM 2.0 implementation.
 */
class SwtpmMachine(private val host: String, private val port: Int) : Closeable {
    private val socket = Socket(host, port)
    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()

    /** Sends one command; a reply starting with "error" fails it unless [raw]. */
    @Synchronized
    private fun command(line: String, raw: Boolean = false): String {
        writer.write(line + "\n")
        writer.flush()
        val reply = reader.readLine() ?: error("machine closed the control connection")
        check(raw || !reply.startsWith("error")) { "$line: $reply" }
        return reply
    }

    /** Serve one enrolment on the next [link]. */
    fun startEnrolment(mtu: Int) { command("enrol $mtu") }

    /** Serve one attestation on the next [link]. */
    fun startAttestation(mtu: Int) { command("attest $mtu") }

    /** Orderly TPM shutdown and restart: reset counter +1, PCRs cleared. */
    fun reboot() { command("reboot") }

    fun extendPcr(index: Int) { command("extend $index") }

    /** Saves the TPM state (with a reboot). */
    fun snapshot() { command("snapshot") }

    /** Restores the saved TPM state: a replayed, older TPM. */
    fun rollback() { command("rollback") }

    /** The machine's verdict on the phone's key attestation at the last enrolment (JSON). */
    fun phoneVerdict(): String = command("phone")

    /** The code the machine's console showed at the last enrolment. */
    fun lastSas(): String = command("sas")

    /**
     * The machine's outcome of the last session ("enrolled", "receipt: …",
     * "error: …" when the machine ended it), or "" after [timeoutMs].
     */
    fun outcome(timeoutMs: Int = 10_000): String = command("outcome $timeoutMs", raw = true)

    fun link(mtu: Int): TcpLink = TcpLink.connect(host, port + 1, mtu)

    override fun close() = socket.close()
}
