#!/usr/bin/env node
// Run the staged release through the singleplayer browser acceptance flow.
// node port/scripts/release-acceptance.mjs --staged <train/staged> --out <external-output-dir>
// Optional: --profiles 26.3,26.2 --cases site-wasm-webgpu,portable-file --jobs 2

import {spawn} from "node:child_process";
import {createWriteStream, existsSync} from "node:fs";
import {mkdir, readFile, readdir, writeFile} from "node:fs/promises";
import {basename, dirname, isAbsolute, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {glFrameGuardFailures} from "../../tools/gl-frame-guard.mjs";
import {VERSION_PROFILES} from "./verification-profiles.mjs";
import {killProcessTree} from "./reload-memory-gate.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "../..");
const HARNESS = join(HERE, "minecraft-263-singleplayer-cdp.mjs");
// Optional wrapper that provisions an isolated browser slot (GAIUS_SLOT=<path to slot.sh>).
// When unset the harness runs directly and launches its default Chrome without slot isolation.
export function harnessCommand(slot = process.env.GAIUS_SLOT, tag = "accept") {
  return slot
    ? {command: "bash", args: [slot, "chrome", tag, "--", "node", HARNESS], slotted: true}
    : {command: process.execPath, args: [HARNESS], slotted: false};
}
export const PROFILE_ORDER = Object.freeze(VERSION_PROFILES.map(profile => profile.id)
  .sort((left, right) => right.localeCompare(left, "en", {numeric: true})));
export const CASE_ORDER = ["site-wasm-auto", "site-wasm-webgl2", "site-wasm-webgpu",
  "acceptance-default", "reload-soak", "portable-file", "quit-game-site",
  "quit-game-portable", "fallback-webgpu-unavailable", "unsupported-browser"];
export const REQUIRED_CHECKS = ["bootsToTitle", "seedTyped", "creativeSelected", "worldEntered",
  "runtimeSelection", "serverRuntimeMatches", "gpuBackendMatches", "terrainRendered", "topDownTerrain", "movesWithW", "breaksBlock",
  "placesBlock", "inventoryStreamingTerrain", "pauseMenu", "saveAndQuit", "worldListed", "chunksReload",
  "playerPositionPersisted", "placedBlockPersisted", "terrainBaseline", "noShaderErrors",
  "noWebGLErrors", "gpuBackend", "noWebGpuErrors", "noUncaughtExceptions", "noSyncGlCalls"];
const WEBGPU_ASSETS = ["gaius-shader-toolchain.js", "gaius-shaderc.js", "gaius-shaderc.wasm",
  "gaius-spvc.js", "gaius-spvc.wasm"];
export const RELEASE_SETTINGS = Object.freeze({terrainVisible: true, settleSeconds: 60,
  bootTimeoutMs: 900000, worldTimeoutMs: 420000, cdpTimeoutMs: 20000,
  slotTimeoutSeconds: 2400, reloadMemoryBudgetBytes: 0.75 * 1024 ** 3});
const HARNESS_ENV = ["ACCEPT_EXTENDED", "ACCEPT_INJECT", "ARTIFACT", "BYPASS_SW", "GAIUS_GPU_EXPECT",
  "ACCEPT_TERRAIN_VISIBLE", "BOOT_TIMEOUT_MS", "CAPTURE_CAMERAS", "CAPTURE_MODE",
  "CAPTURE_STAGE_JS", "CDP_TIMEOUT_MS", "CLEAN_ONLY", "CLEAN_THROUGHPUT",
  "CLEAN_WINDOW_S", "DIST", "GAIUS_CHROME_BIN", "GAIUS_RUNTIME", "MAIN_THREAD_PROFILE", "OUT", "PAGE_URL",
  "PERF_CAPTURE", "PERF_DURATION_MS", "PLAYER_NAME", "PROFILE", "PROFILE_60HZ",
  "PROFILE_60HZ_S", "QUERY", "REENTER", "REENTER_PAGE_URL", "RUNTIME", "SEED", "SETTLE_S",
  "SLOT_TIMEOUT_S", "WORKER_EXC_DEBUG", "WORLD_TIMEOUT_MS", "WORLDGEN_BENCH",
  "WORLDGEN_ENTRY_PROFILE", "WORLDGEN_KERNEL_OFF", "RELOAD_SOAK", "RELOAD_MEMORY_BUDGET_BYTES", "RELOAD_MEMORY_BUDGET_GIB",
    "ACCEPT_PAUSE_ON_LOST_FOCUS", "QUIT_GAME_ACCEPTANCE",
  "STATIC_HOST_HEADERS", "UNSUPPORTED_BROWSER_ACCEPTANCE", "UNSUPPORTED_FORM", "UNSUPPORTED_INJECT",
  "UNSUPPORTED_LOCALE", "CHROME_EXTRA_ARGS_JSON"];

function inside(parent, path) {
  const rel = relative(parent, path);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

export function parseArguments(argv) {
  const options = {jobs: 2, seed: "20261014"};
  for (let index = 0; index < argv.length; index++) {
    const key = argv[index];
    if (!["--staged", "--out", "--profiles", "--cases", "--jobs", "--seed"].includes(key)) {
      throw new Error(`unknown argument: ${key}`);
    }
    const value = argv[++index];
    if (!value || value.startsWith("--")) throw new Error(`missing value for ${key}`);
    options[key.slice(2)] = value;
  }
  if (!options.staged || !options.out) throw new Error("usage: node port/scripts/release-acceptance.mjs --staged <dir> --out <dir> [--profiles ids] [--cases ids] [--jobs 1|2] [--seed integer]");
  options.staged = resolve(options.staged);
  options.out = resolve(options.out);
  options.jobs = Number(options.jobs);
  if (![1, 2].includes(options.jobs)) throw new Error("--jobs must be 1 or 2");
  if (!/^-?\d{1,19}$/.test(options.seed)) throw new Error("--seed must be a signed decimal integer");
  options.profiles = options.profiles ? options.profiles.split(",").map(x => x.trim()) : null;
  options.cases = options.cases ? options.cases.split(",").map(x => x.trim()) : null;
  for (const id of options.profiles || []) if (!PROFILE_ORDER.includes(id)) throw new Error(`unknown profile: ${id}`);
  for (const id of options.cases || []) if (!CASE_ORDER.includes(id)) throw new Error(`unknown case: ${id}`);
  if (options.profiles && new Set(options.profiles).size !== options.profiles.length) throw new Error("duplicate profile");
  if (options.cases && new Set(options.cases).size !== options.cases.length) throw new Error("duplicate case");
  if (inside(REPO, options.out)) throw new Error("--out must be outside the repository");
  if (inside(options.staged, options.out) || inside(options.out, options.staged)) {
    throw new Error("--out and --staged must be separate directories");
  }
  return options;
}

export function planCases({staged, profiles, onlyCases, manifests}) {
  const plan = [];
  for (const profile of profiles) {
    const site = join(staged, `site-${profile}`);
    const acceptance = join(staged, `acceptance-${profile}`);
    const portable = join(staged, `portable-${profile}`, `Gaius-${profile}.html`);
    const manifest = manifests[profile] || null;
    const configs = {
      "site-wasm-auto": {form: "site", dist: site, runtime: "wasm", gpu: "auto"},
      "site-wasm-webgl2": {form: "site", dist: site, runtime: "wasm", gpu: "webgl2", query: "runtime=wasm&gpu=webgl2&gaiusInventoryWorldFps=1"},
      "site-wasm-webgpu": {form: "site", dist: site, runtime: "wasm", gpu: "webgpu", query: "runtime=wasm&gpu=webgpu&gpuAllowFallback=1"},
      "acceptance-default": {form: "acceptance", dist: acceptance, runtime: "wasm", gpu: "auto"},
      "reload-soak": {form: "site", dist: site, runtime: "wasm", gpu: "auto", reloadSoak: true},
      "portable-file": {form: "portable", artifact: portable, runtime: "wasm", gpu: "auto", reenter: "reload"},
      "quit-game-site": {form: "site", dist: site, runtime: "wasm", gpu: "auto", quitGame: true},
      "quit-game-portable": {form: "portable", artifact: portable, runtime: "wasm", gpu: "auto",
        reenter: "reload", quitGame: true},
      "fallback-webgpu-unavailable": {form: "site", dist: site, runtime: "wasm", gpu: "webgl2", query: "runtime=wasm&gpu=webgpu&gpuAllowFallback=1", inject: "no-webgpu", fallback: "gpu"},
      "unsupported-browser": {form: "aggregate", runtime: "unsupported", gpu: "none",
        unsupportedVariants: [
          {form: "site", dist: site, inject: "no-wasmgc", locale: "en_us"},
          {form: "site", dist: site, inject: "no-jspi", locale: "es_es"},
          {form: "portable", artifact: portable, inject: "no-wasmgc", locale: "zh_cn"},
          {form: "portable", artifact: portable, inject: "no-jspi", locale: "en_us"},
        ]},
    };
    for (const id of CASE_ORDER) {
      if (onlyCases && !onlyCases.includes(id)) continue;
      const config = configs[id];
      if (!config) continue;
      const preflight = [];
      if (config.form === "site" && !manifest) preflight.push("missing or unreadable site manifest");
      if (config.dist && !existsSync(join(config.dist, "index.html"))) preflight.push(`missing ${config.form} index.html`);
      if (config.artifact && !existsSync(config.artifact)) preflight.push("missing portable HTML");
      if (id === "unsupported-browser") {
        if (manifest?.runtimePolicy !== "wasm-only") preflight.push("site does not declare WasmGC-only runtime policy");
        if (manifest?.assets?.["classes.js"] || manifest?.assets?.["singleplayer-server.js"]) {
          preflight.push("site still maps a JavaScript client or server asset");
        }
        for (const variant of config.unsupportedVariants) {
          const target = variant.dist || variant.artifact;
          if (!target || !existsSync(target)) preflight.push(`missing unsupported-browser ${variant.form} target`);
          if (variant.dist && !existsSync(join(variant.dist, "index.html"))) {
            preflight.push(`missing unsupported-browser site index.html: ${variant.dist}`);
          }
        }
      }
      if (id === "site-wasm-webgpu") {
        for (const asset of WEBGPU_ASSETS) {
          const name = manifest?.assets?.[asset];
          if (!name || !existsSync(join(site, name))) preflight.push(`missing WebGPU shader toolchain asset: ${asset}`);
        }
      }
      // The boot path defers the gameplay sound pack on sites.  Check the
      // manifest and the staged file before opening Chrome so a silent site
      // cannot pass the runtime interaction checks by accident.
      if (config.form === "site" || config.form === "acceptance") {
        const name = manifest?.assets?.["vanilla-sounds.pack"];
        if (!name || !existsSync(join(config.dist, name))) preflight.push("missing gameplay sound pack: vanilla-sounds.pack");
      }
      plan.push({profile, id, ...config, preflight});
    }
  }
  return plan;
}

// Build the child environment from the runner's staged case. Harness routing and release-only
// terrain policy are owned by this runner; ambient values must not redirect a case.
export function buildHarnessEnv(spec, options, base = process.env) {
  const env = {...base};
  for (const name of HARNESS_ENV) delete env[name];
  Object.assign(env, {OUT: join(options.out, "cases", spec.profile, spec.id), PROFILE: spec.profile,
    SEED: options.seed, ACCEPT_TERRAIN_VISIBLE: "1", ACCEPT_PAUSE_ON_LOST_FOCUS: "0",
    SLOT_TIMEOUT_S: String(RELEASE_SETTINGS.slotTimeoutSeconds), SETTLE_S: String(RELEASE_SETTINGS.settleSeconds),
    BOOT_TIMEOUT_MS: String(RELEASE_SETTINGS.bootTimeoutMs), WORLD_TIMEOUT_MS: String(RELEASE_SETTINGS.worldTimeoutMs)});
  if (spec.dist) env.DIST = spec.dist;
  if (spec.artifact) env.ARTIFACT = spec.artifact;
  if (spec.query) env.QUERY = spec.query;
  if (spec.reenter) env.REENTER = spec.reenter;
  if (spec.inject) env.ACCEPT_INJECT = spec.inject;
  if (spec.unsupportedVariant) {
    env.UNSUPPORTED_BROWSER_ACCEPTANCE = "1";
    env.UNSUPPORTED_FORM = spec.form;
    env.UNSUPPORTED_INJECT = spec.inject;
    env.UNSUPPORTED_LOCALE = spec.locale;
    env.CHROME_EXTRA_ARGS_JSON = JSON.stringify([`--lang=${spec.locale.replaceAll("_", "-")}`]);
  }
  if (spec.reloadSoak) {
    env.RELOAD_SOAK = "1";
    env.RELOAD_MEMORY_BUDGET_BYTES = String(RELEASE_SETTINGS.reloadMemoryBudgetBytes);
  }
  // A masked GPU capability selects WebGL2; missing WasmGC/JSPI is handled as unsupported.
  if (spec.fallback === "gpu") env.GAIUS_GPU_EXPECT = spec.gpu;
  if (spec.quitGame) env.QUIT_GAME_ACCEPTANCE = "1";
  return env;
}

export function assessCase(spec, report, exitCode) {
  const reasons = [...spec.preflight];
  if (exitCode !== 0) reasons.push(`browser command exited ${exitCode ?? "without a status"}`);
  if (!report || typeof report !== "object") return [...reasons, "missing or unreadable browser report"];
  if (report.verdict !== "PASS") reasons.push(`browser verdict: ${report.verdict || "missing"}`);
  if (report.profile !== spec.profile) reasons.push(`profile mismatch: ${report.profile || "missing"}`);
  if (report.error) reasons.push(`harness: ${String(report.error).split("\n")[0]}`);
  if (spec.unsupportedVariant) {
    const unsupported = report.unsupportedBrowser;
    if (!unsupported || Array.isArray(unsupported.variants)) return [...reasons, "missing unsupported-browser evidence"];
    const variants = [spec];
    for (const variant of variants) {
      const evidence = unsupported;
      const label = `${variant.form}/${variant.inject}`;
      if (evidence.locale !== variant.locale) reasons.push(`${label}: locale ${evidence.locale || "missing"}; expected ${variant.locale}`);
      if (evidence.visible !== true || !String(evidence.message || "").trim()) reasons.push(`${label}: unsupported message page is not visible`);
      const localizedMessages = [
        "Gaius needs an up-to-date Chrome with WebAssembly GC and JavaScript Promise Integration (JSPI).",
        "Gaius 需要支持 WebAssembly GC 和 JavaScript Promise Integration（JSPI）的最新 Chrome。",
        "Gaius necesita una versión actualizada de Chrome con WebAssembly GC y JavaScript Promise Integration (JSPI).",
      ];
      if (!localizedMessages.includes(evidence.message)) reasons.push(`${label}: unsupported message is not one of the localized capability messages`);
      if (evidence.retryVisible !== true) reasons.push(`${label}: Retry button is not visible`);
      if (evidence.injection?.mode !== variant.inject || evidence.injection?.applied !== true) reasons.push(`${label}: capability injection did not apply`);
      const selected = evidence.runtimeSelection?.selected;
      if (selected !== "unsupported") reasons.push(`${label}: runtime selected ${selected || "missing"}; expected unsupported`);
      const feature = variant.inject === "no-wasmgc" ? "gc" : "jspi";
      if (evidence.runtimeSelection?.features?.[feature] !== false) reasons.push(`${label}: missing ${feature} capability was not recorded`);
      if (evidence.gameState?.mainType !== "undefined" || evidence.gameState?.mainStart != null || evidence.gameState?.minecraftState !== false) {
        reasons.push(`${label}: game boot began despite unsupported browser`);
      }
      const urls = Array.isArray(evidence.resourceUrls) ? evidence.resourceUrls : [];
      const heavy = urls.filter(url => /(?:classes(?:\.[^/?]+)?\.js|classes(?:\.[^/?]+)?\.wasm|singleplayer-server(?:\.[^/?]+)?\.(?:js|wasm)|vanilla-(?:assets\.pack|sounds\.pack)|gaius-(?:shaderc|spvc|hotpath)|kernels\/)/i.test(url));
      if (heavy.length) reasons.push(`${label}: heavy game resources were requested: ${heavy.slice(0, 3).join(", ")}`);
      if (!Array.isArray(evidence.exceptions) || evidence.exceptions.length) reasons.push(`${label}: uncaught exceptions: ${JSON.stringify(evidence.exceptions || "missing")}`);
    }
    return [...new Set(reasons)];
  }
  if (spec.reloadSoak) {
    if (report.reloadSoak?.gate?.pass !== true) reasons.push(`reload memory gate: ${report.reloadSoak?.gate?.reason || "missing or unavailable"}`);
    return [...new Set(reasons)];
  }
  for (const name of REQUIRED_CHECKS) {
    if (report.checks?.[name]?.ok !== true) reasons.push(`check ${name}: ${JSON.stringify(report.checks?.[name]?.detail ?? "missing").slice(0, 220)}`);
  }
  if (spec.quitGame && report.checks?.quitGame?.ok !== true) {
    reasons.push(`check quitGame: ${JSON.stringify(report.checks?.quitGame?.detail ?? "missing").slice(0, 220)}`);
  }
  if (String(report.seedConfiguration?.effectiveSeed) !== String(report.seed)
      || report.seedConfiguration?.acceptance !== "verified") reasons.push("/seed did not verify the configured seed");
  const selection = report.selection || {};
  if (selection.runtime?.selected !== spec.runtime) {
    reasons.push(`runtime selected ${selection.runtime?.selected || "missing"}; expected ${spec.runtime}${selection.runtime?.reason ? ` (${selection.runtime.reason})` : ""}`);
  }
  if (report.runtimes?.world?.client && report.runtimes.world.client !== spec.runtime) {
    reasons.push(`world runtime ${report.runtimes.world.client}; expected ${spec.runtime}`);
  }
  if (!selection.gpu || !["webgl2", "webgpu"].includes(selection.backend)) {
    reasons.push(`actual graphics backend missing: ${selection.backend || "missing"}`);
  } else if (selection.gpu.selected !== selection.backend) {
    reasons.push(`graphics selection ${selection.gpu.selected} differs from rendered backend ${selection.backend}`);
  }
  if (spec.gpu !== "auto" && selection.backend !== spec.gpu) {
    reasons.push(`rendered backend ${selection.backend || "missing"}; expected ${spec.gpu}${selection.gpu?.reason ? ` (${selection.gpu.reason})` : ""}`);
  }
  if (spec.gpu === "webgpu") {
    if (selection.webgpu?.failure || selection.webgpu?.lost || selection.webgpu?.active !== true) {
      reasons.push(`WebGPU state unhealthy: ${JSON.stringify(selection.webgpu || null)}`);
    }
    for (const name of ["errors", "translateErrors", "pipelineFailures", "skippedDraws"]) {
      const count = selection.webgpu?.stats?.[name];
      if (!Number.isFinite(count) || count !== 0) {
        const messages = JSON.stringify(selection.webgpu?.messages || []).slice(0, 300);
        reasons.push(`WebGPU ${name}: ${count ?? "missing"}; messages: ${messages}`);
      }
    }
  }
  for (const phase of ["beforeQuit", "afterReenter", "end"]) {
    const stages = (report.glStages || []).filter(stage => stage.phase === phase);
    if (stages.length !== 1) {
      reasons.push(`graphics stage ${phase}: expected once, observed ${stages.length}`);
      continue;
    }
    const stage = stages[0], backend = stage.selection?.backend;
    const expected = spec.gpu === "auto" ? selection.backend : spec.gpu;
    if (!expected || backend !== expected || stage.selection?.gpu?.selected !== backend) {
      reasons.push(`graphics stage ${phase}: rendered ${backend || "missing"}, selected ${stage.selection?.gpu?.selected || "missing"}, expected ${expected || "missing"}`);
    }
  }
  if (spec.inject) {
    if (selection.injection?.mode !== spec.inject || selection.injection?.applied !== true) {
      reasons.push(`capability injection did not apply: ${JSON.stringify(selection.injection || null)}`);
    }
  }
  if (spec.fallback === "gpu" && (selection.gpu?.fallback !== true || selection.gpu?.requested !== "webgpu"
      || !/navigator\.gpu|adapter/i.test(selection.gpu?.reason || ""))) {
    reasons.push(`WebGPU fallback not proven: ${JSON.stringify(selection.gpu || null)}`);
  }
  for (const failure of glFrameGuardFailures(report.glFrames, "world")) reasons.push(failure);
  if ((report.shaderConsole || []).length) reasons.push(`${report.shaderConsole.length} shader console error(s)`);
  if ((report.glConsole || []).length) reasons.push(`${report.glConsole.length} WebGL console error(s)`);
  if ((report.exceptions || []).length) reasons.push(`${report.exceptions.length} uncaught exception(s)`);
  return [...new Set(reasons)];
}

function escapeCell(value) {
  return String(value ?? "").replaceAll("|", "\\|").replace(/\s+/g, " ").trim();
}

export function renderMarkdown(matrix) {
  const lines = ["# v0.4.0 发布验收矩阵", "", `- staged: \`${matrix.staged}\``,
    `- seed: \`${matrix.seed}\``,
    `- 配置: jobs=${matrix.settings?.jobs ?? "未记录"}, ACCEPT_TERRAIN_VISIBLE=1, SETTLE_S=${matrix.settings?.settleSeconds ?? "未记录"}, BOOT_TIMEOUT_MS=${matrix.settings?.bootTimeoutMs ?? "未记录"}, WORLD_TIMEOUT_MS=${matrix.settings?.worldTimeoutMs ?? "未记录"}, CDP_TIMEOUT_MS=${matrix.settings?.cdpTimeoutMs ?? "未记录"}（默认）, SLOT_TIMEOUT_S=${matrix.settings?.slotTimeoutSeconds ?? "未记录"}`,
    `- 开始: ${matrix.startedAt}`, `- 结束: ${matrix.finishedAt || "运行中"}`,
    `- 结果: **${matrix.verdict}**（${matrix.rows.filter(x => x.verdict === "PASS").length} PASS / ${matrix.rows.filter(x => x.verdict === "FAIL").length} FAIL）`,
    "", "| Profile | 场景 | 结果 | 用时 | 原因 | 证据 |", "| --- | --- | --- | ---: | --- | --- |"];
  for (const row of matrix.rows) {
    const links = Object.entries(row.evidence || {}).flatMap(([name, value]) => {
      if (typeof value === "string") return [`[${name}](${value.replaceAll("\\", "/")})`];
      if (!value || typeof value !== "object") return [];
      return Object.entries(value).filter(([, path]) => typeof path === "string")
        .map(([child, path]) => `[${name}/${child}](${path.replaceAll("\\", "/")})`);
    }).join(" · ");
    lines.push(`| ${row.profile} | ${row.id} | **${row.verdict}** | ${row.seconds ?? "—"} s | ${escapeCell(row.reasons?.join("; ") || "—")} | ${links || "—"} |`);
  }
  if (matrix.pending?.length) lines.push("", `未运行: ${matrix.pending.join(", ")}`);
  return lines.join("\n") + "\n";
}

async function loadStaged(staged, selectedProfiles) {
  let stage;
  try { stage = JSON.parse(await readFile(join(staged, "STAGED.txt"), "utf8")); }
  catch (error) { throw new Error(`cannot read STAGED.txt: ${error.message}`); }
  if (!Array.isArray(stage.profiles) || !stage.profiles.length) throw new Error("STAGED.txt has no profiles");
  if (stage.pending?.length) throw new Error(`stage has pending artifacts: ${JSON.stringify(stage.pending)}`);
  const available = stage.profiles.map(entry => entry.profile);
  const profiles = selectedProfiles || PROFILE_ORDER.filter(profile => available.includes(profile));
  for (const profile of profiles) if (!available.includes(profile)) throw new Error(`profile ${profile} is absent from staged train`);
  const manifests = {};
  for (const profile of profiles) {
    const path = join(staged, `site-${profile}`, "gaius-site.json");
    try { manifests[profile] = JSON.parse(await readFile(path, "utf8")); }
    catch { manifests[profile] = null; }
    if (manifests[profile]?.profile && manifests[profile].profile !== profile) {
      throw new Error(`manifest profile mismatch: ${path}`);
    }
  }
  return {profiles, manifests};
}

async function runBrowserCase(spec, options) {
  if (spec.id === "unsupported-browser") {
    const variants = [];
    for (const variant of spec.unsupportedVariants || []) {
      const variantSpec = {...spec, ...variant,
        id: `unsupported-browser-${variant.form}-${variant.inject}`,
        unsupportedVariant: true, preflight: []};
      variants.push(await runBrowserCase(variantSpec, options));
    }
    const reasons = variants.flatMap(row => row.reasons || []);
    const evidence = Object.fromEntries(variants.map(row => [row.id, row.evidence || {}]));
    const row = {profile: spec.profile, id: spec.id, form: spec.form,
      verdict: reasons.length ? "FAIL" : "PASS", seconds: variants.reduce((sum, item) => sum + (item.seconds || 0), 0),
      exitCode: variants.every(item => item.exitCode === 0) ? 0 : 1,
      reasons: [...new Set(reasons)], evidence, observed: {variants: variants.map(item => item.id)}};
    console.log(`${row.verdict} ${spec.profile} ${spec.id} ${row.seconds}s${row.reasons.length ? ` — ${row.reasons[0]}` : ""}`);
    return row;
  }
  const directory = join(options.out, "cases", spec.profile, spec.id);
  await mkdir(directory, {recursive: true});
  if (spec.preflight.length) {
    const preflight = join(directory, "preflight.json");
    await writeFile(preflight, JSON.stringify({profile: spec.profile, case: spec.id, reasons: spec.preflight}, null, 2) + "\n");
    const row = {profile: spec.profile, id: spec.id, form: spec.form, verdict: "FAIL", seconds: 0,
      exitCode: null, reasons: spec.preflight, evidence: {preflight: relative(options.out, preflight)}, observed: null};
    console.log(`FAIL ${spec.profile} ${spec.id} preflight — ${row.reasons[0]}`);
    return row;
  }
  const logPath = join(directory, "slot.log");
  const stream = createWriteStream(logPath, {flags: "w"});
  await new Promise((done, reject) => { stream.once("open", done); stream.once("error", reject); });
  const env = buildHarnessEnv(spec, options);
  const tagPrefix = spec.quitGame || spec.reloadSoak ? "quitgame" : "accept";
  const tag = `${tagPrefix}-${basename(options.out).replace(/[^A-Za-z0-9-]/g, "").slice(0, 16)}-${spec.profile}-${spec.id}`;
  const started = Date.now();
  console.log(`START ${spec.profile} ${spec.id}`);
  let code = null, commandError = null;
  try {
    code = await new Promise((done, reject) => {
      const launch = harnessCommand(process.env.GAIUS_SLOT, tag);
      const child = spawn(launch.command, launch.args,
        {cwd: REPO, env, stdio: ["ignore", stream, stream]});
      // slot.sh enforces SLOT_TIMEOUT_S itself; a direct run needs its own limit.
      const limit = launch.slotted ? null : setTimeout(() => { void killProcessTree(child.pid); },
        RELEASE_SETTINGS.slotTimeoutSeconds * 1000);
      child.once("error", error => { clearTimeout(limit); reject(error); });
      child.once("close", status => { clearTimeout(limit); done(status); });
    });
  } catch (error) { commandError = String(error); }
  await new Promise(done => stream.end(done));
  let report = null;
  try { report = JSON.parse(await readFile(join(directory, "report.json"), "utf8")); }
  catch {}
  const reasons = assessCase(spec, report, code);
  if (commandError) reasons.push(`browser command: ${commandError}`);
  const evidence = {slot: relative(options.out, logPath)};
  for (const [name, file] of [["report", "report.json"], ["console", "console.log"],
    ["title", "11-title.png"], ["terrain", "21-spawn.png"], ["reentered", "40-reentered.png"],
    ["reload", "42-topdown-after-reload.png"], ["stopped", "31-title-after-quit-game.png"]]) {
    const path = join(directory, file);
    if (existsSync(path)) evidence[name] = relative(options.out, path);
  }
  const row = {profile: spec.profile, id: spec.id, form: spec.form, verdict: reasons.length ? "FAIL" : "PASS",
    seconds: Math.round((Date.now() - started) / 1000), exitCode: code, reasons, evidence,
    observed: report ? {runtime: report.selection?.runtime?.selected || null,
      backend: report.selection?.backend || null, gpuReason: report.selection?.gpu?.reason || null,
      seed: report.seedConfiguration?.effectiveSeed || null} : null};
  console.log(`${row.verdict} ${spec.profile} ${spec.id} ${row.seconds}s${reasons.length ? ` — ${reasons[0]}` : ""}`);
  return row;
}

async function persist(matrix, out) {
  const file = join(out, "matrix.json");
  const temp = file + ".tmp";
  await writeFile(temp, JSON.stringify(matrix, null, 2) + "\n");
  const {rename} = await import("node:fs/promises");
  await rename(temp, file);
  await writeFile(join(out, "REPORT.md"), renderMarkdown(matrix));
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const {profiles, manifests} = await loadStaged(options.staged, options.profiles);
  const plan = planCases({...options, profiles, onlyCases: options.cases, manifests});
  if (!plan.length) throw new Error("selection contains no applicable cases");
  await mkdir(options.out, {recursive: true});
  const existing = await readdir(options.out);
  if (existing.length) throw new Error("--out must be empty so reports cannot mix different runs");
  const matrix = {schema: 1, staged: options.staged, profiles, seed: options.seed,
    settings: {jobs: options.jobs, ...RELEASE_SETTINGS},
    startedAt: new Date().toISOString(), finishedAt: null, verdict: "RUNNING", rows: [],
    pending: plan.map(spec => `${spec.profile}/${spec.id}`)};
  await persist(matrix, options.out);
  let next = 0, writeChain = Promise.resolve();
  const worker = async () => {
    while (next < plan.length) {
      const spec = plan[next++];
      let row;
      try { row = await runBrowserCase(spec, options); }
      catch (error) { row = {profile: spec.profile, id: spec.id, form: spec.form, verdict: "FAIL",
        reasons: [`runner: ${String(error.stack || error)}`], evidence: {}}; }
      matrix.rows.push(row);
      matrix.pending = plan.filter(item => !matrix.rows.some(done => done.profile === item.profile && done.id === item.id))
        .map(item => `${item.profile}/${item.id}`);
      writeChain = writeChain.then(() => persist(matrix, options.out));
      await writeChain;
    }
  };
  await Promise.all(Array.from({length: Math.min(options.jobs, plan.length)}, worker));
  matrix.rows.sort((a, b) => profiles.indexOf(a.profile) - profiles.indexOf(b.profile)
    || CASE_ORDER.indexOf(a.id) - CASE_ORDER.indexOf(b.id));
  matrix.finishedAt = new Date().toISOString();
  matrix.verdict = matrix.rows.length === plan.length && matrix.rows.every(row => row.verdict === "PASS") ? "PASS" : "FAIL";
  await persist(matrix, options.out);
  console.log(`${matrix.verdict}: ${matrix.rows.filter(row => row.verdict === "PASS").length}/${plan.length} cases; ${join(options.out, "REPORT.md")}`);
  if (matrix.verdict !== "PASS") process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(String(error.stack || error)); process.exitCode = 2; });
}
