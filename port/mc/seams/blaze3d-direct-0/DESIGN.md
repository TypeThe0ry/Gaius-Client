# blaze3d-direct-0 (Minecraft 1.21.11): the seam, its calibration and the REQUIRED patches

The 1.21.11 WebGPU seam: vanilla's single-layer device API with the mini-frontend
26.2 later moved into vanilla, and the two REQUIRED 1.21.11 patches that register it.
`port/versions/1.21.11.json` selects it (`renderSeam: blaze3d-direct-0`); it is registered on the
WebAssembly GC build only (`seam.json` `registration.targets`), so the JavaScript build keeps
vanilla's `GlDevice` and its output. `src/` compiles against the 1.21.11 jar and its tests pass
(`test-seam.sh blaze3d-direct-0`: enum bridges, texel expansion).

## 1. What 1.21.11 differs in

1.21.11 has a single-layer blaze3d API: `GpuDevice`, `CommandEncoder` and `RenderPass` are the
interfaces the game calls and the GL backend implements; 26.2 later split each into a vanilla
frontend class over a `*Backend` interface. The seam therefore implements the 77 abstract methods
directly and also does the frontend's share:

| Mini-frontend part | Where | 26.2 equivalent |
|---|---|---|
| render pass state (one open pass, `close()` ends it), attachment checks | `D0Encoder`, `D0RenderPass` | `CommandEncoder.createRenderPass`, `RenderPass` |
| ARGB `int` clear colours to float RGBA | `D0Encoder` | `Optional<Vector4fc>` |
| `NativeImage` uploads (whole and regions: rows packed, no GL unpack state) | `D0Encoder.writeToTexture` | `CommandEncoder.writeToTexture(NativeImage)` |
| buffer mapping at the encoder (`mapBuffer`) | `D0Encoder.mapBuffer` | `GpuBuffer.map` |
| presentation: canvas sized to the view, blit, submit (1.21.11 has no surface and no submit) | `D0Encoder.presentTexture` | `GpuSurface`, `CommandEncoder.submit` |
| device information getters (vendor, renderer, limits, extensions) | `D0Device` | `DeviceInfo` |
| texture, view and buffer argument checks | `D0Device` | `GpuDevice` |
| pipeline cache by identity, GLSL through the core's front-end (lenient link, GL depth range) | `D0Device`, `D0Pipelines` | backend `precompilePipeline` |
| GL state vocabulary: `DepthTestFunction`, `SourceFactor`/`DestFactor`, colour and alpha write flags, `LogicOp`, one `VertexFormat` | `D0Pipelines`, `D0Enums` | `DepthStencilState`, `BlendFunction`, `ColorTargetState` |
| enum aliases: `TextureFormat` (4), `DepthTestFunction` (5), vertex element table | `D0Enums` (`D0EnumsTest` checks them against the jar) | names equal the core's |

Submit policy: the core's command encoder is submitted at `presentTexture`, once per frame;
buffer writes and mappings are command-ordered inside it, texture writes queue-ordered (vanilla
only adds texels nothing earlier in the frame samples, as on 26.3).

## 2. Calibration (the 2,000-line cut-off)

