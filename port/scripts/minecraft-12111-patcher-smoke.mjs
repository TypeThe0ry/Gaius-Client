#!/usr/bin/env node

// 1.21.11 is the checkpoint-only profile.  This smoke exercises the dedicated
// task-layer holder cursor directly on the named client jar; it never invokes
// the 26.2 patcher and it rejects scheduler pulse/checkpoint bytecode.
import {LEGACY_PROFILE, profileIdPattern} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {patcherSource as stepSource} from "./patcher-sources.mjs";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, readdir, rm, stat} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {basename, delimiter, join, relative} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const requestedProfile = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH) : LEGACY_PROFILE.id);
if (requestedProfile !== LEGACY_PROFILE.id) {
  throw new Error(`Minecraft ${LEGACY_PROFILE.id} patcher smoke got profile ${requestedProfile}`);
}
const rawClientJar = join(repositoryRoot, "port/work/" + LEGACY_PROFILE.id + "/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");
const verifierSource = join(repositoryRoot, "port/scripts/fixtures/GaiusChunkLayerBytecodeVerifier.java");

function jdkTool(name) {
  const configuredHomes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath);
  if (process.platform === "win32") {
    configuredHomes.push(
      "C:\\Program Files\\Java\\jdk-26.0.1",
      "C:\\Program Files\\Java\\jdk-24",
      "C:\\Program Files\\Java\\jdk-21",
    );
  }
  for (const home of [...new Set(configuredHomes)]) {
    const candidate = join(home, "bin", name);
    for (const versionArgs of [["--version"], ["-version"]]) {
      try {
        const version = execFileSync(candidate, versionArgs, {
          encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
        });
        const major = Number(version.match(/(?:^|\s|version\s+)(\d+)(?:\.|\s|$)/i)?.[1]);
        if (Number.isInteger(major) && major >= 21) return candidate;
      } catch {
        // Try the alternate version flag, then the next configured JDK.
      }
    }
  }
  return name;
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature ? bytecode.indexOf(nextSignature, start + signature.length) : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

function occurrences(haystack, needle) {
  return haystack.split(needle).length - 1;
}

function bytecodeInstructions(methodBytecode) {
  return methodBytecode.split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), instruction: match[2]}] : [];
  });
}

function graphicsPresetApply(bytecode) {
  return method(bytecode,
    "public void apply(net.minecraft.client.Minecraft);",
    "private static <T> void set(");
}

function graphicsPresetDistanceConstants(applyBytecode, getter) {
  const instructions = bytecodeInstructions(applyBytecode);
  const matches = instructions.flatMap((entry, index) =>
    entry.instruction.includes(`Options.${getter}:`) ? [index] : []);
  assert.equal(matches.length, 3,
    `GraphicsPreset.apply must have three ${getter} setter calls`);
  return matches.map(index => (instructions[index + 1]?.instruction ?? "").replace(/\s+/g, " "));
}

function graphicsPresetCustomReturn(applyBytecode) {
  const target = applyBytecode.match(/default:\s+(\d+)/);
  assert.ok(target, "GraphicsPreset.apply ordinal switch has no CUSTOM default");
  const entry = bytecodeInstructions(applyBytecode)
    .find(instruction => instruction.offset === Number(target[1]));
  assert.equal(entry?.instruction, "return",
    "GraphicsPreset.apply CUSTOM arm is no longer the default return arm");
  return entry.instruction;
}

const HOLDERS_PER_TURN = 16;

function cursorModel(radius, stop = null, batchLimit = HOLDERS_PER_TURN) {
  const visits = [];
  const batches = [];
  let batch = [];
  let active = true;
  let yieldPending = false;
  for (let x = -radius; x <= radius && active; x++) {
    for (let z = -radius; z <= radius && active; z++) {
      const index = visits.length;
      const outcome = stop?.index === index ? stop.kind : "success";
      const visit = {x, z, outcome};
      visits.push(visit);
      if (outcome !== "success") {
        active = false;
        yieldPending = false;
        if (batch.length) batches.push(batch);
        continue;
      }
      batch.push(visit);
      if (x === radius && z === radius) active = false;
      if (batch.length === batchLimit || !active) {
        batches.push(batch);
        batch = [];
        yieldPending = true;
        yieldPending = false;
      }
    }
  }
  return {visits, batches, active, yieldPending};
}

