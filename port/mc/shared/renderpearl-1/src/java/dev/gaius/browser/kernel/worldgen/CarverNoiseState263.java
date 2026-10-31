package dev.gaius.browser.kernel.worldgen;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * The Java-facing state needed by 26.3 carvers after the Rust terrain fill.
 *
 * <p>The table deliberately stores the schedule bit with each aquifer answer.  Vanilla reads
 * that bit immediately after the corresponding {@code computeSubstance} call; a chunk-wide bit
 * loses that ordering and changes fluid post-processing.</p>
 */
public final class CarverNoiseState263 {
    private CarverNoiseState263() {
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

    private record SamplerKey(int slot, int x, int y, int z) {
    }

    /** Mutable per-chunk recording/replay table. It is intentionally not shared between chunks. */
    public static final class Table {
        private final Map<AquiferKey, AquiferAnswer> aquifer = new HashMap<>();
        private final Map<SamplerKey, Float> samplers = new HashMap<>();
        private final IdentityHashMap<DensityFunction, Integer> slots = new IdentityHashMap<>();
        private int nextSlot;
        private int aquiferQueries;
        private int samplerQueries;
        private int misses;

        int slot(DensityFunction function) {
            Integer existing = slots.get(function);
            if (existing != null) {
                return existing;
            }
            int assigned = nextSlot++;
            slots.put(function, assigned);
            return assigned;
        }

        void recordAquifer(int x, int y, int z, double density, BlockState state, boolean schedule) {
            aquiferQueries++;
            aquifer.put(new AquiferKey(x, y, z, Double.doubleToRawLongBits(density)),
                    new AquiferAnswer(state, schedule));
        }

        AquiferAnswer aquifer(int x, int y, int z, double density) {
            aquiferQueries++;
            AquiferAnswer answer = aquifer.get(new AquiferKey(x, y, z, Double.doubleToRawLongBits(density)));
            if (answer == null && !aquifer.containsKey(new AquiferKey(x, y, z,
                    Double.doubleToRawLongBits(density)))) {
                misses++;
                throw new MissingQuery("26.3 aquifer query missing at " + x + "," + y + "," + z
                        + " densityBits=" + Long.toUnsignedString(Double.doubleToRawLongBits(density)));
            }
            return answer;
        }

        void recordSampler(int slot, int x, int y, int z, float value) {
            samplerQueries++;
            samplers.put(new SamplerKey(slot, x, y, z), value);
        }

        float sampler(int slot, int x, int y, int z) {
            samplerQueries++;
            SamplerKey key = new SamplerKey(slot, x, y, z);
            Float value = samplers.get(key);
            if (value == null && !samplers.containsKey(key)) {
                misses++;
                throw new MissingQuery("26.3 sampler query missing slot=" + slot + " at "
                        + x + "," + y + "," + z);
            }
            return value;
        }

        public int aquiferQueries() {
            return aquiferQueries;
        }

        public int samplerQueries() {
            return samplerQueries;
        }

        public int samplerSlots() {
            return nextSlot;
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
        public BlockState computeSubstance(int x, int y, int z, double density) {
            BlockState state = delegate.computeSubstance(x, y, z, density);
            lastSchedule = delegate.shouldScheduleFluidUpdate();
            table.recordAquifer(x, y, z, density, state, lastSchedule);
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
        public BlockState computeSubstance(int x, int y, int z, double density) {
            AquiferAnswer answer = table.aquifer(x, y, z, density);
            lastSchedule = answer.schedule;
            return answer.state;
        }

        @Override
        public boolean shouldScheduleFluidUpdate() {
            return lastSchedule;
        }
    }

    private abstract static class SamplerWrapper implements DensitySampler {
        protected final DensitySampler delegate;
        protected final Table table;
        protected final int slot;

        SamplerWrapper(DensitySampler delegate, Table table, int slot) {
            this.delegate = delegate;
            this.table = table;
            this.slot = slot;
        }

        protected abstract float value(SamplerContext context, int x, int y, int z);

        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return value(context, x, y, z);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            for (int x = 0; x < volume.sizeX(); x++) {
                for (int y = 0; y < volume.sizeY(); y++) {
                    for (int z = 0; z < volume.sizeZ(); z++) {
                        int blockX = volume.minBlockX() + x * volume.stepBlockX();
                        int blockY = volume.minBlockY() + y * volume.stepBlockY();
                        int blockZ = volume.minBlockZ() + z * volume.stepBlockZ();
                        buffer.set(volume.indexUnchecked(x, y, z),
                                value(context, blockX, blockY, blockZ));
                    }
                }
            }
        }
    }

    private static final class RecordingSampler extends SamplerWrapper {
        RecordingSampler(DensitySampler delegate, Table table, int slot) {
            super(delegate, table, slot);
        }

        @Override
        protected float value(SamplerContext context, int x, int y, int z) {
            float result = delegate.sampleValue(context, x, y, z);
            table.recordSampler(slot, x, y, z, result);
            return result;
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            delegate.sampleVolume(context, buffer, volume);
            for (int x = 0; x < volume.sizeX(); x++) {
                for (int y = 0; y < volume.sizeY(); y++) {
                    for (int z = 0; z < volume.sizeZ(); z++) {
                        int blockX = volume.minBlockX() + x * volume.stepBlockX();
                        int blockY = volume.minBlockY() + y * volume.stepBlockY();
                        int blockZ = volume.minBlockZ() + z * volume.stepBlockZ();
                        table.recordSampler(slot, blockX, blockY, blockZ,
                                buffer.get(volume.indexUnchecked(x, y, z)));
                    }
                }
            }
        }
    }

    private static final class ReplaySampler extends SamplerWrapper {
        ReplaySampler(DensitySampler delegate, Table table, int slot) {
            super(delegate, table, slot);
        }

        @Override
        protected float value(SamplerContext context, int x, int y, int z) {
            return table.sampler(slot, x, y, z);
        }
    }

    public static final class RecordingSamplerSet implements DensitySamplerSet {
        private final DensitySamplerSet delegate;
        private final Table table;

        public RecordingSamplerSet(DensitySamplerSet delegate, Table table) {
            this.delegate = delegate;
            this.table = table;
        }

        @Override
        public DensitySampler.Bound get(DensityFunction function) {
            int slot = table.slot(function);
            DensitySampler.Bound bound = delegate.get(function);
            return new DensitySampler.Bound(new RecordingSampler(bound.sampler(), table, slot), bound.context());
        }
    }

    public static final class ReplaySamplerSet implements DensitySamplerSet {
        private final DensitySamplerSet delegate;
        private final Table table;

        public ReplaySamplerSet(DensitySamplerSet delegate, Table table) {
            this.delegate = delegate;
            this.table = table;
        }

        @Override
        public DensitySampler.Bound get(DensityFunction function) {
            int slot = table.slot(function);
            DensitySampler.Bound bound = delegate.get(function);
            return new DensitySampler.Bound(new ReplaySampler(bound.sampler(), table, slot), bound.context());
        }
    }

    /** A JVM probe chunk that routes the two public state accessors through a recording table. */
    public static final class RecordingNoiseChunk extends NoiseChunk {
        private final RecordingAquifer aquifer;
        private final RecordingSamplerSet samplers;

        public RecordingNoiseChunk(RandomState randomState, Beardifier beardifier,
                NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender,
                DensityVolume volume, Table table) {
            super(randomState, beardifier, settings, picker, blender, volume);
            this.aquifer = new RecordingAquifer(super.aquifer(), table);
            this.samplers = new RecordingSamplerSet(super.cachingSamplers(), table);
        }

        @Override
        public Aquifer aquifer() {
            return aquifer;
        }

        @Override
        public DensitySamplerSet cachingSamplers() {
            return samplers;
        }
    }

    /** A JVM probe chunk that replays the table without evaluating vanilla aquifer/sampler state. */
    public static final class ReplayNoiseChunk extends NoiseChunk {
        private final ReplayAquifer aquifer;
        private final ReplaySamplerSet samplers;

        public ReplayNoiseChunk(RandomState randomState, Beardifier beardifier,
                NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender,
                DensityVolume volume, Table table) {
            super(randomState, beardifier, settings, picker, blender, volume);
            this.aquifer = new ReplayAquifer(table);
            this.samplers = new ReplaySamplerSet(super.cachingSamplers(), table);
        }

        @Override
        public Aquifer aquifer() {
            return aquifer;
        }

        @Override
        public DensitySamplerSet cachingSamplers() {
            return samplers;
        }
    }

    /** Installs the production override added to the mirrored NoiseChunk by the ASM patcher. */
    public static void install(NoiseChunk noiseChunk, Aquifer aquifer, DensitySamplerSet samplers) {
        try {
            Method method = NoiseChunk.class.getMethod("gaius$installCarverState",
                    Aquifer.class, DensitySamplerSet.class);
            method.invoke(noiseChunk, aquifer, samplers);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("26.3 NoiseChunk carver-state seam is absent", e);
        }
    }

    public static void install(NoiseChunk noiseChunk, Table table) {
        install(noiseChunk, new ReplayAquifer(table),
                new ReplaySamplerSet(noiseChunk.cachingSamplers(), table));
    }
}
