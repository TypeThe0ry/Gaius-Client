package dev.gaius.parity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

/**
 * JVM side of structure-rings-parity.mjs: the concentric-ring structure positions (vanilla:
 * strongholds) of the overworld of new "minecraft:normal" worlds, computed by whatever
 * ChunkGeneratorStructureState the class path holds (vanilla, or a patched class in front of
 * the vanilla jar). The registries come from the vanilla data pack (ParityCompat.loadStem); per
 * seed the state is built as ChunkMap builds it (ParityCompat.structureState).
 *
 * <p>Per seed and concentric-ring structure set, in this order:
 * <ol>
 *   <li>{@code getRingPositionsFor} (vanilla joins every biome search here);</li>
 *   <li>the probes of {@code --probes} (lines {@code <seed> <set> x,z x,z ... | x,z ...}, groups
 *       separated by {@code |}), answered in order with {@code contains(new ChunkPos(x, z))} of
 *       that list, which is what ConcentricRingsStructurePlacement.isPlacementChunk asks once per
 *       generated chunk;</li>
 *   <li>a copy of the whole list (iteration, as ChunkGenerator.findNearestMapStructure does).</li>
 * </ol>
 * A list of the lazy-rings patch (dev.gaius.browser.BrowserRingPositions) also reports,
 * by reflection, how many searches had run after getRingPositionsFor and after each probe group,
 * and the start chunk of every search, so the runner can check that each result stays within the
 * reach the lazy contains relies on.
 * The result file is JSON; one line WORLDGEN_RING_PARITY_OK ends a good run.
 *
 *   java -cp ... dev.gaius.parity.StructureRingsParity --seeds 1,2,3 --out result.json
 *        [--probes probes.txt] [--label vanilla]
 */
public final class StructureRingsParity {
    private static final String LAZY_LIST = "dev.gaius.browser.BrowserRingPositions";

    private StructureRingsParity() {
    }

    public static void main(String[] args) throws Exception {
        List<Long> seeds = new ArrayList<>();
        Path out = null;
        Path probeFile = null;
        String label = "run";
        double playerX = Double.NaN;
        double playerZ = Double.NaN;
        for (int index = 0; index < args.length; index++) {
            String value = index + 1 < args.length ? args[index + 1] : null;
            switch (args[index]) {
                case "--seeds" -> {
                    for (String seed : require(value, "--seeds").split(",")) {
                        seeds.add(Long.parseLong(seed.trim()));
                    }
                    index++;
                }
                case "--out" -> {
                    out = Path.of(require(value, "--out"));
                    index++;
                }
                case "--probes" -> {
                    probeFile = Path.of(require(value, "--probes"));
                    index++;
                }
                case "--label" -> {
                    label = require(value, "--label");
                    index++;
                }
                case "--player-x" -> { playerX = Double.parseDouble(require(value, "--player-x")); index++; }
                case "--player-z" -> { playerZ = Double.parseDouble(require(value, "--player-z")); index++; }
                default -> throw new IllegalArgumentException("unknown argument " + args[index]);
            }
        }
        if (seeds.isEmpty() || out == null) {
            throw new IllegalArgumentException("--seeds and --out are required");
        }
        Map<String, List<List<int[]>>> probes = probeFile == null ? Map.of() : readProbes(probeFile);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        StringBuilder json = new StringBuilder();
        json.append("{\"profile\":\"").append(ParityCompat.PROFILE).append("\",\"label\":\"")
                .append(label).append("\",\"seeds\":[");
        long started = System.nanoTime();
        try (WorldStem stem = ParityCompat.loadStem(packs, seeds.get(0), GameType.SURVIVAL,
                Difficulty.NORMAL, false, true)) {
            RegistryAccess access = stem.registries().compositeAccess();
            for (int seedIndex = 0; seedIndex < seeds.size(); seedIndex++) {
                long seed = seeds.get(seedIndex);
                ChunkGeneratorStructureState state = ParityCompat.structureState(access, seed);
                json.append(seedIndex == 0 ? "" : ",").append("{\"seed\":\"").append(seed)
                        .append("\",\"sets\":[");
                boolean firstSet = true;
                for (Holder<StructureSet> set : state.possibleStructureSets()) {
                    if (!(set.value().placement() instanceof ConcentricRingsStructurePlacement rings)) {
                        continue;
                    }
                    String name = ParityCompat.keyName(set.unwrapKey().orElseThrow());
                    json.append(firstSet ? "" : ",");
                    firstSet = false;
                    ringSet(json, state, rings, name, probes.getOrDefault(seed + " " + name, List.of()), playerX, playerZ);
                    System.out.println("WORLDGEN_RING_PARITY_SET seed=" + seed + " set=" + name);
                }
                json.append("]}");
            }
        }
        json.append("],\"millis\":").append((System.nanoTime() - started) / 1_000_000).append("}\n");
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.println("WORLDGEN_RING_PARITY_OK profile=" + ParityCompat.PROFILE + " label=" + label
                + " seeds=" + seeds.size());
        // The vanilla background executor keeps non-daemon threads.
        System.exit(0);
    }

