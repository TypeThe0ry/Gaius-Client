#!/usr/bin/env bash
# Applies the two REQUIRED 1.21.11 patches of the blaze3d-direct-0 seam
# (Minecraft12111BrowserPatcher.patchInitRendererDeviceFactory and
# patchLineWidthVertexFormatAlignment, WebAssembly GC target) to port/work/1.21.11/client-named.jar
# and checks the result on the JVM under -Xverify:all (VerifyRequiredPatches): the rewritten
# RenderSystem verifies and calls the seam's device factory; with WebGPU asked for, every vanilla
# vertex format is WebGPU-addressable; without it, every format has exactly the layout of the
# unpatched jar. A JavaScript-target run must leave both classes alone. Offline: ASM from the
# local Maven repository.
#
#   bash port/mc/seams/blaze3d-direct-0/patches/verify-patches.sh [OUT_DIR]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../../../.." && pwd)"
source "$root/port/tools/seamprobe/seam-env.sh"
seam_env_init "$root" 1.21.11
if [[ "$SEAM_GENERATION" != blaze3d-direct-0 ]]; then
  echo "port/versions/1.21.11.json selects renderSeam $SEAM_GENERATION, not blaze3d-direct-0" >&2
  exit 1
fi
out="${1:-$(mktemp -d)}"
rm -rf "$out/tools" "$out/patched-wasmgc" "$out/patched-js" "$out/classes" "$out/stub"
mkdir -p "$out/tools" "$out/patched-wasmgc" "$out/patched-js" "$out/classes" "$out/stub"
repository="${GAIUS_MAVEN_REPOSITORY:-$HOME/.m2/repository}"
analysis="$(seam_path "$repository/org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar")"
# The tools sources compile as a whole, and TeaVMCoreBrowserPatcher needs teavm-core.
teavm_version="$(jq -er .teaVMVersion "$root/port/config.json" | tr -d '\r')"
teavm_core="$(seam_path "$repository/org/teavm/teavm-core/$teavm_version/teavm-core-$teavm_version.jar")"
find "$root/port/tools/src/main/java" -name '*.java' >"$out/tools-sources.txt"
if command -v cygpath >/dev/null 2>&1; then
  cygpath -m -f "$out/tools-sources.txt" >"$out/tools-sources.win.txt"
  mv "$out/tools-sources.win.txt" "$out/tools-sources.txt"
fi
"$SEAM_JAVAC" -J-Duser.language=en --release 21 -proc:none -nowarn -encoding UTF-8 \
  -d "$(seam_path "$out/tools")" -classpath "$SEAM_ASM$SEAM_SEP$analysis$SEAM_SEP$teavm_core" "@$(seam_path "$out/tools-sources.txt")"
for target in wasmgc js; do
  (cd "$root" && GAIUS_TEAVM_TARGET="$target" GAIUS_BRINGUP= "$SEAM_JAVA" \
    -classpath "$(seam_path "$out/tools")$SEAM_SEP$SEAM_ASM$SEAM_SEP$analysis" \
    dev.gaius.tools.Minecraft12111BrowserPatcher "$(seam_path "$SEAM_JAR")" "$(seam_path "$out/patched-$target")") \
    >"$out/patcher-$target.log"
done
grep -q 'PATCH_TARGET_SKIPPED Minecraft12111BrowserPatcher.patchInitRendererDeviceFactory target=js' "$out/patcher-js.log"
grep -q 'PATCH_TARGET_SKIPPED Minecraft12111BrowserPatcher.patchLineWidthVertexFormatAlignment target=js' "$out/patcher-js.log"
for class in com/mojang/blaze3d/systems/RenderSystem.class com/mojang/blaze3d/vertex/DefaultVertexFormat.class; do
  if [[ -f "$out/patched-js/$class" ]]; then
    echo "FAIL the JavaScript target wrote $class" >&2
    exit 1
  fi
  [[ -f "$out/patched-wasmgc/$class" ]] || { echo "FAIL the wasmgc target did not write $class" >&2; exit 1; }
done
echo "ok   the JavaScript target skips both patches"
"${SEAM_JAVAC%javac}javap" -c -p -classpath "$(seam_path "$out/patched-wasmgc")" com.mojang.blaze3d.systems.RenderSystem \
  | grep -q 'invokestatic .*dev/gaius/mc/seams/blaze3ddirect0/D0Devices.create:(JIZLcom/mojang/blaze3d/shaders/ShaderSource;Z)Lcom/mojang/blaze3d/systems/GpuDevice;'
echo "ok   RenderSystem.initRenderer creates its device through D0Devices.create"
"$SEAM_JAVAC" -J-Duser.language=en --release 21 -proc:none -nowarn -d "$(seam_path "$out/stub")" \
  "$(seam_path "$here/stub/dev/gaius/mc/seams/blaze3ddirect0/D0Devices.java")"
"$SEAM_JAVAC" -J-Duser.language=en --release 21 -proc:none -nowarn -d "$(seam_path "$out/classes")" \
  -classpath "$(seam_path "$SEAM_JAR")$SEAM_SEP$SEAM_LIBS" "$(seam_path "$here/VerifyRequiredPatches.java")"
patched="$(seam_path "$out/patched-wasmgc")$SEAM_SEP$(seam_path "$out/stub")$SEAM_SEP$(seam_path "$out/classes")"
vanilla="$(seam_path "$out/classes")$SEAM_SEP$(seam_path "$SEAM_JAR")$SEAM_SEP$SEAM_LIBS"
"$SEAM_JAVA" -Xverify:all -Dgaius.d0.webgpu=true -classpath "$patched$SEAM_SEP$vanilla" VerifyRequiredPatches \
  | tee "$out/webgpu.txt"
"$SEAM_JAVA" -Xverify:all -classpath "$patched$SEAM_SEP$vanilla" VerifyRequiredPatches | tee "$out/gl.txt"
"$SEAM_JAVA" -Xverify:all -classpath "$vanilla" VerifyRequiredPatches --layout-only >"$out/vanilla.txt"
if ! diff <(grep '^layout ' "$out/vanilla.txt") <(grep '^layout ' "$out/gl.txt"); then
  echo "FAIL the GL path's vertex formats differ from vanilla's" >&2
  exit 1
fi
echo "ok   without WebGPU every vertex format keeps vanilla's layout"
echo "both REQUIRED 1.21.11 patches verified"
