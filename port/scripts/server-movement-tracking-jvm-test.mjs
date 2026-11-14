#!/usr/bin/env node
// Caller owns scratch and supplies cached input roots and profile JSONs. No game build is run.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {appendFileSync, closeSync, existsSync, mkdirSync, openSync, readFileSync, writeFileSync} from "node:fs";
import {createHash} from "node:crypto";
import {homedir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../../", import.meta.url));
const [output, inputRoot, originalRoot, ...profileFiles] = process.argv.slice(2);
assert.ok(output && inputRoot && originalRoot && profileFiles.length,
  "Pass scratch, cached inputs, original-source snapshot root (or -), and profile JSON paths");
assert.ok(process.env.JAVA_HOME, "Set JAVA_HOME explicitly to a JDK that reads the supplied inputs");
const scratch = resolve(output);
const tmp = join(scratch, "tmp");
const logs = join(scratch, "logs");
for (const directory of [scratch, tmp, logs]) mkdirSync(directory, {recursive: true});
const fixtures = join(root, "port/mc/tests/patch-composition");
const tools = join(root, "port/tools/src/main/java");
const browserSource = "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java";
const patchSource = "dev/gaius/tools/patches/serverworker/ServerWorkerPatches.java";
const maven = process.env.GAIUS_MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
const asm = ["asm", "asm-tree", "asm-util"].map(name =>
  join(maven, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
const commandLog = join(scratch, "commands.jsonl");
writeFileSync(commandLog, "");
let sequence = 0;
function run(label, tool, args, shouldPass = true) {
  const executable = join(process.env.JAVA_HOME, "bin", tool);
  const log = join(logs, `${String(++sequence).padStart(3, "0")}-${label}.log`);
  const fd = openSync(log, "w");
  let result;
  try {
    result = spawnSync(executable, args, {cwd: root, timeout: 120000, stdio: ["ignore", fd, fd]});
  } finally { closeSync(fd); }
  appendFileSync(commandLog, JSON.stringify({label, executable, args, cwd: root,
    status: result.status, log}) + "\n");
  if (result.error) throw result.error;
  if (shouldPass) assert.equal(result.status, 0, `${label}: ${readFileSync(log, "utf8")}`);
  else assert.notEqual(result.status, 0, `${label}: original defect was not detected`);
  console.log(`${shouldPass ? "PASS" : "REJECTED ORIGINAL"}: ${label}`);
  return readFileSync(log, "utf8");
}
function compile(label, classes, sources, sourcepath = null, classpath = []) {
  mkdirSync(classes, {recursive: true});
  const args = ["-J-Xmx1g", `-J-Djava.io.tmpdir=${tmp}`, "--release", "21", "-proc:none",
    "-d", classes];
  if (sourcepath) args.push("-sourcepath", sourcepath);
  if (classpath.length) args.push("-cp", classpath.join(delimiter));
  run(label, "javac", [...args, ...sources]);
}
function javaArgs(classpath, main, ...args) {
  return ["-Xmx1g", "-Xverify:all", `-Djava.io.tmpdir=${tmp}`, "-cp", classpath.join(delimiter), main, ...args];
}
function method(source, name) {
  const declaration = new RegExp(`^    (?:public|private) static (?:void|int) ${name}\\(`, "m").exec(source);
  assert.ok(declaration, `production method missing: ${name}`);
  const open = source.indexOf("{", declaration.index);
  let depth = 1, index = open + 1;
  for (; depth && index < source.length; index++) {
    if (source[index] === "{") depth++;
    else if (source[index] === "}") depth--;
  }
  assert.equal(depth, 0, `unbalanced production method: ${name}`);
  return source.slice(declaration.index, index);
}
function trackingSource(source) {
  const start = source.indexOf(source.includes("private static ServerPlayer lastTrackedPlayer")
    ? "    /** Last section tracked" : "    /** Latest tracking request");
  assert.ok(start >= 0, "tracking declarations missing");
  const fields = source.slice(start, source.indexOf("    private static boolean storageFlushRequested;", start));
  const register = method(source, "registerServer");
  const resetStart = register.indexOf(source.includes("private static ServerPlayer lastTrackedPlayer")
    ? "        lastTrackedPlayer = null;" : "        pendingTrackedPlayers.clear();");
  const reset = register.slice(resetStart, register.indexOf("        recordNetworkPumpState(-1, false);", resetStart));
  const methods = ["moveServerPlayerChunkTracking", "flushPendingPlayerChunkTracking", "markIntegratedServerStopped"]
    .map(name => method(source, name));
  if (source.includes("private static int floorSectionCoordinate(")) methods.push(method(source, "floorSectionCoordinate"));
  // Only unrelated lifecycle dependencies and JS diagnostics are replaced by inert JVM boundaries.
  return `package dev.gaius.browser;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
public final class BrowserIntegratedServerMain {
${fields}
    private static MinecraftServer server;
    private static boolean serverThreadExited;
    private static Thread serverThread;
    private static Object appliedDistancePlayerList;
    private static int appliedViewDistance, appliedSimulationDistance;
    private static final AtomicBoolean NETWORK_INPUT_TASK_SCHEDULED = new AtomicBoolean();
    private static Runnable scheduledNetworkInputTask, activeNetworkInputTask;
    private static boolean storageFlushRequested;
    public static boolean workerForTest = true;
    public static void installForTest(MinecraftServer value) {
        server = value; serverThreadExited = false;
${reset}
    }
    public static void flushForTest() { flushPendingPlayerChunkTracking(); }
    private static boolean isWorkerRuntime() { return workerForTest; }
    private static void recordTrackingMoveTelemetry(int event, boolean pending) {}
    private static void finishNetworkInputBurst() {}
    private static void recordNetworkPumpState(int event, boolean pending) {}
    private static void recordNetworkInputPending(boolean pending) {}
    private static void beginIntegratedServerStorageFlush() {}
    private static String workerWorldId() { return "fixture"; }
    private static void report(String event, String detail) {}
${methods.join("\n")}
}
`;
}
const boundaries = {
  "dev/gaius/browser/BrowserChunkTaskPriority.java": `package dev.gaius.browser;
public class BrowserChunkTaskPriority {
    public static void recordPlayerView(double x, double z, float yaw) {}
}`,
  "net/minecraft/server/MinecraftServer.java": `package net.minecraft.server;
public class MinecraftServer {
    private final net.minecraft.server.players.PlayerList players = new net.minecraft.server.players.PlayerList();
    public net.minecraft.server.players.PlayerList getPlayerList() { return players; }
}`,
  "net/minecraft/server/players/PlayerList.java": `package net.minecraft.server.players;
public class PlayerList {
    private final java.util.List<net.minecraft.server.level.ServerPlayer> players = new java.util.ArrayList<>();
    public java.util.List<net.minecraft.server.level.ServerPlayer> getPlayers() { return players; }
}`,
  "net/minecraft/server/level/ServerLevel.java": `package net.minecraft.server.level;
public class ServerLevel {
    public ServerChunkCache cache;
    public ServerLevel(ServerChunkCache value) { cache = value; }
    public ServerChunkCache getChunkSource() { return cache; }
}`,
  "net/minecraft/server/level/ServerPlayer.java": `package net.minecraft.server.level;
public class ServerPlayer {
    public double x, processedX = Double.NaN;
    private final ServerLevel level;
    public ServerPlayer(ServerChunkCache cache) { level = new ServerLevel(cache); }
    public double getX() { return x; }
    public double getZ() { return 0; }
    public float getYRot() { return 0; }
    public ServerLevel level() { return level; }
}`,
  "net/minecraft/server/level/ServerChunkCache.java": `package net.minecraft.server.level;
public class ServerChunkCache {
    public int calls, depth, maxDepth;
    public Runnable yield;
    public boolean captureBeforeYield;
    public void move(ServerPlayer player) {
        calls++; depth++; maxDepth = Math.max(maxDepth, depth);
        double captured = player.getX();
        Runnable callback = yield; yield = null;
        try {
            if (callback != null) callback.run();
            player.processedX = captureBeforeYield ? captured : player.getX();
        } finally { depth--; }
    }
}`,
};
function compileTracking(label, extracted) {
  const sources = [];
  for (const [name, source] of Object.entries({
    ...boundaries, "dev/gaius/browser/BrowserIntegratedServerMain.java": extracted,
  })) {
    const file = join(scratch, label, "src", name);
    mkdirSync(dirname(file), {recursive: true}); writeFileSync(file, source); sources.push(file);
  }
  const classes = join(scratch, label, "classes");
  compile(`${label}-compile`, classes, [...sources, join(fixtures, "ServerTrackingTest.java")]);
  return classes;
}

const patchClasses = join(scratch, "patch-classes");
compile("production-patcher-compile", patchClasses,
  [join(fixtures, "ServerMovementPatchTest.java")], tools, asm);
let originalClasses;
if (originalRoot !== "-") {
  const archivedTools = join(resolve(originalRoot), "port/tools/src/main/java");
  originalClasses = join(scratch, "original-patch-classes");
  compile("original-patcher-compile", originalClasses,
    [join(fixtures, "ServerMovementPatchTest.java"), join(archivedTools, patchSource)],
    [archivedTools, tools].join(delimiter), asm);
}
const scenarios = ["cycle", "latest", "yield-before", "yield-after", "yield-return", "multiplayer",
  "yield-multiplayer", "disconnect", "yield-disconnect", "connection-churn", "reset",
  "dimension", "stop", "direct", "exception"];
const adapted = new Map();
const summary = {profiles: [], tracking: [], originalControls: []};
for (const profileFile of profileFiles) {
  const profilePath = resolve(profileFile);
  const profile = JSON.parse(readFileSync(profilePath, "utf8"));
  const jar = join(resolve(inputRoot), profile.id, "client-named.jar");
  assert.ok(existsSync(jar), `missing read-only input: ${jar}`);
  run(`patch-${profile.id}`, "java", javaArgs([patchClasses, ...asm], "ServerMovementPatchTest",
    jar, join(scratch, "patched", profile.id)));
  if (originalClasses) {
    const log = run(`original-patch-${profile.id}`, "java", javaArgs([originalClasses, ...asm],
      "ServerMovementPatchTest", jar, join(scratch, "original-patched", profile.id)), false);
    assert.match(log, /movement body bypass or unexpected hook/);
    summary.originalControls.push({profile: profile.id, patchRejected: true});
  }
  for (const target of ["js", "wasmgc"]) {
    const resolved = sourceFile(profilePath, "src", "dev/gaius/browser/BrowserIntegratedServerMain.java", {target});
    assert.ok(resolved, "production direct source must resolve");
    assert.ok(!resolved.text.includes("applyWorkerMovement"), "unsafe movement helper must be removed from adapted source");
    const extracted = trackingSource(resolved.text);
    const hash = createHash("sha256").update(extracted).digest("hex");
    if (!adapted.has(hash)) {
      const label = `tracking-${adapted.size + 1}`;
      const classes = compileTracking(label, extracted);
      for (const scenario of scenarios) run(`${label}-${scenario}`, "java",
        javaArgs([classes], "ServerTrackingTest", scenario));
      adapted.set(hash, label);
      summary.tracking.push({label, hash, scenarios});
    }
    summary.profiles.push({profile: profile.id, target, source: resolved.path,
      relocated: resolved.relocated, trackingFixture: adapted.get(hash), hash});
  }
}
if (originalRoot !== "-") {
  const original = readFileSync(join(resolve(originalRoot), browserSource), "utf8");
  const classes = compileTracking("original-tracking", trackingSource(original));
  for (const scenario of ["cycle", "latest", "yield-after", "multiplayer", "yield-multiplayer", "disconnect", "stop"]) {
    const log = run(`original-tracking-${scenario}`, "java", javaArgs([classes], "ServerTrackingTest", scenario), false);
    assert.match(log, /AssertionError/);
    summary.originalControls.push({scenario, trackingRejected: true});
  }
}
writeFileSync(join(scratch, "summary.json"), JSON.stringify(summary, null, 2) + "\n");
console.log("PASS: original-input patch proof and production tracking regressions; JVM boundaries only, no world/browser execution");
