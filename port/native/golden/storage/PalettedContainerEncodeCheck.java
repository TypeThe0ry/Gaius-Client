import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Byte-for-byte check of the chunk save encoding of palettes (patchPalettedContainerBrowserEncode).
 *
 * <pre>java PalettedContainerEncodeCheck LABEL OUT_FILE [--bench N] REGION_DIR...</pre>
 *
 * Decodes every {@code block_states} and {@code biomes} container of the sections of the chunks in
 * the {@code r.*.mca} files of the REGION_DIRs (browser worlds, uncompressed or zlib), re-encodes it
 * with the container codecs the game uses ({@code PalettedContainer.codecRW/RO}, which the patch
 * changes), adds a few thousand seeded random containers (single values, runs, every palette size up
 * to the global palette, overwritten palettes with unused entries), and writes one line per container
 * with the SHA-256 of the NBT bytes the region file would hold. The check script runs it on the
 * vanilla classes and on the patched ones and compares the two files with {@code cmp}.
 *
 * <p>When the Gaius helpers are on the class path it also checks them on their own, against vanilla's
 * own code in the same JVM: the memo codec against the codec it wraps for every block state and
 * biome, and BrowserPaletteRepack.reencode against vanilla's loop (result array and the target
 * palette's entries, in order) for random storages of every width.
 */
public final class PalettedContainerEncodeCheck {
    private static final List<String> failures = new ArrayList<>();
    private static MessageDigest sha;

