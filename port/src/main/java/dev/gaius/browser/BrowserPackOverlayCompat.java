package dev.gaius.browser;

import java.io.File;
import java.util.List;

/** Preserves the overlay list selected by the pack metadata format checks. */
public final class BrowserPackOverlayCompat {
    private BrowserPackOverlayCompat() {
    }

    /**
     * The supplier hook must retain metadata order and exclude unsupported formats,
     * including atlas-only directories. File contents do not establish compatibility.
     */
    public static List<String> mergeSafeAtlasOverlays(File archive, List<String> selected) {
        return List.copyOf(selected);
    }
}
