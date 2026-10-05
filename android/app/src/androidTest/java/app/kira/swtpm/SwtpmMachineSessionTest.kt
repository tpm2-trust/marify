package app.kira.swtpm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kira.SoftwareAnchorKeys
import app.kira.core.Decision
import app.kira.core.Event
import app.kira.core.KiraCore
import app.kira.core.ReceiptResult
import app.kira.core.Verdict
import app.kira.keys.KeyStorage
import app.kira.session.FailureKind
import app.kira.session.Outcome
import app.kira.session.Phase
import app.kira.session.RecordSink
import app.kira.session.SessionMode
import app.kira.session.SessionRunner
import app.kira.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * The app's session code and the real Go core against a tpm2-kira machine on
 * a software TPM (swtpm): real EK and AK, real TPM2_Quote, real
 * ActivateCredential, the attestation blob written to NV with PolicySigned.
 *
 * Isolated from the regular suite: it runs only when the instrumentation
 * argument `swtpm` names the control port of the machine, which
 * scripts/test-swtpm.sh starts and forwards with `adb reverse`.
 */
@RunWith(AndroidJUnit4::class)
class SwtpmMachineSessionTest {

    private lateinit var machine: SwtpmMachine
    private lateinit var keys: SoftwareAnchorKeys
    private var noise = KiraCore.generateNoiseKey()
    private val config = buildJsonObject {
        put("verifier_id", "swtpm-app-test")
        put("verifier_name", "Instrumented test")
        put("policy_id", "default")
        put("receipt_ttl_seconds", 300)
    }.toString()
    private var record = ""

