package dev.gaius.browser;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.datafix.DataFixers;

/** Defers the expensive historical data-fixer graph until an older save actually needs it. */
public final class BrowserLazyDataFixer implements DataFixer {
    private static final BrowserLazyDataFixer INSTANCE = new BrowserLazyDataFixer();

    private DataFixer fallback;

    private BrowserLazyDataFixer() {
    }

    public static DataFixer instance() {
        return INSTANCE;
    }

    /** Current-version browser saves do not need the eager historical schema warmup. */
    public static CompletableFuture<?> skipEagerOptimization(Set<?> ignoredTypes) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> Dynamic<T> update(
            DSL.TypeReference type,
            Dynamic<T> input,
            int sourceVersion,
            int targetVersion) {
        if (sourceVersion >= targetVersion) {
            return input;
        }
        return fallback().update(type, input, sourceVersion, targetVersion);
    }

    @Override
    public Schema getSchema(int version) {
        return fallback().getSchema(version);
    }

    private DataFixer fallback() {
        if (fallback == null) {
            BrowserIntegratedServerMain.reportRuntimeEvent(
                    "datafixer-fallback",
                    "older world data requires the official migration graph");
            fallback = DataFixers.getDataFixer();
        }
        return fallback;
    }
}
