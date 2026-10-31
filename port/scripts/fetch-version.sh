#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
version="$GAIUS_MINECRAFT_VERSION"
work="$root/port/work/$version"
metadata="$work/version.json"
libraries="$work/libraries"
assets="$work/assets"
client_input="$work/client-original.jar"

mkdir -p "$work" "$libraries"

manifest_url="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
curl_with_retries() {
  curl --fail --silent --show-error --location --http1.1 \
    --retry 10 --retry-all-errors --retry-delay 2 --connect-timeout 20 "$@"
}

version_url="$(
  curl_with_retries "$manifest_url" |
    jq -er --arg version "$version" \
      '.versions[] | select(.id == $version) | .url'
)"
curl_with_retries -o "$metadata" "$version_url"

download_from_metadata() {
  local key="$1"
  local output="$2"
  local url sha1
  url="$(jq -er --arg key "$key" '.downloads[$key].url' "$metadata" | tr -d '\r\n')"
  sha1="$(jq -er --arg key "$key" '.downloads[$key].sha1' "$metadata" | tr -d '\r\n')"
  download_verified "$url" "$sha1" "$output"
}

download_verified() {
  local url="$1"
  local expected_sha1="$2"
  local output="$3"
  expected_sha1="$(printf '%s' "$expected_sha1" | tr -d '\r\n')"
  if [[ -f "$output" ]] &&
    [[ "$(gaius_sha1_file "$output")" == "$expected_sha1" ]]; then
    return
  fi

  mkdir -p "$(dirname "$output")"
  local temporary="$output.part"
  if ! curl_with_retries --continue-at - -o "$temporary" "$url"; then
    rm -f "$temporary"
    return 1
  fi
  local actual_sha1
  # Git Bash on Windows can emit CRLF while jq/curl output is LF-only. Normalize
  # both sides before comparing so an identical digest is not rejected.
  actual_sha1="$(gaius_sha1_file "$temporary")"
  if [[ "$actual_sha1" != "$expected_sha1" ]]; then
    echo "SHA-1 mismatch for $url" >&2
    echo "expected: $expected_sha1" >&2
    echo "actual:   $actual_sha1" >&2
    exit 1
  fi
  mv "$temporary" "$output"
}

echo "Fetching Minecraft $version client ($GAIUS_CLIENT_DISTRIBUTION)"
download_from_metadata client "$client_input"
if [[ "$GAIUS_CLIENT_DISTRIBUTION" == "obfuscated-with-mappings" ]]; then
  download_from_metadata client_mappings "$work/client-mappings.txt"
fi

echo "Fetching Java libraries"
jq -r '
  .libraries[]
  | select(.downloads.artifact != null)
  | [.downloads.artifact.path, .downloads.artifact.url, .downloads.artifact.sha1]
  | @tsv
' "$metadata" |
  while IFS=$'\t' read -r path url sha1; do
    download_verified "$url" "$sha1" "$libraries/$path"
  done

find "$libraries" -type f -name '*.jar' -print | sort |
  paste -sd ':' - >"$work/classpath.txt"

if command -v unzip >/dev/null 2>&1; then
  unzip -p "$client_input" version.json >"$work/client-version.json"
else
  python3 - "$client_input" version.json >"$work/client-version.json" <<'PY'
import sys
import zipfile

archive, member = sys.argv[1:]
with zipfile.ZipFile(archive) as bundle:
    sys.stdout.buffer.write(bundle.read(member))
PY
fi

asset_index_id="$(jq -er '.assetIndex.id // .assets' "$metadata" | tr -d '\r\n')"
asset_index_url="$(jq -er '.assetIndex.url' "$metadata" | tr -d '\r\n')"
asset_index_sha1="$(jq -er '.assetIndex.sha1' "$metadata" | tr -d '\r\n')"
asset_index="$assets/indexes/$asset_index_id.json"

echo "Fetching browser sound asset index $asset_index_id"
download_verified "$asset_index_url" "$asset_index_sha1" "$asset_index"

