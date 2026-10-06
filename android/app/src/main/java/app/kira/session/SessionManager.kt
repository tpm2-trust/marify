package app.kira.session

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.kira.ble.Advertisement
import app.kira.ble.BluetoothOff
import app.kira.ble.BleScanner
import app.kira.ble.GattLink
import app.kira.ble.Link
import app.kira.ble.MachineNotReachable
import app.kira.core.KiraCore
import app.kira.data.Machine
import app.kira.data.MachineRepository
import app.kira.keys.AnchorKeyProvider
import app.kira.keys.KeyStorage
import app.kira.keys.NoiseKeyStore
import app.kira.service.AttestationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
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
        // The same enrolling machine keeps its address until enrolment ends.
        val finder = MachineFinder("the machine") { it.isEnrol && it.address == adv.address }
        start(SessionMode.Enrol, connectVia(finder, adv.device), onEnd = finder::stop)
    }

    fun startDemoEnrol() {
        retry = null
        start(SessionMode.Enrol, demo.enrolLink())
    }

    /**
     * Attests [machine]. With [adv] the first attempt goes to that device;
     * otherwise, and on every retry, the machine's next advertisement is
     * awaited (it uses a new address per boot).
     */
    fun startAttest(machine: Machine, adv: Advertisement? = null) {
        retry = { startAttest(machine) }
        val mode = SessionMode.Attest(machine.deviceId, machine.name, machine.summary.lastAttested)
        if (demo.owns(machine.recordJson)) {
            demoSession = true
            start(mode, demo.attestLink(), machine.recordJson)
            return
        }
        demoSession = false
        val finder = MachineFinder(machine.name) {
            it.isAttest && KiraCore.matchAdvertisement(it.serviceData, machine.recordJson)
        }
        start(mode, connectVia(finder, adv?.device), machine.recordJson, onEnd = finder::stop)
    }

    /** First [first] if given, then whatever [finder] sees, one attempt per call. */
    private fun connectVia(finder: MachineFinder, first: BluetoothDevice?): suspend () -> Link {
        var next = first
        return {
            val device = next ?: finder.find(SessionRunner.RETRY_INTERVAL_MS)
            next = null
            GattLink.connect(context, device)
        }
    }

    /**
     * Looks for one machine's advertisements with a single scan that keeps
     * running between connection attempts: Android throttles an app that
     * starts more than five scans in 30 seconds, which retrying every five
     * seconds would otherwise do. The scan stops before connecting.
     */
    private inner class MachineFinder(
        private val name: String,
        private val matches: (Advertisement) -> Boolean,
    ) {
        private val seen = MutableStateFlow<Advertisement?>(null)
        private var job: Job? = null
        @Volatile private var failure: Throwable? = null

        suspend fun find(windowMs: Long): BluetoothDevice {
            if (!scanner.isBluetoothOn()) throw BluetoothOff()
            if (job?.isActive != true) {
                failure = null
                job = scope.launch {
                    try {
                        scanner.scan().collect { if (matches(it)) seen.value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                    }
                }
            }
            val adv = withTimeoutOrNull(windowMs) { seen.filterNotNull().first() }
            if (adv == null) {
                (failure as? BluetoothOff)?.let { throw it }
                throw MachineNotReachable("$name was not seen nearby")
            }
            seen.value = null
            stop()
            return adv.device
        }

        fun stop() {
            job?.cancel()
            job = null
        }
    }

    val canRetry: Boolean get() = retry != null
    fun retry() { retry?.invoke() }

    /** Leaves the session: aborts it in the protocol, or stops connecting. */
    fun cancel() {
        val r = _active.value ?: return
        if (r.state.value.finished) return
        val s = r.state.value
        val j = job
        // While it is still connecting the runner reads no input, so an
        // abort request would wait unread: cancel the job instead.
        if (s.phase == Phase.CONNECTING || s.unreachable != null) {
            j?.cancel()
            return
        }
        r.cancel()
        // A stalled link must not keep the session alive either.
        scope.launch {
            delay(CANCEL_GRACE_MS)
            if (!r.state.value.finished && job === j) j?.cancel()
        }
    }

    /** Forgets a finished session. */
    fun dismiss() {
        val r = _active.value ?: return
        if (r.state.value.finished) _active.value = null
    }

    /** Whether the active attestation is against the demo machine. */
    @Volatile private var demoSession = false

    /**
     * What the machine's screen shows for the running session, where that
     * screen is simulated: the demo machine's boot code. Null for a real
     * machine, whose screen the person looks at.
     */
    fun demoScreenCode(): String? = if (demoSession) demo.screenCode().ifEmpty { null } else null

    private fun start(
        mode: SessionMode,
        connect: suspend () -> Link,
        recordJson: String? = null,
        onEnd: () -> Unit = {},
    ) {
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
            try {
                identity = noise.identity()
                runner.run()
            } finally {
                onEnd()
            }
        }
    }

    @Volatile private var identity: app.kira.keys.VerifierIdentity? = null

    private companion object {
        /** How long an abort may take before the session is cancelled outright. */
        const val CANCEL_GRACE_MS = 3_000L
    }
}
