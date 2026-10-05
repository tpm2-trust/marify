package app.kira

import android.app.KeyguardManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kira.keys.AndroidAnchorKeys
import app.kira.core.KiraCore
import app.kira.keys.BiometricHost
import app.kira.ui.Requirements
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun biometricOnlyKeyRefusesThePin() = runBlocking {
        if (Requirements.hasStrongBiometric(ctx)) {
            keys.create(id, biometricOnly = true)
            val priv = privateKey()
            val info = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore").getKeySpec(priv, KeyInfo::class.java)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
            assertTrue(keys.details(id)!!.biometricOnly)
        } else {
            // Without an enrolled biometric such a key cannot exist; the bind
            // screen hides the option.
            try {
                keys.create(id, biometricOnly = true)
                fail("biometric-only key created without an enrolled biometric")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * A real Keystore attestation, made with the session challenge, read by
     * the very parser the machine uses at enrolment (kiracore). An emulator
     * attests in software, so it is not verified, but every field must parse.
     */
    @Test fun attestationIsReadableByTheMachine() = runBlocking {
        val challenge = ByteArray(32) { it.toByte() }
        val key = keys.create(id, attestationChallenge = challenge)
        val chain = key.attestation
        if (chain == null) {
            // The device has no attestation keys (some custom ROMs): the key
            // was still created, without one.
            return@runBlocking
        }
        val verdict = org.json.JSONObject(KiraCore.checkAnchorAttestation(chain, key.spkiDer, challenge))
        val problems = verdict.getJSONArray("problems").toString()
        assertFalse(problems, problems.contains("different key"))
        assertFalse(problems, problems.contains("not made for this enrolment"))
        assertFalse(problems, problems.contains("does not parse"))
        assertFalse(problems, problems.contains("no readable key description"))
        if (verdict.getString("security_level") == "Software") {
            // Emulator: the machine must refuse to call this secure hardware.
            assertTrue(problems, problems.contains("not in secure hardware"))
            assertFalse(verdict.getBoolean("verified"))
        } else {
            // A real phone: the hardware enforces what the app asked for.
            assertEquals("fingerprint/face or PIN", verdict.getString("unlock"))
            assertFalse(problems, problems.contains("not generated inside"))
            assertFalse(problems, problems.contains("does not enforce an unlock"))
        }
        // Another session's challenge is noticed.
        val other = org.json.JSONObject(KiraCore.checkAnchorAttestation(chain, key.spkiDer, ByteArray(32)))
        assertTrue(other.getJSONArray("problems").toString().contains("not made for this enrolment"))
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
