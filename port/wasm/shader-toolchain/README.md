# Browser shader toolchain

Minecraft 26.3's OpenGL backend no longer compiles GLSL directly: every render
pipeline goes GLSL -> SPIR-V through **shaderc**, is reflected by
**SPIRV-Cross** (spvc), and is decompiled back to GLSL 330 for GL
(`renderpearl/frontend/shaders/GlslCompiler`, `SPIRVModule`,
`backend/opengl/GlPipelineRecompiler`).  In the browser those LWJGL bindings run
on WebAssembly builds of the exact native revisions.

```
Minecraft (TeaVM)
  -> org.lwjgl.util.shaderc.Shaderc / org.lwjgl.util.spvc.Spvc
       bodies redirected by LwjglShadercBrowserPatcher / LwjglSpvcBrowserPatcher
  -> BrowserShaderc / BrowserSpvc            port/overrides/libraries/lwjgl-{shaderc,spvc}
       handles, include pre-resolution, BrowserMemory marshalling, ESSL post-processing
  -> BrowserShadercWasm / BrowserSpvcWasm    (@JSBody)
  -> window.__gaiusShaderToolchain           loader/gaius-shader-toolchain.js
  -> gaius-shaderc.wasm / gaius-spvc.wasm    built by port/scripts/build-wasm-shader-toolchain.sh
```

## Inputs (`pins.env`)

| Source | Commit | Why |
|---|---|---|
| shaderc | `2c8cae77` (v2026.3) | `*.dll.git` of the lwjgl-shaderc 3.4.3 natives |
| glslang | `168d452a` (16.4.0) | shaderc `DEPS` at that commit |
| SPIRV-Tools | `b707790a` | shaderc `DEPS` |
| SPIRV-Headers | `29981f65` | shaderc `DEPS` |
| SPIRV-Cross | `6c09849f` (vulkan-sdk-1.4.357.0) | `*.dll.git` of the lwjgl-spvc 3.4.3 natives |
| emscripten | 6.0.10 (`d6c521a7`) | toolchain the modules are built and validated with |

Sources are fetched by commit id (`git fetch --depth 1 <url> <commit>`), so the
tree is verified by git itself.

## Build

```sh
# emsdk: git clone https://github.com/emscripten-core/emsdk && emsdk install 6.0.10 && emsdk activate 6.0.10
GAIUS_EMSDK=/path/to/emsdk port/scripts/build-wasm-shader-toolchain.sh --dist port/web/dist/26.3
```

`--work` defaults to `port/target/shader-toolchain` (keep it short on Windows:
CMake object paths below it exceed MAX_PATH otherwise).  A stamp over the pins,
export lists and link flags skips the ~2 minute compile when nothing changed.
CMake, Ninja and Git must be on `PATH`.  Build paths are mapped out of the
modules (`-ffile-prefix-map`); two builds from scratch produced byte-identical
modules.

Without emsdk, `--prebuilt DIR` (or `GAIUS_SHADER_TOOLCHAIN_PREBUILT`) takes the
four module files of an earlier run from `DIR`: its
`gaius-shader-toolchain.json` must name exactly the pinned sources and
emscripten, and every file must match its sha256 there.

Artifacts (all next to `index.html`):

| File | Size | gzip |
|---|---|---|
| `gaius-shaderc.wasm` + `.js` | 3,405,778 + 17,222 B | 1,067,026 + 5,606 B |
| `gaius-spvc.wasm` + `.js` | 643,044 + 13,844 B | 210,797 + 4,377 B |
| `gaius-shader-toolchain.js` (loader) | ~40 KB | ~10 KB |
| `gaius-shader-toolchain.json` | pins, emscripten version, exports, sizes, sha256 | |

**Repository policy.**  The modules are build outputs and are not committed:
the repository keeps large binaries only under `port/web/dist` (Git LFS, one
coherent release).  A release build of a profile that needs the toolchain runs
the script with `--dist <profile dist>`; the files then travel with that
release like `gaius-hotpath.wasm`.

## The blaze3d front-end (26.2, 1.21.11)

The WebGPU backends of 26.2 and 1.21.11 receive GLSL and compile it the way the
vanilla 26.2 `GlslCompiler` does, which also sets
`shaderc_compile_options_set_auto_map_locations` (renderpearl does not): their
shaders declare no `layout(location)`, and without the option shaderc refuses
every stage ("SPIR-V requires location for user input/output"). The export list
therefore carries that setter and the loader maps job option 6 to it
(`OPTION_AUTO_MAP_LOCATIONS`; the Java side's `BrowserShadercJob` code must
match). The reflection and rebind that follow shaderc are Rust
(`gaius-shaderxlat` `frontend::link`), not spvc.

Modules built before the export was added (every `--prebuilt` set made before
it, whose manifest has no `exports` record) still serve 26.3: the build prints a
warning, `api.shadercSupportsOption(6)` is false, and a job that asks for the
option fails with a message naming the export while the instance stays in
service. Rebuild them with emsdk (the stamp covers the export list, so the next
build recompiles):

