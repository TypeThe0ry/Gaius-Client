// Gaius Shaders: the 26.3 world post-processing chain, run once per frame between the world
// render and the hand/GUI. Registers GaiusQuality.postChain.
//
// Stage sets by tier (quality-profile.js decides, URL parameters override each stage). The
// chain itself is on by default only on high and ultra (?gaiusPost=1 enables it elsewhere):
//   low   FXAA
//   mid   + SSAO (half resolution, depth-aware blur) + bloom (dual-filter, 4 levels)
//   high  + screen-space reflections on water-like surfaces (behind ?gaiusSsr=1, off by default)
//   tone mapping (highlight shoulder and a slight saturation lift) is off on every tier and
//   only runs with ?gaiusTonemap=1.
// Every stage is a full-screen fragment pass (1-8 draws per stage), so the CPU cost per frame is
// a few dozen GL calls regardless of scene size. Stages whose inputs are missing (no depth
// texture, no projection) are skipped. Intermediate targets are RGBA8 except the bloom chain,
// which uses RGBA16F when the context can render to it.
//
// The world occupies the bottom-left rectWidth x rectHeight of the width x height frame
// textures (smaller than the frame while the world renders below full resolution); every pass
// clamps its taps to that rectangle.
(function installGaiusPostChain(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.postChain) return;

  // Exact piecewise sRGB transfer functions. They invert each other, so with no AO and no bloom
  // the composite pass returns the scene unchanged (a polynomial approximation here crushes the
  // shadows of every frame).
  const COLOR_HELPERS = ""
      + "vec3 toLinear(vec3 c) {\n"
      + "  c = max(c, vec3(0.0));\n"
      + "  return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));\n"
      + "}\n"
      + "vec3 toSrgb(vec3 c) {\n"
      + "  c = max(c, vec3(0.0));\n"
      + "  return mix(c * 12.92, 1.055 * pow(c, vec3(0.416666667)) - 0.055, step(vec3(0.0031308), c));\n"
      + "}\n";

  const DEPTH_HELPERS = ""
      + "uniform vec4 uProjXY;\n"
      + "uniform vec4 uProjZ;\n"
      + "uniform vec2 uRectSize;\n"
      + "uniform vec2 uRectMax;\n"
      + "float viewZ(float d) {\n"
      + "  float n = d * 2.0 - 1.0;\n"
      + "  float denom = n * uProjZ.y - uProjZ.x;\n"
      + "  return abs(denom) < 1e-8 ? -1e9 : (uProjZ.z - n * uProjZ.w) / denom;\n"
      + "}\n"
      + "vec3 viewPos(vec2 pix, float d) {\n"
      + "  vec2 ndc = pix / uRectSize * 2.0 - 1.0;\n"
      + "  float z = viewZ(d);\n"
      + "  float w = uProjZ.y * z + uProjZ.w;\n"
      + "  return vec3((ndc.x * w - uProjXY.z * z) / uProjXY.x,\n"
      + "              (ndc.y * w - uProjXY.w * z) / uProjXY.y, z);\n"
      + "}\n"
      + "bool isSky(float d) { return d <= 0.0 || d >= 1.0; }\n";

  // SSAO at half resolution: 8-tap spiral in screen space scaled from a world-space radius,
  // normal reconstructed from the closer of the two depth neighbours on each axis.
  const SSAO_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp int;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uDepth;\n"
      + "uniform float uRadius;\n"
      + "uniform float uIntensity;\n"
      + "out vec4 outColor;\n"
      + DEPTH_HELPERS
      + "float depthAt(vec2 pix) { return texelFetch(uDepth, ivec2(clamp(pix, vec2(0.0), uRectMax)), 0).r; }\n"
      + "void main() {\n"
      + "  vec2 pix = vec2(ivec2(gl_FragCoord.xy) * 2) + 0.5;\n"
      + "  float d = depthAt(pix);\n"
      + "  if (isSky(d)) { outColor = vec4(1.0); return; }\n"
      + "  vec3 P = viewPos(pix, d);\n"
      + "  if (P.z > -0.05) { outColor = vec4(1.0); return; }\n"
      + "  vec2 dx = vec2(2.0, 0.0);\n"
      + "  vec2 dy = vec2(0.0, 2.0);\n"
      + "  vec3 pr = viewPos(pix + dx, depthAt(pix + dx));\n"
      + "  vec3 pl = viewPos(pix - dx, depthAt(pix - dx));\n"
      + "  vec3 pu = viewPos(pix + dy, depthAt(pix + dy));\n"
      + "  vec3 pd = viewPos(pix - dy, depthAt(pix - dy));\n"
      + "  vec3 ddx = abs(pr.z - P.z) < abs(P.z - pl.z) ? pr - P : P - pl;\n"
      + "  vec3 ddy = abs(pu.z - P.z) < abs(P.z - pd.z) ? pu - P : P - pd;\n"
      + "  vec3 N = cross(ddx, ddy);\n"
      + "  float nl = length(N);\n"
      + "  if (nl < 1e-8) { outColor = vec4(1.0); return; }\n"
      + "  N /= nl;\n"
      + "  if (dot(N, P) > 0.0) N = -N;\n"
      + "  float rPix = clamp(uRadius * uProjXY.x * 0.5 * uRectSize.x / -P.z, 3.0, 96.0);\n"
      + "  float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n"
      + "  float angle = noise * 6.2831853;\n"
      + "  float r2 = uRadius * uRadius;\n"
      + "  float sum = 0.0;\n"
      + "  for (int i = 0; i < 8; i++) {\n"
      + "    float t = (float(i) + 0.5) / 8.0;\n"
      + "    float a = angle + float(i) * 2.3999632;\n"
      + "    vec2 sp = pix + vec2(cos(a), sin(a)) * (rPix * t);\n"
      + "    float sd = depthAt(sp);\n"
      + "    if (isSky(sd)) continue;\n"
      + "    vec3 v = viewPos(sp, sd) - P;\n"
      + "    float vv = dot(v, v);\n"
      + "    float falloff = max(r2 - vv, 0.0) / r2;\n"
      + "    float len = sqrt(vv);\n"
      + "    sum += falloff * max(dot(v, N) / max(len, 1e-4) - 0.12, 0.0);\n"
      + "  }\n"
      + "  float ao = clamp(1.0 - uIntensity * 1.4 * sum / 8.0, 0.0, 1.0);\n"
      + "  ao = mix(ao, 1.0, smoothstep(48.0, 96.0, -P.z));\n"
      + "  outColor = vec4(ao, 0.0, 0.0, 1.0);\n"
      + "}\n";

  // Separable depth-aware blur of the half-resolution AO (5 taps).
  const AO_BLUR_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp int;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uAo;\n"
      + "uniform sampler2D uDepth;\n"
      + "uniform ivec2 uDir;\n"
      + "uniform ivec2 uHalfMax;\n"
      + "out vec4 outColor;\n"
      + DEPTH_HELPERS
      + "float zAt(ivec2 halfPixel) {\n"
      + "  float d = texelFetch(uDepth, clamp(halfPixel * 2, ivec2(0), ivec2(uRectMax)), 0).r;\n"
      + "  return isSky(d) ? 1e9 : viewZ(d);\n"
      + "}\n"
      + "void main() {\n"
      + "  ivec2 hp = ivec2(gl_FragCoord.xy);\n"
      + "  float zc = zAt(hp);\n"
      + "  if (zc > 1e8) { outColor = vec4(1.0); return; }\n"
      + "  float tolerance = 0.05 + 0.03 * abs(zc);\n"
      + "  float sum = 0.0;\n"
      + "  float weights = 0.0;\n"
      + "  for (int i = -2; i <= 2; i++) {\n"
      + "    ivec2 q = clamp(hp + uDir * i, ivec2(0), uHalfMax);\n"
      + "    float w = (3.0 - abs(float(i))) * max(1.0 - abs(zAt(q) - zc) / tolerance, 0.0);\n"
      + "    sum += texelFetch(uAo, q, 0).r * w;\n"
      + "    weights += w;\n"
      + "  }\n"
      + "  outColor = vec4(weights > 0.0 ? sum / weights : 1.0, 0.0, 0.0, 1.0);\n"
      + "}\n";

  // Bloom prefilter: 2x downsample of the scene (4 bilinear taps = 16 texels) in linear light,
  // keeping only the part whose luminance is above the threshold (a clear blue sky stays below
  // it; a max-channel test would extract the whole sky).
  const BLOOM_PREFILTER_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uSource;\n"
      + "uniform vec2 uSrcTexel;\n"
      + "uniform vec2 uSrcClamp;\n"
      + "uniform float uThreshold;\n"
      + "out vec4 outColor;\n"
      + COLOR_HELPERS
      + "vec3 T(vec2 uv) { return toLinear(texture(uSource, clamp(uv, uSrcTexel * 0.5, uSrcClamp)).rgb); }\n"
      + "void main() {\n"
      + "  vec2 uv = gl_FragCoord.xy * 2.0 * uSrcTexel;\n"
      + "  vec3 c = 0.25 * (T(uv + vec2(-uSrcTexel.x, -uSrcTexel.y)) + T(uv + vec2(uSrcTexel.x, -uSrcTexel.y))\n"
      + "      + T(uv + vec2(-uSrcTexel.x, uSrcTexel.y)) + T(uv + uSrcTexel));\n"
      + "  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
      + "  float knee = uThreshold * 0.25;\n"
      + "  float soft = clamp(l - uThreshold + knee, 0.0, 2.0 * knee);\n"
      + "  soft = soft * soft / (4.0 * knee + 1e-5);\n"
      + "  float w = max(soft, l - uThreshold) / max(l, 1e-4);\n"
      + "  outColor = vec4(c * w, 1.0);\n"
      + "}\n";

  const BLOOM_DOWN_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uSource;\n"
      + "uniform vec2 uSrcTexel;\n"
      + "uniform vec2 uSrcClamp;\n"
      + "out vec4 outColor;\n"
      + "vec3 T(vec2 uv) { return texture(uSource, clamp(uv, uSrcTexel * 0.5, uSrcClamp)).rgb; }\n"
      + "void main() {\n"
      + "  vec2 uv = gl_FragCoord.xy * 2.0 * uSrcTexel;\n"
      + "  vec3 sum = T(uv) * 4.0 + T(uv - uSrcTexel) + T(uv + uSrcTexel)\n"
      + "      + T(uv + vec2(uSrcTexel.x, -uSrcTexel.y)) + T(uv - vec2(uSrcTexel.x, -uSrcTexel.y));\n"
      + "  outColor = vec4(sum * 0.125, 1.0);\n"
      + "}\n";

  // Dual-filter upsample of the lower level, averaged with this level's downsample.
  const BLOOM_UP_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uLow;\n"
      + "uniform vec2 uLowTexel;\n"
      + "uniform vec2 uLowClamp;\n"
      + "uniform sampler2D uCurrent;\n"
      + "uniform vec2 uCurTexel;\n"
      + "uniform vec2 uCurClamp;\n"
      + "out vec4 outColor;\n"
      + "vec3 L(vec2 uv) { return texture(uLow, clamp(uv, uLowTexel * 0.5, uLowClamp)).rgb; }\n"
      + "void main() {\n"
      + "  vec2 uv = gl_FragCoord.xy * 0.5 * uLowTexel;\n"
      + "  vec2 h = uLowTexel * 0.5;\n"
      + "  vec3 sum = L(uv + vec2(-2.0 * h.x, 0.0)) + L(uv + vec2(2.0 * h.x, 0.0))\n"
      + "      + L(uv + vec2(0.0, 2.0 * h.y)) + L(uv + vec2(0.0, -2.0 * h.y))\n"
      + "      + 2.0 * (L(uv + vec2(-h.x, h.y)) + L(uv + vec2(h.x, h.y))\n"
      + "      + L(uv + vec2(h.x, -h.y)) + L(uv + vec2(-h.x, -h.y)));\n"
      + "  vec3 current = texture(uCurrent, clamp(gl_FragCoord.xy * uCurTexel, uCurTexel * 0.5, uCurClamp)).rgb;\n"
      + "  outColor = vec4((sum / 12.0 + current) * 0.5, 1.0);\n"
      + "}\n";

  function compositeSource(ssr) {
    const G = Q.gl;
    return G.FRAGMENT_PROLOGUE
        + (ssr ? "#define GAIUS_SSR 1\n" : "")
        + "uniform sampler2D uScene;\n"
        + "uniform sampler2D uAo;\n"
        + "uniform sampler2D uBloom;\n"
        + "uniform sampler2D uDepth;\n"
        + "uniform vec4 uFlags;\n"
        + "uniform vec2 uAoUvScale;\n"
        + "uniform vec2 uAoClamp;\n"
        + "uniform float uAoStrength;\n"
        + "uniform vec2 uBloomUvScale;\n"
        + "uniform vec2 uBloomClamp;\n"
        + "uniform float uBloomIntensity;\n"
        + COLOR_HELPERS
        + DEPTH_HELPERS
        + "vec3 shoulder(vec3 x) {\n"
        + "  const float k = 0.85;\n"
        + "  vec3 over = max(x - k, 0.0);\n"
        + "  return min(x, vec3(k)) + (1.0 - k) * (1.0 - exp(-over / (1.0 - k)));\n"
        + "}\n"
        + "vec3 grade(vec3 c) {\n"
        + "  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
        + "  return max(mix(vec3(l), c, 1.05), 0.0);\n"
        + "}\n"
        + "#ifdef GAIUS_SSR\n"
        + "float depthAt(vec2 pix) { return texelFetch(uDepth, ivec2(clamp(pix, vec2(0.0), uRectMax)), 0).r; }\n"
        + "vec2 project(vec3 q) {\n"
        + "  float w = uProjZ.y * q.z + uProjZ.w;\n"
        + "  vec2 ndc = vec2(uProjXY.x * q.x + uProjXY.z * q.z, uProjXY.y * q.y + uProjXY.w * q.z) / w;\n"
        + "  return (ndc * 0.5 + 0.5) * uRectSize;\n"
        + "}\n"
        + "vec4 reflection(vec2 pix, vec3 sceneColor) {\n"
        + "  float d = depthAt(pix);\n"
        + "  if (isSky(d)) return vec4(0.0);\n"
        + "  float waterLike = smoothstep(0.02, 0.10, sceneColor.b - max(sceneColor.r, sceneColor.g) * 0.95);\n"
        + "  if (waterLike <= 0.0) return vec4(0.0);\n"
        + "  vec3 P = viewPos(pix, d);\n"
        + "  vec3 pr = viewPos(pix + vec2(1.0, 0.0), depthAt(pix + vec2(1.0, 0.0)));\n"
        + "  vec3 pu = viewPos(pix + vec2(0.0, 1.0), depthAt(pix + vec2(0.0, 1.0)));\n"
        + "  vec3 N = cross(pr - P, pu - P);\n"
        + "  if (dot(N, N) < 1e-12) return vec4(0.0);\n"
        + "  N = normalize(N);\n"
        + "  if (dot(N, P) > 0.0) N = -N;\n"
        + "  vec3 V = normalize(P);\n"
        + "  vec3 R = reflect(V, N);\n"
        + "  if (R.z > 0.6) return vec4(0.0);\n"
        + "  float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(-V, N), 0.0), 5.0);\n"
        + "  float stepLen = max(0.15, -P.z * 0.04);\n"
        + "  vec3 q = P;\n"
        + "  for (int i = 0; i < 20; i++) {\n"
        + "    q += R * stepLen;\n"
        + "    stepLen *= 1.18;\n"
        + "    if (q.z > -0.05) break;\n"
        + "    vec2 sp = project(q);\n"
        + "    if (sp.x < 0.0 || sp.y < 0.0 || sp.x > uRectSize.x || sp.y > uRectSize.y) break;\n"
        + "    float sd = depthAt(sp);\n"
        + "    if (isSky(sd)) continue;\n"
        + "    float diff = viewZ(sd) - q.z;\n"
        + "    if (diff > 0.0 && diff < stepLen * 2.0 + 0.25) {\n"
        + "      vec3 lo = q - R * stepLen;\n"
        + "      vec3 hi = q;\n"
        + "      for (int j = 0; j < 4; j++) {\n"
        + "        vec3 mid = (lo + hi) * 0.5;\n"
        + "        float md = depthAt(project(mid));\n"
        + "        if (!isSky(md) && viewZ(md) > mid.z) hi = mid; else lo = mid;\n"
        + "      }\n"
        + "      vec2 hit = project(hi);\n"
        + "      vec2 edge = min(hit, uRectSize - hit) / (uRectSize * 0.1);\n"
        + "      float fade = clamp(min(edge.x, edge.y), 0.0, 1.0) * (1.0 - float(i) / 20.0);\n"
        + "      vec3 c = texture(uScene, clampUv(hit * uTexel)).rgb;\n"
        + "      return vec4(c, waterLike * fresnel * fade * 0.6);\n"
        + "    }\n"
        + "  }\n"
        + "  return vec4(0.0);\n"
        + "}\n"
        + "#endif\n"
        + "void main() {\n"
        + "  vec2 uv = fragUv();\n"
        + "  vec4 scene = texture(uScene, uv);\n"
        + "  vec3 lin = toLinear(scene.rgb);\n"
        + "  if (uFlags.x > 0.5) {\n"
        + "    float ao = texture(uAo, clamp(gl_FragCoord.xy * uAoUvScale, vec2(0.0), uAoClamp)).r;\n"
        + "    lin *= mix(1.0, ao, uAoStrength);\n"
        + "  }\n"
        + "#ifdef GAIUS_SSR\n"
        + "  if (uFlags.w > 0.5) {\n"
        + "    vec4 r = reflection(floor(gl_FragCoord.xy) + 0.5, scene.rgb);\n"
        + "    lin = mix(lin, toLinear(r.rgb), r.a);\n"
        + "  }\n"
        + "#endif\n"
        + "  if (uFlags.y > 0.5) {\n"
        + "    lin += texture(uBloom, clamp(gl_FragCoord.xy * uBloomUvScale, vec2(0.0), uBloomClamp)).rgb\n"
        + "        * uBloomIntensity;\n"
        + "  }\n"
        + "  vec3 mapped = uFlags.z > 0.5 ? grade(shoulder(lin)) : clamp(lin, 0.0, 1.0);\n"
        + "  outColor = vec4(clamp(toSrgb(mapped), 0.0, 1.0), scene.a);\n"
        + "}\n";
  }

  // FXAA (quality variant, 12 search steps), on gamma-encoded colour.
  function fxaaSource() {
    return Q.gl.FRAGMENT_PROLOGUE
        + "uniform sampler2D uSource;\n"
        + "float L(vec2 uv) { return luma(texture(uSource, clampUv(uv)).rgb); }\n"
        + "float stepScale(int i) { return i < 5 ? 1.0 : (i == 5 ? 1.5 : (i < 10 ? 2.0 : (i == 10 ? 4.0 : 8.0))); }\n"
        + "void main() {\n"
        + "  vec2 uv = fragUv();\n"
        + "  vec4 center = texture(uSource, uv);\n"
        + "  float lc = luma(center.rgb);\n"
        + "  float ld = L(uv + vec2(0.0, -uTexel.y));\n"
        + "  float lu = L(uv + vec2(0.0, uTexel.y));\n"
        + "  float ll = L(uv + vec2(-uTexel.x, 0.0));\n"
        + "  float lr = L(uv + vec2(uTexel.x, 0.0));\n"
        + "  float lmin = min(lc, min(min(ld, lu), min(ll, lr)));\n"
        + "  float lmax = max(lc, max(max(ld, lu), max(ll, lr)));\n"
        + "  float range = lmax - lmin;\n"
        + "  if (range < max(0.0312, lmax * 0.125)) { outColor = center; return; }\n"
        + "  float ldl = L(uv + vec2(-uTexel.x, -uTexel.y));\n"
        + "  float lur = L(uv + vec2(uTexel.x, uTexel.y));\n"
        + "  float lul = L(uv + vec2(-uTexel.x, uTexel.y));\n"
        + "  float ldr = L(uv + vec2(uTexel.x, -uTexel.y));\n"
        + "  float ldu = ld + lu;\n"
        + "  float llr = ll + lr;\n"
        + "  float leftCorners = ldl + lul;\n"
        + "  float downCorners = ldl + ldr;\n"
        + "  float rightCorners = ldr + lur;\n"
        + "  float upCorners = lur + lul;\n"
        + "  float edgeH = abs(-2.0 * ll + leftCorners) + abs(-2.0 * lc + ldu) * 2.0 + abs(-2.0 * lr + rightCorners);\n"
        + "  float edgeV = abs(-2.0 * lu + upCorners) + abs(-2.0 * lc + llr) * 2.0 + abs(-2.0 * ld + downCorners);\n"
        + "  bool horizontal = edgeH >= edgeV;\n"
        + "  float l1 = horizontal ? ld : ll;\n"
        + "  float l2 = horizontal ? lu : lr;\n"
        + "  float g1 = l1 - lc;\n"
        + "  float g2 = l2 - lc;\n"
        + "  bool steep1 = abs(g1) >= abs(g2);\n"
        + "  float gradientScaled = 0.25 * max(abs(g1), abs(g2));\n"
        + "  float stepLength = horizontal ? uTexel.y : uTexel.x;\n"
        + "  float localAverage;\n"
        + "  if (steep1) { stepLength = -stepLength; localAverage = 0.5 * (l1 + lc); }\n"
        + "  else { localAverage = 0.5 * (l2 + lc); }\n"
        + "  vec2 cur = uv;\n"
        + "  if (horizontal) cur.y += stepLength * 0.5; else cur.x += stepLength * 0.5;\n"
        + "  vec2 offset = horizontal ? vec2(uTexel.x, 0.0) : vec2(0.0, uTexel.y);\n"
        + "  vec2 uv1 = cur - offset;\n"
        + "  vec2 uv2 = cur + offset;\n"
        + "  float end1 = L(uv1) - localAverage;\n"
        + "  float end2 = L(uv2) - localAverage;\n"
        + "  bool reached1 = abs(end1) >= gradientScaled;\n"
        + "  bool reached2 = abs(end2) >= gradientScaled;\n"
        + "  if (!reached1) uv1 -= offset;\n"
        + "  if (!reached2) uv2 += offset;\n"
        + "  if (!(reached1 && reached2)) {\n"
        + "    for (int i = 2; i < 12; i++) {\n"
        + "      if (!reached1) end1 = L(uv1) - localAverage;\n"
        + "      if (!reached2) end2 = L(uv2) - localAverage;\n"
        + "      reached1 = abs(end1) >= gradientScaled;\n"
        + "      reached2 = abs(end2) >= gradientScaled;\n"
        + "      if (!reached1) uv1 -= offset * stepScale(i);\n"
        + "      if (!reached2) uv2 += offset * stepScale(i);\n"
        + "      if (reached1 && reached2) break;\n"
        + "    }\n"
        + "  }\n"
        + "  float dist1 = horizontal ? (uv.x - uv1.x) : (uv.y - uv1.y);\n"
        + "  float dist2 = horizontal ? (uv2.x - uv.x) : (uv2.y - uv.y);\n"
        + "  bool dir1 = dist1 < dist2;\n"
        + "  float distFinal = min(dist1, dist2);\n"
        + "  float thickness = dist1 + dist2;\n"
        + "  float pixelOffset = thickness > 0.0 ? -distFinal / thickness + 0.5 : 0.0;\n"
        + "  bool centerSmaller = lc < localAverage;\n"
        + "  bool correct = ((dir1 ? end1 : end2) < 0.0) != centerSmaller;\n"
        + "  float finalOffset = correct ? pixelOffset : 0.0;\n"
        + "  float average = (1.0 / 12.0) * (2.0 * (ldu + llr) + leftCorners + rightCorners);\n"
        + "  float sub1 = clamp(abs(average - lc) / range, 0.0, 1.0);\n"
        + "  float sub2 = (-2.0 * sub1 + 3.0) * sub1 * sub1;\n"
        + "  finalOffset = max(finalOffset, sub2 * sub2 * 0.75);\n"
        + "  vec2 finalUv = uv;\n"
        + "  if (horizontal) finalUv.y += finalOffset * stepLength; else finalUv.x += finalOffset * stepLength;\n"
        + "  outColor = vec4(texture(uSource, clampUv(finalUv)).rgb, center.a);\n"
        + "}\n";
  }

  // Fragment sources built once on first use; res.program only compiles on a cache miss, but
  // its source argument is evaluated on every call.
  let compositeFs = null;
  let compositeSsrFs = null;
  let fxaaFs = null;

  function compositeFragment(ssr) {
    if (ssr) return compositeSsrFs || (compositeSsrFs = compositeSource(true));
    return compositeFs || (compositeFs = compositeSource(false));
  }

  function fxaaFragment() {
    return fxaaFs || (fxaaFs = fxaaSource());
  }

  function half(value) {
    return Math.max(1, Math.ceil(value / 2));
  }

  function setDepthUniforms(gl, program, frame) {
    const p = frame.projection;
    gl.uniform4f(program.uniform("uProjXY"), p[0], p[1], p[2], p[3]);
    gl.uniform4f(program.uniform("uProjZ"), p[4], p[5], p[6], p[7]);
    gl.uniform2f(program.uniform("uRectSize"), frame.rectWidth, frame.rectHeight);
    gl.uniform2f(program.uniform("uRectMax"), frame.rectWidth - 1, frame.rectHeight - 1);
  }

  function runSsao(res, frame, settings) {
    const gl = res.gl;
    const G = Q.gl;
    const aoW = half(frame.width);
    const aoH = half(frame.height);
    const rectW = half(frame.rectWidth);
    const rectH = half(frame.rectHeight);
    const raw = res.pool.get(gl, "post.ao", aoW, aoH, "rgba8");
    const blurred = res.pool.get(gl, "post.aoBlur", aoW, aoH, "rgba8");

    const ssao = res.program("ssao", SSAO_FS);
    gl.useProgram(ssao.program);
    G.bindTexture(gl, 0, frame.depth, res.samplers.nearest);
    gl.uniform1i(ssao.uniform("uDepth"), 0);
    setDepthUniforms(gl, ssao, frame);
    gl.uniform1f(ssao.uniform("uRadius"), settings.ssaoRadius);
    gl.uniform1f(ssao.uniform("uIntensity"), settings.ssaoIntensity);
    G.draw(gl, raw.framebuffer, 0, 0, rectW, rectH);

    const blur = res.program("aoBlur", AO_BLUR_FS);
    gl.useProgram(blur.program);
    G.bindTexture(gl, 1, frame.depth, res.samplers.nearest);
    gl.uniform1i(blur.uniform("uAo"), 0);
    gl.uniform1i(blur.uniform("uDepth"), 1);
    setDepthUniforms(gl, blur, frame);
    gl.uniform2i(blur.uniform("uHalfMax"), rectW - 1, rectH - 1);
    G.bindTexture(gl, 0, raw.texture, res.samplers.nearest);
    gl.uniform2i(blur.uniform("uDir"), 1, 0);
    G.draw(gl, blurred.framebuffer, 0, 0, rectW, rectH);
    G.bindTexture(gl, 0, blurred.texture, res.samplers.nearest);
    gl.uniform2i(blur.uniform("uDir"), 0, 1);
    G.draw(gl, raw.framebuffer, 0, 0, rectW, rectH);
    return {texture: raw.texture, width: aoW, height: aoH, rectWidth: rectW, rectHeight: rectH};
  }

  const BLOOM_LEVELS = 4;

  function runBloom(res, frame, settings) {
    const gl = res.gl;
    const G = Q.gl;
    const caps = Q.caps && Q.caps.current ? Q.caps.current() : null;
    const format = caps && caps.source === "attach" && caps.floatTargets && caps.floatTargets.rgba16f
        && caps.extensions && caps.extensions.colorBufferFloat ? "rgba16f" : "rgba8";
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
      levels.push({
        down: res.pool.get(gl, "post.bloomDown" + i, w, h, format),
        up: i < BLOOM_LEVELS - 1 ? res.pool.get(gl, "post.bloomUp" + i, w, h, format) : null,
        width: w, height: h, rectWidth: rw, rectHeight: rh
      });
    }

    const prefilter = res.program("bloomPrefilter", BLOOM_PREFILTER_FS);
    gl.useProgram(prefilter.program);
    G.bindTexture(gl, 0, frame.color, res.samplers.linear);
    gl.uniform1i(prefilter.uniform("uSource"), 0);
    gl.uniform2f(prefilter.uniform("uSrcTexel"), 1 / frame.width, 1 / frame.height);
    gl.uniform2f(prefilter.uniform("uSrcClamp"),
        (frame.rectWidth - 0.5) / frame.width, (frame.rectHeight - 0.5) / frame.height);
    gl.uniform1f(prefilter.uniform("uThreshold"), Math.pow(settings.bloomThreshold, 2.2));
    G.draw(gl, levels[0].down.framebuffer, 0, 0, levels[0].rectWidth, levels[0].rectHeight);

    const down = res.program("bloomDown", BLOOM_DOWN_FS);
    gl.useProgram(down.program);
    gl.uniform1i(down.uniform("uSource"), 0);
    for (let i = 1; i < BLOOM_LEVELS; i++) {
      const src = levels[i - 1];
      G.bindTexture(gl, 0, src.down.texture, res.samplers.linear);
      gl.uniform2f(down.uniform("uSrcTexel"), 1 / src.width, 1 / src.height);
      gl.uniform2f(down.uniform("uSrcClamp"),
          (src.rectWidth - 0.5) / src.width, (src.rectHeight - 0.5) / src.height);
      G.draw(gl, levels[i].down.framebuffer, 0, 0, levels[i].rectWidth, levels[i].rectHeight);
    }

    const up = res.program("bloomUp", BLOOM_UP_FS);
    gl.useProgram(up.program);
    gl.uniform1i(up.uniform("uLow"), 0);
    gl.uniform1i(up.uniform("uCurrent"), 1);
    let low = levels[BLOOM_LEVELS - 1].down;
    let lowLevel = levels[BLOOM_LEVELS - 1];
    for (let i = BLOOM_LEVELS - 2; i >= 0; i--) {
      const cur = levels[i];
      G.bindTexture(gl, 0, low.texture, res.samplers.linear);
      G.bindTexture(gl, 1, cur.down.texture, res.samplers.linear);
      gl.uniform2f(up.uniform("uLowTexel"), 1 / lowLevel.width, 1 / lowLevel.height);
      gl.uniform2f(up.uniform("uLowClamp"),
          (lowLevel.rectWidth - 0.5) / lowLevel.width, (lowLevel.rectHeight - 0.5) / lowLevel.height);
      gl.uniform2f(up.uniform("uCurTexel"), 1 / cur.width, 1 / cur.height);
      gl.uniform2f(up.uniform("uCurClamp"),
          (cur.rectWidth - 0.5) / cur.width, (cur.rectHeight - 0.5) / cur.height);
      G.draw(gl, cur.up.framebuffer, 0, 0, cur.rectWidth, cur.rectHeight);
      low = cur.up;
      lowLevel = cur;
    }
    const top = levels[0];
    return {texture: top.up.texture, width: top.width, height: top.height,
      rectWidth: top.rectWidth, rectHeight: top.rectHeight};
  }

  function hasDepthInputs(frame) {
    return !!(frame.depth && frame.projection && frame.projection.length === 8
        && frame.projection[0] !== 0 && frame.projection[1] !== 0);
  }

  // Copies the world rectangle of the game texture into a pooled target, so a following stage
  // can write the game texture while reading the copy (WebGL forbids feedback loops).
  function copyRect(res, frame, texture) {
    const gl = res.gl;
    const copy = res.pool.get(gl, "post.copy", frame.width, frame.height, "rgba8");
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, res.external.get(gl, texture));
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, copy.framebuffer);
    gl.blitFramebuffer(0, 0, frame.rectWidth, frame.rectHeight, 0, 0, frame.rectWidth,
        frame.rectHeight, gl.COLOR_BUFFER_BIT, gl.NEAREST);
    return copy.texture;
  }

  // Runs the enabled stages. finalFramebuffer, when given, wraps frame.color: the last stage
  // draws into it over the world rectangle and null is returned. Without it, the texture holding
  // the result is returned (frame.color itself when no stage ran). No stage ever samples
  // frame.color while drawing into finalFramebuffer.
  function run(res, frame, settings, finalFramebuffer) {
    const gl = res.gl;
    const G = Q.gl;
    const depthOk = hasDepthInputs(frame);
    const useAo = settings.ssao && depthOk;
    const useBloom = settings.bloom;
    const useSsr = settings.ssr && depthOk;
    const useTonemap = settings.tonemap;
    const useComposite = useAo || useBloom || useSsr || useTonemap;
    const stats = res.stats;

    let ao = null;
    let bloom = null;
    if (useAo) ao = runSsao(res, frame, settings);
    if (useBloom) bloom = runBloom(res, frame, settings);

    let current = frame.color;
    if (useComposite) {
      const target = res.pool.get(gl, "post.composite", frame.width, frame.height, "rgba8");
      const program = res.program(useSsr ? "compositeSsr" : "composite", compositeFragment(useSsr));
      gl.useProgram(program.program);
      G.setRect(gl, program, frame.width, frame.height, frame.rectWidth, frame.rectHeight);
      G.bindTexture(gl, 0, frame.color, res.samplers.linear);
      gl.uniform1i(program.uniform("uScene"), 0);
      if (ao) {
        G.bindTexture(gl, 1, ao.texture, res.samplers.linear);
        gl.uniform2f(program.uniform("uAoUvScale"), 0.5 / ao.width, 0.5 / ao.height);
        gl.uniform2f(program.uniform("uAoClamp"),
            (ao.rectWidth - 0.5) / ao.width, (ao.rectHeight - 0.5) / ao.height);
        gl.uniform1f(program.uniform("uAoStrength"), 0.85);
      } else {
        G.bindTexture(gl, 1, null, res.samplers.linear);
      }
      gl.uniform1i(program.uniform("uAo"), 1);
      if (bloom) {
        G.bindTexture(gl, 2, bloom.texture, res.samplers.linear);
        gl.uniform2f(program.uniform("uBloomUvScale"), 0.5 / bloom.width, 0.5 / bloom.height);
        gl.uniform2f(program.uniform("uBloomClamp"),
            (bloom.rectWidth - 0.5) / bloom.width, (bloom.rectHeight - 0.5) / bloom.height);
        gl.uniform1f(program.uniform("uBloomIntensity"), settings.bloomIntensity);
      } else {
        G.bindTexture(gl, 2, null, res.samplers.linear);
      }
      gl.uniform1i(program.uniform("uBloom"), 2);
      G.bindTexture(gl, 3, depthOk ? frame.depth : null, res.samplers.nearest);
      gl.uniform1i(program.uniform("uDepth"), 3);
      if (depthOk) setDepthUniforms(gl, program, frame);
      gl.uniform4f(program.uniform("uFlags"), ao ? 1 : 0, bloom ? 1 : 0, useTonemap ? 1 : 0,
          useSsr ? 1 : 0);
      G.draw(gl, target.framebuffer, 0, 0, frame.rectWidth, frame.rectHeight);
      // Unbind the auxiliary inputs so a later stage never samples a stale one.
      G.bindTexture(gl, 1, null, null);
      G.bindTexture(gl, 2, null, null);
      G.bindTexture(gl, 3, null, null);
      current = target.texture;
    }

    if (settings.fxaa) {
      if (finalFramebuffer && current === frame.color) current = copyRect(res, frame, frame.color);
      const program = res.program("fxaa", fxaaFragment());
      const target = finalFramebuffer ? null
          : res.pool.get(gl, "post.fxaa", frame.width, frame.height, "rgba8");
      gl.useProgram(program.program);
      G.setRect(gl, program, frame.width, frame.height, frame.rectWidth, frame.rectHeight);
      G.bindTexture(gl, 0, current, res.samplers.linear);
      gl.uniform1i(program.uniform("uSource"), 0);
      G.draw(gl, target ? target.framebuffer : finalFramebuffer, 0, 0,
          frame.rectWidth, frame.rectHeight);
      stats.lastStages = stageList(ao, bloom, useSsr, useTonemap, true);
      return target ? target.texture : null;
    }

    stats.lastStages = stageList(ao, bloom, useSsr, useTonemap, false);
    if (!finalFramebuffer) return current;
    if (current !== frame.color) {
      gl.bindFramebuffer(gl.READ_FRAMEBUFFER,
          res.pool.get(gl, "post.composite", frame.width, frame.height, "rgba8").framebuffer);
      gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, finalFramebuffer);
      gl.blitFramebuffer(0, 0, frame.rectWidth, frame.rectHeight, 0, 0, frame.rectWidth,
          frame.rectHeight, gl.COLOR_BUFFER_BIT, gl.NEAREST);
    }
    return null;
  }

  function stageList(ao, bloom, ssr, tonemap, fxaa) {
    const stages = [];
    if (ao) stages.push("ssao");
    if (bloom) stages.push("bloom");
    if (ssr) stages.push("ssr");
    if (tonemap) stages.push("tonemap");
    if (fxaa) stages.push("fxaa");
    return stages.join("+");
  }

  function anyStage(settings) {
    return !!(settings.enabled
        && (settings.fxaa || settings.tonemap || settings.ssao || settings.bloom || settings.ssr));
  }

  Q.postChain = Object.freeze({
    run: run,
    anyStage: anyStage,
    BLOOM_LEVELS: BLOOM_LEVELS
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
