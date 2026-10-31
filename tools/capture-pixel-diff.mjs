import assert from "node:assert/strict";
import {deflateSync, inflateSync} from "node:zlib";
import {readFile, mkdir, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {pathToFileURL} from "node:url";

const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const crcTable = Uint32Array.from({length: 256}, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function paeth(left, above, diagonal) {
  const prediction = left + above - diagonal;
  const distanceLeft = Math.abs(prediction - left);
  const distanceAbove = Math.abs(prediction - above);
  const distanceDiagonal = Math.abs(prediction - diagonal);
  return distanceLeft <= distanceAbove && distanceLeft <= distanceDiagonal ? left
    : distanceAbove <= distanceDiagonal ? above : diagonal;
}
export function decodePng(bytes) {
  assert.ok(bytes.subarray(0, 8).equals(signature), "PNG signature");
  const compressed = [];
  let width, height, channels;
  for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.toString("ascii", offset + 4, offset + 8);
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    assert.equal(crc32(bytes.subarray(offset + 4, offset + 8 + length)), bytes.readUInt32BE(offset + 8 + length), `PNG CRC ${type}`);
    if (type === "IHDR") {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      assert.equal(data[8], 8); assert.equal(data[10], 0); assert.equal(data[11], 0); assert.equal(data[12], 0);
      assert.ok(data[9] === 2 || data[9] === 6, "PNG must be RGB/RGBA");
      channels = data[9] === 6 ? 4 : 3;
      assert.ok(width > 0 && height > 0 && width * height <= 16000000);
    }
    if (type === "IDAT") compressed.push(data);
    offset += length + 12;
    if (type === "IEND") break;
  }
  const stride = width * channels;
  const raw = inflateSync(Buffer.concat(compressed), {maxOutputLength: (stride + 1) * height});
  assert.equal(raw.length, (stride + 1) * height);
  const decoded = Buffer.alloc(stride * height);
  for (let row = 0; row < height; row++) {
    const filter = raw[row * (stride + 1)]; assert.ok(filter <= 4);
    for (let column = 0; column < stride; column++) {
      const index = row * stride + column;
      const left = column >= channels ? decoded[index - channels] : 0;
      const above = row ? decoded[index - stride] : 0;
      const diagonal = row && column >= channels ? decoded[index - stride - channels] : 0;
      const predictor = [0, left, above, (left + above) >>> 1, paeth(left, above, diagonal)][filter];
      decoded[index] = (raw[row * (stride + 1) + 1 + column] + predictor) & 255;
    }
  }
  const pixels = Buffer.alloc(width * height * 4, 255);
  for (let pixel = 0; pixel < width * height; pixel++) decoded.copy(pixels, pixel * 4, pixel * channels, pixel * channels + channels);
  return {width, height, pixels};
}
export function encodePng({width, height, pixels}) {
  const chunk = (type, data) => {
    const result = Buffer.alloc(data.length + 12);
    result.writeUInt32BE(data.length); result.write(type, 4, 4, "ascii"); data.copy(result, 8);
    result.writeUInt32BE(crc32(result.subarray(4, data.length + 8)), data.length + 8);
    return result;
  };
  const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let row = 0; row < height; row++) pixels.copy(raw, row * (width * 4 + 1) + 1, row * width * 4, (row + 1) * width * 4);
  return Buffer.concat([signature, chunk("IHDR", header), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}
export function compareCaptures(webgl, webgpu, tolerance = 8) {
  assert.equal(webgl.length, 3); assert.equal(webgpu.length, 3);
  const {width, height} = webgl[0];
  for (const image of [...webgl, ...webgpu]) { assert.equal(image.width, width); assert.equal(image.height, height); }
  const regions = Object.fromEntries(["full", "upper", "middle", "lower"].map(name => [name,
    {pixels: 0, rawDifferent: 0, unstable: 0, stableDifferent: 0, stableDelta: 0}]));
  const heatmap = Buffer.alloc(width * height * 4, 255);
  for (let pixel = 0; pixel < width * height; pixel++) {
    let unstable = false, difference = 0, delta = 0;
    for (let channel = 0; channel < 3; channel++) {
      const offset = pixel * 4 + channel;
      const distance = Math.abs(webgl[0].pixels[offset] - webgpu[0].pixels[offset]);
      difference = Math.max(difference, distance); delta += distance;
      for (const series of [webgl, webgpu]) {
        if (series[0].pixels[offset] !== series[1].pixels[offset] || series[1].pixels[offset] !== series[2].pixels[offset]) unstable = true;
      }
    }
    const different = difference > tolerance;
    const band = ["upper", "middle", "lower"][Math.min(2, Math.floor(Math.floor(pixel / width) * 3 / height))];
    for (const name of ["full", band]) {
      const region = regions[name]; region.pixels++; region.rawDifferent += Number(different);
      region.unstable += Number(unstable);
      if (!unstable) { region.stableDifferent += Number(different); region.stableDelta += delta; }
    }
    const grey = Math.round((webgl[0].pixels[pixel * 4] + webgl[0].pixels[pixel * 4 + 1] + webgl[0].pixels[pixel * 4 + 2]) / 12);
    heatmap.set(unstable ? [255, 170, 0, 255] : different ? [255, 0, 0, 255] : [grey, grey, grey, 255], pixel * 4);
  }
  for (const region of Object.values(regions)) {
    region.rawFraction = region.rawDifferent / region.pixels;
    region.unstableFraction = region.unstable / region.pixels;
    region.stablePixels = region.pixels - region.unstable;
    region.stableFraction = region.stablePixels ? region.stableDifferent / region.stablePixels : null;
    region.stableMeanDelta = region.stablePixels ? region.stableDelta / (region.stablePixels * 3) : null;
  }
  return {width, height, tolerance, regions, heatmap: {width, height, pixels: heatmap}};
}
export async function reportCaptureDiff(webglPath, webgpuPath, outputPath) {
  await mkdir(outputPath, {recursive: true});
  const [webgl, webgpu] = await Promise.all([webglPath, webgpuPath].map(async path => JSON.parse(await readFile(join(path, "capture.json"), "utf8"))));
  assert.ok(webgl.complete && webgpu.complete, "both captures must complete");
  for (const field of ["profile", "seed", "settings", "options"]) assert.deepEqual(webgl[field], webgpu[field], `capture ${field}`);
  assert.deepEqual(Object.keys(webgl.views), ["horizon", "entities", "high", "topdown"]);
  assert.deepEqual(Object.keys(webgpu.views), Object.keys(webgl.views));
  if (webgl.stageReceipt || webgpu.stageReceipt) {
    for (const field of ["originalSha256", "stagedSha256"]) {
      assert.match(webgl.stageReceipt?.[field] || "", /^[a-f0-9]{64}$/, `staged ${field}`);
      assert.equal(webgl.stageReceipt[field], webgpu.stageReceipt?.[field], `same staged ${field}`);
    }
  } else {
    assert.match(webgl.clientIdentity?.artifact?.sha256 || "", /^[a-f0-9]{64}$/, "client source identity is required");
    assert.deepEqual(webgl.clientIdentity, webgpu.clientIdentity, "same client build identity");
  }
  const report = {schema: 1, profile: webgl.profile, seed: webgl.seed, threshold: 0.005, tolerance: 8,
    clientIdentity: webgl.clientIdentity, stageReceipt: webgl.stageReceipt,
    attribution: "stable residuals are renderer candidates, not proof of renderer root cause", views: {}};
  const rows = ["# Deterministic renderer comparison", "", "Red: stable cross-backend residual. Orange: changing within either backend; not attributed to rendering.", "",
    "| View / region | Raw diff | Unstable | Stable residual |", "| --- | ---: | ---: | ---: |"];
  for (const name of Object.keys(webgl.views)) {
    assert.deepEqual(webgl.views[name].camera, webgpu.views[name].camera, `camera ${name}`);
    // The same comparator is also used for a mesher-on/off A/B capture. In
    // that case both directories intentionally use the same backend; require
    // a consistent backend label instead of assuming the second run is WebGPU.
    const expectedBackends = webgl.views[name].states.map(state => state.backend);
    const candidateBackends = webgpu.views[name].states.map(state => state.backend);
    const sameBackend = expectedBackends.every((backend, index) => backend === candidateBackends[index]);
    assert.ok(expectedBackends.every(backend => backend === "webgl2" || backend === "webgpu"), `backend ${name}`);
    assert.ok(candidateBackends.every(backend => backend === "webgl2" || backend === "webgpu"), `backend ${name}`);
    if (sameBackend) assert.deepEqual(candidateBackends, expectedBackends, `backend ${name}`);
    for (const [capture, backends] of [[webgl, expectedBackends], [webgpu, candidateBackends]]) {
      assert.equal(capture.views[name].states.length, 3);
      for (const [index, state] of capture.views[name].states.entries()) {
        assert.equal(state.backend, backends[index]); assert.equal(state.animationTick, 0); assert.ok(state.animationApplications > 0);
        assert.equal(state.options.cloudsOff, false, "FANCY clouds must be enabled");
        assert.ok(state.options.particlesMinimal);
        for (const [index, field] of ["x", "y", "z", "yaw", "pitch"].entries()) {
          assert.ok(Math.abs(state.player?.[field] - capture.views[name].camera[index]) < 0.01, `settled ${name} ${field}`);
        }
      }
    }
    const images = await Promise.all([webglPath, webgpuPath].map(async (path, index) => {
      const capture = index ? webgpu : webgl;
      return Promise.all(capture.views[name].files.map(async filename => {
        assert.equal(filename, filename.split(/[\\/]/).pop()); return decodePng(await readFile(join(path, filename)));
      }));
    }));
    const {heatmap, ...metrics} = compareCaptures(...images);
    const heatmapName = `${name}-heatmap.png`; await writeFile(join(outputPath, heatmapName), encodePng(heatmap));
    report.views[name] = {...metrics, heatmap: heatmapName};
    for (const [regionName, region] of Object.entries(metrics.regions)) rows.push(`| ${name} / ${regionName} | ${(region.rawFraction * 100).toFixed(4)}% | ${(region.unstableFraction * 100).toFixed(4)}% | ${region.stableFraction === null ? "n/a" : (region.stableFraction * 100).toFixed(4) + "%"} |`);
  }
  report.stable = Object.values(report.views).every(view => view.regions.full.unstable === 0);
  report.verdict = !report.stable ? "UNSTABLE" : Object.values(report.views).every(view => view.regions.full.rawFraction <= report.threshold) ? "PASS" : "FAIL";
  rows.push("", `Verdict: **${report.verdict}**. A masked result cannot pass acceptance.`);
  await writeFile(join(outputPath, "pixel-diff.json"), JSON.stringify(report, null, 2));
  await writeFile(join(outputPath, "pixel-diff.md"), rows.join("\n") + "\n");
  return report;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [webglPath, webgpuPath, outputPath] = process.argv.slice(2);
  if (!webglPath || !webgpuPath || !outputPath) throw new Error("usage: node tools/capture-pixel-diff.mjs <webgl dir> <webgpu dir> <output dir>");
  const report = await reportCaptureDiff(resolve(webglPath), resolve(webgpuPath), resolve(outputPath));
  console.log(JSON.stringify({verdict: report.verdict,
    views: Object.fromEntries(Object.entries(report.views).map(([name, view]) => [name, view.regions.full]))}));
  process.exitCode = report.verdict === "PASS" ? 0 : 1;
}
