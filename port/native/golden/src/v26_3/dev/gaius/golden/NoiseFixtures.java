package dev.gaius.golden;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.LegacyFbmInitializer;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Noise fixtures for 26.3, whose noise stack is float based: GradientNoise
 * (PerlinNoise, SmearedPerlinNoise, SimplexNoise) layered by NoiseStack, with
 * NormalNoise as a registry-held recipe and BlendedNoise compiled to a
 * DensitySampler. Outputs are floats (widened to double for encoding).
 *
 * <p>Every case carries the random spec of {@link Rngs}; "volume" methods use
 * the layout described in {@link Volumes}. Kind-specific params:
 * <ul>
 *   <li>improved_noise (single-octave gradient noise): "method" "perlin3" (inputs
 *       [x,y,z], PerlinNoise.get), "perlin2" (inputs [x,y], PerlinNoise.get),
 *       "smeared3" (inputs [x,y,z], SmearedPerlinNoise(random, fudge_y_scale).get),
 *       "perlin_volume" or "smeared_volume" (addToVolume); "xo","yo","zo" are the offsets.</li>
 *   <li>perlin_noise (octave stacks): "ctor" "fbm" (BlendedNoise.createFbm(random,
 *       first_octave, fudge_y_scale, fbm_amplitude)) or "legacy_nether"
 *       (LegacyFbmInitializer.createForLegacyNetherBiome(random, first_octave, amplitudes));
 *       "method" "value3" (inputs [x,y,z]) or "volume".</li>
 *   <li>normal_noise: "noise" registry id, "parity" (NormalNoise.createParity(first_octave,
 *       amplitudes)) or "builder"; the resolved recipe "base_amplitude", "base_octave",
 *       "octave_count", "normalize", "amplitude_modifiers", "normalization_factor" and
 *       "octaves" [{index, frequency, amplitude, seed}]; "ctor" "create" |
 *       "legacy_nether"; "method" "value3" or "volume".</li>
 *   <li>simplex_noise: "zero_offset" (SimplexNoise(random, true)); "method" "value2"
 *       (inputs [x,y]) or "value3" (inputs [x,y,z]).</li>
 *   <li>blended_noise: "source" plus "xz_scale","y_scale","xz_factor","y_factor",
 *       "smear_scale_multiplier"; "method" "value" (inputs [x,y,z] ints,
 *       compileSampler(random).sampleValue) or "volume" (sampleVolume).</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // the legacy constructors are still what vanilla calls
final class NoiseFixtures {
    private static final int ROWS = 64;
    private static final String[] RANDOMS = {Rngs.XOROSHIRO, Rngs.LEGACY};

    private NoiseFixtures() {
    }

    static void write(FixtureSink sink) throws ReflectiveOperationException {
        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        writeImproved(sink);
        writePerlin(sink);
        writeNormal(sink, lookup);
        writeSimplex(sink);
        writeBlended(sink, lookup);
    }

    private static void writeImproved(FixtureSink sink) throws ReflectiveOperationException {
        Random r = Inputs.random("improved_noise");
        double[] fudges = {684.412, 85.5515, 2.67348, 1.0, 1.0E-3};
        int line = 0;
        for (String type : RANDOMS) {
            for (long seed : Inputs.SEEDS) {
                Case three = new Case("improved_noise").param("method", "perlin3");
                PerlinNoise perlin = new PerlinNoise(Rngs.seeded(three, type, seed));
                sink.write(fill3(origin(three, perlin), r, ROWS, perlin));

                Case two = new Case("improved_noise").param("method", "perlin2");
                perlin = new PerlinNoise(Rngs.seeded(two, type, seed));
                origin(two, perlin);
                for (int i = 0; i < ROWS; i++) {
                    double x = Inputs.coord(r), z = Inputs.coord(r);
                    two.input(x, z).output(perlin.get(x, z));
                }
                sink.write(two);

                double fudge = fudges[line % fudges.length];
                Case smeared = new Case("improved_noise").param("method", "smeared3").param("fudge_y_scale", fudge);
                SmearedPerlinNoise smearedNoise = new SmearedPerlinNoise(Rngs.seeded(smeared, type, seed), fudge);
                sink.write(fill3(origin(smeared, smearedNoise), r, ROWS, smearedNoise));

                Case volume = new Case("improved_noise").param("method", "perlin_volume");
                perlin = new PerlinNoise(Rngs.seeded(volume, type, seed));
                sink.write(Volumes.addToVolume(origin(volume, perlin), r, line, perlin));

                Case smearedVolume = new Case("improved_noise").param("method", "smeared_volume")
                        .param("fudge_y_scale", fudge);
                smearedNoise = new SmearedPerlinNoise(Rngs.seeded(smearedVolume, type, seed), fudge);
                sink.write(Volumes.addToVolume(origin(smearedVolume, smearedNoise), r, line + 1, smearedNoise));
                line++;
            }
        }
    }

