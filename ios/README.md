# Marify for iOS

The verifier app for [tpm2-kira](https://github.com/mrwiora/tpm2-kira)
machines, the iOS counterpart of [../android](../android/README.md). Before
you type your disk passphrase, the Linux machine advertises over Bluetooth
LE; Marify connects, lets the machine's TPM prove what it booted, shows a
verdict you can act on, and returns a receipt signed with a Secure Enclave
key that needs a fresh unlock (Face ID, Touch ID or passcode) for every
signature.

Both apps link the same Go core (`mobile/kiracore` of tpm2-kira) at the
commit pinned in [`../core.properties`](../core.properties), and show the
same screens: see "What a check looks like" in the Android README; it holds
for this app word for word, with Face ID / Touch ID / passcode in place of
fingerprint / face / PIN.

The normative contract is tpm2-kira's `docs/PROTOCOL-BLE.md`. This app
implements the build brief `docs/mobile/AGENT-PROMPT-IOS.md`.

## Status

Written without a Mac at hand: the code has not been compiled or run yet.
Expect a round of compiler findings in Xcode (see "First build" below), then
the test suites say what else is wrong. The Android app's behaviour is the
reference; where the two disagree, the Android app is right.

## Build

Needs macOS with Xcode 16 or later, Go 1.24 or later, and
[XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
cd ios
./scripts/build-core.sh      # Go core → Frameworks/*.xcframework, from the commit in ../core.properties
xcodegen generate            # project.yml → Marify.xcodeproj
open Marify.xcodeproj
```

Set `DEVELOPMENT_TEAM` in `project.yml` (or in Xcode's Signing settings) to
your Apple Developer Team ID before building for a device or TestFlight.

### First build

Things to check once in Xcode, in this order:

1. `ls Frameworks/Kiratest.xcframework` must list `ios-arm64` and
   `ios-arm64_x86_64-simulator`; `project.yml` names these slices for the
   Debug-only link of the demo machine. If gomobile names them differently,
   adjust the two `FRAMEWORK_SEARCH_PATHS` lines (app and test target).
2. The generated Objective-C names of the core. `scripts/build-core.sh`
   leaves the headers in `Frameworks/Kiracore.xcframework/*/Kiracore.framework/Headers/Kiracore.objc.h`;
   `Marify/Core/KiraCore.swift`, `CoreSession.swift` and
   `Demo/DemoMachineSupport.swift` are the only files that call them.
3. Swift concurrency findings: the project builds in Swift 5 language mode
   with `SWIFT_STRICT_CONCURRENCY=targeted`; the actors and `@MainActor`
   classes are meant to hold up under Swift 6 too, but have not been checked.
4. Run the tests (`⌘U`): `ParseTests`, `SessionRunnerTests` (scripted core,
   no hardware) and `DemoMachineSessionTests` (the real core against
   kiratest's simulated machine, in the simulator).

## Layout

```
ios/
├── project.yml               XcodeGen project: app + unit tests, Debug-only kiratest link
├── scripts/build-core.sh     gomobile bind → Frameworks/Kiracore.xcframework, Kiratest.xcframework
├── scripts/test-swtpm.sh     the swtpm machine tests (see TESTING.md)
├── Marify/
│   ├── MarifyApp.swift       @main, AppContainer (central, store, keys, sessions)
│   ├── Core/                 KiraCore.swift facade, CoreSession.swift, Events.swift (event JSON → enum)
│   ├── BLE/                  Link protocol + INFO value, BleCentral (scan + connect), GattLink, Async helpers
│   ├── Session/              SessionState, SessionRunner (actor), SessionManager (@MainActor)
│   ├── Keys/                 SecureEnclaveAnchorKeys, NoiseKeyStore, Keychain
│   ├── Data/                 MachineStore (records, complete protection, no backup)
│   ├── Demo/                 DemoLink + DemoMachineSupport (#if DEBUG, kiratest)
│   └── UI/                   SwiftUI: Root/Onboarding, Machines, Enrol, Session, Verdict, MachineDetails, Theme/Texts
└── MarifyTests/              ParseTests, SessionRunnerTests, DemoMachineSessionTests, Swtpm/
```

**No trust decisions in Swift.** The app shows `verdict.state`, `reasons`,
`explanation`, `boot_key` and `code`, and passes the user's choice to
`Session.decide` (continue, approve once, approve and remember, reject). It
parses only the INFO characteristic (GATT layer) and the core's event JSON.
The boot key's signature and the code's proof are verified in the core.

**Debug builds** offer a "Demo machine" behind a switch on the enrol screen:
a simulated Thinkpad-X1 from kiratest, with controls on its details screen to
produce *match* (reboot), *changed* (bootloader update) and *failed* (TPM
counter rollback). It has no screen, so the code card says what its screen
would show. Its identity lives only in memory: after an app restart, delete
it and enrol again. Release builds do not link kiratest.

## Security notes (from PROTOCOL-BLE.md §13)

1. The app links `kiracore` and never decides trust itself.
2. Every Step's fragments are written, in order, even when `err()` is set
   (`SessionRunnerTests`).
3. No pairing or bonding, no encrypted characteristics; the link is an
   untrusted pipe secured by Noise. Writes go out with response, one at a
   time, MTU − 3 bytes each (no long writes).
4. Scans use only the service-UUID filter.
5. Only machines whose advertisement matches a stored record are shown,
   except ENROL advertisements on the enrol screen.
6. Anchor keys: Secure Enclave P-256, non-exportable, access control
   `privateKeyUsage + biometryCurrentSet OR devicePasscode` — a fresh unlock
   for every signature (a new `LAContext` per signature, never reused), by
   Face ID / Touch ID **or the passcode**; a changed biometric set
   invalidates the key. "Require Face ID or Touch ID" at binding drops the
   passcode alternative for that machine.

   **Deviation from PROTOCOL-BLE.md §11.2 / §13.6**, which require biometry:
   the passcode is accepted on purpose, as on Android. A phone that is
   merely unlocked still cannot sign, but whoever knows (or watched) the
   passcode can.

   iOS has no key attestation for Secure Enclave keys: the machine is told
   there is none and records that (as for the Android ROMs without one).
7. PCR hex is only shown behind "Details".
8. A *failed* verdict offers Reject; "Approve anyway" sits in a menu and
   requires typing the machine's name (checked by the core). A *match* is
   not signed by itself either: the person continues or rejects after
   comparing the code with the machine's screen (`Decision.continue`).
9. Records are files under Application Support with complete data
   protection (encrypted at rest with a key the device releases only while
   unlocked) and excluded from backups; the Noise key and verifier id are
   keychain items marked *this device only*. Nothing syncs through iCloud.
10. The app disconnects on `done`, `error` and whenever the core reports
    `finished()`. A session keeps its connection while the app is in the
    background (`bluetooth-central` background mode); the lists scan only
    while shown.

Losing the phone means re-enrolling each machine (`sudo tpm2-kira attest
enrol` with the new phone; `attest unenrol` removes the old entry).

## The disk factor

Same as the Android app (`../android/README.md`, "The disk factor"): the
verdict view shows the "Keep this machine's disk factor" card when the
evidence offers one, the session view shows what the machine made of the
released factor, and the machine's details say since when one is kept.
All of it comes from the core; the app has no key material of its own.
