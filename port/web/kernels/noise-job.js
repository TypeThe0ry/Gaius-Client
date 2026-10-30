// Gaius noise job codec: builds framed noise_points jobs for the wasm noise kernel and reads
// its results. The layout mirrors port/native/crates/gaius-kernel-abi (job/result headers) and
// port/native/crates/gaius-noise-wasm/src/job.rs (payload); all integers are little-endian.
//
// This file is a plain script so it can be inlined next to kernel-pool.js. It installs
// globalThis.GaiusNoiseJob:
//
//   const job = GaiusNoiseJob.noisePoints({semantics: profile.kernelSemantics,
//     random: "xoroshiro", seed: "42", fork: "minecraft:ridge",
//     noise: {type: "normal", firstOctave: -7, amplitudes: [1, 2]},
//     xyz: new Float64Array([x0, y0, z0, x1, y1, z1])}, jobId);
//   const values = GaiusNoiseJob.readNoisePoints(await pool.submit("noise_points", job), jobId);
//
// semantics is the kernelSemantics object of the page's version profile
// (port/versions/<id>.json) or its numeric code; the codec knows no Minecraft version.
// noise is {type: "normal", firstOctave, amplitudes}, {type: "recipe", baseAmplitude,
// baseOctave, octaveCount, normalize: "disabled" | "enabled" | "legacy", amplitudeModifiers}
// (float synth semantics only) or {type: "blended", xzScale, yScale, xzFactor, yFactor,
// smearScaleMultiplier} (integral block positions). A Float64Array xyz is copied bit for bit.
(function (global) {
  "use strict";

  const ABI_VERSION = 1;
  const JOB_MAGIC = 0x424a4b47;     // "GKJB" read as little-endian u32
  const RESULT_MAGIC = 0x53524b47;  // "GKRS"
  const HEADER_LEN = 16;
  const KIND_NOISE_POINTS = 0x0101;
  const MAX_POINTS = 1 << 20;

  const RANDOMS = Object.freeze({xoroshiro: 0, legacy: 1});
  const NORMALIZE = Object.freeze({disabled: 0, enabled: 1, legacy: 2});
  const NOISES = Object.freeze({normal: 0, recipe: 1, blended: 2});

  const pad = (offset, align) => Math.ceil(offset / align) * align;

  function code(table, value, what) {
    if (!Object.prototype.hasOwnProperty.call(table, value)) throw new RangeError(`unknown ${what} ${value}`);
    return table[value];
  }

  // The wire byte of a kernelSemantics object (or of a bare code).
  function semanticsCode(semantics) {
    const value = typeof semantics === "number" ? semantics : semantics && semantics.code;
    if (!Number.isInteger(value) || value < 0 || value > 0xff) {
      throw new RangeError(`unknown kernel semantics ${JSON.stringify(semantics)}`);
    }
    return value;
  }

  // Returns the noise section as [byte length, writer(view, offset)]; offsets are payload-relative.
  function noiseSection(noise) {
    switch (noise.type) {
      case "normal": {
        const amplitudes = Array.from(noise.amplitudes);
        return [8 + 8 * amplitudes.length, (view, at) => {
          view.setInt32(at, noise.firstOctave, true);
          view.setUint32(at + 4, amplitudes.length, true);
          amplitudes.forEach((a, i) => view.setFloat64(at + 8 + 8 * i, a, true));
        }];
      }
      case "recipe": {
        const modifiers = Array.from(noise.amplitudeModifiers || []);
        return [24 + 8 * modifiers.length, (view, at) => {
          view.setFloat64(at, noise.baseAmplitude, true);
          view.setInt32(at + 8, noise.baseOctave, true);
          view.setInt32(at + 12, noise.octaveCount, true);
          view.setUint8(at + 16, code(NORMALIZE, noise.normalize, "normalization"));
          view.setUint32(at + 20, modifiers.length, true);
          modifiers.forEach((m, i) => view.setFloat64(at + 24 + 8 * i, m, true));
        }];
      }
      case "blended": {
        const fields = [noise.xzScale, noise.yScale, noise.xzFactor, noise.yFactor, noise.smearScaleMultiplier];
        return [40, (view, at) => fields.forEach((f, i) => view.setFloat64(at + 8 * i, f, true))];
      }
      default:
        throw new RangeError(`unknown noise type ${noise.type}`);
    }
  }

  // Encodes the framed job (header + payload) into one ArrayBuffer ready to transfer.
  function noisePoints(job, jobId) {
    const xyz = job.xyz;
    if (xyz.length % 3 !== 0) throw new RangeError("xyz must hold whole [x, y, z] triples");
    if (xyz.length / 3 > MAX_POINTS) throw new RangeError("too many noise points");
    const fork = new global.TextEncoder().encode(job.fork || "");
    const [noiseLength, writeNoise] = noiseSection(job.noise);
    const noiseAt = pad(20 + fork.length, 8);
    const xyzAt = pad(noiseAt + noiseLength, 8);
    const payloadLength = xyzAt + 8 * xyz.length;

    const buffer = new ArrayBuffer(HEADER_LEN + payloadLength);
    const view = new DataView(buffer);
    view.setUint32(0, JOB_MAGIC, true);
    view.setUint16(4, ABI_VERSION, true);
    view.setUint16(6, KIND_NOISE_POINTS, true);
    view.setUint32(8, jobId >>> 0, true);
    view.setUint32(12, payloadLength, true);

    const payload = new DataView(buffer, HEADER_LEN);
    payload.setUint8(0, semanticsCode(job.semantics));
    payload.setUint8(1, code(NOISES, job.noise.type, "noise type"));
    payload.setUint8(2, code(RANDOMS, job.random || "xoroshiro", "random source"));
    payload.setUint32(4, xyz.length / 3, true);
    payload.setBigInt64(8, BigInt.asIntN(64, BigInt(job.seed)), true);
    payload.setUint32(16, fork.length, true);
    new Uint8Array(buffer, HEADER_LEN + 20, fork.length).set(fork);
    writeNoise(payload, noiseAt);
    if (xyz instanceof Float64Array) {
      // Byte copy keeps every bit pattern, NaN payloads included.
      new Uint8Array(buffer, HEADER_LEN + xyzAt, 8 * xyz.length)
        .set(new Uint8Array(xyz.buffer, xyz.byteOffset, 8 * xyz.length));
    } else {
      for (let i = 0; i < xyz.length; i++) payload.setFloat64(xyzAt + 8 * i, xyz[i], true);
    }
    return buffer;
  }

  // Validates a result header and returns the payload bytes.
  function readResult(buffer, jobId) {
    if (buffer.byteLength < HEADER_LEN) throw new Error("kernel result is shorter than its header");
    const view = new DataView(buffer);
    if (view.getUint32(0, true) !== RESULT_MAGIC) throw new Error("kernel result has a bad magic");
    if (view.getUint16(4, true) !== ABI_VERSION) throw new Error(`kernel result abi ${view.getUint16(4, true)}`);
    const status = view.getUint16(6, true);
    if (status !== 0) throw new Error(`kernel result status ${status}`);
    if (jobId !== undefined && view.getUint32(8, true) !== jobId >>> 0) {
      throw new Error(`kernel result for job ${view.getUint32(8, true)}, expected ${jobId >>> 0}`);
    }
    const length = view.getUint32(12, true);
    if (HEADER_LEN + length > buffer.byteLength) throw new Error("kernel result is truncated");
    return new Uint8Array(buffer, HEADER_LEN, length);
  }

  // The f64 results of a noise_points job, viewed in place (float results are widened).
  function readNoisePoints(buffer, jobId) {
    const bytes = readResult(buffer, jobId);
    if (bytes.byteLength % 8 !== 0) throw new Error("noise_points result is not a whole number of f64");
    return new Float64Array(buffer, HEADER_LEN, bytes.byteLength / 8);
  }

  global.GaiusNoiseJob = Object.freeze({
    ABI_VERSION, KIND_NOISE_POINTS, MAX_POINTS, noisePoints, readResult, readNoisePoints,
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
