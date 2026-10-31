# WebGPU core

The WebGPU backend of Gaius without any Minecraft symbol. Package
`dev.gaius.gpu.webgpu`, glue `port/web/runtime/webgpu/gpu-glue.js` (`globalThis.GaiusWebGpu`).

- The API uses JDK types, `Core*` enums and plain numbers only. `build-overlays.sh` compiles this
  root for every profile against the TeaVM JSO API alone, so a vanilla (or LWJGL) reference fails
  the build. `src/target-<js|wasmgc>/java` may replace or add files, like every other source root.
- A render seam (`port/mc/seams/<generation>`, chosen by the profile's `renderSeam`) adapts one
  vanilla render SPI onto it: `CoreDevice` (resources, `compile(CorePipelineDesc)`, surface,
  telemetry), `CoreEncoder` (passes, clears, ordered buffer writes, copies), `CorePass`
  (pipeline, uniforms by index or `CorePipeline.slotOf(name)`, draws), `TransientArena`.
- Shaders: `CoreShaderSource` is WGSL, SPIR-V (renderpearl; translated by the
  `gaius-shaderxlat` kernel the page installs as `GaiusWebGpu.translator`, synchronously in
  `CorePipeline.Pending.finish()`, prefetchable through `WgslSources.setPrefetcher`) or GLSL
  (blaze3d, 26.2 and 1.21.11). The bind group layout follows the translator's reflection; WGSL
  given directly uses the "Split" layout (`BindingLayout`).
- The blaze3d GLSL front-end (`GlslFrontEnd`): each stage through shaderc with
  the vanilla 26.2 `GlslCompiler` options (the page's shader toolchain,
  `window.__gaiusShaderToolchain`), then both through the translator's `link`
  (`frontend::link`, kind 0x0502), whose bind group entries become the pipeline's uniforms; then
  the same translation as SPIR-V. The browser's `gaius-shaderc.wasm` has no
  `auto_map_locations`: `GlslLocations` writes the locations it would assign into the source and
  restores the module's debug instructions, so the SPIR-V is byte for byte the auto-mapped one
  (`port/tools/glsl-locations`). GLSL written for GL's -1..1 depth range
  (`CoreShaderSource.Glsl.glDepthRange`, 1.21.11) is translated with the kernel's
  `glDepthRemap` (z becomes (z + w) / 2 at the vertex output; the glue's translation flag bit 2).
- `FanIndices`: TRIANGLE_FAN as triangle lists for the blaze3d seams, one
  device-wide index buffer of `0, k, k + 1` drawn with the fan's first vertex as the base vertex
  (GL's triangles and winding); a seam compiles the fan pipeline as a triangle list and uses it for
  non-indexed fans and fans through vanilla's sequential index buffer.
- Uniform buffers are allocated in whole 16-byte rows and bound at least up to the next row
  (`CorePass.bindingSize`): vanilla sizes some (post-pass `BlurConfig`: 12 bytes) by their last
  member, which WebGPU refuses for a 16-byte std140 block.
- Uploads: `StagingRing` (512 KiB blocks, power-of-two dedicated blocks up to 4 MiB,
  32 MiB cap, never waits). `CoreEncoder.writeBuffer` is command-ordered: ring copy plus an encoded
  `copyBufferToBuffer`, transient memory when the ring is full, and a pass split
  (end, copy, resume with `loadOp: load`) when a render pass is open. `CoreDevice.uploadStats()`
  reports the counters. Buffer allocations round up to whole words; a non-word-sized write may
  use that padding only when it ends at the logical buffer's end. Interior writes remain aligned
  so rounding cannot overwrite adjacent data.
- Enum mapping: `EnumBridge` maps a vanilla enum to a core enum by `name()` (core names follow
  renderpearl); each seam has a completeness test (`port/mc/seams/test-seam.sh`).

## Completion and readback

`CoreEncoder.createFence()` records a fence for the next submit. `CoreFence.isComplete()` polls
without blocking the browser thread; it becomes true only when `GPUQueue.onSubmittedWorkDone()`
resolves. The seam ignores the native blocking timeout and returns false while work is pending.
A rejected completion or a lost device fails the fence instead of reporting success. Closing a
fence releases its handle and is idempotent.

Texture readbacks map a staging buffer after submission and pack padded rows into the destination's
CPU copy. GPU-only copies wait for queue completion. Their callbacks share one recording-order
queue, so a later GPU copy cannot overtake an earlier mapped readback. Failed or short mappings
raise an error rather than handing a screenshot callback stale bytes.

Run `node port/web/runtime/webgpu/gpu-completion-smoke.mjs` for deterministic completion and row
packing checks without a browser. These mocked checks do not replace GPU or full-client acceptance.
