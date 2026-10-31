#!/usr/bin/env node
// Minecraft 26.3 StagedVertexBuffer GPU buffer-pool cache smoke (work package P5).
//
// 26.3 moved GpuBufferPool.tryRecycleBuffers() from the start of acquire() to the tail of
// endFrame(), and GpuBuffer/GpuFence/GpuDevice became renderpearl interfaces. The patch now
// advances the cache frame (helper.endFrame) right before that tail sweep, so buffers adopted by
// the sweep are stamped with the new frame instead of aging one frame on arrival.
//
// Checks, on the vanilla 26.3 client-named.jar:
//   1. MinecraftClientPatcher.patchStagedVertexBufferGpuPoolCache runs on the exact jar;
//   2. ASM BasicVerifier + CFG order of every hook (acquire, recycle lambda, takeBest, endFrame
//      tail, close), and that PendingRecycle is never emitted;
//   3. the real patched pool, the 26.3 BrowserGpuBufferPoolCache and vanilla PendingRecycle run
//      on the JVM (-Xverify:all) against mock renderpearl devices for 120 frames with a fence
//      delay: adopted buffers carry the current frame, the frame counter advances once per
//      endFrame, the count/byte budget holds after every acquire, no unsignalled or closed
//      buffer is handed out, and every created buffer is closed exactly once.

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {patcherSource as stepSource} from "../patcher-sources.mjs";
import {RENDERPEARL_PROFILE} from "../verification-profiles.mjs";
import {sourceFile} from "../../tools/sourcesets/sourcesets.mjs";

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
// BrowserGpuBufferPoolCache is one source in port/src/main; the renderpearl profile compiles it
// with its GpuBuffer relocation applied, so the compile step below uses that staged text.
const helperRelative = "dev/gaius/browser/BrowserGpuBufferPoolCache.java";
const helperSource = path.join(repositoryRoot, "port/src/main/java", helperRelative);
// The helper packs its telemetry with dev.gaius.browser.JsArrays (classlib overlay); it
// compiles with the JavaScript implementation.
const jsArraysSource = path.join(repositoryRoot,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java");
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
  throw new Error("26.3 GPU buffer-pool smoke requires JDK 25 (GAIUS_JAVA_HOME or JAVA_HOME)");
}

const tools = selectJdk();
const maven = path.join(homedir(), ".m2/repository");
const asm = ["asm", "asm-tree", "asm-analysis"].map((artifact) =>
  path.join(maven, `org/ow2/asm/${artifact}/9.8/${artifact}-9.8.jar`));
const teaVm = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-platform"].map((artifact) =>
  path.join(maven, `org/teavm/${artifact}/0.15.0/${artifact}-0.15.0.jar`));
await Promise.all([rawClient, helperSource, jsArraysSource, patcherSource, ...asm, ...teaVm]
  .map((file) => access(file)));

// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcherText = stepSource("MinecraftClientPatcher");
const helperProfileSource = sourceFile(RENDERPEARL_PROFILE.id, "src", helperRelative);
assert.ok(helperProfileSource && helperProfileSource.origin === "shared",
  "the renderpearl profile compiles the shared BrowserGpuBufferPoolCache");
const helperText = helperProfileSource.text;
const feature = patcherText.slice(
  patcherText.indexOf("static void patchStagedVertexBufferGpuPoolCache("),
  patcherText.indexOf("static void patchGlDevice("));
for (const contract of ["recycleAtEndFrame", "requireStagedPoolRecycleSite",
  "isStagedPoolEndFrameTail", 'symbols.renderType("com/mojang/blaze3d/buffers/GpuFence")',
  "recycleAtEndFrame ? afterAvailableClear : endFrameReturn"]) {
  assert.ok(feature.includes(contract), `missing 26.3 pool patch contract: ${contract}`);
}
assert.ok(helperText.includes("import com.mojang.renderpearl.api.buffers.GpuBuffer;"),
  "26.3 cache helper must use the renderpearl GpuBuffer interface");
assert.equal(helperText.includes("awaitCompletion"), false, "cache helper must never wait");

