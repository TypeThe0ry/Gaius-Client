// Gaius quality profile: turns the GPU tier from gpu-caps.js into concrete runtime settings
// (canvas pixel-ratio cap, world render scale, post-processing stages, order-independent
// transparency gate, inventory world refresh rate). Registers GaiusQuality.profile.
//
// Every setting has a URL override for testing and a persisted per-browser value for the
// switches a player may toggle at runtime (localStorage gaius.quality.settings.v1):
//   ?gaiusPost=0|1                  Gaius post-processing chain off/on (default on for the
//                                   high and ultra tiers only)
//   ?gaiusPostTier=low|mid|high|ultra  post stage set independent of the GPU tier
//   ?gaiusFxaa= ?gaiusTonemap= ?gaiusSsao= ?gaiusBloom= ?gaiusSsr=   per-stage 0|1
//   ?gaiusRenderScale=1|0.85|0.75|0.67|0.6|0.5|auto   world render scale (default 1)
//   ?gaiusTargetFps=<n>             frame-rate target of the automatic render scale (default 60)
//   ?gaiusSharpness=0..2            RCAS sharpening in stops (0 = strongest, default 0.25)
//   ?gaiusMaxDpr=<number>           canvas device-pixel-ratio cap (default per tier on the
//                                   renderpearl generations, 1 on the blaze3d profiles)
//   ?gaiusOit=0|1                   force improved transparency (OIT) off/on
//   ?gaiusInventoryWorldFps=<n>     world refresh rate behind inventory screens (0 = freeze)
//   ?gaiusPresetReplay=1            vanilla behaviour: re-apply the graphics preset at startup
// Vanilla behaviour is one switch away: ?gaiusPost=0&gaiusRenderScale=1&gaiusOit=0.
(function installGaiusQualityProfile(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.profile) return;

  const SETTINGS_KEY = "gaius.quality.settings.v1";
  const SCALE_STEPS = Object.freeze([0.5, 0.6, 0.67, 0.75, 0.85, 1]);
  const PROFILE_CAPABILITIES = Object.freeze({
    "1.21.11": Object.freeze({renderSeam: "blaze3d-direct-0", patchSet: "legacy-12111", terrainSectionUniformBlock: false}),
    "26.2": Object.freeze({renderSeam: "blaze3d-backend-1", patchSet: "modern", terrainSectionUniformBlock: true}),
    "26.3": Object.freeze({renderSeam: "renderpearl-1", patchSet: "modern", terrainSectionUniformBlock: true}),
  });

  // Per-tier defaults. maxDpr caps the canvas allocation (the GUI always renders at the canvas
  // resolution): low and mid keep the v0.3 1x cap, because a native-ratio backing store
  // quadruples every world, GUI and post pass on a 2x display; high and ultra may go above it
  // on the renderpearl generations only (resolve() keeps the 1x cap on the blaze3d profiles).
  // renderScale only scales the 3D world, which is then upscaled with EASU/RCAS. The tone
  // mapper is off on every tier (it compresses highlights and lifts saturation, changing the
  // vanilla look); the composite pass then only adds SSAO and bloom and clamps.
  const TIER_DEFAULTS = Object.freeze({
    low: Object.freeze({
      maxDpr: 1,
      renderScale: 1,
      fxaa: true, tonemap: false, ssao: false, bloom: false, ssr: false,
      oit: false,
      inventoryWorldFps: 0,
      ssaoRadius: 0.8, ssaoIntensity: 0.9, bloomIntensity: 0.10, bloomThreshold: 0.80
    }),
    mid: Object.freeze({
      maxDpr: 1,
      renderScale: 1,
      fxaa: true, tonemap: false, ssao: true, bloom: true, ssr: false,
      oit: false,
      inventoryWorldFps: 15,
      ssaoRadius: 0.9, ssaoIntensity: 1.0, bloomIntensity: 0.12, bloomThreshold: 0.78
    }),
    high: Object.freeze({
      maxDpr: 1.5,
      renderScale: 1,
      fxaa: true, tonemap: false, ssao: true, bloom: true, ssr: false,
      oit: true,
      inventoryWorldFps: 30,
      ssaoRadius: 1.0, ssaoIntensity: 1.0, bloomIntensity: 0.14, bloomThreshold: 0.76
    }),
    ultra: Object.freeze({
      maxDpr: 2,
      renderScale: 1,
      fxaa: true, tonemap: false, ssao: true, bloom: true, ssr: false,
      oit: true,
      inventoryWorldFps: 60,
      ssaoRadius: 1.0, ssaoIntensity: 1.05, bloomIntensity: 0.15, bloomThreshold: 0.75
    })
  });

  const state = {
    settings: null,
    persisted: null
  };

  function params() {
    try {
      return new URLSearchParams(root.location ? root.location.search : "");
    } catch (error) {
      return null;
    }
  }

  function readPersisted() {
    if (state.persisted) return state.persisted;
    let value = {};
    try {
      const raw = root.localStorage ? root.localStorage.getItem(SETTINGS_KEY) : null;
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === "object") value = parsed;
      }
    } catch (error) {
      value = {};
    }
    state.persisted = value;
    return value;
  }

  function writePersisted(patch) {
    const value = Object.assign({}, readPersisted(), patch);
    state.persisted = value;
    try {
      if (root.localStorage) root.localStorage.setItem(SETTINGS_KEY, JSON.stringify(value));
    } catch (error) {
      // Private mode or blocked storage: the switch still applies for this page.
    }
    invalidate();
    return value;
  }

  function bool(value, fallback) {
    if (value === null || value === undefined || value === "") return fallback;
    const text = String(value).toLowerCase();
    if (text === "1" || text === "true" || text === "on" || text === "yes") return true;
    if (text === "0" || text === "false" || text === "off" || text === "no") return false;
    return fallback;
  }

  function number(value, fallback, min, max) {
    if (value === null || value === undefined || value === "") return fallback;
    const parsed = Number(value);
    if (!isFinite(parsed)) return fallback;
    return Math.max(min, Math.min(max, parsed));
  }

  function quantizeScale(value) {
    let best = SCALE_STEPS[SCALE_STEPS.length - 1];
    let distance = Infinity;
    for (let i = 0; i < SCALE_STEPS.length; i++) {
      const d = Math.abs(SCALE_STEPS[i] - value);
      if (d < distance) {
        distance = d;
        best = SCALE_STEPS[i];
      }
    }
    return best;
  }

  function currentCaps() {
    if (Q.caps && typeof Q.caps.current === "function") {
      const caps = Q.caps.current();
      if (caps) return caps;
    }
    return root.__gaiusGpuCaps || null;
  }

  // Profile defaults are generated from port/versions/*.json; the page value is a diagnostic override.
  function renderPearlProfile() {
    try {
      const profile = PROFILE_CAPABILITIES[String(root.__gaiusProfileId || "").trim()];
      const override = root.__gaiusRenderSeam;
      const seam = typeof override === "string" && override.trim()
        ? override : profile ? profile.renderSeam : "";
      return /^renderpearl-\d+$/.test(String(seam).trim());
    } catch (error) {
      return false;
    }
  }

  function tierName(caps) {
    const name = caps && caps.tier ? String(caps.tier) : "mid";
    const gpuTier = TIER_DEFAULTS[name] ? name : "mid";
    const p = params();
    const override = p && p.get("gaiusMemoryTier");
    const deviceMemory = Number(root.navigator && root.navigator.deviceMemory);
    const heapLimit = Number(root.performance && root.performance.memory
        && root.performance.memory.jsHeapSizeLimit);
    const constrained = (deviceMemory > 0 && deviceMemory <= 4)
        || (heapLimit > 0 && heapLimit <= 1024 * 1024 * 1024);
    const selected = root.__gaiusRuntimeSelection && root.__gaiusRuntimeSelection.selected;
    const wasm = selected ? selected === "wasm"
        : !!(root.__gaiusWasmTimings || root.__gaiusWasmMemory
            || (p && p.get("runtime") === "wasm"));
    const enabled = wasm && override !== "off" && override !== "0";
    const lowMemory = enabled && (override === "low" || constrained);
    root.__gaiusMemoryTier = {
      chosenTier: lowMemory ? "low" : gpuTier,
      gpuTier: gpuTier,
      reason: lowMemory ? (override === "low" ? "override"
          : deviceMemory > 0 && deviceMemory <= 4 ? "device-memory" : "heap-limit") : "gpu",
      deviceMemoryGiB: deviceMemory > 0 ? deviceMemory : null,
      heapLimitBytes: heapLimit > 0 ? heapLimit : null,
      lowMemory: !!lowMemory,
      vanillaDefaults: lowMemory ? {renderDistance: 6, simulationDistance: 4,
        mipmapLevels: 2, particles: "decreased", graphics: "fast", oit: false} : null
    };
    return root.__gaiusMemoryTier.chosenTier;
  }

  function resolve() {
    const caps = currentCaps();
    const tier = tierName(caps);
    const base = TIER_DEFAULTS[tier];
    const p = params();
    const get = function (name) {
      return p ? p.get(name) : null;
    };
    const persisted = readPersisted();

    const postTierParam = get("gaiusPostTier");
    const postTier = postTierParam && TIER_DEFAULTS[String(postTierParam).toLowerCase()]
        ? String(postTierParam).toLowerCase() : tier;
    const post = TIER_DEFAULTS[postTier];

    // Off by default on low (software rasterizers included) and mid (integrated GPUs), where the
    // full-resolution passes cost several milliseconds a frame; setPostEnabled(true) or
    // ?gaiusPost=1 turns the chain on there.
    let postEnabled = typeof persisted.post === "boolean" ? persisted.post
        : (tier === "high" || tier === "ultra");
    postEnabled = bool(get("gaiusPost"), postEnabled);

    let ssr = typeof persisted.ssr === "boolean" ? persisted.ssr : post.ssr;
    ssr = bool(get("gaiusSsr"), ssr);

    let scaleSetting = persisted.renderScale !== undefined ? persisted.renderScale : base.renderScale;
    const scaleParam = get("gaiusRenderScale");
    if (scaleParam !== null && scaleParam !== "") scaleSetting = scaleParam;
    let scaleMode = "fixed";
    let renderScale = 1;
    if (String(scaleSetting).toLowerCase() === "auto") {
      scaleMode = "auto";
      renderScale = 1;
    } else {
      renderScale = quantizeScale(number(scaleSetting, 1, SCALE_STEPS[0], 1));
    }

    // Only the renderpearl generations have the world render scale and EASU path behind a large
    // backing store; the blaze3d profiles share the canvas code but keep the v0.3 1x cap unless
    // ?gaiusMaxDpr asks otherwise.
    const dprParam = get("gaiusMaxDpr");
    const profileDpr = renderPearlProfile() ? base.maxDpr : Math.min(base.maxDpr, 1);
    const maxDpr = number(dprParam, profileDpr, 0.5, 4);

    let oitForced = null;
    const oitParam = get("gaiusOit");
    if (oitParam !== null && oitParam !== "") oitForced = bool(oitParam, null);

    const fpsParam = get("gaiusInventoryWorldFps");
    const inventoryWorldFps = number(fpsParam, base.inventoryWorldFps, 0, 240);

    const targetFps = number(get("gaiusTargetFps"), 60, 20, 240);

    const sharpness = number(get("gaiusSharpness"),
        typeof persisted.sharpness === "number" ? persisted.sharpness : 0.25, 0, 2);

    const settings = {
      tier: tier,
      tierIndex: ["low", "mid", "high", "ultra"].indexOf(tier),
      memoryTier: root.__gaiusMemoryTier,
      postTier: postTier,
      maxDpr: maxDpr,
      renderScaleMode: scaleMode,
      renderScale: renderScale,
      renderScaleSteps: SCALE_STEPS,
      targetFps: targetFps,
      sharpness: sharpness,
      post: {
        enabled: postEnabled,
        fxaa: bool(get("gaiusFxaa"), post.fxaa),
        tonemap: bool(get("gaiusTonemap"), post.tonemap),
        ssao: bool(get("gaiusSsao"), post.ssao),
        bloom: bool(get("gaiusBloom"), post.bloom),
        ssr: ssr,
        ssaoRadius: post.ssaoRadius,
        ssaoIntensity: post.ssaoIntensity,
        bloomIntensity: post.bloomIntensity,
        bloomThreshold: post.bloomThreshold
      },
      oitTier: base.oit,
      oitForced: oitForced,
      inventoryWorldFps: inventoryWorldFps,
      presetReplay: bool(get("gaiusPresetReplay"), false)
    };
    state.settings = settings;
    try {
      root.__gaiusQualitySettings = settings;
    } catch (error) {
      // ignore
    }
    return settings;
  }

  function settings() {
    return state.settings || resolve();
  }

  function invalidate() {
    state.settings = null;
  }

  // Order-independent transparency (26.3 "Improved Transparency") needs float colour targets with
  // blending: RGBA16F accumulation/transmittance targets, RGBA32F depth bounds blended with MAX,
  // two colour outputs from one draw, and per-draw-buffer blend enables and write masks
  // (OES_draw_buffers_indexed; without it BrowserOpenGL drops the index > 0 calls).
  function oitAllowed() {
    const s = settings();
    if (s.oitForced === false) return false;
    const caps = currentCaps();
    const ext = caps && caps.extensions;
    const float = caps && caps.floatTargets;
    const capable = !!(ext && float && ext.colorBufferFloat && ext.floatBlend
        && ext.drawBuffersIndexed && float.rgba16f && float.rgba32f && (caps.maxDrawBuffers || 0) >= 2
        && !caps.contextLost);
    if (s.oitForced === true) return capable;
    return capable && s.oitTier;
  }

  // Device-pixel-ratio for the canvas backing store. Never upsamples above the device ratio.
  function resolvePixelRatio(devicePixelRatio) {
    const raw = Number(devicePixelRatio) > 0 ? Number(devicePixelRatio) : 1;
    return Math.max(0.5, Math.min(raw, settings().maxDpr));
  }

  // --- automatic video preset ----------------------------------------------------------------
  //
  // BrowserFilePersistence seeds options.txt with the tier's vanilla video options on the first
  // start of a browser profile. This remembers what it wrote (localStorage
  // gaius.quality.autoPreset.v1, per storage profile) so a later start can tell whether the
  // player has changed any of those options since:
  //   - unchanged: the options still follow the automatic preset, and a different tier (a new
  //     classification, a ?gaiusTier override) re-applies that tier's values;
  //   - changed: the player's choice wins, and this profile is never adjusted again;
  //   - no record (a profile from before automatic presets, or blocked storage): left alone.
  // Options the game no longer writes (a key another version does not know) are not compared.
  // The frame settings (maxFps, enableVsync) are vanilla's on every tier and never touched.
  const AUTO_PRESET_KEY = "gaius.quality.autoPreset.v1";
  const AUTO_PRESET_OPTIONS = Object.freeze(["graphicsPreset", "renderDistance",
    "simulationDistance", "entityDistanceScaling", "renderClouds", "cloudRange", "ao",
    "cutoutLeaves", "improvedTransparency", "weatherRadius", "chunkSectionFadeInTime",
    "mipmapLevels", "maxAnisotropyBit", "textureFiltering", "biomeBlendRadius", "particles",
    "entityShadows", "menuBackgroundBlurriness"]);

  function parseOptions(text) {
    const values = {};
    const lines = String(text || "").split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const colon = lines[i].indexOf(":");
      if (colon > 0) values[lines[i].slice(0, colon)] = lines[i].slice(colon + 1);
    }
    return values;
  }

  function presetValues(seed) {
    const parsed = parseOptions(seed);
    const values = {};
    for (let i = 0; i < AUTO_PRESET_OPTIONS.length; i++) {
      const key = AUTO_PRESET_OPTIONS[i];
      if (Object.prototype.hasOwnProperty.call(parsed, key)) values[key] = parsed[key];
    }
    return values;
  }

  // Pure decision: {current (options.txt text, null when it was just seeded), seed (the options
  // the tier seeds now), tier, marker (the stored record or null)} ->
  // {action, options (new text or null), marker (record to store or null), changed}.
  // Actions: seeded, untracked, manual, manual-detected, auto-kept, reapplied.
  function reconcilePreset(input) {
    const tier = String(input.tier || "mid");
    const values = presetValues(input.seed);
    const marker = input.marker && typeof input.marker === "object" && input.marker.values
        && typeof input.marker.values === "object" ? input.marker : null;
    if (input.current === null || input.current === undefined) {
      return {action: "seeded", options: null, marker: {tier: tier, values: values}, changed: []};
    }
    if (!marker) return {action: "untracked", options: null, marker: null, changed: []};
    if (marker.manual) return {action: "manual", options: null, marker: null, changed: []};
    const current = parseOptions(input.current);
    const changed = [];
    const keys = Object.keys(marker.values);
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      if (Object.prototype.hasOwnProperty.call(current, key)
          && current[key] !== String(marker.values[key])) {
        changed.push(key);
      }
    }
    if (changed.length) {
      return {action: "manual-detected", options: null, changed: changed,
        marker: {tier: marker.tier, values: marker.values, manual: true, changed: changed}};
    }
    let same = marker.tier === tier;
    const wanted = Object.keys(values);
    for (let i = 0; same && i < wanted.length; i++) {
      if (Object.prototype.hasOwnProperty.call(current, wanted[i])
          && current[wanted[i]] !== values[wanted[i]]) {
        same = false;
      }
    }
    if (same) return {action: "auto-kept", options: null, marker: null, changed: []};
    // Rewrite only the lines the game still writes; keep every other option and the order.
    const lines = String(input.current).split("\n");
    for (let i = 0; i < lines.length; i++) {
      const colon = lines[i].indexOf(":");
      if (colon <= 0) continue;
      const key = lines[i].slice(0, colon);
      if (Object.prototype.hasOwnProperty.call(values, key)) {
        const ending = lines[i].endsWith("\r") ? "\r" : "";
        lines[i] = key + ":" + values[key] + ending;
      }
    }
    return {action: "reapplied", options: lines.join("\n"), changed: [],
      marker: {tier: tier, values: values, from: marker.tier}};
  }

  function readPresetMarkers() {
    try {
      const raw = root.localStorage ? root.localStorage.getItem(AUTO_PRESET_KEY) : null;
      const parsed = raw ? JSON.parse(raw) : null;
      return parsed && typeof parsed === "object" ? parsed : {};
    } catch (error) {
      return {};
    }
  }

  // Called by BrowserFilePersistence while it mounts the storage profile `profile`; `current`
  // is null right after it seeded `seed`. Returns the new options text when the automatic preset
  // changes it, else "". Logs one "Gaius quality:" line and publishes window.__gaiusAutoPreset.
  function syncPreset(profile, current, seed, tier) {
    const markers = readPresetMarkers();
    const key = String(profile || "default");
    const result = reconcilePreset({current: current, seed: seed, tier: tier,
      marker: markers[key] || null});
    if (result.marker) {
      result.marker.at = Date.now();
      markers[key] = result.marker;
      try {
        if (root.localStorage) root.localStorage.setItem(AUTO_PRESET_KEY, JSON.stringify(markers));
      } catch (error) {
        // Blocked storage: the next start sees no record and leaves the options alone.
      }
    }
    const values = presetValues(seed);
    const distance = values.renderDistance ? ", render distance " + values.renderDistance : "";
    const messages = {
      "seeded": "first start, applied the automatic " + tier + " video preset" + distance,
      "reapplied": "video settings untouched since the automatic "
          + (result.marker && result.marker.from || "?") + " preset, applied the " + tier
          + " preset" + distance,
      "auto-kept": "video settings follow the automatic " + tier + " preset",
      "manual-detected": "the player changed video settings (" + result.changed.join(", ")
          + "); automatic presets are off for this profile",
      "manual": "keeping the player's video settings (automatic presets off)",
      "untracked": "keeping the saved video settings (saved before automatic presets)"
    };
    const automatic = result.action === "seeded" || result.action === "reapplied"
        || result.action === "auto-kept";
    try {
      root.__gaiusAutoPreset = {action: result.action, tier: tier, automatic: automatic,
        changed: result.changed.slice(0)};
      const console = root.console;
      const line = "Gaius quality: " + messages[result.action];
      if (console && typeof console.info === "function") console.info(line);
    } catch (error) {
      // Diagnostics only.
    }
    return result.options || "";
  }

  Q.autoPreset = Object.freeze({
    KEY: AUTO_PRESET_KEY,
    OPTIONS: AUTO_PRESET_OPTIONS,
    parseOptions: parseOptions,
    reconcile: reconcilePreset,
    sync: syncPreset
  });

  Q.profile = Object.freeze({
    SETTINGS_KEY: SETTINGS_KEY,
    SCALE_STEPS: SCALE_STEPS,
    TIER_DEFAULTS: TIER_DEFAULTS,
    settings: settings,
    resolve: resolve,
    invalidate: invalidate,
    oitAllowed: oitAllowed,
    resolvePixelRatio: resolvePixelRatio,
    quantizeScale: quantizeScale,
    setPostEnabled: function (enabled) {
      return writePersisted({post: !!enabled});
    },
    setSsr: function (enabled) {
      return writePersisted({ssr: !!enabled});
    },
    setRenderScale: function (value) {
      if (String(value).toLowerCase() === "auto") return writePersisted({renderScale: "auto"});
      return writePersisted({renderScale: quantizeScale(number(value, 1, SCALE_STEPS[0], 1))});
    },
    setSharpness: function (value) {
      return writePersisted({sharpness: number(value, 0.25, 0, 2)});
    },
    setTier: function (name) {
      if (Q.caps && typeof Q.caps.setOverride === "function") Q.caps.setOverride(name);
      invalidate();
      return settings();
    }
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
