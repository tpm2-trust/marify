package app.marify

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marify.core.Decision
import app.marify.core.Event
import app.marify.ui.MarifyTheme
import app.marify.ui.VerdictContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The three verdict screens of PROTOCOL-BLE.md §10.4, from fixed core JSON. */
@RunWith(AndroidJUnit4::class)
class VerdictScreenTest {
    @get:Rule val rule = createComposeRule()

    private val decisions = mutableListOf<Pair<Decision, String>>()
    private var eventlogRequests = 0

    private fun show(json: String, lastAttested: String? = null) {
        val e = Event.parse(json) as Event.VerdictEvent
        rule.setContent {
            MarifyTheme {
                VerdictContent(
                    event = e,
                    machineName = "Thinkpad-X1",
                    lastAttested = lastAttested,
                    decisionEnabled = e.needsDecision,
                    eventlogRequested = false,
                    eventlog = null,
                    onDecide = { d, n -> decisions += d to n },
                    onRequestEventlog = { eventlogRequests++ },
                )
            }
        }
    }

    @Test fun match() {
        show(
            """{"type":"verdict","needs_decision":true,"verdict":{"ok":true,"state":"match","profile":"enrolment baseline",
               "reset_count":13,"restart_count":0,"clock_safe":true,"firmware_version":1,
               "boot_key":"proved","code":"K7QM-2XHD","signing_key":"000b0123456789abcdef0123456789abcdef"}}""",
            lastAttested = "2026-08-12T09:30:00Z",
        )
        rule.onNodeWithTag("verdict_match").assertIsDisplayed()
        rule.onNodeWithText("Thinkpad-X1 — unchanged since", substring = true).assertIsDisplayed()
        // A green check on the headline: the boot state is validly signed;
        // the code the machine's screen must show, before any button.
        rule.onNodeWithTag("signature_valid").assertIsDisplayed()
        rule.onNodeWithTag("signature_invalid").assertDoesNotExist()
        rule.onNodeWithTag("boot_key_valid").assertIsDisplayed()
        rule.onNodeWithTag("boot_code_value").assertTextEquals("K7QM-2XHD")
        rule.onNodeWithTag("boot_key_signer").assertIsDisplayed()
        rule.onNodeWithTag("approve_once").assertDoesNotExist()
        // Nothing is signed by itself: the person continues, or rejects.
        rule.onNodeWithText("The codes match — continue").assertIsDisplayed()
        rule.onNodeWithTag("continue").performClick()
        assertEquals(listOf(Decision.CONTINUE to ""), decisions)
    }

    @Test fun matchRejectedBecauseTheCodesDiffer() {
        show(
            """{"type":"verdict","needs_decision":true,"verdict":{"ok":true,"state":"match",
               "reset_count":13,"restart_count":0,"clock_safe":true,"firmware_version":1,
               "boot_key":"proved","code":"K7QM-2XHD"}}""",
        )
        rule.onNodeWithText("The codes differ — reject").assertIsDisplayed()
        rule.onNodeWithTag("reject").performClick()
        assertEquals(listOf(Decision.REJECT to ""), decisions)
    }

    /** A refused boot key: said so, no code, the boot can still be continued. */
    @Test fun matchWithARefusedBootKey() {
        show(
            """{"type":"verdict","needs_decision":true,"verdict":{"ok":true,"state":"match",
               "reset_count":13,"restart_count":0,"clock_safe":true,"firmware_version":1,"boot_key":"refused"}}""",
        )
        // A red cross on the headline: no valid signature, no code.
        rule.onNodeWithTag("signature_invalid").assertIsDisplayed()
        rule.onNodeWithTag("signature_valid").assertDoesNotExist()
        rule.onNodeWithTag("boot_key_invalid").assertIsDisplayed()
        rule.onNodeWithTag("boot_key_valid").assertDoesNotExist()
        rule.onNodeWithTag("boot_code_value").assertDoesNotExist()
        rule.onNodeWithText("Continue").assertIsDisplayed()
        rule.onNodeWithTag("continue").performClick()
        assertEquals(listOf(Decision.CONTINUE to ""), decisions)
    }

