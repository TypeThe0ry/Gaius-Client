package dev.gaius.browser;

import java.util.IdentityHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Memoized face-sturdiness for {@code BlockBehaviour.BlockStateBase.Cache}, patched in by
 * {@code MinecraftClientPatcher.patchBlockCacheSupportMemo}.
 *
 * <p>The cache asks every block state, for all six faces and all three {@link SupportType}s,
 * whether the face supports something. Each vanilla answer depends only on the state's block
 * support shape (queried with the empty level at the origin): FULL tests whether the face shape is
 * full, CENTER and RIGID join the face shape with a fixed shape. Shapes are immutable and most
 * states share a few shape instances (full cube, slabs, stairs), so the answers are memoized per
 * shape instance, and each state's support shape is computed once instead of eighteen times. This
 * removed about three seconds of voxel joins from client and integrated-server startup.
 */
public final class BrowserBlockSupportMemo {
    private static final int DIRECTIONS = Direction.values().length;
    private static final int TYPES = SupportType.values().length;
    private static final IdentityHashMap<VoxelShape, byte[]> ANSWERS = new IdentityHashMap<>();

    private static BlockState lastState;
    private static VoxelShape lastShape;

    private BrowserBlockSupportMemo() {
    }

    public static boolean isSupporting(SupportType type, BlockState state, BlockGetter level, BlockPos pos,
            Direction direction) {
        VoxelShape shape;
        if (state == lastState) {
            shape = lastShape;
        } else {
            shape = state.getBlockSupportShape(level, pos);
            lastState = state;
            lastShape = shape;
        }
        if (shape == null) {
            return type.isSupporting(state, level, pos, direction);
        }
        byte[] answers = ANSWERS.get(shape);
        if (answers == null) {
            answers = new byte[DIRECTIONS * TYPES];
            ANSWERS.put(shape, answers);
        }
        int index = direction.ordinal() * TYPES + type.ordinal();
        byte known = answers[index];
        if (known != 0) {
            return known == 2;
        }
        boolean supporting = type.isSupporting(state, level, pos, direction);
        answers[index] = supporting ? (byte) 2 : (byte) 1;
        return supporting;
    }
}
