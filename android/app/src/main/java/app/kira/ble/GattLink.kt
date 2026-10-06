package app.kira.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import app.kira.core.KiraCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One GATT connection to a tpm2-kira machine (PROTOCOL-BLE.md §3.3).
 *
 * No bonding, no encrypted characteristics: the link is an untrusted pipe and
 * the Noise session inside the core provides all security (§3.6). Pairing
 * requests are never initiated here; the machine refuses them anyway.
 *
 * Permissions (BLUETOOTH_CONNECT) are checked by the UI before a session
 * starts, hence the MissingPermission suppression.
 */
@SuppressLint("MissingPermission")
class GattLink private constructor(private val device: BluetoothDevice) : Link {

    private val eventsChannel = Channel<LinkEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<LinkEvent> get() = eventsChannel

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    /**
     * Fragments go out as Write Requests: each waits for the machine's Write
     * Response, which paces the phone to what the machine's controller can
     * take. Unacknowledged writes let Android send a whole record in one
     * burst, and an Intel 7265 lost the link (supervision timeout) on the
     * first such burst, a 3.9 KB EnrolAccept. A record costs about one
     * connection interval per fragment more, well under a second.
     */
    private var rxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var mtu = 23
    private lateinit var infoValue: InfoValue
    private val closed = AtomicBoolean(false)
    private val established = AtomicBoolean(false)

    override val maxFragment: Int get() = mtu - 3
    override val info: InfoValue get() = infoValue

    private val queue = WriteQueue(::submitWrite)

    // One pending GATT operation during setup; each completes exactly one of these.
    private var connected = CompletableDeferred<Int>()
    private var mtuChanged = CompletableDeferred<Int>()
    private var discovered = CompletableDeferred<Int>()
    private var infoRead = CompletableDeferred<ByteArray?>()
    private var cccdWritten = CompletableDeferred<Int>()
    private val disconnected = CompletableDeferred<Unit>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state $newState status $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // During setup this fails whichever step is waiting.
                    val failure = if (status == 0) STATUS_DISCONNECTED else status
                    connected.complete(failure)
                    mtuChanged.complete(-1)
                    discovered.complete(failure)
                    infoRead.complete(null)
                    cccdWritten.complete(failure)
                    queue.cancel()
                    if (established.get() && !closed.get()) {
                        eventsChannel.trySend(LinkEvent.Lost("disconnected (status $status)"))
                    }
                    eventsChannel.close()
                    disconnected.complete(Unit)
                    closeGatt()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuChanged.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            infoRead.complete(if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            infoRead.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            cccdWritten.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            queue.onWriteComplete(status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (c.uuid == KiraCore.TX_UUID) {
                Log.d(TAG, "rx fragment ${fragInfo(value)}")
                eventsChannel.trySend(LinkEvent.Notification(value.copyOf()))
            }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // The characteristic's value buffer is reused: copy it right here.
            if (c.uuid == KiraCore.TX_UUID) {
                val v = c.value?.copyOf() ?: ByteArray(0)
                Log.d(TAG, "rx fragment ${fragInfo(v)}")
                eventsChannel.trySend(LinkEvent.Notification(v))
            }
        }
    }

    private suspend fun setUp(context: Context) {
        @Suppress("DEPRECATION") // the replacement needs API 37; minSdk is 28
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw LinkException("connectGatt failed")
        gatt = g
        val st = withTimeoutOrNull(STEP_TIMEOUT_MS) { connected.await() } ?: STATUS_TIMEOUT
        if (st != BluetoothGatt.GATT_SUCCESS) throw ConnectFailed(st)

        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!g.requestMtu(MAX_MTU)) throw LinkException("MTU request failed")
        mtu = withTimeout(STEP_TIMEOUT_MS) { mtuChanged.await() }
        if (mtu < 23) throw LinkException("disconnected during MTU exchange")
        Log.d(TAG, "MTU $mtu")

