#!/usr/bin/env node

import {CANONICAL_PROFILE, LEGACY_PROFILE} from "./verification-profiles.mjs";
import {patcherSource as stepSource} from "./patcher-sources.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {createHash} from "node:crypto";
import {access, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = value => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = value => path.basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const overlayProfileId = process.env.GAIUS_OVERLAY_DIRECTORY
  ? profileIdFromPath(process.env.GAIUS_OVERLAY_DIRECTORY) : "";
const version = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH)
    : (/^\d+(?:\.\d+)+$/.test(overlayProfileId) ? overlayProfileId : CANONICAL_PROFILE.id));
if (version !== CANONICAL_PROFILE.id) {
  throw new Error(`MappableRingBuffer telemetry smoke is ${CANONICAL_PROFILE.id}-only; got ${version}`);
}

const clientCandidates = [
  process.env.GAIUS_CLIENT_NAMED_JAR && nativePath(process.env.GAIUS_CLIENT_NAMED_JAR),
  process.env.GAIUS_BUILD_ROOT
    && path.join(nativePath(process.env.GAIUS_BUILD_ROOT), "client-named.jar"),
  process.env.GAIUS_OVERLAY_DIRECTORY
    && path.join(nativePath(process.env.GAIUS_OVERLAY_DIRECTORY),
      "client-named-" + CANONICAL_PROFILE.id + "-gaius.jar"),
  path.join(repositoryRoot, "port/work/" + CANONICAL_PROFILE.id + "/client-named.jar"),
  path.join(repositoryRoot, "port/work/overlays/" + CANONICAL_PROFILE.id + "/client-named-" + CANONICAL_PROFILE.id + "-gaius.jar"),
  path.join(repositoryRoot, "port/work/overlays/client-named-" + CANONICAL_PROFILE.id + "-gaius.jar"),
].filter(Boolean);
let rawClient = null;
for (const candidate of clientCandidates) {
  try {
    await access(candidate);
    rawClient = candidate;
    break;
  } catch {
    // Try the next exact profile-scoped input.
  }
}
if (!rawClient) {
  throw new Error(`missing exact ${CANONICAL_PROFILE.id} client-named.jar; checked ${clientCandidates.join(", ")}`);
}
const rawClientSha256 = createHash("sha256")
  .update(await readFile(rawClient))
  .digest("hex");
console.log(`CLIENT_NAMED_SHA256 ${rawClientSha256} ${rawClient}`);
const raw121Client = path.join(repositoryRoot, "port/work/" + LEGACY_PROFILE.id + "/client-named.jar");
await access(raw121Client);
const raw121ClientSha256 = createHash("sha256")
  .update(await readFile(raw121Client))
  .digest("hex");
console.log(`CLIENT_NAMED_12111_SHA256 ${raw121ClientSha256} ${raw121Client}`);

const patcherSource = path.join(
  repositoryRoot,
  "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
);
const browserOpenGlSource = path.join(
  repositoryRoot,
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
);

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
    timeout: 180_000,
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
    const java = path.join(home, "bin/java");
    const javac = path.join(home, "bin/javac");
    if (!existsSync(java) && !existsSync(`${java}.exe`)) continue;
    try {
      const output = execFileSync(javac, ["-version"], {encoding: "utf8"});
      if (Number(output.match(/javac (\d+)/)?.[1]) >= 25) return {java, javac};
    } catch {
      // Try the next configured JDK.
    }
  }
  throw new Error("" + CANONICAL_PROFILE.id + " MappableRingBuffer JVM verification requires JDK 25 or newer");
}

const javaTools = selectJavaTools();
const asmRoot = path.join(homedir(), ".m2/repository/org/ow2/asm");
const asm = path.join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = path.join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = path.join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");
await Promise.all([
  access(rawClient),
  access(patcherSource),
  access(browserOpenGlSource),
  access(asm),
  access(asmTree),
  access(asmAnalysis),
]);

