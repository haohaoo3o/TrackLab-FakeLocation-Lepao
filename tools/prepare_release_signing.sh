#!/usr/bin/env bash
# Prepares a local release keystore and AMap registration values.
# Existing private keys are never replaced. Generated secrets remain under .secrets/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS="${ROOT}/.secrets"
JKS="${SECRETS}/tracklab-release.jks"
PROPS="${SECRETS}/keystore.properties"
REGISTRATION="${SECRETS}/amap-registration.txt"
ALIAS="tracklab"
PACKAGE_NAME="io.github.haohaoo3o.tracklab"
TEMP_FILES=()

cleanup() {
  local file
  for file in "${TEMP_FILES[@]}"; do
    [[ -n "${file}" ]] && rm -f -- "${file}"
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

umask 077
mkdir -p "${SECRETS}"
chmod 700 "${SECRETS}"
printf '%s\n' '*' '!.gitignore' > "${SECRETS}/.gitignore"
chmod 600 "${SECRETS}/.gitignore"

read_property() {
  local wanted="$1"
  local key value
  while IFS='=' read -r key value; do
    if [[ "${key}" == "${wanted}" ]]; then
      printf '%s' "${value}"
      return 0
    fi
  done < "${PROPS}"
  return 1
}

if [[ -f "${JKS}" ]]; then
  if [[ ! -f "${PROPS}" ]]; then
    printf '%s\n' "[prepare_release_signing] Error: an existing keystore has no matching local signing properties." >&2
    printf '%s\n' "[prepare_release_signing] Restore .secrets/keystore.properties or move the keystore before generating a new certificate." >&2
    exit 1
  fi
  chmod 600 "${JKS}" "${PROPS}"
  printf '%s\n' '[prepare_release_signing] Reusing the existing release keystore.'
else
  STORE_PASS="$(openssl rand -hex 16)"
  KEY_PASS="${STORE_PASS}"
  STORE_PASS_FILE="$(mktemp "${SECRETS}/.storepass.XXXXXX")"
  KEY_PASS_FILE="$(mktemp "${SECRETS}/.keypass.XXXXXX")"
  TEMP_FILES+=("${STORE_PASS_FILE}" "${KEY_PASS_FILE}")
  printf '%s\n' "${STORE_PASS}" > "${STORE_PASS_FILE}"
  printf '%s\n' "${KEY_PASS}" > "${KEY_PASS_FILE}"
  chmod 600 "${STORE_PASS_FILE}" "${KEY_PASS_FILE}"

  printf '%s\n' '[prepare_release_signing] Generating the local release keystore.'
  if ! keytool -genkeypair \
    -keystore "${JKS}" \
    -alias "${ALIAS}" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass:file "${STORE_PASS_FILE}" \
    -keypass:file "${KEY_PASS_FILE}" \
    -dname "CN=TrackLab Release, OU=TrackLab, O=TrackLab, C=US" \
    >/dev/null 2>&1; then
    rm -f -- "${JKS}"
    printf '%s\n' '[prepare_release_signing] Error: the local release keystore could not be generated.' >&2
    exit 1
  fi
  chmod 600 "${JKS}"

  {
    printf 'storeFile=%s\n' '.secrets/tracklab-release.jks'
    printf 'storePassword=%s\n' "${STORE_PASS}"
    printf 'keyAlias=%s\n' "${ALIAS}"
    printf 'keyPassword=%s\n' "${KEY_PASS}"
  } > "${PROPS}"
  chmod 600 "${PROPS}"
  printf '%s\n' '[prepare_release_signing] Wrote local signing properties.'
  unset STORE_PASS KEY_PASS
fi

STORE_PASS="$(read_property storePassword || true)"
KEY_PASS="$(read_property keyPassword || true)"
KEY_ALIAS="$(read_property keyAlias || true)"
if [[ -z "${STORE_PASS}" || -z "${KEY_PASS}" || -z "${KEY_ALIAS}" ]]; then
  printf '%s\n' '[prepare_release_signing] Error: local signing properties are incomplete.' >&2
  exit 1
fi

STORE_PASS_FILE="$(mktemp "${SECRETS}/.storepass.XXXXXX")"
KEY_PASS_FILE="$(mktemp "${SECRETS}/.keypass.XXXXXX")"
TEMP_FILES+=("${STORE_PASS_FILE}" "${KEY_PASS_FILE}")
printf '%s\n' "${STORE_PASS}" > "${STORE_PASS_FILE}"
printf '%s\n' "${KEY_PASS}" > "${KEY_PASS_FILE}"
chmod 600 "${STORE_PASS_FILE}" "${KEY_PASS_FILE}"
unset STORE_PASS KEY_PASS

if ! KEYTOOL_OUTPUT="$(
  keytool -J-Duser.language=en -J-Duser.country=US -list -v \
    -keystore "${JKS}" \
    -alias "${KEY_ALIAS}" \
    -storepass:file "${STORE_PASS_FILE}" 2>/dev/null
)"; then
  printf '%s\n' '[prepare_release_signing] Error: the certificate could not be read from the local keystore.' >&2
  exit 1
fi

SHA1="$(printf '%s\n' "${KEYTOOL_OUTPUT}" | awk '/SHA1:/ { print $2; exit }')"
unset KEYTOOL_OUTPUT
if [[ ! "${SHA1}" =~ ^([0-9A-Fa-f]{2}:){19}[0-9A-Fa-f]{2}$ ]]; then
  printf '%s\n' '[prepare_release_signing] Error: the certificate SHA-1 could not be read.' >&2
  exit 1
fi

REGISTRATION_TMP="$(mktemp "${SECRETS}/.amap-registration.XXXXXX")"
TEMP_FILES+=("${REGISTRATION_TMP}")
{
  printf 'PackageName=%s\n' "${PACKAGE_NAME}"
  printf 'SHA-1=%s\n' "${SHA1}"
} > "${REGISTRATION_TMP}"
chmod 600 "${REGISTRATION_TMP}"
mv -f "${REGISTRATION_TMP}" "${REGISTRATION}"
chmod 600 "${REGISTRATION}"
unset SHA1

printf '%s\n' "[prepare_release_signing] Saved AMap registration values to ${REGISTRATION}."
printf '[prepare_release_signing] To view them yourself, run: cat %q\n' "${REGISTRATION}"
