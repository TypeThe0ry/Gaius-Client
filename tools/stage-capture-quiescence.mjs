import {createHash} from "node:crypto";

// Diagnostic response staging for the JavaScript client: publishes a vanilla-state quiescence
// producer (globalThis.__gaiusCaptureQuiescence), counts the world packets the client receives
// and lets a capture pin the lightmap's block-light flicker. Nothing here changes a distribution;
// the staged copy is served only to the capture page.
//
// Every value the producer reports is read from the client's own vanilla objects on the page's
// main thread when the harness asks for it: the client chunk cache (FULL chunks with light
// enabled), the client light engine and its queued light updates, the section dirty tracker, the
// vanilla compile queue and the browser compile/upload/mesh-kernel queues. The integrated server
// runs in a Worker whose state the page cannot read; its pending work is only visible through the
// chunk, light and block packets it still sends, so those counters stand in for it and are named
// as network evidence, never as server queues.

const PACKETS = Object.freeze({
  chunk: "handleLevelChunkWithLight", light: "handleLightUpdatePacket", block: "handleBlockUpdate",
  sectionBlocks: "handleChunkBlocksUpdate", forget: "handleForgetLevelChunk", biomes: "handleChunksBiomes",
  blockEntity: "handleBlockEntityData", cacheCenter: "handleSetChunkCacheCenter", cacheRadius: "handleSetChunkCacheRadius",
});

const escape = text => text.replace(/[$]/g, "\\$");

function functionStart(source, name) {
  const matches = [...source.matchAll(new RegExp("(?:\\$rt_java\\.|let |\\n)" + escape(name) + " = [^\\n]*=> \\{", "g"))];
  if (matches.length !== 1) throw new Error("quiescence staging expected one " + name + ", found " + matches.length);
  return {index: matches[0].index, end: matches[0].index + matches[0][0].length};
}

function body(source, name, limit = 4000) {
  const {index} = functionStart(source, name);
  const end = source.indexOf("\n};", index);
  return source.slice(index, Math.min(end, index + limit));
}

function field(text, pattern, label) {
  const match = text.match(pattern);
  if (!match) throw new Error("quiescence staging cannot resolve " + label);
  return match[1];
}

