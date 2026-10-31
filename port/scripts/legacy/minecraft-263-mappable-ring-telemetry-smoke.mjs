#!/usr/bin/env node
// Minecraft 26.3 MappableRingBuffer fence telemetry smoke (work package P5).
//
// 26.3 types the ring with the renderpearl GpuBuffer/GpuFence interfaces and waits with
// GpuFence.NO_TIMEOUT (-1L) instead of Long.MAX_VALUE. BrowserOpenGL polls every client wait
// with a zero timeout, so only the telemetry-visible constant changed.
//
// Checks, on the vanilla 26.3 client-named.jar:
//   1. MinecraftClientPatcher.patchMappableRingBufferTelemetry takes its 26.3 branch;
//   2. ASM BasicVerifier, and the patched currentBuffer equals the vanilla method plus exactly
//      three instructions (entry note, dup + result note after awaitCompletion), keeping -1L;
//   3. on the JVM (-Xverify:all) the patched method runs against mock fences: no-fence, pending
//      and ready slots each report once, the wait uses -1L, and the fence is closed and cleared.

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, mkdir, mkdtemp, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const rawClient = nativePath(process.env.GAIUS_CLIENT_NAMED_JAR)
  || path.join(repositoryRoot, "port/work/26.3/client-named.jar");
const toolsSource = path.join(repositoryRoot, "port/tools/src/main/java");
const patcherSource = path.join(toolsSource, "dev/gaius/tools/MinecraftClientPatcher.java");

function selectJdk() {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const base = nativePath(home);
    try {
      const output = execFileSync(path.join(base, "bin/javac"), ["-version"], {encoding: "utf8"});
      if (Number(output.match(/javac (\d+)/)?.[1]) >= 25) {
        return {java: path.join(base, "bin/java"), javac: path.join(base, "bin/javac")};
      }
    } catch {
      // Try the next configured JDK.
    }
  }
  throw new Error("26.3 MappableRingBuffer smoke requires JDK 25 (GAIUS_JAVA_HOME or JAVA_HOME)");
}

const tools = selectJdk();
const asm = ["asm", "asm-tree", "asm-analysis"].map((artifact) =>
  path.join(homedir(), `.m2/repository/org/ow2/asm/${artifact}/9.8/${artifact}-9.8.jar`));
await Promise.all([rawClient, patcherSource, ...asm].map((file) => access(file)));

const driverSource = String.raw`
import java.lang.reflect.Method;
import java.nio.file.Path;

public final class P5PatchDriver {
    public static void main(String[] args) throws Exception {
        Method method = Class.forName("dev.gaius.tools.patches.telemetry.TelemetryPatches")
                .getDeclaredMethod(args[0], String.class, Path.class);
        method.setAccessible(true);
        method.invoke(null, args[1], Path.of(args[2]));
    }
}
`;

