package dev.gaius.golden.light;

import dev.gaius.browser.kernel.light.BrowserLightEngineHooks;
import dev.gaius.browser.kernel.light.BrowserLightKernel;
import dev.gaius.browser.kernel.light.LightKernelHost;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * First light of the seeded light golden world (the generator of {@code LightGolden}) through a
 * real {@code ThreadedLevelLightEngine}: every column goes through {@code initializeLight} and
 * then {@code lightChunk}, as the INITIALIZE_LIGHT and LIGHT chunk steps call them, and the
 * engine runs its own task queue until it settles. On the vanilla jar this is the vanilla
 * reference; with the {@code LightKernelPatches} output of the profile's patch chain ahead of
 * the jar, {@code lightChunk} goes through {@code BrowserLightKernel} and the light kernel.
 *
 * <p>Writes the block states, light layers and section flags of all 25 columns in the
 * {@code LightGolden} fixture layout, plus {@code route.json} with the kernel counters.
 */
public final class LightRoute {
    private static final int RADIUS = 2;

    private final LevelHeightAccessor height;
    private final Map<ChunkPos, ProtoChunk> chunks = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "light-route-engine");
        thread.setDaemon(true);
        return thread;
    });
    /** The engine's executor; every task holds the host's TURN, as TeaVM runs one thread at a time. */
    private final Executor engineThread = task -> pool.execute(() -> {
        LightKernelHost.TURN.acquireUninterruptibly();
        try {
            task.run();
        } finally {
            LightKernelHost.TURN.release();
        }
    });
    private final ConsecutiveExecutor lightExecutor = new ConsecutiveExecutor(engineThread, "light");
    private final ChunkTaskDispatcher dispatcher = new ChunkTaskDispatcher(lightExecutor, engineThread);
    private final ThreadedLevelLightEngine engine;

    private LightRoute(int minSection, int sectionCount, long seed) throws ReflectiveOperationException {
        // The world of LightGolden, built with the same calls in the same order.
        height = LevelHeightAccessor.create(minSection * 16, sectionCount * 16);
        Strategy strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Random random = new Random(seed);
        BlockState[] choices = {Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState()};
        for (int chunkZ = -RADIUS; chunkZ <= RADIUS; chunkZ++) {
            for (int chunkX = -RADIUS; chunkX <= RADIUS; chunkX++) {
                LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
                for (int section = 0; section < sectionCount; section++) {
                    sections[section] = new LevelChunkSection(new PalettedContainer<>(
                            Blocks.AIR.defaultBlockState(), strategy), null);
                    for (int index = 0; index < 96; index++) {
                        sections[section].setBlockState(random.nextInt(16), random.nextInt(16), random.nextInt(16),
                                choices[random.nextInt(choices.length)]);
                    }
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            if ((localX + localZ + section) % 5 != 0) {
                                sections[section].setBlockState(localX, 8, localZ,
                                        choices[(section + Math.floorMod(chunkX + chunkZ, 4)) % 4]);
                            }
                        }
                    }
                    sections[section].setBlockState(0, 0, 8, Blocks.GLOWSTONE.defaultBlockState());
                    sections[section].setBlockState(15, 15, 8, Blocks.TORCH.defaultBlockState());
                }
                ChunkPos position = new ChunkPos(chunkX, chunkZ);
                ProtoChunk chunk = new ProtoChunk(position, UpgradeData.EMPTY, sections,
                        new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height, null, null);
                chunk.initializeLightSources();
                chunks.put(position, chunk);
            }
        }
        BlockGetter level = new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos position) {
                ProtoChunk chunk = chunks.get(new ChunkPos(position.getX() >> 4, position.getZ() >> 4));
                return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(position);
            }

            @Override
            public FluidState getFluidState(BlockPos position) {
                return getBlockState(position).getFluidState();
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos position) {
                return null;
            }

            @Override
            public int getHeight() {
                return height.getHeight();
            }

            @Override
            public int getMinY() {
                return height.getMinY();
            }
        };
        engine = new ThreadedLevelLightEngine(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                return chunks.get(new ChunkPos(chunkX, chunkZ));
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        }, queueLevels(), true, lightExecutor, dispatcher);
    }

    /**
     * The light engine only asks the chunk map for task queue levels ({@code getChunkQueueLevel},
     * which reads the visible holder map): a map without holders gives every task the lowest
     * priority, as for chunks that are not visible yet.
     */
    private static ChunkMap queueLevels() throws ReflectiveOperationException {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        ChunkMap map = (ChunkMap) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, ChunkMap.class);
        Field visible = ChunkMap.class.getDeclaredField("visibleChunkMap");
        visible.setAccessible(true);
        visible.set(map, new Long2ObjectLinkedOpenHashMap<>());
        return map;
    }

    /** Runs a task on the engine's queue and waits for it. */
    private <T> T onEngine(java.util.function.Supplier<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        lightExecutor.schedule(() -> {
            try {
                result.complete(task.get());
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(60, TimeUnit.SECONDS);
    }

    /** Pumps the engine (the server calls tryScheduleUpdate every tick) until the futures are done. */
    private void pump(List<CompletableFuture<ChunkAccess>> futures, long deadline) throws Exception {
        while (!futures.stream().allMatch(CompletableFuture::isDone)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("light futures did not complete");
            }
            engine.tryScheduleUpdate();
            Thread.sleep(1);
        }
        for (CompletableFuture<ChunkAccess> future : futures) {
            future.join();
        }
    }

    private void settle(long deadline) throws Exception {
        int quiet = 0;
        while (quiet < 3) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("light engine did not settle");
            }
            engine.tryScheduleUpdate();
            Thread.sleep(2);
            boolean idle = onEngine(() -> !engine.hasLightWork() && !dispatcher.hasWork());
            quiet = idle ? quiet + 1 : 0;
        }
    }

    private void light(boolean expectKernel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        Object hooks = engine;
        if (expectKernel != hooks instanceof BrowserLightEngineHooks) {
            throw new IllegalStateException("ThreadedLevelLightEngine is " + (expectKernel ? "not " : "")
                    + "patched for the light kernel");
        }
        List<CompletableFuture<ChunkAccess>> initialized = new ArrayList<>();
        for (ProtoChunk chunk : chunks.values()) {
            initialized.add(engine.initializeLight(chunk, false));
        }
        pump(initialized, deadline);
        List<CompletableFuture<ChunkAccess>> lit = new ArrayList<>();
        for (ProtoChunk chunk : chunks.values()) {
            lit.add(engine.lightChunk(chunk, false));
        }
        pump(lit, deadline);
        settle(deadline);
        for (ProtoChunk chunk : chunks.values()) {
            if (!chunk.isLightCorrect()) {
                throw new IllegalStateException("column " + chunk.getPos() + " is not light-correct");
            }
        }
    }

    private void write(Path output, String profile, String sourceHash, long seed, String mode) throws Exception {
        Files.createDirectories(output);
        int count = height.getSectionsCount();
        int minSection = height.getMinSectionY();
        List<String> rows = new ArrayList<>();
        for (Map.Entry<ChunkPos, ProtoChunk> entry : chunks.entrySet()) {
            ChunkPos position = entry.getKey();
            int chunkX = position.getMinBlockX() >> 4;
            int chunkZ = position.getMinBlockZ() >> 4;
            String name = chunkX + "," + chunkZ;
            ByteBuffer states = ByteBuffer.allocate(count * 4096 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (LevelChunkSection section : entry.getValue().getSections()) {
                for (int cell = 0; cell < 4096; cell++) {
                    states.putShort((short) Block.getId(section.getBlockState(cell & 15, cell >> 8, (cell >> 4) & 15)));
                }
            }
            Files.write(output.resolve(name + ".states"), states.array());
            byte[] flags = new byte[count + 2];
            ByteBuffer light = ByteBuffer.allocate((count + 2) * 4096);
            List<DataLayer> layers = onEngine(() -> {
                List<DataLayer> copies = new ArrayList<>();
                for (int section = 0; section < count + 2; section++) {
                    for (LightLayer kind : new LightLayer[] {LightLayer.BLOCK, LightLayer.SKY}) {
                        DataLayer layer = engine.getLayerListener(kind).getDataLayerData(
                                SectionPos.of(position, minSection - 1 + section));
                        copies.add(layer == null ? null : layer.copy());
                    }
                }
                return copies;
            });
            for (int section = 0; section < count + 2; section++) {
                for (int kind = 0; kind < 2; kind++) {
                    DataLayer layer = layers.get(section * 2 + kind);
                    if (layer != null) {
                        flags[section] |= (byte) (kind == 0 ? 2 : 1);
                        light.put(layer.getData());
                    } else {
                        light.put(new byte[2048]);
                    }
                }
            }
            Files.write(output.resolve(name + ".flags"), flags);
            Files.write(output.resolve(name + ".light"), light.array());
            rows.add("\"" + name + "\"");
        }
        Files.writeString(output.resolve("route.json"), "{\"schema\":1,\"profile\":\"" + profile
                + "\",\"seed\":\"" + seed + "\",\"mode\":\"" + mode + "\",\"sourceSha256\":\"" + sourceHash
                + "\",\"minSection\":" + minSection + ",\"sectionCount\":" + count
                + ",\"kernelEnabled\":" + BrowserLightKernel.enabled()
                + ",\"disabledReason\":\"" + BrowserLightKernel.disabledReason().replace("\"", "'")
                + "\",\"jobs\":" + BrowserLightKernel.jobs() + ",\"applied\":" + BrowserLightKernel.applied()
                + ",\"fallbacks\":" + BrowserLightKernel.fallbacks() + ",\"rechecks\":" + BrowserLightKernel.rechecks()
                + ",\"columns\":[" + String.join(",", rows) + "]}\n");
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        String profile = arguments[1];
        String sourceHash = arguments[2];
        long seed = Long.parseLong(arguments[3]);
        int minSection = Integer.parseInt(arguments[4]);
        int count = Integer.parseInt(arguments[5]);
        String mode = arguments[6];
        boolean expectKernel = !mode.equals("vanilla");
        Path source = Path.of(Block.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if (!sourceHash.equals(actualHash)) {
            throw new IllegalStateException("unexpected vanilla class source");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LightRoute route = new LightRoute(minSection, count, seed);
        route.light(expectKernel);
        route.write(output, profile, sourceHash, seed, mode);
        LightKernelHost.finish();
        route.pool.shutdownNow();
        System.out.println("LIGHT_ROUTE " + seed + " " + mode + " jobs=" + BrowserLightKernel.jobs() + " applied="
                + BrowserLightKernel.applied() + " fallbacks=" + BrowserLightKernel.fallbacks());
        System.exit(0);
    }
}
