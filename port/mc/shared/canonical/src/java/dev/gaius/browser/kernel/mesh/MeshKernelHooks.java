package dev.gaius.browser.kernel.mesh;

import dev.gaius.browser.HostLoop;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.gaius.browser.BrowserRenderScheduler;
import dev.gaius.browser.BrowserSectionAudit;
import dev.gaius.browser.render.BrowserMeshInstall;
import dev.gaius.browser.render.BrowserMeshInstallQueue;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.BrowserMemory;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSObject;

/**
 * Section compiles through the Rust mesher kernel (the selected generation supplies the render
 * API imports). {@code dev.gaius.tools.patches.mesh.MeshKernelPatches} wires two calls into
 * {@code SectionRenderDispatcher.RenderSection.CompileTask}:
 *
 * <ol>
 *   <li>{@link #beforeCompile} at the start of {@code doTask}. When the kernel is ready and the
 *       model table of the dispatcher's SectionCompiler is exported, it snapshots the region
 *       ({@link SectionSnapshot}), submits a mesh job and returns true; {@code doTask} then
 *       returns SUCCESSFUL without compiling, and the section stays pending in
 *       {@link BrowserMeshInstallQueue}. Otherwise vanilla {@code doTask} runs as before.</li>
 *   <li>When the job finishes, {@link #drainResults} hands it to the install queue; its install
 *       step (once per frame, within BrowserRenderScheduler's install budget) re-opens the task
 *       and schedules it again on the dispatcher. That second {@code doTask} is the vanilla one
 *       with its {@code SectionCompiler.compile} call routed through {@link #compile}, which
 *       returns the kernel's meshes as vanilla {@code SectionCompiler.Results}: the vertex bytes
 *       are copied straight into the pack's layer buffers and the translucent index buffer is
 *       written in the kernel's sort order. Everything after compile (CompiledSectionMesh,
 *       uber buffer upload, the latest-mesh guard, upload retries) is the vanilla path.</li>
 * </ol>
 *
 * <p>Stale results are dropped: the install queue checks the request sequence and the level and
 * resource epochs, and a task that vanilla cancelled (re-dirtied, reset, moved) never installs.
 * At most one request per section is out: a task created while one is (the section was dirtied
 * again before the kernel answered) runs the vanilla compiler, so every re-dirty still makes
 * progress.
 * A job the kernel refused (a state it cannot express), a failed job or a disabled kernel sends
 * the task through the vanilla compiler instead.</p>
 *
 * <p>No section waits on the kernel forever: a job without an answer after
 * {@value #ANSWER_TIMEOUT_MILLIS} ms, or any job still out when the kernel gets disabled, is
 * handed to the install queue as a failure and compiled by vanilla (the timeout counts as a
 * kernel failure, so a hung kernel disables itself). Until then the section stays pending in
 * {@link BrowserMeshInstallQueue}, which keeps BrowserSectionAudit from re-dirtying it. A
 * dropped result whose section was left with no compile at all (cancelled, same position, still
 * uncompiled, no newer request) is marked dirty again.</p>
 */
public final class MeshKernelHooks {
    /** The kernel result was consumed, or vanilla compiled the task. */
    static final Object DONE = new Object();
    /** Compile this task with the vanilla compiler. */
    static final Object VANILLA = new Object();

    private static final int VANILLA_VERTEX_BYTES = 28;
    private static final int NEARBY_BLOCKS = 32;
    private static final long EXPORT_SLICE_NANOS = 8_000_000L;
    /**
     * Bounded section wait. A TeaVM system property allows slow-machine validation to raise it,
     * while the 25 s default keeps a lost worker reply from parking resource reload forever.
     */
    private static final long ANSWER_TIMEOUT_MILLIS = configuredAnswerTimeoutMillis();
    private static final long ANSWER_TIMEOUT_NANOS = ANSWER_TIMEOUT_MILLIS * 1_000_000L;
    private static final ChunkSectionLayer[] LAYERS = {
        ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT};
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final MeshJobBuffers BUFFERS = new MeshJobBuffers();
    private static final HashMap<Integer, InFlight> IN_FLIGHT = new HashMap<>();
    /** Submitted jobs, oldest first, for the answer timeout; answered ones are skipped lazily. */
    private static final ArrayDeque<InFlight> SUBMIT_ORDER = new ArrayDeque<>();
    private static int nextTicket = 1;
    private static boolean drainerRegistered;
    private static MeshModelTable table;
    private static ModelTableExporter exporter;
    private static Object failedModels;
    private static Object cachedModels;
    private static boolean cachedTableTried;
    private static int nextEpoch = 1;
    private static long exporterStartedNanos;
    private static String exporterScope;
    private static int exporterResourceEpoch;
    private static int exporterLevelEpoch;

