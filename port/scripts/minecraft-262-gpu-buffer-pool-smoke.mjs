#!/usr/bin/env node

import {CANONICAL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {createHash} from "node:crypto";
import {access, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
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
if (version !== CANONICAL_PROFILE.id) {
  throw new Error(`GPU buffer-pool smoke is ${CANONICAL_PROFILE.id}-only; got profile ${version}`);
}

const clientCandidates = [
  process.env.GAIUS_CLIENT_NAMED_JAR && nativePath(process.env.GAIUS_CLIENT_NAMED_JAR),
  process.env.GAIUS_BUILD_ROOT
    && path.join(nativePath(process.env.GAIUS_BUILD_ROOT), "client-named.jar"),
  path.join(repositoryRoot, `port/work/${version}/client-named.jar`),
].filter(Boolean);
let rawClient = null;
for (const candidate of clientCandidates) {
  try {
    await access(candidate);
    rawClient = candidate;
    break;
  } catch {
    // Try the next profile-scoped source candidate.
  }
}
if (!rawClient) {
  throw new Error(`missing exact ${version} client-named.jar; checked ${clientCandidates.join(", ")}`);
}
const rawClientSha256 = createHash("sha256")
  .update(await readFile(rawClient))
  .digest("hex");
console.log(`CLIENT_NAMED_SHA256 ${rawClientSha256} ${rawClient}`);
const patcherSource = path.join(
  repositoryRoot,
  "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
);
const helperSource = path.join(
  repositoryRoot,
  "port/src/main/java/dev/gaius/browser/BrowserGpuBufferPoolCache.java",
);
// The helper packs its telemetry with dev.gaius.browser.JsArrays (classlib overlay); it
// compiles with the JavaScript implementation.
const jsArraysSource = path.join(
  repositoryRoot,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java",
);

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
    timeout: 120_000,
    ...options,
  });
}

function selectJavaTools() {
  const homes = [
    process.env.GAIUS_JAVA_HOME && nativePath(process.env.GAIUS_JAVA_HOME),
    process.env.JAVA_HOME && nativePath(process.env.JAVA_HOME),
    process.platform === "win32" ? "C:/Program Files/Java/jdk-26.0.1" : null,
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
        };
      }
    } catch {
      // Try the next configured JDK.
    }
  }
  throw new Error("Minecraft " + CANONICAL_PROFILE.id + " GPU buffer-pool smoke requires JDK 25 or newer");
}

const javaTools = selectJavaTools();
const mavenRoot = path.join(homedir(), ".m2/repository");
const asm = path.join(mavenRoot, "org/ow2/asm/asm/9.8/asm-9.8.jar");
const asmTree = path.join(mavenRoot, "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = path.join(
  mavenRoot,
  "org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar",
);
const teaVmJars = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-platform"].map(
  artifact => path.join(
    mavenRoot,
    `org/teavm/${artifact}/0.15.0/${artifact}-0.15.0.jar`,
  ),
);
await Promise.all([
  access(rawClient),
  access(patcherSource),
  access(helperSource),
  access(jsArraysSource),
  access(asm),
  access(asmTree),
  access(asmAnalysis),
  ...teaVmJars.map(jar => access(jar)),
]);

// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
// The pool patch and its helpers up to patchGlDevice, whatever their access modifier.
const patcherText = stepSource("MinecraftClientPatcher");
const helperText = await readFile(helperSource, "utf8");
const featureStart = patcherText.indexOf(
  "static void patchStagedVertexBufferGpuPoolCache(",
);
const featureEnd = patcherText.indexOf(
  "static void patchGlDevice(",
  featureStart,
);
assert.ok(featureStart >= 0 && featureEnd > featureStart,
  "missing isolated GPU buffer-pool patcher method");
const featureText = patcherText.slice(featureStart, featureEnd);

// The call in main() runs under the modern-profile guard (the profiles whose
// patchSet is modern, never 1.21.11), wrapped in PatchRegistry.run so a bring-up
// can skip it.
assert.match(patcherText,
  /static boolean isModernProfile\(String minecraftVersion\) \{\s*return VersionProfile\.forId\(minecraftVersion\)\.modernPatchSet\(\);\s*\}/,
  "isModernProfile must read the profile's patchSet");
