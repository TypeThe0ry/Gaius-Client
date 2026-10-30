#!/usr/bin/env bash
# Exports the vanilla overworld as a worldgen kernel IR (WorldgenExport263 / GaiusWorldgenExport262)
# and dumps reference data computed by the real classes (final density, biomes, filled blocks,
# surface, heightmaps), then runs the kernel's vanilla comparison test against it.
#
#   port/native/golden/worldgen/run-worldgen-reference.sh [seed [x,z ...]]
#
# Environment: GAIUS_WORLDGEN_PROFILE (26.3 or 26.2, default 26.3), GAIUS_WORK (default: port/work
# of the main checkout), GOLDEN_JAVA_HOME (JDK 25).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../../.." && pwd)"
SEED="${1:-12345}"
shift || true
CHUNKS=("$@")
if (( ${#CHUNKS[@]} == 0 )); then
    CHUNKS=(0,0 7,-3 -20,11)
fi
JDK="${GOLDEN_JAVA_HOME:-${GAIUS_JAVA_HOME:-${JAVA_HOME:-$(dirname -- "$(dirname -- "$(command -v javac)")")}}}"
if [[ -z "${GAIUS_WORK:-}" ]]; then
    common_dir="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)"
    GAIUS_WORK="$(dirname -- "$common_dir")/port/work"
fi
PROFILE="${GAIUS_WORLDGEN_PROFILE:-26.3}"
WORK="$GAIUS_WORK/$PROFILE"
BUILD="$HERE/build"
OUT="$BUILD/reference-$PROFILE"

case "$PROFILE" in
    26.3)
        sources=(
            "$ROOT/port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/kernel/worldgen/WorldgenExport263.java"
            "$HERE/v26_3/dev/gaius/golden/worldgen/WorldgenReference.java"
        )
        main=dev.gaius.golden.worldgen.WorldgenReference
        ;;
    26.2)
        sources=(
            "$ROOT/port/mc/shared/canonical/src/java/net/minecraft/world/level/levelgen/GaiusWorldgenExport262.java"
            "$HERE/v26_2/net/minecraft/world/level/levelgen/GaiusWorldgenReference262.java"
        )
        main=net.minecraft.world.level.levelgen.GaiusWorldgenReference262
        ;;
    *)
        echo "unsupported GAIUS_WORLDGEN_PROFILE: $PROFILE" >&2
        exit 2
        ;;
esac

# Git Bash passes /d/... paths; the JDK wants D:/... and ';' separators.
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        SEP=';'
        native_path() { cygpath -m "$1"; }
        native_classpath() { tr ':' '\n' | sed -E 's#^/([a-zA-Z])/#\1:/#' | paste -sd ';' -; }
        ;;
    *)
        SEP=':'
        native_path() { printf '%s\n' "$1"; }
        native_classpath() { cat; }
        ;;
esac

classpath="$( { printf '%s' "$WORK/client-named.jar"; printf ':%s' "$(tr -d '\r\n' < "$WORK/classpath.txt")"; } | native_classpath)"
rm -rf "$BUILD/classes"
mkdir -p "$BUILD/classes" "$OUT"
rm -f "$OUT"/*.ref
classes="$(native_path "$BUILD/classes")"
native_sources=("$(native_path "$ROOT/port/src/main/java/dev/gaius/browser/kernel/worldgen/WorldgenIr.java")" "$(native_path "$HERE/StageTiming.java")")
for source in "${sources[@]}"; do
    native_sources+=("$(native_path "$source")")
done
"$JDK/bin/javac" -nowarn -proc:none -d "$classes" -cp "$classpath" "${native_sources[@]}"
(cd "$OUT" && "$JDK/bin/java" -cp "$classes$SEP$classpath" "$main" "$SEED" . "${CHUNKS[@]}")

cd "$ROOT/port/native"
GAIUS_WORLDGEN_REFERENCE="$(native_path "$OUT")" cargo test -p gaius-worldgen --test vanilla_reference -- --nocapture
