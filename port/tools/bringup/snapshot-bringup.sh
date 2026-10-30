#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo "Snapshot acquisition is CI-only; local bring-up never downloads" >&2
  exit 2
fi
root="$(cd "$(dirname "$0")/../../.." && pwd)"
out="${1:?usage: snapshot-bringup.sh <output-directory>}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
source "$root/port/scripts/version-profile.sh"
curl_ci() {
  curl --fail --silent --show-error --location --retry 3 --connect-timeout 20 "$@"
}
curl_ci https://piston-meta.mojang.com/mc/game/version_manifest_v2.json >"$out/manifest.json"
snapshot="$(jq -er '.latest.snapshot' "$out/manifest.json")"
[[ "$snapshot" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]] || exit 2
url="$(jq -er --arg id "$snapshot" '.versions[] | select(.id == $id) | .url' "$out/manifest.json")"
sha1="$(jq -er --arg id "$snapshot" '.versions[] | select(.id == $id) | .sha1' "$out/manifest.json")"
curl_ci "$url" >"$out/metadata.json"
[[ "$(gaius_sha1_file "$out/metadata.json")" == "$sha1" ]] || exit 2
work="$root/port/work/$snapshot"
mkdir -p "$work"
curl_ci "$(jq -er '.downloads.client.url' "$out/metadata.json")" >"$work/client-original.jar"
[[ "$(gaius_sha1_file "$work/client-original.jar")" == "$(jq -er '.downloads.client.sha1' "$out/metadata.json")" ]] || exit 2
java -Xmx3g "$root/port/tools/bringup/JarProbe.java" "$work/client-original.jar" \
  "$out/jar-version.json" "$out/classes.txt" >"$out/jar-stats.json"
node "$root/port/tools/bringup/snapshot-profile.mjs" "$out"
mapfile -t inputs < <(jq -r --arg id "$snapshot" '[.inputs[], $id] | unique[]' "$out/selection.json" | tr -d '\r')
for id in "${inputs[@]}"; do
  GAIUS_VERSION_PROFILE_PATH="versions/$id.json" GAIUS_FETCH_BROWSER_ASSETS=0 \
    bash "$root/port/scripts/fetch-version.sh" >"$out/fetch-$id.log" 2>&1
  GAIUS_VERSION_PROFILE_PATH="versions/$id.json" \
    bash "$root/port/scripts/remap-client.sh" >"$out/remap-$id.log" 2>&1
done
previous="$(jq -er '.previous' "$out/selection.json")"
set +e
node "$root/port/tools/bringup/gaius-bringup.mjs" "$snapshot" --fast --from "$previous" --out "$out/report"
result=$?
set -e
if [[ "$result" == "1" ]]; then
  echo "Snapshot needs review; report uploaded without promotion"
  exit 0
fi
exit "$result"
