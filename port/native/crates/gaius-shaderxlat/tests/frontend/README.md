# Front-end fixtures

GLSL written for `tests/frontend.rs` (not Mojang's shaders, so the compiled
modules are committed): `*.vsh`/`*.fsh` and their shaderc output
`*.vert.spv`/`*.frag.spv`, compiled exactly as the vanilla 26.2
`GlslCompiler` compiles (global defines, Vulkan 1.2, auto bind uniforms, auto
map locations, debug info, `-O0`, LWJGL 3.4.3 shaderc):

```sh
port/native/golden/shaders/dump-corpus.sh --frontend-fixtures
```
