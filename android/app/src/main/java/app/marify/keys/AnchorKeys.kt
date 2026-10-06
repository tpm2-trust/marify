package app.marify.keys

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import android.util.Log
import androidx.core.content.ContextCompat
import app.marify.BuildConfig
import app.marify.R
import app.marify.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Facts about a stored anchor key, read back from the keystore. */
data class AnchorKeyDetails(
    val storage: KeyStorage,
    val authEnforcedByHardware: Boolean,
    val attestationChainLength: Int,
    /** Only a strong biometric unlocks it, never the screen-lock PIN. */
    val biometricOnly: Boolean = false,
)

/** AndroidKeyStore anchor keys: StrongBox when present, TEE otherwise. */
class AndroidAnchorKeys(
    private val context: Context,
    private val host: BiometricHost,
) : AnchorKeyProvider {

    private fun alias(deviceIdHex: String) = "kira-anchor-$deviceIdHex"

    private fun keyStore() = KeyStore.getInstance(KeystoreAead.ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun create(
        deviceIdHex: String,
        biometricOnly: Boolean,
        attestationChallenge: ByteArray?,
    ): CreatedAnchorKey = withContext(Dispatchers.IO) {
        val alias = alias(deviceIdHex)
        keyStore().deleteEntry(alias)
        var attested = attestationChallenge != null
        val pair = generateWithFallback(alias, biometricOnly, attestationChallenge) { attested = false }
        val details = details(deviceIdHex) ?: error("anchor key vanished")
        if (!BuildConfig.DEBUG && (details.storage == KeyStorage.SOFTWARE || !details.authEnforcedByHardware)) {
            keyStore().deleteEntry(alias)
            throw IllegalStateException("this phone cannot keep the key in secure hardware")
        }
        val chain = if (attested) {
            keyStore().getCertificateChain(alias)
                ?.takeIf { it.size > 1 }
                ?.fold(ByteArray(0)) { acc, c -> acc + c.encoded }
        } else {
            null
        }
        CreatedAnchorKey(pair.public.encoded, details.storage, chain)
    }

    /**
     * StrongBox first, then the TEE. Each with attestation first: some ROMs
     * (e.g. LineageOS) have no attestation keys provisioned and refuse a key
     * with an attestation challenge. The key is then made without one; it is
     * protected the same way, only the machine cannot verify that.
     */
    private fun generateWithFallback(
        alias: String,
        biometricOnly: Boolean,
        challenge: ByteArray?,
        onNoAttestation: () -> Unit,
    ): java.security.KeyPair {
        for (strongBox in listOf(true, false)) {
            try {
                if (challenge != null) {
                    try {
                        return generate(alias, strongBox, biometricOnly, challenge)
                    } catch (e: StrongBoxUnavailableException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "key attestation unavailable (strongBox=$strongBox), creating the key without", e)
                        keyStore().deleteEntry(alias)
                        onNoAttestation()
                    }
                }
                return generate(alias, strongBox, biometricOnly, null)
            } catch (_: StrongBoxUnavailableException) {
                continue
            }
        }
        error("unreachable")
    }

    private fun generate(alias: String, strongBox: Boolean, biometricOnly: Boolean, challenge: ByteArray?): java.security.KeyPair {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Timeout 0: every single signature needs its own authentication,
            // by a strong biometric or by the screen lock's PIN, pattern or
            // password — so a phone that is merely unlocked cannot sign.
            .setUserAuthenticationParameters(
                0,
                if (biometricOnly) KeyProperties.AUTH_BIOMETRIC_STRONG
                else KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
        if (challenge != null) b.setAttestationChallenge(challenge)
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KeystoreAead.ANDROID_KEYSTORE)
        gen.initialize(b.build())
        return gen.generateKeyPair()
    }

    fun details(deviceIdHex: String): AnchorKeyDetails? {
        val ks = keyStore()
        val alias = alias(deviceIdHex)
        val priv = ks.getKey(alias, null) as? PrivateKey ?: return null
        val info = KeyFactory.getInstance(priv.algorithm, KeystoreAead.ANDROID_KEYSTORE)
            .getKeySpec(priv, KeyInfo::class.java)
        val storage = if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyStorage.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyStorage.TEE
                else -> KeyStorage.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyStorage.TEE else KeyStorage.SOFTWARE
        }
        return AnchorKeyDetails(
            storage = storage,
            authEnforcedByHardware = info.isUserAuthenticationRequired &&
                info.isUserAuthenticationRequirementEnforcedBySecureHardware,
            attestationChainLength = ks.getCertificateChain(alias)?.size ?: 0,
            biometricOnly = info.userAuthenticationType == KeyProperties.AUTH_BIOMETRIC_STRONG,
        )
    }

    override suspend fun sign(deviceIdHex: String, tbs: ByteArray, request: SignRequest): ByteArray {
        val priv = withContext(Dispatchers.IO) { keyStore().getKey(alias(deviceIdHex), null) as? PrivateKey }
            ?: throw KeyInvalidated("no anchor key for this machine on this phone")
        val sig = Signature.getInstance("SHA256withECDSA")
        try {
            sig.initSign(priv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidated(e.message ?: "anchor key invalidated")
        }
        // The prompt must offer exactly what the key accepts.
        val biometricOnly = withContext(Dispatchers.IO) { details(deviceIdHex)?.biometricOnly ?: false }
        val activity = host.activity.filterNotNull().first()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val s = result.cryptoObject?.signature
                            if (s == null) {
                                cont.resumeWithException(IllegalStateException("no signature object"))
                                return
                            }
                            val der = runCatching {
                                s.update(tbs)
                                s.sign()
                            }
                            der.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                        }

                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            val e = when (code) {
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_CANCELED,
                                -> SigningCancelled()
                                else -> SigningCancelled("authentication error: $msg")
                            }
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    },
                )
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
                prompt.authenticate(promptInfo(request, biometricOnly), BiometricPrompt.CryptoObject(sig))
            }
        }
    }

    private fun promptInfo(r: SignRequest, biometricOnly: Boolean): BiometricPrompt.PromptInfo {
        val title = when {
            r.purpose == Event.PURPOSE_ENROL_ACCEPT -> context.getString(R.string.sign_enrol, r.machineName)
            r.verdictCode == VERDICT_APPROVED -> context.getString(R.string.sign_approve_changed, r.machineName)
            else -> context.getString(R.string.sign_confirm_unchanged, r.machineName)
        }
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(context.getString(R.string.sign_subtitle))
            .setConfirmationRequired(true)
        if (biometricOnly) {
            b.setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText(context.getString(R.string.cancel))
        } else {
            // No negative button: with DEVICE_CREDENTIAL the system provides
            // "Use PIN" and cancel itself.
            b.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        }
        return b.build()
    }

    override fun delete(deviceIdHex: String) {
        keyStore().deleteEntry(alias(deviceIdHex))
    }

    private companion object {
        const val VERDICT_APPROVED = 2
        const val TAG = "MarifyKeys"
    }
}
