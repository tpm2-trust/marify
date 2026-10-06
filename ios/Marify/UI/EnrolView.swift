import SwiftUI

/// A machine advertising for enrolment, as the list shows it.
struct WaitingMachine: Identifiable, @unchecked Sendable {
    let adv: Advertisement
    var id: String { adv.address }
    var rssi: Int { adv.rssi }
}

/// ENROL-mode advertisements are shown only on this screen (PROTOCOL-BLE.md §2.3).
@MainActor
@Observable
final class EnrolModel {
    private(set) var waiting: [WaitingMachine] = []
    /// Real hardware is the default; the demo machine is listed on request.
    var showDemo = false
    private var seen: [String: Advertisement] = [:]
    private var task: Task<Void, Never>?

    func start(central: BleCentral) {
        stop()
        task = Task { [weak self] in
            do {
                for try await adv in central.scan() where adv.isEnrol {
                    guard let self else { return }
                    self.seen[adv.address] = adv
                    let now = monotonicMs()
                    self.seen = self.seen.filter { now - $0.value.seenAtMs < 15_000 }
                    self.waiting = self.seen.values.sorted { $0.rssi > $1.rssi }.map { WaitingMachine(adv: $0) }
                }
            } catch {
                // Bluetooth off or scan failed: the list stays empty.
            }
        }
    }

    func stop() { task?.cancel(); task = nil }
}

struct EnrolView: View {
    @Environment(AppContainer.self) private var c
    @State private var model = EnrolModel()
    var onSession: () -> Void

    private var debugBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    var body: some View {
        List {
            Section {
                Text("On the computer, run:")
                Text("sudo tpm2-kira attest enrol").font(.system(.body, design: .monospaced)).textSelection(.enabled)
                Text("Machines waiting for a phone appear below. Keep the computer's screen in view: you will compare a six-digit number.")
            }
            if debugBuild {
                // A debug build lists real machines like a release build does,
                // so it has to say what it is (TODO-SEC.md S4.2).
                Section {
                    Text("This is a debug build: it is debuggable and may keep keys outside secure hardware. Use the signed release build for machines you rely on.")
                        .foregroundStyle(.red)
                }
            }
            Section {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Searching…")
                }
                ForEach(model.waiting) { m in
                    Button {
                        c.sessions.startEnrol(m.adv)
                        onSession()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "desktopcomputer")
                            VStack(alignment: .leading) {
                                Text("tpm2-kira machine waiting for enrolment").font(.headline)
                                Text("Signal \(m.rssi) dBm").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .tint(.primary)
                }
            }
            if c.sessions.demo.available {
                Section {
                    Toggle("Show the demo machine (simulated, no hardware needed)", isOn: $model.showDemo)
                    if model.showDemo {
                        Button {
                            c.sessions.startDemoEnrol()
                            onSession()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "ladybug")
                                VStack(alignment: .leading) {
                                    Text("Demo machine (debug build)").font(.headline)
                                    Text("Simulated Thinkpad-X1, no hardware needed").font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .tint(.primary)
                    }
                }
            }
        }
        .navigationTitle("Enrol a machine")
        .onAppear { model.start(central: c.central) }
        .onDisappear { model.stop() }
    }
}
