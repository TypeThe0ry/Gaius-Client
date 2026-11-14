import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {delimiter, dirname, join, resolve} from "node:path";
import {homedir} from "node:os";
import {fileURLToPath} from "node:url";
import {loadProfiles} from "../tools/sourcesets/sourcesets.mjs";

// Run under the machine's JVM/build slot. All fixtures stay in the explicit output directory.
const root = fileURLToPath(new URL("../..", import.meta.url));
const out = resolve(process.argv[2] || (() => { throw new Error("output directory required"); })());
const classes = join(out, "classes");
mkdirSync(classes, {recursive: true});
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
assert(javaHome, "JAVA_HOME required");
const run = (name, args) => {
  const result = execFileSync(join(javaHome, "bin", name), args, {cwd: root, encoding: "utf8",
    windowsHide: true, maxBuffer: 16 * 1024 * 1024});
  writeFileSync(join(out, `${name}.log`), result);
  return result;
};
const profiles = loadProfiles(root).filter(p => p.patchSet === "modern"
  && existsSync(join(root, "port/work", p.id, "client-named.jar")));
assert(profiles.length > 0, "modern vanilla jars required");
const libraries = readFileSync(join(root, "port/work", profiles[0].id, "classpath.txt"), "utf8")
  .trim().split(/;|:(?=\/|[A-Za-z]:\/)/u)
  .map(p => p.replace(/^\/([a-z])\//i, (_, drive) => `${drive.toUpperCase()}:/`));
const fastutil = libraries.find(p => /fastutil.*\.jar$/.test(p));
assert(fastutil && existsSync(fastutil), "cached fastutil required");
const asm = ["asm", "asm-tree", "asm-analysis"].map(name =>
  join(homedir(), ".m2/repository/org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
assert(asm.every(existsSync), "cached ASM required");
const sources = [];
function source(path, text) {
  const file = join(out, "src", path);
  mkdirSync(dirname(file), {recursive: true});
  writeFileSync(file, text);
  sources.push(file);
}
source("org/teavm/jso/JSBody.java", `package org.teavm.jso;
public @interface JSBody { String[] params() default {}; String script(); }
`);
source("dev/gaius/browser/BrowserBuildFlags.java", `package dev.gaius.browser;
public class BrowserBuildFlags { public static boolean telemetry() { return false; } }
`);
source("dev/gaius/browser/kernel/KernelMirror.java", `package dev.gaius.browser.kernel;
public class KernelMirror { public static boolean mesher; public static boolean enabled(String name) { return mesher; } }
`);
source("dev/gaius/browser/BrowserChunkPipelineTelemetry.java",
  readFileSync(join(root, "port/src/main/java/dev/gaius/browser/BrowserChunkPipelineTelemetry.java"), "utf8"));
source("dev/gaius/browser/render/TelemetryCapture.java", `package dev.gaius.browser.render;
public class TelemetryCapture { public static int publishCount; public static int publishedReadyCount; public static double publishedEntryBudget; public static double publishedMaxWait; public static double publishedObservedMaxWait; public static double publishedMaxWaitLimit; }
`);
source("dev/gaius/browser/render/BrowserMeshInstallQueue.java",
  readFileSync(join(root, "port/src/main/java/dev/gaius/browser/render/BrowserMeshInstallQueue.java"), "utf8")
    .replace(/private static native void publish\(([\s\S]+?)\);/, "private static void publish($1) { TelemetryCapture.publishCount++; TelemetryCapture.publishedReadyCount=ready; TelemetryCapture.publishedEntryBudget=readyEntryBudget; TelemetryCapture.publishedMaxWait=readyMaxWaitFrames; TelemetryCapture.publishedObservedMaxWait=observedReadyMaxWaitFrames; TelemetryCapture.publishedMaxWaitLimit=readyMaxWaitLimitFrames; }")
    .replace(/private static native boolean browserInstallPriorityEnabled\(\);/,
      "private static boolean browserInstallPriorityEnabled() { return Boolean.parseBoolean(System.getProperty(\"gaius.mesh.installPriority\", \"false\")); }")
    .replace(/private static native String browserReadyBudgetMiB\(\);/, "private static String browserReadyBudgetMiB() { return System.getProperty(\"gaius.mesh.readyBudgetMiB\", \"\"); }")
    .replace(/private static native String browserReadyMaxEntries\(\);/, "private static String browserReadyMaxEntries() { return System.getProperty(\"gaius.mesh.readyMaxEntries\", \"\"); }"));
for (const name of ["BrowserMeshInstall", "BrowserNeighborReadiness"]) {
  const javaSource = readFileSync(join(root, `port/src/main/java/dev/gaius/browser/render/${name}.java`), "utf8")
    .replace("private static native int configuredMode();", "private static int configuredMode() { return 1; }");
  source(`dev/gaius/browser/render/${name}.java`, javaSource);
}
source("SnapshotLifecycleFixture.java", `
import dev.gaius.browser.render.*;
import dev.gaius.browser.kernel.KernelMirror;
public class SnapshotLifecycleFixture {
  static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
  static boolean ageTrackingAllocated() {
    try {
      java.lang.reflect.Field field=BrowserMeshInstallQueue.class.getDeclaredField("readyEnqueueFrame");
      field.setAccessible(true);
      return field.get(null)!=null;
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }
  static int installOrder;
  static class Result implements BrowserMeshInstall {
    final long node; final int seq, level, resources, bytes; int priority, installed, discarded, installedAt;
    Result(long node, int seq) { this(node, seq, 0, 28); }
    Result(long node, int seq, int priority) { this(node, seq, priority, 28); }
    Result(long node, int seq, int priority, int bytes) { this.node=node; this.seq=seq; this.priority=priority; this.bytes=bytes;
      level=BrowserMeshInstallQueue.levelEpoch(); resources=BrowserMeshInstallQueue.resourceEpoch(); }
    public long sectionNode() { return node; } public int requestSeq() { return seq; }
    public int levelEpoch() { return level; } public int resourceEpoch() { return resources; }
    public int byteSize() { return bytes; } public boolean install() { installed++; installedAt=++installOrder; return true; }
    public int priority() { return priority; }
    public void discard(int reason) { discarded=reason; }
  }
  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("default-off")) {
      long offNode=9001L;
      Result large=new Result(offNode, BrowserMeshInstallQueue.beginRequest(offNode), 0, 2 * 1024 * 1024);
      BrowserMeshInstallQueue.enqueue(large);
      Result first=new Result(9002L, BrowserMeshInstallQueue.beginRequest(9002L), 10);
      Result second=new Result(9003L, BrowserMeshInstallQueue.beginRequest(9003L), 0);
      BrowserMeshInstallQueue.enqueue(first);
      BrowserMeshInstallQueue.enqueue(second);
      check(large.discarded==0 && BrowserMeshInstallQueue.readyCount()==3,
        "ready admission budgets are off by default");
      BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
      check(large.installed==1, "default-off result still installs");
      check(first.installedAt < second.installedAt,
        "default-off install ordering must preserve FIFO despite priority hints");
      check(!ageTrackingAllocated(), "default-off queue must not allocate READY age tracking");
      System.out.println("PASS: READY admission budgets remain disabled by default");
      return;
    }
    if (args.length > 0 && args[0].equals("entry-only")) {
      System.setProperty("gaius.mesh.readyMaxEntries", "2");
      long[] nodes={9101L,9102L,9103L};
      Result[] results=new Result[nodes.length];
      for (int i=0;i<nodes.length;i++) {
        results[i]=new Result(nodes[i], BrowserMeshInstallQueue.beginRequest(nodes[i]), 0, 0);
        BrowserMeshInstallQueue.enqueue(results[i]);
      }
      check(results[0].discarded==BrowserMeshInstallQueue.DISCARD_BUDGET,
        "entry-only budget evicts the oldest rebuildable result");
      check(BrowserMeshInstallQueue.readyCount()==2 && BrowserMeshInstallQueue.readyBytes()==0,
        "entry-only budget bounds empty meshes");
      check(dev.gaius.browser.render.TelemetryCapture.publishedEntryBudget==2,
        "explicit entry budget is published");
      BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
      check(results[1].installed==1 && results[2].installed==1,
        "admitted zero-byte results still install");
      System.out.println("PASS: explicit READY entry-only budget");
      return;
    }
    if (args.length > 0 && args[0].equals("aging")) {
      System.setProperty("gaius.mesh.installPriority", "true");
      Result old=new Result(9500L, BrowserMeshInstallQueue.beginRequest(9500L), 100, 1);
      BrowserMeshInstallQueue.enqueue(old);
      Result[] arrivals=new Result[8];
      for (int i=0; i<8; i++) {
        for (int j=0; j<i; j++) arrivals[j].priority=100;
        long node=9600L+i;
        arrivals[i]=new Result(node, BrowserMeshInstallQueue.beginRequest(node), 0, 1);
        BrowserMeshInstallQueue.enqueue(arrivals[i]);
        BrowserMeshInstallQueue.drain(1,Long.MAX_VALUE);
      }
      check(old.installed==1 && old.installedAt<=8,
        "priority aging installs an old READY result within the bounded wait while view priority changes");
      check(TelemetryCapture.publishedObservedMaxWait >= 8 && TelemetryCapture.publishedMaxWaitLimit == 8,
        "priority aging publishes the observed and configured maximum wait");
      System.out.println("PASS: priority READY aging bounds starvation and publishes wait telemetry");
      return;
    }
    System.setProperty("gaius.mesh.installPriority", "true");
    System.setProperty("gaius.mesh.readyBudgetMiB", "1");
    long node=((-3L)<<42) | ((2L & 0x3fffffL)<<20) | (-4L & 0xfffffL);
    int snapshot=BrowserMeshInstallQueue.snapshotSequence(node);
    BrowserMeshInstallQueue.invalidateSection(-3,-4,2);
    check(!BrowserMeshInstallQueue.isLatest(node,snapshot), "update between task creation and submission");
    int first=BrowserMeshInstallQueue.beginRequest(node);
    Result obsolete=new Result(node,first);
    BrowserMeshInstallQueue.invalidateSection(-3,-4,2);
    check(BrowserMeshInstallQueue.isPending(node), "replacement task can use responsive vanilla fallback");
    BrowserMeshInstallQueue.enqueue(obsolete);
    check(obsolete.discarded==BrowserMeshInstallQueue.DISCARD_SUPERSEDED, "late result rejected");
    check(!BrowserMeshInstallQueue.isPending(node), "discard releases the obsolete pending slot");
    int second=BrowserMeshInstallQueue.beginRequest(node);
    Result fresh=new Result(node,second);
    BrowserMeshInstallQueue.enqueue(fresh);
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(fresh.installed==1 && obsolete.installed==0, "only new snapshot installed");
    Result staleQueued=new Result(node,BrowserMeshInstallQueue.beginRequest(node),0,64);
    BrowserMeshInstallQueue.enqueue(staleQueued);
    Result latestQueued=new Result(node,BrowserMeshInstallQueue.beginRequest(node),0,96);
    BrowserMeshInstallQueue.enqueue(latestQueued);
    check(staleQueued.discarded==BrowserMeshInstallQueue.DISCARD_SUPERSEDED
        && BrowserMeshInstallQueue.readyCount()==1 && BrowserMeshInstallQueue.readyBytes()==96,
      "a newer section result immediately releases its superseded READY result");
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(latestQueued.installed==1 && staleQueued.installed==0,
      "only the latest queued mesh for a section is installed");
    Result queued=new Result(node,BrowserMeshInstallQueue.beginRequest(node));
    BrowserMeshInstallQueue.enqueue(queued);
    BrowserMeshInstallQueue.invalidateSection(-3,-4,2);
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(queued.installed==0 && queued.discarded==1, "queued result invalidated by light update");
    Result ready=new Result(node,BrowserMeshInstallQueue.beginRequest(node));
    BrowserMeshInstallQueue.enqueue(ready);
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    BrowserMeshInstallQueue.invalidateSection(-3,-4,2);
    check(!BrowserMeshInstallQueue.isLatest(node,ready.seq), "second task run must reject handed-off result");
    int stable=BrowserMeshInstallQueue.snapshotSequence(node);
    BrowserMeshInstallQueue.invalidateSection(-2,-4,2);
    check(BrowserMeshInstallQueue.isLatest(node,stable), "unrelated section does not invalidate target");
    BrowserMeshInstallQueue.bumpLevelEpoch();
    check(BrowserMeshInstallQueue.inFlight()==0, "level reset clears pending");
    KernelMirror.mesher=true;
    BrowserNeighborReadiness.setCamera(-3,2);
    check(BrowserNeighborReadiness.ready(true,false,node), "kernel may compile at the visible streaming frontier");
    int provisional=BrowserMeshInstallQueue.beginRequest(node);
    Result provisionalResult=new Result(node,provisional);
    BrowserMeshInstallQueue.enqueue(provisionalResult);
    BrowserMeshInstallQueue.invalidateSection(-3,-4,2);
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(provisionalResult.installed==0 && provisionalResult.discarded==BrowserMeshInstallQueue.DISCARD_SUPERSEDED,
      "neighbor publication invalidates provisional snapshot before install");
    int replacement=BrowserMeshInstallQueue.beginRequest(node);
    Result replacementResult=new Result(node,replacement);
    BrowserMeshInstallQueue.enqueue(replacementResult);
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(replacementResult.installed==1, "section can request a fresh snapshot after neighbor publication");
    check(BrowserNeighborReadiness.ready(true,true,node), "ready kernel section proceeds");
    check(!BrowserNeighborReadiness.ready(false,true,node), "missing center never proceeds");
    long nearNode=((-2L)<<42) | ((3L & 0x3fffffL)<<20) | (-4L & 0xfffffL);
    int farSeq=BrowserMeshInstallQueue.beginRequest(node);
    int nearSeq=BrowserMeshInstallQueue.beginRequest(nearNode);
    Result farResult=new Result(node,farSeq,10);
    Result nearResult=new Result(nearNode,nearSeq,0);
    BrowserMeshInstallQueue.enqueue(farResult);
    BrowserMeshInstallQueue.enqueue(nearResult);
    check(BrowserMeshInstallQueue.readyBytes() == farResult.byteSize() + nearResult.byteSize(),
      "priority reordering preserves ready byte accounting");
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(nearResult.installed==1 && farResult.installed==1 && nearResult.installedAt < farResult.installedAt,
      "priority queue installs nearer result first");
    long visibleNode=30001L, nearbyOffscreenNode=30002L, farOffscreenNode=30003L;
    Result visible=new Result(visibleNode, BrowserMeshInstallQueue.beginRequest(visibleNode), 5000);
    Result nearbyOffscreen=new Result(nearbyOffscreenNode,
      BrowserMeshInstallQueue.beginRequest(nearbyOffscreenNode), 0x8001);
    Result farOffscreen=new Result(farOffscreenNode,
      BrowserMeshInstallQueue.beginRequest(farOffscreenNode), 0x10001);
    BrowserMeshInstallQueue.enqueue(farOffscreen);
    BrowserMeshInstallQueue.enqueue(nearbyOffscreen);
    BrowserMeshInstallQueue.enqueue(visible);
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(visible.installedAt < nearbyOffscreen.installedAt
        && nearbyOffscreen.installedAt < farOffscreen.installedAt,
      "P0 visible mesh results must precede nearby and far off-screen work");
    long turnedVisibleNode=31001L, oldVisibleNode=31002L;
    Result oldViewVisible=new Result(oldVisibleNode, BrowserMeshInstallQueue.beginRequest(oldVisibleNode), 0);
    Result newViewVisible=new Result(turnedVisibleNode, BrowserMeshInstallQueue.beginRequest(turnedVisibleNode), 0x10000);
    BrowserMeshInstallQueue.enqueue(oldViewVisible);
    BrowserMeshInstallQueue.enqueue(newViewVisible);
    oldViewVisible.priority=0x10000;
    newViewVisible.priority=0;
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(newViewVisible.installedAt < oldViewVisible.installedAt,
      "queued results are ranked using the camera view at install time");
    long budgetNearNode=1001L, budgetFarNode=1002L;
    Result budgetNear=new Result(budgetNearNode, BrowserMeshInstallQueue.beginRequest(budgetNearNode), 0, 900000);
    Result budgetFar=new Result(budgetFarNode, BrowserMeshInstallQueue.beginRequest(budgetFarNode), 10, 900000);
    BrowserMeshInstallQueue.enqueue(budgetNear);
    BrowserMeshInstallQueue.enqueue(budgetFar);
    check(budgetFar.discarded==BrowserMeshInstallQueue.DISCARD_BUDGET,
      "ready byte budget releases the far rebuildable result");
    check(BrowserMeshInstallQueue.readyBytes() <= 1024L * 1024L,
      "ready byte budget is bounded");
    check(BrowserMeshInstallQueue.readyBudgetEvictions() > 0,
      "ready budget records an eviction");
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    check(budgetNear.installed==1, "the admitted result still installs");
    for (int i=0; i<65; i++) {
      long emptyNode=2000L+i;
      BrowserMeshInstallQueue.enqueue(new Result(emptyNode,
        BrowserMeshInstallQueue.beginRequest(emptyNode), 0, 0));
    }
    check(BrowserMeshInstallQueue.readyCount()==64,
      "a byte budget also bounds zero-byte READY entries");
    check(dev.gaius.browser.render.TelemetryCapture.publishedEntryBudget==64,
      "derived entry budget is published");
    BrowserMeshInstallQueue.drain(Long.MAX_VALUE,Long.MAX_VALUE);
    int publicationsBeforeOversize=dev.gaius.browser.render.TelemetryCapture.publishCount;
    Result oversize=new Result(1003L, BrowserMeshInstallQueue.beginRequest(1003L), 0, 2 * 1024 * 1024);
    BrowserMeshInstallQueue.enqueue(oversize);
    check(oversize.discarded==BrowserMeshInstallQueue.DISCARD_BUDGET,
      "an individually oversized result is released");
    check(BrowserMeshInstallQueue.readyCount()==0 && dev.gaius.browser.render.TelemetryCapture.publishedReadyCount==0,
      "empty queue eviction publishes its final state");
    check(dev.gaius.browser.render.TelemetryCapture.publishCount>publicationsBeforeOversize,
      "empty queue eviction emits telemetry immediately");
    Result epochResult=new Result(3001L, BrowserMeshInstallQueue.beginRequest(3001L), 0, 28);
    BrowserMeshInstallQueue.enqueue(epochResult);
    int publicationsBeforeEpoch=dev.gaius.browser.render.TelemetryCapture.publishCount;
    BrowserMeshInstallQueue.bumpLevelEpoch();
    check(BrowserMeshInstallQueue.readyCount()==0 && dev.gaius.browser.render.TelemetryCapture.publishedReadyCount==0,
      "epoch clear publishes an empty READY queue");
    check(dev.gaius.browser.render.TelemetryCapture.publishCount>publicationsBeforeEpoch,
      "epoch clear emits telemetry immediately");
    System.out.println("PASS: snapshot/update/rerequest, queued and handed-off updates, negative coordinates, epoch and neighbor readiness");
  }
}
`);
source("SnapshotPatchFixture.java", `
import dev.gaius.tools.patches.mesh.MeshKernelPatches;
import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
public class SnapshotPatchFixture {
  public static void main(String[] args) throws Exception {
    Path out=Path.of(args[1]); MeshKernelPatches.apply(args[0],out,args[2]);
    int stamps=0, invalidates=0;
    for (String name : new String[] {"net/minecraft/client/SectionUpdateTracker",
      "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$CompileTask"}) {
      ClassNode node=new ClassNode(); new ClassReader(Files.readAllBytes(out.resolve(name+".class"))).accept(node,0);
      for (MethodNode method : node.methods) {
        if (method.name.equals("<init>") || method.name.equals("doTask") || method.name.equals("setDirty")
            || method.name.startsWith("gaius$")) {
          new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name,method);
        }
        for (AbstractInsnNode insn : method.instructions) {
          if (insn instanceof MethodInsnNode call) {
            if (call.name.equals("snapshotSequence")) stamps++;
            if (call.name.equals("invalidateSection")) invalidates++;
          }
        }
      }
    }
    if (stamps!=1 || invalidates!=1) throw new AssertionError("missing lifecycle hooks");
    System.out.println("PASS "+args[2]+": constructor stamp, dirty hook and ASM stack verification");
  }
}
`);
const cp = [classes, fastutil, ...asm].join(delimiter);
run("javac", ["-J-Xmx3g", "-proc:none", "-encoding", "UTF-8", "-cp", cp, "-d", classes,
  join(root, "port/tools/src/main/java/dev/gaius/tools/BuildTarget.java"),
  join(root, "port/tools/src/main/java/dev/gaius/tools/PatchTargets.java"),
  join(root, "port/tools/src/main/java/dev/gaius/tools/PatchRegistry.java"),
  ...sources, join(root, "port/tools/src/main/java/dev/gaius/tools/VersionProfile.java"),
  join(root, "port/tools/src/main/java/dev/gaius/tools/patches/mesh/MeshKernelPatches.java")]);
const receipts = [
  run("java", ["-Xmx3g", "-ea", "-cp", cp, "SnapshotLifecycleFixture", "default-off"]),
  run("java", ["-Xmx3g", "-ea", "-cp", cp, "SnapshotLifecycleFixture", "entry-only"]),
  run("java", ["-Xmx3g", "-ea", "-cp", cp, "SnapshotLifecycleFixture", "aging"]),
  run("java", ["-Xmx3g", "-ea", "-cp", cp, "SnapshotLifecycleFixture"]),
];
const oldGate = join(out, "old-gate");
mkdirSync(oldGate, {recursive: true});
const readinessSource = readFileSync(join(out, "src/dev/gaius/browser/render/BrowserNeighborReadiness.java"), "utf8");
const oldReadiness = readinessSource.replace("int gateMode = mode();",
  "if (dev.gaius.browser.kernel.KernelMirror.enabled(\"mesher\")) return false;\n        int gateMode = mode();");
assert.notEqual(oldReadiness, readinessSource, "negative control must restore the old mesher gate");
const oldReadinessPath = join(oldGate, "BrowserNeighborReadiness.java");
writeFileSync(oldReadinessPath, oldReadiness);
run("javac", ["-J-Xmx3g", "-proc:none", "-cp", cp, "-d", oldGate, oldReadinessPath]);
let oldGateRejected = false;
try {
  run("java", ["-Xmx3g", "-ea", "-cp", [oldGate, cp].join(delimiter), "SnapshotLifecycleFixture"]);
} catch (error) {
  const failure = String(error.stderr || error.message);
  writeFileSync(join(out, "old-gate-control.log"), failure);
  oldGateRejected = failure.includes("kernel may compile at the visible streaming frontier");
}
assert(oldGateRejected, "lifecycle test must reject the old hard mesher gate");
receipts.push("PASS: old hard mesher gate rejected at the visible streaming frontier");
const control = join(out, "control");
mkdirSync(control, {recursive: true});
const queueSource = join(out, "src/dev/gaius/browser/render/BrowserMeshInstallQueue.java");
const controlSource = join(control, "BrowserMeshInstallQueue.java");
const originalQueue = readFileSync(queueSource, "utf8");
const brokenQueue = originalQueue.replace(/public static void invalidateSection\(int x, int y, int z\) \{[\s\S]*?\n    \}/,
  "public static void invalidateSection(int x, int y, int z) {}");
assert.notEqual(brokenQueue, originalQueue, "negative control must disable dirty invalidation");
writeFileSync(controlSource, brokenQueue);
run("javac", ["-J-Xmx3g", "-proc:none", "-cp", cp, "-d", control, controlSource]);
let rejected = false;
try {
  run("java", ["-Xmx3g", "-ea", "-cp", [control, cp].join(delimiter), "SnapshotLifecycleFixture"]);
} catch (error) {
  const failure = String(error.stderr || error.message);
  writeFileSync(join(out, "negative-control.log"), failure);
  rejected = failure.includes("update between task creation and submission");
}
assert(rejected, "lifecycle test must reject disabled invalidation");
receipts.push("PASS: negative control without dirty invalidation rejected");
for (const profile of profiles) {
  receipts.push(run("java", ["-Xmx3g", `-Dgaius.profile.json=${join(root, "port/versions", profile.id + ".json")}`,
    "-cp", cp, "SnapshotPatchFixture", join(root, "port/work", profile.id, "client-named.jar"),
    join(out, "patched", profile.id), profile.id]));
}
writeFileSync(join(out, "receipt.txt"), receipts.join("\n"));
console.log(receipts.join("\n"));

const lightFixture = join(out, "VisibleLightFixture.java");
writeFileSync(lightFixture, `package net.minecraft.world.level.lighting;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
public class VisibleLightFixture {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    BlockLightSectionStorage storage=new BlockLightSectionStorage(null);
    var visibleField=LayerLightSectionStorage.class.getDeclaredField("visibleSectionData");
    visibleField.setAccessible(true);
    DataLayerStorageMap<?> visible=(DataLayerStorageMap<?>)visibleField.get(storage);
    var queuedField=LayerLightSectionStorage.class.getDeclaredField("queuedSections");
    queuedField.setAccessible(true);
    Long2ObjectMap<DataLayer> queued=(Long2ObjectMap<DataLayer>)queuedField.get(storage);
    long node=SectionPos.asLong(-3,-4,2), block=BlockPos.asLong(-48,-64,32);
    visible.setLayer(node,new DataLayer(1)); visible.clearCache();
    queued.put(node,new DataLayer(15));
    if (storage.getDataLayerData(node).get(0,0,0)!=15 || storage.getLightValue(block)!=1)
      throw new AssertionError("queued and visible layers must disagree before publication");
    queued.remove(node); visible.setLayer(node,new DataLayer(9)); visible.clearCache();
    if (storage.getDataLayerData(node).get(0,0,0)!=9 || storage.getLightValue(block)!=9)
      throw new AssertionError("published update must be visible to the next request");
    System.out.println("PASS: vanilla queued=15/visible=1, then published=9 on rerequest");
  }
}
`);
for (const profile of profiles) {
  const deps = readFileSync(join(root, "port/work", profile.id, "classpath.txt"), "utf8")
    .trim().split(/;|:(?=\/|[A-Za-z]:\/)/u)
    .map(p => p.replace(/^\/([a-z])\//i, (_, drive) => `${drive.toUpperCase()}:/`));
  const lightClasses = join(out, "light", profile.id);
  mkdirSync(lightClasses, {recursive: true});
  const lightCp = [lightClasses, join(root, "port/work", profile.id, "client-named.jar"), ...deps].join(delimiter);
  run("javac", ["-J-Xmx3g", "-proc:none", "-cp", lightCp, "-d", lightClasses, lightFixture]);
  receipts.push(`${profile.id}: ` + run("java", ["-Xmx3g", "-ea", "-cp", lightCp,
    "net.minecraft.world.level.lighting.VisibleLightFixture"]));
}
writeFileSync(join(out, "receipt.txt"), receipts.join("\n"));
console.log(receipts.slice(-profiles.length).join("\n"));
