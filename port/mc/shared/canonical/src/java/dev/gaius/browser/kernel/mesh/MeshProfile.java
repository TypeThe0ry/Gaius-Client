package dev.gaius.browser.kernel.mesh;

import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block and quad predicates selected by the profile's render seam generation.
 */
final class MeshProfile {
    static final int PROFILE = __GAIUS_MESH_PROFILE__;

    private MeshProfile() {
    }

    /** The AO corner test of BlockModelLighter. */
    static boolean lightPermeable(BlockState state) {
        return MeshVersionOps.INSTANCE.lightPermeable(state);
    }

    /** FlowingFluid's flow blocker. */
    static boolean blocksFluidFlow(BlockState state) {
        return MeshVersionOps.INSTANCE.blocksFluidFlow(state);
    }

    /** The CardinalLighting face of a quad. */
    static int shadeFace(BakedQuad quad) {
        return MeshVersionOps.INSTANCE.shadeFace(quad);
    }
}
