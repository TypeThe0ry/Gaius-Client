#!/usr/bin/env bash
# Two-level SPI closure fingerprint of a profile's render seam.
# Offline: compiles port/gpu/webgpu-core and port/mc/seams/<renderSeam of the profile> against
# port/work/<profile>/client-named.jar and runs SeamProbe.java (ASM from the local Maven
# repository) on the result.
#
#   bash port/tools/seamprobe/seamprobe.sh <profile>            print the summary
#   bash port/tools/seamprobe/seamprobe.sh <profile> --write    (re)write port/mc/profiles/<profile>/spi.fingerprint
#   bash port/tools/seamprobe/seamprobe.sh <profile> --check    compare with the committed fingerprint
#   bash port/tools/seamprobe/seamprobe.sh <profile> --print    print the whole fingerprint
#
# --check exits 0 when unchanged, 1 when the signature changed (the adapter has to change), 2 when
# only method bodies changed (review), 3 when seam.json is out of date or an input is missing.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
source "$root/port/tools/seamprobe/seam-env.sh"

profile="${1:-}"
mode="${2:-}"
if [[ -z "$profile" ]]; then
  echo "usage: seamprobe.sh <profile> [--write|--check|--print]" >&2
  exit 3
fi
seam_env_init "$root" "$profile" || exit 3
if [[ "$SEAM_GENERATION" == "none" ]]; then
  echo "Minecraft $profile selects no render seam (renderSeam is none); nothing to fingerprint."
  exit 0
fi
if [[ ! -f "$SEAM_DIR/seam.json" ]]; then
  echo "Render seam $SEAM_GENERATION has no seam.json ($SEAM_DIR)" >&2
  exit 3
fi
if [[ ! -f "$SEAM_JAR" ]]; then
  echo "Missing $SEAM_JAR: run fetch-version.sh and remap-client.sh for $profile first" >&2
  exit 3
fi
file_pattern="$(jq -r '.fingerprint.file' "$SEAM_DIR/seam.json" | tr -d '\r')"
fingerprint="$root/${file_pattern//\{profile\}/$profile}"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
seam_compile "$scratch" || { echo "The seam does not compile against $SEAM_JAR" >&2; exit 3; }

arguments=(--jar "$(seam_path "$SEAM_JAR")" --seam "$(seam_path "$SEAM_DIR")"
  --seam-classes "$(seam_path "$scratch/seam")" --profile "$profile")
case "$mode" in
  --write) arguments+=(--write "$(seam_path "$fingerprint")") ;;
  --check) arguments+=(--check "$(seam_path "$fingerprint")") ;;
  --print) arguments+=(--print) ;;
  "") ;;
  *) echo "Unknown mode $mode" >&2; exit 3 ;;
esac
"$SEAM_JAVA" -classpath "$SEAM_ASM" "$(seam_path "$root/port/tools/seamprobe/SeamProbe.java")" "${arguments[@]}"
