#!/usr/bin/env bash
# Builds a render seam harness: the harness main, the seam, the WebGPU core and
# the vanilla blaze3d classes it reaches, compiled by TeaVM like the client (the profile's POM from
# generate-pom.sh with the harness main), plus the page and its assets.
#
#   bash port/tools/seam-harness/build.sh <seam> --overlays DIR --out DIR [--target js|wasmgc]
#
#   <seam>       blaze3d-backend-1 (profile 26.2) or blaze3d-direct-0 (profile 1.21.11)
#   --overlays   the profile's overlay directory (build-overlays.sh with renderSeam set: the client
#                jar carries the core and the seam)
#   --out        output: build/ (POM, staged sources, maven), web/ (page, classes.js|wasm, assets)
#   --target     TeaVM target of the overlays (default: the overlays' TEAVM_TARGET)
#
# Environment: GAIUS_JAVA_HOME (JDK 25), GAIUS_SHADER_TOOLCHAIN_PREBUILT (directory with
# gaius-shaderc.{js,wasm}, gaius-spvc.{js,wasm}; the browser shader toolchain), SHADERXLAT_WASM
# (gaius-shaderxlat-wasm build; default: built here with cargo --offline). The TeaVM step is a
# small compile (-Xmx3g). The page's vanilla shader sources are extracted from
# port/work/<profile>/client-named.jar into the output (never committed).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
seam="${1:-}"
shift || true
overlays=""
out=""
target=""
while (( $# > 0 )); do
  case "$1" in
    --overlays) overlays="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --target) target="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
case "$seam" in
  blaze3d-backend-1) profile=26.2; main=dev.gaius.harness.b1.B1Harness ;;
  blaze3d-direct-0) profile=1.21.11; main=dev.gaius.harness.d0.D0Harness ;;
  *) echo "usage: build.sh blaze3d-backend-1|blaze3d-direct-0 --overlays DIR --out DIR [--target js|wasmgc]" >&2; exit 2 ;;
esac
sources="$here/$seam/java"
[[ -n "$overlays" && -f "$overlays/client-named-$profile-gaius.jar" ]] || { echo "--overlays: no client jar in $overlays" >&2; exit 2; }
[[ -n "$out" ]] || { echo "--out is required" >&2; exit 2; }
target="${target:-$(tr -d '\r\n' < "$overlays/TEAVM_TARGET" 2>/dev/null || echo js)}"
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
mkdir -p "$out/build" "$out/web"
out="$(cd "$out" && pwd)"
overlays="$(cd "$overlays" && pwd)"
java_home="${GAIUS_JAVA_HOME:-${JAVA_HOME:-}}"
java_bin() { if [[ -n "$java_home" ]]; then printf '%s/bin/%s' "$(native_path "$java_home")" "$1"; else printf '%s' "$1"; fi; }
sep=":"; command -v cygpath >/dev/null 2>&1 && sep=";"

# 1. The profile's POM with the harness main; the harness sources join the staged sources.
started=$(date +%s)
pom="$(cd "$root" && GAIUS_VERSION_PROFILE_PATH="versions/$profile.json" GAIUS_OVERLAY_DIRECTORY="$overlays" \
  GAIUS_BUILD_ROOT="$out/build" GAIUS_TARGET_DIRECTORY="$out/web" GAIUS_TEAVM_TARGET="$target" \
  GAIUS_MAIN_CLASS="$main" GAIUS_POM="$out/build/harness-pom.xml" GAIUS_SOURCE_MAPS=false \
  bash port/scripts/generate-pom.sh 2>"$out/build/generate-pom.log")"
cp -R "$sources/." "$here/common/java/." "$out/build/sources/"
# The worktree's WebGPU core and seam join the staged sources instead of the copies
# build-overlays.sh put into the client jar, so core and seam edits need no overlay rebuild (the
# harness compiles seam + core + the vanilla classes they reach).
cp -R "$root/port/gpu/webgpu-core/src/main/java/." "$out/build/sources/"
cp -R "$root/port/mc/seams/$seam/src/main/java/." "$out/build/sources/"
sed -i "s|<artifactId>minecraft-$profile-browser</artifactId>|<artifactId>gaius-seam-harness-$seam</artifactId>|" "$pom"
# TeaVM resolves a class from the dependency jars before the compiled sources, so the client jar
# the harness compiles against leaves the core and the seam out.
py=python3
"$py" -c "" 2>/dev/null || py=python
"$py" - "$overlays/client-named-$profile-gaius.jar" "$out/build/client-harness.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        if not item.filename.startswith(("dev/gaius/gpu/webgpu/", "dev/gaius/mc/seams/")):
            dst.writestr(item, src.read(item.filename))
