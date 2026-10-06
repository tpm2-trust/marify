package app.marify.ble

/**
 * The INFO characteristic (PROTOCOL-BLE.md §3.2):
 * `u8 protocol ‖ u8 mode ‖ u16le schema ‖ u32le capabilities`.
 *
 * This is the GATT layer the app owns; nothing above it is parsed in Kotlin.
 */
data class InfoValue(val protocol: Int, val mode: Int, val schema: Int, val capabilities: Long) {

    sealed interface Compatibility {
        data object Ok : Compatibility
        /** The machine speaks a protocol or schema this app does not. */
        data class UpdateNeeded(val appTooOld: Boolean) : Compatibility
        /** The machine is in the other mode (enrolment vs. attestation). */
        data class WrongMode(val actual: Int) : Compatibility
    }

    fun check(expectedMode: Int, coreProtocol: Long, coreSchema: Long): Compatibility = when {
        protocol.toLong() != coreProtocol -> Compatibility.UpdateNeeded(appTooOld = protocol > coreProtocol)
        schema.toLong() > coreSchema -> Compatibility.UpdateNeeded(appTooOld = true)
        mode != expectedMode -> Compatibility.WrongMode(mode)
        else -> Compatibility.Ok
    }

    companion object {
        const val SIZE = 8
        const val MODE_ENROL = 1
        const val MODE_ATTEST = 2

        fun parse(b: ByteArray): InfoValue {
            require(b.size >= SIZE) { "INFO value has ${b.size} bytes, expected $SIZE" }
            fun u(i: Int) = b[i].toInt() and 0xff
            return InfoValue(
                protocol = u(0),
                mode = u(1),
                schema = u(2) or (u(3) shl 8),
                capabilities = (u(4).toLong() or (u(5).toLong() shl 8) or
                    (u(6).toLong() shl 16) or (u(7).toLong() shl 24)),
            )
        }
    }
}
