#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/port/scripts/version-profile.sh"
download_client="${2:-}"
manifest_url="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

for command in curl jq; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 1
  fi
done

# Default: the id of port/config.json's versionProfile (the default profile).
version="${1:-$(jq -er '.versionProfile' "$root/port/config.json" | sed -E 's#^.*/##; s#\.json$##')}"

version_url="$(
  gaius_mojang_curl "$manifest_url" |
    jq -er --arg version "$version" \
      '.versions[] | select(.id == $version) | .url'
)"
metadata="$(gaius_mojang_curl "$version_url")"

echo "Version metadata"
jq '{
  id,
  type,
  releaseTime,
  mainClass,
  javaVersion,
  assets,
  assetIndex,
  downloads: {
    client: .downloads.client,
    client_mappings: .downloads.client_mappings,
    server: .downloads.server
  },
  libraries: [.libraries[].name]
}' <<<"$metadata"

asset_url="$(jq -r '.assetIndex.url' <<<"$metadata")"
echo
echo "Asset index totals"
gaius_mojang_curl "$asset_url" |
  jq '{count: (.objects | length), total_size: ([.objects[].size] | add)}'

if [[ "$download_client" != "--download-client" ]]; then
  exit 0
fi

for command in unzip awk; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 1
  fi
done

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

client_url="$(jq -r '.downloads.client.url' <<<"$metadata")"
client_jar="$temporary_directory/client-$version.jar"
gaius_mojang_curl "$client_url" -o "$client_jar"

echo
echo "Client jar totals"
unzip -l "$client_jar" |
  awk '
    NR > 3 && $4 ~ /\.class$/ {
      classes++;
      bytes += $1
    }
    END {
      print "classes=" classes;
      print "uncompressed_class_bytes=" bytes
    }
  '
