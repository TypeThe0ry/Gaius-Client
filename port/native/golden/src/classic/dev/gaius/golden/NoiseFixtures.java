package dev.gaius.golden;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Noise fixtures for the double-precision noise stack of 1.21.11 and 26.2.
 *
 * <p>Every case carries the random spec of {@link Rngs}. Kind-specific params:
 * <ul>
 *   <li>improved_noise: "method" "noise3" (inputs [x,y,z]) or "noise5" (inputs
 *       [x,y,z,yScale,yMax]); "xo","yo","zo" are the constructed origin.</li>
 *   <li>perlin_noise: "ctor" "create" (PerlinNoise.create(random, first_octave, amplitudes)),
 *       "legacy_blended" (createLegacyForBlendedNoise(random, octaves)) or "legacy_nether"
 *       (createLegacyForLegacyNetherBiome(random, first_octave, amplitudes)); inputs [x,y,z]
 *       for getValue(x,y,z).</li>
 *   <li>normal_noise: "noise" registry id or "synthetic", "first_octave", "amplitudes",
 *       "ctor" "create" | "legacy_nether"; inputs [x,y,z] for getValue(x,y,z).</li>
 *   <li>simplex_noise: "method" "value2" (inputs [x,y]) or "value3" (inputs [x,y,z]).</li>
 *   <li>blended_noise: "method" "compute"; "source" registry id or "synthetic" plus the codec fields
 *       "xz_scale","y_scale","xz_factor","y_factor","smear_scale_multiplier";
 *       inputs [x,y,z] ints for compute(new SinglePointContext(x,y,z)).</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // the legacy constructors are still what vanilla calls
final class NoiseFixtures {
    private static final int ROWS = 64;

    private NoiseFixtures() {
    }

    static void write(FixtureSink sink) throws ReflectiveOperationException {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        writeImproved(sink);
        writePerlin(sink, lookup);
        writeNormal(sink, lookup);
        writeSimplex(sink);
        writeBlended(sink, lookup);
    }

    private static void writeImproved(FixtureSink sink) {
        Random r = Inputs.random("improved_noise");
        for (String type : new String[] {Rngs.XOROSHIRO, Rngs.LEGACY}) {
            for (long seed : Inputs.SEEDS) {
                Case three = new Case("improved_noise").param("method", "noise3");
                ImprovedNoise noise = new ImprovedNoise(Rngs.seeded(three, type, seed));
                origin(three, noise.xo, noise.yo, noise.zo);
                for (int i = 0; i < ROWS; i++) {
                    double x = Inputs.coord(r), y = Inputs.coord(r), z = Inputs.coord(r);
                    three.input(x, y, z).output(noise.noise(x, y, z));
                }
                sink.write(three);

                Case five = new Case("improved_noise").param("method", "noise5");
                noise = new ImprovedNoise(Rngs.seeded(five, type, seed));
                origin(five, noise.xo, noise.yo, noise.zo);
                for (int i = 0; i < ROWS; i++) {
                    double x = Inputs.coord(r), y = Inputs.coord(r), z = Inputs.coord(r);
                    double yScale = switch (r.nextInt(4)) {
                        case 0 -> 0.0;
                        case 1 -> r.nextDouble() * 8.0;
                        case 2 -> 684.412 / (1 << r.nextInt(16));
                        default -> 1.0;
                    };
                    double yMax = r.nextBoolean() ? y : Inputs.coord(r);
                    five.input(x, y, z, yScale, yMax).output(noise.noise(x, y, z, yScale, yMax));
                }
                sink.write(five);
            }
        }
    }

