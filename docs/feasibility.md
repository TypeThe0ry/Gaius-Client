# Browser port feasibility: Minecraft Java Edition 1.21.11

Status: initial technical assessment  
Assessment date: 2026-06-19  
Target release: Java Edition 1.21.11, released 2025-12-09

> This is a historical record, written before the port existed. Gaius v0.4.0 took
> Option A below: a TeaVM 0.15 WasmGC source port with a JSPI-based client and
> integrated server, Rust WebAssembly kernels in Workers, WebGPU with a WebGL2
> fallback, for Java Edition 26.3, 26.2 and
> 1.21.11. The recommendation to build a clean-room client was not followed, so
> read the architecture and delivery sections as the original plan, not as the
> shipped design (see [`architecture-v0.4.md`](architecture-v0.4.md)). The legal
> distribution constraints and the bridge requirements below still apply.

## Verdict

There are two materially different projects hiding inside the phrase “real
browser port”:

| Goal | Verdict |
| --- | --- |
| A browser client that speaks the real 1.21.11 protocol, consumes modern registries and data components, renders modern worlds, and supports basic gameplay | Feasible |
| A mostly mechanical WebAssembly build of Mojang's complete Java client | Not feasible without a large, invasive platform port |
| A redistributed browser bundle containing a transformed official client and official assets | High legal risk; do not make this the project model |
| Near feature parity with the desktop client, including Realms, telemetry, accessibility, every screen, resource-pack edge case, and mod compatibility | Multi-year effort |

The recommended product is a clean-room, wire-compatible modern client. It is
not a 1.8/1.12 fork and does not translate the server down to an old protocol.
Its protocol state machine, registries, chunks, entities, items, recipes, and
rendering inputs are native to 1.21.11.

An internal source-port experiment is still worthwhile. It should be a
time-boxed feasibility spike, not the foundation assumed in advance.

## Facts about the target

Mojang's version metadata identifies 1.21.11 as:

- a release from December 9, 2025;
- Java 21 bytecode (`major version 65`);
- a 31,152,600-byte client jar;
- asset index 29, containing 4,590 objects totaling 446,134,512 bytes;
- a client using LWJGL 3.3.3, Netty 4.2.7, authlib, DataFixerUpper, ICU4J,
  fastutil, JOML, OpenAL, FreeType, STB, and native platform libraries.

Local inspection of the official client and official mappings found:

- 10,291 mapped classes;
- about 135,236 mapped fields and methods;
- 901 renderer classes and 758 GUI classes;
- 356 network-protocol classes;
- 54 core data-component classes;
- packet classes for login, configuration, common, cookie, status, ping, and
  game protocol phases;
- 160 clientbound and 70 serverbound game packet classes, before counting
  shared/common phase packets.

These numbers do not mean every class must be ported. They show why “replace
LWJGL calls and press compile” is not a credible plan.

As of this assessment, Minecraft's current release line has moved to year-based
versioning and the public manifest reports 26.2 as latest. Pinning 1.21.11 is
therefore valuable: protocol and behavior must not silently track latest.

## What “genuine 1.21.11-compatible” should mean

The project should use conformance tests rather than marketing language. A
minimum genuine client must:

1. Perform the 1.21.11 handshake, status, login, configuration, and play state
   transitions.
2. Support online-mode encryption, compression, session joining, disconnects,
   transfers, cookies, and resource-pack negotiation needed by normal servers.
3. Build runtime registries from the configuration stream rather than relying
   on an old client's fixed numeric tables.
4. Decode current chunk sections, palettes, heightmaps, lighting, biomes, block
   entities, entity metadata, attributes, particles, sounds, recipes, tags,
   chat components, and item data components.
5. Render blocks and items from current resource-pack model definitions.
6. Implement current movement, collision, interaction sequencing, inventory
   synchronization, acknowledgements, and teleport handling closely enough for
   ordinary survival gameplay and server anti-cheat expectations.
7. Join an unmodified 1.21.11 Java server through a byte-preserving transport
   bridge. The bridge must not translate the game protocol to an older version.

It does not need, in its first release, to implement single-player world
generation, an integrated server, Realms, mods, shaders, every graphics option,
or every menu.

## Recommended architecture

```text
Browser main thread
  HTML shell, canvas, input, accessibility, sign-in UI
                  |
         transferable commands/events
                  |
Dedicated game worker
  protocol state | registries | world | prediction | meshing
       |                 |                       |
       |                 +---- OPFS worker ------+
       |                       cache/settings
       |
       +---- WebSocket/WebTransport ---- bridge ---- raw TCP ---- Java server
       |
       +---- render command buffers ---- WebGPU
       |                                 WebGL2 fallback
       |
       +---- sound events -------------> WebAudio
```

