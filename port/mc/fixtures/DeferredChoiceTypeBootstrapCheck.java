import com.mojang.datafixers.DSL;
import com.mojang.datafixers.types.Type;
import dev.gaius.browser.BrowserChoiceTypeChecks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One side of the JVM gate of MinecraftClientPatcher.patchDeferredChoiceTypeChecks; the runner
 * (port/scripts/deferred-choice-type-checks-jvm.test.mjs) starts it twice in fresh JVMs, once on
 * the vanilla jar and once with the two patched classes first on the class path, and compares:
 *
 * <ul>
 *   <li>{@code registries.txt}: every built-in registry after {@code Bootstrap.bootStrap()}, in
 *       registry order, each entry with raw id, key and class;</li>
 *   <li>{@code expected.txt}: the checks vanilla's registration runs, derived from the
 *       registries (each serializable entity type as {@code entity_tree <key>}, each
 *       {@code minecraft} block entity type as {@code block_entity <path>}), in registration
 *       order;</li>
 *   <li>{@code pending.txt} (patched): what BrowserChoiceTypeChecks recorded; must equal
 *       expected.txt;</li>
 *   <li>the log (log4j, every level) split by GATE marks into bootstrap / replay / verify;</li>
 *   <li>the class initialization log of the JVM (marker classes split it the same way).</li>
 * </ul>
 * Patched mode then builds the real fixer and replays exactly as BrowserLazyDataFixer.fallback
 * does. Both modes finally resolve every expected pair through the vanilla Util.fetchChoiceType
 * and require a non-null choice type.
 *   java DeferredChoiceTypeBootstrapCheck <vanilla|patched> <out dir>
 */
public final class DeferredChoiceTypeBootstrapCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger("gaius-choice-type-gate");

    static final class BootstrapDone {
        static final boolean MARK = mark("bootstrap");
    }

    static final class ReplayDone {
        static final boolean MARK = mark("replay");
    }

    private static boolean mark(String phase) {
        LOGGER.info("GATE {}", phase);
        return true;
    }

    public static void main(String[] args) throws Exception {
        boolean patched = args[0].equals("patched");
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!BootstrapDone.MARK) {
            throw new AssertionError();
        }

        List<String> registries = new ArrayList<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            dump(registry, registries);
        }
        write(out.resolve("registries.txt"), registries);

        List<String> expected = new ArrayList<>();
        List<DSL.TypeReference> expectedTypes = new ArrayList<>();
        List<String> expectedIds = new ArrayList<>();
        if (SharedConstants.CHECK_DATA_FIXER_SCHEMA) {
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                if (type.canSerialize()) {
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                    expected.add(References.ENTITY_TREE.typeName() + " " + id);
                    expectedTypes.add(References.ENTITY_TREE);
                    expectedIds.add(id);
                }
            }
            for (Object type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                String key = keyOf(BuiltInRegistries.BLOCK_ENTITY_TYPE, type);
                int colon = key.indexOf(':');
                if (key.substring(0, colon).equals("minecraft")) {
                    String path = key.substring(colon + 1);
                    expected.add(References.BLOCK_ENTITY.typeName() + " " + path);
                    expectedTypes.add(References.BLOCK_ENTITY);
                    expectedIds.add(path);
                }
            }
        }
        write(out.resolve("expected.txt"), expected);

        if (patched) {
            write(out.resolve("pending.txt"), BrowserChoiceTypeChecks.pending());
            // BrowserLazyDataFixer.fallback(): build the real fixer, then replay.
            DataFixers.getDataFixer();
            BrowserChoiceTypeChecks.replay();
            if (!BrowserChoiceTypeChecks.pending().isEmpty()) {
                throw new IllegalStateException("replay left checks pending");
            }
        }
        if (!ReplayDone.MARK) {
            throw new AssertionError();
        }

        int resolved = 0;
        for (int index = 0; index < expectedTypes.size(); index++) {
            Type<?> choice = Util.fetchChoiceType(expectedTypes.get(index), expectedIds.get(index));
            if (choice == null) {
                throw new IllegalStateException("no choice type for " + expected.get(index));
            }
            resolved++;
        }
        System.out.println("CHOICE_TYPE_GATE mode=" + args[0] + " registries=" + countRegistries()
                + " entries=" + registries.size() + " expected=" + expected.size() + " resolved=" + resolved
                + " checkSchema=" + SharedConstants.CHECK_DATA_FIXER_SCHEMA
                + " ide=" + SharedConstants.IS_RUNNING_IN_IDE);
        System.exit(0);
    }

    private static int countRegistries() {
        int count = 0;
        for (Registry<?> ignored : BuiltInRegistries.REGISTRY) {
            count++;
        }
        return count;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String keyOf(Registry registry, Object value) {
        return String.valueOf(registry.getKey(value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dump(Registry registry, List<String> lines) {
        lines.add("registry " + registry.key() + " size=" + registry.size());
        for (Object value : registry) {
            lines.add("  " + registry.getId(value) + " " + registry.getKey(value) + " "
                    + value.getClass().getName().replaceAll("\\$\\$Lambda.*", "\\$\\$Lambda"));
        }
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
