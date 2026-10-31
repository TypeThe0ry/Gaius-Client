#!/usr/bin/env bash
set -euo pipefail

# Plumbing test of wasm-opt-release.sh with stand-in wasm-opt executables (no binaryen needed):
# the pinned version runs with the release arguments, every other case skips with a warning and
# copies the module, and GAIUS_WASM_OPT_STRICT=1 turns each skip into an error.
#
#   bash port/scripts/test-wasm-opt-release.sh

root="$(cd "$(dirname "$0")/../.." && pwd)"
script="$root/port/scripts/wasm-opt-release.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
fail() {
  echo "FAIL $1" >&2
  failures=$((failures + 1))
}
ok() {
  echo "ok   $1"
}

printf '\0asm\1\0\0\0module' >"$work/in.wasm"
# A stand-in wasm-opt: reports a version, records its arguments, writes a different module.
fake() {
  local version="$1" path="$work/wasm-opt-$1"
  cat >"$path" <<EOF
#!/usr/bin/env bash
if [[ "\$1" == --version ]]; then echo "wasm-opt version $version (version_$version)"; exit 0; fi
printf '%s\n' "\$@" >"$work/arguments-$version"
out=""
while [[ "\$#" -gt 0 ]]; do
  if [[ "\$1" == -o ]]; then out="\$2"; shift 2; else shift; fi
done
if [[ "\${FAKE_INVALID:-}" == 1 ]]; then
  node -e 'const fs=require("fs"); const b=Buffer.from("0061736d010000000b0a010041ffffffff0f0b0100","hex"); fs.writeFileSync(process.argv[1],b)' "\$out"
else
  printf '\0asm\1\0\0\0optimized' >"\$out"
fi
EOF
  chmod +x "$path"
  printf '%s\n' "$path"
}

# The pinned release runs with -Os -g and the baseline features, and reports both modules.
tool="$(fake 133)"
if GAIUS_WASM_OPT="$tool" bash "$script" "$work/in.wasm" --out "$work/out.wasm" --report "$work/report.json" >/dev/null \
    && grep -q optimized "$work/out.wasm" \
    && grep -qx -- -Os "$work/arguments-133" && grep -qx -- -g "$work/arguments-133" \
    && grep -qx -- '--skip-pass=memory-packing' "$work/arguments-133" \
    && grep -qx -- --enable-gc "$work/arguments-133" && grep -qx -- --enable-exception-handling "$work/arguments-133" \
    && ! grep -qx -- -all "$work/arguments-133" && ! grep -qx -- --closed-world "$work/arguments-133" \
    && grep -q '"applied": true' "$work/report.json" && grep -q '"version": "133"' "$work/report.json" \
    && grep -q "\"sha256\": \"$(sha256sum "$work/in.wasm" | cut -d' ' -f1)\"" "$work/report.json"; then
  ok "binaryen 133 runs with the release arguments"
else
  fail "binaryen 133 runs with the release arguments"
fi

# Binaryen's wrapped active data offset is rejected and the TeaVM input is kept.
rm -f "$work/invalid.wasm" "$work/invalid.json"
if FAKE_INVALID=1 GAIUS_WASM_OPT="$tool" bash "$script" "$work/in.wasm" --out "$work/invalid.wasm" --report "$work/invalid.json" \
    >/dev/null 2>"$work/invalid.stderr" \
    && cmp -s "$work/in.wasm" "$work/invalid.wasm" \
    && grep -q '"reason": "invalid active data segment"' "$work/invalid.json"; then
  ok "wrapped active data segments are rejected"
else
  fail "wrapped active data segments are rejected"
fi

# Another binaryen release, a missing tool and GAIUS_WASM_OPT=off skip: the module is copied.
other="$(fake 120)"
for case_name in other-version missing off; do
  case "$case_name" in
    other-version) setting="$other" ;;
    missing) setting="$work/no-such-wasm-opt" ;;
    off) setting=off ;;
  esac
  rm -f "$work/skip.wasm" "$work/skip.json"
  if GAIUS_WASM_OPT="$setting" bash "$script" "$work/in.wasm" --out "$work/skip.wasm" --report "$work/skip.json" \
        >/dev/null 2>"$work/stderr" \
      && cmp -s "$work/in.wasm" "$work/skip.wasm" && grep -q '"applied": false' "$work/skip.json" \
      && grep -q WARNING "$work/stderr"; then
    ok "$case_name skips with a warning"
  else
    fail "$case_name skips with a warning"
  fi
  if GAIUS_WASM_OPT="$setting" GAIUS_WASM_OPT_STRICT=1 bash "$script" "$work/in.wasm" --out "$work/strict.wasm" \
      >/dev/null 2>&1; then
    fail "$case_name fails a strict build"
  else
    ok "$case_name fails a strict build"
  fi
done

if [[ "$failures" -ne 0 ]]; then
  echo "$failures wasm-opt-release.sh checks failed" >&2
  exit 1
fi
echo "all wasm-opt-release.sh checks passed"
