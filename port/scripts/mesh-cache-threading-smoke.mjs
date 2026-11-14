import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const hooks = await readFile(new URL(
  "../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/MeshKernelHooks.java", import.meta.url), "utf8");
const meshJob = await readFile(new URL("../web/kernels/mesh-kernel-job.js", import.meta.url), "utf8");
const bridge = await readFile(new URL("../src/main/java/dev/gaius/browser/kernel/mesh/MeshKernelBridge.java", import.meta.url), "utf8");

if (hooks.includes("takeCachedTable")) {
  // A cache-enabled build must capture its immutable identity on the caller thread.
  assert.match(hooks, /String currentScope = tableCacheScope\(\)/);
  assert.match(hooks, /HostLoop\.startThread\(\(\) -> runExport\(job, models, currentScope,/);
  assert.match(hooks, /exporterResourceEpoch != currentResourceEpoch/);
  assert.match(hooks, /exporterLevelEpoch != currentLevelEpoch/);
  assert.match(hooks, /!Objects\.equals\(exporterScope, currentScope\)/);
  const begin = hooks.indexOf("private static void runExport(");
  const end = hooks.indexOf("// --- results", begin);
  assert.ok(begin >= 0 && end > begin, "export completion boundary exists");
  const background = hooks.slice(begin, end);
  assert.doesNotMatch(background, /tableCacheScope|getResourceManager|listPacks/);
  assert.match(background, /resourceEpoch != BrowserMeshInstallQueue\.resourceEpoch\(\)/);
  assert.match(background, /levelEpoch != BrowserMeshInstallQueue\.levelEpoch\(\)/);
  assert.match(background, /MeshKernelBridge\.storeTable\(scope\)/);
  assert.match(hooks, /if \(!BUILT_IN_PACKS\.contains\(id\)\) \{\s*return null/);
  assert.match(meshJob, /scope !== tableCache\.entryScope/);
  assert.match(bridge, /takeCachedTable\(String scope\)/);
} else {
  assert.doesNotMatch(hooks, /tableCacheScope|takeCachedTable|storeTable/);
  assert.match(hooks, /HostLoop\.startThread\(\(\) -> runExport\(job, models\)\)/);
  assert.doesNotMatch(meshJob, /tableCacheKey|takeCachedTable|storeTable|mesh-table-v1/);
  assert.doesNotMatch(bridge, /tableCache|CachedTable/);
}
console.log("mesh cache threading smoke passed: background export uses captured identity and rejects stale epochs");
