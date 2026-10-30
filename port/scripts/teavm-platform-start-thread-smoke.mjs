#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

// Exercise the exact TeaVM 0.15 Platform.js resource.  This is deliberately
// smaller than a generated game bundle: it supplies the same bridge symbols
// that Platform.js calls and checks the semantic distinction between a raw
// timer -> launchThread and startThread's native-thread bootstrap.
const root = fileURLToPath(new URL("../..", import.meta.url));
const repository = process.env.M2_REPO || join(homedir(), ".m2", "repository");
const platformJar = join(repository, "org/teavm/teavm-platform/0.15.0",
  "teavm-platform-0.15.0.jar");
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const jar = javaHome ? join(javaHome, "bin", process.platform === "win32" ? "jar.exe" : "jar") : "jar";
const temp = await mkdtemp(join(tmpdir(), "gaius-teavm-platform-"));
try {
  execFileSync(jar, ["xf", platformJar, "org/teavm/platform/plugin/Platform.js"], {
    cwd: temp, stdio: "pipe",
  });
  const resource = await readFile(join(temp, "org/teavm/platform/plugin/Platform.js"), "utf8");
  assert.match(resource, /function startThread\(runnable\)/);
  assert.match(resource, /\$rt_threadStarter\(teavm_javaMethod\("org\.teavm\.platform\.Platform"/);
  assert.match(resource, /function schedule\(runnable, timeout\)/);

  const runner = join(temp, "platform-runner.cjs");
  await writeFile(runner, `
const fs = require('node:fs');
const vm = require('node:vm');
const resource = fs.readFileSync(process.argv[2], 'utf8');
const timerDelays = [];
let currentNativeThread = null;
let nativeRuns = 0;
let rawMissingContext = 0;
let runs = 0;
const nativeSetTimeout = setTimeout;
const context = {
  console,
  teavm_globals: {setTimeout(callback, delay) {
    timerDelays.push(Number(delay));
    const handle = nativeSetTimeout(callback, delay);
    handle.unref();
    return handle;
  }},
  $rt_nativeThread: () => currentNativeThread,
  teavm_javaMethod: (_owner, descriptor) => {
    if (descriptor !== 'launchThread(Lorg/teavm/platform/PlatformRunnable;)V') {
      throw new Error('unexpected TeaVM method ' + descriptor);
    }
    return runnable => {
      if (currentNativeThread === null) {
        rawMissingContext++;
        return;
      }
      runs++;
      runnable.run();
    };
  },
  $rt_threadStarter: launch => runnable => {
    const previous = currentNativeThread;
    currentNativeThread = {kind: 'teavm-native-thread'};
    nativeRuns++;
    try { return launch(runnable); } finally { currentNativeThread = previous; }
  },
};
vm.runInNewContext(resource + '\\nthis.__platform = {startThread, schedule};', context,
  {filename: 'teavm-platform-0.15.0/Platform.js'});
const runnable = {run() {
  if (currentNativeThread === null) throw new Error('continuation lost native context');
}};
context.__platform.startThread(runnable);
context.__platform.schedule(runnable, 0);
if (runs !== 0 || nativeRuns !== 0 || rawMissingContext !== 0) {
  throw new Error('Platform callback ran inline');
}
setTimeout(() => {
  if (runs !== 1 || nativeRuns !== 1 || rawMissingContext !== 1) {
    throw new Error(JSON.stringify({runs, nativeRuns, rawMissingContext, timerDelays}));
  }
  if (JSON.stringify(timerDelays) !== '[0,0]') {
    throw new Error('unexpected timer delays ' + JSON.stringify(timerDelays));
  }
  process.stdout.write(JSON.stringify({runs, nativeRuns, rawMissingContext, timerDelays}) + '\\n');
}, 5);
`);
  const output = execFileSync(process.execPath, [runner,
    join(temp, "org/teavm/platform/plugin/Platform.js")], {encoding: "utf8", timeout: 15000});
  const result = JSON.parse(output.trim());
  assert.deepEqual(result, {runs: 1, nativeRuns: 1, rawMissingContext: 1, timerDelays: [0, 0]});
  console.log("TEAVM_PLATFORM_START_THREAD_OK nativeContext=1 rawScheduleMissingContext=1 deferred=1");
} finally {
  await rm(temp, {recursive: true, force: true});
}
