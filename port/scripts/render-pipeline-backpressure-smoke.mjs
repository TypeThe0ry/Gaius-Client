#!/usr/bin/env node

import {CANONICAL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {basename, delimiter, join} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {patcherSource as stepSource} from "./patcher-sources.mjs";

const scriptsDirectory = fileURLToPath(new URL(".", import.meta.url));
const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const schedulerPath = fileURLToPath(
  new URL("../src/main/java/dev/gaius/browser/BrowserRenderScheduler.java", import.meta.url),
);
const scheduler = await readFile(schedulerPath, "utf8");
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = stepSource("MinecraftClientPatcher");
const patcher262 = stepSource("Minecraft262BrowserPatcher");
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const config = JSON.parse(await readFile(new URL("../config.json", import.meta.url), "utf8"));
const buildRootProfileId = process.env.GAIUS_BUILD_ROOT
  ? profileIdFromPath(process.env.GAIUS_BUILD_ROOT) : "";
const overlayProfileId = process.env.GAIUS_OVERLAY_DIRECTORY
  ? profileIdFromPath(process.env.GAIUS_OVERLAY_DIRECTORY) : "";
const isolatedProfileId = [buildRootProfileId, overlayProfileId]
  .find((value) => /^\d+(?:\.\d+)+$/.test(value)) || "";
const configuredProfilePath = nativePath(
  process.env.GAIUS_VERSION_PROFILE_PATH
    || (isolatedProfileId ? `versions/${isolatedProfileId}.json` : String(config.versionProfile || "")),
);
const configuredProfileUrl = /^[A-Za-z]:[\\/]/.test(configuredProfilePath)
  || configuredProfilePath.startsWith("/")
  ? pathToFileURL(configuredProfilePath)
  : new URL(`../${configuredProfilePath.replaceAll("\\", "/")}`, import.meta.url);
const profile = JSON.parse(await readFile(
  configuredProfileUrl,
  "utf8",
));
const version = String(profile.id);
const configuredProfileId = process.env.GAIUS_VERSION_PROFILE_PATH
  ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH)
  : (isolatedProfileId || version);
if (configuredProfileId !== version) {
  throw new Error(`render pipeline smoke is for profile ${version}, got ${configuredProfileId}`);
}
if (version !== CANONICAL_PROFILE.id) {
  throw new Error(`render pipeline backpressure smoke is ${CANONICAL_PROFILE.id}-only; got profile ${version}`);
}
const overlayRoot = nativePath(process.env.GAIUS_OVERLAY_DIRECTORY ||
  `${repositoryRoot}/port/work/overlays${process.env.GAIUS_BUILD_ROOT || process.env.GAIUS_VERSION_PROFILE_PATH ? `/${version}` : ""}`);
const overlayJar = `${overlayRoot}/client-named-${version}-gaius.jar`;
const rawClientJar = join(repositoryRoot, "port/work/" + CANONICAL_PROFILE.id + "/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");

function javaTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

for (const contract of [
  "int maxUploadAllocationsPerFrame,",
  "long uploadWorkNanos,",
  "long uploadBytesPerFrame,",
  "MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME = 8",
  "UBER_NODE_CLEANUP_BUDGET_NANOS = 250_000L",
  "MAX_UPLOAD_RETRY_YIELDS = 2_048",
  "MAX_UPLOAD_RETRY_NANOS = 5_000_000_000L",
  "UPLOAD_RETRY_SWEEP_INTERVAL_NANOS = 1_000_000_000L",
  "UPLOAD_RETRY_TOMBSTONE_IDLE_NANOS = 5_000_000_000L",
  "scheduleDispatcher(",
  "rememberDispatcherContinuation(",
  "finishDispatcherRun(",
  "disposeDispatcher(",
  "shouldUploadNext(",
  "finishUploadBuffer(",
  "releaseUploadBuffer(",
  "requestEmergencyUpload(",
  "beginUberNodeCleanup(",
  "shouldCleanUberNode(",
  "finishUberNodeCleanup(",
  "UBER_NODE_CLEANUP_CURSORS",
  "awaitUploadRetry(",
  "clearUploadRetry(",
  "sweepExpiredUploadRetries()",
  "TModernRuntimeSupport.yieldToEventLoop(1)",
  "emergencyUploadRequests",
  "emergencyUploadDrains",
  "emergencyUploadDeferrals",
  "uploadRetryYields",
  "uploadRetryNoProgressResumes",
  "uploadRetryCancellations",
  "uploadRetryExpiredStates",
  "lastTaskDrainCount",
  "currentUploadDrainCount",
  "uploadBudgetExhaustions",
  "dispatcherUploadDeferrals",
  "FRAME_WATCHDOG_MILLIS = 50",
  "TModernRuntimeSupport.yieldToEventLoop(0)",
  "longestTaskMillis",
]) {
  assert.ok(scheduler.includes(contract), `missing render scheduler contract: ${contract}`);
}
assert.ok(
  scheduler.includes("Math.max(pendingTasks(), compileBacklog)"),
  "section extraction backpressure ignores the dispatcher backlog",
);
assert.ok(
  scheduler.includes("QUEUE.addFirst(state.runner)"),
  "dispatcher work is not prioritized over generic deferred work",
);
assert.ok(
  scheduler.includes("if (uploadBacklog > 0)") &&
    scheduler.includes("deferDispatcherUntilNextFrame(state)"),
  "dispatcher can refill a non-rotatable staging buffer before pending uploads drain",
);

