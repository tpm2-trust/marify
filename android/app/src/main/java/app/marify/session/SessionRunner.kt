package app.marify.session

import android.util.Log
import app.marify.ble.BluetoothOff
import app.marify.ble.InfoValue
import app.marify.ble.Link
import app.marify.ble.LinkEvent
import app.marify.ble.LinkException
import app.marify.ble.MachineNotReachable
import app.marify.core.CoreSession
import app.marify.core.Decision
import app.marify.core.Event
import app.marify.core.StepResult
import app.marify.debugLog
import app.marify.keys.AnchorKeyProvider
import app.marify.keys.KeyInvalidated
import app.marify.keys.KeyStorage
import app.marify.keys.SignRequest
import app.marify.keys.SigningCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where enrolment and attestation results are persisted. */
interface RecordSink {
    suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage)
    suspend fun replace(recordJson: String)
}

/**
 * Drives one core [CoreSession] over one [Link] (PROTOCOL-BLE.md §10).
 *
 * Every input — a notification, a button press, a biometric result — goes
 * through one inbox and is handled by one coroutine on a single-threaded
 * dispatcher, in arrival order. The runner never decides trust: it writes the
 * core's fragments, shows the core's events and passes the user's answers back.
 */
class SessionRunner(
    private val mode: SessionMode,
    private val connect: suspend () -> Link,
    private val newSession: () -> CoreSession,
    private val keys: AnchorKeyProvider,
    private val records: RecordSink,
    private val coreProtocol: Long,
    private val coreSchema: Long,
    /** How often connecting is retried while the machine is not reachable. */
    private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private sealed interface Input {
        class FromLink(val event: LinkEvent, val from: Link) : Input
        class ConfirmSas(val match: Boolean) : Input
        class ConfirmBind(val bind: Boolean, val biometricOnly: Boolean) : Input
        class Decide(val decision: Decision, val confirmName: String) : Input
        data object RequestEventlog : Input
        class Abort(val reason: String, val kind: FailureKind) : Input
        class Signed(val der: ByteArray) : Input
        class SignFailed(val kind: FailureKind, val reason: String) : Input
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(SessionState(mode))
    val state: StateFlow<SessionState> get() = _state

    private lateinit var link: Link
    private lateinit var core: CoreSession
    private var ended = false
    private var failureKind: FailureKind? = null
    private var deviceId: String? = (mode as? SessionMode.Attest)?.deviceId
    private var machineName: String = (mode as? SessionMode.Attest)?.machineName ?: ""
    private var keyStorage = KeyStorage.SOFTWARE

    // Attestation only: whether the machine identified itself (Hello) in the
    // current connection. A machine that advertises as enrolled but ends the
    // session before that has refused this phone (TODO-SEC.md S1).
    private var helloSeen = false
    // Whether the input being handled came from the machine (a notification).
    private var handlingMachineInput = false
    private var refusals = 0
    private var retryAfterRefusal = false

    // ---- user actions (any thread) ----

    fun confirmSas(match: Boolean) { inbox.trySend(Input.ConfirmSas(match)) }
    fun confirmBind(bind: Boolean, biometricOnly: Boolean = false) {
        inbox.trySend(Input.ConfirmBind(bind, biometricOnly))
    }
    fun decide(decision: Decision, confirmName: String = "") { inbox.trySend(Input.Decide(decision, confirmName)) }
    fun requestEventlog() { inbox.trySend(Input.RequestEventlog) }
    fun cancel(reason: String = "cancelled") { inbox.trySend(Input.Abort(reason, FailureKind.CANCELLED)) }

    /** Runs the session to its end. Cancelling the caller aborts the session. */
    suspend fun run() = withContext(dispatcher) {
        debugLog(TAG) { "session starts: ${mode::class.simpleName}" }
        do {
            retryAfterRefusal = false
            if (!connectAndRun()) return@withContext
        } while (retryAfterRefusal)
    }

    /**
     * One connection: connect, check INFO, run the protocol until the session
     * ends. Returns false when connecting itself ended the session.
     */
    private suspend fun connectAndRun(): Boolean {
        link = try {
            connectWithRetry() ?: return false
        } catch (e: CancellationException) {
            withContext(NonCancellable) { end(Outcome.Failed(FailureKind.CANCELLED, 0, "cancelled")) }
            throw e
        }
        _state.update { it.copy(unreachable = null) }
        try {
            coroutineScope {
                val expected = if (mode is SessionMode.Enrol) InfoValue.MODE_ENROL else InfoValue.MODE_ATTEST
                when (val c = link.info.check(expected, coreProtocol, coreSchema)) {
                    InfoValue.Compatibility.Ok -> Unit
                    is InfoValue.Compatibility.UpdateNeeded -> {
                        val who = if (c.appTooOld) "this app" else "tpm2-kira on the machine"
                        end(Outcome.Failed(FailureKind.UPDATE_NEEDED, 0, "please update $who"))
                        return@coroutineScope
                    }
                    is InfoValue.Compatibility.WrongMode -> {
                        end(Outcome.Failed(FailureKind.WRONG_MODE, c.actual, "the machine is in the other mode"))
                        return@coroutineScope
                    }
                }
                core = newSession()
                core.setMaxFragment(link.maxFragment)
                val current = link
                launch {
                    for (ev in current.events) inbox.send(Input.FromLink(ev, current))
                    inbox.send(Input.FromLink(LinkEvent.Lost("connection closed"), current))
                }
                _state.update { it.copy(phase = Phase.SECURING) }
                process(core.start())
                while (!ended) handle(inbox.receive(), this)
                coroutineContext.cancelChildren()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (::core.isInitialized && !core.finished()) {
                    failureKind = FailureKind.CANCELLED
                    process(core.abort("cancelled"))
                }
                retryAfterRefusal = false
                if (!ended || _state.value.outcome == null) {
                    ended = false
                    end(Outcome.Failed(FailureKind.CANCELLED, 0, "cancelled"))
                }
            }
            throw e
        } finally {
            withContext(NonCancellable) { link.disconnect() }
        }
        if (retryAfterRefusal) {
            // Start over on a new connection and a new core session.
            ended = false
            failureKind = null
            helloSeen = false
            _state.update { it.copy(phase = Phase.CONNECTING, notice = null) }
        }
        return true
    }

    /**
     * Calls [connect] until a link comes up. While the machine is not
     * reachable, or Bluetooth is off, it keeps trying every [retryIntervalMs]
     * (counted from the start of the previous attempt) for as long as the
     * user waits; cancelling ends it. Any other failure ends the session at
     * once. Returns null when the session ended.
     */
    private suspend fun connectWithRetry(): Link? {
        val start = clock()
        var attempt = 0
        while (true) {
            attempt++
            val began = clock()
            try {
                return connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MachineNotReachable) {
                notReachable(attempt, e, start, began, bluetoothOff = false)
            } catch (e: BluetoothOff) {
                notReachable(attempt, e, start, began, bluetoothOff = true)
            } catch (e: Exception) {
                end(Outcome.Failed(FailureKind.CONNECT, 0, e.message ?: "connection failed"))
                return null
            }
        }
    }

    private suspend fun notReachable(attempt: Int, e: Exception, start: Long, began: Long, bluetoothOff: Boolean) {
        debugLog(TAG) { "machine not reachable (attempt $attempt): ${e.message}" }
        _state.update {
            it.copy(unreachable = Unreachable(attempt, e.message ?: "", clock() - start, bluetoothOff))
        }
        delay((retryIntervalMs - (clock() - began)).coerceAtLeast(0))
    }

    private suspend fun handle(input: Input, scope: CoroutineScope) {
        if (input !is Input.FromLink) debugLog(TAG) { "input ${input::class.simpleName} (phase ${_state.value.phase})" }
        when (input) {
            // Events of an earlier connection (before a retry) are stale.
            is Input.FromLink -> if (input.from !== link) Unit else when (val ev = input.event) {
                is LinkEvent.Notification -> {
                    handlingMachineInput = true
                    try {
                        process(core.onNotification(ev.value), scope)
                    } finally {
                        handlingMachineInput = false
                    }
                }
                is LinkEvent.Lost -> {
                    // Nothing can be written any more; end the core's session too.
                    core.abort("connection lost")
                    end(Outcome.Failed(FailureKind.LINK_LOST, 0, ev.reason))
                }
            }
            is Input.ConfirmSas -> {
                if (_state.value.phase != Phase.CONFIRM_SAS) return
                if (!input.match) failureKind = FailureKind.SAS_MISMATCH
                _state.update { it.copy(phase = Phase.ENROLLING) }
                process(core.confirmSas(input.match), scope)
            }
            is Input.ConfirmBind -> {
                if (_state.value.phase != Phase.CONFIRM_BIND) return
                if (!input.bind) {
                    failureKind = FailureKind.CANCELLED
                    process(core.abort("enrolment declined on the phone"), scope)
                    return
                }
                _state.update { it.copy(phase = Phase.ENROLLING) }
                val step = try {
                    val bind = _state.value.bind
                    val key = keys.create(deviceId ?: error("no device id"), input.biometricOnly, bind?.attestationChallenge)
                    keyStorage = key.storage
                    // The machine judges the attestation; the phone only passes it on.
                    core.provideAnchorKeyAttested(key.spkiDer, key.attestation ?: ByteArray(0))
                } catch (ex: Exception) {
                    Log.w(TAG, "anchor key creation failed", ex)
                    failureKind = FailureKind.KEY_ERROR
                    core.abort("could not create the anchor key: ${ex.message}")
                }
                process(step, scope)
            }
            is Input.Decide -> {
                if (_state.value.phase != Phase.DECIDING) return
                _state.update { it.copy(notice = null) }
                val step = core.decide(input.decision, input.confirmName)
                if (step.err.isEmpty()) {
                    _state.update {
                        it.copy(
                            decisionSent = true,
                            phase = if (input.decision == Decision.REJECT) Phase.DELIVERING else Phase.SIGNING,
                        )
                    }
                }
                process(step, scope)
            }
            Input.RequestEventlog -> {
                if (_state.value.eventlogRequested) return
                _state.update { it.copy(eventlogRequested = true) }
                process(core.requestEventlog(), scope)
            }
            is Input.Abort -> {
                failureKind = input.kind
                process(core.abort(input.reason), scope)
            }
            is Input.Signed -> {
                val step = core.provideSignature(input.der)
                if (step.err.isNotEmpty() && !core.finished()) {
                    // The core checked our signature and refused it; retrying
                    // with the same key cannot help.
                    write(step)
                    failureKind = FailureKind.KEY_ERROR
                    process(core.abort("signature rejected: ${step.err}"), scope)
                    return
                }
                if (mode is SessionMode.Attest) _state.update { it.copy(phase = Phase.DELIVERING) }
                process(step, scope)
            }
            is Input.SignFailed -> {
                failureKind = input.kind
                process(core.abort(input.reason), scope)
            }
        }
    }

    /**
     * Writes every fragment of [first] — always, even when its err is set —
     * then acts on its events; steps produced while doing so are processed
     * after it, in order.
     */
    private suspend fun process(first: StepResult, scope: CoroutineScope? = null) {
        val queue = ArrayDeque<StepResult>().apply { add(first) }
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            if (!write(step)) return
            for (e in step.events) onEvent(e, queue, scope)
            if (step.err.isNotEmpty() && !core.finished()) {
                _state.update { it.copy(notice = step.err) }
            }
        }
        if (core.finished() && !ended) {
            end(
                if (core.succeeded()) Outcome.Succeeded
                else Outcome.Failed(failureKind ?: FailureKind.PROTOCOL, 0, "session ended"),
            )
        }
    }

    /** Returns false if the link failed. */
    private suspend fun write(step: StepResult): Boolean {
        for (f in step.fragments) {
            try {
                link.write(f)
            } catch (e: LinkException) {
                if (!core.finished()) core.abort("connection lost")
                end(Outcome.Failed(FailureKind.LINK_LOST, 0, e.message ?: "write failed"))
                return false
            }
        }
        return true
    }

    private suspend fun onEvent(e: Event, queue: ArrayDeque<StepResult>, scope: CoroutineScope?) {
        debugLog(TAG) { "core event ${e::class.simpleName} (phase ${_state.value.phase})" }
        when (e) {
            is Event.Sas -> _state.update { it.copy(phase = Phase.CONFIRM_SAS, sas = e.sas) }
            is Event.NeedAnchorKey -> {
                // The user decides on binding with what is now known about
                // the machine (TODO-SEC.md S3, S5); the key is made after that.
                deviceId = e.deviceId
                machineName = e.friendlyName
                _state.update {
                    it.copy(machineName = e.friendlyName, deviceId = e.deviceId, bind = e, phase = Phase.CONFIRM_BIND)
                }
            }
            is Event.NeedSignature -> {
                _state.update { it.copy(phase = Phase.SIGNING) }
                val tbs = core.pendingTbs()
                val id = deviceId ?: error("signature requested before the machine identified itself")
                val req = SignRequest(machineName, e.purpose, e.verdictCode)
                val s = scope ?: error("signature requested outside the session loop")
                s.launch { inbox.send(sign(id, tbs, req)) }
            }
            is Event.Enrolled -> try {
                records.saveEnrolled(e.recordJson, keyStorage)
            } catch (ex: Exception) {
                Log.e(TAG, "storing the machine record failed", ex)
                failureKind = FailureKind.STORAGE
                _state.update { it.copy(notice = "could not store the machine: ${ex.message}") }
            }
            is Event.Hello -> {
                helloSeen = true
                machineName = e.friendlyName
                _state.update { it.copy(phase = Phase.VERIFYING, machineName = e.friendlyName) }
            }
            is Event.VerdictEvent -> _state.update {
                it.copy(verdict = e, phase = if (e.needsDecision) Phase.DECIDING else Phase.SIGNING)
            }
            is Event.Eventlog -> _state.update { it.copy(eventlog = e) }
            is Event.ReceiptAck -> _state.update { it.copy(receiptAck = e) }
            is Event.RecordUpdated -> try {
                records.replace(e.recordJson)
            } catch (ex: Exception) {
                Log.e(TAG, "updating the machine record failed", ex)
                _state.update { it.copy(notice = "could not update the stored machine: ${ex.message}") }
            }
            Event.Done -> end(
                if (failureKind == FailureKind.STORAGE) {
                    Outcome.Failed(FailureKind.STORAGE, 0, _state.value.notice ?: "storage failure")
                } else {
                    Outcome.Succeeded
                },
            )
            is Event.Error -> end(Outcome.Failed(failureKind ?: FailureKind.PROTOCOL, e.code, e.message))
            is Event.Unknown -> debugLog(TAG) { "ignoring event ${e.type}" }
        }
    }

    private suspend fun sign(deviceId: String, tbs: ByteArray, req: SignRequest): Input = try {
        Input.Signed(keys.sign(deviceId, tbs, req))
    } catch (e: CancellationException) {
        throw e
    } catch (_: SigningCancelled) {
        Input.SignFailed(FailureKind.AUTH_CANCELLED, "authentication cancelled")
    } catch (_: KeyInvalidated) {
        Input.SignFailed(FailureKind.KEY_INVALIDATED, "anchor key invalidated")
    } catch (e: Exception) {
        Log.w(TAG, "signing failed", e)
        Input.SignFailed(FailureKind.KEY_ERROR, "signing failed")
    }

    private suspend fun end(outcome: Outcome) {
        if (ended) return
        debugLog(TAG) { "session ends in phase ${_state.value.phase}: $outcome" }
        ended = true
        var final = outcome
        if (refusedByMachine(outcome)) {
            if (refusals++ == 0) {
                // Once could be the radio: try a fresh connection silently.
                debugLog(TAG) { "session ended before the machine identified itself; retrying once" }
                retryAfterRefusal = true
                if (::link.isInitialized) link.disconnect()
                return
            }
            final = Outcome.Failed(FailureKind.MACHINE_REFUSED, (outcome as Outcome.Failed).code, outcome.message)
        }
        _state.update { it.copy(phase = Phase.FINISHED, outcome = final) }
        if (::link.isInitialized) link.disconnect()
    }

    /**
     * An attestation that ended before the machine's Hello, for a reason other
     * than the user: the machine advertised as enrolled but did not accept
     * this phone's handshake. Decided by phase, never by message content.
     */
    private fun refusedByMachine(o: Outcome): Boolean =
        mode is SessionMode.Attest && !helloSeen && o is Outcome.Failed &&
            _state.value.phase == Phase.SECURING &&
            (o.kind == FailureKind.LINK_LOST || (o.kind == FailureKind.PROTOCOL && handlingMachineInput))

    companion object {
        private const val TAG = "MarifySession"
        const val RETRY_INTERVAL_MS = 5_000L
    }
}