    private static void writePerlin(FixtureSink sink) {
        Random r = Inputs.random("perlin_noise");
        // first octave, fudge y scale, amplitude: the three stacks BlendedNoise builds plus extremes.
        double[][] fbm = {{-15, 684.412, 0.9999847412109375}, {-7, 85.5515, 12.75}, {-3, 2.0, 1.0}, {0, 1.0, 0.5}};
        int line = 0;
        for (double[] config : fbm) {
            for (String type : RANDOMS) {
                long seed = Inputs.SEEDS[line % Inputs.SEEDS.length];
                for (String method : new String[] {"value3", "volume"}) {
                    Case c = new Case("perlin_noise").param("ctor", "fbm").param("method", method)
                            .param("first_octave", (int) config[0]).param("fudge_y_scale", config[1])
                            .param("fbm_amplitude", config[2]);
                    Noise noise = BlendedNoise.createFbm(Rngs.seeded(c, type, seed), (int) config[0], config[1], config[2]);
                    sink.write(sample(c, r, line, method, noise));
                }
                line++;
            }
        }
        Object[][] nether = {{-7, amplitudes(1.0, 1.0)}, {-3, amplitudes(1.0, 0.0, 1.0)}, {0, amplitudes(1.0)},
            {-5, amplitudes(0.0, 0.0, 2.5, 1.0, 0.0)}};
        for (Object[] config : nether) {
            int firstOctave = (Integer) config[0];
            DoubleList amps = (DoubleList) config[1];
            for (String type : RANDOMS) {
                long seed = Inputs.SEEDS[line % Inputs.SEEDS.length];
                for (String method : new String[] {"value3", "volume"}) {
                    Case c = new Case("perlin_noise").param("ctor", "legacy_nether").param("method", method)
                            .param("first_octave", firstOctave).param("amplitudes", amps.toDoubleArray());
                    Noise noise = LegacyFbmInitializer.createForLegacyNetherBiome(
                            Rngs.seeded(c, type, seed), firstOctave, amps);
                    sink.write(sample(c, r, line, method, noise));
                }
                line++;
            }
        }
    }

