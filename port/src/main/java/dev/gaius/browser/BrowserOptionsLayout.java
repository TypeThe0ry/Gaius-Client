package dev.gaius.browser;

import java.util.Arrays;

/**
 * Option-list helper of the browser settings layout ({@code UiPatches.patchOptionsScreensBrowserLayout}).
 *
 * <p>The patched option-array builders of the settings screens load {@code null} in place of an
 * option that has no effect in a browser (exclusive fullscreen, the macOS menu bar, the audio
 * output device, the quit shortcuts, ...) and pass the finished array through {@link #compact}
 * before {@code OptionsList.addSmall} lays it out two per row. The options themselves stay in
 * {@code Options}, so options.txt keeps every key.
 */
public final class BrowserOptionsLayout {
    private BrowserOptionsLayout() {
    }

    /**
     * Returns {@code options} without its {@code null} entries, in order. The result keeps the
     * runtime array type of {@code options} ({@code OptionInstance[]}), which the patched caller
     * casts back to; an array without {@code null} is returned as is.
     */
    public static Object[] compact(Object[] options) {
        if (options == null) {
            return null;
        }
        int kept = 0;
        for (Object option : options) {
            if (option != null) {
                kept++;
            }
        }
        if (kept == options.length) {
            return options;
        }
        Object[] result = Arrays.copyOf(options, kept);
        int index = 0;
        for (Object option : options) {
            if (option != null) {
                result[index++] = option;
            }
        }
        return result;
    }
}
