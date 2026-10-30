import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, readFileSync, rmSync} from "node:fs";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import {RENDERPEARL_PROFILE} from "../verification-profiles.mjs";

const root = fileURLToPath(new URL("../../..", import.meta.url));
const profile = RENDERPEARL_PROFILE.id;
const read = path => readFileSync(join(root, path), "utf8");
const patch = read("port/tools/src/main/java/dev/gaius/tools/patches/telemetry/TickPacketParityPatches.java");
const worldgen = read("port/tools/src/main/java/dev/gaius/tools/patches/worldgen/ModernWorldgenPatches.java");
const executors = read("port/src/target-wasmgc/java/dev/gaius/parity/BrowserTickParityExecutors.java");
const probe = read("port/src/target-wasmgc/java/dev/gaius/parity/BrowserSettleProbe.java");
const jspi = read("port/web/runtime/wasmgc/gaius-jspi.js");

// Source contract: the route exists, is applied after the cursor is written, and only in a WasmGC
// packet-parity debug build; outside a recording both routes return the Worker's behavior.
const start = patch.indexOf("public static void applyBrowserLayerCursor");
assert.notEqual(start, -1, "layer cursor parity route is defined");
const method = patch.slice(start, patch.indexOf("/**", start + 10) < 0 ? undefined : patch.indexOf("/**", start + 10));
assert.match(method, /browserLayerYield/, "applied only to a class that carries the Worker's cursor");
assert.match(method, /"holdersPerTurn", "\(I\)I"/);
assert.match(method, /"boundedLayers", "\(\)Z"/);
assert.match(method, /limits != 1 \|\| continuation == null \|\| starter == null/, "fails closed when the cursor shape changes");
assert.match(worldgen, /BuildTarget\.wasmGc\(\) && TickPacketParityPatches\.requested\(\)[\s\S]{0,400}applyBrowserLayerCursor/,
  "only a WasmGC packet-parity debug build routes the cursor");
assert.match(worldgen.slice(worldgen.indexOf("applyBrowserLayerCursor") - 600, worldgen.indexOf("applyBrowserLayerCursor")),
  /writeChunkGenerationYieldHelper\(root\)/, "the route is applied after the cursor is written");
assert.match(executors, /holdersPerTurn\(int worker\) \{\s*return TickPacketRecorder\.enabled \? Integer\.MAX_VALUE : worker;/);
assert.match(executors, /boundedLayers\(\) \{\s*return !TickPacketRecorder\.enabled;/);
assert.match(probe, /host\.runQueuePending\(\) == 0/, "queued run-queue activations keep the settle open");
assert.match(jspi, /pendingRuns: function \(\) \{\s*return current === null \? 0 : current\.pending\(\);/);

// Model of the two submission schedules the route chooses between: a layer of n holders is queued
// behind other work. Bounded batches interleave other tasks between two batches; the recording's
// layer is one uninterrupted submission, as in vanilla.
function schedule(layerHolders, other, batch) {
  const queue = [...other];
  const order = [];
  for (let sent = 0; sent < layerHolders;) {
    const count = Math.min(batch, layerHolders - sent);
    for (let i = 0; i < count; i++) order.push("layer" + (sent + i));
    sent += count;
    if (sent < layerHolders && queue.length) order.push(queue.shift());
  }
  return [...order, ...queue];
}
const bounded = schedule(40, ["x", "y", "z"], 16);
const unbounded = schedule(40, ["x", "y", "z"], Number.MAX_SAFE_INTEGER);
assert.deepEqual(unbounded.slice(0, 40), Array.from({length: 40}, (unused, i) => "layer" + i), "one uninterrupted submission");
assert.notDeepEqual(bounded.slice(0, 40), unbounded.slice(0, 40), "bounded batches interleave other tasks");

// Optional bytecode check (about 40 s, needs port/work/<profile>/client-named.jar and a JDK): the
// patch chain applies the route to the real ChunkGenerationTask and javap shows both routes.
if (process.env.GAIUS_PATCH_BYTECODE_TEST === "1") {
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  assert.ok(home, "set GAIUS_JAVA_HOME");
  const out = join(process.env.GAIUS_PATCH_BYTECODE_OUT || join(root, "port/work/tmp-layer-cursor-test"));
  rmSync(out, {recursive: true, force: true});
  const run = spawnSync(process.execPath, [join(root, "port/tools/patchkit/patchkit.mjs"), "check", "--profile", profile, "--target", "wasmgc",
    "--out", out, "--keep"], {env: {...process.env, GAIUS_DEBUG_PACKET_PARITY: "1"}, encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
  const scratch = /PATCHKIT_SCRATCH (.+)/.exec(run.stdout)?.[1]?.trim();
  assert.ok(scratch && existsSync(scratch), "patchkit reports its scratch directory: " + run.stdout.slice(-400));
  const quotedProfile = profile.replace(/[.*+?^${}()|[\\]\\\\]/g, "\\\\$&");
  assert.match(run.stdout, new RegExp(`PATCHKIT_RESULT ${quotedProfile} wasmgc .*FAILED=0`));
  const classes = join(scratch, `${profile}-wasmgc/out`);
  const javap = spawnSync(join(home, "bin", process.platform === "win32" ? "javap.exe" : "javap"),
    ["-p", "-c", "-cp", classes, "net.minecraft.server.level.ChunkGenerationTask"], {encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
  const code = javap.stdout.slice(javap.stdout.indexOf("private void scheduleLayer"));
  const layer = code.slice(0, code.indexOf("Exception table"));
  assert.match(layer, /holdersPerTurn:\(I\)I\s*\n\s*\d+: if_icmplt/, "the batch limit goes through holdersPerTurn");
  assert.match(layer, /boundedLayers:\(\)Z\s*\n\s*\d+: ifne\s+\d+\s*\n\s*\d+: goto\s+\d+/, "an unbounded layer skips the continuation");
  assert.equal((layer.match(/HostLoop\.startThread/g) || []).length, 1, "the Worker's continuation is still there for ordinary play");
  void delimiter;
}

process.stdout.write("layer cursor parity patch: pass\n");
