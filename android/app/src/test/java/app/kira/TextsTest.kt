package app.kira

import app.kira.ui.Texts
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every code from PROTOCOL-BLE.md has its own localized text. */
class TextsTest {
    private val reasonCodes = listOf(
        "malformed", "policy_invalid", "device_mismatch", "ak_mismatch", "bad_signature",
        "bad_magic", "bad_type", "qd_mismatch", "reset_count_decreased", "clock_unsafe",
        "firmware_changed", "selection_mismatch", "pcr_values_invalid", "pcr_digest_mismatch",
        "secureboot_off", "no_profile_match", "boot_proof_invalid",
    )
    private val warningCodes = listOf("reset_count_jump", "clock_unsafe", "firmware_changed", "secureboot_off")

    @Test fun reasons() = reasonCodes.forEach { assertTrue(it, it in Texts.reasons) }
    @Test fun warnings() = warningCodes.forEach { assertTrue(it, it in Texts.warnings) }
    @Test fun errorCodes() = (1..11).forEach { assertTrue("$it", it in Texts.errorCodes) }
    @Test fun receiptResults() = (1..5).forEach { assertTrue("$it", it in Texts.receiptResults) }
    @Test fun distinctReasonTexts() = assertTrue(Texts.reasons.values.toSet().size == reasonCodes.size)
}
