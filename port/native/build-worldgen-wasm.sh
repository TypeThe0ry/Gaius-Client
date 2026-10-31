#!/usr/bin/env bash
# Builds the worldgen kernel twice: an optimized module with SIMD128 and a baseline module
# without it (for engines that cannot validate SIMD). The page picks one with
# GaiusWorldgenJob.pickModule (feature detection).
#
#   port/native/build-worldgen-wasm.sh          build both into target/wasm-worldgen/
#   port/native/build-worldgen-wasm.sh --check  build, then check the exports with node
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET=wasm32-unknown-unknown
TARGET_ROOT="${CARGO_TARGET_DIR:-$HERE/target}"
OUT="$TARGET_ROOT/wasm-worldgen"
CRATE=gaius_worldgen_wasm
EXPORTS=(memory alloc dealloc release gaius_abi_version run_load_generator run_biomes run_terrain run_surface run_ore)

check=0
case "${1:-}" in
    "") ;;
    --check) check=1 ;;
    *) printf 'usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

die() {
    printf 'build-worldgen-wasm: %s\n' "$1" >&2
    exit 1
}

command -v cargo > /dev/null || die "cargo not found"
rustup target list --installed 2>/dev/null | grep -qx "$TARGET" || die "missing rust target $TARGET (rustup target add $TARGET)"

cd "$HERE"
mkdir -p "$OUT"
# Each variant keeps its own target directory so the feature flags never share artifacts.
build() {
    local variant="$1" flags="$2"
    CARGO_TARGET_DIR="$TARGET_ROOT/wg-$variant" \
        CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="$flags" \
        cargo build --release --offline --locked --target "$TARGET" -p gaius-worldgen-wasm
    cp "$TARGET_ROOT/wg-$variant/$TARGET/release/$CRATE.wasm" "$OUT/$CRATE.$variant.wasm"
    printf '%-36s %8d bytes\n' "$CRATE.$variant.wasm" "$(wc -c < "$OUT/$CRATE.$variant.wasm")"
}
build simd "-C target-feature=+simd128"
build baseline "-C target-feature=-simd128"

if (( check )); then
    command -v node > /dev/null || die "node not found (needed for --check)"
    for variant in simd baseline; do
        node -e '
            const fs = require("fs");
            const [file, ...wanted] = process.argv.slice(1);
            const module = new WebAssembly.Module(fs.readFileSync(file));
            const names = new Set(WebAssembly.Module.exports(module).map((e) => e.name));
            const missing = wanted.filter((n) => !names.has(n));
            if (missing.length) { console.error(file + ": missing exports " + missing.join(", ")); process.exit(1); }
            if (WebAssembly.Module.imports(module).length) { console.error(file + ": unexpected imports"); process.exit(1); }
            console.log(file + ": exports ok");
        ' "$OUT/$CRATE.$variant.wasm" "${EXPORTS[@]}"
    done
fi
