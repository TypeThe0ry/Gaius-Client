# Exact column light goldens

`LightGolden.java` bootstraps the selected unmodified named vanilla jar (any
profile; it only uses API present on all of them), builds
seeded synthetic block columns, and calls the actual `LevelLightEngine`.
There is no server, client, TeaVM, Chrome, resource download or Maven build.
All compiled classes, fixtures and logs go under `--out`, never `port/work`.
The light table exporter is the production `BrowserLightStateTable` with the
profile's `LightVersionOps`, staged from the source sets exactly as the profile
compiles them (`port/mc/shared`, relocations applied); fixtures bind the staged
texts by SHA-256.

Each seed has a 5x5 area and nine interior 24-section columns: two seeds give
432 distinct initial sections. Every section contains seeded stone, glass,
water, leaves, slabs, torches, glowstone and sea lanterns, perforated ceilings,
and emitters at section/chunk edges. The minimum/maximum build-height cells
and both extra light-storage sections are compared. Four subsequent vanilla
updates add/remove edge emitters and close/open vertical skylight paths.
The fixtures contain the exact pre/post-change block states and nibble layers,
not outputs of a reimplementation of the lighting algorithm.

## Offline reproduction

Use an existing JDK and Rust cache. Set `MC`, `OUT` and `DIST` to the selected
profile, a scratch output directory and an existing staged/shipped dist.
Snapshot the dist's light modules, codec and `kernels.json` if another build
may overwrite them. The comparator verifies manifest sizes and SHA-256 hashes.

```sh
node port/native/golden/light/check.mjs --profile "$MC" --out "$OUT" --dist "$DIST"
```

For separate dump/build/comparison steps (or diagnosis of one snapshot):

```sh
node port/native/golden/light/dump.mjs --profile "$MC" --out "$OUT/golden" --java-heap 2g
CARGO_TARGET_DIR="$OUT/target" cargo build --manifest-path port/native/Cargo.toml \
  --offline --locked -j 1 -p gaius-light-wasm --example jvm_light_golden
node port/scripts/light-golden-compare.test.mjs
node port/scripts/light-jvm-wasm-golden.mjs --profile "$MC" --dist "$DIST" \
  --fixtures "$OUT/golden/fixtures/12345/initial" --out "$OUT/compare/12345/initial" \
  --native-runner "$OUT/target/debug/examples/jvm_light_golden"
```

On Windows the runner has an `.exe` suffix. Repeat the comparison for every
seed/stage in `corpus.json`, including `emitter-add`, `emitter-remove`,
`roof-close` and `roof-open`. `--seeds`, `--min-section` and `--section-count`
select a different corpus; at least two seeds and 300 initial sections are
required. JVM heap defaults to 2 GiB; `--java-heap 3g` on `check.mjs` or
`dump.mjs` selects the maximum supported 3 GiB cap. On Windows each compiler/JVM
invocation waits for 18 GiB commit headroom by default. An explicit
`GAIUS_JVM_MIN_HEADROOM_GIB` selects an integer threshold of at least 8 GiB.
The gate polls every 60 seconds for at most an hour;
failure is explicitly `NOT_RUN`, not parity success. `check.mjs` applies the
same gate before its single-job offline Cargo build. Apply it externally when
running the separate Cargo or mirror-hasher commands on a shared host.

## Boundary contract and approval

The column kernel consumes a one-block ring. INITIAL jobs start with no
center layers. CHECKS jobs use the **previous** vanilla center layers, new
block states, and the changed positions; neighbour-ring cells whose block
state or settled level changed also schedule checks on the matching center
border. Both modes use the settled vanilla neighbour layers as a boundary
condition with PULL_RING, matching the existing column golden contract.

INITIAL jobs keep the production EMIT_OUTGOING flag (first light never
decreases, so the ring is never written). CHECKS jobs run without it: with
EMIT_OUTGOING the ring is live neighbour storage, a decrease that reaches a
ring cell zeroes the kernel's copy and reports the cell, and the light the
relit neighbour sends back into the column only arrives when the Java side
replays that record through the vanilla engine. Against a settled ring nothing
replays it, so those comparisons would judge an intentionally partial column
(this was the whole 1.21.11 emitter-add/remove failure: 1,860 block and 6 sky
nibbles, all darker than vanilla within four blocks of a chunk border).
`settled_ring_checks_*` in `gaius-light` pin both behaviours. Production light
jobs are INITIAL only; incremental lighting of loaded chunks stays vanilla.

This validates column propagation across chunk boundaries; it does not prove
asynchronous Java/Worker outgoing-event delivery, or replace a real browser
gameplay acceptance.

The dump binds the actual vanilla jar, harness/exporter sources, adapter and
all binary inputs by SHA-256. The comparator additionally binds framed jobs,
native executable, dist codec/manifest/modules and framed results. It compares
every effective block/sky nibble, including null-layer sky extrusion, without
tolerance. A missing table, malformed input, mismatched source/hash or any
different nibble fails the check. Tiny comparison tests cover null layers,
both nibbles, padding height limits and deliberate mismatches.

Only an exact native/SIMD/baseline result permits reviewing the selected
profile's light mirror/default through `port/tools/kernelmirror/README.md`.
Keep the default off for unverified or mismatching cases. A stale common lock
semantics declaration is a separate fail-closed gate; do not approve unrelated
kernel families while reviewing light parity.

## First light through the patched engine (route)

`route/route.mjs` runs the same seeded world through a real
`ThreadedLevelLightEngine` (`initializeLight`, then `lightChunk`, as the chunk
steps call them) instead of the bare `LevelLightEngine` calls of the goldens:

- `vanilla`: the vanilla jar alone, the reference;
- every other mode puts the `ThreadedLevelLightEngine` written by the
  profile's patch chain (`--patched`, e.g. `<profile>-wasmgc/out` of
  `patchkit.mjs check --keep`) ahead of the jar, and compiles the light kernel
  bridge (`BrowserLightKernel`, `BrowserLightStateTable`, `GaiusLightColumnAccess`
  and the profile's `LightVersionOps`) from the source sets; `LightKernelHost`
  is replaced by a JVM stand-in (`route/host`) that keeps the production
  protocol (lean job, table resent on "table missing") and runs the dist's
  shipped `light.<variant>.wasm` in a node process (`route/kernel-bridge.mjs`);
- `kernel-off` (host unavailable: the patched engine must decline every chunk
  to vanilla), `simd`, `baseline`, `simd-one-slot` (one job in flight, the
  rest wait), `simd-transient` (every third job refused as back pressure, those
  columns lit by vanilla in their task) and `simd-errors` (every job fails: the
  kernel disables itself after three and vanilla lights the rest).

Every mode must equal `vanilla` in every nibble and section flag of all 25
columns, the kernel modes must have applied every column (or exactly the
expected fallbacks), and with `--fixtures` the vanilla route must equal the
`initial` goldens of `dump.mjs`. TeaVM runs one Java thread at a time and the
bridge's job bookkeeping relies on it, so the harness serializes the engine
executor and the kernel's result threads with one permit (`LightKernelHost.TURN`
of the stand-in).

```sh
node port/native/golden/light/route/route.mjs --profile "$MC" --out "$OUT/route"   --dist "$DIST" --patched "$SCRATCH/$MC-wasmgc/out" --fixtures "$OUT/golden/fixtures" --java-heap 3g
```