| | lines (all) | code lines (no blank, comment, import) |
|---|---:|---:|
| `blaze3d-direct-0` M1 skeleton (11 files, all 77 methods, mini-frontend above) | 1,307 | 904 |
| `blaze3d-direct-0` now (fans, texel expansion, the factory's canvas hand-off and toolchain check, `lineWidthPadding`) | 1,483 | 1,026 |
| `blaze3d-backend-1` (26.2) | 1,481 | — |
| `renderpearl-1` (26.3, M1) | 1,246 | 833 |

Still to add in the seam (estimates, lines with comments):

| Item | Lines | Depends on |
|---|---:|---|
| read mappings (`copyTextureToBuffer` and its callback are in place) | 40 | core readback |
| timer queries (`TimerQuery`, debug frame graph) | 30 | core timestamp queries |
| the remaining GL draw-time checks (vertex buffer for a non-empty format, closed buffers, usage bits, VALIDATION-mode uniform presence) | 100 | — |
| submits before readbacks and fences, on encoder growth | 30 | — |
| per-draw dynamic-offset fast path (as `B1RenderPass`) | 20 | — |
| layered and depth texture uploads | 20 | — |
| **projected seam total** | **≈ 1,720 (range 1,650-1,850)** | |

Outside the seam: the two REQUIRED patches (section 3, about 190 lines with the seam entry
resolution in `Minecraft12111BrowserPatcher`), the core's GL depth remap flag through the
translator and `FanIndices` (section 4), and the rest of the shared degrade table (POINTS,
WIREFRAME).

**Result: the projected size stays below 2,000 lines, so the size trigger of the cut-off rule does
not fire.** The second trigger (1.21.11 reaching the title on WebGPU with the full build) was met:
the shipped 1.21.11 client renders through this seam on WebGPU (`docs/release-v0.4.0.md`). The seam harness (`port/tools/seam-harness`, `D0Harness`)
draws a panorama, two triangle fans, depth-tested quads in the near half of GL's depth range and
a GUI frame through the seam, its device created by the patched `RenderSystem.initRenderer`: on
WebAssembly GC the frame is pixel-identical to vanilla's `GlDevice` on the WebGL 2 shim, and all
87 static 1.21.11 pipelines are valid through the lenient front-end.

## 3. The REQUIRED 1.21.11 patches

`Minecraft12111BrowserPatcher.patchInitRendererDeviceFactory` and
`patchLineWidthVertexFormatAlignment` (spec `REQUIRED wasmgc 1.21.11`; the JavaScript build skips
both, `port/tools/target-skips/js.txt`). Both take their target from `seam.json`
(`entry.deviceFactory`, `entry.lineWidthPadding`) and do nothing for a profile without a render
seam. `patches/verify-patches.sh` runs the patcher on `port/work/1.21.11/client-named.jar` for
both targets and checks the result on the JVM with `-Xverify:all` (`VerifyRequiredPatches`, with a
stand-in `D0Devices` in `patches/stub`): the JavaScript target writes neither class; the rewritten
`RenderSystem` verifies and calls `D0Devices.create`; with WebGPU asked for, all 15 vanilla vertex
formats are WebGPU-addressable; without it, every format has exactly the unpatched layout.

### 3.1 Registration: `RenderSystem.initRenderer` device factory

1.21.11 has no backend loop. `RenderSystem.initRenderer(long window, int debugVerbosity, boolean
synchronousLogs, ShaderSource, boolean debugLabels)` constructs the one device at bytecode 0-11
(from the 1.21.11 bytecode):

```
0: new GlDevice; 3: dup; 4: lload_0; 5: iload_2; 6: iload_3; 7: aload 4; 9: iload 5;
11: invokespecial GlDevice.<init>(JIZLShaderSource;Z)V; 14: putstatic DEVICE
```

The patch removes `new` and `dup` and turns the constructor call into
`invokestatic D0Devices.create(JIZLShaderSource;Z)GpuDevice`: same arguments, same order, one
`GpuDevice` for `putstatic DEVICE` (no frame change; max stack shrinks by two). The match fails
closed unless exactly that sequence is found. `D0Devices.create` returns the WebGPU device when
the page asks for it, the glue has a device and the page's shader toolchain is loaded, else
`new GlDevice(...)` with the original arguments, so the GL path is unchanged.

Fallback: 1.21.11's `Window` creates the window with `CLIENT_API = OPENGL_API` and 3.3 core
hints before `initRenderer`, so recognising `NO_API` cannot
work here; the factory is the fallback point. The GLFW shim attaches that window's WebGL 2 context
lazily: at `glfwMakeContextCurrent`, which in 1.21.11 only `GlDevice` calls, or at
the first read of `window.__gaiusWebGL`; the glue refuses a canvas that holds the game's GL context.
Once the WebGPU device exists, `D0Devices` disarms the lazy attach (`window.__gaiusWebGL` becomes
`null`, so no later reader takes the WebGPU canvas), or, if a context was attached already, has the
canvas host replace the element (rule 2). Every WebGPU failure the factory sees happens before
that (no device, no toolchain, a failed device creation; the `?gpuInject=after-configure` test
fails the glue's init at boot), so `GlDevice` then gets the untouched shim and canvas.

### 3.2 LineWidth vertex-format alignment

`DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH` is Position (3 floats), Color
(4 ubytes), Normal (3 bytes), LineWidth (float): LineWidth at offset 19, stride 23. WebGPU
addresses vertex attributes and strides in whole 4-byte words (`D0EnumsTest` reports it, and
`D0Pipelines` refuses such a format with a message naming this patch). The patch inserts
`invokestatic D0Devices.lineWidthPadding()I; invokevirtual VertexFormat$Builder.padding(I)`
before the `ldc "LineWidth"` of the builder chain whose previous element is Normal
(`DefaultVertexFormat.<clinit>`): with `?gpu=webgpu` the padding is 1, LineWidth moves to 20 and
the stride to 24, the layout of every other vanilla format with a 3-byte normal; otherwise it is 0
and `padding(0)` leaves vanilla's layout, so the GL path is byte for byte vanilla. Vertex writers
(`BufferBuilder`) and the GL backend take offsets and stride from the format, so a WebGPU request
that falls back to GL draws the same lines; no vertex data with this format is persisted. The
1.21.11 shader corpus (`port/native/golden/shaders`, replay mode) is built with the same
realignment.

A generic alternative, aligning every element offset to 4 in `VertexFormat$Builder.add`, would
also cover formats of mods; it changes no vanilla format but this one either.

## 4. The degrade table and the GL depth range

- TRIANGLE_FAN (`sky`, `sunrise_sunset`, `debug_triangle_fan`): compiled as triangle lists and
  drawn through the core's `FanIndices` (`0, k, k + 1` with the fan's first vertex as the base
  vertex; GL's triangles and winding), for the fans 1.21.11 draws: without indices (the sky, the
  sunrise) and through the shared sequential index buffer (immediate-mode fans). A fan through any
  other index buffer is skipped and reported once in the debug messages.
- GL depth range: `CoreShaderSource.Glsl.glDepthRange` reaches the translator (`WgslSources`,
  `GlueTranslator`, `gpu-glue.js` `translateSpirv` flag bit 2, the kernel's `glDepthRemap`): the
  vertex output's z becomes (z + w) / 2, so depth values and depth tests are GL's on WebGPU's 0..1
  clip range.
- NativeImage uploads with other channels than the texture (`D0Encoder.expand`): RGB,
  LUMINANCE and LUMINANCE_ALPHA into `RGBA8` and any image into `RED8`, as GL unpacks
  `GL_RGB`, `GL_RED` and `GL_RG` (missing green and blue 0, missing alpha 1).

### 4.1 Empty GL mip levels

Vanilla retains the requested logical mip count and computes each mip's dimensions by shifting
the base dimensions. A small texture can therefore end in a zero-sized GL image. WebGPU rejects
a physical chain longer than the base dimensions allow. `D0Texture` keeps vanilla's logical
description while `D0Device` allocates only the nonempty physical chain and trims a view that
extends into empty levels. A view starting in an empty level fails explicitly; validated
zero-area uploads do no GPU work. Nonempty mip contents and sampling remain unchanged.

`D0MipLevelsTest` checks dimensions and view ranges. The WasmGC seam harness allocates a
16-by-16 texture with six logical levels, uploads all levels including the empty last one, and
samples a view spanning the final nonempty and empty levels. WebGPU uses five physical levels
and draws the same pixels as vanilla GL with no validation messages.

## 5. Open items

- Read mappings (`D0Encoder.mapBuffer` with `read` throws) and GPU timer queries (the query
  returns no value) are not implemented (the same items as 26.2). Texture readback
  (`copyTextureToBuffer`, screenshots) and region clears go through the core.
- POINTS (the debug renderer's point pipeline draws 1-pixel points) and WIREFRAME (no-op): the
  core's degrade table.