    private val sink = object : RecordSink {
        override suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage) { record = recordJson }
        override suspend fun replace(recordJson: String) { record = recordJson }
    }

    @Before fun connect() {
        val port = InstrumentationRegistry.getArguments().getString("swtpm")
        assumeTrue("swtpm machine not running (scripts/test-swtpm.sh)", port != null)
        machine = SwtpmMachine("127.0.0.1", port!!.toInt())
        keys = SoftwareAnchorKeys()
        // Every test starts from a fresh boot: reset counter advanced, PCRs clear.
        machine.reboot()
    }

    @After fun disconnect() {
        if (::machine.isInitialized) machine.close()
    }

    private fun session(
        mode: SessionMode,
        mtu: Int = 517,
        startServing: Boolean = true,
        react: suspend (SessionRunner, SessionState) -> Unit = { _, _ -> },
    ): SessionState = runBlocking {
        val runner = SessionRunner(
            mode = mode,
            connect = {
                withContext(Dispatchers.IO) {
                    if (startServing) {
                        if (mode is SessionMode.Enrol) machine.startEnrolment(mtu) else machine.startAttestation(mtu)
                    }
                    machine.link(mtu)
                }
            },
            newSession = {
                if (mode is SessionMode.Enrol) KiraCore.newEnrolSession(config, noise)
                else KiraCore.newAttestSession(config, noise, record)
            },
            keys = keys,
            records = sink,
            coreProtocol = KiraCore.protocolVersion,
            coreSchema = KiraCore.schemaVersion,
        )
        val user = CoroutineScope(Dispatchers.Default).launch {
            runner.state.collect { react(runner, it) }
        }
        withTimeout(120_000) { runner.run() }
        user.cancel()
        runner.state.value
    }

    private fun attestMode() =
        KiraCore.recordSummary(record).let { SessionMode.Attest(it.deviceId, it.friendlyName, it.lastAttested) }

    private fun enrol(mtu: Int = 517) {
        var shown: String? = null
        val s = session(SessionMode.Enrol, mtu) { r, st ->
            if (st.phase == Phase.CONFIRM_SAS && shown == null) {
                shown = st.sas
                r.confirmSas(true)
            }
        }
        assertEquals(s.outcome.toString(), Outcome.Succeeded, s.outcome)
        assertEquals("enrolled", machine.outcome())
        assertEquals(machine.lastSas(), shown)
        assertEquals("swtpm-box", KiraCore.recordSummary(record).friendlyName)
    }

    @Test fun enrolThenMatch() = enrolThenMatch(517)

    @Test fun enrolThenMatchAtMinimumMtu() = enrolThenMatch(23)

    private fun enrolThenMatch(mtu: Int) {
        enrol(mtu)
        machine.reboot()
        val s = session(attestMode(), mtu)
        assertEquals(s.verdict?.verdict?.reasons.toString(), Verdict.MATCH, s.verdict?.verdict?.state)
        assertEquals(Outcome.Succeeded, s.outcome)
        assertEquals(ReceiptResult.ACCEPTED, s.receiptAck?.result)
        assertEquals("receipt: verdict=ok authentic=true", machine.outcome())
        assertEquals(listOf(Event.PURPOSE_ENROL_ACCEPT, Event.PURPOSE_RECEIPT), keys.requests.map { it.purpose })
    }

    @Test fun changedWithEventlogApproveAndRememberThenMatch() {
        enrol()
        machine.extendPcr(4)
        var asked = false
        var decided = false
        val s = session(attestMode()) { r, st ->
            if (st.phase == Phase.DECIDING && !asked) {
                asked = true
                r.requestEventlog()
            }
            if (st.eventlog?.complete == true && !decided) {
                decided = true
                r.decide(Decision.APPROVE_REMEMBER)
            }
        }
        val v = s.verdict!!.verdict
        assertEquals(Verdict.CHANGED, v.state)
        assertEquals(listOf(4), v.pcrDiff.map { it.index })
        assertTrue(s.verdict.eventlogAvailable)
        assertEquals(48_000L, s.eventlog!!.total)
        assertEquals("receipt: verdict=approved authentic=true", machine.outcome())
        assertEquals(2, KiraCore.recordSummary(record).profiles.size)

        // The same change after the next boot matches the remembered profile.
        machine.reboot()
        machine.extendPcr(4)
        val again = session(attestMode())
        assertEquals(Verdict.MATCH, again.verdict?.verdict?.state)
        assertTrue(again.verdict!!.verdict.profile.startsWith("approved"))
        assertEquals("receipt: verdict=ok authentic=true", machine.outcome())
    }

    @Test fun replayedTpmStateFailsAndIsRejected() {
        enrol()
        machine.snapshot()          // TPM state saved here
        machine.reboot()
        assertEquals(Verdict.MATCH, session(attestMode()).verdict?.verdict?.state)
        assertEquals("receipt: verdict=ok authentic=true", machine.outcome())

        machine.rollback()          // the older TPM state comes back
        val s = session(attestMode()) { r, st ->
            if (st.phase == Phase.DECIDING) r.decide(Decision.REJECT)
        }
        val v = s.verdict!!.verdict
        assertEquals(Verdict.FAILED, v.state)
        assertTrue(v.reasons.toString(), v.reasons.any { it.code == "reset_count_decreased" && it.hard })
        assertEquals(ReceiptResult.REJECT_NOTED, s.receiptAck?.result)
        assertTrue(machine.outcome().startsWith("receipt: verdict=reject"))
        // The reject went out unsigned: no prompt beyond enrolment and the match.
        assertEquals(2, keys.requests.size)
    }

    @Test fun userAbortsMidSession() {
        enrol()
        machine.extendPcr(7)
        val s = session(attestMode()) { r, st ->
            if (st.phase == Phase.DECIDING) r.cancel()
        }
        assertEquals(FailureKind.CANCELLED, (s.outcome as Outcome.Failed).kind)
        // The machine was told: the Error message from Abort reached it.
        assertEquals("error: peer aborted (code 10): cancelled", machine.outcome())
    }

    @Test fun retriesEveryFiveSecondsUntilTheMachineWaits() {
        enrol()
        machine.reboot()
        // The machine only starts waiting for a phone 7 s after the app began.
        val late = thread {
            Thread.sleep(7_000)
            machine.startAttestation(517)
        }
        var mostAttempts = 0
        val started = System.nanoTime()
        val s = session(attestMode(), startServing = false) { _, st ->
            st.unreachable?.let { mostAttempts = maxOf(mostAttempts, it.attempt) }
        }
        late.join()
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(Verdict.MATCH, s.verdict?.verdict?.state)
        assertEquals(Outcome.Succeeded, s.outcome)
        assertEquals("receipt: verdict=ok authentic=true", machine.outcome())
        // Attempts at 0 s and 5 s fail; the one at 10 s reaches the machine.
        assertEquals(2, mostAttempts)
        assertTrue("took ${seconds}s", seconds in 9.5..20.0)
    }

    @Test fun strangerPhoneGetsNoAnswer() {
        enrol()
        machine.reboot()
        // Another phone that copied the record but not the enrolled Noise key.
        noise = KiraCore.generateNoiseKey()
        val s = session(attestMode())
        // The machine refuses the IK handshake; after one silent retry the
        // app says so instead of "connection lost" (TODO-SEC.md S1).
        val o = s.outcome as Outcome.Failed
        assertEquals(o.toString(), FailureKind.MACHINE_REFUSED, o.kind)
        assertEquals(null, s.verdict)
        assertEquals("error: attest: initiator is not an enrolled verifier", machine.outcome())
    }
}
