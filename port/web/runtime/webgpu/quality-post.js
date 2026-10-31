// Gaius Shaders on WebGPU: the 26.3 world post-processing chain of runtime/quality/post-chain.js
// (FXAA, SSAO, bloom, screen-space reflections, tone mapping) written in WGSL, run once per frame
// between the world render and the hand/GUI when the game renders with WebGPU (the quality
// runtime's endLevel). Registers GaiusQuality.webgpuPost. Loaded with the WebGPU glue
// (gaius-boot.js WEBGPU_SCRIPTS).
//
// Same stages, parameters and tier rules as the WebGL 2 chain (quality-profile.js decides; the
// chain is on by default on high and ultra only), same arithmetic: every shader is the GLSL one
// translated line by line, so both backends produce the same image up to rounding. The passes are
// encoded on the WebGPU glue's open command encoder (GaiusWebGpu.externalEncoder()), between the
// game's own passes, in command order: they read the frame's colour and depth textures and write
// the result back into the colour texture before the hand and the GUI draw on it.
//
// Conventions shared with the game's textures (gpu-glue.js): rows are in OpenGL order, so the
// fragment position of a pass is what gl_FragCoord is in the WebGL 2 chain (row 0 first, the
// world in the rectangle starting at the origin), and texture coordinates match too. The depth
// texture comes from a zero-to-one projection (vanilla's WebGPU and Vulkan backends), which only
// changes the depth-to-NDC step of the view-space reconstruction (frame.zeroToOne).
//
// The world renders at full resolution under WebGPU (the quality runtime's beginLevel answers
// 1000), so there is no upscaling pass here; rectWidth/rectHeight are still honoured.
(function installGaiusWebGpuPost(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.webgpuPost) return;

  const UNIFORM_SLOT = 256;
  const UNIFORM_SLOTS = 256;
  const BLOOM_LEVELS = 4;

  // One uniform block layout for every pass: texel/clamp of the pass's main input, the world
  // rectangle, the projection terms and three per-pass parameter vectors.
  const COMMON = `
struct Params {
  texel: vec2f,
  clampUv: vec2f,
  rectSize: vec2f,
  rectMax: vec2f,
  projXY: vec4f,
  projZ: vec4f,
  a: vec4f,
  b: vec4f,
  c: vec4f,
};
@group(0) @binding(0) var<uniform> u: Params;

@vertex fn vs(@builtin(vertex_index) index: u32) -> @builtin(position) vec4f {
  let p = vec2f(f32((index << 1u) & 2u), f32(index & 2u));
  return vec4f(p * 2.0 - 1.0, 0.0, 1.0);
}

fn toLinear(c0: vec3f) -> vec3f {
  let c = max(c0, vec3f(0.0));
  return select(c / 12.92, pow((c + 0.055) / 1.055, vec3f(2.4)), c >= vec3f(0.04045));
}
fn toSrgb(c0: vec3f) -> vec3f {
  let c = max(c0, vec3f(0.0));
  return select(c * 12.92, 1.055 * pow(c, vec3f(0.416666667)) - 0.055, c >= vec3f(0.0031308));
}
fn luma(c: vec3f) -> f32 { return dot(c, vec3f(0.299, 0.587, 0.114)); }
fn clampUv(uv: vec2f) -> vec2f { return clamp(uv, u.texel * 0.5, u.clampUv); }
`;

  // View-space reconstruction (needs @binding(9) depthTex). u.c.w is 1 for a zero-to-one depth range.
  const DEPTH = `
@group(0) @binding(9) var depthTex: texture_depth_2d;
fn rawDepth(pix: vec2f) -> f32 {
  return textureLoad(depthTex, vec2i(clamp(pix, vec2f(0.0), u.rectMax)), 0);
}
fn viewZ(d: f32) -> f32 {
  let n = select(d * 2.0 - 1.0, d, u.c.w > 0.5);
  let denom = n * u.projZ.y - u.projZ.x;
  return select((u.projZ.z - n * u.projZ.w) / denom, -1e9, abs(denom) < 1e-8);
}
fn viewPos(pix: vec2f, d: f32) -> vec3f {
  let ndc = pix / u.rectSize * 2.0 - 1.0;
  let z = viewZ(d);
  let w = u.projZ.y * z + u.projZ.w;
  return vec3f((ndc.x * w - u.projXY.z * z) / u.projXY.x, (ndc.y * w - u.projXY.w * z) / u.projXY.y, z);
}
fn isSky(d: f32) -> bool { return d <= 0.0 || d >= 1.0; }
`;

  // A plain copy of the world rectangle (the FXAA input when no other stage ran: WebGPU cannot
  // sample the texture it renders into).
  const COPY = COMMON + `
@group(0) @binding(1) var source: texture_2d<f32>;
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  return textureLoad(source, vec2i(min(position.xy, u.rectMax)), 0);
}
`;

  // SSAO at half resolution (u.a.x radius, u.a.y intensity).
  const SSAO = COMMON + DEPTH + `
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let pix = vec2f(vec2i(position.xy) * 2) + 0.5;
  let d = rawDepth(pix);
  if (isSky(d)) { return vec4f(1.0); }
  let P = viewPos(pix, d);
  if (P.z > -0.05) { return vec4f(1.0); }
  let dx = vec2f(2.0, 0.0);
  let dy = vec2f(0.0, 2.0);
  let pr = viewPos(pix + dx, rawDepth(pix + dx));
  let pl = viewPos(pix - dx, rawDepth(pix - dx));
  let pu = viewPos(pix + dy, rawDepth(pix + dy));
  let pd = viewPos(pix - dy, rawDepth(pix - dy));
  let ddx = select(P - pl, pr - P, abs(pr.z - P.z) < abs(P.z - pl.z));
  let ddy = select(P - pd, pu - P, abs(pu.z - P.z) < abs(P.z - pd.z));
  var N = cross(ddx, ddy);
  let nl = length(N);
  if (nl < 1e-8) { return vec4f(1.0); }
  N = N / nl;
  if (dot(N, P) > 0.0) { N = -N; }
  let radius = u.a.x;
  let rPix = clamp(radius * u.projXY.x * 0.5 * u.rectSize.x / -P.z, 3.0, 96.0);
  let noise = fract(52.9829189 * fract(dot(position.xy, vec2f(0.06711056, 0.00583715))));
  let angle = noise * 6.2831853;
  let r2 = radius * radius;
  var sum = 0.0;
  for (var i = 0; i < 8; i++) {
    let t = (f32(i) + 0.5) / 8.0;
    let a = angle + f32(i) * 2.3999632;
    let sp = pix + vec2f(cos(a), sin(a)) * (rPix * t);
    let sd = rawDepth(sp);
    if (isSky(sd)) { continue; }
    let v = viewPos(sp, sd) - P;
    let vv = dot(v, v);
    let falloff = max(r2 - vv, 0.0) / r2;
    let len = sqrt(vv);
    sum += falloff * max(dot(v, N) / max(len, 1e-4) - 0.12, 0.0);
  }
  var ao = clamp(1.0 - u.a.y * 1.4 * sum / 8.0, 0.0, 1.0);
  ao = mix(ao, 1.0, smoothstep(48.0, 96.0, -P.z));
  return vec4f(ao, 0.0, 0.0, 1.0);
}
`;

  // Separable depth-aware blur of the AO (u.a.xy direction, u.a.zw the last half-res texel).
  const AO_BLUR = COMMON + DEPTH + `
@group(0) @binding(1) var aoTex: texture_2d<f32>;
fn zAt(hp: vec2i) -> f32 {
  let d = textureLoad(depthTex, clamp(hp * 2, vec2i(0), vec2i(u.rectMax)), 0);
  return select(viewZ(d), 1e9, isSky(d));
}
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let hp = vec2i(position.xy);
  let zc = zAt(hp);
  if (zc > 1e8) { return vec4f(1.0); }
  let tolerance = 0.05 + 0.03 * abs(zc);
  let dir = vec2i(u.a.xy);
  let halfMax = vec2i(u.a.zw);
  var sum = 0.0;
  var weights = 0.0;
  for (var i = -2; i <= 2; i++) {
    let q = clamp(hp + dir * i, vec2i(0), halfMax);
    let w = (3.0 - abs(f32(i))) * max(1.0 - abs(zAt(q) - zc) / tolerance, 0.0);
    sum += textureLoad(aoTex, q, 0).r * w;
    weights += w;
  }
  return vec4f(select(1.0, sum / weights, weights > 0.0), 0.0, 0.0, 1.0);
}
`;

  // Bloom prefilter: 2x downsample in linear light above the threshold (u.a.x).
  const BLOOM_PREFILTER = COMMON + `
@group(0) @binding(1) var source: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
fn T(uv: vec2f) -> vec3f { return toLinear(textureSampleLevel(source, linearSampler, clampUv(uv), 0.0).rgb); }
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let uv = position.xy * 2.0 * u.texel;
  let c = 0.25 * (T(uv + vec2f(-u.texel.x, -u.texel.y)) + T(uv + vec2f(u.texel.x, -u.texel.y))
      + T(uv + vec2f(-u.texel.x, u.texel.y)) + T(uv + u.texel));
  let l = dot(c, vec3f(0.2126, 0.7152, 0.0722));
  let threshold = u.a.x;
  let knee = threshold * 0.25;
  var soft = clamp(l - threshold + knee, 0.0, 2.0 * knee);
  soft = soft * soft / (4.0 * knee + 1e-5);
  let w = max(soft, l - threshold) / max(l, 1e-4);
  return vec4f(c * w, 1.0);
}
`;

  const BLOOM_DOWN = COMMON + `
@group(0) @binding(1) var source: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
fn T(uv: vec2f) -> vec3f { return textureSampleLevel(source, linearSampler, clampUv(uv), 0.0).rgb; }
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let uv = position.xy * 2.0 * u.texel;
  let sum = T(uv) * 4.0 + T(uv - u.texel) + T(uv + u.texel)
      + T(uv + vec2f(u.texel.x, -u.texel.y)) + T(uv - vec2f(u.texel.x, -u.texel.y));
  return vec4f(sum * 0.125, 1.0);
}
`;

  // Dual-filter upsample of the lower level (texel/clampUv) averaged with this level's
  // downsample (u.a.xy texel, u.a.zw clamp).
  const BLOOM_UP = COMMON + `
@group(0) @binding(1) var low: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
@group(0) @binding(3) var current: texture_2d<f32>;
fn L(uv: vec2f) -> vec3f { return textureSampleLevel(low, linearSampler, clampUv(uv), 0.0).rgb; }
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let uv = position.xy * 0.5 * u.texel;
  let h = u.texel * 0.5;
  let sum = L(uv + vec2f(-2.0 * h.x, 0.0)) + L(uv + vec2f(2.0 * h.x, 0.0))
      + L(uv + vec2f(0.0, 2.0 * h.y)) + L(uv + vec2f(0.0, -2.0 * h.y))
      + 2.0 * (L(uv + vec2f(-h.x, h.y)) + L(uv + vec2f(h.x, h.y))
      + L(uv + vec2f(h.x, -h.y)) + L(uv + vec2f(-h.x, -h.y)));
  let cur = textureSampleLevel(current, linearSampler,
      clamp(position.xy * u.a.xy, u.a.xy * 0.5, u.a.zw), 0.0).rgb;
  return vec4f((sum / 12.0 + cur) * 0.5, 1.0);
}
`;

  // Composite: AO, reflections, bloom, tone mapping. u.a = flags (ao, bloom, tonemap, ssr),
  // u.b = (aoUvScale, aoClamp), u.c = (aoStrength, bloomIntensity, unused, zeroToOne); the bloom
  // scale and clamp come in a second ring slot (@binding(8) extra). The depth helpers (and the
  // depth binding) are only part of the reflection variant.
  function compositeSource(ssr) {
    return COMMON + (ssr ? DEPTH : "") + `
struct Extra { bloomUvScale: vec2f, bloomClamp: vec2f };
@group(0) @binding(8) var<uniform> extra: Extra;
@group(0) @binding(1) var scene: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
@group(0) @binding(3) var aoTex: texture_2d<f32>;
@group(0) @binding(4) var bloomTex: texture_2d<f32>;
fn shoulder(x: vec3f) -> vec3f {
  let k = 0.85;
  let over = max(x - k, vec3f(0.0));
  return min(x, vec3f(k)) + (1.0 - k) * (1.0 - exp(-over / (1.0 - k)));
}
fn grade(c: vec3f) -> vec3f {
  let l = dot(c, vec3f(0.2126, 0.7152, 0.0722));
  return max(mix(vec3f(l), c, 1.05), vec3f(0.0));
}
` + (ssr ? `
fn project(q: vec3f) -> vec2f {
  let w = u.projZ.y * q.z + u.projZ.w;
  let ndc = vec2f(u.projXY.x * q.x + u.projXY.z * q.z, u.projXY.y * q.y + u.projXY.w * q.z) / w;
  return (ndc * 0.5 + 0.5) * u.rectSize;
}
fn reflection(pix: vec2f, sceneColor: vec3f) -> vec4f {
  let d = rawDepth(pix);
  if (isSky(d)) { return vec4f(0.0); }
  let waterLike = smoothstep(0.02, 0.10, sceneColor.b - max(sceneColor.r, sceneColor.g) * 0.95);
  if (waterLike <= 0.0) { return vec4f(0.0); }
  let P = viewPos(pix, d);
  let pr = viewPos(pix + vec2f(1.0, 0.0), rawDepth(pix + vec2f(1.0, 0.0)));
  let pu = viewPos(pix + vec2f(0.0, 1.0), rawDepth(pix + vec2f(0.0, 1.0)));
  var N = cross(pr - P, pu - P);
  if (dot(N, N) < 1e-12) { return vec4f(0.0); }
  N = normalize(N);
  if (dot(N, P) > 0.0) { N = -N; }
  let V = normalize(P);
  let R = reflect(V, N);
  if (R.z > 0.6) { return vec4f(0.0); }
  let fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(-V, N), 0.0), 5.0);
  var stepLen = max(0.15, -P.z * 0.04);
  var q = P;
  for (var i = 0; i < 20; i++) {
    q += R * stepLen;
    stepLen *= 1.18;
    if (q.z > -0.05) { break; }
    let sp = project(q);
    if (sp.x < 0.0 || sp.y < 0.0 || sp.x > u.rectSize.x || sp.y > u.rectSize.y) { break; }
    let sd = rawDepth(sp);
    if (isSky(sd)) { continue; }
    let diff = viewZ(sd) - q.z;
    if (diff > 0.0 && diff < stepLen * 2.0 + 0.25) {
      var lo = q - R * stepLen;
      var hi = q;
      for (var j = 0; j < 4; j++) {
        let mid = (lo + hi) * 0.5;
        let md = rawDepth(project(mid));
        if (!isSky(md) && viewZ(md) > mid.z) { hi = mid; } else { lo = mid; }
      }
      let hit = project(hi);
      let edge = min(hit, u.rectSize - hit) / (u.rectSize * 0.1);
      let fade = clamp(min(edge.x, edge.y), 0.0, 1.0) * (1.0 - f32(i) / 20.0);
      let c = textureSampleLevel(scene, linearSampler, clampUv(hit * u.texel), 0.0).rgb;
      return vec4f(c, waterLike * fresnel * fade * 0.6);
    }
  }
  return vec4f(0.0);
}
` : "") + `
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let uv = position.xy * u.texel;
  let sceneColor = textureSampleLevel(scene, linearSampler, uv, 0.0);
  var lin = toLinear(sceneColor.rgb);
  if (u.a.x > 0.5) {
    let ao = textureSampleLevel(aoTex, linearSampler, clamp(position.xy * u.b.xy, vec2f(0.0), u.b.zw), 0.0).r;
    lin *= mix(1.0, ao, u.c.x);
  }
` + (ssr ? `
  if (u.a.w > 0.5) {
    let r = reflection(floor(position.xy) + 0.5, sceneColor.rgb);
    lin = mix(lin, toLinear(r.rgb), r.a);
  }
` : "") + `
  if (u.a.y > 0.5) {
    lin += textureSampleLevel(bloomTex, linearSampler, clamp(position.xy * extra.bloomUvScale, vec2f(0.0),
        extra.bloomClamp), 0.0).rgb * u.c.y;
  }
  let mapped = select(clamp(lin, vec3f(0.0), vec3f(1.0)), grade(shoulder(lin)), u.a.z > 0.5);
  return vec4f(clamp(toSrgb(mapped), vec3f(0.0), vec3f(1.0)), sceneColor.a);
}
`;
  }

  // FXAA (quality variant, 12 search steps), on gamma-encoded colour.
  const FXAA = COMMON + `
@group(0) @binding(1) var source: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
fn S(uv: vec2f) -> vec4f { return textureSampleLevel(source, linearSampler, uv, 0.0); }
fn L(uv: vec2f) -> f32 { return luma(S(clampUv(uv)).rgb); }
fn stepScale(i: i32) -> f32 {
  return select(select(select(select(8.0, 4.0, i == 10), 2.0, i < 10), 1.5, i == 5), 1.0, i < 5);
}
@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {
  let uv = position.xy * u.texel;
  let center = S(uv);
  let lc = luma(center.rgb);
  let ld = L(uv + vec2f(0.0, -u.texel.y));
  let lu = L(uv + vec2f(0.0, u.texel.y));
  let ll = L(uv + vec2f(-u.texel.x, 0.0));
  let lr = L(uv + vec2f(u.texel.x, 0.0));
  let lmin = min(lc, min(min(ld, lu), min(ll, lr)));
  let lmax = max(lc, max(max(ld, lu), max(ll, lr)));
  let range = lmax - lmin;
  if (range < max(0.0312, lmax * 0.125)) { return center; }
  let ldl = L(uv + vec2f(-u.texel.x, -u.texel.y));
  let lur = L(uv + vec2f(u.texel.x, u.texel.y));
  let lul = L(uv + vec2f(-u.texel.x, u.texel.y));
  let ldr = L(uv + vec2f(u.texel.x, -u.texel.y));
  let ldu = ld + lu;
  let llr = ll + lr;
  let leftCorners = ldl + lul;
  let downCorners = ldl + ldr;
  let rightCorners = ldr + lur;
  let upCorners = lur + lul;
  let edgeH = abs(-2.0 * ll + leftCorners) + abs(-2.0 * lc + ldu) * 2.0 + abs(-2.0 * lr + rightCorners);
  let edgeV = abs(-2.0 * lu + upCorners) + abs(-2.0 * lc + llr) * 2.0 + abs(-2.0 * ld + downCorners);
  let horizontal = edgeH >= edgeV;
  let l1 = select(ll, ld, horizontal);
  let l2 = select(lr, lu, horizontal);
  let g1 = l1 - lc;
  let g2 = l2 - lc;
  let steep1 = abs(g1) >= abs(g2);
  let gradientScaled = 0.25 * max(abs(g1), abs(g2));
  var stepLength = select(u.texel.x, u.texel.y, horizontal);
  var localAverage: f32;
  if (steep1) { stepLength = -stepLength; localAverage = 0.5 * (l1 + lc); }
  else { localAverage = 0.5 * (l2 + lc); }
  var cur = uv;
  if (horizontal) { cur.y += stepLength * 0.5; } else { cur.x += stepLength * 0.5; }
  let offset = select(vec2f(0.0, u.texel.y), vec2f(u.texel.x, 0.0), horizontal);
  var uv1 = cur - offset;
  var uv2 = cur + offset;
  var end1 = L(uv1) - localAverage;
  var end2 = L(uv2) - localAverage;
  var reached1 = abs(end1) >= gradientScaled;
  var reached2 = abs(end2) >= gradientScaled;
  if (!reached1) { uv1 -= offset; }
  if (!reached2) { uv2 += offset; }
  if (!(reached1 && reached2)) {
    for (var i = 2; i < 12; i++) {
      if (!reached1) { end1 = L(uv1) - localAverage; }
      if (!reached2) { end2 = L(uv2) - localAverage; }
      reached1 = abs(end1) >= gradientScaled;
      reached2 = abs(end2) >= gradientScaled;
      if (!reached1) { uv1 -= offset * stepScale(i); }
      if (!reached2) { uv2 += offset * stepScale(i); }
      if (reached1 && reached2) { break; }
    }
  }
  let dist1 = select(uv.y - uv1.y, uv.x - uv1.x, horizontal);
  let dist2 = select(uv2.y - uv.y, uv2.x - uv.x, horizontal);
  let dir1 = dist1 < dist2;
  let distFinal = min(dist1, dist2);
  let thickness = dist1 + dist2;
  let pixelOffset = select(0.0, -distFinal / thickness + 0.5, thickness > 0.0);
  let centerSmaller = lc < localAverage;
  let correct = (select(end2, end1, dir1) < 0.0) != centerSmaller;
  var finalOffset = select(0.0, pixelOffset, correct);
  let average = (1.0 / 12.0) * (2.0 * (ldu + llr) + leftCorners + rightCorners);
  let sub1 = clamp(abs(average - lc) / range, 0.0, 1.0);
  let sub2 = (-2.0 * sub1 + 3.0) * sub1 * sub1;
  finalOffset = max(finalOffset, sub2 * sub2 * 0.75);
  var finalUv = uv;
  if (horizontal) { finalUv.y += finalOffset * stepLength; } else { finalUv.x += finalOffset * stepLength; }
  return vec4f(S(clampUv(finalUv)).rgb, center.a);
}
`;

  const stats = {runs: 0, passes: 0, lastStages: "", errors: 0, lastError: null, lastEncodeMs: 0, disabled: false,
    uniformGrows: 0, computePasses: 0, dispatches: 0};

  // Stages whose target can be a storage texture run as dispatches of one compute pass instead of
  // one render pass each: the same WGSL body (shade(), formerly the fragment entry point) runs per
  // texel at the texel centre and textureStore()s what the fragment returned, clipped to the
  // stage's rectangle like the scissor did. Every shader reads its inputs with textureLoad or
  // textureSampleLevel, so nothing depends on fragment derivatives, and both paths convert the
  // value to the target format the same way. A frame then records one compute pass and one render
  // pass (the result into the game's colour texture, which has no storage usage) instead of up to
  // twelve render passes: twelve fewer pass encoders and descriptors per frame that live until a
  // full collection with their GPU-process objects. ?gaiusPostCompute=0 keeps render passes.
  const STORAGE_FORMATS = new Set(["rgba8unorm", "rgba8snorm", "rgba16float", "rgba32float"]);
  const CLIP_FIELD = "  c: vec4f,\n};";
  const FRAGMENT_ENTRY = "@fragment fn fs(@builtin(position) position: vec4f) -> @location(0) vec4f {";

  function computeSource(code, format) {
    if (code.split(CLIP_FIELD).length !== 2 || code.split(FRAGMENT_ENTRY).length !== 2) return null;
    return code.replace(CLIP_FIELD, "  c: vec4f,\n  clip: vec4u,\n};")
      .replace(FRAGMENT_ENTRY, "fn shade(position: vec4f) -> vec4f {") + `
@group(0) @binding(14) var gaiusOut: texture_storage_2d<${format}, write>;
@compute @workgroup_size(8, 8) fn cs(@builtin(global_invocation_id) id: vec3u) {
  if (id.x >= u.clip.x || id.y >= u.clip.y) { return; }
  textureStore(gaiusOut, vec2i(id.xy), shade(vec4f(vec2f(id.xy) + 0.5, 0.0, 1.0)));
}
`;
  }

  function computeWanted(device) {
    if (!device || typeof device.createComputePipeline !== "function") return false;
    try {
      const value = new URLSearchParams(root.location ? root.location.search : "").get("gaiusPostCompute");
      return value !== "0" && value !== "off";
    } catch (_) {
      return true;
    }
  }

  let state = null;

  function half(value) {
    return Math.max(1, Math.ceil(value / 2));
  }

  function resources(device) {
    if (state && state.device === device) return state;
    state = {
      device: device,
      uniforms: device.createBuffer({label: "gaius post uniforms", size: UNIFORM_SLOT * UNIFORM_SLOTS, usage: 0x48}), // UNIFORM | COPY_DST
      slotCount: UNIFORM_SLOTS,
      // Slots [0, nextSlot) may belong to command buffers the glue has not submitted yet; they are
      // only handed out again once the glue's submit counter moves (see run).
      nextSlot: 0,
      submitMark: null,
      linear: device.createSampler({minFilter: "linear", magFilter: "linear", addressModeU: "clamp-to-edge", addressModeV: "clamp-to-edge"}),
      modules: new Map(),
      pipelines: new Map(),
      pool: new Map(),
      views: new WeakMap(),
      depthViews: new WeakMap(),
      // Uniform blocks of the current run, staged here and written with one writeBuffer per
      // contiguous slot range before the encoder is submitted (the queue applies them in the same
      // order as the per-pass writes did: all before the frame's command buffer).
      staging: new Float32Array(UNIFORM_SLOT * UNIFORM_SLOTS / 4),
      compute: computeWanted(device),
      computeOf: new WeakMap(),
      sources: new WeakMap(),
      cpass: null,
      pendingFirst: 0,
      pendingCount: 0,
      encoder: null,
      // getBindGroupLayout(0) per pipeline, and bind groups by layout + entry resources: every
      // frame binds the same views, sampler and uniform slots, so the groups are reused.
      layouts: new WeakMap(),
      groups: new Map(),
      ids: new WeakMap(),
      nextId: 0
    };
    return state;
  }

  function module(res, name, code) {
    let m = res.modules.get(name);
    if (!m) {
      m = res.device.createShaderModule({label: "gaius post " + name, code: code});
      res.modules.set(name, m);
    }
    return m;
  }

  function pipeline(res, name, code, format) {
    const key = name + "|" + format;
    let p = res.pipelines.get(key);
    if (!p) {
      const shader = module(res, name, code);
      p = res.device.createRenderPipeline({
        label: "gaius post " + key,
        layout: "auto",
        vertex: {module: shader, entryPoint: "vs"},
        fragment: {module: shader, entryPoint: "fs", targets: [{format: format}]},
        primitive: {topology: "triangle-list"}
      });
      res.pipelines.set(key, p);
      res.sources.set(p, {name: name, code: code});
    }
    return p;
  }

  // The compute variant of a render pipeline for a storage target of that format, or null.
  function computePipelineOf(res, renderPipeline, format) {
    let byFormat = res.computeOf.get(renderPipeline);
    if (!byFormat) {
      byFormat = new Map();
      res.computeOf.set(renderPipeline, byFormat);
    }
    if (byFormat.has(format)) return byFormat.get(format);
    const source = res.sources.get(renderPipeline);
    const code = source ? computeSource(source.code, format) : null;
    let p = null;
    if (code !== null) {
      p = res.device.createComputePipeline({
        label: "gaius post " + source.name + " compute|" + format,
        layout: "auto",
        compute: {module: module(res, source.name + "|compute|" + format, code), entryPoint: "cs"}
      });
    }
    byFormat.set(format, p);
    return p;
  }

  function endCompute(res) {
    if (res && res.cpass) {
      res.cpass.end();
      res.cpass = null;
    }
  }

  function target(res, name, width, height, format) {
    let t = res.pool.get(name);
    if (!t || t.width !== width || t.height !== height || t.format !== format) {
      if (t) {
        t.texture.destroy();
        res.groups.clear(); // cached groups may reference the replaced view
      }
      const storage = res.compute && STORAGE_FORMATS.has(format);
      const texture = res.device.createTexture({label: "gaius post " + name, size: {width: width, height: height},
        format: format, usage: storage ? 0x1c : 0x14}); // TEXTURE_BINDING | RENDER_ATTACHMENT (| STORAGE_BINDING)
      t = {texture: texture, view: texture.createView(), width: width, height: height, format: format, storage: storage};
      res.pool.set(name, t);
    }
    return t;
  }

  function depthViewOf(res, texture) {
    let view = res.depthViews.get(texture);
    if (!view) {
      view = texture.createView({aspect: "depth-only", baseMipLevel: 0, mipLevelCount: 1});
      res.depthViews.set(texture, view);
    }
    return view;
  }

  function viewOf(res, texture) {
    let view = res.views.get(texture);
    if (!view) {
      view = texture.createView({baseMipLevel: 0, mipLevelCount: 1});
      res.views.set(texture, view);
    }
    return view;
  }

  // Writes one uniform block (Params, 112 bytes; 128 bytes are written) into the next ring slot;
  // returns its offset. The block is staged and reaches the buffer at flushParams.
  function params(res, values) {
    if (res.nextSlot >= res.slotCount) growUniforms(res);
    const slot = res.nextSlot;
    if (res.pendingCount > 0 && slot !== res.pendingFirst + res.pendingCount) flushParams(res);
    const f = res.staging;
    const base = slot * (UNIFORM_SLOT / 4);
    f.fill(0, base, base + 32);
    f.set(values.texel, base);
    f.set(values.clampUv, base + 2);
    f.set(values.rectSize, base + 4);
    f.set(values.rectMax, base + 6);
    if (values.projection) {
      f.set(values.projection, base + 8);
    }
    if (values.a) f.set(values.a, base + 16);
    if (values.b) f.set(values.b, base + 20);
    if (values.c) f.set(values.c, base + 24);
    if (res.pendingCount === 0) res.pendingFirst = slot;
    res.pendingCount++;
    res.nextSlot = slot + 1;
    return slot * UNIFORM_SLOT;
  }

  // Every slot is taken by command buffers not yet submitted: never wrap onto them. Move to a
  // buffer twice the size (the old one stays alive for the command buffers that bind it; it is
  // only dropped, not destroyed) and start over at its slot 0. Cached groups bind the old buffer.
  function growUniforms(res) {
    flushParams(res);
    const count = res.slotCount * 2;
    res.uniforms = res.device.createBuffer({label: "gaius post uniforms", size: UNIFORM_SLOT * count, usage: 0x48});
    res.staging = new Float32Array(UNIFORM_SLOT * count / 4);
    res.slotCount = count;
    res.nextSlot = 0;
    res.groups.clear();
    stats.uniformGrows++;
  }

  // One writeBuffer for the staged blocks (slots pendingFirst.., 128 bytes each; the unused tail
  // of each 256-byte slot in between stays zero, as it always was).
  function flushParams(res) {
    if (res.pendingCount === 0) return;
    const offset = res.pendingFirst * UNIFORM_SLOT;
    const size = (res.pendingCount - 1) * UNIFORM_SLOT + 128;
    res.pendingCount = 0;
    res.device.queue.writeBuffer(res.uniforms, offset, res.staging.buffer, offset, size);
  }

  function idOf(res, object) {
    let id = res.ids.get(object);
    if (id === undefined) {
      id = ++res.nextId;
      res.ids.set(object, id);
    }
    return id;
  }

  function layoutOf(res, p) {
    let layout = res.layouts.get(p);
    if (!layout) {
      layout = p.getBindGroupLayout(0);
      res.layouts.set(p, layout);
    }
    return layout;
  }

  // One full-screen pass into dest over [0, rectW) x [0, rectH); entries: {binding: resource}.
  function pass(res, encoder, dest, p, offset, entries, rectW, rectH) {
    const clipW = Math.max(1, Math.min(rectW, dest.width));
    const clipH = Math.max(1, Math.min(rectH, dest.height));
    const cp = res.compute && dest.storage ? computePipelineOf(res, p, dest.format) : null;
    if (cp) {
      const clayout = layoutOf(res, cp);
      let ckey = idOf(res, clayout) + "|" + offset + "|out:" + idOf(res, dest.view);
      for (const binding of Object.keys(entries)) {
        const resource = entries[binding];
        ckey += resource.buffer ? "|" + binding + ":b" + idOf(res, resource.buffer) + "@" + resource.offset + "+" + resource.size
          : "|" + binding + ":" + idOf(res, resource);
      }
      let cgroup = res.groups.get(ckey);
      if (!cgroup) {
        const list = [{binding: 0, resource: {buffer: res.uniforms, offset: offset, size: 128}},
          {binding: 14, resource: dest.view}];
        for (const binding of Object.keys(entries)) {
          list.push({binding: Number(binding), resource: entries[binding]});
        }
        cgroup = res.device.createBindGroup({layout: clayout, entries: list});
        if (res.groups.size >= 512) res.groups.clear();
        res.groups.set(ckey, cgroup);
      }
      // The clip rectangle lives in the block's last 16 bytes (Params.clip of the compute variant).
      const words = new Uint32Array(res.staging.buffer, (offset / 4 + 28) * 4, 2);
      words[0] = clipW;
      words[1] = clipH;
      if (res.cpass === null) {
        res.cpass = encoder.beginComputePass();
        stats.computePasses++;
      }
      res.cpass.setPipeline(cp);
      res.cpass.setBindGroup(0, cgroup);
      res.cpass.dispatchWorkgroups(Math.ceil(clipW / 8), Math.ceil(clipH / 8));
      stats.dispatches++;
      return;
    }
    endCompute(res);
    const layout = layoutOf(res, p);
    let key = idOf(res, layout) + "|" + offset;
    for (const binding of Object.keys(entries)) {
      const resource = entries[binding];
      key += resource.buffer ? "|" + binding + ":b" + idOf(res, resource.buffer) + "@" + resource.offset + "+" + resource.size
        : "|" + binding + ":" + idOf(res, resource);
    }
    let group = res.groups.get(key);
    if (!group) {
      const list = [{binding: 0, resource: {buffer: res.uniforms, offset: offset, size: 128}}];
      for (const binding of Object.keys(entries)) {
        list.push({binding: Number(binding), resource: entries[binding]});
      }
      group = res.device.createBindGroup({layout: layout, entries: list});
      if (res.groups.size >= 512) res.groups.clear();
      res.groups.set(key, group);
    }
    const rp = encoder.beginRenderPass({colorAttachments: [{view: dest.view, loadOp: "load", storeOp: "store"}]});
    rp.setPipeline(p);
    rp.setBindGroup(0, group);
    rp.setScissorRect(0, 0, clipW, clipH);
    rp.draw(3, 1, 0, 0);
    rp.end();
    stats.passes++;
  }

  function rectParams(width, height, rectW, rectH, frame) {
    return {texel: [1 / width, 1 / height], clampUv: [(rectW - 0.5) / width, (rectH - 0.5) / height],
      rectSize: [frame.rectWidth, frame.rectHeight], rectMax: [frame.rectWidth - 1, frame.rectHeight - 1],
      projection: frame.projection};
  }

  function runSsao(res, encoder, frame, settings, depthView) {
    const aoW = half(frame.width);
    const aoH = half(frame.height);
    const rectW = half(frame.rectWidth);
    const rectH = half(frame.rectHeight);
    const raw = target(res, "ao", aoW, aoH, "rgba8unorm");
    const blurred = target(res, "aoBlur", aoW, aoH, "rgba8unorm");
    const base = rectParams(frame.width, frame.height, frame.rectWidth, frame.rectHeight, frame);
    const zeroToOne = frame.zeroToOne ? 1 : 0;
    pass(res, encoder, raw, pipeline(res, "ssao", SSAO, "rgba8unorm"),
      params(res, Object.assign({}, base, {a: [settings.ssaoRadius, settings.ssaoIntensity, 0, 0], c: [0, 0, 0, zeroToOne]})),
      {9: depthView}, rectW, rectH);
    const blur = pipeline(res, "aoBlur", AO_BLUR, "rgba8unorm");
    pass(res, encoder, blurred, blur,
      params(res, Object.assign({}, base, {a: [1, 0, rectW - 1, rectH - 1], c: [0, 0, 0, zeroToOne]})),
      {1: raw.view, 9: depthView}, rectW, rectH);
    pass(res, encoder, raw, blur,
      params(res, Object.assign({}, base, {a: [0, 1, rectW - 1, rectH - 1], c: [0, 0, 0, zeroToOne]})),
      {1: blurred.view, 9: depthView}, rectW, rectH);
    return {view: raw.view, width: aoW, height: aoH, rectWidth: rectW, rectHeight: rectH};
  }

  function runBloom(res, encoder, frame, settings, colorView) {
    const format = "rgba16float";
    const levels = [];
    let w = frame.width;
    let h = frame.height;
    let rw = frame.rectWidth;
    let rh = frame.rectHeight;
    for (let i = 0; i < BLOOM_LEVELS; i++) {
      w = half(w);
      h = half(h);
      rw = half(rw);
      rh = half(rh);
      levels.push({down: target(res, "bloomDown" + i, w, h, format),
        up: i < BLOOM_LEVELS - 1 ? target(res, "bloomUp" + i, w, h, format) : null,
        width: w, height: h, rectWidth: rw, rectHeight: rh});
    }
    const linear = res.linear;
    pass(res, encoder, levels[0].down, pipeline(res, "bloomPrefilter", BLOOM_PREFILTER, format),
      params(res, Object.assign(rectParams(frame.width, frame.height, frame.rectWidth, frame.rectHeight, frame),
        {a: [Math.pow(settings.bloomThreshold, 2.2), 0, 0, 0]})),
      {1: colorView, 2: linear}, levels[0].rectWidth, levels[0].rectHeight);
    const down = pipeline(res, "bloomDown", BLOOM_DOWN, format);
    for (let i = 1; i < BLOOM_LEVELS; i++) {
      const src = levels[i - 1];
      pass(res, encoder, levels[i].down, down,
        params(res, rectParams(src.width, src.height, src.rectWidth, src.rectHeight, frame)),
        {1: src.down.view, 2: linear}, levels[i].rectWidth, levels[i].rectHeight);
    }
    const up = pipeline(res, "bloomUp", BLOOM_UP, format);
    let low = levels[BLOOM_LEVELS - 1].down;
    let lowLevel = levels[BLOOM_LEVELS - 1];
    for (let i = BLOOM_LEVELS - 2; i >= 0; i--) {
      const cur = levels[i];
      pass(res, encoder, cur.up, up,
        params(res, Object.assign(rectParams(lowLevel.width, lowLevel.height, lowLevel.rectWidth, lowLevel.rectHeight, frame),
          {a: [1 / cur.width, 1 / cur.height, (cur.rectWidth - 0.5) / cur.width, (cur.rectHeight - 0.5) / cur.height]})),
        {1: low.view, 2: linear, 3: cur.down.view}, cur.rectWidth, cur.rectHeight);
      low = cur.up;
      lowLevel = cur;
    }
    const top = levels[0];
    return {view: top.up.view, width: top.width, height: top.height, rectWidth: top.rectWidth, rectHeight: top.rectHeight};
  }

  function hasDepthInputs(frame) {
    return !!(frame.depth && frame.projection && frame.projection.length === 8
        && frame.projection[0] !== 0 && frame.projection[1] !== 0);
  }

  function anyStage(settings) {
    return !!(settings && settings.enabled
        && (settings.fxaa || settings.tonemap || settings.ssao || settings.bloom || settings.ssr));
  }

  /**
   * Runs the enabled stages on frame = {color, depth (GPUTexture or null), width, height,
   * rectWidth, rectHeight, projection: [m00, m11, m20, m21, m22, m23, m32, m33], zeroToOne} and
   * writes the result into frame.color over the world rectangle. Returns false (nothing encoded)
   * when no stage is enabled or the glue has no encoder to record on.
   */
  function run(frame, settings) {
    if (stats.disabled || !anyStage(settings)) return false;
    const gpu = root.GaiusWebGpu;
    const encoder = gpu && typeof gpu.externalEncoder === "function" ? gpu.externalEncoder() : null;
    if (!encoder || !gpu.device || !frame.color) return false;
    const started = root.performance ? root.performance.now() : 0;
    let res = null;
    try {
      res = resources(gpu.device);
      // Uniform slots only need to be distinct among command buffers that are not submitted yet:
      // the queue applies later writes after an earlier submit. A new encoder does not mean the
      // previous one was submitted (a surface blit finishes it and the glue submits at present),
      // so the ring restarts at slot 0 only once the glue's submit counter has moved; steady
      // frames then reuse the same slots (and so the same cached bind groups).
      if (res.encoder !== encoder) {
        flushParams(res);
        res.encoder = encoder;
      }
      // A glue without the counter: fall back to treating each new encoder as submitted.
      const mark = typeof gpu.submitCount === "number" ? gpu.submitCount : encoder;
      if (mark !== res.submitMark) {
        res.submitMark = mark;
        res.nextSlot = 0;
      }
      const colorFormat = frame.color.format;
      const depthOk = hasDepthInputs(frame);
      const useAo = settings.ssao && depthOk;
      const useBloom = settings.bloom;
      const useSsr = settings.ssr && depthOk;
      const useTonemap = settings.tonemap;
      const useComposite = useAo || useBloom || useSsr || useTonemap;
      const colorView = viewOf(res, frame.color);
      const depthView = depthOk ? depthViewOf(res, frame.depth) : null;
      const finalTarget = {view: colorView, width: frame.width, height: frame.height};
      const base = rectParams(frame.width, frame.height, frame.rectWidth, frame.rectHeight, frame);
      const zeroToOne = frame.zeroToOne ? 1 : 0;
      let ao = null;
      let bloom = null;
      if (useAo) ao = runSsao(res, encoder, frame, settings, depthView);
      if (useBloom) bloom = runBloom(res, encoder, frame, settings, colorView);
      let current = null; // a pooled view holding the result so far; null: frame.color itself
      if (useComposite) {
        const composite = target(res, "composite", frame.width, frame.height, colorFormat);
        const extra = params(res, {texel: bloom ? [0.5 / bloom.width, 0.5 / bloom.height] : [0, 0],
          clampUv: bloom ? [(bloom.rectWidth - 0.5) / bloom.width, (bloom.rectHeight - 0.5) / bloom.height] : [0, 0],
          rectSize: [0, 0], rectMax: [0, 0]});
        const dummy = target(res, "dummy", 1, 1, "rgba8unorm");
        const entries = {1: colorView, 2: res.linear, 3: ao ? ao.view : dummy.view, 4: bloom ? bloom.view : dummy.view,
          8: {buffer: res.uniforms, offset: extra, size: 16}};
        if (useSsr) entries[9] = depthView;
        const p = pipeline(res, useSsr ? "compositeSsr" : "composite", compositeSource(useSsr), colorFormat);
        pass(res, encoder, composite, p,
          params(res, Object.assign({}, base, {a: [ao ? 1 : 0, bloom ? 1 : 0, useTonemap ? 1 : 0, useSsr ? 1 : 0],
            b: ao ? [0.5 / ao.width, 0.5 / ao.height, (ao.rectWidth - 0.5) / ao.width, (ao.rectHeight - 0.5) / ao.height] : [0, 0, 0, 0],
            c: [0.85, settings.bloomIntensity, 0, zeroToOne]})),
          entries, frame.rectWidth, frame.rectHeight);
        current = composite.view;
      }
      if (settings.fxaa) {
        if (current === null) {
          const copy = target(res, "copy", frame.width, frame.height, colorFormat);
          pass(res, encoder, copy, pipeline(res, "copy", COPY, colorFormat), params(res, base), {1: colorView},
            frame.rectWidth, frame.rectHeight);
          current = copy.view;
        }
        pass(res, encoder, finalTarget, pipeline(res, "fxaa", FXAA, colorFormat), params(res, base),
          {1: current, 2: res.linear}, frame.rectWidth, frame.rectHeight);
      } else if (current !== null) {
        pass(res, encoder, finalTarget, pipeline(res, "copy", COPY, colorFormat), params(res, base), {1: current},
          frame.rectWidth, frame.rectHeight);
      }
      const stages = [];
      if (ao) stages.push("ssao");
      if (bloom) stages.push("bloom");
      if (useSsr) stages.push("ssr");
      if (useTonemap) stages.push("tonemap");
      if (settings.fxaa) stages.push("fxaa");
      stats.lastStages = stages.join("+");
      stats.runs++;
      endCompute(res);
      return true;
    } catch (error) {
      stats.errors++;
      stats.lastError = String(error && error.message ? error.message : error);
      if (stats.errors >= 3) stats.disabled = true;
      return false;
    } finally {
      // The staged uniform blocks of every pass encoded so far reach the buffer before submit.
      try { endCompute(res); } catch (_) {}
      if (res) {
        try {
          flushParams(res);
        } catch (error) {
          stats.errors++;
          stats.lastError = String(error && error.message ? error.message : error);
          if (stats.errors >= 3) stats.disabled = true;
        }
      }
      if (root.performance) stats.lastEncodeMs = Math.round((root.performance.now() - started) * 1000) / 1000;
    }
  }

  function dispose() {
    if (!state) return false;
    const old = state;
    state = null;
    try { if (old.uniforms && old.uniforms.destroy) old.uniforms.destroy(); } catch (_) {}
    for (const value of old.pool.values()) {
      try { if (value && value.texture && value.texture.destroy) value.texture.destroy(); } catch (_) {}
    }
    old.pool.clear();
    old.modules.clear();
    old.pipelines.clear();
    old.views = new WeakMap();
    old.depthViews = new WeakMap();
    old.groups.clear();
    old.layouts = new WeakMap();
    old.encoder = null;
    old.pendingCount = 0;
    old.device = null;
    return true;
  }

  Q.webgpuPost = Object.freeze({
    run: run,
    anyStage: anyStage,
    dispose: dispose,
    stats: stats,
    BLOOM_LEVELS: BLOOM_LEVELS
  });
  if (root.__gaiusGraphicsTerminated) Q.webgpuPost.dispose();
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