    private static void writePerlin(FixtureSink sink, HolderLookup.Provider lookup) {
        Random r = Inputs.random("perlin_noise");
        List<Object[]> configs = new java.util.ArrayList<>(List.of(
                new Object[] {-7, amplitudes(1.0, 1.0)},
                new Object[] {-3, amplitudes(1.0, 0.0, 1.0)},
                new Object[] {0, amplitudes(1.0)},
                new Object[] {-5, amplitudes(0.0, 0.0, 2.5, 1.0, 0.0)},
                new Object[] {-16, amplitudes(1.5, 0.0, 1.0, 0.0, 0.0, 0.0)},
                new Object[] {-10, amplitudes(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)}));
        NormalNoise.NoiseParameters continentalness = lookup.lookupOrThrow(Registries.NOISE)
                .getOrThrow(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS).value();
        configs.add(new Object[] {continentalness.firstOctave(), continentalness.amplitudes()});

        int line = 0;
        for (Object[] config : configs) {
            int firstOctave = (Integer) config[0];
            DoubleList amps = (DoubleList) config[1];
            for (String type : new String[] {Rngs.XOROSHIRO, Rngs.LEGACY}) {
                long seed = Inputs.SEEDS[line++ % Inputs.SEEDS.length];
                Case c = new Case("perlin_noise").param("ctor", "create")
                        .param("first_octave", firstOctave).param("amplitudes", amps.toDoubleArray());
                PerlinNoise noise = PerlinNoise.create(Rngs.seeded(c, type, seed), firstOctave, amps);
                sink.write(fill3(c, r, noise::getValue));
            }
        }
        for (int[] octaves : new int[][] {{-15, 0}, {-7, 0}, {-3, 0}}) {
            for (String type : new String[] {Rngs.XOROSHIRO, Rngs.LEGACY}) {
                long seed = Inputs.SEEDS[line++ % Inputs.SEEDS.length];
                Case c = new Case("perlin_noise").param("ctor", "legacy_blended")
                        .param("octaves", IntStream.rangeClosed(octaves[0], octaves[1]).toArray());
                PerlinNoise noise = PerlinNoise.createLegacyForBlendedNoise(
                        Rngs.seeded(c, type, seed), IntStream.rangeClosed(octaves[0], octaves[1]));
                sink.write(fill3(c, r, noise::getValue));
            }
        }
        for (Object[] config : configs.subList(0, 3)) {
            int firstOctave = (Integer) config[0];
            DoubleList amps = (DoubleList) config[1];
            Case c = new Case("perlin_noise").param("ctor", "legacy_nether")
                    .param("first_octave", firstOctave).param("amplitudes", amps.toDoubleArray());
            PerlinNoise noise = PerlinNoise.createLegacyForLegacyNetherBiome(
                    Rngs.seeded(c, Rngs.LEGACY, Inputs.SEEDS[line++ % Inputs.SEEDS.length]), firstOctave, amps);
            sink.write(fill3(c, r, noise::getValue));
        }
    }

    private static void writeNormal(FixtureSink sink, HolderLookup.Provider lookup) {
        Random r = Inputs.random("normal_noise");
        List<Holder.Reference<NormalNoise.NoiseParameters>> registry = lookup.lookupOrThrow(Registries.NOISE)
                .listElements()
                .sorted(Comparator.comparing(ref -> ref.key().identifier().toString()))
                .toList();
        for (Holder.Reference<NormalNoise.NoiseParameters> ref : registry) {
            String id = ref.key().identifier().toString();
            NormalNoise.NoiseParameters params = ref.value();
            // Worldgen path: a positional fork of the world seed keyed by the noise id.
            Case modern = normalCase(id, params, "create");
            NormalNoise noise = NormalNoise.create(Rngs.forked(modern, Rngs.XOROSHIRO, Inputs.WORLD_SEED, id), params);
            sink.write(fill3(modern, r, 32, noise::getValue));

            Case legacy = normalCase(id, params, "create");
            noise = NormalNoise.create(Rngs.forked(legacy, Rngs.LEGACY, Inputs.WORLD_SEED, id), params);
            sink.write(fill3(legacy, r, 12, noise::getValue));
        }
        NormalNoise.NoiseParameters[] synthetic = {
            new NormalNoise.NoiseParameters(-7, 1.0, 1.0),
            new NormalNoise.NoiseParameters(-3, 1.0, 0.0, 1.0),
            new NormalNoise.NoiseParameters(0, 1.0),
            new NormalNoise.NoiseParameters(-9, 0.0, 0.0, 1.0, 1.0, 0.0, 2.0),
            new NormalNoise.NoiseParameters(-12, 1.5, 0.5, 0.25, 0.125, 0.0, 0.0, 1.0),
        };
        int line = 0;
        for (NormalNoise.NoiseParameters params : synthetic) {
            for (String type : new String[] {Rngs.XOROSHIRO, Rngs.LEGACY}) {
                Case c = normalCase("synthetic", params, "create");
                NormalNoise noise = NormalNoise.create(
                        Rngs.seeded(c, type, Inputs.SEEDS[line++ % Inputs.SEEDS.length]), params);
                sink.write(fill3(c, r, noise::getValue));
            }
            Case c = normalCase("synthetic", params, "legacy_nether");
            NormalNoise noise = NormalNoise.createLegacyNetherBiome(
                    Rngs.seeded(c, Rngs.LEGACY, Inputs.SEEDS[line++ % Inputs.SEEDS.length]), params);
            sink.write(fill3(c, r, 32, noise::getValue));
        }
    }

    private static Case normalCase(String id, NormalNoise.NoiseParameters params, String ctor) {
        return new Case("normal_noise").param("ctor", ctor).param("noise", id)
                .param("first_octave", params.firstOctave())
                .param("amplitudes", params.amplitudes().toDoubleArray());
    }

