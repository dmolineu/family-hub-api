#!/usr/bin/env bash
# Refuses an image that carries anything which must never leave the house.
#
# Usage: scripts/check-image.sh <image> <path-inside-image> [<path> ...]
#
# The image is public on GHCR, so this runs in CI before every push and can be
# run locally against any built image. It fails if:
#   1. an environment variable baked into the image looks like a secret,
#   2. the filesystem contains a secret-bearing file (.env, keys, keystores,
#      theme packs, local profiles, credential files), or
#   3. gitleaks finds a secret string under the given paths (jars are unpacked
#      first so Spring resources inside app.jar are scanned too).
set -euo pipefail

IMAGE="${1:?usage: check-image.sh <image> <path-inside-image> [...]}"
shift
SCAN_PATHS=("$@")
if [ "${#SCAN_PATHS[@]}" -eq 0 ]; then
  echo "check-image: give at least one path inside the image to scan" >&2
  exit 2
fi

GITLEAKS_IMAGE="ghcr.io/gitleaks/gitleaks:v8.30.1"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAILED=0
fail() { echo "check-image: FAIL - $*" >&2; FAILED=1; }

echo "check-image: inspecting $IMAGE"

# 1. Environment baked into the image.
docker image inspect "$IMAGE" --format '{{range .Config.Env}}{{println .}}{{end}}' > "$TMP/env"
if grep -Ei '^[A-Z0-9_]*(SECRET|PASSWORD|PASSWD|TOKEN|PRIVATE|CREDENTIAL|API_KEY|APIKEY|ENCRYPTION_KEY|CLIENT_SECRET)[A-Z0-9_]*=' "$TMP/env" > "$TMP/env-hits" 2>/dev/null; then
  fail "secret-looking environment variable(s) baked into the image: $(cut -d= -f1 "$TMP/env-hits" | tr '\n' ' ')"
fi

# 2. Export the filesystem.
mkdir -p "$TMP/fs"
CONTAINER="$(docker create "$IMAGE")"
docker export "$CONTAINER" | tar -x -C "$TMP/fs" 2>/dev/null || true
docker rm "$CONTAINER" > /dev/null

# 3. Files that have no business in a published image. CA bundles and the JDK's
#    own trust store are the only legitimate key material.
find "$TMP/fs" -type f \( \
    -name '.env' -o -name '.env.*' -o -name '*.pem' -o -name '*.key' \
    -o -name '*.p12' -o -name '*.pfx' -o -name '*.jks' -o -name '*.keystore' \
    -o -name 'id_rsa*' -o -name 'id_ed25519*' -o -name 'id_ecdsa*' \
    -o -name '*.familytheme.json' -o -name 'application-local*' \
    -o -name 'credentials.json' -o -name 'token.json' -o -name '.netrc' \
    -o -name '.npmrc' -o -name '.docker' -o -name 'settings.xml' \
  \) \
  -not -path '*/ca-certificates/*' -not -path '*/ssl/certs/*' \
  -not -path '*/etc/ssl/*' -not -path '*/lib/security/*' \
  > "$TMP/forbidden" || true
if [ -s "$TMP/forbidden" ]; then
  fail "forbidden file(s) in the image:"
  sed "s#^$TMP/fs##" "$TMP/forbidden" >&2
fi

# 4. Secret strings under the application paths. Jars are unpacked so that
#    resources inside a Spring Boot fat jar are scanned as plain files.
mkdir -p "$TMP/scan"
n=0
for p in "${SCAN_PATHS[@]}"; do
  src="$TMP/fs${p}"
  if [ ! -e "$src" ]; then
    fail "scan path $p does not exist in the image"
    continue
  fi
  n=$((n + 1))
  cp -R "$src" "$TMP/scan/path$n"
  while IFS= read -r jar; do
    out="$TMP/scan/jar$n-$(basename "$jar" .jar)"
    mkdir -p "$out"
    unzip -q -o "$jar" -d "$out" -x '*.class' 2>/dev/null || true
    # Nested jars (BOOT-INF/lib) are third-party dependencies; drop them so only
    # the application's own resources are judged.
    rm -rf "$out/BOOT-INF/lib" "$out/WEB-INF/lib"
    rm -f "$jar"
  done < <(find "$TMP/scan/path$n" -type f -name '*.jar')
done

if command -v gitleaks > /dev/null 2>&1; then
  gitleaks dir --no-banner --redact=100 --exit-code 3 "$TMP/scan" > "$TMP/gitleaks.log" 2>&1 && rc=0 || rc=$?
else
  docker run --rm -v "$TMP/scan:/scan:ro" "$GITLEAKS_IMAGE" dir --no-banner --redact=100 --exit-code 3 /scan > "$TMP/gitleaks.log" 2>&1 && rc=0 || rc=$?
fi
if [ "$rc" -eq 3 ]; then
  fail "gitleaks found secret-looking strings in the image:"
  cat "$TMP/gitleaks.log" >&2
elif [ "$rc" -ne 0 ]; then
  fail "gitleaks could not run (exit $rc):"
  cat "$TMP/gitleaks.log" >&2
fi

if [ "$FAILED" -ne 0 ]; then
  echo "check-image: $IMAGE must not be published" >&2
  exit 1
fi
echo "check-image: OK - no secrets, no secret files, no secret environment in $IMAGE"
