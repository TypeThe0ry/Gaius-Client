# blaze3d-backend-1 (Minecraft 26.2)

WebGPU behind vanilla 26.2's blaze3d backend SPI: `GpuBackend` -> `GpuDevice(GpuDeviceBackend)`,
the generation between 1.21.11's single-interface `GpuDevice` and renderpearl. Every class
delegates to `port/gpu/webgpu-core`; the seam holds no GPU logic.

| Vanilla type | Seam class | Notes |
|---|---|---|
| `GpuBackend` (4) | `B1Backend` | `GLFW_CLIENT_API = NO_API` like vanilla's Vulkan backend; `createDevice` wraps `B1Device` in vanilla's `GpuDevice` (as `GlBackend` does) and throws `BackendCreationException` without a ready WebGPU device, so vanilla's loop falls back to `GlBackend` |
| `GpuDeviceBackend` (17) | `B1Device` | `precompilePipeline` compiles once per `RenderPipeline` (identity cache, vanilla's `pipelineCache`); `DeviceInfo.isZZeroToOne = true` |
| `CommandEncoderBackend` (17) | `B1Encoder` | texture-to-buffer readback and buffer-to-texture copies go to the core; read mappings of a buffer (`B1Buffer.map` with `read`) are not implemented |
| `RenderPassBackend` (20) | `B1RenderPass` | name-bound uniforms and samplers kept per pass and resolved through `CorePipeline.slotOf`; `drawMultipleIndexed` is vanilla Vulkan's loop; an unavailable pipeline skips its draws; TRIANGLE_FAN pipelines are triangle lists whose draws go through the core's `FanIndices` (non-indexed fans like the sky, and fans through the shared sequential index buffer) |
| `GpuSurfaceBackend` (7) | `B1Surface` | `present()` yields the frame through the GLFW shim's `glfwSwapBuffers`, as on the WebGL 2 path |
| `TransientMemory` (8) | `B1TransientMemory` | core `TransientArena` |
| `GpuBuffer` (3), `GpuTexture` (2), `GpuTextureView` (2), `GpuSampler` (7) | `B1Buffer`, `B1Texture`, `B1TextureView`, `B1Sampler` | extend vanilla's abstract classes (no vanilla code casts them) |
| `GpuFence` (2), `GpuQueryPool` (4), `CompiledRenderPipeline` (1), `RenderPass.UniformUploader` (1) | inline / `B1Pipeline` | fences are always signalled (CPU data is captured when queued); timestamp queries return no values |

95 abstract methods in all.

Shaders: `B1Pipelines` hands the core the GLSL vanilla's Vulkan backend gives shaderc
(`ShaderSource`, the pipeline's defines, `GlslCompiler`'s `gl_VertexID`/`gl_InstanceID` defines)
as `CoreShaderSource.Glsl` (strict link); vertex attributes take locations in element order over
all bindings, as `VulkanRenderPipeline` does. See the core's `GlslFrontEnd`.

Registration (`seam.json` `registration`, WebAssembly GC only): the generic
`Minecraft262BrowserPatcher.patchPreferredGraphicsApi` replaces
`PreferredGraphicsApi.getBackendsToTry()` with a call of `B1Backends.backendsToTry()` (26.2) or
`RpBackends.backendsToTry()` (26.3), the entry `seam.json` names. `B1Backend.createDevice` needs
the glue's device and the page's shader toolchain (`gaius-boot.js` loads it for `?gpu=webgpu` on a
page that names it in `window.__gaiusWebGpuShaderToolchain`); without either it throws
`BackendCreationException` and vanilla's loop moves on to WebGL 2.

Checks:

```sh
bash port/mc/seams/test-seam.sh blaze3d-backend-1     # EnumBridge completeness, flag bits, GlslLocations
bash port/tools/seamprobe/seamprobe.sh 26.2 --check     # SPI fingerprint (port/mc/profiles/26.2)
bash port/tools/seam-harness/build.sh blaze3d-backend-1 ...  # title-like frame, WebGPU vs WebGL 2
```
