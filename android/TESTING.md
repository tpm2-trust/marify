# Testing

## Status (2026-10-06)

The figures below are from the first full run on 2026-10-05; the commit of the
core in use is in `core.properties`. Re-run on 2026-10-06 against tpm2-kira's
current tree: lint clean, all JVM tests, all emulator tests (now including the
enrol list and the permission request) and all 7 swtpm tests pass.

| Check | Result |
|---|---|
| `scripts/build-core.sh` (the commit in `core.properties`, NDK 30, gomobile 2026-09-08) | ✅ both .aar built; generated Java API matches the brief |
| `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest` (AGP 9.4.1 / Gradle 9.8.0, JDK 25) | ✅ |
| `testDebugUnitTest` — 46 JVM tests | ✅ all pass |
| `lintDebug`, `lintRelease` | ✅ no issues |
| No kiratest in release (`apkanalyzer dex packages … \| grep -c kiratest`) | ✅ 0 in release, 37 classes in debug |
| R8 keeps the JNI classes (`go.**`, `kiracore.**`; rules ship in the .aar) | ✅ |
| `connectedDebugAndroidTest` — 9 demo-machine + 3 Compose UI + 3 keystore tests, emulator API 36 x86_64 with PIN 1234 | ✅ all 15 pass |
| Manual, emulator, PIN-only (no biometrics): onboarding passes; demo enrolment and a *match* attestation each signed via the system PIN prompt with the real keystore key; "The machine accepted the receipt" | ✅ (emulator keystore is software-only, accepted in debug builds) |
| App launches on the emulator (onboarding) | ✅ |
| `scripts/test-swtpm.sh` — app session code + Go core against a tpm2-kira machine on swtpm (§5), emulator API 36 | ✅ all 7 pass |
| Real hardware checklist (§4) | ⏳ needs a phone and a Linux machine with Bluetooth |

Re-run everything:

```sh
./gradlew testDebugUnitTest assembleRelease lintDebug lintRelease
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex packages \
    app/build/outputs/apk/release/app-release-unsigned.apk | grep -c kiratest   # expect 0
adb shell locksettings set-pin 1234   # AnchorKeysTest needs a screen lock (skipped otherwise)
./gradlew connectedDebugAndroidTest   # emulator (API 34+, x86_64) or phone attached
```

## 1. Unit tests (JVM, `app/src/test`)

| Test | Covers |
|---|---|
| `EventParseTest` | every event type of PROTOCOL-BLE.md §10.3, omitted (omitempty) fields, record JSON kept verbatim, unknown types |
| `WriteQueueTest` | one write in flight, order, busy retry with backoff, persistent busy, GATT failure, missing ack timeout, cancel |
| `SessionRunnerTest` | connecting retried every 5 s while the machine is not reachable, without giving up (half an hour checked) until cancelled; Bluetooth off waited out the same way; other connection errors not retried; fragments of a failing Step written before disconnect; recoverable error keeps the session; abort writes its fragments; notification order; match → automatic signing → receipt_ack → record_updated → done; unlock-prompt cancel aborts; typed-name decision passed through unchanged; link loss; wrong INFO mode; enrolment stores the record |
| `InfoValueTest` | INFO parsing and protocol/schema/mode checks |
| `TextsTest` | every reason, warning, error and receipt-result code has its own text |

## 2. Instrumented tests with the demo machine (`app/src/androidTest`)

`DemoMachineSessionTest` runs the real `kiracore` against `kiratest` over
`DemoLink`, with `SoftwareAnchorKeys` instead of the keystore key and its unlock prompt:

- enrolment (SAS equals `lastSAS()`), then `match` after `nextBoot()` — at MTU 517 and 23
- `changed` (`changePCR(4)`) + event log transfer + approve-and-remember, then the next boot matches the remembered profile
- `failed` (`rollbackResetCount()`) + reject (unsigned, no prompt)
- `failed` + approve with a wrong, then the right typed name
- user abort mid-session; unlock-prompt cancel; SAS mismatch stores nothing
- `matchAdvertisement` accepts only the enrolled machine's service data

Each asserts `demo.outcome(5000)` (`enrolled`, `receipt: verdict=ok authentic=true`, …).

`AnchorKeysTest` creates the real AndroidKeyStore key and checks with
`KeyInfo`: non-exportable, authentication for every use (timeout 0),
`AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL`, invalidated by biometric
enrolment (asserted only when a biometric is enrolled), and that signing without authentication is refused.

## 3. Compose UI tests

`VerdictScreenTest` renders the three verdict screens from fixed JSON:
match (no decisions), changed (explanation, diff, hex only behind Details,
event log button, three decisions), failed (hard reasons only, Reject primary,
"Approve anyway" in the overflow with typed name).

## 4. Real hardware checklist

Fill in per device:

