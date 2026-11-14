#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$root/port/scripts/fixtures/jsbody-minifier"
out="${JSBODY_FIXTURE_OUT:?set JSBODY_FIXTURE_OUT to an output directory}"
overlay="${JSBODY_FIXTURE_CORE:-$root/port/work/overlays/26.3/teavm-core-0.15.0-gaius.jar}"
mkdir -p "$out"
python3 "$fixture/generate.py" "$out"
MAVEN_OPTS="${MAVEN_OPTS:--Xms256m -Xmx2g -XX:+UseG1GC}" \
  "$root/port/mvnw" --batch-mode --errors \
  "-Dmaven.repo.local=${MAVEN_REPOSITORY:-/root/.m2/repository}" \
  "-Dfixture.core.jar=$overlay" "-Dfixture.out=$out" \
  --file "$fixture/pom.xml" package
node --check "$out/dist/classes.js"
