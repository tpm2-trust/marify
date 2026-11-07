package app.marify

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marify.ui.KeyboardLayoutView
import app.marify.ui.MarifyTheme
import app.marify.ui.OnboardingScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens no other test ends on, drawn for ScreenshotRule's pictures. The keyboard is its view
 * alone with a layout made up here: the screen around it keeps itself out of screenshots
 * (FLAG_SECURE), which a picture of the view does not touch.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    @get:Rule(order = 0) val rule = createComposeRule()
    @get:Rule(order = 1) val screen = ScreenshotRule(rule)

    @Test fun onboarding() {
        rule.setContent { MarifyTheme { OnboardingScreen(onReady = {}) } }
        rule.waitForIdle()
    }

    @Test fun keyboardLayout() {
        val keys = "abcdefghijklmnopqrstuvwxyz0123456789"
        rule.setContent { MarifyTheme { KeyboardLayoutView(keys, keys.reversed(), onClose = {}) } }
        rule.onNodeWithTag("keyboard_layout").assertIsDisplayed()
        // Portrait, the narrowest a key gets: every character the key types fits inside its key.
        for (k in keys) {
            val key = rule.onNodeWithTag("key_$k").getUnclippedBoundsInRoot()
            val types = rule.onNodeWithTag("types_$k", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$k: ${types} outside ${key}", types.top >= key.top && types.bottom <= key.bottom && types.left >= key.left && types.right <= key.right)
        }
    }
}