| Scenario | Pixel (StrongBox) | non-Pixel | Notes |
|---|---|---|---|
| `sudo tpm2-kira attest enrol` → SAS matches → bound | | | |
| Enrol screen lists the real machine without any switch (debug build: with its warning card) | | | |
| Machine details show StrongBox / TEE, hardware-enforced auth | | | |
| `sudo tpm2-kira attest gate` → "asking" banner → match → receipt accepted | | | |
| At boot: machine shows its code and the phone is asked at the same time → the phone shows the eight-character code and the machine's screen shows the same → "The codes match — continue" → PIN → the machine continues to the passphrase prompt | | | |
| After the receipt the "asking for attestation" banner disappears within ten seconds (the machine stopped advertising) | | | |
| Match: green card with the green "Signed" check; changed after a resealed update: orange card with the green check; `attest gate` by hand after boot: red "Not signed" cross and no code | | | |
| Kernel update, resealed by the hook → the phone shows "changed" **with** the code (the signing key approved the new state) → approve and remember | | | |
| `attest gate` by hand on the unlocked system → "The machine's TPM did not release its boot key", no code, match can still be continued | | | |
| At boot, phone ignored: Enter (or 90 s) continues to the passphrase prompt; from then on the phone shows "not reachable" (the machine has stopped asking for this boot) | | | |
| At boot, phone connects in the last seconds of the 90: the machine waits for the answer (up to a minute more), then continues | | | |
| At boot, reject on the phone: the machine shows the rejection and does not continue early | | | |
| Second phone enrolled, initramfs **not** rebuilt → both phones attest at the next boot | | | |
| Phone removed with `attest unenrol` (asks for the signing key; the TOTP code is unchanged), re-enrolled → old entry deleted in the app, new one attests | | | |
| `attest enrol` on a slot without a sealed TOTP key → refused with the `seal` command to run | | | |
| Kernel update → reseal hook runs → the phone is still enrolled and attests (changed, approve once) | | | |
| `nvram delete --nvram N` → slot gone whole; the phone shows the machine as not reachable | | | |
| Machine's gate unit runs confined (`journalctl -b -u tpm2-kira-attest.service` shows no refused operation) | | | |
| Kernel/bootloader update → changed → approve and remember → next boot matches | | | |
| Bluetooth off mid-session → "Connection lost", Try again | | | |
| App killed mid-session → machine times out, next session works | | | |
| Two machines in range → each labelled correctly, strangers not shown | | | |
| New fingerprint enrolled → key invalidated message | | | |
| PIN-only phone (no biometrics): enrol and attest with the PIN prompt | | | |
| Phone with biometrics: "Use PIN" in the prompt also signs | | | |

## 5. Against a machine on a software TPM (swtpm)

`scripts/test-swtpm.sh` runs `app.kira.swtpm.SwtpmMachineSessionTest`: the
app's `SessionRunner` and the real Go core on the emulator or a phone,
against tpm2-kira's **production machine code** (`tpmBackend`) on swtpm —
real EK and AK, real `TPM2_Quote`, real `ActivateCredential`, the
attestation blob written to NV with PolicySigned. Only BLE is replaced, by
TCP through `adb reverse`.

```sh
sudo pacman -S go swtpm          # once
./scripts/test-swtpm.sh          # KIRA_SRC=… if tpm2-kira is not at ../../tpm2-kira
```

| Test | What happens on the machine |
|---|---|
| `enrolThenMatch`, `…AtMinimumMtu` | enrolment (SAS equals the console's), reboot, *match*, receipt accepted — MTU 517 and 23 |
| `changedWithEventlogApproveAndRememberThenMatch` | PCR 4 extended → *changed* with a diff on PCR 4; the 48 kB event log transferred; approve and remember; after a reboot the same change matches the remembered profile |
| `replayedTpmStateFailsAndIsRejected` | TPM state saved, later restored (a replayed TPM) → *failed* with `reset_count_decreased`; unsigned reject delivered |
| `retriesEveryFiveSecondsUntilTheMachineWaits` | the machine starts waiting 7 s after the app; the app shows *not reachable* after the attempts at 0 s and 5 s and gets through at 10 s |
| `userAbortsMidSession` | the machine receives "peer aborted (code 10)" |
| `strangerPhoneGetsNoAnswer` | another phone's Noise key: the machine refuses the IK handshake ("initiator is not an enrolled verifier") and the app sees the connection close |

Isolation:

- **Machine side:** `TestAppMachine` in tpm2-kira `cmd/attest_app_test.go`,
  build tag `apptest`; it runs only with `KIRA_APPTEST_ADDR` set. The swtpm
  helpers it shares with the `integration` suite live in
  `cmd/swtpm_helpers_test.go` (swtpm can now be power-cycled on the same
  state: orderly `TPM2_Shutdown`, restart). The `integration` suite's attest
  tests still pass unchanged.
- **App side:** test sources only (`androidTest/…/swtpm/`: `TcpLink`,
  `SwtpmMachine`, the test class). The regular `connectedDebugAndroidTest`
  excludes the package (`notPackage`); the script includes it with
  `-Pkira.swtpm`. The debug app has the `INTERNET` permission for this
  (instrumented tests run in the app's process); release builds have none.
- **Wire between them:** control port (text commands: `enrol`, `attest`,
  `reboot`, `extend`, `snapshot`, `rollback`, `sas`, `outcome`, `quit`) and
  data port (8-byte INFO, then u16-length-prefixed fragments). The machine's
  log is `app/build/swtpm-machine.log`.

The machine runs from the tpm2-kira working tree, the app's core from the
commit in `core.properties`; keep them on the same protocol version.