// MinecraftClientPatcher with its domain classes (port/tools/patchkit/README.md, "Domains"); the
// feature text is the telemetry patch method up to the next member of its class.
const patcherText = stepSource("MinecraftClientPatcher");
const browserOpenGlText = await readFile(browserOpenGlSource, "utf8");
const featureStart = patcherText.search(
  /\n    (?:private |public )?static void patchMappableRingBufferTelemetry\(/,
);
const nextMember = /\n    (?:(?:private|public|protected) )?static [^\n]*\(/g;
nextMember.lastIndex = featureStart + 1;
const nextMatch = featureStart >= 0 ? nextMember.exec(patcherText) : null;
const featureEnd = nextMatch ? nextMatch.index : -1;
assert.ok(featureStart >= 0 && featureEnd > featureStart,
  "missing isolated MappableRingBuffer telemetry patch");
const featureText = patcherText.slice(featureStart, featureEnd);
// The call in main() runs under the modern-profile guard (the profiles whose
// patchSet is modern, never 1.21.11), wrapped in PatchRegistry.run so a bring-up
// can skip it.
assert.match(patcherText,
  /static boolean isModernProfile\(String minecraftVersion\) \{\s*return VersionProfile\.forId\(minecraftVersion\)\.modernPatchSet\(\);\s*\}/,
  "isModernProfile must read the profile's patchSet");
// The registration's spec limits it to the modern profiles (from the canonical one on).
assert.match(patcherText,
  new RegExp(String.raw`PatchRegistry\.run\("MinecraftClientPatcher\.patchMappableRingBufferTelemetry", "TELEMETRY [a-z ]*>=${CANONICAL_PROFILE.id.replaceAll(".", "[.]")}", \(\) -> (?:TelemetryPatches\.)?patchMappableRingBufferTelemetry\(`),
  "MappableRingBuffer telemetry must remain gated to the modern profiles");
for (const required of [
  "currentBuffer",
  "Long.valueOf(Long.MAX_VALUE)",
  "awaitCompletion",
  "noteMappableRingCurrentBuffer",
  "noteMappableRingAwaitResult",
  "Opcodes.DUP",
  "Opcodes.POP",
  "writeComputeFrames",
]) {
  assert.ok(featureText.includes(required), `missing patcher contract: ${required}`);
}
for (const forbidden of [
  "setTimeout",
  "setInterval",
  "requestAnimationFrame",
  "CompletableFuture",
  "Executor",
  "new Thread",
  "Platform.schedule",
  "BrowserWorldgenScheduler",
  ".pulse(",
]) {
  assert.equal(featureText.includes(forbidden), false,
    `MappableRingBuffer patch contains forbidden runtime primitive: ${forbidden}`);
}
for (const required of [
  "mappableRingCurrentBufferCalls",
  "mappableRingFenceChecks",
  "mappableRingFenceReady",
  "mappableRingFencePending",
  "2147483647",
]) {
  assert.ok(browserOpenGlText.includes(required), `missing fixed scalar telemetry: ${required}`);
}

const bytecodeVerifierSource = String.raw`
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class MappableRingBufferBytecodeVerifier {
    private static final String OWNER = "net/minecraft/client/renderer/MappableRingBuffer";
    private static final String FENCE = "com/mojang/blaze3d/buffers/GpuFence";
    private static final String BUFFER = "com/mojang/blaze3d/buffers/GpuBuffer";
    private static final String TELEMETRY = "org/lwjgl/opengl/BrowserOpenGL";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static byte[] readJar(String jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(OWNER + ".class");
            check(entry != null, "raw jar is missing " + OWNER);
            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodNode currentBuffer(ClassNode node) {
        MethodNode result = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("currentBuffer")
                    && method.desc.equals("()L" + BUFFER + ";")) {
                check(result == null, "duplicate currentBuffer method");
                result = method;
            }
        }
        check(result != null, "missing currentBuffer method");
        return result;
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) result.add(instruction);
        }
        return result;
    }

    private static int targetIndex(List<AbstractInsnNode> instructions, JumpInsnNode jump) {
        AbstractInsnNode cursor = jump.label;
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return instructions.indexOf(cursor);
    }

    private static boolean equivalent(
            AbstractInsnNode raw,
            AbstractInsnNode patched,
            List<AbstractInsnNode> rawInstructions,
            List<AbstractInsnNode> patchedInstructions) {
        if (raw.getOpcode() != patched.getOpcode()) return false;
        if (raw instanceof VarInsnNode a && patched instanceof VarInsnNode b) {
            return a.var == b.var;
        }
        if (raw instanceof FieldInsnNode a && patched instanceof FieldInsnNode b) {
            return a.owner.equals(b.owner) && a.name.equals(b.name) && a.desc.equals(b.desc);
        }
        if (raw instanceof MethodInsnNode a && patched instanceof MethodInsnNode b) {
            return a.owner.equals(b.owner) && a.name.equals(b.name)
                    && a.desc.equals(b.desc) && a.itf == b.itf;
        }
        if (raw instanceof LdcInsnNode a && patched instanceof LdcInsnNode b) {
            return java.util.Objects.equals(a.cst, b.cst);
        }
        if (raw instanceof JumpInsnNode a && patched instanceof JumpInsnNode b) {
            int rawTarget = targetIndex(rawInstructions, a);
            int expectedPatchedTarget = rawTarget <= 10 ? rawTarget + 1 : rawTarget + 3;
            return targetIndex(patchedInstructions, b) == expectedPatchedTarget;
        }
        return raw.getClass() == patched.getClass();
    }

    private static int calls(
            List<AbstractInsnNode> instructions, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static int backwardJumps(List<AbstractInsnNode> instructions) {
        int count = 0;
        for (int index = 0; index < instructions.size(); index++) {
            if (instructions.get(index) instanceof JumpInsnNode jump
                    && targetIndex(instructions, jump) < index) count++;
        }
        return count;
    }

    private static int allocations(List<AbstractInsnNode> instructions) {
        int count = 0;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction.getOpcode() == Opcodes.NEW
                    || instruction.getOpcode() == Opcodes.NEWARRAY
                    || instruction.getOpcode() == Opcodes.ANEWARRAY
                    || instruction.getOpcode() == Opcodes.MULTIANEWARRAY) count++;
        }
        return count;
    }

    private static void basicVerify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
        System.out.println("BASIC_VERIFIER_OK " + node.name);
    }

    public static void main(String[] args) throws Exception {
        ClassNode raw = read(readJar(args[0]));
        ClassNode patched = read(Files.readAllBytes(Path.of(args[1])));
        check(raw.name.equals(OWNER) && patched.name.equals(OWNER), "wrong class identity");
        basicVerify(patched);

        MethodNode rawMethod = currentBuffer(raw);
        MethodNode patchedMethod = currentBuffer(patched);
        List<AbstractInsnNode> rawInstructions = executable(rawMethod);
        List<AbstractInsnNode> patchedInstructions = executable(patchedMethod);
        check(rawInstructions.size() == 26, "raw currentBuffer shape changed");
        check(patchedInstructions.size() == 29,
                "patched currentBuffer must add exactly three executable instructions");
        check(patchedInstructions.get(0) instanceof MethodInsnNode entry
                        && entry.getOpcode() == Opcodes.INVOKESTATIC
                        && entry.owner.equals(TELEMETRY)
                        && entry.name.equals("noteMappableRingCurrentBuffer")
                        && entry.desc.equals("()V"),
                "currentBuffer entry telemetry changed");
        check(patchedInstructions.get(11) instanceof MethodInsnNode await
                        && await.getOpcode() == Opcodes.INVOKEINTERFACE
                        && await.owner.equals(FENCE)
                        && await.name.equals("awaitCompletion")
                        && await.desc.equals("(J)Z"),
                "awaitCompletion position changed");
        check(patchedInstructions.get(10) instanceof LdcInsnNode timeout
                        && Long.valueOf(Long.MAX_VALUE).equals(timeout.cst),
                "awaitCompletion timeout changed");
        check(patchedInstructions.get(12).getOpcode() == Opcodes.DUP,
                "await result must be duplicated for telemetry");
        check(patchedInstructions.get(13) instanceof MethodInsnNode result
                        && result.getOpcode() == Opcodes.INVOKESTATIC
                        && result.owner.equals(TELEMETRY)
                        && result.name.equals("noteMappableRingAwaitResult")
                        && result.desc.equals("(Z)V"),
                "await result telemetry changed");
        check(patchedInstructions.get(14).getOpcode() == Opcodes.POP,
                "original await boolean is no longer discarded");

        for (int rawIndex = 0; rawIndex < rawInstructions.size(); rawIndex++) {
            int patchedIndex = rawIndex <= 10 ? rawIndex + 1 : rawIndex + 3;
            check(equivalent(
                            rawInstructions.get(rawIndex),
                            patchedInstructions.get(patchedIndex),
                            rawInstructions,
                            patchedInstructions),
                    "non-telemetry instruction changed raw=" + rawIndex
                            + " patched=" + patchedIndex);
        }
        check(calls(patchedInstructions, TELEMETRY,
                "noteMappableRingCurrentBuffer", "()V") == 1,
                "entry telemetry call count changed");
        check(calls(patchedInstructions, TELEMETRY,
                "noteMappableRingAwaitResult", "(Z)V") == 1,
                "result telemetry call count changed");
        check(calls(patchedInstructions, FENCE, "awaitCompletion", "(J)Z") == 1,
                "awaitCompletion count changed");
        check(calls(patchedInstructions, FENCE, "close", "()V") == 1,
                "fence close count changed");
        check(backwardJumps(rawInstructions) == backwardJumps(patchedInstructions),
                "telemetry introduced a loop/backedge");
        check(allocations(rawInstructions) == allocations(patchedInstructions),
                "telemetry introduced runtime allocation bytecode");
        for (AbstractInsnNode instruction : patchedInstructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            String signature = call.owner + "." + call.name;
            check(!signature.contains("setTimeout")
                            && !signature.contains("setInterval")
                            && !signature.contains("requestAnimationFrame")
                            && !call.owner.contains("Thread")
                            && !call.owner.contains("Executor")
                            && !call.owner.contains("CompletableFuture"),
                    "telemetry introduced async runtime call " + signature);
        }
        check(patchedMethod.tryCatchBlocks.size() == rawMethod.tryCatchBlocks.size(),
                "telemetry changed exception ownership");
        System.out.println(
                "MAPPABLE_RING_CFG_OK entry=1 await=1 result=dup-note-pop close=1 null=unchanged return=unchanged");
    }
}
`;

const browserOpenGlStubSource = String.raw`
package org.lwjgl.opengl;

public final class BrowserOpenGL {
    private BrowserOpenGL() {}
    public static void noteMappableRingCurrentBuffer() {}
    public static void noteMappableRingAwaitResult(boolean ready) {}
}
`;

const loadVerifierSource = String.raw`
package dev.gaius.smoke;

public final class MappableRingBufferPatchedLoadVerifier {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = MappableRingBufferPatchedLoadVerifier.class.getClassLoader();
        Class<?> ring = Class.forName(
                "net.minecraft.client.renderer.MappableRingBuffer", false, loader);
        Class<?> buffer = Class.forName(
                "com.mojang.blaze3d.buffers.GpuBuffer", false, loader);
        if (ring.getDeclaredMethod("currentBuffer").getReturnType() != buffer) {
            throw new AssertionError("currentBuffer JVM signature changed");
        }
        System.out.println("XVERIFY_OK " + ring.getName());
    }
}
`;

const temporaryRoot = await mkdtemp(path.join(tmpdir(), "gaius-mappable-ring-"));
try {
  const patcherClasses = path.join(temporaryRoot, "patcher-classes");
  const patchedClasses = path.join(temporaryRoot, "patched-classes");
  const verifierClasses = path.join(temporaryRoot, "verifier-classes");
  const stubClasses = path.join(temporaryRoot, "stub-classes");
  const loadClasses = path.join(temporaryRoot, "load-classes");
  const sourceRoot = path.join(temporaryRoot, "src");
  const stubSourceDirectory = path.join(sourceRoot, "org/lwjgl/opengl");
  const loadSourceDirectory = path.join(sourceRoot, "dev/gaius/smoke");
  await Promise.all([
    mkdir(patcherClasses, {recursive: true}),
    mkdir(patchedClasses, {recursive: true}),
    mkdir(verifierClasses, {recursive: true}),
    mkdir(stubClasses, {recursive: true}),
    mkdir(loadClasses, {recursive: true}),
    mkdir(stubSourceDirectory, {recursive: true}),
    mkdir(loadSourceDirectory, {recursive: true}),
  ]);

  const asmPatcherClasspath = [asm, asmTree].join(path.delimiter);
  const asmVerifierClasspath = [asm, asmTree, asmAnalysis].join(path.delimiter);
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
    /Instrumented blaze3d MappableRingBuffer\.currentBuffer fence-result telemetry/,
    "exact 26.2 jar did not run the MappableRingBuffer telemetry patch");

  const patched121Classes = path.join(temporaryRoot, "patched-12111-classes");
  await mkdir(patched121Classes, {recursive: true});
  const patch121Output = run(javaTools.java, [
    "-classpath", [patcherClasses, asmPatcherClasspath].join(path.delimiter),
    "dev.gaius.tools.MinecraftClientPatcher",
    raw121Client,
    patched121Classes,
    LEGACY_PROFILE.id,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.doesNotMatch(patch121Output, /MappableRingBuffer\.currentBuffer/,
    "1.21.11 unexpectedly ran the 26.2 MappableRingBuffer patch");
  await assert.rejects(
    access(path.join(
      patched121Classes,
      "net/minecraft/client/renderer/MappableRingBuffer.class",
    )),
    "1.21.11 unexpectedly emitted a patched MappableRingBuffer.class",
  );

  const patchedRingClass = path.join(
    patchedClasses,
    "net/minecraft/client/renderer/MappableRingBuffer.class",
  );
  await access(patchedRingClass);
  const verifierFile = path.join(sourceRoot, "MappableRingBufferBytecodeVerifier.java");
  const stubFile = path.join(stubSourceDirectory, "BrowserOpenGL.java");
  const loadFile = path.join(
    loadSourceDirectory,
    "MappableRingBufferPatchedLoadVerifier.java",
  );
  await Promise.all([
    writeFile(verifierFile, bytecodeVerifierSource, "utf8"),
    writeFile(stubFile, browserOpenGlStubSource, "utf8"),
    writeFile(loadFile, loadVerifierSource, "utf8"),
  ]);

  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", asmVerifierClasspath,
    "-d", verifierClasses,
    verifierFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const bytecodeOutput = run(javaTools.java, [
    "-classpath", [verifierClasses, asmVerifierClasspath].join(path.delimiter),
    "MappableRingBufferBytecodeVerifier",
    rawClient,
    patchedRingClass,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.match(bytecodeOutput,
    /BASIC_VERIFIER_OK net\/minecraft\/client\/renderer\/MappableRingBuffer/);
  assert.match(bytecodeOutput,
    /MAPPABLE_RING_CFG_OK entry=1 await=1 result=dup-note-pop close=1 null=unchanged return=unchanged/);
  process.stdout.write(bytecodeOutput);

  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-d", stubClasses,
    stubFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const runtimeClasspath = [
    patchedClasses,
    stubClasses,
    rawClient,
  ].join(path.delimiter);
  run(javaTools.javac, [
    "--release", "21", "-proc:none",
    "-classpath", runtimeClasspath,
    "-d", loadClasses,
    loadFile,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const loadOutput = run(javaTools.java, [
    "-Xverify:all",
    "-classpath", [loadClasses, runtimeClasspath].join(path.delimiter),
    "dev.gaius.smoke.MappableRingBufferPatchedLoadVerifier",
  ], {stdio: ["ignore", "pipe", "pipe"]});
  assert.match(loadOutput,
    /XVERIFY_OK net\.minecraft\.client\.renderer\.MappableRingBuffer/);
  process.stdout.write(loadOutput);
} finally {
  await rm(temporaryRoot, {recursive: true, force: true});
}

console.log(JSON.stringify({
  status: "ok",
  profile: version,
  input: {
    clientNamedJar: rawClient,
    sha256: rawClientSha256,
    companion12111Sha256: raw121ClientSha256,
  },
  telemetry: {
    currentCalls: "mappableRingCurrentBufferCalls",
    checks: "mappableRingFenceChecks",
    ready: "mappableRingFenceReady",
    pending: "mappableRingFencePending",
    boundedScalarsOnly: true,
  },
  verification: [
    "exact-input-shape",
    "exact-26.2-profile-gate",
    "1.21.11-no-hook",
    "asm-basic-verifier",
    "asm-cfg-equivalence",
    "jvm-xverify",
    "no-runtime-loop-timer-thread-allocation",
  ],
}, null, 2));
