# Minecraft-facing code

Only code under `port/mc` may reference Minecraft symbols in the new layout.

- `shared/`: the Gaius sources that differ between profiles, as source sets selected by profile
  fields (render seam generation, patch set) instead of version ids: a canonical set, generation
  sets with relocations, and the LTS compat set (`shared/README.md`).
- `seams/<generation>/`: a thin adapter from one vanilla SPI generation onto a Gaius core. Each has
  a `seam.json` (entry points, registration point, fingerprint scope) and is selected per profile
  by `port/versions/<id>.json` (`renderSeam`; `none` selects none); the profiles of a seam are the
  profiles that name it, so a new profile JSON needs no seam edit.
  `build-overlays.sh` compiles the selected seam into the client overlay.
  - `renderpearl-1`: WebGPU behind the renderpearl SPI (26.3), onto `port/gpu/webgpu-core`.
  - `blaze3d-backend-1`: WebGPU behind the blaze3d backend SPI (26.2: `GpuBackend` ->
    `GpuDevice` -> `GpuDeviceBackend`), onto the same core; vanilla's GLSL goes through the core's
    blaze3d GLSL front-end.
  - `blaze3d-direct-0`: WebGPU behind the single-device blaze3d of 1.21.11 (`GpuDevice`,
    `CommandEncoder` and `RenderPass` with the mini-frontend 26.2 later moved into vanilla), onto
    the same core; registered on WebAssembly GC by two REQUIRED patches of
    `Minecraft12111BrowserPatcher` (`seams/blaze3d-direct-0/DESIGN.md`,
    `seams/blaze3d-direct-0/patches/verify-patches.sh`).
  - `bash port/mc/seams/test-seam.sh <generation>` runs the seam's JVM tests (EnumBridge
    completeness, flag mappings) against the vanilla jar of every profile that selects it.
- Patch domains (render, mesh, light, worldgen, server-worker, network, storage, resources, ui,
  input, audio, telemetry, platform): the ASM patch code by domain. It is compiled with the
  patchers, so it does not live under `port/mc`: it is in the Java packages
  `dev.gaius.tools.patches.<domain>` under `port/tools/src/main/java` (`server-worker` is the
  package `serverworker`), which every port/tools compile already reaches through `-sourcepath`;
  the patcher steps keep the chain order and the patch ids (`port/tools/patchkit/README.md`,
  "Domains"). `dev.gaius.tools.patches.support` holds the ASM helpers that several domains of a
  patcher step share (one class per step) and is not a domain.
- `profiles/<id>/`: generated locks only. `spi.fingerprint` is the two-level fingerprint of the
  profile's render seam (`bash port/tools/seamprobe/seamprobe.sh <id> --write|--check`): a
  signature change means the adapter has to change, a body-only change needs a review.
  `usage.lock` is what vanilla references in the JDK and in each native library
  (`bash port/tools/extrefs/extrefs.sh usage <id> --write|--check`): native entry tables
  such as the SDL one of `LwjglSdlBrowserPatcher` are generated from it, and
  `extrefs.sh jdk <id>` reports the JDK members the class library lacks (`port/overrides/jdk`).
  `patch-manifest.<target>.json` (`js`, `wasmgc`) is the patch lock of the profile: the outcome,
  kind, targets and written classes of every client patch on that target, written by
  `node port/tools/patchkit/patchkit.mjs check --write` and compared in CI with `--compare`
  (`port/tools/patchkit/README.md`).
  `kernel-mirror.lock` and `kernel-semantics.json` are the kernel mirror lock and the gate decision
  of the profile (`node port/tools/kernelmirror/kernelmirror.mjs generate|gate`;
  `port/tools/kernelmirror/README.md`).
