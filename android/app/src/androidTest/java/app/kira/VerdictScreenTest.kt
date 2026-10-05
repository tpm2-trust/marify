package app.kira

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kira.core.Decision
import app.kira.core.Event
import app.kira.ui.KiraTheme
import app.kira.ui.VerdictContent
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
            KiraTheme {
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
            """{"type":"verdict","verdict":{"ok":true,"state":"match","profile":"enrolment baseline",
               "reset_count":13,"restart_count":0,"clock_safe":true,"firmware_version":1}}""",
            lastAttested = "2026-08-12T09:30:00Z",
        )
        rule.onNodeWithTag("verdict_match").assertIsDisplayed()
        rule.onNodeWithText("Thinkpad-X1 — unchanged since", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("approve_once").assertDoesNotExist()
        rule.onNodeWithTag("reject").assertDoesNotExist()
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
