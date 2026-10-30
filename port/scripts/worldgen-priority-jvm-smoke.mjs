#!/usr/bin/env node
import { VERSION_PROFILES, escapeRegExp } from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const option = (name) => {
  const index = args.indexOf(name);
  return index < 0 ? null : args[index + 1] || null;
};
const patchedJarOption = option("--jar");
const rawJarOption = option("--raw-jar");
if (!patchedJarOption === !rawJarOption) {
  console.error("usage: node port/scripts/worldgen-priority-jvm-smoke.mjs "
    + "(--jar PATCHED_CLIENT.jar | --raw-jar port/work/<version>/client-named.jar) "
    + "[--classpath-file classpath.txt] [--profile PROFILE]");
  process.exit(2);
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDir, "../..");
const fixtureDir = join(scriptDir, "fixtures");
const rawJar = rawJarOption ? resolve(rawJarOption) : null;
if (rawJar && !existsSync(rawJar)) throw new Error(`raw client jar not found: ${rawJar}`);
const classpathFile = option("--classpath-file")
  || (rawJar && existsSync(join(dirname(rawJar), "classpath.txt"))
    ? join(dirname(rawJar), "classpath.txt") : null);
const dependencyClasspath = classpathFile ? parseClasspath(readFileSync(resolve(classpathFile), "utf8")) : [];
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const suffix = process.platform === "win32" ? ".exe" : "";
const tool = (name) => javaHome ? join(javaHome, "bin", `${name}${suffix}`) : `${name}${suffix}`;
const [java, javac, javap, jarTool] = ["java", "javac", "javap", "jar"].map(tool);
const root = mkdtempSync(join(tmpdir(), "gaius-worldgen-priority-"));
const classes = join(root, "classes");
const sources = join(root, "sources");
mkdirSync(classes, { recursive: true });

function run(command, commandArgs, label, options = {}) {
  const result = spawnSync(command, commandArgs, { encoding: "utf8", timeout: 60_000, ...options });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, `${label} failed\n${result.stdout || ""}${result.stderr || ""}`);
  return result.stdout || "";
}

function occurrences(text, pattern) {
  return (text.match(pattern) || []).length;
}

// javap -c separates members with a blank line; patcher-added methods follow <clinit>.
function methodBytecode(bytecode, signature) {
  const start = bytecode.indexOf(signature);
  assert.ok(start >= 0, `bytecode method missing: ${signature}`);
  const blankLine = /\r?\n\r?\n/g;
  blankLine.lastIndex = start;
  const end = blankLine.exec(bytecode);
  return bytecode.slice(start, end ? end.index : bytecode.length);
}

// Swap the scheduler's two JSBody telemetry sinks and its clock for fixture hooks. The pump,
// turn classification and budget logic compile unchanged from the production source.
function schedulerSourceForJvm() {
  let source = readFileSync(join(repositoryRoot,
    "port/src/main/java/dev/gaius/browser/BrowserWorldgenDispatcherScheduler.java"), "utf8");
  const replaceOnce = (pattern, replacement, label) => {
    assert.equal(occurrences(source, new RegExp(pattern.source, "g")), 1,
      `expected one ${label} in BrowserWorldgenDispatcherScheduler`);
    source = source.replace(pattern, replacement);
  };
  replaceOnce(/private static native void recordTurnTelemetry\(int runnables, int reason, double elapsedMillis\);/,
    "private static void recordTurnTelemetry(int runnables, int reason, double elapsedMillis) {"
      + " WorldgenDispatcherFixtureRuntime.turn(runnables, reason, elapsedMillis); }",
    "turn telemetry sink");
  replaceOnce(/private static native void recordHopTelemetry\(int kind\);/,
    "private static void recordHopTelemetry(int kind) { WorldgenDispatcherFixtureRuntime.hop(kind); }",
    "hop telemetry sink");
  replaceOnce(/return System\.nanoTime\(\);/,
    "return WorldgenDispatcherFixtureRuntime.nanoTime;", "scheduler clock");
  return source;
}

