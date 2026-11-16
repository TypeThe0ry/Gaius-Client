# Worldgen Offline Throughput and Next Takeover Analysis

Date: 2026-10-08. Original baseline: `628b7747`; unified measurement harness and legacy IR support: `69130cd5`; the optimized version is the commit containing this document. All raw data, logs, reference inputs and build artifacts are kept in an output directory outside the repository.

## Conclusions

- One verified Rust optimization is kept: the surface noise condition cache changes from clearing the whole table per block to generation-based invalidation, with no change to computation, access order or RNG.
- Same-process, task-by-task alternating wasm A/B shows: baseline surface time is reduced by 22.1% / 9.3% / 6.0%, and SIMD surface by 22.5% / 10.1% / 4.0%, for the three profiles in the tables below respectively.
- The `combined` paired time drops by only about 2.9–4.1%; this is not full browser chunk throughput and does not prove the 2x target. Native separate-process measurements and long re-runs show clear shared-machine load fluctuation, and the raw regression data was not deleted.
- The three JVM chunk references agree, and the 36 full result frames are byte-identical before and after optimization; baseline/SIMD noise wasm each verified 461 jobs and 12,036 sample points.
- Aquifers and ore veins are already in Rust. The next items worth measuring are Java carvers, their duplicated noise/aquifer context, features/ore placement, structure starts, and result installation and Worker scheduling.
- JavaScript-target build output is unmodified; no full TeaVM, Chrome, browser Worker acceptance or release was run.

## 1. Inputs, scope and re-runnable entry points

All profiles are enumerated from `port/versions/*.json`; `kernelSemantics` determines computation semantics, and the new `worldgenReference` only selects the JVM harness, exporter and compatibility adapter, with no Minecraft version checks added in the kernel.

The overworld IR and `.ref` exported from the official local `client-named.jar` are used: seed `12345`, chunks `(0,0)`, `(7,-3)`, `(-20,11)`, each chunk 16×384×16. Input hashes are in `summary.json`, and the exported source hashes are in `reference/<profile>/provenance.json`. Reference sources are only copied and adapted into the output directory; the shared `port/work` is read only.

Machine: Intel Core i5-14600K, 14 cores / 20 logical processors; Windows 11; Rust/Cargo 1.98, Node 24.19.0, Java 25. The machine is shared with other tasks, and no affinity, priority or power settings were changed. All measurements for this task ran serially, and none of this task's own cargo/JVM runs overlapped with throughput timing.

### Timing scope

- `biomes`: 1536 quart biome results.
- `terrain`: the actual `TERRAIN` noise-fill job, including density evaluation, aquifers, block filling, heightmaps and so on; it is not a pure noise-point micro-measurement. Double-router ore veins run here.
- `surface`: the surface/material rules on the same filled chunk; float-sampler ore veins run inside material/surface. The input chunk clone of the native core is outside the timing.
- `combined`: the existing `TERRAIN` with `FLAG_SURFACE | FLAG_BIOMES`, used to observe the local upper bound of merging the three steps into one job; the browser state machine is unchanged and no new batching ABI was added.
- native `core` calls `Generator` directly; native `framed` includes decoding, the kernel call, encoding, descriptor release, and one result `to_vec` copy. Node `framed` includes alloc/copy-in/run/status-read/release/dealloc, but no copy-out inside the timing; the full byte comparison runs before and after the timing. The two therefore cannot be compared by absolute time on the same footing across languages.
- IR compilation, cold LOAD_GENERATOR, file I/O, real Worker postMessage/transfer, Java result installation, carvers, features, structures, lighting and the full chunk status chain are not in the Rust/Node timing.

Separate-process measurement: three passes, with the before/after order reversed in the second pass and the baseline/SIMD order also reversed; each pass has 7 rounds, each round 6 repetitions × 3 chunks. The tables give the median ms/chunk over 21 rounds, with 378 timed jobs per stage, path and artifact; this is not generating 378 chunks at the same time. A separate long reverse-order re-run for the float sampler is recorded in `followup-*.tsv` and `followup-summary.json`.

To reduce shared-machine time drift, a same-process paired wasm measurement was also made: two instances each LOAD; each job calls before/after back to back, alternating which runs first. 21 rounds × 18 jobs; results for the same input are checked before and after timing. The paired reduction is computed per round as `1 - after/before` and then the median is taken; Q1/Q3 are quartiles over the discrete rounds, not confidence intervals. It may differ from the direct ratio of the two independent medians.

