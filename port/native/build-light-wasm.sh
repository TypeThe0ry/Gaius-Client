#!/usr/bin/env bash
# Builds the light kernel twice: with SIMD128 (the default for every browser that has it) and
# without (baseline, for engines that reject SIMD opcodes). light-job.js picks one at runtime.
#
#   port/native/build-light-wasm.sh          build both into target/light/
#   port/native/build-light-wasm.sh --check  build, then run the node smoke against both
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../.." && pwd)"
TARGET=wasm32-unknown-unknown
TARGET_ROOT="${CARGO_TARGET_DIR:-$HERE/target}"
OUT="$TARGET_ROOT/light"

check=0
case "${1:-}" in
    "") ;;
    --check) check=1 ;;
    *) printf 'usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

die() {
    printf 'build-light-wasm: %s\n' "$1" >&2
    exit 1
}

command -v cargo > /dev/null || die "cargo not found"
rustup target list --installed 2>/dev/null | grep -qx "$TARGET" || die "missing rust target $TARGET (rustup target add $TARGET)"

cd "$HERE"
mkdir -p "$OUT"

# Separate target dirs: the two builds differ only in RUSTFLAGS, which would otherwise make
# cargo rebuild everything on every switch.
CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
    cargo build --release --offline --locked --target "$TARGET" -p gaius-light-wasm
cp "$TARGET_ROOT/$TARGET/release/gaius_light_wasm.wasm" "$OUT/gaius_light_wasm.simd.wasm"

CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=-simd128" \
    cargo build --release --offline --locked --target "$TARGET" -p gaius-light-wasm --target-dir "$TARGET_ROOT/baseline"
cp "$TARGET_ROOT/baseline/$TARGET/release/gaius_light_wasm.wasm" "$OUT/gaius_light_wasm.baseline.wasm"

for wasm in "$OUT"/gaius_light_wasm.*.wasm; do
    printf '%-32s %8d bytes\n' "$(basename "$wasm")" "$(wc -c < "$wasm")"
done

if (( check )); then
    command -v node > /dev/null || die "node not found (needed for --check)"
    node "$ROOT/port/scripts/light-kernel-smoke.mjs" "$OUT/gaius_light_wasm.simd.wasm" "$OUT/gaius_light_wasm.baseline.wasm"
fi
