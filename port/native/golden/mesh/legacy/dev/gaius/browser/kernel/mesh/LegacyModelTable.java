package dev.gaius.browser.kernel.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class LegacyModelTable {
    private final MeshTableWriter writer;
    private LegacyModelTable(int semantics) { writer = new MeshTableWriter(semantics, 1, Block.BLOCK_STATE_REGISTRY.size()); }
    private int face(VoxelShape shape, int direction) {
        if (shape == Shapes.empty()) return MeshTableWriter.FACE_EMPTY;
        if (shape == Shapes.block()) return MeshTableWriter.FACE_FULL;
        int[] rows = new int[16];
        for (AABB box : shape.toAabbs()) {
            double horizontalStart = direction < 4 ? box.minX : box.minZ;
            double horizontalEnd = direction < 4 ? box.maxX : box.maxZ;
            double verticalStart = direction < 2 ? box.minZ : box.minY;
            double verticalEnd = direction < 2 ? box.maxZ : box.maxY;
            for (double coordinate : new double[]{horizontalStart, horizontalEnd, verticalStart, verticalEnd}) {
                if (coordinate * 16 != Math.rint(coordinate * 16)) throw new IllegalStateException("off-grid synthetic state");
            }
            for (int vertical = (int) (verticalStart * 16); vertical < verticalEnd * 16; vertical++)
                for (int horizontal = (int) (horizontalStart * 16); horizontal < horizontalEnd * 16; horizontal++) rows[vertical] |= 1 << horizontal;
        }
        return writer.mask(rows);
    }
    private void state(BlockState state, boolean selected) throws Exception {
        MeshTableWriter.StateRecord record = new MeshTableWriter.StateRecord();
        if (!selected && !state.isAir()) {
            record.markUnsupported();
            writer.state(Block.getId(state), record);
            return;
        }
        Block block = state.getBlock();
        if (state.isAir()) { record.flags |= MeshTableWriter.FLAG_AIR; writer.airId(Block.getId(state)); }
        if (state.isSolidRender()) record.flags |= MeshTableWriter.FLAG_SOLID_RENDER;
        if (state.getRenderShape() == RenderShape.MODEL) record.flags |= MeshTableWriter.FLAG_RENDER_MODEL;
        if (!state.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || state.getLightBlock() == 0) record.flags |= MeshTableWriter.FLAG_LIGHT_PERMEABLE;
        if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) record.flags |= MeshTableWriter.FLAG_COLLISION_FULL;
        if (state.emissiveRendering(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) record.flags |= MeshTableWriter.FLAG_EMISSIVE;
        if (state.isSolid()) record.flags |= MeshTableWriter.FLAG_SOLID;
        if (block instanceof LeavesBlock) record.flags |= MeshTableWriter.FLAG_LEAVES;
        if (block instanceof HalfTransparentBlock) record.flags |= MeshTableWriter.FLAG_HALF_TRANSPARENT;
        if (state.blocksMotion()) record.flags |= MeshTableWriter.FLAG_BLOCKS_FLUID_FLOW;
        if (block instanceof IceBlock) record.flags |= MeshTableWriter.FLAG_ICE;
        record.blockId = BuiltInRegistries.BLOCK.getId(block);
        record.shade = state.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        record.emission = state.getLightEmission();
        record.skip = block instanceof IronBarsBlock ? MeshTableWriter.SKIP_BARS
            : block instanceof HalfTransparentBlock ? MeshTableWriter.SKIP_SAME_BLOCK
            : block instanceof LeavesBlock ? MeshTableWriter.SKIP_LEAVES
            : block instanceof LiquidBlock ? MeshTableWriter.SKIP_SAME_FLUID : MeshTableWriter.SKIP_NONE;
        for (Direction direction : Direction.values()) {
            record.faces[direction.ordinal()] = face(state.getFaceOcclusionShape(direction), direction.ordinal());
            if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction)) record.sturdy |= 1 << direction.ordinal();
            BooleanProperty property = CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(direction);
            if (property != null && state.hasProperty(property)) {
                record.connPresent |= 1 << direction.ordinal();
                if (state.getValue(property)) record.connTrue |= 1 << direction.ordinal();
            }
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            record.fluidHeight = fluid.getOwnHeight();
            boolean water = fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
            record.fluidGroup = water ? 1 : 2;
            record.fluidModel = writer.fluidModel(water ? 2 : 0, water, water ? MeshTableWriter.TINT_WATER : 255, -1,
                new float[]{0,1,0,1, 0,1,0,1, 0,1,0,1});
            if (fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING)) record.flags |= MeshTableWriter.FLAG_FLUID_FALLING;
        }
        if (selected && (block instanceof GrassBlock || block instanceof LeavesBlock)) {
            record.firstTint = writer.tintRun(new int[]{block instanceof GrassBlock ? MeshTableWriter.TINT_GRASS : MeshTableWriter.TINT_FOLIAGE, 0}, 1);
            record.tintCount = 1;
        }
        if (selected && state.getRenderShape() == RenderShape.MODEL) {
            List<Object> choices = MeshGolden.parts.get(state);
            record.multipart = MeshGolden.multipart.contains(state);
            List<Integer> groups = new ArrayList<>();
            if (!record.multipart) {
                groups.add(choices.size() > 1 ? 1 : 0);
                groups.add(choices.size() * (choices.size() + 1) / 2);
                groups.add(choices.size());
            }
            for (int choice = 0; choice < choices.size(); choice++) {
                Object part = choices.get(choice);
                if (record.multipart) { groups.add(0); groups.add(1); groups.add(1); }
                List<Integer> references = new ArrayList<>();
                int[] ends = new int[7];
                for (int direction = 0; direction < 7; direction++) {
                    @SuppressWarnings("unchecked") List<BakedQuad> quads = (List<BakedQuad>) MeshGolden.call(part, "getQuads", direction == 6 ? null : Direction.values()[direction]);
                    for (BakedQuad quad : quads) {
                        float[] xyz = new float[12]; float[] uv = new float[8];
                        for (int corner = 0; corner < 4; corner++) {
                            org.joml.Vector3fc position = quad.position(corner);
                            xyz[corner * 3] = position.x(); xyz[corner * 3 + 1] = position.y(); xyz[corner * 3 + 2] = position.z();
                            uv[corner * 2] = Float.intBitsToFloat((int) quad.packedUV(corner));
                            uv[corner * 2 + 1] = Float.intBitsToFloat((int) (quad.packedUV(corner) >>> 32));
                        }
                        references.add(writer.quad(writer.geometry(xyz), writer.uv(uv), quad.tintIndex(), quad.direction().ordinal(),
                            quad.shade() ? quad.direction().ordinal() : 1, ItemBlockRenderTypes.getChunkRenderType(state).ordinal(), quad.lightEmission()));
                    }
                    ends[direction] = references.size();
                }
                int partId = writer.part(references.stream().mapToInt(Integer::intValue).toArray(), ends, true);
                groups.add(record.multipart ? 1 : choice + 1); groups.add(partId);
            }
            record.firstGroup = writer.groupRun(groups.stream().mapToInt(Integer::intValue).toArray(), groups.size()); record.groupCount = record.multipart ? choices.size() : 1;
        }
        writer.state(Block.getId(state), record);
    }
    static byte[] encode(Map<BlockState, Object> models, int semantics) throws Exception {
        ItemBlockRenderTypes.setCutoutLeaves(true);
        LegacyModelTable table = new LegacyModelTable(semantics);
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) table.state(state, models.containsKey(state));
        return table.writer.encode();
    }
}