### Re-run commands (PowerShell)

Export the references from the worktree root; all compilation and scratch output is directed to `$output`.

```powershell
$output = '<out>/wgperf'
node port/native/worldgen-perf-reference.mjs "$output/reference"
Set-Location port/native
$env:CARGO_TARGET_DIR = "$output/after-native"
cargo build --release --offline --locked -j 2 -p gaius-worldgen-wasm --example perf_ir
& "$output/after-native/release/examples/perf_ir.exe" "$output/reference/$profile" "$output/final-after-jobs/$profile" 7 6
foreach ($variant in @('baseline', 'simd')) {
    $env:CARGO_TARGET_DIR = "$output/after-$variant"
    $env:CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS = if ($variant -eq 'simd') {'-C target-feature=+simd128'} else {'-C target-feature=-simd128'}
    cargo build --release --offline --locked -j 2 --target wasm32-unknown-unknown -p gaius-worldgen-wasm -p gaius-noise-wasm
    node worldgen-perf.mjs "$output/after-$variant/wasm32-unknown-unknown/release/gaius_worldgen_wasm.wasm" "$output/final-before-jobs/$profile" 7 6
    node worldgen-perf-paired.mjs "$output/support-before-$variant/wasm32-unknown-unknown/release/gaius_worldgen_wasm.wasm" "$output/after-$variant/wasm32-unknown-unknown/release/gaius_worldgen_wasm.wasm" "$output/final-before-jobs/$profile" 21 6
}
node worldgen-perf-summary.mjs "$output"
```

`$profile` must be the manifest `id`; the summary tool reads this measurement protocol's `final-{native,baseline,simd}-{before,after}-<id>-<pass>.tsv` and `paired-<variant>-<id>.tsv`. The complete sequential script is saved in the output directory as `measure.ps1`. No aggregate script that might write to the shared work directory or trigger a full build is run.

The pre-change version is extracted from `git archive` of `628b7747` into the output directory, then the unified harness, manifest and ScaledNoise support files are copied in, keeping the original `surface.rs`; the selection list is saved in `baseline-source-files.json`. The before/after comparison for the old version is therefore a performance comparison under equal IR support, and does not claim that the original browser had the old kernel enabled. The original arithmetic of the other two profiles was not changed by the harness preparation.

## 2. Separate-process native and wasm throughput

A positive percentage means time was reduced, and a negative value means that batch of measurements got slower. `jobs/s` is the single-thread hot-instance stage throughput, not full chunks/s. Raw data is kept by time batch, and you may not pick only the fastest run.

### Native core

| Profile | Stage | Before ms/chunk | After ms/chunk | After jobs/s | Time reduction |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 0.690 | 0.694 | 1441.1 | -0.5% |
| 1.21.11 | terrain | 9.049 | 9.260 | 108.0 | -2.3% |
| 1.21.11 | surface | 2.779 | 1.926 | 519.1 | 30.7% |
| 1.21.11 | combined | 14.430 | 11.567 | 86.5 | 19.8% |
| 26.2 | biomes | 0.673 | 0.678 | 1475.6 | -0.7% |
| 26.2 | terrain | 8.165 | 8.804 | 113.6 | -7.8% |
| 26.2 | surface | 5.463 | 5.058 | 197.7 | 7.4% |
| 26.2 | combined | 15.890 | 14.294 | 70.0 | 10.0% |
| 26.3 | biomes | 0.163 | 0.180 | 5555.2 | -10.1% |
| 26.3 | terrain | 3.145 | 3.346 | 298.9 | -6.4% |
| 26.3 | surface | 5.315 | 5.033 | 198.7 | 5.3% |
| 26.3 | combined | 8.673 | 10.241 | 97.6 | -18.1% |

### Native framed

