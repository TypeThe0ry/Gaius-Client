# JVM golden-data harness

Dumps golden fixtures from the real Minecraft classes so the native kernels can
be checked bit for bit against vanilla.

```sh
port/native/golden/run-golden.sh                 # regenerate 26.3, 26.2 and 1.21.11
port/native/golden/run-golden.sh 26.2            # one profile
port/native/golden/run-golden.sh --verify        # test: a fresh dump must equal the checked-in fixtures
port/native/golden/strict/strict-math-check.sh   # test: the StrictMath263-rewritten 26.3 classes dump the same
```

`GOLDEN_CLASSPATH_PREFIX` puts rewritten classes before the client jar when the
harness runs; `strict-math-check.sh` uses it with `GOLDEN_VERIFY_TAG=-strict`
so its `--verify` dump lands in `build/verify-strict-26.3`.

It compiles `src/common` plus one noise variant with JDK 25 against
`port/work/<profile>/client-named.jar` and its `classpath.txt` (from the main
checkout; override with `GAIUS_WORK`, and the JDK with `GOLDEN_JAVA_HOME`),
bootstraps Minecraft (`SharedConstants.tryDetectVersion()`,
`Bootstrap.bootStrap()`), reads the built-in worldgen registry
(`VanillaRegistries.createLookup()`, `createWorldLookup()` on 26.3) and writes
`port/native/fixtures/<profile>/<kind>.jsonl`. All inputs come from fixed-seed
`java.util.Random` instances, so the output is reproducible byte for byte.
Each file is capped at 2 MB.

## Variants

- `classic` (1.21.11, 26.2): the double-precision `ImprovedNoise` /
  `PerlinNoise` / `NormalNoise(NoiseParameters)` / `BlendedNoise.compute` stack.
- `v26_3` (26.3): vanilla replaced that stack with float noise
  (`GradientNoise` -> `PerlinNoise`, `SmearedPerlinNoise`, `SimplexNoise`,
  layered by `NoiseStack`), `NormalNoise` became a registry recipe that
  `create(random)`s a `NoiseStack`, and `BlendedNoise` compiles to a
  `DensitySampler`. The kind names stay the same; `params.method` / `params.ctor`
  name the 26.3 entry point and outputs are floats widened to double. Batch
  sampling (`addToVolume`, `sampleVolume`) is covered as `method: "volume"`.

The variant is chosen by whether the jar still contains `ImprovedNoise`.

## Line format

`{"kind", "params", "inputs": [[...]...], "outputs": [...]}`. Doubles (and
widened floats) are 16-char lowercase hex of the raw bits, longs are decimal
strings, ints are JSON numbers.

Seeded cases carry `random` (`xoroshiro` = `new XoroshiroRandomSource(seed)`,
`legacy` = `new LegacyRandomSource(seed)`), `seed`, and optionally `fork`: the
noise is then seeded from `base.forkPositional().fromHashOf(fork)`, which is how
worldgen derives a registry noise from the world seed (`fork` = noise id) and
the blended noise (`fork` = `minecraft:terrain`).

| kind | params | inputs -> outputs |
| --- | --- | --- |
| `xoroshiro_next_long` / `_double` | `ctor` `seed` (`seed`) or `seed128` (`seed_lo`, `seed_hi`) | none -> 64 consecutive draws |
| `legacy_next_int_bound` | `seed` | `[bound]` -> `nextInt(bound)`, in order from one source |
| `legacy_next_double` | `seed` | none -> 64 consecutive draws |
| `positional_from_hash` | `random`, `seed`, `name` | none -> first 4 `nextLong()` of `forkPositional().fromHashOf(name)` |
| `mth` | `fn` (`floor`, `lfloor`, `lerp`, `lerp2`, `lerp3`, `smoothstep`, `clampedMap`, `map`, `wrap`), `precision` `f64`/`f32` | Java argument order -> result (`floor` int, `lfloor` long) |
| `improved_noise` | classic `method` `noise3`/`noise5`; 26.3 `perlin3`/`perlin2`/`smeared3`/`perlin_volume`/`smeared_volume` (+`fudge_y_scale`); `xo`,`yo`,`zo` | `[x,y,z]`, `[x,y,z,yScale,yMax]`, `[x,z]` |
| `perlin_noise` | classic `ctor` `create`/`legacy_blended`/`legacy_nether`; 26.3 `ctor` `fbm` (`first_octave`, `fudge_y_scale`, `fbm_amplitude`)/`legacy_nether`, `method` `value3`/`volume` | `[x,y,z]` -> `getValue` / `get` |
| `normal_noise` | `noise` (registry id or synthetic), classic `first_octave`, `amplitudes`; 26.3 recipe fields, derived `octaves` and `normalization_factor`; `ctor` `create`/`legacy_nether` | `[x,y,z]` |
| `simplex_noise` | `method` `value2`/`value3`; 26.3 `zero_offset` | `[x,y]`, `[x,y,z]` |
| `blended_noise` | `source`, `xz_scale`, `y_scale`, `xz_factor`, `y_factor`, `smear_scale_multiplier`; classic `method` `compute`, 26.3 `value`/`volume` | int `[x,y,z]` |

Volume cases have no inputs; `params.volume` holds `size`, `min` and `step`
per axis, and outputs list the buffer in index order `y + sy * (x + sx * z)`.
`addToVolume` cases start from a zero buffer and add `xz_scale`, `y_scale`
and the float `amplitude` passed to the call. A param key is written once per
line; the harness fails on a duplicate.

The source Javadoc of each fixture class spells out the exact vanilla calls.
