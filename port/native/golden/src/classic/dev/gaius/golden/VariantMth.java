package dev.gaius.golden;

import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/** Mth functions with the 1.21.11 / 26.2 signatures: double smoothstep, PerlinNoise.wrap. */
final class VariantMth {
    private VariantMth() {
    }

    static void write(FixtureSink sink, Random r) throws ReflectiveOperationException {
        sink.write(MthFixtures.rows("smoothstep", "f64", r, 1, a -> Mth.smoothstep(a[0])));
        Case wrap = MthFixtures.fn("wrap", "f64");
        for (int i = 0; i < MthFixtures.ROWS; i++) {
            double x = Inputs.coord(r) * (r.nextBoolean() ? 1.0 : 128.0);
            wrap.input(x).output(PerlinNoise.wrap(x));
        }
        sink.write(wrap);
    }
}