for (const contract of [
  "patchCurrentUberGpuBufferUploadBudget(node)",
  "patchCurrentSectionRenderDispatcherBufferBudgets(node)",
  '"shouldUploadNext"',
  '"finishUploadBuffer"',
  '"releaseUploadBuffer"',
  '"scheduleDispatcher"',
  '"rememberDispatcherContinuation"',
  '"finishDispatcherRun"',
  '"disposeDispatcher"',
  "patchCurrentSectionTaskQueueBrowserPriorities",
]) {
  assert.ok(patcher.includes(contract), `missing render bytecode patch contract: ${contract}`);
}
for (const contract of [
  '"lambda$new$0"',
  '"(Ljava/lang/String;IIILcom/mojang/blaze3d/vertex/StagingBuffer;)V"',
  "constructorVertexHeap != 1 || constructorIndexHeap != 1 || staging != 2",
  "uberConstructors != 2 || vertexHeap != 1 || indexHeap != 1",
  "usage.operand == 32",
  "Integer.valueOf(134217728).equals(capacity.cst)",
  "capacity.cst = BROWSER_SECTION_VERTEX_HEAP_BYTES",
  "usage.operand == 64",
  "Integer.valueOf(33554432).equals(capacity.cst)",
  "capacity.cst = BROWSER_SECTION_INDEX_HEAP_BYTES",
  '"Current section renderer lambda UberGpuBuffer constructor shape changed"',
  '"Current section renderer lambda heap budgets changed: constructors="',
]) {
  assert.ok(patcher.includes(contract),
    `missing fail-closed SectionRenderDispatcher heap-budget contract: ${contract}`);
}
for (const contract of [
  "patchSectionRenderEmergencyUpload",
  "patchSectionRenderTaskRetryYields",
  '"requestEmergencyUpload"',
  '"awaitUploadRetry"',
  '"clearUploadRetry"',
  "addUploadRetryExceptionCleanup(method)",
  "TryCatchBlockNode",
  "writeComputeFrames(node, root.resolve(owner + \".class\"))",
  "ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS",
  '"uploadTerrainBuffersToGpu"',
  "patchUberGpuBufferNodeCleanup",
  '"beginUberNodeCleanup"',
  '"shouldCleanUberNode"',
  '"finishUberNodeCleanup"',
  '"UberGpuBuffer upload budget patch must run before node cleanup patch"',
]) {
  assert.ok(patcher262.includes(contract), `missing ${CANONICAL_PROFILE.id} progress patch contract: ${contract}`);
}

function javapFrom(classpath, className) {
  return execFileSync(
    javaTool("javap"),
    ["-classpath", classpath, "-p", "-c", className],
    {cwd: scriptsDirectory, encoding: "utf8", maxBuffer: 32 * 1024 * 1024},
  );
}

