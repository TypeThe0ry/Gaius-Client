import test from "node:test";
import assert from "node:assert/strict";
import {deflateSync} from "node:zlib";
import {aggregateRuns, comparePngPixels, deriveChunkArrivalLatency, makeLowendPreamble, parseList, parseRunReport} from "./lowend-profile-cdp.mjs";

test("low-end profile preamble pins the page contract", () => {
  const source = makeLowendPreamble({mode: "chromebook"});
  assert.match(source, /hardwareConcurrency/);
  assert.match(source, /deviceMemory/);
  assert.match(source, /low-power/);
  assert.match(source, /GaiusLowendWorker/);
});

test("parseList rejects empty numeric input and de-duplicates distances", () => {
  assert.deepEqual(parseList("8,16,8", [32]), [8, 16]);
  assert.deepEqual(parseList("", [8, 16]), [8, 16]);
  assert.throws(() => parseList("rd8", [8]), /positive integer list/);
});

test("frame and chunk aggregation fail closed when evidence is absent", () => {
  const report = {verdict: "PASS", seed: "26300", releasePerformance: {renderDistance: 8, simulationDistance: 12, walk: {
    pacing: {fps: 50, spanMs: 1000, intervals: [20, 20, 40]}, path: [
      {ms: 5000, loaded: 289, player: {x: 0, z: 0}, audit: {visible: 10, uncompiled: 0, lost: 0}},
      {ms: 10000, loaded: 289, player: {x: 16, z: 0}, audit: {visible: 10, uncompiled: 0, lost: 0}},
    ],
  }}, checks: {releaseFullSettle: {ok: true}}, backendState: {gpu: {backend: "webgpu"}, runtime: {selected: "wasm"}}};
  const record = parseRunReport(report, {mode: "chromebook", distance: 8, run: 1, runDir: "fixture"});
  assert.equal(record.actualSimulationDistance, 12);
  assert.equal(record.frameTimes.p50FrameMs, 20);
  assert.equal(record.chunkArrivalLatency.p50Ms, 0);
  assert.equal(record.fullDistanceSettle.ok, true);
  const aggregate = aggregateRuns([record, {...record, run: 2, fps: 40}]);
  assert.equal(aggregate[0].runs, 2);
  assert.equal(aggregate[0].fpsMedian, 40);
  const missing = parseRunReport({verdict: "FAIL"}, {mode: "unthrottled", distance: 32, run: 1, runDir: "fixture"});
  assert.equal(missing.frameTimes.p95FrameMs, null);
  assert.equal(missing.chunkArrivalLatency.inconclusive, true);
});

test("chunk latency records unresolved areas instead of inventing a pass", () => {
  const result = deriveChunkArrivalLatency([{ms: 5000, player: {x: 0, z: 0}, loaded: 1, audit: {}},
    {ms: 10000, player: {x: 16, z: 0}, loaded: 1, audit: {uncompiled: 1, lost: 0}}], 8);
  assert.equal(result.observedCount, 0);
  assert.equal(result.inconclusive, true);
});

test("PNG pixel comparison distinguishes equal and changed screenshots", () => {
  const png = rgba => {
    const chunk = (type, data) => { const header = Buffer.alloc(8); header.writeUInt32BE(data.length, 0); header.write(type, 4, "ascii"); return Buffer.concat([header, data, Buffer.alloc(4)]); };
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4); ihdr[8] = 8; ihdr[9] = 6;
    return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(Buffer.from([0, ...rgba]))), chunk("IEND", Buffer.alloc(0))]);
  };
  const equal = png([255, 0, 0, 255]);
  const changed = png([0, 255, 0, 255]);
  assert.equal(comparePngPixels(equal, equal).exact, true);
  assert.equal(comparePngPixels(equal, changed).exact, false);
});
