# Worldgen correctness and safe fallback

## Default policy

The shipped worldgen family is approved again on the two profiles with JVM hook seams after deterministic controls, strict regression references, native and baseline-wasm full-chunk comparisons, and mirror checks. Multi-noise BIOMES requests still decline to vanilla Java: family approval is not approval to replace vanilla's stateful biome search. Fixed and TheEnd sources retain their framed biome path. The independent ordinary-ore family remains default-off on every profile.

The profile with no JVM worldgen hook seam retains its previous policy and takes the vanilla path. Its zero-difference rows establish vanilla-only parity, not execution of a native or wasm worldgen kernel. No JavaScript-target bundle was rebuilt or edited. This JVM/Node evidence is not a TeaVM or browser acceptance certificate.

## Deterministic comparison

`port/scripts/worldgen-correctness.mjs` wraps the status harness without timing-agent instrumentation or warmup. Each fresh JVM uses `-Xmx3g`, one advertised processor, SerialGC and `max.bg.threads=1`. Before every compiler, hasher, patcher, jar packer or worldgen JVM, the runner waits for at least 8 GiB Windows commit headroom, polling every 60 seconds. Missing telemetry fails closed.

For FEATURES or later targets, request the entire two-chunk rectangular halo at FEATURES in fixed row-major order before any chunk reaches FULL and runs postprocessing. Then finish targets and halo at the selected status, verify persisted statuses, and snapshot only after both finish. Provenance pins `statusOrder: features-before-full`; older protocols are rejected. Two independent OFF runs now prove this protocol deterministic on the required matrix.

Each target writes a bounded gzip snapshot with chunk identity, minimum Y, height, full block-state palette strings and section/Y/Z/X cell indices. Comparison validates the target square, jar SHA, seed, stage and settled provenance. A changed block hash reports the first differing absolute coordinate and both full states. Block and biome differences are separate.

The old targets-FULL-before-halo runs were nondeterministic. In particular, the legacy-router control changed 11 chunks on the second seed, including leaf litter and leaf-distance properties. The FEATURES barrier removes that OFF/OFF drift. Historical throughput-run differences are not interchangeable with the deterministic results below.

## Root cause and repair

The deterministic native bisect ran 14 profile/seed/hook rows. Every split-router row matched, and the sampler-f32 biome-only rows matched. The sampler-f32 terrain and all-hook rows changed one chunk on seed `12345`: `(3,-10)`, with 35 final block differences. The first absolute block was `(51,90,-148)`, vanilla dirt versus kernel stone. Raw density bits, raw fill and heightmaps matched in the isolated reference; eight surface cells changed before later feature/postprocessing effects. Eight isolated cells and 35 FULL cells are different observations, not conflicting counts.

The Java glue supplied only the 20 border quart columns. Vanilla surface rules read already-stored biomes from both the center and neighboring chunks. Rust instead reconstructed the missing 16 center columns on demand, in a different lookup order. `Climate.RTree` retains a `ThreadLocal` last leaf; equal-fitness candidates with different biome values depend on that prior search. A job-local fresh search cannot substitute for the biomes already committed by the vanilla BIOMES stage.

Both Java adapters now send the complete 36-column, 6 x 6 stored grid, with x outer, z inner and every quart Y. Rust seeds the center as well as the border and returns requested center biomes without recomputing them. The existing flag and length-prefixed payload remain compatible with the old 20-column border. Missing-center contexts decline when an ambiguous climate search is encountered, before any result is encoded or applied.

All multi-noise BIOMES jobs decline before computation. Restricting this to the current ambiguous chunk would still be unsafe: an earlier accepted chunk would skip updates to vanilla's thread-local candidate and change a later fallback chunk. Retaining cross-chunk vanilla history requires a separate exact state handoff. A decline is a transient per-job fallback, not a kernel error or a successful accelerated result; the worker generator stays usable.

The sampler reference oracle also needed repair. It previously queried biomes in section/Y/Z/X order and reconstructed surface biomes through fresh resolvers. It now queries in vanilla section/X/Y/Z order, serializes the canonical storage layout, reads stored center/neighbor biomes for surface, and appends the complete context. Equal-fitness biome substitutions no longer count as parity. Router and legacy-compatible references append the same context.

## Verified matrix

Fresh deterministic controls are the `control.json` report of a `control` run. Fixed native and baseline runs are the `gate-{native,baseline}.json` reports of `gate` runs, and the default-enabled reruns repeat the gate with the shipped defaults. Each row has 400 FULL target chunks:

