package dev.gaius.browser;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Keeps the vanilla NoiseStack layer order while giving TeaVM monomorphic calls for the
 * implementations used by the 26.3 registry. Unknown Noise implementations retain the vanilla
 * interface dispatch as a compatibility fallback.
 */
public final class BrowserNoiseStack {
    private BrowserNoiseStack() {
    }

    public static void addToVolume(
            Noise noise,
            DensityBuffer buffer,
            DensityVolume volume,
            double xScale,
            double zScale,
            float amplitude) {
        if (noise instanceof SmearedPerlinNoise smeared) {
            smeared.addToVolume(buffer, volume, xScale, zScale, amplitude);
        } else if (noise instanceof PerlinNoise perlin) {
            perlin.addToVolume(buffer, volume, xScale, zScale, amplitude);
        } else if (noise instanceof NoiseStack stack) {
            stack.addToVolume(buffer, volume, xScale, zScale, amplitude);
        } else {
            noise.addToVolume(buffer, volume, xScale, zScale, amplitude);
        }
    }
}
