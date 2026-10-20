#!/usr/bin/env bash
# Generates the release signing key: an ECDSA P-256 private key and a
# self-signed certificate, valid for 100 years.
#
# The certificate is written to signing/release-cert.pem (commit it). The
# private key (PKCS#8, PEM) is never written to disk: it is piped to the
# given command, which must store it. For example:
#
#   signing/generate-key.sh passage insert -m android/passage-signing-key
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <command that stores the key, read from standard input>" >&2
  exit 1
fi
cert="$(dirname "$0")/release-cert.pem"
if [[ -e $cert ]]; then
  echo "$cert already exists. Installed apps can only be updated with the same key, so it must not be replaced." >&2
  exit 1
fi

key=$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256)
# store the key first: a certificate without its key would be useless
printf '%s\n' "$key" | "$@"
openssl req -new -x509 -sha256 -days 36500 -subj "/CN=Passage for Android" \
  -key <(printf '%s\n' "$key") -out "$cert"
echo "Stored the key and wrote $cert:"
openssl x509 -in "$cert" -noout -subject -enddate -fingerprint -sha256
