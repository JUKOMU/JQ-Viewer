#!/usr/bin/env bash

set -euo pipefail

artifact_dir="${1:-}"
if [[ -z "$artifact_dir" || ! -d "$artifact_dir" ]]; then
  echo "usage: scripts/sign-rpm-release.sh <artifact-directory>" >&2
  exit 2
fi

required=(RPM_GPG_PRIVATE_KEY_BASE64 RPM_GPG_PASSPHRASE RPM_GPG_FINGERPRINT)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required RPM signing value: $name" >&2
    exit 1
  fi
done

expected_fingerprint="$(printf '%s' "$RPM_GPG_FINGERPRINT" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
if [[ ! "$expected_fingerprint" =~ ^[0-9A-F]{40,64}$ ]]; then
  echo "RPM_GPG_FINGERPRINT must be a complete hexadecimal fingerprint" >&2
  exit 1
fi

mapfile -t rpm_files < <(find "$artifact_dir" -maxdepth 1 -type f -name 'JQ-Viewer-*.rpm' -print | sort)
if [[ "${#rpm_files[@]}" -ne 2 ]]; then
  echo "Expected exactly two Desktop RPM files, got ${#rpm_files[@]}" >&2
  exit 1
fi

signing_home="$(mktemp -d)"
verification_db="$(mktemp -d)"
private_key_file="$signing_home/private-key.asc"
passphrase_file="$signing_home/passphrase"
public_key_file="$signing_home/public-key.asc"

cleanup() {
  rm -rf "$signing_home" "$verification_db"
}
trap cleanup EXIT

chmod 700 "$signing_home"
printf '%s' "$RPM_GPG_PRIVATE_KEY_BASE64" | base64 --decode > "$private_key_file"
printf '%s' "$RPM_GPG_PASSPHRASE" > "$passphrase_file"
chmod 600 "$private_key_file" "$passphrase_file"

GNUPGHOME="$signing_home" gpg --batch --import "$private_key_file" >/dev/null
mapfile -t secret_fingerprints < <(
  GNUPGHOME="$signing_home" gpg --batch --with-colons --list-secret-keys \
    | awk -F: '$1 == "fpr" { print toupper($10) }'
)
if [[ " ${secret_fingerprints[*]} " != *" $expected_fingerprint "* ]]; then
  echo "Imported RPM signing key does not contain the configured fingerprint" >&2
  exit 1
fi

GNUPGHOME="$signing_home" gpg --batch --armor --export "$expected_fingerprint" > "$public_key_file"
rpmkeys --dbpath "$verification_db" --initdb
rpmkeys --dbpath "$verification_db" --import "$public_key_file"

for rpm_path in "${rpm_files[@]}"; do
  rpmsign --addsign \
    --define "_gpg_name $expected_fingerprint" \
    --define "_gpg_path $signing_home" \
    --define "__gpg /usr/bin/gpg" \
    --define "__gpg_sign_cmd %{__gpg} --batch --no-verbose --no-armor --pinentry-mode loopback --passphrase-file $passphrase_file --no-secmem-warning -u \"%{_gpg_name}\" -sbo %{__signature_filename} %{__plaintext_filename}" \
    "$rpm_path"
  rpmkeys --dbpath "$verification_db" --checksig "$rpm_path" | tee /dev/stderr | grep -q 'digests signatures OK'
done

echo "Signed and verified ${#rpm_files[@]} RPM release assets with $expected_fingerprint"
