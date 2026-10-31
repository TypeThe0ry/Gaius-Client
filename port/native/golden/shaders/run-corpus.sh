#!/usr/bin/env bash
# Shader translation corpus test: dumps a Minecraft pipeline corpus, links it through the GLSL
# front-end (blaze3d profiles), translates every module natively, builds the wasm module and checks
# it in Node, and with --chrome compiles every WGSL module and builds every pipeline in headless
# Chrome WebGPU.
#
#   port/native/golden/shaders/run-corpus.sh [--mc 26.3|26.2|1.21.11] [--profile webgpu|gl]
#                                            [--no-dump] [--chrome] [--swiftshader]
#                                            [--layout split|flat] [--no-flip-y]
#                                            [--target-dir DIR] [--report FILE]
#
# --mc 26.3 (default): the renderpearl corpus, 216 pipelines (--profile gl: the WebGL2 device
#   profile, depth -1..1). --mc 26.2: 113 pipelines; the strict link must reproduce the vanilla
#   26.2 Vulkan front-end byte for byte. --mc 1.21.11: 113 pipelines through the lenient link, and
#   the translation remaps GL's depth range at the vertex output.
# The translation uses the v0.4.0 convention (split binding layout, y flipped); --layout flat
# --no-flip-y is the first prototype's. --no-dump reuses an earlier dump; --swiftshader runs the Chrome
# stage on Dawn's SwiftShader adapter (no GPU needed). Everything (dumps, outputs, cargo targets)
# lands below --target-dir (default port/native/target; GAIUS_NATIVE_TARGET also sets it).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd -- "$HERE/../.." && pwd)"
CRATE="$NATIVE/crates/gaius-shaderxlat"
TARGET="${GAIUS_NATIVE_TARGET:-$NATIVE/target}"
MC=26.3
PROFILE=webgpu
dump=1
stages=wasm
chrome_args=()
report=()
xlat_args=()
usage() {
    printf 'usage: %s [--mc 26.3|26.2|1.21.11] [--profile webgpu|gl] [--no-dump] [--chrome] [--swiftshader]\n' "$0" >&2
    printf '          [--layout split|flat] [--no-flip-y] [--target-dir DIR] [--report FILE]\n' >&2
    exit 2
}
while (( $# > 0 )); do
    case "$1" in
        --mc) MC="${2:?--mc needs 26.3, 26.2 or 1.21.11}"; shift 2 ;;
        --profile) PROFILE="${2:?--profile needs webgpu or gl}"; shift 2 ;;
        --no-dump) dump=0; shift ;;
        --chrome) stages=wasm,chrome; shift ;;
        --swiftshader)
            stages=wasm,chrome
            chrome_args=(--fallback-adapter true --chrome-args "--enable-unsafe-webgpu --use-webgpu-adapter=swiftshader --enable-unsafe-swiftshader")
            shift ;;
        --layout) xlat_args+=(--layout "${2:?--layout needs split or flat}"); shift 2 ;;
        --no-flip-y) xlat_args+=(--no-flip-y); shift ;;
        --target-dir) TARGET="${2:?--target-dir needs a directory}"; shift 2 ;;
        --report) report=(--report "${2:?--report needs a file}"); shift 2 ;;
        *) usage ;;
    esac
done
case "$MC" in
    26.3)
        CORPUS="$TARGET/shader-corpus/$PROFILE"
        OUT="$TARGET/shaderxlat-out/$PROFILE"
        ;;
    26.2 | 1.21.11)
        [[ "$PROFILE" == webgpu ]] || usage
        FRONTEND="$TARGET/shader-corpus/$MC-frontend"
        CORPUS="$TARGET/shader-corpus/$MC"
        OUT="$TARGET/shaderxlat-out/$MC"
        stages="link,$stages"
        # 1.21.11 builds its projections for GL's clip space (no isZZeroToOne).
        [[ "$MC" == 1.21.11 ]] && xlat_args+=(--gl-depth-remap)
        ;;
    *) usage ;;
esac

step() {
    printf '\n== %s\n' "$1"
}

dumped=0
if [[ "$MC" == 26.3 ]]; then
    if (( dump )) || [[ ! -f "$CORPUS/corpus.json" ]]; then
        step "dump corpus (26.3, $PROFILE)"
        "$HERE/dump-corpus.sh" --mc 26.3 --profile "$PROFILE" --out "$CORPUS"
        [[ "$PROFILE" == webgpu ]] && dumped=1
    fi
elif (( dump )) || [[ ! -f "$FRONTEND/frontend.json" ]]; then
    step "dump corpus ($MC)"
    "$HERE/dump-corpus.sh" --mc "$MC" --out "$FRONTEND"
fi

step "native front-end and translation"
# A webgpu dump has just refreshed the test fixtures: then the tests must not skip them.
(cd "$CRATE" && GAIUS_SHADERXLAT_REQUIRE_FIXTURES="$dumped" \
    CARGO_TARGET_DIR="$TARGET/shaderxlat" cargo test --locked -p gaius-shaderxlat -p gaius-shaderxlat-wasm --quiet)
(cd "$CRATE" && CARGO_TARGET_DIR="$TARGET/shaderxlat" cargo build --locked --release --quiet --features corpus --bins)
if [[ "$MC" != 26.3 ]]; then
    rm -rf "$CORPUS"
    "$TARGET/shaderxlat/release/shaderxlat-link" "$FRONTEND" "$CORPUS"
fi
rm -rf "$OUT"
"$TARGET/shaderxlat/release/shaderxlat-corpus" "$CORPUS" "$OUT" ${xlat_args[@]+"${xlat_args[@]}"}

step "wasm build (simd128)"
WASM_TARGET="$TARGET/shaderxlat-wasm-simd"
(cd "$CRATE" && CARGO_TARGET_DIR="$WASM_TARGET" \
    CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
    cargo build --locked --profile release-size --quiet --target wasm32-unknown-unknown -p gaius-shaderxlat-wasm)

step "check ($stages)"
check_args=(--stages "$stages" --corpus "$CORPUS" --native "$OUT"
    --wasm "$WASM_TARGET/wasm32-unknown-unknown/release-size/gaius_shaderxlat_wasm.wasm")
[[ "$MC" != 26.3 ]] && check_args+=(--frontend "$FRONTEND")
node "$HERE/xlat-check.mjs" "${check_args[@]}" ${chrome_args[@]+"${chrome_args[@]}"} ${report[@]+"${report[@]}"}
