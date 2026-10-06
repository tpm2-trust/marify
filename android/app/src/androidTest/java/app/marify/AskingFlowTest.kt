package app.marify

import android.bluetooth.BluetoothAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marify.ble.Advertisement
import app.marify.ui.askingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "X is asking for attestation" is shown while X advertises and goes away
 * when X has stopped: after its check, or out of range.
 */
@RunWith(AndroidJUnit4::class)
class AskingFlowTest {
    private var clock = 1_000_000L
    private val scan = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
    private fun adv(name: String) = Advertisement(
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01"),
        name.toByteArray(), 2L, -50, clock,
    )

    private fun lists(): Pair<MutableList<List<String>>, () -> Unit> {
        val out = mutableListOf<List<String>>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            askingFlow(scan, match = { String(it.serviceData) }, key = { it }, now = { clock }, staleMs = 1_000, sweepMs = 50)
                .collect { l -> synchronized(out) { out += l.map { it.first } } }
        }
        // A shared flow drops what is emitted before anyone listens.
        runBlocking { withTimeout(5_000) { scan.subscriptionCount.first { it > 0 } } }
        return out to { job.cancel() }
    }

    private fun await(out: MutableList<List<String>>, want: List<String>) = runBlocking {
        withTimeout(5_000) {
            while (synchronized(out) { out.lastOrNull() } != want) kotlinx.coroutines.delay(20)
        }
    }

    @Test fun listedWhileHeardAndGoneAfterwards() = runBlocking {
        val (out, stop) = lists()
        await(out, emptyList()) // a fresh look starts from nothing
        scan.emit(adv("laptop"))
        await(out, listOf("laptop"))
        clock += 500
        scan.emit(adv("laptop")) // still advertising: stays
        clock += 800
        assertEquals(listOf("laptop"), synchronized(out) { out.last() })
        clock += 800 // 1.6 s after the last advertisement: gone
        await(out, emptyList())
        stop()
    }

    @Test fun aNewLookDoesNotInheritTheOldList() = runBlocking {
        val (out, stop) = lists()
        scan.emit(adv("laptop"))
        await(out, listOf("laptop"))
        stop()
        runBlocking { withTimeout(5_000) { scan.subscriptionCount.first { it == 0 } } }
        // The machine's check is over; nothing advertises. Looking again
        // must not show it.
        val (again, stopAgain) = lists()
        await(again, emptyList())
        clock += 100
        assertEquals(listOf(emptyList<String>()), synchronized(again) { again.toList() })
        stopAgain()
        Unit
    }
}
