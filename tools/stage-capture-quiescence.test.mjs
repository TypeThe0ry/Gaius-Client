import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import {stageCaptureQuiescence} from "./stage-capture-quiescence.mjs";
import {captureRequest, quiescenceFailures, summarizeSnapshot} from "./capture-quiescence.mjs";

const handlers = ["handleLevelChunkWithLight", "handleLightUpdatePacket", "handleBlockUpdate", "handleChunkBlocksUpdate",
  "handleForgetLevelChunk", "handleChunksBiomes", "handleBlockEntityData", "handleSetChunkCacheCenter", "handleSetChunkCacheRadius"];

// The shapes TeaVM emits for the vanilla methods the producer reads (see classes.js of a 26.3 client).
const fixture = `(function($rt_exports) {
let $rt_java = {};
let $rt_resuming = () => false, $rt_ustr = value => value, Long_fromInt = value => BigInt(value), Long_toNumber = value => Number(value), Long_rem = (a, b) => a % b;
${handlers.map(name => `$rt_java.nmcm_ClientPacketListener_${name} = ($this, $packet) => {\n    return $packet;\n};`).join("\n")}
let nmnpg_ClientboundLevelChunkWithLightPacket__init_ = ($this, $x, $z, $chunkData, $lightData) => {
    $this.$x67 = $x;
    $this.$z39 = $z;
    $this.$chunkData0 = $chunkData;
    $this.$lightData0 = $lightData;
};
let nmnpg_ClientboundLightUpdatePacket__init_0 = ($this, $x, $z, $lightData) => {
    $this.$x64 = $x;
    $this.$z37 = $z;
    $this.$lightData1 = $lightData;
};
let nmnpg_ClientboundLightUpdatePacketData_ctor = () => null,
nmnpg_ClientboundLightUpdatePacketData_skyUpdates = $this => {
    return $this.sky;
},
nmnpg_ClientboundLightUpdatePacketData_blockUpdates = $this => {
    return $this.block;
};
$rt_java.nmnpg_ClientboundLevelChunkPacketData__init_0 = ($this, $heightmaps, $buffer, $blockEntitiesData) => {
    $this.$buffer44 = $buffer;
};
$rt_java.nmwlc_DataLayer_getData = $this => {
    if ($this.$data26 === null) {
        $this.$data26 = new Int8Array(2048);
        if ($this.$defaultValue3)
            $this.$data26.fill($this.$defaultValue3);
    }
    return $this.$data26;
};
let nmwl_LightLayer_$callClinit = () => {
},
nmwl_LightLayer_SKY = "SKY",
nmwl_LightLayer_BLOCK = "BLOCK";
$rt_java.nmwll_LevelLightEngine_getLayerListener = ($this, $layer) => {
    return $this.layers[$layer];
};
let nmc_SectionPos_of3 = () => null,
nmc_SectionPos_of4 = ($x, $y, $z) => {
    return {x: $x, y: $y, z: $z};
};
$rt_java.nmcr_LightmapRenderStateExtractor_tick = $this => {
    let var$1, var$3, var$4;
    var$1 = $this.$blockLightFlicker; var$3 = 0.5; var$4 = 0.5;
        $this.$blockLightFlicker = var$1 + var$3 * var$4 * 0.10000000149011612;
        $this.$needsUpdate0 = 1;
        return;
};
$rt_java.nmcr_GlobalSettingsUniform_update = ($this, $width, $height, $glintAlpha, $gameTime, $worldPartialTicks, $menuBlurRadius, $cameraPos, $useRgss) => {
    let var$16;
            var$16 = (Long_toNumber(Long_rem($gameTime, Long_fromInt(24000))) + $worldPartialTicks) / 24000.0;
    globalThis.__fixtureGameTime = var$16;
};
$rt_java.dgb_BrowserSectionAudit_afterExtract = $tracker => {
    let $minecraft, $level, $renderer;
            $level = $minecraft.$level9;
            $renderer = $minecraft.$levelRenderer0;
};
$rt_java.dgb_BrowserSectionAudit_requeueAfterUploadTimeout = ($section, $alreadyCancelled) => {
        let $extractor = (nmc_Minecraft_getInstance()).$levelExtractor;
};
$rt_java.nmcr_LevelExtractor_extract = $this => {
    let var$5, $ptr = 39;
    main: while (true) { switch ($ptr) {
    case 39:
        var$5 = $this.$sectionUpdateTracker;
        $ptr = 40;
    case 40:
        $rt_java.dgb_BrowserSectionAudit_afterExtract(var$5);
        return;
    }}
};
$rt_java.nmcm_ClientLevel_queueLightUpdate = ($this, $update) => {
    $this.$lightUpdateQueue.$add($update);
};
$rt_java.nmcrc_SectionRenderDispatcher_getCompileQueueSize = $this => {
    return $this.$queue4.$size();
};
$rt_java.nmcr_LevelRenderer_allChanged = $this => {
    let var$6, var$7, var$8, $sectionCompiler, var$10;
    main: while (true) {
        nmcrc_SectionRenderDispatcher__init_(var$6, var$7, var$8, $sectionCompiler, var$10);
        if ($rt_suspending()) {
            break main;
        }
        $this.$sectionRenderDispatcher0 = var$6;
        return;
    }
};
let nmcrc_SectionRenderDispatcher__init_ = () => {}, $rt_suspending = () => false;
$rt_java.nmc_SectionUpdateTracker$SectionDirtyState_isDirty = $this => {
    return $this.$isDirty2;
};
let nmc_SectionPos_asLong0 = $pos => $pos,
nmc_SectionPos_asLong = ($x, $y, $z) => {
    return $x + ":" + $y + ":" + $z;
};
let nmc_SectionUpdateTracker_doesChunkExistAt = ($this, $level, $node) => {
    return $level.chunks.has($node.split(":")[0] + ":" + $node.split(":")[2]) ? 1 : 0;
};
$rt_java.nmsl_ChunkTrackingView_isWithinDistance = ($centerX, $centerZ, $viewDistance, $chunkX, $chunkZ, $includeNeighbors) => {
    const buffer = !$includeNeighbors ? 1 : 2;
    const dx = Math.max(0, Math.abs($chunkX - $centerX) - buffer), dz = Math.max(0, Math.abs($chunkZ - $centerZ) - buffer);
    return dx * dx + dz * dz < $viewDistance * $viewDistance ? 1 : 0;
};
$rt_java.nmwll_LevelLightEngine_hasLightWork = $this => {
    return $this.work ? 1 : 0;
};
let dgb_BrowserRenderScheduler_pending = 0,
dgb_BrowserRenderScheduler_queuedSectionWork = () => {
    return globalThis.__fixture.sectionWork;
};
$rt_java.dgb_BrowserRenderScheduler_uploadBacklog = () => {
    return globalThis.__fixture.upload;
};
let dgbr_BrowserMeshInstallQueue_inFlight = () => {
    return globalThis.__fixture.inFlight;
},
dgbr_BrowserMeshInstallQueue_readyCount = () => {
    return 0;
};
let nmc_Minecraft_getInstance = () => {
    return globalThis.__fixture.minecraft;
};
let nmwlcs_ChunkStatus_$callClinit = () => {}, nmwlcs_ChunkStatus_FULL = "FULL";
$rt_java.nmwlc_LevelChunkSection_getStates = $this => {
    return $this.states;
};
$rt_java.nmwlc_LevelChunkSection_getBiomes = $this => {
    return $this.biomes;
};
$rt_java.nmwlc_PalettedContainer_get = ($this, $index) => {
    return $this[$index % $this.length];
};
let nmwlb_Block_getId0 = () => 0,
nmwlb_Block_getId = $blockState => {
    return $blockState.id;
};
$rt_java.nmc_Holder$Reference_key = $this => {
    return {$identifier: () => ({$toString: () => $this.name})};
};
let nmcrc_CompiledSectionMesh_$callClinit = () => {}, nmcrc_CompiledSectionMesh_UNCOMPILED = {};
$rt_exports.listener = $rt_java;
$rt_exports.main = () => {};
})(globalThis.__exports);`;

