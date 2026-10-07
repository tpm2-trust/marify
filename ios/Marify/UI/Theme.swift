import SwiftUI

/// The three verdict colours, with their text colours, light and dark.
struct VerdictColors {
    let matchContainer: Color
    let onMatch: Color
    let changedContainer: Color
    let onChanged: Color
    let failedContainer: Color
    let onFailed: Color

    static let light = VerdictColors(
        matchContainer: Color(red: 0.80, green: 0.93, blue: 0.82), onMatch: Color(red: 0.05, green: 0.30, blue: 0.12),
        changedContainer: Color(red: 1.00, green: 0.89, blue: 0.70), onChanged: Color(red: 0.42, green: 0.22, blue: 0.00),
        failedContainer: Color(red: 0.98, green: 0.80, blue: 0.80), onFailed: Color(red: 0.45, green: 0.05, blue: 0.05)
    )

    static let dark = VerdictColors(
        matchContainer: Color(red: 0.08, green: 0.30, blue: 0.14), onMatch: Color(red: 0.80, green: 0.95, blue: 0.82),
        changedContainer: Color(red: 0.40, green: 0.26, blue: 0.02), onChanged: Color(red: 1.00, green: 0.90, blue: 0.70),
        failedContainer: Color(red: 0.42, green: 0.10, blue: 0.10), onFailed: Color(red: 0.98, green: 0.80, blue: 0.80)
    )

    static func current(_ scheme: ColorScheme) -> VerdictColors { scheme == .dark ? dark : light }
}

/// A filled card like the Android app's.
struct CardView<Content: View>: View {
    var container: Color
    var content: Color
    @ViewBuilder var body_: () -> Content

    init(container: Color, content: Color, @ViewBuilder content body: @escaping () -> Content) {
        self.container = container
        self.content = content
        self.body_ = body
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { body_() }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(container, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .foregroundStyle(content)
    }
}

/// Formats timestamps from the core (RFC 3339) in the phone's locale.
enum Texts {
    static func dateTime(_ rfc3339: String?) -> String? {
        guard let d = parse(rfc3339) else { return nil }
        return d.formatted(date: .abbreviated, time: .shortened)
    }

    static func date(_ rfc3339: String?) -> String? {
        guard let d = parse(rfc3339) else { return nil }
        return d.formatted(date: .abbreviated, time: .omitted)
    }

    private static func parse(_ s: String?) -> Date? {
        guard let s, !s.isEmpty else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    static func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }

    /// The one table from protocol codes to text (PROTOCOL-BLE.md §7.3.8,
    /// §7.3.14, §10.4). The core decides; these strings only explain.
    static func reason(_ code: String) -> String {
        switch code {
        case "malformed": String(localized: "The evidence is malformed.")
        case "policy_invalid": String(localized: "The stored policy for this machine is invalid.")
        case "device_mismatch": String(localized: "A different machine answered.")
        case "ak_mismatch": String(localized: "The machine's attestation key is not the one it enrolled with.")
        case "bad_signature": String(localized: "The TPM signature does not verify.")
        case "bad_magic": String(localized: "The quote was not produced by a TPM.")
        case "bad_type": String(localized: "The TPM produced something other than a quote.")
        case "qd_mismatch": String(localized: "The quote was not made for this session; it may be replayed.")
        case "reset_count_decreased": String(localized: "The TPM's boot counter went backwards: a replayed quote or a cleared TPM.")
        case "clock_unsafe": String(localized: "The TPM's clock is not trustworthy.")
        case "firmware_changed": String(localized: "The TPM firmware changed.")
        case "boot_proof_invalid": String(localized: "The machine claimed to hold its boot key but could not prove it.")
        case "selection_mismatch": String(localized: "The machine reported different registers than requested.")
        case "pcr_values_invalid": String(localized: "The reported register values are invalid.")
        case "pcr_digest_mismatch": String(localized: "The reported register values are not the ones the TPM signed.")
        case "secureboot_off": String(localized: "Secure Boot is off.")
        case "no_profile_match": String(localized: "The boot chain differs from every state you approved.")
        default: String(localized: "Check failed: \(code)")
        }
    }