| Profile | Seed | OFF/OFF blocks / biomes | Native ON/OFF blocks / biomes | Baseline ON/OFF blocks / biomes |
| --- | --- | --- | --- | --- |
| 1.21.11 | 12345 | 0 / 0 | 0 / 0, vanilla-only | 0 / 0, vanilla-only |
| 1.21.11 | 987654321 | 0 / 0 | 0 / 0, vanilla-only | 0 / 0, vanilla-only |
| 26.2 | 12345 | 0 / 0 | 0 / 0 | 0 / 0 |
| 26.2 | 987654321 | 0 / 0 | 0 / 0 | 0 / 0 |
| 26.3 | 12345 | 0 / 0 | 0 / 0 | 0 / 0 |
| 26.3 | 987654321 | 0 / 0 | 0 / 0 | 0 / 0 |

Per backend, split-router rows execute 108 noise and 108 surface jobs per seed; combined sampler rows execute 108 terrain jobs per seed. All four hooked rows attempt 116 biome jobs per seed and decline all of them. That is 648 accepted terrain/surface jobs, 464 explicit biome fallbacks and zero kernel errors per backend. It does not mean every target chunk was accelerated. Coverage checks reject a missing native export or all-zero hook counters; one incorrectly built DLL was caught by this check and its receipt is not a passing gate.

The diagnostic shim admits only an intentional default-off override; actual bytecode/approval mismatches still reject. Reports always retain `promotionAllowed: false`, including after the defaults are restored. This protects the distinction between a diagnostic comparison and a release/browser certificate.

## Regression goldens and mirror locks

`port/native/golden/worldgen/regressions.json` pins six profile/seed generator IRs and 18 vanilla references for ordered chunks `3,-10`, `0,0`, `-10,-5`, including the failing chunk. Pins include source/jar identity, seed, query order, byte count and SHA-256. `port/native/worldgen-regression-check.mjs` checks the complete matrix and exact provenance; corrupt or incomplete catalogs fail closed. Fresh data is written to an output directory outside the shared `port/work` junction.

Six explicit `GAIUS_WORLDGEN_REFERENCE` runs strictly compare biomes, raw fill, surface and heightmaps; sampler references additionally compare f32 density bits. All 18 chunks match. Router density placeholders are not independent density evidence. Without the environment variable, the Cargo reference test skips its external oracle and must not be cited as fresh vanilla parity.

The existing worldgen mirror watch set already includes `LevelChunkSection.fillBiomesFromNoise`, `ChunkAccess.fillBiomesFromNoise`, the RTree methods and helpers. No new vanilla method is mirrored, so lock bytes and lock SHA approvals remain unchanged. Fresh mirror generation/gate checks and fixture tests pass. Profile table generation and local metadata checks pass for both restored profiles. The legacy profile's shared `version.json` asset-index SHA-1 differs from its manifest; that unrelated metadata check remains open, with no shared-file edit or download.

## Offline reproduction and remaining limits

Run from the repository root in PowerShell, using new output names rather than overwriting receipts (the harness refuses an existing run directory). The JVM runs of the harness apply the commit-headroom guard themselves:

```powershell
$output = '<out>/wgfix'
$env:GAIUS_JAVA_HOME = '<jdk-25-home>'
$env:CARGO_TARGET_DIR = "$output/cargo-rerun"
$matrix = "$output/full-matrix-rerun"
node port/scripts/worldgen-correctness.mjs $matrix control
cargo build --manifest-path port/native/Cargo.toml --release --offline --locked -j 2 -p gaius-worldgen-wasm --features native-host
node port/scripts/worldgen-correctness.mjs $matrix gate native "$output/cargo-rerun/release/gaius_worldgen_wasm.dll"
node port/native/worldgen-regression-check.mjs "$output/regression-matrix15"
```

`control` selects every manifest with `worldgenReference`, both acceptance seeds and a 20 x 20 square by default. `gate baseline <wasm-path>` repeats the same matrix using the baseline module. `bisect native <dll-path>` probes biomes, noise, terrain and all where metadata permits. Sampler-f32 uses a combined terrain/material seam, so noise-only is not a supported independent hook. The reference oracle separates raw fill from surface for aquifer/ore-vein/material isolation without inventing an unavailable vanilla status.

Workspace Cargo tests, fmt and clippy pass offline; Node codec, facade, real Worker pool, SIMD/baseline equality, mirror and correctness-tool checks pass. These checks do not cover unrestricted seeds, every dimension, browser scheduling, JSPI/GC, or rebuilt client artifacts. Multi-noise biome acceleration, the independent ordinary-ore seam, legacy hook integration and real browser acceptance remain separate work. No TeaVM build, Chrome run, network download or JavaScript-target output change was performed.