| Profile | Stage | Before ms/chunk | After ms/chunk | After jobs/s | Time reduction |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 0.699 | 0.691 | 1447.2 | 1.1% |
| 1.21.11 | terrain | 9.233 | 9.193 | 108.8 | 0.4% |
| 1.21.11 | surface | 3.190 | 2.149 | 465.3 | 32.6% |
| 1.21.11 | combined | 12.560 | 11.661 | 85.8 | 7.2% |
| 26.2 | biomes | 0.679 | 0.677 | 1477.5 | 0.4% |
| 26.2 | terrain | 8.301 | 8.554 | 116.9 | -3.0% |
| 26.2 | surface | 5.704 | 5.041 | 198.4 | 11.6% |
| 26.2 | combined | 18.450 | 13.929 | 71.8 | 24.5% |
| 26.3 | biomes | 0.166 | 0.169 | 5923.4 | -1.6% |
| 26.3 | terrain | 3.231 | 3.170 | 315.4 | 1.9% |
| 26.3 | surface | 5.170 | 5.031 | 198.8 | 2.7% |
| 26.3 | combined | 8.554 | 9.623 | 103.9 | -12.5% |

### Node wasm baseline

| Profile | Stage | Before ms/chunk | After ms/chunk | After jobs/s | Time reduction |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 0.907 | 0.914 | 1094.4 | -0.8% |
| 1.21.11 | terrain | 11.600 | 11.766 | 85.0 | -1.4% |
| 1.21.11 | surface | 2.797 | 2.153 | 464.4 | 23.0% |
| 1.21.11 | combined | 15.640 | 16.055 | 62.3 | -2.7% |
| 26.2 | biomes | 0.902 | 1.002 | 998.2 | -11.0% |
| 26.2 | terrain | 10.705 | 11.033 | 90.6 | -3.1% |
| 26.2 | surface | 7.919 | 6.807 | 146.9 | 14.0% |
| 26.2 | combined | 21.856 | 19.114 | 52.3 | 12.5% |
| 26.3 | biomes | 0.238 | 0.257 | 3884.7 | -8.2% |
| 26.3 | terrain | 4.118 | 4.293 | 233.0 | -4.2% |
| 26.3 | surface | 7.194 | 6.987 | 143.1 | 2.9% |
| 26.3 | combined | 11.742 | 12.052 | 83.0 | -2.6% |

### Node wasm SIMD

| Profile | Stage | Before ms/chunk | After ms/chunk | After jobs/s | Time reduction |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 1.012 | 1.020 | 980.3 | -0.8% |
| 1.21.11 | terrain | 12.533 | 11.478 | 87.1 | 8.4% |
| 1.21.11 | surface | 2.783 | 2.059 | 485.7 | 26.0% |
| 1.21.11 | combined | 16.222 | 16.665 | 60.0 | -2.7% |
| 26.2 | biomes | 0.987 | 1.023 | 977.3 | -3.6% |
| 26.2 | terrain | 10.750 | 11.485 | 87.1 | -6.8% |
| 26.2 | surface | 7.561 | 7.326 | 136.5 | 3.1% |
| 26.2 | combined | 20.814 | 20.650 | 48.4 | 0.8% |
| 26.3 | biomes | 0.267 | 0.275 | 3635.3 | -3.1% |
| 26.3 | terrain | 4.147 | 4.124 | 242.5 | 0.6% |
| 26.3 | surface | 6.791 | 6.672 | 149.9 | 1.8% |
| 26.3 | combined | 10.735 | 10.912 | 91.6 | -1.7% |

The biomes and terrain evaluation code was not modified, yet separate batches still show 1–11% fluctuation; native float `combined` even shows an 18.1% slowdown. In the long reverse-order re-run, native core `combined` was still 3.7% slower, and SIMD surface once showed a 30.0% slowdown while the unmodified SIMD terrain in the same batch was also 10.4% slower. These results show that stage differencing across separate processes cannot be reliably attributed; neither are they negligible as browser acceptance evidence. Task-by-task pairing is used afterwards to judge the direction of change, and the raw tables are not overwritten with prettier numbers.

## 3. Paired wasm A/B: attributable gain of the optimization

### Surface

| Profile | wasm | Before ms/chunk | After ms/chunk | Median paired time reduction | Reduction Q1–Q3 | Rounds faster |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1.21.11 | baseline | 2.795 | 2.173 | 22.1% | 19.9%–24.2% | 21/21 |
| 26.2 | baseline | 8.020 | 7.359 | 9.3% | 7.5%–10.3% | 21/21 |
| 26.3 | baseline | 8.476 | 7.958 | 6.0% | 4.2%–8.2% | 19/21 |
| 1.21.11 | simd | 2.714 | 2.135 | 22.5% | 21.1%–22.7% | 21/21 |
| 26.2 | simd | 7.548 | 6.783 | 10.1% | 8.3%–11.7% | 21/21 |
| 26.3 | simd | 7.576 | 7.053 | 4.0% | 1.0%–7.6% | 17/21 |