const verifierSource = String.raw`
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
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class Ring263Verifier {
    static final String OWNER = "net/minecraft/client/renderer/MappableRingBuffer";
    static final String TELEMETRY = "org/lwjgl/opengl/BrowserOpenGL";

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static MethodNode currentBuffer(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("currentBuffer")
                    && method.desc.equals("()Lcom/mojang/renderpearl/api/buffers/GpuBuffer;")) return method;
        }
        throw new AssertionError("missing 26.3 currentBuffer");
    }

    static List<String> canonical(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            String operand = "";
            if (instruction instanceof MethodInsnNode call) {
                operand = call.owner + "." + call.name + call.desc + ":" + call.itf;
            } else if (instruction instanceof FieldInsnNode field) {
                operand = field.owner + "." + field.name + ":" + field.desc;
            } else if (instruction instanceof VarInsnNode variable) {
                operand = Integer.toString(variable.var);
            } else if (instruction instanceof LdcInsnNode constant) {
                operand = constant.cst.getClass().getSimpleName() + ":" + constant.cst;
            } else if (instruction instanceof IntInsnNode integer) {
                operand = Integer.toString(integer.operand);
            } else if (instruction instanceof TypeInsnNode type) {
                operand = type.desc;
            }
            result.add(opcode + " " + operand);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        ClassNode raw = new ClassNode();
        try (ZipFile jar = new ZipFile(args[0])) {
            new ClassReader(jar.getInputStream(jar.getEntry(OWNER + ".class")).readAllBytes()).accept(raw, 0);
        }
        ClassNode patched = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[1]))).accept(patched, 0);
        for (MethodNode method : patched.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(patched.name, method);
        }
        System.out.println("BASIC_VERIFIER_OK " + patched.name);
        List<String> vanilla = canonical(currentBuffer(raw));
        List<String> instrumented = canonical(currentBuffer(patched));
        check(vanilla.contains(Opcodes.LDC + " Long:-1"), "vanilla 26.3 must wait with NO_TIMEOUT (-1L)");
        String entry = Opcodes.INVOKESTATIC + " " + TELEMETRY + ".noteMappableRingCurrentBuffer()V:false";
        String result = Opcodes.INVOKESTATIC + " " + TELEMETRY + ".noteMappableRingAwaitResult(Z)V:false";
        check(instrumented.size() == vanilla.size() + 3, "instrumentation must add exactly 3 instructions");
        check(instrumented.get(0).equals(entry), "entry telemetry must be the first instruction");
        int await = vanilla.indexOf(Opcodes.INVOKEINTERFACE
                + " com/mojang/renderpearl/api/commands/GpuFence.awaitCompletion(J)Z:true");
        check(await > 0, "vanilla awaitCompletion missing");
        List<String> expected = new ArrayList<>();
        expected.add(entry);
        expected.addAll(vanilla.subList(0, await + 1));
        expected.add(Opcodes.DUP + " ");
        expected.add(result);
        expected.addAll(vanilla.subList(await + 1, vanilla.size()));
        check(expected.equals(instrumented), "patched currentBuffer is not vanilla + 3 telemetry instructions:\n"
                + expected + "\n" + instrumented);
        System.out.println("MAPPABLE_RING_263_CFG_OK entry=1 dup-note=1 timeout=-1 rest=vanilla");
    }
}
`;

const telemetryStubSource = String.raw`
package org.lwjgl.opengl;

public final class BrowserOpenGL {
    public static int currentCalls;
    public static int ready;
    public static int pending;
    private BrowserOpenGL() {}
    public static void noteMappableRingCurrentBuffer() { currentCalls++; }
    public static void noteMappableRingAwaitResult(boolean result) { if (result) ready++; else pending++; }
}
`;

const harnessSource = String.raw`
package dev.gaius.smoke;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.opengl.BrowserOpenGL;
import sun.misc.Unsafe;

public final class Ring263Harness {
    static final class Buffer implements GpuBuffer {
        @Override public long size() { return 256; }
        @Override public int usage() { return 2; }
        @Override public boolean isClosed() { return false; }
        @Override public void close() {}
        @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new UnsupportedOperationException();
        }
    }

    static final class Fence implements GpuFence {
        final boolean signalled;
        long timeout = Long.MIN_VALUE;
        int closes;
        Fence(boolean signalled) { this.signalled = signalled; }
        @Override public boolean awaitCompletion(long value) { timeout = value; return signalled; }
        @Override public void close() { closes++; }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Class<?> ringClass = Class.forName("net.minecraft.client.renderer.MappableRingBuffer");
        // The constructor needs RenderSystem's device; the smoke only exercises currentBuffer.
        Object ring = unsafe.allocateInstance(ringClass);
        GpuBuffer[] buffers = {new Buffer(), new Buffer(), new Buffer()};
        GpuFence[] fences = new GpuFence[3];
        set(ringClass, ring, "buffers", buffers);
        set(ringClass, ring, "fences", fences);
        Method currentBuffer = ringClass.getDeclaredMethod("currentBuffer");
        Field current = ringClass.getDeclaredField("current");
        current.setAccessible(true);

        current.setInt(ring, 0);
        check(currentBuffer.invoke(ring) == buffers[0], "no-fence slot returned the wrong buffer");
        check(BrowserOpenGL.currentCalls == 1 && BrowserOpenGL.ready == 0 && BrowserOpenGL.pending == 0,
                "no-fence slot must only count the call");

        Fence pending = new Fence(false);
        fences[1] = pending;
        current.setInt(ring, 1);
        check(currentBuffer.invoke(ring) == buffers[1], "pending slot returned the wrong buffer");
        check(pending.timeout == -1L, "26.3 must wait with GpuFence.NO_TIMEOUT, got " + pending.timeout);
        check(pending.closes == 1 && fences[1] == null, "pending fence must still be closed and cleared");
        check(BrowserOpenGL.currentCalls == 2 && BrowserOpenGL.pending == 1, "pending result not reported");

        Fence ready = new Fence(true);
        fences[2] = ready;
        current.setInt(ring, 2);
        check(currentBuffer.invoke(ring) == buffers[2], "ready slot returned the wrong buffer");
        check(ready.closes == 1 && fences[2] == null, "ready fence must be closed and cleared");
        check(BrowserOpenGL.currentCalls == 3 && BrowserOpenGL.ready == 1 && BrowserOpenGL.pending == 1,
                "ready result not reported exactly once");
        System.out.println("MAPPABLE_RING_263_RUNTIME_OK calls=3 ready=1 pending=1 timeout=-1");
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
`;

