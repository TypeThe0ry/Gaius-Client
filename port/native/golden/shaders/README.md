# Shader translation corpora (26.3, 26.2, 1.21.11 -> WGSL)

Every vanilla render pipeline of the three v0.4.0 profiles through
`gaius-shaderxlat` (`port/native/crates/gaius-shaderxlat`): the static
`RenderPipelines` plus every pass of `assets/minecraft/post_effect/*.json`.

| Profile | Pipelines | Distinct modules | Front-end |
|---|---|---|---|
| 26.3 | 216 (432 stages) | 175 per device profile | the vanilla renderpearl frontend hands over bound SPIR-V |
| 26.2 | 113 (87 static + 26 post) | 111 | shaderc + `frontend::link` strict, byte for byte the vanilla 26.2 Vulkan front-end |
| 1.21.11 | 113 (87 static + 26 post) | 113 | the pipelines adapted to 26.2 `RenderPipeline`s, shaderc + `frontend::link` lenient, GL depth remap |

```sh
port/native/golden/shaders/run-corpus.sh                 # 26.3: dump, native, wasm (Node)
port/native/golden/shaders/run-corpus.sh --chrome        # + headless Chrome WebGPU (GPU adapter)
port/native/golden/shaders/run-corpus.sh --swiftshader   # + headless Chrome on Dawn's SwiftShader
port/native/golden/shaders/run-corpus.sh --profile gl    # 26.3, the WebGL2 device profile (depth -1..1)
port/native/golden/shaders/run-corpus.sh --mc 26.2       # 26.2 (any of the flags above but --profile)
port/native/golden/shaders/run-corpus.sh --mc 1.21.11    # 1.21.11
```

The translation runs with the v0.4.0 convention (split binding layout, y
flipped, `frontFace: "cw"` in Chrome); `--layout flat --no-flip-y` is the first
prototype's ("S4" below) and reproduces its WGSL byte for byte. `--target-dir DIR` puts dumps,
outputs and cargo targets below `DIR` instead of `port/native/target`.

| Step | Tool | Checks |
|---|---|---|
| dump | `dump-corpus.sh --mc <mc>`, `java/.../ShaderXlatCorpusDump*.java` | 26.3: the vanilla frontend (`PipelineBuilder`, `GlslCompiler`, LWJGL shaderc natives) compiles all pipelines with the WebGPU device characteristics; writes `corpus.json` (uniforms, vertex buffers and attributes, color targets, blending, depth state, topology) and `spirv/<sha256>.spv`. 26.2 and 1.21.11: see below; they write `frontend.json` |
| link | `shaderxlat-link` (crate binary, 26.2 and 1.21.11) | every pipeline links; 26.2: the linked modules and bind group entries are byte-identical to the vanilla 26.2 result in the dump. Writes `corpus.json` in the 26.3 format plus the link's fixes |
| native | `shaderxlat-corpus` (crate binary) | every module translates and validates in naga; per-phase timings; `results.json` records the translation options |
| link (wasm) | `xlat-check.mjs --stages link` | the wasm build's `run_shader_link` gives the native link's modules, entries and fixes in Node |
| wasm | `xlat-check.mjs --stages wasm` | the wasm build gives byte-identical WGSL in Node; timings |
| chrome | `xlat-check.mjs --stages chrome` | in headless Chrome (no external network): the same translations on a synchronous main-thread instance (identical WGSL), `createShaderModule` + `getCompilationInfo` (Tint) for every module, `createRenderPipelineAsync` for every pipeline with the bind group layouts from the reflection (group 0 uniform buffers with dynamic offsets, group 1 the rest) and the pipeline's real vertex, target, blend, depth and topology state |

## 26.2 and 1.21.11

The blaze3d backends receive GLSL, so the dump stops where the browser takes
over: `ShaderXlatCorpusDump262` runs the vanilla 26.2 Vulkan front half on the
JVM up to shaderc (`ShaderSource`, `GlslPreprocessor.injectDefines` with the
pipeline defines and `GlslCompiler`'s global defines, `GlslCompiler`'s own
shaderc compiler and options) and records each stage's shaderc output, which is
what `gaius-shaderc.wasm` returns in the browser. For 26.2 it also runs the
vanilla rest of `GlslCompiler.compile` (`createFromSpirv`, `addToBindGroup`,
`rebind`) as the reference for the strict link. For 1.21.11,
`ShaderXlatCorpusDump12111` first dumps the pipelines and their preprocessed
GLSL on the 1.21.11 classpath; the replay adapts them into 26.2
`RenderPipeline`s (the vertex format realigned for WebGPU: the LineWidth
REQUIRED patch), applies the one GLSL fix shaderc needs (`sampler` as an
identifier becomes `gaius_sampler`) and compiles; everything else GL tolerates
is the lenient link. The LWJGL classes and shaderc natives come from
`port/work/26.3` (3.4.3, the revision of the browser toolchain; 3.4.1 gives the
same modules).

`dump-corpus.sh --frontend-fixtures` compiles the crate's own front-end test
shaders (`crates/gaius-shaderxlat/tests/frontend`) the same way.

