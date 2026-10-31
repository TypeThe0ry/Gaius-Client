package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Collapses the nested generic lerp calls used for every interpolated terrain sample. */
public final class BrowserNoiseInterpolator {
    private BrowserNoiseInterpolator() {
    }

    @JSBody(
            params = {
                "x", "y", "z",
                "n000", "n100", "n010", "n110",
                "n001", "n101", "n011", "n111"
            },
            script = """
                    const z0y0 = n000 + x * (n100 - n000);
                    const z0y1 = n010 + x * (n110 - n010);
                    const z1y0 = n001 + x * (n101 - n001);
                    const z1y1 = n011 + x * (n111 - n011);
                    const z0 = z0y0 + y * (z0y1 - z0y0);
                    const z1 = z1y0 + y * (z1y1 - z1y0);
                    return z0 + z * (z1 - z0);
                    """)
    public static native double lerp3(
            double x,
            double y,
            double z,
            double n000,
            double n100,
            double n010,
            double n110,
            double n001,
            double n101,
            double n011,
            double n111);
}