const driverSource = String.raw`
import java.lang.reflect.Method;
import java.nio.file.Path;

public final class P5PatchDriver {
    public static void main(String[] args) throws Exception {
        Method method = Class.forName("dev.gaius.tools.patches.render.RenderPatches")
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class Pool263Verifier {
    static final String OWNER = "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool";
    static final String HELPER = "dev/gaius/browser/BrowserGpuBufferPoolCache";
    static final String BUFFER = "com/mojang/renderpearl/api/buffers/GpuBuffer";
    static final String DEVICE = "com/mojang/renderpearl/api/device/GpuDevice";

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        throw new AssertionError("missing " + name + desc);
    }

    static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + "." + call.name);
        }
        return result;
    }

    static void inOrder(List<String> calls, String... expected) {
        int cursor = -1;
        for (String step : expected) {
            int next = -1;
            for (int index = cursor + 1; index < calls.size(); index++) {
                if (calls.get(index).equals(step)) { next = index; break; }
            }
            check(next > cursor, "hook out of order or missing: " + step + " in " + calls);
            cursor = next;
        }
    }

    public static void main(String[] args) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(node, 0);
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
        System.out.println("BASIC_VERIFIER_OK " + node.name);
        FieldNode cache = null;
        for (FieldNode field : node.fields) if (field.name.equals("gaius$browserCache")) cache = field;
        check(cache != null && cache.desc.equals("L" + HELPER + ";")
                && (cache.access & Opcodes.ACC_FINAL) != 0, "cache field shape");

        List<String> acquire = calls(method(node, "acquire", "(L" + DEVICE + ";I)L" + BUFFER + ";"));
        check(!acquire.contains(OWNER + ".tryRecycleBuffers"), "26.3 acquire gained a recycle sweep");
        inOrder(acquire, HELPER + ".beforeAcquire", "net/minecraft/util/Mth.roundToward",
                HELPER + ".afterRecycleSweep", OWNER + ".takeBestAvailable",
                DEVICE + ".createBuffer", HELPER + ".recordCreate", "java/util/List.add",
                HELPER + ".afterAcquire");

        MethodNode endFrameNode = method(node, "endFrame", "(L" + DEVICE + ";)V");
        List<String> endFrame = calls(endFrameNode);
        inOrder(endFrame, DEVICE + ".createCommandEncoder",
                "com/mojang/renderpearl/api/commands/CommandEncoder.createFence",
                "java/util/List.copyOf", OWNER + "$PendingRecycle.<init>", "java/util/List.add",
                "java/util/List.clear", "java/util/List.size", HELPER + ".endFrame",
                OWNER + ".tryRecycleBuffers");
        check(!endFrame.contains("java/util/List.forEach"), "endFrame still closes available buffers");
        check(endFrame.get(endFrame.size() - 1).equals(OWNER + ".tryRecycleBuffers"),
                "recycle sweep must stay the endFrame tail");
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode instruction : endFrameNode.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(OWNER)) fields.add(field.name);
        }
        check(fields.equals(List.of("usedThisFrame", "pendingRecycle", "usedThisFrame",
                "usedThisFrame", "gaius$browserCache", "pendingRecycle")), "endFrame fields " + fields);

        MethodNode lambda = null;
        for (MethodNode candidate : node.methods) {
            if (calls(candidate).contains(OWNER + "$PendingRecycle.tryRecycle")) lambda = candidate;
        }
        check(lambda != null, "recycle lambda missing");
        List<String> recycle = calls(lambda);
        check(recycle.contains(HELPER + ".recycleResult") && !recycle.contains("java/util/List.addAll"),
                "recycle lambda must hand ready batches to the cache instead of available.addAll");
        List<String> takeBest = calls(method(node, "takeBestAvailable", "(II)L" + BUFFER + ";"));
        check(takeBest.stream().filter((call) -> call.equals(HELPER + ".removeAt")).count() == 2
                && !takeBest.contains("java/util/List.remove"), "takeBest removal delegation");
        List<String> close = calls(method(node, "close", "()V"));
        check(close.get(close.size() - 1).equals(HELPER + ".ownerClosed")
                && close.stream().filter((call) -> call.equals("java/util/List.clear")).count() == 3,
                "ownerClosed must follow the three owner clears");
        System.out.println("GPU_POOL_263_CFG_OK hooks=acquire4+endFrame1+recycle1+removeAt2+close1 tail=tryRecycleBuffers");
    }
}
`;