function javap(className) {
  return javapFrom(overlayJar, className);
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature ? bytecode.indexOf(nextSignature, start + signature.length) : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

function bytecodeInstructions(bytecode) {
  return [...bytecode.matchAll(/^\s*(\d+):\s+(.+)$/gm)]
    .map((match) => ({offset: Number(match[1]), instruction: match[2].trim()}));
}

const bufferBudgetPatchRoot = await mkdtemp(join(tmpdir(), "gaius-section-buffer-budget-"));
try {
  const classesDirectory = join(bufferBudgetPatchRoot, "classes");
  const patchesDirectory = join(bufferBudgetPatchRoot, "patches");
  const verifierSource = join(bufferBudgetPatchRoot, "GaiusSectionBufferBudgetVerifier.java");
  await Promise.all([
    mkdir(classesDirectory, {recursive: true}),
    mkdir(patchesDirectory, {recursive: true}),
  ]);
  for (const required of [rawClientJar, asm, asmTree, asmAnalysis]) {
    assert.ok(existsSync(required), `missing SectionRenderDispatcher budget smoke input: ${required}`);
  }
  const patcherClasspath = [asm, asmTree].join(delimiter);
  execFileSync(javaTool("javac"), [
    "--release", "21", "-proc:none", "-classpath", patcherClasspath,
    "-sourcepath", join(repositoryRoot, "port/tools/src/main/java"),
    "-d", classesDirectory, join(toolsSource, "MinecraftClientPatcher.java"),
  ], {cwd: scriptsDirectory, encoding: "utf8", timeout: 60_000});
  execFileSync(javaTool("java"), [
    "-classpath", [classesDirectory, patcherClasspath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", rawClientJar, patchesDirectory, CANONICAL_PROFILE.id,
  ], {cwd: scriptsDirectory, encoding: "utf8", timeout: 60_000});

  const patchedDispatcher = javapFrom(
    [patchesDirectory, rawClientJar].join(delimiter),
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
  );
  const patchedConstructor = method(
    patchedDispatcher,
    "public net.minecraft.client.renderer.chunk.SectionRenderDispatcher(",
    "public void setCompiler",
  );
  assert.doesNotMatch(patchedConstructor, /\/\/ int (?:134217728|33554432|102760448)\b/,
    "patched SectionRenderDispatcher constructor retained a desktop heap/staging budget");
  const constructorInstructions = bytecodeInstructions(patchedConstructor);
  const stagingCreateIndex = constructorInstructions.findIndex(({instruction}) =>
    instruction.includes("StagingBuffer.create:"));
  assert.ok(stagingCreateIndex > 0 &&
    /^ldc(?:_w)?\s+.*\/\/ int 16777216\b/.test(
      constructorInstructions[stagingCreateIndex - 1].instruction),
  "SectionRenderDispatcher staging buffer allocation is not 16 MiB");
  const stagingLocalStoreIndex = constructorInstructions.findIndex(({instruction}, index) =>
    /^istore\s+7\b/.test(instruction) && index > 0);
  assert.ok(stagingLocalStoreIndex > 0 &&
    /^ldc(?:_w)?\s+.*\/\/ int 16777216\b/.test(
      constructorInstructions[stagingLocalStoreIndex - 1].instruction),
  "SectionRenderDispatcher staging budget mirror is not 16 MiB");

  const patchedHeapFactory = method(
    patchedDispatcher,
    "private net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers "
      + "lambda$new$0(net.minecraft.client.renderer.chunk.ChunkSectionLayer);",
  );
  assert.doesNotMatch(patchedHeapFactory, /\/\/ int (?:134217728|33554432)\b/,
    "lambda$new$0 retained a 128/32 MiB desktop UberGpuBuffer heap");
  const heapInstructions = bytecodeInstructions(patchedHeapFactory);
  const heapConstructors = heapInstructions
    .map(({instruction}, index) => ({instruction, index}))
    .filter(({instruction}) => instruction.includes(
      'UberGpuBuffer."<init>":(Ljava/lang/String;IIILcom/mojang/blaze3d/vertex/StagingBuffer;)V'));
  assert.equal(heapConstructors.length, 2,
    "lambda$new$0 must construct exactly one vertex and one index UberGpuBuffer");
  const vertexCall = heapConstructors[0].index;
  const indexCall = heapConstructors[1].index;
  assert.match(heapInstructions[vertexCall - 11]?.instruction || "", /new\s+.*UberGpuBuffer/,
    "vertex heap constructor allocation moved");
  assert.equal(heapInstructions[vertexCall - 10]?.instruction, "dup",
    "vertex heap constructor DUP moved");
  assert.match(heapInstructions[vertexCall - 8]?.instruction || "", /ChunkSectionLayer\.label/,
    "vertex heap label argument changed");
  assert.match(heapInstructions[vertexCall - 7]?.instruction || "", /^bipush\s+32$/,
    "vertex heap usage must remain 32");
  assert.match(heapInstructions[vertexCall - 6]?.instruction || "",
    /^ldc(?:_w)?\s+.*\/\/ int 16777216\b/,
    "vertex UberGpuBuffer heap is not 16 MiB");
  assert.match(heapInstructions[vertexCall - 4]?.instruction || "", /VertexFormat\.getVertexSize/,
    "vertex heap stride no longer comes from VertexFormat.getVertexSize");
  // v0.4: the stride is rounded to a 4-vertex alignment so every section base vertex is a
  // multiple of 4 (shared quad index buffer); gaiusTerrainAlign=0 keeps the vanilla stride.
  assert.match(heapInstructions[vertexCall - 3]?.instruction || "",
    /BrowserTerrainBatch\.vertexHeapAlignment:\(I\)I/,
    "vertex heap stride is not wrapped in BrowserTerrainBatch.vertexHeapAlignment");
  assert.match(heapInstructions[vertexCall - 1]?.instruction || "", /Field stagingBuffer:/,
    "vertex heap no longer uses the dispatcher staging buffer");

  assert.match(heapInstructions[indexCall - 9]?.instruction || "", /new\s+.*UberGpuBuffer/,
    "index heap constructor allocation moved");
  assert.equal(heapInstructions[indexCall - 8]?.instruction, "dup",
    "index heap constructor DUP moved");
  assert.match(heapInstructions[indexCall - 6]?.instruction || "", /ChunkSectionLayer\.label/,
    "index heap label argument changed");
  assert.match(heapInstructions[indexCall - 5]?.instruction || "", /^bipush\s+64$/,
    "index heap usage must remain 64");
  assert.match(heapInstructions[indexCall - 4]?.instruction || "",
    /^ldc(?:_w)?\s+.*\/\/ int 4194304\b/,
    "index UberGpuBuffer heap is not 4 MiB");
  assert.match(heapInstructions[indexCall - 3]?.instruction || "", /^bipush\s+8$/,
    "index heap stride must remain 8");
  assert.match(heapInstructions[indexCall - 1]?.instruction || "", /Field stagingBuffer:/,
    "index heap no longer uses the dispatcher staging buffer");

  await writeFile(verifierSource, `
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class GaiusSectionBufferBudgetVerifier {
    public static void main(String[] args) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(node, 0);
        int verified = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            verified++;
        }
        if (verified == 0) throw new IllegalStateException("no methods verified");
        System.out.println("BASIC_VERIFIER_OK " + node.name + " methods=" + verified);
    }
}
`, "utf8");
  const verifierClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
  execFileSync(javaTool("javac"), [
    "--release", "21", "-proc:none", "-classpath", verifierClasspath,
    "-d", classesDirectory, verifierSource,
  ], {cwd: scriptsDirectory, encoding: "utf8", timeout: 30_000});
  const verifierOutput = execFileSync(javaTool("java"), [
    "-classpath", [classesDirectory, verifierClasspath].join(delimiter),
    "GaiusSectionBufferBudgetVerifier",
    join(patchesDirectory,
      "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class"),
  ], {cwd: scriptsDirectory, encoding: "utf8", timeout: 30_000});
  assert.match(verifierOutput,
    /BASIC_VERIFIER_OK net\/minecraft\/client\/renderer\/chunk\/SectionRenderDispatcher/,
    "ASM BasicVerifier rejected the patched SectionRenderDispatcher");
} finally {
  await rm(bufferBudgetPatchRoot, {recursive: true, force: true});
}

const uber = javap("com.mojang.blaze3d.vertex.UberGpuBuffer");
const upload = method(
  uber,
  "public boolean uploadStagedAllocations",
  "private static <T, U extends T> void runCallbackUnchecked",
);
for (const contract of [
  "BrowserRenderScheduler.shouldUploadNext",
  "java/util/Iterator.remove",
  "BrowserRenderScheduler.finishUploadBuffer",
]) {
  assert.ok(upload.includes(contract), `patched upload is missing: ${contract}`);
}
assert.ok(
  !upload.includes("Object2ObjectOpenHashMap.clear"),
  "partial upload still clears unprocessed staged entries",
);
assert.ok(
  !upload.includes("ObjectOpenHashSet.clear"),
  "partial upload still clears skip markers for unprocessed entries",
);
const close = method(uber, "public void close()", "static {};");
assert.ok(
  close.includes("BrowserRenderScheduler.releaseUploadBuffer"),
  "UberGpuBuffer.close does not release scheduler telemetry ownership",
);

const dispatcher = javap("net.minecraft.client.renderer.chunk.SectionRenderDispatcher");
const runTask = method(dispatcher, "private void runTask()", "public void setCameraPosition");
const schedule = method(dispatcher, "private void schedule", "public void clearCompileQueue");
const dispose = method(dispatcher, "public void dispose()", "public java.lang.String getStats");
assert.ok(schedule.includes("BrowserRenderScheduler.scheduleDispatcher"),
  "dispatcher schedule does not use the coalesced runner");
assert.ok(runTask.includes("BrowserRenderScheduler.rememberDispatcherContinuation"),
  "dispatcher continuation still enqueues an empty tail runner");
assert.equal(
  runTask.split("BrowserRenderScheduler.finishDispatcherRun").length - 1,
  3,
  "not every " + CANONICAL_PROFILE.id + " runTask return reports the real dispatcher backlog",
);
assert.ok(dispose.includes("BrowserRenderScheduler.disposeDispatcher"),
  "dispatcher dispose does not release its queued runner");
const renderSection = javap(
  "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection",
);
const addSectionBuffers = method(
  renderSection,
  "private boolean addSectionBuffersToUberBuffer",
  "private void lambda$addSectionBuffersToUberBuffer$1",
);
assert.ok(
  addSectionBuffers.includes("BrowserRenderScheduler.requestEmergencyUpload") &&
    addSectionBuffers.indexOf("BrowserRenderScheduler.requestEmergencyUpload") <
      addSectionBuffers.indexOf("SectionRenderDispatcher.uploadTerrainBuffersToGpu"),
  "staging-capacity retry can still spin without forcing one progress upload",
);
for (const taskName of ["CompileTask", "ResortTransparencyTask"]) {
  const task = javap(
    `net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$${taskName}`,
  );
  const doTask = method(task, "public net.minecraft.client.renderer.chunk."
    + "SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult doTask", "public void cancel");
  assert.ok(doTask.includes("BrowserRenderScheduler.awaitUploadRetry"),
    `${taskName} can retry staging in one uninterruptible JS turn`);
  assert.ok(doTask.includes("BrowserRenderScheduler.clearUploadRetry"),
    `${taskName} retains retry telemetry after completing`);
  assert.ok(doTask.includes("Class java/lang/Throwable"),
    `${taskName} has no retry cleanup exception handler`);
  assert.ok(
    doTask.split("BrowserRenderScheduler.clearUploadRetry").length - 1 >= 3,
    `${taskName} does not clean retry ownership on every exit class`,
  );
  assert.ok(doTask.includes("Method cancel:()V"),
    `${taskName} has no bounded-retry cancellation path`);
  assert.ok(!doTask.includes("RenderSystem.isOnRenderThread"),
    `${taskName} still bypasses the asynchronous upload retry`);
  assert.ok(!doTask.includes("Thread.onSpinWait"),
    `${taskName} still retains a desktop spin wait`);
}

const uploadBudgetCheck = scheduler.indexOf(
  "if (currentUploadDrainCount >= effectiveMaxUploadAllocationsPerFrame())",
  scheduler.indexOf("public static boolean shouldUploadNext"),
);
const emergencyDrainCheck = scheduler.indexOf(
  "if (emergencyUploadEntriesRemaining > 0)",
  scheduler.indexOf("public static boolean shouldUploadNext"),
);
assert.ok(uploadBudgetCheck >= 0 && uploadBudgetCheck < emergencyDrainCheck,
  "emergency uploads can bypass the per-frame allocation hard limit");

const queue = javap("net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue");
const dirtyClassification = method(
  queue,
  "private static boolean browserIsDirtyCompile",
  "private double browserDistance",
);
assert.equal(
  dirtyClassification.split("instanceof").filter((part) => part.includes("CompileTask")).length,
  1,
  "dirty compile classification does not exclude transparency resort tasks",
);
assert.ok(dirtyClassification.includes("SectionTask.isRecompile"),
  "dirty compile classification ignores the recompile flag");
const gameRenderer = javap("net.minecraft.client.renderer.GameRenderer");
assert.ok(gameRenderer.includes("BrowserRenderScheduler.beginFrame"),
  "upload budget is not reset from the real render-frame boundary");

const pending = Array.from({length: 19}, (_, index) => index);
const callbacks = [];
const drains = [];
while (pending.length > 0) {
  let drained = 0;
  while (pending.length > 0 && drained < 8) {
    callbacks.push(pending.shift());
    drained++;
  }
  drains.push(drained);
}
assert.deepEqual(drains, [8, 8, 3], "max-entry frame budget model regressed");
assert.deepEqual(callbacks, Array.from({length: 19}, (_, index) => index),
  "resumable upload model lost or duplicated callbacks");

function cleanUberNodes(nodes, cursors, owner, maximumScans) {
  if (nodes.length === 0) {
    cursors.delete(owner);
    return {scanned: 0, released: 0};
  }
  let cursor = (cursors.get(owner) ?? 0) % nodes.length;
  let scanned = 0;
  let released = 0;
  while (nodes.length > 0 && scanned < maximumScans) {
    if (nodes[cursor].free) {
      nodes[cursor].closed = true;
      nodes.splice(cursor, 1);
      released++;
    } else {
      cursor++;
    }
    scanned++;
    if (nodes.length > 0) cursor %= nodes.length;
  }
  if (nodes.length === 0) cursors.delete(owner);
  else cursors.set(owner, cursor);
  return {scanned, released};
}

const cleanupCursorState = new Map();
const cleanupOwner = {};
const uberNodes = Array.from({length: 23}, (_, index) => ({
  id: index,
  free: index % 3 === 1,
  closed: false,
}));
const initialFreeNodeIds = uberNodes.filter(node => node.free).map(node => node.id);
let cleanupScans = 0;
while (uberNodes.some(node => node.free)) {
  const pass = cleanUberNodes(uberNodes, cleanupCursorState, cleanupOwner, 8);
  assert.ok(pass.scanned <= 8, "UberGpuBuffer cleanup exceeded its frame scan budget");
  cleanupScans += pass.scanned;
}
assert.deepEqual(
  uberNodes.filter(node => node.closed).map(node => node.id),
  [],
  "released UberGpuBuffer nodes remained in the live heap list",
);
assert.ok(cleanupScans >= initialFreeNodeIds.length,
  "UberGpuBuffer cleanup skipped a free heap node");
assert.ok(cleanupCursorState.has(cleanupOwner),
  "UberGpuBuffer cursor vanished while reusable heaps remained");
for (const node of uberNodes) node.free = true;
while (uberNodes.length > 0) {
  const pass = cleanUberNodes(uberNodes, cleanupCursorState, cleanupOwner, 8);
  assert.ok(pass.scanned <= 8, "UberGpuBuffer final cleanup exceeded its frame scan budget");
}
assert.equal(cleanupCursorState.has(cleanupOwner), false,
  "UberGpuBuffer cleanup cursor leaked after all heap nodes were released");

const retryRegressionRoot = await mkdtemp(join(tmpdir(), "gaius-render-retry-"));
try {
  const classesDirectory = join(retryRegressionRoot, "classes");
  const sourceDirectory = join(retryRegressionRoot, "src");
  await mkdir(classesDirectory, {recursive: true});

  const sources = new Map([
    ["org/teavm/classlib/java/lang/TModernRuntimeSupport.java", String.raw`
package org.teavm.classlib.java.lang;
public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {}
    public static void yieldToEventLoop(int millis) {}
}
`],
    ["org/teavm/jso/JSBody.java", String.raw`
package org.teavm.jso;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JSBody {
    String[] params() default {};
    String script() default "";
}
`],
    ["org/teavm/jso/browser/Window.java", String.raw`
package org.teavm.jso.browser;
public final class Window {
    private Window() {}
    @FunctionalInterface
    public interface FrameCallback {
        void onAnimationFrame(double timestamp);
    }
    public static int requestAnimationFrame(FrameCallback callback) { return 1; }
    public static void cancelAnimationFrame(int requestId) {}
}
`],
    // HostLoop (the JavaScript build's classlib overlay, compiled below) delegates scheduling to
    // Platform; the stub runs every command inline so the harness stays single-threaded.
    ["org/teavm/platform/PlatformRunnable.java", String.raw`
package org.teavm.platform;
@FunctionalInterface
public interface PlatformRunnable {
    void run();
}
`],
    ["org/teavm/platform/Platform.java", String.raw`
package org.teavm.platform;
public final class Platform {
    private Platform() {}
    public static void startThread(PlatformRunnable command) { command.run(); }
    public static int schedule(PlatformRunnable command, int timeout) { command.run(); return 1; }
    public static void killSchedule(int id) {}
}
`],
    ["dev/gaius/browser/render/BrowserMeshInstallQueue.java", String.raw`
package dev.gaius.browser.render;
public final class BrowserMeshInstallQueue {
    private BrowserMeshInstallQueue() {}
    public static int inFlight() { return 0; }
    public static int readyCount() { return 0; }
    public static long drain(long byteBudget, long deadlineNanos) { return 0L; }
}
`],
    ["dev/gaius/browser/BrowserRenderSchedulerRetryRegression.java", String.raw`
package dev.gaius.browser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class BrowserRenderSchedulerRetryRegression {
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> scheduler = BrowserRenderScheduler.class;
        Class<?> retryState = Class.forName(
                "dev.gaius.browser.BrowserRenderScheduler$UploadRetryState");
        Constructor<?> retryStateConstructor = retryState.getDeclaredConstructor(long.class);
        retryStateConstructor.setAccessible(true);

        Field retryStatesField = field(scheduler, "UPLOAD_RETRY_STATES");
        @SuppressWarnings("unchecked")
        Map<Object, Object> retryStates = (Map<Object, Object>) retryStatesField.get(null);
        long maxRetryNanos = field(scheduler, "MAX_UPLOAD_RETRY_NANOS").getLong(null);
        int maxRetryYields = field(scheduler, "MAX_UPLOAD_RETRY_YIELDS").getInt(null);
        long idleNanos = field(scheduler, "UPLOAD_RETRY_TOMBSTONE_IDLE_NANOS").getLong(null);
        Field terminalField = field(retryState, "terminal");
        Field yieldsField = field(retryState, "yields");
        Field lastTouchedField = field(retryState, "lastTouchedAtNanos");

        Method sweep = scheduler.getDeclaredMethod("sweepExpiredUploadRetries", long.class);
        sweep.setAccessible(true);
        Method awaitRetry = scheduler.getDeclaredMethod("awaitUploadRetry", Object.class);
        awaitRetry.setAccessible(true);

        Object sweptTask = new Object();
        long sweepNow = 20_000_000_000L;
        Object sweptState = retryStateConstructor.newInstance(sweepNow - maxRetryNanos);
        retryStates.put(sweptTask, sweptState);
        sweep.invoke(null, sweepNow);
        check(retryStates.get(sweptTask) == sweptState,
                "time-expired retry was deleted instead of tombstoned");
        check(terminalField.getBoolean(sweptState),
                "time-expired retry was not marked terminal");
        sweep.invoke(null, sweepNow + idleNanos - 1L);
        check(retryStates.get(sweptTask) == sweptState,
                "terminal retry was reclaimed before the idle grace");
        check(!(Boolean) awaitRetry.invoke(null, sweptTask),
                "next await recreated a swept retry budget");
        check(retryStates.get(sweptTask) == sweptState,
                "next await replaced the terminal retry state");
        check(yieldsField.getInt(sweptState) == 0,
                "terminal retry yielded or consumed a fresh attempt");
        long sweptTouchedAt = lastTouchedField.getLong(sweptState);
        sweep.invoke(null, sweptTouchedAt + idleNanos - 1L);
        check(retryStates.containsKey(sweptTask),
                "active tombstone was reclaimed before a full idle grace");
        sweep.invoke(null, sweptTouchedAt + idleNanos);
        check(!retryStates.containsKey(sweptTask),
                "idle terminal tombstone was not reclaimed");

        Object attemptTask = new Object();
        long attemptNow = System.nanoTime();
        Object attemptState = retryStateConstructor.newInstance(attemptNow);
        yieldsField.setInt(attemptState, maxRetryYields - 1);
        retryStates.put(attemptTask, attemptState);
        check(!(Boolean) awaitRetry.invoke(null, attemptTask),
                "attempt-limited retry did not terminate");
        check(terminalField.getBoolean(attemptState),
                "attempt-limited retry was removed instead of tombstoned");
        check(retryStates.get(attemptTask) == attemptState,
                "attempt-limited retry lost its terminal identity");
        check(!(Boolean) awaitRetry.invoke(null, attemptTask),
                "terminal attempt-limited retry received a fresh budget");
        check(yieldsField.getInt(attemptState) == maxRetryYields,
                "terminal retry consumed attempts after cancellation");
        long attemptTouchedAt = lastTouchedField.getLong(attemptState);
        sweep.invoke(null, attemptTouchedAt + idleNanos);
        check(!retryStates.containsKey(attemptTask),
                "attempt-limited tombstone exceeded its idle lifetime");

        System.out.println("Upload retry tombstone regression passed");
    }
}
`],
    ["dev/gaius/browser/BrowserRenderSchedulerUberCleanupRegression.java", String.raw`
package dev.gaius.browser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class BrowserRenderSchedulerUberCleanupRegression {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class<?> scheduler = BrowserRenderScheduler.class;
        Field cursorField = scheduler.getDeclaredField("UBER_NODE_CLEANUP_CURSORS");
        cursorField.setAccessible(true);
        Map<Object, Integer> cursors = (Map<Object, Integer>) cursorField.get(null);
        Field scanLimitField = scheduler.getDeclaredField("MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME");
        scanLimitField.setAccessible(true);
        int scanLimit = scanLimitField.getInt(null);
        // beginFrame reads the per-frame Budget, whose profile probe is a @JSBody native;
        // pin the normal profile so the JVM harness never calls it.
        Field fastProfileInitialized = scheduler.getDeclaredField("fastProfileInitialized");
        fastProfileInitialized.setAccessible(true);
        fastProfileInitialized.setBoolean(null, true);

        Method beginFrame = scheduler.getDeclaredMethod("beginFrame");
        Method begin = scheduler.getDeclaredMethod(
                "beginUberNodeCleanup", Object.class, int.class);
        Method shouldClean = scheduler.getDeclaredMethod("shouldCleanUberNode", Object.class);
        Method finish = scheduler.getDeclaredMethod(
                "finishUberNodeCleanup",
                Object.class, int.class, int.class, int.class, int.class);

        Object buffer = new Object();
        beginFrame.invoke(null);
        int accepted = 0;
        while ((Boolean) shouldClean.invoke(null, buffer)) {
            accepted++;
        }
        check(accepted > 0 && accepted <= scanLimit,
                "Uber cleanup did not obey its frame-wide scan ceiling");
        finish.invoke(null, buffer, 5, 9, accepted, 2);
        check(cursors.get(buffer) == 5,
                "Uber cleanup did not retain its resumable cursor");
        check((Integer) begin.invoke(null, buffer, 9) == 5,
                "Uber cleanup resumed from a different heap node");

        beginFrame.invoke(null);
        int nextFrameAccepted = 0;
        while ((Boolean) shouldClean.invoke(null, buffer)) {
            nextFrameAccepted++;
        }
        check(nextFrameAccepted > 0 && nextFrameAccepted <= scanLimit,
                "Uber cleanup did not reset its budget on the next render frame");
        finish.invoke(null, buffer, 0, 0, nextFrameAccepted, 0);
        check(!cursors.containsKey(buffer),
                "Uber cleanup cursor leaked after the final heap was released");

        finish.invoke(null, buffer, 3, 7, 1, 0);
        BrowserRenderScheduler.releaseUploadBuffer(buffer);
        check(!cursors.containsKey(buffer),
                "Uber cleanup cursor survived UberGpuBuffer release");
        System.out.println("UberGpuBuffer cleanup regression passed");
    }
}
`],
  ]);

  const sourcePaths = [];
  for (const [relativePath, contents] of sources) {
    const sourcePath = join(sourceDirectory, relativePath);
    await mkdir(join(sourcePath, ".."), {recursive: true});
    await writeFile(sourcePath, contents);
    sourcePaths.push(sourcePath);
  }
  // The scheduler publishes telemetry through Float64Array views (JsArrays) and starts its pump
  // through HostLoop; both compile unchanged from the JavaScript build's classlib overlay
  // against TeaVM's JSO jars and the Platform stub above.
  const teavmRoot = join(homedir(), ".m2/repository/org/teavm");
  const teavmClasspath = ["teavm-interop", "teavm-jso", "teavm-jso-apis"]
    .map((name) => join(teavmRoot, name, "0.15.0", `${name}-0.15.0.jar`));
  for (const jar of teavmClasspath) {
    assert.ok(existsSync(jar), `missing TeaVM JSO jar for the render scheduler harness: ${jar}`);
  }
  const classlibOverlay = join(repositoryRoot, "port/overrides/classlib/src/main/java/dev/gaius/browser");
  const runtimeClasspath = [classesDirectory, ...teavmClasspath].join(delimiter);
  execFileSync(javaTool("javac"), [
    "--release", "17", "-proc:none", "-classpath", teavmClasspath.join(delimiter),
    "-d", classesDirectory, schedulerPath,
    join(classlibOverlay, "HostLoop.java"), join(classlibOverlay, "JsArrays.java"),
    ...sourcePaths,
  ], {
    cwd: scriptsDirectory,
    stdio: "inherit",
  });
  execFileSync(javaTool("java"), ["-cp", runtimeClasspath,
    "dev.gaius.browser.BrowserRenderSchedulerRetryRegression"], {
    cwd: scriptsDirectory,
    stdio: "inherit",
  });
  execFileSync(javaTool("java"), ["-cp", runtimeClasspath,
    "dev.gaius.browser.BrowserRenderSchedulerUberCleanupRegression"], {
    cwd: scriptsDirectory,
    stdio: "inherit",
  });
} finally {
  await rm(retryRegressionRoot, {recursive: true, force: true});
}

console.log("Render pipeline backpressure smoke passed");
