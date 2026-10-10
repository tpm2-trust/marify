package app.marify

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marify.core.Decision
import app.marify.core.Event
import app.marify.core.KiraCore
import app.marify.core.ReceiptResult
import app.marify.core.Verdict
import app.marify.demo.DemoLink
import app.marify.keys.KeyStorage
import app.marify.session.FailureKind
import app.marify.session.Outcome
import app.marify.session.Phase
import app.marify.session.RecordSink
import app.marify.session.SessionMode
import app.marify.session.SessionRunner
import app.marify.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real core (kiracore) against the real machine-side protocol over a
 * software TPM (kiratest), with the BLE link replaced by [DemoLink]: every
 * verdict state end to end, as PROTOCOL-BLE.md §10.6 intends.
 */
@RunWith(AndroidJUnit4::class)
class DemoMachineSessionTest {

    private lateinit var demo: kiratest.DemoMachine
    private lateinit var keys: SoftwareAnchorKeys
    private var record: String = ""

    private val sink = object : RecordSink {
        override suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage) { record = recordJson }
        override suspend fun replace(recordJson: String) { record = recordJson }
    }

    @Before fun setUp() {
        demo = kiratest.Kiratest.newDemoMachine()
        keys = SoftwareAnchorKeys()
    }

    /** Runs one session; [react] plays the user for each state change. */
    private fun session(
        mode: SessionMode,
        mtu: Int,
        react: suspend (SessionRunner, SessionState) -> Unit = { _, _ -> },
    ): SessionState = runBlocking {
        val runner = SessionRunner(
            mode = mode,
            connect = {
                if (mode is SessionMode.Enrol) DemoLink.enrol(demo, mtu) else DemoLink.attest(demo, mtu)
            },
            newSession = {
                if (mode is SessionMode.Enrol) KiraCore.newEnrolSession()
                else KiraCore.newAttestSession(record)
            },
            keys = keys,
            records = sink,
            coreProtocol = KiraCore.protocolVersion,
            coreSchema = KiraCore.schemaVersion,
        )
        val user = CoroutineScope(Dispatchers.Default).launch {
            runner.state.collect {
                // The person: compares the code with the machine's screen and
                // continues a matching boot. Other verdicts are the test's.
                if (it.phase == Phase.DECIDING && !it.decisionSent && it.verdict?.verdict?.state == Verdict.MATCH) {
                    val v = it.verdict.verdict
                    codesSeen += v.code to demo.lastBootCode()
                    runner.decide(Decision.CONTINUE)
                }
                react(runner, it)
            }
        }
        withTimeout(30_000) { runner.run() }
        user.cancel()
        runner.state.value
    }

    private var bindShown: Event.NeedAnchorKey? = null

    /** (code on the phone, code on the machine's screen) of every matching boot. */
    private val codesSeen = mutableListOf<Pair<String, String>>()

    private fun enrol(mtu: Int = 517) {
        var sasShown: String? = null
        val s = session(SessionMode.Enrol, mtu) { r, st ->
            if (st.phase == Phase.CONFIRM_BIND) {
                bindShown = st.bind
                r.confirmBind(true)
            }
            if (st.phase == Phase.CONFIRM_SAS && sasShown == null) {
                sasShown = st.sas
                r.confirmSas(true)
            }
        }
        assertEquals(Outcome.Succeeded, s.outcome)
        assertEquals("enrolled", demo.outcome(5000))
        assertEquals(demo.lastSAS(), sasShown)
        assertEquals("Thinkpad-X1", s.machineName)
        assertEquals("Thinkpad-X1", KiraCore.recordSummary(record).friendlyName)
        assertTrue(keys.requests.single().purpose == Event.PURPOSE_ENROL_ACCEPT)
        // The demo machine's software TPM has no EK certificate.
        assertEquals("", bindShown?.ekVerifiedBy)
        assertEquals("the TPM has no vendor certificate for its endorsement key", bindShown?.ekNote)
        assertEquals("", KiraCore.recordSummary(record).ekVerifiedBy)
    }

    private fun attestMode() = KiraCore.recordSummary(record).let { SessionMode.Attest(it.deviceId, it.friendlyName, it.lastAttested, returnsSalt = it.factorKeptAt != null) }

    @Test fun enrolThenMatch() = enrolThenMatch(517)

    @Test fun enrolThenMatchAtMinimumMtu() = enrolThenMatch(23)

    private fun enrolThenMatch(mtu: Int) {
        enrol(mtu)
        demo.nextBoot()
        val s = session(attestMode(), mtu)
        assertEquals(Verdict.MATCH, s.verdict?.verdict?.state)
        // The machine's TPM released its boot key: the phone shows the code
        // the machine shows, and nothing was signed before the decision.
        assertEquals(Verdict.BOOT_KEY_PROVED, s.verdict?.verdict?.bootKey)
        assertEquals(9, s.verdict?.verdict?.code?.length)
        assertEquals(listOf(s.verdict!!.verdict.code to s.verdict.verdict.code), codesSeen)
        assertEquals(Outcome.Succeeded, s.outcome)
        assertEquals(ReceiptResult.ACCEPTED, s.receiptAck?.result)
        assertEquals("receipt: verdict=ok authentic=true", demo.outcome(5000))
        assertTrue(KiraCore.recordSummary(record).lastAttested != null)
    }

    /** A TPM that refuses the key: no code, and the verdict says so; the boot still matches. */
    @Test fun matchWithoutBootKey() {
        enrol()
        demo.nextBoot()
        demo.refuseBootKey(true)
        val s = session(attestMode(), 517)
        assertEquals(Verdict.MATCH, s.verdict?.verdict?.state)
        assertEquals(Verdict.BOOT_KEY_REFUSED, s.verdict?.verdict?.bootKey)
        assertEquals("", s.verdict?.verdict?.code)
        assertEquals("", demo.lastBootCode())
        assertEquals(Outcome.Succeeded, s.outcome)
        demo.refuseBootKey(false)
    }

    @Test fun changedWithEventlogApproveAndRememberThenMatch() {
        enrol()
        demo.changePCR(4)
        var asked = false
        var decided = false
        val s = session(attestMode(), 517) { r, st ->
            if (st.phase == Phase.DECIDING && !asked) {
                asked = true
                assertTrue(st.verdict!!.eventlogAvailable)
                r.requestEventlog()
            }
            if (st.eventlog?.complete == true && !decided) {
                decided = true
                r.decide(Decision.APPROVE_REMEMBER)
            }
        }
        assertEquals(Verdict.CHANGED, s.verdict?.verdict?.state)
        assertTrue(s.verdict!!.verdict.pcrDiff.any { it.index == 4 })
        assertTrue(s.eventlog!!.complete)
        assertEquals(Outcome.Succeeded, s.outcome)
        assertEquals("receipt: verdict=approved authentic=true", demo.outcome(5000))
        assertEquals(2, KiraCore.recordSummary(record).profiles.size)

        // The remembered profile now matches.
        demo.nextBoot()
        val again = session(attestMode(), 517)
        assertEquals(Verdict.MATCH, again.verdict?.verdict?.state)
        assertEquals("receipt: verdict=ok authentic=true", demo.outcome(5000))
    }

    @Test fun failedThenReject() {
        enrol()
        demo.nextBoot()
        demo.nextBoot()
        demo.rollbackResetCount()
        val s = session(attestMode(), 517) { r, st ->
            if (st.phase == Phase.DECIDING) r.decide(Decision.REJECT)
        }
        val v = s.verdict!!.verdict
        assertEquals(Verdict.FAILED, v.state)
        assertTrue(v.reasons.any { it.code == "reset_count_decreased" && it.hard })
        assertEquals(ReceiptResult.REJECT_NOTED, s.receiptAck?.result)
        assertTrue(demo.outcome(5000).startsWith("receipt: verdict=reject"))
        // A rejected boot is not signed: no biometric prompt beyond enrolment.
        assertEquals(1, keys.requests.size)
    }

    @Test fun failedApproveNeedsTheTypedName() {
        enrol()
        demo.nextBoot()
        demo.rollbackResetCount()
        var step = 0
        val s = session(attestMode(), 517) { r, st ->
            if (st.phase == Phase.DECIDING && step == 0) {
                step = 1
                r.decide(Decision.APPROVE_ONCE, "not the name")
            } else if (st.phase == Phase.DECIDING && step == 1 && st.notice != null) {
                step = 2
                r.decide(Decision.APPROVE_ONCE, "Thinkpad-X1")
            }
        }
        assertEquals(2, step)
        assertEquals(Outcome.Succeeded, s.outcome)
        assertEquals("receipt: verdict=approved authentic=true", demo.outcome(5000))
    }

    @Test fun userAbortsMidSession() {
        enrol()
        demo.changePCR(7)
        val s = session(attestMode(), 517) { r, st ->
            if (st.phase == Phase.DECIDING) r.cancel()
        }
        assertEquals(FailureKind.CANCELLED, (s.outcome as Outcome.Failed).kind)
        assertTrue(demo.outcome(5000).startsWith("error:"))
    }

    @Test fun biometricCancelAbortsAndSendsNothingSigned() {
        enrol()
        demo.nextBoot()
        keys.cancelNext = true
        val s = session(attestMode(), 517)
        assertEquals(FailureKind.AUTH_CANCELLED, (s.outcome as Outcome.Failed).kind)
        assertTrue(demo.outcome(5000).startsWith("error:"))
    }

    @Test fun sasMismatchStoresNothing() {
        val s = session(SessionMode.Enrol, 517) { r, st ->
            if (st.phase == Phase.CONFIRM_SAS) r.confirmSas(false)
        }
        assertEquals(FailureKind.SAS_MISMATCH, (s.outcome as Outcome.Failed).kind)
        assertEquals("", record)
        assertTrue(demo.outcome(5000).startsWith("error:"))
    }

    @Test fun advertisementMatchesOnlyItsOwnRecord() {
        enrol()
        assertTrue(KiraCore.matchAdvertisement(demo.serviceData(), record))
        val other = kiratest.Kiratest.newDemoMachine()
        assertTrue(!KiraCore.matchAdvertisement(other.serviceData(), record))
        assertEquals(KiraCore.ADV_FLAG_ATTEST, KiraCore.advertisementFlags(demo.serviceData()))
    }

}
