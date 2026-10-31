#!/usr/bin/env bash
# Builds the module the WebGPU backend translates SPIR-V to WGSL with: the wasm32 crate
# port/native/crates/gaius-shaderxlat/wasm, workspace profile release-size, simd128 (the build
# port/native/golden/shaders/run-corpus.sh checks against native naga). stage-web-runtime.py
# stages it as runtime/webgpu/gaius-shaderxlat.wasm; gaius-boot.js compiles it for ?gpu=webgpu.
#
#   port/web/runtime/webgpu/build-translator.sh [--out FILE]
#
# Default output (what stage-web-runtime.py picks up without --shaderxlat):
#   port/native/target/shaderxlat-wasm/wasm32-unknown-unknown/release-size/gaius_shaderxlat_wasm.wasm
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd -- "$HERE/../../../native" && pwd)"
TARGET=wasm32-unknown-unknown
TARGET_DIR="${CARGO_TARGET_DIR:-$NATIVE/target/shaderxlat-wasm}"
OUT=""

while (( $# > 0 )); do
    case "$1" in
        --out) OUT="${2:?--out needs a file}"; shift 2 ;;
        *) printf 'usage: %s [--out FILE]\n' "$0" >&2; exit 2 ;;
    esac
done

command -v cargo > /dev/null || { echo "build-translator: cargo not found" >&2; exit 1; }
rustup target list --installed 2>/dev/null | grep -qx "$TARGET" \
    || { echo "build-translator: missing rust target $TARGET (rustup target add $TARGET)" >&2; exit 1; }

(cd "$NATIVE" && CARGO_TARGET_DIR="$TARGET_DIR" \
    CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
    cargo build --offline --locked --profile release-size --target "$TARGET" -p gaius-shaderxlat-wasm)
module="$TARGET_DIR/$TARGET/release-size/gaius_shaderxlat_wasm.wasm"
[[ -s "$module" ]] || { echo "build-translator: no $module" >&2; exit 1; }
if [[ -n "$OUT" ]]; then
    mkdir -p "$(dirname -- "$OUT")"
    cp -- "$module" "$OUT"
    module="$OUT"
fi
printf '%s\n' "$module"
