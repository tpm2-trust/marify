package app.marify.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key that needs no user
 * authentication, but an unlocked phone: encryption at rest for the Noise key
 * and machine records. Even code running as this app cannot decrypt them
 * while the phone is locked (TODO-SEC.md S4.3).
 *
 * Output is `"K2" ‖ iv(12) ‖ ciphertext ‖ tag`. Anything else is not data of
 * this app and is refused.
 */
class KeystoreAead(alias: String) {

    /** The key's name in the Android Keystore. */
    private val keyAlias = "$alias-v2"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return MAGIC + c.iv + ct
    }

    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > MAGIC.size + IV_SIZE && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "not data stored by this app"
        }
        return open(key(), blob, MAGIC.size, aad)
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
