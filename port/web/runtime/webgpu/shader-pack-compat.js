(function(root) {
  "use strict";
  var routes = [
    [/terrain.*(cutout|alpha)/, "gbuffers_terrain_cutout"],
    [/terrain.*transluc|water|translucent/, "gbuffers_water"],
    [/terrain|chunk|solid/, "gbuffers_terrain"],
    [/block.?entity|blockentity/, "gbuffers_block"],
    [/entity|mob|player/, "gbuffers_entities"],
    [/sky.*textur|skybox.*texture/, "gbuffers_skytextured"],
    [/sky|stars/, "gbuffers_skybasic"],
    [/hand|item.?in.?hand/, "gbuffers_hand"],
    [/weather|rain|snow/, "gbuffers_weather"],
    [/cloud/, "gbuffers_clouds"],
    [/basic|textured|gui/, "gbuffers_basic"]
  ];
  var uniforms = [
    ["gbufferModelView", "mat4"], ["gbufferModelViewInverse", "mat4"],
    ["gbufferProjection", "mat4"], ["gbufferProjectionInverse", "mat4"],
    ["gbufferPreviousModelView", "mat4"], ["gbufferPreviousProjection", "mat4"],
    ["cameraPosition", "vec3"], ["previousCameraPosition", "vec3"],
    ["sunPosition", "vec3"], ["moonPosition", "vec3"], ["shadowLightPosition", "vec3"],
    ["worldTime", "float"], ["frameTimeCounter", "float"], ["rainStrength", "float"],
    ["eyeBrightnessSmooth", "vec2"], ["near", "float"], ["far", "float"],
    ["viewWidth", "float"], ["viewHeight", "float"], ["aspectRatio", "float"]
  ];
  function route(name) {
    var key = String(name || "").toLowerCase().replace(/[^a-z0-9]+/g, "_");
    for (var i = 0; i < routes.length; i++) if (routes[i][0].test(key)) return routes[i][1];
    return "gbuffers_basic";
  }
  function parseDirectives(source) {
    var result = {drawBuffers: null, renderTargets: null, mipmapped: [], clear: [], source: String(source || "")};
    result.source = result.source.replace(/(\/\*[\s\S]*?\*\/|\/\/[^\r\n]*)/g, function(line) {
      var m = /(?:\/\*|\/\/)?\s*(RENDERTARGETS|DRAWBUFFERS)\s*:\s*([0-7,]+)\s*(?:\*\/)?/i.exec(line);
      if (!m) return line;
      var list = m[2].indexOf(",") >= 0 ? m[2].split(",").map(Number) : m[2].split("").map(Number);
      if (m[1].toUpperCase() === "DRAWBUFFERS") result.drawBuffers = list;
      else result.renderTargets = list;
      return "";
    });
    var mip = /\bmipmap\.(?:(?:enabled\.)?)colortex([0-7])\s*=\s*true/gi, match;
    while ((match = mip.exec(String(source || "")))) result.mipmapped.push(Number(match[1]));
    var clear = /\bclear\.colortex([0-7])\s*=\s*true/gi;
    while ((match = clear.exec(String(source || "")))) result.clear.push(Number(match[1]));
    result.targets = result.renderTargets || result.drawBuffers || [0];
    return result;
  }
  function uniformLayout(values) {
    var slots = [], offset = 0;
    for (var i = 0; i < uniforms.length; i++) {
      var name = uniforms[i][0], type = uniforms[i][1], width = type === "mat4" ? 64 : type === "vec3" ? 16 : type === "vec2" ? 8 : 4;
      if (width < 16 && (offset & 15) + width > 16) offset = (offset + 15) & ~15;
      slots.push({name: name, type: type, offset: offset, size: width});
      offset += width;
      if (width >= 16) offset = (offset + 15) & ~15;
    }
    var data = new Float32Array((offset + 15) >> 2);
    values = values || {};
    for (i = 0; i < slots.length; i++) {
      var slot = slots[i], value = values[slot.name];
      if (typeof value === "number") data[slot.offset >> 2] = value;
      else if (value && value.length) for (var j = 0; j < Math.min(value.length, slot.size >> 2); j++) data[(slot.offset >> 2) + j] = value[j];
    }
    return {slots: slots, byteLength: data.byteLength, data: data};
  }
  function program(files, name) {
    var choices = [name, "world0/" + name, "program/" + name.replace(/^gbuffers_/, ""),
      "program/" + (name === "gbuffers_terrain" ? "basic" : name)];
    for (var i = 0; i < choices.length; i++) {
      var base = "shaders/" + choices[i];
      var v = base + ".vsh", f = base + ".fsh";
      if (files[v] !== undefined && files[f] !== undefined) return {vertex: v, fragment: f};
    }
    return null;
  }
  root.GaiusShaderPackCompat = {route: route, parseDirectives: parseDirectives,
    uniformLayout: uniformLayout, program: program, uniforms: uniforms};
})(typeof globalThis !== "undefined" ? globalThis : this);
