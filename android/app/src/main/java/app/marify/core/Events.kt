package app.marify.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One event from the core (PROTOCOL-BLE.md §10.3). The app reacts to events;
 * it never looks inside protocol messages.
 */
sealed interface Event {
    data class Sas(val sas: String) : Event
    /**
     * The machine passed the checks and waits for this phone's key. Carries
     * what the phone learnt about it, for the user to decide on binding:
     * the EK vendor verification and whether the checked PCRs cover the initrd.
     */
    data class NeedAnchorKey(
        val deviceId: String,
        val friendlyName: String,
        val ekVerifiedBy: String = "",
        val ekNote: String = "",
        val initrdCoverage: String = INITRD_UNKNOWN,
        val initrdPcrs: List<Int> = emptyList(),
        /** Goes into the anchor key's attestation; binds it to this enrolment. */
        val attestationChallenge: ByteArray? = null,
    ) : Event
    data class NeedSignature(val purpose: String, val verdictCode: Int) : Event
    data class Enrolled(val recordJson: String) : Event
    data class Hello(val deviceId: String, val friendlyName: String) : Event
    data class VerdictEvent(
        val verdict: Verdict,
        val needsDecision: Boolean,
        val eventlogAvailable: Boolean,
        /** The machine asks this phone to keep a new disk factor; kept when the verdict is accepted. */
        val factorOffered: Boolean = false,
    ) : Event
    data class Eventlog(val received: Long, val total: Long, val complete: Boolean) : Event
    data class ReceiptAck(val result: Int, val message: String) : Event
    /** What the machine made of the disk factor this phone released (PROTOCOL-BLE.md §7.3.9). */
    data class ReleaseAck(val status: Int, val message: String) : Event
    data class RecordUpdated(val recordJson: String) : Event
    data object Done : Event
    data class Error(val code: Int, val message: String) : Event

    /** An event type this app does not know yet (a newer core). Logged, not acted on. */
    data class Unknown(val type: String, val json: String) : Event

