package dev.gaius.tools.atlasprebake;

import dev.gaius.tools.patches.render.RenderPatches;
import java.nio.file.Path;

/**
 * Applies the two atlas prebake patches of port/tools (the code build-overlays.sh runs) to the
 * vanilla client jar, so the JVM gate loads exactly the bytecode the browser build gets.
 *
 * <pre>PatchStep client-named.jar output-directory</pre>
 */
public final class PatchStep {
    private PatchStep() {
    }

    public static void main(String[] arguments) throws Exception {
        String jar = arguments[0];
        Path root = Path.of(arguments[1]);
        RenderPatches.patchMipmapGeneratorAtlasPrebake(jar,
                root.resolve("net/minecraft/client/renderer/texture/MipmapGenerator.class"));
        RenderPatches.patchStitcherAtlasPrebake(jar,
                root.resolve("net/minecraft/client/renderer/texture/Stitcher.class"),
                root.resolve("net/minecraft/client/renderer/texture/Stitcher$Region.class"));
    }
}