    private static void writeNormal(FixtureSink sink, HolderLookup.Provider lookup) throws ReflectiveOperationException {
        Random r = Inputs.random("normal_noise");
        List<Holder.Reference<NormalNoise>> registry = lookup.lookupOrThrow(Registries.NOISE)
                .listElements()
                .sorted(Comparator.comparing(ref -> ref.key().identifier().toString()))
                .toList();
        int line = 0;
        for (Holder.Reference<NormalNoise> ref : registry) {
            String id = ref.key().identifier().toString();
            NormalNoise recipe = ref.value();
            // Worldgen path: a positional fork of the world seed keyed by the noise id.
            Case modern = NormalRecipe.describe(new Case("normal_noise").param("ctor", "create")
                    .param("method", "value3").param("noise", id), recipe);
            Noise noise = recipe.create(Rngs.forked(modern, Rngs.XOROSHIRO, Inputs.WORLD_SEED, id));
            sink.write(fill3(modern, r, 24, noise));

            Case volume = NormalRecipe.describe(new Case("normal_noise").param("ctor", "create")
                    .param("method", "volume").param("noise", id), recipe);
            noise = recipe.create(Rngs.forked(volume, Rngs.XOROSHIRO, Inputs.WORLD_SEED, id));
            sink.write(Volumes.addToVolume(volume, r, line++, noise));

            Case legacy = NormalRecipe.describe(new Case("normal_noise").param("ctor", "create")
                    .param("method", "value3").param("noise", id), recipe);
            noise = recipe.create(Rngs.forked(legacy, Rngs.LEGACY, Inputs.WORLD_SEED, id));
            sink.write(fill3(legacy, r, 8, noise));
        }

        List<Object[]> synthetic = new ArrayList<>();
        synthetic.add(new Object[] {"parity", -7, new double[] {1.0, 1.0}});
        synthetic.add(new Object[] {"parity", -3, new double[] {1.0, 0.0, 1.0}});
        synthetic.add(new Object[] {"parity", 0, new double[] {1.0}});
        synthetic.add(new Object[] {"parity", -9, new double[] {0.0, 0.0, 1.0, 1.0, 0.0, 2.0}});
        synthetic.add(new Object[] {"parity", -12, new double[] {1.5, 0.5, 0.25, 0.125, 0.0, 0.0, 1.0}});
        for (Object[] config : synthetic) {
            int firstOctave = (Integer) config[1];
            double[] amps = (double[]) config[2];
            NormalNoise recipe = NormalNoise.createParity(firstOctave, amps);
            for (String type : RANDOMS) {
                for (String method : new String[] {"value3", "volume"}) {
                    Case c = NormalRecipe.describe(new Case("normal_noise").param("ctor", "create")
                            .param("method", method).param("noise", "parity")
                            .param("parity_first_octave", firstOctave).param("parity_amplitudes", amps), recipe);
                    Noise noise = recipe.create(Rngs.seeded(c, type, Inputs.SEEDS[line % Inputs.SEEDS.length]));
                    sink.write(sample(c, r, line++, method, noise));
                }
            }
            Case c = NormalRecipe.describe(new Case("normal_noise").param("ctor", "legacy_nether")
                    .param("method", "value3").param("noise", "parity")
                    .param("parity_first_octave", firstOctave).param("parity_amplitudes", amps), recipe);
            Noise noise = recipe.createForLegacyNetherBiome(
                    Rngs.seeded(c, Rngs.LEGACY, Inputs.SEEDS[line++ % Inputs.SEEDS.length]));
            sink.write(fill3(c, r, 32, noise));
        }

        NormalNoise[] built = {
            NormalNoise.builder().setBaseOctave(-6).setOctaveCount(4).build(),
            NormalNoise.builder().setBaseAmplitude(2.0).setBaseOctave(-10).setOctaveCount(6)
                    .setNormalize(false).setAmplitudeModifier(1, 0.0).setAmplitudeModifier(3, 0.5).build(),
            NormalNoise.builder().setBaseOctave(-8).setOctaveCount(3).setLegacyNormalization().build(),
        };
        for (NormalNoise recipe : built) {
            for (String method : new String[] {"value3", "volume"}) {
                Case c = NormalRecipe.describe(new Case("normal_noise").param("ctor", "create")
                        .param("method", method).param("noise", "builder"), recipe);
                Noise noise = recipe.create(Rngs.seeded(c, Rngs.XOROSHIRO, Inputs.SEEDS[line % Inputs.SEEDS.length]));
                sink.write(sample(c, r, line++, method, noise));
            }
        }
    }

    private static void writeSimplex(FixtureSink sink) throws ReflectiveOperationException {
        Random r = Inputs.random("simplex_noise");
        for (String type : RANDOMS) {
            for (long seed : Inputs.SEEDS) {
                for (boolean zeroOffset : new boolean[] {false, true}) {
                    Case two = new Case("simplex_noise").param("method", "value2").param("zero_offset", zeroOffset);
                    SimplexNoise noise = new SimplexNoise(Rngs.seeded(two, type, seed), zeroOffset);
                    origin(two, noise);
                    for (int i = 0; i < ROWS / 2; i++) {
                        double x = Inputs.coord(r), y = Inputs.coord(r);
                        two.input(x, y).output(noise.get(x, y));
                    }
                    sink.write(two);

                    Case three = new Case("simplex_noise").param("method", "value3").param("zero_offset", zeroOffset);
                    noise = new SimplexNoise(Rngs.seeded(three, type, seed), zeroOffset);
                    sink.write(fill3(origin(three, noise), r, ROWS / 2, noise));
                }
            }
        }
    }