        if (!g.discoverServices()) throw LinkException("service discovery failed to start")
        if (withTimeout(STEP_TIMEOUT_MS) { discovered.await() } != BluetoothGatt.GATT_SUCCESS) {
            throw LinkException("service discovery failed")
        }
        val service = g.getService(KiraCore.SERVICE_UUID)
            ?: throw LinkException("the device does not offer the tpm2-kira service")
        val rxChar = service.getCharacteristic(KiraCore.RX_UUID)
        val txChar = service.getCharacteristic(KiraCore.TX_UUID)
        val infoChar = service.getCharacteristic(KiraCore.INFO_UUID)
        if (rxChar == null || txChar == null || infoChar == null) {
            throw LinkException("the tpm2-kira service is incomplete")
        }
        rx = rxChar
        if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            rxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        if (!g.readCharacteristic(infoChar)) throw LinkException("INFO read failed to start")
        val raw = withTimeout(STEP_TIMEOUT_MS) { infoRead.await() }
            ?: throw LinkException("INFO read failed")
        infoValue = InfoValue.parse(raw)

        if (!g.setCharacteristicNotification(txChar, true)) throw LinkException("cannot enable notifications")
        val cccd = txChar.getDescriptor(CCCD_UUID) ?: throw LinkException("TX has no CCCD")
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!started) throw LinkException("CCCD write failed to start")
        if (withTimeout(STEP_TIMEOUT_MS) { cccdWritten.await() } != BluetoothGatt.GATT_SUCCESS) {
            throw LinkException("enabling notifications failed")
        }
        established.set(true)
    }

    private fun submitWrite(value: ByteArray): Int {
        val g = gatt ?: return STATUS_DISCONNECTED
        val c = rx ?: return STATUS_DISCONNECTED
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, rxWriteType)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = rxWriteType
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(c)) WriteQueue.SUCCESS else WriteQueue.BUSY
        }
    }

    override suspend fun write(fragment: ByteArray) {
        if (closed.get()) throw LinkException("link closed")
        Log.d(TAG, "tx fragment ${fragInfo(fragment)}")
        queue.write(fragment)
    }

    override suspend fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        val g = gatt ?: return
        g.disconnect()
        // Give the stack a moment to report the disconnect, then release it.
        withTimeoutOrNull(2_000) { disconnected.await() }
        closeGatt()
        eventsChannel.close()
    }

    private fun closeGatt() {
        synchronized(this) {
            gatt?.close()
            gatt = null
        }
    }

    private class ConnectFailed(val status: Int) : Exception("GATT connect failed (status $status)")

    companion object {
        private const val TAG = "KiraGatt"
        private const val MAX_MTU = 517
        private const val STEP_TIMEOUT_MS = 15_000L
        private const val STATUS_DISCONNECTED = -1
        private const val STATUS_TIMEOUT = -2
        private const val GATT_ERROR_133 = 133

        /** Fragment header (u16le total ‖ u8 flags ‖ u16le seq) for the debug log. */
        fun fragInfo(f: ByteArray): String {
            if (f.size < 5) return "of ${f.size} bytes (short)"
            val total = (f[0].toInt() and 0xff) or ((f[1].toInt() and 0xff) shl 8)
            val seq = (f[3].toInt() and 0xff) or ((f[4].toInt() and 0xff) shl 8)
            val start = if (f[2].toInt() and 1 != 0) " start" else ""
            return "seq $seq$start, record $total bytes, ${f.size} bytes"
        }
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Connects, negotiates the MTU, discovers the service, reads INFO and
         * enables TX notifications. Status 133 (a generic Android stack
         * failure) is retried once.
         */
        suspend fun connect(context: Context, device: BluetoothDevice): GattLink {
            var attempt = 0
            while (true) {
                val link = GattLink(device)
                try {
                    link.setUp(context.applicationContext)
                    return link
                } catch (e: TimeoutCancellationException) {
                    withContext(NonCancellable) { link.disconnect() }
                    throw LinkException("the machine did not answer in time", e)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { link.disconnect() }
                    throw e
                } catch (e: ConnectFailed) {
                    link.disconnect()
                    if (e.status == GATT_ERROR_133 && attempt++ == 0) {
                        delay(500)
                        continue
                    }
                    // Never connected: out of range, switched off, or gone.
                    throw MachineNotReachable("the Bluetooth connection could not be established", e)
                } catch (e: Exception) {
                    link.disconnect()
                    throw if (e is LinkException) e else LinkException(e.message ?: "connection failed", e)
                }
            }
        }
    }
}
