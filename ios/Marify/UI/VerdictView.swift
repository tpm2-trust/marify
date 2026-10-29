import SwiftUI

/// The verdict as the core produced it (PROTOCOL-BLE.md §10.4, PLAN-BLE.md
/// §6.3). This view only displays: state, explanation and reasons come from
/// the core, and the user's choice goes back to it unchanged.
struct VerdictContent: View {
    var event: Event.VerdictEvent
    var machineName: String
    var lastAttested: String?
    /// This phone keeps the machine's phone salt: accepting returns it to the machine.
    var returnsSalt: Bool = false
    var decisionEnabled: Bool
    /// What the machine's screen shows, where that screen is simulated (the demo machine).
    var demoScreenCode: String? = nil
    var eventlogRequested: Bool
    var eventlog: Event.Eventlog?
    var onDecide: (Decision, String) -> Void
    var onRequestEventlog: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let v = event.verdict
        let colors = VerdictColors.current(scheme)
        VStack(alignment: .leading, spacing: 16) {
            switch v.state {
            case Verdict.match:
                Headline(
                    icon: "checkmark.circle.fill",
                    title: Texts.date(lastAttested).map { String(localized: "\(machineName) — unchanged since \($0)") }
                        ?? String(localized: "\(machineName) — matches its enrolled state"),
                    body: nil,
                    container: colors.matchContainer, content: colors.onMatch, verdict: v, colors: colors
                )
            case Verdict.changed:
                Headline(
                    icon: "exclamationmark.triangle.fill",
                    title: v.explanation.isEmpty ? String(localized: "\(machineName) booted something different") : v.explanation,
                    body: v.bootKey == Verdict.bootKeyProved
                        ? String(localized: "New to this phone, but signed by the machine's TPM as approved by its owner.") : nil,
                    container: colors.changedContainer, content: colors.onChanged, verdict: v, colors: colors
                )
            default:
                Headline(
                    icon: "xmark.octagon.fill",
                    title: String(localized: "Attestation failed"),
                    body: String(localized: "Do not type your passphrase unless you know why:"),
                    container: colors.failedContainer, content: colors.onFailed, verdict: v, colors: colors
                ) {
                    ForEach(v.reasons.filter(\.hard), id: \.code) { r in
                        Text("• " + Texts.reason(r.code))
                    }
                }
            }

            BootKeyCard(v: v, demoScreenCode: demoScreenCode, colors: colors)

            if v.state == Verdict.changed && !v.pcrDiff.isEmpty {
                Text("What changed").font(.headline)
                ForEach(v.pcrDiff, id: \.index) { d in
                    Text("• " + (d.description.isEmpty ? String(localized: "PCR \(d.index)") : d.description))
                }
            }

            if !v.warnings.isEmpty {
                Text("Notes").font(.headline)
                ForEach(v.warnings, id: \.code) { w in Text("• " + Texts.warning(w.code)) }
            }

            if event.eventlogAvailable && v.state != Verdict.match {
                if let eventlog {
                    if eventlog.total > 0 {
                        ProgressView(value: Double(eventlog.received), total: Double(eventlog.total))
                    }
                    Text(eventlog.complete
                         ? String(localized: "Event log received (\(Texts.bytes(eventlog.total)))")
                         : String(localized: "Event log: \(Texts.bytes(eventlog.received)) of \(Texts.bytes(eventlog.total))"))
                        .font(.footnote)
                } else if eventlogRequested {
                    ProgressView()
                } else if decisionEnabled {
                    Button("Show event log", action: onRequestEventlog).buttonStyle(.bordered)
                }
            }

            if event.factorOffered { FactorOfferCard() }

            if decisionEnabled && event.needsDecision {
                switch v.state {
                case Verdict.match: MatchDecisions(v: v, returnsSalt: returnsSalt, onDecide: onDecide)
                case Verdict.changed: ChangedDecisions(returnsSalt: returnsSalt, onDecide: onDecide)
                default: FailedDecisions(machineName: machineName, onDecide: onDecide)
                }
            }

            TechnicalDetails(v: v)
        }
    }
}

private struct Headline<Extra: View>: View {
    var icon: String
    var title: String
    var body_: String?
    var container: Color
    var content: Color
    var verdict: Verdict
    var colors: VerdictColors
    var extra: () -> Extra

    init(icon: String, title: String, body: String?, container: Color, content: Color, verdict: Verdict, colors: VerdictColors,
         @ViewBuilder extra: @escaping () -> Extra) {
        self.icon = icon
        self.title = title
        self.body_ = body
        self.container = container
        self.content = content
        self.verdict = verdict
        self.colors = colors
        self.extra = extra
    }

