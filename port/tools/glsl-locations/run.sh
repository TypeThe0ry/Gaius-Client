#!/usr/bin/env bash
# The GLSL location injection of the blaze3d front-end (port/gpu/webgpu-core GlslLocations) against
# shaderc's auto_map_locations, on the 26.2 and 1.21.11 shader corpora (native shaderc of the
# browser toolchain's revision, then the gaius-shaderxlat link).
#
#   bash port/tools/glsl-locations/run.sh [26.2|1.21.11|all] [--out DIR] [--legacy DIR]
#
# --out     default port/native/target/glsl-locations
# --legacy  the 1.21.11 pipelines and GLSL dumped on the 1.21.11 classpath (meta.json + glsl/):
#           port/native/golden/shaders/dump-corpus.sh --mc 1.21.11 writes them to
#           port/native/target/shader-corpus/1.21.11-frontend/legacy (the default)
#
# Needs port/work/26.2 and port/work/26.3 (LWJGL 3.4.3 shaderc natives), JDK 25 (GAIUS_JAVA_HOME or
# JAVA_HOME) and cargo (offline). Exit status 0 when, for every pipeline, the shaderc output with
# injected locations is byte-identical to the auto-mapped one after GlslLocations.Result.restore,
# the vanilla Java link (26.2) and the Rust link give byte-identical modules and entries, and the
# unrestored output differs only in debug instructions.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
port="$(cd "$here/../.." && pwd)"
which="all"
out="$port/native/target/glsl-locations"
legacy="$port/native/target/shader-corpus/1.21.11-frontend/legacy"
while (( $# > 0 )); do
  case "$1" in
    26.2 | 1.21.11 | all) which="$1"; shift ;;
    --out) out="$2"; shift 2 ;;
    --legacy) legacy="$2"; shift 2 ;;
    *) echo "usage: run.sh [26.2|1.21.11|all] [--out DIR] [--legacy DIR]" >&2; exit 2 ;;
  esac
done
np() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
java_tool() {
  local home
  for home in "${GAIUS_JAVA_HOME:-}" "${JAVA_HOME:-}"; do
    if [[ -n "$home" && ( -x "$home/bin/$1" || -x "$home/bin/$1.exe" ) ]]; then
      printf '%s/bin/%s' "$home" "$1"
      return
    fi
  done
  printf '%s' "$1"
}
sep=":"
command -v cygpath >/dev/null 2>&1 && sep=";"
py=python3
"$py" -c "" 2>/dev/null || py=python

# The 26.2 client and libraries with the LWJGL of 26.3 (dump-corpus.sh's classpath 26.2 26.3).
classpath() {
  local work="$port/work/$1" lwjgl="$2" lib cp
  [[ -f "$work/client-named.jar" && -f "$work/classpath.txt" ]] || { echo "missing $work" >&2; exit 1; }
  cp="$(np "$work/client-named.jar")"
  while IFS= read -r lib; do
    [[ -n "$lib" && -f "$work/libraries/$lib" && "$lib" != org/lwjgl/* ]] || continue
    cp+="$sep$(np "$work/libraries/$lib")"
  done < <(tr ':;' '\n\n' < "$work/classpath.txt" | sed -n 's|.*/libraries/||p' | tr -d '\r' | sort -u)
  while IFS= read -r lib; do
    [[ "$lib" == org/lwjgl/* && -f "$port/work/$lwjgl/libraries/$lib" ]] || continue
    cp+="$sep$(np "$port/work/$lwjgl/libraries/$lib")"
  done < <(tr ':;' '\n\n' < "$port/work/$lwjgl/classpath.txt" | sed -n 's|.*/libraries/||p' | tr -d '\r' | sort -u)
  printf '%s' "$cp"
}

mkdir -p "$out"
cp262="$(classpath 26.2 26.3)"
classes="$out/classes"
rm -rf "$classes"
mkdir -p "$classes"
"$(java_tool javac)" --release 25 -proc:none -nowarn -encoding UTF-8 -d "$(np "$classes")" -cp "$cp262" \
  "$(np "$port/native/golden/shaders/java/dev/gaius/shaderxlat/ShaderXlatCorpusDump262.java")" \
  "$(np "$port/gpu/webgpu-core/src/main/java/dev/gaius/gpu/webgpu/GlslLocations.java")" \
  "$(np "$port/gpu/webgpu-core/src/main/java/dev/gaius/gpu/webgpu/CoreShaderStage.java")" \
  "$(np "$here/java/dev/gaius/shaderxlat/GlslLocationsProof.java")"
(cd "$port/native/crates/gaius-shaderxlat" && CARGO_TARGET_DIR="$out/cargo" \
  cargo build --offline --locked --release --features corpus --bin shaderxlat-link >"$out/cargo.log" 2>&1) \
  || { tail -n 20 "$out/cargo.log" >&2; exit 1; }
link="$out/cargo/release/shaderxlat-link"
[[ -x "$link" || -x "$link.exe" ]] || { echo "shaderxlat-link was not built" >&2; exit 1; }

status=0
run() {
  local mc="$1" dir="$out/$1" mode input
  rm -rf "$dir"
  mkdir -p "$dir"
  if [[ "$mc" == 26.2 ]]; then
    mode=strict
    input="$(np "$port/work/26.2/client-named.jar")"
  else
    [[ -f "$legacy/meta.json" ]] || { echo "no 1.21.11 legacy dump in $legacy (dump-corpus.sh --mc 1.21.11)" >&2; return 1; }
    mode=replay
    input="$(np "$legacy")"
  fi
  "$(java_tool java)" -Xmx3g --enable-native-access=ALL-UNNAMED \
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$(np "$out/natives")" \
    -cp "$(np "$classes")$sep$cp262" dev.gaius.shaderxlat.GlslLocationsProof "$mode" "$input" "$(np "$dir")" \
    2> >(grep -v '^WARNING' >&2) || return 1
  local path
  for path in auto inject inject-raw; do
    "$link" "$(np "$dir/$path")" "$(np "$dir/$path-linked")" >"$dir/$path-link.log" 2>&1 \
      || { tail -n 5 "$dir/$path-link.log" >&2; return 1; }
  done
  echo "linked, auto_map_locations vs injected locations + restore:"
  "$py" "$here/compare-linked.py" "$dir/auto-linked" "$dir/inject-linked" | tee "$dir/compare.txt" | sed -n 1p || return 1
  echo "linked, auto_map_locations vs injected locations without restore (debug instructions apart):"
  "$py" "$here/compare-linked.py" --debug-apart "$dir/auto-linked" "$dir/inject-raw-linked" \
    | tee "$dir/compare-raw.txt" | sed -n 1p || return 1
}
for mc in 26.2 1.21.11; do
  if [[ "$which" == all || "$which" == "$mc" ]]; then
    run "$mc" || status=1
  fi
done
exit "$status"
