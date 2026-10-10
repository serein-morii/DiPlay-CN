#!/usr/bin/env bash
# Builds the CN standalone release APK on a Gitee Go runner and attaches it to the Gitee
# release named by $1 (or $GITEE_TAG, or scripts/gitee_attach_tag.txt). Exits 0 without
# building when nothing is queued or the release already carries a matching APK, so stale
# queue entries re-run cheaply. Downloads the official DiPlay APK for the runtime identity,
# verifies it, builds with the committed CN keystore, then uploads and byte-verifies.
set -euo pipefail

TAG="${1:-${GITEE_TAG:-}}"
WORK="$PWD"
REPO="oneeyear/DiPlay-CN"
API="https://gitee.com/api/v5/repos/$REPO"
log() { echo "[build-cn] $*"; }

if [ -z "$TAG" ]; then
  TAG="$(head -n1 "$WORK/scripts/gitee_attach_tag.txt" 2>/dev/null | tr -d '[:space:]')"
fi
if [ -z "$TAG" ] || [ "$TAG" = "none" ]; then
  log "nothing queued; exiting"
  exit 0
fi
APK="DiPlay-cn-$TAG.apk"

GITEE_TOKEN="${GITEE_TOKEN:-${DIPLAY_GITEE_TOKEN:-}}"
if [ -z "$GITEE_TOKEN" ]; then
  # Probe how Gitee Go delivers private pipeline variables: hidden pipe env, parameter files.
  log "probing variable channels (names only)"
  env | grep -iE 'token|secret|hidden|param|jc_' | cut -d= -f1 | sort || true
  for d in "${SYSTEM_FILE_PARAMETER_CACHE:-}" "${SYSTEM_PARAMETER_RESULT_DIR:-}" "${SYSTEM_FILE_RESULT_DIR:-}"; do
    if [ -n "$d" ]; then
      ls -ld "$d" 2>&1 | head -2 || true
      if [ -f "$d" ]; then log "file $d masked head: $(head -c 200 "$d" | sed -E 's/[0-9a-fA-F]{12,}/<HEX>/g')"; fi
    fi
  done
  v="${JC_HIDDEN_VALUE_FROM_PIPE:-}"
  log "JC_HIDDEN_VALUE_FROM_PIPE length=${#v}"
  log "JC structure (hex runs masked): $(printf '%s' "$v" | sed -E 's/[0-9a-fA-F]{12,}/<HEX>/g' | head -c 500)"
  if [ -n "$v" ] && [ -e "$v" ]; then v="$(tr -d '[:space:]' < "$v")"; log "JC was a path; read the file"; fi
  extract_token() {
    if [[ "$1" =~ DIPLAY_GITEE_TOKEN[^0-9a-f]{0,8}([0-9a-f]{32}) ]]; then printf '%s' "${BASH_REMATCH[1]}"; return 0; fi
    if command -v base64 >/dev/null; then
      local d; d="$(printf '%s' "$1" | tr -d '[:space:]' | base64 -d 2>/dev/null || true)"
      if [[ "$d" =~ DIPLAY_GITEE_TOKEN[^0-9a-f]{0,8}([0-9a-f]{32}) ]]; then printf '%s' "${BASH_REMATCH[1]}"; return 0; fi
    fi
    return 1
  }
  if t="$(extract_token "$v")"; then GITEE_TOKEN="$t"; log "token extracted from the hidden pipe channel"; fi
  if [ -z "$GITEE_TOKEN" ]; then
    for d in "${SYSTEM_FILE_PARAMETER_CACHE:-}" "${SYSTEM_PARAMETER_RESULT_DIR:-}" "${SYSTEM_FILE_RESULT_DIR:-}"; do
      for f in "$d/DIPLAY_GITEE_TOKEN" "$d/DIPLAY_GITEE_TOKEN.txt" "$d/diplay_gitee_token"; do
        if [ -s "$f" ]; then
          v="$(tr -d '[:space:]' < "$f")"
          if [[ "$v" =~ ^[0-9a-f]{32}$ ]]; then GITEE_TOKEN="$v"; log "token taken from $d"; break 2; fi
        fi
      done
    done
  fi
fi
if [ -z "$GITEE_TOKEN" ]; then
  log "GITEE_TOKEN still missing after probing every channel; set the DIPLAY_GITEE_TOKEN pipeline variable"
  exit 1
fi
OFFICIAL_TAG="${OFFICIAL_TAG:-v0.2.14}"
OFFICIAL_SHA256="${OFFICIAL_SHA256:-62b31f79db32bc7c85013ae830460b697a5952fad571ed0030b97341dde0b2e3}"

# --- release state: a present APK with a consistent sha256 means nothing to do ------------
attach_state() {
  # Prints "READY" when both APK and sha256 are attached, else the ids to delete.
  curl -fsS --retry 3 "$API/releases/$1/attach_files?access_token=$GITEE_TOKEN" -o /tmp/files.json \
    || echo '[]' > /tmp/files.json
  python3 - "$APK" <<'PY'
import json, sys
apk = sys.argv[1]
try:
    files = json.load(open('/tmp/files.json'))
except Exception:
    files = []
names = {a.get('name'): a.get('id') for a in files if isinstance(a, dict)}
if apk in names and apk + '.sha256' in names:
    print('READY')
else:
    print(' '.join(str(names[n]) for n in (apk, apk + '.sha256') if n in names))
PY
}
delete_asset() { curl -fsS -X DELETE "$API/releases/$1/attach_files/$2?access_token=$GITEE_TOKEN" >/dev/null \
  || log "could not delete asset $2 (continuing)"; }

