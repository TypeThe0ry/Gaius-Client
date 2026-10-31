package dev.gaius.golden;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Records a 26.3 NormalNoise recipe in case params: the codec-level
 * parameters plus the octave list and normalization factor the constructor
 * derives from them, so a kernel can be checked stage by stage.
 */
final class NormalRecipe {
    private NormalRecipe() {
    }

    static Case describe(Case c, NormalNoise noise) throws ReflectiveOperationException {
        Object parameters = field(noise, "parameters");
        c.param("base_amplitude", (Double) call(parameters, "baseAmplitude"))
                .param("base_octave", (Integer) call(parameters, "baseOctave"))
                .param("octave_count", (Integer) call(parameters, "octaveCount"))
                .param("normalize", ((Enum<?>) call(parameters, "normalize")).name().toLowerCase(Locale.ROOT))
                .param("amplitude_modifiers", ((DoubleList) call(parameters, "amplitudeModifiers")).toDoubleArray())
                .param("normalization_factor", (Double) field(noise, "normalizationFactor"));

        List<Object> octaves = new ArrayList<>();
        for (Object octave : (List<?>) field(noise, "octaves")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", call(octave, "octaveIndex"));
            entry.put("frequency", call(octave, "frequency"));
            entry.put("amplitude", call(octave, "amplitude"));
            entry.put("seed", call(octave, "seed"));
            octaves.add(entry);
        }
        return c.param("octaves", octaves);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
