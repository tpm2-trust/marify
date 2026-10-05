package app.kira.core

/**
 * The result of one call into the core: fragments to write to RX in order,
 * events to act on, and a non-empty [err] if the call failed. A failing step
 * still carries fragments (the Error message for the machine): write them.
 */
class StepResult(
    val fragments: List<ByteArray>,
    val events: List<Event>,
    val err: String,
) {
    companion object {
        val EMPTY = StepResult(emptyList(), emptyList(), "")
    }
}

/**
 * One protocol session (PROTOCOL-BLE.md §10.2). Implemented by
 * [GoCoreSession] over kiracore.Session; tests substitute a fake to check
 * the app's side of the contract.
 */
interface CoreSession {
    fun setMaxFragment(n: Int)
    fun start(): StepResult
    fun onNotification(value: ByteArray): StepResult
    fun confirmSas(match: Boolean): StepResult
    fun provideAnchorKey(spkiDer: ByteArray): StepResult
    /** [attestation]: the key's certificate chain, concatenated DER, leaf first; empty if none. */
    fun provideAnchorKeyAttested(spkiDer: ByteArray, attestation: ByteArray): StepResult
    fun pendingTbs(): ByteArray
    fun provideSignature(der: ByteArray): StepResult
    fun decide(decision: Decision, confirmName: String): StepResult
    fun requestEventlog(): StepResult
    fun eventlog(): ByteArray?
    fun abort(reason: String): StepResult
    fun finished(): Boolean
    fun succeeded(): Boolean
    fun recordJson(): String
}

class GoCoreSession(private val s: kiracore.Session) : CoreSession {
    override fun setMaxFragment(n: Int) = s.setMaxFragment(n.toLong())
    override fun start() = s.start().toResult()
    override fun onNotification(value: ByteArray) = s.onNotification(value).toResult()
    override fun confirmSas(match: Boolean) = s.confirmSAS(match).toResult()
    override fun provideAnchorKey(spkiDer: ByteArray) = s.provideAnchorKey(spkiDer).toResult()
    override fun provideAnchorKeyAttested(spkiDer: ByteArray, attestation: ByteArray) =
        s.provideAnchorKeyAttested(spkiDer, attestation).toResult()
    override fun pendingTbs(): ByteArray = s.pendingTBS() ?: ByteArray(0)
    override fun provideSignature(der: ByteArray) = s.provideSignature(der).toResult()
    override fun decide(decision: Decision, confirmName: String) =
        s.decide(decision.code, confirmName).toResult()
    override fun requestEventlog() = s.requestEventlog().toResult()
    override fun eventlog(): ByteArray? = s.eventlog()
    override fun abort(reason: String) = s.abort(reason).toResult()
    override fun finished() = s.finished()
    override fun succeeded() = s.succeeded()
    override fun recordJson(): String = s.recordJSON() ?: ""
}

internal fun kiracore.Step.toResult(): StepResult {
    val frags = (0 until fragmentCount()).map { fragment(it) ?: ByteArray(0) }
    val events = (0 until eventCount()).map { Event.parse(event(it)) }
    return StepResult(frags, events, err() ?: "")
}
