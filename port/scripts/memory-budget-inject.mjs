import {readFileSync, writeFileSync} from "node:fs";
import assert from "node:assert/strict";

const profile = readFileSync(new URL("../web/runtime/quality/quality-profile.js", import.meta.url), "utf8");
const lowMemory = process.env.MEMORY_DEVICE_GIB ? Number(process.env.MEMORY_DEVICE_GIB) : null;
const baseline = process.env.MEMORY_BASELINE === "1";
assert.ok(lowMemory === null || (Number.isFinite(lowMemory) && lowMemory > 0));
const injection = `(() => {
  const device = ${JSON.stringify(lowMemory)};
  const baseline = ${baseline};
  if (device !== null) Object.defineProperty(navigator, "deviceMemory", {get: () => device});
  const experiment = window.__gaiusMemoryExperiment = {qualitySourceInjection: false,
    compiledAllocatorChanged: false, shadowBudgetApplied: false, deviceOverride: device};
  const memory = {initialBytes: null, currentBytes: 0, peakBytes: 0, growthCount: 0, growths: []};
  const timer = setInterval(() => {
    const bytes = window.__gaiusWasmMemory?.buffer.byteLength || 0;
    if (bytes && !memory.initialBytes) memory.initialBytes = bytes;
    if (bytes && bytes !== memory.currentBytes) {
      if (memory.currentBytes) {
        memory.growthCount++;
        if (memory.growths.length < 32) memory.growths.push({atMs: performance.now(),
          fromBytes: memory.currentBytes, toBytes: bytes});
      }
      memory.currentBytes = bytes;
      memory.peakBytes = Math.max(memory.peakBytes, bytes);
    }
    const telemetry = window.__gaiusMemoryTelemetry || (window.__gaiusMemoryTelemetry = {});
    telemetry.linearMemory = memory;
  }, 100);
  window.addEventListener("pagehide", () => clearInterval(timer), {once: true});
  if (baseline) return;
  window.__gaiusBeforeMain = () => {
    const boot = window.__gaiusBoot;
    if (!boot?.beforeMain) throw new Error("boot gate missing");
    const beforeMain = boot.beforeMain;
    boot.beforeMain = async (...args) => {
      await beforeMain.apply(boot, args);
      const quality = window.GaiusQuality;
      if (!quality) throw new Error("quality runtime missing");
      delete quality.profile;
      (new Function(${JSON.stringify(profile)}))();
      const chosen = quality.profile.resolve();
      if (chosen.memoryTier.lowMemory) {
        const caps = quality.caps;
        quality.caps = Object.freeze({...caps,
          ensureTier: () => ({...caps.ensureTier(), tier: "low"})});
      }
      experiment.qualitySourceInjection = true;
    };
  };
  const installShadowBudget = setInterval(() => {
    const state = window.__gaiusGL;
    if (!state?.maxTotalBufferShadowBytes || !window.__gaiusWasmMemory) return;
    clearInterval(installShadowBudget);
    const params = new URLSearchParams(location.search);
    const configured = params.get("gaiusShadowBudgetMB");
    if (configured === "off") return;
    const fallback = window.__gaiusMemoryTier?.lowMemory ? 16 : 32;
    const value = configured === null ? fallback : Number(configured);
    const budget = (Number.isFinite(value) ? Math.max(0, Math.min(64, value)) : fallback) * 1048576;
    const original = state.maxTotalBufferShadowBytes;
    state.maxTotalBufferShadowBytes = function() {return Math.min(original.call(this), budget);};
    const stats = window.__gaiusGLStats || (window.__gaiusGLStats = {});
    stats.memoryShadowBudgetBytes = budget;
    experiment.shadowBudgetApplied = true;
    if (state.trimBufferShadows) state.trimBufferShadows();
  }, 100);
})();`;
assert.ok(process.argv[2], "usage: node memory-budget-inject.mjs output.js");
writeFileSync(process.argv[2], injection);