const harnessSource = String.raw`
package dev.gaius.browser;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class Pool263Harness {
    static int frame;
    static final int FENCE_DELAY = 2;
    static final List<MockBuffer> created = new ArrayList<>();
    /** Handed out and not yet seen back in the available list (fence not adopted yet). */
    static final Map<MockBuffer, Integer> inFlight = new IdentityHashMap<>();

    static final class MockBuffer implements GpuBuffer {
        final long size;
        int closeCalls;
        MockBuffer(long size) { this.size = size; }
        @Override public long size() { return size; }
        @Override public int usage() { return 40; }
        @Override public boolean isClosed() { return closeCalls > 0; }
        @Override public void close() { closeCalls++; }
        @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new UnsupportedOperationException();
        }
    }

    static final class MockFence implements GpuFence {
        final int signalledFrame;
        int closes;
        MockFence(int signalledFrame) { this.signalledFrame = signalledFrame; }
        @Override public boolean awaitCompletion(long timeout) {
            check(timeout == 0L, "PendingRecycle must poll with a zero timeout");
            return frame >= signalledFrame;
        }
        @Override public void close() { closes++; }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static GpuDevice device() {
        CommandEncoder encoder = (CommandEncoder) Proxy.newProxyInstance(
                Pool263Harness.class.getClassLoader(), new Class<?>[] {CommandEncoder.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("createFence")) return new MockFence(frame + FENCE_DELAY);
                    throw new UnsupportedOperationException(method.getName());
                });
        return (GpuDevice) Proxy.newProxyInstance(
                Pool263Harness.class.getClassLoader(), new Class<?>[] {GpuDevice.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("createCommandEncoder")) return encoder;
                    if (method.getName().equals("createBuffer") && args[2] instanceof Long size) {
                        MockBuffer buffer = new MockBuffer(size);
                        created.add(buffer);
                        return buffer;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> "mock-" + System.identityHashCode(proxy);
        };
    }

    public static void main(String[] args) throws Exception {
        Class<?> poolClass = Class.forName(
                "net.minecraft.client.renderer.StagedVertexBuffer$GpuBufferPool");
        Constructor<?> constructor = poolClass.getDeclaredConstructor(Supplier.class, int.class);
        constructor.setAccessible(true);
        Supplier<String> label = () -> "smoke-pool";
        Object pool = constructor.newInstance(label, 40);
        Field cacheField = poolClass.getDeclaredField("gaius$browserCache");
        cacheField.setAccessible(true);
        Field availableField = poolClass.getDeclaredField("available");
        availableField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<GpuBuffer> available = (List<GpuBuffer>) availableField.get(pool);
        check(cacheField.get(pool) instanceof BrowserGpuBufferPoolCache, "constructor did not install the cache");
        // The JVM has no WebGL context; use the helper's test constructor with a live probe.
        BrowserGpuBufferPoolCache cache = new BrowserGpuBufferPoolCache(available, label, 40, () -> true);
        cacheField.set(pool, cache);
        Field frameField = BrowserGpuBufferPoolCache.class.getDeclaredField("frame");
        frameField.setAccessible(true);
        Field stampsField = BrowserGpuBufferPoolCache.class.getDeclaredField("availableFrames");
        stampsField.setAccessible(true);
        Method acquire = poolClass.getDeclaredMethod("acquire", GpuDevice.class, int.class);
        Method endFrame = poolClass.getDeclaredMethod("endFrame", GpuDevice.class);
        Method close = poolClass.getDeclaredMethod("close");
        // GpuBufferPool is package-private; its public methods still need accessibility.
        acquire.setAccessible(true);
        endFrame.setAccessible(true);
        close.setAccessible(true);
        GpuDevice device = device();

        int[] requests = {200_000, 262_144, 300_000, 100_000};
        int handedOut = 0;
        int adopted = 0;
        long peakAfterAcquire = 0;
        for (frame = 0; frame < 120; frame++) {
            int perFrame = 1 + (frame % 3);
            for (int index = 0; index < perFrame; index++) {
                int request = requests[(frame + index) % requests.length];
                MockBuffer buffer = (MockBuffer) acquire.invoke(pool, device, request);
                handedOut++;
                check(!buffer.isClosed(), "acquire handed out a closed buffer");
                check(buffer.size >= request && buffer.size % 262_144 == 0, "acquire size contract");
                check(inFlight.put(buffer, frame) == null,
                        "acquire handed out a buffer whose fence was not signalled yet");
                check(cache.availableCountForTesting() <= BrowserGpuBufferPoolCache.MAX_COUNT
                        && cache.availableBytesForTesting() <= BrowserGpuBufferPoolCache.MAX_BYTES,
                        "retention budget exceeded after acquire");
                peakAfterAcquire = Math.max(peakAfterAcquire, cache.availableBytesForTesting());
            }
            List<GpuBuffer> before = new ArrayList<>(available);
            int frameBefore = (Integer) frameField.get(cache);
            endFrame.invoke(pool, device);
            int frameAfter = (Integer) frameField.get(cache);
            check(frameAfter == frameBefore + 1, "cache frame must advance exactly once per endFrame");
            int[] stamps = (int[]) stampsField.get(cache);
            for (int index = 0; index < available.size(); index++) {
                GpuBuffer buffer = available.get(index);
                check(!buffer.isClosed(), "available holds a closed buffer");
                boolean fresh = before.stream().noneMatch((old) -> old == buffer);
                if (fresh) {
                    adopted++;
                    check(stamps[index] == frameAfter,
                            "buffer adopted at the endFrame tail was stamped " + stamps[index]
                                    + " instead of the new frame " + frameAfter);
                    Integer handedOutAt = inFlight.remove((MockBuffer) buffer);
                    check(handedOutAt != null && frame >= handedOutAt + FENCE_DELAY,
                            "a buffer was adopted before its fence signalled");
                }
                check(frameAfter - stamps[index] <= BrowserGpuBufferPoolCache.MAX_IDLE_FRAMES,
                        "available kept a buffer past its idle TTL");
            }
        }
        long reused = cache.reuseCallsForTesting();
        check(adopted > 0 && reused > 0, "the pool never reused a signalled buffer");
        check(created.size() < handedOut / 2, "the cache did not reduce buffer creation: created="
                + created.size() + " handedOut=" + handedOut);
        check(cache.anomalyDropsForTesting() == 0 && cache.contextDropsForTesting() == 0,
                "the cache failed closed during the steady-state run");
        close.invoke(pool);
        for (MockBuffer buffer : created) {
            check(buffer.closeCalls == 1, "a buffer was closed " + buffer.closeCalls + " times");
        }
        System.out.println("GPU_POOL_263_RUNTIME_OK frames=120 delay=" + FENCE_DELAY
                + " handedOut=" + handedOut + " created=" + created.size() + " reused=" + reused
                + " adopted=" + adopted + " ttlEvictions=" + cache.ttlEvictionsForTesting()
                + " budgetEvictions=" + cache.budgetEvictionsForTesting()
                + " peakBytes=" + peakAfterAcquire);
        // Vanilla Util may have started non-daemon worker pools while Mth initialised.
        System.exit(0);
    }
}
`;