try {
  let jar = patchedJarOption ? resolve(patchedJarOption) : null;
  if (rawJar) {
    const profileId = option("--profile") || basename(dirname(rawJar));
    const profile = VERSION_PROFILES.find((candidate) => candidate.id === profileId);
    assert.ok(profile, "raw jar requires a known profile directory or --profile PROFILE");
    // Patch a private copy with the production patcher; never touch the fetched input.
    const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
    const asmClasspath = [join(asmRoot, "asm/9.8/asm-9.8.jar"),
      join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar")].join(delimiter);
    const toolClasses = join(root, "tool-classes");
    const patchOutput = join(root, "patches");
    mkdirSync(toolClasses, { recursive: true });
    mkdirSync(patchOutput, { recursive: true });
    jar = join(root, "client-patched.jar");
    copyFileSync(rawJar, jar);
    // The patcher registers the light and worldgen kernel patches (the light and worldgen domain
    // packages); -sourcepath picks them up with anything else they reference.
    const toolSources = join(repositoryRoot, "port/tools/src/main/java");
    run(javac, ["--release", "21", "-proc:none", "-classpath", asmClasspath, "-sourcepath", toolSources,
      "-d", toolClasses, join(toolSources, "dev/gaius/tools/MinecraftServerWorkerPatcher.java")],
      "patcher compile");
    const patchLog = run(java, [`-Dgaius.profile=${profile.id}`, "-classpath", [toolClasses, asmClasspath].join(delimiter),
      "dev.gaius.tools.MinecraftServerWorkerPatcher", jar, patchOutput], "MinecraftServerWorkerPatcher");
    assert.match(patchLog, /budgeted bookkeeping turns/,
      "patcher fell back to single-task turns on a StrictQueue-shaped client");
    run(jarTool, ["--update", "--file", jar, "-C", patchOutput, "."], "patched jar update");
  }
  if (!existsSync(jar)) throw new Error(`patched jar not found: ${jar}`);

  const bytecode = run(javap, ["-classpath", jar, "-c", "-p",
    "net.minecraft.util.thread.AbstractConsecutiveExecutor"], "javap AbstractConsecutiveExecutor");
  const runBytecode = methodBytecode(bytecode, "public void run();");
  assert.equal(occurrences(runBytecode, /gaius\$registerForExecutionDeferred/g), 2,
    "worldgen success/catch must use exactly two deferred registrations");
  assert.equal(occurrences(runBytecode, /Method registerForExecution:\(\)V/g), 1,
    "vanilla success and failure paths must share the one trampolined registration");
  assert.equal(occurrences(runBytecode, /Method pollTask:\(\)Z/g), 2,
    "worldgen turn loop and vanilla path must each poll exactly once per iteration");
  assert.equal(occurrences(runBytecode, /Method gaius\$headPriority:\(\)I/g), 2,
    "worldgen turn must classify the runnable it runs and the next queue head");
  for (const [method, descriptor] of [["beginTurn", "()J"], ["continueTurn", "(JIII)I"],
    ["endTurn", "(JII)V"]]) {
    const escaped = escapeRegExp(descriptor);
    assert.equal(occurrences(runBytecode, new RegExp(
      `BrowserWorldgenDispatcherScheduler\\.${method}:${escaped}`, "g")), 1,
      `worldgen turn must call BrowserWorldgenDispatcherScheduler.${method}${descriptor} once`);
  }
  assert.match(runBytecode, /Method gaius\$headPriority:\(\)I\s+\d+: istore_2\s+\d+: aload_0\s+\d+: invokevirtual #\d+\s+\/\/ Method pollTask/,
    "the pre-poll head priority must be captured immediately before pollTask");
  const headPriority = methodBytecode(bytecode, "private int gaius$headPriority();");
  assert.match(headPriority, /instanceof\s+#\d+\s+\/\/ class net\/minecraft\/util\/thread\/StrictQueue\$FixedPriorityQueue/,
    "executor head priority must only trust FixedPriorityQueue");
  const queueBytecode = run(javap, ["-classpath", jar, "-c", "-p",
    "net.minecraft.util.thread.StrictQueue$FixedPriorityQueue"], "javap FixedPriorityQueue");
  const queuePeek = methodBytecode(queueBytecode, "public int gaius$headPriority();");
  assert.match(queuePeek, /InterfaceMethod java\/util\/Queue\.isEmpty:\(\)Z/,
    "queue head priority must peek without polling");
  assert.doesNotMatch(queuePeek, /Queue\.poll|decrementAndGet|incrementAndGet/,
    "queue head priority must not mutate the StrictQueue");

  mkdirSync(join(sources, "org/teavm/jso"), { recursive: true });
  mkdirSync(join(sources, "org/teavm/interop"), { recursive: true });
  mkdirSync(join(sources, "dev/gaius/browser"), { recursive: true });
  writeFileSync(join(sources, "org/teavm/jso/JSBody.java"), `package org.teavm.jso;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface JSBody { String[] params() default {}; String script(); }
`);
  // The scheduler starts its pump through the JavaScript HostLoop of the classlib overlay,
  // which compiles unchanged against these annotation stubs and the fixture Platform.
  writeFileSync(join(sources, "org/teavm/jso/JSFunctor.java"), `package org.teavm.jso;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
public @interface JSFunctor { }
`);
  writeFileSync(join(sources, "org/teavm/jso/JSObject.java"), `package org.teavm.jso;
public interface JSObject { }
`);
  writeFileSync(join(sources, "org/teavm/interop/PlatformMarker.java"), `package org.teavm.interop;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface PlatformMarker { String[] value() default {}; }
`);
  const hostLoopSource = join(repositoryRoot,
    "port/overrides/classlib/src/main/java/dev/gaius/browser/HostLoop.java");
  const schedulerSource = join(sources, "dev/gaius/browser/BrowserWorldgenDispatcherScheduler.java");
  writeFileSync(schedulerSource, schedulerSourceForJvm());
  const compileCp = [jar, ...dependencyClasspath].join(delimiter);
  run(javac, ["--release", "21", "-proc:none", "-classpath", compileCp, "-d", classes,
    join(sources, "org/teavm/jso/JSBody.java"),
    join(sources, "org/teavm/jso/JSFunctor.java"),
    join(sources, "org/teavm/jso/JSObject.java"),
    join(sources, "org/teavm/interop/PlatformMarker.java"),
    hostLoopSource,
    join(fixtureDir, "PlatformRunnable.java"),
    join(fixtureDir, "Platform.java"),
    join(fixtureDir, "TModernRuntimeSupport.java"),
    join(fixtureDir, "WorldgenDispatcherFixtureRuntime.java"),
    schedulerSource,
    join(fixtureDir, "WorldgenPriorityJvmFixture.java")], "fixture compile");
  const runCp = [classes, jar, ...dependencyClasspath].join(delimiter);
  const output = run(java, ["-cp", runCp, "WorldgenPriorityJvmFixture"], "WorldgenPriorityJvmFixture");
  assert.match(output, /WORLDGEN_PRIORITY_JVM_OK/, "fixture did not report success");
  process.stdout.write(output);
} finally {
  rmSync(root, { recursive: true, force: true });
}

function parseClasspath(text) {
  const normalized = text.trim();
  if (!normalized) return [];
  if (process.platform === "win32") {
    // fetch-version.sh emits ':'-separated /c/... entries; accept native paths too.
    const entries = normalized.includes(";")
      ? normalized.split(";")
      : normalized.split(/:(?=\/(?:[a-zA-Z])\/|[A-Za-z]:[\\/])/);
    return entries
      .map((entry) => entry.replace(/^\/([a-zA-Z])\//, "$1:/"))
      .filter(Boolean);
  }
  return normalized.split(":").filter(Boolean);
}
