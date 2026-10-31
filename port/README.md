# Minecraft TeaVM browser port

This directory is the active Gaius implementation. The released profiles are
Minecraft `1.21.11`, `26.2` and `26.3`, defined in `port/versions/*.json`.
`1.21.11` builds from the obfuscated client with its official mappings and
targets JDK 21; it is also the profile the Paper plugin builds against. `26.2`
and `26.3` ship a named client and target JDK 25.

Every profile is compiled by TeaVM 0.15 for the WasmGC release target:

- **WebAssembly GC with JSPI** for the client and the integrated singleplayer
  server (`port/scripts/build-wasmgc.sh`). Rust WebAssembly kernels
  (`port/native`) run in Workers; the Java path stays authoritative and a
  kernel that cannot give the exact result declines to the vanilla code.
  Rendering uses WebGPU and falls back to WebGL2.
- **Internal JavaScript preparation**, used only when the WasmGC release build
  needs generated resources or overlays. The client and server bundles from
  this step are not staged or shipped.

The contributor view of the runtime layers, render seams, kernels and bring-up
flow is in `docs/architecture-v0.4.md`.

## Rules

- Each profile's game implementation comes from its official Minecraft client.
- Browser adaptations are maintained as replacement/patch classes.
- No rewritten TypeScript world simulation is used.
- Official game JARs, mappings, libraries, and assets stay under `port/work/`
  and are never committed.
- Builds must fail on unresolved TeaVM errors; errors are not hidden with
  `|| true` or `stopOnErrors=false`.

## Stage A: acquire and remap

Requirements: JDK 25 or newer for `26.2` and `26.3`, JDK 21 or newer for
`1.21.11` (`GAIUS_JAVA_HOME` or `JAVA_HOME` select it), plus `curl`, `jq`,
`unzip`, Node.js, Python 3 and a SHA-1/SHA-256 tool.

Select the profile explicitly; without `GAIUS_VERSION_PROFILE_PATH` the
scripts use the profile named in `port/config.json`. The example below selects
`26.3`:

```sh
export GAIUS_VERSION_PROFILE_PATH=versions/26.3.json
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
```

Outputs are profile-scoped:

```text
port/work/26.3/client-original.jar
port/work/26.3/client-mappings.txt   (obfuscated-with-mappings profiles only: 1.21.11)
port/work/26.3/client-named.jar
port/work/26.3/libraries/
port/work/26.3/classpath.txt
```

`client-named.jar` is the actual official client bytecode, remapped with
Mojang's official mappings for `1.21.11`; for the named `26.2` and `26.3`
clients it is the official jar without remapping. It is the input to every
later browser build.

## Stage B: TeaVM builds

No system Maven installation is required. The checked-in `port/mvnw` bootstrap
downloads and verifies Apache Maven under the ignored `port/work/tools/`
directory.

The full client analysis needs a large compiler heap: the build scripts default
to `-Xmx14g` (`MAVEN_OPTS`; `GAIUS_TEAVM_MAX_HEAP` for `build-wasmgc.sh`). A
machine with at least 32 GB physical memory is recommended.

```sh
./port/scripts/build-version-release.sh <profile>   # development resources and local site
bash port/scripts/release-train.sh --out <train> --resources <resources.json> --js rebuild
```

`build-wasmgc.sh [--role client|singleplayer-worker] [--release] <profile>`
builds one WebAssembly GC role; it takes the generated resources from a
JavaScript build of the same profile (`GAIUS_WASMGC_RESOURCE_ROOT`), which the
release matrix arranges (`docs/releasing.md`). Use `build-version-release.sh
<profile>` or the matrix so that generated target, overlay, and dist state
cannot cross profiles.

Before every TeaVM run, `build-overlays.sh` creates a patched classlib JAR under
`port/work/overlays/<profile>`. This is required for extending existing TeaVM
classes such as `java.util.UUID`; adding a duplicate class at the end of the
Maven classpath cannot override TeaVM's own classlib.