function run(command, args) {
  return execFileSync(command, args,
    {cwd: repositoryRoot, encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 300_000});
}

const root = await mkdtemp(path.join(tmpdir(), "gaius-263-mappable-ring-"));
try {
  const directories = Object.fromEntries(["patcher", "patched", "verifier", "stub", "harness", "src"]
    .map((name) => [name, path.join(root, name)]));
  await Promise.all(Object.values(directories).map((directory) => mkdir(directory, {recursive: true})));
  const asmPatcher = asm.slice(0, 2).join(path.delimiter);
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath", asmPatcher,
    "-sourcepath", toolsSource, "-d", directories.patcher, patcherSource]);
  const driverFile = path.join(directories.src, "P5PatchDriver.java");
  await writeFile(driverFile, driverSource, "utf8");
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath",
    [directories.patcher, asmPatcher].join(path.delimiter), "-d", directories.patcher, driverFile]);
  const patchedRing = path.join(directories.patched, "net/minecraft/client/renderer/MappableRingBuffer.class");
  const patchOutput = run(tools.java, ["-classpath", [directories.patcher, asmPatcher].join(path.delimiter),
    "P5PatchDriver", "patchMappableRingBufferTelemetry", rawClient, patchedRing]);
  assert.match(patchOutput, /Instrumented renderpearl MappableRingBuffer\.currentBuffer fence-result telemetry/);

  const verifierFile = path.join(directories.src, "Ring263Verifier.java");
  await writeFile(verifierFile, verifierSource, "utf8");
  const asmAll = asm.join(path.delimiter);
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath", asmAll, "-d", directories.verifier,
    verifierFile]);
  const verifierOutput = run(tools.java, ["-classpath", [directories.verifier, asmAll].join(path.delimiter),
    "Ring263Verifier", rawClient, patchedRing]);
  assert.match(verifierOutput, /MAPPABLE_RING_263_CFG_OK/);
  process.stdout.write(verifierOutput);

  const stubDirectory = path.join(directories.src, "org/lwjgl/opengl");
  const harnessDirectory = path.join(directories.src, "dev/gaius/smoke");
  await mkdir(stubDirectory, {recursive: true});
  await mkdir(harnessDirectory, {recursive: true});
  const stubFile = path.join(stubDirectory, "BrowserOpenGL.java");
  const harnessFile = path.join(harnessDirectory, "Ring263Harness.java");
  await writeFile(stubFile, telemetryStubSource, "utf8");
  await writeFile(harnessFile, harnessSource, "utf8");
  run(tools.javac, ["--release", "21", "-proc:none", "-d", directories.stub, stubFile]);
  const runtime = [directories.patched, directories.stub, rawClient].join(path.delimiter);
  run(tools.javac, ["-proc:none", "-classpath", runtime, "-d", directories.harness, harnessFile]);
  const runtimeOutput = run(tools.java, ["-Xverify:all", "--sun-misc-unsafe-memory-access=allow",
    "-classpath", [directories.harness, runtime].join(path.delimiter), "dev.gaius.smoke.Ring263Harness"]);
  assert.match(runtimeOutput, /MAPPABLE_RING_263_RUNTIME_OK calls=3 ready=1 pending=1 timeout=-1/);
  process.stdout.write(runtimeOutput);
} finally {
  await rm(root, {recursive: true, force: true});
}

console.log(JSON.stringify({
  status: "ok",
  profile: "26.3",
  verification: ["exact-jar-patch", "asm-basic-verifier", "asm-vanilla-plus-3", "jvm-xverify-runtime",
    "no-timeout-constant"],
}));
