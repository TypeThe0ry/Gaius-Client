#!/usr/bin/env node

// Source-level regression checks. These do not claim a compiled Worker or
// Chrome gameplay pass; the profile release builds keep their runtime gate.
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const checks = [
  "session-launcher",
  "singleplayer-storage-reliability",
  "singleplayer-world-reload-hydration",
  "singleplayer-messageport-lifecycle",
  "singleplayer-worker-bootstrap-lifecycle",
  "singleplayer-client-session",
  "storage-profile-isolation",
  "singleplayer-region-patch-log",
  "singleplayer-region-cache",
  "singleplayer-network-wakeup",
];
const results = [];
for (const name of checks) {
  const started = performance.now();
  const result = spawnSync(process.execPath, [`port/scripts/${name}-smoke.mjs`], {
    cwd: root,
    stdio: "inherit",
    timeout: 60_000,
    windowsHide: true,
  });
  const ok = !result.error && result.status === 0;
  results.push({name, ok, exitCode: result.status,
    elapsedMs: Math.round(performance.now() - started)});
  if (result.error) console.error(`${name}: ${result.error.message}`);
}
const ok = results.every(result => result.ok);
console.log(JSON.stringify({ok, scope: "singleplayer-source-lifecycle", results}, null, 2));
process.exitCode = ok ? 0 : 1;
