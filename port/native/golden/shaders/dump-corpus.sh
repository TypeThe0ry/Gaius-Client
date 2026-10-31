#!/usr/bin/env bash
# Dumps a Minecraft shader corpus with the vanilla frontend on the JVM: every render pipeline's
# shader modules plus the pipeline state a WebGPU backend needs.
#
#   port/native/golden/shaders/dump-corpus.sh [--mc 26.3|26.2|1.21.11] [--profile webgpu|gl]
#                                             [--out DIR]
#   port/native/golden/shaders/dump-corpus.sh --frontend-fixtures
#
# --mc 26.3 (default): the renderpearl frontend (ShaderXlatCorpusDump) compiles all pipelines with
#   the device characteristics of --profile and writes corpus.json + spirv/<sha256>.spv. The webgpu
#   dump also refreshes fixtures/ (the modules fixtures/MANIFEST lists for the crate's cargo test);
#   they are vanilla shaders, so git ignores them.
# --mc 26.2: the blaze3d frontend (ShaderXlatCorpusDump262, mode 26.2) runs the vanilla 26.2 Vulkan
#   front half up to shaderc and the vanilla link, and writes frontend.json + spirv/ (shaderc output
#   and vanilla result); shaderxlat-link turns it into corpus.json.
# --mc 1.21.11: ShaderXlatCorpusDump12111 dumps the 1.21.11 pipelines and their GLSL on the 1.21.11
#   classpath (DIR/legacy), then ShaderXlatCorpusDump262 (mode replay) compiles them with the 26.2
#   front half and writes frontend.json + spirv/ (shaderc output only: the link is lenient Rust).
# --frontend-fixtures: compiles the GLSL test shaders of the crate's front-end tests
#   (crates/gaius-shaderxlat/tests/frontend/*.vsh|fsh) the same way, next to their sources.
#
# Needs port/work/<mc> (client-named.jar, libraries/, classpath.txt from the fetch; 26.2 and
# 1.21.11 also port/work/26.3, whose LWJGL 3.4.3 shaderc natives are the revision of the browser
# shader toolchain) and JDK 25 (GAIUS_JAVA_HOME or JAVA_HOME, else PATH). DIR defaults to
# port/native/target/shader-corpus/<profile> (26.3) or .../<mc>-frontend; the compiled dumper goes
# to DIR-classes.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd -- "$HERE/../.." && pwd)"
PORT="$(cd -- "$NATIVE/.." && pwd)"
MC=26.3
PROFILE=webgpu
OUT=""
FIXTURES=0
while (( $# > 0 )); do
    case "$1" in
        --frontend-fixtures) FIXTURES=1; MC=26.2; shift ;;
        --mc) MC="${2:?--mc needs 26.3, 26.2 or 1.21.11}"; shift 2 ;;
        --profile) PROFILE="${2:?--profile needs webgpu or gl}"; shift 2 ;;
        --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
        *) printf 'usage: %s [--mc 26.3|26.2|1.21.11] [--profile webgpu|gl] [--out DIR] | --frontend-fixtures\n' "$0" >&2; exit 2 ;;
    esac
done

die() {
    printf 'dump-corpus: %s\n' "$1" >&2
    exit 1
}

case "$MC" in
    26.3) OUT="${OUT:-$NATIVE/target/shader-corpus/$PROFILE}" ;;
    26.2 | 1.21.11)
        [[ "$PROFILE" == webgpu ]] || die "--profile $PROFILE: the $MC corpus has the webgpu profile only"
        OUT="${OUT:-$NATIVE/target/shader-corpus/$MC-frontend}" ;;
    *) die "unknown --mc $MC" ;;
esac
OUT="${OUT%/}"

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

