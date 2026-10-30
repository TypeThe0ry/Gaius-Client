package dev.gaius.browser.kernel.light;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The static light table of the light kernel (port/native/crates/gaius-light, table.rs): per
 * block state id the light dampening, the light emission and a shape class, plus the face shape
 * ids of every class and {@code Shapes.faceShapeOccludes} for every pair of face shapes, all
 * read from vanilla so the kernel never reimplements block geometry.
 *
 * <p>A state is an empty shape, class 0, when {@code LightEngine.isEmptyShape} holds
 * ({@code !canOcclude() || !useShapeForLightOcclusion()}); otherwise its class is the tuple of
 * its six {@code getFaceOcclusionShape} ids. Face shapes are told apart by their boxes.
 *
 * <p>The block state registry is frozen once the game has bootstrapped, so the table is built
 * once (on the server Worker, from the light task queue); {@link #epoch()} hashes its bytes so a
 * kernel instance can keep a decoded copy.
 */
public final class BrowserLightStateTable {
    private static final int MAGIC = 0x42544c47; // "GLTB"
    private static final int VERSION = 1;
    // Props carry the class in 8 bits (table.rs).
    private static final int MAX_CLASSES = 256;
    // Class tuples carry face ids as single bytes (table.rs).
    private static final int MAX_FACES = 256;

    private static byte[] bytes;
    private static long epoch;
    private static int stateCount;

    private BrowserLightStateTable() {
    }

    public static byte[] bytes() {
        if (bytes == null) {
            build();
        }
        return bytes;
    }

    public static long epoch() {
        bytes();
        return epoch;
    }

    public static int stateCount() {
        bytes();
        return stateCount;
    }

    private static void build() {
        IdMapper<BlockState> registry = Block.BLOCK_STATE_REGISTRY;
        int count = registry.size();
        int[] props = new int[count];
        Map<List<AABB>, Integer> faceIds = new HashMap<>();
        List<VoxelShape> faces = new ArrayList<>();
        faceIds.put(Shapes.empty().toAabbs(), 0);
        faces.add(Shapes.empty());
        Map<String, Integer> classIds = new HashMap<>();
        List<int[]> classes = new ArrayList<>();
        classes.add(new int[6]);
        Direction[] directions = Direction.values();
        for (int id = 0; id < count; id++) {
            BlockState state = registry.byId(id);
            if (state == null) {
                continue;
            }
            int dampening = LightVersionOps.INSTANCE.lightDampening(state) & 15;
            int emission = state.getLightEmission() & 15;
            int shapeClass = 0;
            if (state.canOcclude() && state.useShapeForLightOcclusion()) {
                int[] tuple = new int[6];
                for (Direction direction : directions) {
                    VoxelShape face = state.getFaceOcclusionShape(direction);
                    List<AABB> key = face.toAabbs();
                    Integer faceId = faceIds.get(key);
                    if (faceId == null) {
                        faceId = faces.size();
                        faceIds.put(key, faceId);
                        faces.add(face);
                    }
                    tuple[direction.ordinal()] = faceId;
                }
                String key = Arrays.toString(tuple);
                Integer known = classIds.get(key);
                if (known == null) {
                    // Class 0 is reserved for empty shapes, even when every face is empty.
                    known = classes.size();
                    classIds.put(key, known);
                    classes.add(tuple);
                }
                shapeClass = known;
            }
            props[id] = dampening | emission << 4 | shapeClass << 8;
        }
        if (classes.size() > MAX_CLASSES || faces.size() > MAX_FACES) {
            throw new IllegalStateException("light table has " + classes.size() + " shape classes and "
                    + faces.size() + " face shapes; the kernel takes at most " + MAX_CLASSES + " and "
                    + MAX_FACES);
        }

        int runs = 0;
        for (int id = 0; id < count; id++) {
            if (id == 0 || props[id] != props[id - 1]) {
                runs++;
            }
        }
        int faceCount = faces.size();
        int matrixBytes = (faceCount * faceCount + 7) / 8;
        // Run-length coded props, or one u16 per state when that is smaller (waterlogged and
        // similar property pairs make the runs short).
        int rawBytes = (count * 2 + 3) & ~3;
        boolean raw = runs * 8 > rawBytes;
        byte[] out = new byte[24 + (raw ? rawBytes : runs * 8) + classes.size() * 6 + matrixBytes];
        int at = 0;
        at = putInt(out, at, MAGIC);
        at = putInt(out, at, VERSION);
        at = putInt(out, at, count);
        at = putInt(out, at, raw ? 0 : runs);
        at = putInt(out, at, classes.size());
        at = putInt(out, at, faceCount);
        if (raw) {
            for (int id = 0; id < count; id++) {
                out[at + 2 * id] = (byte) props[id];
                out[at + 2 * id + 1] = (byte) (props[id] >>> 8);
            }
            at += rawBytes;
        } else {
            int runStart = 0;
            for (int id = 1; id <= count; id++) {
                if (id == count || props[id] != props[runStart]) {
                    at = putInt(out, at, id - runStart);
                    at = putInt(out, at, props[runStart]);
                    runStart = id;
                }
            }
        }
        for (int[] tuple : classes) {
            for (int face : tuple) {
                out[at++] = (byte) face;
            }
        }
        for (int a = 0; a < faceCount; a++) {
            for (int b = 0; b < faceCount; b++) {
                if (Shapes.faceShapeOccludes(faces.get(a), faces.get(b))) {
                    int bit = a * faceCount + b;
                    out[at + (bit >>> 3)] |= (byte) (1 << (bit & 7));
                }
            }
        }
        long hash = 0xcbf29ce484222325L;
        for (byte value : out) {
            hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
        }
        bytes = out;
        epoch = hash;
        stateCount = count;
    }

    private static int putInt(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
        out[at + 2] = (byte) (value >>> 16);
        out[at + 3] = (byte) (value >>> 24);
        return at + 4;
    }
}
