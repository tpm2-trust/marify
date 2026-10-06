package app.kira

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kira.ui.EnrolContent
import app.kira.ui.KiraTheme
import app.kira.ui.WaitingMachine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real hardware is the default on the enrol screen: machines found over
 * Bluetooth are listed without any switch, and the demo machine of a debug
 * build only appears after its switch is turned on.
 */
@RunWith(AndroidJUnit4::class)
class EnrolScreenTest {
    @get:Rule val rule = createComposeRule()

    private var enrolled: String? = null
    private var demoEnrolled = false

    private fun show(waiting: List<WaitingMachine>, debugBuild: Boolean, demoAvailable: Boolean) = rule.setContent {
        KiraTheme {
            var showDemo by remember { mutableStateOf(false) }
            EnrolContent(
                waiting = waiting, debugBuild = debugBuild, demoAvailable = demoAvailable,
                showDemo = showDemo, onShowDemo = { showDemo = it },
                onEnrol = { enrolled = it }, onEnrolDemo = { demoEnrolled = true },
            )
        }
    }

    @Test fun realMachinesAreListedWithoutASwitch() {
        show(listOf(WaitingMachine("AA:BB:CC:DD:EE:FF", -55)), debugBuild = true, demoAvailable = true)
        rule.onNodeWithTag("waiting_machine").assertIsDisplayed().performClick()
        assertEquals("AA:BB:CC:DD:EE:FF", enrolled)
        // A debug build says what it is, since it lists real machines.
        rule.onNodeWithTag("debug_build_warning").assertIsDisplayed()
    }

    @Test fun demoMachineAppearsOnlyAfterItsSwitch() {
        show(emptyList(), debugBuild = true, demoAvailable = true)
        rule.onNodeWithTag("demo_machine").assertDoesNotExist()
        rule.onNodeWithTag("show_demo_machine").assertIsDisplayed().performClick()
        rule.onNodeWithTag("demo_machine").assertIsDisplayed().performClick()
        assert(demoEnrolled)
    }

    @Test fun releaseBuildHasNeitherDemoNorWarning() {
        show(listOf(WaitingMachine("AA:BB:CC:DD:EE:FF", -55)), debugBuild = false, demoAvailable = false)
        rule.onNodeWithTag("waiting_machine").assertIsDisplayed()
        rule.onNodeWithTag("show_demo_machine").assertDoesNotExist()
        rule.onNodeWithTag("demo_machine").assertDoesNotExist()
        rule.onNodeWithTag("debug_build_warning").assertDoesNotExist()
    }
}
