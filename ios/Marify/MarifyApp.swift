import SwiftUI

/// Everything the screens share. Created once; the Bluetooth manager inside
/// it asks for the Bluetooth permission the first time it exists, which the
/// onboarding screen times.
@MainActor
@Observable
final class AppContainer {
    let central = BleCentral()
    let store = MachineStore()
    let noise = NoiseKeyStore()
    let anchorKeys = SecureEnclaveAnchorKeys()
    let sessions: SessionManager

    init() {
        sessions = SessionManager(central: central, store: store, noise: noise, keys: anchorKeys, demo: DemoFactory.create())
    }
}

@main
struct MarifyApp: App {
    @State private var container = AppContainer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container)
                .onChange(of: scenePhase) { _, phase in
                    // Records are readable only while unlocked: try again on every return.
                    if phase == .active { container.store.loadIfNeeded() }
                }
        }
    }
}
