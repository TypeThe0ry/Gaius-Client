#!/usr/bin/env node

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {copyFile, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {stageSources} from "../tools/sourcesets/sourcesets.mjs";
import {patcherSource as stepSource} from "./patcher-sources.mjs";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const config = JSON.parse(await readFile(
  new URL("../config.json", import.meta.url),
  "utf8",
));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => path.basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
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
  throw new Error(`section task queue smoke is for profile ${version}, got ${configuredProfileId}`);
}
// SectionTaskDynamicQueue and its SectionTask/RenderSection surface are byte-identical in
// 26.2 and 26.3 (after the renderpearl renames), so both modern profiles run the same checks.
if (version !== CANONICAL_PROFILE.id && version !== RENDERPEARL_PROFILE.id) {
  throw new Error(`section task queue smoke supports profiles ${CANONICAL_PROFILE.id} and ${RENDERPEARL_PROFILE.id}; got ${version}`);
}
const overlayJar = process.env.GAIUS_SECTION_QUEUE_JAR
  ? nativePath(process.env.GAIUS_SECTION_QUEUE_JAR)
  : `${nativePath(process.env.GAIUS_OVERLAY_DIRECTORY || `${repositoryRoot}/port/work/overlays${process.env.GAIUS_BUILD_ROOT || process.env.GAIUS_VERSION_PROFILE_PATH ? `/${version}` : ""}`)}/client-named-${version}-gaius.jar`;
const queueClass = "net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue";

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
    timeout: 60_000,
    ...options,
  });
}

