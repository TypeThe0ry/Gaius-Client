# Relay protocol dependency inventory (phase 1)

Scope: the complete `apps/bridge` tree, plus its previous protocol-id tool and
profile/CI inputs. This inventory records **both ids and field layouts**; moving
ids into JSON does not make arbitrary packet serializers version-independent.
The deployed relay is checked-in Node ESM JavaScript under `dist/`, not a
TypeScript build. No browser JavaScript-target build output changes here.

## Production relay

| Place | Protocol dependency and treatment |
| --- | --- |
| `dist/protocol.js` | Former handwritten LOGIN/CONFIGURATION/PLAY tables and numeric lookup. Now reads `protocols/<protocolVersion>.json` once, validates and freezes them, and uses `resolveMinecraftProfile`. Unsupported/unknown numbers remain undefined; there is no nearest-version fallback. |
| `dist/protocol.js: decodeClientboundLoginDistances` | PLAY-login prefix: four-byte player id, hardcore boolean, VarInt level count, length-prefixed level names, VarInt maximum players, chunk radius and simulation distance. This is not LOGIN Hello. It remains the existing reviewed layout; reports do not describe fields. Used by smoke clients, not by the relay to rewrite login payloads. |
| `dist/protocol-schema.js` | Semantic keys map to vanilla report state, direction and resource-location names. Contains schema/layout contract constants, not Minecraft-version switches. Checks directional uniqueness, complete key sets and provenance. The current inspector handles only one-byte packet ids; larger ids decline. |
| `dist/main.js: inspectMinecraftHandshake, parseMinecraftHandshake, isDefinitelyNotMinecraftHandshake` | Pre-negotiation bootstrap: length VarInt, packet id zero, protocol VarInt, VarInt UTF-8 host length, two-byte port and STATUS/LOGIN next-state values. No profile is known until these bytes are read. The bootstrap signature stays conservative; unfamiliar/malformed handshakes tunnel opaquely. |
| `dist/main.js: readMinecraftFrame, decodeMinecraftVarInt, encodeMinecraftVarInt, minecraftPacketId, isPayloadlessPacket` | VarInt outer framing, optional zero uncompressed-data-length prefix and single-byte ids; exact empty bodies are required for acknowledgement/tick recognition. These common framing assumptions are part of the audited layout, not report-derived fields. |
| `dist/main.js: proxyVanillaKeepAlive` | Selects clientbound/serverbound keepalive ids by phase and negotiated table. Rewrites only the exact eleven-byte zero-compressed frame carrying an unchanged eight-byte keepalive value. |
| `dist/main.js: isLoginEncryptionRequest and client/server drain branches` | Recognises the table's clientbound LOGIN encryption-request id only in LOGIN; switches to opaque encrypted transport after the client response. The relay does not decode Hello name/UUID, public keys or secret fields. |
| `dist/main.js: client frame drain, high-water phase recovery, synthetic tick scheduling` | Uses serverbound LOGIN acknowledgement, configuration finish, PLAY configuration acknowledgement and client tick-end ids for state changes and exact saved-tick replay. Unknown protocols receive neither guessed acknowledgements nor synthetic ticks. |
| `dist/main.js: server frame drain and chunk batch admission` | Recognises PLAY start-configuration, chunk-batch start/finish and phase-specific keepalive ids; phase transitions suppress PLAY ticks during reconfiguration. Payloads not required for these decisions stay opaque. |
| `dist/main.js: traceCustomPayload` | Uses direction-specific PLAY custom-payload ids. Its diagnostic preview assumes a small uncompressed channel string with a one-byte length and previews at most 192 bytes. It does not rewrite the payload or claim to decode compressed/encrypted traffic. |
| `dist/main.js: health/manifest protocol metadata` | Advertises the actual loaded profile numbers and selected configuration/play ids, rather than a separate support list. |
| `protocols/*.json` | Per-numeric-protocol data: 7 LOGIN, 15 CONFIGURATION and 19 PLAY ids; `loginLayoutVersion`; actual jar hash and canonical packet-report hash. No executable code, jar paths or timestamps. |

The unchanged opaque tunnel boundary is the fallback for unknown protocols,
unsupported table layouts, unfamiliar framing and encryption. Phase-1 inspection
is not a complete Minecraft packet decoder. In particular, packet reports
supply numeric registration ids, **not** field order, widths or optionality.
`port/versions/*.json` explicitly declares `relayProtocol.loginLayoutVersion`;
declaring layout 1 requires a serializer review, not merely successful id lookup.

