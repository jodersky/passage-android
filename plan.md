## High-level overview

Implement a lightweight password manager for android. which is essentially an
android UI around [passage](https://github.com/FiloSottile/passage):
- it uses age to encrypt passwords
- it uses git to sync

The UI should be similar to that of
[Android-Password-Store](https://github.com/agrahn/Android-Password-Store).

The application uses two hardware-backed, non-exportable keys stored in the
Android Keystore (StrongBox):
- an age identity, used to decrypt passwords
- an SSH key, used to synchronize git

Target device: Pixel 9a on the latest Android (StrongBox via Titan M2). Older
devices are not a goal.

## Implementation notes

- the application must obviously be compatible with passage
- you must not implement your own crypto, call out to a suitable age backend
- use the [mill](https://mill-build.org) build tool to build the project
- use jgit for git functionality
- use sshj for SSH functionality

## Decisions

### age decryption key: P-256 in StrongBox, `age1tag` recipient

- Use the `p256tag` recipient type from age >= 1.3
  ([spec](https://c2sp.org/age#the-tagged-recipient-types)). Stock age can
  encrypt to `age1tag1...` recipients without a plugin, so desktop passage
  only needs the phone's recipient added to `.age-recipients`.
- The private key is a P-256 key generated in the Android Keystore with
  StrongBox and `PURPOSE_AGREE_KEY`. It never leaves the hardware. The spec
  defines no identity encoding, so the app only keeps the Keystore alias.
- Decryption flow:
  1. parse the `p256tag <tag> <enc>` stanza and match the tag against the
     phone's recipient
  2. perform the P-256 ECDH in StrongBox (behind user authentication)
  3. finish the HPKE DHKEM(P-256, HKDF-SHA256) key schedule in software and
     open the 32-byte wrapped file key with ChaCha20-Poly1305
  4. continue with regular age payload decryption
- Steps 1 and 3 are protocol glue around existing primitives (no JVM HPKE
  library allows an external ECDH operation). This is an accepted, deliberate
  stretch of the "no own crypto" rule. It must be tested against the RFC 9180
  test vectors and against files produced by the `age` CLI.
- Encryption to `p256tag` only needs public keys, so it can use an existing
  HPKE implementation (BouncyCastle) as-is.

### age library: jagged + BouncyCastle

- Use [jagged](https://github.com/exceptionfactory/jagged) for the age format
  and the standard recipient types (X25519, `ssh-ed25519`, `ssh-rsa`).
- Add a custom `RecipientStanzaReader` / `RecipientStanzaWriter` pair for
  `p256tag`.
- The phone must encrypt to every recipient in `.age-recipients`. If it finds
  a recipient type it does not support, it refuses to encrypt and shows a
  clear error, instead of silently skipping that recipient.
- Fallback if jagged does not work on Android: Go age via gomobile (supports
  all recipient types upstream, but needs a native build in mill and a Go ->
  Java callback for the Keystore ECDH).

### SSH key: ECDSA P-256 in StrongBox

- `ecdsa-sha2-nistp256` is widely accepted (GitHub, etc.), and sshj signs
  through a standard JCA `Signature`, which works with Keystore keys.
- Ed25519 is not used: in Keystore it is TEE-only (no StrongBox), and sshj
  uses its own Ed25519 key classes.

### Recovery

- A lost phone means a lost decryption key. This is acceptable as long as
  `.age-recipients` always contains at least one other recipient (a desktop
  or a backup key). The app warns when its own recipient is the only one.

## Open questions

- unlock policy: user authentication for every decryption, or once per session
  with a timeout (e.g. 5 minutes)? StrongBox operations take a few hundred ms,
  so both are workable.
- post-quantum `age1tagpq` (ML-KEM-768 + P-256): ML-KEM is not available in
  Keystore, so that half would be a software key (encrypted at rest with a
  Keystore key). An attacker would still need the hardware P-256 key. Deferred
  until after the first version.

## First task: spike

Prove the riskiest parts before building any UI:
1. run jagged on Android 16 (check X25519 and ChaCha20-Poly1305 availability)
2. generate a StrongBox P-256 key and export its `age1tag1...` recipient
3. on a desktop, encrypt a file to that recipient with the `age` CLI (>= 1.3)
4. decrypt the file on the phone through the custom `p256tag` stanza reader
5. as a bonus: sign an SSH auth request with a StrongBox ECDSA key via sshj

### Results (2026-10-20, Pixel 9a)

All steps succeeded.

Step 5: sshj logs in to an SSH server (`mill testserver.run`, reached over
`adb reverse tcp:2222 tcp:2222`) with a StrongBox ECDSA P-256 key, using
sshj's stock `KeyPairWrapper`. This requires
`SecurityUtils.setRegisterBouncyCastle(false)`: otherwise sshj sends all JCA
calls to BouncyCastle, which cannot sign with a Keystore key.

Steps 1-4:
- the key reports security level StrongBox
- a file encrypted by `age` 1.3.2 to the phone's recipient (plus an X25519
  recipient) decrypts on the phone, in ~60-120 ms per file
- jagged works with Android's default crypto provider (X25519 and
  ChaCha20-Poly1305 are available), no extra provider needed
- desktop tests (`mill core.test`) cover the RFC 9180 test vector and
  interoperability with the `age` CLI in both directions

Learned:
- the spike key has no user authentication requirement, so any code running
  as the app can use it, even while the phone is locked. The real key must at
  least set `setUnlockedDeviceRequired(true)`; see the unlock policy question.
- `mill app.androidInstall` only targets the emulator; use
  `adb install -r out/app/androidApk.dest/app.apk` for a real device.
- coursier ignores Gradle module metadata, so some AndroidX artifacts must be
  pinned in `build.mill` to avoid duplicate classes in d8. The same applies
  to BouncyCastle: all its jars must have the same version.
- the spike accepts any SSH host key. The real app needs host key
  verification (trust on first use, with a known_hosts file).
