import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Offline parity check for packed block-state neighborhoods in existing 26.3 chunk NBT. */
public final class RealSectionPackedDiff {
    private static final int AIR = 0;
    private static final int SIZE = 20;

    private record Section(int x, int y, int z, String[] palette, long[] words, int bits,
            Object[] registryStates, int[] unpacked) {
    }

    private record Neighborhood(Section center, Section[] sections, int[][] mappedPalettes) {
    }

    private RealSectionPackedDiff() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: RealSectionPackedDiff <directory-of-gzipped-chunk-nbt>");
        }
        Path root = Path.of(args[0]);
        Map<String, Section> sections = new HashMap<>();
        Map<String, Integer> blockEntityCounts = new HashMap<>();
        TreeSet<String> stateNames = new TreeSet<>();
        int chunks = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".nbt")).toList()) {
                CompoundTag chunk = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                if (chunk.getIntOr("DataVersion", -1) != 5023) {
                    throw new IllegalArgumentException("not a 26.3 chunk (DataVersion 5023): " + file);
                }
                chunks++;
                int chunkX = chunk.getIntOr("xPos", Integer.MIN_VALUE);
                int chunkZ = chunk.getIntOr("zPos", Integer.MIN_VALUE);
                for (CompoundTag entity : chunk.getListOrEmpty("block_entities").compoundStream().toList()) {
                    int sectionY = entity.getIntOr("y", Integer.MIN_VALUE) >> 4;
                    blockEntityCounts.merge(key(chunkX, sectionY, chunkZ), 1, Integer::sum);
                }
                for (CompoundTag sectionTag : chunk.getListOrEmpty("sections").compoundStream().toList()) {
                    CompoundTag states = sectionTag.getCompoundOrEmpty("block_states");
                    ListTag paletteTag = states.getListOrEmpty("palette");
                    if (paletteTag.isEmpty()) {
                        continue;
                    }
                    String[] palette = new String[paletteTag.size()];
                    for (int i = 0; i < palette.length; i++) {
                        palette[i] = paletteTag.getCompoundOrEmpty(i).toString();
                        stateNames.add(palette[i]);
                    }
                    long[] words = states.getLongArray("data").orElseGet(() -> new long[0]);
                    int bits = words.length == 0 ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
                    if (words.length != 0 && words.length != wordCount(bits)) {
                        bits = findBits(words.length);
                    }
                    if (words.length != 0 && words.length != wordCount(bits)) {
                        throw new IllegalArgumentException("unsupported packed width in " + file + " section Y="
                                + sectionTag.getIntOr("Y", 0) + ": palette=" + palette.length + " words=" + words.length);
                    }
                    int y = sectionTag.getIntOr("Y", Integer.MIN_VALUE);
                    sections.put(key(chunkX, y, chunkZ),
                            new Section(chunkX, y, chunkZ, palette, words, bits, null, null));
                }
            }
        }
        List<String> sortedNames = new ArrayList<>(stateNames);
        Map<String, Integer> ids = new HashMap<>();
        Map<String, Object> statesByName = new HashMap<>();
        IdentityHashMap<Object, Integer> registryIds = new IdentityHashMap<>();
        for (int i = 0; i < sortedNames.size(); i++) {
            String name = sortedNames.get(i);
            ids.put(name, i);
            Object state = new Object();
            statesByName.put(name, state);
            registryIds.put(state, i);
        }
        for (Section section : new ArrayList<>(sections.values())) {
            Object[] paletteStates = new Object[section.palette.length];
            for (int i = 0; i < paletteStates.length; i++) paletteStates[i] = statesByName.get(section.palette[i]);
            int[] unpacked = new int[4096];
            if (section.bits != 0) {
                for (int i = 0; i < unpacked.length; i++) unpacked[i] = packed(section.words, section.bits, i);
            }
            sections.put(key(section.x, section.y, section.z), new Section(section.x, section.y, section.z,
                    section.palette, section.words, section.bits, paletteStates, unpacked));
        }
        Map<String, int[]> mappedPalettes = new HashMap<>();
        for (Section section : sections.values()) {
            int[] mapped = new int[section.palette.length];
            for (int i = 0; i < mapped.length; i++) mapped[i] = registryIds.get(section.registryStates[i]);
            mappedPalettes.put(key(section.x, section.y, section.z), mapped);
        }
        List<Section> centers = sections.values().stream()
                .filter(s -> sections.containsKey(key(s.x, s.y - 1, s.z))
                        && sections.containsKey(key(s.x, s.y + 1, s.z)))
                .sorted(Comparator.comparingInt(Section::x).thenComparingInt(Section::y).thenComparingInt(Section::z))
                .toList();
        if (centers.isEmpty()) {
            throw new IllegalStateException("no real section with vertical neighbors found");
        }
        List<Neighborhood> neighborhoods = new ArrayList<>(centers.size());
        for (Section center : centers) {
            Section[] neighbors = new Section[27];
            int[][] neighborPalettes = new int[27][];
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int i = ((dy + 1) * 3 + dz + 1) * 3 + dx + 1;
                Section section = sections.get(key(center.x + dx, center.y + dy, center.z + dz));
                    neighbors[i] = section;
                neighborPalettes[i] = section == null ? null : mappedPalettes.get(key(section.x, section.y, section.z));
            }
            neighborhoods.add(new Neighborhood(center, neighbors, neighborPalettes));
        }
        MessageDigest oldDigest = MessageDigest.getInstance("SHA-256");
        MessageDigest rowDigest = MessageDigest.getInstance("SHA-256");
        int compared = 0;
        int waterSections = 0;
        int slabSections = 0;
        int blockEntityNeighborhoods = 0;
        int boundaryNeighborhoods = 0;
        List<Neighborhood> randomBatch = new ArrayList<>(neighborhoods);
        Collections.shuffle(randomBatch, new Random(0x2635A9L));
        int randomBatchSize = Math.min(64, randomBatch.size());
        ByteArrayOutputStream randomOld = new ByteArrayOutputStream();
        ByteArrayOutputStream randomRow = new ByteArrayOutputStream();
        for (Neighborhood neighborhood : neighborhoods) {
            Section center = neighborhood.center;
            int[] oldOutput = new int[SIZE * SIZE * SIZE];
            int[] rowOutput = new int[SIZE * SIZE * SIZE];
            capture(neighborhood, registryIds, false, oldOutput);
            capture(neighborhood, registryIds, true, rowOutput);
            byte[] oldBytes = encode(oldOutput);
            byte[] rowBytes = encode(rowOutput);
            if (!MessageDigest.isEqual(oldBytes, rowBytes)) {
                throw new AssertionError("state output differs at " + center.x + "," + center.y + "," + center.z
                        + " old=" + hex(MessageDigest.getInstance("SHA-256").digest(oldBytes))
                        + " row=" + hex(MessageDigest.getInstance("SHA-256").digest(rowBytes)));
            }
            oldDigest.update(oldBytes);
            rowDigest.update(rowBytes);
            int missingNeighbors = 0;
            for (Section neighbor : neighborhood.sections) if (neighbor == null) missingNeighbors++;
            if (missingNeighbors != 0) boundaryNeighborhoods++;
            if (contains(center, "water") || contains(center, "lava")) waterSections++;
            if (contains(center, "slab")) slabSections++;
            if (blockEntityCounts.getOrDefault(key(center.x, center.y, center.z), 0) != 0) blockEntityNeighborhoods++;
            compared++;
        }
        for (int i = 0; i < randomBatchSize; i++) {
            Neighborhood neighborhood = randomBatch.get(i);
            int[] oldOutput = new int[SIZE * SIZE * SIZE];
            int[] rowOutput = new int[SIZE * SIZE * SIZE];
            capture(neighborhood, registryIds, false, oldOutput);
            capture(neighborhood, registryIds, true, rowOutput);
            randomOld.writeBytes(encode(oldOutput));
            randomRow.writeBytes(encode(rowOutput));
        }
        if (!MessageDigest.isEqual(randomOld.toByteArray(), randomRow.toByteArray())) {
            throw new AssertionError("random real-section batch differs");
        }
        System.out.println("dataVersion=5023 chunks=" + chunks + " sections=" + sections.size()
                + " globalPaletteStates=" + sortedNames.size() + " comparedNeighborhoods=" + compared
                + " randomBatch=" + randomBatchSize);
        System.out.println("boundaryNeighborhoods=" + boundaryNeighborhoods + " fluidLikeSections=" + waterSections
                + " slabSections=" + slabSections + " blockEntityNeighborhoods=" + blockEntityNeighborhoods);
        System.out.println("oldSha256=" + hex(oldDigest.digest()));
        System.out.println("rowSha256=" + hex(rowDigest.digest()));
        System.out.println("randomOldSha256=" + hex(MessageDigest.getInstance("SHA-256").digest(randomOld.toByteArray())));
        System.out.println("randomRowSha256=" + hex(MessageDigest.getInstance("SHA-256").digest(randomRow.toByteArray())));
        System.out.println("byteEqual=true outputBytesPerNeighborhood=" + (SIZE * SIZE * SIZE * Short.BYTES));
        benchmark(neighborhoods, registryIds);
        System.out.println("scope=real block-state palettes and packed words; excludes published client light and renderers");
    }

    private static boolean contains(Section section, String token) {
        for (String state : section.palette) {
            if (state.contains(token)) return true;
        }
        return false;
    }

    private static void capture(Neighborhood neighborhood, IdentityHashMap<Object, Integer> registryIds,
            boolean rowIncremental, int[] out) {
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int sectionIndex = ((dy + 1) * 3 + dz + 1) * 3 + dx + 1;
            Section section = neighborhood.sections[sectionIndex];
            int[] mappedPalette = neighborhood.mappedPalettes[sectionIndex];
            int x0 = dx < 0 ? 14 : 0, x1 = dx > 0 ? 1 : 15;
            int y0 = dy < 0 ? 14 : 0, y1 = dy > 0 ? 1 : 15;
            int z0 = dz < 0 ? 14 : 0, z1 = dz > 0 ? 1 : 15;
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                int at = ((dy * 16 + y + 2) * SIZE + dz * 16 + z + 2) * SIZE + dx * 16 + x0 + 2;
                int row = (y << 8) | (z << 4);
                int valuesPerWord = section == null || section.bits == 0 ? 1 : 64 / section.bits;
                int index = row | x0;
                int wordIndex = section == null || section.bits == 0 ? 0 : index / valuesPerWord;
                int shift = section == null || section.bits == 0 ? 0 : (index - wordIndex * valuesPerWord) * section.bits;
                int slotsLeft = section == null || section.bits == 0 ? 0 : valuesPerWord - index % valuesPerWord;
                long word = section == null || section.bits == 0 ? 0L : section.words[wordIndex];
                long mask = section == null || section.bits == 0 ? 0L : (1L << section.bits) - 1L;
                for (int x = x0; x <= x1; x++, at++) {
                    int paletteIndex;
                    if (section == null || section.bits == 0) {
                        paletteIndex = 0;
                    } else if (rowIncremental) {
                        paletteIndex = (int) ((word >>> shift) & mask);
                    } else if (dx == 0 && dy == 0 && dz == 0) {
                        paletteIndex = section.unpacked[row | x];
                    } else {
                        paletteIndex = packed(section.words, section.bits, row | x);
                    }
                    out[at] = section == null ? AIR : rowIncremental
                            ? mappedPalette[paletteIndex]
                            : registryIds.get(section.registryStates[paletteIndex]);
                    if (rowIncremental && section != null && section.bits != 0 && x < x1) {
                        if (--slotsLeft == 0) {
                            word = section.words[++wordIndex];
                            shift = 0;
                            slotsLeft = valuesPerWord;
                        } else {
                            shift += section.bits;
                        }
                    }
                }
            }
        }
    }

    private static void benchmark(List<Neighborhood> neighborhoods, IdentityHashMap<Object, Integer> registryIds) {
        int rounds = 40;
        int[] output = new int[SIZE * SIZE * SIZE];
        for (int i = 0; i < 4; i++) runBatch(neighborhoods, registryIds, false, output, 1);
        for (int i = 0; i < 4; i++) runBatch(neighborhoods, registryIds, true, output, 1);
        ThreadMXBean bean = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        boolean allocationAvailable = bean != null && bean.isThreadAllocatedMemorySupported();
        long thread = Thread.currentThread().threadId();
        long oldAllocatedBefore = allocated(bean, allocationAvailable, thread);
        long oldStart = System.nanoTime();
        runBatch(neighborhoods, registryIds, false, output, rounds);
        long oldNanos = System.nanoTime() - oldStart;
        long oldAllocated = allocated(bean, allocationAvailable, thread) - oldAllocatedBefore;
        long rowAllocatedBefore = allocated(bean, allocationAvailable, thread);
        long rowStart = System.nanoTime();
        runBatch(neighborhoods, registryIds, true, output, rounds);
        long rowNanos = System.nanoTime() - rowStart;
        long rowAllocated = allocated(bean, allocationAvailable, thread) - rowAllocatedBefore;
        long captures = (long) rounds * neighborhoods.size();
        double oldNs = (double) oldNanos / captures;
        double rowNs = (double) rowNanos / captures;
        System.out.println("jvmStateBatch rounds=" + rounds + " capturesPerPath=" + captures
                + " oldNsPerCapture=" + oldNs + " rowNsPerCapture=" + rowNs
                + " reductionPercent=" + ((1.0 - rowNs / oldNs) * 100.0)
                + " oldAllocatedBytesPerCapture=" + (allocationAvailable ? (double) oldAllocated / captures : -1)
                + " rowAllocatedBytesPerCapture=" + (allocationAvailable ? (double) rowAllocated / captures : -1));
    }

    private static long allocated(ThreadMXBean bean, boolean enabled, long thread) {
        return enabled ? bean.getThreadAllocatedBytes(thread) : 0L;
    }

    private static void runBatch(List<Neighborhood> neighborhoods, IdentityHashMap<Object, Integer> registryIds,
            boolean rowIncremental,
            int[] output, int rounds) {
        for (int round = 0; round < rounds; round++) {
            for (Neighborhood neighborhood : neighborhoods) capture(neighborhood, registryIds, rowIncremental, output);
        }
    }

    private static int packed(long[] words, int bits, int index) {
        int valuesPerWord = 64 / bits;
        int word = index / valuesPerWord;
        int shift = (index - word * valuesPerWord) * bits;
        return (int) ((words[word] >>> shift) & ((1L << bits) - 1L));
    }

    private static int findBits(int words) {
        for (int bits = 1; bits <= 32; bits++) if (wordCount(bits) == words) return bits;
        return -1;
    }

    private static int wordCount(int bits) {
        return (4096 + 64 / bits - 1) / (64 / bits);
    }

    private static byte[] encode(int[] ids) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(ids.length * Short.BYTES);
        for (int id : ids) {
            if (id > 0xffff) throw new IllegalArgumentException("fixture state id exceeds narrow buffer: " + id);
            out.write(id);
            out.write(id >>> 8);
        }
        return out.toByteArray();
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
