package dev.gaius.browser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounds completed migration rewrites without changing in-flight rewrite coordination. */
public final class BrowserDataFixerCaches {
    public static final int MAX_COMPLETED_REWRITES = 65_536;

    private BrowserDataFixerCaches() {
    }

    public static <K, V> Map<K, V> completedRewrites() {
        return Collections.synchronizedMap(new CompletedRewrites<>());
    }

    private static final class CompletedRewrites<K, V> extends LinkedHashMap<K, V> {
        CompletedRewrites() {
            super(256, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > MAX_COMPLETED_REWRITES;
        }
    }
}
