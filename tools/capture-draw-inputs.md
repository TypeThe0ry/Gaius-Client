# Ordered backend draw-input diagnostics

Status on this branch: `port/scripts/minecraft-263-singleplayer-cdp.mjs` wires only `CAPTURE_MODE`, `CAPTURE_STAGE_JS` and `CAPTURE_CAMERAS` (see `tools/acceptance-harness.md`). The remaining `CAPTURE_*` switches below (draw inputs, quiescence gating, reload, early freeze, replay, ...) are consumed by the modules in `tools/` (`stage-*.mjs`, `draw-input-capture.mjs`, `capture-quiescence.mjs`, `compare-draw-inputs.mjs`, `verify-capture-determinism.mjs`) but the driver that sets them is not present in the entry script, so the capture flow cannot be run end to end until that wiring is restored. The comparison and verification commands work on existing capture directories.

These tools require fresh vanilla quiescence evidence and repeated identical draw inputs before interpreting a frozen capture's pixel heatmap. A stable screenshot does not establish identical world or render inputs. The WebGL2/vanilla path is the reference; diagnostic staging never changes an existing distribution.

## Vanilla quiescence producer

`stage-capture-quiescence.mjs` stages the served JavaScript client (capture mode with `CAPTURE_STAGE_JS=1` always applies it) and publishes `globalThis.__gaiusCaptureQuiescence` (`schema: 2`, `source: "vanilla"`). Field and method names are resolved from the compiled vanilla code; an unknown shape fails the staging instead of guessing. `snapshot(request)` runs on the page's main thread, where the client's vanilla objects live, and reports for the requested view:

- `loadedChunks`: chunks that are FULL in the client chunk cache and have light enabled (the vanilla section compile gate), scanned over the render distance plus the two-chunk tracking border. `expectedMissing` lists chunks vanilla's `ChunkTrackingView.isWithinDistance` says the server tracks for this view but the client does not have yet; it must be empty.
- `lightPending`: queued client light updates plus `LevelLightEngine.hasLightWork()`.
- `dirtySections`, `uncompiledVisible`, `compileQueued`: dirty flags, missing meshes and queued or in-flight compiles of the sections in the render list. Vanilla never compiles sections outside it, so every unseen section stays dirty; those do not hold back a view.
- `sectionWork`, `uploadQueued`, `meshPending`: the browser compile/upload scheduler and outstanding mesh-kernel requests or results.

The integrated server runs in a Worker whose objects the page cannot read. Its remaining world work is observed where it reaches the client: `network.packets` counts chunk, light, block, section-block, forget, biome and block-entity packets as the client handles them. A view is stable only while none of them arrive.

Two render clocks would otherwise differ between runs at the same frozen game state, so a capture pins them in the served client: the lightmap's block-light flicker (an unseeded random walk, held at its initial 0) and the `Globals` uniform's game time (held at the frozen day time 6000 with no partial tick). Both are states vanilla reaches; the snapshot reports them under `pins`.

`worldDigest(chunks)` returns, per loaded chunk, an FNV-1a digest of the last chunk packet's section data as the server sent it and of the client's final sky and block light layers (empty layers are read without being materialized). It is written once per view as `world-digest-<view>.json` and locates world-state differences between runs.

## Capture

Use the existing singleplayer capture entry point with a local distribution whose build identity selects the catalog profile. Keep the same original build, camera file, capture options and response-stage policies on all runs. Set `GAIUS_CHROME_BIN` when Chrome is not at the default path. The per-draw instrumentation stages `classes.js` from the internal JavaScript instrumentation build; the shipped WasmGC client needs the same hooks compiled in, which is a separate build gate.

Required environment switches:

- `CAPTURE_MODE=1`, `CAPTURE_STAGE_JS=1`: the quiescence-gated frozen capture with output-local response staging.
- `CAPTURE_DRAW_INPUTS=1`: instrument logical frontend draws, including every section in a batched terrain draw.
- `CAPTURE_BUFFER_ONLY=1`: track buffers without assembling upload-side texture shadows.
- `CAPTURE_READ_TEXTURES=1`: read the actual sampled RGBA8 texture mips after the captured frame. This adds diagnostic WebGPU copy-source usage; it does not generate replacement mips.
- `CAPTURE_NATIVE_PRESENT=1`: skip the SDL coroutine yield only for an unthreaded native callback. Resumable client yields are unchanged.
- `CAPTURE_LINEAR_MIPMAPS=1`: apply the narrow normal-sampler mip-filter fix to an older JS distribution in the served response. Non-filtering depth samplers are unchanged.
- `CAPTURE_OFFLINE=1`: refuse non-loopback page fetch/XHR and skip the remote blocklist. This page hook is not a worker-context network sandbox.

