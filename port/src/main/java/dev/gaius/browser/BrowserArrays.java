package dev.gaius.browser;

import java.util.function.Predicate;

/** Browser-safe replacements for reflective array creation used by vanilla bytecode. */
public final class BrowserArrays {
    private BrowserArrays() {
    }

    public static Object newInstance(Class<?> componentType, int[] dimensions) {
        if (componentType == Predicate.class && dimensions.length == 3) {
            return new Predicate<?>[dimensions[0]][dimensions[1]][dimensions[2]];
        }
        throw new IllegalArgumentException("Unsupported browser array shape");
    }
}
