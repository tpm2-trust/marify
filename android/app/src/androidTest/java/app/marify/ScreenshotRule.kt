package app.marify

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * The screen a passed test ends on, as a PNG: Class-method.png in the directory the Gradle plugin
 * hands the run (additionalTestOutputDir), which it copies to
 * build/outputs/connected_android_test_additional_output after the run. Without that directory, or
 * for a test that shows nothing, no picture; a failure here fails no test. Inside the compose rule
 * (a higher order), so the screen is still there.
 */
class ScreenshotRule(private val compose: ComposeTestRule) : TestWatcher() {
    override fun succeeded(description: Description) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val name = "${description.testClass.simpleName}-${description.methodName}.png"
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.i("MarifyScreens", "no picture of $name: $it") }
    }
}
