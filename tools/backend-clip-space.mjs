// Cross-backend draw-input normalization, limited to two documented representation differences:
//
// - Clip space: WebGL2 has z in [-1, 1] and WebGPU in [0, 1]; the WebGPU path builds its
//   projection for the [0, 1] range directly, so the same camera yields P_webgpu = C * P_gl with
//   C mapping z' = (z + w) / 2. The WebGPU Projection is mapped back to the GL convention
//   (row2 = 2 * row2 - row3) and both sides are rounded to six significant digits, since the two
//   matrices are computed separately in float32.
// - Sampler LOD clamps: GL keeps its defaults (-1000, 1000) while WebGPU rejects a negative
//   minimum and stores 0..32. Both are compared as the LOD range they select from the texture's
//   actual mip chain: [max(0, min), min(max, mips - 1)].
//
// Every other input stays byte-exact.

const significant = value => Number(Math.fround(value).toPrecision(6));

export function projectionToGl(values, backend) {
  if (!Array.isArray(values) || values.length !== 16 || !values.every(Number.isFinite)) throw new Error("projection requires 16 finite floats");
  const result = values.slice();
  if (backend === "webgpu") {
    for (let column = 0; column < 4; column++) result[column * 4 + 2] = 2 * values[column * 4 + 2] - values[column * 4 + 3];
  } else if (backend !== "webgl2") {
    throw new Error("unknown backend clip space: " + backend);
  }
  return result.map(significant);
}

export function effectiveSampler(sampler, mipLevels) {
  if (!sampler || sampler.unknown || !Number.isInteger(mipLevels) || mipLevels < 1) return sampler;
  const {lodMinClamp, lodMaxClamp, ...rest} = sampler;
  return {...rest, lodRange: [Math.max(0, lodMinClamp), Math.min(lodMaxClamp, mipLevels - 1)]};
}

export function normalizeBackendFrame(frame) {
  const draws = frame.draws.map(draw => ({...draw, uniforms: draw.uniforms.map(uniform => {
    if (uniform.kind === "texture") return {...uniform, sampler: effectiveSampler(uniform.sampler, uniform.uploads?.length)};
    if (uniform.name !== "Projection") return uniform;
    if (typeof uniform.hex !== "string" || uniform.hex.length < 128) return {name: uniform.name, unknown: true};
    const bytes = Buffer.from(uniform.hex.slice(0, 128), "hex");
    const values = Array.from({length: 16}, (_, index) => bytes.readFloatLE(index * 4));
    return {name: uniform.name, length: uniform.length, clipSpace: "gl-normalized", values: projectionToGl(values, frame.backend),
      tail: uniform.hex.slice(128)};
  })}));
  return {...frame, draws};
}
