package app.kira.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kira.R
import app.kira.core.ReceiptResult
import app.kira.keys.KeyStorage
import app.kira.session.FailureKind
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The one table from protocol codes to localized text (PROTOCOL-BLE.md
 * §7.3.8, §7.3.14, §10.4). The core decides; these strings only explain.
 */
object Texts {
    val reasons: Map<String, Int> = mapOf(
        "malformed" to R.string.reason_malformed,
        "policy_invalid" to R.string.reason_policy_invalid,
        "device_mismatch" to R.string.reason_device_mismatch,
        "ak_mismatch" to R.string.reason_ak_mismatch,
        "bad_signature" to R.string.reason_bad_signature,
        "bad_magic" to R.string.reason_bad_magic,
        "bad_type" to R.string.reason_bad_type,
        "qd_mismatch" to R.string.reason_qd_mismatch,
        "reset_count_decreased" to R.string.reason_reset_count_decreased,
        "clock_unsafe" to R.string.reason_clock_unsafe,
        "firmware_changed" to R.string.reason_firmware_changed,
        "selection_mismatch" to R.string.reason_selection_mismatch,
        "pcr_values_invalid" to R.string.reason_pcr_values_invalid,
        "pcr_digest_mismatch" to R.string.reason_pcr_digest_mismatch,
        "secureboot_off" to R.string.reason_secureboot_off,
        "no_profile_match" to R.string.reason_no_profile_match,
    )

    val warnings: Map<String, Int> = mapOf(
        "reset_count_jump" to R.string.warning_reset_count_jump,
        "clock_unsafe" to R.string.warning_clock_unsafe,
        "firmware_changed" to R.string.warning_firmware_changed,
        "secureboot_off" to R.string.warning_secureboot_off,
    )

    val errorCodes: Map<Int, Int> = mapOf(
        1 to R.string.err_1, 2 to R.string.err_2, 3 to R.string.err_3, 4 to R.string.err_4,
        5 to R.string.err_5, 6 to R.string.err_6, 7 to R.string.err_7, 8 to R.string.err_8,
        9 to R.string.err_9, 10 to R.string.err_10, 11 to R.string.err_11,
    )

    val receiptResults: Map<Int, Int> = mapOf(
        ReceiptResult.ACCEPTED to R.string.ack_accepted,
        ReceiptResult.BAD_SIGNATURE to R.string.ack_bad_signature,
        ReceiptResult.BINDING_MISMATCH to R.string.ack_binding_mismatch,
        ReceiptResult.REJECT_NOTED to R.string.ack_reject_noted,
        ReceiptResult.MALFORMED to R.string.ack_malformed,
    )

    @StringRes
    fun failure(kind: FailureKind): Int = when (kind) {
        FailureKind.CONNECT -> R.string.fail_connect
        FailureKind.UPDATE_NEEDED -> R.string.fail_update_needed
        FailureKind.WRONG_MODE -> R.string.fail_wrong_mode
        FailureKind.LINK_LOST -> R.string.fail_link_lost
        FailureKind.CANCELLED -> R.string.fail_cancelled
        FailureKind.SAS_MISMATCH -> R.string.fail_sas_mismatch
        FailureKind.AUTH_CANCELLED -> R.string.fail_auth_cancelled
        FailureKind.KEY_INVALIDATED -> R.string.fail_key_invalidated
        FailureKind.KEY_ERROR -> R.string.fail_key_error
        FailureKind.STORAGE -> R.string.fail_storage
        FailureKind.PROTOCOL -> R.string.fail_protocol
    }

    @StringRes
    fun keyStorage(s: KeyStorage): Int = when (s) {
        KeyStorage.STRONGBOX -> R.string.key_strongbox
        KeyStorage.TEE -> R.string.key_tee
        KeyStorage.SOFTWARE -> R.string.key_software
    }

    /** Formats an RFC 3339 timestamp from the core in the phone's locale and zone. */
    fun dateTime(rfc3339: String?): String? = rfc3339?.let {
        runCatching {
            OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        }.getOrNull()
    }

    fun date(rfc3339: String?): String? = rfc3339?.let {
        runCatching {
            OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }.getOrNull()
    }
}

@Composable
fun reasonText(code: String): String =
    Texts.reasons[code]?.let { stringResource(it) } ?: stringResource(R.string.reason_unknown, code)

@Composable
fun warningText(code: String): String =
    Texts.warnings[code]?.let { stringResource(it) } ?: stringResource(R.string.warning_unknown, code)

@Composable
fun errorCodeText(code: Int): String =
    Texts.errorCodes[code]?.let { stringResource(it) } ?: stringResource(R.string.err_unknown, code)

@Composable
fun receiptResultText(result: Int): String =
    Texts.receiptResults[result]?.let { stringResource(it) } ?: stringResource(R.string.ack_unknown, result)
