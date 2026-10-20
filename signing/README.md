# Release signing

Release builds are signed with an ECDSA P-256 key. Android only installs an
update if it is signed with the same key as the installed app, so this key
must be kept (and backed up) for as long as the app is distributed. Losing it
means users have to uninstall, which deletes the app's hardware-backed keys
(its age recipient and SSH key change).

- `release-cert.pem`: the key's certificate. It is public, and committed.
- The private key is never stored in the repository, nor written to disk by
  the build: the build runs a command that prints it, and passes it to
  `apksigner` on standard input.

## Creating the key (once)

```sh
signing/generate-key.sh passage insert -m android/passage-signing-key
git add signing/release-cert.pem
```

The script generates the key and pipes it (PKCS#8, PEM) to the given command,
which must store it; here, in a passage store. It then writes the certificate
(valid for 100 years). It refuses to replace an existing certificate.

## Building a release

```sh
git tag v0.1.0   # the version comes from the last v<major>.<minor>.<patch> tag
PASSAGE_SIGNING_KEY_COMMAND='passage show android/passage-signing-key' \
  mill --no-daemon show release.androidApk
```

`PASSAGE_SIGNING_KEY_COMMAND` is any shell command printing the key, as PEM or
DER. `--no-daemon` lets the command ask for a passphrase or a PIN on the
terminal; without it, the command runs in mill's background daemon, which has
none.

Check the signature with:

```sh
apksigner verify --print-certs out/release/androidApk.dest/app.apk
```

The certificate's SHA-256 digest must match
`openssl x509 -in signing/release-cert.pem -outform der | sha256sum`.