    companion object {
        const val PURPOSE_ENROL_ACCEPT = "enrol_accept"
        const val PURPOSE_RECEIPT = "receipt"

        const val INITRD_COVERED = "covered"
        const val INITRD_NOT_COVERED = "not_covered"
        const val INITRD_NONE = "no_initrd"
        const val INITRD_UNKNOWN = "unknown"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Event {
            val o = json.parseToJsonElement(text).jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
            fun long(k: String) = o[k]?.jsonPrimitive?.longOrNull ?: 0L
            fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
            // The record is opaque: keep the core's JSON object unchanged.
            fun record() = (o["record"] as? JsonObject
                ?: throw IllegalArgumentException("event without record")).toString()

            return when (val type = str("type")) {
                "sas" -> Sas(str("sas"))
                "need_anchor_key" -> NeedAnchorKey(
                    deviceId = str("device_id"),
                    friendlyName = str("friendly_name"),
                    ekVerifiedBy = str("ek_verified_by"),
                    ekNote = str("ek_note"),
                    initrdCoverage = str("initrd_coverage").ifEmpty { INITRD_UNKNOWN },
                    initrdPcrs = (o["initrd_pcrs"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
                    attestationChallenge = str("attestation_challenge").takeIf { it.isNotEmpty() }?.hexToByteArray(),
                )
                "need_signature" -> NeedSignature(str("purpose"), int("verdict_code"))
                "enrolled" -> Enrolled(record())
                "hello" -> Hello(str("device_id"), str("friendly_name"))
                "verdict" -> VerdictEvent(
                    verdict = json.decodeFromJsonElement(
                        Verdict.serializer(),
                        o["verdict"] ?: throw IllegalArgumentException("verdict event without verdict"),
                    ),
                    needsDecision = bool("needs_decision"),
                    eventlogAvailable = bool("eventlog_available"),
                    factorOffered = bool("factor_offered"),
                )
                "eventlog" -> Eventlog(long("received"), long("total"), bool("complete"))
                "receipt_ack" -> ReceiptAck(int("result"), str("message"))
                "release_ack" -> ReleaseAck(int("result"), str("message"))
                "record_updated" -> RecordUpdated(record())
                "done" -> Done
                "error" -> Error(int("code"), str("message"))
                else -> Unknown(type, text)
            }
        }
    }
}

/** The verdict object (PROTOCOL-BLE.md §10.4), displayed as the core produced it. */
@Serializable
data class Verdict(
    val ok: Boolean = false,
    val state: String,
    val profile: String = "",
    val reasons: List<Reason> = emptyList(),
    val warnings: List<Warning> = emptyList(),
    @SerialName("pcr_diff") val pcrDiff: List<PcrDiff> = emptyList(),
    @SerialName("diff_against") val diffAgainst: String = "",
    val explanation: String = "",
    @SerialName("reset_count") val resetCount: Long = 0,
    @SerialName("restart_count") val restartCount: Long = 0,
    @SerialName("clock_safe") val clockSafe: Boolean = false,
    @SerialName("firmware_version") val firmwareVersion: Long = 0,
    /** What the machine's TPM made of the boot challenge (PROTOCOL-BLE.md §7.6). */
    @SerialName("boot_key") val bootKey: String = BOOT_KEY_UNUSED,
    /** The code the machine's screen must show, "ABCD-EFGH"; only with [BOOT_KEY_PROVED]. */
    val code: String = "",
    /** TPM Name (hex) of the machine's signing key whose approval the TPM enforced; only with [BOOT_KEY_PROVED]. */
    @SerialName("signing_key") val signingKey: String = "",
) {
    companion object {
        const val MATCH = "match"
        const val CHANGED = "changed"
        const val FAILED = "failed"

        const val BOOT_KEY_PROVED = "proved"
        const val BOOT_KEY_REFUSED = "refused"
        const val BOOT_KEY_INVALID = "invalid"
        const val BOOT_KEY_FAILED = "failed"
        const val BOOT_KEY_UNUSED = "unused"

        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Verdict = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Reason(val code: String, val detail: String = "", val hard: Boolean = false)

@Serializable
data class Warning(val code: String, val detail: String = "")

@Serializable
data class PcrDiff(
    val index: Int,
    val expected: String = "",
    val actual: String = "",
    val description: String = "",
)

/** Display fields of a machine record, from Kiracore.recordSummary. */
@Serializable
data class RecordSummary(
    @SerialName("device_id") val deviceId: String,
    @SerialName("friendly_name") val friendlyName: String,
    @SerialName("enrolled_at") val enrolledAt: String,
    @SerialName("last_attested") val lastAttested: String? = null,
    val slot: Int = 0,
    val profiles: List<Profile> = emptyList(),
    /** TPM vendor whose certificate vouched for the EK at enrolment; empty if not verified. */
    @SerialName("ek_verified_by") val ekVerifiedBy: String = "",
    /** When this phone kept the machine's disk factor; null when it keeps none. */
    @SerialName("factor_kept_at") val factorKeptAt: String? = null,
) {
    @Serializable
    data class Profile(
        val name: String,
        @SerialName("valid_from") val validFrom: String = "",
        @SerialName("added_by") val addedBy: String = "",
        @SerialName("uses_left") val usesLeft: Long? = null,
        @SerialName("valid_until") val validUntil: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): RecordSummary = json.decodeFromString(serializer(), text)
    }
}

/** Decisions for [CoreSession.decide] (values equal Kiracore.Decision*). */
enum class Decision(val code: Long) {
    APPROVE_ONCE(1),
    APPROVE_REMEMBER(2),
    REJECT(3),
    /** A matching boot: the person has compared the code and wants the receipt signed. */
    CONTINUE(4),
}

/** Answers in a receipt_ack event (PROTOCOL-BLE.md §7.3.8). */
object ReceiptResult {
    const val ACCEPTED = 1
    const val BAD_SIGNATURE = 2
    const val BINDING_MISMATCH = 3
    const val REJECT_NOTED = 4
    const val MALFORMED = 5
}

