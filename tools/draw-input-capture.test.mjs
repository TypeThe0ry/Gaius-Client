import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import {webcrypto} from "node:crypto";
import {DRAW_CAPTURE_BOOT_SOURCE} from "./draw-input-capture.mjs";
import {compareDrawInputs, compareTerrainSections} from "./compare-draw-inputs.mjs";
import {stageLinearMipmapSampler} from "./stage-linear-mipmap-sampler.mjs";
import {stageOfflineCapture} from "./stage-offline-capture.mjs";
import {stagePresentCapture} from "./stage-present-capture.mjs";
import {stageCaptureCreation} from "./stage-capture-creation.mjs";
import {containedCapturePath, copyCaptureWorldProfile, readCaptureWorldSnapshot} from "./capture-world-snapshot.mjs";
import {mkdir, mkdtemp, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";

test("saved-world replay requires a complete source, the original origin and contained independent copies", async () => {
  assert.ok(process.env.OUT, "tests require an explicit output directory");
  const root = resolve(process.env.OUT); await mkdir(root, {recursive: true});
  const directory = await mkdtemp(join(root, "snapshot-")), source = join(directory, "source"), output = join(directory, "candidate");
  const profile = join(source, "profile"), destination = join(output, "profile");
  await mkdir(profile, {recursive: true}); await mkdir(destination, {recursive: true});
  await writeFile(join(profile, "world.bin"), "saved world");
  const report = {verdict: "PASS", seed: "example-seed", profile: "catalog-profile", capture: {complete: true,
    worldSnapshot: {saved: true, origin: "http://127.0.0.1:43123", chromeProfile: "profile"}}};
  await writeFile(join(source, "report.json"), JSON.stringify(report));
  const snapshot = await readCaptureWorldSnapshot(output, source, report.seed, report.profile);
  assert.equal(snapshot.port, 43123); assert.match(snapshot.identity.sourceSha256, /^[a-f0-9]{64}$/);
  assert.match(snapshot.identity.profileSha256, /^[a-f0-9]{64}$/);
  assert.equal(snapshot.identity.profileFiles, 1);
  await copyCaptureWorldProfile(snapshot, output, destination);
  await writeFile(join(destination, "world.bin"), "candidate world");
  assert.equal(await readFile(join(profile, "world.bin"), "utf8"), "saved world");
  const changed = join(output, "changed"); await mkdir(changed);
  await writeFile(join(profile, "world.bin"), "tampered world");
  await assert.rejects(copyCaptureWorldProfile(snapshot, output, changed), /match the source bytes/);
  await writeFile(join(profile, "world.bin"), "saved world");
  await assert.rejects(readCaptureWorldSnapshot(output, source, "other-seed", report.profile), /snapshot seed/);
  await assert.rejects(readCaptureWorldSnapshot(output, source, report.seed, "other-profile"), /snapshot profile/);
  assert.throws(() => containedCapturePath(output, output), /inside the output/);
  assert.throws(() => containedCapturePath(output, source), /inside the output/);
  report.capture.worldSnapshot.chromeProfile = "../candidate/profile";
  await writeFile(join(source, "report.json"), JSON.stringify(report));
  await assert.rejects(readCaptureWorldSnapshot(output, source, report.seed, report.profile), /inside the output/);
  report.capture.worldSnapshot.chromeProfile = "profile"; report.verdict = "FAIL";
  await writeFile(join(source, "report.json"), JSON.stringify(report));
  await assert.rejects(readCaptureWorldSnapshot(output, source, report.seed, report.profile), /successful capture/);
  // A profile kept on the scratch volume is resolved against that root and must stay inside it.
  const scratch = join(directory, "scratch"), kept = join(scratch, "run-1", "profile");
  await mkdir(kept, {recursive: true}); await writeFile(join(kept, "world.bin"), "saved world");
  report.verdict = "PASS"; delete report.capture.worldSnapshot.chromeProfile;
  report.capture.worldSnapshot.scratchProfile = "run-1/profile";
  await writeFile(join(source, "report.json"), JSON.stringify(report));
  await assert.rejects(readCaptureWorldSnapshot(output, source, report.seed, report.profile), /requires CAPTURE_SCRATCH/);
  const scratched = await readCaptureWorldSnapshot(output, source, report.seed, report.profile, {scratchRoot: scratch});
  assert.equal(scratched.identity.profileSha256, snapshot.identity.profileSha256);
  const target = join(scratch, "run-2", "profile"); await mkdir(target, {recursive: true});
  await copyCaptureWorldProfile(scratched, scratch, target);
  report.capture.worldSnapshot.scratchProfile = "../source/profile";
  await writeFile(join(source, "report.json"), JSON.stringify(report));
  await assert.rejects(readCaptureWorldSnapshot(output, source, report.seed, report.profile, {scratchRoot: scratch}), /inside the output/);
});

test("creation pin uses the integer overload and vanilla rules before world creation", () => {
  const source = String.raw`let test_CreateWorldScreen_onCreate = $this => {
    switch (0) {
    case 0:
      $context = $this.$ui.$getSettings();
    }
};
let test_WorldCreationUiState_getGameRules = $ui => $ui.rules;
let test_GameRules_get = $this => {return test_GameRuleMap_get($this.$rules, rule);};
let test_GameRuleMap_set = (map, key, value) => map.set(key, value);
$rt_java.jl_Integer_valueOf = ($string, $radix) => {throw new Error("string overload");};
let jl_Integer_valueOf = $i => {return $i;};
let jl_Boolean_valueOf = $value => {return !!$value;};
let test_GameRules_RANDOM_TICK_SPEED = 1;
let test_GameRules_SPAWN_MOBS = 2;
let test_GameRules_ADVANCE_TIME = 3;
let test_GameRules_ADVANCE_WEATHER = 4;
let test_GameRules_SPAWN_MONSTERS = 5;
let test_GameRules_SPAWN_PATROLS = 6;
let test_GameRules_SPAWN_PHANTOMS = 7;
let test_GameRules_SPAWN_WANDERING_TRADERS = 8;
let test_GameRules_MOB_GRIEFING = 9;
let test_GameRules_SPREAD_VINES = 10;
let test_GameRules_FIRE_SPREAD_RADIUS_AROUND_PLAYER = 11;`;
  const staged = stageCaptureCreation(source);
  assert.ok(staged.source.includes("test_GameRuleMap_set(captureRules.$rules,test_GameRules_RANDOM_TICK_SPEED,jl_Integer_valueOf(0))"));
  assert.ok(staged.source.includes("test_GameRuleMap_set(captureRules.$rules,test_GameRules_MOB_GRIEFING,jl_Boolean_valueOf(0))"));
  assert.equal(staged.receipt.rules.length, 11);
  assert.ok(staged.source.indexOf("const captureRules=") < staged.source.indexOf("$context ="));
  assert.equal(staged.receipt.policy, "pin-client-ui-rules-before-world-creation");
  assert.throws(() => stageCaptureCreation(staged.source));
  assert.throws(() => stageCaptureCreation(source.replace("$i =>", "$text =>")));
  assert.throws(() => stageCaptureCreation(source.replace("    case 0:", "    case 1:")));
  assert.throws(() => stageCaptureCreation(source.replace("\n};", "\n}")));
  assert.throws(() => stageCaptureCreation("invalid"));
});

test("draw capture spans exactly one present-to-present frame and hashes bytes", async () => {
  class Gl {
    bindBuffer() {}
    bufferData() {}
    bufferSubData() {}
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, ArrayBuffer,
    TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), buffer = {};
  gl.bindBuffer(1, buffer);
  gl.bufferData(1, new Uint8Array([1, 2, 3, 4]));
  gl.bufferSubData(1, 1, new Uint8Array([9]));
  const capture = context.__gaiusDrawCapture;
  const pending = capture.frame();
  const record = name => capture.record(name, {}, [3, 1, 0], [buffer], null, value => value ? capture.snapshot(value, 0, 4) : null);
  record("outside-before");
  capture.boundary(); record("inside"); capture.boundary(); record("outside-after");
  const frame = await pending;
  assert.equal(frame.boundary, "surface-present");
  assert.equal(frame.beginPresent, 1);
  assert.equal(frame.endPresent, 2);
  assert.equal(frame.draws.length, 1);
  assert.equal(frame.draws[0].pipeline, "inside");
  assert.equal(frame.draws[0].vertices[0].sha256, Buffer.from(await webcrypto.subtle.digest("SHA-256", new Uint8Array([1, 9, 3, 4]))).toString("hex"));
  assert.equal(compareDrawInputs(frame, frame).verdict, "PASS");
  const altered = structuredClone(frame);
  altered.draws[0].args[0]++;
  assert.equal(compareDrawInputs(frame, altered).verdict, "FAIL");
  altered.draws[0].vertices[0].unknown = true;
  assert.ok(compareDrawInputs(altered, altered).unknown.length);
  assert.equal(compareDrawInputs({...frame, draws: []}, {...frame, draws: []}).verdict, "FAIL");
});

test("response staging changes only normal mip filtering and preserves the depth sampler", () => {
  const source = String.raw`let test_CoreSampler__init_ = (sampler, min, mag) => {
    var$9 = test_CoreFilterMode_webName(min);
    var$10 = test_CoreFilterMode_webName(mag);
    var$1 = test_CoreFilterMode_webName(min);
    sampler.handle = globalThis.GaiusWebGpu.createSampler($rt_ustr(addressU), $rt_ustr(addressV), $rt_ustr(var$9), $rt_ustr(var$10), $rt_ustr(var$1), anisotropy, maxLod);
};
let depth = () => globalThis.GaiusWebGpu.createSampler("clamp-to-edge", "clamp-to-edge", "nearest", "nearest", "nearest", 1, 0);`;
  const staged = stageLinearMipmapSampler(source);
  assert.equal(staged.receipt.nativeCallsChanged, 1);
  assert.ok(staged.source.includes('$rt_ustr(var$10), "linear", anisotropy, maxLod'));
  assert.ok(staged.source.includes('"nearest", "nearest", "nearest", 1, 0'));
  assert.throws(() => stageLinearMipmapSampler(staged.source));
  assert.throws(() => stageLinearMipmapSampler("invalid"));
});

test("texture evidence includes the smallest CPU mip and the latest upload", async () => {
  class Gl {
    RGBA = 1;
    UNSIGNED_BYTE = 2;
    bindTexture() {}
    texSubImage2D() {}
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, ArrayBuffer,
    TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), texture = {}, capture = context.__gaiusDrawCapture;
  gl.bindTexture(3, texture);
  gl.texSubImage2D(3, 0, 0, 0, 2, 2, 1, 2, new Uint8Array(16));
  gl.texSubImage2D(3, 1, 0, 0, 1, 1, 1, 2, new Uint8Array([1, 2, 3, 255]));
  const frame = async () => {
    const pending = capture.frame();
    capture.boundary();
    capture.record("terrain", {}, [6], [texture], null, value => value ? {uploads: capture.texture(value)} : null);
    capture.boundary();
    return await pending;
  };
  const before = await frame();
  assert.deepEqual(Array.from(before.draws[0].vertices[0].uploads, entry => entry.mip), [0, 1]);
  gl.texSubImage2D(3, 1, 0, 0, 1, 1, 1, 2, new Uint8Array([1, 2, 3, 127]));
  const after = await frame();
  assert.equal(after.draws[0].vertices[0].uploads[1].covered, 1);
  assert.equal(before.draws[0].vertices[0].uploads[0].sha256, after.draws[0].vertices[0].uploads[0].sha256);
  assert.notEqual(before.draws[0].vertices[0].uploads[1].sha256, after.draws[0].vertices[0].uploads[1].sha256);
  assert.equal(compareDrawInputs(before, after).verdict, "FAIL");
});

test("unresolvable draw evidence is reported without interrupting the renderer", async () => {
  const context = vm.createContext({crypto: webcrypto, Uint8Array, ArrayBuffer, TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const capture = context.__gaiusDrawCapture, pending = capture.frame();
  capture.boundary();
  assert.doesNotThrow(() => capture.record("terrain", {}, [6], [{}], null, () => { throw new Error("missing resource"); }));
  capture.boundary();
  const frame = await pending;
  assert.equal(compareDrawInputs(frame, frame).verdict, "FAIL");
  assert.match(frame.failures[0], /missing resource/);
});

test("referenced mesh hashes ignore arena placement but detect consumed bytes and index order", async () => {
  class Gl {
    bindBuffer() {}
    bufferData() {}
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, Uint32Array, ArrayBuffer,
    DataView, TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const capture = context.__gaiusDrawCapture, gl = new Gl();
  const frame = async (base, first, changed = false) => {
    const vertices = {}, indices = {}, pass = {};
    const vertexData = new Uint8Array((base + 4) * 2);
    vertexData.set([11, 12, 21, 22, 31, changed ? 33 : 32, 41, 42], base * 2);
    const indexData = new Uint32Array(first + 6);
    indexData.set([0, 1, 2, 2, 3, 0], first);
    gl.bindBuffer(1, vertices); gl.bufferData(1, vertexData);
    gl.bindBuffer(2, indices); gl.bufferData(2, indexData);
    capture.indexWidth(pass, 4);
    const pending = capture.frame(); capture.boundary();
    capture.record("terrain", pass, [6, 1, first, base, 0], [vertices], indices,
      (value, range) => range.kind === "indices"
        ? capture.indexSlice(value, 0, range.first, range.count, range.width)
        : capture.snapshot(value, range.offset, range.length), [{stride: 2, stepRate: 0}]);
    capture.boundary(); return await pending;
  };
  const before = await frame(4, 3), moved = await frame(12, 8);
  const same = compareDrawInputs(before, moved);
  assert.equal(same.verdict, "PASS");
  assert.equal(same.addressDifferences.length, 1);
  assert.equal(same.drawHashes[0].referenceSha256, same.drawHashes[0].candidateSha256);
  assert.equal(before.draws[0].vertices[0].length, 8);
  assert.equal(compareDrawInputs(before, await frame(12, 8, true)).verdict, "FAIL");
  const shuffled = structuredClone(moved); shuffled.draws[0].indices.sha256 = "different";
  assert.equal(compareDrawInputs(before, shuffled).verdict, "FAIL");
});

test("offline staging is explicit and refuses duplicate or missing providers", () => {
  const source = 'let test_MojangBlockListSupplier_createBlockList = $this => {\n    return remote();\n};';
  const staged = stageOfflineCapture(source);
  assert.ok(staged.source.includes('if(globalThis.__gaiusCaptureOffline)return null;'));
  assert.ok(staged.source.includes('return remote();'));
  assert.throws(() => stageOfflineCapture(staged.source));
  assert.throws(() => stageOfflineCapture("missing provider"));
});

test("native upload diagnostics cannot suppress the original operation", () => {
  class Gl {
    calls = 0;
    bufferSubData() { this.calls++; }
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, ArrayBuffer,
    TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl();
  assert.doesNotThrow(() => gl.bufferSubData(1, 0, new Uint8Array([1])));
  assert.equal(gl.calls, 1);
});

test("mapped GPU evidence follows every returned offset and copy range", async () => {
  class BufferObject {
    getMappedRange(offset, length) { return new ArrayBuffer(length); }
    unmap() {}
  }
  class Queue { writeBuffer() {} writeTexture() {} }
  class Encoder { copyBufferToBuffer() {} copyBufferToTexture() {} }
  class Device { createShaderModule() {} createSampler() {} createRenderPipeline() {} createRenderPipelineAsync() {} }
  const context = vm.createContext({GPUBuffer: BufferObject, GPUQueue: Queue, GPUCommandEncoder: Encoder,
    GPUDevice: Device, crypto: webcrypto, Uint8Array, ArrayBuffer, TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const source = new BufferObject(), target = new BufferObject(), encoder = new Encoder();
  new Uint8Array(source.getMappedRange(0, 4)).set([1, 2, 3, 4]);
  new Uint8Array(source.getMappedRange(16, 4)).set([5, 6, 7, 8]);
  encoder.copyBufferToBuffer(source, 16, target, 0, 4);
  const capture = context.__gaiusDrawCapture, pending = capture.frame();
  capture.boundary();
  capture.record("copy", {}, [1], [target], null, value => capture.snapshot(value, 0, 4));
  capture.boundary();
  const frame = await pending;
  assert.equal(frame.failures.length, 0);
  assert.equal(frame.draws[0].vertices[0].sha256, Buffer.from(await webcrypto.subtle.digest("SHA-256", new Uint8Array([5, 6, 7, 8]))).toString("hex"));
});

test("logical section draws suppress duplicate batch draws and bind named uniforms", async () => {
  const context = vm.createContext({crypto: webcrypto, Uint8Array, ArrayBuffer, TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const capture = context.__gaiusDrawCapture, pass = {}, pending = capture.frame();
  capture.boundary();
  capture.logical(pass);
  assert.equal(capture.physical(pass), false);
  capture.record("section", pass, [6], [], null, value => ({sha256: value}), null,
    name => name === "ChunkSection" ? 0 : -1, [["ChunkSection", "current-section"], ["Unused", "unused"]]);
  capture.boundary();
  const frame = await pending;
  assert.equal(frame.draws.length, 1);
  assert.equal(frame.draws[0].uniforms.length, 1);
  assert.equal(frame.draws[0].uniforms[0].name, "ChunkSection");
  assert.equal(frame.draws[0].uniforms[0].sha256, "current-section");
});

test("present staging skips only a native callback and preserves resumable yields", () => {
  const source = '$rt_java.test_BrowserSdlDom_presentYield = (hidden, interval) => {\n    yieldFrame(hidden, interval);\n};';
  const staged = stagePresentCapture(source);
  assert.ok(staged.source.includes('if($rt_nativeThread()===null)return;'));
  assert.ok(staged.source.includes('yieldFrame(hidden, interval);'));
  assert.throws(() => stagePresentCapture(staged.source));
  assert.throws(() => stagePresentCapture("missing yield"));
});

test("section comparison preserves duplicate FIFO identities and reports order and padding", () => {
  const draw = (mesh, position, padding = "aa") => ({pipeline: "terrain", args: [6], formats: [],
    vertices: [{sha256: mesh}], indices: {sha256: "indices"},
    uniforms: [{name: "ChunkSection", length: 256, hex: position.repeat(12) + "0000803f" + padding.repeat(240)}]});
  const first = draw("first", "01"), second = draw("second", "01"), other = draw("other", "02");
  const matched = compareTerrainSections({draws: [first, second, other]}, {draws: [draw("first", "01", "bb"), other, second]});
  assert.equal(matched.sameMesh, 3);
  assert.equal(matched.differentMesh, 0);
  assert.equal(matched.differentOrder, 2);
  assert.equal(matched.paddingOnlyUniforms, 1);
  assert.equal(matched.differentConsumedUniforms, 0);
  assert.deepEqual(matched.consumedUniformDifferencesByName, {});
  const missing = compareTerrainSections({draws: [first, second]}, {draws: [first]});
  assert.equal(missing.sameMesh, 1);
  assert.equal(missing.missing, 1);
});

test("projection differences remain raw input failures and are classified by name", () => {
  const section = {name: "ChunkSection", length: 256, hex: "00".repeat(256)};
  const draw = value => ({pipeline: "terrain", args: [6], formats: [], vertices: [], indices: null,
    uniforms: [section, {name: "Projection", length: 64, hex: value.repeat(64), sha256: value}]});
  const reference = {schema: 2, boundary: "surface-present", normalization: "referenced-index-window", failures: [], draws: [draw("00")]};
  const candidate = {...reference, draws: [draw("01")]};
  const result = compareDrawInputs(reference, candidate);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.candidateDrawCount, 1);
  assert.notEqual(result.drawHashes[0].referenceSha256, result.drawHashes[0].candidateSha256);
  assert.deepEqual(result.terrainSections.consumedUniformDifferencesByName, {Projection: 1});
});

test("texture hashes are upload-partition invariant and honor unpack rows and framebuffer copies", async () => {
  class Gl {
    RGBA = 1; UNSIGNED_BYTE = 2; TEXTURE0 = 3; TEXTURE_2D = 4;
    UNPACK_ROW_LENGTH = 5; UNPACK_SKIP_ROWS = 6; UNPACK_SKIP_PIXELS = 7; UNPACK_ALIGNMENT = 8;
    FRAMEBUFFER = 9; READ_FRAMEBUFFER = 10; DRAW_FRAMEBUFFER = 11; COLOR_ATTACHMENT0 = 12; COLOR_BUFFER_BIT = 16;
    bindTexture() {} texStorage2D() {} texSubImage2D() {} pixelStorei() {} bindFramebuffer() {}
    framebufferTexture2D() {} readBuffer() {} copyTexSubImage2D() {} blitFramebuffer() {} deleteTexture() {}
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, ArrayBuffer,
    TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), original = {}, partitioned = {}, copied = {}, framebuffer = {}, capture = context.__gaiusDrawCapture;
  const data = new Uint8Array(Array.from({length: 16}, (_, index) => index));
  gl.bindTexture(gl.TEXTURE_2D, original); gl.texStorage2D(gl.TEXTURE_2D, 1, 1, 2, 2);
  gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, 2, 2, gl.RGBA, gl.UNSIGNED_BYTE, data);
  gl.bindTexture(gl.TEXTURE_2D, partitioned); gl.texStorage2D(gl.TEXTURE_2D, 1, 1, 2, 2);
  gl.pixelStorei(gl.UNPACK_ROW_LENGTH, 2); gl.pixelStorei(gl.UNPACK_SKIP_PIXELS, 1);
  gl.texSubImage2D(gl.TEXTURE_2D, 0, 1, 0, 1, 2, gl.RGBA, gl.UNSIGNED_BYTE, data);
  gl.pixelStorei(gl.UNPACK_SKIP_PIXELS, 0);
  gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, 1, 2, gl.RGBA, gl.UNSIGNED_BYTE, data);
  gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, partitioned, 0);
  gl.bindTexture(gl.TEXTURE_2D, copied); gl.texStorage2D(gl.TEXTURE_2D, 1, 1, 2, 2);
  gl.copyTexSubImage2D(gl.TEXTURE_2D, 0, 0, 0, 0, 0, 2, 2);
  const pending = capture.frame(); capture.boundary();
  for (const texture of [original, partitioned, copied]) capture.record("texture", {}, [1], [texture], null, value => ({uploads: capture.texture(value)}));
  capture.boundary(); const frame = await pending;
  assert.equal(frame.failures.length, 0);
  assert.equal(frame.draws[0].vertices[0].uploads[0].covered, 4);
  assert.equal(frame.draws[0].vertices[0].uploads[0].sha256, frame.draws[1].vertices[0].uploads[0].sha256);
  assert.equal(frame.draws[0].vertices[0].uploads[0].sha256, frame.draws[2].vertices[0].uploads[0].sha256);
});

test("unallocated upload bounds grow monotonically on both axes and remain incomplete", async () => {
  class Gl {
    RGBA = 1; UNSIGNED_BYTE = 2;
    bindTexture() {} texSubImage2D() {}
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, ArrayBuffer,
    TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), texture = {}, capture = context.__gaiusDrawCapture;
  gl.bindTexture(3, texture);
  gl.texSubImage2D(3, 0, 0, 0, 1, 4, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(16));
  gl.texSubImage2D(3, 0, 1, 0, 3, 1, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(12));
  const pending = capture.frame(); capture.boundary();
  capture.record("texture", {}, [1], [texture], null, value => ({uploads: capture.texture(value)}));
  capture.boundary(); const frame = await pending;
  assert.equal(frame.failures.length, 0);
  const level = frame.draws[0].vertices[0].uploads[0];
  assert.equal(level.width, 4); assert.equal(level.height, 4); assert.equal(level.covered, 7);
  assert.equal(level.unknown, true);
  assert.equal(compareDrawInputs(frame, frame).verdict, "FAIL");
});

test("native mip readback removes row padding and restores GL pack and framebuffer state", async () => {
  class Gl {
    RGBA = 1; UNSIGNED_BYTE = 2; TEXTURE_2D = 3; READ_FRAMEBUFFER = 4;
    READ_FRAMEBUFFER_BINDING = 5; COLOR_ATTACHMENT0 = 6; FRAMEBUFFER_COMPLETE = 7;
    PIXEL_PACK_BUFFER = 8; PIXEL_PACK_BUFFER_BINDING = 9; PACK_ALIGNMENT = 10;
    PACK_ROW_LENGTH = 11; PACK_SKIP_ROWS = 12; PACK_SKIP_PIXELS = 13; NO_ERROR = 0;
    state = new Map([[5, "original-framebuffer"], [9, "original-pack-buffer"], [10, 8], [11, 7], [12, 2], [13, 1]]);
    bindTexture() {} texStorage2D() {} createFramebuffer() { return {}; } deleteFramebuffer() {} readBuffer() {}
    getParameter(parameter) { return this.state.get(parameter); }
    pixelStorei(parameter, value) { this.state.set(parameter, value); }
    bindFramebuffer(target, value) { this.state.set(this.READ_FRAMEBUFFER_BINDING, value); }
    bindBuffer(target, value) { this.state.set(this.PIXEL_PACK_BUFFER_BINDING, value); }
    framebufferTexture2D(target, attachment, textureTarget, texture, mip) { this.mip = mip; }
    checkFramebufferStatus() { return this.FRAMEBUFFER_COMPLETE; } getError() { return 0; }
    readPixels(x, y, width, height, format, type, raw) {
      raw.set(Array.from({length: raw.length}, (_, index) => index + this.mip * 17));
    }
  }
  class Queue {
    submit(commands) {
      for (const {source, destination, extent} of commands) {
        const raw = new Uint8Array(destination.buffer.data);
        for (let row = 0; row < extent.height; row++)
          for (let column = 0; column < extent.width * 4; column++)
            raw[row * destination.bytesPerRow + column] = row * extent.width * 4 + column + source.mipLevel * 17;
      }
    }
  }
  class Buffer {
    constructor(size) { this.data = new ArrayBuffer(size); }
    async mapAsync() {} getMappedRange() { return this.data; } unmap() {} destroy() {}
  }
  class Device {
    queue = new Queue();
    createTexture(descriptor) { return {width: descriptor.size[0], height: descriptor.size[1], format: descriptor.format, mipLevelCount: 2, usage: descriptor.usage}; }
    createBuffer(descriptor) { return new Buffer(descriptor.size); }
    createCommandEncoder() { return {copyTextureToBuffer(source, destination, extent) { this.command = {source, destination, extent}; }, finish() { return this.command; }}; }
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, GPUDevice: Device, GPUQueue: Queue, GPUBuffer: Buffer, GPUCommandEncoder: class {},
    crypto: webcrypto, Uint8Array, ArrayBuffer, TextEncoder, setTimeout, clearTimeout, __gaiusCaptureReadTextures: true});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), texture = {}, previous = [...gl.state], device = new Device();
  const gpuTexture = device.createTexture({size: [2, 2], format: "rgba8unorm", usage: 20});
  assert.equal(gpuTexture.usage, 21);
  const capture = context.__gaiusDrawCapture;
  gl.bindTexture(gl.TEXTURE_2D, texture);
  const pending = capture.frame(); capture.boundary();
  gl.texStorage2D(gl.TEXTURE_2D, 2, 32856, 2, 2);
  for (const native of [texture, gpuTexture]) capture.record("readback", {}, [1], [native], null,
    value => ({uploads: capture.readTexture(value, {width: 2, height: 2, mipLevels: 2})}));
  capture.boundary(); const frame = await pending;
  assert.deepEqual([...gl.state], previous);
  assert.equal(frame.failures.length, 0, frame.failures.join("; "));
  assert.equal(frame.draws[0].vertices[0].uploads.length, 2);
  assert.equal(frame.draws[0].vertices[0].uploads[0].sha256, frame.draws[1].vertices[0].uploads[0].sha256);
  assert.equal(frame.draws[0].vertices[0].uploads[1].sha256, frame.draws[1].vertices[0].uploads[1].sha256);
  assert.equal(frame.draws[0].vertices[0].uploads[1].covered, 1);
});

test("sampler evidence preserves spatial filtering, mip filtering and LOD bounds", () => {
  class Gl { samplerParameteri() {} samplerParameterf() {} }
  class Device { createSampler() { return {}; } }
  const context = vm.createContext({WebGL2RenderingContext: Gl, GPUDevice: Device, GPUQueue: class {}, GPUBuffer: class {
    getMappedRange() {} unmap() {}
  }, GPUCommandEncoder: class {}, crypto: webcrypto, Uint8Array, ArrayBuffer, TextEncoder, setTimeout, clearTimeout});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), sampler = {}, capture = context.__gaiusDrawCapture;
  gl.samplerParameteri(sampler, 10242, 33071);
  gl.samplerParameteri(sampler, 10243, 10497);
  gl.samplerParameteri(sampler, 10241, 9986);
  gl.samplerParameteri(sampler, 10240, 9728);
  gl.samplerParameterf(sampler, 33083, 0);
  const actual = capture.sampler(sampler);
  assert.equal(actual.addressModeU, "clamp-to-edge");
  assert.equal(actual.addressModeV, "repeat");
  assert.equal(actual.minFilter, "nearest");
  assert.equal(actual.magFilter, "nearest");
  assert.equal(actual.mipmapFilter, "linear");
  assert.equal(actual.lodMaxClamp, 0);
  const gpu = capture.sampler(new Device().createSampler({minFilter: "nearest", magFilter: "nearest", mipmapFilter: "linear", lodMaxClamp: 0}));
  assert.equal(gpu.mipmapFilter, "linear");
  assert.equal(gpu.lodMaxClamp, 0);
  assert.equal(gpu.lodMinClamp, 0);
  assert.equal(capture.sampler(new Device().createSampler()).mipmapFilter, "nearest");
  assert.equal(capture.sampler({}).unknown, true);
});

test("vertex column hashes name the differing attribute and the dump stays within its budget", async () => {
  class Gl {
    bindBuffer() {}
    bufferData() {}
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, Uint32Array, ArrayBuffer,
    DataView, TextEncoder, setTimeout, clearTimeout, btoa: value => Buffer.from(value, "binary").toString("base64"), String});
  vm.runInContext("globalThis.__gaiusCaptureVertexColumns=true;globalThis.__gaiusCaptureVertexDumpBytes=24;" + DRAW_CAPTURE_BOOT_SOURCE, context);
  const capture = context.__gaiusDrawCapture, gl = new Gl();
  const frame = async light => {
    const vertices = {}, pass = {};
    // Two 8-byte vertices: word 0 position, word 1 light.
    const data = new Uint8Array([1, 0, 0, 0, light, 0, 0, 0, 2, 0, 0, 0, light, 0, 0, 0]);
    gl.bindBuffer(1, vertices); gl.bufferData(1, data);
    const pending = capture.frame(); capture.boundary();
    capture.record("terrain", pass, [2, 1, 0, 0], [vertices], null,
      (value, range) => capture.snapshot(value, range.offset, range.length, range.stride), [{stride: 8, stepRate: 0}]);
    capture.record("terrain", pass, [2, 1, 0, 0], [vertices], null,
      (value, range) => capture.snapshot(value, range.offset, range.length, range.stride), [{stride: 8, stepRate: 0}]);
    capture.boundary(); return await pending;
  };
  const dark = await frame(0), lit = await frame(15);
  assert.equal(dark.draws[0].vertices[0].stride, 8);
  assert.equal(dark.draws[0].vertices[0].columns.length, 2);
  assert.equal(dark.draws[0].vertices[0].columns[0], lit.draws[0].vertices[0].columns[0]);
  assert.notEqual(dark.draws[0].vertices[0].columns[1], lit.draws[0].vertices[0].columns[1]);
  assert.deepEqual([...Buffer.from(lit.draws[0].vertices[0].base64, "base64")], [1, 0, 0, 0, 15, 0, 0, 0, 2, 0, 0, 0, 15, 0, 0, 0]);
  assert.equal(lit.draws[1].vertices[0].base64, undefined, "the second window exceeds the 24-byte dump budget");
  const sections = compareTerrainSections(
    {draws: dark.draws.slice(0, 1).map(draw => ({...draw, uniforms: [{name: "ChunkSection", hex: "00".repeat(16)}]}))},
    {draws: lit.draws.slice(0, 1).map(draw => ({...draw, uniforms: [{name: "ChunkSection", hex: "00".repeat(16)}]}))});
  assert.deepEqual(sections.differentColumns, {"1": 1});
});

test("GL textures allocated with texImage2D are eligible for verified readback, other formats are not", async () => {
  class Gl {
    RGBA = 6408; RGBA8 = 32856; UNSIGNED_BYTE = 5121; FLOAT = 5126; TEXTURE_2D = 3553; TEXTURE0 = 33984; READ_FRAMEBUFFER = 36008;
    READ_FRAMEBUFFER_BINDING = 36010; COLOR_ATTACHMENT0 = 36064; FRAMEBUFFER_COMPLETE = 36053; PIXEL_PACK_BUFFER = 35051;
    PIXEL_PACK_BUFFER_BINDING = 35053; PACK_ALIGNMENT = 3333; PACK_ROW_LENGTH = 3330; PACK_SKIP_ROWS = 3331; PACK_SKIP_PIXELS = 3332; NO_ERROR = 0;
    bindTexture() {} texImage2D() {} createFramebuffer() { return {}; } deleteFramebuffer() {} readBuffer() {} getParameter() { return 0; }
    pixelStorei() {} bindFramebuffer() {} bindBuffer() {} framebufferTexture2D() {} checkFramebufferStatus() { return this.FRAMEBUFFER_COMPLETE; }
    getError() { return 0; } readPixels(x, y, width, height, format, type, raw) { raw.fill(7); }
  }
  const context = vm.createContext({WebGL2RenderingContext: Gl, crypto: webcrypto, Uint8Array, ArrayBuffer, TextEncoder,
    setTimeout, clearTimeout, __gaiusCaptureReadTextures: true, __gaiusCaptureBufferOnly: true});
  vm.runInContext(DRAW_CAPTURE_BOOT_SOURCE, context);
  const gl = new Gl(), atlas = {}, depth = {}, capture = context.__gaiusDrawCapture;
  gl.bindTexture(gl.TEXTURE_2D, atlas);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 4, 4, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  gl.texImage2D(gl.TEXTURE_2D, 1, gl.RGBA8, 2, 2, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  gl.bindTexture(gl.TEXTURE_2D, depth);
  gl.texImage2D(gl.TEXTURE_2D, 0, 36012, 4, 4, 0, 6402, gl.FLOAT, null);
  const pending = capture.frame(); capture.boundary();
  for (const native of [atlas, depth]) capture.record("readback", {}, [1], [native], null,
    value => ({uploads: capture.readTexture(value, {width: 4, height: 4, mipLevels: 2})}));
  capture.boundary(); const frame = await pending;
  assert.equal(JSON.stringify(frame.draws[0].vertices[0].uploads.map(mip => [mip.mip, mip.width, mip.unknown])), "[[0,4,false],[1,2,false]]");
  assert.equal(frame.draws[1].vertices[0].uploads[0].unknown, true);
  assert.match(frame.failures.join("; "), /verified RGBA8/);
});

test("strict comparison covers only the declared uniform block, never shorter or unknown blocks", () => {
  const frame = (section, globals = "11".repeat(48)) => ({schema: 2, boundary: "surface-present", normalization: "referenced-index-window", failures: [],
    draws: [{pipeline: "terrain", args: [6], formats: [], vertices: [], indices: null, uniforms: [
      {name: "ChunkSection", length: 256, sha256: section.slice(0, 64).padEnd(64, "0"), hex: section},
      {name: "Globals", length: 48, sha256: "g".repeat(64), hex: globals}]}]});
  const block = "01000000".repeat(3) + "0000803f";
  // Stale bytes after the 16-byte ChunkSection block are not inputs.
  assert.equal(compareDrawInputs(frame(block + "aa".repeat(240)), frame(block + "bb".repeat(240))).verdict, "PASS");
  // A changed position or visibility inside the block is.
  assert.equal(compareDrawInputs(frame(block + "aa".repeat(240)), frame("02000000" + block.slice(8) + "aa".repeat(240))).verdict, "FAIL");
  assert.equal(compareDrawInputs(frame(block + "aa".repeat(240)), frame(block + "aa".repeat(240), "12".repeat(48))).verdict, "FAIL");
  // A slice shorter than its declared block keeps every recorded byte.
  const short = frame(block); short.draws[0].uniforms[0].length = 8;
  assert.equal(compareDrawInputs(short, frame(block)).verdict, "FAIL");
});

test("opaque terrain draw order is canonical, translucent and other draw order stays strict", () => {
  const section = (pipeline, position) => ({pipeline, args: [6], formats: [], vertices: [{sha256: pipeline + position}], indices: null,
    uniforms: [{name: "ChunkSection", length: 16, hex: position.repeat(16)}]});
  const frame = draws => ({schema: 2, boundary: "surface-present", normalization: "referenced-index-window", failures: [], draws});
  const solid = "minecraft:pipeline/solid_terrain", translucent = "minecraft:pipeline/translucent_terrain";
  const a = section(solid, "01"), b = section(solid, "02"), c = section(translucent, "01"), d = section(translucent, "02");
  assert.equal(compareDrawInputs(frame([a, b, c, d]), frame([b, a, c, d])).verdict, "PASS");
  assert.equal(compareDrawInputs(frame([a, b, c, d]), frame([a, b, d, c])).verdict, "FAIL");
  // Reordering never moves a draw across a different pipeline.
  assert.equal(compareDrawInputs(frame([a, c, b]), frame([b, c, a])).verdict, "FAIL");
  assert.equal(compareTerrainSections(frame([a, b]), frame([b, a])).differentOrder, 2, "recorded order stays visible in the summary");
});
