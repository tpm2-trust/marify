import XCTest
@testable import Marify

final class EventParseTests: XCTestCase {
    func testVerdictEvent() throws {
        let e = try Event.parse("""
        {"type":"verdict","needs_decision":true,"eventlog_available":true,"verdict":{
          "ok":false,"state":"changed","reasons":[{"code":"no_profile_match","detail":"no profile","hard":false}],
          "warnings":[{"code":"reset_count_jump"}],
          "pcr_diff":[{"index":4,"expected":"aa","actual":"bb","description":"Bootloader"}],
          "explanation":"PCR 4 changed.","reset_count":14,"restart_count":0,"clock_safe":true,"firmware_version":1,
          "boot_key":"proved","code":"K7QM-2XHD","signing_key":"000b0123"}}
        """)
        guard case .verdict(let v) = e else { return XCTFail("\(e)") }
        XCTAssertTrue(v.needsDecision)
        XCTAssertTrue(v.eventlogAvailable)
        XCTAssertEqual(v.verdict.state, Verdict.changed)
        XCTAssertEqual(v.verdict.reasons, [Verdict.Reason(code: "no_profile_match", detail: "no profile", hard: false)])
        XCTAssertEqual(v.verdict.warnings.map(\.code), ["reset_count_jump"])
        XCTAssertEqual(v.verdict.pcrDiff, [Verdict.PcrDiff(index: 4, expected: "aa", actual: "bb", description: "Bootloader")])
        XCTAssertEqual(v.verdict.resetCount, 14)
        XCTAssertTrue(v.verdict.clockSafe)
        XCTAssertEqual(v.verdict.bootKey, Verdict.bootKeyProved)
        XCTAssertEqual(v.verdict.code, "K7QM-2XHD")
        XCTAssertEqual(v.verdict.signingKey, "000b0123")
    }