const guardedPoolCall = patcherText.search(/\(\) -> (?:RenderPatches\.)?patchStagedVertexBufferGpuPoolCache\(/);
const profileGuardStart = patcherText.lastIndexOf(
  "if (isModernProfile(minecraftVersion)) {",
  guardedPoolCall,
);
const profileGuardEnd = patcherText.indexOf("\n        }", profileGuardStart);
assert.ok(guardedPoolCall >= 0 && profileGuardStart >= 0 && profileGuardEnd > profileGuardStart,
  "missing modern-profile guard around the GPU buffer-pool patch");
assert.ok(guardedPoolCall > profileGuardStart && guardedPoolCall < profileGuardEnd,
  "GPU buffer-pool patch must remain gated to the modern profiles");
for (const contract of [
  "gaius$browserCache",
  "beforeAcquire",
  "recycleResult",
  "afterRecycleSweep",
  "removeAt",
  "recordCreate",
  "afterAcquire",
  "endFrame",
  "ownerClosed",
]) {
  assert.ok(featureText.includes(contract), `missing patcher contract: ${contract}`);
}
for (const contract of [
  "MAX_COUNT = 4",
  "MAX_BYTES = 1024L * 1024L",
  "MAX_IDLE_FRAMES = 3",
  "availableFrames",
  "availableSizes",
  "trimBudgetLinear",
  "TELEMETRY_INTERVAL_FRAMES = 4",
  "MAX_TELEMETRY_POOLS = 24",
  "gpuBufferPoolCache",
  "telemetryPublishes",
  "admittedAvailableHighWater",
  "retainedAvailableHighWater",
  "closedPools",
  "lastClosedPoolId",
  "labelForPublish",
  "state.gpuContextLost",
  "state.gpuSubmissionBlocked",
]) {
  assert.ok(helperText.includes(contract), `missing helper contract: ${contract}`);
}
for (const [label, source] of [["patcher feature", featureText], ["cache helper", helperText]]) {
  for (const forbidden of [
    "IdentityHashMap",
    "CompletableFuture",
    "Executor",
    "new Thread",
    "setTimeout",
    "setInterval",
    "requestAnimationFrame",
    "Platform.schedule",
    "BrowserWorldgenScheduler",
    ".pulse(",
  ]) {
    assert.equal(source.includes(forbidden), false,
      `${label} contains forbidden asynchronous/worldgen primitive: ${forbidden}`);
  }
}
assert.equal(helperText.includes("awaitCompletion"), false,
  "cache helper must never wait for a fence");
for (const rawShapeContract of [
  '"BUFFER_SIZE_INCREMENT", "MAX_REUSE_SIZE_FACTOR", "label", "usage"',
  'Integer.valueOf(262144)',
  'Integer.valueOf(4)',
  '"awaitCompletion"',
  'awaitTimeout.getOpcode() != Opcodes.LCONST_0',
  '"usedThisFrame", "pendingRecycle", "usedThisFrame", "usedThisFrame"',
]) {
  assert.ok(featureText.includes(rawShapeContract),
    `patcher is missing raw fail-closed contract: ${rawShapeContract}`);
}

function jsBodyBefore(declaration) {
  const declarationOffset = helperText.indexOf(declaration);
  assert.notEqual(declarationOffset, -1, `missing JSBody declaration: ${declaration}`);
  const marker = 'script = """';
  const bodyStart = helperText.lastIndexOf(marker, declarationOffset);
  assert.notEqual(bodyStart, -1, `missing JSBody script for ${declaration}`);
  const scriptStart = bodyStart + marker.length;
  const scriptEnd = helperText.indexOf('""")', scriptStart);
  assert.ok(scriptEnd >= scriptStart && scriptEnd < declarationOffset,
    `unterminated JSBody script for ${declaration}`);
  return helperText.slice(scriptStart, scriptEnd);
}

function parameterizedJsBodyBefore(declaration) {
  const declarationOffset = helperText.indexOf(declaration);
  assert.notEqual(declarationOffset, -1, `missing JSBody declaration: ${declaration}`);
  const annotationStart = helperText.lastIndexOf("@JSBody(params = {", declarationOffset);
  assert.notEqual(annotationStart, -1, `missing JSBody parameters for ${declaration}`);
  const scriptMarker = '}, script = """';
  const scriptMarkerOffset = helperText.indexOf(scriptMarker, annotationStart);
  assert.ok(scriptMarkerOffset > annotationStart && scriptMarkerOffset < declarationOffset,
    `missing parameterized JSBody script for ${declaration}`);
  const parameterText = helperText.slice(annotationStart, scriptMarkerOffset);
  const parameters = [...parameterText.matchAll(/"([A-Za-z0-9]+)"/g)]
    .map(match => match[1]);
  const scriptStart = scriptMarkerOffset + scriptMarker.length;
  const scriptEnd = helperText.indexOf('""")', scriptStart);
  assert.ok(scriptEnd >= scriptStart && scriptEnd < declarationOffset,
    `unterminated parameterized JSBody script for ${declaration}`);
  return {parameters, script: helperText.slice(scriptStart, scriptEnd)};
}

const savedGlState = globalThis.__gaiusGL;
const savedWebGl = globalThis.__gaiusWebGL;
const savedGlStats = globalThis.__gaiusGLStats;
try {
  const contextProbe = new Function(jsBodyBefore(
    "private static native boolean browserContextAvailableJs()",
  ));
  delete globalThis.__gaiusGL;
  delete globalThis.__gaiusWebGL;
  assert.equal(contextProbe(), false, "missing GL state must fail closed");
  globalThis.__gaiusGL = {};
  assert.equal(contextProbe(), false, "missing WebGL object must fail closed");
  globalThis.__gaiusWebGL = {};
  assert.equal(contextProbe(), false, "missing isContextLost must fail closed");
  globalThis.__gaiusWebGL = {isContextLost: () => false};
  assert.equal(contextProbe(), true, "live context must pass the context gate");
  globalThis.__gaiusGL.gpuContextLost = true;
  assert.equal(contextProbe(), false, "gpuContextLost must fail closed");
  globalThis.__gaiusGL.gpuContextLost = false;
  globalThis.__gaiusGL.gpuSubmissionBlocked = true;
  assert.equal(contextProbe(), false, "gpuSubmissionBlocked must fail closed");
  globalThis.__gaiusGL.gpuSubmissionBlocked = false;
  globalThis.__gaiusWebGL.isContextLost = () => true;
  assert.equal(contextProbe(), false, "isContextLost true must fail closed");
  globalThis.__gaiusWebGL.isContextLost = () => { throw new Error("probe"); };
  assert.equal(contextProbe(), false, "isContextLost exception must fail closed");

  const telemetryBody = parameterizedJsBodyBefore(
    "private static native void publishTelemetryBrowser(",
  );
  const publishTelemetry = new Function(...telemetryBody.parameters, telemetryBody.script);
  // One packed Float64Array plus the two strings: index P_X of the helper holds field x.
  assert.deepEqual(telemetryBody.parameters, ["v", "label", "reason"],
    "pool telemetry must be one packed array plus label and reason");
  const packedIndex = Object.fromEntries([...helperText.matchAll(
    /private static final int P_([A-Z_]+) = (\d+);/g)].map(match => [match[1], Number(match[2])]));
  const packedCount = packedIndex.COUNT;
  assert.ok(packedCount > 0 && packedCount <= 64, "missing packed telemetry layout");
  const publish = (poolId, ownerClosed, reason = ownerClosed ? "owner-close" : "periodic") => {
    const packed = new Float64Array(packedCount);
    packed[packedIndex.POOL_ID] = poolId;
    packed[packedIndex.USAGE] = poolId & 7;
    packed[packedIndex.OWNER_CLOSED] = ownerClosed ? 1 : 0;
    packed[packedIndex.MAX_POOLS] = 24;
    packed[packedIndex.ACQUIRED_BYTES] = poolId * 4096;
    publishTelemetry(packed, `pool-${poolId}`, reason);
  };
  globalThis.__gaiusGLStats = {};
  for (let poolId = 1; poolId <= 24; poolId++) publish(poolId, false);
  let telemetryRoot = globalThis.__gaiusGLStats.gpuBufferPoolCache;
  assert.equal(telemetryRoot.pools.length, 24,
    "bounded telemetry did not admit all 24 live pools");
  const firstSlot = telemetryRoot.pools[0];
  assert.equal(firstSlot.poolId, 1);
  assert.equal(firstSlot.label, "pool-1");
  assert.equal(firstSlot.usage, 1);
  assert.equal(firstSlot.acquiredBytes, 4096, "packed telemetry index mismatch");
  assert.equal(firstSlot.lastPublishReason, "periodic");
  for (const key of ["frame", "availableCount", "availableBytes", "pendingDepth", "createCalls",
    "storageCompactionFailures"]) {
    assert.equal(firstSlot[key], 0, `packed telemetry field ${key} missing`);
  }
  publish(25, false);
  assert.equal(telemetryRoot.pools.length, 24);
  assert.equal(telemetryRoot.overflowPublishes, 1,
    "25th simultaneous live pool must produce one bounded overflow");
  publish(1, true);
  publish(25, false);
  assert.equal(telemetryRoot.pools.length, 24,
    "freed telemetry slot was not reused by the overflowed live pool");
  assert.equal(telemetryRoot.pools.some(slot => slot.poolId === 1), false,
    "closed telemetry slot retained stale pool state");
  assert.equal(telemetryRoot.pools.some(slot => slot.poolId === 25), true,
    "reused telemetry slot did not publish the new live pool");

  globalThis.__gaiusGLStats = {};
  for (let poolId = 1; poolId <= 128; poolId++) {
    publish(poolId, false);
    assert.equal(globalThis.__gaiusGLStats.gpuBufferPoolCache.pools.length, 1,
      "sequential pool publish did not reuse its bounded slot");
    publish(poolId, true);
    assert.equal(globalThis.__gaiusGLStats.gpuBufferPoolCache.pools.length, 0,
      "owner close left a stale telemetry slot");
  }
  telemetryRoot = globalThis.__gaiusGLStats.gpuBufferPoolCache;
  assert.equal(telemetryRoot.overflowPublishes, 0,
    "sequential create/close overflowed bounded telemetry slots");
  assert.equal(telemetryRoot.closedPools, 128,
    "closed-pool bounded scalar did not count sequential closes");
  assert.equal(telemetryRoot.lastClosedPoolId, 128,
    "lastClosedPoolId did not track the terminal publish");
  publish(4096, true);
  assert.equal(telemetryRoot.pools.length, 0,
    "close-only publish must never create a telemetry slot");
  telemetryRoot.closedPools = 2147483647;
  publish(4097, true);
  assert.equal(telemetryRoot.closedPools, 2147483647,
    "closedPools scalar must saturate instead of growing without bound");
} finally {
  if (savedGlState === undefined) delete globalThis.__gaiusGL;
  else globalThis.__gaiusGL = savedGlState;
  if (savedWebGl === undefined) delete globalThis.__gaiusWebGL;
  else globalThis.__gaiusWebGL = savedWebGl;
  if (savedGlStats === undefined) delete globalThis.__gaiusGLStats;
  else globalThis.__gaiusGLStats = savedGlStats;
}

const bytecodeVerifierSource = String.raw`
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class GpuBufferPoolBytecodeVerifier {
    private static final String OWNER =
            "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool";
    private static final String PENDING = OWNER + "$PendingRecycle";
    private static final String BUFFER = "com/mojang/blaze3d/buffers/GpuBuffer";
    private static final String HELPER = "dev/gaius/browser/BrowserGpuBufferPoolCache";
    private static final String HELPER_DESC = "L" + HELPER + ";";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] readJar(Path jar, String entry) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var item = zip.getEntry(entry);
            check(item != null, "missing jar entry " + entry);
            try (InputStream input = zip.getInputStream(item)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(name) && candidate.desc.equals(desc)) return candidate;
        }
        throw new AssertionError("missing method " + node.name + "." + name + desc);
    }

    private static AbstractInsnNode nextExec(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
                cursor != null; cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static AbstractInsnNode previousExec(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
                cursor != null; cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static int index(MethodNode method, AbstractInsnNode target) {
        int current = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == target) return current;
            current++;
        }
        return -1;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) result.add(call);
        }
        return result;
    }

    private static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static void basicVerify(ClassNode node) throws Exception {
        for (MethodNode candidate : node.methods) {
            if ((candidate.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, candidate);
        }
        System.out.println("BASIC_VERIFIER_OK " + node.name);
    }

    private static int labelTargetIndex(MethodNode method, LabelNode label) {
        AbstractInsnNode cursor = label;
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        check(cursor != null, "jump label has no executable target");
        int executable = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            if (instruction == cursor) return executable;
            executable++;
        }
        throw new AssertionError("jump target not found");
    }

    private static List<String> canonical(MethodNode method, boolean normalizeCache) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (instruction instanceof FieldInsnNode field) {
                if (normalizeCache && field.owner.equals(OWNER)
                        && field.name.equals("gaius$browserCache")) {
                    result.add(opcode + ":F:" + OWNER + ":available:Ljava/util/List;");
                } else {
                    result.add(opcode + ":F:" + field.owner + ":" + field.name + ":" + field.desc);
                }
            } else if (instruction instanceof MethodInsnNode call) {
                if (normalizeCache && call.owner.equals(HELPER)
                        && call.name.equals("removeAt")
                        && call.desc.equals("(I)L" + BUFFER + ";")) {
                    result.add(Opcodes.INVOKEINTERFACE
                            + ":M:java/util/List:remove:(I)Ljava/lang/Object;:true");
                } else {
                    result.add(opcode + ":M:" + call.owner + ":" + call.name + ":"
                            + call.desc + ":" + call.itf);
                }
            } else if (instruction instanceof JumpInsnNode jump) {
                result.add(opcode + ":J:" + labelTargetIndex(method, jump.label));
            } else if (instruction instanceof VarInsnNode variable) {
                result.add(opcode + ":V:" + variable.var);
            } else if (instruction instanceof IntInsnNode integer) {
                result.add(opcode + ":I:" + integer.operand);
            } else if (instruction instanceof LdcInsnNode constant) {
                result.add(opcode + ":L:" + String.valueOf(constant.cst));
            } else if (instruction instanceof TypeInsnNode type) {
                result.add(opcode + ":T:" + type.desc);
            } else if (instruction instanceof IincInsnNode increment) {
                result.add(opcode + ":N:" + increment.var + ":" + increment.incr);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                result.add(opcode + ":D:" + dynamic.name + ":" + dynamic.desc);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                result.add(opcode + ":TS:" + table.min + ":" + table.max);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                result.add(opcode + ":LS:" + lookup.keys);
            } else if (instruction instanceof MultiANewArrayInsnNode multi) {
                result.add(opcode + ":A:" + multi.desc + ":" + multi.dims);
            } else {
                result.add(Integer.toString(opcode));
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path rawJar = Path.of(args[0]);
        Path patchedClass = Path.of(args[1]);
        Path helperClass = Path.of(args[2]);
        ClassNode raw = read(readJar(rawJar,
                "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool.class"));
        ClassNode rawPending = read(readJar(rawJar,
                "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool$PendingRecycle.class"));
        ClassNode patched = read(Files.readAllBytes(patchedClass));
        ClassNode helper = read(Files.readAllBytes(helperClass));
        check(patched.name.equals(OWNER), "patched wrong class " + patched.name);
        basicVerify(patched);
        basicVerify(helper);

        String[] rawFieldNames = {
            "BUFFER_SIZE_INCREMENT", "MAX_REUSE_SIZE_FACTOR", "label", "usage",
            "available", "usedThisFrame", "pendingRecycle"
        };
        String[] rawFieldDescriptors = {
            "I", "I", "Ljava/util/function/Supplier;", "I",
            "Ljava/util/List;", "Ljava/util/List;", "Ljava/util/List;"
        };
        int[] rawFieldAccess = {
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL
        };
        check(raw.fields.size() == rawFieldNames.length, "raw pool field count changed");
        for (int fieldIndex = 0; fieldIndex < rawFieldNames.length; fieldIndex++) {
            FieldNode field = raw.fields.get(fieldIndex);
            check(field.name.equals(rawFieldNames[fieldIndex])
                            && field.desc.equals(rawFieldDescriptors[fieldIndex])
                            && field.access == rawFieldAccess[fieldIndex],
                    "raw pool field shape changed at " + fieldIndex);
        }
        check(Integer.valueOf(262144).equals(raw.fields.get(0).value)
                        && Integer.valueOf(4).equals(raw.fields.get(1).value),
                "raw pool constants changed");
        check((rawPending.access & Opcodes.ACC_FINAL) != 0
                        && rawPending.fields.size() == 2,
                "raw PendingRecycle class shape changed");
        check(rawPending.fields.get(0).name.equals("buffers")
                        && rawPending.fields.get(0).desc.equals("Ljava/util/List;")
                        && rawPending.fields.get(0).access
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)
                        && rawPending.fields.get(1).name.equals("fence")
                        && rawPending.fields.get(1).desc.equals(
                        "Lcom/mojang/blaze3d/buffers/GpuFence;")
                        && rawPending.fields.get(1).access
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL),
                "raw PendingRecycle fields changed");

        MethodNode pendingTry = method(rawPending, "tryRecycle", "()Ljava/util/List;");
        var pendingAwaits = calls(pendingTry, "com/mojang/blaze3d/buffers/GpuFence",
                "awaitCompletion", "(J)Z");
        check(pendingAwaits.size() == 1, "PendingRecycle await count changed");
        MethodInsnNode pendingAwait = pendingAwaits.get(0);
        check(previousExec(pendingAwait).getOpcode() == Opcodes.LCONST_0,
                "PendingRecycle wait is no longer awaitCompletion(0)");
        AbstractInsnNode pendingBranchNode = nextExec(pendingAwait);
        check(pendingBranchNode instanceof JumpInsnNode
                        && pendingBranchNode.getOpcode() == Opcodes.IFEQ,
                "PendingRecycle wait branch changed");
        JumpInsnNode pendingBranch = (JumpInsnNode) pendingBranchNode;
        AbstractInsnNode pendingFalseNull = nextExec(pendingBranch.label);
        check(pendingFalseNull.getOpcode() == Opcodes.ACONST_NULL
                        && nextExec(pendingFalseNull).getOpcode() == Opcodes.ARETURN,
                "PendingRecycle false path must return null");
        var pendingTryCloses = calls(pendingTry, "com/mojang/blaze3d/buffers/GpuFence",
                "close", "()V");
        check(pendingTryCloses.size() == 1
                        && index(pendingTry, pendingTryCloses.get(0))
                        < index(pendingTry, pendingFalseNull),
                "PendingRecycle true path must close the fence before returning buffers");
        MethodNode pendingOwnerClose = method(rawPending, "close", "()V");
        List<String> pendingCloseFields = new ArrayList<>();
        for (AbstractInsnNode instruction : pendingOwnerClose.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(PENDING)) pendingCloseFields.add(field.name);
        }
        check(calls(pendingOwnerClose, "java/util/List", "forEach",
                        "(Ljava/util/function/Consumer;)V").size() == 1
                        && calls(pendingOwnerClose, "com/mojang/blaze3d/buffers/GpuFence",
                        "close", "()V").size() == 1
                        && countOpcode(pendingOwnerClose, Opcodes.RETURN) == 1
                        && pendingCloseFields.equals(List.of("buffers", "fence")),
                "PendingRecycle owner close shape changed");

        List<FieldNode> cacheFields = new ArrayList<>();
        for (FieldNode field : patched.fields) {
            if (field.name.equals("gaius$browserCache")) cacheFields.add(field);
        }
        check(cacheFields.size() == 1, "cache field count changed");
        FieldNode cacheField = cacheFields.get(0);
        check(cacheField.desc.equals(HELPER_DESC), "cache field descriptor changed");
        check((cacheField.access & Opcodes.ACC_PRIVATE) != 0
                        && (cacheField.access & Opcodes.ACC_FINAL) != 0,
                "cache field must be private final");

        MethodNode constructor = method(patched, "<init>", "(Ljava/util/function/Supplier;I)V");
        var helperConstructors = calls(constructor, HELPER, "<init>",
                "(Ljava/util/List;Ljava/util/function/Supplier;I)V");
        check(helperConstructors.size() == 1, "helper constructor count changed");
        int helperStores = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(OWNER)
                    && field.name.equals("gaius$browserCache")) helperStores++;
        }
        check(helperStores == 1, "cache constructor store count changed");

        MethodNode acquire = method(patched, "acquire",
                "(Lcom/mojang/blaze3d/systems/GpuDevice;I)L" + BUFFER + ";");
        MethodInsnNode beforeAcquire = calls(acquire, HELPER, "beforeAcquire", "()V").get(0);
        MethodInsnNode recycle = calls(acquire, OWNER, "tryRecycleBuffers", "()V").get(0);
        MethodInsnNode roundToward = calls(acquire, "net/minecraft/util/Mth",
                "roundToward", "(II)I").get(0);
        MethodInsnNode afterRecycleSweep = calls(acquire, HELPER,
                "afterRecycleSweep", "(I)V").get(0);
        MethodInsnNode takeBest = calls(acquire, OWNER, "takeBestAvailable",
                "(II)L" + BUFFER + ";").get(0);
        MethodInsnNode create = calls(acquire, "com/mojang/blaze3d/systems/GpuDevice",
                "createBuffer", "(Ljava/util/function/Supplier;IJ)L" + BUFFER + ";").get(0);
        MethodInsnNode recordCreate = calls(acquire, HELPER, "recordCreate",
                "(L" + BUFFER + ";)V").get(0);
        MethodInsnNode usedAdd = calls(acquire, "java/util/List", "add",
                "(Ljava/lang/Object;)Z").get(0);
        MethodInsnNode afterAcquire = calls(acquire, HELPER, "afterAcquire",
                "(L" + BUFFER + ";I)V").get(0);
        check(calls(acquire, HELPER, "beforeAcquire", "()V").size() == 1
                        && calls(acquire, HELPER, "afterRecycleSweep", "(I)V").size() == 1
                        && calls(acquire, HELPER, "recordCreate", "(L" + BUFFER + ";)V").size() == 1
                        && calls(acquire, HELPER, "afterAcquire", "(L" + BUFFER + ";I)V").size() == 1,
                "acquire helper call count changed");
        check(index(acquire, beforeAcquire) < index(acquire, recycle)
                        && index(acquire, recycle) < index(acquire, roundToward)
                        && index(acquire, roundToward) < index(acquire, afterRecycleSweep)
                        && index(acquire, afterRecycleSweep) < index(acquire, takeBest)
                        && index(acquire, create) < index(acquire, recordCreate)
                        && index(acquire, recordCreate) < index(acquire, usedAdd)
                        && index(acquire, usedAdd) < index(acquire, afterAcquire)
                        && index(acquire, afterAcquire) < index(acquire,
                        acquire.instructions.getLast()),
                "acquire cache hooks are out of order");
        AbstractInsnNode roundIncrement = previousExec(roundToward);
        AbstractInsnNode roundRequest = previousExec(roundIncrement);
        check(roundIncrement instanceof LdcInsnNode
                        && Integer.valueOf(262144).equals(((LdcInsnNode) roundIncrement).cst)
                        && roundRequest instanceof VarInsnNode
                        && roundRequest.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) roundRequest).var == 2,
                "acquire must retain roundToward(request, 262144)");
        AbstractInsnNode takeBestMultiply = previousExec(takeBest);
        AbstractInsnNode takeBestFactor = previousExec(takeBestMultiply);
        AbstractInsnNode takeBestMaximumInput = previousExec(takeBestFactor);
        AbstractInsnNode takeBestMinimumInput = previousExec(takeBestMaximumInput);
        check(takeBestMultiply.getOpcode() == Opcodes.IMUL
                        && takeBestFactor.getOpcode() == Opcodes.ICONST_4
                        && takeBestMaximumInput instanceof VarInsnNode
                        && ((VarInsnNode) takeBestMaximumInput).var == 3
                        && takeBestMinimumInput instanceof VarInsnNode
                        && ((VarInsnNode) takeBestMinimumInput).var == 3,
                "takeBest maximum must remain roundedMinimum * 4");

        MethodNode recycleLambda = null;
        MethodInsnNode tryRecycle = null;
        for (MethodNode candidate : patched.methods) {
            var found = calls(candidate, PENDING, "tryRecycle", "()Ljava/util/List;");
            if (!found.isEmpty()) {
                check(recycleLambda == null, "multiple recycle lambdas");
                recycleLambda = candidate;
                tryRecycle = found.get(0);
            }
        }
        check(recycleLambda != null && tryRecycle != null, "missing recycle lambda");
        AbstractInsnNode recycledStore = nextExec(tryRecycle);
        check(recycledStore instanceof VarInsnNode variable
                        && recycledStore.getOpcode() == Opcodes.ASTORE && variable.var == 2,
                "tryRecycle result is not stored in local 2");
        AbstractInsnNode cursor = nextExec(recycledStore);
        check(cursor instanceof VarInsnNode && cursor.getOpcode() == Opcodes.ALOAD,
                "recycleResult receiver load missing");
        cursor = nextExec(cursor);
        check(cursor instanceof FieldInsnNode field && field.owner.equals(OWNER)
                        && field.name.equals("gaius$browserCache"),
                "recycleResult cache field missing");
        cursor = nextExec(cursor);
        check(cursor instanceof VarInsnNode value && value.getOpcode() == Opcodes.ALOAD
                        && value.var == 2, "recycleResult value load changed");
        cursor = nextExec(cursor);
        check(cursor instanceof MethodInsnNode call && call.owner.equals(HELPER)
                        && call.name.equals("recycleResult"),
                "recycleResult call is not immediately after ASTORE 2");
        check(calls(recycleLambda, "java/util/List", "addAll",
                "(Ljava/util/Collection;)Z").isEmpty(), "vanilla available.addAll remains");
        check(countOpcode(recycleLambda, Opcodes.IRETURN) == 2,
                "recycle lambda true/false returns changed");
        AbstractInsnNode nullLoad = nextExec(cursor);
        AbstractInsnNode nullBranch = nextExec(nullLoad);
        check(nullLoad instanceof VarInsnNode load && load.var == 2
                        && nullBranch instanceof JumpInsnNode
                        && nullBranch.getOpcode() == Opcodes.IFNULL,
                "recycle lambda null true/false branch changed");

        MethodNode rawTakeBest = method(raw, "takeBestAvailable", "(II)L" + BUFFER + ";");
        MethodNode patchedTakeBest = method(patched, "takeBestAvailable", "(II)L" + BUFFER + ";");
        check(calls(patchedTakeBest, HELPER, "removeAt", "(I)L" + BUFFER + ";").size() == 2,
                "takeBest must delegate both removal paths to the cache");
        check(calls(patchedTakeBest, "java/util/List", "remove",
                "(I)Ljava/lang/Object;").isEmpty(), "takeBest retains raw List.remove");
        check(canonical(rawTakeBest, false).equals(canonical(patchedTakeBest, true)),
                "takeBest selection comparator/CFG changed beyond removeAt delegation");

        MethodNode endFrame = method(patched, "endFrame",
                "(Lcom/mojang/blaze3d/systems/GpuDevice;)V");
        MethodInsnNode encoder = calls(endFrame, "com/mojang/blaze3d/systems/GpuDevice",
                "createCommandEncoder", "()Lcom/mojang/blaze3d/systems/CommandEncoder;").get(0);
        MethodInsnNode fence = calls(endFrame, "com/mojang/blaze3d/systems/CommandEncoder",
                "createFence", "()Lcom/mojang/blaze3d/buffers/GpuFence;").get(0);
        MethodInsnNode copy = calls(endFrame, "java/util/List", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/List;").get(0);
        MethodInsnNode pendingCtor = calls(endFrame, PENDING, "<init>",
                "(Ljava/util/List;Lcom/mojang/blaze3d/buffers/GpuFence;)V").get(0);
        MethodInsnNode pendingAdd = calls(endFrame, "java/util/List", "add",
                "(Ljava/lang/Object;)Z").get(0);
        MethodInsnNode usedClear = calls(endFrame, "java/util/List", "clear", "()V").get(0);
        MethodInsnNode cacheEnd = calls(endFrame, HELPER, "endFrame", "(I)V").get(0);
        check(index(endFrame, encoder) < index(endFrame, fence)
                        && index(endFrame, fence) < index(endFrame, copy)
                        && index(endFrame, copy) < index(endFrame, pendingCtor)
                        && index(endFrame, pendingCtor) < index(endFrame, pendingAdd)
                        && index(endFrame, pendingAdd) < index(endFrame, usedClear)
                        && index(endFrame, usedClear) < index(endFrame, cacheEnd),
                "endFrame used-to-fence-to-pending path changed");
        check(calls(endFrame, "java/util/List", "clear", "()V").size() == 1,
                "endFrame must clear only usedThisFrame");
        check(calls(endFrame, "java/util/List", "forEach",
                "(Ljava/util/function/Consumer;)V").isEmpty(),
                "endFrame still closes available buffers");
        check(calls(endFrame, HELPER, "endFrame", "(I)V").size() == 1,
                "cache endFrame publish count changed");
        List<String> endFrameFields = new ArrayList<>();
        for (AbstractInsnNode instruction : endFrame.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(OWNER)) endFrameFields.add(field.name);
        }
        check(endFrameFields.equals(List.of(
                        "usedThisFrame", "pendingRecycle", "usedThisFrame", "usedThisFrame",
                        "gaius$browserCache", "pendingRecycle")),
                "endFrame specific list receivers changed: " + endFrameFields);
        check(countOpcode(endFrame, Opcodes.RETURN) == 1
                        && index(endFrame, cacheEnd) < index(endFrame, endFrame.instructions.getLast()),
                "endFrame cache hook must dominate its single return");

        MethodNode close = method(patched, "close", "()V");
        var closeForEach = calls(close, "java/util/List", "forEach",
                "(Ljava/util/function/Consumer;)V");
        var closeClears = calls(close, "java/util/List", "clear", "()V");
        var ownerClosed = calls(close, HELPER, "ownerClosed", "()V");
        check(closeForEach.size() == 3 && closeClears.size() == 3,
                "owner close must retain all three close+clear paths");
        check(ownerClosed.size() == 1
                        && index(close, ownerClosed.get(0)) > index(close, closeClears.get(2))
                        && index(close, ownerClosed.get(0)) < index(close,
                        close.instructions.getLast()),
                "ownerClosed must run once after all owner list clears");
        List<String> closeFields = new ArrayList<>();
        for (AbstractInsnNode instruction : close.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(OWNER)) closeFields.add(field.name);
        }
        check(closeFields.equals(List.of(
                        "available", "usedThisFrame", "pendingRecycle",
                        "available", "usedThisFrame", "pendingRecycle", "gaius$browserCache")),
                "close specific list receivers changed: " + closeFields);
        check(countOpcode(close, Opcodes.RETURN) == 1,
                "close must retain a single return dominated by ownerClosed");

        System.out.println("GPU_POOL_CFG_OK field=final hooks=7 removeAt=2 ownerClose=3 rawPending=exact");
    }
}
`;

const verifyLoadSource = String.raw`
package dev.gaius.smoke;

import java.lang.reflect.Modifier;

public final class GpuBufferPoolPatchedLoadVerifier {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = GpuBufferPoolPatchedLoadVerifier.class.getClassLoader();
        Class<?> pool = Class.forName(
                "net.minecraft.client.renderer.StagedVertexBuffer$GpuBufferPool", false, loader);
        Class<?> helper = Class.forName(
                "dev.gaius.browser.BrowserGpuBufferPoolCache", false, loader);
        var field = pool.getDeclaredField("gaius$browserCache");
        if (!Modifier.isFinal(field.getModifiers()) || field.getType() != helper) {
            throw new AssertionError("cache field failed JVM verification");
        }
        pool.getDeclaredMethod("acquire",
                Class.forName("com.mojang.blaze3d.systems.GpuDevice", false, loader), int.class);
        pool.getDeclaredMethod("endFrame",
                Class.forName("com.mojang.blaze3d.systems.GpuDevice", false, loader));
        pool.getDeclaredMethod("close");
        System.out.println("XVERIFY_OK " + pool.getName());
    }
}
`;

const helperHarnessSource = String.raw`
package dev.gaius.browser;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public final class BrowserGpuBufferPoolCacheHarness {
    private static final long KIB = 1024L;

    private static final class MockBuffer extends GpuBuffer {
        private boolean closed;
        private int closeCalls;

        MockBuffer(long size) {
            super(2, size);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closeCalls++;
            closed = true;
        }

        @Override
        public GpuBufferSlice.MappedView map(
                long offset, long length, boolean read, boolean write) {
            return null;
        }
    }

    private static final class ThrowingPartialAddList extends ArrayList<GpuBuffer> {
        @Override
        public boolean addAll(Collection<? extends GpuBuffer> incoming) {
            int copied = 0;
            for (GpuBuffer buffer : incoming) {
                super.add(buffer);
                copied++;
                if (copied == 2) {
                    throw new IllegalStateException("partial add fixture");
                }
            }
            return copied != 0;
        }
    }

    private record Pending(MockBuffer buffer, int readyFrame) {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static BrowserGpuBufferPoolCache cache(
            List<GpuBuffer> available, boolean[] context, int usage) {
        return new BrowserGpuBufferPoolCache(available, () -> "smoke-" + usage,
                usage, () -> context[0]);
    }

    private static void javaBridgeFailClosed() {
        try {
            var method = BrowserGpuBufferPoolCache.class.getDeclaredMethod(
                    "browserContextAvailable");
            method.setAccessible(true);
            check(Boolean.FALSE.equals(method.invoke(null)),
                    "unavailable JVM JS bridge must fail the context gate closed");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not verify the Java context bridge", failure);
        }
    }

    private static void signalOnly() {
        List<GpuBuffer> available = new ArrayList<>();
        BrowserGpuBufferPoolCache cache = cache(available, new boolean[] {true}, 2);
        cache.recycleResult(null);
        check(available.isEmpty(), "unsignalled recycle entered the cache");
        check(cache.recycleNotReadyForTesting() == 1, "unsignalled telemetry changed");
        MockBuffer ready = new MockBuffer(256 * KIB);
        cache.recycleResult(List.of(ready));
        cache.afterRecycleSweep(256 * 1024);
        check(available.size() == 1 && available.get(0) == ready,
                "signalled recycle did not enter the cache");
    }

    private static void inclusiveCountAndBytes() {
        List<GpuBuffer> available = new ArrayList<>();
        BrowserGpuBufferPoolCache cache = cache(available, new boolean[] {true}, 2);
        MockBuffer[] exact = new MockBuffer[5];
        for (int index = 0; index < exact.length; index++) {
            exact[index] = new MockBuffer(256 * KIB);
        }
        cache.recycleResult(List.<GpuBuffer>of(exact));
        cache.afterRecycleSweep(256 * 1024);
        check(cache.removeAt(0) == exact[0],
                "vanilla first exact candidate was not preserved before trim");
        cache.afterAcquire(exact[0], 256 * 1024);
        check(cache.availableCountForTesting() == 4
                        && cache.availableBytesForTesting() == 1024 * KIB,
                "four 256KiB entries must fit inclusively");
        for (int index = 0; index < exact.length; index++) {
            check(exact[index].closeCalls == 0, "inclusive budget closed an in-budget entry");
        }

        List<GpuBuffer> countAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache countCache = cache(
                countAvailable, new boolean[] {true}, 2);
        MockBuffer[] countBuffers = new MockBuffer[5];
        for (int index = 0; index < countBuffers.length; index++) {
            countBuffers[index] = new MockBuffer(64 * KIB);
        }
        countCache.recycleResult(List.<GpuBuffer>of(countBuffers));
        countCache.afterRecycleSweep(2 * 1024 * 1024);
        MockBuffer countMiss = new MockBuffer(2 * 1024 * KIB);
        countCache.recordCreate(countMiss);
        countCache.afterAcquire(countMiss, 2 * 1024 * 1024);
        check(countBuffers[0].closeCalls == 1 && countBuffers[4].closeCalls == 0,
                "same-age count trim must choose the lowest stable index");
        check(countCache.availableCountForTesting() == 4
                        && countCache.budgetEvictionsForTesting() == 1,
                "count trim did not retain exactly four entries");

        List<GpuBuffer> byteAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache byteCache = cache(
                byteAvailable, new boolean[] {true}, 3);
        MockBuffer small = new MockBuffer(128 * KIB);
        MockBuffer largeFirst = new MockBuffer(512 * KIB);
        MockBuffer largeSecond = new MockBuffer(512 * KIB);
        byteCache.recycleResult(List.of(small, largeFirst, largeSecond));
        byteCache.afterRecycleSweep(2 * 1024 * 1024);
        MockBuffer byteMiss = new MockBuffer(2 * 1024 * KIB);
        byteCache.recordCreate(byteMiss);
        byteCache.afterAcquire(byteMiss, 2 * 1024 * 1024);
        check(largeFirst.closeCalls == 1 && largeSecond.closeCalls == 0,
                "same-age byte trim must choose largest then lowest index");
        check(byteCache.availableBytesForTesting() == 640 * KIB,
                "byte budget did not track remaining primitive stamps");

        List<GpuBuffer> ageAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache ageCache = cache(ageAvailable, new boolean[] {true}, 4);
        MockBuffer oldest = new MockBuffer(64 * KIB);
        MockBuffer newerLarge = new MockBuffer(1024 * KIB);
        ageCache.recycleResult(List.of(oldest));
        ageCache.endFrame(0);
        ageCache.recycleResult(List.of(newerLarge));
        ageCache.afterRecycleSweep(2 * 1024 * 1024);
        MockBuffer ageMiss = new MockBuffer(2 * 1024 * KIB);
        ageCache.recordCreate(ageMiss);
        ageCache.afterAcquire(ageMiss, 2 * 1024 * 1024);
        check(oldest.closeCalls == 1 && newerLarge.closeCalls == 0,
                "budget eviction must prefer age before size");
    }

    private static void ttlAndRefresh() {
        List<GpuBuffer> available = new ArrayList<>();
        BrowserGpuBufferPoolCache cache = cache(available, new boolean[] {true}, 2);
        MockBuffer buffer = new MockBuffer(64 * KIB);
        cache.recycleResult(List.of(buffer));
        cache.endFrame(0);
        cache.endFrame(0);
        cache.endFrame(0);
        check(buffer.closeCalls == 0 && available.size() == 1,
                "TTL must retain age three inclusively");
        cache.endFrame(0);
        check(buffer.closeCalls == 1 && available.isEmpty(),
                "TTL must purge at age four");
        check(cache.ttlEvictionsForTesting() == 1, "TTL telemetry changed");

        List<GpuBuffer> refreshedAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache refreshed = cache(
                refreshedAvailable, new boolean[] {true}, 2);
        MockBuffer reused = new MockBuffer(64 * KIB);
        refreshed.recycleResult(List.of(reused));
        refreshed.endFrame(0);
        refreshed.endFrame(0);
        refreshed.afterRecycleSweep(64 * 1024);
        check(refreshed.removeAt(0) == reused, "removeAt lost the reusable entry");
        refreshed.recycleResult(List.of(reused));
        refreshed.endFrame(0);
        refreshed.endFrame(0);
        refreshed.endFrame(0);
        check(reused.closeCalls == 0, "reuse did not refresh the idle stamp");
        refreshed.endFrame(0);
        check(reused.closeCalls == 1, "refreshed entry did not expire at refreshed age four");
    }

    private static void contextAndAnomalies() {
        boolean[] context = {true};
        List<GpuBuffer> available = new ArrayList<>();
        BrowserGpuBufferPoolCache cache = cache(available, context, 2);
        MockBuffer cached = new MockBuffer(64 * KIB);
        MockBuffer used = new MockBuffer(64 * KIB);
        MockBuffer pending = new MockBuffer(64 * KIB);
        List<GpuBuffer> usedOwnerList = new ArrayList<>(List.of(used));
        List<GpuBuffer> pendingOwnerList = new ArrayList<>(List.of(pending));
        cache.recycleResult(List.of(cached));
        context[0] = false;
        cache.beforeAcquire();
        check(cached.closeCalls == 1 && available.isEmpty(),
                "context loss did not purge available");
        check(usedOwnerList.size() == 1 && pendingOwnerList.size() == 1
                        && used.closeCalls == 0 && pending.closeCalls == 0,
                "context loss touched owner used/pending state");
        check(cache.contextDropsForTesting() == 1, "context drop telemetry changed");

        List<GpuBuffer> duplicateAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache duplicateCache = cache(
                duplicateAvailable, new boolean[] {true}, 2);
        MockBuffer duplicate = new MockBuffer(64 * KIB);
        duplicateCache.recycleResult(List.of(duplicate, duplicate));
        duplicateCache.afterRecycleSweep(64 * 1024);
        check(duplicateAvailable.isEmpty() && duplicate.closeCalls == 1,
                "duplicate anomaly did not fail closed exactly once");

        List<GpuBuffer> closedAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache closedCache = cache(
                closedAvailable, new boolean[] {true}, 2);
        MockBuffer alreadyClosed = new MockBuffer(64 * KIB);
        alreadyClosed.close();
        closedCache.recycleResult(List.of(alreadyClosed));
        closedCache.afterRecycleSweep(64 * 1024);
        check(closedAvailable.isEmpty() && alreadyClosed.closeCalls == 1,
                "closed-buffer anomaly reclosed or retained the entry");

        List<GpuBuffer> mismatchAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache mismatchCache = cache(
                mismatchAvailable, new boolean[] {true}, 2);
        MockBuffer external = new MockBuffer(64 * KIB);
        mismatchAvailable.add(external);
        mismatchCache.beforeAcquire();
        check(mismatchAvailable.isEmpty() && external.closeCalls == 1,
                "size/stamp mismatch did not fail closed");

        List<GpuBuffer> invalidIndexAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache invalidIndexCache = cache(
                invalidIndexAvailable, new boolean[] {true}, 2);
        MockBuffer invalidA = new MockBuffer(64 * KIB);
        MockBuffer invalidB = new MockBuffer(64 * KIB);
        invalidIndexCache.recycleResult(List.of(invalidA, invalidB));
        invalidIndexCache.afterRecycleSweep(64 * 1024);
        check(invalidIndexCache.removeAt(9) == null,
                "invalid index must return a fail-closed miss");
        check(invalidIndexAvailable.isEmpty()
                        && invalidA.closeCalls == 1 && invalidB.closeCalls == 1,
                        "invalid index did not close only available entries");
    }

    private static void adoptionFailureAndLargeBatches() {
        ThrowingPartialAddList throwingAvailable = new ThrowingPartialAddList();
        MockBuffer retainedBeforeFailure = new MockBuffer(16 * KIB);
        throwingAvailable.add(retainedBeforeFailure);
        BrowserGpuBufferPoolCache throwing = cache(
                throwingAvailable, new boolean[] {true}, 2);
        MockBuffer partialA = new MockBuffer(16 * KIB);
        MockBuffer partialB = new MockBuffer(16 * KIB);
        MockBuffer partialC = new MockBuffer(16 * KIB);
        throwing.recycleResult(List.of(partialA, partialB, partialC));
        check(throwingAvailable.isEmpty()
                        && retainedBeforeFailure.closeCalls == 1
                        && partialA.closeCalls == 1
                        && partialB.closeCalls == 1
                        && partialC.closeCalls == 1,
                "partial add failure did not close the available+incoming ownership union exactly once");
        check(throwing.anomalyDropsForTesting() == 1,
                "partial add failure was not recorded as an anomaly");

        List<GpuBuffer> invalidAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache invalid = cache(
                invalidAvailable, new boolean[] {true}, 2);
        MockBuffer duplicate = new MockBuffer(16 * KIB);
        MockBuffer alreadyClosed = new MockBuffer(16 * KIB);
        alreadyClosed.close();
        invalid.recycleResult(java.util.Arrays.asList(
                duplicate, duplicate, null, alreadyClosed));
        invalid.afterRecycleSweep(16 * 1024);
        check(invalidAvailable.isEmpty()
                        && duplicate.closeCalls == 1
                        && alreadyClosed.closeCalls == 1,
                "duplicate/null/closed adoption did not fail closed exactly once");

        List<GpuBuffer> manyAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache many = cache(
                manyAvailable, new boolean[] {true}, 2);
        MockBuffer[] manyBuffers = new MockBuffer[128];
        for (int index = 0; index < manyBuffers.length; index++) {
            long size = index == 73 ? 64 * KIB : (65 + index) * KIB;
            manyBuffers[index] = new MockBuffer(size);
            many.recycleResult(List.of(manyBuffers[index]));
        }
        check(many.adoptionOperationsForTesting() == 128,
                "per-batch adoption is not linear in admitted buffers");
        many.afterRecycleSweep(64 * 1024);
        check(many.sweepIdentityComparisonsForTesting() <= 128L * 127L / 2L,
                "128 ready batches exceeded one unified identity sweep");
        check(many.sweepSelectionComparisonsForTesting() == 128,
                "best-fit selection must scan the admitted set exactly once");
        check(many.removeAt(73) == manyBuffers[73],
                "unified sweep did not preserve vanilla's first exact candidate");
        many.afterAcquire(manyBuffers[73], 64 * 1024);
        check(many.availableCountForTesting() <= 4
                        && many.availableBytesForTesting() <= 1024 * KIB,
                "128-batch retained cache exceeds count/byte limits");
        check(many.metadataCapacityForTesting() == 4
                        && many.listTrimCallsForTesting() == 1,
                "128-batch primitive/list storage did not compact once after trim");
        check(many.admittedHighWaterForTesting() == 128
                        && many.retainedHighWaterForTesting() <= 4,
                "admitted and retained high-water telemetry was not separated");
        check(many.trimComparisonsForTesting() <= 128L * 12L,
                "128-batch budget trim exceeded its O(n * MAX_COUNT) bound");

        List<GpuBuffer> hugeAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache huge = cache(
                hugeAvailable, new boolean[] {true}, 2);
        List<GpuBuffer> hugeBatch = new ArrayList<>();
        MockBuffer[] hugeBuffers = new MockBuffer[1024];
        for (int index = 0; index < hugeBuffers.length; index++) {
            hugeBuffers[index] = new MockBuffer(index == 900 ? 64 * KIB : KIB);
            hugeBatch.add(hugeBuffers[index]);
        }
        huge.recycleResult(hugeBatch);
        huge.afterRecycleSweep(64 * 1024);
        check(huge.removeAt(900) == hugeBuffers[900],
                "1024-buffer batch changed the vanilla exact candidate");
        huge.afterAcquire(hugeBuffers[900], 64 * 1024);
        check(huge.availableCountForTesting() == 4
                        && huge.metadataCapacityForTesting() == 4
                        && huge.listTrimCallsForTesting() == 1,
                "1024-buffer batch did not converge to compact bounded storage");
        check(huge.adoptionOperationsForTesting() == 1024
                        && huge.sweepIdentityComparisonsForTesting()
                        <= 1024L * 1023L / 2L
                        && huge.sweepSelectionComparisonsForTesting() == 1024,
                "1024-buffer adoption/sweep exceeded the explicit operation bound");
        check(huge.trimComparisonsForTesting() <= 1024L * 12L,
                "1024-buffer trim exceeded O(n * MAX_COUNT)");
        int closed = 0;
        for (MockBuffer buffer : hugeBuffers) {
            if (buffer.closeCalls == 1) closed++;
            else check(buffer.closeCalls == 0, "large-batch buffer closed more than once");
        }
        check(closed == 1019,
                "1024 batch must leave one acquired plus four retained buffers live");

        boolean[] context = {true};
        List<GpuBuffer> contextAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache contextPurge = cache(contextAvailable, context, 2);
        List<GpuBuffer> contextBatch = new ArrayList<>();
        MockBuffer[] contextBuffers = new MockBuffer[128];
        for (int index = 0; index < contextBuffers.length; index++) {
            contextBuffers[index] = new MockBuffer(KIB);
            contextBatch.add(contextBuffers[index]);
        }
        contextPurge.recycleResult(contextBatch);
        check(contextPurge.metadataCapacityForTesting() >= 128,
                "large context fixture did not expand primitive metadata");
        context[0] = false;
        contextPurge.beforeAcquire();
        check(contextAvailable.isEmpty()
                        && contextPurge.metadataCapacityForTesting() == 4
                        && contextPurge.listTrimCallsForTesting() == 1,
                "context purge did not compact large primitive/list storage");
        for (MockBuffer buffer : contextBuffers) {
            check(buffer.closeCalls == 1,
                    "context purge did not close every unique admitted buffer exactly once");
        }

        List<GpuBuffer> closeAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache closeCache = cache(
                closeAvailable, new boolean[] {true}, 2);
        List<GpuBuffer> closeBatch = new ArrayList<>();
        MockBuffer[] closeBuffers = new MockBuffer[128];
        for (int index = 0; index < closeBuffers.length; index++) {
            closeBuffers[index] = new MockBuffer(KIB);
            closeBatch.add(closeBuffers[index]);
        }
        closeCache.recycleResult(closeBatch);
        check(closeCache.metadataCapacityForTesting() >= 128,
                "large close fixture did not expand primitive metadata");
        // Preserve the product close ordering: the owner closes/clears its list first, then
        // ownerClosed publishes and compacts only helper metadata.
        for (GpuBuffer buffer : closeAvailable) buffer.close();
        closeAvailable.clear();
        closeCache.ownerClosed();
        check(closeCache.metadataCapacityForTesting() == 4
                        && closeCache.listTrimCallsForTesting() == 1
                        && closeCache.anomalyDropsForTesting() == 0
                        && closeCache.telemetryPublishesForTesting() == 1,
                "owner close did not compact large storage and publish one clean terminal state");
        for (MockBuffer buffer : closeBuffers) {
            check(buffer.closeCalls == 1,
                    "owner close did not preserve exactly-once ownership cleanup");
        }
    }

    private static void telemetryThrottleAndLazyLabel() {
        int[] labelCalls = {0};
        List<GpuBuffer> available = new ArrayList<>();
        BrowserGpuBufferPoolCache cache = new BrowserGpuBufferPoolCache(
                available,
                () -> {
                    labelCalls[0]++;
                    return "lazy-label";
                },
                2,
                () -> true);
        check(labelCalls[0] == 0, "label Supplier ran in the constructor");
        cache.endFrame(0);
        cache.endFrame(0);
        cache.endFrame(0);
        check(cache.telemetryPublishesForTesting() == 0 && labelCalls[0] == 0,
                "normal telemetry was not throttled for four frames");
        cache.endFrame(0);
        check(cache.telemetryPublishesForTesting() == 1 && labelCalls[0] == 1,
                "first periodic publish did not lazily resolve the label");
        cache.ownerClosed();
        check(cache.telemetryPublishesForTesting() == 2 && labelCalls[0] == 1,
                "owner close did not publish immediately or re-evaluated the label");

        int[] anomalyLabelCalls = {0};
        List<GpuBuffer> anomalyAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache anomaly = new BrowserGpuBufferPoolCache(
                anomalyAvailable,
                () -> {
                    anomalyLabelCalls[0]++;
                    return "anomaly";
                },
                2,
                () -> true);
        MockBuffer buffer = new MockBuffer(16 * KIB);
        anomaly.recycleResult(List.of(buffer));
        anomaly.afterRecycleSweep(16 * 1024);
        anomaly.removeAt(7);
        check(anomaly.telemetryPublishesForTesting() == 1
                        && anomalyLabelCalls[0] == 1,
                "anomaly telemetry was not immediate");
    }

    private static void overflowAndOwnerClose() {
        List<GpuBuffer> overflowAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache overflow = cache(
                overflowAvailable, new boolean[] {true}, 2);
        MockBuffer overflowBuffer = new MockBuffer(64 * KIB);
        overflow.recycleResult(List.of(overflowBuffer));
        overflow.setFrameForTesting(Integer.MAX_VALUE);
        overflow.endFrame(0);
        check(overflowBuffer.closeCalls == 1 && overflowAvailable.isEmpty(),
                "frame overflow did not purge available");
        check(overflow.overflowPurgesForTesting() == 1,
                "frame overflow telemetry changed");

        List<GpuBuffer> ownerAvailable = new ArrayList<>();
        BrowserGpuBufferPoolCache owner = cache(
                ownerAvailable, new boolean[] {true}, 2);
        MockBuffer buffer = new MockBuffer(64 * KIB);
        owner.recycleResult(List.of(buffer));
        for (GpuBuffer item : ownerAvailable) item.close();
        ownerAvailable.clear();
        owner.ownerClosed();
        owner.ownerClosed();
        check(buffer.closeCalls == 1 && ownerAvailable.isEmpty(),
                "owner close plus ownerClosed double-closed the buffer");
        check(owner.telemetryPublishesForTesting() == 1,
                "owner close terminal telemetry must publish exactly once");
    }

    private static void delayedFenceModel() {
        List<GpuBuffer> available = new ArrayList<>();
        BrowserGpuBufferPoolCache cache = cache(available, new boolean[] {true}, 2);
        List<Pending> pending = new ArrayList<>();
        List<MockBuffer> physical = new ArrayList<>();
        int maxAvailable = 0;
        for (int frame = 0; frame < 120; frame++) {
            cache.beforeAcquire();
            List<GpuBuffer> ready = new ArrayList<>();
            for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext();) {
                Pending item = iterator.next();
                if (item.readyFrame() <= frame) {
                    ready.add(item.buffer());
                    iterator.remove();
                }
            }
            cache.recycleResult(ready.isEmpty() ? null : ready);
            cache.afterRecycleSweep(256 * 1024);
            maxAvailable = Math.max(maxAvailable, available.size());
            MockBuffer acquired;
            if (available.isEmpty()) {
                acquired = new MockBuffer(256 * KIB);
                physical.add(acquired);
                cache.recordCreate(acquired);
            } else {
                acquired = (MockBuffer) cache.removeAt(0);
            }
            cache.afterAcquire(acquired, 256 * 1024);
            pending.add(new Pending(acquired, frame + 2));
            cache.endFrame(pending.size());
        }
        check(physical.size() == 2 && cache.createCallsForTesting() == 2,
                "two-frame signal delay should stabilize at two physical buffers");
        check(cache.reuseCallsForTesting() == 118,
                "120-frame two-frame delay reuse count changed");
        check(maxAvailable == 1 && maxAvailable <= BrowserGpuBufferPoolCache.MAX_COUNT,
                "two-frame model exceeded the bounded available cache");
        check(cache.telemetryPublishesForTesting() == 30,
                "120 normal frames must publish at the four-frame throttle");
        for (MockBuffer buffer : physical) {
            check(buffer.closeCalls == 0, "active two-frame ring closed a live buffer");
        }
    }

    private static void independentPools() {
        boolean[] contextA = {true};
        boolean[] contextB = {true};
        List<GpuBuffer> availableA = new ArrayList<>();
        List<GpuBuffer> availableB = new ArrayList<>();
        BrowserGpuBufferPoolCache cacheA = cache(availableA, contextA, 2);
        BrowserGpuBufferPoolCache cacheB = cache(availableB, contextB, 4);
        MockBuffer bufferA = new MockBuffer(64 * KIB);
        MockBuffer bufferB = new MockBuffer(64 * KIB);
        cacheA.recycleResult(List.of(bufferA));
        cacheB.recycleResult(List.of(bufferB));
        contextA[0] = false;
        cacheA.beforeAcquire();
        check(bufferA.closeCalls == 1 && availableA.isEmpty(),
                "pool A context loss did not purge A");
        check(bufferB.closeCalls == 0 && availableB.size() == 1,
                "pool A context loss leaked into independent pool B");
        check(cacheA.poolIdForTesting() != cacheB.poolIdForTesting(),
                "independent pools share a telemetry identity");
    }

    public static void main(String[] args) {
        javaBridgeFailClosed();
        signalOnly();
        inclusiveCountAndBytes();
        ttlAndRefresh();
        contextAndAnomalies();
        adoptionFailureAndLargeBatches();
        telemetryThrottleAndLazyLabel();
        overflowAndOwnerClose();
        delayedFenceModel();
        independentPools();
        System.out.println("GPU_BUFFER_POOL_MODEL_OK frames=120 delay=2 count=4 bytes=1048576 ttl=3 batches=128/1024 telemetry=4");
    }
}
`;

const temporaryRoot = await mkdtemp(path.join(tmpdir(), "gaius-gpu-buffer-pool-"));
try {
  const patcherClasses = path.join(temporaryRoot, "patcher-classes");
  const patchedClasses = path.join(temporaryRoot, "patched-classes");
  const helperClasses = path.join(temporaryRoot, "helper-classes");
  const verifierClasses = path.join(temporaryRoot, "verifier-classes");
  const harnessClasses = path.join(temporaryRoot, "harness-classes");
  const sourceRoot = path.join(temporaryRoot, "src");
  const loadSourceDirectory = path.join(sourceRoot, "dev/gaius/smoke");
  const helperHarnessDirectory = path.join(sourceRoot, "dev/gaius/browser");
  await Promise.all([
    mkdir(patcherClasses, {recursive: true}),
    mkdir(patchedClasses, {recursive: true}),
    mkdir(helperClasses, {recursive: true}),
    mkdir(verifierClasses, {recursive: true}),
    mkdir(harnessClasses, {recursive: true}),
    mkdir(loadSourceDirectory, {recursive: true}),
    mkdir(helperHarnessDirectory, {recursive: true}),
  ]);

  const asmPatcherClasspath = [asm, asmTree].join(path.delimiter);
  const asmVerifierClasspath = [asm, asmTree, asmAnalysis].join(path.delimiter);
  const helperClasspath = [rawClient, ...teaVmJars].join(path.delimiter);

  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", asmPatcherClasspath,
    "-sourcepath", path.join(repositoryRoot, "port/tools/src/main/java"),
    "-d", patcherClasses,
    patcherSource,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const patchOutput = run(javaTools.java, [
    "-classpath", [patcherClasses, asmPatcherClasspath].join(path.delimiter),
    "dev.gaius.tools.MinecraftClientPatcher",
    rawClient,
    patchedClasses,
    CANONICAL_PROFILE.id,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.match(patchOutput,
    /Instrumented blaze3d StagedVertexBuffer GPU pool cache: count=4 bytes=1048576 idle=3/,
    "exact " + CANONICAL_PROFILE.id + " jar did not run the GPU buffer-pool patch");

  const patchedPoolClass = path.join(
    patchedClasses,
    "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool.class",
  );
  const forbiddenPendingClass = path.join(
    patchedClasses,
    "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool$PendingRecycle.class",
  );
  await access(patchedPoolClass);
  await assert.rejects(access(forbiddenPendingClass),
    "patcher must never emit or modify PendingRecycle.class");

  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", helperClasspath,
    "-d", helperClasses,
    helperSource,
    jsArraysSource,
  ], {stdio: ["ignore", "pipe", "pipe"]});

  const bytecodeVerifierFile = path.join(sourceRoot, "GpuBufferPoolBytecodeVerifier.java");
  const loadVerifierFile = path.join(
    loadSourceDirectory,
    "GpuBufferPoolPatchedLoadVerifier.java",
  );
  const helperHarnessFile = path.join(
    helperHarnessDirectory,
    "BrowserGpuBufferPoolCacheHarness.java",
  );
  await Promise.all([
    writeFile(bytecodeVerifierFile, bytecodeVerifierSource, "utf8"),
    writeFile(loadVerifierFile, verifyLoadSource, "utf8"),
    writeFile(helperHarnessFile, helperHarnessSource, "utf8"),
  ]);

  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", asmVerifierClasspath,
    "-d", verifierClasses,
    bytecodeVerifierFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const bytecodeOutput = run(javaTools.java, [
    "-classpath", [verifierClasses, asmVerifierClasspath].join(path.delimiter),
    "GpuBufferPoolBytecodeVerifier",
    rawClient,
    patchedPoolClass,
    path.join(helperClasses, "dev/gaius/browser/BrowserGpuBufferPoolCache.class"),
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.match(bytecodeOutput,
    /BASIC_VERIFIER_OK net\/minecraft\/client\/renderer\/StagedVertexBuffer\$GpuBufferPool/);
  assert.match(bytecodeOutput,
    /BASIC_VERIFIER_OK dev\/gaius\/browser\/BrowserGpuBufferPoolCache/);
  assert.match(bytecodeOutput,
    /GPU_POOL_CFG_OK field=final hooks=7 removeAt=2 ownerClose=3 rawPending=exact/);
  process.stdout.write(bytecodeOutput);

  const runtimeClasspath = [
    patchedClasses,
    helperClasses,
    rawClient,
    ...teaVmJars,
  ].join(path.delimiter);
  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", runtimeClasspath,
    "-d", harnessClasses,
    loadVerifierFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const loadOutput = run(javaTools.java, [
    "-Xverify:all",
    "-classpath", [harnessClasses, runtimeClasspath].join(path.delimiter),
    "dev.gaius.smoke.GpuBufferPoolPatchedLoadVerifier",
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.match(loadOutput,
    /XVERIFY_OK net\.minecraft\.client\.renderer\.StagedVertexBuffer\$GpuBufferPool/);
  process.stdout.write(loadOutput);

  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", [helperClasses, helperClasspath].join(path.delimiter),
    "-d", harnessClasses,
    helperHarnessFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const modelOutput = run(javaTools.java, [
    "-Xverify:all",
    "-classpath", [harnessClasses, helperClasses, helperClasspath].join(path.delimiter),
    "dev.gaius.browser.BrowserGpuBufferPoolCacheHarness",
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.match(modelOutput,
    /GPU_BUFFER_POOL_MODEL_OK frames=120 delay=2 count=4 bytes=1048576 ttl=3 batches=128\/1024 telemetry=4/);
  process.stdout.write(modelOutput);
} finally {
  await rm(temporaryRoot, {recursive: true, force: true});
}

console.log(JSON.stringify({
  status: "ok",
  profile: version,
  cache: {
    maxCount: 4,
    maxBytes: 1024 * 1024,
    maxIdleFrames: 3,
  },
  verification: [
    "exact-jar-patcher",
    "asm-basic-verifier",
    "asm-cfg",
    "jvm-xverify",
    "pure-java-model",
  ],
}));
