package app.marify.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.marify.MarifyApp
import app.marify.R

@Composable
fun OnboardingScreen(onReady: () -> Unit) {
    val ctx = LocalContext.current
    val scanner = (ctx.applicationContext as MarifyApp).container.scanner
    // Re-evaluated whenever the screen resumes (e.g. back from Settings).
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val permsOk = remember(tick) { Requirements.hasBluetoothPermissions(ctx) }
    val btOn = remember(tick) { permsOk && scanner.isBluetoothOn() }
    val auth = remember(tick) { Requirements.userAuth(ctx) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)

        Step(
            title = stringResource(R.string.onboarding_permissions),
            body = stringResource(
                if (Build.VERSION.SDK_INT >= 31) R.string.onboarding_permissions_rationale
                else R.string.onboarding_permissions_rationale_legacy,
            ),
            done = permsOk,
        ) {
            Button(onClick = {
                permLauncher.launch(Requirements.bluetoothPermissions + Requirements.optionalPermissions)
            }) { Text(stringResource(R.string.onboarding_permissions)) }
            OutlinedButton(onClick = { ctx.startActivity(appSettings(ctx.packageName)) }) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
        }

        if (permsOk) {
            Step(stringResource(R.string.onboarding_bluetooth_on), null, btOn) {
                Button(onClick = {
                    @Suppress("MissingPermission")
                    enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }) { Text(stringResource(R.string.onboarding_bluetooth_on)) }
            }
        }

        Step(
            title = stringResource(R.string.onboarding_screen_lock),
            body = stringResource(
                if (auth == Requirements.UserAuth.UNAVAILABLE) R.string.onboarding_screen_lock_unavailable
                else R.string.onboarding_screen_lock_rationale,
            ),
            done = auth == Requirements.UserAuth.READY,
        ) {
            if (auth == Requirements.UserAuth.NO_SCREEN_LOCK) {
                Button(onClick = { ctx.startActivity(screenLockSetup()) }) {
                    Text(stringResource(R.string.onboarding_open_settings))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onReady,
            enabled = permsOk && auth == Requirements.UserAuth.READY,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onboarding_continue)) }
    }
}

@Composable
private fun Step(title: String, body: String?, done: Boolean, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.granted), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            if (!done) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

private fun appSettings(pkg: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null))

/** Settings flow that sets up a screen lock (and offers a biometric on top). */
private fun screenLockSetup(): Intent =
    Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