    private MeshKernelHooks() {
    }

    private static long configuredAnswerTimeoutMillis() {
        try {
            long value = Long.parseLong(System.getProperty("gaius.mesh.answerTimeoutMs", "25000"));
            return Math.max(5_000L, Math.min(120_000L, value));
        } catch (RuntimeException ignored) {
            return 25_000L;
        }
    }

    /** One submitted job. */
    static final class InFlight {
        final MeshKernelAccess.Task task;
        final SectionRenderDispatcher.RenderSection section;
        final long node;
        final int requestSeq;
        final int levelEpoch;
        final int resourceEpoch;
        final int ticket;
        final long submittedNanos;
        final List<BlockEntity> blockEntities;
        /** Point of view of the camera the kernel sorted the translucent quads for. */
        final TranslucencyPointOfView pointOfView;
        /** An answer (or the timeout) was handed to the install queue. */
        boolean answered;

        InFlight(MeshKernelAccess.Task task, SectionRenderDispatcher.RenderSection section, long node,
                int requestSeq, int ticket, List<BlockEntity> blockEntities, Vec3 camera) {
            this.task = task;
            this.section = section;
            this.node = node;
            this.requestSeq = requestSeq;
            this.levelEpoch = BrowserMeshInstallQueue.levelEpoch();
            this.resourceEpoch = BrowserMeshInstallQueue.resourceEpoch();
            this.ticket = ticket;
            this.submittedNanos = System.nanoTime();
            this.blockEntities = blockEntities;
            this.pointOfView = TranslucencyPointOfView.of(camera, node);
        }

        boolean alive() {
            return task.gaius$meshKernelState() == this && !task.gaius$meshKernelCancelled()
                    && section.getSectionNode() == node;
        }

        void redirtyStaleSnapshot() {
            if (levelEpoch == BrowserMeshInstallQueue.levelEpoch()
                    && section.getSectionNode() == node && !task.gaius$meshKernelCancelled()
                    && !BrowserMeshInstallQueue.isPending(node)) {
                // Never rerun the old region: its immutable block copies precede the update.
                task.gaius$setMeshKernelState(DONE);
                BrowserSectionAudit.requeueAfterKernelDrop(section);
            }
        }

        /**
         * Called when this job's result is dropped because the task is no longer alive. Vanilla
         * cancels a task when it replaces it or moves the section, so normally another compile
         * follows; a section that is still uncompiled at the same position, with no compile and
         * no newer kernel request, would stay a hole, so it is marked dirty again. With uploads
         * queued a finished vanilla compile may still be on its way to the section, so that case
         * is left to BrowserSectionAudit.
         */
        void redirtyIfOrphaned() {
            if (levelEpoch != BrowserMeshInstallQueue.levelEpoch()
                    || section.getSectionNode() != node
                    || section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED
                    || section.gaius$hasPendingCompile()
                    || !BrowserMeshInstallQueue.isLatest(node, requestSeq)
                    || BrowserRenderScheduler.uploadBacklog() > 0) {
                return;
            }
            BrowserSectionAudit.requeueAfterKernelDrop(section);
        }
    }

    /** A meshed result waiting for the task's second run. */
    static final class Ready {
        final InFlight job;
        final JSObject record;
        /** Camera of the second doTask run (the one vanilla sorts and records its point of view for). */
        Vec3 runCamera;

