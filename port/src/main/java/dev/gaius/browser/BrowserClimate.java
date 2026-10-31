package dev.gaius.browser;

import net.minecraft.world.level.biome.Climate;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Allocation-free browser hot paths for seven-dimensional biome climate lookup. */
public final class BrowserClimate {
    private BrowserClimate() {
    }

    public static double[] prepareBounds(Climate.Parameter[] parameterSpace) {
        double[] bounds = new double[14];
        for (int index = 0; index < 7; index++) {
            Climate.Parameter parameter = parameterSpace[index];
            long first = parameter.min();
            long second = parameter.max();
            bounds[index * 2] = Math.min(first, second);
            bounds[index * 2 + 1] = Math.max(first, second);
        }
        return bounds;
    }

    @JSBody(params = {"bounds", "target"}, script = """
            const values = target;
            if (!bounds || !values || bounds.length < 14 || values.length < 7) {
              return BigInt(0);
            }
            let value0 = values.__gaiusClimateValue0;
            if (value0 === undefined) {
              value0 = Number(values[0]);
              values.__gaiusClimateValue0 = value0;
              values.__gaiusClimateValue1 = Number(values[1]);
              values.__gaiusClimateValue2 = Number(values[2]);
              values.__gaiusClimateValue3 = Number(values[3]);
              values.__gaiusClimateValue4 = Number(values[4]);
              values.__gaiusClimateValue5 = Number(values[5]);
              values.__gaiusClimateValue6 = Number(values[6]);
            }
            let total = 0;
            let value = value0;
            let distance = value > bounds[1]
              ? value - bounds[1] : (value < bounds[0] ? bounds[0] - value : 0);
            total += distance * distance;
            value = values.__gaiusClimateValue1;
            distance = value > bounds[3]
              ? value - bounds[3] : (value < bounds[2] ? bounds[2] - value : 0);
            total += distance * distance;
            value = values.__gaiusClimateValue2;
            distance = value > bounds[5]
              ? value - bounds[5] : (value < bounds[4] ? bounds[4] - value : 0);
            total += distance * distance;
            value = values.__gaiusClimateValue3;
            distance = value > bounds[7]
              ? value - bounds[7] : (value < bounds[6] ? bounds[6] - value : 0);
            total += distance * distance;
            value = values.__gaiusClimateValue4;
            distance = value > bounds[9]
              ? value - bounds[9] : (value < bounds[8] ? bounds[8] - value : 0);
            total += distance * distance;
            value = values.__gaiusClimateValue5;
            distance = value > bounds[11]
              ? value - bounds[11] : (value < bounds[10] ? bounds[10] - value : 0);
            total += distance * distance;
            value = values.__gaiusClimateValue6;
            distance = value > bounds[13]
              ? value - bounds[13] : (value < bounds[12] ? bounds[12] - value : 0);
            total += distance * distance;
            return BigInt(Math.floor(total));
            """)
    public static native long distance(
            @JSByRef double[] bounds,
            @JSByRef long[] target);
}
