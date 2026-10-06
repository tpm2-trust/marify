package app.marify.swtpm

import app.marify.ble.InfoValue
import app.marify.ble.Link
import app.marify.ble.LinkEvent
import app.marify.ble.LinkException
import app.marify.ble.MachineNotReachable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A [Link] to the swtpm test machine over TCP (tpm2-kira cmd/attest_app_test.go),
 * standing in for GATT: the machine sends the 8-byte INFO value first, then
 * fragments travel both ways, each prefixed by a big-endian u16 length.
 * Test sources only.
 */
class TcpLink private constructor(
    private val socket: Socket,
    override val maxFragment: Int,
    override val info: InfoValue,
) : Link {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val channel = Channel<LinkEvent>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    override val events: ReceiveChannel<LinkEvent> get() = channel

    init {
        thread(name = "kira-tcp-rx", isDaemon = true) {
            try {
                while (true) {
                    val n = input.readUnsignedShort()
                    val f = ByteArray(n)
                    input.readFully(f)
                    channel.trySend(LinkEvent.Notification(f))
                }
            } catch (_: IOException) {
                // The machine ended the session, or we disconnected.
            }
            if (!closed.get()) channel.trySend(LinkEvent.Lost("machine closed the connection"))
            channel.close()
        }
    }

    override suspend fun write(fragment: ByteArray) {
        if (closed.get()) throw LinkException("link closed")
        if (fragment.size > maxFragment) throw LinkException("fragment exceeds MTU")
        try {
            synchronized(output) {
                output.writeShort(fragment.size)
                output.write(fragment)
                output.flush()
            }
        } catch (e: IOException) {
            throw LinkException("write failed", e)
        }
    }

    override suspend fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        channel.close()
    }

    companion object {
        /**
         * Connects like GattLink: a machine that is not serving (connection
         * refused, or closed before INFO) is [MachineNotReachable].
         */
        fun connect(host: String, port: Int, mtu: Int): TcpLink {
            val s = try {
                Socket(host, port)
            } catch (e: IOException) {
                throw MachineNotReachable("the test machine refused the connection", e)
            }
            s.tcpNoDelay = true
            val info = ByteArray(InfoValue.SIZE)
            try {
                DataInputStream(s.getInputStream()).readFully(info)
            } catch (e: IOException) {
                s.close()
                throw MachineNotReachable("the test machine is not waiting for a phone", e)
            }
            return TcpLink(s, mtu - 3, InfoValue.parse(info))
        }
    }
}
