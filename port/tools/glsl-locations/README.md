# GLSL locations without shaderc's auto_map_locations

The blaze3d GLSL front-end of the 26.2 and 1.21.11 WebGPU seams compiles vanilla GLSL the way the
vanilla 26.2 Vulkan `GlslCompiler` does, which sets shaderc's `auto_map_locations`: the shaders
declare no `layout(location)`. The browser front-end does not rely on that export (shaderc modules
built before the toolchain exported the setter lack it, and rebuilding them needs emsdk), so
`GlslLocations` (`port/gpu/webgpu-core`) writes the locations
auto-mapping would assign into the source, and `GlslLocations.Result.restore` puts back the three
debug instructions that the rewritten source changes (`OpSource` text, the
`GL_ARB_separate_shader_objects` `OpSourceExtension`, `OpModuleProcessed "auto-map-locations"`).

`run.sh` proves the equivalence on both corpora with the native shaderc of the browser
toolchain's revision (LWJGL 3.4.3), for every pipeline:

| Check | 26.2 | 1.21.11 |
|---|---|---|
| shaderc with injected locations + restore = shaderc with `auto_map_locations`, byte for byte | 226/226 stages | 226/226 stages |
| the same without restore: only `OpSource` text, `OpSourceExtension`, `OpModuleProcessed` differ (no location literal, no `OpLine`) | 226/226 | 226/226 |
| vanilla Java link (`IntermediaryShaderModule.rebind`) of both paths, modules and entries | 113/113 identical | (no vanilla link) |
| `shaderxlat-link` of both paths (strict 26.2, lenient 1.21.11), modules, entries, fixes | 113/113 identical | 113/113 identical |
| without locations and without auto-mapping shaderc refuses ("SPIR-V requires location for user input/output") | 8/8 | 8/8 |

How the injection matches glslang's auto-mapping (`TDefaultIoResolverBase.resolveInOutLocation`,
default I/O mapper, entries ordered by symbol id): inputs and outputs are counted separately from
0 in declaration order, each variable advancing by its location size (matrices by columns, arrays
by element count, structs and blocks by their members); variables with their own location,
built-ins and built-in blocks are skipped; the preprocessor conditionals are evaluated so that a
declaration in an inactive branch takes no location. Locations on varyings need GLSL 4.10, so the
injection enables `GL_ARB_separate_shader_objects` after `#version` and restores the line
numbering with `#line` (GLSL 3.30+ and cpp-style `#line` number the following line), which keeps
every `OpLine` unchanged.

```sh
bash port/native/golden/shaders/dump-corpus.sh --mc 1.21.11   # once: the 1.21.11 legacy dump
bash port/tools/glsl-locations/run.sh all
```
