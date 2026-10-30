package dev.gaius.tools;

import static dev.gaius.tools.patches.support.ClientPatchSupport.isModernProfile;

import dev.gaius.tools.patches.audio.AudioPatches;
import dev.gaius.tools.patches.input.InputPatches;
import dev.gaius.tools.patches.light.LightPatches;
import dev.gaius.tools.patches.mesh.MeshPatches;
import dev.gaius.tools.patches.network.NetworkPatches;
import dev.gaius.tools.patches.platform.PlatformPatches;
import dev.gaius.tools.patches.render.RenderPatches;
import dev.gaius.tools.patches.resources.ResourcePatches;
import dev.gaius.tools.patches.serverworker.ServerWorkerPatches;
import dev.gaius.tools.patches.storage.StoragePatches;
import dev.gaius.tools.patches.telemetry.TelemetryPatches;
import dev.gaius.tools.patches.telemetry.TickPacketParityPatches;
import dev.gaius.tools.patches.ui.SettingsLayoutPatches;
import dev.gaius.tools.patches.ui.UiPatches;
import dev.gaius.tools.patches.worldgen.WorldgenPatches;
import java.io.IOException;
import java.nio.file.Path;

/** Removes desktop-only diagnostics from the official browser client graph. */
public final class MinecraftClientPatcher {

    private MinecraftClientPatcher() {
    }