### Combined

| Profile | wasm | Before ms/chunk | After ms/chunk | Median paired time reduction | Reduction Q1–Q3 | Rounds faster |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1.21.11 | baseline | 17.577 | 16.420 | 3.6% | 1.4%–5.8% | 19/21 |
| 26.2 | baseline | 19.009 | 18.343 | 4.1% | 2.8%–5.1% | 19/21 |
| 26.3 | baseline | 13.532 | 12.894 | 2.9% | 0.6%–4.7% | 17/21 |
| 1.21.11 | simd | 17.127 | 16.157 | 3.9% | 1.9%–5.2% | 20/21 |
| 26.2 | simd | 19.364 | 18.157 | 3.9% | 3.3%–6.1% | 20/21 |
| 26.3 | simd | 11.408 | 10.879 | 3.2% | 2.5%–5.4% | 18/21 |

All six surface pairs point in the positive direction, and the two router profiles were faster in 21/21 rounds; the float sampler's gain is smaller and noisy. The unmodified biomes/terrain pairs show a median reduction of about -1.1% to +1.3%, close to zero. The paired reduction for the six `combined` groups is only 2.9–4.1%, with some rounds slower: the risk of a real browser-level overall regression remains, and an end-to-end stable speedup cannot be claimed.

SIMD does not automatically bring 2x. The existing `simd.rs` mainly vectorizes f32 buffer add/sub/mul and similar operations; noise lookups, recursive IR, random branches and most double paths remain scalar. No SIMD operations were added this time, and no change was made to floating-point associativity, to replacing division with reciprocal multiplication, or to enabling FMA/fast-math. Baseline and SIMD were run in different time windows, so the two time columns cannot be used directly to assert a stable relative gain from SIMD.

## 4. Hotspots, the kept optimization and abandoned approaches

The source hotspots are `Context`, `update_xz`, `update_y` and `Condition::NoiseThreshold` in `crates/gaius-worldgen/src/surface.rs`. The original cache allocated a `Vec<Option<f64>>` sized to the whole surface condition count; each time a stone block advanced Y, it wrote `None` to every entry even though the vast majority of noise conditions were never accessed.

It is changed to `NoiseCache { values, stamps, generation }`: reads honor only the current generation, and invalidation only increments generation. An ordinary Y update goes from O(condition_count) writes to O(1), and X/Z switches still invalidate the 2D and 3D caches respectively; on generation wraparound the stamps are cleared. Bit-pattern tests for -0 and NaN payloads, a stale-slot test and a wraparound test are kept, to avoid false hits after 2^32 advances.

This makes the invalidation path allocation-free, not the whole job. Each cache goes from one Vec to two, so surface initialization has two extra allocations; the theoretical slot payload goes from 16 bytes for Option<f64> to 12 bytes for f64+u32, with Vec capacity/alignment and allocator metadata counted separately. No new long-lived shared cache, cross-chunk mutable state or cross-Worker data dependency was added.

Hotspot identification is based on the source loops and A/B runs, not a sampled flamegraph; this time no hardware performance counters, real Worker trace or per-function CPU share were obtained.

Double-router Y-independent column reuse and interpolation step reuse were also tried; results stayed consistent, but the pilot terrain core rose from about 8.2 ms to 9–9.7 ms, with no stable gain on the frame path, so all of it was reverted. The output directory only keeps `rejected-interpolation-column-prototype.patch`, which is not part of the commit. The reason is that the existing marker/arena already caches some results, and the extra generic node checks and cache indexing may cost more than the evaluations saved; committing on the assumption that "a cache should be faster" is not acceptable.

The existing wasm generator cache and arena scratch pool already reuse the IR and some buffers; no global cache was rebuilt this time. Allocation clearing for biomes/terrain, extending noise SIMD lanes, cross-chunk noise columns and generic job batching all lack sufficient evidence of gain, so the patch is not widened.

### Job framing / batching boundary

Request sizes for the first reference chunk: BIOMES 48 bytes, TERRAIN 88 bytes, SURFACE 18,760 bytes, combined 88 bytes; the results are 6,168 / 18,688 / 26,892 / 33,036 bytes respectively. These are the sizes after the existing sparse/palette encoding, not a direct transfer of the full int volume.

