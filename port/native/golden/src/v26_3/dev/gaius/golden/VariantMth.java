package dev.gaius.golden;

import java.lang.reflect.Method;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.GradientNoise;

/** Mth functions with the 26.3 signatures: float smoothstep/lerp2/lerp3, GradientNoise.wrap. */
final class VariantMth {
    private VariantMth() {
    }

    static void write(FixtureSink sink, Random r) throws ReflectiveOperationException {
        sink.write(MthFixtures.rowsF("smoothstep", r, 1, a -> Mth.smoothstep(a[0])));
        sink.write(MthFixtures.rowsF("lerp2", r, 6, a -> Mth.lerp2(a[0], a[1], a[2], a[3], a[4], a[5])));
        sink.write(MthFixtures.rowsF("lerp3", r, 11,
                a -> Mth.lerp3(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10])));

        Method wrapMethod = GradientNoise.class.getDeclaredMethod("wrap", double.class);
        wrapMethod.setAccessible(true);
        Case wrap = MthFixtures.fn("wrap", "f64");
        for (int i = 0; i < MthFixtures.ROWS; i++) {
            double x = Inputs.coord(r) * (r.nextBoolean() ? 1.0 : 128.0);
            wrap.input(x).output((Double) wrapMethod.invoke(null, x));
        }
        sink.write(wrap);
    }
}
