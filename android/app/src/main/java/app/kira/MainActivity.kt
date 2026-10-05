package app.kira

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import app.kira.ui.KiraNavHost
import app.kira.ui.KiraTheme

/** A FragmentActivity so BiometricPrompt can attach to it. */
class MainActivity : FragmentActivity() {
    private val container get() = (application as KiraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KiraTheme {
                Surface(Modifier.fillMaxSize()) {
                    KiraNavHost(container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.biometricHost.attach(this)
    }

    override fun onStop() {
        container.biometricHost.detach(this)
        super.onStop()
    }
}
