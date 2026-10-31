import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

/** Compares Identifier equality/hash behavior against the unmodified client classes. */
public final class IdentifierHashParity {
    private IdentifierHashParity() {
    }

    public static void main(String[] args) {
        List<String> values = List.of(
                "minecraft:stone",
                "minecraft:block/stone",
                "minecraft:entity/player",
                "custom:with-dashes_and.dots",
                "a:0",
                "z:path/path/path",
                "minecraft:"
        );
        long hashDigest = 1125899906842597L;
        long equalityDigest = 1469598103934665603L;
        List<Identifier> ids = new ArrayList<>();
        for (String value : values) {
            Identifier id = Identifier.parse(value);
            Identifier rebuilt = Identifier.fromNamespaceAndPath(
                    new String(id.getNamespace()), new String(id.getPath()));
            Identifier different = Identifier.parse(value + "_x");
            hashDigest = 31 * hashDigest + id.hashCode();
            hashDigest = 31 * hashDigest + rebuilt.hashCode();
            hashDigest = 31 * hashDigest + different.hashCode();
            equalityDigest = 31 * equalityDigest + (id == id ? 1 : 0);
            equalityDigest = 31 * equalityDigest + (id.equals(rebuilt) ? 1 : 0);
            equalityDigest = 31 * equalityDigest + (id.equals(different) ? 1 : 0);
            equalityDigest = 31 * equalityDigest + (id.equals(value) ? 1 : 0);
            ids.add(id);
            ids.add(rebuilt);
            ids.add(different);
        }

        Map<Identifier, Integer> map = new HashMap<>();
        for (int index = 0; index < values.size(); index++) {
            map.put(Identifier.parse(values.get(index)), index);
        }
        int misses = 0;
        for (String value : values) {
            Integer found = map.get(Identifier.parse(new String(value)));
            if (found == null) {
                misses++;
            }
        }
        // Every registered id of every built-in registry: hash, equality with a rebuilt copy and with
        // its neighbour, and a HashMap round trip through rebuilt keys.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<Identifier> registered = new ArrayList<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            registered.add(registry.key().identifier());
            registered.addAll(registry.keySet());
        }
        long registryHash = 1125899906842597L;
        long registryEquality = 1469598103934665603L;
        Map<Identifier, Integer> registryMap = new HashMap<>();
        for (int index = 0; index < registered.size(); index++) {
            Identifier id = registered.get(index);
            Identifier rebuilt = Identifier.fromNamespaceAndPath(new String(id.getNamespace()), new String(id.getPath()));
            Identifier next = registered.get((index + 1) % registered.size());
            registryHash = 31 * registryHash + id.hashCode();
            registryHash = 31 * registryHash + rebuilt.hashCode();
            registryEquality = 31 * registryEquality + (id.equals(rebuilt) ? 1 : 0);
            registryEquality = 31 * registryEquality + (rebuilt.equals(id) ? 1 : 0);
            registryEquality = 31 * registryEquality + (id.equals(next) ? 1 : 0);
            registryEquality = 31 * registryEquality + (id.hashCode() == next.hashCode() ? 1 : 0);
            registryMap.putIfAbsent(id, index);
        }
        int registryMisses = 0;
        for (Identifier id : registered) {
            Identifier rebuilt = Identifier.parse(id.toString());
            if (!registryMap.containsKey(rebuilt)) {
                registryMisses++;
            }
        }
        System.out.println("registryIds " + registered.size() + " distinct " + registryMap.size());
        System.out.println("registryHash " + Long.toHexString(registryHash));
        System.out.println("registryEquality " + Long.toHexString(registryEquality));
        System.out.println("registryMisses " + registryMisses);
        System.out.println("count " + ids.size());
        System.out.println("hashDigest " + Long.toHexString(hashDigest));
        System.out.println("equalityDigest " + Long.toHexString(equalityDigest));
        System.out.println("mapMisses " + misses);
        System.out.println("hashFormula " + (31 * "minecraft".hashCode() + "stone".hashCode()));
    }
}
