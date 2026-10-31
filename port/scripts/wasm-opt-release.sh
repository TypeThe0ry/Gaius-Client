#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"

# The binaryen step of the WebAssembly GC release role (build-wasmgc.sh --release).
#
#   wasm-opt-release.sh <module.wasm> --out <optimized.wasm> [--report <report.json>]
#
# Runs wasm-opt -Os on the module TeaVM wrote, with its name section (-g keeps and rewrites
# it, so the symbol file that build-wasmgc.sh splits off afterwards names the functions of the
# optimized module).  Settings:
#   * open world: no --closed-world.  The closed-world GC passes treat every type as private to
#     the module and miscompiled a JSO callback (a Java object handed to JavaScript and called
#     back later), so they stay off.
#   * only features that every browser of the runtime baseline (JSPI: Chrome 137, Firefox 153,
#     Safari 27) ships, never -all: a pass may only emit what the input already needed.
#   * -Os: smaller than -O2 on these modules and no slower at run time.
# The report records the tool version, the arguments and both modules (bytes, sha256).
#
# GAIUS_WASM_OPT names wasm-opt (default: the one on PATH); "off" skips the step on purpose.
# The output is only reproducible with one binaryen release, GAIUS_WASM_OPT_VERSION (default
# 133): another version, or no wasm-opt at all, skips the step with a warning, and
# GAIUS_WASM_OPT_STRICT=1 (release builds of build-release-matrix.sh) makes both an error.
# A skipped step copies the module unchanged to --out and says why in the report.

usage() {
  echo "usage: $0 <module.wasm> --out <optimized.wasm> [--report <report.json>]" >&2
  exit 2
}

input=""
output=""
report=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --out)
      [[ "$#" -ge 2 ]] || usage
      output="$2"
      shift 2
      ;;
    --report)
      [[ "$#" -ge 2 ]] || usage
      report="$2"
      shift 2
      ;;
    -*)
      usage
      ;;
    *)
      [[ -z "$input" ]] || usage
      input="$1"
      shift
      ;;
  esac
done
[[ -n "$input" && -n "$output" ]] || usage
if [[ ! -s "$input" ]]; then
  echo "wasm-opt-release.sh: missing module $input" >&2
  exit 1
fi

expected_version="${GAIUS_WASM_OPT_VERSION:-133}"
strict="${GAIUS_WASM_OPT_STRICT:-0}"
# The features of the browser baseline; binaryen's own defaults are not relied on.
features=(
  --enable-gc
  --enable-reference-types
  --enable-exception-handling
  --enable-bulk-memory
  --enable-nontrapping-float-to-int
  --enable-sign-ext
  --enable-mutable-globals
  --enable-multivalue
  --enable-simd
)
# Binaryen 133's memory-packing rewrite can wrap active data offsets in the
# large WasmGC server modules.  Keep the pass disabled; this preserves the
# optimized pipeline and avoids shipping modules that fail at instantiation.
arguments=(-Os -g --skip-pass=memory-packing "${features[@]}")

native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

sha256_of() {
  sha256sum "$1" | cut -d' ' -f1
}

json_string() {
  # Paths and versions only: no control characters; escape backslashes and quotes.
  local value="${1//\\/\\\\}"
  printf '"%s"' "${value//\"/\\\"}"
}

write_report() {
  local applied="$1" reason="$2" version="$3" seconds="$4"
  [[ -n "$report" ]] || return 0
  local argument_list="" argument
  for argument in "${arguments[@]}"; do
    argument_list+="${argument_list:+, }$(json_string "$argument")"
  done
  mkdir -p "$(dirname "$report")"
  {
    printf '{\n'
    printf '  "tool": "wasm-opt",\n'
    printf '  "applied": %s,\n' "$applied"
    printf '  "reason": %s,\n' "$(json_string "$reason")"
    printf '  "expectedVersion": %s,\n' "$(json_string "$expected_version")"
    printf '  "version": %s,\n' "$(json_string "$version")"
    printf '  "arguments": [%s],\n' "$argument_list"
    printf '  "input": {"bytes": %s, "sha256": "%s"},\n' "$(wc -c <"$input" | tr -d ' ')" "$(sha256_of "$input")"
    printf '  "output": {"bytes": %s, "sha256": "%s"},\n' "$(wc -c <"$output" | tr -d ' ')" "$(sha256_of "$output")"
    printf '  "seconds": %s\n' "$seconds"
    printf '}\n'
  } >"$report"
}

