package dev.gaius.browser;

import dev.gaius.browser.render.BrowserMeshInstallQueue;
import dev.gaius.browser.render.BrowserNeighborReadiness;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import org.teavm.jso.JSBody;

/**
 * Keeps terrain section rebuild requests from being lost in the browser pipeline.
 *
 * <p>LevelExtractor clears a section's dirty flag when it extracts the update, and only
 * LevelRenderer.compileSections turns the extracted update into a compile task. Hooks patched
 * into the client put a section back into the dirty set whenever that hand-off fails:</p>
 * <ul>
 * <li>{@link #requeueUnconsumed}: updates extracted for a frame whose world render was skipped
 *     (inventory screens) or threw before compileSections;</li>
 * <li>{@link #requeueAfterUploadTimeout}: compiles cancelled because their GPU upload never got
 *     staging space;</li>
 * <li>{@link #requeueAfterKernelDrop}: mesh kernel results dropped for a cancelled task that left
 *     its section uncompiled with no other compile on the way;</li>
 * <li>{@link #staleMeshRejected}: counts older meshes that finished uploading after a newer
 *     compile and were discarded instead of replacing it.</li>
 * <li>{@link #carryUnconsumedChunkLoading} and {@link #beforeOcclusionUpdate}: the loaded-chunk
 *     and empty-section changes of a frame. LevelExtractor hands ClientChunkCache's
 *     double-buffered change sets to the frame state and flips them, and only
 *     SectionOcclusionGraph.update (inside LevelRenderer.render) applies them. A frame whose
 *     world render is skipped (inventory screens) or throws first would drop them: the chunks
 *     that arrived in that frame (typically a whole row or column at the edge of the view)
 *     never enter the graph's loaded set, so their sections are never visible, never compiled
 *     and show the sky although their blocks are there.</li>
 * </ul>
 * <p>Once per second, {@link #afterExtract} also audits the visible sections: a section whose
 * chunk is ready but which stays uncompiled, not dirty and without a compile in flight for
 * {@value #STALE_AUDITS} audits has lost its update and is marked dirty again. The in-flight
 * check is per section (RenderSection.gaius$hasPendingCompile, added by TerrainBatchPatches,
 * plus BrowserMeshInstallQueue for asynchronous meshes), so lost sections are repaired while
 * the rest of the pipeline is still busy. Counts are published to
 * window.__gaiusSectionAudit.</p>
 *
 * <p>{@link #afterExtract} also feeds the player's chunk to BrowserNeighborReadiness every
 * frame, before its once-per-second gate.</p>
 */
public final class BrowserSectionAudit {
    private static final long INTERVAL_MILLIS = 1000L;
    private static final int STALE_AUDITS = 3;

    private static final Long2IntOpenHashMap STALE = new Long2IntOpenHashMap();
    private static long lastRun;
    private static long redirtied;
    private static long audits;
    private static long unconsumedRequeued;
    private static long uploadTimeoutRequeued;
    private static long kernelDropRequeued;
    private static long staleMeshesRejected;
    private static final LongOpenHashSet NO_CHUNKS = new LongOpenHashSet();
    /** The frame state holds chunk-loading changes that no occlusion update has applied yet. */
    private static boolean chunkLoadingPending;
    private static ClientLevel chunkLoadingLevel;
    /** The level whose occlusion graph has run update() at least once. */
    private static ClientLevel occlusionUpdatedLevel;
    private static long chunkLoadingCarried;

    private BrowserSectionAudit() {
    }

