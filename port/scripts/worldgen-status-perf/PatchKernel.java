package dev.gaius.wgperf;

import java.nio.file.Path;

public final class PatchKernel {
    public static void main(String[] args) throws Exception {
        if (!dev.gaius.tools.patches.worldgen.WorldgenKernelPatches.patch(Path.of(args[0]), Path.of(args[1]))) {
            throw new IllegalStateException("No worldgen hooks patched");
        }
    }
}
