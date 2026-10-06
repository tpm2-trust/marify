package app.marify.demo

import app.marify.ble.InfoValue
import app.marify.ble.Link
import app.marify.ble.LinkEvent
import app.marify.ble.LinkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Link] to a kiratest DemoMachine: RX writes go to `DemoMachine.write`,
 * notifications come from `DemoMachine.nextNotification`. Debug and test
 * builds only.
 */
class DemoLink(
    private val demo: kiratest.DemoMachine,
    mtu: Int,
    mode: Int,
) : Link {
    override val maxFragment = mtu - 3
    override val info = InfoValue(protocol = 1, mode = mode, schema = 1, capabilities = 1)

    private val channel = Channel<LinkEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<LinkEvent> get() = channel
    private val closed = AtomicBoolean(false)
    private val pump = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fragments the app wrote, for tests. */
    val written = mutableListOf<ByteArray>()

    init {
        pump.launch {
            while (isActive && !closed.get()) {
                val n = demo.nextNotification(100) ?: continue
                channel.send(LinkEvent.Notification(n))
            }
        }
    }

    override suspend fun write(fragment: ByteArray) {
        if (closed.get()) throw LinkException("link closed")
        if (fragment.size > maxFragment) throw LinkException("fragment exceeds MTU")
        synchronized(written) { written.add(fragment) }
        demo.write(fragment)
    }

    override suspend fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        pump.cancel()
        channel.close()
    }

    companion object {
        /** Starts the demo machine's side of a session and returns the app's link to it. */
        fun enrol(demo: kiratest.DemoMachine, mtu: Int = 517): DemoLink {
            demo.startEnrolment(mtu.toLong())
            return DemoLink(demo, mtu, InfoValue.MODE_ENROL)
        }

        fun attest(demo: kiratest.DemoMachine, mtu: Int = 517): DemoLink {
            demo.startAttestation(mtu.toLong())
            return DemoLink(demo, mtu, InfoValue.MODE_ATTEST)
        }
    }
}
