import CoreBluetooth
import LocalAuthentication
import SwiftUI

enum Route: Hashable {
    case enrol
    case session
    case machine(String)
}

/// What the app needs before it can be used; checked on every launch.
enum Requirements {
    static var bluetoothAllowed: Bool { CBCentralManager.authorization == .allowedAlways }

    /// A passcode (and so the keychain's unlock requirement) is set.
    static var screenLockSet: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    static var hasBiometrics: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    static func allMet(central: BleCentral) -> Bool {
        bluetoothAllowed && central.isPoweredOn && screenLockSet
    }
}

struct RootView: View {
    @Environment(AppContainer.self) private var c
    @State private var path: [Route] = []
    @State private var onboarded = false

    var body: some View {
        VStack(spacing: 0) {
            MarifyHeader()
            NavigationStack(path: $path) {
                Group {
                    if onboarded {
                        MachinesView(
                            onEnrol: { path.append(.enrol) },
                            onOpenMachine: { path.append(.machine($0)) },
                            onOpenSession: openSession
                        )
                    } else {
                        OnboardingView(onReady: { onboarded = true })
                    }
                }
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .enrol:
                        EnrolView(onSession: {
                            path.removeAll()
                            path.append(.session)
                        })
                    case .session:
                        SessionView(onClose: { path.removeAll() })
                    case .machine(let id):
                        MachineDetailsView(deviceId: id, onBack: { path.removeLast() }, onSession: openSession)
                    }
                }
            }
            .task {
                await c.central.waitForKnownState()
                onboarded = Requirements.allMet(central: c.central)
                c.store.loadIfNeeded()
            }
        }
    }

    private func openSession() {
        if path.last != .session { path.append(.session) }
    }
}

/// The app's name and logo on every screen; a debug build says so beside
/// them (it is debuggable and may keep keys outside secure hardware).
struct MarifyHeader: View {
    var body: some View {
        HStack(spacing: 10) {
            Image("MarifyLogo")
                .resizable()
                .frame(width: 28, height: 28)
            Text("Marify").font(.headline)
            Spacer()
            #if DEBUG
            Text("DEBUG")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Color(red: 0.70, green: 0.15, blue: 0.12), in: RoundedRectangle(cornerRadius: 6))
                .accessibilityIdentifier("debug_banner")
            #endif
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(Color.accentColor.opacity(0.15))
        .accessibilityIdentifier("marify_header")
    }
}
