#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
profile="$1"
export GAIUS_VERSION_PROFILE_PATH="versions/$profile.json"

# The patcher provisions these absolute paths from verified archives. Never
# let the Clang driver discover the linker or optimizer as a side effect of PATH.
: "${GAIUS_CLANG:?missing pinned Clang}" "${GAIUS_WASM_LD:?missing pinned wasm-ld}" \
  "${GAIUS_WASM_OPT:?missing pinned wasm-opt}" "${GAIUS_LLVM_VERSION:?missing LLVM pin}" \
  "${GAIUS_WASM_OPT_VERSION:?missing Binaryen pin}"
export GAIUS_WASM_OPT_STRICT=1

version_line() {
  local name="$1" tool="$2" version="$3" pattern="$4" tool_output line
  if [[ "$tool" != /* && ! "$tool" =~ ^[A-Za-z]:/ ]]; then
    echo "Pinned $name needs an absolute executable path: $tool" >&2
    return 1
  fi
  if ! tool_output="$("$tool" --version)"; then
    echo "Could not run pinned $name $version at $tool" >&2
    return 1
  fi
  line="${tool_output%%$'\n'*}"
  line="${line%$'\r'}"
  if [[ ! "$line" =~ $pattern || "${BASH_REMATCH[1]}" != "$version" ]]; then
    echo "Pinned $name at $tool reported '$line'; expected version $version" >&2
    return 1
  fi
  printf '%s\n' "$line"
}

# lld's upstream Linux binary uses these pinned compatibility libraries. Do
# not pass the modified loader path to any other part of the release build.
lld_version="$(LD_LIBRARY_PATH="${GAIUS_LLVM_LIBRARY_DIR:-}" version_line wasm-ld "$GAIUS_WASM_LD" "$GAIUS_LLVM_VERSION" '^LLD ([0-9.]+)( |$)')"
clang_version="$(version_line Clang "$GAIUS_CLANG" "$GAIUS_LLVM_VERSION" '^clang version ([0-9.]+)( |$)')"
binaryen_version="$(version_line wasm-opt "$GAIUS_WASM_OPT" "$GAIUS_WASM_OPT_VERSION" '^wasm-opt version ([0-9]+)( |$)')"
printf 'Wasm host tool: %s\n' "$clang_version" "$lld_version" "$binaryen_version"
jq -n --arg clang "$clang_version" --arg wasmLd "$lld_version" --arg wasmOpt "$binaryen_version" \
  '{clang: $clang, wasmLd: $wasmLd, wasmOpt: $wasmOpt}' >"${GAIUS_WASM_HOST_TOOLS_REPORT:?}"

# Keep the historical compile/export recipe in the normal release pipeline so
# its identity is written after the overlay edits. The recipe's source bytes
# are themselves part of that identity; replace only its Bash driver lookup.
unset CCC_OVERRIDE_OPTIONS
clang() {
  if [[ "${1:-}" != --target=wasm32 ]]; then
    echo 'Unexpected Clang invocation in the locked release' >&2
    return 1
  fi
  local linker="$GAIUS_WASM_LD" output="${!#}"
  if command -v cygpath >/dev/null 2>&1; then
    linker="$(cygpath -m "$linker")" || return
    output="$(cygpath -m "$output")" || return
  fi
  LD_LIBRARY_PATH="${GAIUS_LLVM_LIBRARY_DIR:-}" "$GAIUS_CLANG" --no-default-config \
    --no-wasm-opt "-fuse-ld=$linker" -Wl,--keep-section=target_features "$@" || return
  "$GAIUS_WASM_OPT" "$output" -O3 -o "$output"
}
export -f clang
export GAIUS_SKIP_WASM_HOTPATH=false GAIUS_REQUIRE_WASM_HOTPATH=true
exec bash "$root/port/scripts/build-version-release.sh" "$profile"
