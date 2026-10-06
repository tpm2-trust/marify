package app.kira

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

/** Data at rest under a key that needs an unlocked phone (TODO-SEC.md S4.3). */
@RunWith(AndroidJUnit4::class)
class KeystoreAeadTest {
    private val alias = "kira-aead-test"
    private val aad = "aad".toByteArray()
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanUp() {
        ks().deleteEntry("$alias-v2")
    }

    @Test fun roundTripUnderTheKeystoreKey() {
        val a = KeystoreAead(alias)
        val blob = a.encrypt("secret".toByteArray(), aad)
        assertEquals('K'.code.toByte(), blob[0])
        assertEquals('2'.code.toByte(), blob[1])
        assertArrayEquals("secret".toByteArray(), a.decrypt(blob, aad))
        assertTrue(ks().containsAlias("$alias-v2"))
        // The same plaintext never gives the same bytes twice.
        assertFalse(blob.contentEquals(a.encrypt("secret".toByteArray(), aad)))
    }

    /** One format: anything without the marker is not this app's data. */
    @Test fun dataInAnotherFormatIsRefused() {
        val a = KeystoreAead(alias)
        val blob = a.encrypt("secret".toByteArray(), aad)
        for (other in listOf(blob.copyOfRange(2, blob.size), ByteArray(0), "K2".toByteArray(), ByteArray(64))) {
            try {
                a.decrypt(other, aad)
                fail("decrypted ${other.size} bytes that are not in the app's format")
            } catch (_: Exception) {
            }
        }
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

    @Test fun alteredDataIsRejected() {
        val a = KeystoreAead(alias)
        val blob = a.encrypt("x".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        try {
            a.decrypt(blob, aad)
            fail("decrypted altered data")
        } catch (_: Exception) {
        }
    }
}
