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
