package app.marify

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import app.marify.ui.MarifyNavHost
import app.marify.ui.MarifyTheme

/** The header takes the status bar inset; the screens below must not add it again. */
@Composable
private fun headerInsets(): WindowInsets = WindowInsets.statusBars

/**
 * The app's name and logo on every screen; a debug build says so beside
 * them (it is debuggable and accepts anchor keys outside secure hardware,
 * TODO-SEC.md S4.2).
 */
@Composable
private fun MarifyHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("marify_header"),
    ) {
        Image(
            painterResource(R.drawable.marify_logo),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.app_name),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (BuildConfig.DEBUG) {
            Text(
                stringResource(R.string.debug_chip),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(Color(0xFFB3261E), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("debug_banner"),
            )
        }
    }
}

/** A FragmentActivity so BiometricPrompt can attach to it. */
class MainActivity : FragmentActivity() {
    private val container get() = (application as MarifyApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Another app must not draw over the code comparison or the approve
        // buttons and catch the taps (TODO-SEC.md S8.2).
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setContent {
            MarifyTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        MarifyHeader()
                        Box(Modifier.weight(1f).consumeWindowInsets(headerInsets())) {
                            MarifyNavHost(container)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.biometricHost.attach(this)
        // Records are only readable on an unlocked phone (TODO-SEC.md S4.3).
        container.scope.launch { container.machines.loadIfNeeded() }
    }

    override fun onStop() {
        container.biometricHost.detach(this)
        super.onStop()
    }
}
