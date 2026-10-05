package app.kira.ble

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One connection to a machine: an untrusted, ordered byte pipe
 * (PROTOCOL-BLE.md §3). [GattLink] implements it over BLE; tests and the
 * debug demo machine implement it in memory.
 */
interface Link {
    /** Largest RX write: ATT MTU − 3. */
    val maxFragment: Int

    /** The INFO characteristic read after discovery. */
    val info: InfoValue

    /**
     * TX notifications in arrival order, never dropped, followed by at most
     * one [LinkEvent.Lost] when the connection ends.
     */
    val events: ReceiveChannel<LinkEvent>

    /** Writes one fragment to RX and returns once the stack has taken it. */
    suspend fun write(fragment: ByteArray)

    /** Ends the connection. Idempotent. */
    suspend fun disconnect()
}

sealed interface LinkEvent {
    class Notification(val value: ByteArray) : LinkEvent
    data class Lost(val reason: String) : LinkEvent
}

class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