        Ready(InFlight job, JSObject record) {
            this.job = job;
            this.record = record;
        }
    }

    /**
     * Start of {@code CompileTask.doTask}: true when the section went to the kernel (doTask then
     * returns SUCCESSFUL), false to run the vanilla doTask.
     */
    public static boolean beforeCompile(Object taskObject, SectionCompiler compiler, Vec3 camera) {
        MeshKernelAccess.Task task = (MeshKernelAccess.Task) taskObject;
        Object state = task.gaius$meshKernelState();
        if (state instanceof Ready ready) {
            InFlight job = ready.job;
            if (!BrowserMeshInstallQueue.isLatest(job.node, job.requestSeq)
                    || job.levelEpoch != BrowserMeshInstallQueue.levelEpoch()
                    || job.resourceEpoch != BrowserMeshInstallQueue.resourceEpoch()) {
                job.redirtyStaleSnapshot();
                return true;
            }
            ready.runCamera = camera;
            return false;
        }
        if (state != null || compiler == null || camera == null
                || task.gaius$meshKernelCancelled() || !MeshKernelBridge.ready()) {
            return false;
        }
        if (!drainerRegistered) {
            drainerRegistered = true;
            MeshKernelBridge.setDrainer(MeshKernelHooks::drainResults);
        }
        try {
            drainResults();
            int blend = Minecraft.getInstance().options.biomeBlendRadius().get();
            if (blend < 0 || blend > MeshKernelBridge.MAX_BIOME_BLEND) {
                // The snapshot's biome grid reaches two columns past the section; wider blends
                // stay with the vanilla compiler.
                MeshKernelBridge.noteBlendSkip();
                return false;
            }
            MeshModelTable current = currentTable(compiler);
            if (current == null) {
                return false;
            }
            return submit(task, compiler, camera, current, blend);
        } catch (RuntimeException error) {
            MeshKernelBridge.noteFailure("snapshot: " + error);
            return false;
        }
    }

    /**
     * Starts the model-table export as soon as the renderer's compiler has its resource models.
     * SectionCompiler is constructed during the client resource reload, before the title screen
     * becomes interactive, so the first terrain compile does not have to create this 5 MB table.
     * The existing cache and built-in pack scope checks remain the single source of truth.
     */
    public static void prewarm(SectionCompiler compiler) {
        if (compiler == null || !dev.gaius.browser.kernel.KernelMirror.enabled("mesher")) {
            return;
        }
        try {
            currentTable(compiler);
        } catch (RuntimeException error) {
            System.out.println("[mesh-kernel] title prewarm skipped: " + error);
        }
    }

