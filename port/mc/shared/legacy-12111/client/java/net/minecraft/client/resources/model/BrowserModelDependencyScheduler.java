package net.minecraft.client.resources.model;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.resources.Identifier;

/**
 * Splits custom resource-pack root discovery across browser frames.
 *
 * <p>The vanilla continuation visits every block-state and item root in one event-loop turn. The
 * discovery result is immutable until the continuation completes, so yielding between roots keeps
 * the original reload dependency graph intact while allowing the browser to paint and process
 * network events.
 */
public final class BrowserModelDependencyScheduler {
    private static final long WORK_BUDGET_NANOS = 4_000_000L;

    private BrowserModelDependencyScheduler() {
    }

    public static Function<Void, CompletableFuture<ModelDiscovery>> continuation(
            CompletableFuture<Map<Identifier, UnbakedModel>> models,
            CompletableFuture<BlockStateModelLoader.LoadedModels> blockStates,
            CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> clientItems,
            Executor executor) {
        return ignored -> discoverAsync(models.join(), blockStates.join(), clientItems.join(), executor);
    }

    private static CompletableFuture<ModelDiscovery> discoverAsync(
            Map<Identifier, UnbakedModel> models,
            BlockStateModelLoader.LoadedModels blockStates,
            ClientItemInfoLoader.LoadedClientInfos clientItems,
            Executor executor) {
        CompletableFuture<ModelDiscovery> result = new CompletableFuture<>();
        DiscoveryTask task = new DiscoveryTask(models, blockStates, clientItems, result, executor);
        task.schedule();
        return result;
    }

    private static final class DiscoveryTask implements Runnable {
        private final Iterator<BlockStateModel.UnbakedRoot> blockRoots;
        private final Iterator<ClientItem> itemRoots;
        private final CompletableFuture<ModelDiscovery> result;
        private final Executor executor;
        private final ModelDiscovery discovery;

        private DiscoveryTask(
                Map<Identifier, UnbakedModel> models,
                BlockStateModelLoader.LoadedModels blockStates,
                ClientItemInfoLoader.LoadedClientInfos clientItems,
                CompletableFuture<ModelDiscovery> result,
                Executor executor) {
            blockRoots = blockStates.models().values().iterator();
            itemRoots = clientItems.contents().values().iterator();
            this.result = result;
            this.executor = executor;
            discovery = new ModelDiscovery(models, MissingBlockModel.missingModel());
            discovery.addSpecialModel(
                    net.minecraft.client.renderer.block.model.ItemModelGenerator.GENERATED_ITEM_MODEL_ID,
                    new net.minecraft.client.renderer.block.model.ItemModelGenerator());
        }

        private void schedule() {
            executor.execute(this);
        }

        @Override
        public void run() {
            if (result.isDone()) {
                return;
            }
            long startedAt = System.nanoTime();
            try {
                while (blockRoots.hasNext()) {
                    discovery.addRoot(blockRoots.next());
                    if (System.nanoTime() - startedAt >= WORK_BUDGET_NANOS) {
                        schedule();
                        return;
                    }
                }
                while (itemRoots.hasNext()) {
                    discovery.addRoot(itemRoots.next().model());
                    if (System.nanoTime() - startedAt >= WORK_BUDGET_NANOS) {
                        schedule();
                        return;
                    }
                }
                result.complete(discovery);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }
    }
}