## Smoke and acceptance consumers

| Place | Id/layout dependency |
| --- | --- |
| `multiplayer-smoke.mjs` | Selects the relay profile by name/number or the version manifest. Constructs handshake, Hello (name/UUID), login key/acknowledgement, configuration client information, known-pack selection, ping/keepalive replies, resource-pack statuses and PLAY acknowledgements/ticks. Parses compression, login completion/disconnect/encryption, configuration resource-pack URL/hash/UUID/forced/prompt, dialog network NBT, code of conduct, PLAY-login distance prefix, chunk batches, cache center/radius and simulation-distance packets. Uses table ids; the field codecs remain audited smoke code. |
| `public-relay-smoke.mjs` | Selects loaded tables; constructs handshake/status request/login Hello and interprets status response framing plus phase keepalives/ticks. STATUS request/response id-zero framing is a fixed bootstrap fixture, not a per-version relay rewrite. |
| `profile-protocol-smoke.mjs` | Original three-profile regression expectations and handshake/login/configuration/PLAY mock transcripts; checks unknown-protocol pass-through and profile transitions. Relay aliases now resolve by protocol instead of named relay exports. Shared browser constants, pinned world/pack metadata and historical expectations deliberately remain separate. |
| `encryption-request-smoke.mjs` | Iterates loaded relay tables; LOGIN encryption-request id and Hello field fixture, zero-compressed framing, malformed VarInts, arbitrary encrypted bytes and PLAY id-one counterexample. These require phase-aware detection, not just matching a payload byte. |
| `client-tcp-bounded-drain-smoke.mjs` | Pure framing/drain model with its original three-protocol support set and independent high-water acknowledgement/tick id snapshot. `checkProfileIdsMatchRelay` binds those historical ids to the loaded tables. This older model is not the production support registry. |
| `browser-full-path-smoke.mjs` | Loaded relay profile selected from the build manifest. Full fake-client codecs cover handshake/Hello, encryption/compression, LOGIN acknowledgement, configuration client information/known packs/pack statuses/ping/pong/dialog actions/code of conduct, PLAY login/chunk batches/window/distances/player-loaded/tick/configuration acknowledgement and keepalive payloads. Network NBT/dialog codecs and packet-length parsing are explicit field-layout assumptions. The pinned periodic-server-sync classifier still contains experiment-specific profile/protocol/packet ids. |
| `browser-full-path-contract-smoke.mjs` | Source contracts, original chunk-window ids and PLAY-login prefix tests, pinned acceptance/stress profile metadata and packet telemetry fixtures. Id expectations now read the independent baseline JSON rather than regexes against handwritten exports. The wider script also creates temporary manifest files and fake Java tools; it is not just the socket-free table unit test. |
| `browser-full-path-acceptance.mjs` | Explicit two-profile acceptance order, protocol/world/build metadata and reported layout/packet telemetry. This is a pinned experimental gate, not production protocol selection. |
| `browser-full-path-stress.mjs` | Pinned profile/protocol, PLAY-login id/layout and periodic-server-sync id/nominal timing in stress fixture and telemetry assertions. These acceptance-specific expected values are not a generic packet registry. |
| `browser-poll-scheduler-smoke.mjs` | Evaluates browser/full-path code and checks periodic-sync classification with positive/negative profile/protocol/packet-id samples. Depends on that classifier's pinned experiment, not relay rewrites. |
| `browser-full-path-artifact-contract-smoke.mjs` | Matches built-client, native/build manifest and nested profile protocol metadata; delegates wire exercise to the full-path smoke. No independent per-state relay id table. |
| `offline-server-smoke.mjs` and `online-mode-server-smoke.mjs` | Select vanilla profile/server jar/Java and delegate packet interpretation to `multiplayer-smoke.mjs`. Offline versus encrypted-login acceptance is explicitly separated. |
| `vanilla-server.mjs` | Reads profile id/protocol/Java/official jar hashes, resolves local build/server paths and provides server smoke setup. Its server-start/download paths are **not** used by the new report generator. |
| `protocol-tables.test.mjs` and `test-fixtures/protocol-tables.json` | New socket-free baseline parity, runtime lookup, fail-closed validation, isolated generator/CI checks and real relay helper tests. The fixture is an independent snapshot of pre-migration relay values, not generator output. |

