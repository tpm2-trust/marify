# Notes for agents working on marify (the Marify apps)

## No backwards compatibility

This project is in development and has no installed base to protect. Do not
spend code, tests or documentation on older versions of anything.

- **One format, the current one.** Stored records, protocol messages and
  machine-side data of another version are an error that says what to do now
  (enrol again). Do not read old formats, convert them, or accept several
  versions side by side.
- **No migration paths.** When a format, a stored key scheme or a protocol
  field changes, the old one is gone in the same change: no fallbacks, no
  re-encryption of old data, no deprecation periods.
- **No compatibility across versions** of the app, the core or kira. The
  app is built against the kira commit in `core.properties` (one pin for both apps);
  machine and phone are updated together.
- **Say what it is, not what it was.** Comments, UI texts and documentation
  describe the current design only.
- **Tell the user plainly** when a change means enrolling a machine again.

The same rule, with kira's other standing decisions, is in
`../kira/AGENTS.md`.

## Other standing decisions

- **Two apps, one behaviour.** `android/` and `ios/` are the same app on two
  platforms: same screens, same texts, same decisions, the same Go core at
  the commit in `core.properties`. A change in one is made in the other.
  The Android app is the reference where they disagree; the iOS app has not
  been compiled yet (no Mac at hand when it was written, see `ios/README.md`).
- **Names and ids.** The app is "Marify": bundle id `io.wiora.marify` on
  both platforms (`.debug` suffix on Android debug builds), Kotlin package
  `app.marify`. The Go packages stay `kiracore` and `kiratest`: they belong
  to kira, the machine side.
- **Real machines are the default** on the enrol screen, in debug builds too;
  the demo machine is behind a switch. Release builds do not link kiratest.
- **Diagnostic logging is debug-only** (`debugLog`); release builds log
  warnings and errors only.
- **The phone keeps the machine's wrapped phone salt, never the salt in the clear.**
  What a machine hands over at `phone salt enrol` is a TPM credential the
  phone cannot open; it is kept with the record, replaced only when a
  verdict offering a new one is accepted, and released after an accepted
  receipt. The salt and the derived key exist on the machine only.
- **The one-time keyboard is shown, never kept.** With a record's `keyboard` the core sends a seed
  with each release and gives the app `keyboard_layout` once the machine's TPM opened it: shown full
  screen, landscape, with FLAG_SECURE, and dropped with the session. The pepper typed through it is the
  person's; the app never asks for it (kira's docs/DISK-KEY.md).
- **Unlock for every signature by biometrics or the device credential**
  (PIN/pattern/password on Android, passcode on iOS), a deliberate deviation
  from PROTOCOL-BLE.md §11.2; "biometrics only" is a per-machine choice at
  binding.
