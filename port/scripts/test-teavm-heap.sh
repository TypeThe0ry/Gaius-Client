#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"

(
  unset MAVEN_OPTS
  GAIUS_TEAVM_MAX_HEAP=9g
  gaius_apply_teavm_heap "-Xms2g -XX:+UseG1GC"
  [[ "$MAVEN_OPTS" == "-Xms2g -XX:+UseG1GC -Xmx9g" ]]
)

(
  MAVEN_OPTS="-Xms1g -Xmx2g -Dfixture=true"
  GAIUS_TEAVM_MAX_HEAP=11g
  gaius_apply_teavm_heap "-Xms2g -Xmx14g -XX:+UseG1GC"
  [[ "$MAVEN_OPTS" == "-Xms1g -Dfixture=true -Xmx11g" ]]
)

(
  MAVEN_OPTS="-Xmx1g -Xmx2g"
  GAIUS_TEAVM_MAX_HEAP=12g
  gaius_apply_teavm_heap
  [[ "$(grep -o -- '-Xmx' <<<"$MAVEN_OPTS" | wc -l)" -eq 1 ]]
  [[ "$MAVEN_OPTS" == "-Xmx12g" ]]
)

if (GAIUS_TEAVM_MAX_HEAP=invalid gaius_apply_teavm_heap) >/dev/null 2>&1; then
  echo "invalid heap value was accepted" >&2
  exit 1
fi

echo "all TeaVM heap checks passed"
