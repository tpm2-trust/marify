package app.kira.keys

/** Where an anchor key lives (shown in machine details). */
enum class KeyStorage { STRONGBOX, TEE, SOFTWARE }

class CreatedAnchorKey(val spkiDer: ByteArray, val storage: KeyStorage)

/** What a signature is for; selects the biometric prompt's text. */
data class SignRequest(val machineName: String, val purpose: String, val verdictCode: Int)

/** The user dismissed the authentication prompt (biometric or PIN). */
class SigningCancelled(message: String = "authentication cancelled") : Exception(message)

/** The key was invalidated (new biometric enrolled, or the screen lock removed): the machine must be enrolled again. */
class KeyInvalidated(message: String = "anchor key invalidated") : Exception(message)

/**
 * The per-machine anchor key (PROTOCOL-BLE.md §11.2): ECDSA P-256,
 * non-exportable, hardware-backed, a fresh biometric authentication for every
 * signature. Tests substitute a software implementation.
 */
interface AnchorKeyProvider {
    /**
     * Creates (or replaces) the key for this machine; returns SPKI DER (91
     * bytes). With [biometricOnly] the screen-lock PIN does not unlock it.
     */
    suspend fun create(deviceIdHex: String, biometricOnly: Boolean = false): CreatedAnchorKey

    /** DER ECDSA over SHA-256([tbs]) — the platform hashes, no raw r‖s. */
    suspend fun sign(deviceIdHex: String, tbs: ByteArray, request: SignRequest): ByteArray

    fun delete(deviceIdHex: String)
}
