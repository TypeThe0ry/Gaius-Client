import {readFile, writeFile, mkdir} from "node:fs/promises";
import {join, resolve} from "node:path";
import {pathToFileURL} from "node:url";
import assert from "node:assert/strict";
import {createHash} from "node:crypto";

/**
 * std140 sizes of the uniform blocks declared by the vanilla shader includes (chunksection,
 * terrainglobals, globals, fog, projection, dynamictransforms). A bound uniform slice is padded
 * to the device's offset alignment and the bytes past the block are whatever the ring buffer held
 * before, which no shader reads; only the declared block takes part in the comparison. A slice
 * shorter than its block, or a block without its bytes, is compared in full and so still fails.
 */
export const UNIFORM_BLOCK_BYTES = Object.freeze({ChunkSection: 16, TerrainUniform: 72, Globals: 48,
  Fog: 40, Projection: 64, DynamicTransforms: 156});

function consumedUniform(value) {
  const size = UNIFORM_BLOCK_BYTES[value.name];
  if (!size || typeof value.hex !== "string" || value.hex.length < size * 2 || value.length < size) return null;
  return {name: value.name, block: size, hex: value.hex.slice(0, size * 2)};
}

function semantic(value) {
  if (Array.isArray(value)) return value.map(semantic);
  if (value && typeof value === "object") {
    const consumed = consumedUniform(value);
    if (consumed) return consumed;
    return Object.fromEntries(Object.entries(value)
      .filter(([key]) => value.kind !== "texture" || key !== "label").map(([key, entry]) => [key, semantic(entry)]));
  }
  return value;
}

/**
 * Terrain pipelines that draw without blending (opaque and alpha-tested). Sections are disjoint
 * boxes and depth testing decides every pixel, so the order in which these sections are drawn
 * is not an input of the image; vanilla adds sections to its visible list as their compiles
 * finish, which makes that order depend on compile timing. Within each run of consecutive draws
 * of one of these pipelines the comparison orders draws by section position. Translucent and
 * every other draw keep their recorded order.
 */
export const ORDER_INDEPENDENT_PIPELINES = Object.freeze(["minecraft:pipeline/solid_terrain", "minecraft:pipeline/cutout_terrain"]);

export function canonicalDrawOrder(frame) {
  const draws = [];
  const sectionKey = draw => draw.uniforms?.find(uniform => uniform.name === "ChunkSection")?.hex?.slice(0, 32) ?? null;
  for (let index = 0; index < frame.draws.length;) {
    const pipeline = frame.draws[index].pipeline;
    let end = index + 1;
    if (ORDER_INDEPENDENT_PIPELINES.includes(pipeline) && sectionKey(frame.draws[index]) !== null) {
      while (end < frame.draws.length && frame.draws[end].pipeline === pipeline && sectionKey(frame.draws[end]) !== null) end++;
      draws.push(...frame.draws.slice(index, end).sort((left, right) => sectionKey(left) < sectionKey(right) ? -1 : sectionKey(left) > sectionKey(right) ? 1 : 0));
    } else draws.push(frame.draws[index]);
    index = end;
  }
  return {...frame, draws};
}

