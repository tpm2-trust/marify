package app.marify

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * The screen a passed test ends on, as a PNG: Class-method.png in the directory the Gradle plugin
 * hands the run (additionalTestOutputDir), which it copies to
 * build/outputs/connected_android_test_additional_output after the run. Every picture is the phone's
 * screen between its status bar and its navigation bar, so they all have one size and show the app
 * alone; the tests draw through ScreenFrame, as the app keeps its content inside the bars. Without that directory, or
 * for a test that shows nothing, no picture; a failure here fails no test. Inside the compose rule
 * (a higher order), so the screen is still there.
 */
class ScreenshotRule(private val compose: ComposeTestRule) : TestWatcher() {
    override fun succeeded(description: Description) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val name = "${description.testClass.simpleName}-${description.methodName}.png"
        runCatching {
            // A test that draws nothing (one that checks the build) has no screen to show.
            if (compose.onAllNodes(isRoot()).fetchSemanticsNodes().isEmpty()) return
            compose.waitForIdle()
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: error("no screenshot")
            // The app's area alone: the status bar and the navigation bar cut off, the same
            // rows in every picture (ScreenFrame keeps the content inside them).
            val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
            fun bar(name: String) = res.getIdentifier(name, "dimen", "android").let { if (it != 0) res.getDimensionPixelSize(it) else 0 }
            val top = bar("status_bar_height")
            val bottom = bar("navigation_bar_height")
            val bitmap = Bitmap.createBitmap(screen, 0, top, screen.width, screen.height - top - bottom)
            File(dir).mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.i("MarifyScreens", "no picture of $name: $it") }
    }
}