combined can save one local terrain→surface result encode/decode and several round trips, but it also contains the computation of both steps. Hot caches, the core/framed timing difference and shared load make a simple subtraction produce a negative "frame overhead", so no exact serialization share can be derived from it. Node does not include message transport overhead.

In the browser, double-router NOISE and SURFACE belong to different statuses, and the SURFACE input also needs the actual neighboring biome ring, structure beard and invalidation rules, so they cannot be merged unconditionally; float-sampler terrain already carries `FLAG_SURFACE`, while BIOMES remains a separate status. This round measured only the existing combined flag and did not change browser job ordering. Later batching should start with small batches of independent chunks of the same kind, preserving per-job decline/error/cancellation and a tail latency bound.

## 5. JVM reference stage timing on the same chunks

3 warm iterations, 5 measured rounds × 2 repeats × 3 chunks, for 30 samples per stage per profile. File serialization is outside the timing. Double-router calls vanilla createBiomes/fillFromNoise/buildSurface; the float-sampler density/material API builds the same chunk following the existing reference harness. The neighbor biome resolver has a warm cache, so the numbers cannot be extrapolated to a cold start or real region state.

| Profile | biomes ms | terrain ms | surface ms | Sum of three stage medians ms | B/T/S ratio within the three stages |
| --- | --- | --- | --- | --- | --- |
| 1.21.11 | 0.8595 | 8.0627 | 3.3256 | 12.2478 | 7.0% / 65.8% / 27.2% |
| 26.2 | 1.2851 | 10.1123 | 8.7455 | 20.1429 | 6.4% / 50.2% / 43.4% |
| 26.3 | 0.2751 | 4.7417 | 20.1847 | 25.2015 | 1.1% / 18.8% / 80.1% |

The float sampler also has a `density` diagnostic pass: median 2.5041 ms, range 2.3867–3.3405 ms. terrain performs the related density evaluation again itself; the diagnostic pass must not be added to terrain and passed off as total chunk time. The sum of the stage medians is also not the median of per-chunk totals.

The surface ratios here, 27.2% / 43.4% / 80.1%, are only within the three measured pieces of work; for the modern profiles this part can already be taken over by Rust, so it must not be treated as a cost "still in the Java Worker". The JVM is also not TeaVM wasm-GC/JSPI, so cross JVM/Node numbers are not a real Worker speedup ratio.

The existing offline reference has no full `ServerLevel/WorldGenRegion/ChunkStatus` dependency chain. Carvers, real neighbor features, structure starts, lighting and scheduling were not run; timing them with empty neighbors, empty structures or arbitrary fake implementations would change the task semantics. The whole-chunk time share of these stages therefore remains unknown, rather than filled in with a guessed percentage.

## 6. Hooks and work still in the Java Worker

| Profile / path | Can run in Rust | Still in Java / not taken over |
| --- | --- | --- |
| 1.21.11 | This round only proves that the offline IR/kernel matches golden | The browser hook is still off, and chunk generation follows the vanilla path |
| 26.2 router | BIOMES, NOISE (density/aquifers/ore veins/heightmaps/postprocess), standalone SURFACE | CARVERS, region/structure input collection, result installation, features, structures, lighting, status chain |
| 26.3 sampler | BIOMES, terrain+surface/material (including aquifers/ore veins) | After the kernel fills, a new Java NoiseChunk runs carvers, whose noise/aquifer/material on-demand evaluation is still Java; other region/status work as above |
| decline/fallback | None | Vanilla runs on unsupported IR, non-ProtoChunk, non-empty Blender, upgrading/retrogen, the switch being off, an invalidated token or a Worker failure |

Evidence entry points (all unmodified):

