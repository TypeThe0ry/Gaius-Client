package dev.gaius.tools;

import dev.gaius.tools.patches.light.LightKernelPatches;
import dev.gaius.tools.patches.serverworker.WorkerOverridePatches;
import dev.gaius.tools.patches.worldgen.WorldgenKernelPatches;
import java.io.IOException;
import java.nio.file.Path;

/** Removes dedicated-server services that cannot run inside the browser Worker. */
public final class MinecraftServerWorkerPatcher {
    private MinecraftServerWorkerPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        PatchRegistry.configureOutput(outputRoot);
        PatchRegistry.runPart("MinecraftServerWorkerPatcher.classOverrides", "REQUIRED OVERRIDE js wasmgc",
                () -> WorkerOverridePatches.patchWorker(jar, outputRoot));
        PatchRegistry.runPart("MinecraftServerWorkerPatcher.lightKernelPatches",
                "PERF NOT_APPLICABLE js wasmgc",
                () -> LightKernelPatches.patch(jar, outputRoot));
        PatchRegistry.runPart("MinecraftServerWorkerPatcher.worldgenKernelPatches",
                "PERF NOT_APPLICABLE js wasmgc",
                () -> WorldgenKernelPatches.patch(jar, outputRoot));
    }
}