Keep the authoritative game model in one worker initially. Minecraft's normal
client tick is logically single-threaded, and preserving deterministic ordering
is more valuable than prematurely spreading mutable world state across many
workers. Add a mesh-worker pool after the vertical slice works.

Cross-worker shared memory is useful for chunk meshes and command queues, but
it requires HTTPS and cross-origin isolation. The deployment must emit
appropriate COOP and COEP headers and ensure every embedded resource is
compatible with that policy.

### Module boundaries

| Module | Responsibility | Preferred implementation |
| --- | --- | --- |
| `protocol` | Framing, VarInts, compression, encryption, packet codecs, protocol state | Rust/Wasm or portable Java/TeaVM |
| `registry` | Dynamic registries, tags, holders, IDs, known packs | Portable core |
| `data-components` | Typed item component map and codecs | Portable core |
| `world` | Chunks, sections, light, block entities, entities | Portable core |
| `simulation` | Input, movement, collision, prediction, interaction | Portable core |
| `resources` | Pack loading, model baking, atlases, language and sound indexes | Worker plus OPFS |
| `mesher` | Block model evaluation, culling, AO, mesh generation | Worker pool/Wasm |
| `renderer` | GPU resources, passes, visibility, entities, UI composition | WebGPU; WebGL2 fallback |
| `audio` | Positional sounds, music, streaming and mixing | WebAudio |
| `platform-web` | Input, clipboard, pointer lock, storage, fetch, lifecycle | TypeScript |
| `bridge` | Authenticated byte tunnel from web transport to TCP | Small audited server |

No game-domain module should call browser APIs directly. Browser facilities
must sit behind narrow interfaces so protocol and simulation tests can run on
the JVM or natively.

## Runtime strategy

### Option A: TeaVM/WasmGC source port

TeaVM is the strongest current Java-path candidate. Its current releases
support modern Java bytecode, WasmGC, browser interop, reflection
configuration, class substitution, direct NIO buffers, coroutine-style thread
support, and shared buffers.

It does not supply desktop Java's operating system, native libraries, OpenGL,
OpenAL, GLFW, arbitrary sockets, or unrestricted dynamic class loading.
Minecraft and its dependencies would still need extensive substitutions:

- all LWJGL surfaces;
- Netty transports and event-loop assumptions;
- filesystem and watch-service behavior;
- native compression and memory paths;
- thread and synchronization behavior;
- reflection/service-loader reachability metadata;
- auth and HTTP integration;
- clipboard, windowing, crash-report, telemetry, and system inspection;
- shader and render-state translation.

This route maximizes code and behavioral reuse but also carries the largest
legal and build-system burden. It is suitable for a four-to-six-week internal
spike.

### Option B: browser JVM

A browser JVM can improve Java class-library compatibility, but it does not
make LWJGL, raw TCP, native libraries, or licensing disappear. At assessment
time the public documentation of the available browser JVMs described
compatibility only through Java 17, while 1.21.11 requires Java 21. Even with Java 21 support, the native
graphics and networking surfaces would still need replacements.

Use this only if a vendor demonstrates the unmodified Java 21 client reaching
the first LWJGL window creation call and offers acceptable redistribution and
commercial terms.

### Option C: clean-room Rust/Wasm client

This has the best engineering control and clearest distribution story. Rust is
well suited to binary codecs, compact world structures, worker-safe data, and
Wasm. It gives up direct reuse of Mojang implementation code but can reproduce
observable 1.21.11 behavior through protocol captures, generated reports,
resource definitions, and differential tests.

This is the recommended production route.

### Recommended hybrid

Use a clean-room implementation for the shipped client, while maintaining a
JVM reference harness that:

- loads official mappings and generated reports locally;
- records packet and registry fixtures from a controlled server;
- runs movement, codec, model-baking, and component test vectors;
- compares browser output with the official client/server behavior.

If the TeaVM spike proves a genuinely portable subset—packet codecs, registry
logic, selected math, or model parsing—reuse only code that the project's legal
review permits.

## Graphics

WebGPU is a good architectural match for modern chunk rendering: persistent
GPU buffers, explicit pipelines, indirect drawing where available, compute
work, and lower per-draw CPU overhead. It is still not universally available,
so production support needs one of:

- a WebGL2 renderer with a reduced feature set; or
- a clearly declared WebGPU-only browser support policy.

Do not emulate OpenGL call by call. Minecraft's desktop renderer contains
OpenGL-specific state and assumptions; reproducing that API would preserve its
least portable layer. Instead:

1. retain high-level render phases and material semantics;
2. bake resource-pack models into browser-native vertex/index buffers;
3. translate supported core shader intent into WGSL and GLSL ES;
4. use texture arrays/atlases within adapter limits;
5. degrade optional effects based on queried GPU limits.

Initial renderer scope:

