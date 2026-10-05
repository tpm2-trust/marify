package app.kira

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import app.kira.ui.KiraNavHost
import app.kira.ui.KiraTheme

/** The banner takes the status bar inset; the screens below must not add it again. */
@Composable
private fun debugInsets(): WindowInsets =
    if (BuildConfig.DEBUG) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0)

/**
 * Debug builds are debuggable and accept anchor keys outside secure hardware:
 * say so on every screen (TODO-SEC.md S4.2).
 */
@Composable
private fun DebugBanner() {
    Text(
        stringResource(R.string.debug_banner),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFB3261E))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(vertical = 4.dp)
            .testTag("debug_banner"),
    )
}

/** A FragmentActivity so BiometricPrompt can attach to it. */
class MainActivity : FragmentActivity() {
    private val container get() = (application as KiraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KiraTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (BuildConfig.DEBUG) DebugBanner()
                        Box(Modifier.weight(1f).consumeWindowInsets(debugInsets())) {
                            KiraNavHost(container)
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