    private static boolean submit(MeshKernelAccess.Task task, SectionCompiler compiler, Vec3 camera,
            MeshModelTable current, int blend) {
        long start = System.nanoTime();
        SectionRenderDispatcher.RenderSection section = task.gaius$meshSection();
        RenderSectionRegion region = task.gaius$meshRegion();
        if (section == null || region == null) {
            return false;
        }
        long node = section.getSectionNode();
        if (!BrowserMeshInstallQueue.isLatest(node, task.gaius$meshSnapshotSequence())) {
            // A dirty notification can precede creation of the replacement vanilla task.
            task.gaius$setMeshKernelState(DONE);
            BrowserSectionAudit.requeueAfterKernelDrop(section);
            return true;
        }
        if (BrowserMeshInstallQueue.isPending(node)) {
            // A request for this section is still out and vanilla just replaced its task (the
            // section was dirtied again): compile this one directly, so a section re-dirtied
            // faster than the kernel round trip (redstone clocks, the loading frontier) still
            // gets a mesh. The old result is dropped as not alive when it arrives.
            return false;
        }
        List<BlockEntity> blockEntities = new ArrayList<>();
        MeshJobBuffers b = BUFFERS;
        if (!SectionSnapshot.capture(b, region, node, current, camera, blockEntities, blend)) {
            return false;
        }
        MeshKernelAccess.Compiler options = (MeshKernelAccess.Compiler) (Object) compiler;
        int sx = SectionPos.x(node);
        int sy = SectionPos.y(node);
        int sz = SectionPos.z(node);
        double cx = (sx << 4) + 8.0 - camera.x;
        double cy = (sy << 4) + 8.0 - camera.y;
        double cz = (sz << 4) + 8.0 - camera.z;
        int distance = (int) Math.min(1.0e6, Math.sqrt(cx * cx + cy * cy + cz * cz));
        boolean recompile = ((SectionRenderDispatcher.RenderSection.SectionTask) (Object) task).isRecompile();
        int requestSeq = BrowserMeshInstallQueue.beginRequest(node);
        int ticket = nextTicket++;
        if (nextTicket == Integer.MAX_VALUE) {
            nextTicket = 1;
        }
        int[] h = b.header;
        h[MeshKernelBridge.H_TICKET] = ticket;
        h[MeshKernelBridge.H_EPOCH] = current.epoch;
        h[MeshKernelBridge.H_WIDE] = b.wide ? 1 : 0;
        h[MeshKernelBridge.H_FLAGS] = (options.gaius$ambientOcclusion() ? MeshKernelBridge.FLAG_AO : 0)
                | (options.gaius$cutoutLeaves() ? MeshKernelBridge.FLAG_CUTOUT_LEAVES : 0)
                | (recompile && distance <= NEARBY_BLOCKS ? MeshKernelBridge.FLAG_NEARBY : 0);
        h[MeshKernelBridge.H_SX] = sx;
        h[MeshKernelBridge.H_SY] = sy;
        h[MeshKernelBridge.H_SZ] = sz;
        h[MeshKernelBridge.H_REQUEST_SEQ] = requestSeq;
        h[MeshKernelBridge.H_NON_AIR] = SectionSnapshot.lastNonAir;
        h[MeshKernelBridge.H_DISTANCE] = distance;
        h[MeshKernelBridge.H_BLEND] = blend;
        boolean sent;
        try {
            sent = MeshKernelBridge.submit(h, b.ids16, b.ids32, b.light, b.quarts, b.palette, b.swamp, b.floats);
        } catch (RuntimeException error) {
            BrowserMeshInstallQueue.cancel(node, requestSeq);
            throw error;
        }
        if (!sent) {
            BrowserMeshInstallQueue.cancel(node, requestSeq);
            return false;
        }
        InFlight job = new InFlight(task, section, node, requestSeq, ticket, blockEntities, camera);
        IN_FLIGHT.put(ticket, job);
        SUBMIT_ORDER.addLast(job);
        task.gaius$setMeshKernelState(job);
        MeshKernelBridge.noteSnapshotNanos(System.nanoTime() - start);
        return true;
    }

    /**
     * Replacement for the {@code SectionCompiler.compile} call inside vanilla {@code doTask}: the
     * kernel's meshes when this task carries them, the vanilla compiler otherwise.
     */
    public static SectionCompiler.Results compile(SectionCompiler compiler, SectionPos pos,
            RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack pack, Object taskObject) {
        MeshKernelAccess.Task task = (MeshKernelAccess.Task) taskObject;
        Object state = task.gaius$meshKernelState();
        if (state instanceof Ready ready) {
            task.gaius$setMeshKernelState(DONE);
            long start = System.nanoTime();
            try {
                SectionCompiler.Results results = buildResults(ready, pack, sorting);
                MeshKernelBridge.noteInstallNanos(System.nanoTime() - start);
                return results;
            } catch (RuntimeException error) {
                MeshKernelBridge.noteFailure("install: " + error);
            } finally {
                MeshKernelBridge.release(ready.record);
            }
        } else if (state != null && state != DONE) {
            task.gaius$setMeshKernelState(DONE);
        }
        return compiler.compile(pos, region, sorting, pack);
    }

