#!/usr/bin/env bash
# Builds the worker wasm kernels twice and writes the runtime manifest:
#   <name>.simd.wasm      optimized build, -C target-feature=+simd128
#   <name>.baseline.wasm  the same crate without SIMD, for engines whose WebAssembly.validate
#                         rejects the SIMD probe (port/web/kernels/kernel-runtime.js) or whose
#                         SIMD module fails to compile
#   kernels.json          {schema, kernels: {<name>: {crate, kinds, variants: {simd, baseline}}}}
#
#   port/native/build-wasm-variants.sh [--out DIR]     default DIR: target/kernels
#
# A kernel is a cdylib crate named crates/gaius-<name>-wasm (gaius-noise-wasm -> "noise") that
# exports memory, alloc and at least one run_<kind>; the kinds are read from the module exports.
# Each build gets its own cargo target directory so the two never invalidate each other.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET=wasm32-unknown-unknown
TARGET_ROOT="${CARGO_TARGET_DIR:-$HERE/target}"
OUT="$TARGET_ROOT/kernels"

while (( $# > 0 )); do
    case "$1" in
        --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
        *) printf 'usage: %s [--out DIR]\n' "$0" >&2; exit 2 ;;
    esac
done

die() {
    printf 'build-wasm-variants: %s\n' "$1" >&2
    exit 1
}

command -v cargo > /dev/null || die "cargo not found"
command -v node > /dev/null || die "node not found (needed to read the module exports)"
rustup target list --installed 2>/dev/null | grep -qx "$TARGET" || die "missing rust target $TARGET (rustup target add $TARGET)"

crates=()
for manifest in "$HERE"/crates/gaius-*-wasm/Cargo.toml; do
    [[ -f "$manifest" ]] || continue
    crate="$(basename -- "$(dirname -- "$manifest")")"
    [[ "$crate" != gaius-png-wasm ]] || continue
    crates+=("$crate")
done
(( ${#crates[@]} > 0 )) || die "no kernel crates (crates/gaius-<name>-wasm)"
packages=()
for crate in "${crates[@]}"; do
    packages+=(-p "$crate")
done

cd "$HERE"
build() {
    local variant="$1" flags="$2"
    CARGO_TARGET_DIR="$TARGET_ROOT/wasm-$variant" \
    CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="$flags" \
        cargo build --release --offline --locked --target "$TARGET" "${packages[@]}"
}
build simd "-C target-feature=+simd128"
build baseline "-C target-feature=-simd128"

mkdir -p "$OUT"
entries=()
for crate in "${crates[@]}"; do
    name="${crate#gaius-}"
    name="${name%-wasm}"
    name="${name//-/_}"
    artifact="${crate//-/_}.wasm"
    for variant in simd baseline; do
        source="$TARGET_ROOT/wasm-$variant/$TARGET/release/$artifact"
        [[ -f "$source" ]] || die "missing $source"
        cp -- "$source" "$OUT/$name.$variant.wasm"
    done
    entries+=("$name=$crate")
done

node "$HERE/js/kernel-manifest.mjs" "$OUT" "${entries[@]}"