    /** The demo machine has no screen: the app says what it would show. */
    @Test fun demoMachineScreen() {
        val e = Event.parse(
            """{"type":"verdict","needs_decision":true,"verdict":{"ok":true,"state":"match",
               "reset_count":1,"restart_count":0,"clock_safe":true,"firmware_version":1,
               "boot_key":"proved","code":"K7QM-2XHD"}}""",
        ) as Event.VerdictEvent
        rule.setContent {
            MarifyTheme {
                VerdictContent(
                    event = e, machineName = "Demo", lastAttested = null, decisionEnabled = true,
                    eventlogRequested = false, eventlog = null, onDecide = { _, _ -> }, onRequestEventlog = {},
                    demoScreenCode = "K7QM-2XHD",
                )
            }
        }
        rule.onNodeWithTag("demo_screen_code").assertIsDisplayed()
        rule.onNodeWithText("The demo machine's screen shows: K7QM-2XHD").assertIsDisplayed()
    }

    /** The changed (orange) card with a green check: new values, validly signed as approved. */
    @Test fun changedButSigned() {
        show(
            """{"type":"verdict","needs_decision":true,"verdict":{
               "ok":false,"state":"changed",
               "reasons":[{"code":"no_profile_match","detail":"no profile","hard":false}],
               "pcr_diff":[{"index":11,"expected":"aaaa","actual":"bbbb","description":"Kernel"}],
               "explanation":"PCR 11 changed.",
               "reset_count":14,"restart_count":0,"clock_safe":true,"firmware_version":1,
               "boot_key":"proved","code":"K7QM-2XHD","signing_key":"000b0123456789abcdef"}}""",
        )
        rule.onNodeWithTag("verdict_changed").assertIsDisplayed()
        rule.onNodeWithTag("signature_valid").assertIsDisplayed()
        rule.onNodeWithText("New to this phone, but signed", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("boot_code_value").assertTextEquals("K7QM-2XHD")
        rule.onNodeWithTag("approve_remember").assertIsDisplayed()
    }

    @Test fun changed() {
        show(
            """{"type":"verdict","needs_decision":true,"eventlog_available":true,"verdict":{
               "ok":false,"state":"changed",
               "reasons":[{"code":"no_profile_match","detail":"no profile","hard":false}],
               "pcr_diff":[{"index":4,"expected":"aaaa","actual":"bbbb","description":"Boot Manager Code and Boot Attempts"}],
               "diff_against":"enrolment baseline",
               "explanation":"PCR 4 changed. The bootloader changed.",
               "reset_count":14,"restart_count":0,"clock_safe":true,"firmware_version":1}}""",
        )
        rule.onNodeWithTag("verdict_changed").assertIsDisplayed()
        rule.onNodeWithText("PCR 4 changed. The bootloader changed.").assertIsDisplayed()
        rule.onNodeWithText("• Boot Manager Code and Boot Attempts").assertIsDisplayed()
        // PCR hex only behind Details.
        rule.onNodeWithText("aaaa", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("details").performClick()
        rule.onNodeWithText("aaaa", substring = true).assertExists()

        rule.onNodeWithTag("show_eventlog").performClick()
        assertEquals(1, eventlogRequests)
        rule.onNodeWithTag("approve_remember").performClick()
        rule.onNodeWithTag("approve_once").performClick()
        rule.onNodeWithTag("reject").performClick()
        assertEquals(
            listOf(Decision.APPROVE_REMEMBER to "", Decision.APPROVE_ONCE to "", Decision.REJECT to ""),
            decisions,
        )
    }

    @Test fun failed() {
        show(
            """{"type":"verdict","needs_decision":true,"verdict":{
               "ok":false,"state":"failed",
               "reasons":[{"code":"reset_count_decreased","detail":"12 -> 0","hard":true},
                          {"code":"no_profile_match","detail":"x","hard":false}],
               "reset_count":0,"restart_count":0,"clock_safe":true,"firmware_version":1}}""",
        )
        rule.onNodeWithTag("verdict_failed").assertIsDisplayed()
        rule.onNodeWithText("Attestation failed").assertIsDisplayed()
        rule.onNodeWithText("• The TPM's boot counter went backwards", substring = true).assertIsDisplayed()
        // Only hard reasons are listed as failures.
        rule.onNodeWithText("• The boot chain differs", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("approve_once").assertDoesNotExist()

        // Approving is hidden in the overflow and needs the typed name.
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithTag("approve_anyway").performClick()
        rule.onNodeWithTag("confirm_name").performTextInput("Thinkpad-X1")
        rule.onNodeWithTag("confirm_approve").performClick()
        rule.onNodeWithTag("reject").performClick()
        assertEquals(listOf(Decision.APPROVE_ONCE to "Thinkpad-X1", Decision.REJECT to ""), decisions)
    }
}
