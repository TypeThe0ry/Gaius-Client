package dev.gaius.browser;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.minecraft.commands.Commands;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldDataConfiguration;

/**
 * Runs vanilla's own {@link WorldLoader#load} once, ahead of the click, over the default data
 * pack and the default load parameters (a dedicated server's command set and function
 * permissions). {@code BrowserWorldLoadCache} records what the load computes (the tag lists, the
 * two decoded registry groups and the reloadable resources) so that the real load of a world
 * with the same pack selection reuses it. The load is stopped before the step that binds tags
 * and components into the static registries (the recorded resources are never completed to
 * vanilla's next stage), so this run changes no global state beyond class initialization.
 */
final class BrowserWorldLoadOps {
    private BrowserWorldLoadOps() {
    }

    static String prewarm(boolean forceMiss) throws Exception {
        CompletableFuture<Void> done = BrowserWorldLoadCache.beginRecording();
        boolean complete = false;
        try {
            WorldLoader.InitConfig config = new WorldLoader.InitConfig(
                    new WorldLoader.PackConfig(ServerPacksSource.createVanillaTrustedRepository(),
                            WorldDataConfiguration.DEFAULT, false, false),
                    Commands.CommandSelection.DEDICATED,
                    LevelBasedPermissionSet.GAMEMASTER);
            // The stand-in world data supplier hands the loader the datapack dimensions as the
            // final ones; nothing of it is kept.
            WorldLoader.WorldDataSupplier<Object> supplier = context ->
                    new WorldLoader.DataLoadOutput<>(null, context.datapackDimensions());
            WorldLoader.ResultFactory<Object, Object> never = (resources, reloadable, layers, cookie) -> {
                throw new IllegalStateException("world load prewarm must not reach the result");
            };
            try {
                // The executors of the Worker's own start (ServerWorkerPatches' cooperative world
                // loader patch): both stages run on the queue Util.blockUntilDone drains.
                Util.blockUntilDone(executor -> {
                    WorldLoader.load(config, supplier, never, executor, executor).whenComplete((result, failure) -> {
                        if (failure != null) {
                            done.completeExceptionally(failure);
                        }
                    });
                    return done;
                }).get();
            } catch (ExecutionException failure) {
                throw failure;
            }
            complete = BrowserWorldLoadCache.endRecording(forceMiss);
            if (!complete) {
                return "incomplete";
            }
            return BrowserWorldLoadCache.hasResources() ? "ready+resources" : "ready";
        } finally {
            if (!complete) {
                BrowserWorldLoadCache.discard();
            }
        }
    }

    static void discard() {
        BrowserWorldLoadCache.discard();
    }
}
