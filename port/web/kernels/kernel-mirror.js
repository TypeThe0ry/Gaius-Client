(function (global) {
  "use strict";

  const FAMILIES = Object.freeze({
    noise_points: "noise", load_model_table: "mesher", mesh_section: "mesher",
    light_column: "light", load_generator: "worldgen", biomes: "worldgen",
    terrain: "worldgen", surface: "worldgen", shader_xlat: "shaderxlat", shader_link: "shaderxlat",
  });
  let expectedProfile = null;

  function bind(table, profile) {
    expectedProfile = typeof profile === "string" && profile ? profile : null;
    global.__gaiusKernelSemantics = table;
  }

  function enabled(kernel, profile) {
    const table = global.__gaiusKernelSemantics;
    const expected = profile || global.__gaiusProfileId || expectedProfile;
    return !!(table && table.schemaVersion === 1 && typeof table.profile === "string" && table.profile
      && (!expected || table.profile === expected) && table.kernels
      && Object.prototype.hasOwnProperty.call(table.kernels, kernel)
      && table.kernels[kernel] && table.kernels[kernel].enabled === true);
  }

  function family(kind) {
    return FAMILIES[kind] || null;
  }

  function allowed(kind, kernel, profile) {
    const name = family(kind);
    return !!name && (!kernel || name === kernel) && enabled(name, profile);
  }

  global.GaiusKernelMirror = Object.freeze({bind, enabled, family, allowed});
})(typeof globalThis !== "undefined" ? globalThis : self);