PY
sed -i "s|$(native_path "$overlays")/client-named-$profile-gaius.jar|$(native_path "$out/build/client-harness.jar")|" "$pom"
grep -q "client-harness.jar" "$pom" || { echo "could not point the POM at the harness client jar" >&2; exit 1; }

# 2. TeaVM (javac of the staged sources, then the harness closure).
MAVEN_OPTS="-Xms512m -Xmx3g -XX:+UseG1GC" "$root/port/mvnw" --batch-mode --errors --offline \
  --file "$(native_path "$pom")" package >"$out/build/teavm.log" 2>&1 || { tail -n 60 "$out/build/teavm.log" >&2; exit 1; }
compiled=$(date +%s)

# 3. The page and its assets.
cp "$here/web/index.html" "$here/web/run.mjs" "$out/web/" 2>/dev/null || cp "$here/web/index.html" "$out/web/"
cp "$root/port/web/runtime/webgpu/gpu-glue.js" "$out/web/"
cp "$root/port/native/golden/shaders/shaderxlat-job.js" "$out/web/"
cp "$root/port/wasm/shader-toolchain/loader/gaius-shader-toolchain.js" "$out/web/"
prebuilt="${GAIUS_SHADER_TOOLCHAIN_PREBUILT:-}"
for file in gaius-shaderc.js gaius-shaderc.wasm gaius-spvc.js gaius-spvc.wasm; do
  [[ -n "$prebuilt" && -f "$prebuilt/$file" ]] || { echo "GAIUS_SHADER_TOOLCHAIN_PREBUILT has no $file" >&2; exit 1; }
  cp "$prebuilt/$file" "$out/web/"
done
xlat="${SHADERXLAT_WASM:-}"
if [[ -z "$xlat" ]]; then
  (cd "$root/port/native/crates/gaius-shaderxlat" && CARGO_TARGET_DIR="$out/build/cargo" \
    CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
    cargo build --offline --locked --profile release-size --target wasm32-unknown-unknown -p gaius-shaderxlat-wasm) \
    >"$out/build/cargo.log" 2>&1
  xlat="$out/build/cargo/wasm32-unknown-unknown/release-size/gaius_shaderxlat_wasm.wasm"
fi
cp "$xlat" "$out/web/shaderxlat.wasm"
if [[ "$target" == wasmgc ]]; then
  # The client's loader (JSPI threads): run the page with ?js=gaius-wasm-boot.js.
  teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json" | tr -d '')"
  unzip -p "$overlays/teavm-core-$teavm_version-gaius.jar" org/teavm/backend/wasm/wasm-gc-runtime.js     >"$out/build/wasm-gc-runtime.js"
  node "$root/port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs" "$out/build/wasm-gc-runtime.js"     "$out/web/gaius-wasm-gc-runtime.js"
  cp "$root/port/web/runtime/wasmgc/gaius-wasm-boot.js" "$root/port/web/runtime/wasmgc/gaius-jspi.js" "$out/web/"
fi

# 4. The vanilla shader sources the harness's ShaderSource serves (JVM, the profile's classpath).
work="$root/port/work/$profile"
cp_list="$(native_path "$work/client-named.jar")"
while IFS= read -r lib; do
  [[ -n "$lib" && -f "$work/libraries/$lib" ]] && cp_list+="$sep$(native_path "$work/libraries/$lib")"
done < <(tr ':;' '\n\n' < "$work/classpath.txt" | sed -n 's|.*/libraries/||p' | tr -d '\r' | sort -u)
rm -rf "$out/build/extract-classes"
"$(java_bin javac)" --release 25 -proc:none -nowarn -encoding UTF-8 -d "$(native_path "$out/build/extract-classes")" \
  -cp "$cp_list" "$(native_path "$here/extract/java/dev/gaius/harness/extract/HarnessShaders.java")"
"$(java_bin java)" -Xmx1g -cp "$(native_path "$out/build/extract-classes")$sep$cp_list" \
  dev.gaius.harness.extract.HarnessShaders "$(native_path "$work/client-named.jar")" \
  "$(native_path "$out/web/shaders.json")"
echo "seam harness $seam ($target): TeaVM build $(( compiled - started )) s -> $out/web"