    /**
     * Moves finished kernel jobs into the install queue, then hands jobs the kernel did not
     * answer in time (or any job, once the kernel is disabled) to it as failures.
     */
    public static void drainResults() {
        JSObject record;
        while ((record = MeshKernelBridge.poll()) != null) {
            InFlight job = IN_FLIGHT.remove(MeshKernelBridge.ticket(record));
            if (job == null) {
                // Timed out before: vanilla compiled the task already.
                continue;
            }
            job.answered = true;
            int status = MeshKernelBridge.status(record);
            int bytes = 0;
            if (status == MeshKernelBridge.STATUS_MESHED) {
                int quads = MeshKernelBridge.quadCount(record, 0) + MeshKernelBridge.quadCount(record, 1)
                        + MeshKernelBridge.quadCount(record, 2);
                bytes = quads * 4 * VANILLA_VERTEX_BYTES;
                MeshKernelBridge.noteMeshed();
            } else if (status == MeshKernelBridge.STATUS_FAILED) {
                String code = MeshKernelBridge.code(record);
                if (!schedulingOutcome(code)) {
                    MeshKernelBridge.noteFailure(code + ": " + MeshKernelBridge.message(record));
                }
            }
            BrowserMeshInstallQueue.enqueue(new Install(job, record, status, bytes));
        }
        expireUnanswered();
    }

    /** Oldest-first sweep; cheap while the oldest job out is young. */
    private static void expireUnanswered() {
        long now = System.nanoTime();
        while (!SUBMIT_ORDER.isEmpty()) {
            InFlight job = SUBMIT_ORDER.peekFirst();
            if (job.answered) {
                SUBMIT_ORDER.pollFirst();
                continue;
            }
            boolean kernelOff = MeshKernelBridge.disabled();
            if (!kernelOff && now - job.submittedNanos < ANSWER_TIMEOUT_NANOS) {
                return;
            }
            SUBMIT_ORDER.pollFirst();
            IN_FLIGHT.remove(job.ticket);
            job.answered = true;
            if (!kernelOff) {
                MeshKernelBridge.disable("section-reply-timeout");
                MeshKernelBridge.cancelAll();
                System.out.println("[mesh-kernel] section reply timed out after "
                        + ANSWER_TIMEOUT_MILLIS + " ms; using vanilla compiler");
            }
            BrowserMeshInstallQueue.enqueue(new Install(job, null, MeshKernelBridge.STATUS_FAILED, 0));
        }
    }

    private static boolean schedulingOutcome(String code) {
        return "superseded".equals(code) || "stale".equals(code) || "cancelled".equals(code)
                || "backpressure".equals(code) || "request-cap".equals(code);
    }

