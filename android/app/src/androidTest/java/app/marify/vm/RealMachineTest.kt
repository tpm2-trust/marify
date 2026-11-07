package app.marify.vm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marify.ble.BleScanner
import app.marify.ble.GattLink
import app.marify.core.Decision
import app.marify.core.KiraCore
import app.marify.core.Verdict
import app.marify.keys.KeyStorage
import app.marify.session.Outcome
import app.marify.session.Phase
import app.marify.session.RecordSink
import app.marify.session.SessionMode
import app.marify.session.SessionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The app's real Bluetooth LE code, session and core against a real tpm2-kira machine: a test VM
 * on the emulator's simulated Bluetooth (netsim), driven by tpm2-kira's tools/phone-vm-test.py.
 * Isolated from the regular suite: it runs only when the instrumentation argument `vm` names a
 * stage, one instrumentation run each:
 *
 *   reset    forget the record and the anchor key of an earlier run
 *   scan     wait for any advertisement of tpm2-kira's service: the radio path, before anything
 *   enrol    enrol the machine that advertises for enrolment (`tpm2-kira phone enrol`)
 *   attest   attest the enrolled machine: the booted system (a phone salt's hand-over) or a boot
 *
 * The person is played: the bind and the SAS confirmed (the script compares the SAS with the
 * machine's), a matching boot continued, a changed one approved and remembered when `approve`
 * is "changed". What the script needs is logged under the tag KiraVm: sas=, state=, code=,
 * layout_keys=, layout=, outcome=.
 */
@RunWith(AndroidJUnit4::class)
@RunByPhoneVmTest
class RealMachineTest {

    private lateinit var stage: String
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "vmtest")
    private val recordFile = File(dir, "record.json")
    private val keys = FileAnchorKeys(dir)

    private val sink = object : RecordSink {
        override suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage) = save(recordJson)
        override suspend fun replace(recordJson: String) = save(recordJson)
    }

    private fun save(recordJson: String) {
        dir.mkdirs()
        recordFile.writeText(recordJson)
    }

    private fun say(line: String) {
        Log.i(TAG, line)
    }

    @Before fun stage() {
        val s = args.getString("vm")
        assumeTrue("no stage: run by tools/phone-vm-test.py (tpm2-kira)", s != null)
        stage = s!!
    }

    @Test fun run() {
        when (stage) {
            "reset" -> {
                dir.deleteRecursively()
                say("outcome=reset")
            }
            "scan" -> runBlocking {
                val adv = withTimeout(timeoutMs()) { BleScanner(context).scan().first() }
                say("seen=${adv.address} flags=${adv.flags}")
                say("outcome=seen")
            }
            "rawscan" -> rawScan()
            "enrol" -> enrol()
            "attest" -> attest()
            else -> throw IllegalArgumentException("no stage $stage")
        }
    }

    /** Every advertisement, unfiltered, for a while: what the controller passes up at all. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun rawScan() {
        val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        val seen = mutableSetOf<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (seen.add(result.device.address)) say("raw=${result.device.address} uuids=${result.scanRecord?.serviceUuids} data=${result.scanRecord?.serviceData?.keys} legacy=${result.isLegacy}")
            }
        }
        adapter.bluetoothLeScanner!!.startScan(null, android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(false).build(), cb)
        Thread.sleep(timeoutMs())
        adapter.bluetoothLeScanner!!.stopScan(cb)
        say("outcome=raw ${seen.size}")
    }

    private fun timeoutMs() = (args.getString("timeout")?.toLongOrNull() ?: 300L) * 1000

    private fun session(mode: SessionMode, record: String, matches: (app.marify.ble.Advertisement) -> Boolean) = runBlocking {
        val scanner = BleScanner(context)
        val approveChanged = args.getString("approve") == "changed"
        val runner = SessionRunner(
            mode = mode,
            connect = {
                val adv = scanner.scan().first { matches(it) }
                say("found=${adv.address}")
                GattLink.connect(context, adv.device)
            },
            newSession = { if (mode is SessionMode.Enrol) KiraCore.newEnrolSession() else KiraCore.newAttestSession(record) },
            keys = keys,
            records = sink,
            coreProtocol = KiraCore.protocolVersion,
            coreSchema = KiraCore.schemaVersion,
        )
        var sasSaid = false
        var decided = false
        var layoutSaid = false
        val person = CoroutineScope(Dispatchers.Default).launch {
            runner.state.collect { st ->
                if (st.phase == Phase.CONFIRM_BIND) runner.confirmBind(true)
                if (st.phase == Phase.CONFIRM_SAS && !sasSaid) {
                    sasSaid = true
                    say("sas=${st.sas}")
                    runner.confirmSas(true)
                }
                val v = st.verdict?.verdict
                if (st.phase == Phase.DECIDING && !st.decisionSent && v != null && !decided) {
                    decided = true
                    say("state=${v.state}")
                    say("code=${v.code}")
                    when {
                        v.state == Verdict.MATCH -> runner.decide(Decision.CONTINUE)
                        v.state == Verdict.CHANGED && approveChanged -> runner.decide(Decision.APPROVE_REMEMBER)
                        else -> runner.decide(Decision.REJECT)
                    }
                }
                st.keyboardLayout?.takeIf { !layoutSaid }?.let {
                    layoutSaid = true
                    say("layout_keys=${it.keys}")
                    say("layout=${it.layout}")
                }
            }
        }
        withTimeout(timeoutMs()) { runner.run() }
        person.cancel()
        val end = runner.state.value
        say("outcome=${end.outcome}")
        end
    }

    private fun enrol() {
        dir.deleteRecursively()
        val end = session(SessionMode.Enrol, "") { it.isEnrol }
        assertEquals(end.notice.orEmpty(), Outcome.Succeeded, end.outcome)
        say("name=${KiraCore.recordSummary(recordFile.readText()).friendlyName}")
    }

    private fun attest() {
        val record = recordFile.readText()
        val summary = KiraCore.recordSummary(record)
        val mode = SessionMode.Attest(summary.deviceId, summary.friendlyName, summary.lastAttested, returnsSalt = summary.factorKeptAt != null)
        val end = session(mode, record) { it.isAttest && KiraCore.matchAdvertisement(it.serviceData, record) }
        assertEquals(end.notice.orEmpty(), Outcome.Succeeded, end.outcome)
    }

    companion object {
        const val TAG = "KiraVm"
    }
}
