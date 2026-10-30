# v0.4.0 architecture

This document describes the v0.4.0 contributor architecture. Profile selection is data driven: use `port/versions/<profile>.json` and the source-set manifests instead of adding Minecraft version literals to code.

## Runtime layers

The browser launcher in `port/web/boot/gaius-boot.js` checks the required WasmGC and JSPI capabilities before requesting game resources. Release sites use `runtimePolicy: "wasm-only"`; a missing WasmGC, `try_table`, JSPI or required decompression capability selects a localized unsupported-browser page with a Retry button. The WasmGC client and integrated server use TeaVM 0.15, `port/web/runtime/wasmgc/gaius-wasm-boot.js`, `gaius-wasm-worker.js`, and the JSPI bridge. The JavaScript TeaVM target may build internally to prepare overlays and resources, but its client and server bundles are excluded from release packages.

`port/scripts/build-wasmgc.sh` runs the TeaVM compiler JVM with `-XX:+UseCompactObjectHeaders` when the JDK supports it (the WasmGC output does not depend on identity hashes) and with `-Xmx` taken from `GAIUS_TEAVM_MAX_HEAP` (default `14g`); the JavaScript build scripts do not set compact headers because TeaVM's JavaScript output follows identity-hash order.

A release site is published per profile (`Gaius-<profile>.html` at the Pages root redirects to the profile's multi-file site), ships the WasmGC modules gzip-only, and each profile also has a single-file portable page `Gaius-<profile>.html` as a release asset.

The integrated server follows the WasmGC client automatically. `?runtime=wasm`, `?serverRuntime=wasm` and `?gpu=webgpu|webgl2` are diagnostic assertions. WebGPU is attempted only with an available adapter and shader assets; a missing or rejected adapter keeps the WasmGC client on WebGL2. A client startup failure is terminal. The selection and immutable gzip module URLs are documented in [`docs/releasing.md`](releasing.md).

## Vanilla render SPIs and generations

The render backend is implemented once in `port/gpu/webgpu-core` and adapted at the vanilla SPI seam selected by `renderSeam` in the profile:

- `blaze3d-direct-0` (`port/mc/seams/blaze3d-direct-0`) adapts the single-device Blaze3D seam used by 1.21.11.
- `blaze3d-backend-1` (`port/mc/seams/blaze3d-backend-1`) adapts the `GpuBackend`/`GpuDevice` seam used by 26.2.
- `renderpearl-1` (`port/mc/seams/renderpearl-1`) adapts the renderpearl SPI used by 26.3.

Each seam keeps vanilla's render contract and falls back to WebGL2 when WebGPU cannot be created. Shader translation is selected by the profile's `shaderFront` and `bringup.shader` fields; do not branch on a version string in Java.

## Rust kernels in Workers

Hot cores are Rust crates under `port/native/crates`, compiled to `wasm32-unknown-unknown` and run in dedicated Workers through `port/web/kernels/kernel-pool.js`. The `gaius-kernel-abi` job header is the wire contract: a 16-byte `GKJB` header carries ABI version, `u16` kind, job id and payload length, and results use the `GKRS` header. Kind families are 0x01xx noise, 0x02xx mesh, 0x03xx light, 0x04xx worldgen and 0x05xx shader jobs (the optional PNG path is separately documented in the native README).

The kernel families are `gaius-noise(-wasm)`, `gaius-mesher(-wasm)`, `gaius-light(-wasm)`, `gaius-worldgen(-wasm)`, and `gaius-shaderxlat(+wasm)`. Java bridges and job codecs live beside the profile sources in `port/web/kernels` and `port/mc/shared`. Each profile enables kernels in `kernelMirror.enabled` of `port/versions/<profile>.json`: noise, light, worldgen and shaderxlat are enabled on 26.3, 26.2 and 1.21.11, and the mesher only on 26.3 (it is quarantined on 26.2 and 1.21.11 with the reason recorded under `kernelMirror.quarantined`); `ore` is off on all three.

A kernel is used only when its result is exact. The Java path remains the authority: an unsupported input, mirror refusal, table miss, resource limit, cancellation, or any parity failure declines to vanilla. Worldgen reports `worldgen-fallback:` for a chunk-level decline; the worker and Java bridge count and route that chunk through the normal implementation. There is no approximate result mode.

## Goldens and mirror locks

`port/native/golden/run-golden.sh` runs the JVM harness against `port/work/<profile>/client-named.jar` and writes reproducible fixtures under `port/native/fixtures/<profile>`. The fixture checks cover random sources, noise, `Mth`, and profile-specific worldgen and light/mesh harnesses. `port/native/check.sh`, the Rust tests and the Node fixture checks compare native/Wasm output with those JVM goldens.

Kernel semantics are declared in `port/versions/<profile>.json` under `kernelSemantics`. `port/native/crates/gaius-noise/build.rs` generates the accepted semantic codes, so a new profile reuses an existing implementation only when its declared semantics match. `port/native/kernel-mirror.toml` and the kernelmirror checks lock the vanilla owners, descriptors and method bodies watched by each mirror. `port/native/reviews/` records reviewed lock changes. A mirror lock or golden mismatch blocks the kernel route; contributors must fix or explicitly keep the vanilla path.

## Source sets, VersionOps and canonical sources

`port/mc/shared/sourcesets.json` resolves a profile from `canonical`, a generation set such as `renderpearl-1`, and the `legacy-12111` compatibility set. Relocations and exclusions adapt package/type names; the selector fields are `renderSeam`, `patchSet`, and profile data. The canonical set is the single source for shared behavior. Generation-specific contracts are small adapters implementing `VersionOps`, `MeshVersionOps`, and `LightVersionOps` (see `port/mc/shared/canonical/src/java/dev/gaius/browser/ops/VersionOps.java` and the generation implementations). Add a relocation or adapter when a vanilla SPI changes; do not duplicate a version-named source tree.

## Patchkit v2

Patch registrations live in domain packages under `port/tools/src/main/java/dev/gaius/tools/patches` (`render`, `mesh`, `light`, `worldgen`, `serverworker`, `network`, `storage`, `resources`, `ui`, `input`, `audio`, `telemetry`, `platform`). `port/tools/patchkit/README.md` documents the v2 registry contract:

- every registration declares `REQUIRED`, `FEATURE`, `PERF`, or `TELEMETRY` and explicit `js`/`wasmgc` targets and profile ranges;
- check mode restores disabled output and writes per-profile/per-target manifests;
- schema-2 method targets fingerprint the original vanilla bodies and static write sets;
- body changes require an exact human entry in `port/tools/patchkit/acknowledgements.json`; locks are never refreshed automatically.

Patch IDs remain stable keys for manifests, bring-up lists and acknowledgements. Run `node port/tools/patchkit/patchkit.mjs lint` and the targeted `check` before changing a registration.

## Bring-up flow

1. Add or update `port/versions/<profile>.json`, including render seam, patch set, shader bring-up, `kernelSemantics`, and mirror lock hash.
2. Resolve source sets with `node port/tools/sourcesets/sourcesets.mjs check`; add only relocations, adapters or a new seam when the profile exposes a new contract.
3. Run patchkit lint/check for both TeaVM targets and inspect the manifest/lock diff and acknowledgements.
4. Generate or verify JVM fixtures with `port/native/golden/run-golden.sh --verify`; run `port/native/check.sh --verify` and the relevant kernel Node checks.
5. Build the selected overlays and Wasm kernels with the repository scripts, stage profile-scoped output, and run the release metadata and lifecycle checks.
6. Complete browser acceptance for the profile: WasmGC capability gating, WebGPU then WebGL2 fallback, singleplayer Worker startup, world entry/reload, rendering, input, sound and multiplayer transport. Record browser-only items as explicit release acceptance results.

Useful references: [`port/native/README.md`](../port/native/README.md), [`port/native/golden/README.md`](../port/native/golden/README.md), [`port/mc/README.md`](../port/mc/README.md), [`port/mc/shared/README.md`](../port/mc/shared/README.md), and [`docs/releasing.md`](releasing.md).