Optional switches:

- The deterministic four-view capture now performs vanilla F3+A (`LevelRenderer.allChanged()`) at every view by default, after teleporting to that camera and before waiting for settled draw inputs. It rebuilds visible sections with the camera held still, so translucent quads are sorted for the view being compared; without it, far translucent sections keep the order from whichever earlier camera last compiled them. The capture report records each reload. The lower-level quiescence tool still accepts `CAPTURE_RELOAD_CHUNKS=view` to reload at every view, `1` to reload before the first view, or `probe` to record inputs before and after reload (`draw-inputs-<view>-before-reload.json`).
- `CAPTURE_EARLY_FREEZE=1`: for a new world, freeze the game right after entering it, set `random_tick_speed 0`, `mob_griefing false`, `spawn_mobs false` and `spread_vines false`, then resume. The integrated server ignores rules chosen in the creation screen (`CAPTURE_CREATION_RULES=1` now verifies this and fails), and its level random is unseeded, so random ticks and mob actions otherwise change different blocks in every run. The capture re-reads the rules before it starts.
- `CAPTURE_CHROME_LOG=1`: keep Chrome's own process log as `chrome.log` in the output directory.
- `CAPTURE_VERTEX_COLUMNS=1`: add per 4-byte vertex column hashes to vertex windows; a section whose topology matches then names the vertex attributes that differ. `CAPTURE_VERTEX_DUMP_MB=<n>` also embeds raw vertex windows, up to n MiB per frame.
- `CAPTURE_SCRATCH=<dir>`: keep the Chrome profile and the staged `classes.js` on another volume; both are deleted after the run, the staged file is identified by its SHA-256 in `capture-staging.json`.

`CAPTURE_GPU_GLUE` optionally serves an exact output-local glue copy. Both original and served classes/glue SHA-256 values are retained in `capture-staging.json` and `capture.json`. Ordinary non-capture behavior and production output are unchanged when diagnostics are disabled. Capture mode fails closed if the quiescence producer or the draw hooks are unavailable.

The acceptance entry point requires four independently recorded browser runs, in this order:

```powershell
node tools/verify-capture-determinism.mjs <webgl-a> <webgl-b> <webgpu-a> <webgpu-b> <report-dir>
```

