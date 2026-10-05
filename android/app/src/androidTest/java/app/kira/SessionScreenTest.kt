package app.kira

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kira.session.FailureKind
import app.kira.session.Outcome
import app.kira.session.Phase
import app.kira.session.SessionMode
import app.kira.session.SessionState
import app.kira.ui.KiraTheme
import app.kira.ui.SessionContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: SessionState) = rule.setContent {
        KiraTheme {
            SessionContent(
                state = state, canRetry = true,
                onConfirmSas = {}, onDecide = { _, _ -> }, onRequestEventlog = {}, onRetry = {}, onDone = {},
            )
        }
    }

    @Test fun refusalIsARedWarningWithoutRetry() {
        show(
            SessionState(
                mode = SessionMode.Attest("a0".repeat(16), "Thinkpad-X1"),
                phase = Phase.FINISHED,
                outcome = Outcome.Failed(FailureKind.MACHINE_REFUSED, 0, "machine closed the connection"),
            ),
        )
        rule.onNodeWithTag("refused").assertIsDisplayed()
        rule.onNodeWithText("Thinkpad-X1 did not accept this phone").assertIsDisplayed()
        rule.onNodeWithText("Do not type your passphrase", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Try again").assertDoesNotExist()
    }

    private fun enrolState(b: app.kira.core.Event.NeedAnchorKey) = SessionState(
        mode = SessionMode.Enrol, phase = Phase.CONFIRM_BIND, machineName = b.friendlyName, bind = b,
    )

    @Test fun bindShowsVerifiedTPM() {
        show(enrolState(app.kira.core.Event.NeedAnchorKey("a0", "box", ekVerifiedBy = "Intel PTT", initrdCoverage = "covered", initrdPcrs = listOf(9))))
        rule.onNodeWithText("Bind box to this phone?").assertIsDisplayed()
        rule.onNodeWithTag("tpm_verified").assertIsDisplayed()
        rule.onNodeWithText("TPM verified as genuine: Intel PTT").assertIsDisplayed()
        rule.onNodeWithText("include the initrd (PCR 9)", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("initrd_not_covered").assertDoesNotExist()
    }

    @Test fun bindWarnsAboutUnverifiedTPMAndUncoveredInitrd() {
        show(enrolState(app.kira.core.Event.NeedAnchorKey("a0", "box", ekNote = "no certificate", initrdCoverage = "not_covered", initrdPcrs = listOf(9))))
        rule.onNodeWithTag("tpm_unverified").assertIsDisplayed()
        rule.onNodeWithText("no certificate", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("initrd_not_covered").assertIsDisplayed()
        rule.onNodeWithText("measured into PCR 9", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("bind").assertIsDisplayed()
        rule.onNodeWithTag("bind_cancel").assertIsDisplayed()
    }

    @Test fun unreachableDoesNotClaimTheMachineIsFine() {
        show(
            SessionState(
                mode = SessionMode.Attest("a0".repeat(16), "Thinkpad-X1"),
                unreachable = app.kira.session.Unreachable(3, "Thinkpad-X1 was not seen nearby", 15_000),
            ),
        )
        rule.onNodeWithText("Thinkpad-X1 is not reachable").assertIsDisplayed()
        rule.onNodeWithText("a missing result is not a good result", substring = true).assertIsDisplayed()
        rule.onNodeWithText("not a problem with the machine", substring = true).assertDoesNotExist()
    }
}