## Test fixtures

`fixtures/MANIFEST` names five 26.3 modules (one per SPIR-V patch) for the
crate's `cargo test`. They are compiled from Mojang's shader sources, so they
are not committed: the 26.3 webgpu dump copies them into `fixtures/` (ignored by
git), and without them those tests skip (`GAIUS_SHADERXLAT_REQUIRE_FIXTURES=1`
makes a missing fixture fail; `run-corpus.sh` sets it after a 26.3 webgpu dump).
Everything else is regenerated from `port/work/<mc>` as well.

## Results (2026-10-04, Windows, RTX 4060, Chrome 154, machine shared with builds)

Split layout, y flipped; 1.21.11 with the GL depth remap.

| | 26.3 | 26.2 | 1.21.11 |
|---|---|---|---|
| link | (none) | 113/113, 113/113 identical to vanilla | 113/113 (strict rules: 86/113) |
| lenient fixes | | | undeclared resources in 27 pipelines (20 blocks, 11 samplers), 1 dropped fragment input, 4 attribute base types in 3 pipelines |
| naga (native) | 175/175, 216/216 | 111/111, 113/113 | 113/113, 113/113 |
| wasm vs native (link, WGSL) | 175/175 | 113/113, 111/111 | 113/113, 113/113 |
| Tint, D3D12 and SwiftShader | 175/175, 0 warnings | 111/111, 0 warnings | 113/113, 0 warnings |
| `createRenderPipelineAsync`, real layout, D3D12 and SwiftShader | 216/216 | 113/113 | 113/113 |
| pipelines, D3D12 cold (sum / mean / p95) | 5.66 s / 26.2 ms / 83 ms | 1.43 s / 12.6 ms / 37 ms | 1.74 s / 15.4 ms / 46 ms |
| pipelines, SwiftShader (sum / mean) | 1.57 s / 7.3 ms | 0.53 s / 4.7 ms | 0.55 s / 4.9 ms |

The S4 output (flat layout, no flip) is reproduced byte for byte: 175/175 WGSL
files of the 26.3 corpus. Without `float32-blendable` the 31 26.3
`oit_depth_bounds_*` pipelines fail (they blend with `MAX` into
`RGBA32_FLOAT`): 185/216.

Front-end link time per pipeline (both stages): native p50 63-95 us, max
0.3-0.95 ms; wasm in Node p50 0.36-0.84 ms including the job framing.

Translation time per module (26.3, 175 modules; warm = median of 20 runs per
module; S4 measurement, flat layout):

| | first calls, all modules | warm mean | warm p50 | warm p95 | warm max | warm, all modules |
|---|---|---|---|---|---|---|
| native x86-64 | 39 ms | 0.150 ms | 0.11 ms | 0.38 ms | 0.71 ms | 26 ms |
| wasm, Node 24 | 66-72 ms (first job 13 ms) | 0.23 ms | 0.17 ms | 0.56 ms | 1.8 ms | 40 ms |
| wasm, Chrome main thread | 62 ms (first job 13 ms) | 0.22 ms | 0.2 ms | 0.5 ms | 0.9 ms | 39 ms |

The first job of a fresh instance pays for compiling the module's functions
(lazy compilation); a boot-time warm-up job removes it from `finishCompile`.
Native phases (mean): SPIR-V patches 35 us, naga parse 53 us, validation 30 us,
WGSL writer 53 us.

| wasm build | raw | gzip -9 | brotli 11 | Node warm mean (best of 4) |
|---|---|---|---|---|
| simd, `opt-level = "s"`, with the link (shipped) | 957,180 | 292,894 | 218,494 | |
| simd, `opt-level = "s"`, `--no-default-features` (no link) | 907,006 | 278,192 | 206,506 | |
| S4: simd, `opt-level = "s"` | 902,937 | 273,606 | 205,719 | 0.226 ms |
| S4: baseline, `opt-level = "s"` | 908,250 | 274,940 | 206,421 | 0.221 ms |
| S4: simd, `opt-level = 3` | 1,025,990 | 309,277 | 231,713 | 0.187 ms |
| S4: simd, `opt-level = "z"` | 844,507 | 257,011 | 195,218 | 0.302 ms |

SIMD makes no difference (naga is scalar code). `"s"` saves 26 KB brotli over
`3` for 0.04 ms per module (7 ms over the whole corpus). The shipped modules are served
gzip-compressed (the gzip column); the brotli column is kept as measured.

Chrome 154, D3D12 adapter (RTX 4060), fresh profile (S4): `createShaderModule` +
`getCompilationInfo` 0.9 ms mean (150-160 ms for all 175 modules);
`createRenderPipelineAsync` 25-28 ms mean, p95 75-89 ms, max 150 ms,
5.3-5.9 s for all 216, 0.2 ms when repeated in the same session. Second launch
with the same profile (Dawn's disk cache): Tint 33 ms for all modules, pipelines
1.2 ms mean, 0.26 s for all 216. Pipeline creation, not translation, is the
cold-start cost.
