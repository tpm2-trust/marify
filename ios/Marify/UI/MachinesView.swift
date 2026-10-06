import SwiftUI

/// A machine the user enrolled, currently asking for attestation nearby.
struct AskingMachine: Identifiable, @unchecked Sendable {
    let machine: Machine
    let adv: Advertisement
    var id: String { machine.deviceId }
}

/// Scans while the machine list is visible and labels ATTEST advertisements
/// with enrolled machines; unmatched ones are someone else's and are never
/// shown (PROTOCOL-BLE.md §2.3). Each stays listed for ten seconds after it
/// was last heard and goes when the machine has stopped advertising (its
/// check is over, or it is out of range). Every look starts from nothing.
@MainActor
@Observable
final class AskingModel {
    private(set) var asking: [AskingMachine] = []
    private var seen: [String: AskingMachine] = [:]
    private var task: Task<Void, Never>?
    private var sweeper: Task<Void, Never>?

    static let staleMs: Int64 = 10_000

    func start(central: BleCentral, store: MachineStore) {
        stop()
        seen = [:]
        asking = []
        task = Task { [weak self] in
            do {
                for try await adv in central.scan() {
                    guard let self, adv.isAttest, let m = store.matchAdvertisement(adv.serviceData) else { continue }
                    self.seen[m.deviceId] = AskingMachine(machine: m, adv: adv)
                    self.publish()
                }
            } catch {
                // Bluetooth off or scan failed: show nothing.
            }
        }
        sweeper = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self else { return }
                let now = monotonicMs()
                let before = self.seen.count
                self.seen = self.seen.filter { now - $0.value.adv.seenAtMs <= Self.staleMs }
                if self.seen.count != before { self.publish() }
            }
        }
    }

    func stop() {
        task?.cancel(); task = nil
        sweeper?.cancel(); sweeper = nil
    }

    private func publish() {
        asking = seen.values.sorted { $0.machine.name.lowercased() < $1.machine.name.lowercased() }
    }
}

struct MachinesView: View {
    @Environment(AppContainer.self) private var c
    @State private var askingModel = AskingModel()
    var onEnrol: () -> Void
    var onOpenMachine: (String) -> Void
    var onOpenSession: () -> Void

    var body: some View {
        let activeState = c.sessions.active?.model.value
        List {
            if let activeState, !activeState.finished {
                Banner(title: String(localized: "A session is in progress"), subtitle: nil, action: onOpenSession)
            }
            if !c.central.isPoweredOn {
                Text("Bluetooth is off. Machines asking for attestation cannot be seen.").foregroundStyle(.red)
            }
            ForEach(askingModel.asking) { a in
                Banner(title: String(localized: "\(a.machine.name) is asking for attestation"),
                       subtitle: String(localized: "Tap to verify")) {
                    c.sessions.startAttest(a.machine, adv: a.adv)
                    onOpenSession()
                }
            }
            if c.store.machines.isEmpty {
                Text("No machines yet. Enrol your computer to start.").foregroundStyle(.secondary)
            }
            ForEach(c.store.machines) { m in
                Button { onOpenMachine(m.deviceId) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "desktopcomputer")
                        VStack(alignment: .leading) {
                            Text(m.name).font(.headline)
                            Text(Texts.dateTime(m.summary.lastAttested).map { String(localized: "Last verified \($0)") }
                                 ?? String(localized: "Not verified yet"))
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
                .tint(.primary)
            }
        }
        .navigationTitle("Machines")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { onEnrol() } label: { Label("Enrol a machine", systemImage: "plus") }
            }
        }
        .onAppear { askingModel.start(central: c.central, store: c.store) }
        .onDisappear { askingModel.stop() }
    }
}

struct Banner: View {
    var title: String
    var subtitle: String?
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "dot.radiowaves.left.and.right")
                VStack(alignment: .leading) {
                    Text(title).font(.headline)
                    if let subtitle { Text(subtitle).font(.subheadline) }
                }
            }
        }
        .listRowBackground(Color.accentColor.opacity(0.15))
    }
}
