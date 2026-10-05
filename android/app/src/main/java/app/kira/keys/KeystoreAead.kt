package app.kira.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key that needs no user
 * authentication, but an unlocked phone: encryption at rest for the Noise key
 * and machine records. Even code running as this app cannot decrypt them
 * while the phone is locked (TODO-SEC.md S4.3).
 *
 * Output is `"K2" ‖ iv(12) ‖ ciphertext ‖ tag`. Blobs without the marker were
 * written by the first version's key (`alias` itself, usable while locked);
 * [decrypt] still reads them and says so, and callers rewrite them.
 */
class KeystoreAead(private val alias: String) {

    /** Plaintext, and whether it came from the legacy key and should be rewritten. */
    class Decrypted(val plain: ByteArray, val legacy: Boolean)

    private val currentAlias = "$alias-v2"

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun currentKey(): SecretKey {
        (keyStore().getKey(currentAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(currentAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun legacyKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, currentKey())
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return MAGIC + c.iv + ct
    }

    fun decrypt(blob: ByteArray, aad: ByteArray): Decrypted {
        if (blob.size > MAGIC.size + IV_SIZE && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            try {
                return Decrypted(open(currentKey(), blob, MAGIC.size, aad), legacy = false)
            } catch (e: AEADBadTagException) {
                // One legacy IV in 65536 starts with the marker: try that key.
                legacyKey() ?: throw e
            }
        }
        val legacy = legacyKey() ?: throw IllegalStateException("no key for this data")
        return Decrypted(open(legacy, blob, 0, aad), legacy = true)
    }

    private fun open(key: SecretKey, blob: ByteArray, off: Int, aad: ByteArray): ByteArray {
        require(blob.size > off + IV_SIZE) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, off, IV_SIZE))
        c.updateAAD(aad)
        return c.doFinal(blob, off + IV_SIZE, blob.size - off - IV_SIZE)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private val MAGIC = byteArrayOf('K'.code.toByte(), '2'.code.toByte())
    }
}