async function copyUnsignedOverlayJar(source, destination, javaTools) {
  const jar = path.join(path.dirname(javaTools.javac),
    process.platform === "win32" ? "jar.exe" : "jar");
  assert.ok(existsSync(jar), `missing JDK jar tool for signer-isolated queue harness: ${jar}`);
  const unpacked = await mkdtemp(path.join(path.dirname(destination), "unsigned-overlay-"));
  try {
    run(jar, ["--extract", "--file", source], {cwd: unpacked, timeout: 180_000});
    const metaInf = path.join(unpacked, "META-INF");
    for (const entry of ["MOJANGCS.SF", "MOJANGCS.RSA", "MOJANGCS.DSA", "MOJANGCS.EC"]) {
      await rm(path.join(metaInf, entry), {force: true});
    }
    run(jar, ["--create", "--file", destination, "-C", unpacked, "."],
      {timeout: 180_000});
    const remainingSignatures = run(jar, ["--list", "--file", destination])
      .split(/\r?\n/)
      .filter((entry) => /^META-INF\/[^/]+\.(?:SF|RSA|DSA|EC)$/i.test(entry));
    assert.deepEqual(remainingSignatures, [],
      "temporary queue harness overlay retained package-signing metadata");
  } finally {
    await rm(unpacked, {recursive: true, force: true});
  }
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature
    ? bytecode.indexOf(nextSignature, start + signature.length)
    : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

function selectJavaTools() {
  const requested = Number(profile.javaVersion);
  const homes = [
    process.env.GAIUS_JAVA_HOME && nativePath(process.env.GAIUS_JAVA_HOME),
    process.env.JAVA_HOME && nativePath(process.env.JAVA_HOME),
    `/opt/homebrew/opt/openjdk@${requested}/libexec/openjdk.jdk/Contents/Home`,
    `/usr/local/opt/openjdk@${requested}/libexec/openjdk.jdk/Contents/Home`,
  ].filter(Boolean);
  for (const home of homes) {
    const javac = path.join(home, "bin/javac");
    const java = path.join(home, "bin/java");
    const javap = path.join(home, "bin/javap");
    try {
      const versionOutput = execFileSync(javac, ["-version"], {encoding: "utf8"});
      const major = Number(versionOutput.match(/javac (\d+)/)?.[1]);
      if (major >= requested) {
        return {java, javac, javap};
      }
    } catch {
      // Try the next configured JDK home.
    }
  }
  throw new Error(`Minecraft ${version} requires JDK ${requested} or newer`);
}

const javaTools = selectJavaTools();

// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = stepSource("MinecraftClientPatcher");
for (const contract of [
  "browserDirtyTasks",
  "browserTaskOrder",
  "browserRebuild",
  "browserTakeNearest",
  "browserHeapAdd",
  "browserHeapPoll",
  "browserSiftDown",
  "browserCancelAndClear",
  "java/util/ArrayList",
]) {
  assert.ok(patcher.includes(contract), `missing queue patch contract: ${contract}`);
}

const bytecode = run(javaTools.javap, ["-classpath", overlayJar, "-p", "-c", queueClass]);
assert.ok(
  !bytecode.includes("implements java.util.Comparator"),
  "patched queue still routes comparisons through PriorityQueue.Comparator",
);
assert.ok(!bytecode.includes("java.util.PriorityQueue"), "PriorityQueue rebuild path survived");
assert.ok(bytecode.includes("java.util.ArrayList<"), "array-backed heaps are missing");
assert.ok(!bytecode.includes("java/util/ListIterator"), "poll still scans the task list");
assert.ok(!bytecode.includes("java/util/List.remove"), "poll still removes by list index");

const poll = method(bytecode, "public synchronized net.minecraft.client.renderer.chunk."
  + "SectionRenderDispatcher$RenderSection$SectionTask poll", "public int size()");
assert.equal(
  poll.split("browserRebuild").length - 1,
  2,
  "camera changes do not rebuild both heaps exactly once",
);
assert.equal(
  poll.split("browserTakeNearest").length - 1,
  2,
  "poll does not select one exact candidate from each priority class",
);
assert.ok(poll.includes("Vec3.distanceToSqr"), "poll still takes a camera-distance sqrt");
assert.ok(poll.includes("recompileQuota"), "dirty compile quota semantics were dropped");
const add = method(bytecode, "public synchronized void add", "public synchronized net.minecraft."
  + "client.renderer.chunk.SectionRenderDispatcher$RenderSection$SectionTask poll");
assert.equal(
  add.split("getRenderOrigin").length - 1,
  1,
  "add does not snapshot each task origin exactly once",
);
assert.ok(!bytecode.includes("Double.valueOf"), "distance-cache updates still allocate boxed doubles");

const dirtyClassifier = method(
  bytecode,
  "private static boolean browserIsDirtyCompile",
  "private double browserDistance",
);
assert.ok(dirtyClassifier.includes("isRecompile"), "dirty flag is not checked");
assert.ok(dirtyClassifier.includes("CompileTask"), "resort tasks are not excluded");
const comparator = method(bytecode, "private int browserCompare", "private void browserHeapAdd");
assert.ok(comparator.includes("Double.compare"), "heap does not compare camera distance");
assert.ok(comparator.includes("Long.compare"), "equal-distance insertion order is unstable");
const rebuild = method(bytecode, "private void browserRebuild", "private net.minecraft.client.renderer.chunk."
  + "SectionRenderDispatcher$RenderSection$SectionTask browserTakeNearest");
assert.ok(rebuild.includes("browserUpdateDistance"), "camera rebuild does not refresh cached distances");
assert.ok(rebuild.includes("browserSiftDown"), "camera rebuild does not use linear Floyd heapify");
assert.ok(!rebuild.includes("browserHeapAdd"), "camera rebuild is still O(N log N)");
const liveRoot = method(bytecode, "private net.minecraft.client.renderer.chunk."
  + "SectionRenderDispatcher$RenderSection$SectionTask browserTakeNearest",
"private void browserRequeue");
assert.ok(liveRoot.includes("AtomicBoolean.get"), "cancelled roots are not filtered");
assert.ok(liveRoot.includes("browserHeapPoll"), "cancelled roots are not removed lazily");
assert.ok(!liveRoot.includes("Math.sqrt"), "poll still repeats sqrt for heap candidates");
assert.ok(!liveRoot.includes("getRenderOrigin"), "poll still repeats task origin reads");
const clear = method(bytecode, "public synchronized void clear()", "private static boolean");
assert.equal(
  clear.split("browserCancelAndClear").length - 1,
  2,
  "clear does not cancel both heaps",
);

const harnessSource = String.raw`
package net.minecraft.client.renderer.chunk;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

public final class SectionTaskQueueSmokeHarness {
    private static final Unsafe UNSAFE = unsafe();
    private static final SectionRenderDispatcher.RenderSection EMPTY_SECTION = rawSection(0, 0, 0);
    private static long originReads;

    private record PollMetrics(long originReads, long p99Nanos, long maxNanos) {}

    private static final class TestTask
            extends SectionRenderDispatcher.RenderSection.SectionTask {
        private final int id;
        private final BlockPos origin;

        TestTask(int id, int x, int y, int z) {
            EMPTY_SECTION.super(false);
            this.id = id;
            this.origin = new BlockPos(x, y, z);
        }

        @Override
        public SectionTaskResult doTask(SectionBufferBuilderPack buffers) {
            return null;
        }

        @Override
        public void cancel() {
            this.isCancelled.set(true);
        }

        @Override
        public BlockPos getRenderOrigin() {
            originReads++;
            return this.origin;
        }

        boolean cancelled() {
            return this.isCancelled.get();
        }
    }

    public static void main(String[] args) {
        verifyDirtyAndDistancePriority();
        verifyDirtyQuota();
        verifyCameraEpochRebuild();
        verifyWithinEpochCameraPriority();
        verifyStableTies();
        PollMetrics drain = verifyBacklogAndCancellation();
        PollMetrics moving = verifyMovingCameraLatency();
        verifyClearLifecycle();
        System.out.println("Section task queue JVM smoke passed: backlog=1200 originReads="
                + drain.originReads() + " drainP99Micros=" + nanosToMicros(drain.p99Nanos())
                + " drainMaxMicros=" + nanosToMicros(drain.maxNanos())
                + " movingBacklog=4096 movingP99Micros=" + nanosToMicros(moving.p99Nanos())
                + " movingMaxMicros=" + nanosToMicros(moving.maxNanos()));
    }

    private static void verifyDirtyAndDistancePriority() {
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        var dirtyNear = compileTask(rawSection(0, 0, 0), true);
        var resortNear = resortTask(rawSection(1, 0, 0));
        var regularCompile = compileTask(rawSection(2, 0, 0), false);
        var dirtyFar = compileTask(rawSection(4, 0, 0), true);
        queue.add(dirtyFar);
        queue.add(regularCompile);
        queue.add(resortNear);
        queue.add(dirtyNear);

        Vec3 camera = new Vec3(0.0, 0.0, 0.0);
        check(queue.poll(camera) == dirtyNear, "near dirty compile lost priority");
        check(queue.poll(camera) == resortNear, "resort was not distance-prioritized");
        check(queue.poll(camera) == regularCompile, "regular compile distance order changed");
        check(queue.poll(camera) == dirtyFar, "far dirty compile was lost");
        check(queue.poll(camera) == null && queue.size() == 0, "priority queue did not drain");
    }

    private static void verifyCameraEpochRebuild() {
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        TestTask left = new TestTask(0, -320, 0, 0);
        TestTask middle = new TestTask(1, 0, 0, 0);
        TestTask right = new TestTask(2, 320, 0, 0);
        queue.add(left);
        queue.add(middle);
        queue.add(right);
        check(queue.poll(new Vec3(300.0, 0.0, 0.0)) == right,
                "initial camera distance was ignored");
        check(queue.poll(new Vec3(-300.0, 0.0, 0.0)) == left,
                "camera movement did not rebuild heap order");
        check(queue.poll(new Vec3(-300.0, 0.0, 0.0)) == middle,
                "camera epoch lost a remaining task");
    }

    private static void verifyDirtyQuota() {
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        var dirty0 = compileTask(rawSection(0, 0, 0), true);
        var dirty1 = compileTask(rawSection(1, 0, 0), true);
        var dirtyFar = compileTask(rawSection(100, 0, 0), true);
        TestTask regular = new TestTask(0, 50 * 16, 0, 0);
        queue.add(dirtyFar);
        queue.add(regular);
        queue.add(dirty1);
        queue.add(dirty0);
        Vec3 camera = Vec3.ZERO;
        check(queue.poll(camera) == dirty0, "first dirty quota pick changed");
        check(queue.poll(camera) == dirty1, "second dirty quota pick changed");
        check(queue.poll(camera) == regular,
                "exhausted dirty quota did not yield to a regular task");
        check(queue.poll(camera) == dirtyFar, "dirty task was lost after the quota reset");
    }

    private static void verifyWithinEpochCameraPriority() {
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        TestTask center = new TestTask(0, 0, 0, 0);
        TestTask right = new TestTask(1, 16, 0, 0);
        TestTask left = new TestTask(2, -16, 0, 0);
        queue.add(center);
        queue.add(right);
        queue.add(left);
        check(queue.poll(Vec3.ZERO) == center, "camera anchor selected the wrong root");
        check(queue.poll(new Vec3(-15.0, 0.0, 0.0)) == left,
                "within-epoch movement did not select the exact nearest task");
        check(queue.poll(new Vec3(-15.0, 0.0, 0.0)) == right,
                "within-epoch selection lost the displaced heap root");
    }

    private static void verifyStableTies() {
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        TestTask first = new TestTask(0, 16, 0, 16);
        TestTask second = new TestTask(1, 16, 0, 16);
        queue.add(first);
        queue.add(second);
        Vec3 camera = Vec3.ZERO;
        check(queue.poll(camera) == first, "equal-distance FIFO order changed");
        check(queue.poll(camera) == second, "equal-distance task was lost");
    }

    private static PollMetrics verifyBacklogAndCancellation() {
        final int backlog = 1200;
        warmQueue();
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        List<TestTask> tasks = new ArrayList<>(backlog);
        originReads = 0;
        for (int index = 0; index < backlog; index++) {
            int id = (index * 811) % backlog;
            TestTask task = new TestTask(id, id * 16, 0, 0);
            tasks.add(task);
            queue.add(task);
        }
        check(originReads == backlog,
                "add did not snapshot each task origin exactly once: " + originReads);
        Set<Integer> cancelled = new HashSet<>();
        for (TestTask task : tasks) {
            if (task.id % 17 == 0) {
                task.cancel();
                cancelled.add(task.id);
            }
        }

        originReads = 0;
        List<Integer> drained = new ArrayList<>(backlog - cancelled.size());
        long[] pollNanos = new long[backlog + 1];
        int pollSamples = 0;
        SectionRenderDispatcher.RenderSection.SectionTask next;
        while (true) {
            long started = System.nanoTime();
            next = queue.poll(new Vec3(
                    drained.size() % 2 == 0 ? 0.25 : -0.25, 0.0, 0.0));
            pollNanos[pollSamples++] = System.nanoTime() - started;
            if (next == null) {
                break;
            }
            TestTask task = (TestTask) next;
            check(!task.cancelled(), "poll returned a cancelled task");
            drained.add(task.id);
        }

        check(queue.size() == 0, "cancelled tombstones survived a complete drain");
        check(drained.size() == backlog - cancelled.size(), "backlog lost or duplicated tasks");
        Set<Integer> unique = new HashSet<>(drained);
        check(unique.size() == drained.size(), "backlog returned a task twice");
        for (int index = 1; index < drained.size(); index++) {
            check(drained.get(index - 1) < drained.get(index),
                    "camera-distance order regressed at drain index " + index);
        }

        check(originReads == 0,
                "poll reread task origins after add: " + originReads);
        PollMetrics metrics = summarize(originReads, pollNanos, pollSamples);
        check(metrics.p99Nanos() <= 250_000L,
                "stationary-camera p99 poll exceeded 250 us: " + metrics.p99Nanos());
        check(metrics.maxNanos() <= 5_000_000L,
                "stationary-camera longest poll exceeded 5 ms: " + metrics.maxNanos());
        return metrics;
    }

    private static PollMetrics verifyMovingCameraLatency() {
        final int backlog = 4096;
        final int samples = 256;
        warmQueue();
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        List<TestTask> remaining = new ArrayList<>(backlog);
        originReads = 0;
        for (int index = 0; index < backlog; index++) {
            int id = (index * 4051) & (backlog - 1);
            TestTask task = new TestTask(
                    id,
                    ((id & 127) - 64) * 16,
                    (((id >>> 7) & 3) - 2) * 16,
                    (((id >>> 9) & 7) - 4) * 16);
            remaining.add(task);
            queue.add(task);
        }
        check(originReads == backlog,
                "moving backlog did not snapshot each task origin exactly once: " + originReads);

        originReads = 0;
        long[] pollNanos = new long[samples];
        for (int sample = 0; sample < samples; sample++) {
            Vec3 camera = new Vec3(
                    (sample % 2 == 0 ? 640.0 : -640.0) + (sample % 7) * 19.0,
                    ((sample * 11) % 5 - 2) * 24.0,
                    ((sample * 37) % 17 - 8) * 53.0);
            TestTask expected = nearest(remaining, camera);
            long started = System.nanoTime();
            TestTask actual = (TestTask) queue.poll(camera);
            pollNanos[sample] = System.nanoTime() - started;
            check(actual == expected,
                    "moving-camera nearest task mismatch at sample " + sample
                            + ": expected=" + expected.id + " actual=" + actual.id);
            check(remaining.remove(actual), "moving-camera poll returned a duplicate task");
        }
        check(originReads == 0,
                "camera rebuild reread task origins: " + originReads);
        PollMetrics metrics = summarize(originReads, pollNanos, samples);
        check(metrics.p99Nanos() <= 5_000_000L,
                "moving-camera p99 poll exceeded 5 ms: " + metrics.p99Nanos());
        check(metrics.maxNanos() <= 30_000_000L,
                "moving-camera longest poll exceeded 30 ms: " + metrics.maxNanos());
        queue.clear();
        return metrics;
    }

    private static void warmQueue() {
        for (int pass = 0; pass < 3; pass++) {
            SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
            for (int index = 0; index < 512; index++) {
                queue.add(new TestTask(index, index * 16, 0, (index & 15) * 16));
            }
            for (int sample = 0; sample < 64; sample++) {
                queue.poll(new Vec3(sample % 2 == 0 ? 320.0 : -320.0, 0.0, sample * 19.0));
            }
            queue.clear();
        }
    }

    private static TestTask nearest(List<TestTask> tasks, Vec3 camera) {
        TestTask nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (TestTask task : tasks) {
            double distance = camera.distanceToSqr(
                    task.origin.getX() + 0.5,
                    task.origin.getY() + 0.5,
                    task.origin.getZ() + 0.5);
            if (distance < nearestDistance) {
                nearest = task;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static PollMetrics summarize(long reads, long[] samples, int sampleCount) {
        long[] sorted = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(sorted);
        int p99Index = Math.max(0, (int) Math.ceil(sampleCount * 0.99) - 1);
        return new PollMetrics(reads, sorted[p99Index], sorted[sampleCount - 1]);
    }

    private static long nanosToMicros(long nanos) {
        return (nanos + 999L) / 1_000L;
    }

    private static void verifyClearLifecycle() {
        SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        List<TestTask> tasks = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            TestTask task = new TestTask(index, index * 16, 0, 0);
            tasks.add(task);
            queue.add(task);
        }
        queue.clear();
        check(queue.size() == 0, "clear did not empty both heaps");
        for (TestTask task : tasks) {
            check(task.cancelled(), "clear skipped task " + task.id);
        }
        check(queue.poll(Vec3.ZERO) == null, "clear left a pollable task");
    }

    private static SectionRenderDispatcher.RenderSection rawSection(int x, int y, int z) {
        try {
            var section = (SectionRenderDispatcher.RenderSection)
                    UNSAFE.allocateInstance(SectionRenderDispatcher.RenderSection.class);
            Field origin = SectionRenderDispatcher.RenderSection.class
                    .getDeclaredField("renderOrigin");
            UNSAFE.putObject(section, UNSAFE.objectFieldOffset(origin),
                    new BlockPos.MutableBlockPos(x * 16, y * 16, z * 16));
            return section;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static SectionRenderDispatcher.RenderSection.SectionTask compileTask(
            SectionRenderDispatcher.RenderSection section, boolean recompile) {
        return reflectTask("CompileTask", section,
                new Class<?>[] {RenderSectionRegion.class, boolean.class},
                new Object[] {null, recompile});
    }

    private static SectionRenderDispatcher.RenderSection.SectionTask resortTask(
            SectionRenderDispatcher.RenderSection section) {
        return reflectTask("ResortTransparencyTask", section,
                new Class<?>[] {CompiledSectionMesh.class}, new Object[] {null});
    }

    private static SectionRenderDispatcher.RenderSection.SectionTask reflectTask(
            String simpleName,
            SectionRenderDispatcher.RenderSection section,
            Class<?>[] argumentTypes,
            Object[] arguments) {
        try {
            Class<?> type = Class.forName(
                    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$"
                            + simpleName);
            Class<?>[] constructorTypes = new Class<?>[argumentTypes.length + 1];
            constructorTypes[0] = SectionRenderDispatcher.RenderSection.class;
            System.arraycopy(argumentTypes, 0, constructorTypes, 1, argumentTypes.length);
            Object[] constructorArguments = new Object[arguments.length + 1];
            constructorArguments[0] = section;
            System.arraycopy(arguments, 0, constructorArguments, 1, arguments.length);
            var constructor = type.getDeclaredConstructor(constructorTypes);
            constructor.setAccessible(true);
            return (SectionRenderDispatcher.RenderSection.SectionTask)
                    constructor.newInstance(constructorArguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
`;

const temporaryRoot = await mkdtemp(path.join(tmpdir(), "gaius-section-queue-smoke-"));
try {
  const sourceDirectory = path.join(
    temporaryRoot,
    "src/net/minecraft/client/renderer/chunk",
  );
  const classesDirectory = path.join(temporaryRoot, "classes");
  const sourceFile = path.join(sourceDirectory, "SectionTaskQueueSmokeHarness.java");
  await mkdir(sourceDirectory, {recursive: true});
  await mkdir(classesDirectory, {recursive: true});
  await writeFile(sourceFile, harnessSource, "utf8");
  const unsignedOverlayJar = path.join(temporaryRoot, "client-named-unsigned.jar");
  const javaTools = selectJavaTools();
  await copyUnsignedOverlayJar(overlayJar, unsignedOverlayJar, javaTools);
  let minecraftClasspath = (await readFile(
    `${repositoryRoot}/port/work/${version}/classpath.txt`,
    "utf8",
  )).trim();
  // classpath.txt is written for the bash build with `:` separators and MSYS
  // `/c/...` paths. Windows javac/java need `;` separators and drive-letter
  // paths, so convert the list before handing it to the JDK directly.
  if (process.platform === "win32") {
    minecraftClasspath = minecraftClasspath
      .split(":")
      .map((entry) => {
        const normalized = entry.replaceAll("\\", "/");
        const repositoryRelative = normalized.toLowerCase().indexOf("/port/work/");
        if (repositoryRelative >= 0) {
          return path.join(repositoryRoot, normalized.slice(repositoryRelative + 1));
        }
        return nativePath(entry);
      })
      .join(path.delimiter);
  }
  // The harness declares classes in the same Minecraft package as the
  // patched overlay.  A signed Mojang jar plus unsigned harness classes makes
  // the JVM reject the package before main() runs, so execute against a
  // job-local unsigned copy while retaining the signed jar for javap checks.
  const compileClasspath = [unsignedOverlayJar, minecraftClasspath].join(path.delimiter);
  // 26.3's StrictMath263 rewrite makes Mth (sqrt and friends) call dev.gaius.browser
  // BrowserStrictMath, which lives in port/src. Compile its JVM twin next to the harness: the
  // two JSBody natives (Math.fround) become float casts, which round the same way on the JVM.
  const strictMathSource = await readFile(
    path.join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserStrictMath.java"), "utf8");
  const strictMathTwin = strictMathSource
    .replace(/^import org\.teavm\.jso\.JSBody;\r?\n/m, "")
    .replace(/@JSBody\(.*\)\r?\n\s*public static native float (\w+)\((\w+) value\);/g,
      "public static float $1($2 value) {\n        return (float) value;\n    }");
  assert.ok(!/\bnative\b/.test(strictMathTwin.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, "")),
    "BrowserStrictMath gained a native the JVM twin does not cover");
  const strictMathFile = path.join(temporaryRoot, "src/dev/gaius/browser/BrowserStrictMath.java");
  await mkdir(path.dirname(strictMathFile), {recursive: true});
  await writeFile(strictMathFile, strictMathTwin, "utf8");
  // The patched section classes call BrowserMeshInstallQueue (port/src, TeaVM-only natives) to
  // invalidate streamed mesh snapshots and bump its epochs; the harness needs none of its state,
  // so a stub with the entry points the patches reference stands in for it on the JVM.
  const installQueueFile = path.join(temporaryRoot, "src/dev/gaius/browser/render/BrowserMeshInstallQueue.java");
  await mkdir(path.dirname(installQueueFile), {recursive: true});
  await writeFile(installQueueFile, [
    "package dev.gaius.browser.render;",
    "public final class BrowserMeshInstallQueue {",
    "    private BrowserMeshInstallQueue() {}",
    "    public static int snapshotSequence(long sectionNode) { return 0; }",
    "    public static void invalidateSection(int x, int y, int z) {}",
    "    public static void bumpLevelEpoch() {}",
    "    public static void bumpResourceEpoch() {}",
    "}",
    "",
  ].join("\n"), "utf8");
  // The mesh kernel patches make RenderSection and SectionCompiler implement the interfaces of
  // dev.gaius.browser.kernel.mesh.MeshKernelAccess (port/mc/shared, per profile); compile them
  // from the profile's staged sources (javac pulls in what they reference through -sourcepath).
  const profileSources = path.join(temporaryRoot, "profile-src");
  stageSources(profile, "src", "js", profileSources, repositoryRoot);
  const sourceRoots = [profileSources];
  const portSources = ["dev/gaius/browser/kernel/mesh/MeshKernelAccess.java"]
    .map((relative) => path.join(profileSources, relative))
    .filter((file) => existsSync(file));
  run(javaTools.javac, [
    "-proc:none",
    "-implicit:class",
    "-classpath", compileClasspath,
    "-sourcepath", sourceRoots.join(path.delimiter),
    "-d", classesDirectory,
    sourceFile,
    strictMathFile,
    installQueueFile,
    ...portSources,
  ], {stdio: ["ignore", "pipe", "pipe"]});
  const output = run(javaTools.java, [
    "--sun-misc-unsafe-memory-access=allow",
    "-Xverify:all",
    "-ea",
    "-classpath", [classesDirectory, compileClasspath].join(path.delimiter),
    "net.minecraft.client.renderer.chunk.SectionTaskQueueSmokeHarness",
  ], {stdio: ["ignore", "pipe", "pipe"]}).trim();
  assert.match(
    output,
    /backlog=1200 originReads=0 drainP99Micros=\d+ drainMaxMicros=\d+ movingBacklog=4096 movingP99Micros=\d+ movingMaxMicros=\d+$/,
  );
  console.log(output);
  console.log("Section task queue bytecode smoke passed");
} finally {
  await rm(temporaryRoot, {recursive: true, force: true});
}
