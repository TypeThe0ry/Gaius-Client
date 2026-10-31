#!/usr/bin/env bash
# Golden checks of the kernel modules a build shipped (v0.4.0 M2: golden data on the wasm
# artifacts, not only on a native or freshly built module): the kernels/*.wasm files of a dist, in
# both the simd128 and the baseline build, run in Node the way kernel-worker.js runs them.
#
#   port/scripts/dist-kernel-golden.sh <dist> [<cargo target dir>]
#
#   noise    the JVM golden fixtures of every profile (port/native/fixtures, dumped from the
#            client jars by port/native/golden/run-golden.sh) through run_noise_points
#            (port/native/wasm-fixture-check.mjs);
#   mesher   the probe jobs of the mesher_wasm_probe example (load_model_table and mesh_section
#            jobs over synthetic sections) against the native build of the same crates,
#            byte for byte (port/native/wasm-check.mjs);
#   light    light-kernel-smoke.mjs: hand-computed levels, simd == baseline, the table-missing
#            path.
# The vanilla Java side of the mesher and the light engine is checked elsewhere: mesh-kernel-ab.mjs
# renders the same world with the kernel and with the vanilla section compiler of the client, and
# worldgen-seed-parity.mjs compares the light a build saved with the vanilla light engine.
# Exit status: 0 every check passed, 1 a check failed, 2 usage.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
dist="${1:-}"
[[ -n "$dist" && -d "$dist/kernels" ]] || { echo "usage: $0 <dist with kernels/> [<cargo target dir>]" >&2; exit 2; }
target="${2:-${CARGO_TARGET_DIR:-$root/port/native/target}}"
kernels="$dist/kernels"
failed=0
check() {
  local label="$1"
  shift
  if "$@"; then
    echo "DIST_KERNEL_GOLDEN_PASS $label"
  else
    echo "DIST_KERNEL_GOLDEN_FAIL $label"
    failed=1
  fi
}
for variant in simd baseline; do
  check "noise.$variant fixtures" node "$root/port/native/wasm-fixture-check.mjs" "$kernels/noise.$variant.wasm" \
    "$root/port/native/fixtures"
done
mkdir -p "$target/dist-kernel-golden"
probe="$(mktemp -d "$target/dist-kernel-golden/mesher-probe.XXXXXX")"
if (cd "$root/port/native" && CARGO_TARGET_DIR="$target" cargo run --quiet --offline --locked -j 4 -p gaius-mesher-wasm \
    --example mesher_wasm_probe -- "$probe"); then
  for variant in simd baseline; do
    check "mesher.$variant probes" node "$root/port/native/wasm-check.mjs" "$kernels/mesher.$variant.wasm" "$probe" \
      run_mesh_section run_load_model_table
  done
else
  echo "DIST_KERNEL_GOLDEN_FAIL mesher probes (the native probe build failed)"
  failed=1
fi
check "light simd and baseline" node "$root/port/scripts/light-kernel-smoke.mjs" "$kernels/light.simd.wasm" \
  "$kernels/light.baseline.wasm"
echo "DIST_KERNEL_GOLDEN $([[ $failed == 0 ]] && echo PASS || echo FAIL) $dist"
exit $failed
