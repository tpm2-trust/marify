package app.marify

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marify.core.KiraCore
import app.marify.ui.VersionLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VersionLineTest {
    @get:Rule(order = 0) val rule = createComposeRule()
    @get:Rule(order = 1) val screen = ScreenshotRule(rule)

    /** The app's commit, the core it linked, and what that core speaks. */
    @Test fun saysWhatThisBuildIs() {
        rule.setContent { ScreenFrame { VersionLine() } }
        rule.onNodeWithTag("version_line").assertIsDisplayed().assertTextEquals(
            "Marify ${BuildConfig.VERSION_NAME} · core ${BuildConfig.CORE_COMMIT} · " +
                "protocol ${KiraCore.protocolVersion} · schema ${KiraCore.schemaVersion}",
        )
    }

    /** Filled in at the build, from the real commits. */
    @Test fun theCommitsAreReal() {
        assertEquals("0.1.0+${BuildConfig.MARIFY_COMMIT}", BuildConfig.VERSION_NAME)
        assertTrue(BuildConfig.MARIFY_COMMIT, Regex("[0-9a-f]{7}(-dirty)?").matches(BuildConfig.MARIFY_COMMIT))
        assertTrue(BuildConfig.CORE_COMMIT, Regex("[0-9a-f]{7}").matches(BuildConfig.CORE_COMMIT))
    }
}
