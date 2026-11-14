import dev.gaius.tools.patches.ui.UiPatches;
import java.nio.file.Path;

/**
 * Writes the browser's font patches of a vanilla client jar into a class directory for the
 * browser arms of the unihex gate: the real patch bodies of MinecraftClientPatcher
 * (patchUnihexProviderBrowserAccess, patchUnihexProviderBrowserBulkParser and
 * patchFontProviderSelectionIndex), nothing else.
 *
 * <pre>java UnihexGatePatcher &lt;client-named.jar&gt; &lt;output dir&gt;</pre>
 */
public final class UnihexGatePatcher {
    private UnihexGatePatcher() {
    }

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        Path root = Path.of(args[1]);
        String providers = "net/minecraft/client/gui/font/providers/";
        UiPatches.patchUnihexProviderBrowserBulkParser(jar, root.resolve(providers + "UnihexProvider$Definition.class"));
        UiPatches.patchUnihexProviderBrowserAccess(jar, root.resolve(providers + "UnihexProvider.class"));
        UiPatches.patchFontProviderSelectionIndex(jar, root);
        System.out.println("UNIHEX_GATE_PATCHED " + root);
    }
}
