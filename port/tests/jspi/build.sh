#!/usr/bin/env bash
# Builds the JSPI semantic suite (port/tests/jspi, design D3, M1 WP2) as a small TeaVM
# WEBASSEMBLY_GC program with the patched teavm-core/teavm-classlib of a wasmgc overlay
# directory and the Gaius options of the wasmgc client role (gaius.teavm.jspi=true), and
# stages a runnable directory: classes.wasm, gaius-wasm-gc-runtime.js (TeaVM's loader patched
# by port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs, as build-wasmgc.sh does), gaius-jspi.js
# and index.html.
#
#   port/tests/jspi/build.sh <out-dir>
#
# GAIUS_OVERLAY_DIRECTORY must name overlays built with GAIUS_TEAVM_TARGET=wasmgc
# (port/scripts/build-overlays.sh). The TeaVM compile is a small one and runs with -Xmx3g;
# Maven artifacts come from the local repository only (GAIUS_MAVEN_REPOSITORY or ~/.m2).
# Then: node port/tests/jspi/run-node.mjs <out-dir>, or run-chrome.mjs <out-dir>.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: port/tests/jspi/build.sh <out-dir>" >&2
  exit 2
fi
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
out="$1"
overlays="${GAIUS_OVERLAY_DIRECTORY:?GAIUS_OVERLAY_DIRECTORY must name a wasmgc overlay directory}"
if [[ "$(tr -d '\r\n' <"$overlays/TEAVM_TARGET" 2>/dev/null)" != wasmgc ]]; then
  echo "$overlays is not a complete wasmgc overlay directory (TEAVM_TARGET)" >&2
  exit 2
fi
if [[ -n "${GAIUS_JAVA_HOME:-}" ]]; then
  java_home="$GAIUS_JAVA_HOME"
  if command -v cygpath >/dev/null 2>&1; then
    java_home="$(cygpath -u "$java_home")"
  fi
  PATH="$java_home/bin:$PATH"
fi
teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json")"
repository="$(gaius_maven_repository_for_java "$root")"
separator=":"
native() { printf '%s\n' "$1"; }
if command -v cygpath >/dev/null 2>&1; then
  separator=";"
  native() { cygpath -m "$1"; }
fi
core="$(native "$overlays/teavm-core-$teavm_version-gaius.jar")"
classlib="$(native "$overlays/teavm-classlib-$teavm_version-gaius.jar")"
for jar in "$overlays/teavm-core-$teavm_version-gaius.jar" "$overlays/teavm-classlib-$teavm_version-gaius.jar"; do
  [[ -f "$jar" ]] || { echo "missing $jar" >&2; exit 2; }
done
deps=""
for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-jso-impl teavm-platform \
    teavm-metaprogramming-api teavm-metaprogramming-impl teavm-relocated-libs-asm \
    teavm-relocated-libs-asm-tree teavm-relocated-libs-asm-analysis teavm-relocated-libs-asm-commons \
    teavm-relocated-libs-asm-util teavm-relocated-libs-hppc teavm-relocated-libs-rhino \
    teavm-extension-apis teavm-extension-spi teavm-extension-spi-util teavm-tooling; do
  deps="$deps$separator$repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
deps="${deps#"$separator"}$separator$repository/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"
deps="$deps$separator$repository/joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"

rm -rf "$out"
mkdir -p "$out/classes" "$out/driver"
out_native="$(native "$out")"
javac -J-Duser.language=en --release 21 -proc:none -encoding UTF-8 \
  -cp "$classlib$separator$core$separator$deps" -d "$out_native/classes" \
  $(find "$here/src" -name '*.java' | sort | while read -r source; do native "$source"; done)
javac -J-Duser.language=en --release 21 -proc:none -encoding UTF-8 \
  -cp "$core$separator$deps" -d "$out_native/driver" "$(native "$here/driver/JspiSuiteBuild.java")"
java -Xmx3g -Duser.language=en \
  -cp "$out_native/driver$separator$core$separator$classlib$separator$deps$separator$out_native/classes" \
  JspiSuiteBuild "$out_native" dev.gaius.tests.jspi.JspiSuite \
  gaius.teavm.role=client gaius.teavm.jspi=true gaius.teavm.telemetry=true \
  | tee "$out/build.log"
grep -q 'jspi: async analysis skipped, 0 coroutine methods' "$out/build.log" \
  || { echo "the compile did not run in JSPI mode" >&2; exit 1; }
grep -q 'jspi: class initializers wait for their initializing thread' "$out/build.log" \
  || { echo "the compile generated no class initialization guard" >&2; exit 1; }
unzip -p "$overlays/teavm-core-$teavm_version-gaius.jar" org/teavm/backend/wasm/wasm-gc-runtime.js \
  >"$out/wasm-gc-runtime.js"
node "$root/port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs" \
  "$out/wasm-gc-runtime.js" "$out/gaius-wasm-gc-runtime.js"
cp "$root/port/web/runtime/wasmgc/gaius-jspi.js" "$here/index.html" "$out/"
echo "JSPI suite staged in $out"