/** Splits a build classpath.txt (":"-separated MSYS paths, or ";"-separated native ones). */
function splitClasspath(text) {
  const parts = text.split(text.includes(";") ? ";" : ":");
  const merged = [];
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index];
    if (/^[A-Za-z]$/.test(part) && index + 1 < parts.length) {
      merged.push(`${part}:${parts[++index]}`);
    } else if (part) {
      merged.push(part);
    }
  }
  return merged;
}

/** The 26.3 client libraries (JOML, Guava, logging, ...) that vanilla Mth/Util need. */
async function minecraftLibraries() {
  const text = (await readFile(path.join(repositoryRoot, "port/work/26.3/classpath.txt"), "utf8")).trim();
  return splitClasspath(text).map((entry) => {
    const normalized = entry.replaceAll("\\", "/");
    const relative = normalized.toLowerCase().indexOf("/port/work/");
    return relative >= 0 ? path.join(repositoryRoot, normalized.slice(relative + 1)) : nativePath(entry);
  }).filter((entry) => !entry.endsWith("client-named.jar"));
}

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: repositoryRoot, encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 300_000, ...options,
  });
}

const root = await mkdtemp(path.join(tmpdir(), "gaius-263-gpu-pool-"));
try {
  const directories = Object.fromEntries(["patcher", "patched", "helper", "verifier", "harness", "src"]
    .map((name) => [name, path.join(root, name)]));
  await Promise.all(Object.values(directories).map((directory) => mkdir(directory, {recursive: true})));
  const asmPatcher = asm.slice(0, 2).join(path.delimiter);
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath", asmPatcher,
    "-sourcepath", toolsSource, "-d", directories.patcher, patcherSource]);
  const driverFile = path.join(directories.src, "P5PatchDriver.java");
  await writeFile(driverFile, driverSource, "utf8");
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath",
    [directories.patcher, asmPatcher].join(path.delimiter), "-d", directories.patcher, driverFile]);
  const patchedPool = path.join(directories.patched,
    "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool.class");
  const patchOutput = run(tools.java, ["-classpath", [directories.patcher, asmPatcher].join(path.delimiter),
    "P5PatchDriver", "patchStagedVertexBufferGpuPoolCache", rawClient, patchedPool]);
  assert.match(patchOutput,
    /Instrumented renderpearl StagedVertexBuffer GPU pool cache: count=4 bytes=1048576 idle=3/,
    "the 26.3 jar did not take the 26.3 pool branch");
  await access(patchedPool);
  await assert.rejects(access(path.join(directories.patched,
    "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool$PendingRecycle.class")),
  "the patch must never emit PendingRecycle");

  const verifierFile = path.join(directories.src, "Pool263Verifier.java");
  await writeFile(verifierFile, verifierSource, "utf8");
  const asmAll = asm.join(path.delimiter);
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath", asmAll, "-d", directories.verifier,
    verifierFile]);
  const verifierOutput = run(tools.java, ["-classpath", [directories.verifier, asmAll].join(path.delimiter),
    "Pool263Verifier", patchedPool]);
  assert.match(verifierOutput, /BASIC_VERIFIER_OK net\/minecraft\/client\/renderer\/StagedVertexBuffer\$GpuBufferPool/);
  assert.match(verifierOutput, /GPU_POOL_263_CFG_OK/);
  process.stdout.write(verifierOutput);

  const helperClasspath = [rawClient, ...teaVm].join(path.delimiter);
  const stagedHelper = path.join(directories.src, "staged-helper", helperRelative);
  await mkdir(path.dirname(stagedHelper), {recursive: true});
  await writeFile(stagedHelper, helperText, "utf8");
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath", helperClasspath,
    "-d", directories.helper, stagedHelper, jsArraysSource]);
  const harnessDirectory = path.join(directories.src, "dev/gaius/browser");
  await mkdir(harnessDirectory, {recursive: true});
  const harnessFile = path.join(harnessDirectory, "Pool263Harness.java");
  await writeFile(harnessFile, harnessSource, "utf8");
  const runtime = [directories.patched, directories.helper, rawClient, ...teaVm,
    ...await minecraftLibraries()].join(path.delimiter);
  run(tools.javac, ["--release", "21", "-proc:none", "-classpath", runtime, "-d", directories.harness,
    harnessFile]);
  const runtimeOutput = run(tools.java, ["-Xverify:all", "-ea", "-classpath",
    [directories.harness, runtime].join(path.delimiter), "dev.gaius.browser.Pool263Harness"]);
  assert.match(runtimeOutput, /GPU_POOL_263_RUNTIME_OK frames=120 delay=2 /);
  process.stdout.write(runtimeOutput);
} finally {
  await rm(root, {recursive: true, force: true});
}

console.log(JSON.stringify({
  status: "ok",
  profile: "26.3",
  cache: {maxCount: 4, maxBytes: 1024 * 1024, maxIdleFrames: 3},
  verification: ["exact-jar-patch", "asm-basic-verifier", "asm-cfg-order", "jvm-xverify-runtime",
    "tail-adoption-stamped-with-new-frame", "budget-after-acquire", "single-close"],
}));
