package dev.gaius.parity;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.SystemReport;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gizmos.GizmoCollector;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

/**
 * The Minecraft 1.21.11 side of the parity harnesses' version layer: every vanilla API that differs
 * between the supported profiles goes through this class, and worldgen-seed-parity.mjs compiles
 * the copy under compat/&lt;profile&gt;/ next to WorldgenSeedParity.java (compat/26.2 and
 * compat/1.21.11 hold the other profiles).
 */
final class ParityCompat {
    static final String PROFILE = profileId();

    private static String profileId() {
        net.minecraft.SharedConstants.tryDetectVersion();
        return net.minecraft.SharedConstants.getCurrentVersion().id();
    }

    /** The last status before FEATURES (noise, surface, carvers; 26.3 merged them into TERRAIN). */
    static final ChunkStatus TERRAIN = ChunkStatus.CARVERS;

    private ParityCompat() {
    }

    static int x(ChunkPos position) {
        return position.x;
    }

    static int z(ChunkPos position) {
        return position.z;
    }

    /**
     * The spawn chunk vanilla's spawn search and the browser's fast initial spawn start from
     * (before 26.3: the climate sampler's spawn position).
     */
    static ChunkPos origin(ServerChunkCache chunks) {
        BlockPos spawn = chunks.randomState().sampler().findSpawnPosition();
        return new ChunkPos(spawn.getX() >> 4, spawn.getZ() >> 4);
    }

    static BlockPos respawnPos(ServerLevel level) {
        return level.getRespawnData().pos();
    }

    /** The climate target vanilla's biome lookup samples at the quart (Climate.Sampler.sample). */
    static Climate.TargetPoint climateTarget(ServerLevel level, int quartX, int quartY, int quartZ) {
        return level.getChunkSource().randomState().sampler().sample(quartX, quartY, quartZ);
    }