    func testVerdictWithoutBootKeyIsUnused() throws {
        let e = try Event.parse(#"{"type":"verdict","verdict":{"ok":true,"state":"match"}}"#)
        guard case .verdict(let v) = e else { return XCTFail("\(e)") }
        XCTAssertEqual(v.verdict.bootKey, Verdict.bootKeyUnused)
        XCTAssertFalse(v.needsDecision)
    }

    func testNeedAnchorKey() throws {
        let e = try Event.parse("""
        {"type":"need_anchor_key","device_id":"ab","friendly_name":"box","ek_verified_by":"Infineon",
         "initrd_coverage":"covered","initrd_pcrs":[9],"attestation_challenge":"0a0b"}
        """)
        guard case .needAnchorKey(let n) = e else { return XCTFail("\(e)") }
        XCTAssertEqual(n.deviceId, "ab")
        XCTAssertEqual(n.friendlyName, "box")
        XCTAssertEqual(n.ekVerifiedBy, "Infineon")
        XCTAssertEqual(n.initrdCoverage, Event.initrdCovered)
        XCTAssertEqual(n.initrdPcrs, [9])
        XCTAssertEqual(n.attestationChallenge, Data([0x0a, 0x0b]))
    }

    func testRecordIsKeptAsAnObject() throws {
        let e = try Event.parse(#"{"type":"enrolled","record":{"b":1,"a":[1,2]}}"#)
        guard case .enrolled(let json) = e else { return XCTFail("\(e)") }
        let o = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        XCTAssertEqual(o?["b"] as? Int, 1)
        XCTAssertEqual(o?["a"] as? [Int], [1, 2])
    }

    func testEnrolledWithoutRecordFails() {
        XCTAssertThrowsError(try Event.parse(#"{"type":"enrolled"}"#))
    }

    func testOtherEvents() throws {
        XCTAssertEqual(try Event.parse(#"{"type":"sas","sas":"123456"}"#), .sas("123456"))
        XCTAssertEqual(try Event.parse(#"{"type":"need_signature","purpose":"receipt","verdict_code":2}"#), .needSignature(purpose: "receipt", verdictCode: 2))
        XCTAssertEqual(try Event.parse(#"{"type":"hello","device_id":"d","friendly_name":"n"}"#), .hello(deviceId: "d", friendlyName: "n"))
        XCTAssertEqual(try Event.parse(#"{"type":"eventlog","received":10,"total":20,"complete":false}"#), .eventlog(Event.Eventlog(received: 10, total: 20, complete: false)))
        XCTAssertEqual(try Event.parse(#"{"type":"receipt_ack","result":1,"message":"ok"}"#), .receiptAck(result: 1, message: "ok"))
        XCTAssertEqual(try Event.parse(#"{"type":"release_ack","result":1,"message":"kept for the disk"}"#), .releaseAck(status: 1, message: "kept for the disk"))
        XCTAssertEqual(try Event.parse(#"{"type":"done"}"#), .done)
        XCTAssertEqual(try Event.parse(#"{"type":"error","code":10,"message":"cancelled"}"#), .error(code: 10, message: "cancelled"))
        XCTAssertEqual(try Event.parse(#"{"type":"something_new","x":1}"#), .unknown(type: "something_new"))
    }

    func testVerdictOffersAFactor() throws {
        guard case .verdict(let e) = try Event.parse(#"{"type":"verdict","needs_decision":true,"factor_offered":true,"verdict":{"ok":true,"state":"match","reasons":[]}}"#) else {
            return XCTFail("not a verdict")
        }
        XCTAssertTrue(e.factorOffered)
        guard case .verdict(let plain) = try Event.parse(#"{"type":"verdict","verdict":{"ok":true,"state":"match","reasons":[]}}"#) else {
            return XCTFail("not a verdict")
        }
        XCTAssertFalse(plain.factorOffered)
    }

    func testRecordSummaryWithAFactor() throws {
        let s = try RecordSummary.parse(#"{"device_id":"00","friendly_name":"box","enrolled_at":"2026-10-07T08:00:00Z","slot":1,"profiles":[],"factor_kept_at":"2026-10-07T09:00:00Z"}"#)
        XCTAssertEqual(s.factorKeptAt, "2026-10-07T09:00:00Z")
        XCTAssertNil(try RecordSummary.parse(#"{"device_id":"00","friendly_name":"box","enrolled_at":"2026-10-07T08:00:00Z","profiles":[]}"#).factorKeptAt)
    }

    func testRecordSummary() throws {
        let s = try RecordSummary.parse("""
        {"device_id":"d","friendly_name":"n","enrolled_at":"2026-08-12T09:30:00Z","last_attested":null,"slot":2,
         "profiles":[{"name":"enrolled","valid_from":"2026-08-12T09:30:00Z","added_by":"enrolment","uses_left":null}],
         "ek_verified_by":""}
        """)
        XCTAssertEqual(s.deviceId, "d")
        XCTAssertNil(s.lastAttested)
        XCTAssertEqual(s.slot, 2)
        XCTAssertEqual(s.profiles.count, 1)
        XCTAssertNil(s.profiles[0].usesLeft)
    }
}

final class InfoValueTests: XCTestCase {
    func testParse() throws {
        let v = try InfoValue.parse(Data([1, 2, 0x34, 0x12, 1, 0, 0, 0]))
        XCTAssertEqual(v, InfoValue(protocolVersion: 1, mode: 2, schema: 0x1234, capabilities: 1))
        XCTAssertThrowsError(try InfoValue.parse(Data([1, 2, 3])))
    }

    func testCheck() {
        let v = InfoValue(protocolVersion: 1, mode: InfoValue.modeAttest, schema: 2, capabilities: 1)
        XCTAssertEqual(v.check(expectedMode: InfoValue.modeAttest, coreProtocol: 1, coreSchema: 2), .ok)
        XCTAssertEqual(v.check(expectedMode: InfoValue.modeAttest, coreProtocol: 1, coreSchema: 3), .ok)
        XCTAssertEqual(v.check(expectedMode: InfoValue.modeAttest, coreProtocol: 1, coreSchema: 1), .updateNeeded(appTooOld: true))
        XCTAssertEqual(v.check(expectedMode: InfoValue.modeAttest, coreProtocol: 2, coreSchema: 2), .updateNeeded(appTooOld: false))
        XCTAssertEqual(v.check(expectedMode: InfoValue.modeEnrol, coreProtocol: 1, coreSchema: 2), .wrongMode(actual: InfoValue.modeAttest))
    }
}

final class HexTests: XCTestCase {
    func testRoundTrip() {
        XCTAssertEqual(Data(hex: "00ff10")?.hexString, "00ff10")
        XCTAssertEqual(Data(hex: "ABCD"), Data([0xab, 0xcd]))
        XCTAssertNil(Data(hex: ""))
        XCTAssertNil(Data(hex: "abc"))
        XCTAssertNil(Data(hex: "zz"))
    }
}
