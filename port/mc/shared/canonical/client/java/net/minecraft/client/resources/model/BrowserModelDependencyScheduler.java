package net.minecraft.client.resources.model;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.cuboid.MissingCuboidModel;
import net.minecraft.resources.Identifier;

/** Splits model dependency discovery across browser event-loop turns. */
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
            this.blockRoots = blockStates.models().values().iterator();
            this.itemRoots = clientItems.contents().values().iterator();
            this.result = result;
            this.executor = executor;
            this.discovery = new ModelDiscovery(models, MissingCuboidModel.missingModel());
            this.discovery.addSpecialModel(
                    ItemModelGenerator.GENERATED_ITEM_MODEL_ID,
                    new ItemModelGenerator());
        }

        private void schedule() {
            this.executor.execute(this);
        }

        @Override
        public void run() {
            if (this.result.isDone()) {
                return;
            }
            long startedAt = System.nanoTime();
            try {
                while (this.blockRoots.hasNext()) {
                    this.discovery.addRoot(this.blockRoots.next());
                    if (System.nanoTime() - startedAt >= WORK_BUDGET_NANOS) {
                        schedule();
                        return;
                    }
                }
                while (this.itemRoots.hasNext()) {
                    this.discovery.addRoot(this.itemRoots.next().model());
                    if (System.nanoTime() - startedAt >= WORK_BUDGET_NANOS) {
                        schedule();
                        return;
                    }
                }
                this.result.complete(this.discovery);
            } catch (Throwable error) {
                this.result.completeExceptionally(error);
            }
        }
    }
}
