package app.kira.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kira.AppContainer
import app.kira.ble.Advertisement
import app.kira.data.Machine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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

    val asking: StateFlow<List<AskingMachine>> = channelFlow {
        val seen = LinkedHashMap<String, AskingMachine>()
        val lock = Mutex()
        launch {
            while (true) {
                delay(2_000)
                lock.withLock {
                    val now = SystemClock.elapsedRealtime()
                    if (seen.values.removeAll { now - it.adv.seenAtMs > STALE_MS }) send(seen.values.toList())
                }
            }
        }
        c.scanner.scan()
            .catch { /* Bluetooth off or scan failed: show nothing */ }
            .collect { adv ->
                if (!adv.isAttest) return@collect
                val m = c.machines.matchAdvertisement(adv.serviceData) ?: return@collect
                lock.withLock {
                    seen[m.deviceId] = AskingMachine(m, adv)
                    send(seen.values.toList())
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptyList())

    fun bluetoothOn() = c.scanner.isBluetoothOn()

    fun attest(a: AskingMachine) = c.sessions.startAttest(a.machine, a.adv)

    private companion object {
        const val STALE_MS = 10_000L
    }
}
