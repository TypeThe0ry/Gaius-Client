import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Prints every answer that Block.isShapeFullBlock gives (and the block state cache values built
 * from it during bootstrap) as a digest per category plus the first differing details, so that
 * shape-memo-parity.mjs can compare the vanilla classes with the patched Block and VoxelShape.
 */
public final class ShapeFullBlockParity {
    private ShapeFullBlockParity() {
    }

    private static final class Digest {
        long hash = 1125899906842597L;
        int count;
        int trues;

        void add(boolean value) {
            hash = 31 * hash + (value ? 1231 : 1237);
            count++;
            if (value) {
                trues++;
            }
        }

        @Override
        public String toString() {
            return count + " " + trues + " " + Long.toHexString(hash);
        }
    }

    public static void main(String[] args) {
        long bootStart = System.nanoTime();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long bootMillis = (System.nanoTime() - bootStart) / 1_000_000L;

        List<BlockState> states = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            states.addAll(block.getStateDefinition().getPossibleStates());
        }
        Map<VoxelShape, Boolean> distinct = new IdentityHashMap<>();
        List<VoxelShape> shapes = new ArrayList<>();
        for (BlockState state : states) {
            addShape(shapes, distinct, state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            addShape(shapes, distinct, state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            addShape(shapes, distinct, state.getOcclusionShape());
            addShape(shapes, distinct, state.getBlockSupportShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            for (Direction direction : Direction.values()) {
                addShape(shapes, distinct, state.getFaceOcclusionShape(direction));
                addShape(shapes, distinct,
                        state.getBlockSupportShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).getFaceShape(direction));
            }
        }
        // Shapes that no block uses, made fresh so that no cache has seen them.
        addShape(shapes, distinct, Shapes.empty());
        addShape(shapes, distinct, Shapes.block());
        addShape(shapes, distinct, Shapes.box(0, 0, 0, 1, 1, 1));
        addShape(shapes, distinct, Shapes.box(0, 0, 0, 1, 0.999, 1));
        addShape(shapes, distinct, Shapes.join(Shapes.box(0, 0, 0, 1, 0.5, 1), Shapes.box(0, 0.5, 0, 1, 1, 1),
                BooleanOp.OR));
        addShape(shapes, distinct, Shapes.join(Shapes.box(0, 0, 0, 0.5, 1, 1), Shapes.box(0.5, 0, 0, 1, 1, 1),
                BooleanOp.OR).optimize());
        addShape(shapes, distinct, Shapes.box(0.25, 0, 0.25, 0.75, 1, 0.75));

        Digest stateCache = new Digest();
        for (BlockState state : states) {
            stateCache.add(state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            stateCache.add(state.isSolidRender());
            stateCache.add(state.propagatesSkylightDown());
            for (Direction direction : Direction.values()) {
                for (SupportType type : SupportType.values()) {
                    stateCache.add(state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction, type));
                }
            }
        }
        System.out.println("states " + states.size());
        System.out.println("stateCache " + stateCache);

        // The boxes of every state's outline, collision and occlusion shape, walls separately.
        long shapeHash = 1125899906842597L;
        long wallHash = 1125899906842597L;
        int wallStates = 0;
        Map<VoxelShape, Boolean> wallInstances = new IdentityHashMap<>();
        for (BlockState state : states) {
            String boxes = boxes(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) + "|"
                    + boxes(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) + "|"
                    + boxes(state.getOcclusionShape());
            shapeHash = 31 * shapeHash + boxes.hashCode();
            if (state.getBlock() instanceof WallBlock) {
                wallHash = 31 * wallHash + (BuiltInRegistries.BLOCK.getKey(state.getBlock()) + state.toString()
                        + boxes).hashCode();
                wallStates++;
                wallInstances.put(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Boolean.TRUE);
                wallInstances.put(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Boolean.TRUE);
            }
        }
        System.out.println("stateShapes " + Long.toHexString(shapeHash));
        System.out.println("wallShapes " + wallStates + " " + Long.toHexString(wallHash));
        System.out.println("# wallShapeInstances " + wallInstances.size());
        System.out.println("shapes " + shapes.size());
        System.out.println("# distinctShapes " + distinct.size());

        long lookupStart = System.nanoTime();
        for (int round = 0; round < 3; round++) {
            Digest digest = new Digest();
            for (VoxelShape shape : shapes) {
                digest.add(Block.isShapeFullBlock(shape));
            }
            System.out.println("isShapeFullBlock.round" + round + " " + digest);
        }
        long lookupMicros = (System.nanoTime() - lookupStart) / 1000L;

        // Each answer against the cache-free vanilla expression, shape by shape.
        int mismatches = 0;
        for (VoxelShape shape : shapes) {
            boolean expected = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
            if (Block.isShapeFullBlock(shape) != expected) {
                if (mismatches++ < 5) {
                    System.out.println("mismatch " + shape + " expected " + expected);
                }
            }
        }
        System.out.println("expressionMismatches " + mismatches);
        System.out.println("# bootstrapMillis " + bootMillis + " lookupMicros " + lookupMicros);
    }

    /** The shape's boxes in a canonical order (exact doubles). */
    private static String boxes(VoxelShape shape) {
        List<String> boxes = new ArrayList<>();
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> boxes.add(x0 + "," + y0 + "," + z0 + "," + x1 + "," + y1 + ","
                + z1));
        java.util.Collections.sort(boxes);
        return String.join(";", boxes);
    }

    private static void addShape(List<VoxelShape> shapes, Map<VoxelShape, Boolean> distinct, VoxelShape shape) {
        shapes.add(shape);
        distinct.put(shape, Boolean.TRUE);
    }
}
