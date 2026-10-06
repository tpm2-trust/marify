import CoreBluetooth
import SwiftUI

/// Three requirements, each with a button that leads to it: Bluetooth
/// permission, Bluetooth on, a passcode set.
struct OnboardingView: View {
    @Environment(AppContainer.self) private var c
    @Environment(\.scenePhase) private var scenePhase
    var onReady: () -> Void

    @State private var bluetoothState: CBManagerState = .unknown
    @State private var screenLock = Requirements.screenLockSet

    private var allowed: Bool { Requirements.bluetoothAllowed }
    private var poweredOn: Bool { bluetoothState == .poweredOn }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Know what your computer booted").font(.title.weight(.semibold))
                Text("Before you type your disk passphrase, your Linux machine asks this phone to check it. The machine's TPM signs a statement of what it booted; Marify compares it with what it recorded when you bound the machine, shows you the result, and sends back a receipt signed with a key that only your Face ID, Touch ID or passcode can unlock.")

                step(done: allowed, title: String(localized: "Allow Bluetooth"),
                     body: String(localized: "Marify talks to your machine over Bluetooth Low Energy. It only looks for tpm2-kira machines and never uses your location."),
                     action: String(localized: "Open settings")) { openSettings() }

                step(done: poweredOn, title: String(localized: "Turn on Bluetooth"),
                     body: String(localized: "Bluetooth is off. Machines asking for attestation cannot be seen."),
                     action: String(localized: "Open settings")) { openSettings() }

                step(done: screenLock, title: String(localized: "Set up a passcode"),
                     body: String(localized: "Every receipt is signed with a key that needs a fresh unlock — your passcode, Face ID or Touch ID — so that someone holding your unlocked phone cannot vouch for a machine without knowing your passcode."),
                     action: String(localized: "Open settings")) { openSettings() }

                Button(action: onReady) {
                    Text("Continue").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!(allowed && poweredOn && screenLock))
            }
            .padding(24)
        }
        .onAppear { refresh() }
        .onChange(of: scenePhase) { _, p in if p == .active { refresh() } }
        .task {
            c.central.onStateChange = { state in
                Task { @MainActor in bluetoothState = state }
            }
            await c.central.waitForKnownState()
            refresh()
        }
    }

    private func refresh() {
        bluetoothState = c.central.state
        screenLock = Requirements.screenLockSet
    }

    @ViewBuilder
    private func step(done: Bool, title: String, body: String, action: String, _ act: @escaping () -> Void) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? Color.green : Color.secondary)
                .font(.title2)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.headline)
                if !done {
                    Text(body).font(.subheadline)
                    Button(action, action: act).buttonStyle(.bordered)
                }
            }
        }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
