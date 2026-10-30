package dev.gaius.parity;

import com.mojang.datafixers.util.Pair;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * JVM side of the worldgen seed-parity harness (migration plan P6, acceptance "seed parity")
 * for every supported profile: the vanilla APIs that differ between Minecraft versions go
 * through {@code ParityCompat}, compiled from compat/&lt;profile&gt;/ParityCompat.java (TERRAIN
 * below is CARVERS before 26.3, the origin the climate sampler's spawn position, and the 1.21.11
 * run uses the remapped client jar). Creates a fresh default ("minecraft:normal", structures
 * on, no bonus chest, vanilla data pack only) overworld for one seed in-process, the way the vanilla game-test
 * server builds its world, and hashes a square of chunks centred on the spawn chunk:
 * <ul>
 *   <li>{@code terrain} mode marks the world as initialized so setInitialSpawn does not
 *       generate the spawn area, asks for every chunk only up to ChunkStatus.TERRAIN (noise,
 *       surface rules, aquifers, carvers) and hashes block states and biomes right away. No
 *       feature has run yet, so these hashes do not depend on generation order.</li>
 *   <li>{@code full} mode runs the vanilla setInitialSpawn, records the world spawn, generates
 *       the chunks to FULL and hashes block states and biomes. Features may write across chunk
 *       borders, so a full-hash difference needs a per-block look before it is called a
 *       worldgen difference.</li>
 * </ul>
 * The centre is {@code ChunkGenerator.getOrigin(RandomState)} (before 26.3 the climate
 * sampler's {@code findSpawnPosition}), the spawn chunk vanilla and the browser's fast initial
 * spawn both start from. {@code spawn} is the world spawn the
 * harness server wrote: the vanilla spawn search in full mode (on the patched worldgen classes
 * in a --patched run), the level default in terrain mode. {@code browserSpawn} (--patched runs
 * only) is the spawn the browser's own setInitialSpawn writes, run by {@code BrowserSpawnProbe}.
 * {@code --positions <file>} (one {@code x,z} chunk per line) replaces the square.
 *
 * <p>{@code saved} mode generates nothing of its own first: it hashes, with the same
 * functions, every chunk stored in the region files of {@code --saved <region dir>} (a world a
 * browser build generated and saved, see browser-world.mjs) and records each chunk's persisted
 * status, so a chunk the browser left at TERRAIN can be compared with a terrain-mode chunk of
 * this harness and the biomes of every chunk at BIOMES or later with the same position here.
 * It then compares the unticked saved chunks block by block with the same area generated here
 * ({@code blockDiff}, see diffBlocks). {@code --level-dat <file>} adds that world's saved spawn
 * ({@code savedSpawn}). {@code capture} mode is the vanilla control for it: it loads a square
 * of chunks to FULL from the centre outwards and writes every chunk in memory to the region
 * files of {@code --capture <region dir>}.
 *
 * <p>usage: {@code WorldgenSeedParity --seed <long> --mode terrain|full|saved|capture
 * [--side <chunks>] [--positions <file>] [--saved <region dir> [--level-dat <file>]]
 * [--capture <region dir>] --universe <dir> --out <json> [--label <text>]}. Runs with the
 * vanilla client (or server) jar and its libraries on the class path; with the patched
 * worldgen classes and the JVM helper shims first on the class path it checks the browser
 * patches instead.
 */
public final class WorldgenSeedParity {
    private WorldgenSeedParity() {
    }

    record Options(long seed, String mode, int side, Path universe, Path out, String label,
            List<ChunkPos> positions, Path saved, Path levelDat, Path capture, String order) {
        static Options parse(String[] args) throws IOException {
            Long seed = null;
            String mode = null;
            int side = 8;
            Path universe = null;
            Path out = null;
            String label = "";
            List<ChunkPos> positions = null;
            Path saved = null;
            Path levelDat = null;
            Path capture = null;
            String order = "spiral";
            for (int index = 0; index < args.length; index++) {
                String value = index + 1 < args.length ? args[index + 1] : null;
                switch (args[index]) {
                    case "--seed" -> seed = Long.parseLong(require(value, "--seed"));
                    case "--mode" -> mode = require(value, "--mode");
                    case "--side" -> side = Integer.parseInt(require(value, "--side"));
                    case "--universe" -> universe = Path.of(require(value, "--universe"));
                    case "--out" -> out = Path.of(require(value, "--out"));
                    case "--label" -> label = require(value, "--label");
                    case "--positions" -> positions = readPositions(Path.of(require(value, "--positions")));
                    case "--saved" -> saved = Path.of(require(value, "--saved"));
                    case "--level-dat" -> levelDat = Path.of(require(value, "--level-dat"));
                    case "--capture" -> capture = Path.of(require(value, "--capture"));
                    case "--order" -> order = require(value, "--order");
                    default -> throw new IllegalArgumentException("unknown argument " + args[index]);
                }
                index++;
            }
            boolean generating = "terrain".equals(mode) || "full".equals(mode);
            if (seed == null || universe == null || out == null
                    || !(generating || "saved".equals(mode) || "capture".equals(mode)
                            || "light-fixtures".equals(mode)) || side < 1 || side > 32
                    || ("saved".equals(mode) != (saved != null)) || ("capture".equals(mode) != (capture != null))
                    || (positions != null && (!generating && !"capture".equals(mode) && !"saved".equals(mode)
                            || positions.isEmpty() || positions.size() > 4096))
                    || !("spiral".equals(order) || "shuffle".equals(order))) {
                throw new IllegalArgumentException("usage: WorldgenSeedParity --seed <long> "
                        + "--mode terrain|full|saved|capture|light-fixtures [--side 1..32] [--positions <file of x,z lines, "
                        + "terrain|full>] [--saved <region dir>, saved mode] [--level-dat <file>] "
                        + "[--capture <region dir> [--order spiral|shuffle], capture mode] --universe <dir> "
                        + "--out <json> [--label <text>]");
            }
            return new Options(seed, mode, side, universe, out, label, positions, saved, levelDat, capture, order);
        }

        private static List<ChunkPos> readPositions(Path file) throws IOException {
            List<ChunkPos> positions = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split(",");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("bad chunk position line: " + line);
                }
                positions.add(new ChunkPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())));
            }
            return positions;
        }

        private static String require(String value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " needs a value");
            }
            return value;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--strip-signature")) {
            stripSignature(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        // Before the options: 1.21.11's ChunkPos class initializer (ChunkPyramid, ChunkStatus) needs
        // the bootstrapped registries, and --positions builds ChunkPos values.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Options options = Options.parse(args);
        Files.createDirectories(options.universe());
        String worldName = "parity-" + options.mode() + "-" + Long.toUnsignedString(options.seed());
        deleteRecursively(options.universe().resolve(worldName));
        LevelStorageSource source = LevelStorageSource.createDefault(options.universe());
        LevelStorageSource.LevelStorageAccess access = source.createAccess(worldName);
        PackRepository packs = ServerPacksSource.createPackRepository(access);
        long started = System.nanoTime();
        ParityServer server = MinecraftServer.spin(thread -> ParityServer.create(thread, access, packs, options));
        while (server.isRunning() || !server.finished) {
            Thread.sleep(100);
            if (server.failure != null) {
                break;
            }
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (server.failure != null) {
            server.failure.printStackTrace();
            System.out.println("WORLDGEN_SEED_PARITY_FAIL " + server.failure);
            System.exit(1);
        }
        System.out.println("WORLDGEN_SEED_PARITY_OK mode=" + options.mode() + " seed="
                + options.seed() + " chunks=" + server.chunkCount + " millis=" + millis
                + " aggregate=" + server.aggregate);
        System.exit(0);
    }

    /**
     * Copies a signed jar without its signature files. Patched worldgen classes can only
     * replace classes of the vanilla jar on the class path when the jar is unsigned (a signed
     * package rejects classes from another code source).
     */
    static void stripSignature(Path input, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(input));
                var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(output))) {
            java.util.zip.ZipEntry entry;
            int removed = 0;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                        || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
                    removed++;
                    continue;
                }
                out.putNextEntry(new java.util.zip.ZipEntry(name));
                in.transferTo(out);
                out.closeEntry();
            }
            System.out.println("WORLDGEN_SEED_PARITY_STRIPPED " + output + " signatureFiles=" + removed);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    /**
     * A headless server in the style of vanilla's GameTestServer, for one seed (the version
     * dependent boilerplate is ParityCompat.ServerBase).
     */
    static final class ParityServer extends ParityCompat.ServerBase {
        private final Options options;
        private boolean generated;
        volatile boolean finished;
        volatile Throwable failure;
        volatile int chunkCount;
        volatile String aggregate = "";

        private ParityServer(Thread thread, LevelStorageSource.LevelStorageAccess access,
                PackRepository packs, WorldStem stem, Options options) {
            super(thread, access, packs, stem);
            this.options = options;
        }

        static ParityServer create(Thread thread, LevelStorageSource.LevelStorageAccess access,
                PackRepository packs, Options options) {
            // Survival, normal difficulty, no commands; terrain mode skips setInitialSpawn (it
            // would generate the spawn area to FULL).
            WorldStem stem = ParityCompat.loadStem(packs, options.seed(), GameType.SURVIVAL, Difficulty.NORMAL,
                    false, !"full".equals(options.mode()));
            return new ParityServer(thread, access, packs, stem, options);
        }

        @Override
        protected void parityTick(BooleanSupplier haveTime) {
            if (generated) {
                vanillaTick(haveTime);
                return;
            }
            // Hash before the first level tick: scheduled fluid and block ticks of the chunks
            // generated so far would otherwise change blocks between runs.
            generated = true;
            try {
                generateAndHash();
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                finished = true;
                halt(false);
            }
        }

        private void generateAndHash() throws IOException {
            ServerLevel level = overworld();
            ServerChunkCache chunks = level.getChunkSource();
            ChunkPos origin = ParityCompat.origin(chunks);
            BlockPos spawn = ParityCompat.respawnPos(level);
            int side = options.side();
            List<String> chunkLines = new ArrayList<>();
            MessageDigest blocksAggregate = sha256();
            MessageDigest biomesAggregate = sha256();
            Map<BlockState, byte[]> stateNames = new IdentityHashMap<>();
            Map<Holder<Biome>, byte[]> biomeNames = new IdentityHashMap<>();
            long generationNanos = 0;
            String savedSpawn = "null";
            String blockDiff = "null";
            if ("light-fixtures".equals(options.mode())) {
                generationNanos = lightFixtures(level, chunkLines, blocksAggregate, biomesAggregate,
                        stateNames, biomeNames);
            } else if ("saved".equals(options.mode())) {
                Map<ChunkPos, ChunkStatus> statuses = new HashMap<>();
                Map<ChunkPos, LevelChunkSection[]> untickedChunks = new LinkedHashMap<>();
                Map<ChunkPos, LevelChunkSection[]> fullChunks = new LinkedHashMap<>();
                generationNanos = hashSaved(level, chunkLines, blocksAggregate, biomesAggregate,
                        stateNames, biomeNames, statuses, untickedChunks, fullChunks);
                if (options.levelDat() != null) {
                    savedSpawn = savedSpawn(options.levelDat());
                }
                biomeDifferences(level, untickedChunks, fullChunks);
                blockDiff = diffBlocks(chunks, origin, statuses, untickedChunks, fullChunks);
            } else if ("capture".equals(options.mode())) {
                generationNanos = capture(level, origin, chunkLines, blocksAggregate, biomesAggregate,
                        stateNames, biomeNames);
            } else {
                ChunkStatus status = "terrain".equals(options.mode()) ? ParityCompat.TERRAIN : ChunkStatus.FULL;
                List<ChunkPos> positions = options.positions();
                if (positions == null) {
                    positions = new ArrayList<>();
                    int minX = ParityCompat.x(origin) - side / 2;
                    int minZ = ParityCompat.z(origin) - side / 2;
                    for (int dz = 0; dz < side; dz++) {
                        for (int dx = 0; dx < side; dx++) {
                            positions.add(new ChunkPos(minX + dx, minZ + dz));
                        }
                    }
                }
                for (ChunkPos position : positions) {
                    int x = ParityCompat.x(position);
                    int z = ParityCompat.z(position);
                    long start = System.nanoTime();
                    ChunkAccess chunk = chunks.getChunk(x, z, status, true);
                    generationNanos += System.nanoTime() - start;
                    if (chunk == null) {
                        throw new IllegalStateException("chunk " + x + "," + z + " was not generated");
                    }
                    if (!chunk.getPersistedStatus().isOrAfter(status)) {
                        throw new IllegalStateException("chunk " + x + "," + z + " is only "
                                + chunk.getPersistedStatus());
                    }
                    Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                            Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
                    CompoundTag generatedTag = SerializableChunkData.copyOf(level, chunk).write();
                    addChunk(chunkLines, blocksAggregate, biomesAggregate, x, z,
                            chunk.getPersistedStatus().getName(),
                            hashBlocks(chunk.getSections(), stateNames),
                            hashBiomes(chunk.getSections(), biomeNames), null, null,
                            chunk.getHeightmaps().stream().collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, entry -> entry.getValue().getRawData())),
                            nbtHash(generatedTag, "block_entities"), nbtHashNested(generatedTag, "structures", "starts"));
                }
            }
            String blocks = HexFormat.of().formatHex(blocksAggregate.digest());
            String biomes = HexFormat.of().formatHex(biomesAggregate.digest());
            chunkCount = chunkLines.size();
            aggregate = "blocks:" + blocks.substring(0, 16) + ",biomes:" + biomes.substring(0, 16);
            // --patched runs also carry BrowserSpawnProbe: it runs the browser's own
            // setInitialSpawn (fast initial spawn) against recording proxies, after hashing.
            String browserSpawn = "null";
            try {
                Class<?> probe = Class.forName("dev.gaius.parity.BrowserSpawnProbe");
                browserSpawn = String.valueOf(probe.getMethod("run", ServerLevel.class).invoke(null, level));
            } catch (ClassNotFoundException expected) {
                // Vanilla run: the probe needs the patched jar.
            } catch (java.lang.reflect.InvocationTargetException exception) {
                throw new IllegalStateException("Browser spawn probe failed", exception.getCause());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Browser spawn probe failed", exception);
            }
            String pulses = "";
            try {
                Class<?> shim = Class.forName("dev.gaius.browser.BrowserWorldgenDeepCheckpoint");
                pulses = String.valueOf(shim.getMethod("parityReport").invoke(null));
            } catch (ClassNotFoundException expected) {
                // Vanilla run: no browser classes on the class path.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Pulse shim report failed", exception);
            }
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"tool\": \"worldgen-seed-parity\",\n");
            json.append("  \"format\": 1,\n");
            json.append("  \"label\": ").append(quote(options.label())).append(",\n");
            json.append("  \"minecraft\": ").append(quote(SharedConstants.getCurrentVersion().name())).append(",\n");
            json.append("  \"seed\": \"").append(options.seed()).append("\",\n");
            json.append("  \"mode\": \"").append(options.mode()).append("\",\n");
            json.append("  \"preset\": \"minecraft:normal\",\n");
            json.append("  \"side\": ").append(side).append(",\n");
            json.append(String.format(Locale.ROOT, "  \"origin\": {\"x\": %d, \"z\": %d},%n", ParityCompat.x(origin), ParityCompat.z(origin)));
            json.append(String.format(Locale.ROOT, "  \"spawn\": {\"x\": %d, \"y\": %d, \"z\": %d, \"vanillaSpawnSearch\": %s},%n",
                    spawn.getX(), spawn.getY(), spawn.getZ(), "full".equals(options.mode())));
            json.append("  \"browserSpawn\": ").append(browserSpawn).append(",\n");
            json.append("  \"savedSpawn\": ").append(savedSpawn).append(",\n");
            json.append("  \"blockDiff\": ").append(blockDiff).append(",\n");
            json.append("  \"aggregate\": {\"blocks\": \"").append(blocks).append("\", \"biomes\": \"")
                    .append(biomes).append("\"},\n");
            json.append("  \"generationMillis\": ").append(TimeUnit.NANOSECONDS.toMillis(generationNanos)).append(",\n");
            json.append("  \"pulses\": ").append(pulses.isEmpty() ? "null" : pulses).append(",\n");
            json.append("  \"chunks\": [\n");
            json.append(String.join(",\n", chunkLines));
            json.append("\n  ]\n}\n");
            Files.createDirectories(options.out().toAbsolutePath().getParent());
            Files.writeString(options.out(), json, StandardCharsets.UTF_8);
        }

        private static void addChunk(List<String> chunkLines, MessageDigest blocksAggregate,
                MessageDigest biomesAggregate, int x, int z, String status, String blocks, String biomes) {
            addChunk(chunkLines, blocksAggregate, biomesAggregate, x, z, status, blocks, biomes, null, null, null);
        }

        /**
         * As above, with the light hash of a saved chunk and the hash of the light vanilla computes
         * for the saved blocks (saved mode; null when the chunk is not lit).
         */
        private static void addChunk(List<String> chunkLines, MessageDigest blocksAggregate,
                MessageDigest biomesAggregate, int x, int z, String status, String blocks, String biomes,
                String light, String relight, Map<Heightmap.Types, long[]> heightmaps) {
            addChunk(chunkLines, blocksAggregate, biomesAggregate, x, z, status, blocks, biomes, light, relight,
                    heightmaps, null, null);
        }

        private static void addChunk(List<String> chunkLines, MessageDigest blocksAggregate,
                MessageDigest biomesAggregate, int x, int z, String status, String blocks, String biomes,
                String light, String relight, Map<Heightmap.Types, long[]> heightmaps,
                String blockEntities, String structures) {
            blocksAggregate.update(blocks.getBytes(StandardCharsets.US_ASCII));
            biomesAggregate.update(biomes.getBytes(StandardCharsets.US_ASCII));
            String heightmapJson = heightmaps == null ? "" : ", \"heightmaps\": " + heightmapHashes(heightmaps);
            String nbtHashes = blockEntities == null && structures == null ? "" : String.format(Locale.ROOT,
                    ", \"blockEntities\": %s, \"structures\": %s", blockEntities == null ? "null" : quote(blockEntities),
                    structures == null ? "null" : quote(structures));
            chunkLines.add(String.format(Locale.ROOT,
                    "    {\"x\": %d, \"z\": %d, \"status\": \"%s\", \"blocks\": \"%s\", \"biomes\": \"%s\"%s%s%s%s}",
                    x, z, status, blocks, biomes, light == null ? "" : ", \"light\": \"" + light + "\"",
                    relight == null ? "" : ", \"relight\": \"" + relight + "\"", heightmapJson, nbtHashes));
        }

        private static String nbtHash(CompoundTag tag, String key) {
            return tag.contains(key) ? sha256Text(tag.get(key).toString()) : sha256Text("missing");
        }

        private static String nbtHashNested(CompoundTag tag, String parent, String child) {
            if (!tag.contains(parent) || !(tag.get(parent) instanceof CompoundTag compound) || !compound.contains(child)) {
                return sha256Text("missing");
            }
            return sha256Text(compound.get(child).toString());
        }

        private static String sha256Text(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private static void addChunk(List<String> chunkLines, MessageDigest blocksAggregate,
                MessageDigest biomesAggregate, int x, int z, String status, String blocks, String biomes,
                String light, String relight) {
            addChunk(chunkLines, blocksAggregate, biomesAggregate, x, z, status, blocks, biomes, light, relight, null);
        }

        private static String heightmapHashes(Map<Heightmap.Types, long[]> heightmaps) {
            return heightmaps.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(Enum::name)))
                    .map(entry -> quote(entry.getKey().name()) + ": " + quote(sha256Longs(entry.getValue())))
                    .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
        }

        private static String sha256Longs(long[] values) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES);
                for (long value : values) {
                    bytes.clear();
                    bytes.putLong(value);
                    digest.update(bytes.array());
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        /**
         * "&lt;block&gt;:&lt;sky&gt;": SHA-256 over the effective block light, and over the
         * effective sky light, of a saved chunk's level sections, bottom to top, 4096 levels per
         * section in y, z, x order. A section without stored
         * block light is dark; a section without stored sky light takes, per column, the bottom
         * row of the nearest stored sky layer above it, or 15 when none is stored above, which is
         * how the vanilla light storage answers for it. So the hash does not depend on which
         * all-dark or uniform layers a build chose to store, only on the light a game reads.
         * The hash is "none" when the save stores no light layer at all although the chunk is
         * marked lit (the light engine no longer held the column when the chunk was written; such a
         * chunk cannot be compared).
         */
        private static String hashLight(ServerLevel level, List<SerializableChunkData.SectionData> sectionData) {
            Map<Integer, net.minecraft.world.level.chunk.DataLayer> block = new HashMap<>();
            Map<Integer, net.minecraft.world.level.chunk.DataLayer> sky = new HashMap<>();
            for (SerializableChunkData.SectionData section : sectionData) {
                if (section.blockLight() != null) {
                    block.put(section.y(), section.blockLight());
                }
                if (section.skyLight() != null) {
                    sky.put(section.y(), section.skyLight());
                }
            }
            if (block.isEmpty() && sky.isEmpty()) {
                return "none";
            }
            return hashLight(level, block, sky);
        }

        /** hashLight over stored block and sky layers of the light sections, keyed by section y. */
        private static String hashLight(ServerLevel level, Map<Integer, net.minecraft.world.level.chunk.DataLayer> block,
                Map<Integer, net.minecraft.world.level.chunk.DataLayer> sky) {
            int minSection = level.getMinSectionY();
            int count = level.getSectionsCount();
            // Stored layers of the light sections: one below and one above the level sections.
            java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> blockLayers = new java.util.TreeMap<>(block);
            java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> skyLayers = new java.util.TreeMap<>(sky);
            MessageDigest digest = sha256();
            MessageDigest skyDigest = sha256();
            byte[] levels = new byte[4096];
            for (int index = 0; index < count; index++) {
                int sectionY = minSection + index;
                net.minecraft.world.level.chunk.DataLayer blockLayer = blockLayers.get(sectionY);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            levels[(y << 8) | (z << 4) | x] = (byte) (blockLayer == null ? 0 : blockLayer.get(x, y, z));
                        }
                    }
                }
                digest.update(levels);
                net.minecraft.world.level.chunk.DataLayer skyLayer = skyLayers.get(sectionY);
                Map.Entry<Integer, net.minecraft.world.level.chunk.DataLayer> above =
                        skyLayer == null ? skyLayers.higherEntry(sectionY) : null;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int value;
                            if (skyLayer != null) {
                                value = skyLayer.get(x, y, z);
                            } else if (above != null) {
                                value = above.getValue().get(x, 0, z);
                            } else {
                                value = 15;
                            }
                            levels[(y << 8) | (z << 4) | x] = (byte) value;
                        }
                    }
                }
                skyDigest.update(levels);
            }
            return HexFormat.of().formatHex(digest.digest()) + ":" + HexFormat.of().formatHex(skyDigest.digest());
        }

        /**
         * Hashes every chunk of the region files in {@code --saved} without generating anything:
         * the chunk NBT is parsed with the vanilla codecs (no data fixing; the save is from the
         * same game version) and its sections are laid out like {@code ChunkAccess.getSections()}.
         * A section the save does not contain counts as empty (air, the default biome), as in a
         * vanilla load. Chunks are taken in region file name order, then z, then x.
         */
        private long hashSaved(ServerLevel level, List<String> chunkLines, MessageDigest blocksAggregate,
                MessageDigest biomesAggregate, Map<BlockState, byte[]> stateNames,
                Map<Holder<Biome>, byte[]> biomeNames, Map<ChunkPos, ChunkStatus> statuses,
                Map<ChunkPos, LevelChunkSection[]> untickedChunks, Map<ChunkPos, LevelChunkSection[]> fullChunks)
                throws IOException {
            Path directory = options.saved();
            List<Path> regions;
            try (var list = Files.list(directory)) {
                regions = list.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                        .sorted().toList();
            }
            if (regions.isEmpty()) {
                throw new IllegalStateException("no region files in " + directory);
            }
            PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
            long start = System.nanoTime();
            int missingSections = 0;
            record Parsed(ChunkPos position, ChunkStatus status, LevelChunkSection[] sections, String light,
                    Map<Heightmap.Types, long[]> heightmaps,
                    List<SerializableChunkData.SectionData> sectionData, String blockEntities, String structures) {
            }
            List<Parsed> parsed = new ArrayList<>();
            try (RegionFileStorage storage = ParityCompat.regionStorage("saved", directory)) {
                for (Path region : regions) {
                    String[] parts = region.getFileName().toString().split("\\.");
                    int regionX = Integer.parseInt(parts[1]);
                    int regionZ = Integer.parseInt(parts[2]);
                    for (int localZ = 0; localZ < 32; localZ++) {
                        for (int localX = 0; localX < 32; localX++) {
                            ChunkPos position = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
                            if (options.positions() != null && !options.positions().contains(position)) {
                                continue;
                            }
                            CompoundTag tag = storage.read(position);
                            if (tag == null) {
                                continue;
                            }
                            SerializableChunkData data = SerializableChunkData.parse(level, factory, tag);
                            if (!data.chunkPos().equals(position)) {
                                throw new IllegalStateException("region " + region.getFileName() + " holds chunk "
                                        + data.chunkPos() + " at " + position);
                            }
                            LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
                            for (SerializableChunkData.SectionData section : data.sectionData()) {
                                int index = level.getSectionIndexFromSectionY(section.y());
                                if (section.chunkSection() != null && index >= 0 && index < sections.length) {
                                    sections[index] = section.chunkSection();
                                }
                            }
                            for (int index = 0; index < sections.length; index++) {
                                if (sections[index] == null) {
                                    sections[index] = new LevelChunkSection(factory);
                                    missingSections++;
                                }
                            }
                            parsed.add(new Parsed(position, data.chunkStatus(), sections,
                                    data.lightCorrect() ? hashLight(level, data.sectionData()) : null,
                                    data.heightmaps(), data.sectionData(), nbtHash(tag, "block_entities"),
                                    nbtHashNested(tag, "structures", "starts")));
                            statuses.put(position, data.chunkStatus());
                            if (data.chunkStatus().isOrAfter(ChunkStatus.FULL)) {
                                fullChunks.put(position, sections);
                            } else if (data.chunkStatus().isOrAfter(ParityCompat.TERRAIN)) {
                                untickedChunks.put(position, sections);
                            }
                        }
                    }
                }
            }
            Map<ChunkPos, LevelChunkSection[]> relightInput = new LinkedHashMap<>();
            for (Parsed chunk : parsed) {
                if (chunk.status().isOrAfter(ChunkStatus.FEATURES)) {
                    relightInput.put(chunk.position(), chunk.sections());
                }
            }
            Map<ChunkPos, Map<Integer, net.minecraft.world.level.chunk.DataLayer[]>> relitLayers = new HashMap<>();
            Map<ChunkPos, String> relit = relight(level, factory, relightInput, relitLayers);
            String fixtures = System.getenv("GAIUS_PARITY_LIGHT_FIXTURES");
            if (fixtures != null && !fixtures.isBlank()) {
                LightFixtureWriter.write(Path.of(fixtures).resolve(options.label())
                        .resolve(Long.toString(options.seed())), level, relightInput, relitLayers,
                        options.seed(), "saved-columns");
            }
            String debug = System.getenv("GAIUS_PARITY_LIGHT_DEBUG");
            if (debug != null && !debug.isBlank()) {
                for (Parsed chunk : parsed) {
                    String key = ParityCompat.x(chunk.position()) + "," + ParityCompat.z(chunk.position());
                    if (!java.util.Arrays.asList(debug.split(";")).contains(key) || !relitLayers.containsKey(chunk.position())) {
                        continue;
                    }
                    lightDifferences(level, key, chunk.sectionData(), relitLayers.get(chunk.position()));
                }
            }
            for (Parsed chunk : parsed) {
                addChunk(chunkLines, blocksAggregate, biomesAggregate, ParityCompat.x(chunk.position()),
                        ParityCompat.z(chunk.position()), chunk.status().getName(),
                        hashBlocks(chunk.sections(), stateNames), hashBiomes(chunk.sections(), biomeNames),
                        chunk.light(), chunk.light() == null ? null : relit.get(chunk.position()), chunk.heightmaps(),
                        chunk.blockEntities(), chunk.structures());
            }
            System.out.println("WORLDGEN_SEED_PARITY_SAVED regions=" + regions.size() + " chunks="
                    + chunkLines.size() + " missingSections=" + missingSections + " relit=" + relit.size());
            return System.nanoTime() - start;
        }

        /**
         * The vanilla light of saved blocks: every chunk of {@code input} (the saved chunks at
         * FEATURES or later) becomes a ProtoChunk with the saved sections, and one vanilla
         * LevelLightEngine over them all does what ThreadedLevelLightEngine does for a new chunk
         * (initializeLight: section status, light disabled, data not retained; lightChunk:
         * propagateLightSources), then runs every update. The returned effective-light hash of a
         * chunk (hashLight) is the light vanilla computes for exactly these blocks; it is complete
         * where the chunk's eight neighbours are in the input too (no light travels farther than
         * 15 blocks), which is where worldgen-seed-parity.mjs compares it with the saved light.
         */
        private long lightFixtures(ServerLevel level, List<String> lines, MessageDigest blocks,
                MessageDigest biomes, Map<BlockState, byte[]> stateNames,
                Map<Holder<Biome>, byte[]> biomeNames) throws IOException {
            String destination = System.getenv("GAIUS_PARITY_LIGHT_FIXTURES");
            if (destination == null || destination.isBlank()) {
                throw new IOException("light-fixtures requires GAIUS_PARITY_LIGHT_FIXTURES");
            }
            long start = System.nanoTime();
            PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
            Map<ChunkPos, LevelChunkSection[]> input = new LinkedHashMap<>();
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                for (int chunkX = -1; chunkX <= 1; chunkX++) {
                    LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
                    for (int index = 0; index < sections.length; index++) {
                        sections[index] = new LevelChunkSection(factory);
                    }
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            int worldX = chunkX * 16 + localX;
                            int worldZ = chunkZ * 16 + localZ;
                            sections[level.getSectionIndex(64)].setBlockState(localX, 0, localZ,
                                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                            if (worldX >= 0 && worldX < 24 && worldZ >= 0 && worldZ < 24) {
                                sections[level.getSectionIndex(80)].setBlockState(localX, 0, localZ,
                                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                            }
                            if (worldX == 8 && worldZ == 8) {
                                sections[level.getSectionIndex(65)].setBlockState(localX, 1, localZ,
                                        net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
                            }
                            if (worldX == 15 && worldZ == 8) {
                                sections[level.getSectionIndex(66)].setBlockState(localX, 2, localZ,
                                        net.minecraft.world.level.block.Blocks.GLOWSTONE.defaultBlockState());
                            }
                            if (worldX == 9 && worldZ == 8) {
                                sections[level.getSectionIndex(65)].setBlockState(localX, 1, localZ,
                                        net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState());
                            }
                        }
                    }
                    ChunkPos position = new ChunkPos(chunkX, chunkZ);
                    input.put(position, sections);
                    addChunk(lines, blocks, biomes, chunkX, chunkZ, "light-fixture",
                            hashBlocks(sections, stateNames), hashBiomes(sections, biomeNames));
                }
            }
            Map<ChunkPos, Map<Integer, net.minecraft.world.level.chunk.DataLayer[]>> layers = new HashMap<>();
            relight(level, factory, input, layers);
            LightFixtureWriter.write(Path.of(destination), level, input, layers, options.seed(), "synthetic-column");
            return System.nanoTime() - start;
        }

        private static Map<ChunkPos, String> relight(ServerLevel level, PalettedContainerFactory factory,
                Map<ChunkPos, LevelChunkSection[]> input,
                Map<ChunkPos, Map<Integer, net.minecraft.world.level.chunk.DataLayer[]>> layersOut) {
            Map<Long, net.minecraft.world.level.chunk.ProtoChunk> chunks = new HashMap<>();
            for (Map.Entry<ChunkPos, LevelChunkSection[]> entry : input.entrySet()) {
                ChunkPos position = entry.getKey();
                LevelChunkSection[] sections = new LevelChunkSection[entry.getValue().length];
                for (int index = 0; index < sections.length; index++) {
                    sections[index] = entry.getValue()[index].copy();
                }
                net.minecraft.world.level.chunk.ProtoChunk chunk = new net.minecraft.world.level.chunk.ProtoChunk(
                        position, net.minecraft.world.level.chunk.UpgradeData.EMPTY, sections,
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(), new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        level, factory, null);
                chunk.initializeLightSources();
                chunks.put(chunkKey(position), chunk);
            }
            net.minecraft.world.level.chunk.LightChunkGetter getter = new net.minecraft.world.level.chunk.LightChunkGetter() {
                @Override
                public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) {
                    return chunks.get(chunkKey(new ChunkPos(x, z)));
                }

                @Override
                public net.minecraft.world.level.BlockGetter getLevel() {
                    return level;
                }
            };
            net.minecraft.world.level.lighting.LevelLightEngine engine =
                    new net.minecraft.world.level.lighting.LevelLightEngine(getter, true,
                            level.dimensionType().hasSkyLight());
            for (Map.Entry<ChunkPos, LevelChunkSection[]> entry : input.entrySet()) {
                ChunkPos position = entry.getKey();
                LevelChunkSection[] sections = chunks.get(chunkKey(position)).getSections();
                for (int index = 0; index < sections.length; index++) {
                    if (!sections[index].hasOnlyAir()) {
                        engine.updateSectionStatus(net.minecraft.core.SectionPos.of(position,
                                level.getSectionYFromSectionIndex(index)), false);
                    }
                }
                engine.setLightEnabled(position, false);
                engine.retainData(position, false);
            }
            for (ChunkPos position : input.keySet()) {
                engine.propagateLightSources(position);
            }
            while (engine.hasLightWork()) {
                engine.runLightUpdates();
            }
            Map<ChunkPos, String> hashes = new HashMap<>();
            for (ChunkPos position : input.keySet()) {
                Map<Integer, net.minecraft.world.level.chunk.DataLayer> block = new HashMap<>();
                Map<Integer, net.minecraft.world.level.chunk.DataLayer> sky = new HashMap<>();
                for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
                    net.minecraft.core.SectionPos section = net.minecraft.core.SectionPos.of(position, y);
                    net.minecraft.world.level.chunk.DataLayer blockLayer = engine.getLayerListener(
                            net.minecraft.world.level.LightLayer.BLOCK).getDataLayerData(section);
                    net.minecraft.world.level.chunk.DataLayer skyLayer = engine.getLayerListener(
                            net.minecraft.world.level.LightLayer.SKY).getDataLayerData(section);
                    if (blockLayer != null) {
                        block.put(y, blockLayer);
                    }
                    if (skyLayer != null) {
                        sky.put(y, skyLayer);
                    }
                }
                hashes.put(position, hashLight(level, block, sky));
                Map<Integer, net.minecraft.world.level.chunk.DataLayer[]> layers = new HashMap<>();
                for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
                    layers.put(y, new net.minecraft.world.level.chunk.DataLayer[] {block.get(y), sky.get(y)});
                }
                layersOut.put(position, layers);
            }
            return hashes;
        }

        /**
         * GAIUS_PARITY_LIGHT_DEBUG=x,z;x,z (saved mode): per level section, the cells whose
         * effective light (hashLight's reading) differs between the stored layers and the relit
         * ones, to find why a chunk's light differs.
         */
        private static void lightDifferences(ServerLevel level, String key,
                List<SerializableChunkData.SectionData> stored,
                Map<Integer, net.minecraft.world.level.chunk.DataLayer[]> relit) {
            java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> storedBlock = new java.util.TreeMap<>();
            java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> storedSky = new java.util.TreeMap<>();
            for (SerializableChunkData.SectionData section : stored) {
                if (section.blockLight() != null) {
                    storedBlock.put(section.y(), section.blockLight());
                }
                if (section.skyLight() != null) {
                    storedSky.put(section.y(), section.skyLight());
                }
            }
            java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> relitBlock = new java.util.TreeMap<>();
            java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> relitSky = new java.util.TreeMap<>();
            for (Map.Entry<Integer, net.minecraft.world.level.chunk.DataLayer[]> entry : relit.entrySet()) {
                if (entry.getValue()[0] != null) {
                    relitBlock.put(entry.getKey(), entry.getValue()[0]);
                }
                if (entry.getValue()[1] != null) {
                    relitSky.put(entry.getKey(), entry.getValue()[1]);
                }
            }
            for (int index = 0; index < level.getSectionsCount(); index++) {
                int sectionY = level.getMinSectionY() + index;
                for (int layer = 0; layer < 2; layer++) {
                    int count = 0;
                    StringBuilder samples = new StringBuilder();
                    for (int cell = 0; cell < 4096; cell++) {
                        int x = cell & 15;
                        int z = (cell >> 4) & 15;
                        int y = cell >> 8;
                        int a = layer == 0 ? effective(storedBlock, sectionY, x, y, z, 0) : effective(storedSky, sectionY, x, y, z, 15);
                        int b = layer == 0 ? effective(relitBlock, sectionY, x, y, z, 0) : effective(relitSky, sectionY, x, y, z, 15);
                        if (a != b) {
                            if (count < 8) {
                                samples.append(String.format(Locale.ROOT, " %d,%d,%d:%d/%d", x, sectionY * 16 + y, z, a, b));
                            }
                            count++;
                        }
                    }
                    if (count > 0) {
                        System.out.println("WORLDGEN_SEED_PARITY_LIGHT_DEBUG " + key + " section " + sectionY + " "
                                + (layer == 0 ? "block" : "sky") + " differing=" + count + " stored/relit" + samples);
                    }
                }
            }
        }

        private static int effective(java.util.TreeMap<Integer, net.minecraft.world.level.chunk.DataLayer> layers,
                int sectionY, int x, int y, int z, int missing) {
            net.minecraft.world.level.chunk.DataLayer layer = layers.get(sectionY);
            if (layer != null) {
                return layer.get(x, y, z);
            }
            if (missing == 0) {
                return 0;
            }
            Map.Entry<Integer, net.minecraft.world.level.chunk.DataLayer> above = layers.higherEntry(sectionY);
            return above == null ? 15 : above.getValue().get(x, 0, z);
        }

        /**
         * GAIUS_PARITY_BIOME_DEBUG=x,z;x,z (saved mode): the 4x4x4 biome cells where the saved chunk
         * and the same chunk generated here to BIOMES differ, with the cell's block coordinates, the
         * saved biome and the vanilla one. Where the profile can sample the climate here
         * (ParityCompat.climateTarget), each cell also names the biome of the same climate target
         * in the parameter list as TeaVM's JavaScript backend quantizes it ("teavmJs"):
         * Climate.quantizeCoord computes {@code (long) (value * 10000.0F)}, and without a float
         * rounding pass (StrictMath263 rounds only 26.3 and later) the product stays a double, so
         * a bound such as 0.7F quantizes to 6999 instead of 7000. A cell whose saved biome is the
         * teavmJs one comes from that quantization, not from the noise.
         */
        private static void biomeDifferences(ServerLevel level, Map<ChunkPos, LevelChunkSection[]> unticked,
                Map<ChunkPos, LevelChunkSection[]> full) {
            String debug = System.getenv("GAIUS_PARITY_BIOME_DEBUG");
            if (debug == null || debug.isBlank()) {
                return;
            }
            for (String key : debug.split(";")) {
                String[] parts = key.split(",");
                ChunkPos position = new ChunkPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                LevelChunkSection[] saved = unticked.containsKey(position) ? unticked.get(position) : full.get(position);
                if (saved == null) {
                    System.out.println("WORLDGEN_SEED_PARITY_BIOME_DEBUG " + key + " not saved at TERRAIN or later");
                    continue;
                }
                LevelChunkSection[] vanilla = level.getChunkSource()
                        .getChunk(ParityCompat.x(position), ParityCompat.z(position), ChunkStatus.BIOMES, true).getSections();
                int count = 0;
                for (int index = 0; index < saved.length; index++) {
                    for (int cell = 0; cell < 64; cell++) {
                        int x = cell & 3;
                        int z = (cell >> 2) & 3;
                        int y = cell >> 4;
                        Holder<Biome> a = saved[index].getNoiseBiome(x, y, z);
                        Holder<Biome> b = vanilla[index].getNoiseBiome(x, y, z);
                        if (a.equals(b)) {
                            continue;
                        }
                        if (count++ < 64) {
                            int quartX = ParityCompat.x(position) * 4 + x;
                            int quartY = level.getSectionYFromSectionIndex(index) * 4 + y;
                            int quartZ = ParityCompat.z(position) * 4 + z;
                            String teavmJs = teavmJsBiome(level, quartX, quartY, quartZ);
                            System.out.println("WORLDGEN_SEED_PARITY_BIOME_DEBUG " + key + " cell "
                                    + quartX * 4 + "," + quartY * 4 + "," + quartZ * 4 + " saved "
                                    + a.unwrapKey().map(ParityCompat::keyName).orElse("?") + " vanilla "
                                    + b.unwrapKey().map(ParityCompat::keyName).orElse("?")
                                    + (teavmJs == null ? "" : " teavmJs " + teavmJs));
                        }
                    }
                }
                System.out.println("WORLDGEN_SEED_PARITY_BIOME_DEBUG " + key + " differing cells " + count);
            }
        }

        private static Climate.ParameterList<Holder<Biome>> teavmJsParameters;

        /**
         * The biome vanilla's climate lookup finds for the cell's climate target in the overworld
         * parameter list with every bound quantized as TeaVM's JavaScript backend does (see
         * {@link #biomeDifferences}); null where the profile cannot sample the climate here or
         * the level has no multi-noise source. The search runs on a new thread, so it does not
         * start from a previous result (Climate.RTree.lastResult).
         */
        private static String teavmJsBiome(ServerLevel level, int quartX, int quartY, int quartZ) {
            Climate.TargetPoint target = ParityCompat.climateTarget(level, quartX, quartY, quartZ);
            if (target == null || !(level.getChunkSource().getGenerator().getBiomeSource()
                    instanceof MultiNoiseBiomeSource source)) {
                return null;
            }
            try {
                if (teavmJsParameters == null) {
                    java.lang.reflect.Method parameters = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
                    parameters.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Climate.ParameterList<Holder<Biome>> list = (Climate.ParameterList<Holder<Biome>>) parameters.invoke(source);
                    List<Pair<Climate.ParameterPoint, Holder<Biome>>> values = new ArrayList<>();
                    for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : list.values()) {
                        Climate.ParameterPoint p = entry.getFirst();
                        values.add(Pair.of(new Climate.ParameterPoint(teavmJs(p.temperature()), teavmJs(p.humidity()),
                                teavmJs(p.continentalness()), teavmJs(p.erosion()), teavmJs(p.depth()), teavmJs(p.weirdness()),
                                teavmJs(p.offset())), entry.getSecond()));
                    }
                    teavmJsParameters = new Climate.ParameterList<>(values);
                }
                Object[] found = new Object[1];
                Thread search = new Thread(() -> found[0] = teavmJsParameters.findValueIndex(target));
                search.start();
                search.join();
                @SuppressWarnings("unchecked")
                Holder<Biome> biome = (Holder<Biome>) found[0];
                return biome.unwrapKey().map(ParityCompat::keyName).orElse("?");
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("MultiNoiseBiomeSource.parameters changed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private static Climate.Parameter teavmJs(Climate.Parameter parameter) {
            return new Climate.Parameter(teavmJs(parameter.min()), teavmJs(parameter.max()));
        }

        /**
         * A bound quantized from the float nearest to {@code quantized / 10000} (the four-decimal
         * constants of the overworld parameter list) as {@code (long) (value * 10000.0)} in double;
         * a bound that float does not reproduce in vanilla is kept.
         */
        private static long teavmJs(long quantized) {
            float value = (float) (quantized / 10000.0);
            return Climate.quantizeCoord(value) == quantized ? (long) ((double) value * 10000.0) : quantized;
        }

        private static long chunkKey(ChunkPos position) {
            return ((long) ParityCompat.x(position) & 0xffffffffL) | (((long) ParityCompat.z(position)) << 32);
        }

        /**
         * The vanilla counterpart of a browser save (browser-world.mjs), the control for saved mode:
         * loads a side x side square around the spawn chunk to FULL, ring by ring from the centre
         * the way a player-centred load does ({@code --order spiral}) or in a shuffled order fixed
         * by the seed ({@code --order shuffle}), then writes every chunk in memory, at whatever
         * status it reached, to region files in {@code --capture} with the vanilla serializer. A
         * saved-mode run on that directory measures what saved mode reports for a correct
         * build: biomes must match exactly, while feature blocks can differ with the order in
         * which neighbouring chunks were decorated (saved mode decorates centre-out, so the
         * shuffled order shows how far a different order moves them).
         */
        private long capture(ServerLevel level, ChunkPos origin, List<String> chunkLines, MessageDigest blocksAggregate,
                MessageDigest biomesAggregate, Map<BlockState, byte[]> stateNames,
                Map<Holder<Biome>, byte[]> biomeNames) throws IOException {
            ServerChunkCache chunks = level.getChunkSource();
            long start = System.nanoTime();
            int radius = options.side() / 2;
            List<ChunkPos> order = new ArrayList<>();
            if (options.positions() != null) {
                order.addAll(options.positions());
            } else {
                for (int ring = 0; ring <= radius; ring++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        for (int dx = -ring; dx <= ring; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                                order.add(new ChunkPos(ParityCompat.x(origin) + dx, ParityCompat.z(origin) + dz));
                            }
                        }
                    }
                }
                if ("shuffle".equals(options.order())) {
                    java.util.Collections.shuffle(order, new java.util.Random(options.seed()));
                }
            }
            for (ChunkPos position : order) {
                chunks.getChunk(ParityCompat.x(position), ParityCompat.z(position), ChunkStatus.FULL, true);
            }
            long generation = System.nanoTime() - start;
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> holders;
            try {
                Field field = net.minecraft.server.level.ChunkMap.class.getDeclaredField("visibleChunkMap");
                field.setAccessible(true);
                holders = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) field.get(chunks.chunkMap);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("ChunkMap.visibleChunkMap changed", exception);
            }
            Files.createDirectories(options.capture());
            int written = 0;
            try (RegionFileStorage storage = ParityCompat.regionStorage("capture", options.capture())) {
                for (Object value : holders.values()) {
                    net.minecraft.server.level.ChunkHolder holder = (net.minecraft.server.level.ChunkHolder) value;
                    ChunkAccess chunk = holder.getLatestChunk();
                    if (chunk == null || chunk.getPersistedStatus() == ChunkStatus.EMPTY) {
                        continue;
                    }
                    ParityCompat.write(storage, holder.getPos(), SerializableChunkData.copyOf(level, chunk).write());
                    addChunk(chunkLines, blocksAggregate, biomesAggregate, ParityCompat.x(holder.getPos()), ParityCompat.z(holder.getPos()),
                            chunk.getPersistedStatus().getName(), hashBlocks(chunk.getSections(), stateNames),
                            hashBiomes(chunk.getSections(), biomeNames));
                    written++;
                }
                storage.flush();
            }
            System.out.println("WORLDGEN_SEED_PARITY_CAPTURE chunks=" + written + " into " + options.capture());
            return generation;
        }

        /**
         * Block-by-block check of every saved chunk that no level tick touched: status TERRAIN up to
         * SPAWN (a FULL chunk was ticked: fluids, plants and mobs change its blocks). These chunks
         * are not pure terrain: the FEATURES step of a chunk writes features into the chunks around
         * it, and which neighbours had been decorated, and in which order, decides what a chunk
         * holds. So this harness rebuilds the same situation: it generates every such chunk to
         * TERRAIN and copies its blocks (pure terrain), then generates to FEATURES every chunk the
         * save holds at FEATURES or later around them (the whole decorated area, FULL chunks
         * included), nearest to the spawn chunk first like a player-centred load, and copies them
         * again (decorated). A saved block equal to the decorated block is in parity. Differences
         * are split by what the pure terrain says: "missing" where the saved block is pure terrain
         * but this harness placed a feature, "extra" where this harness kept pure terrain but the
         * save holds something else, "other" where both changed it differently. Feature placement
         * depends on the order in which neighbouring chunks were decorated, which vanilla does not
         * fix, so feature blocks can differ between two correct builds; "extraKinds" lists what the
         * save holds over pure terrain, so a terrain difference (stone, water or air where vanilla
         * has something else) stands out.
         *
         * <p>"hard" is exact and covers every saved chunk at TERRAIN or later, FULL ones too: no
         * overworld feature and no level tick places or removes bedrock, so where either the saved
         * block or the pure terrain block is bedrock they differ only if terrain generation (the
         * bedrock surface rules and their positional random) differs. Stone and deepslate are not
         * such a pair: some features (lake barriers, for one) write stone into deepslate, which the
         * vanilla control shows.
         */
        private static String diffBlocks(ServerChunkCache chunks, ChunkPos origin, Map<ChunkPos, ChunkStatus> statuses,
                Map<ChunkPos, LevelChunkSection[]> comparable, Map<ChunkPos, LevelChunkSection[]> fullChunks) {
            if (comparable.isEmpty() && fullChunks.isEmpty()) {
                return "null";
            }
            long start = System.nanoTime();
            Map<ChunkPos, BlockState[]> pure = new HashMap<>();
            for (ChunkPos position : comparable.keySet()) {
                pure.put(position, copyBlocks(chunks.getChunk(ParityCompat.x(position), ParityCompat.z(position), ParityCompat.TERRAIN, true)
                        .getSections()));
            }
            boolean fullReference = "1".equals(System.getenv("GAIUS_PARITY_FULL_SAVED"));
            long[] hard = new long[3];
            List<String> hardSamples = new ArrayList<>();
            for (Map.Entry<ChunkPos, LevelChunkSection[]> entry : fullChunks.entrySet()) {
                ChunkPos position = entry.getKey();
                ChunkAccess chunk = chunks.getChunk(ParityCompat.x(position), ParityCompat.z(position), fullReference ? ChunkStatus.FULL : ParityCompat.TERRAIN, true);
                hardDifferences(position, chunk, copyBlocks(entry.getValue()), copyBlocks(chunk.getSections()), hard,
                        hardSamples);
            }
            for (Map.Entry<ChunkPos, LevelChunkSection[]> entry : comparable.entrySet()) {
                ChunkPos position = entry.getKey();
                hardDifferences(position, chunks.getChunk(ParityCompat.x(position), ParityCompat.z(position), ParityCompat.TERRAIN, true),
                        copyBlocks(entry.getValue()), pure.get(position), hard, hardSamples);
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (ChunkPos position : comparable.keySet()) {
                minX = Math.min(minX, ParityCompat.x(position) - 2);
                maxX = Math.max(maxX, ParityCompat.x(position) + 2);
                minZ = Math.min(minZ, ParityCompat.z(position) - 2);
                maxZ = Math.max(maxZ, ParityCompat.z(position) + 2);
            }
            List<ChunkPos> decorate = new ArrayList<>();
            for (Map.Entry<ChunkPos, ChunkStatus> entry : statuses.entrySet()) {
                ChunkPos position = entry.getKey();
                if (entry.getValue().isOrAfter(ChunkStatus.FEATURES) && ParityCompat.x(position) >= minX && ParityCompat.x(position) <= maxX
                        && ParityCompat.z(position) >= minZ && ParityCompat.z(position) <= maxZ) {
                    decorate.add(position);
                }
            }
            decorate.sort(java.util.Comparator.<ChunkPos>comparingInt(position -> Math.max(
                    Math.abs(ParityCompat.x(position) - ParityCompat.x(origin)), Math.abs(ParityCompat.z(position) - ParityCompat.z(origin))))
                    .thenComparingInt(ParityCompat::z).thenComparingInt(ParityCompat::x));
            for (ChunkPos position : decorate) {
                chunks.getChunk(ParityCompat.x(position), ParityCompat.z(position), ChunkStatus.FEATURES, true);
            }
            long[][] totals = new long[2][6];
            int[] chunkCounts = new int[2];
            int[] exactCounts = new int[2];
            Map<String, Integer> extraKinds = new java.util.TreeMap<>();
            List<String> details = new ArrayList<>();
            for (Map.Entry<ChunkPos, LevelChunkSection[]> entry : comparable.entrySet()) {
                ChunkPos position = entry.getKey();
                int group = statuses.get(position) == ParityCompat.TERRAIN ? 0 : 1;
                ChunkAccess chunk = chunks.getChunk(ParityCompat.x(position), ParityCompat.z(position), ParityCompat.TERRAIN, true);
                BlockState[] decorated = copyBlocks(chunk.getSections());
                BlockState[] terrain = pure.get(position);
                BlockState[] saved = copyBlocks(entry.getValue());
                // 0 blocks, 1 same, 2 same feature blocks, 3 missing, 4 extra, 5 other
                long[] counts = new long[6];
                List<String> samples = new ArrayList<>();
                for (int index = 0; index < saved.length; index++) {
                    counts[0]++;
                    if (saved[index] == decorated[index]) {
                        counts[1]++;
                        if (decorated[index] != terrain[index]) {
                            counts[2]++;
                        }
                        continue;
                    }
                    String kind;
                    if (saved[index] == terrain[index]) {
                        counts[3]++;
                        kind = "missing";
                    } else if (decorated[index] == terrain[index]) {
                        counts[4]++;
                        kind = "extra";
                        extraKinds.merge(saved[index].getBlock() + " over " + terrain[index].getBlock(), 1, Integer::sum);
                    } else {
                        counts[5]++;
                        kind = "other";
                    }
                    if (samples.size() < 4) {
                        int local = index & 4095;
                        int y = chunk.getSectionYFromSectionIndex(index >> 12) * 16 + (local >> 8);
                        samples.add(String.format(Locale.ROOT,
                                "{\"kind\": \"%s\", \"x\": %d, \"y\": %d, \"z\": %d, \"saved\": %s, \"terrain\": %s, "
                                        + "\"decorated\": %s}",
                                kind, ParityCompat.x(position) * 16 + (local & 15), y, ParityCompat.z(position) * 16 + ((local >> 4) & 15),
                                quote(saved[index].toString()), quote(terrain[index].toString()),
                                quote(decorated[index].toString())));
                    }
                }
                chunkCounts[group]++;
                for (int index = 0; index < counts.length; index++) {
                    totals[group][index] += counts[index];
                }
                if (counts[1] == counts[0]) {
                    exactCounts[group]++;
                    continue;
                }
                details.add(String.format(Locale.ROOT,
                        "{\"x\": %d, \"z\": %d, \"status\": \"%s\", \"missing\": %d, \"extra\": %d, \"other\": %d, "
                                + "\"samples\": [%s]}",
                        ParityCompat.x(position), ParityCompat.z(position), statuses.get(position).getName(), counts[3], counts[4], counts[5],
                        String.join(", ", samples)));
            }
            StringBuilder kinds = new StringBuilder();
            for (Map.Entry<String, Integer> kind : extraKinds.entrySet()) {
                kinds.append(kinds.isEmpty() ? "" : ", ").append(quote(kind.getKey())).append(": ").append(kind.getValue());
            }
            String[] names = {"terrain", "decorated"};
            StringBuilder groups = new StringBuilder();
            for (int group = 0; group < 2; group++) {
                long[] total = totals[group];
                groups.append(group == 0 ? "" : ", ").append(String.format(Locale.ROOT,
                        "\"%s\": {\"chunks\": %d, \"exact\": %d, \"blocks\": %d, \"same\": %d, \"featureBlocksSame\": %d, "
                                + "\"missing\": %d, \"extra\": %d, \"other\": %d}",
                        names[group], chunkCounts[group], exactCounts[group], total[0], total[1], total[2], total[3],
                        total[4], total[5]));
                System.out.println("WORLDGEN_SEED_PARITY_BLOCK_DIFF " + names[group] + " chunks=" + chunkCounts[group]
                        + " exact=" + exactCounts[group] + " blocks=" + total[0] + " same=" + total[1]
                        + " missing=" + total[3] + " extra=" + total[4] + " other=" + total[5]);
            }
            System.out.println("WORLDGEN_SEED_PARITY_BLOCK_DIFF decoratedChunks=" + decorate.size() + " millis="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            System.out.println("WORLDGEN_SEED_PARITY_BLOCK_DIFF hard chunks=" + hard[0] + " blocks=" + hard[1]
                    + " differences=" + hard[2]);
            return String.format(Locale.ROOT, "{\"decoratedChunks\": %d, %s, \"hard\": {\"chunks\": %d, \"blocks\": %d, "
                            + "\"differences\": %d, \"samples\": [%s]}, \"extraKinds\": {%s}, \"differingChunks\": [%s]}",
                    decorate.size(), groups, hard[0], hard[1], hard[2], String.join(", ", hardSamples), kinds,
                    String.join(", ", details));
        }

        /**
         * Counts, into {@code hard} (chunks, blocks looked at, differences), the saved blocks that
         * differ from pure terrain where one of the two is bedrock: a change no feature and no
         * level tick makes.
         */
        private static void hardDifferences(ChunkPos position, ChunkAccess chunk, BlockState[] saved, BlockState[] terrain,
                long[] hard, List<String> samples) {
            hard[0]++;
            for (int index = 0; index < saved.length; index++) {
                net.minecraft.world.level.block.Block savedBlock = saved[index].getBlock();
                net.minecraft.world.level.block.Block terrainBlock = terrain[index].getBlock();
                if (savedBlock != net.minecraft.world.level.block.Blocks.BEDROCK
                        && terrainBlock != net.minecraft.world.level.block.Blocks.BEDROCK) {
                    continue;
                }
                hard[1]++;
                if (savedBlock == terrainBlock) {
                    continue;
                }
                hard[2]++;
                if (samples.size() < 20) {
                    int local = index & 4095;
                    int y = chunk.getSectionYFromSectionIndex(index >> 12) * 16 + (local >> 8);
                    samples.add(String.format(Locale.ROOT, "{\"x\": %d, \"y\": %d, \"z\": %d, \"saved\": %s, \"terrain\": %s}",
                            ParityCompat.x(position) * 16 + (local & 15), y, ParityCompat.z(position) * 16 + ((local >> 4) & 15),
                            quote(saved[index].toString()), quote(terrain[index].toString())));
                }
            }
        }

        /** Block states of the sections, section by section in y, z, x order (index y << 8 | z << 4 | x). */
        private static BlockState[] copyBlocks(LevelChunkSection[] sections) {
            BlockState[] states = new BlockState[sections.length * 4096];
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            states[sectionIndex << 12 | y << 8 | z << 4 | x] = section.getBlockState(x, y, z);
                        }
                    }
                }
            }
            return states;
        }

        /** The world spawn a saved level.dat records, as {"x", "y", "z"} or null. */
        private static String savedSpawn(Path levelDat) throws IOException {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompound("Data").orElse(root);
            int[] position = data.getCompound("spawn").flatMap(spawn -> spawn.getIntArray("pos")).orElse(null);
            if (position != null && position.length == 3) {
                return String.format(Locale.ROOT, "{\"x\": %d, \"y\": %d, \"z\": %d}",
                        position[0], position[1], position[2]);
            }
            if (data.getInt("SpawnX").isPresent() && data.getInt("SpawnZ").isPresent()) {
                return String.format(Locale.ROOT, "{\"x\": %d, \"y\": %d, \"z\": %d}",
                        data.getInt("SpawnX").get(), data.getInt("SpawnY").orElse(0), data.getInt("SpawnZ").get());
            }
            System.out.println("WORLDGEN_SEED_PARITY_LEVEL_DAT no spawn in " + data.keySet());
            return "null";
        }

        /**
         * SHA-256 over every block state of the chunk, section by section in y, z, x order,
         * each written as its canonical {@code BlockState.toString()} and a newline.
         */
        private static String hashBlocks(LevelChunkSection[] sections, Map<BlockState, byte[]> names) {
            MessageDigest digest = sha256();
            for (LevelChunkSection section : sections) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            digest.update(names.computeIfAbsent(state, value ->
                                    (value.toString() + "\n").getBytes(StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        /** SHA-256 over the biome id of every 4x4x4 cell, section by section in y, z, x order. */
        private static String hashBiomes(LevelChunkSection[] sections, Map<Holder<Biome>, byte[]> names) {
            MessageDigest digest = sha256();
            for (LevelChunkSection section : sections) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        for (int x = 0; x < 4; x++) {
                            Holder<Biome> biome = section.getNoiseBiome(x, y, z);
                            digest.update(names.computeIfAbsent(biome, value -> (value.unwrapKey()
                                    .map(ParityCompat::keyName)
                                    .orElse("unregistered") + "\n").getBytes(StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        @Override
        protected void parityExit() {
            finished = true;
        }

        @Override
        protected void parityCrash(String report) {
            failure = new IllegalStateException(report);
        }

        @Override
        public boolean isSingleplayerOwner(NameAndId player) {
            return false;
        }
    }
}