    /**
     * The model table of {@code compiler}'s resources; null (vanilla compiles) until its export,
     * started here on its own TeaVM thread, has finished.
     */
    private static MeshModelTable currentTable(SectionCompiler compiler) {
        MeshKernelAccess.Compiler access = (MeshKernelAccess.Compiler) (Object) compiler;
        var models = access.gaius$blockModelSet();
        var fluids = access.gaius$fluidModelSet();
        var colors = access.gaius$blockColors();
        if (models == null || fluids == null || colors == null) {
            return null;
        }
        String currentScope = tableCacheScope();
        int currentResourceEpoch = BrowserMeshInstallQueue.resourceEpoch();
        int currentLevelEpoch = BrowserMeshInstallQueue.levelEpoch();
        MeshModelTable current = table;
        if (current != null && current.matches(models, fluids, colors)) {
            if (!MeshKernelBridge.hasTable(current.epoch)
                    && !MeshKernelBridge.setTable(current.bytes, current.bytes.length, current.epoch)) {
                return null;
            }
            return current;
        }
        if (failedModels == models) {
            return null;
        }
        if (cachedModels != models) {
            cachedModels = models;
            cachedTableTried = false;
        }
        if (!cachedTableTried && MeshKernelBridge.tableCacheReady()) {
            cachedTableTried = true;
            byte[] cachedBytes = currentScope == null ? null : MeshKernelBridge.takeCachedTable(currentScope);
            MeshModelTable cached = MeshModelTable.fromCached(cachedBytes, models, fluids, colors);
            if (cached != null) {
                nextEpoch = Math.max(nextEpoch, cached.epoch + 1);
                if (MeshKernelBridge.setTable(cached.bytes, cached.bytes.length, cached.epoch)) {
                    table = cached;
                    System.out.println("[mesh-kernel] model table cache hit: " + cached.bytes.length
                            + " bytes, epoch " + cached.epoch);
                    return cached;
                }
            }
        }
        if (exporter != null && (!exporter.matches(models, fluids, colors)
                || exporterResourceEpoch != currentResourceEpoch
                || exporterLevelEpoch != currentLevelEpoch
                || !Objects.equals(exporterScope, currentScope))) {
            // A reload, pack switch or level leave invalidates the worker's immutable inputs.
            // Drop it without poisoning this model identity; the next main-thread call retries.
            exporter = null;
            exporterScope = null;
            System.out.println("[mesh-kernel] discarded model table export after resource/level change");
        }
        if (exporter != null && System.nanoTime() - exporterStartedNanos >= ANSWER_TIMEOUT_NANOS) {
            exporter = null;
            exporterScope = null;
            failedModels = models;
            MeshKernelBridge.disable("model-table-export-timeout");
            MeshKernelBridge.cancelAll();
            System.out.println("[mesh-kernel] model table export timed out after "
                    + ANSWER_TIMEOUT_MILLIS + " ms; using vanilla compiler");
            return null;
        }
        if (exporter == null || !exporter.matches(models, fluids, colors)) {
            table = null;
            ModelTableExporter job = new ModelTableExporter(models, fluids, colors, nextEpoch++);
            exporter = job;
            exporterStartedNanos = System.nanoTime();
            exporterScope = currentScope;
            exporterResourceEpoch = currentResourceEpoch;
            exporterLevelEpoch = currentLevelEpoch;
            // About 0.6 s of work for every block state: run it in slices separated by one
            // event-loop turn on a thread of its own, so the kernel is ready for the first chunks
            // of a world instead of advancing a few milliseconds per compile request.
            HostLoop.startThread(() -> runExport(job, models, currentScope,
                    currentResourceEpoch, currentLevelEpoch));
        }
        return null;
    }

    /** Pack ids whose contents ship with the client, so the asset hash already identifies them. */
    private static final java.util.Set<String> BUILT_IN_PACKS = java.util.Set.of(
            "vanilla", "programmer_art", "high_contrast");