The phase-1 change does not rewrite or expand the older browser/stress acceptance
contracts or their field codecs. A future layout change needs a separate review.
The new loader/generator contain no Minecraft-version literals. Historical
fixtures and opt-in experiments do not decide runtime support.

## Generator, packaging and non-Minecraft exclusions

- `scripts/gen-protocol-ids.mjs` replaces the old compare-only tool: selects
  `port/versions/*.json`, pins jar bytes, invokes vanilla reports sequentially
  with `-Xmx3g`, resolves semantic names and writes canonical numeric tables.
  `tools/gen-protocol-ids.mjs` is the compatibility entrypoint.
- `Dockerfile` must include `protocols/` along with `dist/`. Direct installation
  already copies the whole bridge directory; rollback documentation now keeps
  matching tables with matching code. `GAIUS_BRIDGE_PROTOCOL_DIRECTORY` changes
  the startup source; it is not a hot-reload or deploy command.
- `package.json` and `.github/workflows/repository-guard.yml` run the socket-free
  tests and exact-byte regeneration check. Missing local jars produce explicit
  skips only after table/manifest validation. Hash/report/Java/drift failures
  cannot become skips. No jar is downloaded by this check.
- `dist/config.js`, `dist/policy.js`, `dist/registry.js`, `registry-smoke.mjs`,
  `browser-arrival-timeline-runtime-smoke.mjs` and the origin proxy use RelayNode
  envelope/manifest/registration schema versions; their `protocolVersion: 1`
  does **not** denote a Minecraft protocol number.
- `dist/framed-stream.js`, `framed-stream-smoke.mjs` and
  `relay-frame-batching-smoke.mjs` depend on Minecraft outer VarInt framing and
  byte ownership, not per-version packet ids or payload fields.
  `server-packet-processor-isolation-smoke.mjs` checks processor ownership and
  phase-recovery source boundaries without a separate packet registry.
- `arrival-timeline-smoke.mjs` retains arbitrary packet-id telemetry examples;
  `relay-frame-timeline-smoke.mjs`, `remote-session-drain-smoke.mjs`,
  `browser-empty-frame-budget-smoke.mjs` and `blob-arrival-order-smoke.mjs`
  exercise transport/arrival ordering rather than Minecraft field layouts.
- `resource-pack-timeout-smoke.mjs` and `resource-pack-stream-smoke.mjs` test
  HTTP resource bytes/timing, not the Minecraft resource-pack packet codec.
  `lan-skin-smoke.mjs` tests session/skin relay behavior without a packet-id table.
- Browser/shared constants in `packages/protocol/dist/constants.js` and
  `port/web/smoke/singleplayer-worker-smoke.js` are outside this relay phase;
  they and all browser build artifacts remain unchanged.

## Source evidence and remaining gate

| Profile / protocol | Local report source | State |
| --- | --- | --- |
| 1.21.11 / 774 | Shared `port/work/1.21.11/client-original.jar` and cached libraries, verified against `official.clientSha1` | Explicit client-equivalent reports; no local pinned server jar found. Server-source verification remains open. |
| 26.2 / 776 | `<repo-root>/port/target/26.2/multiplayer-smoke-server/server.jar`, SHA-1 `823e2250d24b3ddac457a60c92a6a941943fcd6a` | Official server reports; exact-byte regeneration passed. |
| 26.3 / 777 | `<repo-root>/port/target/26.3/multiplayer-smoke-server/server.jar`, SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c` | Official server reports; exact-byte regeneration passed. |

Read-only bounded searches of the known work/build/artifact directories and
local Minecraft caches did not find the pinned 1.21.11 server jar
(`64bb6d763bed0a9f1d632ec347938594144943ed`). No download was attempted. Source
jars are read-only; copies, bundler extraction, reports and logs are retained
only under the `--out` scratch directory. There is no automatic client
fallback. Its explicit source kind prevents CI from pretending it verified an
unavailable server jar.

All 123 selected ids match the pre-migration relay baseline. A separate local
26.2 client/server comparison also matched the complete canonical packet-report
hash and all 41 selected ids; it does not close the legacy server-source gate.
When that exact legacy server jar becomes available, use the normal server
route to replace client provenance, review the ids, then run the strict check.
No relay deployment, server connection, browser session or full TeaVM build was
performed for this task.
