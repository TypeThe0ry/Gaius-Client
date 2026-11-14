#!/usr/bin/env bash
# Proves that patchPalettedContainerBrowserEncode (BrowserPaletteEntryCodec, BrowserPaletteRepack) leaves
# the chunk save encoding of block state and biome containers byte for byte as it was.
#
#   port/native/golden/storage/palette-encode-check.sh [profile...]    (default 26.3)
#
# For each profile it applies the patch to the profile's client-named.jar, then runs
# PalettedContainerEncodeCheck twice, on the vanilla classes and with the patched PalettedContainer
# in front, over the regions of REGION_DIRS (default: the three browser worlds of the tvfix capture,
# if present) plus seeded random containers, and requires the two digest files to be identical.
# The patched run must also have used the helpers (memo hits) and the helper unit checks must pass.
#
# Environment: GAIUS_WORK (default: port/work of this checkout, else of the main checkout),
# GOLDEN_JAVA_HOME / GAIUS_JAVA_HOME, TMP (scratch root), REGION_DIRS (colon separated), BENCH (runs, default 0).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../../.." && pwd)"
JDK="${GOLDEN_JAVA_HOME:-${GAIUS_JAVA_HOME:-${JAVA_HOME:-$(dirname -- "$(dirname -- "$(command -v javac)")")}}}"
M2="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"
ASM="$M2/org/ow2/asm"
SCRATCH="${TMP:-${TMPDIR:-/tmp}}"
BENCH="${BENCH:-0}"

if [[ -z "${GAIUS_WORK:-}" ]]; then
    if [[ -f "$ROOT/port/work/26.3/client-named.jar" ]]; then
        GAIUS_WORK="$ROOT/port/work"
    else
        GAIUS_WORK="$(dirname -- "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)")/port/work"
    fi
fi
if [[ -z "${REGION_DIRS:-}" ]]; then
    base=/data/projects/Gaius/v040/codex/out/chunkload/par/tvfix/browser-wasm
    REGION_DIRS=""
    for seed in 26300 20260930 -7327290349473744307; do
        [[ -d "$base/$seed/region" ]] && REGION_DIRS="${REGION_DIRS:+$REGION_DIRS:}$base/$seed/region"
    done
fi
IFS=':' read -r -a region_dirs <<< "${REGION_DIRS:-}"
(( ${#region_dirs[@]} > 0 )) || printf 'palette-encode-check: no region dirs, only the seeded containers are compared\n'

profiles=("$@")
(( ${#profiles[@]} > 0 )) || profiles=(26.3)

BUILD="$HERE/../build/palette-encode"
mkdir -p "$BUILD"
BUILD="$(cd -- "$BUILD" && pwd -P)"
asm_path="$ASM/asm/9.8/asm-9.8.jar:$ASM/asm-tree/9.8/asm-tree-9.8.jar"

rm -rf "$BUILD/tools"
mkdir -p "$BUILD/tools"
"$JDK/bin/javac" --release 21 -proc:none -classpath "$asm_path" -sourcepath "$ROOT/port/tools/src/main/java" \
    -d "$BUILD/tools" "$HERE/ApplyPalettedContainerPatch.java"

for profile in "${profiles[@]}"; do
    work="$GAIUS_WORK/$profile"
    jar="$work/client-named.jar"
    [[ -f "$jar" ]] || { printf 'palette-encode-check: missing %s\n' "$jar" >&2; exit 1; }
    # classpath.txt names the libraries of whichever checkout built it; point it at this work dir
    libs="$(tr -d '\r\n' < "$work/classpath.txt" | sed -E "s#[^:]*/port/work/#$GAIUS_WORK/#g")"
    classpath="$jar:$libs"
    out="$BUILD/$profile"
    rm -rf "$out"
    mkdir -p "$out/patched" "$out/helpers" "$out/check"

    "$JDK/bin/java" -cp "$BUILD/tools:$asm_path" ApplyPalettedContainerPatch "$jar" "$out/patched"
    "$JDK/bin/javac" --release 21 -proc:none -nowarn -cp "$classpath" -d "$out/helpers" \
        "$ROOT/port/mc/shared/canonical/src/java/dev/gaius/browser/BrowserPaletteEntryCodec.java" \
        "$ROOT/port/mc/shared/canonical/src/java/dev/gaius/browser/BrowserPaletteRepack.java"
    "$JDK/bin/javac" --release 21 -proc:none -nowarn -cp "$classpath" -d "$out/check" "$HERE/PalettedContainerEncodeCheck.java"

    # the patched class must really call both helpers (the memo hits in the run prove the codec hook too)
    patched_code="$("$JDK/bin/javap" -p -c -cp "$out/patched" net.minecraft.world.level.chunk.PalettedContainer)"
    grep -q 'dev/gaius/browser/BrowserPaletteEntryCodec.wrap' <<< "$patched_code" \
        || { printf 'palette-encode-check: %s: PalettedContainer.codec does not call BrowserPaletteEntryCodec.wrap\n' "$profile" >&2; exit 1; }
    grep -q 'dev/gaius/browser/BrowserPaletteRepack.reencode' <<< "$patched_code" \
        || { printf 'palette-encode-check: %s: reencodeContents does not call BrowserPaletteRepack.reencode\n' "$profile" >&2; exit 1; }

    run() {
        local label="$1" prefix="$2" digest="$3"
        "$JDK/bin/java" -Xss16m -Xmx6g -cp "${prefix:+$prefix:}$out/check:$out/helpers:$classpath" \
            PalettedContainerEncodeCheck "$label" "$digest" ${BENCH:+--bench "$BENCH"} "${region_dirs[@]}"
    }
    printf '== %s\n' "$profile"
    run vanilla "" "$out/vanilla.digest"
    run patched "$out/patched" "$out/patched.digest"
    cmp "$out/vanilla.digest" "$out/patched.digest" \
        || { printf 'palette-encode-check: %s: the patched encoding differs from vanilla (cmp above)\n' "$profile" >&2; exit 1; }
    printf 'palette-encode-check: %s: %s containers encode to identical bytes\n' "$profile" "$(wc -l < "$out/vanilla.digest")"
done
