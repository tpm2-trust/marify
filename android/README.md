# Marify for Android

The verifier app for [tpm2-kira](https://github.com/mrwiora/tpm2-kira)
machines. Before you type your disk passphrase, the Linux machine advertises
over Bluetooth LE; Marify connects, lets the machine's TPM prove what it
booted, shows a verdict you can act on, and returns a receipt signed with a
hardware key that needs a fresh unlock (fingerprint, face or PIN) for every signature.

## What a check looks like

The machine shows its one-time code at boot and asks for a phone at the same
time. Marify lists it as "asking for attestation" while its advertisement is
heard (and drops it ten seconds after the last one); tap to verify, or let a
running session connect by itself.

The verdict screen then shows, before anything is signed:

- **The headline card**: green *match* (what it booted is what you approved),
  orange *changed* (with what changed and why that may be), or red *failed*.
- **A badge on that card**: a green check **Signed** when the machine's TPM
  signed this boot state as one its owner approved, a red cross **Not
  signed** when it did not, a grey lock **After boot** when the machine is
  up and its TPM keeps the boot key locked until the next boot, by design
  (a check of the running system, say at a remote salt's enrolment). The
  colour answers "is this signature valid" before you read anything. An
  orange card with a green check is a state new to this phone that the
  machine's owner approved (a resealed update).
- **The code card** with an eight-character code. The machine's screen must
  show the same code, as the slot's second code under the one-time code:
  only that machine's TPM, in an approved boot state, could recover it. A
  red card says why there is none (the TPM refused the key, or the machine
  could not answer); a grey one that there is none after boot.
- **The buttons**: "The codes match - continue" or "The codes differ -
  reject" on a match; approve once, approve and remember, or reject on a
  change. Nothing is signed by itself: the PIN or biometric prompt comes
  after your choice.

The construction behind this is tpm2-kira's `docs/SECURITY-BACKGROUND.md`
§3.4 (the boot key) and §13 (who signs what, and why the phone believes it);
the wire format is `docs/PROTOCOL-BLE.md` §7.6.

The normative contract is tpm2-kira's `docs/PROTOCOL-BLE.md`. This app
implements the build brief `docs/mobile/AGENT-PROMPT-ANDROID.md`.

## Build

Requirements: JDK 17+ (Gradle uses or provisions a JDK 25 daemon), Android
SDK with platform 37 and an NDK (`sdkmanager "ndk;30.0.16248370"`), Go ≥ 1.24,
and a tpm2-kira checkout next to this repository (`../../tpm2-kira`) or network
access to clone it.

```sh
./scripts/build-core.sh          # Go core → app/libs/*.aar, from the commit in ../core.properties
./gradlew assembleDebug          # debug APK (includes the demo machine)
./gradlew assembleRelease        # release APK (no kiratest code)
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (device or emulator)
./scripts/test-swtpm.sh               # against a tpm2-kira machine on swtpm (needs go, swtpm)
```

### Release signing

Only a signed release build belongs on a phone that holds real machines
(the debug build is debuggable, shows a red banner and hides real machines
behind a switch). Create the key once and keep it safe — Android installs
updates only when they are signed with the same key:

```sh
keytool -genkeypair -v -keystore ~/keys/kira-release.jks -alias kira \
    -keyalg EC -groupname secp256r1 -validity 10000
```

Then either export the four values for the build

```sh
export KIRA_KEYSTORE=~/keys/kira-release.jks KIRA_KEY_ALIAS=kira
export KIRA_KEYSTORE_PASSWORD=… KIRA_KEY_PASSWORD=…
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

or put them in `keystore.properties` next to `settings.gradle.kts`
(git-ignored; `storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
Without them the release APK is built unsigned. Keep the keystore out of
this directory.

The debug app is `io.wiora.marify.debug` and installs beside the release app
`io.wiora.marify`; neither can read the other's data.

Gradle needs about 3 GB of free RAM. `gradle.properties` is tuned for an 8 GB
machine (one JVM, Kotlin compiled in-process); on such a machine run one task
at a time (lint separately from builds).

### The Go core

`../core.properties` (shared with the iOS app) pins the tpm2-kira commit and the `golang.org/x/mobile`
version. `scripts/build-core.sh` exports that commit with `git archive`, adds
`golang.org/x/mobile` to the *temporary* copy's module graph (the bind needs
it; the pinned sources are not changed), and runs `gomobile bind`:

| Output | Packages | Linked into |
|---|---|---|
| `app/libs/kiracore.aar` | `mobile/kiracore` | release |
| `app/libs/kiracore-debug.aar` | `mobile/kiracore` + `mobile/kiratest` | debug, instrumented tests |

**Deviation from the brief:** the brief asks for `kiracore.aar` plus a separate
`kiratest.aar` in `androidTestImplementation`. Two separately bound gomobile
libraries cannot be linked together — each carries its own `libgojni.so` and
the `go.*` runtime classes — so the debug/test library is one bind of both
packages. The release variant links only `kiracore.aar`; verify with
`apkanalyzer dex packages app/build/outputs/apk/release/*.apk | grep kiratest`
(must print nothing).

To move to a new tpm2-kira commit, change `kira.commit` and rerun the script.

## Architecture

```
app/src/main/java/app/kira/
├── core/      KiraCore.kt     facade over the gomobile package `kiracore`
│              CoreSession.kt  Session/Step as a Kotlin interface (+ GoCoreSession)
│              Events.kt       event JSON → sealed Event, Verdict, RecordSummary
├── ble/       BleScanner.kt   scan with the service-UUID filter only
│              GattLink.kt     connect, MTU 517, discovery, INFO, CCCD, notifications
│              WriteQueue.kt   one GATT write at a time, busy retry with backoff
│              InfoValue.kt    INFO characteristic + version/mode check
├── session/   SessionRunner.kt  drives one CoreSession over one Link (the loop)
│              SessionManager.kt the app's one active session; scan-then-connect
├── keys/      AnchorKeys.kt   StrongBox/TEE P-256 keys, BiometricPrompt per signature
│              NoiseKeyStore.kt static Noise key + verifier_id, Keystore-wrapped
│              KeystoreAead.kt AES-256-GCM under a Keystore key (data at rest)
├── data/      MachineRepository.kt  encrypted record per machine, verbatim JSON
├── service/   AttestationService.kt foreground service (connectedDevice)
└── ui/        Compose screens: onboarding, machines, enrol, session/verdict, details
app/src/debug/   demo machine (kiratest) wiring: DemoLink, DemoMachineSupport
app/src/release/ DemoFactory → NoDemo
```

**The session loop.** Every input — a TX notification, a button press, an
unlock result, a lost link — goes into one inbox and is handled by one
coroutine on a single-threaded dispatcher, in arrival order. Each `Step`'s
fragments are written in order *before* its events are acted on, also when
`err()` is set. Steps produced while handling events (e.g. after creating the
anchor key) are queued behind the current one. The unlock prompt runs
outside the loop and posts its result back into the inbox, so notifications
that arrive meanwhile (event log chunks) are not reordered or blocked.

**No trust decisions in Kotlin.** The app shows `verdict.state`, `reasons`,
`explanation`, `boot_key` and `code`, and passes the user's choice to
`Session.decide` (continue, approve once, approve and remember, reject). It
parses only the INFO characteristic (GATT layer) and the core's event JSON.
The boot key's signature and the code's proof are verified in the core.

**Debug builds** offer a "Demo machine" behind a switch on the enrol screen:
a simulated Thinkpad-X1 from kiratest, with controls on its details screen to
produce *match* (reboot), *changed* (bootloader update) and *failed* (TPM
counter rollback). It has no screen, so the code card says what its screen
would show. Its identity lives only in memory: after an app restart, delete
it and enrol again.

## Security notes (from PROTOCOL-BLE.md §13)

1. The app links `kiracore` and never decides trust itself.
2. Every Step's fragments are written, in order, even when `Err()` is set
   (unit-tested in `SessionRunnerTest`).
3. No BLE pairing or bonding (`createBond` is never called), no encrypted
   characteristics; the link is an untrusted pipe secured by Noise.
4. Scans use only the service-UUID filter.
5. Only machines whose advertisement matches a stored record are shown,
   except ENROL advertisements on the enrol screen.
6. Anchor keys: AndroidKeyStore EC P-256, StrongBox when available, TEE
   otherwise; non-exportable; `setUserAuthenticationParameters(0,
   AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)` — a fresh unlock for every
   signature, by strong biometric **or the screen-lock PIN/pattern/password**;
   invalidated by removal of the screen lock and, if a biometric was enrolled
   when the key was created, by a new biometric enrolment (on a PIN-only phone
   keystore has no biometric to bind, so adding a fingerprint later does not
   invalidate the key).
   Release builds refuse keys that are not in secure hardware. Rejects are
   unsigned and need no prompt.

   **Deviation from PROTOCOL-BLE.md §11.2 / §13.6**, which require biometry:
   PIN-locked phones are supported on purpose. A phone that is merely
   unlocked still cannot sign, but whoever knows (or watched) the PIN can.
   This needs Android 11 (per-use keys accepting device credentials), hence
   `minSdk 30` instead of the brief's 28.
7. PCR hex is only shown behind "Details".
8. A *failed* verdict offers Reject; "Approve anyway" sits in an overflow
   menu and requires typing the machine's name (checked by the core). A
   *match* is not signed by itself either: the person continues or rejects
   after comparing the code with the machine's screen (`Decision.CONTINUE`,
   enforced by the core for matching boots only).