    static func warning(_ code: String) -> String {
        switch code {
        case "reset_count_jump": String(localized: "The machine booted several times since the last check.")
        case "clock_unsafe": String(localized: "The TPM's clock is not trustworthy.")
        case "firmware_changed": String(localized: "The TPM firmware changed.")
        case "secureboot_off": String(localized: "Secure Boot is off.")
        default: String(localized: "Note: \(code)")
        }
    }

    static func errorCode(_ code: Int) -> String {
        switch code {
        case 1: String(localized: "Protocol violation")
        case 2: String(localized: "Unknown device")
        case 3: String(localized: "This phone is not enrolled with the machine")
        case 4: String(localized: "Confirmation code rejected")
        case 5: String(localized: "TPM failure")
        case 6: String(localized: "Credential activation failed: the TPM could not prove its key")
        case 7: String(localized: "Storage failure")
        case 8: String(localized: "Refused by policy")
        case 9: String(localized: "Timeout")
        case 10: String(localized: "Aborted by the user")
        case 11: String(localized: "Not supported")
        default: String(localized: "Error \(code)")
        }
    }

    /// What the machine made of the released remote salt (PROTOCOL-BLE.md §7.3.9).
    static func releaseStatus(_ status: Int) -> String {
        switch status {
        case 1: String(localized: "The machine took the remote salt.")
        case 2: String(localized: "This machine takes no remote salt.")
        case 3: String(localized: "The machine's TPM refused to open the remote salt: this boot is not one its owner approved.")
        case 4: String(localized: "The machine had no accepted receipt for it.")
        default: String(localized: "The machine answered with result \(status).")
        }
    }

    static func receiptResult(_ result: Int) -> String {
        switch result {
        case ReceiptResult.accepted: String(localized: "The machine accepted the receipt.")
        case ReceiptResult.badSignature: String(localized: "The machine says the receipt is not from its enrolled phone.")
        case ReceiptResult.bindingMismatch: String(localized: "The machine says the receipt does not belong to this session.")
        case ReceiptResult.rejectNoted: String(localized: "Rejection delivered.")
        case ReceiptResult.malformed: String(localized: "The machine could not read the receipt.")
        default: String(localized: "The machine answered with result \(result).")
        }
    }

    static func failure(_ kind: FailureKind) -> String {
        switch kind {
        case .machineRefused: String(localized: "The machine is advertising as yours but refused the secure connection, twice. Its enrolment data was changed or removed, or this is a different machine imitating it.")
        case .connect: String(localized: "Marify reached the machine, but setting up the connection failed.")
        case .updateNeeded: String(localized: "This machine speaks a different protocol version. Please update the app or tpm2-kira.")
        case .wrongMode: String(localized: "The machine is not in the expected mode. For enrolment run 'attest enrol'; for verification, 'attest gate'.")
        case .linkLost: String(localized: "Connection lost.")
        case .cancelled: String(localized: "Cancelled.")
        case .sasMismatch: String(localized: "The numbers did not match, so nothing was stored. Someone may be interfering; try again in a different place.")
        case .authCancelled: String(localized: "Cancelled at the unlock check. Nothing was signed.")
        case .keyInvalidated: String(localized: "The key for this machine was invalidated because the phone's Face ID, Touch ID or passcode changed. Delete the machine and enrol it again.")
        case .keyError: String(localized: "The phone's key store failed.")
        case .storage: String(localized: "The machine could not be stored on this phone.")
        case .protocolFailure: String(localized: "The session failed.")
        }
    }

    static func keyStorage(_ s: KeyStorage) -> String {
        switch s {
        case .secureEnclave: String(localized: "Secure Enclave")
        case .software: String(localized: "Software (not hardware-backed)")
        }
    }
}
