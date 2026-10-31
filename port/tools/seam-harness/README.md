# Render seam harnesses

Small TeaVM programs for the blaze3d seams: the harness main, the seam, the WebGPU core and only
the vanilla classes they reach, compiled like the client (the profile's `generate-pom.sh` POM
with the harness as main class, -Xmx3g) in well under a minute, so seam and core work does not
wait for a full client build.

| Seam | Profile | Harness | What it draws |
|---|---|---|---|
| `blaze3d-backend-1` | 26.2 | `dev.gaius.harness.b1.B1Harness` | a title-screen-like frame through vanilla's frontend, pipelines and shaders: the panorama (`RenderPipelines.PANORAMA`, cube map, 85 degree perspective), the menu blur (`post_effect/blur`: `core/screenquad` + `post/box_blur`, main to swap and back, vanilla's 12-byte `BlurConfig`), then GUI quads, two triangle fans of `SUNRISE_SUNSET` (one without indices like the sky, one through the shared sequential index buffer at an index and vertex offset), the logo and button sprites and text (`GUI`, `GUI_TEXTURED`, `GUI_TEXT`, a rotated splash) |
| `blaze3d-direct-0` | 1.21.11 | `dev.gaius.harness.d0.D0Harness` | the panorama (depth-tested), GUI quads, two `SUNRISE_SUNSET` triangle fans (without indices, and through the sequential index buffer at an index and vertex offset), two overlapping depth-tested quads in the near half of GL's depth range (only drawn with the GL depth remap), a sprite and a texture with an empty final GL mip sampled through a trimmed view, through the single-layer `GpuDevice` API; the device comes from `RenderSystem.initRenderer` as the REQUIRED registration patch leaves it in the client jar (`D0Devices.create`: the seam's device, or vanilla's `GlDevice`), so the harness needs WebAssembly GC overlays |

`?mode=webgpu` runs the frame on the seam (`B1Backend.createBackendDevice`, the page's shader
toolchain and `gaius-shaderxlat` kernel installed as the game page will), `?mode=webgl2` on
vanilla's `GlBackend` over Gaius's WebGL 2 LWJGL shim: the same sequence, compared pixel by pixel.
Textures are generated; the vanilla shader sources are extracted from `port/work/<profile>` into
the output directory at build time (`extract/`), never committed.

```sh
# overlays of the profile (build-overlays.sh; js or wasmgc), then:
GAIUS_SHADER_TOOLCHAIN_PREBUILT=<dir with gaius-shaderc.{js,wasm} and gaius-spvc.{js,wasm}> \
  bash port/tools/seam-harness/build.sh blaze3d-backend-1 --overlays <overlay dir> --out <dir>
node <dir>/web/run.mjs --root=<dir>/web --out=<dir>/result     # headless Chrome (GAIUS_CHROME_BIN)
# WasmGC overlays: the page runs the client's JSPI loader
node <dir>/web/run.mjs --root=<dir>/web --out=<dir>/result   "mode=webgpu&js=gaius-wasm-boot.js" "mode=webgl2&preserveDrawingBuffer=1&js=gaius-wasm-boot.js"
```

The core and seam sources of the worktree replace the copies in the client overlay jar, so a core
or seam edit needs only the harness build. `run.mjs` serves the page, drives headless Chrome
without external network, records `__harness.info` (device, draws, front-end timings, skipped
draws), the SHA-256 of every shaderc module the browser front-end produced (to compare with the
native corpus, `port/native/golden/shaders`), screenshots of both modes, `diff.png` and the
comparison (differing pixels, largest channel delta, histogram, distinct colours of each frame
against a trivial match).

Results on 2026-10-04 and 2026-10-07 (Chrome 154, RTX 4060, machine loaded by other builds;
WebGPU frame against the WebGL 2 frame of the same harness):

| Harness | Target | Build (javac + TeaVM) | Draws | Differing pixels of 921,600 | Largest delta | Browser shaderc modules = native auto-mapped corpus |
|---|---|---:|---:|---:|---:|---|
| blaze3d-backend-1 | JS | 27-43 s | 16, 0 skipped | 69 | 1 | 10/10 |
| blaze3d-backend-1 | WasmGC (JSPI) | 31-60 s | 18 (2 fans), 0 skipped | 82 | 1 | 10/10 |
| blaze3d-direct-0 | JS | 34 s | 3, 0 skipped | 120 | 1 | — |
| blaze3d-direct-0 | WasmGC (JSPI) | 29-36 s | 8 (2 fans), 0 skipped | 0 | 0 | — |

No WebGPU validation message in any run; the one-level differences are bilinear and blur rounding.
After the frame the 26.2 harness also precompiles every static vanilla pipeline through the
browser front-end and WebGPU pipeline creation: all 87 are valid, the three TRIANGLE_FAN ones
(`sky`, `sunrise_sunset`, `debug_triangle_fan`) as triangle lists drawn through the core's
`FanIndices`. The 1.21.11 harness does the same through the lenient front-end:
all 87 are valid on WebAssembly GC, with the three fan pipelines and, through the LineWidth
REQUIRED patch (stride 24 when the page asks for WebGPU), the three line pipelines `lines`,
`lines_translucent` and `secondary_block_outline`. Without the GL depth remap (the translation
flag masked in the page) the 1.21.11 frame differs from GL in 40,524 pixels: the near quads are
clipped.
