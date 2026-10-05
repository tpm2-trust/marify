package app.kira

import android.app.KeyguardManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kira.keys.AndroidAnchorKeys
import app.kira.keys.BiometricHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

/**
 * The real AndroidKeyStore anchor key (PROTOCOL-BLE.md §11.2, with screen-lock
 * credentials allowed): non-exportable, and every signature needs a fresh
 * unlock. Needs a secure lock screen: `adb shell locksettings set-pin 1234`.
 */
@RunWith(AndroidJUnit4::class)
class AnchorKeysTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = AndroidAnchorKeys(ctx, BiometricHost())
    private val id = "f0".repeat(16)

    @Before fun needsScreenLock() {
        assumeTrue("set a screen lock PIN first", ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure)
    }

    @After fun cleanUp() = keys.delete(id)

    private fun privateKey(): PrivateKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey("kira-anchor-$id", null) as PrivateKey
    }

    @Test fun keyPolicy() = runBlocking {
        val created = keys.create(id)
        assertEquals("P-256 SubjectPublicKeyInfo", 91, created.spkiDer.size)

        val priv = privateKey()
        assertNull("private key must not be exportable", priv.encoded)
        val info = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore").getKeySpec(priv, KeyInfo::class.java)
        assertTrue(info.isUserAuthenticationRequired)
        assertEquals("authentication for every use", 0, info.userAuthenticationValidityDurationSeconds)
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            info.userAuthenticationType,
        )
        // Keystore binds this to the biometrics enrolled when the key is made;
        // on a PIN-only phone there are none, and it reports false.
        val hasBiometric = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (hasBiometric) assertTrue(info.isInvalidatedByBiometricEnrollment)
        assertEquals(created.storage, keys.details(id)!!.storage)
    }

    @Test fun cannotSignWithoutAuthentication() = runBlocking {
        keys.create(id)
        val s = Signature.getInstance("SHA256withECDSA")
        try {
            // Without a CryptoObject authentication, the keystore refuses.
            s.initSign(privateKey())
            s.update(byteArrayOf(1, 2, 3))
            s.sign()
            fail("an unauthenticated signature was produced")
        } catch (_: Exception) {
        }
    }

    @Test fun recreateReplacesTheKey() = runBlocking {
        val a = keys.create(id).spkiDer
        val b = keys.create(id).spkiDer
        assertTrue(!a.contentEquals(b))
    }
}
