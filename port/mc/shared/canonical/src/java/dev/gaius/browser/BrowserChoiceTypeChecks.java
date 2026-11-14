package dev.gaius.browser;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.types.Type;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;

/**
 * The data-fixer schema checks of entity and block entity registration, run once the real data
 * fixer exists instead of building it during bootstrap.
 *
 * <p>{@code EntityType$Builder.build} and the block entity {@code register} call
 * {@code Util.fetchChoiceType(type, id)} and drop the result: the call only asks the current
 * schema for the choice type and logs {@code "No data fixer registered for {}"} when it has none.
 * Its first call builds the whole historical fixer graph ({@code DataFixers.<clinit>}), which
 * the browser otherwise builds only for an older save ({@link BrowserLazyDataFixer}).
 * {@code MinecraftClientPatcher.patchDeferredChoiceTypeChecks} points those two call sites,
 * each followed by a {@code POP}, at {@link #defer}; it returns null, which the caller drops.
 *
 * <p>{@link #defer} records the pair when {@code SharedConstants.CHECK_DATA_FIXER_SCHEMA} is set
 * (otherwise {@code fetchChoiceType} does nothing either). {@link #replay} runs the recorded
 * pairs through the vanilla {@code Util.fetchChoiceType}, in registration order, after the real
 * fixer is built; later calls of {@link #defer} check at once. The vanilla method logs any
 * missing choice type with its own logger and message. Nothing suspends in here: no JS call, no
 * lock, only list appends.
 */
public final class BrowserChoiceTypeChecks {
    private static List<DSL.TypeReference> types = new ArrayList<>();
    private static List<String> ids = new ArrayList<>();

    private BrowserChoiceTypeChecks() {
    }

    /** Stands in for {@code Util.fetchChoiceType} at the call sites that drop its result. */
    public static Type<?> defer(DSL.TypeReference type, String id) {
        if (types == null) {
            return Util.fetchChoiceType(type, id);
        }
        if (SharedConstants.CHECK_DATA_FIXER_SCHEMA) {
            types.add(type);
            ids.add(id);
        }
        return null;
    }

    /** Runs the recorded checks once; call it after the real data fixer is built. */
    public static void replay() {
        List<DSL.TypeReference> recordedTypes = types;
        List<String> recordedIds = ids;
        if (recordedTypes == null) {
            return;
        }
        types = null;
        ids = null;
        for (int index = 0; index < recordedTypes.size(); index++) {
            Util.fetchChoiceType(recordedTypes.get(index), recordedIds.get(index));
        }
    }

    /** The checks still waiting for {@link #replay}, as {@code type id} lines (JVM gate). */
    public static List<String> pending() {
        List<String> result = new ArrayList<>();
        if (types != null) {
            for (int index = 0; index < types.size(); index++) {
                result.add(types.get(index).typeName() + " " + ids.get(index));
            }
        }
        return result;
    }
}