- opaque, cutout, and translucent block layers;
- block and sky lighting;
- fog, sky, clouds, weather, particles;
- block entities needed for common gameplay;
- entity models, held items, breaking overlay, selection outline;
- HUD, chat, inventory, and basic screens.

Defer shader packs and arbitrary resource-pack core shaders. They need a
separate compatibility and security design.

## Networking and authentication

Browser WebSocket deliberately does not expose raw network access. WebTransport
also connects to a WebTransport server, not an arbitrary Minecraft TCP socket.
A bridge is mandatory for ordinary Java servers.

The bridge should be a byte tunnel:

```text
browser framing -> authenticated tunnel -> TCP stream -> Minecraft server
```

It must not decode or translate ordinary game packets. This keeps the client
responsible for the real 1.21.11 protocol and allows encrypted online-mode
traffic to remain opaque after the Minecraft encryption handshake.

Bridge requirements:

- TLS and origin checks;
- authenticated users;
- explicit destination policy or server-issued connection tickets;
- DNS rebinding and private-address protection;
- connection, bandwidth, and frame-size limits;
- backpressure and bounded buffers;
- idle and handshake timeouts;
- no open-proxy behavior;
- abuse logging that does not capture access tokens or decrypted game traffic.

Start with WebSocket. Minecraft uses an ordered reliable byte stream, so
WebTransport's datagrams do not improve protocol semantics. Add a
WebTransport bidirectional stream later if measurements show a latency or
backpressure benefit.

For Microsoft sign-in, a browser application should use authorization code with
PKCE. Minecraft/Xbox token exchange, entitlement verification, profile lookup,
and session joining require a separate, carefully reviewed design. Do not place
a client secret in the browser and do not send reusable Microsoft credentials
to a generic network bridge.

Two reasonable deployments are:

1. a first-party backend-for-frontend holds short-lived server-side session
   state and returns narrowly scoped connection tickets; or
2. the SPA stores short-lived public-client tokens in memory and calls only
   endpoints that explicitly support the browser origin.

Authentication feasibility must be proven against the actual Minecraft
services and their allowed app-registration model before calling online-mode
support complete.

## Assets and storage

The 1.21.11 asset set is roughly 446 MB before browser caches and generated
atlases. The client must not eagerly fetch and expand everything on first load.

Use:

- a service worker for immutable, hash-addressed downloads;
- OPFS for packs, generated atlases, settings, server data, and caches;
- content hashes as cache keys;
- resumable/range downloads where supported;
- a small bootstrap pack for menus and progress UI;
- lazy sound and music acquisition;
- an explicit cache-size UI and eviction policy;
- `navigator.storage.persist()` as an optimization, never an assumption.

Official assets and code must not be committed to or served as part of this
repository. A production acquisition flow should verify ownership/entitlement
and fetch licensed content for the user. Whether direct CDN acquisition,
user-imported local files, or a patch/build flow is acceptable needs legal
review.

## Legal distribution constraint

This is an engineering assessment, not legal advice.

Minecraft's EULA distinguishes original mods from modded versions of the game
and says modded client/server software may not be distributed. The Usage
Guidelines also say not to redistribute the games, alterations, or game files.
A compiled WebAssembly transformation of substantial official client code is
very likely to be treated as a modified game client rather than an independent
mod.

Accordingly:

- do not commit decompiled/remapped source, client jars, assets, or generated
  browser bundles containing them;
- keep research tooling artifact-free;
- prefer clean-room implementation and independently written tests;
- require a legal review before public distribution, authentication rollout,
  branding, monetization, or automated asset acquisition;
- include the required unofficial-product disclaimer if the project becomes
  public.

“Users build it themselves from their own jar” may reduce redistribution risk
but does not automatically resolve every license or circumvention issue.

## Performance model

The first desktop-class target should be:

- Chromium/Firefox desktop on x86-64 or arm64;
- 8–12 chunk render distance;
- 60 Hz at 1080p on a midrange integrated GPU;
- under 1.0 GB total tab memory in an ordinary multiplayer scene;
- bounded chunk and resource caches;
- no required mobile support.

Important constraints:

- Wasm and browser memory pressure can terminate a tab rather than produce a
  recoverable Java heap error.
- GPU limits vary and must be queried.
- Workers reduce main-thread stalls but copying large meshes can erase the
  gain; use transferable buffers first and shared buffers when justified.
- Java object-heavy world structures are likely too expensive if ported
  literally. Compact arrays and palette-aware storage are preferable.
- Transparent block sorting, model baking, light updates, texture upload, and
  chunk churn are more likely bottlenecks than raw triangle throughput.

Measure frame time, tick time, mesh queue latency, bytes per loaded chunk, GPU
buffer use, GC pauses, network backlog, and cache size from the first vertical
slice.

