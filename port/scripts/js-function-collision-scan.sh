#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
input="${1:?usage: js-function-collision-scan.sh classes.js}"
repo="${M2_REPO:-${MAVEN_REPOSITORY:-$HOME/.m2/repository}}"
core="$repo/org/teavm/teavm-core/0.15.0/teavm-core-0.15.0.jar"
rhino="$repo/org/teavm/teavm-relocated-libs-rhino/0.15.0/teavm-relocated-libs-rhino-0.15.0.jar"
classes="${TMPDIR:?set TMPDIR to task scratch}/js-function-collision-scan-classes"
mkdir -p "$classes"
classpath="$core:$rhino"
javac -cp "$classpath" -d "$classes" \
  "$root/port/tools/jsbodycheck/JsFunctionCollisionScan.java"
java -Xmx8g -cp "$classes:$classpath" JsFunctionCollisionScan "$input"