function world({missing = [], dirty = 0, pending = 0, light = false, skyValue = 15, blockId = 1} = {}) {
  const chunks = new Set();
  for (let x = -10; x <= 10; x++) for (let z = -10; z <= 10; z++) {
    if ($within(0, 0, 8, x, z) && !missing.some(([mx, mz]) => mx === x && mz === z)) chunks.add(x + ":" + z);
  }
  // Every tracked section of an unseen column stays dirty in vanilla; only visible ones count.
  const tracker = {$getDirtyState: node => ({$isDirty2: typeof node === "string" ? 1 : node.dirty})};
  const sections = Array.from({length: 4}, (_, index) => ({$getSectionMesh: () => ({}),
    $getSectionNode: () => ({dirty: index < dirty ? 1 : 0}), $gaius$hasPendingCompile: () => index < pending}));
  const layer = value => ({$getDataLayerData0: position => position.y === 2 ? {$data26: null, $defaultValue3: value}
    : position.y === 3 ? {$data26: {data: new Int8Array([position.x, position.z, value])}} : null});
  const level = {chunks, $lightUpdateQueue: {$size: () => 0}, $getMinSectionY: () => -4, $getMaxSectionY: () => 19,
    $getChunk0: (x, z) => chunks.has(x + ":" + z) ? {$getSections: () => ({data: [{states: [{id: x === 1 ? blockId : 1}, {id: 2}], biomes: [{name: "minecraft:plains"}]}]})} : null,
    $getLightEngine: () => ({work: light, layers: {SKY: layer(skyValue), BLOCK: layer(0)}}),
    $dimension: () => ({$identifier: () => ({$toString: () => "minecraft:overworld"})})};
  return {sectionWork: 0, upload: 0, inFlight: 0, minecraft: {$level9: level, $levelExtractor: {$sectionUpdateTracker: tracker},
    $levelRenderer0: {$sectionRenderDispatcher0: {$queue4: {$size: () => 0}},
      $visibleSections: () => ({$iterator5: () => { let index = 0; return {$hasNext: () => index < sections.length, $next: () => sections[index++]}; }})}}};
}
function $within(cx, cz, distance, x, z) {
  const dx = Math.max(0, Math.abs(x - cx) - 2), dz = Math.max(0, Math.abs(z - cz) - 2);
  return dx * dx + dz * dz < distance * distance;
}

