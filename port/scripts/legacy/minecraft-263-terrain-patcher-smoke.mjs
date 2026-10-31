#!/usr/bin/env node
// Minecraft 26.3 terrain pipeline and frame-loop patch smoke (work package P5).
//
// Builds the modern patch chain (MinecraftClientPatcher -> MinecraftChunkDrawTelemetryPatcher
// -> Minecraft262BrowserPatcher -> Minecraft263BrowserPatcher) on the vanilla 26.3 client jar
// in a temporary directory, with the 26.3 bring-up list active exactly like
// `GAIUS_BRINGUP=1 build-overlays.sh` (other packages' unfinished patches are skipped, P5 has
// none listed), then asserts the terrain hooks in the patched bytecode with javap and runs the
// ASM BasicVerifier over every patched terrain class.
//
//   node port/scripts/legacy/minecraft-263-terrain-patcher-smoke.mjs
//   node port/scripts/legacy/minecraft-263-terrain-patcher-smoke.mjs --client-jar \
//       port/work/overlays/26.3/client-named-26.3-gaius.jar     (assert an existing build)
//
// assertTerrain263(javapClass) is exported for the 26.3 p1 patcher smoke framework (P9):
// javapClass(binaryName) must return `javap -p -c` output for the patched jar.

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {copyFile, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};

export function method(bytecode, signature, nextSignature = "\n\n") {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = bytecode.indexOf(nextSignature, start + signature.length);
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

export function occurrences(haystack, needle) {
  return haystack.split(needle).length - 1;
}

function instructions(methodBytecode) {
  return methodBytecode.split(/\r?\n/).flatMap((line) => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), text: match[2]}] : [];
  });
}

function indexOfInstruction(list, needle, from = 0) {
  for (let index = from; index < list.length; index++) {
    if (list[index].text.includes(needle)) return index;
  }
  return -1;
}

const RENDERPEARL_BUFFER = "com/mojang/renderpearl/api/buffers/GpuBuffer";

