package app.kira.session

import app.kira.core.Event

/** What a session is for. */
sealed interface SessionMode {
    data object Enrol : SessionMode
    data class Attest(
        val deviceId: String,
        val machineName: String,
        /** last_attested of the record before this session, for "unchanged since". */
        val lastAttested: String? = null,
    ) : SessionMode
}

enum class Phase {
    CONNECTING,
    SECURING,       // Noise handshake
    CONFIRM_SAS,    // enrolment: compare the six digits
    ENROLLING,      // enrolment: offer, credential activation, binding
    VERIFYING,      // attestation: waiting for evidence
    DECIDING,       // changed / failed: the user decides
    SIGNING,        // biometric prompt for the anchor key
    DELIVERING,     // receipt sent, waiting for the machine's answer
    FINISHED,
}

/** Why a session ended without success; selects the message the user sees. */
enum class FailureKind {
    CONNECT,
    UPDATE_NEEDED,
    WRONG_MODE,
    LINK_LOST,
    CANCELLED,
    SAS_MISMATCH,
    AUTH_CANCELLED,
    KEY_INVALIDATED,
    KEY_ERROR,
    STORAGE,
    PROTOCOL,
}

sealed interface Outcome {
    data object Succeeded : Outcome
    data class Failed(val kind: FailureKind, val code: Int, val message: String) : Outcome
}

data class SessionState(
    val mode: SessionMode,
    val phase: Phase = Phase.CONNECTING,
    val machineName: String? = (mode as? SessionMode.Attest)?.machineName,
    val deviceId: String? = (mode as? SessionMode.Attest)?.deviceId,
    val sas: String? = null,
    val verdict: Event.VerdictEvent? = null,
    val decisionSent: Boolean = false,
    val eventlogRequested: Boolean = false,
    val eventlog: Event.Eventlog? = null,
    val receiptAck: Event.ReceiptAck? = null,
    /** A recoverable error from the core (the session continues). */
    val notice: String? = null,
    val outcome: Outcome? = null,
) {
    val finished: Boolean get() = outcome != null
}
