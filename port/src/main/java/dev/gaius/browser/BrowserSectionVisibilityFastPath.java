package dev.gaius.browser;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.teavm.jso.JSBody;

/** Opt-in section-visibility hot paths for the browser renderer. */
public final class BrowserSectionVisibilityFastPath {
    private static final int MAX_FACE_CACHE_ENTRIES = 4096;
    private static final SectionMesh[] FACE_KEYS = new SectionMesh[MAX_FACE_CACHE_ENTRIES];
    private static final long[] FACE_SEEN = new long[MAX_FACE_CACHE_ENTRIES];
    private static final long[] FACE_RESULTS = new long[MAX_FACE_CACHE_ENTRIES];
    private static final int RECENT_FACE_SLOTS = 8;
    private static final SectionMesh[] RECENT_FACE_KEYS = new SectionMesh[RECENT_FACE_SLOTS];
    private static final long[] RECENT_FACE_SEEN = new long[RECENT_FACE_SLOTS];
    private static final long[] RECENT_FACE_RESULTS = new long[RECENT_FACE_SLOTS];
    private static int recentFaceCursor;
    // The graph walks adjacent directions on the same compiled mesh repeatedly. Keep the
    // most recent entry out of the identity-hash probe; this is only a memoization shortcut.
    private static SectionMesh lastFaceMesh;
    private static long lastFaceSeen;
    private static long lastFaceResults;
    private static final boolean SOD_CULL_ENABLED = enabled();
    private static final boolean FACE_CACHE_ENABLED = faceCacheEnabled();
    private static Object lastGraph;
    private static int lastSectionX;
    private static int lastSectionY;
    private static int lastSectionZ;
    private static int lastFov;
    private static boolean lastSmartCull;
    private static boolean cameraStateInitialized;

    private BrowserSectionVisibilityFastPath() {
    }

    /** Keep the previous graph while the camera remains inside one section. */
    public static boolean skipGraphInvalidation(
            SectionOcclusionGraph graph, CameraRenderState camera, int fov) {
        if (!SOD_CULL_ENABLED || graph == null || camera == null || camera.pos == null) {
            return false;
        }
        int sectionX = SectionPos.blockToSectionCoord(camera.pos.x);
        int sectionY = SectionPos.blockToSectionCoord(camera.pos.y);
        int sectionZ = SectionPos.blockToSectionCoord(camera.pos.z);
        boolean smartCull = camera.smartCull;
        if (graph != lastGraph) {
            lastGraph = graph;
            cameraStateInitialized = false;
        }
        boolean sameSection = cameraStateInitialized
                && sectionX == lastSectionX
                && sectionY == lastSectionY
                && sectionZ == lastSectionZ
                && fov == lastFov
                && smartCull == lastSmartCull;
        lastSectionX = sectionX;
        lastSectionY = sectionY;
        lastSectionZ = sectionZ;
        lastFov = fov;
        lastSmartCull = smartCull;
        cameraStateInitialized = true;
        return sameSection;
    }

    /** Memoize the immutable 6x6 face relation of a compiled section mesh. */
    public static boolean facesCanSeeEachOther(
            SectionMesh mesh, Direction from, Direction to) {
        if (mesh == null) {
            return false;
        }
        // Face connectivity is immutable for a compiled mesh, so memoization is vanilla-exact.
        // Graph invalidation remains separately opt-in because it depends on camera orientation.
        if (!FACE_CACHE_ENABLED || from == null || to == null) {
            return mesh.facesCanSeeEachother(from, to);
        }
        int index = from.ordinal() * 6 + to.ordinal();
        long bit = 1L << index;
        if (mesh == lastFaceMesh && (lastFaceSeen & bit) != 0L) {
            return (lastFaceResults & bit) != 0L;
        }
        for (int i = 0; i < RECENT_FACE_SLOTS; i++) {
            if (RECENT_FACE_KEYS[i] == mesh) {
                long seen = RECENT_FACE_SEEN[i];
                if ((seen & bit) != 0L) {
                    lastFaceMesh = mesh;
                    lastFaceSeen = seen;
                    lastFaceResults = RECENT_FACE_RESULTS[i];
                    return (RECENT_FACE_RESULTS[i] & bit) != 0L;
                }
                break;
            }
        }
        int slot = System.identityHashCode(mesh) & (MAX_FACE_CACHE_ENTRIES - 1);
        int start = slot;
        while (true) {
            SectionMesh key = FACE_KEYS[slot];
            if (key == mesh) {
                if ((FACE_SEEN[slot] & bit) != 0L) {
                    lastFaceMesh = mesh;
                    lastFaceSeen = FACE_SEEN[slot];
                    lastFaceResults = FACE_RESULTS[slot];
                    return (FACE_RESULTS[slot] & bit) != 0L;
                }
                break;
            }
            if (key == null) {
                FACE_KEYS[slot] = mesh;
                break;
            }
            slot = (slot + 1) & (MAX_FACE_CACHE_ENTRIES - 1);
            if (slot == start) {
                // Evict one entry instead of clearing all 4096 slots. The cache is only a
                // memoization layer; replacing this slot cannot change the vanilla result.
                if (lastFaceMesh == FACE_KEYS[start]) {
                    lastFaceMesh = null;
                    lastFaceSeen = 0L;
                    lastFaceResults = 0L;
                }
                FACE_KEYS[start] = mesh;
                FACE_SEEN[start] = 0L;
                FACE_RESULTS[start] = 0L;
                slot = start;
                break;
            }
        }
        boolean result = mesh.facesCanSeeEachother(from, to);
        FACE_SEEN[slot] |= bit;
        if (result) {
            FACE_RESULTS[slot] |= bit;
        }
        int recent = -1;
        for (int i = 0; i < RECENT_FACE_SLOTS; i++) {
            if (RECENT_FACE_KEYS[i] == mesh) {
                recent = i;
                break;
            }
        }
        if (recent < 0) {
            recent = recentFaceCursor++ & (RECENT_FACE_SLOTS - 1);
            RECENT_FACE_KEYS[recent] = mesh;
            RECENT_FACE_SEEN[recent] = 0L;
            RECENT_FACE_RESULTS[recent] = 0L;
        }
        RECENT_FACE_SEEN[recent] |= bit;
        if (result) {
            RECENT_FACE_RESULTS[recent] |= bit;
        }
        lastFaceMesh = mesh;
        lastFaceSeen = FACE_SEEN[slot];
        lastFaceResults = FACE_RESULTS[slot];
        return result;
    }

    @JSBody(script = """
            try {
              const value = new URLSearchParams(String(location.search || ''))
                .get('gaiusSodCull');
              return value === '1' || value === 'true';
            } catch (ignored) {
              return false;
            }
            """)
    private static native boolean enabled();

    @JSBody(script = """
            try {
              const value = new URLSearchParams(String(location.search || ''))
                .get('gaiusSodCull');
              return value !== '0' && value !== 'false';
            } catch (ignored) {
              return true;
            }
            """)
    private static native boolean faceCacheEnabled();
}
