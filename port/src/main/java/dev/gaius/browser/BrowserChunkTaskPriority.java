package dev.gaius.browser;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.teavm.jso.JSBody;

/** Selects the most useful equal-priority generation task for the local browser player. */
public final class BrowserChunkTaskPriority {
    private static final int MAX_CANDIDATES_TO_SCAN = 512;
    private static final long DISTANCE_WEIGHT = 4096L;
    private static final long DIRECTION_WEIGHT = 64L;

    private static boolean playerPositionKnown;
    private static int playerChunkX;
    private static int playerChunkZ;
    private static int movementX;
    private static int movementZ;

    private BrowserChunkTaskPriority() {
    }

    public static void recordPlayerPosition(double x, double z) {
        int nextChunkX = floorToChunk(x);
        int nextChunkZ = floorToChunk(z);
        if (playerPositionKnown
                && nextChunkX == playerChunkX
                && nextChunkZ == playerChunkZ) {
            return;
        }
        if (playerPositionKnown) {
            movementX = Integer.compare(nextChunkX, playerChunkX);
            movementZ = Integer.compare(nextChunkZ, playerChunkZ);
        }
        playerChunkX = nextChunkX;
        playerChunkZ = nextChunkZ;
        playerPositionKnown = true;
        recordPlayerChunk(nextChunkX, nextChunkZ, movementX, movementZ);
    }

    /**
     * Vanilla uses FIFO order for chunks with the same ticket level. That is inexpensive when
     * generation runs on a pool, but a browser Worker executes those tasks serially. Re-evaluate
     * only the current priority bucket so the player's chunk and nearby forward chunks do not sit
     * behind stale work from the previous tracking center.
     */
    public static long chooseNext(Long2ObjectLinkedOpenHashMap<?> queuedChunks) {
        long first = queuedChunks.firstLongKey();
        if (!playerPositionKnown || queuedChunks.size() < 2) {
            return first;
        }

        long selected = first;
        long selectedScore = score(first);
        int scanned = 0;
        LongIterator iterator = queuedChunks.keySet().iterator();
        while (iterator.hasNext() && scanned < MAX_CANDIDATES_TO_SCAN) {
            long candidate = iterator.nextLong();
            long candidateScore = score(candidate);
            if (candidateScore < selectedScore) {
                selected = candidate;
                selectedScore = candidateScore;
            }
            scanned++;
        }
        long selectedDistanceSquared = distanceSquared(selected);
        recordSelection(
                selected != first,
                scanned,
                (int) Math.min(Integer.MAX_VALUE, selectedDistanceSquared));
        return selected;
    }

    private static long score(long chunkKey) {
        int x = (int) chunkKey;
        int z = (int) (chunkKey >>> 32);
        long offsetX = (long) x - playerChunkX;
        long offsetZ = (long) z - playerChunkZ;
        long distanceSquared = offsetX * offsetX + offsetZ * offsetZ;
        long forward = offsetX * movementX + offsetZ * movementZ;
        return distanceSquared * DISTANCE_WEIGHT - forward * DIRECTION_WEIGHT;
    }

    private static long distanceSquared(long chunkKey) {
        long offsetX = (long) (int) chunkKey - playerChunkX;
        long offsetZ = (long) (int) (chunkKey >>> 32) - playerChunkZ;
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    private static int floorToChunk(double coordinate) {
        int block = (int) Math.floor(coordinate);
        return block >> 4;
    }

    @JSBody(params = {"x", "z", "dx", "dz"}, script = """
            const stats = globalThis.__gaiusChunkPriorityStats ||
              (globalThis.__gaiusChunkPriorityStats = {
                playerUpdates: 0,
                pops: 0,
                reorderedPops: 0,
                scannedCandidates: 0,
                maxCandidates: 0
              });
            stats.playerUpdates++;
            stats.playerChunk = x + ',' + z;
            stats.direction = dx + ',' + dz;
            """)
    private static native void recordPlayerChunk(int x, int z, int dx, int dz);

    @JSBody(params = {"reordered", "scanned", "distanceSquared"}, script = """
            const stats = globalThis.__gaiusChunkPriorityStats ||
              (globalThis.__gaiusChunkPriorityStats = {
                playerUpdates: 0,
                pops: 0,
                reorderedPops: 0,
                scannedCandidates: 0,
                maxCandidates: 0
              });
            stats.pops++;
            stats.scannedCandidates += scanned;
            stats.maxCandidates = Math.max(stats.maxCandidates, scanned);
            if (reordered) stats.reorderedPops++;
            stats.lastSelectedDistanceSquared = Number(distanceSquared);
            """)
    private static native void recordSelection(
            boolean reordered,
            int scanned,
            int distanceSquared);
}
