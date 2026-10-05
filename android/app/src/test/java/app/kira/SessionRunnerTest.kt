@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.kira

import app.kira.ble.BluetoothOff
import app.kira.ble.InfoValue
import app.kira.ble.Link
import app.kira.ble.LinkEvent
import app.kira.ble.LinkException
import app.kira.ble.MachineNotReachable
import app.kira.core.CoreSession
import app.kira.core.Decision
import app.kira.core.Event
import app.kira.core.StepResult
import app.kira.core.Verdict
import app.kira.keys.AnchorKeyProvider
import app.kira.keys.CreatedAnchorKey
import app.kira.keys.KeyStorage
import app.kira.keys.SignRequest
import app.kira.keys.SigningCancelled
import app.kira.session.FailureKind
import app.kira.session.Outcome
import app.kira.session.Phase
import app.kira.session.RecordSink
import app.kira.session.SessionMode
import app.kira.session.SessionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunnerTest {

    private fun frag(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun step(vararg frags: ByteArray, events: List<Event> = emptyList(), err: String = "") =
        StepResult(frags.toList(), events, err)

    /** A core whose answers are scripted; records every call. */
    private class FakeCore : CoreSession {
        val calls = mutableListOf<String>()
        var finished = false
        var succeeded = false
        var onStart: () -> StepResult = { StepResult.EMPTY }
        var onNotification: (ByteArray) -> StepResult = { StepResult.EMPTY }
        var onSignature: (ByteArray) -> StepResult = { StepResult.EMPTY }
        var onDecide: (Decision, String) -> StepResult = { _, _ -> StepResult.EMPTY }
        var onAbort: (String) -> StepResult = { reason ->
            finished = true
            StepResult(listOf(byteArrayOf(0x7f)), listOf(Event.Error(10, reason)), reason)
        }

        override fun setMaxFragment(n: Int) { calls += "setMaxFragment($n)" }
        override fun start() = onStart().also { calls += "start" }
        override fun onNotification(value: ByteArray) = onNotification.invoke(value).also { calls += "notify(${value.toList()})" }
        override fun confirmSas(match: Boolean) = StepResult.EMPTY.also { calls += "confirmSas($match)" }
        override fun provideAnchorKey(spkiDer: ByteArray) = StepResult.EMPTY.also { calls += "anchorKey" }
        override fun pendingTbs() = byteArrayOf(1, 2, 3)
        override fun provideSignature(der: ByteArray) = onSignature(der).also { calls += "signature" }
        override fun decide(decision: Decision, confirmName: String) = onDecide(decision, confirmName).also { calls += "decide($decision)" }
        override fun requestEventlog() = StepResult.EMPTY.also { calls += "requestEventlog" }
        override fun eventlog(): ByteArray? = null
        override fun abort(reason: String) = onAbort(reason).also { calls += "abort($reason)" }
        override fun finished() = finished
        override fun succeeded() = succeeded
        override fun recordJson() = ""
    }

    private class FakeLink(mode: Int = InfoValue.MODE_ATTEST) : Link {
        override val maxFragment = 182
        override val info = InfoValue(1, mode, 1, 0)
        val channel = Channel<LinkEvent>(Channel.UNLIMITED)
        override val events get() = channel
        val written = mutableListOf<List<Byte>>()
        var disconnected = false
        override suspend fun write(fragment: ByteArray) {
            check(!disconnected) { "write after disconnect" }
            written += fragment.toList()
        }
        override suspend fun disconnect() { disconnected = true; channel.close() }
        fun notify(vararg b: Int) = channel.trySend(LinkEvent.Notification(ByteArray(b.size) { b[it].toByte() }))
    }

    private class FakeKeys(var signResult: () -> ByteArray = { byteArrayOf(0x30) }) : AnchorKeyProvider {
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var requests = mutableListOf<SignRequest>()
        override suspend fun create(deviceIdHex: String) = CreatedAnchorKey(ByteArray(91), KeyStorage.TEE)
        override suspend fun sign(deviceIdHex: String, tbs: ByteArray, request: SignRequest): ByteArray {
            requests += request
            gate.await()
            return signResult()
        }
        override fun delete(deviceIdHex: String) = Unit
    }

    private class FakeRecords : RecordSink {
        val saved = mutableListOf<String>()
        val replaced = mutableListOf<String>()
        override suspend fun saveEnrolled(recordJson: String, keyStorage: KeyStorage) { saved += recordJson }
        override suspend fun replace(recordJson: String) { replaced += recordJson }
    }

    private fun TestScope.runner(
        core: FakeCore, link: FakeLink, keys: FakeKeys = FakeKeys(), records: FakeRecords = FakeRecords(),
        mode: SessionMode = SessionMode.Attest("a0".repeat(16), "Thinkpad-X1"),
        connect: suspend () -> Link = { link },
    ) = SessionRunner(
        mode = mode,
        connect = connect,
        newSession = { core },
        keys = keys,
        records = records,
        coreProtocol = 1,
        coreSchema = 1,
        clock = { testScheduler.currentTime },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun failingStepFragmentsAreWrittenBeforeDisconnect() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        core.onStart = {
            core.finished = true
            step(frag(1), frag(2), frag(3), events = listOf(Event.Error(1, "boom")), err = "boom")
        }
        val r = runner(core, link)
        r.run()
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2), listOf<Byte>(3)), link.written)
        assertTrue(link.disconnected)
        assertEquals(Outcome.Failed(FailureKind.PROTOCOL, 1, "boom"), r.state.value.outcome)
        assertEquals("setMaxFragment(182)", core.calls.first())
    }

    @Test fun recoverableErrorStillWritesFragmentsAndContinues() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        core.onStart = { step(frag(9)) }
        core.onNotification = { step(frag(8), err = "transient") }
        val r = runner(core, link)
        val job = launch { r.run() }
        link.notify(5)
        advanceUntilIdle()
        assertEquals(listOf(listOf<Byte>(9), listOf<Byte>(8)), link.written)
        assertEquals("transient", r.state.value.notice)
        assertFalse(r.state.value.finished)
        r.cancel()
        advanceUntilIdle()
        assertTrue(job.isCompleted)
    }

    @Test fun cancelWritesAbortFragmentsThenDisconnects() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val r = runner(core, link)
        launch { r.run() }
        advanceUntilIdle()
        r.cancel()
        advanceUntilIdle()
        assertEquals(listOf(listOf<Byte>(0x7f)), link.written)
        assertTrue(link.disconnected)
        assertEquals(FailureKind.CANCELLED, (r.state.value.outcome as Outcome.Failed).kind)
    }

    @Test fun notificationsAreFedInOrder() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val r = runner(core, link)
        launch { r.run() }
        for (i in 1..50) link.notify(i)
        advanceUntilIdle()
        val fed = core.calls.filter { it.startsWith("notify") }
        assertEquals((1..50).map { "notify([$it])" }, fed)
        r.cancel()
    }

    @Test fun matchSignsAutomaticallyAndFinishes() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val keys = FakeKeys()
        val records = FakeRecords()
        val verdict = Event.VerdictEvent(Verdict(ok = true, state = Verdict.MATCH), false, false)
        core.onNotification = { v ->
            when (v[0].toInt()) {
                1 -> step(frag(0x10), events = listOf(Event.Hello("a0".repeat(16), "Thinkpad-X1")))
                2 -> step(events = listOf(verdict, Event.NeedSignature("receipt", 1)))
                else -> {
                    core.finished = true; core.succeeded = true
                    step(frag(0x7e), events = listOf(Event.ReceiptAck(1, ""), Event.RecordUpdated("{}"), Event.Done))
                }
            }
        }
        core.onSignature = { step(frag(0x06)) }
        val r = runner(core, link, keys, records)
        launch { r.run() }
        link.notify(1); link.notify(2)
        advanceUntilIdle()
        assertEquals(listOf(SignRequest("Thinkpad-X1", "receipt", 1)), keys.requests)
        assertEquals(Phase.DELIVERING, r.state.value.phase)
        link.notify(3)
        advanceUntilIdle()
        assertEquals(listOf(listOf<Byte>(0x10), listOf<Byte>(0x06), listOf<Byte>(0x7e)), link.written)
        assertEquals(Outcome.Succeeded, r.state.value.outcome)
        assertEquals(listOf("{}"), records.replaced)
        assertEquals(1, r.state.value.receiptAck?.result)
        assertTrue(link.disconnected)
    }

    @Test fun biometricCancelAbortsTheSession() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val keys = FakeKeys(signResult = { throw SigningCancelled() })
        core.onNotification = { step(events = listOf(Event.NeedSignature("receipt", 1))) }
        val r = runner(core, link, keys)
        launch { r.run() }
        link.notify(1)
        advanceUntilIdle()
        assertTrue(core.calls.contains("abort(authentication cancelled)"))
        assertEquals(listOf(listOf<Byte>(0x7f)), link.written)
        assertEquals(FailureKind.AUTH_CANCELLED, (r.state.value.outcome as Outcome.Failed).kind)
    }

    @Test fun decisionIsPassedThroughUnchanged() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val failed = Event.VerdictEvent(Verdict(state = Verdict.FAILED), needsDecision = true, eventlogAvailable = false)
        core.onNotification = { step(events = listOf(failed)) }
        core.onDecide = { _, name -> if (name == "Thinkpad-X1") step(frag(0x06)) else step(err = "requires typing the machine's name") }
        val r = runner(core, link)
        launch { r.run() }
        link.notify(1)
        advanceUntilIdle()
        assertEquals(Phase.DECIDING, r.state.value.phase)
        r.decide(Decision.APPROVE_ONCE, "wrong")
        advanceUntilIdle()
        assertEquals(Phase.DECIDING, r.state.value.phase)
        assertEquals("requires typing the machine's name", r.state.value.notice)
        r.decide(Decision.APPROVE_ONCE, "Thinkpad-X1")
        advanceUntilIdle()
        assertEquals(listOf("decide(APPROVE_ONCE)", "decide(APPROVE_ONCE)"), core.calls.filter { it.startsWith("decide") })
        assertEquals(listOf(listOf<Byte>(0x06)), link.written)
        r.cancel()
    }

    @Test fun linkLossEndsTheSession() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val r = runner(core, link)
        launch { r.run() }
        advanceUntilIdle()
        link.channel.trySend(LinkEvent.Lost("gone"))
        advanceUntilIdle()
        assertEquals(FailureKind.LINK_LOST, (r.state.value.outcome as Outcome.Failed).kind)
        assertTrue(link.written.isEmpty())
    }

    @Test fun wrongModeIsRefusedBeforeStarting() = runTest {
        val core = FakeCore()
        val link = FakeLink(mode = InfoValue.MODE_ENROL)
        val r = runner(core, link)
        r.run()
        assertEquals(FailureKind.WRONG_MODE, (r.state.value.outcome as Outcome.Failed).kind)
        assertTrue(core.calls.isEmpty())
    }

    @Test fun enrolmentStoresTheRecordWithKeyStorage() = runTest {
        val core = FakeCore()
        val link = FakeLink(mode = InfoValue.MODE_ENROL)
        val records = FakeRecords()
        core.onNotification = { v ->
            when (v[0].toInt()) {
                1 -> step(events = listOf(Event.Sas("123456")))
                2 -> step(events = listOf(Event.NeedAnchorKey("a0".repeat(16), "Thinkpad-X1")))
                else -> {
                    core.finished = true; core.succeeded = true
                    step(frag(0x7e), events = listOf(Event.Enrolled("""{"r":1}"""), Event.Done))
                }
            }
        }
        val r = runner(core, link, records = records, mode = SessionMode.Enrol)
        launch { r.run() }
        link.notify(1)
        advanceUntilIdle()
        assertEquals("123456", r.state.value.sas)
        assertEquals(Phase.CONFIRM_SAS, r.state.value.phase)
        r.confirmSas(true)
        link.notify(2)
        link.notify(3)
        advanceUntilIdle()
        assertTrue(core.calls.containsAll(listOf("confirmSas(true)", "anchorKey")))
        assertEquals(listOf("""{"r":1}"""), records.saved)
        assertEquals(Outcome.Succeeded, r.state.value.outcome)
        assertEquals("Thinkpad-X1", r.state.value.machineName)
    }

    // ---- reaching the machine ----

    @Test fun retriesEveryFiveSecondsUntilTheMachineIsReachable() = runTest {
        val core = FakeCore()
        val link = FakeLink()
        val attempts = mutableListOf<Long>()
        val r = runner(core, link, connect = {
            attempts += currentTime
            // A scan window: the machine is not seen for 5 s, twice.
            if (attempts.size < 3) { delay(5_000); throw MachineNotReachable("Thinkpad-X1 was not seen nearby") }
            link
        })
        launch { r.run() }
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, r.state.value.unreachable?.attempt)
        assertEquals(Phase.CONNECTING, r.state.value.phase)
        advanceUntilIdle()
        assertEquals(listOf(0L, 5_000L, 10_000L), attempts)
        assertEquals(null, r.state.value.unreachable)
        assertEquals(Phase.SECURING, r.state.value.phase)
        r.cancel()
    }

    @Test fun fastFailuresWaitOutTheInterval() = runTest {
        val attempts = mutableListOf<Long>()
        val r = runner(FakeCore(), FakeLink(), connect = {
            attempts += currentTime
            throw MachineNotReachable("the Bluetooth connection could not be established")
        })
        val job = launch { r.run() }
        advanceTimeBy(20_001); runCurrent()
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L), attempts)
        job.cancel() // while connecting, SessionManager cancels the job
    }

    @Test fun keepsLookingUntilCancelled() = runTest {
        var attempts = 0
        val r = runner(FakeCore(), FakeLink(), connect = {
            attempts++
            delay(5_000)
            throw MachineNotReachable("Thinkpad-X1 was not seen nearby")
        })
        val job = launch { r.run() }
        advanceTimeBy(30 * 60_000L + 1); runCurrent()   // half an hour
        assertEquals(Phase.CONNECTING, r.state.value.phase)
        assertEquals(null, r.state.value.outcome)
        assertEquals(361, attempts)
        assertEquals(360, r.state.value.unreachable?.attempt)
        job.cancel()
        advanceUntilIdle()
        assertEquals(FailureKind.CANCELLED, (r.state.value.outcome as Outcome.Failed).kind)
    }

    @Test fun bluetoothOffIsWaitedOut() = runTest {
        var on = false
        val link = FakeLink()
        val r = runner(FakeCore(), link, connect = { if (!on) throw BluetoothOff() else link })
        launch { r.run() }
        runCurrent()
        assertEquals(true, r.state.value.unreachable?.bluetoothOff)
        on = true
        advanceTimeBy(5_001); runCurrent()
        assertEquals(null, r.state.value.unreachable)
        assertEquals(Phase.SECURING, r.state.value.phase)
        r.cancel()
    }

    @Test fun otherConnectionErrorsAreNotRetried() = runTest {
        var attempts = 0
        val r = runner(FakeCore(), FakeLink(), connect = { attempts++; throw LinkException("TX has no CCCD") })
        r.run()
        assertEquals(FailureKind.CONNECT, (r.state.value.outcome as Outcome.Failed).kind)
        assertEquals(1, attempts)
    }

    @Test fun cancellingWhileUnreachableEndsTheSession() = runTest {
        val r = runner(FakeCore(), FakeLink(), connect = { delay(5_000); throw MachineNotReachable("not seen") })
        val job = launch { r.run() }
        advanceTimeBy(7_000); runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(FailureKind.CANCELLED, (r.state.value.outcome as Outcome.Failed).kind)
        assertTrue(r.state.value.finished)
    }
}
