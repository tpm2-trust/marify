package app.kira

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kira.keys.KeystoreAead
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Data at rest under a key that needs an unlocked phone, and migration from the first version (TODO-SEC.md S4.3). */
@RunWith(AndroidJUnit4::class)
class KeystoreAeadTest {
    private val alias = "kira-aead-test"
    private val aad = "aad".toByteArray()
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanUp() {
        ks().deleteEntry(alias)
        ks().deleteEntry("$alias-v2")
    }

    /** What the first version wrote: iv ‖ ciphertext under `alias`, no marker. */
    private fun legacyBlob(plain: ByteArray): ByteArray {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        val key: SecretKey = gen.generateKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    @Test fun newDataUsesTheUnlockedDeviceKey() {
        val a = KeystoreAead(alias)
        val blob = a.encrypt("secret".toByteArray(), aad)
        assertEquals('K'.code.toByte(), blob[0])
        assertEquals('2'.code.toByte(), blob[1])
        val d = a.decrypt(blob, aad)
        assertFalse(d.legacy)
        assertArrayEquals("secret".toByteArray(), d.plain)
        assertTrue(ks().containsAlias("$alias-v2"))
    }

    @Test fun legacyDataIsReadAndFlaggedForRewrap() {
        val old = legacyBlob("old record".toByteArray())
        val a = KeystoreAead(alias)
        val d = a.decrypt(old, aad)
        assertTrue(d.legacy)
        assertArrayEquals("old record".toByteArray(), d.plain)
        val rewrapped = a.encrypt(d.plain, aad)
        assertFalse(a.decrypt(rewrapped, aad).legacy)
    }

    @Test fun wrongAadIsRejected() {
        val a = KeystoreAead(alias)
        val blob = a.encrypt("x".toByteArray(), aad)
        try {
            a.decrypt(blob, "other".toByteArray())
            fail("decrypted with the wrong associated data")
        } catch (_: Exception) {
        }
    }
}
