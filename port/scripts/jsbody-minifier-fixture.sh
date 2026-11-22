#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
fixture="$root/port/scripts/fixtures/jsbody-minifier"
out="${JSBODY_FIXTURE_OUT:?set JSBODY_FIXTURE_OUT to an output directory}"
overlay="${JSBODY_FIXTURE_CORE:-$root/port/work/overlays/26.3/teavm-core-0.15.0-gaius.jar}"
mkdir -p "$out"
"$root/port/scripts/run-python.sh" "$fixture/generate.py" "$out"
maven_offline_args=()
if [[ "${GAIUS_MAVEN_OFFLINE:-false}" == "true" ]]; then
  maven_offline_args=(--offline)
fi
teavm_max_heap="${GAIUS_TEAVM_MAX_HEAP:-2g}"
if [[ ! "$teavm_max_heap" =~ ^[1-9][0-9]*[gm]$ ]]; then
  echo "Invalid GAIUS_TEAVM_MAX_HEAP: $teavm_max_heap (expected e.g. 2g)" >&2
  exit 2
fi
gaius_apply_teavm_heap "-Xms256m -XX:+UseG1GC"
if [[ -z "${GAIUS_MAVEN_REPOSITORY:-}" && -n "${MAVEN_REPOSITORY:-}" ]]; then
  export GAIUS_MAVEN_REPOSITORY="$MAVEN_REPOSITORY"
fi
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
MAVEN_OPTS="$MAVEN_OPTS" \
  "$root/port/mvnw" --batch-mode --errors "${maven_offline_args[@]}" \
  "-Dmaven.repo.local=$maven_repository_for_java" \
  "-Dfixture.core.jar=$overlay" "-Dfixture.out=$out" \
  --file "$fixture/pom.xml" package
node --check "$out/dist/classes.js"
