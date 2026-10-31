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
source("dev/gaius/browser/kernel/KernelMirror.java", `package dev.gaius.browser.kernel;
public class KernelMirror { public static boolean mesher; public static boolean enabled(String name) { return mesher; } }
`);
source("dev/gaius/browser/render/BrowserMeshInstallQueue.java",
  readFileSync(join(root, "port/src/main/java/dev/gaius/browser/render/BrowserMeshInstallQueue.java"), "utf8")
    .replace(/private static native void publish\(([^;]+)\);/, "private static void publish($1) {}"));
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
  static class Result implements BrowserMeshInstall {
    final long node; final int seq, level, resources; int installed, discarded;
    Result(long node, int seq) { this.node=node; this.seq=seq;
      level=BrowserMeshInstallQueue.levelEpoch(); resources=BrowserMeshInstallQueue.resourceEpoch(); }
    public long sectionNode() { return node; } public int requestSeq() { return seq; }
    public int levelEpoch() { return level; } public int resourceEpoch() { return resources; }
    public int byteSize() { return 28; } public boolean install() { installed++; return true; }
    public void discard(int reason) { discarded=reason; }
  }
  public static void main(String[] args) {
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
const receipts = [run("java", ["-Xmx3g", "-ea", "-cp", cp, "SnapshotLifecycleFixture"])];
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
