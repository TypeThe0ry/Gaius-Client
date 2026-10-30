import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const runtimeScript = fileURLToPath(new URL(
  "./singleplayer-worker-runtime-smoke.mjs",
  import.meta.url,
));
const result = spawnSync(process.execPath, [runtimeScript], {
  cwd: fileURLToPath(new URL("../../", import.meta.url)),
  env: {...process.env, GAIUS_SMOKE_SELF_TEST: "1"},
  encoding: "utf8",
  maxBuffer: 4 * 1024 * 1024,
});

if (result.error) throw result.error;
if (result.status !== 0) {
  throw new Error(`runtime self-test exited ${result.status}: ${result.stderr}`);
}

let evidence;
try {
  evidence = JSON.parse(result.stdout);
} catch (error) {
  throw new Error(`runtime self-test did not emit JSON: ${error.message}`);
}

const slowProbe = evidence.slowProbe;
const expected = {
  schema: "gaius.worker-event-loop-slow-sample.v2",
  thresholdMs: 250,
  limit: 64,
  totalCandidates: 65,
  retainedTopK: 64,
  droppedCandidates: 1,
};
for (const [field, value] of Object.entries(expected)) {
  if (slowProbe?.[field] !== value) {
    throw new Error(`slow-probe contract mismatch for ${field}: expected ${value}`);
  }
}
if (slowProbe.maxFieldsPerGroup !== 32 ||
    slowProbe.perScopeLimit !== 32 ||
    slowProbe.retentionModel !== "global-top-64-with-balanced-phase-views" ||
    slowProbe.fastProbeExcluded !== true ||
    slowProbe.timingDecomposition !== true ||
    slowProbe.boundedTopK !== true) {
  throw new Error("slow-probe bounded evidence contract mismatch");
}

process.stdout.write(JSON.stringify({
  ok: true,
  schema: expected.schema,
  thresholdMs: expected.thresholdMs,
  totalCandidates: expected.totalCandidates,
  retainedTopK: expected.retainedTopK,
  droppedCandidates: expected.droppedCandidates,
  retentionModel: slowProbe.retentionModel,
  maxFieldsPerGroup: slowProbe.maxFieldsPerGroup,
  fastProbeExcluded: slowProbe.fastProbeExcluded,
  timingDecomposition: slowProbe.timingDecomposition,
}) + "\n");