    static String keyName(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    /** 1.21.11 keeps the RegionFileStorage constructor package-private. */
    static RegionFileStorage regionStorage(String name, Path directory) {
        try {
            var constructor = RegionFileStorage.class.getDeclaredConstructor(
                    RegionStorageInfo.class, Path.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new RegionStorageInfo(name, Level.OVERWORLD, "chunk"), directory, false);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("RegionFileStorage constructor changed", exception);
        }
    }

    /** 1.21.11 keeps RegionFileStorage.write protected. */
    static void write(RegionFileStorage storage, ChunkPos position, CompoundTag tag) throws java.io.IOException {
        try {
            var method = RegionFileStorage.class.getDeclaredMethod("write", ChunkPos.class, CompoundTag.class);
            method.setAccessible(true);
            method.invoke(storage, position, tag);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new IllegalStateException("RegionFileStorage.write failed", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("RegionFileStorage.write changed", exception);
        }
    }

    /**
     * Loads the vanilla data pack and the "minecraft:normal" preset for a new world of
     * {@code seed} (structures on, no bonus chest). {@code initialized} skips setInitialSpawn.
     */
    static WorldStem loadStem(PackRepository packs, long seed, GameType gameType, Difficulty difficulty,
            boolean allowCommands, boolean initialized) {
        return loadStem(packs, seed, gameType, difficulty, allowCommands, initialized, Commands.CommandSelection.DEDICATED);
    }

    /** As above, with the command set of a dedicated or an integrated (singleplayer) server. */
    static WorldStem loadStem(PackRepository packs, long seed, GameType gameType, Difficulty difficulty,
            boolean allowCommands, boolean initialized, Commands.CommandSelection commands) {
        packs.reload();
        WorldDataConfiguration dataConfiguration = new WorldDataConfiguration(
                new DataPackConfig(List.of("vanilla"), List.of()), FeatureFlags.DEFAULT_FLAGS);
        LevelSettings settings = new LevelSettings("Gaius worldgen parity", gameType, false, difficulty,
                allowCommands, new GameRules(dataConfiguration.enabledFeatures()), dataConfiguration);
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, dataConfiguration, false, true);
        WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(packConfig,
                commands, LevelBasedPermissionSet.OWNER);
        WorldOptions worldOptions = new WorldOptions(seed, true, false);
        try {
            return Util.<WorldStem>blockUntilDone(executor -> WorldLoader.load(
                    initConfig,
                    context -> {
                        Registry<LevelStem> noDatapackDimensions = new MappedRegistry<>(
                                Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                        WorldDimensions preset = context.datapackWorldgen()
                                .lookupOrThrow(Registries.WORLD_PRESET)
                                .getOrThrow(WorldPresets.NORMAL).value()
                                .createWorldDimensions();
                        WorldDimensions.Complete dimensions = preset.bake(noDatapackDimensions);
                        PrimaryLevelData data = new PrimaryLevelData(settings, worldOptions,
                                dimensions.specialWorldProperty(), dimensions.lifecycle());
                        if (initialized) {
                            data.setInitialized(true);
                        }
                        return new WorldLoader.DataLoadOutput<net.minecraft.world.level.storage.WorldData>(
                                data, dimensions.dimensionsRegistryAccess());
                    },
                    WorldStem::new,
                    Util.backgroundExecutor(),
                    executor)).get();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load the vanilla data pack", exception);
        }
    }

    /**
     * The structure state of the overworld of a new world of {@code seed} as ChunkMap builds it
     * (StructureRingsParity): the preset's noise generator, its RandomState and createState.
     */
    static ChunkGeneratorStructureState structureState(RegistryAccess access, long seed) {
        ChunkGenerator generator = access.lookupOrThrow(Registries.LEVEL_STEM)
                .getOrThrow(LevelStem.OVERWORLD).value().generator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            throw new IllegalStateException("The overworld generator is not noise based: " + generator);
        }
        RandomState randomState = RandomState.create(noise.generatorSettings().value(),
                access.lookupOrThrow(Registries.NOISE), seed);
        return generator.createState(access.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
    }

    /**
     * A headless server in the style of vanilla's GameTestServer: the boilerplate overrides of
     * this version's MinecraftServer, with the harness's own work in {@link #parityTick},
     * {@link #parityExit} and {@link #parityCrash}.
     */
    abstract static class ServerBase extends MinecraftServer {
        private final LocalSampleLogger sampleLogger = new LocalSampleLogger(4);

        protected ServerBase(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs,
                WorldStem stem) {
            super(thread, access, packs, stem, Proxy.NO_PROXY, DataFixers.getDataFixer(), noServices(),
                    LoggingLevelLoadListener.forDedicatedServer());
        }

        private static Services noServices() {
            try {
                Field field = GameTestServer.class.getDeclaredField("NO_SERVICES");
                field.setAccessible(true);
                return (Services) field.get(null);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("GameTestServer.NO_SERVICES changed", exception);
            }
        }

        /** The harness's tick; {@link #vanillaTick} runs the vanilla server tick. */
        protected abstract void parityTick(BooleanSupplier haveTime);

        protected void parityExit() {
        }

        protected void parityCrash(String report) {
        }

        protected final void vanillaTick(BooleanSupplier haveTime) {
            super.tickServer(haveTime);
        }

        @Override
        protected boolean initServer() {
            setPlayerList(new PlayerList(this, registries(), playerDataStorage,
                    new EmptyNotificationService()) {
            });
            try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(GizmoCollector.NOOP)) {
                loadLevel();
            }
            return true;
        }

        @Override
        public void tickServer(BooleanSupplier haveTime) {
            parityTick(haveTime);
        }

        /** True for a server that ticks in real time (a session with a client), false for a harness run. */
        protected boolean realTimeTicks() {
            return false;
        }

        @Override
        protected void waitUntilNextTick() {
            if (realTimeTicks()) {
                super.waitUntilNextTick();
            } else {
                runAllTasks();
            }
        }

        @Override
        protected SampleLogger getTickTimeLogger() {
            return sampleLogger;
        }

        @Override
        public boolean isTickTimeLoggingEnabled() {
            return false;
        }

        @Override
        public SystemReport fillServerSystemReport(SystemReport report) {
            report.setDetail("Type", "Gaius parity server");
            return report;
        }

        @Override
        public void onServerExit() {
            super.onServerExit();
            parityExit();
        }

        @Override
        public void onServerCrash(net.minecraft.CrashReport report) {
            super.onServerCrash(report);
            parityCrash(report.getFriendlyReport(net.minecraft.ReportType.CRASH));
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public LevelBasedPermissionSet operatorUserPermissions() {
            return LevelBasedPermissionSet.OWNER;
        }

        @Override
        public PermissionSet getFunctionCompilationPermissions() {
            return LevelBasedPermissionSet.OWNER;
        }

        @Override
        public boolean shouldRconBroadcast() {
            return false;
        }

        @Override
        public boolean isDedicatedServer() {
            return false;
        }

        @Override
        public int getRateLimitPacketsPerSecond() {
            return 0;
        }

        @Override
        public boolean useNativeTransport() {
            return false;
        }

        @Override
        public boolean isPublished() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        @Override
        public int getMaxPlayers() {
            return 1;
        }
    }
}