function lightData(sky, block) {
  const list = arrays => ({$iterator: () => { let index = 0; return {$hasNext: () => index < arrays.length, $next: () => ({data: new Int8Array(arrays[index++])})}; }});
  return {sky: list(sky), block: list(block)};
}

function load() {
  const staged = stageCaptureQuiescence(fixture);
  const context = vm.createContext({__exports: {}, BigInt, Number, Math, JSON, Object});
  context.globalThis = context;
  vm.runInContext(staged.source, context);
  return {context, staged};
}

test("staging resolves vanilla fields and fails closed on an unknown client shape", () => {
  const {staged} = load();
  assert.deepEqual(staged.receipt.fields, {level: "$level9", renderer: "$levelRenderer0", extractor: "$levelExtractor",
    tracker: "$sectionUpdateTracker", lightQueue: "$lightUpdateQueue", dispatcherField: "$sectionRenderDispatcher0",
    dispatcherQueue: "$queue4", dirty: "$isDirty2", flicker: "$blockLightFlicker", needsUpdate: "$needsUpdate0",
    chunkFields: ["$x67", "$z39", "$chunkData0", "$lightData0"], chunkBuffer: "$buffer44", layerData: "$data26", layerDefault: "$defaultValue3"});
  assert.throws(() => stageCaptureQuiescence(fixture.replace("$this.$needsUpdate0 = 1;", "$this.$other = 2;")), /lightmap update flag/);
  assert.throws(() => stageCaptureQuiescence(fixture.replace("+ $worldPartialTicks) / 24000.0", ") / 24000.0")), /game time/);
  assert.throws(() => stageCaptureQuiescence(fixture.replace("handleBlockUpdate", "handleBlockUpdated")), /handleBlockUpdate/);
});

test("the staged producer reports tracked chunks, dirty sections and queues from vanilla objects", () => {
  const {context} = load();
  const camera = [0.5, 70, 0.5, 0, 0], request = captureRequest(camera, 8);
  context.__fixture = world();
  const idle = summarizeSnapshot(context.__gaiusCaptureQuiescence.snapshot(request));
  // Pins are off until a capture turns them on.
  assert.deepEqual(quiescenceFailures(idle, request), ["render clock pins inactive"]);
  context.__gaiusCapture = {pinLightFlicker: true, pinGameTime: 6000};
  const extractor = {$blockLightFlicker: 0.3, $needsUpdate0: 0};
  context.__exports.listener.nmcr_LightmapRenderStateExtractor_tick(extractor);
  assert.deepEqual([extractor.$blockLightFlicker, extractor.$needsUpdate0], [0, 1]);
  context.__exports.listener.nmcr_GlobalSettingsUniform_update(null, 1, 1, 0, 123457n, 0.75, 0, null, 0);
  assert.equal(context.__fixtureGameTime, 0.25);
  const pinned = summarizeSnapshot(context.__gaiusCaptureQuiescence.snapshot(request));
  assert.deepEqual(quiescenceFailures(pinned, request), []);
  assert.ok(pinned.client.sample > idle.client.sample);
  for (const [state, failure] of [[{missing: [[8, 3]]}, "client tracked chunks incomplete"], [{dirty: 2}, "client.dirtySections is busy or unknown"], [{pending: 1}, "client.compileQueued is busy or unknown"],
    [{light: true}, "client.lightPending is busy or unknown"]]) {
    context.__fixture = world(state);
    assert.ok(quiescenceFailures(summarizeSnapshot(context.__gaiusCaptureQuiescence.snapshot(request)), request).includes(failure), failure);
  }
  context.__fixture = world(); context.__fixture.inFlight = 1;
  assert.ok(quiescenceFailures(summarizeSnapshot(context.__gaiusCaptureQuiescence.snapshot(request)), request).includes("client.meshPending is busy or unknown"));
});

