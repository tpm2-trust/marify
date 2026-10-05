package app.kira.data

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import app.kira.core.KiraCore
import app.kira.core.RecordSummary
import app.kira.keys.KeyStorage
import app.kira.keys.KeystoreAead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One enrolled machine: the core's record, verbatim, plus what the app knows about its key. */
data class Machine(
    val recordJson: String,
    val summary: RecordSummary,
    val keyStorage: KeyStorage,
    /** Reserved for salt release (PLAN-FACTORRELEASE.md): an opaque per-machine blob. */
    val factorBlob: String? = null,
) {
    val deviceId: String get() = summary.deviceId
    val name: String get() = summary.friendlyName
}

/**
 * Machine records (PROTOCOL-BLE.md §10.5): one file per machine under
 * files/machines, AES-GCM under a Keystore key, never backed up. The record
 * JSON is stored exactly as the core produced it and never edited; display
 * fields come from Kiracore.recordSummary.
 */
class MachineRepository(context: Context) {
    private val dir = File(context.noBackupFilesDir, "machines").apply { mkdirs() }
    private val aead = KeystoreAead("kira-records")
    private val mutex = Mutex()
    private val _machines = MutableStateFlow<List<Machine>>(emptyList())
    val machines: StateFlow<List<Machine>> get() = _machines

    @Serializable
    private data class Envelope(
        val record: String,
        @SerialName("key_storage") val keyStorage: KeyStorage,
        @SerialName("factor_blob") val factorBlob: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var loaded = false

    /**
     * Reads the stored machines once. The records cannot be decrypted while
     * the phone is locked; then nothing is marked loaded and the next call
     * (MainActivity.onStart) tries again.
     */
    suspend fun loadIfNeeded() = mutex.withLock {
        if (loaded) return@withLock
        withContext(Dispatchers.IO) {
            var locked = false
            val list = (dir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(SUFFIX) }
                .mapNotNull { f ->
                    runCatching { read(f) }
                        .onFailure {
                            if (it is UserNotAuthenticatedException) locked = true
                            Log.w(TAG, "cannot read ${f.name}", it)
                        }
                        .getOrNull()
                }
                .sortedBy { it.name.lowercase() }
            if (!locked) {
                _machines.value = list
                loaded = true
            }
        }
    }

    fun get(deviceId: String): Machine? = _machines.value.firstOrNull { it.deviceId == deviceId }

    /** The enrolled machine that produced this advertisement, if any (§2.3). */
    fun matchAdvertisement(serviceData: ByteArray): Machine? =
        _machines.value.firstOrNull { KiraCore.matchAdvertisement(serviceData, it.recordJson) }

    /** Stores the record from an `enrolled` event. */
    suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage) =
        put(Envelope(recordJson, keyStorage))

    /** Replaces the record after `record_updated`, keeping what the app knows about the key. */
    suspend fun replace(recordJson: String) {
        val id = KiraCore.recordSummary(recordJson).deviceId
        val old = get(id)
        put(Envelope(recordJson, old?.keyStorage ?: KeyStorage.SOFTWARE, old?.factorBlob))
    }

    suspend fun delete(deviceId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file(deviceId).delete()
            _machines.value = _machines.value.filterNot { it.deviceId == deviceId }
        }
    }

    private suspend fun put(env: Envelope) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val summary = KiraCore.recordSummary(env.record)
            val f = file(summary.deviceId)
            val tmp = File(dir, f.name + ".tmp")
            val plain = json.encodeToString(Envelope.serializer(), env).toByteArray()
            tmp.writeBytes(aead.encrypt(plain, aad(summary.deviceId)))
            if (!tmp.renameTo(f)) {
                tmp.delete()
                error("cannot store machine record")
            }
            val m = Machine(env.record, summary, env.keyStorage, env.factorBlob)
            _machines.value = (_machines.value.filterNot { it.deviceId == m.deviceId } + m)
                .sortedBy { it.name.lowercase() }
        }
    }

    private fun read(f: File): Machine {
        val id = f.name.removeSuffix(SUFFIX)
        val d = aead.decrypt(f.readBytes(), aad(id))
        if (d.legacy) {
            // Re-encrypt under the key that needs an unlocked phone.
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(aead.encrypt(d.plain, aad(id)))
            if (!tmp.renameTo(f)) tmp.delete()
        }
        val env = json.decodeFromString(Envelope.serializer(), String(d.plain))
        val summary = KiraCore.recordSummary(env.record)
        check(summary.deviceId == id) { "record does not belong to its file" }
        return Machine(env.record, summary, env.keyStorage, env.factorBlob)
    }

    private fun file(deviceId: String): File {
        require(deviceId.matches(Regex("[0-9a-f]{32}"))) { "bad device id" }
        return File(dir, deviceId + SUFFIX)
    }

    private fun aad(deviceId: String) = "kira/machine/v1/$deviceId".toByteArray()

    private companion object {
        const val TAG = "KiraMachines"
        const val SUFFIX = ".machine"
    }
}
