package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * Publishes Minecraft's resource-reload progress to the launcher page.
 *
 * <p>{@code MinecraftClientPatcher.patchLoadingOverlayBrowserForeground} calls
 * {@link #reloadProgress} from {@code LoadingOverlay.extractRenderState} with
 * {@code ReloadInstance.getActualProgress()}. The boot screen (boot-art.js) maps it onto the
 * last segment of its progress bar, which otherwise sat at one value for the whole reload.
 */
public final class BrowserBootProgress {
    private BrowserBootProgress() {
    }

    @JSBody(params = "progress", script = "globalThis.__gaiusReloadProgress = progress;")
    public static native void reloadProgress(float progress);
}