/** Asserts every P5 terrain / frame-loop hook on a patched 26.3 client. */
export function assertTerrain263(rawJavapClass) {
  const javapClass = (name) => rawJavapClass(name).replaceAll("\r\n", "\n");
  const results = {};

  // DynamicGpuData: transforms and chunk-section UBOs start at the 32767 browser slab.
  const dynamic = javapClass("net.minecraft.client.renderer.DynamicGpuData");
  for (const label of ["Dynamic Transforms UBO", "Chunk Sections UBO"]) {
    const at = dynamic.indexOf(`// String ${label}`);
    assert.ok(at >= 0, `DynamicGpuData lost the ${label} storage`);
    const window = dynamic.slice(at, dynamic.indexOf("DynamicGpuDataStorageMapped.\"<init>\"", at));
    assert.match(window, /sipush\s+128[\s\S]*?sipush\s+32767/,
      `${label} initial capacity is not the browser slab`);
  }
  const terrainUbo = dynamic.slice(dynamic.indexOf("// String Terrain UBO"));
  assert.match(terrainUbo, /sipush\s+128\s*\n\s*\d+: iconst_1/,
    "the once-per-frame Terrain UBO must keep its vanilla capacity");

  // MappableRingBuffer: fence telemetry around the -1L (NO_TIMEOUT) wait.
  const ring = method(javapClass("net.minecraft.client.renderer.MappableRingBuffer"),
    `public ${RENDERPEARL_BUFFER.replaceAll("/", ".")} currentBuffer();`);
  const ringCode = instructions(ring);
  assert.match(ringCode[0].text, /BrowserOpenGL\.noteMappableRingCurrentBuffer/,
    "MappableRingBuffer.currentBuffer entry telemetry missing");
  const wait = indexOfInstruction(ringCode, "GpuFence.awaitCompletion:(J)Z");
  assert.match(ringCode[wait - 1].text, /ldc2_w\s+#\d+\s+\/\/ long -1l/,
    "26.3 fence wait must keep GpuFence.NO_TIMEOUT");
  assert.match(ringCode[wait + 1].text, /^dup/);
  assert.match(ringCode[wait + 2].text, /noteMappableRingAwaitResult:\(Z\)V/,
    "fence result telemetry missing after awaitCompletion");

  // StagedVertexBuffer pool: 26.3 recycles at the endFrame tail, after the helper's frame++.
  const pool = javapClass("net.minecraft.client.renderer.StagedVertexBuffer$GpuBufferPool");
  assert.ok(pool.includes("private final dev.gaius.browser.BrowserGpuBufferPoolCache gaius$browserCache;"),
    "pool cache field missing");
  const acquire = method(pool, "public com.mojang.renderpearl.api.buffers.GpuBuffer acquire(");
  assert.equal(occurrences(acquire, "tryRecycleBuffers"), 0,
    "26.3 acquire must not gain a recycle sweep");
  const acquireOrder = ["BrowserGpuBufferPoolCache.beforeAcquire", "Mth.roundToward",
    "BrowserGpuBufferPoolCache.afterRecycleSweep", "takeBestAvailable",
    "GpuDevice.createBuffer", "BrowserGpuBufferPoolCache.recordCreate",
    "java/util/List.add", "BrowserGpuBufferPoolCache.afterAcquire"];
  let cursor = -1;
  for (const step of acquireOrder) {
    const next = acquire.indexOf(step, cursor + 1);
    assert.ok(next > cursor, `acquire hook out of order at ${step}`);
    cursor = next;
  }
  const endFrame = instructions(method(pool,
    "public void endFrame(com.mojang.renderpearl.api.device.GpuDevice);"));
  const helperEnd = indexOfInstruction(endFrame, "BrowserGpuBufferPoolCache.endFrame:(I)V");
  const sweep = indexOfInstruction(endFrame, "tryRecycleBuffers:()V");
  assert.ok(helperEnd >= 0 && sweep > helperEnd,
    "the cache must advance its frame before the endFrame recycle sweep adopts buffers");
  assert.equal(endFrame[sweep + 1].text, "return", "endFrame recycle sweep is no longer the tail");
  assert.equal(indexOfInstruction(endFrame, "List.forEach"), -1,
    "endFrame still closes the available buffers");
  results.poolRecycleAfterHelperEndFrame = true;

  // GameRenderer frame loop.
  const gameRenderer = javapClass("net.minecraft.client.renderer.GameRenderer");
  const render = method(gameRenderer, "  public void render();");
  assert.match(instructions(render)[0].text, /BrowserRenderScheduler\.beginFrame/,
    "GameRenderer.render must open the browser frame budget first");
  const renderCode = instructions(render);
  // v0.4: QualityPatches263 routes the MinecraftClientPatcher throttle through the quality
  // runtime (reduced-rate world refresh behind inventory screens) and records rendered frames.
  assert.equal(indexOfInstruction(renderCode, "shouldSkipWorldRenderForScreen"), -1,
    "26.3 inventory throttle still calls BrowserOpenGL.shouldSkipWorldRenderForScreen");
  const skip = indexOfInstruction(renderCode,
    "BrowserQualityFrame.shouldSkipWorldRender:(Ljava/lang/Object;)Z");
  const renderLevel = indexOfInstruction(renderCode, "Method renderLevel:()V");
  assert.ok(skip >= 0 && renderLevel > skip, "inventory world-render throttle missing");
  assert.ok(indexOfInstruction(renderCode, "BrowserQualityFrame.worldFrameDone:(III)V") > renderLevel,
    "inventory throttle frame snapshot (worldFrameDone) missing after renderLevel");
  assert.equal(renderCode[skip + 2].text, "aload_1",
    "throttle skip path must pop the local-1 profiler");
  assert.match(renderCode[skip + 3].text, /ProfilerFiller\.pop/);
  assert.equal(renderCode[renderLevel - 1].text, "aload_0");
  assert.ok(indexOfInstruction(renderCode, "LevelLoadingScreen") > skip - 40,
    "stale loading screen close before world render missing");
  const extract = instructions(method(gameRenderer,
    "  public void extract(net.minecraft.client.DeltaTracker, boolean);"));
  const camera = indexOfInstruction(extract, "extractCamera:(Lnet/minecraft/client/DeltaTracker;F)V");
  const refresh = indexOfInstruction(extract, "BrowserTargeting.refreshFramePick");
  const level = indexOfInstruction(extract, "LevelExtractor.extract");
  assert.ok(camera >= 0 && refresh > camera && level > refresh,
    "frame targeting is not refreshed between camera and level extraction");
  assert.match(extract[refresh - 1].text, /Camera\.getCameraEntityPartialTicks/,
    "frame targeting must use the camera entity partial tick");
  assert.equal(extract[refresh - 2].text, "aload_1");
  assert.equal(occurrences(gameRenderer, "BrowserTargeting.stabilizeBlockHit"), 0,
    "26.3 must stay on the vanilla single-raycast targeting");
  const minecraft = javapClass("net.minecraft.client.Minecraft");
  const renderFrame = method(minecraft, "  public void renderFrame(boolean);");
  assert.equal(occurrences(renderFrame, "BrowserTargeting.deferFramePick"), 1,
    "Minecraft.renderFrame pick is not deferred");
  assert.equal(occurrences(renderFrame, "Method pick:(F)V"), 0,
    "Minecraft.renderFrame still picks before camera extraction");

  // LevelRenderer.
  const levelRenderer = javapClass("net.minecraft.client.renderer.LevelRenderer");
  const extractGroups = method(levelRenderer, "  private int extractSectionDrawGroups(");
  assert.match(levelRenderer, /private it\.unimi\.dsi\.fastutil\.ints\.Int2ObjectOpenHashMap gaius\$drawGroupCache;/,
    "reusable draw-group cache field missing");
  assert.equal((extractGroups.match(/new\s+#\d+\s+\/\/ class it\/unimi\/dsi\/fastutil\/ints\/Int2ObjectOpenHashMap/g) ?? []).length, 0,
    "draw-group extraction unexpectedly allocates its cache in the frame loop");
  assert.equal(occurrences(extractGroups, "Int2ObjectOpenHashMap.clear:()V"), 1,
    "draw-group cache is not cleared once per extraction");
  assert.equal(occurrences(extractGroups, "BrowserChunkSectionLayers.values"), 1,
    "per-section ChunkSectionLayer.values() clone is not redirected");
  assert.equal(occurrences(extractGroups, "ChunkSectionLayer.values"), 0);
  const groups = instructions(extractGroups);
  assert.match(groups[0].text, /aload_0/);
  assert.match(groups[1].text, /getfield .*levelRenderState/);
  assert.match(groups[2].text, /BrowserChunkDrawTelemetry\.beginPrepare/,
    "chunk-draw telemetry prepare begin missing");
  const register = indexOfInstruction(groups, "BrowserChunkDrawTelemetry.registerSection");
  assert.ok(register > 0, "chunk-draw section registration missing");
  assert.match(groups[register - 6].text, /^istore\s+15$/);
  assert.match(groups[register - 5].text, /^iload\s+15$/);
  const prepare = method(levelRenderer,
    "  public net.minecraft.client.renderer.chunk.ChunkSectionsToRender prepareChunkRenders(org.joml.Matrix4fc, boolean);");
  const prepareCode = instructions(prepare);
  const stats = indexOfInstruction(prepareCode, "BrowserChunkDrawTelemetry.recordPrepareStats:(II)V");
  assert.ok(stats > 0 && prepareCode[stats + 1].text === "areturn",
    "prepare statistics must be recorded right before the prepare result returns");
  assert.match(prepareCode[stats - 1].text, /^iload\s+8$/,
    "prepare statistics must report largestIndexCount (local 8)");
  assert.equal(occurrences(prepare, "BrowserChunkSectionLayers.values"), 1);
  assert.equal(occurrences(method(levelRenderer,
    "  private static void lambda$prepareChunkRenders$2("), "BrowserChunkDrawTelemetry.armUniformIndex"), 1,
  "uniform index arm missing from the ChunkSection callback");
  const levelRender = instructions(method(levelRenderer,
    "  public void render(com.mojang.blaze3d.resource.GraphicsResourceAllocator, boolean, net.minecraft.client.renderer.state.level.CameraRenderState"));
  const updates = levelRender.flatMap((entry, index) =>
    entry.text.includes("SectionOcclusionGraph.update:") ? [index] : []);
  assert.equal(updates.length, 2, "LevelRenderer.render must have the early and the late occlusion update");
  const terrainMatrix = indexOfInstruction(levelRender, "class org/joml/Matrix4f");
  const indirect = indexOfInstruction(levelRender, "prepareChunkRendersIndirect:");
  const separate = indexOfInstruction(levelRender, "Method prepareChunkRenders:");
  assert.ok(updates[0] + 1 === terrainMatrix && terrainMatrix < indirect && terrainMatrix < separate,
    "early occlusion update must sit right before the terrain matrix copy that both prepares follow");
  assert.equal(levelRender[updates[0] - 7].text, "aload_3", "early update must read camera state local 3");
  const late = updates[1];
  const clear = indexOfInstruction(levelRender, "Method clearVisibleSections:()V", late);
  const refill = indexOfInstruction(levelRender, "SectionOcclusionGraph.addSectionsInFrustum", late);
  assert.ok(late > separate && clear > late && refill > clear,
    "late visible-section refresh must clear the lists before refilling them");
  assert.match(levelRender[late + 3].text, /consumeFrustumUpdate/);
  assert.equal(levelRender[late + 5].text, "aload_3");
  const compile = method(levelRenderer,
    "  private void compileSections(net.minecraft.client.renderer.state.level.CameraRenderState);");
  const compileCode = instructions(compile);
  const consumption = compileCode.findIndex((entry, index) =>
    entry.text.includes("LevelRenderState.sectionUpdateRenderStates")
      && compileCode[index + 1]?.text.includes("List.clear:()V"));
  const resort = indexOfInstruction(compileCode, "String scheduleTranslucentResort");
  assert.ok(consumption > 0 && resort > consumption,
    "compileSections does not mark the extracted updates consumed before the resort pass");
  assert.equal(occurrences(compile, "List.clear:()V"), 1);
  const compileSync = indexOfInstruction(compileCode, "compileSync:");
  assert.ok(compileCode.slice(0, compileSync).some((entry) => entry.text === "iconst_0"),
    "synchronous section compile branch is not disabled");
  assert.equal(occurrences(compile, "canScheduleSection"), 0,
    "compileSections must never drop an extracted dirty section");
  const breaking = method(levelRenderer, "  private void submitBlockDestroyAnimation(");
  assert.equal(occurrences(breaking, "BrowserBlockBreakingTelemetry.recordSubmitPass"), 1);
  const breakingCode = instructions(breaking);
  const actual = indexOfInstruction(breakingCode, "BrowserBlockBreakingTelemetry.recordActualSubmit");
  assert.ok(actual > 0 && breakingCode[actual + 1].text.includes(
    "submitBreakingBlockModel:(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"),
  "breaking-model submit telemetry must precede the 26.3 (…IZ)V submit");
  assert.match(method(levelRenderer, "  private void submitBlockOutline("), /sipush\s+180/,
    "block outline opacity patch missing");
  const constructor = instructions(method(levelRenderer, "  public net.minecraft.client.renderer.LevelRenderer("));
  const mdiStore = indexOfInstruction(constructor, "putfield") >= 0
    ? constructor.findIndex((entry) => entry.text.includes("Field multiDrawIndirectAvailable:Z")
      && entry.text.startsWith("putfield")) : -1;
  assert.ok(mdiStore > 1 && constructor[mdiStore - 2].text !== "pop",
  "multi-draw-indirect terrain capability must reach the renderpearl device");

  // LevelExtractor: requeue/throttle/audit pair with the compileSections consumption above.
  const extractor = javapClass("net.minecraft.client.renderer.extract.LevelExtractor");
  const levelExtract = method(extractor,
    "  public void extract(net.minecraft.client.DeltaTracker, net.minecraft.client.Camera, float);");
  const levelExtractCode = instructions(levelExtract);
  assert.match(levelExtractCode[4].text, /BrowserSectionAudit\.requeueUnconsumed/,
    "LevelExtractor.extract must requeue unconsumed updates first");
  assert.equal(occurrences(levelExtract, "BrowserRenderScheduler.canScheduleSection"), 1);
  assert.ok(occurrences(levelExtract, "BrowserSectionAudit.afterExtract") >= 1);
  assert.equal(occurrences(levelExtract, "Camera.getCapturedFrustum"), 0,
    "captured-frustum short circuit is still present");
  const destroy = method(extractor, "  private void extractBlockDestroyAnimation(");
  assert.equal(occurrences(destroy, "BrowserBlockBreakingTelemetry.recordExtraction"), 1);
  assert.equal(occurrences(destroy, "BrowserBlockBreakingTelemetry.recordEmitted"), 1);

  // EntityRenderDispatcher null guard on the 26.3 (…DDDF)Z descriptor.
  const dispatcher = javapClass("net.minecraft.client.renderer.entity.EntityRenderDispatcher");
  const shouldRender = instructions(method(dispatcher,
    "boolean shouldRender(E, net.minecraft.client.renderer.culling.Frustum, double, double, double, float);"));
  assert.equal(shouldRender[0].text, "aload_1");
  assert.match(shouldRender[1].text, /^ifnonnull/);
  assert.equal(shouldRender[2].text, "iconst_0");
  assert.equal(shouldRender[3].text, "ireturn");

  // Section dispatcher budgets and telemetry.
  const sectionDispatcher = javapClass("net.minecraft.client.renderer.chunk.SectionRenderDispatcher");
  for (const vanilla of ["int 134217728", "int 33554432", "int 102760448"]) {
    assert.equal(occurrences(sectionDispatcher, `// ${vanilla}`), 0,
      `section renderer still allocates the vanilla ${vanilla}`);
  }
  const upload = method(sectionDispatcher, "  public void uploadTerrainBuffersToGpu();");
  assert.equal(occurrences(upload, "BrowserRenderScheduler.beginUploadPass"), 1);
  assert.equal(occurrences(upload, "BrowserRenderScheduler.endUploadPass"), 2);

  // UberGpuBuffer upload budget and bounded heap cleanup (interface close on 26.3).
  const uber = javapClass("com.mojang.blaze3d.vertex.UberGpuBuffer");
  const uploadStaged = method(uber, "  public boolean uploadStagedAllocations(com.mojang.renderpearl.api.device.GpuDevice");
  const cleanup = uploadStaged.slice(uploadStaged.indexOf("finishUploadBuffer"));
  for (const hook of ["beginUberNodeCleanup", "shouldCleanUberNode", "List.remove:(I)Ljava/lang/Object;",
    "finishUberNodeCleanup"]) {
    assert.ok(cleanup.includes(hook), `UberGpuBuffer cleanup lost ${hook}`);
  }
  assert.ok(!cleanup.includes("List.iterator"), "UberGpuBuffer cleanup still iterates every node");
  assert.match(cleanup, /invokeinterface #\d+,\s+1\s+\/\/ InterfaceMethod com\/mojang\/renderpearl\/api\/buffers\/GpuBuffer\.close:\(\)V/,
    "heap cleanup must close the renderpearl GpuBuffer through INVOKEINTERFACE");
  assert.ok(occurrences(uploadStaged, "BrowserRenderScheduler.shouldUploadNext") >= 1,
    "UberGpuBuffer upload budget missing");

  // StagingBuffer$Cpu.copyTo slices through the JDK compat helper.
  const staging = javapClass("com.mojang.blaze3d.vertex.StagingBuffer$Cpu");
  const copyTo = method(staging,
    "  protected void copyTo(com.mojang.renderpearl.api.commands.CommandEncoder, com.mojang.renderpearl.api.buffers.GpuBuffer, long, long, long);");
  assert.equal(occurrences(copyTo, "BrowserJdkCompat.slice"), 1);
  assert.equal(occurrences(copyTo, "java/nio/ByteBuffer.slice"), 0);

  // Section retry / latest-mesh guard (applied unchanged from 26.2).
  const renderSection = javapClass("net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection");
  assert.ok(renderSection.includes("public java.lang.Object gaius$latestMesh;"));
  assert.equal(occurrences(method(renderSection, "  private void checkSectionMesh("),
    "BrowserSectionAudit.staleMeshRejected"), 1, "stale mesh guard missing");
  assert.equal(occurrences(method(renderSection, "  public void reset();"), "Field gaius$latestMesh"), 1);
  assert.equal(occurrences(method(renderSection, "  private boolean addSectionBuffersToUberBuffer("),
    "BrowserRenderScheduler.requestEmergencyUpload"), 1);
  const compileTask = javapClass(
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask");
  assert.equal(occurrences(compileTask, "BrowserSectionAudit.requeueAfterUploadTimeout"), 1);
  assert.equal(occurrences(compileTask, "RenderSection.gaius$latestMesh"), 1);
  assert.equal(occurrences(compileTask, "BrowserRenderScheduler.awaitUploadRetry"), 1);
  assert.equal(occurrences(compileTask, "Thread.onSpinWait"), 0);

  // Occlusion graph and neighbour readiness.
  const graph = javapClass("net.minecraft.client.renderer.SectionOcclusionGraph");
  assert.equal(occurrences(method(graph, "  public void update("), "isFrustumCaptured"), 0);
  const loadedUpdates = instructions(method(graph, "  public void updateLoadedChunks("));
  const addLoaded = indexOfInstruction(loadedUpdates, "LongOpenHashSet.addAll");
  assert.ok(addLoaded >= 0);
  assert.match(loadedUpdates[addLoaded + 1]?.text ?? "", /^istore/,
    "loaded-chunk add mutation result must be retained");
  const removeLoaded = indexOfInstruction(loadedUpdates, "LongOpenHashSet.removeAll", addLoaded + 1);
  assert.ok(removeLoaded > addLoaded, "loaded-chunk removal mutation missing");
  assert.match(loadedUpdates[removeLoaded + 1]?.text ?? "", /^ifeq/,
    "loaded-chunk removal result must guard the merged invalidation");
  const invalidateLoaded = indexOfInstruction(loadedUpdates, "invalidate:()V", removeLoaded + 1);
  assert.ok(invalidateLoaded > removeLoaded,
    "loaded-chunk invalidation must follow both mutation results");

  // Chunk-draw telemetry draw half on the renderpearl frontend loop.
  const frontend = javapClass("com.mojang.renderpearl.frontend.FrontendRenderPass");
  const drawLoop = instructions(method(frontend, "void drawMultipleIndexed("));
  const begin = indexOfInstruction(drawLoop, "BrowserChunkDrawTelemetry.beginDraw");
  assert.ok(begin > 0 && drawLoop[begin + 1].text.includes("RenderPass$Draw.uniformUploaderConsumer"));
  const drawIndexed = indexOfInstruction(drawLoop, "RenderPassBackend.drawIndexed:(IIIII)V");
  assert.match(drawLoop[drawIndexed + 1].text, /^aload\s+9$/);
  assert.match(drawLoop[drawIndexed + 2].text, /RenderPass\$Draw\.indexCount/);
  assert.match(drawLoop[drawIndexed + 3].text, /BrowserChunkDrawTelemetry\.commitSuccessfulDraw/);

  results.levelRenderer = {earlyUpdateBeforeMatrix: true, refreshClears: true, mdiPinned: true};
  return results;
}

export const TERRAIN_263_CLASSES = [
  "net/minecraft/client/renderer/DynamicGpuData",
  "net/minecraft/client/renderer/MappableRingBuffer",
  "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool",
  "net/minecraft/client/renderer/GameRenderer",
  "net/minecraft/client/Minecraft",
  "net/minecraft/client/renderer/LevelRenderer",
  "net/minecraft/client/renderer/extract/LevelExtractor",
  "net/minecraft/client/renderer/entity/EntityRenderDispatcher",
  "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
  "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection",
  "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$CompileTask",
  "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$ResortTransparencyTask",
  "net/minecraft/client/renderer/SectionOcclusionGraph",
  "net/minecraft/client/SectionUpdateTracker",
  "com/mojang/blaze3d/vertex/UberGpuBuffer",
  "com/mojang/blaze3d/vertex/StagingBuffer$Cpu",
  "com/mojang/renderpearl/frontend/FrontendRenderPass",
];

const basicVerifierSource = String.raw`
import java.io.InputStream;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class Terrain263BasicVerifier {
    public static void main(String[] args) throws Exception {
        try (ZipFile jar = new ZipFile(args[0])) {
            for (int index = 1; index < args.length; index++) {
                var entry = jar.getEntry(args[index] + ".class");
                if (entry == null) throw new AssertionError("missing " + args[index]);
                ClassNode node = new ClassNode();
                try (InputStream input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(node, 0);
                }
                int methods = 0;
                for (MethodNode method : node.methods) {
                    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                    methods++;
                }
                System.out.println("BASIC_VERIFIER_OK " + node.name + " methods=" + methods);
            }
        }
    }
}
`;

function selectJdk() {
  const homes = [
    process.env.GAIUS_JAVA_HOME && nativePath(process.env.GAIUS_JAVA_HOME),
    process.env.JAVA_HOME && nativePath(process.env.JAVA_HOME),
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
  throw new Error("26.3 terrain smoke requires JDK 25 (set GAIUS_JAVA_HOME or JAVA_HOME)");
}

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

async function authlibJar() {
  const classpath = (await readFile(path.join(repositoryRoot, "port/work/26.3/classpath.txt"),
    "utf8")).trim();
  for (const element of splitClasspath(classpath)) {
    const normalized = element.replaceAll("\\", "/");
    if (!normalized.includes("/com/mojang/authlib/") || !normalized.endsWith(".jar")) continue;
    const relative = normalized.toLowerCase().indexOf("/port/work/");
    const candidate = relative >= 0
      ? path.join(repositoryRoot, normalized.slice(relative + 1)) : nativePath(normalized);
    if (existsSync(candidate)) return candidate;
  }
  throw new Error("cannot locate the 26.3 authlib jar from port/work/26.3/classpath.txt");
}

async function buildChain(tools, workDirectory) {
  const maven = path.join(homedir(), ".m2/repository/org/ow2/asm");
  const asm = [path.join(maven, "asm/9.8/asm-9.8.jar"), path.join(maven, "asm-tree/9.8/asm-tree-9.8.jar")];
  const classes = path.join(workDirectory, "tools");
  const patches = path.join(workDirectory, "client-patches");
  const clientJar = path.join(workDirectory, "client-26.3.jar");
  await mkdir(classes, {recursive: true});
  await mkdir(patches, {recursive: true});
  await copyFile(path.join(repositoryRoot, "port/work/26.3/client-named.jar"), clientJar);
  const toolsSource = path.join(repositoryRoot, "port/tools/src/main/java");
  execFileSync(tools.javac, ["--release", "21", "-proc:none",
    "-classpath", asm.join(path.delimiter), "-sourcepath", toolsSource, "-d", classes,
    ...["MinecraftClientPatcher", "MinecraftChunkDrawTelemetryPatcher",
      "Minecraft262BrowserPatcher", "Minecraft263BrowserPatcher"].map((name) =>
      path.join(toolsSource, `dev/gaius/tools/${name}.java`))],
  {encoding: "utf8", timeout: 180_000});
  const properties = [
    "-Dgaius.profile=26.3",
    "-Dgaius.bringup=1",
    `-Dgaius.bringup.list=${path.join(repositoryRoot, "port/tools/bringup/26.3.txt")}`,
    `-Dgaius.authlib.jar=${await authlibJar()}`,
  ];
  const log = [];
  const step = (main, args) => {
    log.push(execFileSync(tools.java, ["-classpath", [classes, ...asm].join(path.delimiter),
      ...properties, `dev.gaius.tools.${main}`, ...args],
    {encoding: "utf8", timeout: 300_000, maxBuffer: 64 * 1024 * 1024}));
    execFileSync(tools.jar, ["--update", "--file", clientJar, "-C", patches, "."],
      {encoding: "utf8", timeout: 300_000});
  };
  // The REQUIRED class-initialization patch rewrites a Gaius source class that
  // the overlay build compiles into the client jar; seed it as patchkit does.
  const seedClasses = path.join(workDirectory, "client-source-classes");
  execFileSync(tools.javac, ["--release", "21", "-proc:none", "-d", seedClasses,
    path.join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserClassInitialization.java")],
  {encoding: "utf8", timeout: 60_000});
  execFileSync(tools.jar, ["--update", "--file", clientJar, "-C", seedClasses,
    "dev/gaius/browser/BrowserClassInitialization.class"], {encoding: "utf8", timeout: 60_000});
  step("MinecraftClientPatcher", [clientJar, patches, "26.3"]);
  step("MinecraftChunkDrawTelemetryPatcher", ["26.3", clientJar, patches]);
  step("Minecraft262BrowserPatcher", [clientJar, patches, "26.3"]);
  step("Minecraft263BrowserPatcher", [clientJar, patches, "26.3"]);
  return {clientJar, asm, log: log.join("")};
}

async function main() {
  const argumentIndex = process.argv.indexOf("--client-jar");
  const tools = selectJdk();
  const workDirectory = await mkdtemp(path.join(tmpdir(), "gaius-263-terrain-"));
  try {
    let clientJar;
    let asm;
    let log = "";
    if (argumentIndex > 0) {
      clientJar = path.resolve(nativePath(process.argv[argumentIndex + 1]));
      const maven = path.join(homedir(), ".m2/repository/org/ow2/asm");
      asm = [path.join(maven, "asm/9.8/asm-9.8.jar"), path.join(maven, "asm-tree/9.8/asm-tree-9.8.jar")];
    } else {
      ({clientJar, asm, log} = await buildChain(tools, workDirectory));
      for (const line of [
        "Raised 26.3 DynamicGpuData transforms and chunk-section UBO capacity",
        "Instrumented renderpearl MappableRingBuffer.currentBuffer fence-result telemetry",
        "Instrumented renderpearl StagedVertexBuffer GPU pool cache: count=4 bytes=1048576 idle=3",
        "Patched 26.3 section draw-group extraction and prepare statistics",
        "Patched 26.3 early occlusion update before both chunk prepares",
        "Patched renderpearl visible section refresh after late occlusion update",
        "CHUNK_DRAW_TELEMETRY_PATCH_OK profile=26.3",
        "Moved Minecraft 26.3 frame targeting after camera extraction",
        "Bounded Minecraft renderpearl UberGpuBuffer heap cleanup",
        "Preserved 26.3 LevelRenderer.multiDrawIndirectAvailable capability gate",
        "Patched 26.3 reusable LevelRenderer draw-group cache",
        "Verified 26.3 terrain chain",
      ]) {
        assert.ok(log.includes(line), `patch chain log is missing: ${line}`);
      }
      const p5Skips = log.split(/\r?\n/).filter((line) => /^BRINGUP_SKIP /.test(line)
        && /(DynamicUniforms|MappableRing|StagedVertexBuffer|GameRenderer|LevelRenderer|EntityRenderDispatcher|SectionRenderDispatcher|UberGpuBuffer|LiveFrameTargeting|StagingBuffer|TerrainPatches263)/.test(line));
      assert.deepEqual(p5Skips, [], "a P5 terrain patch was bring-up skipped");
    }
    const javapClass = (name) => execFileSync(tools.javap, ["-classpath", clientJar, "-p", "-c", name],
      {encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 120_000});
    const results = assertTerrain263(javapClass);

    const verifierDirectory = path.join(workDirectory, "verifier");
    await mkdir(verifierDirectory, {recursive: true});
    const verifierFile = path.join(verifierDirectory, "Terrain263BasicVerifier.java");
    await writeFile(verifierFile, basicVerifierSource, "utf8");
    const analysis = path.join(homedir(), ".m2/repository/org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar");
    const verifierClasspath = [...asm, analysis].join(path.delimiter);
    execFileSync(tools.javac, ["--release", "21", "-proc:none", "-classpath", verifierClasspath,
      "-d", verifierDirectory, verifierFile], {encoding: "utf8", timeout: 120_000});
    const verified = execFileSync(tools.java, ["-classpath",
      [verifierDirectory, verifierClasspath].join(path.delimiter), "Terrain263BasicVerifier",
      clientJar, ...TERRAIN_263_CLASSES], {encoding: "utf8", timeout: 300_000});
    assert.equal(occurrences(verified, "BASIC_VERIFIER_OK "), TERRAIN_263_CLASSES.length);
    process.stdout.write(verified);
    console.log("Minecraft 26.3 terrain patcher smoke passed", JSON.stringify({
      source: argumentIndex > 0 ? "client-jar" : "patch-chain",
      verifiedClasses: TERRAIN_263_CLASSES.length,
      ...results,
    }));
  } finally {
    await rm(workDirectory, {recursive: true, force: true});
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await main();
}
