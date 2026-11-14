import dev.gaius.tools.patches.storage.StoragePatches;
import java.nio.file.Path;

/** Runs patchPalettedContainerBrowserEncode on a client jar: {@code JAR OUTPUT_ROOT}. */
public final class ApplyPalettedContainerPatch {
    private ApplyPalettedContainerPatch() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: ApplyPalettedContainerPatch CLIENT_JAR OUTPUT_ROOT");
        }
        StoragePatches.patchPalettedContainerBrowserEncode(args[0],
                Path.of(args[1]).resolve("net/minecraft/world/level/chunk/PalettedContainer.class"));
    }
}