    /** Called at the start of LevelExtractor.extract, before the frame state is reset. */
    public static void requeueUnconsumed(LevelRenderState state, SectionUpdateTracker tracker) {
        carryUnconsumedChunkLoading(state);
        if (state == null || tracker == null) {
            return;
        }
        List<SectionUpdateRenderState> updates = state.sectionUpdateRenderStates;
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (SectionUpdateRenderState update : updates) {
            long node = update.sectionNode();
            tracker.setDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node),
                    update.playerChanged());
        }
        unconsumedRequeued += updates.size();
        updates.clear();
    }

    /**
     * Applies the previous frame's loaded-chunk and empty-section changes to the occlusion graph
     * when no SectionOcclusionGraph.update consumed them, exactly as update() would have, before
     * this extraction flips ClientChunkCache's change sets and clears them. Frames are applied
     * one at a time in order, so nothing is merged across frames.
     */
    private static void carryUnconsumedChunkLoading(LevelRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (chunkLoadingPending && level != null && level == chunkLoadingLevel
                && level == occlusionUpdatedLevel && state != null) {
            ChunkLoadingRenderState changes = state.chunkLoadingRenderState;
            LevelRenderer renderer = minecraft.levelRenderer;
            if (changes != null && renderer != null) {
                SectionOcclusionGraph graph = renderer.sectionOcclusionGraph();
                if (changes.addedLoadedChunks != null && changes.removedLoadedChunks != null) {
                    graph.updateLoadedChunks(changes.addedLoadedChunks, changes.removedLoadedChunks);
                }
                if (changes.addedEmptySections != null && changes.removedEmptySections != null) {
                    graph.updateEmptySections(changes.addedEmptySections, changes.removedEmptySections);
                }
                chunkLoadingCarried++;
            }
        }
        // This extraction fills the frame state with the next set of changes.
        chunkLoadingPending = level != null;
        chunkLoadingLevel = level;
    }

    /**
     * Called at the start of SectionOcclusionGraph.update. Marks the frame's chunk-loading
     * changes consumed, and makes sure every expected chunk the client now holds is in the
     * graph's loaded set: vanilla keeps that set equal to the chunks ClientChunkCache holds, so
     * this adds nothing there, but it repairs a graph that missed a loaded chunk (the sections
     * waiting on that chunk would otherwise wait forever).
     */
    public static void beforeOcclusionUpdate(SectionOcclusionGraph graph, ChunkLoadingRenderState state) {
        chunkLoadingPending = false;
        occlusionUpdatedLevel = Minecraft.getInstance().level;
        if (graph != null && state != null && state.loadedExpectedChunks != null
                && !state.loadedExpectedChunks.isEmpty()) {
            graph.updateLoadedChunks(state.loadedExpectedChunks, NO_CHUNKS);
        }
    }

    /** Called by a compile task that gives up waiting for upload space, before it cancels. */
    public static void requeueAfterUploadTimeout(
            SectionRenderDispatcher.RenderSection section, boolean alreadyCancelled) {
        if (section == null || alreadyCancelled) {
            return;
        }
        LevelExtractor extractor = Minecraft.getInstance().levelExtractor;
        if (extractor == null) {
            return;
        }
        long node = section.getSectionNode();
        extractor.setSectionDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        uploadTimeoutRequeued++;
    }

    /**
     * Called by the mesh kernel hooks when a result is dropped because its task was cancelled
     * and the section, still uncompiled at the same position, has no compile or newer kernel
     * request on the way.
     */
    public static void requeueAfterKernelDrop(SectionRenderDispatcher.RenderSection section) {
        if (section == null) {
            return;
        }
        LevelExtractor extractor = Minecraft.getInstance().levelExtractor;
        if (extractor == null) {
            return;
        }
        long node = section.getSectionNode();
        extractor.setSectionDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        kernelDropRequeued++;
    }

    /** Called when an uploaded mesh is dropped because a newer compile superseded it. */
    public static void staleMeshRejected() {
        staleMeshesRejected++;
    }

    public static void afterExtract(SectionUpdateTracker tracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null) {
            BrowserNeighborReadiness.setCamera(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        }
        long now = Util.getMillis();
        if (tracker == null || now - lastRun < INTERVAL_MILLIS) {
            return;
        }
        lastRun = now;
        ClientLevel level = minecraft.level;
        LevelRenderer renderer = minecraft.levelRenderer;
        if (level == null || renderer == null) {
            STALE.clear();
            return;
        }
        audits++;
        // With compile or upload work queued, an uncompiled section may simply be waiting its
        // turn; re-dirtying it would cancel and requeue that task. A busy pipeline only
        // protects sections that actually have a compile or mesh request in flight.
        boolean pipelineIdle = BrowserRenderScheduler.queuedSectionWork() == 0;
        int uncompiledInFlight = 0;
        int visible = 0;
        int uncompiled = 0;
        int uncompiledDirty = 0;
        int uncompiledWaiting = 0;
        int uncompiledLost = 0;
        int fixed = 0;
        int dirtyNeighborsReady = 0;
        int dirtyNeighborsMissing = 0;
        StringBuilder dirtySections = new StringBuilder("[");
        int dirtySectionSamples = 0;
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) {
            visible++;
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                continue;
            }
            uncompiled++;
            long node = section.getSectionNode();
            SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(node);
            if (state == null) {
                continue;
            }
            if (state.isDirty()) {
                uncompiledDirty++;
                boolean neighborsReady = tracker.hasAllNeighbors(level, node);
                if (neighborsReady) {
                    dirtyNeighborsReady++;
                } else {
                    dirtyNeighborsMissing++;
                }
                if (dirtySectionSamples < 64) {
                    if (dirtySectionSamples++ > 0) {
                        dirtySections.append(',');
                    }
                    dirtySections.append("{\"x\":").append(SectionPos.x(node))
                            .append(",\"y\":").append(SectionPos.y(node))
                            .append(",\"z\":").append(SectionPos.z(node))
                            .append(",\"neighborsReady\":").append(neighborsReady).append('}');
                }
                continue;
            }
            if (!tracker.hasAllNeighbors(level, node)) {
                uncompiledWaiting++;
                continue;
            }
            // Staged uploads drain within a few frames; a section whose compile finished may
            // still be waiting for one, so only compile backlog alone no longer blocks repair.
            if (!pipelineIdle && (section.gaius$hasPendingCompile()
                    || BrowserMeshInstallQueue.isPending(node)
                    || BrowserRenderScheduler.uploadBacklog() > 0)) {
                uncompiledInFlight++;
                continue;
            }
            uncompiledLost++;
            int count = STALE.get(node) + 1;
            if (count >= STALE_AUDITS) {
                state.setDirty(false);
                redirtied++;
                fixed++;
            } else {
                seen.put(node, count);
            }
        }
        dirtySections.append(']');
        STALE.clear();
        STALE.putAll(seen);
        publish(visible, uncompiled, uncompiledDirty, uncompiledWaiting, uncompiledLost,
                fixed, (double) redirtied, (double) audits, (double) unconsumedRequeued,
                (double) uploadTimeoutRequeued, (double) staleMeshesRejected,
                dirtyNeighborsReady, dirtyNeighborsMissing, dirtySections.toString());
        publishReadiness(uncompiledInFlight, BrowserNeighborReadiness.waitingColumns(),
                (double) BrowserNeighborReadiness.nearbyGrants(),
                (double) BrowserNeighborReadiness.graceGrants(),
                (double) BrowserNeighborReadiness.waits(), (double) kernelDropRequeued,
                BrowserMeshInstallQueue.inFlight(), (double) chunkLoadingCarried);
    }

    @JSBody(params = {"inFlight", "waitingColumns", "nearbyGrants", "graceGrants", "waits",
            "kernelDrops", "meshRequestsOut", "chunkLoadingCarried"}, script = """
            const audit=globalThis.__gaiusSectionAudit;
            if (!audit) return;
            audit.uncompiledInFlight=inFlight;
            audit.neighborWaitingColumns=waitingColumns;
            audit.neighborNearbyGrants=nearbyGrants;
            audit.neighborGraceGrants=graceGrants;
            audit.neighborWaits=waits;
            audit.kernelDropRequeued=kernelDrops;
            audit.meshRequestsOut=meshRequestsOut;
            audit.chunkLoadingCarried=chunkLoadingCarried;
            """)
    private static native void publishReadiness(int inFlight, int waitingColumns,
            double nearbyGrants, double graceGrants, double waits, double kernelDrops,
            int meshRequestsOut, double chunkLoadingCarried);

    @JSBody(params = {"visible", "uncompiled", "uncompiledDirty", "uncompiledWaiting",
            "uncompiledLost", "fixed", "redirtied", "audits", "unconsumed", "uploadTimeouts",
            "staleMeshes", "dirtyNeighborsReady", "dirtyNeighborsMissing", "dirtySections"}, script = """
            globalThis.__gaiusSectionAudit = {
              visible: visible, uncompiled: uncompiled, uncompiledDirty: uncompiledDirty,
              uncompiledWaiting: uncompiledWaiting, uncompiledLost: uncompiledLost,
              fixedThisAudit: fixed, redirtiedTotal: redirtied, audits: audits,
              unconsumedRequeued: unconsumed, uploadTimeoutRequeued: uploadTimeouts,
              staleMeshesRejected: staleMeshes, dirtyNeighborsReady: dirtyNeighborsReady,
              dirtyNeighborsMissing: dirtyNeighborsMissing, dirtySections: JSON.parse(dirtySections),
              at: Date.now()
            };
            """)
    private static native void publish(int visible, int uncompiled, int uncompiledDirty,
            int uncompiledWaiting, int uncompiledLost, int fixed, double redirtied, double audits,
            double unconsumed, double uploadTimeouts, double staleMeshes,
            int dirtyNeighborsReady, int dirtyNeighborsMissing, String dirtySections);
}
