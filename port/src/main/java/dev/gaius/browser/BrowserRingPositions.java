package dev.gaius.browser;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/**
 * The positions of one concentric-ring structure placement (vanilla: strongholds), searched
 * lazily on both TeaVM targets (MinecraftClientPatcher.patchChunkGeneratorStructureStateBrowserLazyRings).
 *
 * <p>Vanilla {@code ChunkGeneratorStructureState.generateRingPositions} draws the start chunk of
 * every ring position from one RandomSource, forks a RandomSource for it and submits its biome
 * search to the background executor: a lambda that runs {@code BiomeSource.findBiomeHorizontal}
 * over the square of {@code blockRadius} blocks around the middle of the start chunk and returns
 * the chunk of the block it picked (the start chunk when no biome matches). The first generated
 * chunk waits for every search (ConcentricRingsStructurePlacement.isPlacementChunk joins the
 * list), which is the whole stronghold search before the world shows its first chunk.
 *
 * <p>The patch keeps that loop, its random draws and the lambdas with their forked sources; it
 * hands each search to {@link #defer} instead of the executor and the list to {@link #sequence}
 * instead of {@code Util.sequence}. A search runs once, when a caller needs its result:
 * {@link #contains} (isPlacementChunk, once per generated chunk) runs only the searches whose
 * start lies within {@link #reach} chunks of the queried chunk, since a search cannot leave its
 * square; {@link #get} and iteration (ChunkGenerator.findNearestMapStructure: ender eyes,
 * /locate) run the searches they reach. Every search is vanilla's lambda with vanilla's inputs
 * and nothing else draws from its forked source, so every position and every answer equals
 * vanilla's; only the time of the work moves (port/scripts/structure-rings-parity.mjs).
 *
 * <p>One source for both TeaVM targets: it uses only java.util, java.util.concurrent and
 * java.util.function types of the TeaVM class library. The monitor on {@link #get} keeps two
 * threads (JSPI activations on WebAssembly GC) from running one search twice, since its forked
 * RandomSource is stateful; vanilla's search has no suspension point, so the monitor is not
 * contended on the green threads of the JavaScript build.
 */
public final class BrowserRingPositions extends AbstractList<ChunkPos> implements RandomAccess {
    private final Supplier<ChunkPos>[] searches;
    private final int[] startX;
    private final int[] startZ;
    private final ChunkPos[] positions;
    /** Chunks a result can lie from its start: the search square reaches blockRadius blocks from the chunk middle. */
    private final int reach;
    /** Set if a result ever left its reach (not possible for vanilla's search): contains then reads every position. */
    private volatile boolean exhaustive;

    @SuppressWarnings("unchecked")
    private BrowserRingPositions(List<?> deferred, int blockRadius) {
        int count = deferred.size();
        searches = new Supplier[count];
        startX = new int[count];
        startZ = new int[count];
        positions = new ChunkPos[count];
        reach = (blockRadius + 8 + 15) >> 4;
        for (int index = 0; index < count; index++) {
            Deferred search = (Deferred) deferred.get(index);
            searches[index] = search.search;
            startX[index] = search.startChunkX;
            startZ[index] = search.startChunkZ;
        }
    }

    /** One ring position's search and start chunk, in place of {@code CompletableFuture.supplyAsync(search, executor)}. */
    public static Object defer(Supplier<ChunkPos> search, int startChunkX, int startChunkZ) {
        return new Deferred(search, startChunkX, startChunkZ);
    }

    /** The positions of the deferred searches, in place of {@code Util.sequence(futures)}. */
    public static CompletableFuture<List<ChunkPos>> sequence(List<?> deferred, int blockRadius) {
        return CompletableFuture.completedFuture(new BrowserRingPositions(deferred, blockRadius));
    }

    @Override
    public int size() {
        return positions.length;
    }

    @Override
    public synchronized ChunkPos get(int index) {
        ChunkPos position = positions[index];
        if (position == null) {
            position = searches[index].get();
            searches[index] = null;
            positions[index] = position;
            if (Math.abs(chunkX(position) - startX[index]) > reach
                    || Math.abs(chunkZ(position) - startZ[index]) > reach) {
                exhaustive = true;
            }
        }
        return position;
    }

    @Override
    public boolean contains(Object value) {
        if (!(value instanceof ChunkPos query) || exhaustive) {
            return super.contains(value);
        }
        int x = chunkX(query);
        int z = chunkZ(query);
        for (int index = 0; index < positions.length; index++) {
            if (Math.abs(startX[index] - x) <= reach && Math.abs(startZ[index] - z) <= reach
                    && query.equals(get(index))) {
                return true;
            }
        }
        return exhaustive && super.contains(value);
    }

    private static int chunkX(ChunkPos position) {
        return position.getMinBlockX() >> 4;
    }

    private static int chunkZ(ChunkPos position) {
        return position.getMinBlockZ() >> 4;
    }

    private static final class Deferred {
        final Supplier<ChunkPos> search;
        final int startChunkX;
        final int startChunkZ;

        Deferred(Supplier<ChunkPos> search, int startChunkX, int startChunkZ) {
            this.search = search;
            this.startChunkX = startChunkX;
            this.startChunkZ = startChunkZ;
        }
    }
}