    private static void writeBlended(FixtureSink sink, HolderLookup.Provider lookup)
            throws ReflectiveOperationException {
        Random r = Inputs.random("blended_noise");
        HolderLookup.RegistryLookup<DensityFunction> functions = lookup.lookupOrThrow(Registries.DENSITY_FUNCTION);
        List<Object[]> configs = new ArrayList<>();
        for (String field : new String[] {"BASE_3D_NOISE_OVERWORLD", "BASE_3D_NOISE_NETHER", "BASE_3D_NOISE_END"}) {
            Field f = NoiseRouterData.class.getDeclaredField(field);
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            ResourceKey<DensityFunction> key = (ResourceKey<DensityFunction>) f.get(null);
            BlendedNoise b = (BlendedNoise) functions.getOrThrow(key).value();
            configs.add(new Object[] {key.identifier().toString(),
                new double[] {b.xzScale(), b.yScale(), b.xzFactor(), b.yFactor(), b.smearScaleMultiplier()}});
        }
        configs.add(new Object[] {"synthetic", new double[] {1.0, 1.0, 80.0, 160.0, 8.0}});
        configs.add(new Object[] {"synthetic", new double[] {0.25, 0.375, 80.0, 60.0, 8.0}});
        configs.add(new Object[] {"synthetic", new double[] {0.001, 1000.0, 0.001, 1000.0, 1.0}});

        String terrain = BlendedNoise.NOISE_SEED.toString();
        String[][] randoms = {{Rngs.XOROSHIRO, terrain}, {Rngs.LEGACY, null}, {Rngs.XOROSHIRO, null}};
        int line = 0;
        for (Object[] config : configs) {
            double[] p = (double[]) config[1];
            BlendedNoise blended = new BlendedNoise(p[0], p[1], p[2], p[3], p[4]);
            for (String[] spec : randoms) {
                for (String method : new String[] {"value", "volume"}) {
                    Case c = new Case("blended_noise").param("method", method).param("source", config[0])
                            .param("xz_scale", p[0]).param("y_scale", p[1]).param("xz_factor", p[2])
                            .param("y_factor", p[3]).param("smear_scale_multiplier", p[4]);
                    RandomSource random = Rngs.forked(c, spec[0], Inputs.WORLD_SEED, spec[1]);
                    DensitySampler sampler = blended.compileSampler(random);
                    if (method.equals("value")) {
                        for (int i = 0; i < ROWS / 2; i++) {
                            int x = Inputs.block(r), y = Inputs.blockY(r), z = Inputs.block(r);
                            c.input(x, y, z).output(sampler.sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z));
                        }
                    } else {
                        DensityVolume volume = Volumes.volume(c, r, line++);
                        DensityBuffer buffer = DensityBuffer.createUnpooled(volume.size());
                        sampler.sampleVolume(SamplerContext.EMPTY_UNCACHED, buffer, volume);
                        Volumes.outputs(c, buffer, volume);
                    }
                    sink.write(c);
                }
            }
        }
    }

    private static Case sample(Case c, Random r, int line, String method, Noise noise) {
        return method.equals("volume") ? Volumes.addToVolume(c, r, line, noise) : fill3(c, r, ROWS / 2, noise);
    }

    private static Case fill3(Case c, Random r, int rows, Noise noise) {
        for (int i = 0; i < rows; i++) {
            double x = Inputs.coord(r), y = Inputs.coord(r), z = Inputs.coord(r);
            c.input(x, y, z).output(noise.get(x, y, z));
        }
        return c;
    }

    private static Case origin(Case c, GradientNoise noise) throws ReflectiveOperationException {
        for (String[] names : new String[][] {{"xo", "offsetX"}, {"yo", "offsetY"}, {"zo", "offsetZ"}}) {
            Field f = GradientNoise.class.getDeclaredField(names[1]);
            f.setAccessible(true);
            c.param(names[0], f.getDouble(noise));
        }
        return c;
    }

    private static DoubleList amplitudes(double... values) {
        return new DoubleArrayList(values);
    }
}