```sh
GAIUS_EMSDK=/path/to/emsdk port/scripts/build-wasm-shader-toolchain.sh --force
node port/wasm/shader-toolchain/check-loader-exports.mjs --toolchain port/target/shader-toolchain/out
```

`check-loader-exports.mjs` checks that every function the loader calls is
exported and every option setter has an option code (without `--toolchain`
it only reads the two files), and with `--toolchain` compiles an unlocated
stage on the modules with and without the option.

## Page contract

`postprocess-index-html.py` (`patch_shader_toolchain_loader`) adds

```html
<script data-gaius-shader-toolchain data-profile="26.3" src="gaius-shader-toolchain.js?v=<token>"></script>
```

to `<head>` **only when classes.js calls the toolchain** (the JSBody scripts of
BrowserShadercWasm/BrowserSpvcWasm name `__gaiusShaderToolchain`; no 26.2 JavaScript
client does), and awaits `window.__gaiusShaderToolchainReady` right before
`main(args)`.  The token is the content hash of the five files.  A missing
toolchain file is a warning, or an error with `GAIUS_SHADER_TOOLCHAIN_STRICT=1`.

On the WebAssembly GC pages, `port/web/runtime/wasmgc/make-wasm-page.py` names the loader in
`window.__gaiusWebGpuShaderToolchain` for a profile whose WebGPU front-end compiles GLSL in the
page (26.2 and 1.21.11), and `gaius-boot.js` loads it for `?gpu=webgpu` only; the WebGL2 path never
needs it, and without the toolchain `?gpu=webgpu` falls back to WebGL2 (`GAIUS_SHADER_TOOLCHAIN_STRICT=1`
makes the missing files a build error).

The loader compiles both modules (`compileStreaming`), starts one instance of
each plus a spare, attaches the profile's IndexedDB result cache
(`gaius-shader-cache-v1-<profile>`, at most 3 s of the boot), and resolves the
promise to the API, also installed as `window.__gaiusShaderToolchain`.  A
portable page sets `window.__gaiusShaderToolchainUrls = {shadercJs, shadercWasm,
spvcJs, spvcWasm}` before the loader runs.

## Behaviour

* **shaderc jobs are self-contained.**  BrowserShaderc records option calls in
  order and resolves includes before compiling: it scans the source for
  `#include "x"` / `<x>` and calls Minecraft's resolver with the arguments
  shaderc would pass (requested, type, requesting source name, depth),
  recursively, reading each `ShadercIncludeResult` by (pointer, length) - the
  Minecraft strings carry no terminator.  The WebAssembly compiler answers its
  include requests from that table, so it never calls back into Java; the
  release callback runs after the compilation.
* **Result cache.**  A job's SHA-256 over every input plus the toolchain token
  keys its SPIR-V; hits skip WebAssembly entirely.  Successful results persist
  in IndexedDB; entries of other toolchain versions are deleted on load.
* **Failure recovery.**  Any exception out of a WebAssembly call (trap, abort,
  out of memory) retires that instance: the job reports
  `shaderc_compilation_status_internal_error`, spvc calls with handles of the
  old generation return `SPVC_ERROR_OUT_OF_MEMORY` (or 0/null), and the next
  call runs on the spare; a new spare is started in the background.
* **ESSL.**  BrowserSpvc turns spvc's GLSL 330 into WebGL2 ESSL 3.00
  (`BrowserEsslPostProcessor`): texel buffers become `highp [iu]sampler2D` read
  through a 4096-wide `texelFetch` helper (BrowserOpenGL's glTexBuffer
  emulation), fragment output arrays are split per location, the header gets
  highp defaults for float, int and every sampler type used, and the text is
  made a fixed point of `BrowserOpenGL.translateShaderSource` (whose 26.2
  snippet rules would otherwise corrupt spvc output), so BrowserOpenGL stays
  unchanged.

## Verification

`node port/scripts/shader-corpus-smoke.mjs --toolchain <artifacts>` (needs the
26.3 overlays from `build-overlays.sh`, JDK 25 and Chrome):

* javap: every Shaderc/Spvc method the client calls is redirected, nothing loads
  a native library or calls JNI;
* ShaderCorpusHarness (`port/tools/shader-corpus`) compiles all 216 pipelines
  (static RenderPipelines + every post_effect pass) on the JVM natively and
  through the patched bindings and shims: reflection, patched SPIR-V and raw
  GLSL identical, every ESSL output a translateShaderSource fixed point;
* the shim run's SPI tapes (432 shaderc jobs, 864 spvc sessions / 47,350 calls)
  replay byte-identically on the WebAssembly modules, in Node and in Chrome
  through the real page bootstrap;
* every ESSL program compiles and links in headless Chrome WebGL2 (216/216);
* instance recovery after a trap in each module; IndexedDB reload hits.

Measured in Chrome 154 (Windows, RTX 4060, machine shared with other builds,
two runs): download 1.31 MB gzip (budget 3 MB); load 47-76 ms; all 216
pipelines cold 0.75-0.94 s (budget 5 s); after a reload from the IndexedDB
cache 0.25-0.38 s (budget 0.5 s).
