# Notes for agents working on marify (the Kira app)

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
- **No compatibility across versions** of the app, the core or tpm2-kira. The
  app is built against the tpm2-kira commit in `android/core.properties`;
  machine and phone are updated together.
- **Say what it is, not what it was.** Comments, UI texts and documentation
  describe the current design only.
- **Tell the user plainly** when a change means enrolling a machine again.

The same rule, with tpm2-kira's other standing decisions, is in
`../tpm2-kira/AGENTS.md`.

## Other standing decisions

- **Real machines are the default** on the enrol screen, in debug builds too;
  the demo machine is behind a switch.
- **Diagnostic logging is debug-only** (`debugLog`); release builds log
  warnings and errors only.
