package dev.gaius.browser;

import java.util.HashMap;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Shared wall shapes for {@code WallBlock.makeShapes}, patched in by
 * {@code MinecraftClientPatcher.patchWallBlockShapeMemo}.
 *
 * <p>Every wall block builds its outline and collision shapes in its constructor: for each of its
 * states, the post shape (if {@code up}) joined with a low or tall side shape per direction. The
 * shape of a state depends only on the two heights given to makeShapes and on the state's
 * {@code up} and four side values, so every wall block computes the same 162 joins for both of its
 * height pairs.
 * The vanilla function still computes the first shape for every (heights, up, sides) key; later
 * walls get that shape, which equals what their own join would give. Only the instance is shared
 * between walls, as it already is between the waterlogged and dry state of one wall.
 */
public final class BrowserWallShapes {
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final HashMap<Long, HashMap<Integer, VoxelShape>> SHAPES = new HashMap<>();

    private BrowserWallShapes() {
    }

    public static Function<BlockState, VoxelShape> memo(Function<BlockState, VoxelShape> vanilla, float postHeight,
            float sideHeight) {
        long heights = ((long) Float.floatToIntBits(postHeight) << 32) | (Float.floatToIntBits(sideHeight) & 0xFFFFFFFFL);
        HashMap<Integer, VoxelShape> shapes = SHAPES.computeIfAbsent(heights, ignored -> new HashMap<>());
        return state -> {
            Integer key = key(state);
            VoxelShape shape = shapes.get(key);
            if (shape == null) {
                shape = vanilla.apply(state);
                shapes.put(key, shape);
            }
            return shape;
        };
    }

    /** {@code up} and the four side values, the only state values the vanilla function reads. */
    static int key(BlockState state) {
        int key = state.getValue(WallBlock.UP) ? 1 : 0;
        for (Direction side : SIDES) {
            key = key << 3 | state.getValue(WallBlock.PROPERTY_BY_DIRECTION.get(side)).ordinal();
        }
        return key;
    }
}