test("packet counters count every world packet once per call outside a resumed coroutine", () => {
  const {context} = load();
  context.__exports.listener.nmcm_ClientPacketListener_handleLightUpdatePacket(null, {$x64: 0, $z37: 0, $lightData1: lightData([], [])});
  context.__exports.listener.nmcm_ClientPacketListener_handleLightUpdatePacket(null, {$x64: 0, $z37: 0, $lightData1: lightData([], [])});
  context.__exports.listener.nmcm_ClientPacketListener_handleLevelChunkWithLight(null, {$x67: 0, $z39: 0, $chunkData0: {$buffer44: {data: new Int8Array(0)}}, $lightData0: lightData([], [])});
  assert.deepEqual({...context.__gaiusCaptureNetwork}, {light: 2, chunk: 1});
});

test("world digests hash the sent chunk data and final light layers without materializing them", () => {
  const {context} = load();
  const packet = {$x67: 1, $z39: -2, $chunkData0: {$buffer44: {data: new Int8Array([1, 2, 3])}}, $lightData0: lightData([[15]], [[1]])};
  context.__exports.listener.nmcm_ClientPacketListener_handleLevelChunkWithLight(null, packet);
  context.__fixture = world();
  const first = context.__gaiusCaptureQuiescence.worldDigest([[1, -2], [0, 0]]);
  // FNV-1a 32 of [1, 2, 3].
  assert.equal(first["1,-2"].packet, "56cf37ab");
  assert.equal(first["0,0"].packet, null);
  assert.match(first["0,0"].sky, /^[0-9a-f]{8}$/);
  assert.notEqual(first["1,-2"].sky, first["0,0"].sky);
  context.__fixture = world({skyValue: 14});
  const second = context.__gaiusCaptureQuiescence.worldDigest([[1, -2], [0, 0]]);
  assert.notEqual(second["0,0"].sky, first["0,0"].sky);
  assert.equal(second["0,0"].block, first["0,0"].block);
  packet.$chunkData0.$buffer44.data = new Int8Array([1, 2, 4]);
  context.__exports.listener.nmcm_ClientPacketListener_handleLevelChunkWithLight(null, packet);
  assert.notEqual(context.__gaiusCaptureQuiescence.worldDigest([[1, -2]])["1,-2"].packet, first["1,-2"].packet);
});

test("content digests follow block registry ids and biome keys, not object identity", () => {
  const {context} = load();
  context.__fixture = world();
  const first = context.__gaiusCaptureQuiescence.contentDigest([[1, 0], [0, 0]]);
  context.__fixture = world();
  assert.deepEqual(context.__gaiusCaptureQuiescence.contentDigest([[1, 0], [0, 0]]), first);
  context.__fixture = world({blockId: 7});
  const changed = context.__gaiusCaptureQuiescence.contentDigest([[1, 0], [0, 0]]);
  assert.notEqual(changed["1,0"].blocks, first["1,0"].blocks);
  assert.equal(changed["1,0"].biomes, first["1,0"].biomes);
  assert.equal(changed["0,0"].blocks, first["0,0"].blocks);
  assert.equal(context.__gaiusCaptureQuiescence.contentDigest([[30, 30]])["30,30"], null);
});

test("light digests keep the server's chunk and update light per chunk in arrival order", () => {
  const {context} = load();
  const listener = context.__exports.listener;
  listener.nmcm_ClientPacketListener_handleLevelChunkWithLight(null, {$x67: 2, $z39: 3, $chunkData0: {$buffer44: {data: new Int8Array(0)}}, $lightData0: lightData([[15]], [[0]])});
  listener.nmcm_ClientPacketListener_handleLightUpdatePacket(null, {$x64: 2, $z37: 3, $lightData1: lightData([], [[7]])});
  context.__fixture = world();
  const first = context.__gaiusCaptureQuiescence.worldDigest([[2, 3]])["2,3"].sentLight;
  assert.equal(first.length, 2);
  listener.nmcm_ClientPacketListener_handleLightUpdatePacket(null, {$x64: 2, $z37: 3, $lightData1: lightData([], [[8]])});
  const second = context.__gaiusCaptureQuiescence.worldDigest([[2, 3]])["2,3"].sentLight;
  assert.equal(second.length, 3);
  assert.notEqual(second[2], second[1], "different block light arrays hash differently");
});