    var body: some View {
        CardView(container: container, content: content) {
            HStack {
                Image(systemName: icon).font(.system(size: 36))
                Spacer()
                SignatureBadge(v: verdict, colors: colors)
            }
            Text(title).font(.title2.weight(.semibold))
            if let body_ { Text(body_) }
            extra()
        }
    }
}

extension Headline where Extra == EmptyView {
    init(icon: String, title: String, body: String?, container: Color, content: Color, verdict: Verdict, colors: VerdictColors) {
        self.init(icon: icon, title: title, body: body, container: container, content: content, verdict: verdict, colors: colors) { EmptyView() }
    }
}

/// The one thing to see at a glance, on every card: a green check when the
/// machine's TPM signed this boot state as approved, a red cross when it did
/// not - and a grey "after boot" when the key is locked until the next boot
/// by design (a check of the running system, as at a phone salt's
/// enrolment). The words below say why.
private struct SignatureBadge: View {
    var v: Verdict
    var colors: VerdictColors

    var body: some View {
        let valid = v.bootKey == Verdict.bootKeyProved
        let locked = v.bootKey == Verdict.bootKeyLocked
        HStack(spacing: 6) {
            Image(systemName: valid ? "checkmark.circle.fill" : (locked ? "lock.fill" : "xmark.circle.fill"))
            Text(valid ? String(localized: "Signed") : (locked ? String(localized: "After boot") : String(localized: "Not signed")))
                .font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(valid ? colors.matchContainer : (locked ? Color(.secondarySystemFill) : colors.failedContainer), in: Capsule())
        .foregroundStyle(valid ? colors.onMatch : (locked ? Color.secondary : colors.onFailed))
        .accessibilityIdentifier(valid ? "signature_valid" : (locked ? "signature_locked" : "signature_invalid"))
    }
}

/// The code the machine's screen must show, or one line on why there is
/// none. The signature itself is the badge on the headline; this card is
/// for the comparison.
private struct BootKeyCard: View {
    var v: Verdict
    var demoScreenCode: String?
    var colors: VerdictColors

    var body: some View {
        let valid = v.bootKey == Verdict.bootKeyProved
        let locked = v.bootKey == Verdict.bootKeyLocked
        CardView(container: valid ? colors.matchContainer : (locked ? Color(.secondarySystemFill) : colors.failedContainer),
                 content: valid ? colors.onMatch : (locked ? Color.secondary : colors.onFailed)) {
            if valid {
                Text("Code on the machine's screen").font(.headline)
                Text(v.code)
                    .font(.system(size: 34, weight: .semibold, design: .monospaced))
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("boot_code_value")
                Text(demoScreenCode.map { String(localized: "The demo machine's screen shows: \($0)") }
                     ?? String(localized: "The machine must show the same code."))
                    .font(.callout)
                if !v.signingKey.isEmpty {
                    Text("Signing key …\(Self.shortKey(v.signingKey))").font(.footnote)
                }
            } else {
                let why: String = switch v.bootKey {
                case Verdict.bootKeyRefused:
                    String(localized: "No code: the TPM did not release the boot key. This boot state is not approved by the machine's owner (an update not yet approved, or asked after the code screen).")
                case Verdict.bootKeyInvalid:
                    Texts.reason("boot_proof_invalid")
                case Verdict.bootKeyLocked:
                    String(localized: "No code: the machine is up, and its TPM keeps the boot key locked until the next boot, by design. The quote above says this is the running system of a boot you know; it does not say the boot was approved. The next boot shows a code again.")
                default:
                    String(localized: "No code: the machine could not answer with its boot key.")
                }
                Text(why).font(.callout)
            }
        }
        .accessibilityIdentifier(valid ? "boot_key_valid" : (locked ? "boot_key_locked" : "boot_key_invalid"))
    }

    static func shortKey(_ hex: String) -> String {
        let tail = String(hex.suffix(16))
        return stride(from: 0, to: tail.count, by: 4).map {
            let start = tail.index(tail.startIndex, offsetBy: $0)
            let end = tail.index(start, offsetBy: min(4, tail.count - $0))
            return String(tail[start..<end])
        }.joined(separator: " ")
    }
}

/// The machine asks this phone to keep its phone salt: a wrapped secret only
/// that machine's TPM can open, returned after every accepted verdict so the
/// disk's key can be derived. Kept when this verdict is accepted.
private struct FactorOfferCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Keep this machine's phone salt").font(.headline)
            Text("The machine asks this phone to keep the salt of its disk key - the phone salt: a sealed value only its own TPM can open, in a boot it has approved. Accepting this verdict keeps it, replacing any kept before. From then on it is handed back after every verdict you accept, and the machine needs it together with your password to unlock the disk.")
                .font(.body)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityIdentifier("factor_offer")
    }
}

