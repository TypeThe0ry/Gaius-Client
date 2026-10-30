package dev.gaius.tools;

import dev.gaius.tools.patches.mesh.ModernMeshPatches;
import dev.gaius.tools.patches.network.ModernNetworkPatches;
import dev.gaius.tools.patches.platform.ModernPlatformPatches;
import dev.gaius.tools.patches.render.ModernRenderPatches;
import dev.gaius.tools.patches.resources.ModernResourcePatches;
import dev.gaius.tools.patches.serverworker.ModernServerWorkerPatches;
import dev.gaius.tools.patches.storage.ModernStoragePatches;
import dev.gaius.tools.patches.support.ClientPatchSupport;
import dev.gaius.tools.patches.ui.ModernUiPatches;
import dev.gaius.tools.patches.worldgen.ModernWorldgenPatches;
import java.io.IOException;
import java.nio.file.Path;

/** Removes Java 25 and desktop-only paths that remain reachable in Minecraft 26.2. */
public final class Minecraft262BrowserPatcher {
    private Minecraft262BrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: Minecraft262BrowserPatcher INPUT_JAR OUTPUT_ROOT [MINECRAFT_VERSION]");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        String minecraftVersion = args.length == 3 ? args[2] : PatchRegistry.requestedProfile();
        if (minecraftVersion == null) {
            throw new IllegalArgumentException("Minecraft262BrowserPatcher needs MINECRAFT_VERSION "
                    + "(or " + PatchRegistry.BUILD_PROFILE_ENV + ")");
        }
        if (!ClientPatchSupport.isModernProfile(minecraftVersion)) {
            throw new IllegalArgumentException(
                    "Minecraft262BrowserPatcher serves the modern patch set only, not profile "
                            + minecraftVersion);
        }
        // Every patch call below goes through PatchRegistry so that a profile in bring-up
        // (GAIUS_BRINGUP=1, bringupAllowed profiles only) can skip listed patches; the rest run.
        // The spec of each call is its kind, targets and profiles
        // (port/tools/patchkit/classification.tsv records why).
        PatchRegistry.configureProfile(minecraftVersion);
        PatchRegistry.configureOutput(root);
        PatchRegistry.run("Minecraft262BrowserPatcher.patchNativeLibrariesBootstrap", "REQUIRED js wasmgc >=26.2", () -> ModernPlatformPatches.patchNativeLibrariesBootstrap(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchPreferredGraphicsApi", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchPreferredGraphicsApi(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchVulkanBackend", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchVulkanBackend(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchGlDeviceCapabilities", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchGlDeviceCapabilities(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchTerrainSectionUniformBlock", "REQUIRED wasmgc 26.2", () -> ModernRenderPatches.patchTerrainSectionUniformBlock(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchFramerateLimiter", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchFramerateLimiter(jar, root));
        // Preserve configured mipmap levels and texture resolution; throughput fixes stay in
        // scheduling rather than lowering the graphics preset.
        PatchRegistry.run("Minecraft262BrowserPatcher.patchGraphicsPresetBrowserDistances", "FEATURE js wasmgc >=26.2", () -> ModernUiPatches.patchGraphicsPresetBrowserDistances(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchChunkGenerationCooperation", "REQUIRED js wasmgc >=26.2", () -> ModernWorldgenPatches.patchChunkGenerationCooperation(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchDistanceManagerCooperation", "REQUIRED js wasmgc >=26.2", () -> ModernServerWorkerPatches.patchDistanceManagerCooperation(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchChunkMapMovementCooperation", "REQUIRED js wasmgc >=26.2", () -> ModernServerWorkerPatches.patchChunkMapMovementCooperation(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchChunkTrackingViewDifferenceCooperation", "REQUIRED js wasmgc >=26.2", () -> ModernServerWorkerPatches.patchChunkTrackingViewDifferenceCooperation(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchServerChunkBroadcastCooperation", "REQUIRED js wasmgc >=26.2", () -> ModernServerWorkerPatches.patchServerChunkBroadcastCooperation(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchRegionFileStorageCache", "PERF js wasmgc >=26.2", () -> ModernStoragePatches.patchRegionFileStorageCache(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchGlBufferMappedViewRanges", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchGlBufferMappedViewRanges(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchLiveFrameTargeting", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchLiveFrameTargeting(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchSectionRenderTaskRetryYields", "REQUIRED js wasmgc >=26.2", () -> ModernMeshPatches.patchSectionRenderTaskRetryYields(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchSectionRenderEmergencyUpload", "REQUIRED js wasmgc >=26.2", () -> ModernMeshPatches.patchSectionRenderEmergencyUpload(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchStagingBuffer", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchStagingBuffer(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchUberGpuBufferNodeCleanup", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchUberGpuBufferNodeCleanup(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchTemplateSource", "REQUIRED js wasmgc >=26.2", () -> ModernStoragePatches.patchTemplateSource(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchNoiseChunkGraphMapper", "PERF js wasmgc >=26.2", () -> ModernWorldgenPatches.patchNoiseChunkGraphMapper(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchRemoteFriendList", "REQUIRED js wasmgc >=26.2", () -> ModernNetworkPatches.patchRemoteFriendList(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchNativeModuleLister", "REQUIRED js wasmgc >=26.2", () -> ModernPlatformPatches.patchNativeModuleLister(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchMacosUtil", "REQUIRED js wasmgc >=26.2", () -> ModernPlatformPatches.patchMacosUtil(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchVulkanDebug", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.patchVulkanDebug(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchDetailedMemoryDebug", "REQUIRED js wasmgc >=26.2", () -> ModernPlatformPatches.patchDetailedMemoryDebug(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchSystemSpecsDebug", "REQUIRED js wasmgc >=26.2", () -> ModernPlatformPatches.patchSystemSpecsDebug(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchFileFixAccess", "REQUIRED js wasmgc >=26.2", () -> ModernStoragePatches.patchFileFixAccess(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchFileFixerUpperHardLinks", "REQUIRED js wasmgc >=26.2", () -> ModernStoragePatches.patchFileFixerUpperHardLinks(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchIdentifierResolveAgainst", "REQUIRED js wasmgc >=26.2", () -> ModernPlatformPatches.patchIdentifierResolveAgainst(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchCopyOnWriteFileSystem", "REQUIRED js wasmgc >=26.2", () -> ModernStoragePatches.patchCopyOnWriteFileSystem(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchCopyOnWriteProvider", "REQUIRED js wasmgc >=26.2", () -> ModernStoragePatches.patchCopyOnWriteProvider(jar, root));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchDownloadQueueBrowserCooperativeExecutor", "REQUIRED js wasmgc >=26.2", () -> ModernResourcePatches.patchDownloadQueueBrowserCooperativeExecutor(jar, root));
        // After patchLiveFrameTargeting: that patch writes Minecraft.class from the input jar,
        // while this one reads Minecraft and Options back from root and composes with it.
        PatchRegistry.run("Minecraft262BrowserPatcher.patchGraphicsPresetStartupReplay", "FEATURE js wasmgc >=26.2", () -> ModernUiPatches.patchGraphicsPresetStartupReplay(jar, root));
        // It reads LevelRenderer, LevelExtractor and RenderSection as the patches above left
        // them in root, and nothing after it in this step rewrites those classes.
        PatchRegistry.run("Minecraft262BrowserPatcher.terrainBatchPatches", "REQUIRED js wasmgc >=26.2", () -> ModernRenderPatches.terrainBatchPatches(jar, root, minecraftVersion));
        PatchRegistry.run("Minecraft262BrowserPatcher.patchSectionSliceCache", "PERF js wasmgc >=26.2", () -> ModernRenderPatches.patchSectionSliceCache(jar, root));
        // Last: after every patch that rewrites CompileTask.doTask (upload retry yields, bounded
        // cancellation, latest-mesh guard) and RenderSection; reads them back from root and adds
        // the mesh kernel prologue and compile hook to doTask.
        PatchRegistry.run("Minecraft262BrowserPatcher.meshKernelPatches", "PERF js wasmgc >=26.2", () -> ModernMeshPatches.meshKernelPatches(jar, root, minecraftVersion));
        PatchRegistry.printSummary();
    }
}
