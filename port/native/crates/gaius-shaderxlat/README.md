# gaius-shaderxlat

SPIR-V to WGSL for the WebGPU backends of all three v0.4.0 profiles, plus
the GLSL front-end of the two blaze3d ones.

| Profile | Render SPI | What the backend receives | Here |
|---|---|---|---|
| 26.3 | renderpearl-1 | bound SPIR-V (`BackendRenderPipeline.CreateInfo.shaders()`) | `translate` |
| 26.2 | blaze3d-backend-1 | GLSL (`precompilePipeline(RenderPipeline, ShaderSource)`) | shaderc, `frontend::link` (strict), `translate` |
| 1.21.11 | blaze3d-direct-0 | GLSL (`GpuDevice.precompilePipeline`) | shaderc, `frontend::link` (lenient), `translate` with `gl_depth_remap` |

`translate` takes what a renderpearl backend receives for each stage: the
vanilla frontend's shaderc output (Vulkan 1.2 / SPIR-V 1.5, `-O0`, debug info),
every resource in descriptor set 0 at its index in the pipeline's uniform list.
The WebGPU device reports `isZZeroToOne = true`, so 26.3 compiles with
`RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` and 26.2's `Projection` builds 0..1 matrices;
only 1.21.11 needs the depth rewrite.

```
SPIR-V -> strip debug info -> drop gl_PointSize writes -> texel buffers to storage buffers
       -> split combined image samplers -> split output arrays [-> GL depth remap]
                                                                 (src/passes, word level)
       -> naga 30.0.1 spv-in [y flip] -> WGSL binding layout -> naga validation -> naga wgsl-out
```

| Patch | Why | Vanilla users |
|---|---|---|
| `strip` | naga rejects `OpLine` in the type section | every module (the frontends always ask for debug info) |
| `point_size` | WGSL has no point size; WebGPU points are 1 px | `debug_point.vsh` (4 debug pipelines) |
| `texel_buffer` | WGSL has no texel buffers | `clouds.vsh` `isamplerBuffer CloudFaces` (`R8_SINT`, 8 pipelines) |
| `split_sampler` | naga spv-in and WGSL have no combined image samplers | every `sampler2D`/`samplerCube`, including function parameters (`sample_lightmap`) |
| `output_array` | WGSL entry point outputs cannot be arrays | `oit_add_transmittance.glsl` `out vec4 coeff[2]` |
| `depth_remap` (`Options::gl_depth_remap`) | 1.21.11 projects into GL's `-w..w`; WebGPU clips to `0..w` | every 1.21.11 vertex stage: `z = (z + w) / 2` before each return |

Every patch checks the shapes it handles and refuses anything else with
`Error::Spirv` rather than guessing (a resource pack's core shader that uses,
say, sampler arrays fails that one pipeline; the backend disables only that pipeline and skips its draws).

## WGSL binding layout

`Options::binding_layout` places the resources of vanilla uniform `i` (the
pipeline's uniform list; the 26.2/1.21.11 link's entry `i`):

| | uniform buffer | texture or texel buffer | split-off sampler |
|---|---|---|---|
| `Split` (default) | `@group(0) @binding(i)` | `@group(1) @binding(2i)` | `@group(1) @binding(2i + 1)` |
| `Flat` (the first prototype, "S4") | `@group(0) @binding(2i)` | `@group(0) @binding(2i)` | `@group(0) @binding(2i + 1)` |

With `Split`, group 0 holds only uniform buffers, which the backend binds from
its uniform ring with dynamic offsets (a draw only moves offsets), and group 1
the textures, samplers and read-only storage buffers (texel buffers), cached per
texture combination. Both stages derive their slots from the uniform index and
the resource kind alone, so a pipeline's stages agree without seeing each other.
`Flat` stays to reproduce the prototype's corpus output (the corpus with
`--layout flat --no-flip-y` reproduces it byte for byte).

`translate` also reflects each binding: group, binding, vanilla uniform index,
uniform block size, texture dimension, sample kind, and whether the texture is
ever passed to `textureSample*` (`sampled`, followed through function calls). A
texture that is only read with `textureLoad` can be bound as
`unfilterable-float` (depth and 32-bit float targets); a sampled one needs
`float` and a filtering sampler.

## Texel buffers

`texelFetch(buf, i)` becomes a load of word `i >> log2(texels per word)` of
`var<storage, read> buf: struct { data: array<u32> }` and the decode of the
texel format the backend passes in `Options::texel_buffers` (from
`UniformDescription.gpuFormat()`): `R8/R16/R32` `SINT`/`UINT`, `R32_FLOAT`.
The backend binds the buffer's bytes unchanged, padded to a multiple of 4.

## Clip space

