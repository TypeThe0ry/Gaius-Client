package dev.gaius.browser;

import java.net.URI;
import net.minecraft.client.Minecraft;
import org.teavm.jso.JSBody;

/**
 * Input dispatch helpers for the Minecraft 26.3 SDL input path (work package P2).
 *
 * <p>{@code InputPatches263} rewrites the eight {@code Minecraft.execute(Runnable)} calls
 * of {@code com.mojang.blaze3d.platform.SDLEventHandler} into {@link #runNow}. The
 * browser pump is re-entrant (disconnect, world load and {@code Minecraft.<init>} pump
 * events from inside input handlers), where {@code execute} would defer the handler and
 * the deferred lambda would later read an {@code SDL_Event} that pollEvents has already
 * reused or freed. Running inline keeps the synchronous dispatch of Gaius on 26.2.
 *
 * <p>Unreachable on 26.2: nothing in the 26.2 patch set references this class, so TeaVM
 * does not emit it there.
 */
public final class BrowserInputDispatch {
    private BrowserInputDispatch() {
    }

    /** Replacement for {@code minecraft.execute(task)} in SDLEventHandler. */
    public static void runNow(Minecraft minecraft, Runnable task) {
        task.run();
    }

    /**
     * Replacement body of {@code com.mojang.blaze3d.Blaze3D.openUri(URI)}: opens http(s)
     * links in a new tab while the click that asked for it still counts as user
     * activation; other schemes (the screenshot and resource pack folders) are ignored.
     */
    public static void openUri(URI uri) {
        if (uri == null) {
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return;
        }
        String lower = scheme.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("http") || lower.equals("https")) {
            openInNewTab(uri.toString());
        }
    }

    @JSBody(params = {"url"}, script = """
            try {
              window.open(url, '_blank', 'noopener,noreferrer');
            } catch (ignored) {
              // A blocked popup is not an error for the game.
            }
            """)
    private static native void openInNewTab(String url);
}