    private static void ringSet(StringBuilder json, ChunkGeneratorStructureState state,
            ConcentricRingsStructurePlacement rings, String name, List<List<int[]>> probes,
            double playerX, double playerZ) throws Exception {
        long listStart = System.nanoTime();
        List<ChunkPos> positions = state.getRingPositionsFor(rings);
        long listNanos = System.nanoTime() - listStart;
        boolean lazy = positions.getClass().getName().equals(LAZY_LIST);
        int resolvedAtList = lazy ? resolved(positions) : positions.size();
        StringBuilder answers = new StringBuilder();
        StringBuilder resolvedAfterGroups = new StringBuilder();
        long probeStart = System.nanoTime();
        for (List<int[]> group : probes) {
            for (int[] probe : group) {
                answers.append(positions.contains(new ChunkPos(probe[0], probe[1])) ? '1' : '0');
            }
            resolvedAfterGroups.append(resolvedAfterGroups.isEmpty() ? "" : ",")
                    .append(lazy ? resolved(positions) : positions.size());
        }
        long probeNanos = System.nanoTime() - probeStart;
        int resolvedBeforeCopy = lazy ? resolved(positions) : positions.size();
        long copyStart = System.nanoTime();
        List<ChunkPos> copy = new ArrayList<>(positions);
        long copyNanos = System.nanoTime() - copyStart;
        ChunkPos nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        if (!Double.isNaN(playerX) && !Double.isNaN(playerZ)) {
            for (ChunkPos position : copy) {
                double dx = position.getMiddleBlockX() - playerX;
                double dz = position.getMiddleBlockZ() - playerZ;
                double distance = dx * dx + dz * dz;
                if (distance < nearestDistance) { nearestDistance = distance; nearest = position; }
            }
        }
        json.append("{\"set\":\"").append(name).append("\",\"count\":").append(rings.count())
                .append(",\"list\":\"").append(positions.getClass().getName()).append("\",\"positions\":[");
        for (int index = 0; index < copy.size(); index++) {
            ChunkPos position = copy.get(index);
            json.append(index == 0 ? "" : ",").append('[').append(ParityCompat.x(position)).append(',')
                    .append(ParityCompat.z(position)).append(']');
        }
        json.append("],\"probes\":\"").append(answers).append("\",\"resolvedAtList\":")
                .append(resolvedAtList).append(",\"resolvedAfterGroups\":[").append(resolvedAfterGroups)
                .append("],\"resolvedBeforeCopy\":").append(resolvedBeforeCopy);
        if (lazy) {
            int[] startX = (int[]) field(positions, "startX");
            int[] startZ = (int[]) field(positions, "startZ");
            json.append(",\"reach\":").append(field(positions, "reach")).append(",\"starts\":[");
            for (int index = 0; index < startX.length; index++) {
                json.append(index == 0 ? "" : ",").append('[').append(startX[index]).append(',')
                        .append(startZ[index]).append(']');
            }
            json.append(']');
        }
        json.append(",\"listMillis\":").append(listNanos / 1e6).append(",\"probeMillis\":")
                .append(probeNanos / 1e6).append(",\"copyMillis\":").append(copyNanos / 1e6);
        if (nearest != null) json.append(",\"nearest\":[").append(ParityCompat.x(nearest)).append(',')
                .append(ParityCompat.z(nearest)).append("],\"nearestDistance\":").append(nearestDistance);
        json.append('}');
    }

    private static int resolved(List<ChunkPos> positions) throws ReflectiveOperationException {
        int count = 0;
        for (Object position : (Object[]) field(positions, "positions")) {
            if (position != null) {
                count++;
            }
        }
        return count;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Map<String, List<List<int[]>>> readProbes(Path file) throws IOException {
        Map<String, List<List<int[]>>> probes = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split(" ");
            if (parts.length < 2) {
                continue;
            }
            List<List<int[]>> groups = new ArrayList<>();
            groups.add(new ArrayList<>());
            for (int index = 2; index < parts.length; index++) {
                if (parts[index].equals("|")) {
                    groups.add(new ArrayList<>());
                    continue;
                }
                String[] xz = parts[index].split(",");
                groups.get(groups.size() - 1).add(new int[] {Integer.parseInt(xz[0]), Integer.parseInt(xz[1])});
            }
            probes.put(parts[0] + " " + parts[1], groups);
        }
        return probes;
    }

    private static String require(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        return value;
    }
}