echo "Fetching browser gameplay sound assets"
browser_sound_metadata_assets=(
  "minecraft/sounds.json"
)
browser_font_assets=(
  "minecraft/font/include/unifont.json"
  "minecraft/font/include/unifont_pua.json"
  "minecraft/font/unifont.zip"
  "minecraft/font/unifont_jp.zip"
  "minecraft/font/unifont_pua.zip"
)
browser_background_assets=()
while IFS= read -r logical_path; do
  browser_background_assets+=("$logical_path")
done < <(jq -r '.objects | keys[] | select(startswith("minecraft/textures/gui/title/background/"))' "$asset_index" | tr -d '\r')
browser_sound_assets=(
  "minecraft/sounds/ui/toast/in.ogg"
  "minecraft/sounds/ui/toast/out.ogg"
  "minecraft/sounds/ui/toast/challenge_complete.ogg"
  "minecraft/sounds/ui/stonecutter/cut1.ogg"
  "minecraft/sounds/ui/stonecutter/cut2.ogg"
)
while IFS= read -r logical_path; do
  browser_sound_assets+=("$logical_path")
done < <(
  jq -r '
    .objects
    | keys[]
    | select(
        endswith(".ogg")
        and (
          startswith("minecraft/sounds/block/")
          or startswith("minecraft/sounds/random/")
          or startswith("minecraft/sounds/dig/")
          or startswith("minecraft/sounds/step/")
          or startswith("minecraft/sounds/mob/")
          or startswith("minecraft/sounds/entity/")
          or startswith("minecraft/sounds/item/")
          or startswith("minecraft/sounds/damage/")
          or startswith("minecraft/sounds/liquid/")
          or startswith("minecraft/sounds/fire/")
          or startswith("minecraft/sounds/portal/")
          or startswith("minecraft/sounds/minecart/")
          or startswith("minecraft/sounds/enchant/")
          or startswith("minecraft/sounds/fireworks/")
          or startswith("minecraft/sounds/event/")
          or startswith("minecraft/sounds/note/")
          or startswith("minecraft/sounds/tile/")
        )
      )
  ' "$asset_index" | tr -d '\r'
)
download_browser_asset() {
  local logical_path="$1"
  local hash
  hash="$(jq -er --arg path "$logical_path" '.objects[$path].hash' "$asset_index" | tr -d '\r\n')"
  download_verified \
    "https://resources.download.minecraft.net/${hash:0:2}/$hash" \
    "$hash" \
    "$assets/objects/${hash:0:2}/$hash"
}
export asset_index assets
export -f gaius_hash_file gaius_sha1_file gaius_sha256_file
export -f curl_with_retries download_verified download_browser_asset
# GAIUS_FETCH_BROWSER_ASSETS=0 skips the browser sound/font/background objects
# (thousands of files). Profile checks and overlay builds do not read them;
# only the TeaVM asset packaging does.
if [[ "${GAIUS_FETCH_BROWSER_ASSETS:-1}" == "0" ]]; then
  echo "Skipping browser asset objects (GAIUS_FETCH_BROWSER_ASSETS=0)"
else
  printf '%s\n' "${browser_sound_metadata_assets[@]}" "${browser_sound_assets[@]}" "${browser_font_assets[@]}" "${browser_background_assets[@]}" |
    xargs -n 1 -P "${GAIUS_FETCH_PARALLEL:-16}" bash -c 'download_browser_asset "$0"'
fi

echo "Fetched and verified:"
echo "  client:   $client_input"
if [[ "$GAIUS_CLIENT_DISTRIBUTION" == "obfuscated-with-mappings" ]]; then
  echo "  mappings: $work/client-mappings.txt"
else
  echo "  mappings: not required for the named client distribution"
fi
echo "  libraries: $(find "$libraries" -type f -name '*.jar' | wc -l | tr -d ' ')"
echo "  browser sound assets: ${#browser_sound_assets[@]} playable, ${#browser_sound_metadata_assets[@]} metadata"
echo "  browser Unicode font assets: ${#browser_font_assets[@]}"
jq '{id, protocol_version, world_version, java_version, pack_version}' \
  "$work/client-version.json"
node "$root/port/scripts/check-version-profile.mjs" \
  --profile "$GAIUS_VERSION_PROFILE" \
  --require-local
