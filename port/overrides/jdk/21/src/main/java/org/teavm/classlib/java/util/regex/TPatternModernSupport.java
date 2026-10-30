package org.teavm.classlib.java.util.regex;

import java.util.function.Predicate;

public final class TPatternModernSupport {
    private TPatternModernSupport() {
    }

    public static Predicate<String> asPredicate(TPattern pattern) {
        return value -> pattern.matcher(value).find();
    }
}
