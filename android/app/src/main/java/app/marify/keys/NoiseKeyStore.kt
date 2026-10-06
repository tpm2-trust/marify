package app.marify.keys

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.marify.core.KiraCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

private val Context.identityStore by preferencesDataStore(name = "identity")

/** This installation's verifier identity (PROTOCOL-BLE.md §11.1). */
class VerifierIdentity(val noisePriv: ByteArray, val verifierId: String, val verifierName: String) {
    /** Config JSON for Kiracore.new*Session (§10.1). */
    fun configJson(): String = buildJsonObject {
        put("verifier_id", verifierId)
        put("verifier_name", verifierName)
        put("policy_id", "default")
        put("receipt_ttl_seconds", 300)
    }.toString()
}

/**
 * The static Noise key, created once per installation with
 * Kiracore.generateNoiseKey and kept encrypted under a Keystore AES key in
 * DataStore, next to the verifier id. Excluded from backups by the manifest.
 */
class NoiseKeyStore(private val context: Context) {
    private val aead = KeystoreAead("kira-noise-wrap")
    private val mutex = Mutex()
    @Volatile private var cached: VerifierIdentity? = null

    suspend fun identity(): VerifierIdentity = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.IO) { loadOrCreate() }.also { cached = it }
    }

    private suspend fun loadOrCreate(): VerifierIdentity {
        val prefs = context.identityStore.data.first()
        val ct = prefs[NOISE_CT]
        val id = prefs[VERIFIER_ID]
        val name = Build.MODEL ?: "Android"
        if (ct != null && id != null) {
            return VerifierIdentity(aead.decrypt(Base64.decode(ct, Base64.NO_WRAP), AAD), id, name)
        }
        val priv = KiraCore.generateNoiseKey()
        val newId = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val blob = aead.encrypt(priv, AAD)
        context.identityStore.edit {
            it[NOISE_CT] = Base64.encodeToString(blob, Base64.NO_WRAP)
            it[VERIFIER_ID] = newId
        }
        return VerifierIdentity(priv, newId, name)
    }

    companion object {
        private val NOISE_CT = stringPreferencesKey("noise_key_ct")
        private val VERIFIER_ID = stringPreferencesKey("verifier_id")
        private val AAD = "kira/noise-key/v1".toByteArray()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
