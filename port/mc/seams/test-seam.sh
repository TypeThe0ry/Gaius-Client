#!/usr/bin/env bash
# JVM unit tests of a render seam against the vanilla jar of every profile that selects it
# (EnumBridge completeness and flag mappings; seam.json "tests"). Offline, javac + java only.
#
#   bash port/mc/seams/test-seam.sh <generation> [profile...]
#   default: the port/versions/*.json profiles whose renderSeam is <generation>
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
source "$root/port/tools/seamprobe/seam-env.sh"

generation="${1:-}"
if [[ -z "$generation" || ! -f "$root/port/mc/seams/$generation/seam.json" ]]; then
  echo "usage: test-seam.sh <generation> [profile...]  (port/mc/seams/<generation>/seam.json)" >&2
  exit 2
fi
shift
seam_json="$root/port/mc/seams/$generation/seam.json"
profiles=("$@")
if [[ ${#profiles[@]} -eq 0 ]]; then
  while IFS= read -r profile; do
    profiles+=("$profile")
  done < <(jq -r --arg generation "$generation" 'select(.renderSeam == $generation) | .id' \
    "$root"/port/versions/*.json | tr -d '\r')
  if [[ ${#profiles[@]} -eq 0 ]]; then
    echo "No port/versions profile selects renderSeam $generation; name a profile to test against" >&2
    exit 2
  fi
fi
test_root="$root/port/mc/seams/$generation/$(jq -r '.tests.sources' "$seam_json" | tr -d '\r')"
status=0
for profile in "${profiles[@]}"; do
  seam_env_init "$root" "$profile"
  if [[ "$SEAM_GENERATION" != "$generation" ]]; then
    echo "FAIL $generation: port/versions/$profile.json selects renderSeam $SEAM_GENERATION" >&2
    status=1
    continue
  fi
  scratch="$(mktemp -d)"
  if ! seam_compile "$scratch" "$test_root"; then
    echo "FAIL $generation/$profile: does not compile against $SEAM_JAR" >&2
    status=1
    rm -rf "$scratch"
    continue
  fi
  while IFS= read -r main; do
    echo "== $generation/$profile $main"
    if ! "$SEAM_JAVA" -classpath "$(seam_path "$scratch/seam")$SEAM_SEP$SEAM_CLASSPATH" "$main"; then
      status=1
    fi
  done < <(jq -r '.tests.mains[]' "$seam_json" | tr -d '\r')
  rm -rf "$scratch"
done
exit "$status"
