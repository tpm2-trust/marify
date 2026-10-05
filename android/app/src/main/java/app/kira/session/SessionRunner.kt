package app.kira.session

import android.util.Log
import app.kira.ble.BluetoothOff
import app.kira.ble.InfoValue
import app.kira.ble.Link
import app.kira.ble.LinkEvent
import app.kira.ble.LinkException
import app.kira.ble.MachineNotReachable
import app.kira.core.CoreSession
import app.kira.core.Decision
import app.kira.core.Event
import app.kira.core.StepResult
import app.kira.keys.AnchorKeyProvider
import app.kira.keys.KeyInvalidated
import app.kira.keys.KeyStorage
import app.kira.keys.SignRequest
import app.kira.keys.SigningCancelled
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
        class FromLink(val event: LinkEvent) : Input
        class ConfirmSas(val match: Boolean) : Input
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

    // ---- user actions (any thread) ----

    fun confirmSas(match: Boolean) { inbox.trySend(Input.ConfirmSas(match)) }
    fun decide(decision: Decision, confirmName: String = "") { inbox.trySend(Input.Decide(decision, confirmName)) }
    fun requestEventlog() { inbox.trySend(Input.RequestEventlog) }
    fun cancel(reason: String = "cancelled") { inbox.trySend(Input.Abort(reason, FailureKind.CANCELLED)) }

    /** Runs the session to its end. Cancelling the caller aborts the session. */
    suspend fun run() = withContext(dispatcher) {
        link = try {
            connectWithRetry() ?: return@withContext
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
                launch {
                    for (ev in link.events) inbox.send(Input.FromLink(ev))
                    inbox.send(Input.FromLink(LinkEvent.Lost("connection closed")))
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
                if (!ended) end(Outcome.Failed(FailureKind.CANCELLED, 0, "cancelled"))
            }
            throw e
        } finally {
            withContext(NonCancellable) { link.disconnect() }
        }
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
        Log.i(TAG, "machine not reachable (attempt $attempt): ${e.message}")
        _state.update {
            it.copy(unreachable = Unreachable(attempt, e.message ?: "", clock() - start, bluetoothOff))
        }
        delay((retryIntervalMs - (clock() - began)).coerceAtLeast(0))
    }

    private suspend fun handle(input: Input, scope: CoroutineScope) {
        when (input) {
            is Input.FromLink -> when (val ev = input.event) {
                is LinkEvent.Notification -> process(core.onNotification(ev.value), scope)
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
        when (e) {
            is Event.Sas -> _state.update { it.copy(phase = Phase.CONFIRM_SAS, sas = e.sas) }
            is Event.NeedAnchorKey -> {
                deviceId = e.deviceId
                machineName = e.friendlyName
                _state.update { it.copy(machineName = e.friendlyName, deviceId = e.deviceId) }
                try {
                    val key = keys.create(e.deviceId)
                    keyStorage = key.storage
                    queue.add(core.provideAnchorKey(key.spkiDer))
                } catch (ex: Exception) {
                    Log.w(TAG, "anchor key creation failed", ex)
                    failureKind = FailureKind.KEY_ERROR
                    queue.add(core.abort("could not create the anchor key: ${ex.message}"))
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
            is Event.Unknown -> Log.i(TAG, "ignoring event ${e.type}")
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
        ended = true
        _state.update { it.copy(phase = Phase.FINISHED, outcome = outcome) }
        if (::link.isInitialized) link.disconnect()
    }

    companion object {
        private const val TAG = "KiraSession"
        const val RETRY_INTERVAL_MS = 5_000L
    }
}
