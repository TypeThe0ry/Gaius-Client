package dev.gaius.browser.kernel.worldgen;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Java carver state for the 26.2 interpolation based NoiseChunk. */
public final class CarverNoiseState262 {
    private CarverNoiseState262() {
    }

    public static final class MissingQuery extends IllegalStateException {
        public MissingQuery(String message) {
            super(message);
        }
    }

    public static final class AquiferAnswer {
        public final BlockState state;
        public final boolean schedule;

        AquiferAnswer(BlockState state, boolean schedule) {
            this.state = state;
            this.schedule = schedule;
        }
    }

    private record AquiferKey(int x, int y, int z, long densityBits) {
    }

    private record SurfaceKey(int x, int z) {
    }

    public static final class Table {
        private final Map<AquiferKey, AquiferAnswer> aquifer = new HashMap<>();
        private final Map<SurfaceKey, Integer> preliminarySurface = new HashMap<>();
        private int aquiferQueries;
        private int preliminaryQueries;
        private int misses;

        void recordAquifer(int x, int y, int z, double density, BlockState state, boolean schedule) {
            aquiferQueries++;
            aquifer.put(new AquiferKey(x, y, z, Double.doubleToRawLongBits(density)),
                    new AquiferAnswer(state, schedule));
        }

        AquiferAnswer aquifer(int x, int y, int z, double density) {
            aquiferQueries++;
            AquiferKey key = new AquiferKey(x, y, z, Double.doubleToRawLongBits(density));
            AquiferAnswer answer = aquifer.get(key);
            if (answer == null && !aquifer.containsKey(key)) {
                misses++;
                throw new MissingQuery("26.2 aquifer query missing at " + x + "," + y + "," + z
                        + " densityBits=" + Long.toUnsignedString(key.densityBits()));
            }
            return answer;
        }

        void recordPreliminarySurface(int x, int z, int value) {
            preliminaryQueries++;
            preliminarySurface.put(new SurfaceKey(x, z), value);
        }

        int preliminarySurface(int x, int z) {
            preliminaryQueries++;
            SurfaceKey key = new SurfaceKey(x, z);
            Integer value = preliminarySurface.get(key);
            if (value == null && !preliminarySurface.containsKey(key)) {
                misses++;
                throw new MissingQuery("26.2 preliminary surface query missing at " + x + "," + z);
            }
            return value;
        }

        public int aquiferQueries() {
            return aquiferQueries;
        }

        public int preliminaryQueries() {
            return preliminaryQueries;
        }

        public int misses() {
            return misses;
        }
    }

    public static final class RecordingAquifer implements Aquifer {
        private final Aquifer delegate;
        private final Table table;
        private boolean lastSchedule;

        public RecordingAquifer(Aquifer delegate, Table table) {
            this.delegate = delegate;
            this.table = table;
        }

        @Override
        public BlockState computeSubstance(DensityFunction.FunctionContext context, double density) {
            BlockState state = delegate.computeSubstance(context, density);
            lastSchedule = delegate.shouldScheduleFluidUpdate();
            table.recordAquifer(context.blockX(), context.blockY(), context.blockZ(), density,
                    state, lastSchedule);
            return state;
        }

        @Override
        public boolean shouldScheduleFluidUpdate() {
            return lastSchedule;
        }
    }

    public static final class ReplayAquifer implements Aquifer {
        private final Table table;
        private boolean lastSchedule;

        public ReplayAquifer(Table table) {
            this.table = table;
        }

        @Override
        public BlockState computeSubstance(DensityFunction.FunctionContext context, double density) {
            AquiferAnswer answer = table.aquifer(context.blockX(), context.blockY(), context.blockZ(), density);
            lastSchedule = answer.schedule;
            return answer.state;
        }

        @Override
        public boolean shouldScheduleFluidUpdate() {
            return lastSchedule;
        }
    }

    /** A NoiseChunk whose public carver-facing queries are recorded. */
    public static final class RecordingNoiseChunk extends NoiseChunk {
        private final RecordingAquifer aquifer;
        private final Table table;

        public RecordingNoiseChunk(int cellCountXZ, RandomState randomState, int firstCellX, int firstCellZ,
                NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
                NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender, Table table) {
            super(cellCountXZ, randomState, firstCellX, firstCellZ, noiseSettings, beardifier,
                    settings, picker, blender);
            this.table = table;
            this.aquifer = new RecordingAquifer(super.aquifer(), table);
        }

        @Override
        public Aquifer aquifer() {
            return aquifer;
        }

        @Override
        public int preliminarySurfaceLevel(int x, int z) {
            int value = super.preliminarySurfaceLevel(x, z);
            if (table == null) {
                return value;
            }
            table.recordPreliminarySurface(x, z, value);
            return value;
        }
    }

    /** A NoiseChunk whose public carver-facing queries replay a table. */
    public static final class ReplayNoiseChunk extends NoiseChunk {
        private final ReplayAquifer aquifer;
        private final Table table;

        public ReplayNoiseChunk(int cellCountXZ, RandomState randomState, int firstCellX, int firstCellZ,
                NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
                NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender, Table table) {
            super(cellCountXZ, randomState, firstCellX, firstCellZ, noiseSettings, beardifier,
                    settings, picker, blender);
            this.table = table;
            this.aquifer = new ReplayAquifer(table);
        }

        @Override
        public Aquifer aquifer() {
            return aquifer;
        }

        @Override
        public int preliminarySurfaceLevel(int x, int z) {
            if (table == null) {
                return super.preliminarySurfaceLevel(x, z);
            }
            return table.preliminarySurface(x, z);
        }
    }

    public static RecordingNoiseChunk recordingForChunk(ChunkAccess chunk, RandomState randomState,
            NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender, Table table) {
        NoiseSettings noise = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int cellCountXZ = 16 / noise.getCellWidth();
        return new RecordingNoiseChunk(cellCountXZ, randomState, chunk.getPos().getMinBlockX(),
                chunk.getPos().getMinBlockZ(), noise, beardifierMarker(), settings, picker, blender, table);
    }

    public static ReplayNoiseChunk replayForChunk(ChunkAccess chunk, RandomState randomState,
            NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender, Table table) {
        NoiseSettings noise = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int cellCountXZ = 16 / noise.getCellWidth();
        return new ReplayNoiseChunk(cellCountXZ, randomState, chunk.getPos().getMinBlockX(),
                chunk.getPos().getMinBlockZ(), noise, beardifierMarker(), settings, picker, blender, table);
    }

    private static DensityFunctions.BeardifierOrMarker beardifierMarker() {
        try {
            Class<?> marker = Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker");
            java.lang.reflect.Field field = marker.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            return (DensityFunctions.BeardifierOrMarker) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("26.2 beardifier marker unavailable", e);
        }
    }

    /** Installs the production override added to the mirrored NoiseChunk by the ASM patcher. */
    public static void install(NoiseChunk noiseChunk, Aquifer aquifer, IntBinaryOperator preliminarySurface) {
        try {
            Method method = NoiseChunk.class.getMethod("gaius$installCarverState",
                    Aquifer.class, IntBinaryOperator.class);
            method.invoke(noiseChunk, aquifer, preliminarySurface);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("26.2 NoiseChunk carver-state seam is absent", e);
        }
    }

    public static void install(NoiseChunk noiseChunk, Table table) {
        install(noiseChunk, new ReplayAquifer(table), table::preliminarySurface);
    }
}