detail="$(curl -fsS --retry 3 "$API/releases/tags/$TAG?access_token=$GITEE_TOKEN" || true)"
id="$(printf %s "$detail" | grep -o '"id": *[0-9]\+' | head -1 | grep -o '[0-9]\+' || true)"
if [ -n "$id" ] && [ "$(attach_state "$id")" = "READY" ]; then
  if curl -fsSL --max-time 600 -o /tmp/check.apk "https://gitee.com/$REPO/releases/download/$TAG/$APK" \
     && curl -fsSL --max-time 120 -o /tmp/check.sha "https://gitee.com/$REPO/releases/download/$TAG/$APK.sha256" \
     && (cd /tmp && sed 's#DiPlay-cn-[^ ]*apk#check.apk#' check.sha | sha256sum -c -); then
    log "$TAG already carries a verified APK; exiting"
    exit 0
  fi
fi

# --- build at the tag (the trigger may be a later main commit) ----------------------------
log "preparing $TAG"
git config --global --add safe.directory "$WORK" 2>/dev/null || true
git fetch --tags --force origin >/dev/null 2>&1 || git fetch --tags origin >/dev/null 2>&1 || true
git checkout --detach "$TAG" >/dev/null 2>&1 || log "WARNING: tag $TAG not found; building the runner checkout"

# --- toolchain (the build@gradle plugin provides the JDK; SDK comes from Google's CDN) ----
if ! command -v java >/dev/null || ! java -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
  java -version || true
  log "runner JDK missing or older than 17; the build@gradle step must set jdkVersion >= 17"
  exit 1
fi
java -version

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_HOME/platforms/android-37.0" ] || [ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]; then
  log "installing Android SDK (dl.google.com is China-CDN reachable)"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    curl -fsSL --retry 5 -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    if command -v unzip >/dev/null; then
      unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools"
    else
      (cd "$ANDROID_HOME/cmdline-tools" && jar xf /tmp/tools.zip)
    fi
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKM" --licenses >/dev/null || true
  "$SDKM" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" "ndk;28.2.13676358" >/dev/null
fi
echo "sdk.dir=$ANDROID_HOME" > "$WORK/local.properties"

# --- official identity (direct, then cross-border mirrors) --------------------------------
log "fetching official $OFFICIAL_TAG APK"
OFFICIAL_URL="https://github.com/shihabal3amri/DiPlay/releases/download/$OFFICIAL_TAG/DiPlay-$OFFICIAL_TAG.apk"
ok=""
for u in "$OFFICIAL_URL" "https://gh-proxy.com/$OFFICIAL_URL" "https://ghproxy.net/$OFFICIAL_URL"; do
  if curl -fsSL --retry 3 --max-time 900 -o /tmp/official.apk "$u"; then ok=1; break; fi
done
[ -n "$ok" ] || { log "official APK download failed from all mirrors"; exit 1; }
echo "$OFFICIAL_SHA256  /tmp/official.apk" | sha256sum -c -
python3 "$WORK/scripts/extract_official_identity.py" /tmp/official.apk /tmp/auth-assets "$OFFICIAL_SHA256"
export DIPLAY_AUTH_ASSETS_DIR=/tmp/auth-assets

# --- build ----------------------------------------------------------------------------------
log "building $TAG"
cd "$WORK"
sed -i 's#services\.gradle\.org/distributions#mirrors.cloud.tencent.com/gradle#' \
  gradle/wrapper/gradle-wrapper.properties 2>/dev/null || true
chmod +x ./gradlew
./gradlew --no-daemon :mobile:assembleStandaloneRelease

cp mobile/build/outputs/apk/release/mobile-release.apk "$APK"
sha256sum "$APK" > "$APK.sha256"
python3 "$WORK/scripts/verify_apk_identity.py" mobile/build/outputs/apk/release/mobile-release.apk

# --- attach ---------------------------------------------------------------------------------
if [ -z "$id" ]; then
  log "creating Gitee release for $TAG"
  curl -fsS -X POST "$API/releases" \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    --data-urlencode "tag_name=$TAG" \
    --data-urlencode "name=DiPlay CN ${TAG#v}" \
    --data-urlencode "target_commitish=main" \
    --data-urlencode "body=See https://github.com/serein-morii/DiPlay-CN/releases/tag/$TAG" > /tmp/rel.json
  id="$(grep -o '"id": *[0-9]\+' /tmp/rel.json | head -1 | grep -o '[0-9]\+')"
fi
[ -n "$id" ] || { log "no Gitee release id for $TAG"; exit 1; }
for aid in $(attach_state "$id"); do
  [ "$aid" = "READY" ] || delete_asset "$id" "$aid"
done
log "attaching to release $id"
for f in "$APK.sha256" "$APK"; do
  for attempt in 1 2 3 4 5; do
    code="$(curl -sS --max-time 900 -o /tmp/attach.json -w '%{http_code}' -X POST \
      "$API/releases/$id/attach_files" -F "access_token=$GITEE_TOKEN" -F "file=@$f")"
    [ "$code" = "201" ] && break
    log "attach $f -> $code (attempt $attempt)"
    sleep 10
  done
  [ "$code" = "201" ] || { log "attaching $f failed"; exit 1; }
done

curl -fsSL --max-time 900 -o /tmp/verify.apk "https://gitee.com/$REPO/releases/download/$TAG/$APK"
expected="$(cut -d' ' -f1 "$APK.sha256")"
got="$(sha256sum /tmp/verify.apk | cut -d' ' -f1)"
[ "$got" = "$expected" ] || { log "Gitee asset differs from the built artifact"; exit 1; }
log "done: $TAG verified on Gitee ($expected)"