It re-validates every quiescence receipt from its recorded observations, the exact staged client, four distinct run IDs/directories, capture settings and cameras. It first compares both within-backend pairs; `NONDETERMINISTIC` stops there. Passing pairs proceed to cross-backend inputs, where only the Projection uniform is normalized (`backend-clip-space.mjs`: WebGPU's [0, 1] clip depth is mapped back to the GL convention and both are rounded to six significant digits); every other input stays byte-exact. Unequal inputs or loaded chunk sets give `INPUT_MISMATCH`, and the pixel report is then kept only as diagnostics. Missing prerequisites or evidence produce `BLOCKED`. Fixture tests of this gate do not establish real-world determinism.

Low-level diagnostic commands remain available:

```powershell
node tools/compare-draw-inputs.mjs <reference-capture> <candidate-capture> <input-report-dir>
node tools/capture-pixel-diff.mjs <reference-capture> <candidate-capture> <pixel-report-dir>
```

The draw command exits nonzero for unequal inputs, missing data or instrumentation failures. The standalone pixel command enforces the existing four-view 0.5% threshold and rejects temporal instability, but does not prove vanilla quiescence or repeated-run input equality. Use the four-run entry point for acceptance. Do not combine passing views from different capture pairs or treat an unequal-input comparison as an isolated renderer regression.

## Recorded evidence

For each view, normal ticking remains enabled until the producer reports an idle view for at least `drainMs` (3 s) with unchanged packet counters and loaded chunks. The harness then freezes ticks, hides the HUD and requires four distinct complete surface-present frames with identical ordered draw fingerprints, each bracketed by fresh idle observations with advancing sample counters and unchanged packets. Busy queues, arriving packets or changing hashes restart the streak. There are no elapsed-time or visible-audit readiness shortcuts.

Each view retains the last stable `draw-inputs-<view>.json` frame, `world-digest-<view>.json` and bounded `quiescence-<view>-<phase>.json` receipts. Every screenshot is bracketed by an idle observation, a frame with the stable fingerprint and another idle observation; these checks are not an atomic screenshot/present binding. Frame evidence includes explicit consecutive `beginPresent`/`endPresent` counters, successful texture readbacks and a complete hashed lightmap. Native upload/copy/mapped-write shadows supply referenced vertex windows and indices. Indices are normalized relative to their referenced window, not to backend allocation addresses. Raw draw arguments are retained separately. Terrain batches expand in their actual order and do not duplicate their nested native draws.

Every draw retains pipeline, counts, vertex formats, mesh hashes, named uniform hashes and sampled texture/sampler descriptors. Native RGBA8 mip readback strips row padding and restores GL pack/framebuffer state. Unsupported formats, missing shadows, incomplete uploads, failed readbacks and more than 4096 draws fail closed. Uploaded mip bytes and sampled final-atlas bytes are distinct evidence.

The comparison records ordered per-draw SHA-256 values and a secondary FIFO section match keyed by pipeline and ChunkSection coordinates. The section summary classifies mesh/count/order differences, same-topology meshes and, with vertex columns, the differing vertex words, consumed-uniform differences by name and padding-only changes. Texture labels and arena addresses are not geometry identities.

Two normalizations are part of the strict comparison, both fixed by the vanilla shader and pipeline contract rather than by observed differences:

- Uniform blocks are compared over their declared std140 size (`UNIFORM_BLOCK_BYTES`: ChunkSection 16, TerrainUniform 72, Globals 48, Fog 40, Projection 64, DynamicTransforms 156 bytes). A bound slice is padded to the device's offset alignment and the remainder holds stale ring-buffer bytes no shader reads. A slice shorter than its block is compared in full.
- Within a run of consecutive solid or cutout terrain draws, draws are ordered by section position (`ORDER_INDEPENDENT_PIPELINES`). These pipelines do not blend and sections are disjoint, so the order is not an image input; vanilla appends sections to its visible list as their compiles finish, which makes it timing dependent. Translucent and all other draws keep their recorded order.

The lightmap is re-rendered on every client tick, frozen or not, so whether a captured frame contains that pass depends on frame timing; it is removed from the ordered draws and its result is compared as the sampled Lightmap texture.

## Recommended four-run protocol

```text
CAPTURE_MODE=1 CAPTURE_STAGE_JS=1 CAPTURE_OFFLINE=1 CAPTURE_NATIVE_PRESENT=1 CAPTURE_DRAW_INPUTS=1
CAPTURE_BUFFER_ONLY=1 CAPTURE_READ_TEXTURES=1 CAPTURE_EARLY_FREEZE=1 CAPTURE_RELOAD_CHUNKS=view
QUERY=runtime=wasm&serverRuntime=wasm&gpu=<webgl2|webgpu>&gaiusPost=0&gaiusRenderScale=1&gaiusKernels=0
```

`gaiusKernels=0` keeps every world job on the vanilla Java path for both backends. With the kernels on, worldgen output differs between runs of one seed, and on the JavaScript client the mesher kernel's vertex positions differ from the compiled vanilla compiler by one float ulp (the kernel rounds every float operation like the JVM; the JavaScript output keeps intermediate float sums in double precision), while which of the two compiles a section depends on timing. Neither is a renderer input difference; both make repeated captures unequal. The per-view F3+A rebuild also normalizes camera-dependent translucent sorting before each backend sample.

## Optional saved-world replay

`CAPTURE_KEEP_WORLD=1` saves and quits after a successful capture and retains that run's private Chrome profile under its output directory, or under `CAPTURE_SCRATCH` (recorded as `scratchProfile`, which a replay resolves against its own `CAPTURE_SCRATCH`). `CAPTURE_WORLD_SOURCE=<capture-directory>` copies it into a new empty output-local profile and serves the original loopback origin. Source/destination containment, successful capture, seed/profile, report SHA and a bounded content hash of every ordinary profile file are checked; links and a modified copy are rejected.

A replay reproduces the saved block states, biomes and light exactly (identical world digests in every replay). It is still experimental: on the development machine about half of the WebGL2 replays and every WebGPU replay stopped responding right after the integrated server started (the page then ignores even `Debugger.enable`; `world-entry-hang` records that), so the four-run protocol uses new worlds with `CAPTURE_EARLY_FREEZE=1` instead. `CAPTURE_CREATION_RULES=1` stages the creation-screen rules, and the capture verifies them on the server: the integrated server currently ignores them.

## Sampler regressions

`node tools/webgpu-sampler-contract.mjs <output>` compiles the actual CoreSampler with a recording glue stub, using Java/javac heap limits of 2 GiB. It checks 48 normal/depth/address/filter/LOD combinations without TeaVM or dependency downloads.

`node tools/webgpu-sampler-browser-smoke.mjs <output>` uses real WebGL2/WebGPU on a two-mip CPU-uploaded texture, six fractional LOD values and alpha discard. Set `GAIUS_CHROME_BIN` when Chrome is not at the default path. The old nearest-mip behavior must differ, and linear-mip behavior must match the reference byte-for-byte with no WebGPU validation errors. This controlled fixture proves the sampler contract, not full vanilla-world foliage parity.