## Delivery plan and gates

### Phase 0: proof of feasibility — 4 to 6 weeks

Build throwaway spikes, not product scaffolding:

- parse the official 1.21.11 metadata and generated packet reports;
- implement status ping through a WebSocket-to-TCP bridge;
- complete offline-mode login to a controlled server;
- receive configuration registries and one chunk;
- render that chunk using resource-pack block models;
- test TeaVM 0.15/WasmGC against selected Java 21 codec and registry classes;
- establish the asset and legal review path.

Go only if:

- packet framing/compression sustains live chunk traffic;
- a real chunk can be decoded without hard-coded old registries;
- the bridge is secure enough not to be an open relay;
- the renderer holds 60 Hz in the target scene;
- there is a legally supportable distribution model.

### Phase 1: multiplayer vertical slice — 3 to 5 months

- configuration protocol and dynamic registries;
- chunks, lighting, block updates, basic entities;
- movement, collision, teleport and keepalive;
- block break/place/use;
- hotbar, inventory, health, food, chat;
- essential sounds and particles;
- online-mode authentication on controlled infrastructure;
- reconnect, disconnect, resource-pack, and cache UX.

Acceptance test: join an unmodified 1.21.11 survival server, walk several
kilometers, mine, craft, fight a basic mob, die, respawn, and reconnect.

### Phase 2: basic playable alpha — additional 6 to 12 months

- broad entity and block-entity coverage;
- containers, recipes, advancements, effects, attributes;
- robust resource packs and language support;
- WebGL2 fallback or firm WebGPU support matrix;
- performance, memory, accessibility, and browser lifecycle hardening;
- conformance suite across vanilla and selected server implementations.

### Phase 3: parity expansion — 1 to 3 additional years

Realms, broad settings/UI parity, edge-case rendering, signed-chat/reporting
behavior, controller/touch, extensive resource-pack compatibility, and ongoing
protocol maintenance.

### Team estimate

A credible vertical slice needs roughly three to five experienced engineers:

- protocol/gameplay;
- rendering/meshing;
- web runtime/platform;
- bridge/auth/backend;
- test infrastructure, with roles overlapping.

A stable basic-play alpha is plausibly 40–90 engineer-months. Near desktop
parity is a larger, continuing product, not a one-off port.

## Highest risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Distribution of transformed Mojang code/assets | Critical | Clean-room client, user entitlement, legal review |
| Java closed-world/native incompatibility | Critical for source-port route | Time-box TeaVM spike; keep production architecture language-neutral |
| Online-mode auth and service policy | High | Prove early with an approved app registration and backend design |
| Secure arbitrary-server TCP bridge | High | Tickets/allowlists, SSRF controls, quotas, audited implementation |
| Resource/model/shader compatibility | High | Versioned pack pipeline; define unsupported shader behavior |
| Memory use and browser tab eviction | High | Compact data, bounded caches, instrumentation, desktop-first target |
| Exact movement and interaction behavior | High | Differential tests and packet replay against vanilla |
| Protocol churn | Medium | Generated codecs/fixtures and strict version modules |
| WebGPU/browser variability | Medium | Capability tiers and WebGL2 fallback or explicit support policy |

## Recommendation

Proceed, but name the project accurately: it is a modern, clean-room,
1.21.11-compatible browser client, informed by the Java client—not a quick
Wasm recompilation.

The immediate next milestone should be a six-week vertical feasibility spike
with three outputs:

1. live status/login/configuration/chunk traffic through a secure bridge;
2. one correctly model-baked and lit chunk rendered in WebGPU;
3. a written decision on TeaVM reuse and a legally reviewed asset/auth model.

Do not start with menus, account polish, an OpenGL emulation layer, or a broad
class-by-class port. The protocol-to-registry-to-chunk-to-render path is the
project's load-bearing beam.

## Sources

Primary and authoritative sources used for this assessment:

- [Minecraft Java Edition 1.21.11 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11)
- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Minecraft EULA](https://www.minecraft.net/en-us/eula)
- [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)
- [WebSocket Standard](https://websockets.spec.whatwg.org/)
- [WebTransport specification](https://www.w3.org/TR/webtransport/)
- [WebGPU specification](https://www.w3.org/TR/webgpu/)
- [WebGPU API notes and support status](https://developer.mozilla.org/en-US/docs/Web/API/WebGPU_API)
- [Origin private file system](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API/Origin_private_file_system)
- [SharedArrayBuffer and Wasm shared-memory requirements](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer)
- [Microsoft OAuth authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)
- [TeaVM releases](https://github.com/konsoletyper/teavm/releases)

Measurements attributed to “local inspection” were produced from Mojang's
version metadata, client jar, asset index, and official client mappings. Those
artifacts were kept outside the repository.

