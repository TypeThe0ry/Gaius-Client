package net.minecraft.world.level.biome;

/**
 * Read access to the children of a vanilla {@code Climate.RTree.SubTree}. The WasmGC patcher
 * ({@code WorldgenPatches.patchClimateRTreeBrowserFlatIndex}) makes {@code SubTree} implement
 * this interface so {@link GaiusClimateIndex} can copy the tree once; nothing else uses it.
 */
public interface GaiusClimateSubTree {
    /** The vanilla {@code children} array, in vanilla order. Callers must not modify it. */
    Climate.RTree.Node<?>[] gaius$children();
}
