#!/usr/bin/env bash
set -euo pipefail

if [[ ! "${RELEASE_TAG:-}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-cn\.[0-9]+$ ]]; then
  echo "::error::RELEASE_TAG must be a DiPlay CN version tag"
  exit 1
fi
if [ -z "${GITEE_TOKEN:-}" ]; then
  echo "::error::GITEE_TOKEN secret is not configured"
  exit 1
fi

apk="DiPlay-cn-$RELEASE_TAG.apk"
sha="$apk.sha256"
for file in "$apk" "$sha" release-notes.md; do
  if [ ! -f "$file" ]; then
    echo "::error::Missing release file: $file"
    exit 1
  fi
done
shasum -a 256 -c "$sha"

api="https://gitee.com/api/v5/repos/oneeyear/DiPlay-CN"
temp_dir="$(mktemp -d)"
trap 'rm -r "$temp_dir"' EXIT

release_json() {
  curl -sS --connect-timeout 10 --max-time 30 --get \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    "$api/releases/tags/$RELEASE_TAG"
}

release_id="$(release_json | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get("id") or "" if isinstance(data,dict) else "")' || true)"
if [ -z "$release_id" ]; then
  code="$(curl -sS --connect-timeout 10 --max-time 60 \
    -o "$temp_dir/create.json" -w '%{http_code}' -X POST "$api/releases" \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    --data-urlencode "tag_name=$RELEASE_TAG" \
    --data-urlencode "name=DiPlay CN ${RELEASE_TAG#v}" \
    --data-urlencode "target_commitish=main" \
    --data-urlencode "body=$(cat release-notes.md)")"
  release_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("id") or "")' "$temp_dir/create.json" 2>/dev/null || true)"
  if [ -z "$release_id" ]; then
    echo "::error::Could not create or find Gitee release (HTTP $code)"
    exit 1
  fi
fi

verify_asset() {
  local file="$1" url downloaded
  url="$(release_json | python3 -c 'import json,sys; name=sys.argv[1]; data=json.load(sys.stdin); assets=data.get("assets", []) if isinstance(data,dict) else []; print(next((a.get("browser_download_url", "") for a in assets if a.get("name") == name), ""))' "$file")" || return 1
  [ -n "$url" ] || return 1
  downloaded="$temp_dir/$(basename "$file")"
  if ! curl -fsSL --connect-timeout 10 --max-time 180 "$url" -o "$downloaded"; then
    return 1
  fi
  if ! cmp -s "$file" "$downloaded"; then
    echo "::error::Gitee asset $file differs from the GitHub release artifact"
    exit 1
  fi
}

for file in "$apk" "$sha"; do
  if verify_asset "$file"; then
    echo "Gitee asset already verified: $file"
    continue
  fi
  uploaded=""
  for attempt in 1 2; do
    if curl -fsS --connect-timeout 10 --max-time 120 \
        --speed-limit 1024 --speed-time 45 -X POST \
        "$api/releases/$release_id/attach_files" \
        -F "access_token=$GITEE_TOKEN" -F "file=@$file" >/dev/null || verify_asset "$file"; then
      uploaded=1
      break
    fi
    echo "attach $file attempt $attempt failed"
    [ "$attempt" = 2 ] || sleep 15
  done
  if [ -z "$uploaded" ]; then
    echo "::error::attaching $file failed after retries"
    exit 1
  fi
  verified=""
  for attempt in 1 2; do
    if verify_asset "$file"; then
      verified=1
      break
    fi
    [ "$attempt" = 2 ] || sleep 10
  done
  if [ -z "$verified" ]; then
    echo "::error::Gitee asset $file could not be downloaded and verified"
    exit 1
  fi
done
echo "Gitee release assets verified: id=$release_id"
