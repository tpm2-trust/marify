package app.kira.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.ContextCompat

/** What the app needs before it can run a session. */
object Requirements {
    val bluetoothPermissions: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Asked together with Bluetooth, but optional: the session notification. */
    val optionalPermissions: Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    fun hasBluetoothPermissions(ctx: Context) = bluetoothPermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    enum class UserAuth { READY, NO_SCREEN_LOCK, UNAVAILABLE }

    /** A screen lock (PIN, pattern, password) or a strong biometric can authorise signatures. */
    fun userAuth(ctx: Context): UserAuth =
        when (BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> UserAuth.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> UserAuth.NO_SCREEN_LOCK
            else -> UserAuth.UNAVAILABLE
        }

    /** A strong (class 3) biometric is enrolled. */
    fun hasStrongBiometric(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun allMet(ctx: Context) = hasBluetoothPermissions(ctx) && userAuth(ctx) == UserAuth.READY
}
