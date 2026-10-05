package app.kira.session

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.kira.ble.Advertisement
import app.kira.ble.BleScanner
import app.kira.ble.GattLink
import app.kira.ble.Link
import app.kira.ble.LinkException
import app.kira.core.KiraCore
import app.kira.data.Machine
import app.kira.data.MachineRepository
import app.kira.keys.AnchorKeyProvider
import app.kira.keys.KeyStorage
import app.kira.keys.NoiseKeyStore
import app.kira.service.AttestationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the one active session of the app. Sessions run in the application
 * scope, kept alive by [AttestationService] while they last, so rotating the
 * screen or briefly leaving the app does not cut the machine off.
 */
class SessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scanner: BleScanner,
    private val repo: MachineRepository,
    private val noise: NoiseKeyStore,
    private val keys: AnchorKeyProvider,
    val demo: DemoSupport,
) {
    private val _active = MutableStateFlow<SessionRunner?>(null)
    val active: StateFlow<SessionRunner?> get() = _active
    private var job: Job? = null
    private var retry: (() -> Unit)? = null

    private val sink = object : RecordSink {
        override suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage) =
            repo.saveEnrolled(recordJson, keyStorage)
        override suspend fun replace(recordJson: String) = repo.replace(recordJson)
    }

    fun startEnrol(adv: Advertisement) {
        retry = null
        start(SessionMode.Enrol, connect = { connectBle(adv.device) })
    }

    fun startDemoEnrol() {
        retry = null
        start(SessionMode.Enrol, demo.enrolLink())
    }

    /**
     * Attests [machine]. With [adv] the device is known; without, the
     * machine's next advertisement is awaited (it uses a new address per boot).
     */
    fun startAttest(machine: Machine, adv: Advertisement? = null) {
        retry = { startAttest(machine) }
        val connect: suspend () -> Link = when {
            demo.owns(machine.recordJson) -> demo.attestLink()
            adv != null -> ({ connectBle(adv.device) })
            else -> ({ connectBle(findMachine(machine)) })
        }
        start(SessionMode.Attest(machine.deviceId, machine.name, machine.summary.lastAttested), connect, machine.recordJson)
    }

    val canRetry: Boolean get() = retry != null
    fun retry() { retry?.invoke() }

    /** Leaves the session: aborts it in the protocol, or stops connecting. */
    fun cancel() {
        val r = _active.value ?: return
        if (r.state.value.finished) return
        if (r.state.value.phase == Phase.CONNECTING) job?.cancel() else r.cancel()
    }

    /** Forgets a finished session. */
    fun dismiss() {
        val r = _active.value ?: return
        if (r.state.value.finished) _active.value = null
    }

    private fun start(mode: SessionMode, connect: suspend () -> Link, recordJson: String? = null) {
        val previous = _active.value
        if (previous != null && !previous.state.value.finished) return
        val runner = SessionRunner(
            mode = mode,
            connect = connect,
            newSession = {
                // The identity is loaded before connecting; see below.
                val id = identity ?: error("verifier identity not loaded")
                if (recordJson == null) KiraCore.newEnrolSession(id.configJson(), id.noisePriv)
                else KiraCore.newAttestSession(id.configJson(), id.noisePriv, recordJson)
            },
            keys = keys,
            records = sink,
            coreProtocol = KiraCore.protocolVersion,
            coreSchema = KiraCore.schemaVersion,
        )
        _active.value = runner
        ContextCompat.startForegroundService(context, Intent(context, AttestationService::class.java))
        job = scope.launch {
            identity = noise.identity()
            runner.run()
        }
    }

    @Volatile private var identity: app.kira.keys.VerifierIdentity? = null

    private suspend fun connectBle(device: BluetoothDevice): Link = GattLink.connect(context, device)

    private suspend fun findMachine(machine: Machine): BluetoothDevice =
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            scanner.scan().first { it.isAttest && KiraCore.matchAdvertisement(it.serviceData, machine.recordJson) }
        }?.device ?: throw LinkException("${machine.name} is not advertising nearby")

    private companion object {
        const val SEARCH_TIMEOUT_MS = 30_000L
    }
}
