// Turns golden fixture lines (port/native/fixtures/<profile>/*.jsonl) into noise_points kernel
// jobs built with the browser codec (port/web/kernels/noise-job.js), plus the expected result
// bits. Shared by the node checks of the wasm kernel and the kernel pool. The profiles and their
// kernel semantics come from the version profiles (port/versions/*.json).
import {readdirSync, readFileSync} from "node:fs";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

export const nativeDir = fileURLToPath(new URL("..", import.meta.url));
export const defaultFixtures = join(nativeDir, "fixtures");
export const defaultWasm = join(nativeDir, "target", "wasm32-unknown-unknown", "release", "gaius_noise_wasm.wasm");
export const kernelsDir = fileURLToPath(new URL("../../web/kernels/", import.meta.url));
export const versionsDir = fileURLToPath(new URL("../../versions/", import.meta.url));

/** The kernelSemantics of every version profile, by profile id, in file name order. */
export function profileSemantics(dir = versionsDir) {
  const semantics = new Map();
  for (const name of readdirSync(dir).filter((file) => file.endsWith(".json")).sort()) {
    const profile = JSON.parse(readFileSync(join(dir, name), "utf8"));
    if (!profile.kernelSemantics) throw new Error(`${name} declares no kernelSemantics`);
    semantics.set(profile.id, profile.kernelSemantics);
  }
  return semantics;
}

export const PROFILES = [...profileSemantics().keys()];
const FILES = ["normal_noise.jsonl", "blended_noise.jsonl"];

/** Loads noise-job.js the way a page does (a plain script) and returns GaiusNoiseJob. */
export function loadCodec() {
  if (!globalThis.GaiusNoiseJob) {
    const path = join(kernelsDir, "noise-job.js");
    vm.runInThisContext(readFileSync(path, "utf8"), {filename: path});
  }
  return globalThis.GaiusNoiseJob;
}

const bitsOf = (hex) => BigInt.asUintN(64, BigInt("0x" + hex));
const fromBits = (hexes) => new Float64Array(BigUint64Array.from(hexes, bitsOf).buffer);
const f64 = (hex) => fromBits([hex])[0];

/** The kernel noise spec for a point-sampled fixture line, or null when the kernel has no such job. */
function noiseSpec(semantics, kind, p) {
  if (p.method === "volume" || p.ctor === "legacy_nether") return null;
  if (kind === "blended_noise") {
    return {
      type: "blended", xzScale: f64(p.xz_scale), yScale: f64(p.y_scale), xzFactor: f64(p.xz_factor),
      yFactor: f64(p.y_factor), smearScaleMultiplier: f64(p.smear_scale_multiplier),
    };
  }
  if (p.parity_first_octave !== undefined) {
    return {type: "normal", firstOctave: p.parity_first_octave, amplitudes: fromBits(p.parity_amplitudes)};
  }
  if (semantics.synth === "f32") {
    return {
      type: "recipe", baseAmplitude: f64(p.base_amplitude), baseOctave: p.base_octave,
      octaveCount: p.octave_count, normalize: p.normalize, amplitudeModifiers: fromBits(p.amplitude_modifiers),
    };
  }
  return {type: "normal", firstOctave: p.first_octave, amplitudes: fromBits(p.amplitudes)};
}

/**
 * Every fixture line the noise_points kernel covers, as
 * {label, job: {profile, semantics, random, seed, fork, noise, xyz}, expected: BigUint64Array};
 * job.profile only labels the job, the codec writes job.semantics.
 */
export function fixtureJobs(fixturesDir = defaultFixtures, profiles = PROFILES) {
  const jobs = [];
  const declared = profileSemantics();
  for (const profile of profiles) {
    const semantics = declared.get(profile);
    if (!semantics) throw new Error(`no version profile declares the kernel semantics of ${profile}`);
    for (const file of FILES) {
      const lines = readFileSync(join(fixturesDir, profile, file), "utf8").split("\n").filter(Boolean);
      lines.forEach((text, index) => {
        const line = JSON.parse(text);
        const noise = noiseSpec(semantics, line.kind, line.params);
        if (!noise) return;
        // Hex inputs are written as raw words so every bit pattern survives; ints are exact doubles.
        const words = new BigUint64Array(line.inputs.length * 3);
        const xyz = new Float64Array(words.buffer);
        line.inputs.flat().forEach((v, i) => {
          if (typeof v === "string") words[i] = bitsOf(v);
          else xyz[i] = v;
        });
        const p = line.params;
        jobs.push({
          label: `${profile}/${file}:${index + 1}`,
          job: {profile, semantics, random: p.random, seed: p.seed, fork: p.fork, noise, xyz},
          expected: BigUint64Array.from(line.outputs, bitsOf),
        });
      });
    }
  }
  return jobs;
}

/** Returns null when values match expected bit for bit, otherwise a description of the first difference. */
export function compareBits(values, expected) {
  const actual = new BigUint64Array(values.buffer, values.byteOffset, values.length);
  if (actual.length !== expected.length) return `${actual.length} values, expected ${expected.length}`;
  for (let i = 0; i < actual.length; i++) {
    if (actual[i] !== expected[i]) {
      const hex = (bits) => bits.toString(16).padStart(16, "0");
      return `value #${i}: got ${hex(actual[i])}, expected ${hex(expected[i])}`;
    }
  }
  return null;
}
