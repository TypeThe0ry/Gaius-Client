#!/usr/bin/env node

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {copyFile, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {patcherSource as stepSource} from "./patcher-sources.mjs";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => path.basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const overlayProfileId = process.env.GAIUS_OVERLAY_DIRECTORY
  ? profileIdFromPath(process.env.GAIUS_OVERLAY_DIRECTORY) : "";
const version = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH)
    : (/^\d+(?:\.\d+)+$/.test(overlayProfileId) ? overlayProfileId : CANONICAL_PROFILE.id));
// Per-profile class shapes.  26.2 runs MinecraftClientPatcher on the vanilla client jar;
// 26.3 (renderpearl GL backend) checks the patched client jar that build-overlays.sh wrote
// (a GAIUS_BRINGUP=1 build is enough), because the full 26.3 patch chain only runs there.
const PROFILE_SHAPES = {
  [CANONICAL_PROFILE.id]: {
    gl: "com.mojang.blaze3d.opengl",
    transientMemory: "com.mojang.blaze3d.systems.TransientMemory",
    lwjglVersion: "3.4.1",
    lwjglCoreJar: "lwjgl-3.4.1-unsafe.jar",
    patchVanillaJar: true,
  },
  [RENDERPEARL_PROFILE.id]: {
    gl: "com.mojang.renderpearl.backend.opengl",
    transientMemory: "com.mojang.renderpearl.api.buffers.TransientMemory",
    lwjglVersion: "3.4.3",
    lwjglCoreJar: "lwjgl-3.4.3.jar",
    patchVanillaJar: false,
  },
};
const shape = PROFILE_SHAPES[version];
if (!shape) {
  throw new Error(`GPU fence retire smoke supports profiles ${Object.keys(PROFILE_SHAPES)
    .join(", ")}; got ${version}`);
}
const glPackage = shape.gl;
const workRoot = path.join(repositoryRoot, "port/work", version);
const overlayRoot = nativePath(process.env.GAIUS_OVERLAY_DIRECTORY || path.join(
  repositoryRoot, "port/work/overlays",
  process.env.GAIUS_BUILD_ROOT || process.env.GAIUS_VERSION_PROFILE_PATH ? version : "",
));
const baseClient = path.join(workRoot, "client-named.jar");
const overlayClient = path.join(
  overlayRoot,
  `client-named-${version}-gaius.jar`,
);
const patcherSource = path.join(
  repositoryRoot,
  "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
);
const browserOpenGlSource = path.join(
  repositoryRoot,
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
);
// BrowserOpenGL hands terrain shaders to BrowserTerrainShaders, so both compile together.
// BrowserOpenGL delegates its redundant-state caches to BrowserOpenGLPerf.
const browserOpenGlPerfSource = path.join(
  repositoryRoot,
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGLPerf.java",
);
const browserTerrainShadersSource = path.join(
  repositoryRoot,
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserTerrainShaders.java",
);
// BrowserOpenGL passes Java arrays to its JSBody calls through dev.gaius.browser.JsArrays of the
// classlib overlay; it compiles with the JavaScript implementation (typed-array views).
const jsArraysSource = path.join(
  repositoryRoot,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java",
);
const lwjglOpenGl = path.join(
  overlayRoot,
  `libraries/org/lwjgl/lwjgl-opengl/${shape.lwjglVersion}/lwjgl-opengl-${shape.lwjglVersion}.jar`,
);
const lwjglCore = path.join(
  overlayRoot,
  `libraries/org/lwjgl/lwjgl/${shape.lwjglVersion}/${shape.lwjglCoreJar}`,
);
const browserOpenGlIndexedSource = path.join(
  repositoryRoot,
  "port/overrides/libraries/lwjgl-opengl/src/versions/3.4.3/java/org/lwjgl/opengl/"
    + "BrowserOpenGLIndexed.java",
);

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
    timeout: 90_000,
    ...options,
  });
}

