package dev.gaius.browser.kernel.worldgen;

/**
 * Added to the 26.2 {@code StructureManager} by {@code dev.gaius.tools.patches.worldgen.WorldgenKernelPatches}: the
 * world seed (its {@code worldOptions.seed()}), which the 26.2 RandomState does not keep but the
 * generator export needs.
 */
public interface WorldgenSeedSource262 {
    long gaius$worldSeed();
}