    private static void writeSimplex(FixtureSink sink) {
        Random r = Inputs.random("simplex_noise");
        for (String type : new String[] {Rngs.XOROSHIRO, Rngs.LEGACY}) {
            for (long seed : Inputs.SEEDS) {
                Case two = new Case("simplex_noise").param("method", "value2");
                SimplexNoise noise = new SimplexNoise(Rngs.seeded(two, type, seed));
                origin(two, noise.xo, noise.yo, noise.zo);
                for (int i = 0; i < ROWS; i++) {
                    double x = Inputs.coord(r), y = Inputs.coord(r);
                    two.input(x, y).output(noise.getValue(x, y));
                }
                sink.write(two);

                Case three = new Case("simplex_noise").param("method", "value3");
                noise = new SimplexNoise(Rngs.seeded(three, type, seed));
                origin(three, noise.xo, noise.yo, noise.zo);
                sink.write(fill3(three, r, noise::getValue));
            }
        }
    }

    private static void writeBlended(FixtureSink sink, HolderLookup.Provider lookup)
            throws ReflectiveOperationException {
        Random r = Inputs.random("blended_noise");
        HolderLookup.RegistryLookup<DensityFunction> functions = lookup.lookupOrThrow(Registries.DENSITY_FUNCTION);
        List<Object[]> configs = new java.util.ArrayList<>();
        for (String field : new String[] {"BASE_3D_NOISE_OVERWORLD", "BASE_3D_NOISE_NETHER", "BASE_3D_NOISE_END"}) {
            Field f = NoiseRouterData.class.getDeclaredField(field);
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            ResourceKey<DensityFunction> key = (ResourceKey<DensityFunction>) f.get(null);
            BlendedNoise registered = (BlendedNoise) functions.getOrThrow(key).value();
            configs.add(new Object[] {key.identifier().toString(), blendedFields(registered)});
        }
        configs.add(new Object[] {"synthetic", new double[] {1.0, 1.0, 80.0, 160.0, 8.0}});
        configs.add(new Object[] {"synthetic", new double[] {0.25, 0.375, 80.0, 60.0, 8.0}});
        configs.add(new Object[] {"synthetic", new double[] {0.001, 1000.0, 0.001, 1000.0, 1.0}});

        for (Object[] config : configs) {
            double[] p = (double[]) config[1];
            BlendedNoise unseeded = BlendedNoise.createUnseeded(p[0], p[1], p[2], p[3], p[4]);
            String[][] randoms = {
                {Rngs.XOROSHIRO, "minecraft:terrain"}, {Rngs.LEGACY, null}, {Rngs.XOROSHIRO, null},
            };
            for (String[] spec : randoms) {
                Case c = new Case("blended_noise").param("method", "compute").param("source", config[0])
                        .param("xz_scale", p[0]).param("y_scale", p[1]).param("xz_factor", p[2])
                        .param("y_factor", p[3]).param("smear_scale_multiplier", p[4]);
                RandomSource random = Rngs.forked(c, spec[0], Inputs.WORLD_SEED, spec[1]);
                BlendedNoise noise = unseeded.withNewRandom(random);
                for (int i = 0; i < ROWS; i++) {
                    int x = Inputs.block(r), y = Inputs.blockY(r), z = Inputs.block(r);
                    c.input(x, y, z).output(noise.compute(new DensityFunction.SinglePointContext(x, y, z)));
                }
                sink.write(c);
            }
        }
    }

    private static double[] blendedFields(BlendedNoise noise) throws ReflectiveOperationException {
        String[] names = {"xzScale", "yScale", "xzFactor", "yFactor", "smearScaleMultiplier"};
        double[] values = new double[names.length];
        for (int i = 0; i < names.length; i++) {
            Field f = BlendedNoise.class.getDeclaredField(names[i]);
            f.setAccessible(true);
            values[i] = f.getDouble(noise);
        }
        return values;
    }

    private interface Noise3 {
        double get(double x, double y, double z);
    }

    private static Case fill3(Case c, Random r, Noise3 noise) {
        return fill3(c, r, ROWS, noise);
    }

    private static Case fill3(Case c, Random r, int rows, Noise3 noise) {
        for (int i = 0; i < rows; i++) {
            double x = Inputs.coord(r), y = Inputs.coord(r), z = Inputs.coord(r);
            c.input(x, y, z).output(noise.get(x, y, z));
        }
        return c;
    }

    private static void origin(Case c, double xo, double yo, double zo) {
        c.param("xo", xo).param("yo", yo).param("zo", zo);
    }

    private static DoubleList amplitudes(double... values) {
        return new DoubleArrayList(values);
    }
}
