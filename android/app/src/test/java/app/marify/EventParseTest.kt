package app.marify

import app.marify.core.Decision
import app.marify.core.Event
import app.marify.core.RecordSummary
import app.marify.core.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every event type of PROTOCOL-BLE.md §10.3, in the shape the Go core emits it. */
class EventParseTest {

    private val record = """{"version":1,"device_id":"a0a1a2a3a4a5a6a7a8a9aaabacadaeaf","friendly_name":"Thinkpad-X1","policy":{"id":"default","profiles":[]},"reset_count":12,"enrolled_at":"2026-10-05T06:10:00.123456789+02:00"}"""

    @Test fun sas() = assertEquals(Event.Sas("394551"), Event.parse("""{"type":"sas","sas":"394551"}"""))

    @Test fun needAnchorKey() = assertEquals(
        Event.NeedAnchorKey("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "Thinkpad-X1"),
        Event.parse("""{"type":"need_anchor_key","device_id":"a0a1a2a3a4a5a6a7a8a9aaabacadaeaf","friendly_name":"Thinkpad-X1"}"""),
    )

    @Test fun needAnchorKeyWithMachineChecks() {
        val e = Event.parse(
            """{"type":"need_anchor_key","device_id":"a0","friendly_name":"box","ek_verified_by":"Intel PTT",
               "initrd_coverage":"not_covered","initrd_pcrs":[9]}""",
        ) as Event.NeedAnchorKey
        assertEquals("Intel PTT", e.ekVerifiedBy)
        assertEquals(Event.INITRD_NOT_COVERED, e.initrdCoverage)
        assertEquals(listOf(9), e.initrdPcrs)
        // An older core sends none of these: unknown, never "covered".
        val old = Event.parse("""{"type":"need_anchor_key","device_id":"a0","friendly_name":"box"}""") as Event.NeedAnchorKey
        assertEquals(Event.INITRD_UNKNOWN, old.initrdCoverage)
        assertEquals("", old.ekVerifiedBy)
    }

    @Test fun needSignatureEnrol() = assertEquals(
        Event.NeedSignature("enrol_accept", 0),
        Event.parse("""{"type":"need_signature","purpose":"enrol_accept","tbs":"dHBtMi1raXJh"}"""),
    )

    @Test fun needSignatureReceipt() = assertEquals(
        Event.NeedSignature("receipt", 2),
        Event.parse("""{"type":"need_signature","purpose":"receipt","tbs":"AA==","verdict_code":2}"""),
    )

    @Test fun enrolledKeepsRecordVerbatim() {
        val e = Event.parse("""{"type":"enrolled","record":$record}""") as Event.Enrolled
        assertEquals(record, e.recordJson)
    }

    @Test fun recordUpdated() {
        val e = Event.parse("""{"type":"record_updated","record":$record}""") as Event.RecordUpdated
        assertEquals(record, e.recordJson)
    }

    @Test fun hello() = assertEquals(
        Event.Hello("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "Thinkpad-X1"),
        Event.parse("""{"type":"hello","device_id":"a0a1a2a3a4a5a6a7a8a9aaabacadaeaf","friendly_name":"Thinkpad-X1"}"""),
    )

    @Test fun verdictChanged() {
        val e = Event.parse(
            """{"type":"verdict","needs_decision":true,"eventlog_available":true,"verdict":{
              "ok":false,"state":"changed",
              "reasons":[{"code":"no_profile_match","detail":"no profile matches","hard":false}],
              "warnings":[{"code":"firmware_changed","detail":"x"}],
              "pcr_diff":[{"index":4,"expected":"aa","actual":"bb","description":"Boot Manager Code and Boot Attempts"}],
              "diff_against":"enrolment baseline","explanation":"PCR 4 changed.",
              "reset_count":12,"restart_count":0,"clock_safe":true,"firmware_version":1234}}""",
        ) as Event.VerdictEvent
        assertTrue(e.needsDecision)
        assertTrue(e.eventlogAvailable)
        assertEquals(Verdict.CHANGED, e.verdict.state)
        assertEquals("no_profile_match", e.verdict.reasons.single().code)
        assertFalse(e.verdict.reasons.single().hard)
        assertEquals(4, e.verdict.pcrDiff.single().index)
        assertEquals("PCR 4 changed.", e.verdict.explanation)
        assertEquals(1234L, e.verdict.firmwareVersion)
        assertEquals(Verdict.BOOT_KEY_UNUSED, e.verdict.bootKey)
        assertEquals("", e.verdict.code)
    }

    @Test fun verdictCarriesTheBootKeyAndCode() {
        val e = Event.parse(
            """{"type":"verdict","needs_decision":true,"verdict":{"ok":true,"state":"match","profile":"enrolment baseline",
              "reset_count":13,"restart_count":0,"clock_safe":true,"firmware_version":1,"boot_key":"proved","code":"K7QM-2XHD"}}""",
        ) as Event.VerdictEvent
        assertTrue(e.needsDecision)
        assertEquals(Verdict.BOOT_KEY_PROVED, e.verdict.bootKey)
        assertEquals("K7QM-2XHD", e.verdict.code)
        assertEquals(4L, Decision.CONTINUE.code)
    }

    @Test fun verdictMatchOmitsEmptyFields() {
        // The core omits false/empty fields (Go omitempty).
        val e = Event.parse(
            """{"type":"verdict","verdict":{"ok":true,"state":"match","profile":"enrolment baseline","reset_count":13,"restart_count":0,"clock_safe":true,"firmware_version":1}}""",
        ) as Event.VerdictEvent
        assertFalse(e.needsDecision)
        assertFalse(e.eventlogAvailable)
        assertEquals(Verdict.MATCH, e.verdict.state)
        assertTrue(e.verdict.reasons.isEmpty())
    }

    @Test fun eventlog() {
        assertEquals(Event.Eventlog(100, 300, false), Event.parse("""{"type":"eventlog","received":100,"total":300}"""))
        assertEquals(Event.Eventlog(300, 300, true), Event.parse("""{"type":"eventlog","received":300,"total":300,"complete":true}"""))
    }

    @Test fun receiptAck() = assertEquals(
        Event.ReceiptAck(1, "ok"),
        Event.parse("""{"type":"receipt_ack","result":1,"message":"ok"}"""),
    )

    @Test fun releaseAck() = assertEquals(
        Event.ReleaseAck(1, "kept for the disk"),
        Event.parse("""{"type":"release_ack","result":1,"message":"kept for the disk"}"""),
    )

    @Test fun verdictOffersAFactor() {
        val e = Event.parse(
            """{"type":"verdict","needs_decision":true,"factor_offered":true,"verdict":{"ok":true,"state":"match","reasons":[]}}""",
        ) as Event.VerdictEvent
        assertTrue(e.factorOffered)
    }

    @Test fun recordSummaryWithAFactor() {
        val s = RecordSummary.parse(
            """{"device_id":"00","friendly_name":"box","enrolled_at":"2026-10-07T08:00:00Z","slot":1,"profiles":[],"factor_kept_at":"2026-10-07T09:00:00Z"}""",
        )
        assertEquals("2026-10-07T09:00:00Z", s.factorKeptAt)
        assertEquals(null, RecordSummary.parse("""{"device_id":"00","friendly_name":"box","enrolled_at":"2026-10-07T08:00:00Z","profiles":[]}""").factorKeptAt)
    }

    @Test fun done() = assertEquals(Event.Done, Event.parse("""{"type":"done"}"""))

    @Test fun error() = assertEquals(
        Event.Error(10, "cancelled"),
        Event.parse("""{"type":"error","code":10,"message":"cancelled"}"""),
    )

    @Test fun unknownTypeIsKept() {
        val e = Event.parse("""{"type":"release_factor","slot":1}""") as Event.Unknown
        assertEquals("release_factor", e.type)
    }
}