    private PalettedContainerEncodeCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: LABEL OUT_FILE [--bench N] REGION_DIR...");
        }
        String label = args[0];
        Path out = Path.of(args[1]);
        int benchRuns = 0;
        List<Path> regionDirs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--bench")) {
                benchRuns = Integer.parseInt(args[++i]);
            } else {
                regionDirs.add(Path.of(args[i]));
            }
        }
        sha = MessageDigest.getInstance("SHA-256");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Factory factory = new Factory();
        List<byte[]> corpus = new ArrayList<>();
        int chunks = 0;
        for (Path dir : regionDirs) {
            try (var files = Files.newDirectoryStream(dir, "r.*.mca")) {
                List<Path> sorted = new ArrayList<>();
                files.forEach(sorted::add);
                sorted.sort(null);
                for (Path file : sorted) {
                    chunks += readRegion(file, corpus);
                }
            }
        }
        List<CompoundTag> sectionTags = new ArrayList<>();
        for (byte[] chunk : corpus) {
            CompoundTag root = NbtIo.read(new DataInputStream(new ByteArrayInputStream(chunk)));
            Tag sections = root.get("sections");
            if (sections instanceof ListTag list) {
                for (Tag section : list) {
                    if (section instanceof CompoundTag compound) {
                        sectionTags.add(compound);
                    }
                }
            }
        }

        List<String> lines = new ArrayList<>();
        long containers = 0;
        long bytes = 0;
        int undecodable = 0;
        List<PalettedContainer<BlockState>> blockContainers = new ArrayList<>();
        List<PalettedContainerRO<Holder<Biome>>> biomeContainers = new ArrayList<>();
        for (CompoundTag section : sectionTags) {
            Tag states = section.get("block_states");
            if (states != null) {
                // a profile older than the one that saved the world may not know every block
                var parsed = factory.parseBlocks(states);
                if (parsed.isPresent()) {
                    blockContainers.add(parsed.get());
                } else {
                    undecodable++;
                }
            }
            Tag biomes = section.get("biomes");
            if (biomes != null) {
                var parsed = factory.biomeCodec.parse(NbtOps.INSTANCE, biomes).result();
                if (parsed.isPresent()) {
                    biomeContainers.add(parsed.get());
                } else {
                    undecodable++;
                }
            }
        }
        Random random = new Random(0x5A17E5L);
        int synthetic = 0;
        for (int i = 0; i < 4000; i++) {
            blockContainers.add(factory.randomBlocks(random));
            biomeContainers.add(factory.randomBiomes(random));
            synthetic += 2;
        }
        System.out.println(label + ": " + chunks + " chunks, " + sectionTags.size() + " sections, "
                + blockContainers.size() + " block containers, " + biomeContainers.size() + " biome containers ("
                + synthetic + " synthetic, " + undecodable + " not decodable by this profile)");

        for (int i = 0; i < blockContainers.size(); i++) {
            byte[] encoded = encode(factory.blockCodec, blockContainers.get(i));
            lines.add("B " + i + " " + encoded.length + " " + hex(sha.digest(encoded)));
            containers++;
            bytes += encoded.length;
        }
        for (int i = 0; i < biomeContainers.size(); i++) {
            byte[] encoded = encode(factory.biomeCodec, biomeContainers.get(i));
            lines.add("M " + i + " " + encoded.length + " " + hex(sha.digest(encoded)));
            containers++;
            bytes += encoded.length;
        }
        // A decode-encode round trip of the stored sections must also reproduce the stored bytes of
        // every container whose stored form is canonical (what the game itself wrote).
        long canonical = 0;
        long stored = 0;
        for (CompoundTag section : sectionTags) {
            Tag states = section.get("block_states");
            if (states instanceof CompoundTag tag) {
                var parsed = factory.blockCodec.parse(NbtOps.INSTANCE, tag).result();
                if (parsed.isEmpty()) {
                    continue; // names this profile does not know: the sections above stand in them with stone
                }
                stored++;
                if (Arrays.equals(nbtBytes(tag), encode(factory.blockCodec, parsed.get()))) {
                    canonical++;
                }
            }
        }
        lines.add("canonical-block-states " + canonical + "/" + stored);
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.println(label + ": " + containers + " containers, " + bytes + " NBT bytes, "
                + canonical + "/" + stored + " stored block_states re-encode to their stored bytes");

        helperChecks(factory);

        if (benchRuns > 0) {
            bench(label, factory, blockContainers, biomeContainers, sectionTags.size(), benchRuns);
        }
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(failure -> System.err.println("palette encode: " + failure));
            System.err.println("palette encode: " + failures.size() + " problem(s)");
            System.exit(1);
        }
    }

    // --- the codecs the game builds in PalettedContainerFactory.create ----------------------------

    private static final class Factory {
        final Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        final BlockState air = Blocks.AIR.defaultBlockState();
        final Codec<PalettedContainer<BlockState>> blockCodec =
                PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, air);
        final MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        final Strategy<Holder<Biome>> biomeStrategy;
        final Holder<Biome> plains;
        final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
        boolean reportedFirstFailure;
        final List<BlockState> states = new ArrayList<>();
        final List<Holder<Biome>> biomeHolders = new ArrayList<>();

        Factory() {
            // the lookup's order is a hash order: register by name so every run builds the same registry
            lookup().lookupOrThrow(Registries.BIOME).listElements()
                    .sorted(java.util.Comparator.comparing(holder -> holder.key().toString()))
                    .forEach(holder -> biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
            biomes.freeze();
            biomeStrategy = Strategy.createForBiomes(biomes.asHolderIdMap());
            plains = biomes.getOrThrow(Biomes.PLAINS);
            biomeCodec = PalettedContainer.codecRO(biomes.holderByNameCodec(), biomeStrategy, plains);
            for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
                states.add(Block.BLOCK_STATE_REGISTRY.byId(id));
            }
            biomes.listElements().forEach(biomeHolders::add);
        }

        /**
         * Decodes a stored block_states tag. A profile older than the world's writer may not know a block
         * name: those palette entries become plain stone (the palette can then repeat a state, which is a
         * shape the repack must handle too).
         */
        java.util.Optional<PalettedContainer<BlockState>> parseBlocks(Tag stored) {
            var directResult = blockCodec.parse(NbtOps.INSTANCE, stored);
            var direct = directResult.result();
            if (direct.isEmpty() && !reportedFirstFailure) {
                reportedFirstFailure = true;
                System.out.println("first block_states decode failure: " + directResult.error().map(e -> e.message()).orElse("?"));
            }
            if (direct.isPresent() || !(stored instanceof CompoundTag compound) || !(compound.get("palette") instanceof ListTag palette)) {
                return direct;
            }
            CompoundTag copy = compound.copy();
            ListTag fixed = new ListTag();
            for (Tag entry : (ListTag) copy.get("palette")) {
                if (BlockState.CODEC.parse(NbtOps.INSTANCE, entry).result().isPresent()) {
                    fixed.add(entry);
                } else {
                    CompoundTag stone = new CompoundTag();
                    stone.putString("Name", "minecraft:stone");
                    fixed.add(stone);
                }
            }
            copy.put("palette", fixed);
            return blockCodec.parse(NbtOps.INSTANCE, copy).result();
        }

        /** createLookup before 26.3, createWorldLookup from it. */
        private static HolderLookup.Provider lookup() {
            try {
                for (String name : new String[] {"createWorldLookup", "createLookup"}) {
                    try {
                        return (HolderLookup.Provider) VanillaRegistries.class.getMethod(name).invoke(null);
                    } catch (NoSuchMethodException absent) {
                        // try the other name
                    }
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            throw new IllegalStateException("VanillaRegistries has neither createWorldLookup nor createLookup");
        }

        PalettedContainer<BlockState> randomBlocks(Random random) {
            PalettedContainer<BlockState> container = new PalettedContainer<>(air, blockStrategy);
            fill(random, container, 16, states, true);
            return container;
        }

        PalettedContainerRO<Holder<Biome>> randomBiomes(Random random) {
            PalettedContainer<Holder<Biome>> container = new PalettedContainer<>(plains, biomeStrategy);
            fill(random, container, 4, biomeHolders, false);
            return container;
        }

        private static <T> void fill(Random random, PalettedContainer<T> container, int edge, List<T> universe,
                boolean blocks) {
            int pattern = random.nextInt(9);
            // pool sizes reach the linear (<= 16), hash (<= 256) and global palettes
            int[] poolSizes = {1, 2, 3, 5, 9, 17, 40, 130, 300, 1500, 4096};
            int poolSize = Math.min(universe.size(), poolSizes[random.nextInt(poolSizes.length)]);
            if (!blocks) {
                poolSize = Math.min(poolSize, 1 + random.nextInt(Math.min(70, universe.size())));
            }
            List<T> pool = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                pool.add(universe.get(random.nextInt(universe.size())));
            }
            int cells = edge * edge * edge;
            switch (pattern) {
                case 0 -> { // uniform
                    T value = pool.get(0);
                    for (int i = 0; i < cells; i++) {
                        container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge), value);
                    }
                }
                case 1 -> { // runs of random length
                    int i = 0;
                    while (i < cells) {
                        T value = pool.get(random.nextInt(pool.size()));
                        int run = 1 + random.nextInt(50);
                        for (int end = Math.min(cells, i + run); i < end; i++) {
                            container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge), value);
                        }
                    }
                }
                case 2 -> { // alternating
                    for (int i = 0; i < cells; i++) {
                        container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge),
                                pool.get(i % pool.size()));
                    }
                }
                case 3 -> { // layered: one value per y, like terrain strata
                    for (int y = 0; y < edge; y++) {
                        T value = pool.get(random.nextInt(pool.size()));
                        for (int x = 0; x < edge; x++) {
                            for (int z = 0; z < edge; z++) {
                                container.set(x, y, z, value);
                            }
                        }
                    }
                }
                case 4 -> { // overwritten: the palette keeps the entries of the first fill
                    for (int i = 0; i < cells; i++) {
                        container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge),
                                pool.get(random.nextInt(pool.size())));
                    }
                    T value = pool.get(random.nextInt(pool.size()));
                    for (int i = 0; i < cells; i++) {
                        container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge), value);
                    }
                }
                case 5 -> { // a few scattered values in a uniform background (ore, water pockets)
                    T background = pool.get(0);
                    for (int i = 0; i < cells; i++) {
                        container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge), background);
                    }
                    for (int i = 0; i < Math.min(cells, 1 + random.nextInt(60)); i++) {
                        container.set(random.nextInt(edge), random.nextInt(edge), random.nextInt(edge),
                                pool.get(random.nextInt(pool.size())));
                    }
                }
                case 6 -> { // every cell different when the pool allows it
                    for (int i = 0; i < cells; i++) {
                        container.set(i & (edge - 1), (i / edge) & (edge - 1), i / (edge * edge),
                                pool.get(random.nextInt(pool.size())));
                    }
                }
                case 7 -> { // the empty container
                }
                default -> { // a handful of cells only
                    for (int i = 0; i < 1 + random.nextInt(8); i++) {
                        container.set(random.nextInt(edge), random.nextInt(edge), random.nextInt(edge),
                                pool.get(random.nextInt(pool.size())));
                    }
                }
            }
        }
    }

    // --- the helpers against vanilla's code -------------------------------------------------------

    private static void helperChecks(Factory factory) throws Exception {
        Class<?> memo;
        Class<?> repack;
        try {
            memo = Class.forName("dev.gaius.browser.BrowserPaletteEntryCodec");
            repack = Class.forName("dev.gaius.browser.BrowserPaletteRepack");
        } catch (ClassNotFoundException absent) {
            System.out.println("helpers: not on the class path, the digest comparison alone decides");
            return;
        }
        var wrap = memo.getMethod("wrap", Codec.class);
        var hits = memo.getMethod("hits");
        var reencode = repack.getMethod("reencode", BitStorage.class, Palette.class, Palette.class);

        // memo codec = the codec it wraps, for every entry, first and repeated, and non-NBT ops
        @SuppressWarnings("unchecked")
        Codec<BlockState> blocks = (Codec<BlockState>) wrap.invoke(null, BlockState.CODEC);
        long compared = 0;
        for (int round = 0; round < 3; round++) {
            for (BlockState state : factory.states) {
                Tag expected = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result().orElseThrow();
                DataResult<Tag> actualResult = blocks.encodeStart(NbtOps.INSTANCE, state);
                Tag actual = actualResult.result().orElseThrow();
                compared++;
                if (!expected.equals(actual) || !Arrays.equals(tagBytes(expected), tagBytes(actual))) {
                    failures.add("memo codec result differs for " + state + " round " + round);
                }
                if (actual instanceof CompoundTag compound) {
                    compound.putString("tampered", "yes"); // must not reach the memo
                }
            }
        }
        for (BlockState state : factory.states.subList(0, Math.min(500, factory.states.size()))) {
            if (!sameResult(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state), blocks.encodeStart(JsonOps.INSTANCE, state))) {
                failures.add("memo codec json result differs for " + state);
            }
            if (!sameResult(BlockState.CODEC.encode(state, NbtOps.INSTANCE, new CompoundTag()),
                    blocks.encode(state, NbtOps.INSTANCE, new CompoundTag()))) {
                failures.add("memo codec with a prefix differs for " + state);
            }
        }
        @SuppressWarnings("unchecked")
        Codec<Holder<Biome>> biomes = (Codec<Holder<Biome>>) wrap.invoke(null, factory.biomes.holderByNameCodec());
        for (int round = 0; round < 2; round++) {
            for (Holder<Biome> holder : factory.biomeHolders) {
                Tag expected = factory.biomes.holderByNameCodec().encodeStart(NbtOps.INSTANCE, holder).result().orElseThrow();
                Tag actual = biomes.encodeStart(NbtOps.INSTANCE, holder).result().orElseThrow();
                compared++;
                if (!expected.equals(actual)) {
                    failures.add("memo codec biome result differs for " + holder);
                }
            }
        }
        // the decode side is the delegate's
        Tag encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, Blocks.OAK_STAIRS.defaultBlockState()).result().orElseThrow();
        if (!BlockState.CODEC.parse(NbtOps.INSTANCE, encoded).equals(blocks.parse(NbtOps.INSTANCE, encoded))) {
            failures.add("memo codec decode differs");
        }
        long served = (long) hits.invoke(null);
        long unplanned = (long) memo.getMethod("unplanned").invoke(null);
        System.out.println("helpers: memo codec equal to its delegate in " + compared + " encodings (" + served
                + " served from the memo, " + unplanned + " entries not memoizable)");
        if (served == 0) {
            failures.add("the memo codec never served an encoding");
        }
        if (unplanned != 0) {
            failures.add(unplanned + " block state or biome encodings could not be rebuilt with their key order");
        }
        trees(wrap, hits, memo);

        // repack = vanilla's loop, with the same target palette afterwards
        Random random = new Random(0x9E3779B9L);
        long cases = 0;
        for (int bits = 0; bits <= 15; bits++) {
            for (int trial = 0; trial < 60; trial++) {
                int size = trial % 5 == 0 ? 64 : 4096;
                int maxDistinct = bits == 0 ? 1 : Math.min(1 << bits, bits > 8 ? 3000 : 1 << bits);
                int distinct = 1 + random.nextInt(maxDistinct);
                Palette<BlockState> source;
                BitStorage storage;
                int[] raw = new int[size];
                if (bits > 8) {
                    // global palette: ids are block state ids
                    source = new GlobalPalette<>(Block.BLOCK_STATE_REGISTRY);
                    int[] idPool = new int[distinct];
                    for (int i = 0; i < distinct; i++) {
                        idPool[i] = random.nextInt(Block.BLOCK_STATE_REGISTRY.size());
                    }
                    fillRuns(random, raw, idPool);
                    storage = new SimpleBitStorage(bits, size, raw.clone());
                } else {
                    HashMapPalette<BlockState> hash = new HashMapPalette<>(Math.max(bits, 1));
                    PaletteResize<BlockState> none = (b, v) -> {
                        throw new IllegalStateException("resize");
                    };
                    int entries = bits == 0 ? 1 : Math.min(1 << bits, 1 + distinct);
                    int[] idPool = new int[Math.min(distinct, entries)];
                    for (int i = 0; i < entries; i++) {
                        int id = hash.idFor(factory.states.get(random.nextInt(factory.states.size())), none);
                        if (i < idPool.length) {
                            idPool[i] = id;
                        }
                    }
                    for (int i = 0; i < idPool.length; i++) {
                        idPool[i] = idPool[i] % Math.max(1, hash.getSize());
                    }
                    fillRuns(random, raw, idPool);
                    storage = bits == 0 ? new ZeroBitStorage(size) : new SimpleBitStorage(bits, size, raw.clone());
                    if (bits == 0) {
                        Arrays.fill(raw, 0);
                    }
                    source = hash;
                }
                int targetBits = Math.max(1, Math.min(15, bits + random.nextInt(2)));
                HashMapPalette<BlockState> expectedTarget = new HashMapPalette<>(targetBits);
                HashMapPalette<BlockState> actualTarget = new HashMapPalette<>(targetBits);
                int[] expected;
                int[] actual;
                String expectedFailure = null;
                String actualFailure = null;
                try {
                    expected = vanillaReencode(storage, source, expectedTarget);
                } catch (RuntimeException failure) {
                    expected = null;
                    expectedFailure = failure.getClass().getName();
                }
                try {
                    @SuppressWarnings("unchecked")
                    int[] result = (int[]) reencode.invoke(null, storage, source, actualTarget);
                    actual = result;
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    actual = null;
                    actualFailure = failure.getCause().getClass().getName();
                }
                cases++;
                if (!java.util.Objects.equals(expectedFailure, actualFailure)) {
                    failures.add("repack failure differs at bits=" + bits + " trial=" + trial + ": " + expectedFailure
                            + " vs " + actualFailure);
                } else if (expected != null && !Arrays.equals(expected, actual)) {
                    failures.add("repack ids differ at bits=" + bits + " trial=" + trial);
                }
                if (!expectedTarget.getEntries().equals(actualTarget.getEntries())) {
                    failures.add("repack target palette differs at bits=" + bits + " trial=" + trial);
                }
            }
        }
        System.out.println("helpers: repack equal to vanilla's loop in " + cases + " storages (bits 0..15)");
    }

    /** Seeded nested tags through the memo codec: same bytes every time; unreproducible key orders are not memoized. */
    @SuppressWarnings("unchecked")
    private static void trees(java.lang.reflect.Method wrap, java.lang.reflect.Method hits, Class<?> memo) throws Exception {
        Codec<Integer> random = new Codec<>() {
            @Override
            public <U> DataResult<com.mojang.datafixers.util.Pair<Integer, U>> decode(com.mojang.serialization.DynamicOps<U> ops, U input) {
                return DataResult.error(() -> "not decodable");
            }

            @Override
            public <U> DataResult<U> encode(Integer seed, com.mojang.serialization.DynamicOps<U> ops, U prefix) {
                return DataResult.success((U) randomTree(new Random(seed), 0, true));
            }
        };
        Codec<Integer> wrapped = (Codec<Integer>) wrap.invoke(null, random);
        long before = (long) hits.invoke(null);
        long beforeMisses = (long) memo.getMethod("misses").invoke(null);
        int checked = 0;
        Integer[] seeds = new Integer[1500];
        for (int i = 0; i < seeds.length; i++) {
            seeds[i] = i; // distinct objects: the memo is keyed by identity
        }
        for (int round = 0; round < 3; round++) {
            for (Integer seed : seeds) {
                byte[] expected;
                byte[] actual;
                try {
                    expected = tagBytes(random.encodeStart(NbtOps.INSTANCE, seed).result().orElseThrow());
                    actual = tagBytes(wrapped.encodeStart(NbtOps.INSTANCE, seed).result().orElseThrow());
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                checked++;
                if (!Arrays.equals(expected, actual)) {
                    failures.add("memo codec tree bytes differ for seed " + seed + " round " + round);
                }
            }
        }
        long served = (long) hits.invoke(null) - before;
        long ran = (long) memo.getMethod("misses").invoke(null) - beforeMisses;
        System.out.println("helpers: memo codec rebuilt " + checked + " seeded trees byte for byte (" + served
                + " from the memo, " + ran + " through the delegate)");
        if (served == 0) {
            failures.add("the memo codec rebuilt no seeded tree");
        }

        // a compound whose key order no insertion order of a hash map gives back must not be memoized
        Codec<Integer> crafted = new Codec<>() {
            @Override
            public <U> DataResult<com.mojang.datafixers.util.Pair<Integer, U>> decode(com.mojang.serialization.DynamicOps<U> ops, U input) {
                return DataResult.error(() -> "not decodable");
            }

            @Override
            public <U> DataResult<U> encode(Integer seed, com.mojang.serialization.DynamicOps<U> ops, U prefix) {
                try {
                    var constructor = CompoundTag.class.getDeclaredConstructor(java.util.Map.class);
                    constructor.setAccessible(true);
                    java.util.LinkedHashMap<String, Tag> fixed = new java.util.LinkedHashMap<>();
                    // JDK iteration order of these keys is by bucket; this order is the opposite
                    List<String> keys = new ArrayList<>(List.of("a", "b", "c", "d", "e"));
                    keys.sort((x, y) -> Integer.compare(bucket(y), bucket(x)));
                    for (String key : keys) {
                        fixed.put(key, net.minecraft.nbt.StringTag.valueOf(key));
                    }
                    return DataResult.success((U) constructor.newInstance(fixed));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        Codec<Integer> guarded = (Codec<Integer>) wrap.invoke(null, crafted);
        long unplannedBefore = (long) memo.getMethod("unplanned").invoke(null);
        long servedBefore = (long) hits.invoke(null);
        Integer key = 7;
        for (int round = 0; round < 3; round++) {
            Tag expected = crafted.encodeStart(NbtOps.INSTANCE, key).result().orElseThrow();
            Tag actual = guarded.encodeStart(NbtOps.INSTANCE, key).result().orElseThrow();
            if (!expected.equals(actual) || !new ArrayList<>(((CompoundTag) expected).keySet())
                    .equals(new ArrayList<>(((CompoundTag) actual).keySet()))) {
                failures.add("memo codec changed a compound whose key order cannot be rebuilt");
            }
        }
        if ((long) memo.getMethod("unplanned").invoke(null) - unplannedBefore != 1 || (long) hits.invoke(null) != servedBefore) {
            failures.add("a compound with an unreproducible key order was memoized");
        }
        System.out.println("helpers: a compound with an unreproducible key order is left to the delegate");
    }

    private static int bucket(String key) {
        int hash = key.hashCode();
        return (hash ^ (hash >>> 16)) & 15;
    }

    /** Nested compound/list/array/primitive tree from a seed: the shapes a palette entry or a palette could have. */
    private static Tag randomTree(Random random, int depth, boolean root) {
        int kind = root ? 0 : depth > 3 ? 6 + random.nextInt(2) : random.nextInt(8);
        switch (kind) {
            case 0, 1 -> {
                CompoundTag compound = new CompoundTag();
                int keys = random.nextInt(14); // past the first resize of the hash map too
                for (int i = 0; i < keys; i++) {
                    String name = random.nextBoolean() ? "key" + random.nextInt(40) : Long.toString(random.nextLong(), 36);
                    compound.put(name, randomTree(random, depth + 1, false));
                }
                return compound;
            }
            case 2 -> {
                ListTag list = new ListTag();
                int items = random.nextInt(5);
                boolean compounds = random.nextBoolean();
                for (int i = 0; i < items; i++) {
                    list.add(compounds && depth < 3 ? randomTree(random, depth + 1, true)
                            : net.minecraft.nbt.StringTag.valueOf("s" + random.nextInt(9)));
                }
                return list;
            }
            case 3 -> {
                return new net.minecraft.nbt.LongArrayTag(new long[] {random.nextLong(), random.nextLong()});
            }
            case 4 -> {
                return new net.minecraft.nbt.IntArrayTag(new int[] {random.nextInt(), random.nextInt()});
            }
            case 5 -> {
                return new net.minecraft.nbt.ByteArrayTag(new byte[] {(byte) random.nextInt(), 1});
            }
            case 6 -> {
                return net.minecraft.nbt.IntTag.valueOf(random.nextInt());
            }
            default -> {
                return net.minecraft.nbt.StringTag.valueOf("v" + random.nextInt(1000));
            }
        }
    }

    /** Equal results; two errors are equal when their messages are. */
    private static <T> boolean sameResult(DataResult<T> expected, DataResult<T> actual) {
        if (expected.result().isPresent() != actual.result().isPresent()) {
            return false;
        }
        if (expected.result().isPresent()) {
            return expected.result().get().equals(actual.result().get());
        }
        return expected.error().map(error -> error.message()).equals(actual.error().map(error -> error.message()));
    }

    private static void fillRuns(Random random, int[] raw, int[] idPool) {
        int i = 0;
        int mode = random.nextInt(3);
        while (i < raw.length) {
            int id = idPool[random.nextInt(idPool.length)];
            int run = mode == 0 ? 1 : 1 + random.nextInt(mode == 1 ? 6 : 200);
            for (int end = Math.min(raw.length, i + run); i < end; i++) {
                raw[i] = id;
            }
        }
    }

    /** reencodeContents of the vanilla jar, copied. */
    private static <T> int[] vanillaReencode(BitStorage storage, Palette<T> source, Palette<T> target) {
        int[] ids = new int[storage.getSize()];
        storage.unpack(ids);
        PaletteResize<T> resize = PaletteResize.noResizeExpected();
        int last = -1;
        int lastId = -1;
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (id != last) {
                last = id;
                T value = source.valueFor(id);
                lastId = target.idFor(value, resize);
            }
            ids[i] = lastId;
        }
        return ids;
    }

    // --- timing --------------------------------------------------------------------------------------

    private static void bench(String label, Factory factory, List<PalettedContainer<BlockState>> blocks,
            List<PalettedContainerRO<Holder<Biome>>> biomes, int sections, int runs) throws IOException {
        // the corpus part only: the synthetic containers sit at the end of both lists
        int corpusBlocks = blocks.size() - 4000;
        int corpusBiomes = biomes.size() - 4000;
        double[] millis = new double[runs];
        for (int run = -2; run < runs; run++) {
            long start = System.nanoTime();
            long bytes = 0;
            for (int i = 0; i < corpusBlocks; i++) {
                bytes += factory.blockCodec.encodeStart(NbtOps.INSTANCE, blocks.get(i)).result().orElseThrow().hashCode();
            }
            for (int i = 0; i < corpusBiomes; i++) {
                bytes += factory.biomeCodec.encodeStart(NbtOps.INSTANCE, biomes.get(i)).result().orElseThrow().hashCode();
            }
            if (run >= 0) {
                millis[run] = (System.nanoTime() - start) / 1e6;
            }
            if (bytes == 42) {
                System.out.print("");
            }
        }
        Arrays.sort(millis);
        System.out.printf("%s bench: %d block + %d biome containers (%d sections) encoded in %.1f ms (best of %d, median %.1f)%n",
                label, corpusBlocks, corpusBiomes, sections, millis[0], runs, millis[runs / 2]);
    }

    // --- NBT bytes -----------------------------------------------------------------------------------

    private static byte[] encode(Codec<?> codec, Object container) throws IOException {
        @SuppressWarnings("unchecked")
        Codec<Object> raw = (Codec<Object>) codec;
        Tag tag = raw.encodeStart(NbtOps.INSTANCE, container).result().orElseThrow();
        return tagBytes(tag);
    }

    private static byte[] tagBytes(Tag tag) throws IOException {
        if (tag instanceof CompoundTag compound) {
            return nbtBytes(compound);
        }
        CompoundTag holder = new CompoundTag();
        holder.put("v", tag);
        return nbtBytes(holder);
    }

    private static byte[] nbtBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(buffer));
        return buffer.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
    }

    // --- region files --------------------------------------------------------------------------------

    private static int readRegion(Path file, List<byte[]> chunks) throws IOException {
        byte[] data = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(data);
        int count = 0;
        for (int slot = 0; slot < 1024; slot++) {
            int entry = header.getInt(slot * 4);
            int sector = entry >>> 8;
            if (sector == 0 || (long) sector * 4096 + 5 > data.length) {
                continue;
            }
            int length = header.getInt(sector * 4096);
            int compression = data[sector * 4096 + 4] & 0xFF;
            if (length < 2 || (long) sector * 4096 + 4 + length > data.length) {
                continue;
            }
            InputStream raw = new ByteArrayInputStream(data, sector * 4096 + 5, length - 1);
            byte[] payload;
            switch (compression) {
                case 1 -> payload = new GZIPInputStream(raw).readAllBytes();
                case 2 -> payload = new InflaterInputStream(raw).readAllBytes();
                case 3 -> payload = raw.readAllBytes();
                default -> {
                    continue;
                }
            }
            chunks.add(payload);
            count++;
        }
        return count;
    }
}
