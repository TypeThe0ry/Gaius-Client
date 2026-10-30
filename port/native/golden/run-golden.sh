#!/usr/bin/env bash
# Compiles the JVM golden-data harness against a profile's named client jar and
# dumps the golden fixtures into port/native/fixtures/<profile>/.
#
#   run-golden.sh [profile...]           regenerate fixtures (default: every version profile,
#                                        port/versions/*.json)
#   run-golden.sh --verify [profile...]  regenerate into a scratch directory and fail if
#                                        the result differs from the checked-in fixtures
#
# Environment: GAIUS_WORK overrides the directory holding <profile>/client-named.jar
# and <profile>/classpath.txt (default: port/work of the main checkout).
# GOLDEN_JAVA_HOME overrides the JDK (default: the active Java home).
# GOLDEN_CLASSPATH_PREFIX (a native class path) goes before the client jar when the harness
# runs, to dump from rewritten classes; --verify then works in build/verify<GOLDEN_VERIFY_TAG>-<profile>.
# GOLDEN_BUILD_ROOT isolates compiled harnesses and regenerated verification fixtures.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../.." && pwd)"
FIXTURES="$ROOT/port/native/fixtures"
BUILD_ROOT="${GOLDEN_BUILD_ROOT:-$HERE/build}"
MAX_BYTES=$((2 * 1024 * 1024))

JDK="${GOLDEN_JAVA_HOME:-${GAIUS_JAVA_HOME:-${JAVA_HOME:-$(dirname -- "$(dirname -- "$(command -v javac)")")}}}"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"

if [[ -z "${GAIUS_WORK:-}" ]]; then
    common_dir="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)"
    GAIUS_WORK="$(dirname -- "$common_dir")/port/work"
fi

die() {
    printf 'run-golden: %s\n' "$1" >&2
    exit 1
}

# Converts a colon-separated list of /d/... paths into a Windows classpath when
# running under Git Bash, and leaves it alone elsewhere.
native_classpath() {
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*)
            tr ':' '\n' | sed -E 's#^/([a-zA-Z])/#\1:/#' | paste -sd ';' - ;;
        *) cat ;;
    esac
}

native_path() {
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*) cygpath -m "$1" ;;
        *) printf '%s\n' "$1" ;;
    esac
}

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac

run_profile() {
    local profile="$1" out="$2"
    [[ "$profile" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]] || die "invalid profile id"
    local work="$GAIUS_WORK/$profile"
    local jar="$work/client-named.jar"
    [[ -f "$jar" ]] || die "missing $jar"
    [[ -f "$work/classpath.txt" ]] || die "missing $work/classpath.txt"

    # 26.3 replaced the double-precision ImprovedNoise stack with float
    # GradientNoise/NoiseStack, so the noise sources come in two variants.
    local variant=v26_3
    if "$JDK/bin/jar" tf "$jar" | grep -c '^net/minecraft/world/level/levelgen/synth/ImprovedNoise.class$' > /dev/null; then
        variant=classic
    fi

    local classpath
    classpath="$( { printf '%s' "$jar"; printf ':%s' "$(tr -d '\r\n' < "$work/classpath.txt")"; } | native_classpath)"

    local build="$BUILD_ROOT/$profile"
    [[ "$build" == "$BUILD_ROOT/"* ]] || die "build path outside build root"
    [[ "$out" == "$BUILD_ROOT/"* || "$out" == "$FIXTURES/"* ]] || die "fixture output outside selected roots"
    rm -rf "$build"
    mkdir -p "$build"
    local sources=()
    while IFS= read -r file; do
        sources+=("$(native_path "$file")")
    done < <(find "$HERE/src/common" "$HERE/src/$variant" -name '*.java' | sort)
    "$JAVAC" -J-Duser.language=en -Xlint:deprecation -encoding UTF-8 -d "$(native_path "$build")" -cp "$classpath" "${sources[@]}"

    rm -rf "$out"
    mkdir -p "$out"
    local prefix="${GOLDEN_CLASSPATH_PREFIX:-}"
    (cd "$build" && "$JAVA" -Xss16m -cp "${prefix:+$prefix$SEP}$(native_path "$build")$SEP$classpath" \
        dev.gaius.golden.Main "$profile" "$(native_path "$out")" 2> "$build/stderr.log") \
        || { cat "$build/stderr.log" >&2; die "harness failed for $profile"; }

    local file size
    for file in "$out"/*.jsonl; do
        size=$(wc -c < "$file")
        (( size <= MAX_BYTES )) || die "$file is $size bytes (limit $MAX_BYTES)"
    done
}

verify=0
if [[ "${1:-}" == "--verify" ]]; then
    verify=1
    shift
fi
profiles=("$@")
[[ "${GOLDEN_VERIFY_TAG:-}" =~ ^[-A-Za-z0-9._]*$ ]] || die "invalid golden verify tag"
mkdir -p "$BUILD_ROOT"
BUILD_ROOT="$(cd -- "$BUILD_ROOT" && pwd -P)"
if (( ${#profiles[@]} == 0 )); then
    mapfile -t profiles < <(jq -r '.id' "$ROOT"/port/versions/*.json | tr -d '\r')
    (( ${#profiles[@]} > 0 )) || die "no version profiles under $ROOT/port/versions"
fi

for profile in "${profiles[@]}"; do
    if (( verify )); then
        scratch="$BUILD_ROOT/verify${GOLDEN_VERIFY_TAG:-}-$profile"
        run_profile "$profile" "$scratch"
        diff -r "$FIXTURES/$profile" "$scratch" > /dev/null \
            || die "fixtures for $profile differ from a fresh dump (diff -r $FIXTURES/$profile $scratch)"
        printf 'run-golden: %s fixtures reproduce byte for byte\n' "$profile"
    else
        run_profile "$profile" "$FIXTURES/$profile"
    fi
done
