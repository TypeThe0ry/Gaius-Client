import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Runs the patched PlayerSpawnFinder (patchPlayerSpawnFinderBrowser) on the JVM.
 *
 * <p>The classpath holds the patched PlayerSpawnFinder and PrepareSpawnTask in front of the
 * profile's vanilla client jar and libraries. The level is a flat overworld-shaped stand-in
 * (y -64..320): stone up to {@link #SURFACE}, or, for the ocean case, stone up to
 * {@code WATER_TOP - 10} and water up to {@link #WATER_TOP}; air above. The browser
 * setInitialSpawn stores the world spawn at the top of the level, so every case starts the
 * search from y = 320, as a respawn in a browser-created world does.</p>
 *
 * <p>The ServerLevel and LevelChunk subclasses are allocated without running a constructor; the
 * smoke fills the super call's argument list in for the profile's ServerLevel constructor.</p>
 */
public final class GaiusRespawnSearchFixture {
    static final int SURFACE = 64;
    static final int WATER_TOP = 62;
    private static sun.misc.Unsafe unsafe;

    private GaiusRespawnSearchFixture() {
    }

    public static void main(String[] args) throws Throwable {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        BlockPos browserWorldSpawn = new BlockPos(8, 320, 8);

        // A respawn (ServerPlayer.adjustSpawnLocation) runs vanilla's search to the end: on the
        // ground, within the default respawn radius of 10. The unpatched browser findSpawn
        // returned (8.5, 320, 8.5) here and the player fell to death on every respawn.
        FakeLevel land = level(false);
        Vec3 respawn = PlayerSpawnFinder.findSpawn(land, browserWorldSpawn).getNow(null);
        check(respawn != null, "a respawn search must complete at once");
        check(respawn.y == SURFACE + 1, "a respawn must stand on the ground: " + respawn);
        check(Math.abs(respawn.x - 8.5) <= 10 && Math.abs(respawn.z - 8.5) <= 10,
                "a respawn must stay within the respawn radius: " + respawn);
        check(land.chunkLookups > 0, "a respawn search must look the spawn chunk up");
        System.out.println("RESPAWN_SEARCH_ON_GROUND " + respawn);

        // respawn_radius 0: vanilla's single candidate is the spawn column itself.
        FakeLevel exact = level(false);
        exact.rules.set(GameRules.RESPAWN_RADIUS, 0, null);
        Vec3 column = PlayerSpawnFinder.findSpawn(exact, new BlockPos(-37, 320, 1003)).getNow(null);
        check(column != null && column.x == -36.5 && column.y == SURFACE + 1 && column.z == 1003.5,
                "respawn_radius 0 must respawn on the spawn column: " + column);
        System.out.println("RESPAWN_SEARCH_RADIUS_ZERO " + column);

        // No candidate qualifies (all water): vanilla's fixupSpawnHeight fallback settles the
        // world spawn on the water instead of leaving it in the air.
        FakeLevel ocean = level(true);
        Vec3 wet = PlayerSpawnFinder.findSpawn(ocean, browserWorldSpawn).getNow(null);
        check(wet != null && wet.x == 8.5 && wet.z == 8.5 && wet.y == WATER_TOP + 1,
                "an ocean respawn must fall back to the water surface: " + wet);
        System.out.println("RESPAWN_SEARCH_FALLBACK " + wet);

        // A joining player (PrepareSpawnTask.start sets the flag) keeps the deferred path:
        // findSpawn returns the world spawn as is without touching the level, and consumes the
        // flag so the next respawn searches again.
        Field defer = PlayerSpawnFinder.class.getField("gaius$deferSpawnSearch");
        defer.setBoolean(null, true);
        Vec3 joined = PlayerSpawnFinder.findSpawn(null, browserWorldSpawn).getNow(null);
        check(new Vec3(8.5, 320, 8.5).equals(joined),
                "a deferred join spawn must be the world spawn as is: " + joined);
        check(!defer.getBoolean(null), "findSpawn must consume the defer flag");
        System.out.println("JOIN_SPAWN_SEARCH_DEFERRED " + joined);

        // The patched PrepareSpawnTask (flag set and cleared around its orElseGet) verifies.
        Class.forName("net.minecraft.server.network.config.PrepareSpawnTask", true,
                GaiusRespawnSearchFixture.class.getClassLoader());
        System.out.println("PREPARE_SPAWN_TASK_DEFER_VERIFIED");
    }

    private static FakeLevel level(boolean ocean) throws Exception {
        FakeLevel level = (FakeLevel) unsafe.allocateInstance(FakeLevel.class);
        level.ocean = ocean;
        level.rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
        level.border = new WorldBorder();
        level.dimension = overworldShape();
        MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(
                Class.forName("net.minecraft.client.server.IntegratedServer"));
        WorldData data = (WorldData) Proxy.newProxyInstance(WorldData.class.getClassLoader(),
                new Class<?>[] {WorldData.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getGameType")) {
                        return GameType.SURVIVAL;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Field worldData = MinecraftServer.class.getDeclaredField("worldData");
        worldData.setAccessible(true);
        worldData.set(server, data);
        level.server = server;
        return level;
    }

    /** An overworld-like DimensionType: sky light, no ceiling, y -64..320. */
    private static DimensionType overworldShape() throws Exception {
        var components = DimensionType.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = switch (components[i].getName()) {
                case "hasSkyLight" -> Boolean.TRUE;
                case "minY" -> Integer.valueOf(-64);
                case "height", "logicalHeight" -> Integer.valueOf(384);
                case "coordinateScale" -> Double.valueOf(1.0D);
                default -> zero(types[i]);
            };
        }
        var constructor = DimensionType.class.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return (DimensionType) constructor.newInstance(values);
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == float.class) return Float.valueOf(0.0F);
        if (type == double.class) return Double.valueOf(0.0D);
        if (type == long.class) return Long.valueOf(0L);
        return null;
    }

    public static class FakeLevel extends ServerLevel {
        boolean ocean;
        GameRules rules;
        WorldBorder border;
        DimensionType dimension;
        MinecraftServer server;
        int chunkLookups;

        private FakeLevel() {
            super(/*SERVER_LEVEL_SUPER_ARGUMENTS*/);
        }

        BlockState at(int y) {
            if (y <= (ocean ? WATER_TOP - 10 : SURFACE)) return Blocks.STONE.defaultBlockState();
            if (ocean && y <= WATER_TOP) return Blocks.WATER.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }

        int top() {
            return ocean ? WATER_TOP : SURFACE;
        }

        @Override public MinecraftServer getServer() { return server; }
        @Override public GameRules getGameRules() { return rules; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public DimensionType dimensionType() { return dimension; }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public BlockState getBlockState(BlockPos pos) { return at(pos.getY()); }

        @Override
        public LevelChunk getChunk(int x, int z) {
            chunkLookups++;
            try {
                FakeChunk chunk = (FakeChunk) unsafe.allocateInstance(FakeChunk.class);
                chunk.level = this;
                return chunk;
            } catch (InstantiationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        /** Water counts as a collision here, as for vanilla's noCollisionNoLiquid. */
        @Override
        public boolean noCollision(Entity entity, AABB box, boolean liquid) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                if (!at(y).isAir()) return false;
            }
            return true;
        }
    }

    public static class FakeChunk extends LevelChunk {
        FakeLevel level;

        private FakeChunk() {
            super(null, null);
        }

        /** Heightmap value: the highest matching block's y. */
        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            if (type == Heightmap.Types.OCEAN_FLOOR) {
                return level.ocean ? WATER_TOP - 10 : SURFACE;
            }
            return level.top();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
