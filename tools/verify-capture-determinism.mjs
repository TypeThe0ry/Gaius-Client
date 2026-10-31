import assert from "node:assert/strict";
import {mkdir, readFile, realpath, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {pathToFileURL} from "node:url";
import {compareDrawInputs} from "./compare-draw-inputs.mjs";
import {reportCaptureDiff} from "./capture-pixel-diff.mjs";
import {CLIENT_COUNTERS, captureCapabilityFailures, captureChunkBounds, drawInputFingerprint, quiescenceFailures} from "./capture-quiescence.mjs";
import {normalizeBackendFrame} from "./backend-clip-space.mjs";

const viewNames = ["horizon", "entities", "high", "topdown"];
const json = async path => JSON.parse(await readFile(path, "utf8"));

/** Re-checks a quiescence receipt from its own bounded evidence instead of trusting `complete`. */
export function validateQuiescenceReceipt(receipt, bounds, fingerprint = null) {
  assert.equal(receipt?.schema, 2, "quiescence receipt required");
  assert.equal(receipt.source, "vanilla", "vanilla state required");
  assert.equal(receipt.complete, true, "quiescence incomplete");
  assert.ok(Number.isInteger(receipt.stableFrames) && receipt.stableFrames >= 4, "at least four stable observations required");
  assert.ok(receipt.stableMs >= receipt.minStableMs, "stable window shorter than required");
  assert.deepEqual(receipt.bounds, bounds, "quiescence view bounds");
  assert.equal(receipt.fingerprint, fingerprint, "same stable input fingerprint");
  const tail = receipt.history?.slice(-receipt.stableFrames);
  assert.equal(tail?.length, receipt.stableFrames, "stable observation history required");
  const samples = {client: 0, network: 0}, requests = new Set();
  const base = tail[0].before.summary;
  let previousPresent = 0;
  for (const observation of tail) {
    assert.deepEqual(observation.failures, [], "busy stable observation");
    for (const sample of [observation.before, ...(fingerprint ? [observation.after] : [])]) {
      assert.deepEqual(sample?.request?.bounds, bounds, "sample view bounds");
      assert.deepEqual(quiescenceFailures(sample.summary, sample.request), [], "unknown or busy vanilla state");
      assert.ok(!requests.has(sample.request.requestId), "duplicate observation request");
      requests.add(sample.request.requestId);
      for (const realm of ["client", "network"]) {
        assert.ok(sample.summary[realm].sample > samples[realm], "stale " + realm + " sample");
        samples[realm] = sample.summary[realm].sample;
      }
      assert.deepEqual(sample.summary.network.packets, base.network.packets, "world packets arrived in the stable window");
      assert.equal(sample.summary.client.loadedSha256, base.client.loadedSha256, "loaded chunks changed in the stable window");
    }
    if (fingerprint) {
      assert.equal(observation.fingerprint, fingerprint, "unstable per-draw inputs");
      assert.ok(Number.isSafeInteger(observation.present?.[0]) && observation.present[0] > previousPresent
        && observation.present[1] === observation.present[0] + 1, "distinct complete presents required");
      previousPresent = observation.present[1];
    }
  }
  return base;
}

function validateBracket(bracket, bounds, fingerprint, base) {
  assert.deepEqual(bracket?.failures, [], "screenshot bracket failed");
  assert.equal(bracket.fingerprint, fingerprint, "screenshot draw inputs changed");
  for (const sample of [bracket.before, bracket.after]) {
    assert.deepEqual(sample.request.bounds, bounds, "bracket view bounds");
    assert.deepEqual(quiescenceFailures(sample.summary, sample.request), [], "bracket vanilla state busy");
    assert.deepEqual(sample.summary.network.packets, base.network.packets, "world packets arrived around a screenshot");
    assert.equal(sample.summary.client.loadedSha256, base.client.loadedSha256, "loaded chunks changed around a screenshot");
  }
}

async function readCapture(directory, backend, requireWasm) {
  const receipt = await json(join(directory, "capture.json"));
  assert.equal(receipt.complete, true, "capture incomplete");
  assert.equal(receipt.schema, 2, "quiescence-gated capture required");
  assert.match(receipt.runIdentity?.id || "", /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, "independent browser run identity required");
  assert.deepEqual(captureCapabilityFailures(receipt.capabilities, {requireWasm}), [], "capture capabilities");
  assert.match(receipt.clientIdentity?.artifact?.sha256 || "", /^[a-f0-9]{64}$/, "exact client artifact identity required");
  assert.deepEqual(Object.keys(receipt.views), viewNames, "four capture views required");
  const frames = {}, worlds = {};
  for (const name of viewNames) {
    const view = receipt.views[name];
    const bounds = captureChunkBounds(view.camera, receipt.quiescence.radius);
    frames[name] = await json(join(directory, "draw-inputs-" + name + ".json"));
    assert.equal(frames[name].backend, backend, "actual draw backend");
    const fingerprint = drawInputFingerprint(frames[name]);
    validateQuiescenceReceipt(view.drained, bounds);
    const base = validateQuiescenceReceipt(view.stable, bounds, fingerprint);
    assert.equal(view.screenshotInputs?.length, 3, "three bracketed screenshots required");
    for (const inputs of view.screenshotInputs) {
      validateBracket(inputs.before, bounds, fingerprint, base);
      validateBracket(inputs.after, bounds, fingerprint, base);
    }
    for (const state of view.states) {
      assert.ok(state.pins?.lightFlicker && Number.isInteger(state.pins.gameTime), "render clock pins inactive at screenshot");
    }
    worlds[name] = {loadedSha256: base.client.loadedSha256, loadedCount: base.client.loadedCount,
      trackedSections: base.client.trackedSections, dimension: base.dimension};
    for (const counter of CLIENT_COUNTERS) assert.equal(base.client[counter], 0, "idle " + counter);
  }
  let content = null, light = null;
  try {
    content = await json(join(directory, "world-content.json"));
    light = await json(join(directory, "world-digest-" + viewNames[0] + ".json"));
  } catch {}
  return {receipt, frames, worlds, content, light};
}

/** Diagnostic: chunks whose block states, biomes or final light differ from the first run. */
function worldDifferences(captures) {
  const [first, ...rest] = captures;
  return rest.map(capture => {
    if (!first.content || !capture.content || !first.light || !capture.light) return null;
    const count = (left, right, field) => Object.keys(left).filter(key => left[key]?.[field] !== right[key]?.[field]);
    return {blocks: count(first.content, capture.content, "blocks"), biomes: count(first.content, capture.content, "biomes"),
      skyLight: count(first.light, capture.light, "sky"), blockLight: count(first.light, capture.light, "block")};
  });
}

const compareViews = (left, right, normalize = false) => Object.fromEntries(viewNames.map(name => [name,
  normalize ? compareDrawInputs(normalizeBackendFrame(left.frames[name]), normalizeBackendFrame(right.frames[name]))
    : compareDrawInputs(left.frames[name], right.frames[name])]));
const brief = views => Object.fromEntries(Object.entries(views).map(([name, view]) => [name, {verdict: view.verdict,
  draws: [view.drawCount, view.candidateDrawCount], differences: view.differences.length, unknown: view.unknown.length,
  errors: view.errors.length, terrain: view.terrainSections, firstDifferences: view.differences.slice(0, 6).map(entry => entry.path)}]));

/**
 * The four-run gate: two WebGL2 and two WebGPU captures. Each backend must first reproduce its own
 * per-draw inputs exactly (NONDETERMINISTIC otherwise); only then are the backends compared, after
 * the documented clip-space normalization of the projection (INPUT_MISMATCH otherwise). Pixels are
 * judged only for identical inputs. A pixel report for mismatched inputs is kept as diagnostics.
 */
export async function verifyCaptureDeterminism(directories, outputPath, {requireWasm = false} = {}) {
  const report = {schema: 2, verdict: "BLOCKED", sameBackend: {}, crossBackend: null, worlds: null, pixels: null};
  await mkdir(outputPath, {recursive: true});
  try {
    assert.equal(directories.length, 4, "two WebGL2 and two WebGPU directories required");
    const paths = await Promise.all(directories.map(directory => realpath(directory)));
    assert.equal(new Set(paths.map(path => process.platform === "win32" ? path.toLowerCase() : path)).size, 4, "four independent run directories required");
    const captures = [];
    for (const [index, path] of paths.entries()) captures.push(await readCapture(path, index < 2 ? "webgl2" : "webgpu", requireWasm));
    assert.equal(new Set(captures.map(capture => capture.receipt.runIdentity.id)).size, 4, "four independent browser runs required");
    for (const capture of captures.slice(1)) {
      for (const field of ["profile", "seed", "settings", "options", "clientIdentity", "worldSource"]) {
        assert.deepEqual(capture.receipt[field], captures[0].receipt[field], "same " + field);
      }
      for (const field of ["originalSha256", "stagedSha256"]) {
        assert.equal(capture.receipt.stageReceipt?.[field], captures[0].receipt.stageReceipt?.[field], "same staged client " + field);
      }
      for (const name of viewNames) assert.deepEqual(capture.receipt.views[name].camera, captures[0].receipt.views[name].camera, "same camera " + name);
    }
    report.worlds = captures.map(capture => capture.worlds);
    const sameWorld = viewNames.every(name => new Set(captures.map(capture => capture.worlds[name].loadedSha256)).size === 1);
    report.sameLoadedChunks = sameWorld;
    report.worldDifferences = worldDifferences(captures);
    report.sameBackend.webgl2 = brief(compareViews(captures[0], captures[1]));
    report.sameBackend.webgpu = brief(compareViews(captures[2], captures[3]));
    if (Object.values(report.sameBackend).flatMap(Object.values).some(view => view.verdict !== "PASS")) {
      report.verdict = "NONDETERMINISTIC";
    } else {
      report.crossBackend = brief(compareViews(captures[0], captures[2], true));
      report.pixels = await reportCaptureDiff(paths[0], paths[2], join(outputPath, "pixels"));
      report.verdict = !sameWorld || Object.values(report.crossBackend).some(view => view.verdict !== "PASS")
        ? "INPUT_MISMATCH" : report.pixels.verdict;
    }
  } catch (error) {
    report.error = String(error.stack || error);
  }
  await writeFile(join(outputPath, "determinism.json"), JSON.stringify(report, null, 2) + "\n");
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [webglFirst, webglSecond, webgpuFirst, webgpuSecond, output] = process.argv.slice(2);
  if (!output) throw new Error("usage: verify-capture-determinism.mjs GL-A GL-B GPU-A GPU-B OUTPUT");
  const report = await verifyCaptureDeterminism([webglFirst, webglSecond, webgpuFirst, webgpuSecond], resolve(output),
    {requireWasm: process.env.CAPTURE_REQUIRE_WASM === "1"});
  console.log(JSON.stringify({verdict: report.verdict, error: report.error?.split("\n")[0]}));
  if (report.verdict !== "PASS") process.exitCode = 1;
}
