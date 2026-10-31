// Gaius world upscaler: edge-adaptive spatial upsampling (EASU) followed by robust
// contrast-adaptive sharpening (RCAS), after the FidelityFX Super Resolution 1 algorithms
// (MIT licensed), written for WebGL2 / GLSL ES 3.00. Registers GaiusQuality.upscaler.
//
// The world renders into the bottom-left sub-rectangle (srcWidth x srcHeight) of the full-size
// frame textures; EASU reconstructs the full frame from that rectangle into a pooled RGBA8
// target, RCAS sharpens it into the destination framebuffer. Both passes use texelFetch with the
// rectangle clamped, so nothing outside the rendered rectangle is ever read.
(function installGaiusUpscaler(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.upscaler) return;

  const EASU_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp int;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uSource;\n"
      + "uniform ivec2 uSrcMax;\n"
      + "uniform vec4 uCon;\n"
      + "out vec4 outColor;\n"
      + "vec4 fetch4(ivec2 p) { return texelFetch(uSource, clamp(p, ivec2(0), uSrcMax), 0); }\n"
      + "vec3 fetch(ivec2 p) { return fetch4(p).rgb; }\n"
      + "float lum(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }\n"
      + "void easuSet(inout vec2 dir, inout float len, float w,\n"
      + "    float lA, float lB, float lC, float lD, float lE) {\n"
      + "  float dc = lD - lC;\n"
      + "  float cb = lC - lB;\n"
      + "  float lenX = max(abs(dc), abs(cb));\n"
      + "  lenX = lenX > 0.0 ? 1.0 / lenX : 0.0;\n"
      + "  float dirX = lD - lB;\n"
      + "  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n"
      + "  lenX *= lenX;\n"
      + "  float ec = lE - lC;\n"
      + "  float ca = lC - lA;\n"
      + "  float lenY = max(abs(ec), abs(ca));\n"
      + "  lenY = lenY > 0.0 ? 1.0 / lenY : 0.0;\n"
      + "  float dirY = lE - lA;\n"
      + "  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n"
      + "  lenY *= lenY;\n"
      + "  dir += vec2(dirX, dirY) * w;\n"
      + "  len += dot(vec2(w), vec2(lenX, lenY));\n"
      + "}\n"
      + "void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2,\n"
      + "    float lob, float clp, vec3 c) {\n"
      + "  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);\n"
      + "  v *= len2;\n"
      + "  float d2 = min(v.x * v.x + v.y * v.y, clp);\n"
      + "  float wB = (2.0 / 5.0) * d2 - 1.0;\n"
      + "  float wA = lob * d2 - 1.0;\n"
      + "  wB *= wB;\n"
      + "  wA *= wA;\n"
      + "  wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);\n"
      + "  float w = wB * wA;\n"
      + "  aC += c * w;\n"
      + "  aW += w;\n"
      + "}\n"
      + "void main() {\n"
      + "  vec2 pp = floor(gl_FragCoord.xy) * uCon.xy + uCon.zw;\n"
      + "  vec2 fp = floor(pp);\n"
      + "  pp -= fp;\n"
      + "  ivec2 o = ivec2(fp);\n"
      + "  vec3 b = fetch(o + ivec2(0, -1));\n"
      + "  vec3 c = fetch(o + ivec2(1, -1));\n"
      + "  vec3 e = fetch(o + ivec2(-1, 0));\n"
      + "  vec4 f4 = fetch4(o);\n"
      + "  vec3 f = f4.rgb;\n"
      + "  vec3 g = fetch(o + ivec2(1, 0));\n"
      + "  vec3 h = fetch(o + ivec2(2, 0));\n"
      + "  vec3 i = fetch(o + ivec2(-1, 1));\n"
      + "  vec3 j = fetch(o + ivec2(0, 1));\n"
      + "  vec3 k = fetch(o + ivec2(1, 1));\n"
      + "  vec3 l = fetch(o + ivec2(2, 1));\n"
      + "  vec3 n = fetch(o + ivec2(0, 2));\n"
      + "  vec3 m = fetch(o + ivec2(1, 2));\n"
      + "  float bL = lum(b); float cL = lum(c); float eL = lum(e); float fL = lum(f);\n"
      + "  float gL = lum(g); float hL = lum(h); float iL = lum(i); float jL = lum(j);\n"
      + "  float kL = lum(k); float lL = lum(l); float nL = lum(n); float mL = lum(m);\n"
      + "  vec2 dir = vec2(0.0);\n"
      + "  float len = 0.0;\n"
      + "  easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n"
      + "  easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n"
      + "  easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n"
      + "  easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, mL);\n"
      + "  vec2 dir2 = dir * dir;\n"
      + "  float dirR = dir2.x + dir2.y;\n"
      + "  bool zro = dirR < (1.0 / 32768.0);\n"
      + "  dirR = zro ? 1.0 : inversesqrt(dirR);\n"
      + "  dir.x = zro ? 1.0 : dir.x;\n"
      + "  dir *= dirR;\n"
      + "  len = len * 0.5;\n"
      + "  len *= len;\n"
      + "  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1e-6);\n"
      + "  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n"
      + "  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n"
      + "  float clp = 1.0 / lob;\n"
      + "  vec3 min4 = min(min(f, g), min(j, k));\n"
      + "  vec3 max4 = max(max(f, g), max(j, k));\n"
      + "  vec3 aC = vec3(0.0);\n"
      + "  float aW = 0.0;\n"
      + "  easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);\n"
      + "  easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);\n"
      + "  easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);\n"
      + "  easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);\n"
      + "  easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);\n"
      + "  easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);\n"
      + "  easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);\n"
      + "  easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);\n"
      + "  easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);\n"
      + "  easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);\n"
      + "  easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, m);\n"
      + "  easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);\n"
      + "  vec3 pix = aW != 0.0 ? aC / aW : f;\n"
      + "  outColor = vec4(min(max4, max(min4, pix)), f4.a);\n"
      + "}\n";

  const RCAS_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp int;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uSource;\n"
      + "uniform ivec2 uMax;\n"
      + "uniform float uSharpness;\n"
      + "out vec4 outColor;\n"
      + "vec3 tap(ivec2 p) { return texelFetch(uSource, clamp(p, ivec2(0), uMax), 0).rgb; }\n"
      + "float lum(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }\n"
      + "void main() {\n"
      + "  ivec2 sp = ivec2(gl_FragCoord.xy);\n"
      + "  vec4 e4 = texelFetch(uSource, clamp(sp, ivec2(0), uMax), 0);\n"
      + "  vec3 e = e4.rgb;\n"
      + "  vec3 b = tap(sp + ivec2(0, 1));\n"
      + "  vec3 d = tap(sp + ivec2(-1, 0));\n"
      + "  vec3 f = tap(sp + ivec2(1, 0));\n"
      + "  vec3 h = tap(sp + ivec2(0, -1));\n"
      + "  vec3 mn4 = min(min(b, d), min(f, h));\n"
      + "  vec3 mx4 = max(max(b, d), max(f, h));\n"
      + "  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-5));\n"
      + "  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));\n"
      + "  vec3 lobeRGB = max(-hitMin, hitMax);\n"
      + "  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uSharpness;\n"
      + "  float bL = lum(b); float dL = lum(d); float eL = lum(e); float fL = lum(f); float hL = lum(h);\n"
      + "  float nz = 0.25 * (bL + dL + fL + hL) - eL;\n"
      + "  float range = max(max(max(bL, dL), max(fL, hL)), eL) - min(min(min(bL, dL), min(fL, hL)), eL);\n"
      + "  nz = range > 0.0 ? clamp(abs(nz) / range, 0.0, 1.0) : 0.0;\n"
      + "  lobe *= -0.5 * nz + 1.0;\n"
      + "  float rcpL = 1.0 / (4.0 * lobe + 1.0);\n"
      + "  vec3 pix = (lobe * (b + d + h + f) + e) * rcpL;\n"
      + "  outColor = vec4(clamp(pix, 0.0, 1.0), e4.a);\n"
      + "}\n";

  // Bilinear copy of a sub-rectangle to the full target, used for auxiliary targets (the entity
  // outline) that must line up with the upscaled world.
  const STRETCH_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp sampler2D;\n"
      + "uniform sampler2D uSource;\n"
      + "uniform vec2 uScale;\n"
      + "uniform vec2 uMaxUv;\n"
      + "uniform vec2 uMinUv;\n"
      + "out vec4 outColor;\n"
      + "void main() {\n"
      + "  vec2 uv = clamp(gl_FragCoord.xy * uScale, uMinUv, uMaxUv);\n"
      + "  outColor = texture(uSource, uv);\n"
      + "}\n";

  // Upscales source[0..srcWidth, 0..srcHeight] (texture size texWidth x texHeight) to a
  // dstWidth x dstHeight framebuffer. sharpness is in stops (0 = strongest); negative skips RCAS.
  function run(res, source, srcWidth, srcHeight, dstFramebuffer, dstWidth, dstHeight, sharpness) {
    const gl = res.gl;
    const G = Q.gl;
    const easu = res.program("easu", EASU_FS);
    const sharpen = sharpness >= 0;
    const intermediate = sharpen
        ? res.pool.get(gl, "upscale.easu", dstWidth, dstHeight, "rgba8") : null;

    gl.useProgram(easu.program);
    G.bindTexture(gl, 0, source, res.samplers.nearest);
    gl.uniform1i(easu.uniform("uSource"), 0);
    gl.uniform2i(easu.uniform("uSrcMax"), srcWidth - 1, srcHeight - 1);
    const scaleX = srcWidth / dstWidth;
    const scaleY = srcHeight / dstHeight;
    gl.uniform4f(easu.uniform("uCon"), scaleX, scaleY, 0.5 * scaleX - 0.5, 0.5 * scaleY - 0.5);
    G.draw(gl, sharpen ? intermediate.framebuffer : dstFramebuffer, 0, 0, dstWidth, dstHeight);
    if (!sharpen) return;

    const rcas = res.program("rcas", RCAS_FS);
    gl.useProgram(rcas.program);
    G.bindTexture(gl, 0, intermediate.texture, res.samplers.nearest);
    gl.uniform1i(rcas.uniform("uSource"), 0);
    gl.uniform2i(rcas.uniform("uMax"), dstWidth - 1, dstHeight - 1);
    gl.uniform1f(rcas.uniform("uSharpness"), Math.pow(2, -Math.max(0, sharpness)));
    G.draw(gl, dstFramebuffer, 0, 0, dstWidth, dstHeight);
  }

  // Stretches texture's bottom-left srcWidth x srcHeight to its full width x height in place
  // (through a pooled scratch target).
  function stretchInPlace(res, texture, srcWidth, srcHeight, width, height) {
    const gl = res.gl;
    const G = Q.gl;
    const scratch = res.pool.get(gl, "upscale.stretch", width, height, "rgba8");
    const program = res.program("stretch", STRETCH_FS);
    gl.useProgram(program.program);
    G.bindTexture(gl, 0, texture, res.samplers.linear);
    gl.uniform1i(program.uniform("uSource"), 0);
    gl.uniform2f(program.uniform("uScale"), srcWidth / (width * width), srcHeight / (height * height));
    gl.uniform2f(program.uniform("uMinUv"), 0.5 / width, 0.5 / height);
    gl.uniform2f(program.uniform("uMaxUv"), (srcWidth - 0.5) / width, (srcHeight - 0.5) / height);
    G.draw(gl, scratch.framebuffer, 0, 0, width, height);
    // Copy back 1:1.
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, scratch.framebuffer);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, res.external.get(gl, texture));
    gl.blitFramebuffer(0, 0, width, height, 0, 0, width, height, gl.COLOR_BUFFER_BIT, gl.NEAREST);
  }

  Q.upscaler = Object.freeze({
    run: run,
    stretchInPlace: stretchInPlace
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
