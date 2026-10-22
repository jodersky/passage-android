<img src="./logo.svg" alt="logo" align="right">

# Passage for Android

A password manager for android, compatible with
[passage](https://github.com/FiloSottile/passage) (itself based on
[pass](https://www.passwordstore.org/), but using
[age](https://github.com/FiloSottile/age) encryption instead of gpg).

## Motivation

On my computer, I recently moved from [pass](https://www.passwordstore.org/) to
[passage](https://github.com/FiloSottile/passage) (mainly because I find working
with gpg to be quite clunky). On Android, I used to use
[Android-Password-Store](https://github.com/android-password-store/android-password-store),
but that of course uses pass and is not compatible with passage. I couldn't find
any existing passage client, so I decided to write my own.

I also wanted the Android client to be quite minimal, yet tailored to my
needs:
- I have a phone with
  [StrongBox](https://developer.android.com/privacy-and-security/keystore#StrongBoxKeyMint)
  support, so I can generate an unextractable hardware-bound key. This fits
  into my use of yubikeys on the laptop.
- I synchronize passwords with a git repo, so I need SSH support (again, with a
  hardware-bound key).

## Features

- Hardware-bound keys

  When the application is first launched, it asks the phone's secure element to
  generate a key for `age` and for `SSH`. You can then configure your passage
  recipients and git server to use the generated public keys. The private keys
  always stay in the secure element, and require your approval (e.g. via
  fingerprint) for any use, such as when decrypting a password or synching git.

- Git synchronization over SSH

- Auto-fill

- TOTP support

  If you store the seed of a time-based one-time password (TOTP), the
  application will show the current TOTP. This is compatible with the
  [pass-otp](https://github.com/tadfisher/pass-otp) extension.

## Building

Requirements:

- A phone running Android 17 (API level 37) or newer, with
  [StrongBox](https://developer.android.com/privacy-and-security/keystore#StrongBoxKeyMint)
  support (the app's keys are generated in it), and with
  [USB debugging](https://developer.android.com/studio/debug/dev-options#enable)
  enabled
- [adb](https://developer.android.com/tools/adb), to install the app

Instructions:

1. Build the APK:

   ```sh
   ./mill app.androidApk
   ```

2. Connect the phone over USB and install the APK:

   ```sh
   adb install -r out/app/androidApk.dest/app.apk
   ```
## Maintainer

- `scripts/test` runs all tests
- `scripts/release` builds the release APK, signed with the release key (see
  [signing/README.md](signing/README.md))

## Notice on use of LLMs

The coding part of this project was done with a large language model. However, a
human was responsible for designing, setting constraints, planning, and
reviewing the produced code.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
