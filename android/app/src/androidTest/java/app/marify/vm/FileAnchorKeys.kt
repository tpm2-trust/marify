package app.marify.vm

import app.marify.keys.AnchorKeyProvider
import app.marify.keys.CreatedAnchorKey
import app.marify.keys.KeyStorage
import app.marify.keys.SignRequest
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Software P-256 anchor keys kept in files, so the stages of [RealMachineTest] - each its own
 * instrumentation run - sign with the key the enrolment made. A test fixture: the app's own keys are
 * in the keystore, behind biometrics.
 */
class FileAnchorKeys(private val dir: File) : AnchorKeyProvider {
    private fun file(deviceIdHex: String) = File(dir, "anchor-$deviceIdHex.pk8")

    override suspend fun create(deviceIdHex: String, biometricOnly: Boolean, attestationChallenge: ByteArray?): CreatedAnchorKey {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        dir.mkdirs()
        file(deviceIdHex).writeBytes(kp.private.encoded)
        return CreatedAnchorKey(kp.public.encoded, KeyStorage.SOFTWARE)
    }

    override suspend fun sign(deviceIdHex: String, tbs: ByteArray, request: SignRequest): ByteArray {
        val key: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(file(deviceIdHex).readBytes()))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(tbs)
            sign()
        }
    }

    override fun delete(deviceIdHex: String) {
        file(deviceIdHex).delete()
    }
}
