package app.kira.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import app.kira.core.KiraCore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One tpm2-kira advertisement: the device and its 13-byte service data. */
class Advertisement(
    val device: BluetoothDevice,
    val serviceData: ByteArray,
    val flags: Long,
    val rssi: Int,
    val seenAtMs: Long,
) {
    val isEnrol: Boolean get() = flags >= 0 && flags and KiraCore.ADV_FLAG_ENROL != 0L
    val isAttest: Boolean get() = flags >= 0 && flags and KiraCore.ADV_FLAG_ATTEST != 0L
    val address: String get() = device.address
}

/**
 * Active scan, filtered by the tpm2-kira service UUID only (PROTOCOL-BLE.md
 * §13.4). Which results are shown is decided by the caller with
 * [KiraCore.matchAdvertisement] (§2.3).
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    fun isBluetoothOn(): Boolean = adapter()?.isEnabled == true

    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    fun scan(): Flow<Advertisement> = callbackFlow {
        val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(LinkException("Bluetooth is off"))
            return@callbackFlow
        }
        val service = ParcelUuid(KiraCore.SERVICE_UUID)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(service) ?: return
                trySend(
                    Advertisement(
                        device = result.device,
                        serviceData = data,
                        flags = KiraCore.advertisementFlags(data),
                        rssi = result.rssi,
                        seenAtMs = SystemClock.elapsedRealtime(),
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(LinkException("scan failed ($errorCode)"))
            }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(service).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        awaitClose {
            runCatching { scanner.stopScan(cb) }
        }
    }
}
