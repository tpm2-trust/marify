import SwiftUI

struct SessionView: View {
    @Environment(AppContainer.self) private var c
    var onClose: () -> Void
    @State private var confirmLeave = false

    var body: some View {
        if let r = c.sessions.active {
            let state = r.model.value
            SessionContent(
                state: state,
                canRetry: c.sessions.canRetry,
                demoScreenCode: state.verdict != nil ? c.sessions.demoScreenCode() : nil,
                onConfirmSas: { r.confirmSas($0) },
                onConfirmBind: { bind, bio in r.confirmBind(bind, biometricOnly: bio) },
                onDecide: { d, name in r.decide(d, confirmName: name) },
                onRequestEventlog: { r.requestEventlog() },
                onRetry: { c.sessions.dismiss(); c.sessions.retry() },
                onDone: { c.sessions.dismiss(); onClose() },
                onStop: { confirmLeave = true }
            )
            .navigationBarBackButtonHidden(!state.finished)
            .toolbar {
                if !state.finished {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Stop") { confirmLeave = true }
                    }
                }
            }
            .alert("Stop this session?", isPresented: $confirmLeave) {
                Button("Stop", role: .destructive) { c.sessions.cancel() }
                Button("Continue", role: .cancel) {}
            } message: {
                Text("The machine will be told that you cancelled.")
            }
        } else {
            Color.clear.onAppear(perform: onClose)
        }
    }
}

struct SessionContent: View {
    var state: SessionState
    var canRetry: Bool
    var demoScreenCode: String? = nil
    var onConfirmSas: (Bool) -> Void
    var onConfirmBind: (Bool, Bool) -> Void = { _, _ in }
    var onDecide: (Decision, String) -> Void
    var onRequestEventlog: () -> Void
    var onRetry: () -> Void
    var onDone: () -> Void
    var onStop: () -> Void = {}

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let name = state.machineName ?? String(localized: "a machine")
        let colors = VerdictColors.current(scheme)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if state.phase == .confirmSas, let sas = state.sas {
                    SasContent(sas: sas, onConfirm: onConfirmSas)
                } else if state.phase == .confirmBind, let bind = state.bind {
                    BindContent(bind: bind, onConfirm: onConfirmBind)
                } else if let verdict = state.verdict {
                    VerdictContent(
                        event: verdict,
                        machineName: name,
                        lastAttested: state.lastAttested,
                        decisionEnabled: !state.decisionSent && !state.finished && state.phase == .deciding,
                        demoScreenCode: demoScreenCode,
                        eventlogRequested: state.eventlogRequested,
                        eventlog: state.eventlog,
                        onDecide: onDecide,
                        onRequestEventlog: onRequestEventlog
                    )
                    if !state.finished {
                        switch state.phase {
                        case .signing: Progress(String(localized: "Waiting for your confirmation…"))
                        case .delivering: Progress(String(localized: "Delivering the receipt…"))
                        default: EmptyView()
                        }
                    }
                } else if !state.finished, let u = state.unreachable {
                    UnreachableView(machineName: state.machineName, u: u)
                } else if !state.finished {
                    let text: String = switch state.phase {
                    case .connecting: String(localized: "Connecting…")
                    case .securing: String(localized: "Establishing a secure channel…")
                    case .signing: String(localized: "Waiting for your confirmation…")
                    case .enrolling, .confirmSas: String(localized: "Checking the machine's TPM…")
                    default: String(localized: "Verifying \(name)…")
                    }
                    Progress(text)
                }

                if let notice = state.notice {
                    Text(notice).foregroundStyle(.red).font(.callout)
                }

