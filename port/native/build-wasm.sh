#!/usr/bin/env bash
# Builds the wasm kernels (release, SIMD128, plus a baseline build without SIMD) and prints
# their sizes.
#
#   port/native/build-wasm.sh          build into target/wasm32-unknown-unknown/release/ (simd128)
#                                      and target/wasm-baseline/wasm32-unknown-unknown/release/
#   port/native/build-wasm.sh --check  build, then check exports and that every probe
#                                      job answers bit for bit like the native build (test),
#                                      for both builds
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET=wasm32-unknown-unknown
TARGET_ROOT="${CARGO_TARGET_DIR:-$HERE/target}"
OUT="$TARGET_ROOT/$TARGET/release"
BASELINE_OUT="$TARGET_ROOT/wasm-baseline/$TARGET/release"
# crate name : run_<kind> exports it must provide (the first one runs the probe jobs) : probe
# example (named per crate, since cargo examples share one output directory)
KERNELS=(
    "gaius_noise_wasm:run_noise_points:wasm_probe"
    "gaius_mesher_wasm:run_mesh_section,run_load_model_table:mesher_wasm_probe"
)

check=0
case "${1:-}" in
    "") ;;
    --check) check=1 ;;
    *) printf 'usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

die() {
    printf 'build-wasm: %s\n' "$1" >&2
    exit 1
}

command -v cargo > /dev/null || die "cargo not found"
rustup target list --installed 2>/dev/null | grep -qx "$TARGET" || die "missing rust target $TARGET (rustup target add $TARGET)"

cd "$HERE"
# Scoped to the wasm target so host builds and tests keep their own cache.
export CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128"
packages=()
for entry in "${KERNELS[@]}"; do
    packages+=(-p "${entry%%:*}")
done
cargo build --release --offline --locked --target "$TARGET" "${packages[@]//_/-}"
# The baseline build for engines without SIMD gets its own target directory.
CARGO_TARGET_DIR="$TARGET_ROOT/wasm-baseline" CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=-simd128" \
    cargo build --release --offline --locked --target "$TARGET" "${packages[@]//_/-}"

for entry in "${KERNELS[@]}"; do
    for dir in "$OUT" "$BASELINE_OUT"; do
        wasm="$dir/${entry%%:*}.wasm"
        [[ -f "$wasm" ]] || die "missing $wasm"
        printf '%-24s %8d bytes  %s\n' "${entry%%:*}.wasm" "$(wc -c < "$wasm")" "$wasm"
    done
done

if (( check )); then
    command -v node > /dev/null || die "node not found (needed for --check)"
    for entry in "${KERNELS[@]}"; do
        crate="${entry%%:*}"
        probe="$TARGET_ROOT/wasm-probe/$crate"
        rm -rf "$probe"
        rest="${entry#*:}"
        example="${rest##*:}"
        cargo run --quiet --offline --locked -p "${crate//_/-}" --example "$example" -- "$probe"
        IFS=',' read -r -a exports <<< "${rest%%:*}"
        for dir in "$OUT" "$BASELINE_OUT"; do
            node "$HERE/wasm-check.mjs" "$dir/$crate.wasm" "$probe" "${exports[@]}"
        done
    done
fi
