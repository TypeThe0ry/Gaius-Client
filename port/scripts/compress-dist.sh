#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
dist="$(gaius_dist_directory "$root")"

if [[ ! -d "$dist" ]]; then
  echo "Missing dist directory: $dist" >&2
  exit 1
fi

shopt -s nullglob
if [[ -n "${GAIUS_COMPRESS_FILES:-}" ]]; then
  IFS=: read -r -a requested_files <<<"$GAIUS_COMPRESS_FILES"
  files=()
  for requested_file in "${requested_files[@]}"; do
    if [[ "$requested_file" == */* || ! -f "$dist/$requested_file" ]]; then
      echo "Missing or invalid dist asset: $requested_file" >&2
      exit 1
    fi
    files+=("$dist/$requested_file")
  done
else
  files=(
    "$dist"/*.js
    "$dist"/*.html
    "$dist"/*.css
    "$dist"/*.json
    "$dist"/*.wasm
  )
fi

if [[ -n "${GAIUS_COMPRESS_EXCLUDE:-}" ]]; then
  IFS=: read -r -a excluded_files <<<"$GAIUS_COMPRESS_EXCLUDE"
  filtered_files=()
  for file in "${files[@]}"; do
    excluded=false
    for excluded_file in "${excluded_files[@]}"; do
      if [[ -z "$excluded_file" || "$excluded_file" == */* ]]; then
        echo "Invalid dist compression exclusion: $excluded_file" >&2
        exit 1
      fi
      if [[ "${file##*/}" == "$excluded_file" ]]; then
        excluded=true
        break
      fi
    done
    if [[ "$excluded" != "true" ]]; then
      filtered_files+=("$file")
    fi
  done
  files=("${filtered_files[@]}")
fi

if [[ "${#files[@]}" -eq 0 ]]; then
  echo "No compressible dist assets found in $dist" >&2
  exit 1
fi

for file in "${files[@]}"; do
  gzip -kf -9 "$file"
  if command -v brotli >/dev/null 2>&1; then
    brotli -f -q 11 "$file"
  elif command -v node >/dev/null 2>&1; then
    node "$root/port/scripts/compress-brotli.mjs" "$file"
  else
    echo "Neither brotli nor node is available to create $file.br" >&2
    exit 1
  fi
done

echo "Compressed ${#files[@]} dist assets in $dist"