                if let ack = state.receiptAck {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Receipt delivered").font(.headline)
                            Text(Texts.receiptResult(ack.result))
                            if !ack.message.isEmpty { Text(ack.message).font(.footnote) }
                        }
                    }
                }

                if let ack = state.releaseAck {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Remote salt handed back").font(.headline)
                            Text(Texts.releaseStatus(ack.status))
                            if !ack.message.isEmpty { Text(ack.message).font(.footnote) }
                        }
                    }
                }

                switch state.outcome {
                case .none:
                    EmptyView()
                case .succeeded:
                    if state.mode.isEnrol {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(Color.accentColor)
                        Text("\(name) is bound to this phone").font(.title2.weight(.semibold))
                        Text("From now on, when this machine boots and waits for attestation, Marify will show it here.")
                    }
                    Button(action: onDone) { Text("Done").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                case .failed(let kind, let code, let message):
                    if kind == .machineRefused {
                        RefusedView(name: state.machineName ?? String(localized: "the machine"), detail: message, colors: colors)
                        Button(action: onDone) { Text("Done").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    } else {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.circle").foregroundStyle(.red)
                            Text("The session ended").font(.title2)
                        }
                        Text(Texts.failure(kind))
                        if code != 0 && kind == .protocolFailure { Text(Texts.errorCode(code)) }
                        if !message.isEmpty { Text(message).font(.footnote) }
                        let retryable = kind == .linkLost || kind == .connect || kind == .authCancelled
                        if retryable && canRetry && state.mode.isAttest {
                            Button(action: onRetry) { Text("Try again").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                        }
                        Button(action: onDone) { Text("Done").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// What the phone learnt about the machine during enrolment, before the user
/// binds it (TODO-SEC.md S3, S5). Nothing here comes from the app: the core
/// decided it, the screen only shows it.
private struct BindContent: View {
    var bind: Event.NeedAnchorKey
    var onConfirm: (Bool, Bool) -> Void
    @Environment(\.colorScheme) private var scheme
    @State private var biometricOnly = false

    var body: some View {
        let colors = VerdictColors.current(scheme)
        let verified = !bind.ekVerifiedBy.isEmpty
        Text("Bind \(bind.friendlyName) to this phone?").font(.title2.weight(.semibold))
        CardView(container: verified ? colors.matchContainer : colors.changedContainer,
                 content: verified ? colors.onMatch : colors.onChanged) {
            Text(verified ? String(localized: "TPM verified as genuine: \(bind.ekVerifiedBy)")
                          : String(localized: "TPM not verified as genuine hardware"))
                .font(.headline)
            Text(verified
                 ? String(localized: "The machine's TPM proved it holds an endorsement key certified by its manufacturer.")
                 : String(localized: "Marify could not confirm that this machine's TPM is real hardware (\(bind.ekNote)). This is common for firmware TPMs without a usable certificate. Only continue if you trust the machine's current state: a machine that is already compromised could pretend to have a TPM."))
            if !verified {
                let vendors = KiraCore.ekVendors()
                if !vendors.isEmpty {
                    Text("This version of Marify can verify TPMs from: \(vendors.joined(separator: ", ")).").font(.footnote)
                }
            }
        }
        let pcrs = bind.initrdPcrs.map(String.init).joined(separator: " or ")
        switch bind.initrdCoverage {
        case Event.initrdNotCovered:
            CardView(container: colors.changedContainer, content: colors.onChanged) {
                Text("The checked boot measurements do not include the initrd").font(.headline)
                Text("On this boot the initrd was measured into PCR \(pcrs), which this enrolment does not check. A modified initrd, for example one that records your passphrase, would then show as unchanged. Cancel, and enrol again with “--pcrs” including PCR \(pcrs).")
            }
        case Event.initrdCovered:
            Text("The checked boot measurements include the initrd (PCR \(pcrs)).")
        case Event.initrdNone:
            Text("This machine boots without an initrd.")
        default:
            Text("The machine could not tell where its initrd was measured. Make sure the checked PCRs cover it.")
        }
        if Requirements.hasBiometrics {
            Toggle(isOn: $biometricOnly) {
                VStack(alignment: .leading) {
                    Text("Require Face ID or Touch ID").font(.subheadline.weight(.semibold))
                    Text("For this machine the passcode will not be enough. Changing the biometrics later means enrolling the machine again.").font(.footnote)
                }
            }
        }
        Button { onConfirm(true, biometricOnly) } label: { Text("Bind").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
        Button { onConfirm(false, false) } label: { Text("Cancel").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
    }
}

private struct SasContent: View {
    var sas: String
    var onConfirm: (Bool) -> Void

    var body: some View {
        Text("Compare the numbers").font(.title2.weight(.semibold))
        Text(Self.grouped(sas))
            .font(.system(size: 56, design: .monospaced))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
        Text("Does this match the number on your computer's screen?").font(.headline)
        Button { onConfirm(true) } label: { Text("Matches").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
        Button { onConfirm(false) } label: { Text("Does not match").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
    }

    static func grouped(_ s: String) -> String {
        stride(from: 0, to: s.count, by: 3).map {
            let start = s.index(s.startIndex, offsetBy: $0)
            let end = s.index(start, offsetBy: min(3, s.count - $0))
            return String(s[start..<end])
        }.joined(separator: " ")
    }
}

/// The machine advertised as enrolled but refused this phone (TODO-SEC.md S1):
/// shown with the same weight as a failed attestation, and without a retry.
private struct RefusedView: View {
    var name: String
    var detail: String
    var colors: VerdictColors

    var body: some View {
        CardView(container: colors.failedContainer, content: colors.onFailed) {
            Image(systemName: "xmark.octagon.fill").font(.system(size: 40))
            Text("\(name) did not accept this phone").font(.title2.weight(.semibold))
            Text("The machine is advertising as yours but refused the secure connection, twice. Its enrolment data was changed or removed, or this is a different machine imitating it.")
            Text("Do not type your passphrase. If you did not change the enrolment yourself (for example by running “attest enrol” with another phone), treat the machine as tampered with.").fontWeight(.semibold)
            if !detail.isEmpty { Text(detail).font(.footnote) }
        }
    }
}

/// Connecting keeps failing because the machine cannot be reached; the runner retries.
private struct UnreachableView: View {
    var machineName: String?
    var u: Unreachable

    var body: some View {
        let name = machineName ?? String(localized: "the machine")
        HStack(spacing: 16) {
            ProgressView()
            Text(u.bluetoothOff ? String(localized: "Bluetooth is off") : String(localized: "\(name) is not reachable")).font(.title3)
        }
        Text(u.bluetoothOff
             ? String(localized: "Turn on Bluetooth on this phone. Marify continues looking for \(name) as soon as it is on.")
             : String(localized: "Marify keeps looking for it until you cancel. The machine asks for a phone only while it shows its one-time code at boot (about 90 seconds); once it is at the passphrase prompt, it has stopped asking for this boot. If it is showing the code, it is out of range, its Bluetooth did not start, or (if you test by hand) it is not running “sudo tpm2-kira attest gate”. If its screen shows a red tpm2-kira line saying the enrolment record was not accepted, do not type your passphrase. Marify cannot say anything about this machine until it reaches it — a missing result is not a good result."))
        Text("Attempt \(u.attempt) · \(u.reason)").font(.footnote)
    }
}

struct Progress: View {
    var text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        HStack(spacing: 16) {
            ProgressView()
            Text(text).font(.headline)
        }
    }
}
