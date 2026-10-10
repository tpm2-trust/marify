package app.marify.core

import java.util.UUID

/**
 * Thin facade over the gomobile package `kiracore`. Every function here only
 * forwards to the Go core; nothing in the app re-implements its logic.
 */
object KiraCore {
    val SERVICE_UUID: UUID = UUID.fromString(kiracore.Kiracore.ServiceUUID)
    val RX_UUID: UUID = UUID.fromString(kiracore.Kiracore.RXCharUUID)
    val TX_UUID: UUID = UUID.fromString(kiracore.Kiracore.TXCharUUID)
    val INFO_UUID: UUID = UUID.fromString(kiracore.Kiracore.InfoUUID)

    val ADV_FLAG_ENROL: Long = kiracore.Kiracore.AdvFlagEnrol
    val ADV_FLAG_ATTEST: Long = kiracore.Kiracore.AdvFlagAttest

    val protocolVersion: Long get() = kiracore.Kiracore.protocolVersion()
    val schemaVersion: Long get() = kiracore.Kiracore.schemaVersion()

    /** The machine's verdict on an anchor key attestation (JSON); for display and tests. */
    fun checkAnchorAttestation(chain: ByteArray, spki: ByteArray, challenge: ByteArray): String =
        kiracore.Kiracore.checkAnchorAttestation(chain, spki, challenge)

    /** TPM vendors whose EK certificates the core in this app can verify. */
    fun ekVendors(): List<String> =
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(kiracore.Kiracore.ekVendors())

    /**
     * The phone's settings for a session. Its identity towards a machine is
     * no setting: the core makes a new one at every enrolment and keeps it
     * in that machine's record (PROTOCOL-BLE.md §11.1).
     */
    const val CONFIG_JSON = """{"policy_id":"default","receipt_ttl_seconds":300}"""

    fun newEnrolSession(configJson: String = CONFIG_JSON): CoreSession =
        GoCoreSession(kiracore.Kiracore.newEnrolSession(configJson))

    fun newAttestSession(recordJson: String, configJson: String = CONFIG_JSON): CoreSession =
        GoCoreSession(kiracore.Kiracore.newAttestSession(configJson, recordJson))

    /** Flags byte of the 13-byte service data, or -1. */
    fun advertisementFlags(serviceData: ByteArray): Long = kiracore.Kiracore.advertisementFlags(serviceData)

    fun matchAdvertisement(serviceData: ByteArray, recordJson: String): Boolean =
        kiracore.Kiracore.matchAdvertisement(serviceData, recordJson)

    fun recordSummary(recordJson: String): RecordSummary =
        RecordSummary.parse(kiracore.Kiracore.recordSummary(recordJson))
}
