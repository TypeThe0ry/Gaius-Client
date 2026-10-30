package dev.gaius.browser;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.teavm.jso.JSBody;

/** Browser hot paths for immutable Perlin octave metadata. */
public final class BrowserPerlinNoise {
    private BrowserPerlinNoise() {
    }

    public static double[] copyAmplitudes(DoubleList amplitudes) {
        double[] values = new double[amplitudes.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = amplitudes.getDouble(index);
        }
        return values;
    }

    @JSBody(params = "value", script = """
            if (!Number.isFinite(value)) {
              return value;
            }
            const period = 33554432;
            const scaled = value / period + 0.5;
            let rounded;
            if (scaled >= -9007199254740992 && scaled <= 9007199254740992) {
              rounded = Math.floor(scaled);
            } else {
              const maximum = BigInt("9223372036854775807");
              const minimum = BigInt("-9223372036854775808");
              let integral;
              if (scaled >= Number(maximum)) {
                integral = maximum;
              } else if (scaled <= Number(minimum)) {
                integral = minimum;
              } else {
                integral = BigInt(Math.trunc(scaled));
                if (scaled < Number(integral)) {
                  integral = integral - BigInt(1);
                }
              }
              rounded = Number(integral);
            }
            return value - rounded * period;
            """)
    public static native double wrap(double value);
}