function selectJavaTools() {
  const homes = [
    process.env.GAIUS_JAVA_HOME && nativePath(process.env.GAIUS_JAVA_HOME),
    process.env.JAVA_HOME && nativePath(process.env.JAVA_HOME),
    "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home",
    "/usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home",
  ].filter(Boolean);
  for (const home of homes) {
    const javac = path.join(home, "bin/javac");
    try {
      const output = execFileSync(javac, ["-version"], {encoding: "utf8"});
      if (Number(output.match(/javac (\d+)/)?.[1]) >= 25) {
        return {
          java: path.join(home, "bin/java"),
          javac,
          javap: path.join(home, "bin/javap"),
          jar: path.join(home, "bin/jar"),
        };
      }
    } catch {
      // Try the next configured JDK.
    }
  }
  throw new Error(`Minecraft ${version} GPU retire smoke requires JDK 25 or newer`);
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature
    ? bytecode.indexOf(nextSignature, start + signature.length)
    : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = stepSource("MinecraftClientPatcher");
const browserOpenGl = await readFile(browserOpenGlSource, "utf8");

function jsBodyBefore(declaration) {
  const declarationOffset = browserOpenGl.indexOf(declaration);
  assert.notEqual(declarationOffset, -1, `missing JSBody declaration: ${declaration}`);
  const marker = 'script = """';
  const bodyStart = browserOpenGl.lastIndexOf(marker, declarationOffset);
  assert.notEqual(bodyStart, -1, `missing JSBody script for: ${declaration}`);
  const scriptStart = bodyStart + marker.length;
  const scriptEnd = browserOpenGl.indexOf('""")', scriptStart);
  assert.ok(scriptEnd >= scriptStart && scriptEnd < declarationOffset,
    `unterminated JSBody script for: ${declaration}`);
  return browserOpenGl.slice(scriptStart, scriptEnd);
}

// TeaVM 0.15 rejects repeated top-level lexical binding names in one @JSBody
// even when ECMAScript block scoping would otherwise make the source valid.
// Keep this method-specific gate so the expensive release build cannot finish
// with an invalid emitted client after a nested failure path adds a duplicate.
const clientWaitBody = jsBodyBefore(
  "private static native int clientWaitSyncJs(int sync, int flags, int timeout);");
const clientWaitLexicalNames = [...clientWaitBody.matchAll(
  /\b(?:const|let)\s+([A-Za-z_$][\w$]*)/g,
)].map((match) => match[1]);
const clientWaitDuplicateLexicals = [...new Set(clientWaitLexicalNames.filter(
  (name, index) => clientWaitLexicalNames.indexOf(name) !== index,
))];
assert.deepEqual(clientWaitDuplicateLexicals, [],
  "clientWaitSyncJs repeats lexical names rejected by TeaVM's @JSBody parser");

for (const contract of [
  "BROWSER_GPU_RETIRE_SLOTS = 8",
  "gaius$pollRetireSlot",
  "gaius$retireBacklog",
  "beginGpuRetireFrame",
  "endGpuRetireFrame",
  "markNextFenceRetireOwned",
  "patchCurrentGlTransientMemoryRotations",
  "patchCurrentGlTransientMemoryFallback",
  "gaius$retireRotations",
]) {
  assert.ok(patcher.includes(contract), `missing patcher contract: ${contract}`);
}
assert.ok(!patcher.includes("Failed to wait for frame completion"),
  "the browser patch still emits the two-frame timeout crash");

for (const contract of [
  "gpuFenceTimeouts",
  "gpuFenceNativeWaitCalls",
  "gpuFenceNativeTimeouts",
  "gpuFenceCoalescedWaits",
  "gpuFenceCoalescedTimeouts",
  "gpuFenceFlushOnlyCalls",
  "gpuFenceExplicitFlushes",
  "gpuFenceSuppressedRepeatFlushes",
  "gpuFenceTimeoutBurst",
  "globalThis.queueMicrotask",
  "gpuRetireBacklogMax",
  "gpuWaitFailures",
  "gpuContextLosses",
  "gpuFenceMaxAgeFrames",
  "gpuRetireRecent",
  "gpuFenceDuplicateDeletes",
  "gpuEarlyResourceReuse",
  "webglcontextlost",
  "webglcontextrestored",
  "gpuContextRecovery='reload-scheduled'",
]) {
  assert.ok(browserOpenGl.includes(contract), `missing WebGL telemetry contract: ${contract}`);
}
assert.ok(browserOpenGl.includes("clientWaitSync(object,safeFlags,0)"),
  "WebGL fence wait is not a nonblocking zero-timeout poll");
assert.ok(clientWaitBody.includes("safeFlags===0 || retireOwned"),
  "flagged timeout coalescing is not scoped to internal retire-owned fences");
assert.ok(!clientWaitBody.includes("gl.flush()"),
  "retire timeout cache still emits per-hit explicit WebGL flushes");
assert.ok(browserOpenGl.includes("return 0x911D"),
  "missing sync/context loss does not return WAIT_FAILED");
assert.ok(!browserOpenGl.includes(
  "return object?window.__gaiusWebGL.clientWaitSync(object,flags,0):0x911A"),
"missing sync still masquerades as ALREADY_SIGNALED");
assert.ok(browserOpenGl.includes("this.gpuRetireRecent.length>120"),
  "GPU retire telemetry is not bounded to 120 frames");

const javaTools = selectJavaTools();

async function copyUnsignedClientJar(source, destination) {
  const jar = path.join(
    path.dirname(javaTools.javac),
    process.platform === "win32" ? "jar.exe" : "jar",
  );
  assert.ok(existsSync(jar), `missing JDK jar tool for signer-isolated verifier: ${jar}`);
  const unpacked = await mkdtemp(path.join(path.dirname(destination), "unsigned-client-"));
  try {
    run(jar, ["--extract", "--file", source], {cwd: unpacked});
    const metaInf = path.join(unpacked, "META-INF");
    for (const entry of ["MOJANGCS.SF", "MOJANGCS.RSA", "MOJANGCS.DSA", "MOJANGCS.EC"]) {
      await rm(path.join(metaInf, entry), {force: true});
    }
    run(jar, ["--create", "--file", destination, "-C", unpacked, "."]);
    const remainingSignatures = run(jar, ["--list", "--file", destination])
      .split(/\r?\n/)
      .filter((entry) => /^META-INF\/[^/]+\.(?:SF|RSA|DSA|EC)$/i.test(entry));
    assert.deepEqual(remainingSignatures, [],
      "temporary verifier client jar retained package-signing metadata");
  } finally {
    await rm(unpacked, {recursive: true, force: true});
  }
  return destination;
}

const temporaryRoot = await mkdtemp(path.join(tmpdir(), "gaius-gpu-retire-smoke-"));
try {
  const patcherClasses = path.join(temporaryRoot, "patcher-classes");
  const patchedClasses = path.join(temporaryRoot, "patched-classes");
  const browserClasses = path.join(temporaryRoot, "browser-classes");
  const harnessClasses = path.join(temporaryRoot, "harness-classes");
  const harnessSourceDirectory = path.join(temporaryRoot, "src/dev/gaius/smoke");
  const unsignedBaseClient = path.join(temporaryRoot, "client-named-unsigned.jar");
  await Promise.all([
    mkdir(patcherClasses, {recursive: true}),
    mkdir(patchedClasses, {recursive: true}),
    mkdir(browserClasses, {recursive: true}),
    mkdir(harnessClasses, {recursive: true}),
    mkdir(harnessSourceDirectory, {recursive: true}),
  ]);
  if (shape.patchVanillaJar) {
    await copyUnsignedClientJar(baseClient, unsignedBaseClient);
  } else {
    assert.ok(existsSync(overlayClient),
      `missing patched ${version} client jar ${overlayClient} (run build-overlays.sh)`);
  }

  const asm = path.join(
    homedir(),
    ".m2/repository/org/ow2/asm/asm/9.8/asm-9.8.jar",
  );
  const asmTree = path.join(
    homedir(),
    ".m2/repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar",
  );
  const asmClasspath = [asm, asmTree].join(path.delimiter);
  if (shape.patchVanillaJar) {
    run(javaTools.javac, [
      "-proc:none",
      "-classpath", asmClasspath,
      "-sourcepath", path.join(repositoryRoot, "port/tools/src/main/java"),
      "-d", patcherClasses,
      patcherSource,
    ], {stdio: ["ignore", "pipe", "pipe"]});
    // The REQUIRED class-initialization patch rewrites a Gaius source class that
    // the overlay build compiles into the client jar; seed the unsigned copy with
    // it, as patchkit does, and patch that copy.
    const seedClasses = path.join(temporaryRoot, "client-source-classes");
    run(javaTools.javac, ["--release", "21", "-proc:none", "-d", seedClasses,
      path.join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserClassInitialization.java"),
    ], {stdio: ["ignore", "pipe", "pipe"]});
    run(javaTools.jar, ["--update", "--file", unsignedBaseClient, "-C", seedClasses,
      "dev/gaius/browser/BrowserClassInitialization.class"], {stdio: ["ignore", "pipe", "pipe"]});
    run(javaTools.java, [
      "-classpath", [patcherClasses, asmClasspath].join(path.delimiter),
      "dev.gaius.tools.MinecraftClientPatcher",
      unsignedBaseClient,
      patchedClasses,
      version,
    ], {stdio: ["ignore", "pipe", "pipe"]});
  }

  let runtimeClasspath = (await readFile(
    path.join(workRoot, "classpath.txt"),
    "utf8",
  )).trim();
  // classpath.txt is emitted by the Git-Bash build with ':' separators and
  // MSYS /c/... paths.  Windows JDKs require ';' plus drive-letter paths;
  // without this conversion javac silently drops the later jars (notably
  // Brigadier), making the BrowserOpenGL compile fail for a missing Message.
  if (process.platform === "win32") {
    const workMarker = `/port/work/${version}/`;
    runtimeClasspath = runtimeClasspath
      .split(":")
      .filter(Boolean)
      .map((entry) => {
        const nativeEntry = entry.replace(/^\/([a-z])\//i, "$1:/");
        const normalized = nativeEntry.replaceAll("\\", "/");
        const markerOffset = normalized.toLowerCase().indexOf(workMarker.toLowerCase());
        return markerOffset === -1
          ? nativeEntry
          : path.join(repositoryRoot, ...normalized.slice(markerOffset + 1).split("/"));
      })
      .join(path.delimiter);
  }
  const teaVmJars = ["teavm-interop", "teavm-jso", "teavm-jso-apis"].map(
    artifact => path.join(
      homedir(),
      `.m2/repository/org/teavm/${artifact}/0.15.0/${artifact}-0.15.0.jar`,
    ),
  );
  const browserCompileClasspath = [
    overlayClient,
    lwjglOpenGl,
    lwjglCore,
    ...teaVmJars,
    runtimeClasspath,
  ].join(path.delimiter);
  // build-overlays.sh generates GaiusProfileCapabilities from the profile fields for the
  // lwjgl-opengl overlay; BrowserOpenGL reads the patch set and the terrain block from it.
  const profile = JSON.parse(await readFile(path.join(repositoryRoot, "port/versions", `${version}.json`), "utf8"));
  const profileCapabilitiesSource = path.join(temporaryRoot, "src/org/lwjgl/opengl/GaiusProfileCapabilities.java");
  await mkdir(path.dirname(profileCapabilitiesSource), {recursive: true});
  await writeFile(profileCapabilitiesSource, [
    "package org.lwjgl.opengl;",
    "final class GaiusProfileCapabilities {",
    `    static final String PATCH_SET = ${JSON.stringify(profile.patchSet || "")};`,
    `    static final boolean TERRAIN_SECTION_UNIFORM_BLOCK = ${profile.terrainSectionUniformBlock === true};`,
    "}",
    "",
  ].join("\n"));
  run(javaTools.javac, [
    "-proc:none",
    "-classpath", browserCompileClasspath,
    "-d", browserClasses,
    profileCapabilitiesSource,
    browserOpenGlSource,
    browserOpenGlPerfSource,
    browserTerrainShadersSource,
    jsArraysSource,
    ...(shape.lwjglVersion === "3.4.3" ? [browserOpenGlIndexedSource] : []),
  ], {stdio: ["ignore", "pipe", "pipe"]});

  // The patcher emits replacement classes into a directory.  Loading those
  // beside a signed Mojang jar trips the JVM package-signer check before the
  // verifier reaches the patched methods.  Use a job-local unsigned copy so
  // this remains a bytecode/runtime verifier, rather than a signer artifact.
  const patchedClasspath = (shape.patchVanillaJar
    ? [patchedClasses, unsignedBaseClient] : [overlayClient]).join(path.delimiter);
  const encoderBytecode = run(javaTools.javap, [
    "-classpath", patchedClasspath,
    "-p", "-c", "-verbose",
    `${glPackage}.GlCommandEncoder`,
  ]);
  assert.match(encoderBytecode, /ConstantValue: int 8/,
    "MAX_SUBMITS_IN_FLIGHT was not patched to eight");
  assert.match(encoderBytecode, /bipush\s+8\s*\n\s*\d+:\s+newarray\s+long/,
    "GlCommandEncoder fence array was not patched to eight slots");
  const submit = method(encoderBytecode, "public void submit();", "public boolean awaitSubmit");
  assert.ok(submit.includes("gaius$pollRetireSlot"), "submit does not poll the oldest slot");
  assert.ok(submit.includes("BrowserOpenGL.beginGpuRetireFrame"),
    "submit does not begin frame telemetry");
  assert.ok(submit.includes("BrowserOpenGL.endGpuRetireFrame"),
    "submit does not end frame telemetry");
  assert.ok(submit.includes("BrowserOpenGL.markNextFenceRetireOwned"),
    "submit does not mark its retirement fence ownership");
  assert.ok(
    submit.indexOf("BrowserOpenGL.markNextFenceRetireOwned")
      < submit.indexOf("GL33C.glFenceSync"),
    "submit marks retirement ownership after creating its fence",
  );
  assert.ok(!submit.includes("IllegalStateException"), "submit still throws on timeout");
  assert.ok(
    submit.indexOf("GlTransientMemory.rotate") < submit.lastIndexOf("currentSubmitIndex:J"),
    "transient rotation is not attached to the matching fence slot before index advance",
  );
  const awaitSubmit = method(
    encoderBytecode,
    "public boolean awaitSubmit(long, long);",
    `public ${shape.transientMemory} transientMemory();`,
  );
  assert.ok(awaitSubmit.includes("gaius$pollRetireSlot"),
    "awaitSubmit does not use the nonblocking retire poll");
  assert.ok(!awaitSubmit.includes("IllegalStateException"),
    "awaitSubmit still throws for current or timed-out submissions");
  const poll = method(
    encoderBytecode,
    "private boolean gaius$pollRetireSlot(int, long);",
    "private static void gaius$bindDefaultUniforms",
  );
  assert.ok(poll.includes("int 37146") && poll.includes("int 37148"),
    "retire poll does not recognize both successful wait statuses");
  assert.ok(poll.includes("GL33C.glDeleteSync"), "signaled fences are not deleted");

  const transientBytecode = run(javaTools.javap, [
    "-classpath", patchedClasspath,
    "-p", "-c",
    `${glPackage}.GlTransientMemory$PersistentMapping`,
  ]);
  assert.match(transientBytecode, /bipush\s+8\s*\n\s*\d+:\s+anewarray/,
    "persistent transient rotations were not patched to eight slots");
  const fallbackBytecode = run(javaTools.javap, [
    "-classpath", patchedClasspath,
    "-p", "-c",
    `${glPackage}.GlTransientMemory$Fallback`,
  ]);
  assert.ok(fallbackBytecode.includes("gaius$retireRotations"),
    "fallback transient memory has no deferred rotation ring");
  assert.match(fallbackBytecode, /bipush\s+8\s*\n\s*\d+:\s+anewarray\s+#[^\n]+java\/lang\/Runnable/,
    "fallback transient rotations were not patched to eight slots");
  const fallbackRotate = method(
    fallbackBytecode,
    "public void rotate();",
    `private ${glPackage}.GlTransientMemory$Fallback$GlAllocation allocateGlBlock`,
  );
  assert.ok(
    fallbackRotate.indexOf("java/lang/Runnable.run")
      < fallbackRotate.indexOf("TransientBlockAllocator.rotate"),
    "fallback executes the new frame's allocator rotation before its fence retires",
  );

  const harnessSource = `
package dev.gaius.smoke;

public final class GpuRetirePatchedClassVerifier {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = GpuRetirePatchedClassVerifier.class.getClassLoader();
        Class<?> encoder = Class.forName(
                "${glPackage}.GlCommandEncoder", false, loader);
        Class<?> persistent = Class.forName(
                "${glPackage}.GlTransientMemory$PersistentMapping", false, loader);
        Class<?> fallback = Class.forName(
                "${glPackage}.GlTransientMemory$Fallback", false, loader);
        encoder.getDeclaredMethod("submit");
        encoder.getDeclaredMethod("awaitSubmit", long.class, long.class);
        encoder.getDeclaredMethod("gaius$pollRetireSlot", int.class, long.class);
        encoder.getDeclaredMethod("gaius$retireBacklog");
        persistent.getDeclaredMethod("rotate");
        fallback.getDeclaredMethod("rotate");
        fallback.getDeclaredField("gaius$retireRotations");
        System.out.println("verified=" + encoder.getName() + "," + persistent.getName()
                + "," + fallback.getName());
    }
}
`;
  const harnessSourceFile = path.join(
    harnessSourceDirectory,
    "GpuRetirePatchedClassVerifier.java",
  );
  await writeFile(harnessSourceFile, harnessSource, "utf8");
  const verifyClasspath = [
    ...(shape.patchVanillaJar ? [patchedClasses] : []),
    browserClasses,
    shape.patchVanillaJar ? unsignedBaseClient : overlayClient,
    lwjglOpenGl,
    lwjglCore,
    ...teaVmJars,
    runtimeClasspath,
  ].join(path.delimiter);
  run(javaTools.javac, [
    "-proc:none",
    "-classpath", verifyClasspath,
    "-d", harnessClasses,
    harnessSourceFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const verifyOutput = run(javaTools.java, [
    "-Xverify:all",
    "-classpath", [harnessClasses, verifyClasspath].join(path.delimiter),
    "dev.gaius.smoke.GpuRetirePatchedClassVerifier",
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.ok(verifyOutput.includes(`verified=${glPackage}.GlCommandEncoder`),
    `patched ${version} GPU retire classes did not verify`);
} finally {
  await rm(temporaryRoot, {recursive: true, force: true});
}

const ALREADY_SIGNALED = 0x911A;
const TIMEOUT_EXPIRED = 0x911B;
const CONDITION_SATISFIED = 0x911C;
const WAIT_FAILED = 0x911D;

const initializeGpuFenceLifecycleBody = jsBodyBefore(
  "private static native void initializeGpuFenceLifecycleJs(",
);
const timeoutCacheStart = initializeGpuFenceLifecycleBody.indexOf(
  "state.gpuCacheFenceTimeout=function",
);
const timeoutCacheEnd = initializeGpuFenceLifecycleBody.indexOf(
  "state.gpuOldestFenceAge=function",
  timeoutCacheStart,
);
assert.ok(timeoutCacheStart >= 0 && timeoutCacheEnd > timeoutCacheStart,
  "missing bounded GPU timeout cache helper");
const timeoutCacheBody = initializeGpuFenceLifecycleBody.slice(
  timeoutCacheStart,
  timeoutCacheEnd,
);
assert.doesNotMatch(timeoutCacheBody, /setTimeout|MessageChannel|requestAnimationFrame/,
  "GPU timeout cache checkpoint can cross the current microtask boundary");
assert.match(timeoutCacheBody, /globalThis\.queueMicrotask/,
  "GPU timeout cache is not cleared by a queueMicrotask checkpoint");
const initializeGpuFenceLifecycle = new Function(initializeGpuFenceLifecycleBody);
const browserMarkNextFenceRetireOwned = new Function(jsBodyBefore(
  "public static native void markNextFenceRetireOwned();",
));
const fenceSyncBody = jsBodyBefore(
  "private static native int fenceSyncJs(int condition, int flags);",
);
assert.ok(
  fenceSyncBody.indexOf("state.gpuNextFenceRetireOwned=false")
    < fenceSyncBody.indexOf("if (!state || !gl) return 0"),
  "retire ownership token is not consumed before every fence creation exit",
);
const browserFenceSync = new Function("condition", "flags", fenceSyncBody);
const browserClientWaitSyncBody = jsBodyBefore(
  "private static native int clientWaitSyncJs(int sync, int flags, int timeout);",
);
assert.equal(
  browserClientWaitSyncBody.match(/gpuCacheFenceTimeout\(id,object\)/g)?.length,
  1,
  "GPU timeout cache has an unexpected write path",
);
const timeoutCacheWrite = browserClientWaitSyncBody.indexOf(
  "state.gpuCacheFenceTimeout(id,object)",
);
assert.ok(
  browserClientWaitSyncBody.lastIndexOf("if (status===0x911B)", timeoutCacheWrite) >= 0,
  "GPU timeout cache is populated without a native TIMEOUT_EXPIRED status",
);
const browserClientWaitSync = new Function(
  "sync", "flags", "timeout", browserClientWaitSyncBody,
);
const browserDeleteSync = new Function("sync", jsBodyBefore(
  "private static native void deleteSyncJs(int sync);",
));

const previousWindow = globalThis.window;
const webGlListeners = new Map();
const webGlWaitCalls = [];
const webGlDeleted = [];
const webGlWaitStatuses = [];
let webGlParameterReads = 0;
let webGlFlushCalls = 0;
let webGlFlushFailure = null;
let webGlLoseContextOnFlush = false;
const mockGl = {
  MAX_CLIENT_WAIT_TIMEOUT_WEBGL: 0x9247,
  lost: false,
  nextObject: 1,
  canvas: {
    addEventListener(type, listener) {
      webGlListeners.set(type, listener);
    },
  },
  getParameter(parameter) {
    assert.equal(parameter, this.MAX_CLIENT_WAIT_TIMEOUT_WEBGL);
    webGlParameterReads++;
    return 0;
  },
  isContextLost() {
    return this.lost;
  },
  fenceSync(condition, flags) {
    return {id: this.nextObject++, condition, flags};
  },
  clientWaitSync(object, flags, timeout) {
    webGlWaitCalls.push({object, flags, timeout});
    return webGlWaitStatuses.shift() ?? ALREADY_SIGNALED;
  },
  flush() {
    webGlFlushCalls++;
    if (webGlLoseContextOnFlush) this.lost = true;
    if (webGlFlushFailure) {
      const failure = webGlFlushFailure;
      webGlFlushFailure = null;
      throw failure;
    }
  },
  deleteSync(object) {
    webGlDeleted.push(object.id);
  },
};
const mockWindow = {
  __gaiusGL: {next: 1, syncs: new Map()},
  __gaiusWebGL: mockGl,
  __gaiusGLStats: {},
  // Keep the smoke process alive until assertions finish; production uses the
  // scheduled reload, while this harness only verifies the quarantine signal.
  __gaiusDisableGpuContextAutoReload: true,
  location: {reload() {}},
};
globalThis.window = mockWindow;
try {
  assert.doesNotThrow(() => initializeGpuFenceLifecycle(),
    "GPU lifecycle JSBody did not initialize");
  assert.equal(webGlParameterReads, 1,
    "GPU maximum wait timeout was not cached during lifecycle initialization");
  let state = mockWindow.__gaiusGL;
  const originalGlMethods = new Map(
    Object.keys(mockGl)
      .filter((key) => typeof mockGl[key] === "function")
      .map((key) => [key, mockGl[key]]),
  );

  browserMarkNextFenceRetireOwned();
  mockGl.lost = true;
  assert.equal(browserFenceSync(0x9117, 0), 0,
    "context-lost fence creation unexpectedly succeeded");
  // A real context loss quarantines the WebGL object and schedules a page
  // reload; it must not be reused as though it were restored in-place.  Reset
  // the mock to a fresh context before exercising normal timeout/signaled
  // polling so this smoke tests both lifecycle halves without weakening the
  // production quarantine contract.
  mockGl.lost = false;
  for (const [key, method] of originalGlMethods) {
    Object.defineProperty(mockGl, key, {
      configurable: true,
      writable: true,
      value: method,
    });
  }
  mockWindow.__gaiusGL = {next: 1, syncs: new Map()};
  mockWindow.__gaiusGLStats = {};
  initializeGpuFenceLifecycle();
  state = mockWindow.__gaiusGL;
  const parameterReadsBeforePolling = webGlParameterReads;
  state.gpuBeginRetireFrame(0, 8);
  const unrelatedFence = browserFenceSync(0x9117, 0);
  state.gpuEndRetireFrame(0, false);
  browserDeleteSync(unrelatedFence);
  assert.equal(mockWindow.__gaiusGLStats.gpuEarlyResourceReuse, 0,
    "failed ownership token leaked into an unrelated fence");

  state.gpuBeginRetireFrame(0, 8);
  browserMarkNextFenceRetireOwned();
  const firstFence = browserFenceSync(0x9117, 0);
  const firstFenceNativeCalls = webGlWaitCalls.length;
  const firstFenceFlushCalls = webGlFlushCalls;
  webGlWaitStatuses.push(TIMEOUT_EXPIRED, CONDITION_SATISFIED, ALREADY_SIGNALED);
  assert.equal(browserClientWaitSync(firstFence, 1, 1_000_000), TIMEOUT_EXPIRED);
  assert.equal(webGlParameterReads, parameterReadsBeforePolling,
    "GPU fence poll performed a synchronous WebGL parameter read");
  assert.equal(webGlWaitCalls.at(-1).timeout, 0,
    "BrowserOpenGL passed a blocking timeout to WebGL");
  assert.equal(webGlWaitCalls.length, firstFenceNativeCalls + 1,
    "the first timeout was not a native WebGL wait");
  assert.equal(state.syncs.has(firstFence), true, "timeout discarded the WebGL sync");
  assert.equal(webGlDeleted.length, 1, "timeout deleted the WebGL sync");
  assert.equal(browserClientWaitSync(firstFence, 1, 1_000_000), TIMEOUT_EXPIRED,
    "same-burst duplicate did not preserve the logical timeout");
  assert.equal(webGlWaitCalls.length, firstFenceNativeCalls + 1,
    "same-burst duplicate performed another native WebGL wait");
  assert.equal(webGlFlushCalls, firstFenceFlushCalls,
    "retire-owned same-burst duplicate performed a redundant explicit flush");
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceWaits, 2,
    "coalescing changed logical GPU wait telemetry");
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceTimeouts, 2,
    "coalescing changed logical GPU timeout telemetry");
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceNativeWaitCalls, 1);
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceNativeTimeouts, 1);
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceCoalescedWaits, 1);
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceCoalescedTimeouts, 1);
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceFlushOnlyCalls, 0);
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceExplicitFlushes, 0);
  assert.equal(mockWindow.__gaiusGLStats.gpuFenceSuppressedRepeatFlushes, 1);
  await Promise.resolve();
  assert.equal(browserClientWaitSync(firstFence, 1, 1_000_000), CONDITION_SATISFIED);
  assert.equal(webGlWaitCalls.length, firstFenceNativeCalls + 2,
    "microtask checkpoint did not expire the timeout cache");
  assert.equal(browserClientWaitSync(firstFence, 1, 1_000_000), ALREADY_SIGNALED,
    "successful wait status was cached instead of polling natively");
  assert.equal(webGlWaitCalls.length, firstFenceNativeCalls + 3,
    "successful wait was incorrectly coalesced");
  browserDeleteSync(firstFence);
  assert.equal(state.syncs.has(firstFence), false, "signaled sync was not cleared");
  assert.deepEqual(webGlDeleted, [1, 2], "signaled sync was not deleted exactly once");
  assert.equal(mockWindow.__gaiusGLStats.gpuEarlyResourceReuse, 0,
    "signaled retirement was reported as early reuse");

  const flagsZeroFence = browserFenceSync(0x9117, 0);
  const flagsZeroNativeCalls = webGlWaitCalls.length;
  const flagsZeroFlushCalls = webGlFlushCalls;
  webGlWaitStatuses.push(TIMEOUT_EXPIRED, CONDITION_SATISFIED);
  assert.equal(browserClientWaitSync(flagsZeroFence, 0, 0), TIMEOUT_EXPIRED);
  assert.equal(browserClientWaitSync(flagsZeroFence, 0, 0), TIMEOUT_EXPIRED,
    "flags=0 same-burst timeout did not coalesce");
  assert.equal(webGlWaitCalls.length, flagsZeroNativeCalls + 1,
    "flags=0 same-burst timeout performed another native wait");
  assert.equal(webGlFlushCalls, flagsZeroFlushCalls,
    "flags=0 same-burst timeout unexpectedly flushed WebGL");
  assert.equal(browserClientWaitSync(flagsZeroFence, 0, 1), TIMEOUT_EXPIRED,
    "telemetry-only requested timeout changed the burst result");
  assert.equal(webGlWaitCalls.length, flagsZeroNativeCalls + 1,
    "telemetry-only requested timeout performed another native wait");
  assert.equal(webGlFlushCalls, flagsZeroFlushCalls,
    "flags=0 timeout path flushed WebGL");
  assert.equal(browserClientWaitSync(flagsZeroFence, 1, 1), CONDITION_SATISFIED,
    "application-owned flagged duplicate changed the native wait result");
  assert.equal(webGlWaitCalls.length, flagsZeroNativeCalls + 2,
    "application-owned flagged duplicate reused a flags=0 cached timeout");
  assert.equal(webGlFlushCalls, flagsZeroFlushCalls,
    "application-owned flagged duplicate used a non-native explicit flush");
  browserDeleteSync(flagsZeroFence);
  await Promise.resolve();

  const waitFailedFence = browserFenceSync(0x9117, 0);
  const waitFailedNativeCalls = webGlWaitCalls.length;
  webGlWaitStatuses.push(WAIT_FAILED, WAIT_FAILED);
  assert.equal(browserClientWaitSync(waitFailedFence, 0, 0), WAIT_FAILED);
  assert.equal(browserClientWaitSync(waitFailedFence, 0, 0), WAIT_FAILED);
  assert.equal(webGlWaitCalls.length, waitFailedNativeCalls + 2,
    "WAIT_FAILED was cached instead of polling natively");
  browserDeleteSync(waitFailedFence);

  const applicationFlaggedFence = browserFenceSync(0x9117, 0);
  const applicationFlaggedNativeCalls = webGlWaitCalls.length;
  webGlWaitStatuses.push(TIMEOUT_EXPIRED, CONDITION_SATISFIED);
  assert.equal(browserClientWaitSync(applicationFlaggedFence, 1, 0), TIMEOUT_EXPIRED);
  assert.equal(state.gpuFenceTimeoutBurst.has(applicationFlaggedFence), false,
    "application-owned flagged timeout entered the retire-only cache");
  assert.equal(browserClientWaitSync(applicationFlaggedFence, 1, 0), CONDITION_SATISFIED,
    "application-owned flagged wait was not polled natively");
  assert.equal(webGlWaitCalls.length, applicationFlaggedNativeCalls + 2,
    "application-owned flagged wait was coalesced");
  browserDeleteSync(applicationFlaggedFence);
  await Promise.resolve();

  const deleteCachedFence = browserFenceSync(0x9117, 0);
  const deleteCachedNativeCalls = webGlWaitCalls.length;
  webGlWaitStatuses.push(TIMEOUT_EXPIRED);
  assert.equal(browserClientWaitSync(deleteCachedFence, 0, 0), TIMEOUT_EXPIRED);
  assert.equal(state.gpuFenceTimeoutBurst.has(deleteCachedFence), true,
    "native timeout was not cached for delete cleanup test");
  browserDeleteSync(deleteCachedFence);
  assert.equal(state.gpuFenceTimeoutBurst.has(deleteCachedFence), false,
    "delete retained a burst timeout cache entry");
  assert.equal(browserClientWaitSync(deleteCachedFence, 0, 0), WAIT_FAILED,
    "deleted sync did not fail closed");
  assert.equal(webGlWaitCalls.length, deleteCachedNativeCalls + 1,
    "deleted sync touched native WebGL wait");
  await Promise.resolve();

  const originalQueueMicrotask = globalThis.queueMicrotask;
  let queuedCheckpoints = 0;
  globalThis.queueMicrotask = (callback) => {
    queuedCheckpoints++;
    originalQueueMicrotask(callback);
  };
  const firstMultiFence = browserFenceSync(0x9117, 0);
  const secondMultiFence = browserFenceSync(0x9117, 0);
  const multiNativeCalls = webGlWaitCalls.length;
  try {
    webGlWaitStatuses.push(TIMEOUT_EXPIRED, TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(firstMultiFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(secondMultiFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(queuedCheckpoints, 1,
      "multiple sync timeouts queued more than one burst checkpoint");
    assert.equal(browserClientWaitSync(firstMultiFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(secondMultiFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(webGlWaitCalls.length, multiNativeCalls + 2,
      "multi-sync duplicates were not coalesced independently");
    await Promise.resolve();
    assert.equal(state.gpuFenceTimeoutBurst.size, 0,
      "multi-sync checkpoint did not clear the full burst cache");
  } finally {
    globalThis.queueMicrotask = originalQueueMicrotask;
  }
  browserDeleteSync(firstMultiFence);
  browserDeleteSync(secondMultiFence);

  const noMicrotaskFence = browserFenceSync(0x9117, 0);
  const noMicrotaskNativeCalls = webGlWaitCalls.length;
  globalThis.queueMicrotask = undefined;
  try {
    webGlWaitStatuses.push(TIMEOUT_EXPIRED, TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(noMicrotaskFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(noMicrotaskFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(webGlWaitCalls.length, noMicrotaskNativeCalls + 2,
      "missing queueMicrotask API still coalesced native waits");
    assert.equal(state.gpuFenceTimeoutBurst.has(noMicrotaskFence), false,
      "missing queueMicrotask API retained a timeout cache entry");
  } finally {
    globalThis.queueMicrotask = originalQueueMicrotask;
  }
  browserDeleteSync(noMicrotaskFence);

  const throwingMicrotaskFence = browserFenceSync(0x9117, 0);
  const throwingMicrotaskNativeCalls = webGlWaitCalls.length;
  globalThis.queueMicrotask = () => {
    throw new Error("synthetic queueMicrotask failure");
  };
  try {
    webGlWaitStatuses.push(TIMEOUT_EXPIRED, TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(throwingMicrotaskFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(throwingMicrotaskFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(webGlWaitCalls.length, throwingMicrotaskNativeCalls + 2,
      "throwing queueMicrotask API still coalesced native waits");
    assert.equal(state.gpuFenceTimeoutBurst.has(throwingMicrotaskFence), false,
      "throwing queueMicrotask API retained a timeout cache entry");
  } finally {
    globalThis.queueMicrotask = originalQueueMicrotask;
  }
  assert.ok(mockWindow.__gaiusGLStats.gpuFenceCoalesceScheduleFailures >= 2,
    "queueMicrotask scheduling failures were not recorded");
  browserDeleteSync(throwingMicrotaskFence);

  const synchronousMicrotaskFence = browserFenceSync(0x9117, 0);
  const synchronousMicrotaskNativeCalls = webGlWaitCalls.length;
  globalThis.queueMicrotask = (callback) => callback();
  try {
    webGlWaitStatuses.push(TIMEOUT_EXPIRED, TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(synchronousMicrotaskFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(browserClientWaitSync(synchronousMicrotaskFence, 0, 0), TIMEOUT_EXPIRED);
    assert.equal(webGlWaitCalls.length, synchronousMicrotaskNativeCalls + 2,
      "synchronous queueMicrotask monkey patch created a cross-checkpoint cache");
    assert.equal(state.gpuFenceTimeoutBurst.has(synchronousMicrotaskFence), false,
      "synchronous queueMicrotask monkey patch retained a timeout cache entry");
  } finally {
    globalThis.queueMicrotask = originalQueueMicrotask;
  }
  browserDeleteSync(synchronousMicrotaskFence);

  const missingNativeCalls = webGlWaitCalls.length;
  const missingFlushCalls = webGlFlushCalls;
  assert.equal(browserClientWaitSync(0x7fffffff, 1, 0), WAIT_FAILED,
    "missing sync did not fail closed");
  assert.equal(webGlWaitCalls.length, missingNativeCalls,
    "missing sync touched native WebGL wait");
  assert.equal(webGlFlushCalls, missingFlushCalls,
    "missing sync flushed WebGL");

  state.gpuBeginRetireFrame(0, 8);
  browserMarkNextFenceRetireOwned();
  const lostFence = browserFenceSync(0x9117, 0);
  const lostFenceNativeCalls = webGlWaitCalls.length;
  const deletedBeforeLostPoll = webGlDeleted.length;
  webGlWaitStatuses.push(TIMEOUT_EXPIRED);
  assert.equal(browserClientWaitSync(lostFence, 1, 0), TIMEOUT_EXPIRED);
  mockGl.lost = true;
  assert.equal(browserClientWaitSync(lostFence, 1, 0), WAIT_FAILED,
    "context loss after a cached retire wait returned a stale timeout");
  assert.equal(webGlWaitCalls.length, lostFenceNativeCalls + 1,
    "context loss after cached retire wait performed another native wait");
  assert.equal(state.gpuFenceTimeoutBurst.size, 0,
    "context loss retained timeout cache entries");
  assert.equal(state.syncs.has(lostFence), true, "context loss discarded the WebGL sync");
  assert.equal(webGlDeleted.length, deletedBeforeLostPoll, "context loss deleted a WebGL sync");
  assert.equal(mockWindow.__gaiusGLStats.gpuContextLossWaits, 1);
  // Context loss is a quarantine boundary: the WebGL object remains unusable
  // until the scheduled page reload, so polling the old sync must continue to
  // return WAIT_FAILED rather than pretending the context recovered in-place.
  mockGl.lost = false;
  assert.equal(browserClientWaitSync(lostFence, 0, 0), WAIT_FAILED,
    "quarantined context unexpectedly resumed fence polling");
  assert.equal(webGlDeleted.length, deletedBeforeLostPoll,
    "context-loss polling touched a WebGL sync after quarantine");

  // Start a fresh mock context for the remaining retirement-ring assertions.
  for (const [key, method] of originalGlMethods) {
    Object.defineProperty(mockGl, key, {
      configurable: true,
      writable: true,
      value: method,
    });
  }
  mockGl.nextObject = 1;
  webGlDeleted.length = 0;
  webGlWaitStatuses.length = 0;
  mockWindow.__gaiusGL = {next: 1, syncs: new Map()};
  mockWindow.__gaiusGLStats = {};
  initializeGpuFenceLifecycle();
  state = mockWindow.__gaiusGL;

  state.gpuBeginRetireFrame(0, 8);
  browserMarkNextFenceRetireOwned();
  const earlyFence = browserFenceSync(0x9117, 0);
  state.gpuEndRetireFrame(1, false);
  browserDeleteSync(earlyFence);
  assert.equal(mockWindow.__gaiusGLStats.gpuEarlyResourceReuse, 1,
    "unsignaled retire-owned fence deletion was not detected");

  for (let frame = 0; frame < 130; frame++) {
    state.gpuBeginRetireFrame(0, 8);
    state.gpuEndRetireFrame(0, false);
  }
  assert.equal(state.gpuRetireRecent.length, 120,
    "actual BrowserOpenGL telemetry ring exceeded 120 frames");
} finally {
  if (previousWindow === undefined) delete globalThis.window;
  else globalThis.window = previousWindow;
}

class RetireLifecycle {
  constructor(defaultDelay) {
    this.capacity = 8;
    this.defaultDelay = defaultDelay;
    this.currentIndex = 2n;
    this.frame = 0;
    this.nextFence = 1;
    this.fences = Array(this.capacity).fill(null);
    this.rotations = Array(this.capacity).fill(null);
    this.currentResources = [];
    this.deleted = new Set();
    this.released = new Set();
    this.telemetry = {
      timeouts: 0,
      waitFailures: 0,
      contextLosses: 0,
      backlogMax: 0,
      backpressureFrames: 0,
      closeDeferrals: 0,
      recent: [],
    };
    this.contextLost = false;
    this.closing = false;
  }

  backlog() {
    return this.fences.filter(Boolean).length;
  }

  wait(fence) {
    if (this.contextLost) {
      this.telemetry.waitFailures++;
      return WAIT_FAILED;
    }
    if (this.frame < fence.signalFrame) {
      this.telemetry.timeouts++;
      return TIMEOUT_EXPIRED;
    }
    return this.frame === fence.signalFrame ? CONDITION_SATISFIED : ALREADY_SIGNALED;
  }

  retireSlot(slot) {
    const fence = this.fences[slot];
    if (!fence) return true;
    const status = this.wait(fence);
    if (status !== ALREADY_SIGNALED && status !== CONDITION_SATISFIED) return false;
    assert.equal(this.deleted.has(fence.id), false, "duplicate fence delete");
    this.deleted.add(fence.id);
    this.fences[slot] = null;
    const rotation = this.rotations[slot];
    if (rotation) {
      assert.equal(rotation.submitIndex, fence.submitIndex,
        "retired a rotation owned by another fence");
      for (const resource of rotation.resources) {
        assert.ok(this.closing || this.frame - resource.frame >= this.capacity,
          "transient resource was reused before the eight-frame retire window");
        assert.equal(this.released.has(resource.id), false, "resource released twice");
        this.released.add(resource.id);
      }
      this.rotations[slot] = null;
    }
    return true;
  }

  submit(resourceId) {
    this.frame++;
    this.currentResources.push({id: resourceId, frame: this.frame});
    const entry = {
      frame: this.frame,
      backlogBefore: this.backlog(),
      backlogAfter: 0,
      backpressure: false,
    };
    this.telemetry.recent.push(entry);
    if (this.telemetry.recent.length > 120) {
      this.telemetry.recent.splice(0, this.telemetry.recent.length - 120);
    }
    const slot = Number(this.currentIndex % BigInt(this.capacity));
    if (!this.retireSlot(slot)) {
      entry.backpressure = true;
      entry.backlogAfter = this.backlog();
      this.telemetry.backpressureFrames++;
      this.telemetry.backlogMax = Math.max(this.telemetry.backlogMax, entry.backlogAfter);
      return false;
    }
    const delay = this.defaultDelay;
    const fence = {
      id: this.nextFence++,
      submitIndex: this.currentIndex,
      createdFrame: this.frame,
      signalFrame: Number.isFinite(delay) ? this.frame + delay : Number.POSITIVE_INFINITY,
    };
    this.fences[slot] = fence;
    assert.equal(this.rotations[slot], null, "rotation slot reused before retire");
    this.rotations[slot] = {
      submitIndex: this.currentIndex,
      resources: this.currentResources,
    };
    this.currentResources = [];
    this.currentIndex++;
    entry.backlogAfter = this.backlog();
    this.telemetry.backlogMax = Math.max(this.telemetry.backlogMax, entry.backlogAfter);
    return true;
  }

  signalAll() {
    for (const fence of this.fences) {
      if (fence) fence.signalFrame = this.frame;
    }
  }

  close() {
    this.closing = true;
    let clean = true;
    for (let slot = 0; slot < this.capacity; slot++) {
      if (!this.retireSlot(slot)) clean = false;
    }
    if (!clean) {
      this.closing = false;
      this.telemetry.closeDeferrals++;
      return false;
    }
    assert.equal(this.backlog(), 0, "close left live fences");
    assert.equal(this.deleted.size, this.nextFence - 1, "fence cleanup count mismatch");
    return true;
  }
}

for (const delay of [1, 3, 8]) {
  const lifecycle = new RetireLifecycle(delay);
  for (let frame = 0; frame < 160; frame++) {
    assert.doesNotThrow(() => lifecycle.submit(`delay-${delay}-${frame}`));
  }
  assert.equal(lifecycle.telemetry.recent.length, 120,
    "retire telemetry ring exceeded 120 frames");
  assert.equal(lifecycle.telemetry.backlogMax, 8, "retire backlog was not bounded");
  assert.ok(lifecycle.released.size > 0, `delay ${delay} never retired resources`);
  lifecycle.signalAll();
  assert.doesNotThrow(() => lifecycle.close());
  assert.equal(lifecycle.deleted.size, lifecycle.nextFence - 1,
    `delay ${delay} did not delete every fence exactly once`);
}

const eventual = new RetireLifecycle(Number.POSITIVE_INFINITY);
for (let frame = 0; frame < 32; frame++) {
  assert.doesNotThrow(() => eventual.submit(`timeout-${frame}`));
}
assert.equal(eventual.backlog(), 8, "continuous timeout exceeded the fence bound");
assert.equal(eventual.currentIndex, 10n, "timeout advanced the submit index");
assert.equal(eventual.released.size, 0, "timeout released transient resources early");
assert.ok(eventual.telemetry.timeouts >= 24, "continuous timeout telemetry was lost");
assert.ok(eventual.telemetry.backpressureFrames >= 24,
  "full-backlog cooperative deferral was not recorded");
assert.doesNotThrow(() => assert.equal(eventual.close(), false),
  "continuous timeout made close throw instead of deferring");
assert.equal(eventual.deleted.size, 0, "deferred close deleted an unsignaled fence");
assert.equal(eventual.released.size, 0, "deferred close released transient resources");
eventual.signalAll();
eventual.defaultDelay = 0;
for (let frame = 0; frame < 16; frame++) {
  assert.doesNotThrow(() => eventual.submit(`recovery-${frame}`));
  eventual.signalAll();
}
assert.ok(eventual.released.size > 0, "eventual signal did not resume retirement");
eventual.signalAll();
assert.doesNotThrow(() => eventual.close());

const contextLoss = new RetireLifecycle(Number.POSITIVE_INFINITY);
for (let frame = 0; frame < 8; frame++) contextLoss.submit(`context-fill-${frame}`);
contextLoss.contextLost = true;
contextLoss.telemetry.contextLosses++;
const indexAtLoss = contextLoss.currentIndex;
for (let frame = 0; frame < 12; frame++) {
  assert.doesNotThrow(() => contextLoss.submit(`context-lost-${frame}`));
}
assert.equal(contextLoss.currentIndex, indexAtLoss, "context loss advanced submit state");
assert.equal(contextLoss.deleted.size, 0, "context loss deleted an unsignaled fence");
assert.equal(contextLoss.released.size, 0, "context loss released transient resources");
assert.ok(contextLoss.telemetry.waitFailures >= 12, "context loss wait failures were not counted");
assert.doesNotThrow(() => assert.equal(contextLoss.close(), false),
  "context loss made close throw instead of retaining resources");
assert.equal(contextLoss.deleted.size, 0, "context-loss close deleted a fence");
assert.equal(contextLoss.released.size, 0, "context-loss close released resources");
contextLoss.contextLost = false;
contextLoss.defaultDelay = 0;
contextLoss.signalAll();
for (let frame = 0; frame < 16; frame++) {
  assert.doesNotThrow(() => contextLoss.submit(`context-recovered-${frame}`));
  contextLoss.signalAll();
}
contextLoss.signalAll();
assert.doesNotThrow(() => contextLoss.close());
assert.equal(contextLoss.deleted.size, contextLoss.nextFence - 1,
  "context recovery cleanup duplicated or omitted a fence delete");

console.log("GPU fence retire patched-class and lifecycle smoke passed");
