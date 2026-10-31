package dev.gaius.browser.render;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.teavm.jso.JSBody;

/**
 * Compile gate for a dirty terrain section (SectionUpdateTracker.hasAllNeighbors on 26.2 and
 * 26.3, patched by MinecraftClientPatcher.patchSectionNeighborReadiness).
 *
 * <p>Vanilla waits until all eight horizontal neighbor chunks are loaded and lit. Browser
 * delivery is sparse, so v0.2 compiled as soon as the center chunk was ready; every later
 * neighbor then made vanilla re-dirty the column's sections, compiling a streaming section up
 * to nine times. This gate compiles a section when</p>
 * <ul>
 *   <li>all neighbors are ready, or</li>
 *   <li>its column is within {@value #NEARBY_CHUNKS} chunks of the player (holes next to the
 *       player are never acceptable), or</li>
 *   <li>a neighbor of its column has been missing for {@value #MISSING_GRACE_MILLIS} ms
 *       (sparse delivery still gets a provisional mesh, rebuilt when the neighbor arrives).</li>
 * </ul>
 *
 * <p>A waiting column's uncompiled sections are checked on every extracted frame while they
 * are visible. A wait that was not checked for {@value #STALE_WAIT_MILLIS} ms belongs to a
 * column that left view or was unloaded, so its next check starts a new wait instead of
 * granting at once.</p>
 *
 * <p>Kernel snapshots may be provisional too. Block, light and neighbor publication invalidates
 * the snapshot revision and dirties the section, so an early mesh is replaced when the complete
 * neighborhood arrives. Requiring every neighbor before task creation can strand visible edge
 * sections dirty forever when their adjacent chunks are outside the server's tracking view.</p>
 *
 * <p>URL {@code gaiusNeighborGate=provisional} restores the v0.2 center-only gate,
 * {@code gaiusNeighborGate=vanilla} waits for all neighbors like the desktop game.</p>
 */
public final class BrowserNeighborReadiness {
    static final long MISSING_GRACE_MILLIS = 300L;
    static final int NEARBY_CHUNKS = 2;
    static final long STALE_WAIT_MILLIS = 2000L;
    private static final int MODE_VANILLA = 0;
    private static final int MODE_GATED = 1;
    private static final int MODE_PROVISIONAL = 2;
    private static final int MAX_TRACKED_COLUMNS = 65536;

    private static final Long2LongOpenHashMap FIRST_MISSING = new Long2LongOpenHashMap();
    /** Last check of each waiting column; a missing key reads 0, which is always stale. */
    private static final Long2LongOpenHashMap LAST_CHECKED = new Long2LongOpenHashMap();
    private static int mode = -1;
    private static boolean cameraKnown;
    private static int cameraChunkX;
    private static int cameraChunkZ;
    private static long nearbyGrants;
    private static long graceGrants;
    private static long waits;

    static {
        FIRST_MISSING.defaultReturnValue(Long.MIN_VALUE);
    }

    private BrowserNeighborReadiness() {
    }

    /**
     * The gate. {@code centerReady} is vanilla's readiness of the section's own chunk,
     * {@code allNeighbors} the vanilla eight-neighbor check, {@code sectionNode} the packed
     * SectionPos.
     */
    public static boolean ready(boolean centerReady, boolean allNeighbors, long sectionNode) {
        int chunkX = (int) (sectionNode >> 42);
        int chunkZ = (int) (sectionNode << 22 >> 42);
        long column = column(chunkX, chunkZ);
        if (!centerReady) {
            return false;
        }
        if (allNeighbors) {
            if (!FIRST_MISSING.isEmpty()) {
                FIRST_MISSING.remove(column);
                LAST_CHECKED.remove(column);
            }
            return true;
        }
        // Published updates invalidate any snapshot that crossed the streaming frontier. This
        // keeps provisional compilation safe while allowing visible edge sections to progress.
        int gateMode = mode();
        if (gateMode == MODE_PROVISIONAL) {
            return true;
        }
        if (gateMode == MODE_VANILLA) {
            return false;
        }
        if (cameraKnown
                && Math.abs(chunkX - cameraChunkX) <= NEARBY_CHUNKS
                && Math.abs(chunkZ - cameraChunkZ) <= NEARBY_CHUNKS) {
            nearbyGrants++;
            return true;
        }
        long now = System.currentTimeMillis();
        long first = FIRST_MISSING.get(column);
        if (first == Long.MIN_VALUE || now - LAST_CHECKED.get(column) > STALE_WAIT_MILLIS) {
            if (first == Long.MIN_VALUE && FIRST_MISSING.size() >= MAX_TRACKED_COLUMNS) {
                FIRST_MISSING.clear();
                LAST_CHECKED.clear();
            }
            FIRST_MISSING.put(column, now);
            LAST_CHECKED.put(column, now);
            waits++;
            return false;
        }
        // Kept after a grace grant: the column's other sections must not start a new wait.
        LAST_CHECKED.put(column, now);
        if (now - first >= MISSING_GRACE_MILLIS) {
            graceGrants++;
            return true;
        }
        waits++;
        return false;
    }

    /** Player chunk, refreshed once per extracted frame. */
    public static void setCamera(int chunkX, int chunkZ) {
        cameraKnown = true;
        cameraChunkX = chunkX;
        cameraChunkZ = chunkZ;
    }

    /** Forgets all waiting columns (level change). */
    public static void clear() {
        FIRST_MISSING.clear();
        LAST_CHECKED.clear();
        cameraKnown = false;
    }

    public static int waitingColumns() {
        return FIRST_MISSING.size();
    }

    public static long nearbyGrants() {
        return nearbyGrants;
    }

    public static long graceGrants() {
        return graceGrants;
    }

    public static long waits() {
        return waits;
    }

    private static long column(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int mode() {
        if (mode < 0) {
            mode = configuredMode();
            if (mode < MODE_VANILLA || mode > MODE_PROVISIONAL) {
                mode = MODE_GATED;
            }
        }
        return mode;
    }

    @JSBody(script = """
            try {
              const value=String(new URLSearchParams(String(location.search || ''))
                .get('gaiusNeighborGate') || '').toLowerCase();
              if (value==='vanilla') return 0;
              if (value==='provisional') return 2;
            } catch (ignored) {}
            return 1;
            """)
    private static native int configuredMode();
}