export function compareTerrainSections(reference, candidate) {
  const key = draw => {
    const section = draw.uniforms?.find(uniform => uniform.name === "ChunkSection");
    return section?.hex?.length >= 32 ? draw.pipeline + "|" + section.hex.slice(0, 24) : null;
  };
  const mesh = draw => JSON.stringify(semantic(Object.fromEntries(
    ["args", "formats", "vertices", "indices"].map(field => [field, draw[field]]))));
  const queues = new Map();
  const summary = {sameMesh: 0, differentMesh: 0, missing: 0, extra: 0, differentOrder: 0,
    differentConsumedUniforms: 0, consumedUniformDifferencesByName: {}, paddingOnlyUniforms: 0, examples: []};
  for (const [index, draw] of candidate.draws.entries()) {
    const section = key(draw);
    if (!section) continue;
    if (!queues.has(section)) queues.set(section, []);
    queues.get(section).push({index, draw});
  }
  for (const [index, draw] of reference.draws.entries()) {
    const section = key(draw);
    if (!section) continue;
    const match = queues.get(section)?.shift();
    if (!match) { summary.missing++; continue; }
    if (index !== match.index) summary.differentOrder++;
    if (mesh(draw) === mesh(match.draw)) summary.sameMesh++;
    else {
      summary.differentMesh++;
      const sameIndices = JSON.stringify(draw.indices) === JSON.stringify(match.draw.indices) && draw.args[0] === match.draw.args[0];
      summary.sameTopology = (summary.sameTopology || 0) + (sameIndices ? 1 : 0);
      // Vertex column hashes (CAPTURE_VERTEX_COLUMNS) name the 4-byte vertex words that differ.
      const left = draw.vertices?.[0], right = match.draw.vertices?.[0];
      if (sameIndices && left?.columns && right?.columns && left.columns.length === right.columns.length) {
        const words = left.columns.map((value, word) => value === right.columns[word] ? null : word).filter(word => word !== null);
        const label = words.join(",") || "none";
        summary.differentColumns = summary.differentColumns || {};
        summary.differentColumns[label] = (summary.differentColumns[label] || 0) + 1;
      }
      if (summary.examples.length < 8) {
        const position = Buffer.from(section.split("|").at(-1), "hex");
        summary.examples.push({pipeline: draw.pipeline, position: [0, 4, 8].map(offset => position.readInt32LE(offset)),
          referenceDraw: index, candidateDraw: match.index, referenceCount: draw.args[0], candidateCount: match.draw.args[0],
          referenceVertices: draw.vertices, candidateVertices: match.draw.vertices,
          referenceIndices: draw.indices, candidateIndices: match.draw.indices});
      }
    }
    for (const left of draw.uniforms || []) {
      const right = match.draw.uniforms?.find(uniform => uniform.name === left.name);
      if (!left.hex || !right?.hex) continue;
      const consumed = UNIFORM_BLOCK_BYTES[left.name] || left.length;
      if (left.hex.slice(0, consumed * 2) !== right.hex.slice(0, consumed * 2)) {
        summary.differentConsumedUniforms++;
        summary.consumedUniformDifferencesByName[left.name] = (summary.consumedUniformDifferencesByName[left.name] || 0) + 1;
      }
      else if (left.hex !== right.hex) summary.paddingOnlyUniforms++;
    }
  }
  summary.extra = [...queues.values()].reduce((count, queue) => count + queue.length, 0);
  return summary;
}

export function compareDrawInputs(recordedReference, recordedCandidate) {
  const reference = canonicalDrawOrder(recordedReference), candidate = canonicalDrawOrder(recordedCandidate);
  const differences = [], unknown = [], addressDifferences = [], drawHashes = [];
  const compare = (path, left, right) => {
    if (JSON.stringify(semantic(left)) !== JSON.stringify(semantic(right))) differences.push({path, reference: left, candidate: right});
  };
  const inspect = (value, path) => {
    if (!value || typeof value !== "object") return;
    if (value.unknown) unknown.push(path);
    for (const [key, entry] of Object.entries(value)) inspect(entry, path + "." + key);
  };
  compare("boundary", reference.boundary, candidate.boundary);
  compare("schema", reference.schema, candidate.schema);
  compare("normalization", reference.normalization, candidate.normalization);
  compare("drawCount", reference.draws.length, candidate.draws.length);
  for (let index = 0; index < Math.max(reference.draws.length, candidate.draws.length); index++) {
    const left = reference.draws[index], right = candidate.draws[index];
    inspect(left, "reference." + index); inspect(right, "candidate." + index);
    for (const field of ["pipeline", "args", "formats", "vertices", "indices", "uniforms"]) compare(index + "." + field, left?.[field], right?.[field]);
    const digest = draw => createHash("sha256").update(JSON.stringify(semantic(Object.fromEntries(
      ["pipeline", "args", "formats", "vertices", "indices", "uniforms"].map(field => [field, draw?.[field]]))))).digest("hex");
    drawHashes.push({draw: index, pipeline: left?.pipeline, referenceSha256: digest(left), candidateSha256: digest(right)});
    if (JSON.stringify(left?.rawArgs) !== JSON.stringify(right?.rawArgs)) addressDifferences.push({draw: index, reference: left?.rawArgs, candidate: right?.rawArgs});
  }
  const errors = [...reference.failures, ...candidate.failures];
  return {verdict: differences.length || unknown.length || errors.length || !reference.draws.length ? "FAIL" : "PASS",
    drawCount: reference.draws.length, candidateDrawCount: candidate.draws.length, drawHashes, differences, addressDifferences, unknown, errors,
    terrainSections: compareTerrainSections(recordedReference, recordedCandidate)};
}

