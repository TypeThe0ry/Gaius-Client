// Builds the 26.3 random sources and noises inside a loaded TeaVM artifact (teavm-artifact.mjs)
// exactly as the golden harness (golden/src/v26_3) builds them on the JVM, and evaluates one
// point-sampled fixture line. Volume lines are not evaluated here.

const bitsOf = (hex) => BigInt.asUintN(64, BigInt("0x" + hex));
export const f64 = (hex) => new Float64Array(BigUint64Array.of(bitsOf(hex)).buffer)[0];
const f64s = (hexes) => Array.from(hexes, f64);

// Marks code that rounds floats after the StrictMath263 rewrite: a call to a float helper of
// BrowserStrictMath, or the inlined body of one. The saturating casts are not a float marker.
const STRICT = /Math\.fround|BrowserStrictMath_(round|fromDouble|fromInt|fromLong)/;

export class TeaVMNoise {
  constructor(artifact) {
    this.t = artifact;
    this.factors = {checked: 0, differ: 0};
  }

  // --- random sources --------------------------------------------------------------------

  base(type, seed) {
    const t = this.t;
    if (type === "xoroshiro") {
      // XoroshiroRandomSource(long) upgrades the seed; the Seed128bit constructor does not.
      return t.construct("nmwll_XoroshiroRandomSource", ["$seed"], [t.long(seed)],
        (body) => body.includes("upgradeSeedTo128bit"));
    }
    if (type === "legacy") return t.construct("nmwll_LegacyRandomSource", ["$seed"], [t.long(seed)]);
    throw new Error(`unknown random ${type}`);
  }

  random(p) {
    const base = this.base(p.random, p.seed);
    if (p.fork === undefined) return base;
    const owner = p.random === "xoroshiro" ? "nmwll_XoroshiroRandomSource" : "nmwll_LegacyRandomSource";
    const factoryOwner = p.random === "xoroshiro"
      ? "nmwll_XoroshiroRandomSource$XoroshiroPositionalRandomFactory"
      : "nmwll_LegacyRandomSource$LegacyPositionalRandomFactory";
    const factory = this.t.method(`${owner}_forkPositional`, ["$this"])(base);
    return this.t.method(`${factoryOwner}_fromHashOf`, ["$this", "$name"])(factory, this.t.jstr(p.fork));
  }

  /** RandomSource.nextLong() through the virtual table (legacy inherits it from BitRandomSource). */
  nextLong(random) {
    return BigInt.asIntN(64, random[this.t.virtualName("nmwll_XoroshiroRandomSource_nextLong")]());
  }

  // --- noises ----------------------------------------------------------------------------

  doubleList(values) {
    return this.t.construct("iudfd_DoubleArrayList", ["$a"], [this.t.doubles(values)]);
  }

  /** Calls Noise.get(x, y, z) through the virtual table, whatever the concrete class. */
  noiseGet(noise, x, y, z) {
    return noise[this.t.virtualName("nmwlls_NoiseStack_get")](x, y, z);
  }

  normalRecipe(p) {
    const t = this.t;
    t.method("nmwlls_NormalNoise$Normalization_$callClinit", [])();
    const normalize = t.staticField(`nmwlls_NormalNoise$Normalization_${p.normalize.toUpperCase()}`);
    const parameters = t.construct("nmwlls_NormalNoise$Parameters",
      ["$baseAmplitude", "$baseOctave", "$octaveCount", "$normalize", "$amplitudeModifiers"],
      [f64(p.base_amplitude), p.base_octave, p.octave_count, normalize, this.doubleList(f64s(p.amplitude_modifiers))]);
    return t.construct("nmwlls_NormalNoise", ["$parameters"], [parameters]);
  }

