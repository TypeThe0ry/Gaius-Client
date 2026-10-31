#!/usr/bin/env bash
# Proves the worldgen float rewrite (WorldgenStrictMath, the patch
# StrictMath263.roundWorldgenArithmetic) keeps vanilla semantics: rewrites the 26.3 client
# jar's worldgen scope, checks the bytecode (BasicVerifier, every float rounded, no raw cast
# left), checks the BrowserStrictMath helpers against the JVM's own casts and DoubleStream.sum,
# then dumps the 26.3 golden fixtures from the rewritten classes on the JVM and requires them to
# equal the checked-in fixtures byte for byte.
#
#   port/native/golden/strict/strict-math-check.sh
#
# The browser side (that TeaVM output rounds like this) is checked on a built client by
# port/native/teavm-fixture-check.mjs. Environment as for run-golden.sh.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GOLDEN="$(cd -- "$HERE/.." && pwd)"
ROOT="$(cd -- "$GOLDEN/../../.." && pwd)"
JDK="${GOLDEN_JAVA_HOME:-${GAIUS_JAVA_HOME:-${JAVA_HOME:-$(dirname -- "$(dirname -- "$(command -v javac)")")}}}"
M2="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"
ASM="$M2/org/ow2/asm"

if [[ -z "${GAIUS_WORK:-}" ]]; then
    GAIUS_WORK="$(dirname -- "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)")/port/work"
fi
JAR="$GAIUS_WORK/26.3/client-named.jar"
[[ -f "$JAR" ]] || { printf 'strict-math-check: missing %s\n' "$JAR" >&2; exit 1; }

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) SEP=';'; native() { cygpath -m "$1"; } ;;
    *) SEP=':'; native() { printf '%s\n' "$1"; } ;;
esac

BUILD="$GOLDEN/build/strict"
rm -rf "$BUILD"
mkdir -p "$BUILD/tools" "$BUILD/helper" "$BUILD/check" "$BUILD/rewritten" "$BUILD/twin"
asm_path="$(native "$ASM/asm/9.8/asm-9.8.jar")$SEP$(native "$ASM/asm-tree/9.8/asm-tree-9.8.jar")"
analysis="$(native "$ASM/asm-analysis/9.8/asm-analysis-9.8.jar")"
tools_src="$ROOT/port/tools/src/main/java"

"$JDK/bin/javac" --release 21 -proc:none -classpath "$asm_path" -sourcepath "$(native "$tools_src")" \
    -d "$(native "$BUILD/tools")" "$(native "$tools_src/dev/gaius/tools/patches/worldgen/WorldgenStrictMath.java")"
"$JDK/bin/javac" --release 21 -proc:none -classpath "$(native "$M2/org/teavm/teavm-jso/0.15.0/teavm-jso-0.15.0.jar")" \
    -d "$(native "$BUILD/helper")" "$(native "$ROOT/port/src/main/java/dev/gaius/browser/BrowserStrictMath.java")"
"$JDK/bin/javac" --release 21 -proc:none -classpath "$asm_path$SEP$analysis$SEP$(native "$BUILD/tools")" \
    -d "$(native "$BUILD/check")" "$(native "$HERE/StrictMathCheck.java")"

"$JDK/bin/java" -cp "$(native "$BUILD/tools")$SEP$asm_path" dev.gaius.tools.patches.worldgen.WorldgenStrictMath \
    "$(native "$JAR")" "$(native "$BUILD/rewritten")"
"$JDK/bin/java" -cp "$(native "$BUILD/check")$SEP$(native "$BUILD/tools")$SEP$asm_path$SEP$analysis" StrictMathCheck \
    "$(native "$JAR")" "$(native "$BUILD/rewritten")" \
    "$(native "$BUILD/helper/dev/gaius/browser/BrowserStrictMath.class")" "$(native "$BUILD/twin")"

GOLDEN_CLASSPATH_PREFIX="$(native "$BUILD/twin")$SEP$(native "$BUILD/rewritten")" GOLDEN_VERIFY_TAG=-strict \
    "$GOLDEN/run-golden.sh" --verify 26.3
printf 'strict-math-check: the rewritten 26.3 worldgen classes reproduce the golden fixtures\n'