The builds use the real `net.minecraft.client.main.Main` and
`stopOnErrors=true`. The error report in `<build root>/teavm-build.log`
(`port/target/<profile>/` by default) is the authoritative platform-port
backlog. Every JavaScript build also writes:

```text
port/target/<profile>/teavm-gap.json
port/target/<profile>/teavm-gap.md
```

The build keeps TeaVM's original non-zero exit status. The reports do not turn
a failed compilation into a successful one.

## Stage C: browser platform overlays

Browser platform classes live under:

```text
port/src/main/java/                   shared by both TeaVM targets
port/src/target-wasmgc/java/          WebAssembly GC only
port/overrides/                       classlib, JDK API gaps, libraries, client overrides
port/mc/shared/                       profile-selected vanilla-facing source sets
```

Those classes shadow desktop-only surfaces in the named client JAR. The
bytecode patches that adapt the client are registered in `port/tools`
(`port/tools/patchkit/README.md`); the selected source sets are described in
`port/mc/shared/README.md`.

## Runtime architecture

The browser launcher (`port/web/boot/gaius-boot.js`) gates the WasmGC runtime
and the renderer: a browser without WasmGC, `try_table` or JSPI receives the
localized unsupported-browser page; WebGPU is used when an adapter and shader
assets are available, WebGL2 otherwise. A release is served as one multi-file site per
profile; the site root carries `Gaius-<profile>.html` redirects to the
per-profile paths. Single-player launches the official server entry point in
a Worker started by `singleplayer-server-worker.js`, which loads
`singleplayer-server.wasm`; the client
and server communicate through a paired `MessageChannel`, while IndexedDB
persists world data between Worker sessions. Build output lives in
`port/web/dist/<profile>/` (JavaScript; the WebAssembly GC build defaults to
`port/web/dist/<profile>-wasmgc/`) and in the release root of
`release-train.sh`; `.wasm` modules are published gzip-only.

The portable single-file page `Gaius-<profile>.html` is built from a profile
dist (`build-teavm-release.sh` writes it as `Gaius.html`). It reconstructs the
compressed client and Wasm modules as browser-local Blob URLs and passes the
compressed official server payload into its Worker. Single-player therefore
remains frontend-only and works without a hosted Gaius service.

External Java servers remain unmodified. `apps/bridge/dist/main.js` is a raw,
backpressured WebSocket-to-TCP tunnel with SRV resolution and constrained HTTP
proxies for Mojang authentication, skins, Realms, and resource packs. The
resource-pack proxy retries interrupted upstream bodies before exposing a
response to Chrome, publishes the verified byte length, cancels orphaned
downloads, and keeps a bounded short-lived disk cache for repeated joins;
Mojang's
`blockedservers` request is proxied as well, so it does not depend on cross-origin
access from the page. The
online-mode smoke starts an official plugin-free `server.jar`, performs the
RSA/AES session handshake against a controlled session fixture, and requires
PLAY plus real chunk packets before passing.

RelayNode discovery also negotiates the node's per-target TCP timeout. The
browser keeps the WebSocket alive long enough for an SRV target to time out and
fall back to the entered host without leaving an orphaned TCP attempt when the
player cancels.

Remote connection order is: optional same-host Paper plugin, then configured
and default RelayNodes ranked for the requested normalized `host:port` by
active target affinity, recent reachability, free capacity, and configured
priority. Every status ping or join remains an isolated, short-lived TCP
tunnel. The browser accepts repeated `bridge` query parameters,
`globalThis.__gaiusBridgeUrls`, or the `gaius.bridgeNodes` local-storage JSON
array, allowing already-deployed relay capacity to be contributed independently
of the client. Short discovery caches avoid repeating an unavailable plugin
probe and RelayNode manifest fetch between a status ping and its join; they do
not share the underlying protocol stream.

For unencrypted traffic of the supported protocols (`774`, `776` and `777`),
RelayNode also tracks server-initiated `PLAY -> CONFIGURATION -> PLAY` cycles. Synthetic
browser-stall ticks are disabled throughout reconfiguration, preventing stale
PLAY packets from being sent into the configuration protocol after a plugin
changes server state.