9. Records and the Noise key are AES-GCM encrypted under Keystore keys, in
   `noBackupFilesDir` / DataStore; `allowBackup="false"` and data extraction
   rules exclude everything.
10. The app disconnects on `done`, `error` and whenever the core reports
    `finished()`.

Losing the phone means re-enrolling each machine (`sudo tpm2-kira attest
enrol` with the new phone; `attest unenrol` removes the old entry).

## The remote salt

A machine can ask this phone to keep the salt of its disk key - the remote salt
(tpm2-kira's `docs/PLAN-FACTORRELEASE.md`): a wrapped secret that only
that machine's TPM can open, and only in a boot its owner approved. The
phone never sees the secret itself.

- **Keeping it**: `tpm2-kira remote-salt enrol` on the machine runs an
  ordinary check in which the verdict screen shows the card "Keep this
  machine's remote salt". Accepting the verdict keeps it (replacing any
  kept before); rejecting keeps nothing. It is part of the machine's
  record, in the app's protected storage, like the rest of the record.
- **Handing it back**: after every verdict you accept, once the machine
  has accepted the receipt, the phone sends the remote salt and the session
  shows what the machine made of it ("The machine took the remote salt", or
  that it takes none, or that its TPM refused it because this boot is not
  one its owner approved). The machine still needs your password: the
  salt alone opens nothing.
- **Seeing it**: the machine's details say since when a remote salt is
  kept, and a verdict for such a machine says so above its buttons ("This
  phone keeps the salt of this machine's disk key…"): the match button
  reads "The codes match — attest and return the salt".
- **Without the phone**: the machine's recovery passphrase, in a second
  LUKS keyslot, opens the disk at systemd's own prompt.

The core does all of it (`kiracore`: record field `factor`, verdict event
`factor_offered`, event `release_ack`); the app shows the card, the line
and the row.
