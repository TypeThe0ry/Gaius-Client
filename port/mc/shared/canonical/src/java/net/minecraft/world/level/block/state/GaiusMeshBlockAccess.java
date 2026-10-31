package net.minecraft.world.level.block.state;

/**
 * Protected block offset limits for the mesh kernel's model table exporter
 * ({@code dev.gaius.browser.kernel.mesh.ModelTableExporter}). Lives in the vanilla package
 * because {@code getMaxHorizontalOffset} and {@code getMaxVerticalOffset} are protected members
 * of {@link BlockBehaviour}. Identical copies exist for 26.2 and 26.3.
 */
public final class GaiusMeshBlockAccess {
    private GaiusMeshBlockAccess() {
    }

    public static float maxHorizontalOffset(BlockBehaviour block) {
        return block.getMaxHorizontalOffset();
    }

    public static float maxVerticalOffset(BlockBehaviour block) {
        return block.getMaxVerticalOffset();
    }
}
