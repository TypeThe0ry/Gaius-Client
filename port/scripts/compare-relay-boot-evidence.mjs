import assert from "node:assert/strict";
import {readFile, writeFile} from "node:fs/promises";

const [baselinePath, candidatePath, outPath] = process.argv.slice(2);
assert.ok(baselinePath && candidatePath && outPath,
  "usage: node compare-relay-boot-evidence.mjs <baseline.json> <race9.json> <out.json>");
const [baseline, candidate] = await Promise.all([baselinePath, candidatePath].map(async path =>
  JSON.parse(await readFile(path, "utf8"))));
assert.equal(Boolean(baseline.reload), Boolean(candidate.reload),
  "compare cold evidence with cold evidence, or reload with reload");
for (const [label, evidence] of [["baseline", baseline], ["race9", candidate]]) {
  assert.equal(evidence.hooksIntact, true, `${label} is missing intact boot observation hooks`);
  assert.ok(evidence.titleIdleMs >= 5000, `${label} did not observe five seconds after title`);
  assert.ok(Array.isArray(evidence.workers), `${label} is missing worker coverage evidence`);
  for (const worker of evidence.workers) {
    assert.ok(worker.observed && !worker.error, `${label} has incomplete worker observation`);
  }
  assert.ok(evidence.reachedTitle, `${label} did not reach TitleScreen`);
  assert.ok(evidence.headed && evidence.gpu?.selected === "webgpu" && evidence.gpuFallback === false);
  assert.deepEqual(evidence.errors, [], `${label} threw an uncaught exception`);
  assert.deepEqual(evidence.preconnects, [], `${label} inserted preconnect links at boot`);
  assert.equal(evidence.relayNetwork.length, 0, `${label} requested relay network resources at boot`);
  assert.equal(evidence.relayWebSockets.length, 0, `${label} opened relay WebSockets at boot`);
  assert.deepEqual(evidence.relayCounters,
    {fetch:0, webSocket:0, setTimeout:0, setInterval:0, localStorage:0},
    `${label} performed instrumented relay work during boot`);
}
const comparison = {
  headed: baseline.headed && candidate.headed,
  browserBaseline: baseline.browser,
  browserCandidate: candidate.browser,
  baseline: {networkRequests:baseline.networkRequests, webSocketCreated:baseline.webSocketCreated,
    allCounters:baseline.allCounters},
  race9: {networkRequests:candidate.networkRequests, webSocketCreated:candidate.webSocketCreated,
    allCounters:candidate.allCounters},
  timings: {baseline:baseline.timings, race9:candidate.timings},
  exactStartupCountsMatch: baseline.networkRequests === candidate.networkRequests &&
    baseline.webSocketCreated === candidate.webSocketCreated &&
    JSON.stringify(baseline.allCounters) === JSON.stringify(candidate.allCounters),
};
await writeFile(outPath, JSON.stringify(comparison, null, 2));
console.log(JSON.stringify(comparison));
// Non-relay totals are diagnostics and can vary with scheduling and the package contents.