export function stageCaptureQuiescence(source) {
  const edits = [];
  const chunkRecord = body(source, "let nmnpg_ClientboundLevelChunkWithLightPacket__init_");
  const chunkFields = ["x", "z", "chunkData", "lightData"].map(name => field(chunkRecord, new RegExp("\\$this\\.(\\$\\w+) = \\$" + name + ";"), "chunk packet " + name));
  const chunkBuffer = field(body(source, "$rt_java.nmnpg_ClientboundLevelChunkPacketData__init_0"), /\$this\.(\$\w+) = \$buffer;/, "chunk packet buffer");
  const lightRecord = body(source, "let nmnpg_ClientboundLightUpdatePacket__init_0");
  const lightFields = ["x", "z", "lightData"].map(name => field(lightRecord, new RegExp("\\$this\\.(\\$\\w+) = \\$" + name + ";"), "light packet " + name));
  for (const name of ["\nnmnpg_ClientboundLightUpdatePacketData_skyUpdates = $this => {",
    "\nnmnpg_ClientboundLightUpdatePacketData_blockUpdates = $this => {"]) {
    if (!source.includes(name)) throw new Error("quiescence staging missing " + name);
  }
  // FNV-1a over every sky and block light array a packet carries, appended per chunk in arrival
  // order: tells whether the server sent different light, or the client computed it differently.
  const lightDigest = (packet, x, z, data) => "{const light=" + packet + "." + data + ";let hash=2166136261;"
    + "for(const list of [nmnpg_ClientboundLightUpdatePacketData_skyUpdates(light),nmnpg_ClientboundLightUpdatePacketData_blockUpdates(light)]){"
    + "const iterator=list.$iterator();hash=Math.imul(hash^59,16777619)>>>0;while(iterator.$hasNext()){const bytes=iterator.$next().data;"
    + "for(let index=0;index<bytes.length;index++)hash=Math.imul(hash^(bytes[index]&255),16777619)>>>0;hash=Math.imul(hash^44,16777619)>>>0;}}"
    + "const store=globalThis.__gaiusCaptureLightPackets||(globalThis.__gaiusCaptureLightPackets={}),key=" + packet + "." + x + "+','+" + packet + "." + z + ";"
    + "(store[key]||(store[key]=[])).push(hash.toString(16).padStart(8,'0'));}";
  for (const [counter, handler] of Object.entries(PACKETS)) {
    const {end} = functionStart(source, "$rt_java.nmcm_ClientPacketListener_" + handler);
    // The chunk handler also keeps an FNV-1a digest of the section data the server sent last.
    const digest = counter !== "chunk" ? "" : "const data=$packet." + chunkFields[2] + "." + chunkBuffer + ".data;let hash=2166136261;"
      + "for(let index=0;index<data.length;index++)hash=Math.imul(hash^(data[index]&255),16777619)>>>0;"
      + "(globalThis.__gaiusCaptureChunkPackets||(globalThis.__gaiusCaptureChunkPackets={}))[$packet." + chunkFields[0] + "+','+$packet." + chunkFields[1] + "]=hash.toString(16).padStart(8,'0');"
      + lightDigest("$packet", chunkFields[0], chunkFields[1], chunkFields[3]);
    const lightUpdate = counter !== "light" ? "" : lightDigest("$packet", lightFields[0], lightFields[1], lightFields[2]);
    edits.push({at: end, text: "\n    if(!$rt_resuming()){const counters=globalThis.__gaiusCaptureNetwork||(globalThis.__gaiusCaptureNetwork={});counters." + counter + "=(counters." + counter + "||0)+1;" + digest + lightUpdate + "}"});
  }
  const tick = body(source, "$rt_java.nmcr_LightmapRenderStateExtractor_tick");
  const flicker = field(tick, /\$this\.(\$\w+) = var\$1 \+ /, "block light flicker");
  const needsUpdate = field(tick, /\$this\.(\$\w+) = 1;\s*return;/, "lightmap update flag");
  {
    const {end} = functionStart(source, "$rt_java.nmcr_LightmapRenderStateExtractor_tick");
    edits.push({at: end, text: "\n    const capture=globalThis.__gaiusCapture;"
      + "\n    if(capture&&capture.pinLightFlicker===true&&!$rt_resuming()){$this." + flicker + "=0;$this." + needsUpdate + "=1;"
      + "capture.pinnedLightFlickerTicks=(capture.pinnedLightFlickerTicks||0)+1;return;}"});
  }
  {
    // The Globals uniform carries (gameTime % 24000 + partialTick) / 24000. The game time is the
    // world's age in ticks, so two runs freeze at different values; a capture pins the uniform's
    // inputs to the frozen day time with no partial tick, a state vanilla itself reaches.
    const {end, index} = functionStart(source, "$rt_java.nmcr_GlobalSettingsUniform_update");
    const header = source.slice(index, end);
    if (!/\(\$this, \$width, \$height, \$glintAlpha, \$gameTime, \$worldPartialTicks, /.test(header)
        || !body(source, "$rt_java.nmcr_GlobalSettingsUniform_update", 8000).includes("(Long_toNumber(Long_rem($gameTime, Long_fromInt(24000))) + $worldPartialTicks) / 24000.0")) {
      throw new Error("quiescence staging cannot resolve the Globals game time");
    }
    edits.push({at: end, text: "\n    if(Number.isInteger(globalThis.__gaiusCapture?.pinGameTime)&&!$rt_resuming()){"
      + "$gameTime=Long_fromInt(globalThis.__gaiusCapture.pinGameTime);$worldPartialTicks=0;"
      + "globalThis.__gaiusCapture.pinnedGameTimeFrames=(globalThis.__gaiusCapture.pinnedGameTimeFrames||0)+1;}"});
  }
  const audit = body(source, "$rt_java.dgb_BrowserSectionAudit_afterExtract", 3000);
  const level = field(audit, /\$level = \$minecraft\.(\$\w+);/, "Minecraft.level");
  const renderer = field(audit, /\$renderer = \$minecraft\.(\$\w+);/, "Minecraft.levelRenderer");
  const extractor = field(body(source, "$rt_java.dgb_BrowserSectionAudit_requeueAfterUploadTimeout"),
    /\$extractor = \(nmc_Minecraft_getInstance\(\)\)\.(\$\w+);/, "Minecraft.levelExtractor");
  const trackerCall = source.indexOf("$rt_java.dgb_BrowserSectionAudit_afterExtract(var$5);");
  if (trackerCall < 0 || source.indexOf("$rt_java.dgb_BrowserSectionAudit_afterExtract(var$5);", trackerCall + 1) >= 0) {
    throw new Error("quiescence staging expected one section audit call");
  }
  const tracker = field(source.slice(trackerCall - 400, trackerCall), /var\$5 = \$this\.(\$\w+);\s*\$ptr = \d+;\s*case \d+:\s*$/, "LevelExtractor.sectionUpdateTracker");
  const lightQueue = field(body(source, "$rt_java.nmcm_ClientLevel_queueLightUpdate"), /\$this\.(\$\w+)\.\$add\(\$update\);/, "ClientLevel.lightUpdateQueue");
  const dispatcherQueue = field(body(source, "$rt_java.nmcrc_SectionRenderDispatcher_getCompileQueueSize"), /return \$this\.(\$\w+)\.\$size\(\);/, "SectionRenderDispatcher.queue");
  const dispatcherField = (() => {
    const matches = [...source.matchAll(/nmcrc_SectionRenderDispatcher__init_\((var\$\d+), [^\n]*\n(?:[^\n]*\n){0,4}?\s*\$this\.(\$\w+) = \1;/g)];
    if (new Set(matches.map(match => match[2])).size !== 1) throw new Error("quiescence staging expected one section dispatcher owner");
    return matches[0][2];
  })();
  const dirty = field(body(source, "$rt_java.nmc_SectionUpdateTracker$SectionDirtyState_isDirty"), /return \$this\.(\$\w+);/, "SectionDirtyState.isDirty");
  const dataLayer = body(source, "$rt_java.nmwlc_DataLayer_getData");
  const layerData = field(dataLayer, /if \(\$this\.(\$\w+) === null\)/, "DataLayer.data");
  const layerDefault = field(dataLayer, /if \(\$this\.(\$\w+)\)/, "DataLayer.defaultValue");
  for (const name of ["\nnmc_SectionPos_asLong = ($x, $y, $z) => {", "\nlet nmc_SectionUpdateTracker_doesChunkExistAt = ",
    "\nnmc_SectionPos_of4 = ($x, $y, $z) => {", "\n$rt_java.nmwll_LevelLightEngine_getLayerListener = ($this, $layer) => {",
    "\nlet nmwl_LightLayer_$callClinit = () => {", "\nnmwl_LightLayer_SKY = ", "\nnmwl_LightLayer_BLOCK = ",
    "\n$rt_java.nmsl_ChunkTrackingView_isWithinDistance = ", "\n$rt_java.nmwll_LevelLightEngine_hasLightWork = ",
    "\ndgb_BrowserRenderScheduler_queuedSectionWork = () => {", "\n$rt_java.dgb_BrowserRenderScheduler_uploadBacklog = () => {",
    "\nlet dgbr_BrowserMeshInstallQueue_inFlight = () => {", "\ndgbr_BrowserMeshInstallQueue_readyCount = () => {",
    "\nlet nmc_Minecraft_getInstance = () => {", "\n$rt_java.nmwlc_LevelChunkSection_getStates = $this => {",
    "\n$rt_java.nmwlc_LevelChunkSection_getBiomes = $this => {", "\n$rt_java.nmwlc_PalettedContainer_get = ($this, $index) => {",
    "\nnmwlb_Block_getId = $blockState => {", "\n$rt_java.nmc_Holder$Reference_key = $this => {"]) {
    if (!source.includes(name)) throw new Error("quiescence staging missing " + name);
  }
  const producer = `
globalThis.__gaiusCaptureQuiescence = (() => {
  let sample = 0;
  const realm = (request, value) => ({requestId: request.requestId, bounds: request.bounds, sample: ++sample, ...value});
  const fnv = (bytes, seed = 2166136261) => {
    let hash = seed >>> 0;
    for (let index = 0; index < bytes.length; index++) hash = Math.imul(hash ^ (bytes[index] & 255), 16777619) >>> 0;
    return hash;
  };
  const world = () => {
    const minecraft = nmc_Minecraft_getInstance(), level = minecraft.${level};
    const extractor = minecraft.${extractor}, renderer = minecraft.${renderer};
    return level === null || extractor === null || renderer === null ? null : {minecraft, level, extractor, renderer};
  };
  return {
    schema: 2, source: "vanilla",
    capabilities: ${JSON.stringify(QUIESCENCE_REQUIREMENTS)},
    snapshot(request) {
      const current = world();
      if (current === null) return {schema: 2, source: "vanilla", requestId: request.requestId, dimension: null};
      const {level, extractor, renderer} = current;
      const tracker = extractor.${tracker}, dispatcher = renderer.${dispatcherField};
      const dimension = $rt_ustr(level.$dimension().$identifier().$toString());
      const bounds = request.bounds, view = request.view;
      const loadedChunks = [], expectedMissing = [], unexpected = [];
      let trackedSections = 0;
      const minY = level.$getMinSectionY(), maxY = level.$getMaxSectionY();
      nmwlcs_ChunkStatus_$callClinit();
      for (let x = bounds.minX; x <= bounds.maxX; x++) for (let z = bounds.minZ; z <= bounds.maxZ; z++) {
        const loaded = level.$getChunk0(x, z, nmwlcs_ChunkStatus_FULL, 0) !== null
          && nmc_SectionUpdateTracker_doesChunkExistAt(tracker, level, nmc_SectionPos_asLong(x, 0, z));
        const expected = $rt_java.nmsl_ChunkTrackingView_isWithinDistance(view.centerX, view.centerZ, view.distance, x, z, 1) !== 0;
        if (loaded) loadedChunks.push([x, z]);
        if (expected && !loaded) expectedMissing.push([x, z]);
        if (loaded && !expected) unexpected.push([x, z]);
        if (!loaded) continue;
        for (let y = minY; y <= maxY; y++) if (tracker.$getDirtyState(nmc_SectionPos_asLong(x, y, z)) !== null) trackedSections++;
      }
      // Vanilla compiles only the sections in the render list; a dirty section out of view keeps
      // its flag until it becomes visible, so only visible sections can hold back this view.
      let visible = 0, uncompiled = 0, dirtySections = 0, compileActive = 0;
      nmcrc_CompiledSectionMesh_$callClinit();
      const iterator = renderer.$visibleSections().$iterator5();
      while (iterator.$hasNext()) {
        const section = iterator.$next();
        visible++;
        if (section.$getSectionMesh() === nmcrc_CompiledSectionMesh_UNCOMPILED) uncompiled++;
        const state = tracker.$getDirtyState(section.$getSectionNode());
        if (state !== null && state.${dirty}) dirtySections++;
        if (section.$gaius$hasPendingCompile()) compileActive++;
      }
      const engine = level.$getLightEngine();
      const client = realm(request, {dimension, loadedChunks, expectedMissing, unexpected,
        lightPending: level.${lightQueue}.$size() + ($rt_java.nmwll_LevelLightEngine_hasLightWork(engine) ? 1 : 0),
        dirtySections, trackedSections, visibleSections: visible, uncompiledVisible: uncompiled,
        compileQueued: dispatcher.${dispatcherQueue}.$size() + compileActive,
        sectionWork: dgb_BrowserRenderScheduler_queuedSectionWork(),
        uploadQueued: $rt_java.dgb_BrowserRenderScheduler_uploadBacklog(),
        meshPending: dgbr_BrowserMeshInstallQueue_inFlight() + dgbr_BrowserMeshInstallQueue_readyCount()});
      return {schema: 2, source: "vanilla", requestId: request.requestId, dimension, client,
        network: realm(request, {dimension, packets: {...(globalThis.__gaiusCaptureNetwork || {})}}),
        pins: {lightFlicker: globalThis.__gaiusCapture?.pinLightFlicker === true,
          lightFlickerTicks: globalThis.__gaiusCapture?.pinnedLightFlickerTicks || 0,
          gameTime: globalThis.__gaiusCapture?.pinGameTime ?? null,
          gameTimeFrames: globalThis.__gaiusCapture?.pinnedGameTimeFrames || 0}};
    },
    // Per loaded chunk: FNV-1a of the last chunk packet's section data as the server sent it, and
    // of the client's final sky/block light layers (read without materializing empty layers).
    worldDigest(chunks) {
      const current = world();
      if (current === null) return null;
      const {level} = current;
      const engine = level.$getLightEngine();
      nmwl_LightLayer_$callClinit();
      const listeners = [$rt_java.nmwll_LevelLightEngine_getLayerListener(engine, nmwl_LightLayer_SKY),
        $rt_java.nmwll_LevelLightEngine_getLayerListener(engine, nmwl_LightLayer_BLOCK)];
      const minY = level.$getMinSectionY() - 1, maxY = level.$getMaxSectionY() + 1;
      const packets = globalThis.__gaiusCaptureChunkPackets || {};
      const result = {};
      for (const [x, z] of chunks) {
        const light = listeners.map(listener => {
          let hash = 2166136261;
          for (let y = minY; y <= maxY; y++) {
            const layer = listener.$getDataLayerData0(nmc_SectionPos_of4(x, y, z));
            const data = layer === null ? null : layer.${layerData};
            hash = fnv(layer === null ? [255, y & 255] : data === null ? [254, layer.${layerDefault} & 255] : data.data, hash);
          }
          return hash.toString(16).padStart(8, "0");
        });
        result[x + "," + z] = {packet: packets[x + "," + z] || null, sky: light[0], block: light[1],
          sentLight: (globalThis.__gaiusCaptureLightPackets || {})[x + "," + z] || []};
      }
      return result;
    },
    // Per loaded chunk: FNV-1a over the registry ids of every block state and the keys of every
    // biome entry of the client's sections. Independent of palette order, unlike packet bytes.
    contentDigest(chunks) {
      const current = world();
      if (current === null) return null;
      const {level} = current;
      const ids = new Map(), names = new Map(), result = {};
      nmwlcs_ChunkStatus_$callClinit();
      for (const [x, z] of chunks) {
        const chunk = level.$getChunk0(x, z, nmwlcs_ChunkStatus_FULL, 0);
        if (chunk === null) { result[x + "," + z] = null; continue; }
        let blocks = 2166136261, biomes = 2166136261;
        for (const section of chunk.$getSections().data) {
          const states = $rt_java.nmwlc_LevelChunkSection_getStates(section);
          for (let index = 0; index < 4096; index++) {
            const state = $rt_java.nmwlc_PalettedContainer_get(states, index);
            let id = ids.get(state);
            if (id === undefined) { id = nmwlb_Block_getId(state); ids.set(state, id); }
            blocks = Math.imul(blocks ^ (id & 65535), 16777619) >>> 0;
            blocks = Math.imul(blocks ^ (id >>> 16), 16777619) >>> 0;
          }
          const entries = $rt_java.nmwlc_LevelChunkSection_getBiomes(section);
          for (let index = 0; index < 64; index++) {
            const holder = $rt_java.nmwlc_PalettedContainer_get(entries, index);
            let name = names.get(holder);
            if (name === undefined) { name = $rt_ustr($rt_java.nmc_Holder$Reference_key(holder).$identifier().$toString()); names.set(holder, name); }
            for (let character = 0; character < name.length; character++) biomes = Math.imul(biomes ^ name.charCodeAt(character), 16777619) >>> 0;
            biomes = Math.imul(biomes ^ 59, 16777619) >>> 0;
          }
        }
        result[x + "," + z] = {blocks: blocks.toString(16).padStart(8, "0"), biomes: biomes.toString(16).padStart(8, "0")};
      }
      return result;
    }
  };
})();
`;
  const exportsAt = source.lastIndexOf("\n$rt_exports.main = ");
  if (exportsAt < 0) throw new Error("quiescence staging cannot find the module exports");
  edits.push({at: exportsAt, text: producer});
  const parts = [];
  let previous = 0;
  for (const edit of edits.sort((left, right) => left.at - right.at)) {
    parts.push(source.slice(previous, edit.at), edit.text);
    previous = edit.at;
  }
  parts.push(source.slice(previous));
  const staged = parts.join("");
  return {source: staged, receipt: {mode: "diagnostic-response-only", policy: "vanilla-quiescence-producer-v2",
    packetCounters: Object.keys(PACKETS), fields: {level, renderer, extractor, tracker, lightQueue, dispatcherField, dispatcherQueue, dirty, flicker, needsUpdate, chunkFields, chunkBuffer, layerData, layerDefault},
    stagedSha256: createHash("sha256").update(staged).digest("hex")}};
}

export const QUIESCENCE_REQUIREMENTS = Object.freeze([
  "client-chunks", "client-light", "section-dirty", "section-compile", "section-upload",
  "network-chunks", "network-light", "network-blocks", "render-clock-pins",
]);