export async function reportDrawInputs(referencePath, candidatePath, outputPath) {
  const [receipt, candidateReceipt] = await Promise.all([referencePath, candidatePath].map(async path => JSON.parse(await readFile(join(path, "capture.json"), "utf8"))));
  assert.ok(receipt.complete && candidateReceipt.complete, "both captures must complete");
  for (const field of ["profile", "seed", "settings", "options"]) assert.deepEqual(receipt[field], candidateReceipt[field], "same capture " + field);
  for (const field of ["originalSha256", "stagedSha256"]) {
    assert.match(receipt.stageReceipt?.[field] || "", /^[a-f0-9]{64}$/, "required staged identity");
    assert.equal(receipt.stageReceipt[field], candidateReceipt.stageReceipt?.[field], "same source " + field);
  }
  assert.deepEqual(receipt.clientIdentity, candidateReceipt.clientIdentity, "same client build identity");
  assert.deepEqual(receipt.worldSource, candidateReceipt.worldSource, "same saved world identity");
  assert.match(receipt.stageReceipt.drawInputs?.bootSha256 || "", /^[a-f0-9]{64}$/, "required exact draw instrumentation identity");
  for (const field of ["bootSha256", "bufferOnly", "readTextures"]) assert.deepEqual(receipt.stageReceipt.drawInputs?.[field], candidateReceipt.stageReceipt.drawInputs?.[field], "same draw instrumentation " + field);
  assert.deepEqual(receipt.stageReceipt.glueSha256, candidateReceipt.stageReceipt.glueSha256, "same staged GPU glue");
  assert.deepEqual(Object.keys(receipt.views), Object.keys(candidateReceipt.views), "same capture views");
  const views = {};
  for (const name of Object.keys(receipt.views)) {
    assert.deepEqual(receipt.views[name].camera, candidateReceipt.views[name].camera, "same camera " + name);
    assert.deepEqual(receipt.views[name].states[0].renderOptions, candidateReceipt.views[name].states[0].renderOptions, "same observed render options " + name);
    const reference = JSON.parse(await readFile(join(referencePath, "draw-inputs-" + name + ".json"), "utf8"));
    const candidate = JSON.parse(await readFile(join(candidatePath, "draw-inputs-" + name + ".json"), "utf8"));
    views[name] = compareDrawInputs(reference, candidate);
  }
  const report = {schema: 1, verdict: Object.values(views).every(view => view.verdict === "PASS") ? "PASS" : "FAIL", views};
  await mkdir(outputPath, {recursive: true});
  await writeFile(join(outputPath, "draw-input-comparison.json"), JSON.stringify(report, null, 2) + "\n");
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [reference, candidate, output] = process.argv.slice(2);
  const report = await reportDrawInputs(resolve(reference), resolve(candidate), resolve(output));
  console.log(JSON.stringify({verdict: report.verdict, views: Object.fromEntries(Object.entries(report.views).map(([name, view]) => [name, {draws: view.drawCount, differences: view.differences.length, unknown: view.unknown.length, errors: view.errors.length}]))}));
  if (report.verdict !== "PASS") process.exitCode = 1;
}