/// The one line that says what accepting does besides signing: the salt goes back to the machine.
private struct ReturnsSaltNote: View {
    var body: some View {
        Text("This phone keeps the salt of this machine's disk key. Accepting attests the boot and returns the salt to the machine, which needs it together with your password.")
            .font(.callout)
            .accessibilityIdentifier("returns_salt_note")
    }
}

/// A matching boot still waits for the person: they compare the code with the
/// machine's screen and only then let the receipt be signed.
private struct MatchDecisions: View {
    var v: Verdict
    var returnsSalt: Bool
    var onDecide: (Decision, String) -> Void

    var body: some View {
        let proved = v.bootKey == Verdict.bootKeyProved
        if returnsSalt { ReturnsSaltNote() }
        Button { onDecide(.continue, "") } label: {
            Text(proved
                 ? (returnsSalt ? String(localized: "The codes match — attest and return the salt") : String(localized: "The codes match — continue"))
                 : (returnsSalt ? String(localized: "Attest and return the salt") : String(localized: "Continue")))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .accessibilityIdentifier("continue")
        Button { onDecide(.reject, "") } label: {
            Text(proved ? String(localized: "The codes differ — reject") : String(localized: "Reject")).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier("reject")
    }
}

private struct ChangedDecisions: View {
    var returnsSalt: Bool
    var onDecide: (Decision, String) -> Void

    var body: some View {
        if returnsSalt { ReturnsSaltNote() }
        Button { onDecide(.approveOnce, "") } label: { Text("Approve once").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent).accessibilityIdentifier("approve_once")
        Button { onDecide(.approveRemember, "") } label: { Text("Approve and remember").frame(maxWidth: .infinity) }
            .buttonStyle(.bordered).accessibilityIdentifier("approve_remember")
        Button { onDecide(.reject, "") } label: { Text("Reject").frame(maxWidth: .infinity) }
            .buttonStyle(.bordered).accessibilityIdentifier("reject")
    }
}

private struct FailedDecisions: View {
    var machineName: String
    var onDecide: (Decision, String) -> Void
    @State private var dialog = false
    @State private var typed = ""

    var body: some View {
        HStack {
            Button(role: .destructive) { onDecide(.reject, "") } label: { Text("Reject").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .accessibilityIdentifier("reject")
            Menu {
                Button("Approve anyway…") { dialog = true }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title2)
            }
            .accessibilityIdentifier("more")
        }
        .alert("Approve a failed attestation?", isPresented: $dialog) {
            TextField("Machine name", text: $typed)
            // The core checks the name; the button only avoids an obvious typo round trip.
            Button("Approve") { onDecide(.approveOnce, typed) }.disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("A hard check failed. Approving tells the machine that this boot is trusted. Type the machine's name, \(machineName), to confirm.")
        }
    }
}

/// PCR hex and counters, collapsed by default (PROTOCOL-BLE.md §13.7).
private struct TechnicalDetails: View {
    var v: Verdict
    @State private var open = false

    var body: some View {
        Button(open ? "Technical details" : "Details") { open.toggle() }
            .accessibilityIdentifier("details")
        if open {
            VStack(alignment: .leading, spacing: 6) {
                if !v.diffAgainst.isEmpty { Text("Compared with: \(v.diffAgainst)").font(.footnote) }
                ForEach(v.reasons, id: \.code) { r in Text("\(r.code): \(r.detail)").font(.footnote) }
                ForEach(v.pcrDiff, id: \.index) { d in
                    Divider()
                    Text("PCR \(d.index) — \(d.description)").font(.subheadline.weight(.semibold))
                    Text("expected \(d.expected)").font(.system(.footnote, design: .monospaced))
                    Text("actual \(d.actual)").font(.system(.footnote, design: .monospaced))
                }
                Divider()
                Text("Reset count \(v.resetCount) · restart count \(v.restartCount) · firmware \(v.firmwareVersion) · clock safe: \(v.clockSafe ? String(localized: "yes") : String(localized: "no"))")
                    .font(.footnote)
            }
        }
    }
}
