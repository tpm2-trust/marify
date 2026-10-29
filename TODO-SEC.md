# Security TODO

Findings from the review of the machine ↔ phone binding (2026-10-05), with
how to fix each one. Reviewed by reading the code at tpm2-kira `6dceb2a` and
marify `a4f4205`; no attacks were run. Not reviewed in depth: the BLE HCI
layer (`transport/ble`) and the TLV parsers (`attest/wire.go`,
`attest/messages.go`).

Paths starting with `android/` are in this repository; all others are in
tpm2-kira.

| # | Finding | Severity | Where the fix goes | Status |
|---|---|---|---|---|
| [S1](#s1-the-phone-does-not-say-that-the-machines-enrolment-changed) | Phone gives a generic error when the machine's blob was replaced, and one text falsely reassures | High | app | done |
| [S2](#s2-the-machines-attestation-blob-can-be-replaced-and-its-signature-is-never-checked) | Machine blob replaceable; its signature is never verified | High | tpm2-kira | done |
| [S3](#s3-enrolment-does-not-verify-that-the-tpm-is-genuine) | Enrolment trusts the TPM on first use (EK certificate not verified) | Medium | core, tpm2-kira, app | done (Intel); needs a real-hardware check |
| [S4](#s4-phone-debug-builds-and-data-at-rest) | Debug build usable for real machines; stored policy editable by code running as the app | Medium | app | 1–3 done; 4 not done (see S4) |
| [S5](#s5-the-pcr-selection-may-not-cover-the-initrd) | PCR selection may not cover the initrd | Medium | tpm2-kira, app | done |
| [S6](#s6-the-blob-is-readable-by-anyone-with-tpm-access) | Blob (machine Noise key, advertising key) readable with TPM access | Low, accepted | docs | done (documented) |
| [S7](#s7-relay-to-a-look-alike-machine) | Relay to a look-alike machine | Inherent | docs | done (documented) |
| [S8](#s8-smaller-hardening) | Noise test vectors, overlay taps, biometric-only option | Low | both | done |
| [S9](#s9-the-machine-checks-the-phones-hardware-too) | The machine did not check the phone's key or its own TPM | Medium | both | done; needs real phones |

What already holds: Noise XX with a commit-then-reveal six-digit code at
enrolment; credential activation proving the AK is in the TPM that owns the
EK; quotes verified only against the AK pinned at enrolment; quotes bound to
both nonces and the session (`qd`); the phone, not the machine, chooses the
quoted PCRs; receipts bound to the session's quote and signed by a
non-exportable key that needs a fresh unlock.

The rule everything below follows: **only the phone's verdict counts.** No
green check on the phone means no assurance, whatever the machine's console
says.

---

## S1: The phone does not say that the machine's enrolment changed

**Problem.** If someone replaces the machine's attestation blob (S2), the
phone never shows a false green, but it does not say what happened either:

| Attacker does | Phone shows today |
|---|---|
| Replaces the enrolled-phone list (their phone instead of yours) | "Connection lost." |
| Replaces the machine's Noise key, keeps the advertising key | "Connection lost." / "The session failed." |
| Replaces or deletes the whole blob | "… is not reachable", with the text "This is not a problem with the machine's boot or its TPM" |

The last text (added in `a4f4205`) is wrong in exactly this case.

**Fix (app).**

1. `android/app/src/main/java/app/kira/session/SessionState.kt`: add
   `FailureKind.MACHINE_REFUSED`.
2. `session/SessionRunner.kt`: in attestation mode, when the session ends
   before a `hello` event was seen — the link is lost during
   `Phase.SECURING`, or the core reports `handshake failed` — end with
   `MACHINE_REFUSED` instead of `LINK_LOST` / `PROTOCOL`. Track "hello seen"
   with a boolean set in the `Event.Hello` branch. Do not look at message
   contents; the phase is enough.
3. `ui/SessionScreen.kt` + `res/values/strings.xml`: show this as a red
   card, same weight as "Attestation failed":
   *"<name> did not accept this phone. The machine is advertising as yours
   but refused the secure connection. Its enrolment data was changed or
   removed, or this is a different machine imitating it. Do not type your
   passphrase. If you did not change the enrolment yourself, treat the
   machine as tampered with."* No "Try again" as the primary action.
   One retry is reasonable before showing it (a link can drop for radio
   reasons during the handshake): retry once silently, show the card on the
   second refusal.
4. `strings.xml` `unreachable_body`: remove "This is not a problem with the
   machine's boot or its TPM". Replace with: *"Marify cannot say anything
   about this machine until it reaches it. A missing result is not a good
   result."* Same change for the notification text if it implies safety.
5. Machines list: when a machine was verified before and has asked for
   nothing since, nothing can be shown (the app only scans in the
   foreground). Leave as is; do not add a "last seen" claim.

**Tests.** `SessionRunnerTest`: link lost before hello → `MACHINE_REFUSED`
after one retry; link lost after hello → `LINK_LOST`.
`SwtpmMachineSessionTest.strangerPhoneGetsNoAnswer`: assert the new kind.
Compose test for the red card.

**Done.** As described, with one refinement: a protocol error counts as a
refusal only when it came from handling the machine's answer, not from the
app's own core before anything was received
(`localCoreErrorAtStartIsNotARefusal`). Enrolment never reports a refusal.

---

## S2: The machine's attestation blob can be replaced, and its signature is never checked

**Problem.** The blob (`cmd/attest_blob.go`: AK, machine Noise key,
advertising key, enrolled phones with their anchor keys) is stored in an NV
index defined through the owner hierarchy with empty auth
(`cmd/nvram.go`, `WriteToNVRAM`). `PolicySigned` protects only in-place
writes: anyone with root, or any other OS booted on the machine, can
`NV_UndefineSpace` the index and define and write their own. The signature
inside the blob is parsed (`UnmarshalAttestBlob`) and verified nowhere, not
even by `phone status`.

Consequences: the console line "ATTESTED by …" can be made to trust an
attacker's phone; restoring an old copy of the blob re-enrols a phone that
was removed. In lazy mode the line is advisory, so this is a display
problem today. It becomes a real problem the moment anything on the machine
acts on the receipt (enforced mode).

**Fix (tpm2-kira).** Undefining cannot be prevented without setting an
owner password (which breaks other tooling) or platform auth (not available
to the OS). So: detect, and stop presenting the unauthenticated result as
fact.

1. Add `VerifyAttestBlobSignature(signed []byte, pub crypto.PublicKey)` in
   `cmd/attest_blob.go`, modelled on `VerifyBlobSignature` in `cmd/blob.go`
   (same envelope: signature over `version ‖ payloadLen ‖ payload`).
2. Call it wherever the signing public key is available
   (`DefaultPublicKeyPath`, `/etc/tpm2-kira/keys/seal.pub`, or the path
   stored in the sealed blob):
   - `AttestStatus`: print `Blob signature: valid` / `INVALID — the
     enrolment data was not written by this machine's signing key`; exit
     non-zero on invalid.
   - `AttestEnrol`: verify the existing blob before `UpsertVerifier`;
     refuse to extend an unauthenticated blob (offer to start fresh).
   - New `phone check` command plus a systemd unit that runs after unlock
     on the booted system and logs/notifies on an invalid signature or a
     changed verifier list. Store the last seen verifier-list digest under
     `/var/lib/tpm2-kira/` (on the encrypted disk) and compare.
3. `reportReceipt` (`cmd/attest.go`): in lazy mode append to every line
   "(not verified on this machine — the phone's screen is authoritative)".
4. Document in `docs/PROTOCOL-BLE.md` §15 / `PLAN-BLE.md` §7 that enforced
   mode must not be built on the NV blob: it needs the anchor pinned in the
   measured image (PLAN-PHONE.md §10.2), so that changing it
   changes the PCRs.

**Tests.** Unit: tampered payload, wrong key, missing signature. swtpm
integration: write a blob with a second key by undefine/redefine and check
that `phone status` and `phone check` flag it.

The phone side of this finding is S1.

**Done, then redone differently** (tpm2-kira, see its log). The first version
was `phone check` plus a unit that ran after unlock. That was withdrawn on
2026-10-06: after unlock the passphrase has already been typed, so a check
there helps nobody. `phone status` and the refusal in `phone enrol` remain;
the detection itself now happens in the initrd, before the gate advertises:

- the record's **signature** is checked against the machine's signing public
  key, a copy of which the initramfs hooks put into the image
  (`attest signer`), which refuses a record written by anyone else;
- the record's **count** is checked against an NV counter the TPM holds for
  the slot. Every record carries the counter's next value, and a TPM counter
  cannot be turned back, even by deleting it, which refuses an older,
  genuinely signed record put back.

Since 2026-10-06 the enrolment is not a record of its own any more: it is a
section of the slot's blob, next to the TOTP key and under the same signature
(one blob per slot; the counter stays a separate NV index). The two checks are
the same, applied to that blob.

Enrolling or removing a phone therefore needs no initramfs rebuild. A refused
record means the gate does not advertise: the phone shows "not reachable" and
the machine's console a red line (exit status 6). The swtpm test performs the
attacks through the owner hierarchy: a foreign record, an older genuine one,
and that one with the counter deleted, recreated or replaced by an ordinary
index holding the old count.

Limits, stated in tpm2-kira's SECURITY.md: the public key is as trustworthy
as the initramfs carrying it (a Secure Boot-signed UKI, or PCRs covering the
initrd in the seal or the phone's selection); and someone with the owner
hierarchy can raise the counter, which makes the genuine record stale until
the user enrols again, a denial of the phone check that never yields an
accepted record.

---

## S3: Enrolment does not verify that the TPM is genuine

**Problem.** `checkOffer` (`attest/verifier.go`) only parses `ek_cert`
(`x509.ParseCertificate`). It does not validate a chain and does not check
that the certificate's key is `ek_pub`. The EK's attributes are whatever the
public area claims. A machine that is already compromised when you enrol it
can therefore offer a software "TPM" and produce good-looking quotes
forever.

**Fix.** Verify the EK certificate chain in the core, and tell the user the
result. It can only ever be "verified by vendor X" or "not verified": many
firmware TPMs ship without a usable certificate, so this must not block
enrolment.

Intel PTT (fTPM) is the common laptop case. Its chain is documented and
tested in <https://github.com/mrwiora/intel-ek-dechainer>:

```
OnDie CA Root (static)                       intel-root.der in that repo
└ OnDie CA CSME Intermediate (static)        intel-int1.der
  └ On Die CSME P_MCC … Issuing CA (static)  intel-int2.der
    └ CSME MCC ROM CA            ┐
      └ CSME MCC SVN.. Kernel CA │ per machine, concatenated DER in
        └ CSME MCC PTT ..SVN     ┘ NV index 0x01C00100
          └ EK certificate         NV 0x01C00002 (RSA) / 0x01C0000A (ECC)
```

Steps:

1. **Machine** (`cmd/attest_tpm.go`): next to `readEKCert`, add
   `readEKCertChain` that reads NV `0x01C00100` if present and returns it
   unchanged. `EKPublic()` returns it as a third value.
2. **Protocol** (`attest/messages.go`, `docs/PROTOCOL-BLE.md` §7.3.11): new
   optional EnrolOffer field 17 `ek_cert_chain`, bytes ≤ 16384, concatenated
   DER. Optional field, so the schema version stays 1.
3. **Core** (new `attest/ekcert.go`; the pure core must not import
   go-attestation, see `attest/importgraph_test.go`):
   - Split the chain the way `dechainer.py` does, but strictly: read
     ASN.1 SEQUENCE headers (`30 82 LL LL` and the short forms) at exact
     offsets; reject trailing garbage instead of scanning for the next `30`.
   - Embed trusted roots with `go:embed` under `attest/ekroots/`: Intel
     OnDie root plus the two static intermediates (take them from
     intel-ek-dechainer, then confirm each SHA-256 fingerprint against the
     copy at `https://tsci.intel.com/content/OnDieCA/certs/…` and record the
     fingerprints in a test). Add Infineon, STMicroelectronics and Nuvoton
     roots for discrete TPMs the same way. AMD fTPM certificates are fetched
     online per chip; leave AMD as "not verified" for now.
   - `VerifyEKCertificate(ekPub, ekCert, chain []byte, now time.Time)
     (vendor string, err error)`:
     - `x509.Verify` with the embedded roots, intermediates = static ones
       plus the machine-supplied chain, `KeyUsages: ExtKeyUsageAny`.
     - EK certificates carry a critical subjectAltName with a directoryName
       (TPM manufacturer/model/version). Go reports it in
       `UnhandledCriticalExtensions`; remove exactly that OID (2.5.29.17)
       after checking it parses, otherwise `Verify` fails.
     - Require the EK-certificate EKU `2.23.133.8.1` (in `UnknownExtKeyUsage`).
     - **Bind the certificate to the key:** the certificate's public key must
       equal the key in `ek_pub` (compare the ECC point, or RSA modulus and
       exponent). Without this the check proves nothing.
     - RSA EK certificates may use the RSAES-OAEP key OID, which
       `crypto/x509` does not parse; handle ECC first and report RSA-OAEP as
       "not verified" until a parser is added.
   - No revocation check: the release app has no network permission. Say so
     in the result ("revocation not checked").
4. **Core API**: `checkOffer` calls it and stores the outcome in the record
   (`ek_verified_by`, empty when not verified). Add it to
   `Kiracore.RecordSummary` and to the `need_anchor_key` event so the app can
   show it before the user binds.
5. **App**: on the bind step and in machine details show either
   *"TPM verified: Intel PTT"* or an amber *"This TPM could not be verified
   as genuine hardware. Only continue if you trust the machine's current
   state."* Requires rebuilding the core (`core.properties`).

**Tests.** A real Intel EK certificate + chain captured once as a fixture
(public data); swap one byte; a valid chain with a different `ek_pub`;
truncated and padded chain blobs.

**Limit.** This proves a genuine TPM holds the EK. It does not stop attacks
on the bus of a discrete TPM or firmware bugs in the TPM itself.

**Done** (tpm2-kira `db193b1`, core pinned in `android/core.properties`).
Steps 1–5 as described, with these decisions:

- Only the Intel root is a trust anchor; the two static Intel intermediates
  are embedded as helpers. All three are byte-identical in
  intel-ek-dechainer and on tsci.intel.com, and their SHA-256 fingerprints
  are pinned in `attest/ekcert_test.go`; the real Intel signatures between
  them verify in that test.
- Intel products with another P_MCC issuing CA, and other vendors
  (Infineon, ST, Nuvoton, AMD), show as "not verified" until their roots
  are added the same way.
- The machine now offers the EK whose algorithm has a certificate (Intel
  PTT often certifies only RSA). An EK certificate Go cannot parse no longer
  aborts enrolment; it becomes "not verified".
- The EKU is required only when the certificate has one.
- The app shows the result on a new "Bind <machine> to this phone?" step
  between the code comparison and the key/unlock prompt, and in machine
  details.

**Vendors are data** (tpm2-kira `203a65c`, `a4db157`): roots and
intermediates live in `attest/ekroots/<vendor>/`, listed in
`attest/ekroots/vendors.json` with sources and pinned SHA-256 values. The
core, which runs in the phone app, ignores a vendor whose files do not match
their pins. `attest/ekroots/README.md` describes adding a vendor (two
independent sources, a manifest entry, an app core rebuild). Roots are only
ever compiled into the app, never added at runtime. The bind screen lists the
vendors the installed app can verify; `CoreOnDeviceTest` checks on a device
that the roots are embedded.

**Still open:** no real Intel EK certificate was available here, so the full
chain (EK ← per-machine CAs ← embedded CAs) is tested only with a synthetic
hierarchy. On a laptop with Intel PTT, run `sudo tpm2-kira phone ekcert`:
it prints what the phone will conclude.

---

## S4: Phone: debug builds and data at rest

**Problem.**

- The debug APK is debuggable (`run-as` reads its data and uses its
  Keystore keys), accepts anchor keys that are not hardware-backed, and has
  network permission. It must not be used with real machines.
- Machine records and the Noise key are AES-GCM encrypted under Keystore
  keys that need no user presence (`keys/KeystoreAead.kt`). That stops
  offline extraction and backups. Code running as the app (a rooted phone)
  can decrypt a record, add a "known-good" profile, and re-encrypt it; the
  next attestation of a tampered boot then shows green.

**Fix (app).**

1. Release signing: `android/app/build.gradle.kts` reads keystore path and
   passwords from environment variables or an ignored
   `keystore.properties`; document in `android/README.md`. Install only the
   signed release build on real phones.
2. Debug builds: `applicationIdSuffix = ".debug"`, a permanent banner
   "DEBUG BUILD — demo only", and refuse real (BLE) enrolment unless a
   developer switch is turned on.
3. `KeystoreAead`: add `setUnlockedDeviceRequired(true)` so stored data
   cannot be decrypted while the phone is locked. `MachineRepository.load()`
   currently runs in `Application.onCreate`; move it to first foreground use
   and handle `UserNotAuthenticatedException` by retrying on unlock.
4. Policy integrity (defence in depth, design first): make changes to the
   profile list need the user. Sketch: the core exposes
   `PolicyDigest(recordJSON)`; at enrolment and at "approve and remember"
   the app signs that digest with the machine's anchor key (one extra
   unlock prompt, both are rare) and stores the signature beside the
   record; on every load the app verifies it with the public key taken from
   the Keystore certificate, not from the record. A mismatch shows the
   machine as "stored data was modified — enrol again". A rooted phone can
   still replace both key and record; the machine then reports an anchor
   mismatch.

**Tests.** `apkanalyzer`: release not debuggable, no `INTERNET`.
Instrumented: record with a flipped profile fails to load; load while
locked is deferred.

**Done: 1–2.** Signing reads `KIRA_KEYSTORE`, `KIRA_KEYSTORE_PASSWORD`,
`KIRA_KEY_ALIAS`, `KIRA_KEY_PASSWORD` or `keystore.properties`; without them
the release stays unsigned. Checked with a throwaway key (deleted
afterwards): `apksigner` verifies, release `debuggable=false` and no
`INTERNET`, the minified release starts. Debug is `io.wiora.marify.debug`
("Marify debug") and has a red banner on every screen. The release key itself
is still to be created by the owner (README.md).

**Changed on 2026-10-06, by decision:** the debug build used to list real
machines on the enrol screen only after a switch. That is reversed: machines
found over Bluetooth are always listed, as in a release build, and the switch
now shows the *demo* machine. Testing on real hardware is what debug builds
are used for here. What remains to keep a debug build from being mistaken for
a release: the banner ("not for machines you rely on"), a warning card on the
enrol screen, the `.debug` application id and the "Marify debug" name.

**Done: 3.** Data at rest is encrypted under a Keystore key with
`setUnlockedDeviceRequired(true)` and carries a `K2` prefix; anything else is
refused. Records load when the screen starts instead of at process start, and
retry if the phone is locked. `KeystoreAeadTest` covers the round trip, data
in another format, wrong associated data and altered data; the locked-phone
refusal itself relies on the platform and was not exercised on the emulator.

**Not done: 4, by decision.** Signing the profile list only stops an attacker
who can change Marify's files *without* running code as Marify. With backups
disabled and no exported providers that attacker has no way in; one who runs
code as Marify (root) can also replace the Keystore key the signature is
checked against. The cost would be an extra unlock prompt at enrolment and at
every "approve and remember". Revisit if Marify ever shares its files.

---

## S5: The PCR selection may not cover the initrd

**Problem.** `phone enrol` quotes the sealed slot's PCRs, or `0,2,4,7`
when there is none (`cmd/attest.go`). Whether that covers the kernel and
initrd depends on the boot path. With GRUB and a separate initrd they are
measured into PCR 8/9, with a UKI into PCR 4 and 11. If the initrd is not
covered, a modified initrd (the classic passphrase logger) verifies as
"match". Not checked on a real machine yet.

**Fix.**

1. `AttestEnrol`: after choosing the selection, use the existing boot-path
   knowledge (`cmd/pcrtips.go`, `cmd/ukipredict.go`, the event log) to
   determine which PCRs measured the kernel and initrd on this boot. If none
   of them is in the selection, print a prominent warning naming the PCRs to
   add and ask for confirmation.
2. Send the finding with the offer (BootContext has room for an optional
   field) so the core can add a warning, and show the quoted PCRs with their
   descriptions on the app's bind screen under "Details".
3. Add a `docs/` table: boot setup → PCRs that must be quoted.

**Done: 1 and 3** (tpm2-kira `e90fab9`). `phone enrol` recognises GRUB
(PCR 9 file), the EFI stub's LoadFile2 tag (PCR 9) and a UKI (PCR 11
`.initrd`, PCR 4 image), prints whether the selection covers the initrd and
asks (default no) when it does not; the table is in tpm2-kira's README.
Checked on real event logs from go-attestation's test data. **Step 2** is
done with S3 (tpm2-kira `db193b1`): BootContext fields 7/8 carry where the
initrd was measured; the bind step warns in amber when the enrolled PCRs do
not cover it. The machine reports only a recognised measurement ("none
found" stays "unknown", so an unfamiliar boot path cannot hide the warning).

---

## S6: The blob is readable by anyone with TPM access

**Problem.** NV reads need no secret (`AuthRead` with empty auth). The blob
contains the machine's Noise private key and the advertising key. Someone
who reads it once (root, or a live USB on that machine) can imitate the
machine's Bluetooth endpoint, recognise its advertisements, and learn the
phone's public Noise key. They cannot produce a quote.

**Decision: accept, document.** Sealing these keys to PCRs would stop a
tampered boot from talking to the phone at all, but then a legitimate
update could not show its "changed" diff either, which is the main feature.
Add the reasoning to `docs/PROTOCOL-BLE.md` §2.2 and SECURITY.md. With S1,
an imitated endpoint ends in a red "did not accept this phone" or a failed
verdict, never a green one.

**Done:** SECURITY.md ("what it does not protect against") and
PROTOCOL-BLE.md §2.2 in tpm2-kira.

---

## S7: Relay to a look-alike machine

**Problem.** Attestation proves that *your enrolled TPM* booted a known
state. It does not prove that the laptop you are typing on is that machine.
An attacker who swaps the laptop for a look-alike and relays Bluetooth to
the real one (kept elsewhere, booted cleanly) gets a green check, and you
type the passphrase into the fake. The TOTP check has the same limit.

**No cheap fix.** Bluetooth LE has no secure distance bounding, and any code
shown on the real machine's screen can be relayed too. Document it in
SECURITY.md as a residual risk, with the practical mitigations: tamper-
evident marking of the device, and the planned salt release (the released
factor is useless without the real machine's TPM, so the passphrase alone
no longer opens the disk).

**Done:** documented in tpm2-kira's SECURITY.md.

---

## S8: Smaller hardening

1. **Noise implementation** (`attest/noise.go`) is hand-written. The docs
   say it interoperates with the reference library, but no handshake test
   vectors are in the repository. Add the official XX and IK vectors for
   `25519_ChaChaPoly_SHA256` (cacophony / noise-c format) to
   `attest/vectors_test.go`.
   **Done:** `attest/noise_vectors_test.go` replays the XX and IK vectors from
   cacophony and snow (`attest/testdata/noise_vectors.json`); all four match
   byte for byte, including the handshake hash.
2. **Overlay taps** (app): in `MainActivity.onCreate` set
   `window.decorView.filterTouchesWhenObscured = true` and, on API 31+,
   `window.setHideOverlayWindows(true)`, so another app cannot draw over the
   compare-code and approve buttons.
3. **Biometric-only option** (app): a per-machine switch that creates the
   anchor key with `AUTH_BIOMETRIC_STRONG` only, for users who do not want
   the PIN to be enough (PROTOCOL-BLE.md §11.2 allows it). Requires
   re-enrolling that machine.
   **Done (2, 3):** overlays are filtered (`filterTouchesWhenObscured`, and
   `setHideOverlayWindows` with the `HIDE_OVERLAY_WINDOWS` permission). The
   bind screen offers "Require fingerprint or face" when a strong biometric
   is enrolled; the key is then created with `AUTH_BIOMETRIC_STRONG` only,
   the prompt offers no PIN, and machine details shows the key's policy.
4. **Half-finished enrolment**: if the machine commits but its confirmation
   does not reach the phone, the machine lists a phone that has no record.
   `phone status` should make stale entries visible; enrolling again with
   the same phone replaces the entry.
   **No change needed:** enrolling again with the same phone already
   replaces its entry (`UpsertVerifier` keys on the verifier id), and
   `phone status` lists every entry with its anchor digest.

---

## S9: The machine checks the phone's hardware too

**Problem.** The phone verifies the machine's TPM (S3), but the machine took
the phone's anchor key on trust: it could not tell a StrongBox key on a
locked phone from a key in software on a rooted one. And `phone enrol` did
not show the machine's own TPM verdict before enrolling.

**Done** (tpm2-kira `aac0ef6`, `a360fbd`; the app's pinned core is in
`android/core.properties`):

- The core gives the app an attestation challenge bound to the session; the
  app creates the anchor key with it and sends the key's attestation chain
  (EnrolAccept field 7). Where the ROM cannot attest (no attestation keys,
  e.g. LineageOS), the key is created without and the machine is told so.
- The machine checks the chain against pinned Google attestation roots
  (`attest/phoneroots/`, the two roots Google's attestation API serves,
  identical to the documented ones): the certificate is for the anchor key,
  the challenge is this session's, the key was generated in StrongBox/TEE,
  an unlock is enforced for every use, the bootloader is locked and the boot
  verified; on the booted machine also Google's revocation list.
- `phone enrol --verify-phone` and `--verify-tpm` (`warn` | `require` | `off`,
  default `warn`: show and ask, default answer no). The TPM check runs the
  phone's EK verification on the machine before advertising.
- Tests: Google's real (development-device) attestation chains, synthetic
  chains for every rule, a full enrolment refused by the machine, the swtpm
  machine reporting the test phone's missing attestation, and a real
  Keystore attestation from the emulator read by the machine's parser
  (`AnchorKeysTest`): it parses, matches key and challenge, and is correctly
  rejected as a software keystore.
- Found on the way: `trimNVPadding` could cut an EK chain whose last byte
  was 0x00 or 0xFF (about one in 128); fixed with a regression test.

**Still open:** a real phone. On the Pixel 6a expect "verified by Google:
StrongBox …, locked bootloader, verified boot"; on the LineageOS 4a expect
either no attestation or "self-signed boot" — the machine asks, and `y`
enrols it.

## Remaining

- S3: run `sudo tpm2-kira phone ekcert` on a laptop with Intel PTT to check
  the full Intel chain on real hardware; add roots for other vendors.
- S4.1: create the release signing key (see `android/README.md`).
- Real-hardware checks in `android/TESTING.md` §4, including what only a
  boot with a Bluetooth adapter shows on the machine: the gate's confined
  unit, and its check of the enrolment record (signature and counter).

## Suggested order

1. S1 (small, app only) and the wording fix.
2. S4.1–S4.2 (signed release build, debug guard) before using real phones.
3. S2.1–S2.3 (blob signature checks, console wording).
4. S5 (check the selection on the real machine first; it may already be fine).
5. S3 (EK certificate), then S4.3–S4.4, S8.
