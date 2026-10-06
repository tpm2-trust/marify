# Marify

The phone app that verifies what a [tpm2-kira](https://github.com/mrwiora/tpm2-kira)
machine booted, before you type your disk passphrase: the machine's TPM
proves its boot state over Bluetooth LE, the phone shows a verdict, and
returns a receipt signed with a key that needs a fresh unlock every time.

- [`android/`](android/README.md) — Kotlin / Jetpack Compose; the reference implementation.
- [`ios/`](ios/README.md) — Swift / SwiftUI; same screens, same core, not yet compiled (written without a Mac).
- [`core.properties`](core.properties) — the one tpm2-kira commit both apps link (`mobile/kiracore` via gomobile).

Both apps are "Marify", bundle id `io.wiora.marify`. Rules for working on
them are in [`AGENTS.md`](AGENTS.md); the protocol is tpm2-kira's
`docs/PROTOCOL-BLE.md`, the security design `docs/SECURITY-BACKGROUND.md`.
