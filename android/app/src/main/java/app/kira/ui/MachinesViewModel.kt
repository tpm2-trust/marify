package app.kira.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kira.AppContainer
import app.kira.ble.Advertisement
import app.kira.data.Machine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A machine the user enrolled, currently asking for attestation nearby. */
data class AskingMachine(val machine: Machine, val adv: Advertisement)

/**
 * Scans while the machine list is visible and labels ATTEST advertisements
 * with enrolled machines; unmatched ones are someone else's and are never
 * shown (PROTOCOL-BLE.md §2.3).
 */
class MachinesViewModel(private val c: AppContainer) : ViewModel() {
    val machines = c.machines.machines
    val active = c.sessions.active

    val asking: StateFlow<List<AskingMachine>> = askingFlow(
        scan = c.scanner.scan().catch { /* Bluetooth off or scan failed: show nothing */ },
        match = { adv -> if (adv.isAttest) c.machines.matchAdvertisement(adv.serviceData) else null },
        key = { it.deviceId },
    ).map { list -> list.map { (m, adv) -> AskingMachine(m, adv) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptyList())

    fun bluetoothOn() = c.scanner.isBluetoothOn()

    fun attest(a: AskingMachine) = c.sessions.startAttest(a.machine, a.adv)

}

/**
 * The machines currently asking, from the advertisements heard: each stays
 * listed for [staleMs] after it was last heard and goes when the machine has
 * stopped advertising (its check is over, or it is out of range). Every
 * collection starts from nothing: what was asking the last time the list
 * was looked at is not known to be asking now.
 */
internal fun <M> askingFlow(
    scan: Flow<Advertisement>,
    match: (Advertisement) -> M?,
    key: (M) -> String,
    now: () -> Long = { SystemClock.elapsedRealtime() },
    staleMs: Long = 10_000L,
    sweepMs: Long = 2_000L,
): Flow<List<Pair<M, Advertisement>>> = channelFlow {
    val seen = LinkedHashMap<String, Pair<M, Advertisement>>()
    val lock = Mutex()
    send(emptyList())
    launch {
        while (true) {
            delay(sweepMs)
            lock.withLock {
                if (seen.values.removeAll { now() - it.second.seenAtMs > staleMs }) send(seen.values.toList())
            }
        }
    }
    scan.collect { adv ->
        val m = match(adv) ?: return@collect
        lock.withLock {
            seen[key(m)] = m to adv
            send(seen.values.toList())
        }
    }
}