The vanilla shaders are written for GL's clip space (y up) and the GL backend
passes their SPIR-V through SPIRV-Cross without any flip. WebGPU's clip space is
also y up, but its framebuffer row 0 is the top while GL's is the bottom; every
`gl_FragCoord`-indexed `texelFetch` (OIT), every full-screen pass (`screenquad`
`uv = pos * 0.5 + 0.5`, written the same way in all three profiles) and every
readback assumes GL's row order. `Options::flip_vertex_y` (default on, the
v0.4.0 convention) negates the vertex position's y (naga's
`adjust_coordinate_space`) so the backend renders in GL's row order; it then
uses `frontFace: "cw"` for the same culling and flips once when presenting, like
the vanilla Vulkan backend. `Options::gl_depth_remap` is the 1.21.11 depth rule
above.

## GLSL front-end (26.2, 1.21.11)

The blaze3d backends compile GLSL in the browser the way the vanilla 26.2
Vulkan backend does. The Java side prepares each stage (`ShaderSource`,
`GlslPreprocessor.injectDefines` with the pipeline defines, then with
`gl_VertexID -> gl_VertexIndex` and `gl_InstanceID -> gl_InstanceIndex`; for
1.21.11 also renames identifiers called `sampler`, `samplerShadow` or
`subpassInput` to `gaius_<name>`) and runs `gaius-shaderc.wasm` with
`GlslCompiler`'s options (Vulkan 1.2, auto bind uniforms, auto map locations,
debug info, `-O0`). `frontend::link` then does the rest of
`GlslCompiler.compile` on the two modules:

1. reflection with SPIRV-Cross's rules (`IntermediaryShaderModule.createFromSpirv`):
   uniform blocks (named by block type), combined image samplers, stage outputs
   and stage inputs in declaration order, built-ins left out; every stage output
   is renumbered to its index;
2. `GlslCompiler.addToBindGroup`, vertex stage then fragment stage: uniform
   buffers, texel buffers (a sampler the pipeline declares as a uniform) and
   sampled images, each name once; the entry index is the binding and the
   vanilla uniform index for `translate`;
3. `IntermediaryShaderModule.rebind`: bindings become entry indices; a vertex
   input's location is its index among the pipeline's vertex attributes the
   shader declares; a fragment input's location its index among the vertex
   outputs the fragment stage declares. A stage input nobody provides, or a
   resource no entry holds, fails with the vanilla message.

Only the literals of `OpDecorate Binding` and `OpDecorate Location` change, so
`Mode::Strict` is byte for byte the vanilla result: all 113 pipelines of 26.2
match the vanilla 26.2 front-end (`port/native/golden/shaders`, `--mc 26.2`).

`Mode::Lenient` (1.21.11) adds what a GL driver accepts and the vanilla rules
refuse (strict: 86/113; lenient: 113/113):

| Rule | 1.21.11 users |
|---|---|
| a uniform block or sampler the pipeline does not declare gets an entry after its stage's others (`Entry::declared == false`; GL binds the built-in blocks by name and an undeclared sampler reads unit 0) | 27 pipelines (`Globals`, `Fog`, `Lighting`, `Sampler1`, ...) |
| a fragment input no vertex output provides is removed if the shader never reads it (`passes::dead_input`) | `text_background` (`texCoord0`) |
| a vertex input declared as a float vector for an integer attribute is read as the integer type and converted at every load (`passes::attribute_type`, the attribute's `GpuFormat` gives the type) | `dragon_explosion_alpha`, `crumbling`, `item_entity_translucent_cull` (`UV1`/`UV2`, `RG16_SINT`) |

A vanilla quirk carries over unchanged: fragment input locations count only the
vertex outputs the fragment stage declares, so a fragment stage that skips an
output in the middle gets locations the vertex stage does not use. No vanilla
26.2 or 1.21.11 pipeline does that (the corpus builds every pipeline in Chrome);
a resource pack's that does fails pipeline creation, and the backend disables that pipeline.

## Build and use

```sh
cd port/native/crates/gaius-shaderxlat
CARGO_TARGET_DIR=../../target/shaderxlat cargo test -p gaius-shaderxlat -p gaius-shaderxlat-wasm
CARGO_TARGET_DIR=../../target/shaderxlat-wasm-simd \
  CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
  cargo build --profile release-size --target wasm32-unknown-unknown -p gaius-shaderxlat-wasm
```

The wasm wrapper (`wasm/`) is a `gaius-kernel-abi` kernel without imports:
`run_shader_xlat` (job kind `0x0501`; flags: bit 0 y flip, bit 1 flat layout,
bit 2 GL depth remap) and `run_shader_link` (job kind `0x0502`, the front-end
link). `port/native/golden/shaders/shaderxlat-job.js` encodes both jobs and runs
them on a synchronous instance. Tests: `tests/fixtures.rs` (vanilla 26.3
modules, copied by the corpus dump, skipped without them) and
`tests/frontend.rs` (the committed shaders of `tests/frontend`). Corpus tests and
measurements: `port/native/golden/shaders/README.md`.
