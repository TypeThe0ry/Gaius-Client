package dev.gaius.browser.kernel.mesh;

/**
 * Accessor that {@code dev.gaius.tools.patches.mesh.MeshKernelPatches} adds to
 * {@code BiomeManager} on 26.2 and 26.3: the mesh kernel reproduces the biome zoom
 * ({@code BiomeManager.getBiome}) and needs its private seed.
 */
public interface MeshBiomeAccess {
    long gaius$biomeZoomSeed();
}