- `port/tools/src/main/java/dev/gaius/tools/patches/worldgen/WorldgenKernelPatches.java`: when the `getLightDampening()I` probe fails it prints that the kernel stays off, so legacy is not automatically enabled by this round's new IR support.
- `port/mc/shared/canonical/src/java/dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks262.java` and `WorldgenKernel262.java`: NOISE stores the decoded chunk token, SURFACE reads the region biome ring and beard; Java installs blocks/heightmaps/postprocess.
- `port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks263.java`, `ChunkGeneratorKernelHooks263.java` and `WorldgenKernel263.java`: terrain carries only the SURFACE flag, with a separate BIOMES hook; `carve` creates a new NoiseChunk and calls generateCarvers.
- `port/src/main/java/dev/gaius/browser/kernel/worldgen/BrowserWorldgenKernel.java` and `port/web/kernels/worldgen-kernel.js`: async facade, instance load/cache, stored-noise token/byte budget, encode/decode, and the exception and Java fallback boundary.
- `port/src/main/java/dev/gaius/browser/BrowserWorldgenDispatcherScheduler.java`: bookkeeping for priority 0–2 can be drained within one turn, with a budget of 2 ms / at most 1024 runnables; a suspendable generation at priority 3 ends that turn and re-entry is forbidden. The share taken by real timers, suspension and result installation needs a browser trace and cannot be given by the offline JVM.

## 7. Next kernel candidates: gains are hypotheses, risks must be encapsulated first

The priority order is "first observe the real total status time and transferable bytes, then design small jobs around immutable snapshots/deltas", not rewriting ChunkGenerator in one go.

| Candidate | Boundary that can stay deterministic / bit-comparable | Conditional gain estimate | Main risks |
| --- | --- | --- | --- |
| Carvers | Input: immutable chunk/state flags, carving mask, seed/RNG state, neighbor carver biome/config and noise/liquid snapshot; output: block/mask/postprocess deltas in vanilla order | If measured at 15–30% of the full chunk and the kernel is 2–3x faster, about 1.08–1.25x overall | Neighbor loop order, RNG consumption, repeated carve masks, aquifer/liquid, structure/blending/retrogen context; porting only the cave geometry is not enough |
| Ordinary ore placement within Features | Limited to a single placed feature, carrying the exact placement RNG/neighbor heights/replaceable block snapshot; returns sequenced deltas; Java commits them in the existing order | If this subset is 10–25% and 2–4x faster, about 1.05–1.23x overall | Cross-chunk writes and feature ordering, order-dependent conditions, Java heightmap updates; ordinary ore features are not the ore veins already taken over |
| Placement/candidate core of Structure starts | First take over seed derivation, candidate coordinates and pure geometric checks; template/registry/region queries are supplied by Java as snapshots; match candidates/bounding boxes separately | If it is 2–8% and the pure core is 3x faster, about 1.014–1.056x overall | Terrain height/sample, template pool, piece RNG, structure references and region writes; the gain from pure placement must not be taken as the gain of the whole starts path |
| Continued optimization inside Aquifer / ore vein | Already in Rust; first measure the profile, remove duplicate sampling / reuse an immutable noise grid for the current chunk only, keeping exact coordinates, arithmetic and random-branch order | The sub-path share has not been measured, so no overall gain is given for now; usually only part of an already taken-over stage can be optimized | Confusing the residual Java aquifer in carvers with the part of terrain already taken over; stale grids, sampling order and f32 rounding |
| Java result installation and small batches of same-kind jobs | First measure decoding/palette resolve/ChunkSection writes and message cost; use the existing valid results and status order to reduce repeated encoding/round trips | The frames are already compressed, and byte volume is not evidence; Node cannot determine the real gain | Long tasks on the main Worker, GC, queue priority, cancellation/failure isolation, stored-noise token lifetime |

These percentages are not measured shares, only planning scenarios. Amdahl: `S = 1 / (1 - p + p/r)`, where p is the real share of the full chunk and r is the speedup factor of that subset. Even if a kernel were infinitely fast, an overall 2x requires the accelerable cost to be at least 50%; with a subset 3x faster it requires p≥75%. The current surface optimization falls far short of this condition.

## 8. Consistency and acceptance

- For the 9 reference chunks from the local official jar: 13,824 biome ids, 1,769,472 block state words across the noise+surface stages, and 4,608 final heightmap values are consistent; the float sampler additionally has 294,912 density f32 bit patterns consistent. These biomes were matched without any equal-fitness tie tolerance. The double-router `.ref` has no raw f64 density block, so it does not claim that every full-volume f64 intermediate value was checked bit by bit.
- The 36 full ABI result frames before and after optimization are byte-identical, and Node baseline/SIMD were compared against the previous version's native frames before and after timing. The noise wasm in both variants has 461 jobs / 12,036 points exact; native noise golden covers the fixtures of all profiles in the repository.
- New generic ScaledNoise op 32: double-router only, following the original order `scale * abs(noise(x/scale,y/scale,z/scale))`. The actual scale accepts only a finite positive constant, or an IntervalSelect whose branches are all such, and does not trust misreported bounds; the float sampler explicitly declines. It is compatibility support for the legacy reference export, not a surface optimization, and does not change browser eligibility.
- The legacy adapter rechecks the rarity mapper thresholds/scales against the local official bytecode; key source replacements, reflection and compilation have failure checks, and are verified by actual chunk parity; it only adapts the overworld and cannot be extrapolated to the Nether legacy seed or other dimensions.