    /**
     * The profile of a run without a version argument: the build's profile (gaius.profile,
     * GAIUS_MINECRAFT_VERSION), else the one profile that takes the legacy patch set.
     */
    private static String defaultProfile() {
        String profile = PatchRegistry.requestedProfile();
        return profile != null ? profile : VersionProfile.only(VersionProfile.LEGACY_PATCH_SET);
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[1]);
        String minecraftVersion = args.length >= 3 ? args[2] : defaultProfile();
        // Every patch call below goes through PatchRegistry so that a profile in bring-up
        // (GAIUS_BRINGUP=1, only when its bringupAllowed is true) can skip listed patches; for
        // the other profiles each call simply runs. The spec of each call is its kind, targets and
        // profiles (port/tools/patchkit/classification.tsv records why).
        PatchRegistry.configureProfile(minecraftVersion);
        PatchRegistry.configureOutput(root);
        PatchRegistry.run("MinecraftClientPatcher.patchNativeModuleLister", "REQUIRED js wasmgc", () -> PlatformPatches.patchNativeModuleLister(
                args[0], root.resolve("net/minecraft/util/NativeModuleLister.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchJvmProfiler", "REQUIRED js wasmgc", () -> PlatformPatches.patchJvmProfiler(
                args[0], root.resolve("net/minecraft/util/profiling/jfr/JvmProfiler.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchEventLoopGroupHolder", "REQUIRED js wasmgc", () -> NetworkPatches.patchEventLoopGroupHolder(
                args[0], root.resolve("net/minecraft/server/network/EventLoopGroupHolder.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchServerMainBrowser", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerMainBrowser(
                args[0], root.resolve("net/minecraft/server/Main.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchWorldLoaderBrowserStartupTelemetry", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchWorldLoaderBrowserStartupTelemetry(
                args[0], root.resolve("net/minecraft/server/WorldLoader.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBlocksBrowserStartupYield", "REQUIRED js", () -> PlatformPatches.patchBlocksBrowserStartupYield(
                args[0], root.resolve("net/minecraft/world/level/block/Blocks.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBlockStateBaseBrowserStartupYield", "REQUIRED js", () -> PlatformPatches.patchBlockStateBaseBrowserStartupYield(args[0], root.resolve(
                "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBuiltInRegistriesBrowserStartupYield", "REQUIRED js", () -> PlatformPatches.patchBuiltInRegistriesBrowserStartupYield(args[0], root.resolve(
                "net/minecraft/core/registries/BuiltInRegistries.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDedicatedServerBrowser", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchDedicatedServerBrowser(
                args[0], root.resolve("net/minecraft/server/dedicated/DedicatedServer.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDedicatedServerBrowserConsole", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchDedicatedServerBrowserConsole(args[0], root.resolve(
                "net/minecraft/server/dedicated/DedicatedServer$1.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDedicatedSettingsBrowser", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchDedicatedSettingsBrowser(
                args[0], root.resolve("net/minecraft/server/dedicated/Settings.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchServerTextFilterBrowser", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerTextFilterBrowser(
                args[0], root.resolve("net/minecraft/server/network/ServerTextFilter.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchServerConnectionListenerBrowserWorker", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerConnectionListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerConnectionListener.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBrowserServerAddressResolver", "REQUIRED js wasmgc", () -> NetworkPatches.patchBrowserServerAddressResolver(args[0], root.resolve(
                "net/minecraft/client/multiplayer/resolver/ServerAddressResolver.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBrowserServerRedirectHandler", "REQUIRED js wasmgc", () -> NetworkPatches.patchBrowserServerRedirectHandler(args[0], root.resolve(
                "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchResolvedServerAddressBrowserUnresolved", "REQUIRED js wasmgc", () -> NetworkPatches.patchResolvedServerAddressBrowserUnresolved(args[0], root.resolve(
                "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress$1.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchConnectionBrowserWebSocket", "REQUIRED js wasmgc", () -> NetworkPatches.patchConnectionBrowserWebSocket(args[0], root.resolve(
                "net/minecraft/network/Connection.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchConnectionExceptionDiagnostics", "REQUIRED js wasmgc", () -> NetworkPatches.patchConnectionExceptionDiagnostics(root.resolve(
                "net/minecraft/network/Connection.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchCompressionDecoderBrowser", "REQUIRED js wasmgc", () -> NetworkPatches.patchCompressionDecoderBrowser(root.resolve(
                "net/minecraft/network/Connection.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchClientPacketUtilsBrowserInline", "REQUIRED js wasmgc", () -> NetworkPatches.patchClientPacketUtilsBrowserInline(args[0], root.resolve(
                "net/minecraft/network/protocol/PacketUtils.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchCommandErrorDiagnostics", "TELEMETRY js wasmgc", () -> NetworkPatches.patchCommandErrorDiagnostics(args[0], root.resolve(
                "net/minecraft/commands/Commands.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchServerCommandPacketErrorDiagnostics", "TELEMETRY js wasmgc >=26.2", () -> NetworkPatches.patchServerCommandPacketErrorDiagnostics(args[0], root.resolve(
                "net/minecraft/server/network/ServerGamePacketListenerImpl.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchConnectScreenBrowserRecovery", "REQUIRED js wasmgc", () -> NetworkPatches.patchConnectScreenBrowserRecovery(args[0], root.resolve(
                "net/minecraft/client/gui/screens/ConnectScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPacketProcessorBrowserSlice", "REQUIRED js wasmgc", () -> NetworkPatches.patchPacketProcessorBrowserSlice(args[0], root.resolve(
                "net/minecraft/network/PacketProcessor.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchClientKeepAliveBrowser", "REQUIRED js wasmgc", () -> NetworkPatches.patchClientKeepAliveBrowser(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDownloadedPackSourceBrowserRecovery", "REQUIRED js wasmgc", () -> ResourcePatches.patchDownloadedPackSourceBrowserRecovery(args[0], root.resolve(
                "net/minecraft/client/resources/server/DownloadedPackSource.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchResourceReloadProfiling", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchResourceReloadProfiling(args[0], root.resolve(
                "net/minecraft/server/packs/resources/SimpleReloadInstance.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSimpleJsonResourceReloadListenerBrowserStartupYield", "REQUIRED js", () -> PlatformPatches.patchSimpleJsonResourceReloadListenerBrowserStartupYield(args[0], root.resolve(
                "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchResourceReloadTaskLabels", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchResourceReloadTaskLabels(
                args[0],
                root.resolve("net/minecraft/client/resources/model/ModelManager.class"),
                root.resolve("net/minecraft/client/gui/font/FontManager.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchAtlasManagerReloadTaskLabels", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchAtlasManagerReloadTaskLabels(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchBitmapFontImageSharing", "PERF js wasmgc", () -> UiPatches.patchBitmapFontImageSharing(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchFontProviderSelectionIndex", "PERF js wasmgc", () -> UiPatches.patchFontProviderSelectionIndex(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchUnihexProviderBrowserBulkParser", "PERF js wasmgc", () -> UiPatches.patchUnihexProviderBrowserBulkParser(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/UnihexProvider$Definition.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchUnihexProviderBrowserAccess", "REQUIRED js wasmgc", () -> UiPatches.patchUnihexProviderBrowserAccess(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/UnihexProvider.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchNetworkEncoderMatchers", "REQUIRED js wasmgc", () -> NetworkPatches.patchNetworkEncoderMatchers(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchClassTreeIdRegistry", "REQUIRED js wasmgc", () -> PlatformPatches.patchClassTreeIdRegistry(args[0], root.resolve("net/minecraft/util/ClassTreeIdRegistry.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSynchedEntityDataClassInitialization", "REQUIRED js wasmgc", () -> PlatformPatches.patchSynchedEntityDataClassInitialization(
                args[0], root.resolve("net/minecraft/network/syncher/SynchedEntityData.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBrowserClassInitialization", "REQUIRED js wasmgc", () -> PlatformPatches.patchBrowserClassInitialization(
                args[0], root.resolve("dev/gaius/browser/BrowserClassInitialization.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchEnderDragonPhaseFactory", "REQUIRED js wasmgc", () -> PlatformPatches.patchEnderDragonPhaseFactory(
                args[0], root.resolve("net/minecraft/world/entity/boss/enderdragon/phases/EnderDragonPhase.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchEntityBrowserUuidUsesGlobalRandom", "REQUIRED js wasmgc", () -> PlatformPatches.patchEntityBrowserUuidUsesGlobalRandom(args[0], root.resolve(
                "net/minecraft/world/entity/Entity.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchGlx", "REQUIRED js wasmgc", () -> RenderPatches.patchGlx(args[0], root.resolve("com/mojang/blaze3d/platform/GLX.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchGlDebugBrowserNoCallback", "REQUIRED js wasmgc", () -> RenderPatches.patchGlDebugBrowserNoCallback(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlDebug.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchRenderSystemBrowserDeadlineCompensation", "REQUIRED js wasmgc", () -> RenderPatches.patchRenderSystemBrowserDeadlineCompensation(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchFramerateLimitTrackerBrowserNoThrottle", "REQUIRED js wasmgc", () -> RenderPatches.patchFramerateLimitTrackerBrowserNoThrottle(args[0], root.resolve(
                "com/mojang/blaze3d/platform/FramerateLimitTracker.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTracyZoneFiller", "REQUIRED js wasmgc", () -> PlatformPatches.patchTracyZoneFiller(
                args[0], root.resolve("net/minecraft/util/profiling/TracyZoneFiller.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTracingExecutorBrowser", "REQUIRED js wasmgc", () -> PlatformPatches.patchTracingExecutorBrowser(args[0], root.resolve("net/minecraft/TracingExecutor.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBlockableEventLoopBrowser", "REQUIRED js wasmgc", () -> PlatformPatches.patchBlockableEventLoopBrowser(args[0], root.resolve(
                "net/minecraft/util/thread/BlockableEventLoop.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchMacosUtil", "REQUIRED js wasmgc", () -> PlatformPatches.patchMacosUtil(
                args[0], root.resolve("com/mojang/blaze3d/platform/MacosUtil.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchInputConstants", "REQUIRED js wasmgc", () -> InputPatches.patchInputConstants(
                args[0], root.resolve("com/mojang/blaze3d/platform/InputConstants.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchMemoryDebug", "REQUIRED js wasmgc", () -> PlatformPatches.patchMemoryDebug(args[0], root.resolve(
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class")));
        if (isModernProfile(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchDebugScreenOverlayBrowserNoChunk", "REQUIRED js wasmgc >=26.2", () -> UiPatches.patchDebugScreenOverlayBrowserNoChunk(args[0], root.resolve(
                    "net/minecraft/client/gui/components/DebugScreenOverlay.class")));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchMainBrowserStorageMount", "REQUIRED js wasmgc", () -> StoragePatches.patchMainBrowserStorageMount(args[0], root.resolve("net/minecraft/client/main/Main.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchMinecraft", "REQUIRED js wasmgc", () -> PlatformPatches.patchMinecraft(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchWorldStemBrowserSave", "REQUIRED js wasmgc", () -> StoragePatches.patchWorldStemBrowserSave(args[0], root.resolve(
                "net/minecraft/server/WorldStem.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchLevelStorageAccessBrowserDelete", "REQUIRED js wasmgc", () -> StoragePatches.patchLevelStorageAccessBrowserDelete(args[0], root.resolve(
                "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchCommandEncoderLegacyTextureUpload", "REQUIRED js wasmgc", () -> RenderPatches.patchCommandEncoderLegacyTextureUpload(args[0], root.resolve(
                "com/mojang/blaze3d/systems/CommandEncoder.class")));
        // Keep Minecraft's LoadingOverlay for in-game resource reloads.
        // The launcher HTML owns only the first-start branded shell.
        PatchRegistry.run("MinecraftClientPatcher.patchPauseScreenBrowserSingleplayer", "FEATURE js wasmgc", () -> UiPatches.patchPauseScreenBrowserSingleplayer(args[0], root.resolve(
                "net/minecraft/client/gui/screens/PauseScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchOptionsBrowserLowSimulationDistance", "FEATURE js wasmgc", () -> UiPatches.patchOptionsBrowserLowSimulationDistance(args[0], root.resolve(
                "net/minecraft/client/Options.class")));
        // Browser settings layout: the settings screens leave out what has no effect in a browser.
        PatchRegistry.run("MinecraftClientPatcher.patchOptionsScreenBrowserLayout", "FEATURE js wasmgc", () -> SettingsLayoutPatches.patchOptionsScreenBrowserLayout(args[0], root));
        if (isModernProfile(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchOptionsScreenBrowserServerListing", "FEATURE js wasmgc >=26.2", () -> SettingsLayoutPatches.patchOptionsScreenBrowserServerListing(args[0], root));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchOnlineOptionsScreenBrowserLayout", "FEATURE js wasmgc", () -> SettingsLayoutPatches.patchOnlineOptionsScreenBrowserLayout(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchVideoSettingsScreenBrowserLayout", "FEATURE js wasmgc", () -> SettingsLayoutPatches.patchVideoSettingsScreenBrowserLayout(args[0], root));
        if (SettingsLayoutPatches.glfwInput(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchVideoSettingsScreenBrowserNoFullscreenToggle", "FEATURE js wasmgc <=26.2", () -> SettingsLayoutPatches.patchVideoSettingsScreenBrowserNoFullscreenToggle(args[0], root));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchSoundOptionsScreenBrowserLayout", "FEATURE js wasmgc", () -> SettingsLayoutPatches.patchSoundOptionsScreenBrowserLayout(args[0], root));
        if (SettingsLayoutPatches.controlsHaveQuitShortcuts(args[0])) {
            PatchRegistry.run("MinecraftClientPatcher.patchControlsScreenBrowserLayout", "FEATURE js wasmgc >=26.3", () -> SettingsLayoutPatches.patchControlsScreenBrowserLayout(args[0], root));
        }
        if (SettingsLayoutPatches.glfwInput(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchMouseSettingsScreenBrowserNoCursorChanges", "FEATURE js wasmgc <=26.2", () -> SettingsLayoutPatches.patchMouseSettingsScreenBrowserNoCursorChanges(args[0], root));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchNarratorOptionsBrowserLayout", "FEATURE js wasmgc", () -> SettingsLayoutPatches.patchNarratorOptionsBrowserLayout(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchPackSelectionScreenBrowserNoFolderButton", "FEATURE js wasmgc", () -> SettingsLayoutPatches.patchPackSelectionScreenBrowserNoFolderButton(args[0], root));
        if (isModernProfile(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchTextureUtilBrowserSolidify", "PERF js wasmgc >=26.2", () -> RenderPatches.patchTextureUtilBrowserSolidify(args[0], root.resolve(
                    "com/mojang/blaze3d/platform/TextureUtil.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchTextureAtlasBrowserReleaseStaticImages", "PERF js wasmgc >=26.2", () -> RenderPatches.patchTextureAtlasBrowserReleaseStaticImages(args[0], root));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchBrowserInputCallbacks", "REQUIRED js wasmgc", () -> InputPatches.patchBrowserInputCallbacks(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchGuiGraphicsBrowserItemCache", "PERF js wasmgc", () -> UiPatches.patchGuiGraphicsBrowserItemCache(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchGuiRenderTelemetry", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchGuiRenderTelemetry(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchDynamicUniformsBrowserInitialCapacity", "PERF js wasmgc", () -> RenderPatches.patchDynamicUniformsBrowserInitialCapacity(args[0], root.resolve(
                "net/minecraft/client/renderer/DynamicUniforms.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchFreeTypeUtil", "REQUIRED js wasmgc", () -> UiPatches.patchFreeTypeUtil(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDebugMemoryUntracker", "REQUIRED js wasmgc", () -> PlatformPatches.patchDebugMemoryUntracker(args[0], root.resolve(
                "com/mojang/blaze3d/platform/DebugMemoryUntracker.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchMinecraftServer", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchMinecraftServer(args[0], root.resolve(
                "net/minecraft/server/MinecraftServer.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchMobBrowserAiCooperation", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchMobBrowserAiCooperation(args[0], root.resolve(
                "net/minecraft/world/entity/Mob.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChunkMapBrowserInitialViewDistance", "FEATURE js wasmgc", () -> ServerWorkerPatches.patchChunkMapBrowserInitialViewDistance(args[0], root.resolve(
                "net/minecraft/server/level/ChunkMap.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChunkTaskPriorityQueueBrowserNearestFirst", "PERF js wasmgc", () -> ServerWorkerPatches.patchChunkTaskPriorityQueueBrowserNearestFirst(args[0], root.resolve(
                "net/minecraft/server/level/ChunkTaskPriorityQueue.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPlayerSpawnFinderBrowser", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchPlayerSpawnFinderBrowser(args[0], root.resolve(
                "net/minecraft/server/level/PlayerSpawnFinder.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPrepareSpawnTaskBrowser", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchPrepareSpawnTaskBrowser(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchServerChunkCacheBrowserOffThreadWait", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerChunkCacheBrowserOffThreadWait(args[0], root.resolve(
                "net/minecraft/server/level/ServerChunkCache.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchStructureTemplateManagerBrowserGzip", "REQUIRED js wasmgc", () -> StoragePatches.patchStructureTemplateManagerBrowserGzip(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchServerCommonPacketListenerBrowserWorker", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerCommonPacketListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerCommonPacketListenerImpl.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchServerGamePacketListenerBrowserWorker", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerGamePacketListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerGamePacketListenerImpl.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPlayerChunkSenderBrowserWorker", "PERF js wasmgc", () -> ServerWorkerPatches.patchPlayerChunkSenderBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/PlayerChunkSender.class"),
                isModernProfile(minecraftVersion)));
        PatchRegistry.run("MinecraftClientPatcher.patchServerPlayerGameModeBrowserWorker", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchServerPlayerGameModeBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/level/ServerPlayerGameMode.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChunkGeneratorStructureStateBrowserLazyRings", "PERF js wasmgc", () -> WorldgenPatches.patchChunkGeneratorStructureStateBrowserLazyRings(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ChunkGeneratorStructureState.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBlockPosBrowserPackedCoordinates", "PERF js", () -> PlatformPatches.patchBlockPosBrowserPackedCoordinates(args[0], root.resolve(
                "net/minecraft/core/BlockPos.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBiomeManagerBrowserNearestCorner", "PERF js wasmgc", () -> WorldgenPatches.patchBiomeManagerBrowserNearestCorner(args[0], root.resolve(
                "net/minecraft/world/level/biome/BiomeManager.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchAquiferBrowserNearestCenters", "PERF js", () -> WorldgenPatches.patchAquiferBrowserNearestCenters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBeardifierBrowserPackedCompute", "PERF js", () -> WorldgenPatches.patchBeardifierBrowserPackedCompute(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/Beardifier.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchImprovedNoiseBrowserHotPath", "PERF js", () -> WorldgenPatches.patchImprovedNoiseBrowserHotPath(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/synth/ImprovedNoise.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPerlinNoiseBrowserDoubleWrap", "PERF js", () -> WorldgenPatches.patchPerlinNoiseBrowserDoubleWrap(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/synth/PerlinNoise.class")));
        boolean deepWorldgenCheckpoints = isModernProfile(minecraftVersion);
        PatchRegistry.run("MinecraftClientPatcher.patchNoiseBasedChunkGeneratorBrowserSynchronous", "REQUIRED js wasmgc", () -> WorldgenPatches.patchNoiseBasedChunkGeneratorBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class"),
                deepWorldgenCheckpoints));
        PatchRegistry.run("MinecraftClientPatcher.patchNoiseChunkBrowserSynchronous", "REQUIRED js", () -> WorldgenPatches.patchNoiseChunkBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchNoiseInterpolatorBrowserLerp", "PERF js", () -> WorldgenPatches.patchNoiseInterpolatorBrowserLerp(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchNoiseChunkContextBrowserIntCounters", "REQUIRED js", () -> WorldgenPatches.patchNoiseChunkContextBrowserIntCounters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$1.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchNoiseChunkCacheOnceBrowserIntCounters", "REQUIRED js", () -> WorldgenPatches.patchNoiseChunkCacheOnceBrowserIntCounters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDensityFunctionsPureTransformersBrowserDirect", "PERF js", () -> WorldgenPatches.patchDensityFunctionsPureTransformersBrowserDirect(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchWorldgenRecordHashCodeCaches", "PERF js wasmgc", () -> WorldgenPatches.patchWorldgenRecordHashCodeCaches(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchClimateRTreeBrowserSynchronous", "REQUIRED js wasmgc", () -> WorldgenPatches.patchClimateRTreeBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/biome/Climate$RTree$SubTree.class"),
                deepWorldgenCheckpoints));
        PatchRegistry.run("MinecraftClientPatcher.patchClimateRTreeBrowserFlatIndex", "PERF wasmgc >=26.2", () -> WorldgenPatches.patchClimateRTreeBrowserFlatIndex(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchClimateRTreeNodeBrowserDoubleDistance", "PERF js", () -> WorldgenPatches.patchClimateRTreeNodeBrowserDoubleDistance(args[0], root.resolve(
                "net/minecraft/world/level/biome/Climate$RTree$Node.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSurfaceSystemBrowserSynchronous", "REQUIRED js wasmgc", () -> WorldgenPatches.patchSurfaceSystemBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceSystem.class"),
                deepWorldgenCheckpoints));
        PatchRegistry.run("MinecraftClientPatcher.patchSurfaceRulesContextBrowserReusableBiomeSupplier", "PERF js wasmgc", () -> WorldgenPatches.patchSurfaceRulesContextBrowserReusableBiomeSupplier(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceRules$Context.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSurfaceRulesLazyConditionBrowserPrimitiveCache", "PERF js wasmgc", () -> WorldgenPatches.patchSurfaceRulesLazyConditionBrowserPrimitiveCache(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchSurfaceRulesSequenceBrowserIndexed", "PERF js wasmgc", () -> WorldgenPatches.patchSurfaceRulesSequenceBrowserIndexed(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChunkGeneratorBrowserSynchronous", "REQUIRED js wasmgc", () -> WorldgenPatches.patchChunkGeneratorBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ChunkGenerator.class"),
                deepWorldgenCheckpoints));
        if (deepWorldgenCheckpoints) {
            PatchRegistry.run("MinecraftClientPatcher.patchJigsawPlacementBrowserDeepCheckpoints", "REQUIRED js wasmgc >=26.2", () -> WorldgenPatches.patchJigsawPlacementBrowserDeepCheckpoints(args[0], root.resolve(
                    "net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement$Placer.class")));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchWorldCarverBrowserSynchronous", "REQUIRED js wasmgc", () -> WorldgenPatches.patchWorldCarverBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/carver/WorldCarver.class"),
                deepWorldgenCheckpoints));
        PatchRegistry.run("MinecraftClientPatcher.patchLightEngineBrowserSynchronous", "REQUIRED js wasmgc", () -> LightPatches.patchLightEngineBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/lighting/LightEngine.class"),
                deepWorldgenCheckpoints));
        PatchRegistry.run("MinecraftClientPatcher.patchLevelChunkSectionBrowserSynchronous", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchLevelChunkSectionBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/chunk/LevelChunkSection.class"),
                deepWorldgenCheckpoints));
        PatchRegistry.run("MinecraftClientPatcher.patchFriendlyByteBufBrowserLongArray", "PERF js", () -> NetworkPatches.patchFriendlyByteBufBrowserLongArray(args[0], root.resolve(
                "net/minecraft/network/FriendlyByteBuf.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSimpleBitStorageBrowserUnpack", "PERF js", () -> PlatformPatches.patchSimpleBitStorageBrowserUnpack(args[0], root.resolve(
                "net/minecraft/util/SimpleBitStorage.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchProtoChunkBrowserHeightmapCache", "PERF js wasmgc", () -> WorldgenPatches.patchProtoChunkBrowserHeightmapCache(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ProtoChunk.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchHeightmapBrowserStorage", "PERF js", () -> WorldgenPatches.patchHeightmapBrowserStorage(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/Heightmap.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBufferBuilderBrowserFastVertex", "PERF js", () -> MeshPatches.patchBufferBuilderBrowserFastVertex(args[0], root.resolve(
                "com/mojang/blaze3d/vertex/BufferBuilder.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchByteBufferBuilderBrowserReserve", "PERF js", () -> MeshPatches.patchByteBufferBuilderBrowserReserve(args[0], root.resolve(
                "com/mojang/blaze3d/vertex/ByteBufferBuilder.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchCompiledSectionMeshBrowserVertexBufferReuse", "PERF js wasmgc", () -> MeshPatches.patchCompiledSectionMeshBrowserVertexBufferReuse(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/CompiledSectionMesh.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchRegionFileVersionBrowserNoCompression", "REQUIRED js wasmgc", () -> StoragePatches.patchRegionFileVersionBrowserNoCompression(args[0], root.resolve(
                "net/minecraft/world/level/chunk/storage/RegionFileVersion.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchNaturalSpawnerGenerationSpawnTelemetry", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchNaturalSpawnerGenerationSpawnTelemetry(args[0], root.resolve(
                "net/minecraft/world/level/NaturalSpawner.class"), minecraftVersion));
        // Debug-only: the order trace must stay out of release patch manifests.
        if (TelemetryPatches.chunkGenerationOrderRequested()) {
            PatchRegistry.run("MinecraftClientPatcher.patchChunkGenerationOrderTelemetry", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchChunkGenerationOrderTelemetry(args[0], root));
        }
        // Preserve both vanilla generation-time creature packs and per-tick
        // spawning, including the hostile-mob gamerule filter.
        PatchRegistry.run("MinecraftClientPatcher.patchChaseClient", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchChaseClient(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseClient.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchLanServerPinger", "REQUIRED js wasmgc", () -> NetworkPatches.patchLanServerPinger(args[0], root.resolve(
                "net/minecraft/client/server/LanServerPinger.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchHttpUtil", "REQUIRED js wasmgc", () -> NetworkPatches.patchHttpUtil(args[0], root.resolve("net/minecraft/util/HttpUtil.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDownloadQueueBrowser", "REQUIRED js wasmgc", () -> ResourcePatches.patchDownloadQueueBrowser(args[0], root.resolve(
                "net/minecraft/server/packs/DownloadQueue.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSkinTextureDownloader", "REQUIRED js wasmgc", () -> ResourcePatches.patchSkinTextureDownloader(args[0], root.resolve(
                "net/minecraft/client/renderer/texture/SkinTextureDownloader.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSkinManagerUploadedSkinSecurity", "FEATURE js wasmgc", () -> ResourcePatches.patchSkinManagerUploadedSkinSecurity(args[0], root.resolve(
                "net/minecraft/client/resources/SkinManager.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchUtilJarFileSystem", "REQUIRED js wasmgc", () -> StoragePatches.patchUtilJarFileSystem(args[0], root.resolve("net/minecraft/util/Util.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchUtilRunNamedBrowserOutput", "REQUIRED js wasmgc", () -> PlatformPatches.patchUtilRunNamedBrowserOutput(root.resolve("net/minecraft/util/Util.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchUtilBlockUntilDoneBrowserOutput", "REQUIRED js wasmgc", () -> PlatformPatches.patchUtilBlockUntilDoneBrowserOutput(root.resolve("net/minecraft/util/Util.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchResourceKeyRegistryRoot", "REQUIRED js wasmgc", () -> PlatformPatches.patchResourceKeyRegistryRoot(args[0], root.resolve("net/minecraft/resources/ResourceKey.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchVanillaPackResourcesBuilder", "REQUIRED js wasmgc", () -> ResourcePatches.patchVanillaPackResourcesBuilder(args[0], root.resolve(
                "net/minecraft/server/packs/VanillaPackResourcesBuilder.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchFilePackResourcesBrowserAtlasOverlays", "REQUIRED js wasmgc", () -> ResourcePatches.patchFilePackResourcesBrowserAtlasOverlays(args[0], root.resolve(
                "net/minecraft/server/packs/FilePackResources$FileResourcesSupplier.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchFilePackResourcesBrowserIndex", "PERF js wasmgc", () -> ResourcePatches.patchFilePackResourcesBrowserIndex(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchSingleFileBrowserAtlasFallback", "REQUIRED js wasmgc", () -> ResourcePatches.patchSingleFileBrowserAtlasFallback(args[0], root.resolve(
                "net/minecraft/client/renderer/texture/atlas/sources/SingleFile.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchIndexedAssetSourceBrowserNoop", "REQUIRED js wasmgc", () -> ResourcePatches.patchIndexedAssetSourceBrowserNoop(args[0], root.resolve(
                "net/minecraft/client/resources/IndexedAssetSource.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchLocalTimeItemModelProperty", "REQUIRED js wasmgc", () -> PlatformPatches.patchLocalTimeItemModelProperty(args[0], root.resolve(
                "net/minecraft/client/renderer/item/properties/select/LocalTime.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchLanServerDetector", "REQUIRED js wasmgc", () -> NetworkPatches.patchLanServerDetector(args[0], root.resolve(
                "net/minecraft/client/server/LanServerDetection$LanServerDetector.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPackSelectionScreenBrowserImport", "FEATURE js wasmgc", () -> UiPatches.patchPackSelectionScreenBrowserImport(args[0], root.resolve(
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchPackWatcher", "REQUIRED js wasmgc", () -> UiPatches.patchPackWatcher(args[0], root.resolve(
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen$Watcher.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChaseServer", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchChaseServer(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseServer.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchOpenUri", "REQUIRED js wasmgc", () -> PlatformPatches.patchOpenUri(args[0], root.resolve("net/minecraft/util/Util$OS.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchRealmsNetwork", "REQUIRED js wasmgc", () -> NetworkPatches.patchRealmsNetwork(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchReflectivePatternArray", "REQUIRED js wasmgc", () -> PlatformPatches.patchReflectivePatternArray(args[0], root.resolve(
                "net/minecraft/world/level/block/state/pattern/BlockPatternBuilder.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChunkPosSpliterator", "PERF js wasmgc", () -> PlatformPatches.patchChunkPosSpliterator(args[0], root.resolve(
                "net/minecraft/world/level/ChunkPos$2.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchDetectedVersion", "REQUIRED js wasmgc", () -> PlatformPatches.patchDetectedVersion(
                args[0], root.resolve("net/minecraft/DetectedVersion.class"), minecraftVersion));
        PatchRegistry.run("MinecraftClientPatcher.patchSingleplayerCrypto", "REQUIRED js wasmgc", () -> NetworkPatches.patchSingleplayerCrypto(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchSingleplayerLogin", "REQUIRED js wasmgc", () -> ServerWorkerPatches.patchSingleplayerLogin(args[0], root.resolve(
                "net/minecraft/server/network/ServerLoginPacketListenerImpl.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSingleplayerHostCompression", "PERF wasmgc", () -> ServerWorkerPatches.patchSingleplayerHostCompression(root.resolve(
                "net/minecraft/server/network/ServerLoginPacketListenerImpl.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchChatSigning", "REQUIRED js wasmgc", () -> NetworkPatches.patchChatSigning(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchSuppressInsecureServerToast", "FEATURE js wasmgc", () -> NetworkPatches.patchSuppressInsecureServerToast(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchMultiplayerExecutor", "REQUIRED js wasmgc", () -> NetworkPatches.patchMultiplayerExecutor(args[0], root.resolve(
                "net/minecraft/client/gui/screens/multiplayer/"
                        + "ServerSelectionList$OnlineServerEntry.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchClientShutdownWatchdog", "REQUIRED js wasmgc", () -> PlatformPatches.patchClientShutdownWatchdog(args[0], root.resolve(
                "com/mojang/blaze3d/platform/ClientShutdownWatchdog.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchScreenBrowserFastMenus", "FEATURE js wasmgc", () -> UiPatches.patchScreenBrowserFastMenus(args[0], root.resolve(
                "net/minecraft/client/gui/screens/Screen.class")));
        // Keep the actual resource-reload completion path intact while hiding
        // only the foreground LoadingOverlay once a joined world is live.
        // This is required for both the legacy mapped 1.21.11 profile and the
        // current named 26.2 profile; the helper is deliberately separate
        // from Minecraft#getOverlay so reload exceptions still reach vanilla.
        PatchRegistry.run("MinecraftClientPatcher.patchLoadingOverlayBrowserForeground", "FEATURE js wasmgc", () -> UiPatches.patchLoadingOverlayBrowserForeground(args[0], root.resolve(
                "net/minecraft/client/gui/screens/LoadingOverlay.class")));
        // The overlay covers the real reload only: once it is done (or failed), the title screen
        // (or the reloaded game) shows on the next frame instead of after the 2 s logo fade-out.
        PatchRegistry.run("MinecraftClientPatcher.patchLoadingOverlayBrowserNoFade", "FEATURE js wasmgc", () -> UiPatches.patchLoadingOverlayBrowserNoFade(args[0], root.resolve(
                "net/minecraft/client/gui/screens/LoadingOverlay.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchJoinMultiplayerScreenRelays", "FEATURE js wasmgc", () -> NetworkPatches.patchJoinMultiplayerScreenRelays(args[0], root.resolve(
                "net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.class")));
        // Startup: copy image rectangles row by row and memoize block face sturdiness.
        PatchRegistry.run("MinecraftClientPatcher.patchNativeImageBulkCopyRect", "PERF js wasmgc", () -> RenderPatches.patchNativeImageBulkCopyRect(args[0], root.resolve(
                "com/mojang/blaze3d/platform/NativeImage.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchNativeImageBrowserPng", "REQUIRED js wasmgc", () -> RenderPatches.patchNativeImageBrowserPng(args[0], root.resolve(
                "com/mojang/blaze3d/platform/NativeImage.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBlockCacheSupportMemo", "PERF js wasmgc", () -> PlatformPatches.patchBlockCacheSupportMemo(args[0], root.resolve(
                "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBlockShapeFullMemo", "PERF js wasmgc", () -> PlatformPatches.patchBlockShapeFullMemo(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchIdentifierHashMemo", "PERF js wasmgc", () -> PlatformPatches.patchIdentifierHashMemo(args[0], root.resolve(
                "net/minecraft/resources/Identifier.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchWallBlockShapeMemo", "PERF js wasmgc", () -> PlatformPatches.patchWallBlockShapeMemo(args[0], root.resolve(
                "net/minecraft/world/level/block/WallBlock.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchLevelLoadingScreenBrowserFastProgress", "PERF js wasmgc", () -> UiPatches.patchLevelLoadingScreenBrowserFastProgress(args[0], root.resolve(
                "net/minecraft/client/gui/screens/LevelLoadingScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTitleScreenBrowserFastMenus", "FEATURE js wasmgc", () -> UiPatches.patchTitleScreenBrowserFastMenus(args[0], root.resolve(
                "net/minecraft/client/gui/screens/TitleScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTitleScreenBrowserNoFadeIn", "FEATURE js wasmgc", () -> UiPatches.patchTitleScreenBrowserNoFadeIn(args[0], root.resolve(
                "net/minecraft/client/gui/screens/TitleScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTitleScreenBrowserNoRealms", "FEATURE js wasmgc", () -> UiPatches.patchTitleScreenBrowserNoRealms(args[0], root.resolve(
                "net/minecraft/client/gui/screens/TitleScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTitleScreenBrowserAboutLink", "FEATURE js wasmgc", () -> UiPatches.patchTitleScreenBrowserAboutLink(args[0], root.resolve(
                "net/minecraft/client/gui/screens/TitleScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchAbstractButtonBrowserFastSprite", "PERF js wasmgc", () -> UiPatches.patchAbstractButtonBrowserFastSprite(args[0], root.resolve(
                "net/minecraft/client/gui/components/AbstractButton.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchCreateWorldScreenBrowserDefaults", "FEATURE js wasmgc", () -> UiPatches.patchCreateWorldScreenBrowserDefaults(args[0], root.resolve(
                "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSelectWorldScreenWorldTransfer", "FEATURE js wasmgc", () -> UiPatches.patchSelectWorldScreenWorldTransfer(args[0], root.resolve(
                "net/minecraft/client/gui/screens/worldselection/SelectWorldScreen.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchWorldSelectionListTelemetry", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchWorldSelectionListTelemetry(args[0], root.resolve(
                "net/minecraft/client/gui/screens/worldselection/WorldSelectionList.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBrowserAudio", "REQUIRED js wasmgc", () -> AudioPatches.patchBrowserAudio(args[0], root.resolve(
                "com/mojang/blaze3d/audio/Library.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchBrowserAudioListener", "REQUIRED js wasmgc", () -> AudioPatches.patchBrowserAudioListener(args[0], root.resolve(
                "com/mojang/blaze3d/audio/Listener.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchGlslPreprocessor", "REQUIRED js wasmgc", () -> RenderPatches.patchGlslPreprocessor(args[0], root.resolve(
                "com/mojang/blaze3d/preprocessor/GlslPreprocessor.class")));
        if (isModernProfile(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchSectionBufferBuilderPoolTelemetry", "PERF wasmgc >=26.2", () -> TelemetryPatches.patchSectionBufferBuilderPoolTelemetry(args[0], root.resolve(
                    "net/minecraft/client/renderer/SectionBufferBuilderPool.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchMappableRingBufferTelemetry", "TELEMETRY js wasmgc >=26.2", () -> TelemetryPatches.patchMappableRingBufferTelemetry(args[0], root.resolve(
                    "net/minecraft/client/renderer/MappableRingBuffer.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchStagedVertexBufferGpuPoolCache", "REQUIRED js wasmgc >=26.2", () -> RenderPatches.patchStagedVertexBufferGpuPoolCache(args[0], root.resolve(
                    "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchFeatureRenderDispatcherPreparedFrameRemoveIf", "PERF js wasmgc >=26.2", () -> RenderPatches.patchFeatureRenderDispatcherPreparedFrameRemoveIf(args[0], root.resolve(
                    "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchFeatureSubmitsRemoveIf", "PERF js wasmgc >=26.2", () -> RenderPatches.patchFeatureSubmitsRemoveIf(args[0], root.resolve(
                    "net/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase$FeatureSubmits.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchSubmitNodeStorageDrainRemoveIf", "PERF js wasmgc >=26.2", () -> RenderPatches.patchSubmitNodeStorageDrainRemoveIf(args[0], root.resolve(
                    "net/minecraft/client/renderer/SubmitNodeStorage.class")));
            PatchRegistry.run("MinecraftClientPatcher.patchInventoryAvatarRenderTelemetry", "TELEMETRY js wasmgc >=26.2", () -> TelemetryPatches.patchInventoryAvatarRenderTelemetry(args[0], root));
            PatchRegistry.run("MinecraftClientPatcher.patchGuiEntitySubmitTelemetry", "TELEMETRY js wasmgc >=26.2", () -> TelemetryPatches.patchGuiEntitySubmitTelemetry(args[0], root.resolve(
                    "net/minecraft/client/gui/GuiGraphicsExtractor.class")));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchGlDevice", "REQUIRED js wasmgc", () -> RenderPatches.patchGlDevice(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchGlConstWebGLTextureFormats", "REQUIRED js wasmgc", () -> RenderPatches.patchGlConstWebGLTextureFormats(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlConst.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTextureFormatWebGLColorAspect", "REQUIRED js wasmgc", () -> RenderPatches.patchTextureFormatWebGLColorAspect(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchGlStateManagerTextureBinding", "REQUIRED js wasmgc", () -> RenderPatches.patchGlStateManagerTextureBinding(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlStateManager.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchGlRenderPipelineDrawMetadata", "REQUIRED js wasmgc", () -> RenderPatches.patchGlRenderPipelineDrawMetadata(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlRenderPipeline.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchGlCommandEncoder", "REQUIRED js wasmgc", () -> RenderPatches.patchGlCommandEncoder(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchGameRendererBrowserAutoScreenshot", "REQUIRED js wasmgc", () -> RenderPatches.patchGameRendererBrowserAutoScreenshot(args[0], root.resolve(
                "net/minecraft/client/renderer/GameRenderer.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchParticleGroupBrowserTickSafety", "REQUIRED js wasmgc", () -> RenderPatches.patchParticleGroupBrowserTickSafety(args[0], root.resolve(
                "net/minecraft/client/particle/ParticleGroup.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchClientLevelBrowserBlockBreakEffects", "PERF js wasmgc", () -> RenderPatches.patchClientLevelBrowserBlockBreakEffects(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientLevel.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchMultiPlayerGameModeBrowserHitSound", "FEATURE js wasmgc", () -> AudioPatches.patchMultiPlayerGameModeBrowserHitSound(args[0], root.resolve(
                "net/minecraft/client/multiplayer/MultiPlayerGameMode.class")));
        if (isModernProfile(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.patchLevelRendererBrowserBlockBreakProgress", "REQUIRED js wasmgc", () -> RenderPatches.patchLevelRendererBrowserBlockBreakProgress(args[0], root.resolve(
                    "net/minecraft/client/renderer/LevelRenderer.class"), true));
            PatchRegistry.run("MinecraftClientPatcher.patchSectionOcclusionGraphBrowserMovementRefresh", "REQUIRED js wasmgc >=26.2", () -> RenderPatches.patchSectionOcclusionGraphBrowserMovementRefresh(args[0], root.resolve(
                    "net/minecraft/client/renderer/SectionOcclusionGraph.class")));
        } else {
            PatchRegistry.run("MinecraftClientPatcher.patchLevelRendererBrowserBlockBreakProgress", "REQUIRED js wasmgc", () -> RenderPatches.patchLevelRendererBrowserBlockBreakProgress(args[0], root.resolve(
                    "net/minecraft/client/renderer/LevelRenderer.class"), false));
        }
        PatchRegistry.run("MinecraftClientPatcher.patchCurrentLevelExtractorBrowserSectionCompileThrottle", "REQUIRED js wasmgc", () -> MeshPatches.patchCurrentLevelExtractorBrowserSectionCompileThrottle(args[0], root.resolve(
                "net/minecraft/client/renderer/extract/LevelExtractor.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchEntityRenderDispatcherBrowserNullEntityGuard", "REQUIRED js wasmgc", () -> RenderPatches.patchEntityRenderDispatcherBrowserNullEntityGuard(args[0], root.resolve(
                "net/minecraft/client/renderer/entity/EntityRenderDispatcher.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchRenderSectionRegionBrowserDirectSectionCoordinates", "PERF js wasmgc", () -> MeshPatches.patchRenderSectionRegionBrowserDirectSectionCoordinates(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/RenderSectionRegion.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSectionCompilerBrowserDirectRelativeCoordinates", "PERF js wasmgc", () -> MeshPatches.patchSectionCompilerBrowserDirectRelativeCoordinates(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionCompiler.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchSectionNeighborReadiness", "REQUIRED js wasmgc", () -> MeshPatches.patchSectionNeighborReadiness(args[0], root, minecraftVersion));
        PatchRegistry.run("MinecraftClientPatcher.patchSectionRenderDispatcherBrowserThrottles", "REQUIRED js wasmgc", () -> MeshPatches.patchSectionRenderDispatcherBrowserThrottles(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchCurrentSectionTaskQueueBrowserPriorities", "PERF js wasmgc", () -> MeshPatches.patchCurrentSectionTaskQueueBrowserPriorities(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionTaskDynamicQueue.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchUberGpuBufferBrowserTelemetry", "REQUIRED js wasmgc", () -> RenderPatches.patchUberGpuBufferBrowserTelemetry(args[0], root.resolve(
                "com/mojang/blaze3d/vertex/UberGpuBuffer.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchFaceBakeryBrowserFloatTolerance", "REQUIRED js", () -> RenderPatches.patchFaceBakeryBrowserFloatTolerance(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchLevelLoadTrackerBrowserTimeout", "FEATURE js wasmgc", () -> NetworkPatches.patchLevelLoadTrackerBrowserTimeout(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchLevelLoadTrackerBrowserNoCloseDelay", "FEATURE js wasmgc", () -> UiPatches.patchLevelLoadTrackerBrowserNoCloseDelay(args[0], root));
        PatchRegistry.run("MinecraftClientPatcher.patchClientPacketListenerLoadingDiagnostics", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchClientPacketListenerLoadingDiagnostics(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientPacketListener.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchWorldUnloadTelemetry", "TELEMETRY js wasmgc", () -> TelemetryPatches.patchWorldUnloadTelemetry(args[0], root.resolve(
                "net/minecraft/client/telemetry/events/WorldUnloadEvent.class")));
        PatchRegistry.run("MinecraftClientPatcher.generateSoundApiStubs", "REQUIRED js wasmgc", () -> AudioPatches.generateSoundApiStubs(root));
        PatchRegistry.run("MinecraftClientPatcher.generateCryptoApiStubs", "REQUIRED js wasmgc", () -> PlatformPatches.generateCryptoApiStubs(root));
        PatchRegistry.run("MinecraftClientPatcher.generateUnsafeStub", "REQUIRED js wasmgc", () -> PlatformPatches.generateUnsafeStub(root));
        // 26.2 and 26.3 take the mesh kernel patches in Minecraft262BrowserPatcher, after the
        // compile task rewrites there; any other profile logs here that it keeps vanilla meshing.
        if (!isModernProfile(minecraftVersion)) {
            PatchRegistry.run("MinecraftClientPatcher.meshKernelPatches", "PERF NOT_APPLICABLE js wasmgc", () -> MeshPatches.meshKernelPatches(args[0], root, minecraftVersion));
        }
        // WebAssembly GC only (design D4): the frame boundary at the head of the Minecraft.run
        // loop, last so that it patches the Minecraft class that patchMinecraft wrote.
        PatchRegistry.run("MinecraftClientPatcher.patchMinecraftRunFrameBoundary", "REQUIRED wasmgc", () -> PlatformPatches.patchMinecraftRunFrameBoundary(args[0], root.resolve(
                "net/minecraft/client/Minecraft.class")));
        PatchRegistry.run("MinecraftClientPatcher.patchTickPacketParity", "TELEMETRY wasmgc", () -> {
            if (BuildTarget.wasmGc() && TickPacketParityPatches.requested()) {
                TickPacketParityPatches.apply(args[0], root);
                TickPacketParityPatches.applyBrowser(args[0], root);
                TickPacketParityPatches.applyBrowserSchedule(args[0], root);
                TickPacketParityPatches.applyBrowserPacketApplyInline(args[0], root);
            }
        });
        PatchRegistry.printSummary();
    }
}
