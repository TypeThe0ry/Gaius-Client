package dev.gaius.golden;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.Noise;

/**
 * Batch ("volume") sampling as 26.3 worldgen does it. A volume case records
 * params "volume": {"size": [sx,sy,sz], "min": [x,y,z], "step": [x,y,z]} and
 * outputs the buffer in index order y + sy * (x + sx * z), where the sample at
 * (ix, iy, iz) sits at block (min + i * step) per axis. For Noise.addToVolume
 * the buffer starts at 0.0f and params add "xz_scale", "y_scale" and the float
 * "amplitude" passed to the call.
 */
final class Volumes {
    private static final int[][] SHAPES = {
        // size x, y, z, step x, y, z
        {4, 8, 4, 4, 8, 4},
        {5, 9, 5, 4, 8, 4},
        {3, 5, 3, 1, 1, 1},
        {2, 16, 2, 16, 4, 16},
    };

    private static final double[][] SCALES = {
        // xz scale, y scale, amplitude
        {1.0, 1.0, 1.0}, {0.25, 0.125, 1.0}, {0.5, 0.0, 0.75}, {1.0E-3, 2.0, -2.5},
    };

    private Volumes() {
    }

    static DensityVolume volume(Case c, Random r, int index) {
        int[] shape = SHAPES[index % SHAPES.length];
        int minX = Math.floorDiv(Inputs.block(r), 4) * 4;
        int minY = Math.floorDiv(Inputs.blockY(r), 8) * 8;
        int minZ = Math.floorDiv(Inputs.block(r), 4) * 4;
        DensityVolume volume = new DensityVolume(
                shape[0], shape[1], shape[2], minX, minY, minZ, shape[3], shape[4], shape[5]);
        c.param("volume", describe(volume));
        return volume;
    }

    private static Map<String, Object> describe(DensityVolume v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", List.of(v.sizeX(), v.sizeY(), v.sizeZ()));
        out.put("min", List.of(v.minBlockX(), v.minBlockY(), v.minBlockZ()));
        out.put("step", List.of(v.stepBlockX(), v.stepBlockY(), v.stepBlockZ()));
        return out;
    }

    /** Runs noise.addToVolume on a zeroed buffer and records the scales and the buffer. */
    static Case addToVolume(Case c, Random r, int index, Noise noise) {
        DensityVolume volume = volume(c, r, index);
        double[] scales = SCALES[index % SCALES.length];
        float amplitude = (float) scales[2];
        c.param("xz_scale", scales[0]).param("y_scale", scales[1]).param("amplitude", amplitude);
        DensityBuffer buffer = DensityBuffer.createUnpooled(volume.size());
        noise.addToVolume(buffer, volume, scales[0], scales[1], amplitude);
        return outputs(c, buffer, volume);
    }

    static Case outputs(Case c, DensityBuffer buffer, DensityVolume volume) {
        for (int i = 0; i < volume.size(); i++) {
            c.output(buffer.get(i));
        }
        return c;
    }
}