  /** A function (inputs) -> output for one point-sampled fixture line, or null to skip it. */
  sampler(line) {
    const {kind, params: p} = line;
    const t = this.t;
    if (p.method === "volume" || String(p.method).endsWith("_volume")) return null;
    switch (kind) {
      case "improved_noise": {
        if (p.method === "smeared3") {
          const noise = t.construct("nmwlls_SmearedPerlinNoise", ["$random", "$fudgeYScale"],
            [this.random(p), f64(p.fudge_y_scale)]);
          const get = t.method("nmwlls_SmearedPerlinNoise_get", ["$this", "$_x", "$_y", "$_z"]);
          return ([x, y, z]) => get(noise, f64(x), f64(y), f64(z));
        }
        const noise = t.construct("nmwlls_PerlinNoise", ["$random"], [this.random(p)]);
        if (p.method === "perlin2") {
          const get = t.method("nmwlls_PerlinNoise_get", ["$this", "$x", "$y"]);
          return ([x, z]) => get(noise, f64(x), f64(z));
        }
        const get = t.method("nmwlls_PerlinNoise_get", ["$this", "$_x", "$_y", "$_z"]);
        return ([x, y, z]) => get(noise, f64(x), f64(y), f64(z));
      }
      case "simplex_noise": {
        const noise = t.construct("nmwlls_SimplexNoise", ["$random", "$discardNoiseOffset"],
          [this.random(p), p.zero_offset ? 1 : 0]);
        if (p.method === "value2") {
          const get = t.method("nmwlls_SimplexNoise_get", ["$this", "$_xin", "$_yin"]);
          return ([x, y]) => get(noise, f64(x), f64(y));
        }
        const get = t.method("nmwlls_SimplexNoise_get", ["$this", "$_xin", "$_yin", "$_zin"]);
        return ([x, y, z]) => get(noise, f64(x), f64(y), f64(z));
      }
      case "perlin_noise": {
        const noise = p.ctor === "fbm"
          ? t.method("nmwlls_BlendedNoise_createFbm", ["$random", "$firstOctave", "$smearScaleY", "$valueFactor"])(
            this.random(p), p.first_octave, f64(p.fudge_y_scale), f64(p.fbm_amplitude))
          : t.method("nmwlls_LegacyFbmInitializer_createForLegacyNetherBiome", ["$random", "$firstOctave", "$amplitudes"])(
            this.random(p), p.first_octave, this.doubleList(f64s(p.amplitudes)));
        return ([x, y, z]) => this.noiseGet(noise, f64(x), f64(y), f64(z));
      }
      case "normal_noise": {
        const recipe = this.normalRecipe(p);
        // DoubleStream.sum() over the octave amplitudes; vanilla's is compensated.
        this.factors.checked++;
        if (!Object.is(recipe.$normalizationFactor, f64(p.normalization_factor))) this.factors.differ++;
        const create = p.ctor === "legacy_nether"
          ? t.method("nmwlls_NormalNoise_createForLegacyNetherBiome", ["$this", "$random"])
          : t.method("nmwlls_NormalNoise_create", ["$this", "$random"]);
        const noise = create(recipe, this.random(p));
        return ([x, y, z]) => this.noiseGet(noise, f64(x), f64(y), f64(z));
      }
      case "blended_noise": {
        const blended = t.construct("nmwlls_BlendedNoise",
          ["$xzScale", "$yScale", "$xzFactor", "$yFactor", "$smearScaleMultiplier"],
          [f64(p.xz_scale), f64(p.y_scale), f64(p.xz_factor), f64(p.y_factor), f64(p.smear_scale_multiplier)]);
        const sampler = t.method("nmwlls_BlendedNoise_compileSampler", ["$this", "$random"])(blended, this.random(p));
        t.method("nmwlld_SamplerContext_$callClinit", [])();
        const context = t.staticField("nmwlld_SamplerContext_EMPTY_UNCACHED");
        const sampleValue = this.sampleValueName(sampler);
        return ([x, y, z]) => sampler[sampleValue](context, x, y, z);
      }
      case "mth":
        return this.mth(p);
      default:
        return null;
    }
  }

  /** The virtual name of DensitySampler.sampleValue(SamplerContext, int, int, int) on a sampler. */
  sampleValueName(sampler) {
    for (let proto = sampler; proto; proto = Object.getPrototypeOf(proto)) {
      const name = Object.getOwnPropertyNames(proto)
        .find((key) => /^\$sampleValue\d*$/.test(key) && proto[key].length === 4);
      if (name) return name;
    }
    throw new Error("the compiled blended sampler has no sampleValue");
  }

  /**
   * Mth rows. The float and double overloads of one function compile to identical code until the
   * StrictMath263 rewrite marks the float ones, so a row is evaluated with every candidate overload
   * and counts as ambiguous when they disagree.
   */
  mth(p) {
    const t = this.t;
    if (p.fn === "wrap") {
      const wrap = t.method("nmwlls_GradientNoise_wrap", ["$x"]);
      return ([x]) => wrap(f64(x));
    }
    const names = {
      floor: ["$v"], lerp: ["$alpha1", "$p0", "$p1"],
      lerp2: ["$alpha1", "$alpha2", "$x00", "$x10", "$x01", "$x11"],
      lerp3: ["$alpha1", "$alpha2", "$alpha3", "$x000", "$x100", "$x010", "$x110", "$x001", "$x101", "$x011", "$x111"],
      smoothstep: ["$x"], clampedMap: ["$value", "$fromMin", "$fromMax", "$toMin", "$toMax"],
      map: ["$value", "$fromMin", "$fromMax", "$toMin", "$toMax"],
    }[p.fn];
    if (!names) return null;
    const all = t.overloads(`nmu_Mth_${p.fn}`, names);
    const strict = all.filter((fn) => STRICT.test(Function.prototype.toString.call(fn)));
    const candidates = p.precision === "f32" && strict.length > 0 ? strict
      : all.filter((fn) => !strict.includes(fn));
    if (candidates.length === 0) throw new Error(`no compiled Mth.${p.fn} for ${p.precision}`);
    return (inputs) => {
      const args = inputs.map(f64);
      const results = candidates.map((fn) => fn(...args));
      if (results.some((value) => !Object.is(value, results[0]))) {
        throw Object.assign(new Error("overloads disagree"), {ambiguous: true});
      }
      return results[0];
    };
  }

  /** Whether the GradientNoise kernels were compiled after the StrictMath263 rewrite. */
  strictFloat() {
    return this.t.overloads("nmwlls_GradientNoise$Gradient_dotXz", ["$this", "$x", "$z"],
      (body) => STRICT.test(body)).length > 0;
  }
}
