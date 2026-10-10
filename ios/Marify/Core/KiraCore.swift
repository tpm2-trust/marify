import CoreBluetooth
import Foundation
import Kiracore

/// Thin facade over the gomobile package `kiracore`. Every function here only
/// forwards to the Go core; nothing in the app re-implements its logic.
enum KiraCore {
    static let serviceUUID = CBUUID(string: KiracoreServiceUUID)
    static let rxUUID = CBUUID(string: KiracoreRXCharUUID)
    static let txUUID = CBUUID(string: KiracoreTXCharUUID)
    static let infoUUID = CBUUID(string: KiracoreInfoUUID)

    // Int(...) covers both widths gomobile may give a Go int constant.
    static let advFlagEnrol = Int(KiracoreAdvFlagEnrol)
    static let advFlagAttest = Int(KiracoreAdvFlagAttest)

    static var protocolVersion: Int { KiracoreProtocolVersion() }
    static var schemaVersion: Int { KiracoreSchemaVersion() }

    struct CoreError: Error, CustomStringConvertible {
        let description: String
    }

    /// The machine's verdict on an anchor key attestation (JSON); for display and tests.
    static func checkAnchorAttestation(chain: Data, spki: Data, challenge: Data) -> String {
        KiracoreCheckAnchorAttestation(chain, spki, challenge)
    }

    /// TPM vendors whose EK certificates the core in this app can verify.
    static func ekVendors() -> [String] {
        guard let data = KiracoreEKVendors().data(using: .utf8),
              let list = try? JSONSerialization.jsonObject(with: data) as? [String]
        else { return [] }
        return list
    }

    /// The phone's settings for a session. Its identity towards a machine is
    /// no setting: the core makes a new one at every enrolment and keeps it
    /// in that machine's record (PROTOCOL-BLE.md §11.1).
    static let configJSON = #"{"policy_id":"default","receipt_ttl_seconds":300}"#

    static func newEnrolSession(configJSON: String = KiraCore.configJSON) throws -> CoreSession {
        var err: NSError?
        guard let s = KiracoreNewEnrolSession(configJSON, &err), err == nil else {
            throw CoreError(description: err?.localizedDescription ?? "cannot create the enrol session")
        }
        return GoCoreSession(s)
    }

    static func newAttestSession(recordJSON: String, configJSON: String = KiraCore.configJSON) throws -> CoreSession {
        var err: NSError?
        guard let s = KiracoreNewAttestSession(configJSON, recordJSON, &err), err == nil else {
            throw CoreError(description: err?.localizedDescription ?? "cannot create the attest session")
        }
        return GoCoreSession(s)
    }

    /// Flags byte of the 13-byte service data, or -1.
    static func advertisementFlags(_ serviceData: Data) -> Int {
        KiracoreAdvertisementFlags(serviceData)
    }

    static func matchAdvertisement(_ serviceData: Data, recordJSON: String) -> Bool {
        KiracoreMatchAdvertisement(serviceData, recordJSON)
    }

    static func recordSummary(_ recordJSON: String) throws -> RecordSummary {
        var err: NSError?
        let text = KiracoreRecordSummary(recordJSON, &err)
        if let err { throw CoreError(description: err.localizedDescription) }
        return try RecordSummary.parse(text)
    }
}
