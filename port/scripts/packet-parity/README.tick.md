# Tick-exact packet acquisition and replay

## Gate status

This is an opt-in M2 acquisition method. JVM self-parity is its first gate: two
independent JVM captures of one profile script must compare with zero unexplained
differences under the strict comparator. That gate passes for all three selected
profiles (see "Paired JVM results"). The browser capture is a separate, still open
gate. The WasmGC Worker now has a settle probe with the JVM host's schedule (see
"WasmGC settle probe"); its logic is unit-tested on the JVM against stubs, but no
debug WasmGC server has been built or captured yet (see "WasmGC build and capture
handoff"). Node tests validate the comparator, capture page and probe-report
contract only. They are not vanilla or Worker execution evidence.

The previous 95,442 packet rows remain unproven. This method does not relabel them
or loosen the existing logical-comparator gate.

## Implementation and default-off behavior

- `TickPacketParityPatches` (`port/tools`, `patches/telemetry`) instruments vanilla `MinecraftServer.tickServer`,
  outgoing `Connection.send`, serverbound application handlers after the vanilla
  thread barrier, `ChunkHolder.broadcastChanges` group boundaries, and `Entity`
  allocation/identity updates.
- `TickPacketRecorder`, `TickParitySettle`, `TickParityClient` and
  `TickParityScenario` are shared by the JVM harness and WasmGC Worker. Inputs pass
  the vanilla PLAY codec and packet handler on the server thread, before the
  original tick body increments its counter. No timer chooses an input tick.
- `tickParity` in the selected `port/versions/*.json` defines seed, spawn,
  distances, setup commands, movements, the settle strategy and the reviewed
  normalization rules with their codec layout facts. Protocol packet IDs and command
  argument types come from that profile's vanilla registries, not version literals
  in the driver.
- The optional patchkit-v2 registration is `TELEMETRY wasmgc`
  (`MinecraftClientPatcher.patchTickPacketParity`): the recorder hooks above, the
  Worker bridge and the settle probe's executor schedule (see "WasmGC settle probe").
  It requires `GAIUS_DEBUG_PACKET_PARITY=1` or the patcher JVM property
  `gaius.debug.packetParity=true`. With neither flag, no core hook is written.
  JS skips the registration regardless of the flag.
- A debug Worker additionally requires `__gaiusTickParityDebug === true` and
  `__gaiusTickParityScript` containing the complete JSON script before bootstrap.
  `BrowserTickPacketParity.properties(Properties)` applies the scenario before
  vanilla reads its settings; `prepare(MinecraftServer)` runs at `loadLevel`
  completion. Debug-only PlayerList guards suppress later distance ramps.
- In an explicitly instrumented debug binary with the runtime flag off, hook
  guards do not alter gameplay/settings. Guard/class-initialization overhead is
  not a claim of byte-identical runtime execution. Default production binaries
  have no new hooks.

G1 source delta: one WasmGC-only TELEMETRY registration, its classification row,
and one JS skip-allowlist row. The JavaScript-target overlay products are unchanged
(strict G1 comparison of rebuilt JS overlays; see "Local checks" and the report).
The JVM executor schedule below is written only by `TickPacketParityPatches.main`
(the JVM harness instrument step); the browser schedule only by
`TickPacketParityPatches.applyBrowserSchedule` from the WasmGC registration (see
"WasmGC settle probe").

## Deterministic execution (settle)

Vanilla runs worldgen, lighting and entity deserialization on a background
executor and storage IO on an IO pool. Without control, two JVM runs of one script
differ in which tick a chunk becomes ready, which worker allocates an entity id,
and even in chunk content: cross-chunk feature placement depends on the order in
which neighbouring chunks run FEATURES (section palette order, and whether a
mineshaft corridor finds air for its chest minecart). The capture therefore
serializes all asynchronous work at exact tick boundaries.

- `TickPacketRecorder.settle(phase)` drains the work on the server thread with
  vanilla's own chunk main-thread executor (`ServerChunkCache.pollTask`, which also
  applies ticket updates and schedules light work). A main-thread task runs only
  when the background executor is idle and held; the IO it starts completes before
  the background thread is released. The background thread is held whenever the
  server thread runs vanilla code outside a settle, so it never runs concurrently
  with the tick body, the scripted inputs or a main-thread task.