skip() {
  local reason="$1" version="${2:-}"
  if [[ "$strict" == 1 ]]; then
    echo "wasm-opt-release.sh: $reason (GAIUS_WASM_OPT_STRICT=1)" >&2
    exit 1
  fi
  echo "WARNING: $reason; $(basename "$input") ships without the binaryen step" >&2
  mkdir -p "$(dirname "$output")"
  if [[ "$(native_path "$input")" != "$(native_path "$output")" ]]; then
    cp -f "$input" "$output"
  fi
  write_report false "$reason" "$version" 0
  echo "wasm-opt: skipped ($reason)"
  exit 0
}

tool="${GAIUS_WASM_OPT:-}"
if [[ "$tool" == off ]]; then
  [[ "$strict" == 1 ]] && { echo "wasm-opt-release.sh: GAIUS_WASM_OPT=off in a strict build" >&2; exit 1; }
  skip "GAIUS_WASM_OPT=off"
fi
if [[ -z "$tool" ]]; then
  tool="$(command -v wasm-opt 2>/dev/null || true)"
  [[ -n "$tool" ]] || skip "no wasm-opt (set GAIUS_WASM_OPT to binaryen $expected_version's wasm-opt)"
elif [[ ! -x "$tool" ]]; then
  skip "GAIUS_WASM_OPT does not name an executable: $tool"
fi
version_line="$("$tool" --version 2>/dev/null | head -n 1 | tr -d '\r' || true)"
version="$(sed -nE 's/^wasm-opt version ([0-9]+).*/\1/p' <<<"$version_line")"
if [[ "$version" != "$expected_version" ]]; then
  skip "wasm-opt is '${version_line:-unknown}', not binaryen $expected_version" "$version"
fi

mkdir -p "$(dirname "$output")"
temporary="$output.tmp-$$"
rm -f "$temporary"
started="$(date +%s)"
if ! "$tool" "${arguments[@]}" "$(native_path "$input")" -o "$(native_path "$temporary")"; then
  rm -f "$temporary"
  echo "wasm-opt-release.sh: wasm-opt failed on $input" >&2
  exit 1
fi
seconds="$(( $(date +%s) - started ))"
if [[ ! -s "$temporary" ]]; then
  echo "wasm-opt-release.sh: wasm-opt wrote no module" >&2
  exit 1
fi
# An out-of-bounds active data segment is only reported by V8 at instantiation,
# after compile validation has already passed. Reject such output and retain
# TeaVM's module (build-wasmgc.sh has already re-encoded TeaVM's own offsets
# with wasm-data-fix.mjs, so the input itself passes this check).
if ! node "$root/port/tools/wasm/wasm-data-check.mjs" "$temporary"; then
  echo "wasm-opt: rejected output with an out-of-bounds active data segment; keeping TeaVM module" >&2
  # The report writer hashes the output path: put TeaVM's module there first.
  cp -- "$input" "$temporary"
  mv -f "$temporary" "$output"
  write_report false "invalid active data segment" "$version" "$seconds"
  echo "wasm-opt $version: skipped invalid rewrite" >&2
  exit 0
fi
mv -f "$temporary" "$output"
write_report true "" "$version" "$seconds"
echo "wasm-opt $version: $(wc -c <"$input" | tr -d ' ') -> $(wc -c <"$output" | tr -d ' ') bytes in ${seconds}s"