# Paths handed to Java must be native on Windows.
native_path() {
    if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

SEP=":"
[[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]] && SEP=";"

# classpath PROFILE [LWJGL_PROFILE]: client-named.jar plus the libraries of port/work/PROFILE.
# classpath.txt names the libraries of the machine that fetched them; each entry is mapped onto
# this checkout's port/work/<profile>/libraries. With LWJGL_PROFILE every org/lwjgl entry comes
# from that profile instead.
classpath() {
    local work="$PORT/work/$1" lwjgl="${2:-}" lib count=0 cp
    [[ -f "$work/client-named.jar" ]] || die "missing $work/client-named.jar"
    [[ -f "$work/classpath.txt" ]] || die "missing $work/classpath.txt"
    cp="$(native_path "$work/client-named.jar")"
    while IFS= read -r lib; do
        [[ -n "$lib" && -f "$work/libraries/$lib" ]] || continue
        [[ -n "$lwjgl" && "$lib" == org/lwjgl/* ]] && continue
        cp+="$SEP$(native_path "$work/libraries/$lib")"
        count=$((count + 1))
    done < <(tr ':;' '\n\n' < "$work/classpath.txt" | sed -n 's|.*/libraries/||p' | tr -d '\r' | sort -u)
    if [[ -n "$lwjgl" ]]; then
        local other="$PORT/work/$lwjgl"
        [[ -f "$other/classpath.txt" ]] || die "missing $other/classpath.txt (LWJGL of $lwjgl)"
        while IFS= read -r lib; do
            [[ "$lib" == org/lwjgl/* && -f "$other/libraries/$lib" ]] || continue
            cp+="$SEP$(native_path "$other/libraries/$lib")"
            count=$((count + 1))
        done < <(tr ':;' '\n\n' < "$other/classpath.txt" | sed -n 's|.*/libraries/||p' | tr -d '\r' | sort -u)
    fi
    (( count >= 40 )) || die "too few libraries resolved for $1: $count"
    printf '%s' "$cp"
}

# compile CLASSES CLASSPATH SOURCE...
compile() {
    local classes="$1" cp="$2"
    shift 2
    rm -rf "$classes"
    mkdir -p "$classes"
    local sources=()
    for source in "$@"; do sources+=("$(native_path "$HERE/java/dev/gaius/shaderxlat/$source")"); done
    "$(java_tool javac)" --release 25 -proc:none -nowarn -encoding UTF-8 -d "$(native_path "$classes")" -cp "$cp" "${sources[@]}"
}

CLASSES="$OUT-classes"
case "$MC" in
    26.3)
        CP="$(classpath 26.3)"
        compile "$CLASSES" "$CP" ShaderXlatCorpusDump.java
        rm -rf "$OUT"
        mkdir -p "$OUT"
        "$(java_tool java)" -Xmx3g --enable-native-access=ALL-UNNAMED \
            -cp "$(native_path "$CLASSES")$SEP$CP" dev.gaius.shaderxlat.ShaderXlatCorpusDump \
            "$PROFILE" "$(native_path "$PORT/work/26.3/client-named.jar")" "$(native_path "$OUT")"
        ;;
    26.2 | 1.21.11)
        CP262="$(classpath 26.2 26.3)"
        compile "$CLASSES" "$CP262" ShaderXlatCorpusDump262.java
        java262() {
            "$(java_tool java)" -Xmx3g --enable-native-access=ALL-UNNAMED \
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=$(native_path "$OUT-natives")" \
                -cp "$(native_path "$CLASSES")$SEP$CP262" dev.gaius.shaderxlat.ShaderXlatCorpusDump262 "$@"
        }
        if (( FIXTURES )); then
            tests="$NATIVE/crates/gaius-shaderxlat/tests/frontend"
            java262 fixtures "$(native_path "$tests")" "$(native_path "$tests")"
            rm -rf "$CLASSES" "$OUT-natives"
            exit 0
        fi
        rm -rf "$OUT"
        mkdir -p "$OUT"
        if [[ "$MC" == 26.2 ]]; then
            java262 26.2 "$(native_path "$PORT/work/26.2/client-named.jar")" "$(native_path "$OUT")"
        else
            CP12="$(classpath 1.21.11)"
            compile "$CLASSES-12111" "$CP12" ShaderXlatCorpusDump12111.java
            "$(java_tool java)" -Xmx2g -cp "$(native_path "$CLASSES-12111")$SEP$CP12" dev.gaius.shaderxlat.ShaderXlatCorpusDump12111 \
                "$(native_path "$PORT/work/1.21.11/client-named.jar")" "$(native_path "$OUT/legacy")"
            java262 replay "$(native_path "$OUT/legacy")" "$(native_path "$OUT")"
        fi
        ;;
esac

# The crate's test fixtures: "<sha256> <file>" lines of fixtures/MANIFEST name
# modules of the 26.3 webgpu dump (spirv/<sha256>.spv).
if [[ "$MC" == 26.3 && "$PROFILE" == webgpu ]]; then
    while read -r sha name; do
        [[ -z "$sha" || "${sha:0:1}" == "#" ]] && continue
        module="$OUT/spirv/$sha.spv"
        [[ -f "$module" ]] || die "the corpus has no module $sha for fixture $name"
        [[ "$(sha256sum "$module" | cut -d ' ' -f 1)" == "$sha" ]] || die "$module does not hash to $sha"
        cp "$module" "$HERE/fixtures/$name"
    done < "$HERE/fixtures/MANIFEST"
fi
