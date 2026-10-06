package app.marify.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes RX writes: Android allows one GATT operation at a time, so the
 * next fragment goes out only after `onCharacteristicWrite` for the previous
 * one. A busy stack is retried with backoff; nothing is reordered or dropped.
 *
 * [submit] starts one write and returns a `BluetoothStatusCodes` value
 * ([SUCCESS], [BUSY], or another error).
 */
class WriteQueue(
    private val submit: (ByteArray) -> Int,
    private val maxAttempts: Int = 10,
    private val initialBackoffMs: Long = 5,
    private val completionTimeoutMs: Long = 10_000,
) {
    private val mutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null

    suspend fun write(value: ByteArray) = mutex.withLock {
        val done = CompletableDeferred<Int>()
        pending = done
        try {
            var backoff = initialBackoffMs
            var attempt = 1
            while (true) {
                val rc = submit(value)
                if (rc == SUCCESS) break
                if (rc != BUSY || attempt >= maxAttempts) {
                    throw LinkException("RX write failed (status $rc)")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(200)
                attempt++
            }
            val status = withTimeoutOrNull(completionTimeoutMs) { done.await() }
                ?: throw LinkException("RX write not acknowledged")
            if (status != GATT_SUCCESS) throw LinkException("RX write failed (GATT status $status)")
        } finally {
            pending = null
        }
    }

    /** Called from `onCharacteristicWrite`. */
    fun onWriteComplete(gattStatus: Int) {
        pending?.complete(gattStatus)
    }

    /** Fails a write in flight, e.g. on disconnect. */
    fun cancel() {
        pending?.complete(GATT_DISCONNECTED)
    }

    companion object {
        const val SUCCESS = 0                 // BluetoothStatusCodes.SUCCESS
        const val BUSY = 201                  // BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        const val GATT_SUCCESS = 0            // BluetoothGatt.GATT_SUCCESS
        private const val GATT_DISCONNECTED = -1
    }
}
