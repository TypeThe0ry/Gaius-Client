#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
source_file="$root/port/wasm/hotpath/gaius_hotpath.c"
dist="$(gaius_dist_directory "$root")"
output="$dist/gaius-hotpath.wasm"

generate_directly() {
  echo "Generating the Gaius Wasm hot-path module directly." >&2
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/generate-wasm-hotpath.py" -o "$output"
}

if ! command -v clang >/dev/null 2>&1; then
  echo "clang was not found; using the deterministic Wasm generator." >&2
  generate_directly
  exit 0
fi

wasm_ld="${GAIUS_WASM_LD:-}"
if [[ -z "$wasm_ld" ]]; then
  wasm_ld="$(command -v wasm-ld || true)"
fi
if [[ -z "$wasm_ld" && "$(command -v ld.lld || true)" != "" ]]; then
  wasm_ld="$(command -v ld.lld)"
fi
if [[ -z "$wasm_ld" ]]; then
  echo "wasm-ld/ld.lld was not found; using the deterministic Wasm generator." >&2
  generate_directly
  exit 0
fi

export PATH="$(dirname "$wasm_ld"):$PATH"

mkdir -p "$dist"

clang \
  --target=wasm32 \
  -O3 \
  -flto \
  -nostdlib \
  -fno-builtin \
  -Wl,--no-entry \
  -Wl,--export-memory \
  -Wl,--initial-memory=67108864 \
  -Wl,--max-memory=67108864 \
  -Wl,--export=gaius_hotpath_version \
  -Wl,--export=gaius_shift_indices_capacity \
  -Wl,--export=gaius_shift_indices_input_ptr \
  -Wl,--export=gaius_shift_indices_output_ptr \
  -Wl,--export=gaius_repack_source_ptr \
  -Wl,--export=gaius_repack_output_ptr \
  -Wl,--export=gaius_repack_layouts_ptr \
  -Wl,--export=gaius_repack_source_capacity \
  -Wl,--export=gaius_repack_output_capacity \
  -Wl,--export=gaius_repack_layout_capacity \
  -Wl,--export=gaius_unpack_bit_storage_input_ptr \
  -Wl,--export=gaius_unpack_bit_storage_output_ptr \
  -Wl,--export=gaius_unpack_bit_storage_value_capacity \
  -Wl,--export=gaius_unpack_bit_storage_long_capacity \
  -Wl,--export=gaius_shift_indices \
  -Wl,--export=gaius_shift_indices_last_type \
  -Wl,--export=gaius_shift_indices_last_bytes \
  -Wl,--export=gaius_shift_indices_last_min \
  -Wl,--export=gaius_shift_indices_last_max \
  -Wl,--export=gaius_repack_interleaved \
  -Wl,--export=gaius_repack_last_bytes \
  -Wl,--export=gaius_unpack_bit_storage \
  -Wl,--export=gaius_unpack_bit_storage_last_values \
  -Wl,--strip-all \
  "$source_file" \
  -o "$output"

echo "Built Wasm hot-path module: $output"
