# Testing the iOS app

None of this has run yet: the app was written without a Mac. The first
person with Xcode fills in the table. The Android app's `TESTING.md` is the
reference for what each check must show.

## Automated

| Suite | How | Status |
|---|---|---|
| `ParseTests` (event JSON, INFO value, hex) | `⌘U`, or `xcodebuild test -scheme Marify -destination 'platform=iOS Simulator,name=iPhone 16'` | ☐ |
| `SessionRunnerTests` (scripted core and link: fragments on failing steps, order, refusals, retries) | same | ☐ |
| `DemoMachineSessionTests` (real core against kiratest's simulated machine, every verdict state) | same | ☐ |
| `SwtpmMachineSessionTests` (real core against a tpm2-kira machine on swtpm: real TPM quotes, boot key) | `scripts/test-swtpm.sh` (needs `brew install swtpm`, Go, the tpm2-kira checkout next to this repository) | ☐ |

The swtpm suite is skipped unless `MARIFY_SWTPM` names the machine's control
port; the script sets it. The simulator reaches the Mac's loopback directly.

## On a phone

Bluetooth does not work in the simulator; everything below needs an iPhone
and a tpm2-kira machine (the package from `tpm2-kira`, the same commit as
`../core.properties`).

| Check | Result |
|---|---|
| Onboarding: Bluetooth permission asked once; the three steps turn green; Continue enabled only then | ☐ |
| Enrol: the machine appears within seconds of `sudo tpm2-kira attest enrol`; six-digit number equals the machine's; "TPM verified as genuine" or the unverified card; bind → Face ID / Touch ID / passcode prompt → "bound to this phone" | ☐ |
| At boot: the machine shows its code and asks for a phone → "… is asking for attestation" on the list → tap → green card with the green **Signed** check, the eight-character code equals the slot's second code on the machine's screen → "The codes match — continue" → unlock prompt → the machine continues to the passphrase prompt | ☐ |
| After the receipt the "asking for attestation" banner disappears within ten seconds | ☐ |
| Changed after a resealed update: orange card with the green check and "New to this phone, but signed…"; approve and remember; next boot matches | ☐ |
| `attest gate` by hand after boot: red **Not signed** cross, no code, the reason line | ☐ |
| Reject: no unlock prompt; the machine shows the rejection | ☐ |
| Stop while connecting, while deciding, at the unlock prompt: the machine is told each time | ☐ |
| Background: switch apps during a session; the receipt still arrives | ☐ |
| Lock the phone, open the app: the machine list is there (records readable once unlocked) | ☐ |
| Change Face ID enrolment, then verify: "key was invalidated"; delete and enrol again | ☐ |
| A second iPhone or an Android phone enrolled on the same machine: both verify independently | ☐ |
| Release build (TestFlight): no demo switch on the enrol screen; the debug banner is absent | ☐ |