    /**
     * The resource pack stack the model table may be cached under, or null when it must not be
     * cached: the persisted table is keyed by the shipped asset hash, so any pack whose contents
     * are not part of the shipped assets (a user pack, a server resource pack) changes block
     * geometry without changing that key. Built-in packs are listed in order in the scope.
     */
    private static String tableCacheScope() {
        try {
            StringBuilder scope = new StringBuilder("packs:");
            for (PackResources pack : Minecraft.getInstance().getResourceManager().listPacks().toList()) {
                String id = pack.packId();
                if (!BUILT_IN_PACKS.contains(id)) {
                    return null;
                }
                scope.append(id).append(',');
            }
            return scope.toString();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static void runExport(ModelTableExporter job, Object models, String scope,
            int resourceEpoch, int levelEpoch) {
        try {
            while (exporter == job) {
                if (job.step(System.nanoTime() + EXPORT_SLICE_NANOS)) {
                    MeshModelTable done = job.finish();
                    if (exporter == job) {
                        if (resourceEpoch != BrowserMeshInstallQueue.resourceEpoch()
                                || levelEpoch != BrowserMeshInstallQueue.levelEpoch()) {
                            exporter = null;
                            exporterScope = null;
                            System.out.println("[mesh-kernel] discarded completed model table export after epoch change");
                            return;
                        }
                        exporter = null;
                        exporterScope = null;
                        table = done;
                        if (MeshKernelBridge.setTable(done.bytes, done.bytes.length, done.epoch)
                                && scope != null) {
                            // Persist only the immutable pack stack captured on the main thread.
                            MeshKernelBridge.storeTable(scope);
                        }
                    }
                    return;
                }
                TModernRuntimeSupport.yieldToEventLoop(0);
            }
        } catch (RuntimeException error) {
            if (exporter == job) {
                exporter = null;
                exporterScope = null;
                if (resourceEpoch != BrowserMeshInstallQueue.resourceEpoch()
                        || levelEpoch != BrowserMeshInstallQueue.levelEpoch()) {
                    System.out.println("[mesh-kernel] discarded failed model table export after epoch change: "
                            + error);
                } else {
                    failedModels = models;
                    System.out.println("[mesh-kernel] model table export failed, sections use the vanilla compiler: "
                            + error);
                }
            }
        }
    }

    // --- results ----------------------------------------------------------------------------------

    private static SectionCompiler.Results buildResults(Ready ready, SectionBufferBuilderPack pack,
            VertexSorting sorting) {
        JSObject record = ready.record;
        SectionCompiler.Results results = new SectionCompiler.Results();
        MeshData[] built = new MeshData[LAYERS.length];
        ByteBufferBuilder[] touched = new ByteBufferBuilder[LAYERS.length];
        try {
            for (int layer = 0; layer < LAYERS.length; layer++) {
                int quads = MeshKernelBridge.quadCount(record, layer);
                if (quads <= 0) {
                    continue;
                }
                ChunkSectionLayer sectionLayer = LAYERS[layer];
                VertexFormat format = sectionLayer.vertexFormat();
                if (format.getVertexSize() != VANILLA_VERTEX_BYTES) {
                    throw new IllegalStateException("terrain vertex size " + format.getVertexSize());
                }
                int vertices = quads * 4;
                int bytes = vertices * VANILLA_VERTEX_BYTES;
                if (MeshKernelBridge.vertexBytes(record, layer) != bytes) {
                    // Checked before anything is reserved in the pack's buffer.
                    throw new IllegalStateException("kernel vertex bytes do not match the quad count");
                }
                ByteBufferBuilder buffer = pack.buffer(sectionLayer);
                touched[layer] = buffer;
                long pointer = buffer.reserve(bytes);
                copyVertices(record, layer, pointer, bytes);
                ByteBufferBuilder.Result vertexBuffer = buffer.build();
                if (vertexBuffer == null) {
                    throw new IllegalStateException("empty layer buffer");
                }
                MeshData mesh = new MeshData(vertexBuffer, new MeshData.DrawState(format, vertices,
                        PrimitiveTopology.QUADS.indexCount(vertices), PrimitiveTopology.QUADS,
                        IndexType.least(vertices)));
                built[layer] = mesh;
                if (sectionLayer == ChunkSectionLayer.TRANSLUCENT) {
                    results.transparencyState = mesh.sortQuads(buffer, kernelOrder(ready, quads, sorting));
                }
                results.renderedLayers.put(sectionLayer, mesh);
            }
        } catch (RuntimeException error) {
            for (MeshData mesh : built) {
                if (mesh != null) {
                    mesh.close();
                }
            }
            // Bytes reserved but never built would prefix the mesh of the vanilla compile that
            // follows on the same buffers: build them into a result and free it, which rewinds
            // the buffer (or moves its next result past them).
            for (ByteBufferBuilder buffer : touched) {
                if (buffer == null) {
                    continue;
                }
                try {
                    ByteBufferBuilder.Result leftover = buffer.build();
                    if (leftover != null) {
                        leftover.close();
                    }
                } catch (RuntimeException ignored) {
                    // A closed buffer: the vanilla compile reports it; keep the original error.
                }
            }
            throw error;
        }
        results.blockEntities.addAll(ready.job.blockEntities);
        results.visibilitySet = visibility(MeshKernelBridge.visibilityLo(record),
                MeshKernelBridge.visibilityHi(record));
        return results;
    }

    /** Writes the kernel's vertex bytes for {@code layer} at a ByteBufferBuilder pointer. */
    private static void copyVertices(JSObject record, int layer, long pointer, int bytes) {
        if (dev.gaius.browser.ops.VersionOps.INSTANCE.copyVertices(record, layer, pointer, bytes)) {
            return;
        }
        byte[] data = null;
        int offset = 0;
        try {
            data = BrowserMemory.data(pointer);
            offset = BrowserMemory.dataOffset(pointer);
        } catch (RuntimeException notArrayBacked) {
            data = null;
        }
        if (data != null) {
            if (MeshKernelBridge.copyVertices(record, layer, data, offset) != bytes) {
                throw new IllegalStateException("kernel vertex bytes do not match the quad count");
            }
            return;
        }
        byte[] staging = new byte[bytes];
        if (MeshKernelBridge.copyVertices(record, layer, staging, 0) != bytes) {
            throw new IllegalStateException("kernel vertex bytes do not match the quad count");
        }
        ByteBuffer target = BrowserMemory.byteBuffer(pointer, bytes);
        target.put(staging);
    }

    /**
     * The kernel's back-to-front quad order as a VertexSorting, or {@code fallback} (vanilla's
     * sorting for this run's camera) when the camera moved to another translucency point of view
     * since the job was submitted; vanilla resorts only on such a change, so within one point of
     * view the kernel's order is as good as the one vanilla would compute now.
     */
    private static VertexSorting kernelOrder(Ready ready, int quads, VertexSorting fallback) {
        if (ready.runCamera == null
                || !TranslucencyPointOfView.of(ready.runCamera, ready.job.node).equals(ready.job.pointOfView)) {
            return fallback;
        }
        int[] order = new int[quads];
        if (MeshKernelBridge.copyTranslucentOrder(ready.record, order) != quads) {
            return fallback;
        }
        return centroids -> centroids.size() == quads ? order : fallback.sort(centroids);
    }

    private static VisibilitySet visibility(int lo, int hi) {
        VisibilitySet set = new VisibilitySet();
        for (int bit = 0; bit < 36; bit++) {
            boolean visible = bit < 32 ? ((lo >>> bit) & 1) != 0 : ((hi >>> (bit - 32)) & 1) != 0;
            if (visible) {
                set.set(DIRECTIONS[bit % 6], DIRECTIONS[bit / 6], true);
            }
        }
        return set;
    }

    // --- install ----------------------------------------------------------------------------------

    /** A finished job in BrowserMeshInstallQueue; installing it schedules the task's second run. */
    static final class Install implements BrowserMeshInstall {
        private final InFlight job;
        private final int status;
        private final int bytes;
        private JSObject record;

        Install(InFlight job, JSObject record, int status, int bytes) {
            this.job = job;
            this.record = record;
            this.status = status;
            this.bytes = bytes;
        }

        @Override
        public long sectionNode() {
            return job.node;
        }

        @Override
        public int requestSeq() {
            return job.requestSeq;
        }

        @Override
        public int levelEpoch() {
            return job.levelEpoch;
        }

        @Override
        public int resourceEpoch() {
            return job.resourceEpoch;
        }

        @Override
        public int byteSize() {
            return bytes;
        }

        @Override
        public boolean install() {
            JSObject result = record;
            record = null;
            if (!job.alive()) {
                if (result != null) {
                    MeshKernelBridge.release(result);
                }
                job.redirtyIfOrphaned();
                return true;
            }
            if (status == MeshKernelBridge.STATUS_MESHED && result != null) {
                job.task.gaius$setMeshKernelState(new Ready(job, result));
            } else {
                if (result != null) {
                    MeshKernelBridge.release(result);
                }
                job.task.gaius$setMeshKernelState(VANILLA);
                MeshKernelBridge.noteVanillaFallback();
            }
            job.task.gaius$requeueForMeshKernel();
            return true;
        }

        @Override
        public void discard(int reason) {
            if (record != null) {
                MeshKernelBridge.release(record);
            }
            record = null;
            if (reason == BrowserMeshInstallQueue.DISCARD_FAILED && job.alive()) {
                job.task.gaius$setMeshKernelState(VANILLA);
                MeshKernelBridge.noteVanillaFallback();
                job.task.gaius$requeueForMeshKernel();
            } else if (job.alive()) {
                job.redirtyStaleSnapshot();
            } else {
                job.redirtyIfOrphaned();
            }
        }
    }
}
