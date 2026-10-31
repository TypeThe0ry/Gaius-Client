package dev.gaius.tools;

import dev.gaius.tools.patches.render.LegacyRenderPatches;
import dev.gaius.tools.patches.ui.LegacyUiPatches;
import dev.gaius.tools.patches.worldgen.LegacyWorldgenPatches;
import java.io.IOException;
import java.nio.file.Path;

/** Adds checkpoint-only holder cursors for the Minecraft 1.21.11 browser client. */
public final class Minecraft12111BrowserPatcher {

    private Minecraft12111BrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: Minecraft12111BrowserPatcher INPUT_JAR OUTPUT_ROOT [MINECRAFT_VERSION]");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        // The legacy patch set's profile: the argument, else the build's profile
        // (GAIUS_MINECRAFT_VERSION), else the one profile that takes this patch set.
        String profile = args.length == 3 ? args[2] : PatchRegistry.requestedProfile();
        if (profile == null) {
            profile = VersionProfile.only(VersionProfile.LEGACY_PATCH_SET);
        }
        if (!VersionProfile.forId(profile).legacyPatchSet()) {
            throw new IllegalArgumentException(
                    "Minecraft12111BrowserPatcher serves the legacy patch set only, not profile " + profile);
        }
        PatchRegistry.configureProfile(profile);
        PatchRegistry.configureOutput(root);
        PatchRegistry.run("Minecraft12111BrowserPatcher.patchGraphicsPresetBrowserDistances", "FEATURE js wasmgc 1.21.11", () -> LegacyUiPatches.patchGraphicsPresetBrowserDistances(jar, root));
        PatchRegistry.run("Minecraft12111BrowserPatcher.patchChunkGenerationCooperation", "REQUIRED js 1.21.11", () -> LegacyWorldgenPatches.patchChunkGenerationCooperation(jar, root));
        PatchRegistry.run("Minecraft12111BrowserPatcher.patchGraphicsPresetStartupReplay", "FEATURE js wasmgc 1.21.11", () -> LegacyUiPatches.patchGraphicsPresetStartupReplay(jar, root));
        // The blaze3d-direct-0 render seam (WebAssembly GC only; the JavaScript build keeps GlDevice).
        PatchRegistry.run("Minecraft12111BrowserPatcher.patchInitRendererDeviceFactory", "REQUIRED wasmgc 1.21.11", () -> LegacyRenderPatches.patchInitRendererDeviceFactory(jar, root));
        PatchRegistry.run("Minecraft12111BrowserPatcher.patchLineWidthVertexFormatAlignment", "REQUIRED wasmgc 1.21.11", () -> LegacyRenderPatches.patchLineWidthVertexFormatAlignment(jar, root));
        PatchRegistry.printSummary();
    }
}