function pendingDependencyLayerModel(
  turns,
  dependencyOf = new Map(),
  {drainAfterEachTurn = false} = {},
) {
  const submitted = [];
  const submittedSet = new Set();
  const completed = [];
  const completedSet = new Set();
  const pending = [];
  // Keep the planned turn shape separate from the turns actually submitted.
  // The old one-holder await stops after turn 0, but its intended schedule is
  // still [1, ...] and must be visible to the regression assertion.
  const batches = turns.map(turn => [...turn]);
  const submittedBatches = [];
  const drains = [];

  function drainPending(stage) {
    let progress = true;
    const drained = [];
    while (progress) {
      progress = false;
      const remaining = [];
      for (const item of pending) {
        const dependency = item.dependsOn;
        if (dependency == null || completedSet.has(dependency)) {
          completed.push(item.holder);
          completedSet.add(item.holder);
          drained.push(item.holder);
          progress = true;
        } else {
          remaining.push(item);
        }
      }
      pending.splice(0, pending.length, ...remaining);
    }
    drains.push({stage, drained, pending: pending.map(item => item.holder)});
  }

  for (const [turnIndex, turn] of turns.entries()) {
    const submittedBatch = [];
    for (const holder of turn) {
      assert.equal(submittedSet.has(holder), false,
        `layer model submitted holder ${holder} more than once`);
      submittedSet.add(holder);
      submitted.push(holder);
      submittedBatch.push(holder);
      pending.push({holder, dependsOn: dependencyOf.get(holder) ?? null});
    }
    submittedBatches.push(submittedBatch);
    if (drainAfterEachTurn) {
      drainPending(`turn-${turnIndex}`);
      if (pending.length) {
        return {
          blocked: true,
          blockedAtTurn: turnIndex,
          waitedAt: submitted.length,
          submitted,
          completed,
          pending: pending.map(item => ({...item})),
          batches,
          submittedBatches,
          drains,
        };
      }
    }
  }

  // The fixed barrier drains only after every holder in the layer was submitted.
  drainPending("final-layer-barrier");
  return {
    blocked: pending.length !== 0,
    blockedAtTurn: null,
    waitedAt: submitted.length,
    submitted,
    completed,
    pending: pending.map(item => ({...item})),
    batches,
    submittedBatches,
    drains,
  };
}

function boundedLayerTurns(holderCount, batchLimit) {
  const turns = [];
  for (let holder = 0; holder < holderCount; holder += batchLimit) {
    turns.push(Array.from(
      {length: Math.min(batchLimit, holderCount - holder)},
      (_, offset) => holder + offset,
    ));
  }
  return turns;
}

function oneHolderTurns(holderCount) {
  return boundedLayerTurns(holderCount, 1);
}

function layerBarrierSafetyModel(holderCount, batchLimit, waitBetweenBatches) {
  const run = pendingDependencyLayerModel(
    boundedLayerTurns(holderCount, batchLimit),
    new Map([[0, holderCount - 1]]),
    {drainAfterEachTurn: waitBetweenBatches},
  );
  return {
    blocked: run.blocked,
    waitedAt: run.waitedAt,
    scheduled: run.submitted,
    batches: run.batches,
    submittedBatches: run.submittedBatches,
    completed: run.completed,
    pending: run.pending,
    drains: run.drains,
  };
}

for (const radius of [0, 1, 2]) {
  const run = cursorModel(radius);
  const expected = (radius * 2 + 1) ** 2;
  assert.equal(run.visits.length, expected,
    `cursor radius ${radius} must visit every holder exactly once`);
  assert.equal(run.active, false,
    `cursor radius ${radius} must close after its final holder`);
  assert.equal(new Set(run.visits.map(({x, z}) => `${x},${z}`)).size, expected,
    `cursor radius ${radius} must not duplicate holders`);
  assert.equal(run.visits.at(-1)?.outcome, "success",
    `cursor radius ${radius} must process its final holder before closing`);
  assert.equal(run.batches.length, Math.ceil(expected / HOLDERS_PER_TURN));
  assert.ok(run.batches.every(batch => batch.length > 0 && batch.length <= HOLDERS_PER_TURN));
}
const cancelledCursor = cursorModel(2, {index: 3, kind: "cancel"});
assert.equal(cancelledCursor.visits.length, 4, "cancellation must stop at its holder");
assert.equal(cancelledCursor.visits.at(-1)?.outcome, "cancel");
assert.equal(cancelledCursor.active, false);
assert.equal(cancelledCursor.yieldPending, false);
const failedCursor = cursorModel(2, {index: 4, kind: "failure"});
assert.equal(failedCursor.visits.length, 5, "failure must stop at its holder");
assert.equal(failedCursor.visits.at(-1)?.outcome, "failure");
assert.equal(failedCursor.active, false);
assert.equal(failedCursor.yieldPending, false);

const unsafeLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, true);
assert.equal(unsafeLayerOrder.blocked, true,
  "the dependency fixture must expose per-batch await starvation");
assert.equal(unsafeLayerOrder.waitedAt, HOLDERS_PER_TURN);
const fixedLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, false);
assert.equal(fixedLayerOrder.blocked, false,
  "the active cursor must submit the complete layer before waiting");
assert.deepEqual(fixedLayerOrder.batches.map(batch => batch.length), [16, 1]);
assert.deepEqual(fixedLayerOrder.submittedBatches.map(batch => batch.length), [16, 1],
  "the fixed barrier must submit both bounded turns before draining");
assert.deepEqual(fixedLayerOrder.scheduled,
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed barrier must submit all 17 holders before waiting");
assert.equal(fixedLayerOrder.waitedAt, 17);
assert.equal(fixedLayerOrder.pending.length, 0,
  "the fixed layer barrier must drain every pending holder future");
assert.deepEqual(fixedLayerOrder.completed.sort((a, b) => a - b),
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed layer barrier must complete all holders after the final batch");
assert.equal(fixedLayerOrder.drains.length, 1,
  "the fixed model must not drain any holder batch before the final barrier");
assert.equal(fixedLayerOrder.drains.at(-1)?.stage, "final-layer-barrier",
  "the fixed layer must drain pending futures only at its final barrier");

const oldOneHolderOrder = pendingDependencyLayerModel(
  oneHolderTurns(17),
  new Map([[0, 16]]),
  {drainAfterEachTurn: true},
);
assert.equal(oldOneHolderOrder.blocked, true,
  "the old one-holder await order must block on a same-layer dependency");
assert.equal(oldOneHolderOrder.blockedAtTurn, 0,
  "the old order must await holder 0 before submitting its dependency holder");
assert.deepEqual(oldOneHolderOrder.batches.map(batch => batch.length),
  Array.from({length: 17}, () => 1),
  "the old regression model must use one holder per browser turn");
assert.deepEqual(oldOneHolderOrder.pending, [{holder: 0, dependsOn: 16}],
  "the old blocked order must retain holder 0 pending on holder 16");
assert.deepEqual(oldOneHolderOrder.submitted, [0],
  "the old await model must not submit the dependency holder after blocking");

await Promise.all([access(rawClientJar), access(asm), access(asmTree), access(asmAnalysis), access(verifierSource)]);
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcherSource = stepSource("Minecraft12111BrowserPatcher");
assert.match(patcherSource, /checkpoint-only/,
  "" + LEGACY_PROFILE.id + " patcher must declare checkpoint-only ownership");
assert.match(patcherSource, /BROWSER_HOLDERS_PER_TURN = 16/,
  "" + LEGACY_PROFILE.id + " holder batching must retain the reviewed 16-holder upper bound");
assert.doesNotMatch(patcherSource, /BrowserWorldgenScheduler/,
  "" + LEGACY_PROFILE.id + " task patcher must not reference BrowserWorldgenScheduler");
assert.doesNotMatch(patcherSource, /BrowserWorldgenScheduler|"pulse"|"checkpoint"|\.pulse\(|\.checkpoint\(/,
  "" + LEGACY_PROFILE.id + " task patcher must not add pulse/checkpoint calls");
assert.match(patcherSource, /Opcodes\.GETFIELD, CHUNK_POS, "x", "I"/,
  "" + LEGACY_PROFILE.id + " cursor must read public ChunkPos.x");
assert.match(patcherSource, /Opcodes\.GETFIELD, CHUNK_POS, "z", "I"/,
  "" + LEGACY_PROFILE.id + " cursor must read public ChunkPos.z");
assert.match(patcherSource, /Platform/,
  "" + LEGACY_PROFILE.id + " cursor must schedule a browser-turn continuation");
assert.match(patcherSource, /patchGraphicsPresetBrowserDistances\(jar, root\)/,
  "" + LEGACY_PROFILE.id + " dedicated patcher must invoke its FAST graphics overlay");
assert.match(patcherSource, /findGraphicsPresetDistanceConstant/,
  "" + LEGACY_PROFILE.id + " graphics overlay must retain the render-distance shape guard");
assert.match(patcherSource, /GraphicsPreset\.apply FAST .*getter/,
  "" + LEGACY_PROFILE.id + " graphics overlay must retain the per-getter shape guard");

const root = await mkdtemp(join(tmpdir(), "gaius-mc12111-cursor-"));
try {
  const classes = join(root, "classes");
  const patches = join(root, "patches");
  const clientJar = join(root, "client.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(patches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
  ]);
  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const jar = jdkTool("jar");
  const javap = jdkTool("javap");
  const asmClasspath = [asm, asmTree].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", asmClasspath,
    "-sourcepath", join(repositoryRoot, "port/tools/src/main/java"), "-d", classes,
    join(toolsSource, "Minecraft12111BrowserPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  execFileSync(java, ["-Xverify:all", "-classpath", [classes, asmClasspath].join(delimiter),
    "dev.gaius.tools.Minecraft12111BrowserPatcher", clientJar, patches], {
    encoding: "utf8", timeout: 60_000,
  });
  const patchFiles = await filesUnder(patches);
  const patchNames = patchFiles.map(file => relative(patches, file).replaceAll("\\", "/"));
  assert.ok(patchNames.includes("net/minecraft/server/level/ChunkGenerationTask.class"),
    "dedicated patcher did not emit ChunkGenerationTask.class");
  assert.ok(patchNames.includes("dev/gaius/browser/BrowserChunkGenerationYield.class"),
    "dedicated patcher did not emit BrowserChunkGenerationYield.class");
  assert.ok(patchNames.includes("net/minecraft/client/GraphicsPreset.class"),
    "dedicated patcher did not emit GraphicsPreset.class");
  for (const name of ["net/minecraft/client/Options.class", "net/minecraft/client/Minecraft.class"]) {
    assert.ok(patchNames.includes(name),
      `dedicated patcher did not emit ${name} (graphics preset startup replay)`);
  }

  const rawGraphics = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
    });
  await execFileSync(jar, ["--update", "--file", clientJar, "-C", patches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const patchedGraphics = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
    });
  const rawGraphicsApply = graphicsPresetApply(rawGraphics);
  const patchedGraphicsApply = graphicsPresetApply(patchedGraphics);
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "" + LEGACY_PROFILE.id + " raw FAST render distance shape changed");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "" + LEGACY_PROFILE.id + " raw FAST simulation distance shape changed");
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "" + LEGACY_PROFILE.id + " FAST render distance was reduced from vanilla 8");
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "" + LEGACY_PROFILE.id + " FAST simulation distance was reduced from vanilla 6");
  assert.equal(graphicsPresetCustomReturn(patchedGraphicsApply),
    graphicsPresetCustomReturn(rawGraphicsApply),
    "" + LEGACY_PROFILE.id + " CUSTOM graphics preset arm changed");
  const patchedMinecraft = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.Minecraft"], {
      encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 60_000,
    });
  const minecraftInit = method(patchedMinecraft,
    "public net.minecraft.client.Minecraft(net.minecraft.client.main.GameConfig);");
  assert.equal(occurrences(minecraftInit, "Options.gaius$applyStartupGraphicsPreset"), 1,
    "" + LEGACY_PROFILE.id + " Minecraft.<init> must replay the graphics preset through the startup gate once");
  assert.equal(occurrences(minecraftInit, "Options.applyGraphicsPreset"), 0,
    "" + LEGACY_PROFILE.id + " Minecraft.<init> still re-applies the saved graphics preset unconditionally");

  const verifierClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", verifierClasspath,
    "-sourcepath", join(repositoryRoot, "port/tools/src/main/java"), "-d", classes, verifierSource,
  ], {encoding: "utf8", timeout: 30_000});
  const verifierOutput = execFileSync(java, [
    "-Xverify:all", "-classpath", [classes, verifierClasspath].join(delimiter),
    "GaiusChunkLayerBytecodeVerifier", clientJar, LEGACY_PROFILE.id,
  ], {encoding: "utf8", timeout: 30_000});
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*ChunkGenerationTask\.class/);
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*BrowserChunkGenerationYield\.class/);
  assert.match(verifierOutput, /CFG_VERIFIER_OK net\/minecraft\/server\/level\/ChunkGenerationTask/,
    "ASM CFG verifier did not validate the " + LEGACY_PROFILE.id + " chunk layer barrier");
  assert.match(verifierOutput,
    new RegExp(String.raw`PROFILE_CALL_SURFACE_OK ${profileIdPattern(LEGACY_PROFILE)} net\/minecraft\/server\/level\/ChunkGenerationTask`),
    "ASM profile verifier did not validate " + LEGACY_PROFILE.id + " task scheduler call surface");
  assert.match(verifierOutput,
    new RegExp(String.raw`PROFILE_CALL_SURFACE_OK ${profileIdPattern(LEGACY_PROFILE)} dev\/gaius\/browser\/BrowserChunkGenerationYield`),
    "ASM profile verifier did not validate " + LEGACY_PROFILE.id + " yield scheduler call surface");
  process.stdout.write(verifierOutput);

  const bytecode = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.ChunkGenerationTask"], {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
  });
  assert.doesNotMatch(bytecode, /BrowserWorldgenScheduler/,
    "" + LEGACY_PROFILE.id + " ChunkGenerationTask must remain scheduler-call free");
  assert.match(bytecode, /Platform\.startThread/,
    "" + LEGACY_PROFILE.id + " ChunkGenerationTask must bootstrap holder continuations in a TeaVM thread");
  assert.match(bytecode, /Field net\/minecraft\/world\/level\/ChunkPos\.x:I/,
    "" + LEGACY_PROFILE.id + " patched bytecode must use ChunkPos.x field");
  assert.match(bytecode, /Field net\/minecraft\/world\/level\/ChunkPos\.z:I/,
    "" + LEGACY_PROFILE.id + " patched bytecode must use ChunkPos.z field");
  const runUntilWait = method(bytecode,
    "public java.util.concurrent.CompletableFuture<?> runUntilWait();",
    "private void scheduleNextLayer");
  const runInstructions = bytecodeInstructions(runUntilWait);
  const backedges = runInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(backedges.length, 1, "runUntilWait must retain one loop backedge");
  const yieldIndex = runInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerYield"));
  assert.ok(yieldIndex > 0, "runUntilWait yield gate is missing");
  assert.equal(backedges[0].target, runInstructions[yieldIndex - 1].offset,
    "runUntilWait backedge must re-enter the yield gate");
  const activeIndex = runInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerActive"));
  const firstScheduleNextIndex = runInstructions.findIndex(entry =>
    entry.instruction.includes("Method scheduleNextLayer:()V"));
  const firstLayerWaitIndex = runInstructions.findIndex(entry =>
    entry.instruction.includes("Method waitForScheduledLayer:"));
  assert.ok(yieldIndex < activeIndex
      && activeIndex < firstScheduleNextIndex
      && firstScheduleNextIndex < firstLayerWaitIndex,
  "" + LEGACY_PROFILE.id + " active cursor must resume before waitForScheduledLayer is reachable");
  assert.equal(occurrences(runUntilWait, "Method scheduleNextLayer:()V"), 2,
    "" + LEGACY_PROFILE.id + " runUntilWait must retain active-resume and fresh-layer scheduling paths");
  const scheduleLayer = method(bytecode,
    "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    "private int getRadiusForLayer");
  assert.match(scheduleLayer, /Exception table:[\s\S]*Throwable/,
    "scheduleLayer must clean cursor state on holder exceptions");
  const cleanup = scheduleLayer.slice(scheduleLayer.lastIndexOf("astore"));
  assert.match(cleanup, /Field browserLayerActive/,
    "scheduleLayer exception path must clear active cursor state");
  assert.match(cleanup, /Field browserLayerYield/,
    "scheduleLayer exception path must clear pending continuation");
  const scheduleInstructions = bytecodeInstructions(scheduleLayer);
  const batchBackedges = scheduleInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^if_icmplt\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(batchBackedges.length, 1,
    "" + LEGACY_PROFILE.id + " scheduleLayer must retain one bounded holder-batch backedge");
  assert.match(scheduleInstructions[batchBackedges[0].index - 1]?.instruction ?? "",
    /(?:bipush\s+16|ldc(?:_w)?\s+.*\/\/ int 16)/,
    "" + LEGACY_PROFILE.id + " batch backedge must enforce the 16-holder upper bound");
  assert.ok(scheduleLayer.indexOf("scheduleChunkInLayer") < scheduleLayer.indexOf("Platform.startThread"),
    "" + LEGACY_PROFILE.id + " must submit holders before publishing its batch continuation");
  assert.equal(occurrences(scheduleLayer, "BrowserWorldgenScheduler"), 0);
  console.log("Minecraft " + LEGACY_PROFILE.id + " checkpoint-only holder cursor smoke passed", JSON.stringify({
    patchClasses: patchFiles.length,
    inputBytes: (await stat(rawClientJar)).size,
    cursorRadii: [0, 1, 2],
    cancellation: true,
    failure: true,
    finalHolder: true,
    holderBatchLimit: HOLDERS_PER_TURN,
    layerBarrier: true,
  }));
} finally {
  await rm(root, {recursive: true, force: true});
}

async function filesUnder(directory) {
  const files = [];
  async function visit(current) {
    for (const entry of await readdir(current, {withFileTypes: true})) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) files.push(path);
    }
  }
  await visit(directory);
  return files;
}