- Settles run once per preload chunk at tick zero (`preload`), after the scripted
  inputs of every tick (`tick-start`) and after the vanilla tick body
  (`tick-end`). Each is bracketed by `settle-start`/`settle-end` rows. Background
  entity allocations are recorded inside a settle window with `worker: true`; one
  outside a window keeps `tick=-1` and fails validation.
- The preload makes vanilla's FULL request (`getChunkFuture`) from a short-lived
  poster thread so the request is queued on the chunk executor, then settles it.
- No vanilla system property gives a deterministic executor: `max.bg.threads`
  only caps vanilla's ForkJoinPool (`Util.maxAllowedExecutorThreads` clamps to at
  least one worker). A ForkJoinPool worker picks among non-empty submission queues
  from a per-process random origin, and vanilla blocks a worker on its own
  submission (`IOWorker.isOldChunkAround` joins a `supplyAsync` on the background
  executor from a generation step), which makes the pool start a concurrent spare.
  The JVM harness therefore replaces the bodies of `Util.makeExecutor` and
  `Util.makeIoExecutor` in its own instrumented copy with
  `TickParityExecutors`: one background thread draining one FIFO queue (the shape
  of the WasmGC Worker's executor), self-submitted background tasks run at once on
  that thread, and IO on the calling thread (`MoreExecutors.newDirectExecutorService`,
  vanilla's own zero-thread choice). Every vanilla task is unchanged; only its legal
  execution order is fixed. Nothing of this reaches the browser registration.
- Setup command `save-off` (vanilla, permission 4) keeps chunks from being unloaded
  and reloaded during the 40 ticks. `ChunkMap.processUnloads` depends on the
  wall-clock `haveTime` supplier and a reloaded proto chunk repacks its section
  palettes, so unload timing would otherwise reach chunk packets.
- The scripted peer ends every tick in which it sends input with `client_tick_end`,
  as a vanilla client does. 26.3 rejects a second position packet before the
  per-tick reset (`receivedPositionThisTick`); without it the tick-8 move
  disconnected the peer with `invalid_player_movement` in every earlier capture.

## WasmGC settle probe

The WasmGC Worker runs every Java thread as a JSPI activation on one JavaScript
thread, and its executors (TeaVM's `TScheduledThreadPoolExecutor` behind vanilla's
`ForkJoinPool` and `newCachedThreadPool`) run each task at once on the submitting
thread. Generation therefore runs depth first inside whatever submitted it, an
order unlike the JVM capture's. In a packet-parity debug build, and only when the
Worker asks for a capture, the settle probe gives the Worker the JVM host's schedule:

- `TickPacketParityPatches.applyBrowserSchedule` (in the registration above) prepends
  to vanilla `Util.makeExecutor`/`makeIoExecutor` a call to
  `BrowserTickParityExecutors.background`/`io`. Unless `__gaiusTickParityDebug === true`
  they return null and vanilla's body runs unchanged. It also passes the name argument
  of every `AbstractConsecutiveExecutor` constructor through
  `BrowserTickParityExecutors.consecutiveName`, and routes four Worker paths that
  `MinecraftClientPatcher` wrote earlier through the bridge (see "Vanilla paths during
  a recording" below). All hook points are vanilla methods or calls that no later
  patcher rewrites; `parity-hooks.py` checks them in the final overlay.
- Background (`TickParitySerialExecutor`): until `BrowserTickPacketParity.prepare`
  (vanilla `loadLevel` has returned) it runs tasks on the submitting thread exactly as
  the Worker executor does, because server start-up joins futures that TeaVM's
  `CompletableFuture` cannot block on. From there on it has TickParityExecutors'
  schedule: one background thread (`Worker-Main-1`) drains one FIFO queue; a task it
  submits to itself runs at once, depth first (nesting deeper than 256 fails the
  capture instead of the Wasm stack); the pending count covers queued and running
  tasks and the hold; a hold parks the thread in a task of its own and voids the
  capture after 60 s. Every wait is a monitor wait, never a spin, because all
  threads share one JavaScript thread. At the end of the recording new tasks run on
  their submitting thread again, so vanilla's shutdown is not blocked.
- IO (`TickParityDirectExecutor`): every IO executor runs its task on the calling
  thread (the Worker's own behaviour and the JVM harness's choice), so IO is complete
  when `execute` returns. A shared gauge counts tasks still on a suspended thread's
  stack; it is the probe's `ioIdle`, and the settle releases a hold only after it.
- Worldgen dispatcher: `MinecraftServerWorkerPatcher.classOverrides` names it
  `worldgen-dispatcher`, and its patched `run()` defers that executor's re-registration
  through a new host activation (a pump) that no executor sees, so the next turn queues
  behind other background work. In a Worker that requested a capture,
  `consecutiveName` keeps vanilla's name `dispatcher`, and the patched `run()` takes the
  path of every other consecutive executor: one task per run, re-registered at once on
  the running thread as a loop (the Worker patcher's trampoline), which under the FIFO
  executor is the order of vanilla's inline re-registration. The rename holds for the
  whole session of such a Worker, so its start-up drains worldgen inline as well.
- Kernels are off for the capture. The server-side Rust worldgen and light kernels
  deliver results through the page's kernel runtime and install them from new Java
  threads in the order the kernel workers finish (worldgen through `supplyAsync` on
  the background executor, light through later light-engine tasks; a failed job falls
  back to vanilla later, on the kernel thread; the 26.2 surface step turns from
  synchronous to asynchronous). Reordering their results into the Java FIFO order
  would add a scheduling layer that production does not have, and the comparison
  would then judge that layer. With the kernels off every chunk runs vanilla's Java
  generation in vanilla's place, which is what the JVM capture runs; kernel
  bit-exactness is the golden-fixture gate (`port/native/fixtures`), not this one, and
  1.21.11 keeps vanilla worldgen on the server (only its light kernel is applied). The capture page posts no
  `gaius-kernel-port` message and sets `__gaiusWorldgenKernelConfig.enabled=false`,
  `__gaiusLightKernelConfig.enabled=false` and `GAIUS_LIGHT_KERNEL=false` before the
  Worker bootstrap; `capture-plan.json` and `capture.json` record this as `kernels`.
  The probe refuses to start when a kernel runtime, client, facade or mirror exists
  in the Worker, fails the capture when one appears or the kernel job count moves,
  and counts kernel jobs in flight (client `submitted - completed - failed`, facade
  `pending()`) as open work in `idle()`.
- Vanilla paths during a recording (`TickPacketRecorder.enabled`): the Worker fast
  path at the head of `handleMovePlayer` (`applyWorkerMovement`, which returns before
  vanilla's thread barrier, so scripted moves would never reach the parity input hook),
  its deferred same-section chunk-tracking walk in place of `ServerChunkCache.move`, the
  nearest-first choice in `ChunkTaskPriorityQueue.pop`, and the four unacknowledged
  chunk batches a new `PlayerChunkSender` starts with all step aside for vanilla's
  code (`workerMovement`, `moveChunkTracking`, `chunkTaskKey`, `unacknowledgedBatches`).
  Outside a recording each forwards to the Worker's own path.
- Fail closed: the probe exists only when `Util.backgroundExecutor()` and
  `Util.ioPool()` are the parity executors (a hook that a later patcher lost leaves
  the first settle without a probe); a dispatcher pump hop during the capture
  (`__gaiusWorldgenStats.dispatcher.deferredHops` moved) means the dispatcher hook is
  missing; a missing probe, a broken schedule, a settle timeout or a refused hold
  emits a `failure` row before vanilla's crash handling runs.
- Reports: the Worker posts `tick-parity-probe` messages (`configured`, `started`,
  then `complete` or `failed`, with task, hold, nesting, IO and kernel counters)
  beside the event rows into `worker-control.jsonl`. The capture CLI requires them
  (`validateProbeReports`) and copies them into `capture.json` as `probe`; the compared
  `events.jsonl` keeps the JVM schema.

The cooperative worldgen machinery of the JavaScript build (the task-layer holder
cursor, the distance-manager update budget, chunk-broadcast pulses, deep checkpoints,
the packet slice that defers movement) is registered for the `js` target only and does
not exist in the WasmGC Worker. The yields the Worker keeps (one checkpoint per
server tick after `tickServer`, `BrowserFuturePump.joinFromWorker`, timed parks) only
suspend the running thread and reorder no vanilla work.

The probe does not control these differences; review each one individually when the
first browser capture is compared: TeaVM's `CompletableFuture` runs dependent actions
in registration order where the JDK pops them from a stack (most recent first), and
its `join()`/`get()` throw on an incomplete future instead of blocking; TeaVM classlib
hash and identity orders; the browser server's own start-up patches that run before
the capture starts (`prepareLevels` without vanilla's initial-chunk wait and ticket,
the replaced `setInitialSpawn`, the structure preload) and so may leave a different
state at tick zero; and `patchChunkGeneratorStructureStateBrowserFastRings` (PERF, both
targets), which skips vanilla's biome search for concentric-ring structure positions.

## Capture contract

JSONL rows have a contiguous global `sequence` and a recorder `tick`. Tick zero
contains setup receipts, preload settles and allocations. For tick N,
`tick-start.serverTick` must be N-1 and `tick-end.serverTick` must be N. These
values come from the actual vanilla tick counter, not a wall-clock estimate.

`prove` attempts both independent JVMs even if one fails. `attempts.json` records
process and capture validity separately. Invalid or incomplete captures produce
a failed comparison with `unexplained=null`, never a zero-difference receipt.
Teleport acknowledgement layouts come from `tickParity.teleportAcknowledgement`
in the selected profile and still pass the vanilla inbound codec. Unknown layouts
and unsupported relative position acknowledgements fail instead of assuming an
ID-only wire layout. Shared browser code has no JVM-harness dependency.

| Row | Evidence |
| --- | --- |
| `session` | Complete script; exact script hash is required between captures |
| `setup-command` | Ordered vanilla command callback, success and result |
| `settle-start`, `settle-end` | Phase (`preload`, `tick-start`, `tick-end`) and pending server tasks |
| `ready` | Observed seed, fixed respawn and view/simulation distances after all preload settles |
| `player-construct` | Scenario player id and constructor yaw bits, before vanilla places it |
| `player-spawn` | Actual player coordinates after vanilla teleport to the fixed block center on tick one |
| `tick-start`, `tick-end` | Contiguous tick boundaries and observed distances; the `tick-end` settle closes the tick |
| `inject` | Exact tick, connection, script action index or queued response |
| `input` | Vanilla handler application-entry boundary, connection input order |
| `packet` | PLAY codec bytes, packet name, tick and connection output order |
| `bundle-start`, `bundle-end` | Original bundle grouping around ordered children |
| `chunk-broadcast-start`, `chunk-broadcast-end` | One `ChunkHolder.broadcastChanges` call and its chunk |
| `entity`, `entity-identity` | Allocated ID, constructor UUID, type/dimension and identity updates |
| `complete` | Final tick and exact scripted-action count; missing footer fails |

Every injected packet must produce exactly one matching application-entry row.
An input row does not assert that vanilla accepted a movement or changed player
state: vanilla handler validation still runs. Packet bytes, entity IDs, setup
results and event order remain strict apart from the rules below. Unknown/live
inputs, off-server-thread tick events, gaps, late events, missing settles, work
after the closing settle, unterminated broadcast groups, a vanilla `disconnect`,
failures and truncated captures fail closed. Acquisition is bounded to 200,000 rows and 64 MiB.

The default scenario plans 40 ticks, view/simulation distances 2/2, seed `420040`,
respawn `(0,80,0)` and 49 preload requests (radius 3). Commands disable mob
spawning, random spawn radius, time/weather advancement and random block ticks,
set daytime and clear weather, and turn saving off. These are declared debug
scenario controls implemented through vanilla commands, not production defaults.
A normal vanilla teleport after join fixes the player's initial coordinates to
`(spawn.x + 0.5, spawn.y, spawn.z + 0.5)`; its observed receipt is mandatory.
Scripted packets target ticks 2, 4 and 8, each tick ending with `client_tick_end`.
Teleport, chunk-batch, keepalive and ping acknowledgements are queued and handled
at the next exact server tick.

Each JVM uses a new world directory and independent process, a default `-Xmx2g`,
`-XX:ActiveProcessorCount=2`, `-Dmax.bg.threads=1` and fixed locale. JVMs are
launched sequentially, never concurrently by this driver.

## Normalization rules

`tick-compare.mjs` applies only the rules a profile lists in
`tickParity.normalization.rules`; an unknown rule name fails. Each rule names the
vanilla source of the difference, decodes only its field with the profile's exact
layout, and leaves the row strict when that decode fails. A row is `explained`
only when a rule touched it and the normalized rows are equal; both original row
hashes stay in the ledger together with both sequences. There is no generic UUID,
clock, chunk-order or payload waiver and no dropped row.

| Rule | Vanilla source | Normalized field; everything else stays exact |
| --- | --- | --- |
| `entity-constructor-uuid` | `Entity.<init>` draws its UUID from `RandomSource.create()` (`RandomSupport.generateUniqueSeed`: seed uniquifier xor `System.nanoTime`) | Allocation-row UUIDs; identity rows and the `add_entity` UUID field compare as the recorded allocation of the same run that drew that UUID. Unknown UUIDs stay strict; a UUID drawn twice fails |
| `living-entity-constructor-yaw` | `LivingEntity.<init>` sets yRot from the same unseeded random | The `player-construct` yaw, and `player_position` / `accept_teleportation` yaw fields that carry exactly those bits |
| `recipe-property-set-order` | `RecipeManager` property sets: `Set.copyOf` of identity-hashed items in an immutable map | `update_recipes` property sets as a key-sorted map of item-id sets (duplicates fail) |
| `advancement-progress-order-and-grant-time` | `PlayerAdvancements.flushDirty` fills a HashMap from a set of records with identity-hashed members; `CriterionProgress.grant` stores `Instant.now()` | `update_advancements` progress entries sorted by id; obtained instants reduced to their presence. Additions use the profile's declared `advancementPositionBytes`; a display entry fails the decode |
| `heightmap-type-order` | `ClientboundLevelChunkPacketData` collects heightmaps with `Collectors.toMap` (HashMap keyed by an enum, identity hash) | Heightmap entries of `level_chunk_with_light` sorted by type id |
| `attribute-snapshot-order` | `AttributeMap.attributesToSync` is an `ObjectOpenHashSet` of `AttributeInstance` | `update_attributes` snapshots sorted by attribute id |
| `chunk-broadcast-holder-order` | `ServerChunkCache.chunkHoldersToBroadcast` is a `ReferenceOpenHashSet` of `ChunkHolder` | Adjacent recorded broadcast groups sorted by chunk; the run's connection output orders are reassigned in that order. Rows inside a group keep their order; a run with a repeated chunk stays as recorded |
| `game-rule-command-order` (1.21.11) | `GameRuleCommand.register` visits rules through a `Reference2ObjectOpenHashMap` keyed by `GameRule`; the packet numbers nodes breadth first | The command graph by node path; child order is ignored only below the listed `unorderedCommandChildren` paths. Parser properties come from the profile's argument-type registry; unknown property layouts fail |

## Paired JVM results

Receipts of the reviewed run, kept with the review evidence as `<evidence>/final/<profile>`
(2026-10-09, JDK 25.0.4, the commands below). Each pair is two independent JVM processes of the
same script; every capture is valid (40 ticks, 129 settles, no disconnect).

| Profile | Rows per capture | Packets | Worker allocations (in settles) | Explained | Unexplained |
| --- | --- | --- | --- | --- | --- |
| 26.3 | 815 | 185 | 15 of 28 | 41 | 0 |
| 26.2 | 817 | 185 | 15 of 28 | 317 | 0 |
| 1.21.11 | 853 | 201 | 15 of 28 | 39 | 0 |

Seven further independent JVM-vs-JVM comparisons (three 26.3, two 26.2, two
1.21.11, including one across two prove invocations) also have zero unexplained
rows. Which rules fire varies between pairs because identity-hash orders vary per
process; `game-rule-command-order` has not been needed by any deterministic
1.21.11 pair so far and rests on the earlier legacy capture evidence and its unit
tests.

## Offline JVM commands (PowerShell)

Run from this worktree. Use a fresh output directory per attempt. `prepare` only
hashes cached files and starts no JVM. `prove` checks fresh Windows commit
headroom before every compiler/runtime spawn and polls every 60 seconds. The default
minimum is 18 GiB; `GAIUS_JVM_MIN_HEADROOM_GIB` can select an explicitly approved
minimum of at least 8 GiB. `--java-heap 3g` selects a bounded 3 GiB heap for both
compilation and captures; the default remains 2 GiB. `--headroom-wait-ms 0`
waits indefinitely; a finite limit aborts without starting the next JVM.
The headroom check reads Windows performance counters through `powershell.exe`. If your
setup has a machine job runner that limits concurrent heavy jobs, run each JVM command
through it.

```powershell
# GAIUS_JAVA_HOME: a JDK that satisfies each profile's javaVersion (JDK 25 or newer covers all three).
$env:GAIUS_JAVA_HOME = '<jdk-home>'
$env:GAIUS_JVM_MIN_HEADROOM_GIB = '8'
$out = '<evidence>/packet-parity'
$profiles = (Get-ChildItem -LiteralPath port/versions -Filter '*.json').BaseName
foreach ($profile in $profiles) {
  node port/scripts/packet-parity/tick-parity.mjs prepare --profile $profile --out "$out/prepare/$profile"
  if ($LASTEXITCODE -ne 0) { throw "offline preparation failed: $profile" }
}
foreach ($profile in $profiles) {
  node port/scripts/packet-parity/tick-parity.mjs `
    prove --profile $profile --out "$out/jvm/$profile" --headroom-wait-ms 0
  if ($LASTEXITCODE -ne 0) { throw "JVM parity did not pass: $profile" }
}
```

Expected successful receipts: `commands.jsonl`, `headroom.jsonl`, compiler/runtime
logs, `protocol.json`, `instrumentation.json`, `script.json`, independent
`run-a/events.jsonl` and `run-b/events.jsonl`, `comparison.json` and `ledger.jsonl`.
The receipt hashes the vanilla source, harness sources, all seven patched core
classes (including the harness copy of `Util`), script and captures.
`comparison.json.passed` must be true and `unexplained` zero; `explainedByRule`
counts the explained rows per rule. Process startup is not success.

The offline source is the cached `client-named.jar` containing vanilla server
classes. The small existing headless ServerBase adapter and an in-process PLAY
peer are used; this is not a literal dedicated-server-jar launch, real Minecraft
client, TCP transport, login/configuration parity or full vanilla integrated
client/server session. No browser gameplay overlays enter the JVM classpath.
That narrower scope must not be presented as complete M2 coverage. The virtual
peer ignores send-completion callbacks, so a vanilla disconnect would not close it;
the validator therefore rejects any capture containing a `disconnect` packet.

## WasmGC build and capture handoff

These steps are heavy: a full TeaVM compile of the WasmGC server role per profile, then
headless Chrome. They build the debug server with outputs and overlays under the
evidence directory, read the resources of an existing JavaScript build read-only
(`GAIUS_WASMGC_RESOURCE_ROOT` / `GAIUS_WASMGC_RESOURCE_DIST`, see the header of
`build-wasmgc.sh`), keep Maven offline, check that every parity hook survived the later
patchers, capture with the JVM script of that profile's `prove` output (`$out/jvm/<profile>`
above) and compare against that pair's `run-a` capture. `GAIUS_TEAVM_MAX_HEAP` is the
TeaVM compiler heap (`build-wasmgc.sh` default 14g); set what your machine's commit
headroom allows, and a compile that does not fit is a resource blocker. Every other JVM
(overlay patchers, javac) is capped at 3 GiB by `JAVA_TOOL_OPTIONS`. If your setup has a
machine job runner that limits concurrent builds or browser runs, start the build and
capture commands through it.

```powershell
$out = '<evidence>/packet-parity'
$heap = '14g'
$profiles = (Get-ChildItem -LiteralPath port/versions -Filter '*.json').BaseName
foreach ($profile in $profiles) {
  New-Item -ItemType Directory -Path "$out/wasmgc/$profile" -Force | Out-Null
  $env:GAIUS_DEBUG_PACKET_PARITY = '1'
  $env:MAVEN_ARGS = '--offline'
  $env:GAIUS_TEAVM_MAX_HEAP = $heap
  $env:JAVA_TOOL_OPTIONS = '-Xmx3g -XX:ActiveProcessorCount=2'
  $env:GAIUS_BUILD_ROOT = "$out/wasmgc/$profile/build"
  $env:GAIUS_OVERLAY_DIRECTORY = "$out/wasmgc/$profile/overlays"
  $env:GAIUS_DIST_DIRECTORY = "$out/wasmgc/$profile/dist"
  $env:GAIUS_WASMGC_RESOURCE_ROOT = "<js-build-root>/$profile"
  $env:GAIUS_WASMGC_RESOURCE_DIST = "<js-dist>/$profile"
  bash port/scripts/build-wasmgc.sh --role singleplayer-worker $profile *> "$out/wasmgc/$profile/build.log"
  if ($LASTEXITCODE -ne 0) { throw "WasmGC build failed: $profile" }
  $patches = Select-String -LiteralPath "$out/wasmgc/$profile/build/overlays.log" -Pattern '^PATCH_\w+ MinecraftClientPatcher\.patchTickPacketParity\b'
  if (@($patches | Where-Object { $_.Line -like 'PATCH_APPLIED *' }).Count -ne 1) { throw "parity registration not applied: $profile" }
  python port/scripts/packet-parity/parity-hooks.py "$out/wasmgc/$profile/overlays/client-named-$profile-gaius.jar" `
    --expect present --json "$out/wasmgc/$profile/parity-hooks.json"
  if ($LASTEXITCODE -ne 0) { throw "parity hooks lost by a later patcher: $profile" }
}
Remove-Item Env:GAIUS_DEBUG_PACKET_PARITY, Env:MAVEN_ARGS, Env:GAIUS_TEAVM_MAX_HEAP, Env:JAVA_TOOL_OPTIONS, Env:GAIUS_BUILD_ROOT, Env:GAIUS_OVERLAY_DIRECTORY, Env:GAIUS_DIST_DIRECTORY, Env:GAIUS_WASMGC_RESOURCE_ROOT, Env:GAIUS_WASMGC_RESOURCE_DIST
New-Item -ItemType Directory -Path "$out/wasmgc-capture" -Force | Out-Null
foreach ($profile in $profiles) {
  node port/scripts/packet-parity/tick-browser-capture.mjs `
    --dist "$out/wasmgc/$profile/dist" --script "$out/jvm/$profile/script.json" `
    --chrome '<chrome>' `
    --out "$out/wasmgc-capture/$profile"
  if ($LASTEXITCODE -ne 0) { throw "WasmGC capture failed: $profile" }
  node port/scripts/packet-parity/tick-parity.mjs compare `
    --left "$out/jvm/$profile/run-a/events.jsonl" `
    --right "$out/wasmgc-capture/$profile/events.jsonl" --out "$out/wasmgc-compare/$profile"
  if ($LASTEXITCODE -ne 0) { throw "WasmGC parity did not pass: $profile" }
}
```

A default (flag-off) WasmGC overlay of the same tree must show no hook:
`python port/scripts/packet-parity/parity-hooks.py <overlays>/client-named-<profile>-gaius.jar --expect absent`.

The capture CLI requires the selected parent to exist and the final profile
directory to be new. It serves only the selected local runtime/bootstrap files on
loopback with COOP/COEP, gives Chrome its own user-data directory, records raw
events/control messages and hashes the runtime plus script. Startup polling/watchdogs
are not input schedulers. Failed captures preserve partial evidence and never gain
a success footer. A capture passes only with the probe's `configured`, `started` and
`complete` reports: the parity schedule installed, kernels off and silent, no
dispatcher pump hop, no failed background task and only the final hold pending.

Acceptance of the WasmGC capture still requires actual Worker execution, authoritative setup receipts,
no distance ramp, all tick/input/entity boundaries, zero unexplained differences,
final overlay hook retention, default-off/G1 artifact evidence and the broader
real-client/transport coverage. Browser-vs-JVM captures may need further reviewed
rules where the TeaVM classlib orders collections or runs future dependents
differently from the JDK (see "WasmGC settle probe"); none are pre-approved here.

## Local checks

```powershell
node --test port/scripts/packet-parity/*test.mjs
node --check port/scripts/packet-parity/tick-parity.mjs
node --check port/scripts/packet-parity/tick-browser-capture.mjs
node --check port/scripts/packet-parity/settle-probe-jvm.mjs
node port/tools/patchkit/patchkit.mjs lint
git diff --check
```

These start JVMs; if your setup has a machine job runner that limits concurrent heavy jobs, run each through it:

```powershell
$env:GAIUS_JAVA_HOME = '<jdk-home>'
$out = '<evidence>/packet-parity/checks'
# The WasmGC probe, executors and TickParitySettle against Minecraft stubs (no TeaVM build).
node port/scripts/packet-parity/settle-probe-jvm.mjs --out "$out/probe-jvm"
# Both targets, three profiles, flag off; then the wasmgc chain with the debug flag.
node port/tools/patchkit/patchkit.mjs check --keep --scratch "$out/patchkit-scratch" --out "$out/patchkit"
$env:GAIUS_DEBUG_PACKET_PARITY = '1'
node port/tools/patchkit/patchkit.mjs check --target wasmgc --keep --scratch "$out/patchkit-flag-scratch" --out "$out/patchkit-flag"
Remove-Item Env:GAIUS_DEBUG_PACKET_PARITY
```

G1 (JavaScript target unchanged), with the method of the reviewed G1 lock: build the
JS overlays of every profile (`GAIUS_TEAVM_TARGET=js`, Maven
offline, JVMs at `-Xmx3g`; `build-overlays.sh` with `GAIUS_VERSION_PROFILE_PATH`,
`GAIUS_OVERLAY_DIRECTORY` and `GAIUS_BUILD_ROOT` under the output directory), snapshot
each overlay directory and diff it against the reviewed baseline:

```powershell
python port/scripts/overlay-golden-diff.py snapshot "$out/g1/candidate/overlays/<profile>" "$out/g1/candidate/<profile>.json"
python port/scripts/overlay-golden-diff.py diff <g1-baseline>/<profile>.json `
  "$out/g1/candidate/<profile>.json" --products --strict-added --profile <profile> --accept port/reviews/g1-js-overlays.json
python port/scripts/packet-parity/parity-hooks.py "$out/g1/candidate/overlays/<profile>/client-named-<profile>-gaius.jar" --expect absent
```

`settle-probe-jvm.mjs` also runs one seeded synthetic workload through
`TickParitySettle` with the JVM harness's TickParityExecutors and with the browser
schedule and requires identical histories. patchkit's `check` compiles the tools with
`javac -J-Xmx3g` and runs each patcher step with `-Xmx2g`.