Verification commands (in `port/native`, all cargo runs use `--offline --locked -j 2`; the target is still the output directory):

```powershell
cargo test --offline --locked -j 2 -p gaius-worldgen -p gaius-worldgen-wasm -p gaius-noise
$env:GAIUS_WORLDGEN_REFERENCE = "$output/reference/$profile"
cargo test --offline --locked -j 2 -p gaius-worldgen --test vanilla_reference -- --nocapture
Remove-Item Env:GAIUS_WORLDGEN_REFERENCE
cargo fmt --all -- --check
cargo clippy --offline --locked -j 2 -p gaius-worldgen -p gaius-worldgen-wasm -p gaius-noise --all-targets -- -D warnings
node --check worldgen-perf-reference.mjs
node --check worldgen-perf.mjs
node --check worldgen-perf-paired.mjs
node --check worldgen-perf-summary.mjs
node wasm-fixture-check.mjs "$output/after-$variant/wasm32-unknown-unknown/release/gaius_noise_wasm.wasm"
node worldgen-perf-summary.mjs "$output"
```

Without the environment variable, `vanilla_reference` is skipped; the real consistency evidence comes from running with the variable for each of the three manifest profiles, not from that skip-pass. An unknown profile errors out before javac is started. See `tests-completion.log`, `parity-completion-<id>.log`, `fmt-completion.log`, `clippy-completion.log` and `noise-completion-<variant>.log` for the full results.

### Acceptance still open

Only one seed and three overworld chunks: multiple seeds, Nether/End, full neighbor features/structures, blending/retrogen, multi-Worker cancellation, biome ties and broader special terrain are not covered. There is no real-browser cold-load, JIT/GC, Worker queue, result installation or full status trace; the roughly 2x browser chunk target is unverified. Because this task explicitly forbids Chrome/full TeaVM, Node success or JVM golden cannot be taken as completing these acceptances.

No dependencies were downloaded, no shared work writes, no changes to other worktrees/branches, no push or release. The new measurement tooling, IR compatibility and surface optimization can be reviewed separately; JavaScript-target build output is unchanged.

## 9. Full-status measurement and the ordinary ore experiment

The follow-up measurements of 2026-10-08 no longer treat the Section 7 hypotheses as shares. For each profile, two seeds with 400 FULL chunks requested per seed were run with the existing Java hooks disabled and enabled respectively, plus supplementary native, Node wasm baseline/SIMD and ordinary ore combinations. The method, all statuses, the ordered delta ABI, the independent vanilla golden, the mirror gate and the re-run commands are in [worldgen-ore-throughput.md](worldgen-ore-throughput.md). The raw reports are kept in an output directory outside the repository.

The JVM exclusive CPU share of ordinary ore is only 2.65% / 3.10% / 2.94%; carvers only 1.78% / 2.19% / 1.62%. They are not the 10–25% assumed earlier. The largest remaining identified feature is SculkPatch, and a further 34.63–47.95% of JVM process CPU is still unattributed, including background JIT/GC and uninstrumented paths. Ordinary ore was chosen to validate a geometry/snapshot/delta boundary that can be independently reviewed, not to claim it is the largest full Java hotspot.

The new ore ABI has passed the independent JVM ordered golden for all three profiles and both wasm variants, but stays off by default and is not wired into the production browser hook. At that time the full-chunk block hashes of the existing worldgen hooks were inconsistent, with small boundary drifts even for OFF/OFF; these were correctness gates open at the time and cannot be wiped away by local ore parity. The issue was fixed in a deterministic re-test the same day (a FEATURES-before-FULL barrier and full stored biome context), and worldgen was re-approved on the two profiles that have a JVM hook seam; see [worldgen-correctness.md](worldgen-correctness.md). The 2x browser full-chunk target has still not been reached or verified.
