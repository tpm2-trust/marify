import SwiftUI

struct MachineDetailsView: View {
    @Environment(AppContainer.self) private var c
    var deviceId: String
    var onBack: () -> Void
    var onSession: () -> Void
    @State private var confirmDelete = false

    var body: some View {
        if let machine = c.store.get(deviceId) {
            let s = machine.summary
            let key = c.anchorKeys.details(deviceId: deviceId)
            let isDemo = c.sessions.demo.owns(recordJSON: machine.recordJSON)
            List {
                Section {
                    if let at = Texts.dateTime(s.enrolledAt) { Text("Enrolled \(at)") }
                    Text(Texts.dateTime(s.lastAttested).map { String(localized: "Last verified \($0)") } ?? String(localized: "Not verified yet"))
                    Button {
                        c.sessions.startAttest(machine)
                        onSession()
                    } label: { Text("Verify now").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                }
                Section("Approved boot states") {
                    ForEach(s.profiles, id: \.name) { p in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.name)
                            if !p.addedBy.isEmpty { Text("Added by \(p.addedBy)").font(.footnote).foregroundStyle(.secondary) }
                            if let until = Texts.dateTime(p.validUntil) { Text("Valid until \(until)").font(.footnote).foregroundStyle(.secondary) }
                            if let n = p.usesLeft { Text(n == 1 ? String(localized: "1 use left") : String(localized: "\(n) uses left")).font(.footnote).foregroundStyle(.secondary) }
                        }
                    }
                }
                Section("TPM") {
                    Text(s.ekVerifiedBy.isEmpty ? String(localized: "Not verified as genuine hardware") : String(localized: "Verified as genuine: \(s.ekVerifiedBy)"))
                }
                Section("Key storage") {
                    if let key {
                        Text(Texts.keyStorage(key.storage))
                        Text(key.biometricOnly ? String(localized: "Unlocked by Face ID or Touch ID only") : String(localized: "Unlocked by Face ID, Touch ID or passcode"))
                        if key.storage == .secureEnclave { Text("Unlock for every signature, enforced by the Secure Enclave").font(.footnote) }
                    } else {
                        Text("No key on this phone")
                    }
                }
                if isDemo {
                    Section("Demo machine controls") {
                        Button("Reboot (match)") { c.sessions.demo.nextBoot() }
                        Button("Update bootloader (changed)") { c.sessions.demo.changePcr(4) }
                        Button("Roll back TPM counter (failed)") { c.sessions.demo.rollbackResetCount() }
                        Text("The demo machine exists only while the app runs. After a restart, delete it and enrol again.").font(.footnote)
                    }
                }
                Section {
                    Button("Delete machine", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle(machine.name)
            .alert("Delete \(machine.name)?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) {
                    c.anchorKeys.delete(deviceId: deviceId)
                    try? c.store.delete(deviceId)
                    onBack()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This phone forgets the machine and destroys its key. On the machine, also run: sudo tpm2-kira attest unenrol")
            }
        } else {
            Color.clear.onAppear(perform: onBack)
        }
    }
}
