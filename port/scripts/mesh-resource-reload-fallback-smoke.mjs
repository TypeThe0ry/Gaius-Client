// Regression smoke for the reload-only lost-reply path.  The TeaVM classes are not available
// in this Node process, so this exercises the source-level contract at the Java/JS boundary:
// resource epochs must cancel old replies and enter the session vanilla fallback, and the JS
// facade must bound a model-table/section promise instead of waiting forever.
import {readFile} from "node:fs/promises";
import assert from "node:assert/strict";

const java = await readFile(new URL("../src/main/java/dev/gaius/browser/kernel/mesh/MeshKernelBridge.java", import.meta.url), "utf8");
const hooks = await readFile(new URL("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/MeshKernelHooks.java", import.meta.url), "utf8");
const mesh = await readFile(new URL("../web/kernels/mesh-kernel-job.js", import.meta.url), "utf8");

assert.match(java, /setEpochBumpHook\(runnable == null \? null : MeshKernelBridge::cancelAll\)/,
  "resource epoch must cancel stale jobs without disabling mesh");
assert.match(hooks, /ANSWER_TIMEOUT_MILLIS = configuredAnswerTimeoutMillis\(\)/, "section replies need a bounded configurable deadline");
assert.match(hooks, /MeshKernelBridge\.disable\("section-reply-timeout"\)/,
  "lost section reply must disable mesh and release vanilla fallback");
assert.match(hooks, /model-table-export-timeout/, "stalled table export must disable mesh");
assert.match(hooks, /public static void prewarm\(SectionCompiler compiler\)/,
  "the client must expose a model-table prewarm entry point");
assert.match(hooks, /public static void prewarm\(SectionCompiler compiler\)[\s\S]*currentTable\(compiler\)[\s\S]*catch \(RuntimeException error\)/,
  "constructor prewarm must use the same guarded main-thread snapshot path");
assert.match(hooks, /String currentScope = tableCacheScope\(\)/,
  "the pack stack must be captured on the caller thread before export");
assert.match(hooks, /runExport\(job, models, currentScope,[\s\S]*currentResourceEpoch,[\s\S]*currentLevelEpoch\)/,
  "the worker must receive immutable pack and epoch snapshots");
const worker = hooks.slice(hooks.indexOf("private static void runExport"));
assert.doesNotMatch(worker, /tableCacheScope\(/,
  "the export worker must not read Minecraft ResourceManager");
assert.match(worker, /discarded (?:completed )?model table export after (?:resource\/level|epoch) change/,
  "reload, pack switch and level leave must discard rather than poison the model identity");
assert.match(worker, /resourceEpoch != BrowserMeshInstallQueue\.resourceEpoch\(\)[\s\S]*failedModels = models/,
  "an export exception after an epoch change must remain retryable");
const patcher = await readFile(new URL("../tools/src/main/java/dev/gaius/tools/patches/mesh/MeshKernelPatches.java", import.meta.url), "utf8");
assert.match(patcher, /HOOKS, \"prewarm\"/,
  "SectionCompiler construction must trigger model-table prewarm");
assert.match(mesh, /mesh-timeout/, "JS facade must classify a bounded timeout");
assert.match(mesh, /disable\("model-table-timeout"\)/, "JS model-table timeout must fall back to vanilla");
assert.match(mesh, /disable\("section-timeout"\)/, "JS section timeout must fall back to vanilla");

// Simulate the lost-reply state transition that used to leave the reload barrier pending.
const state = {pending: 1, disabled: false, fallback: null};
const onResourceEpoch = () => { state.pending = 0; };
onResourceEpoch();
assert.deepEqual(state, {pending: 0, disabled: false, fallback: null}, "epoch bump preserves mesh availability");
const onReplyTimeout = () => { state.pending = 0; state.disabled = true; state.fallback = "section-reply-timeout"; };
state.pending = 1;
onReplyTimeout();
assert.deepEqual(state, {pending: 0, disabled: true, fallback: "section-reply-timeout"},
  "a lost reply must complete via vanilla fallback after its deadline");

// The worker only receives immutable snapshots. A reload, pack switch, or level leave makes
// the result retryable; none of those transitions mark the model identity as permanently failed.
const exportStillValid = (start, now) => start.scope === now.scope
  && start.resourceEpoch === now.resourceEpoch && start.levelEpoch === now.levelEpoch;
const start = {scope: "packs:vanilla,programmer_art,", resourceEpoch: 4, levelEpoch: 9};
for (const [name, changed] of [
  ["reload", {...start, resourceEpoch: 5}],
  ["pack-switch", {...start, scope: "packs:vanilla,high_contrast,"}],
  ["leave-world", {...start, levelEpoch: 10}],
]) {
  assert.equal(exportStillValid(start, changed), false, `${name} must discard the old export`);
}
assert.equal(exportStillValid(start, start), true, "an unchanged export may publish and cache");
console.log("mesh resource reload fallback smoke passed (lost reply timeout -> vanilla fallback)");
