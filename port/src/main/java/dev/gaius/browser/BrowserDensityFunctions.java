package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Non-suspending scalar transforms used by browser density-function hot loops. */
public final class BrowserDensityFunctions {
    private BrowserDensityFunctions() {
    }

    @JSBody(params = {"value", "minimum", "maximum"}, script = """
            return Math.min(Math.max(value, minimum), maximum);
            """)
    public static native double clamp(double value, double minimum, double maximum);

    @JSBody(params = {"value", "type", "argument"}, script = """
            switch (type) {
              case 0: return value * argument;
              case 1: return value + argument;
              default: throw new Error('Unknown MulOrAdd density transform: ' + type);
            }
            """)
    public static native double transformMulOrAdd(double value, int type, double argument);

    @JSBody(params = {"value", "type"}, script = """
            switch (type) {
              case 0: return Math.abs(value);
              case 1: return value * value;
              case 2: return value * value * value;
              case 3: return value > 0 ? value : value * 0.5;
              case 4: return value > 0 ? value : value * 0.25;
              case 5: return 1 / value;
              case 6: {
                const clamped = Math.min(Math.max(value, -1), 1);
                return clamped / 2 - clamped * clamped * clamped / 24;
              }
              default: throw new Error('Unknown mapped density transform: ' + type);
            }
            """)
    public static native double transformMapped(double value, int type);
}
