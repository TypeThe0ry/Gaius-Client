package dev.gaius.tools;

import static dev.gaius.tools.patches.worldgen.RenderpearlWorldgenPatches.CARVING_MASK;
import static dev.gaius.tools.patches.worldgen.RenderpearlWorldgenPatches.MATERIAL_RULE_CONTEXT;

import dev.gaius.tools.patches.audio.RenderpearlAudioPatches;
import dev.gaius.tools.patches.input.RenderpearlInputPatches;
import dev.gaius.tools.patches.network.RenderpearlNetworkPatches;
import dev.gaius.tools.patches.platform.JdkCompatPatches;
import dev.gaius.tools.patches.platform.RenderpearlPlatformPatches;
import dev.gaius.tools.patches.render.QualityTierPatches;
import dev.gaius.tools.patches.render.RenderpearlRenderPatches;
import dev.gaius.tools.patches.render.RenderpearlTerrainPatches;
import dev.gaius.tools.patches.resources.RenderpearlResourcePatches;
import dev.gaius.tools.patches.serverworker.RenderpearlServerWorkerPatches;
import dev.gaius.tools.patches.worldgen.RenderpearlWorldgenPatches;
import dev.gaius.tools.patches.worldgen.WorldgenStrictMath;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Tail of the modern patch chain for the renderpearl render seam (26.3 and later):
 * AbstractSpliterator &rarr; MinecraftClientPatcher &rarr; MinecraftChunkDrawTelemetryPatcher
 * &rarr; Minecraft262BrowserPatcher &rarr; MinecraftServerWorkerPatcher &rarr; this patcher.
 * It reads the jar produced by the previous steps, so it sees their output. A profile selects it
 * through {@code extraPatchers} in port/versions; the spec of every patch names its profiles.
 *
 * <p>Holds the transforms that only the renderpearl generation needs and the rewrites that share
 * nothing with the 26.2 patches. The patch code lives in the domain packages
 * ({@code patches/<domain>}, port/tools/patchkit/README.md "Domains"): {@code Renderpearl<Domain>Patches}
 * for render, input, platform, server-worker, network, audio, resources and worldgen, plus
 * {@link QualityTierPatches} (graphics quality tiers), {@link RenderpearlTerrainPatches}
 * (terrain), {@link WorldgenStrictMath} (Java float semantics of the worldgen classes) and
 * {@link JdkCompatPatches} (the JDK APIs TeaVM's class library lacks). This class registers them
 * in the historical order of contract C2, group by group, under their historical ids
 * ({@code <Group>Patches263.<method>}, which key the manifests, classification.tsv, the
 * target-skip and bring-up lists): render (P3), input (P2), terrain (P5), worldgen (P6), server
 * (P7a), ui (P8), then the worldgen float rewrite (after every group, so it also rounds what they
 * inserted) and the JDK API redirects (over the whole jar, after the package domains), and last
 * the non-suspending worldgen guard over the whole output. Each group prints its banner line
 * ({@code <Group>Patches263: ...}); patch-registry-smoke checks their order. A patch reads a
 * class from {@code root} when an earlier patch already wrote it there, otherwise from the jar, so
 * two patches of one class compose instead of the later one overwriting the earlier.
 *
 * <p>Never runs for a blaze3d profile: it refuses a profile without a renderpearl render seam and
 * any jar that does not probe as the renderpearl API, so blaze3d output cannot depend on it.
 */
public final class Minecraft263BrowserPatcher {
    private Minecraft263BrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: Minecraft263BrowserPatcher INPUT_JAR OUTPUT_ROOT MINECRAFT_VERSION");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        String version = args[2];
        if (!VersionProfile.forId(version).renderpearl()) {
            throw new IllegalArgumentException(
                    "Minecraft263BrowserPatcher runs only for a renderpearl render seam, not for profile "
                            + version);
        }
        PatchRegistry.configureProfile(version);
        PatchRegistry.configureOutput(root);
        ModernSymbols symbols = null;
        try {
            symbols = ModernSymbols.probe(jar);
            if (!symbols.renderpearl()) {
                throw new IllegalStateException("not a 26.3+ renderpearl client (" + symbols.summary() + ")");
            }
            System.out.println("Minecraft263BrowserPatcher: profile " + version + ", "
                    + symbols.summary());
        } catch (RuntimeException failure) {
            String reason = "Minecraft263BrowserPatcher ModernSymbols preflight failed for " + jar
                    + ": " + failure.getMessage();
            System.err.println("PATCH_PREFLIGHT_FAILURE " + reason);
            symbols = null;
            PatchRegistry.configurePreflightFailure(reason);
        }
        renderPatches(jar, root, symbols);
        inputPatches(jar, root, symbols);
        terrainPatches(jar, root, symbols);
        worldgenPatches(jar, root, symbols);
        serverPatches(jar, root);
        uiPatches(jar, root);
        strictMathPatches(jar, root);
        // Whole-jar rewrite: after the package domains, so it also sees the classes they wrote.
        jdkCompatPatches(jar, root);
        // After every domain: the worldgen non-suspending guard sees the whole output of the step.
        worldgenChainTail(jar, root);
        PatchRegistry.printSummary();
    }

    /** The render group (work package P3): renderpearl backends, platform stubs, quality tiers. */
    public static void renderPatches(String jar, Path root, ModernSymbols symbols) throws IOException {
        System.out.println("RenderPatches263: 26.3 render backend patches (P3)");
        PatchRegistry.run("RenderPatches263.patchGlBackendLibrary", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlRenderPatches.patchGlBackendLibrary(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchVulkanBackend", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlRenderPatches.patchVulkanBackend(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchVulkanFeatureSetsBootstrap", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlRenderPatches.patchVulkanFeatureSetsBootstrap(jar, root));
        PatchRegistry.run("RenderPatches263.patchSystemSpecsCpuInfo", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlPlatformPatches.patchSystemSpecsCpuInfo(jar, root));
        PatchRegistry.run("RenderPatches263.patchGlBufferExplicitFlush", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlRenderPatches.patchGlBufferExplicitFlush(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchImprovedTransparencyByTier", "REQUIRED js wasmgc >=26.3",
                () -> QualityTierPatches.patchImprovedTransparencyByTier(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchLevelQualityHooks", "FEATURE js wasmgc >=26.3",
                () -> QualityTierPatches.patchLevelQualityHooks(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchSetupDrawUseProgramBeforeVertexArrayBind",
                "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlRenderPatches.patchSetupDrawUseProgramBeforeVertexArrayBind(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchMacosUtil", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlPlatformPatches.patchMacosUtil(jar, root));
        // Rewrites the inventory throttle MinecraftClientPatcher injected into
        // GameRenderer.render, so it runs after the GameRenderer patches above.
        PatchRegistry.run("RenderPatches263.patchInventoryWorldRenderThrottle", "FEATURE js wasmgc >=26.3",
                () -> QualityTierPatches.patchInventoryWorldRenderThrottle(jar, root, symbols));
    }

    /**
     * The input group (work package P2): first checks that the SDL shim covers every SDL entry
     * point the client references, then the SDL input patches and {@code Blaze3D.openUri}.
     */
    public static void inputPatches(String jar, Path root, ModernSymbols symbols) throws IOException {
        if (symbols != null && !symbols.sdl()) {
            throw new IllegalStateException("InputPatches263 needs the SDL input backend: " + symbols.summary());
        }
        if (symbols != null) RenderpearlInputPatches.checkSdlShimCoverage(jar);
        PatchRegistry.run("InputPatches263.patchSdlEventHandlerDispatch", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlInputPatches.patchSdlEventHandlerDispatch(jar, root));
        PatchRegistry.run("InputPatches263.patchMouseHandlerButtonHooks", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlInputPatches.patchMouseHandlerButtonHooks(jar, root));
        PatchRegistry.run("InputPatches263.patchBlaze3dOpenUri", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlPlatformPatches.patchBlaze3dOpenUri(jar, root));
        PatchRegistry.run("InputPatches263.patchSdlDebug", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlInputPatches.patchSdlDebug(jar, root));
    }

    /** The terrain group (work package P5): the multi-draw-indirect pin and the chain check. */
    public static void terrainPatches(String jar, Path root, ModernSymbols symbols) throws IOException {
        if (symbols != null && !symbols.renderpearl()) {
            throw new IllegalStateException("TerrainPatches263 requires a renderpearl client");
        }
        System.out.println("TerrainPatches263: multi-draw-indirect pin and terrain chain check (P5)");
        PatchRegistry.run("TerrainPatches263.forceSeparateTerrainDraws", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlTerrainPatches.forceSeparateTerrainDraws(jar, root));
        PatchRegistry.run("TerrainPatches263.patchReusableDrawGroupCache", "PERF js wasmgc >=26.3",
                () -> RenderpearlTerrainPatches.patchReusableDrawGroupCache(jar, root));
        PatchRegistry.run("TerrainPatches263.verifyTerrainChain", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlTerrainPatches.verifyTerrainChain(jar, root, symbols));
    }

    /** The worldgen group (work package P6); its non-suspending guard runs last, see main. */
    public static void worldgenPatches(String jar, Path root, ModernSymbols symbols) throws IOException {
        if (symbols != null && (!symbols.hasEntry(CARVING_MASK + ".class")
                || !symbols.hasEntry(MATERIAL_RULE_CONTEXT + ".class"))) {
            throw new IllegalStateException("WorldgenPatches263: " + jar
                    + " has no 26.3 CarvingMask/MaterialRuleContext (" + symbols.summary() + ")");
        }
        System.out.println("WorldgenPatches263: 26.3 worldgen patches and non-suspending guard (P6)");
        PatchRegistry.run("WorldgenPatches263.patchCarvingMaskDeepPulses", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlWorldgenPatches.patchCarvingMaskDeepPulses(jar, root));
        PatchRegistry.run("WorldgenPatches263.patchMaterialRuleContextIntCounters", "PERF js wasmgc >=26.3",
                () -> RenderpearlWorldgenPatches.patchMaterialRuleContextIntCounters(jar, root));
        PatchRegistry.run("WorldgenPatches263.patchMaterialSystemSurfaceHeightCache", "PERF js wasmgc >=26.3",
                () -> RenderpearlWorldgenPatches.patchMaterialSystemSurfaceHeightCache(jar, root));
        PatchRegistry.run("WorldgenPatches263.patchMaterialRuleContextDirectBiomeManager", "PERF js wasmgc >=26.3",
                () -> RenderpearlWorldgenPatches.patchMaterialRuleContextDirectBiomeManager(jar, root));
        PatchRegistry.run("WorldgenPatches263.patchChunkGeneratorStructureGroupingCache", "PERF js wasmgc >=26.3",
                () -> RenderpearlWorldgenPatches.patchChunkGeneratorStructureGroupingCache(jar, root));
    }

    /** The server group (work package P7a): server-worker, platform and network patches. */
    public static void serverPatches(String jar, Path root) throws IOException {
        // Domain banner: patch-registry-smoke checks the order of these lines.
        System.out.println("ServerPatches263: 26.3 server patches (P7a)");
        PatchRegistry.run("ServerPatches263.patchRegistryLoadTaskBrowserStartupYield",
                "REQUIRED js >=26.3", () -> RenderpearlPlatformPatches.patchRegistryLoadTaskBrowserStartupYield(jar, root));
        PatchRegistry.run("ServerPatches263.patchPlayerListIsOpBrowserCommands", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlServerWorkerPatches.patchPlayerListIsOpBrowserCommands(jar, root));
        PatchRegistry.run("ServerPatches263.patchDiscoveryServiceBrowser", "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlNetworkPatches.patchDiscoveryServiceBrowser(jar, root));
        PatchRegistry.run("ServerPatches263.patchMobSpawnOverlayCache", "PERF js wasmgc >=26.3",
                () -> RenderpearlServerWorkerPatches.patchMobSpawnOverlayCache(jar, root));
    }

    /** The ui group (work package P8): the mining hit sound volume and the vanilla pack layers. */
    public static void uiPatches(String jar, Path root) throws IOException {
        PatchRegistry.run("UiPatches263.patchClientLevelBreakingSoundVolume", "FEATURE js wasmgc >=26.3",
                () -> RenderpearlAudioPatches.patchClientLevelBreakingSoundVolume(jar, root));
        PatchRegistry.run("UiPatches263.patchVanillaPackResourcesSingleLayer", "PERF js wasmgc >=26.3",
                () -> RenderpearlResourcePatches.patchVanillaPackResourcesSingleLayer(jar, root));
        System.out.println("UiPatches263: ClientLevel.playBreakingSound hit volume /8 -> /4;"
                + " VanillaPackResourcesBuilder.build uses the full resources as the only layer");
    }

    /**
     * Java float and cast semantics of the worldgen classes (P6), after the package domains,
     * before the JDK API redirects. The WebAssembly GC build only rewrites the DoubleStream sums.
     */
    public static void strictMathPatches(String jar, Path root) throws IOException {
        boolean sumsOnly = BuildTarget.wasmGc();
        PatchRegistry.run("StrictMath263.roundWorldgenArithmetic", "REQUIRED js wasmgc >=26.3",
                () -> WorldgenStrictMath.roundWorldgenArithmetic(jar, root, sumsOnly));
    }

    /** The JDK APIs that TeaVM's class library lacks (P0), over the whole jar. */
    public static void jdkCompatPatches(String jar, Path root) throws IOException {
        // Domain banner: patch-registry-smoke checks the order of these lines.
        System.out.println("JdkCompatPatches263: TeaVM class library gaps (P0)");
        PatchRegistry.run("JdkCompatPatches263.redirectJdkApis", "REQUIRED js wasmgc >=26.3",
                () -> JdkCompatPatches.redirectJdkApis(jar, root));
        PatchRegistry.run("JdkCompatPatches263.remapScopedValue", "REQUIRED js wasmgc >=26.3",
                () -> JdkCompatPatches.remapScopedValue(jar, root));
    }

    /**
     * Runs the non-suspending worldgen guard (R8) on the finished output of the step, so pulses
     * that later domains insert into guarded code are caught too; it reads root first, then the
     * jar.
     */
    public static void worldgenChainTail(String jar, Path root) throws IOException {
        PatchRegistry.run("WorldgenPatches263.verifyNonSuspendingWorldgenKernels",
                "REQUIRED js wasmgc >=26.3",
                () -> RenderpearlWorldgenPatches.verifyNonSuspendingWorldgenKernels(jar, root));
    }
}
