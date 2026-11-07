package app.marify

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.marify.ui.MarifyTheme

/**
 * A screen of the app as a test draws it: the theme, a surface the size of the window, and the
 * content inside the system bars, as the app's own screens keep it - so every test's last screen
 * (ScreenshotRule) looks like the app's, at one size.
 */
@Composable
fun ScreenFrame(content: @Composable () -> Unit) {
    MarifyTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
        }
    }
}
