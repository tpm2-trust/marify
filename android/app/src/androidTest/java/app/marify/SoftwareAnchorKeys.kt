package app.marify

import app.marify.keys.AnchorKeyProvider
import app.marify.keys.CreatedAnchorKey
import app.marify.keys.KeyStorage
import app.marify.keys.SignRequest
import app.marify.keys.SigningCancelled
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Software P-256 keys standing in for the biometry-bound keystore key, so
 * tests run without a finger on the sensor. Produces the same DER signatures
 * as SHA256withECDSA in AndroidKeyStore.
 */
class SoftwareAnchorKeys : AnchorKeyProvider {
    private val keys = mutableMapOf<String, KeyPair>()
    val requests = mutableListOf<SignRequest>()
    @Volatile var cancelNext = false

    override suspend fun create(deviceIdHex: String, biometricOnly: Boolean, attestationChallenge: ByteArray?): CreatedAnchorKey {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        synchronized(keys) { keys[deviceIdHex] = kp }
        return CreatedAnchorKey(kp.public.encoded, KeyStorage.SOFTWARE)
    }

    override suspend fun sign(deviceIdHex: String, tbs: ByteArray, request: SignRequest): ByteArray {
        synchronized(requests) { requests += request }
        if (cancelNext) {
            cancelNext = false
            throw SigningCancelled()
        }
        val kp = synchronized(keys) { keys.getValue(deviceIdHex) }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(tbs)
            sign()
        }
    }

    override fun delete(deviceIdHex: String) {
        synchronized(keys) { keys.remove(deviceIdHex) }
    }
}
