import assert from "node:assert/strict";
import {test} from "node:test";
import {mkdtemp, mkdir, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {PROFILE_ORDER} from "./release-acceptance.mjs";
import {CASE_ORDER, RELEASE_SETTINGS, REQUIRED_CHECKS, assessCase, buildHarnessEnv, harnessCommand, parseArguments, planCases, renderMarkdown, servePortable} from "./release-acceptance.mjs";

const [MODERN_PROFILE, PREVIOUS_PROFILE, LEGACY_PROFILE] = PROFILE_ORDER;

function completeReport(profile = MODERN_PROFILE, backend = "webgpu") {
  return {profile, seed: "20261014", verdict: "PASS",
    checks: Object.fromEntries(REQUIRED_CHECKS.map(name => [name, {ok: true}])),
    seedConfiguration: {effectiveSeed: "20261014", acceptance: "verified"},
    runtimes: {world: {client: "wasm"}},
    selection: {runtime: {selected: "wasm", fallback: false, reason: "site-auto"},
      gpu: {requested: backend, selected: backend, fallback: false, reason: "site-auto"},
      backend, webgpu: {active: true, failure: "", lost: "", stats: {
        errors: 0, translateErrors: 0, pipelineFailures: 0, skippedDraws: 0}, messages: []}},
    glFrames: {installed: true, gameFrames: 120, syncCalls: 0, ms: 2000},
    glStages: ["beforeQuit", "afterReenter", "end"].map(phase => ({phase,
      selection: {backend, gpu: {selected: backend}},
      guard: {installed: true, gameFrames: 120, syncCalls: 0, ms: 2000}}))};
}

test("argument parsing requires an external output and known case IDs", () => {
  const out = join(tmpdir(), "release-acceptance-test-output");
  const staged = join(tmpdir(), "release-acceptance-test-stage");
  const options = parseArguments(["--staged", staged, "--out", out, "--jobs", "1",
    "--profiles", MODERN_PROFILE, "--cases", "site-wasm-webgpu"]);
  assert.equal(options.jobs, 1);
  assert.deepEqual(options.profiles, [MODERN_PROFILE]);
  assert.deepEqual(options.cases, ["site-wasm-webgpu"]);
  assert.throws(() => parseArguments(["--staged", staged, "--out", out, "--cases", "missing"]), /unknown case/);
  assert.throws(() => parseArguments(["--staged", staged, "--out", new URL("../artifacts", import.meta.url).pathname]), /outside the repository/);
});

test("runner pins visible terrain policy and isolates ambient harness routing", () => {
  const options = {out: "D:/acceptance-out", seed: "20261014"};
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-webgl2", dist: `D:/staged/site-${MODERN_PROFILE}`,
    runtime: "wasm", gpu: "webgl2", query: "runtime=wasm&gpu=webgl2", preflight: []};
  const env = buildHarnessEnv(spec, options, {
    PAGE_URL: "file:///wrong-target.html", DIST: "D:/wrong-dist", RUNTIME: "js",
    GAIUS_RUNTIME: "js", QUERY: "runtime=js", ACCEPT_TERRAIN_VISIBLE: "0",
    GAIUS_GPU_EXPECT: "webgpu",
    OUT: "D:/wrong-out", PROFILE: "wrong", SEED: "wrong", KEEP_FOR_TEST: "yes",
    SETTLE_S: "12", BOOT_TIMEOUT_MS: "1", WORLD_TIMEOUT_MS: "2",
    SLOT_TIMEOUT_S: "3", CDP_TIMEOUT_MS: "4", GAIUS_CHROME_BIN: "/usr/bin/google-chrome",
    GAIUS_CHROME_GPU_FLAGS_JSON: "[]"});
  assert.equal(env.ACCEPT_TERRAIN_VISIBLE, "1");
  assert.equal(env.ACCEPT_PAUSE_ON_LOST_FOCUS, "0");
  assert.equal(env.DIST, spec.dist);
  assert.equal(env.QUERY, spec.query);
  assert.equal(env.OUT, join(options.out, "cases", spec.profile, spec.id));
  assert.equal(env.PROFILE, MODERN_PROFILE);
  assert.equal(env.SEED, "20261014");
  for (const name of ["PAGE_URL", "RUNTIME", "GAIUS_RUNTIME", "GAIUS_GPU_EXPECT", "ACCEPT_INJECT", "ARTIFACT", "REENTER"]) {
    assert.equal(env[name], undefined, `${name} must not leak into a release case`);
  }
  assert.equal(env.KEEP_FOR_TEST, "yes");
  assert.equal(env.SETTLE_S, "60");
  assert.equal(env.BOOT_TIMEOUT_MS, "900000");
  assert.equal(env.WORLD_TIMEOUT_MS, "420000");
  assert.equal(env.SLOT_TIMEOUT_S, "2400");
  assert.equal(env.CDP_TIMEOUT_MS, "4");
  assert.equal(env.GAIUS_CHROME_BIN, "/usr/bin/google-chrome");
  assert.equal(env.GAIUS_CHROME_GPU_FLAGS_JSON, "[]");
});

test("GAIUS_EXTRA_QUERY appends diagnostic page terms to the case query and nothing else", () => {
  const options = {out: "D:/acceptance-out", seed: "20261014"};
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-webgl2", dist: `D:/staged/site-${MODERN_PROFILE}`,
    runtime: "wasm", gpu: "webgl2", query: "runtime=wasm&gpu=webgl2", preflight: []};
  assert.equal(buildHarnessEnv(spec, options, {GAIUS_EXTRA_QUERY: "gaiusServerTickTelemetry=1"}).QUERY,
    "runtime=wasm&gpu=webgl2&gaiusServerTickTelemetry=1");
  assert.equal(buildHarnessEnv(spec, options, {}).QUERY, spec.query);
  const bare = {...spec, query: undefined};
  assert.equal(buildHarnessEnv(bare, options, {GAIUS_EXTRA_QUERY: "a=1"}).QUERY, "runtime=wasm&a=1");
});

test("GPU fallback case expects rendered WebGL2 while retaining its WebGPU request", () => {
  const options = {out: "D:/acceptance-out", seed: "20261014"};
  const spec = {profile: PREVIOUS_PROFILE, id: "fallback-webgpu-unavailable", dist: `D:/staged/site-${PREVIOUS_PROFILE}`,
    runtime: "wasm", gpu: "webgl2", query: "runtime=wasm&gpu=webgpu&gpuAllowFallback=1",
    inject: "no-webgpu", fallback: "gpu", preflight: []};
  const env = buildHarnessEnv(spec, options, {GAIUS_GPU_EXPECT: "webgpu"});
  assert.equal(env.GAIUS_GPU_EXPECT, "webgl2");
  assert.equal(env.QUERY, spec.query);
  assert.equal(env.ACCEPT_INJECT, "no-webgpu");
});

test("matrix plan requires WasmGC-only sites and four unsupported-browser probes", async () => {
  const staged = await mkdtemp(join(tmpdir(), "release-acceptance-plan-"));
  try {
    const manifests = {[PREVIOUS_PROFILE]: {runtimePolicy: "wasm-only", assets: {}},
      [LEGACY_PROFILE]: {runtimePolicy: "wasm-only", assets: {}}};
    for (const profile of Object.keys(manifests)) {
      for (const form of ["site", "acceptance"]) {
        const directory = join(staged, `${form}-${profile}`);
        await mkdir(directory, {recursive: true});
        await writeFile(join(directory, "index.html"), "");
      }
      const portable = join(staged, `portable-${profile}`);
      await mkdir(portable, {recursive: true});
      await writeFile(join(portable, `Gaius-${profile}.html`), "");
    }
    const plan = planCases({staged, profiles: [PREVIOUS_PROFILE, LEGACY_PROFILE], manifests});
    assert.deepEqual(plan.filter(x => x.profile === PREVIOUS_PROFILE).map(x => x.id), CASE_ORDER);
    assert.deepEqual(plan.filter(x => x.profile === LEGACY_PROFILE).map(x => x.id), CASE_ORDER);
    assert.match(plan.find(x => x.profile === PREVIOUS_PROFILE && x.id === "site-wasm-webgpu").preflight.join(" "),
      /missing WebGPU shader toolchain asset: gaius-shader-toolchain.js/);
    assert.match(plan.find(x => x.profile === PREVIOUS_PROFILE && x.id === "site-wasm-auto").preflight.join(" "),
      /missing gameplay sound pack: vanilla-sounds.pack/);
    const unsupported = plan.find(x => x.profile === PREVIOUS_PROFILE && x.id === "unsupported-browser");
    assert.equal(unsupported.unsupportedVariants.length, 4);
    assert.deepEqual(unsupported.unsupportedVariants.map(x => `${x.form}/${x.inject}`), [
      "site/no-wasmgc", "site/no-jspi", "portable/no-wasmgc", "portable/no-jspi",
    ]);
    assert.deepEqual(unsupported.preflight, []);
    assert.equal(plan.length, CASE_ORDER.length * 2);
    const badManifest = {[PREVIOUS_PROFILE]: {runtimePolicy: "auto", assets: {"classes.js": "classes.js"}}};
    const badPlan = planCases({staged, profiles: [PREVIOUS_PROFILE], manifests: badManifest});
    assert.match(badPlan.find(x => x.id === "unsupported-browser").preflight.join(" "), /WasmGC-only/);
  } finally { await rm(staged, {recursive: true, force: true}); }
});

test("WebGPU telemetry must exist and show no shader or pipeline errors", () => {
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-webgpu", runtime: "wasm", gpu: "webgpu", preflight: []};
  const report = completeReport();
  delete report.selection.webgpu.stats;
  assert.match(assessCase(spec, report, 0).join("\n"), /WebGPU errors: missing/);
  report.selection.webgpu.stats = {errors: 0, translateErrors: 1, pipelineFailures: 2, skippedDraws: 3};
  report.selection.webgpu.messages = ["shader translation failed"];
  const reasons = assessCase(spec, report, 0).join("\n");
  assert.match(reasons, /WebGPU translateErrors: 1/);
  assert.match(reasons, /WebGPU pipelineFailures: 2/);
  assert.match(reasons, /WebGPU skippedDraws: 3/);
  assert.match(reasons, /shader translation failed/);
});

test("graphics backend is verified at save, re-entry and final world stages", () => {
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-webgpu", runtime: "wasm", gpu: "webgpu", preflight: []};
  const report = completeReport();
  report.glStages[1].selection.backend = "webgl2";
  assert.match(assessCase(spec, report, 0).join("\n"), /graphics stage afterReenter: rendered webgl2/);
  report.glStages.splice(0, 1);
  assert.match(assessCase(spec, report, 0).join("\n"), /graphics stage beforeQuit: expected once, observed 0/);
});

test("case gate requires full world evidence, the rendered backend and zero synchronous GL calls", () => {
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-webgpu", runtime: "wasm", gpu: "webgpu", preflight: []};
  const report = completeReport();
  assert.deepEqual(assessCase(spec, report, 0), []);
  report.selection.gpu.selected = "webgl2";
  report.selection.gpu.reason = "shader toolchain: the site carries no shader toolchain";
  report.selection.backend = "webgl2";
  report.glFrames.syncCalls = 3;
  report.glFrames.syncByName = {readPixels: 3};
  report.checks.chunksReload.ok = false;
  const reasons = assessCase(spec, report, 0).join("\n");
  assert.match(reasons, /check chunksReload/);
  assert.match(reasons, /rendered backend webgl2; expected webgpu/);
  assert.match(reasons, /3 synchronous GL calls/);
  report.glFrames.syncCalls = 0;
  report.glFrames.gameFrames = 0;
  assert.match(assessCase(spec, report, 0).join("\n"), /no game frame was presented/);
});

test("required inventory terrain evidence must be emitted by the browser report", () => {
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-auto", runtime: "wasm", gpu: "auto", preflight: []};
  const report = completeReport();
  delete report.checks.inventoryStreamingTerrain;
  assert.match(assessCase(spec, report, 0).join("\n"), /check inventoryStreamingTerrain: "missing"/);
  report.checks.inventoryStreamingTerrain = {ok: true, detail: {loaded: 289, holes: {hole: 0}}};
  assert.deepEqual(assessCase(spec, report, 0), []);
});

test("case gate requires server/runtime and backend consistency receipts", () => {
  const spec = {profile: MODERN_PROFILE, id: "site-wasm-auto", runtime: "wasm", gpu: "auto", preflight: []};
  const report = completeReport(MODERN_PROFILE, "webgpu");
  assert.deepEqual(assessCase(spec, report, 0), []);
  report.checks.serverRuntimeMatches.ok = false;
  report.checks.serverRuntimeMatches.detail = {expected: "wasm", actual: "js"};
  report.checks.gpuBackendMatches.ok = false;
  report.checks.gpuBackendMatches.detail = {expected: "webgpu", actual: "webgl2"};
  const reasons = assessCase(spec, report, 0).join("\n");
  assert.match(reasons, /check serverRuntimeMatches/);
  assert.match(reasons, /check gpuBackendMatches/);
});

test("unsupported-browser case requires visible localized message and no game or heavy resources", () => {
  const spec = {profile: MODERN_PROFILE, id: "unsupported-browser-site-no-jspi", form: "site",
    runtime: "unsupported", gpu: "none", unsupportedVariant: true, inject: "no-jspi", locale: "en_us", preflight: []};
  const report = {profile: MODERN_PROFILE, verdict: "PASS", checks: {}, unsupportedBrowser: {
    form: "site", inject: "no-jspi", locale: "en_us",
    visible: true, message: "Gaius needs an up-to-date Chrome with WebAssembly GC and JavaScript Promise Integration (JSPI).",
    retryVisible: true, injection: {mode: "no-jspi", applied: true},
    runtimeSelection: {selected: "unsupported", features: {jspi: false}},
    gameState: {mainType: "undefined", mainStart: null, minecraftState: false}, resourceUrls: [], exceptions: [],
  }};
  assert.deepEqual(assessCase(spec, report, 0), []);
  report.unsupportedBrowser.resourceUrls.push("https://example.invalid/classes.abc.wasm");
  assert.match(assessCase(spec, report, 0).join("\n"), /heavy game resources were requested/);
  report.unsupportedBrowser.resourceUrls = [];
  report.unsupportedBrowser.gameState.mainStart = 1;
  assert.match(assessCase(spec, report, 0).join("\n"), /game boot began/);
});

test("reload soak case is gated by its renderer memory report", () => {
  const spec = {profile: MODERN_PROFILE, id: "reload-soak", runtime: "wasm", gpu: "auto", reloadSoak: true, preflight: []};
  const report = {profile: MODERN_PROFILE, verdict: "PASS", reloadSoak: {gate: {pass: false, reason: "private browser bytes grew 900000000 bytes"}}};
  assert.match(assessCase(spec, report, 0).join("\n"), /reload memory gate/);
  report.reloadSoak.gate.pass = true;
  assert.deepEqual(assessCase(spec, report, 0), []);
});

test("Markdown matrix carries settings, verdicts, reasons and evidence links", () => {
  const markdown = renderMarkdown({staged: "D:/train/staged", seed: "20261014",
    settings: {jobs: 2, ...RELEASE_SETTINGS},
    startedAt: "start", finishedAt: "finish", verdict: "FAIL", pending: [], rows: [
      {profile: PREVIOUS_PROFILE, id: "site-wasm-webgpu", verdict: "FAIL", seconds: 12,
        reasons: ["missing shader | toolchain"], evidence: {report: `cases\\${PREVIOUS_PROFILE}\\report.json`}},
      {profile: MODERN_PROFILE, id: "unsupported-browser", verdict: "PASS", seconds: 4,
        reasons: [], evidence: {probe: {slot: `cases\\${MODERN_PROFILE}\\slot.log`,
          report: `cases\\${MODERN_PROFILE}\\report.json`}}},
    ]});
  assert.match(markdown, /\*\*FAIL\*\*/);
  assert.match(markdown, /jobs=2, ACCEPT_TERRAIN_VISIBLE=1, SETTLE_S=60, BOOT_TIMEOUT_MS=900000/);
  assert.match(markdown, /missing shader \\\| toolchain/);
  assert.ok(markdown.includes(`[report](cases/${PREVIOUS_PROFILE}/report.json)`));
  assert.ok(markdown.includes(`[probe/report](cases/${MODERN_PROFILE}/report.json)`));
});

test("browser harness runs through GAIUS_SLOT when set and directly otherwise", () => {
  const direct = harnessCommand(undefined, "tag");
  assert.equal(direct.slotted, false);
  assert.equal(direct.command, process.execPath);
  assert.equal(direct.args.length, 1);
  const slotted = harnessCommand("/work/slot.sh", "tag");
  assert.equal(slotted.slotted, true);
  assert.equal(slotted.command, "bash");
  assert.deepEqual(slotted.args.slice(0, 5), ["/work/slot.sh", "chrome", "tag", "--", "node"]);
});


test("frozen fallback matrix keeps all capability probes and runs normal save checks", async () => {
  const staged = await mkdtemp(join(tmpdir(), "release-frozen-plan-"));
  try {
    const manifest = {runtimePolicy: "auto", assets: {"classes.js.gz": "client.js.gz", "singleplayer-server.js.gz": "server.js.gz"}};
    for (const form of ["site", "acceptance"]) {
      await mkdir(join(staged, `${form}-${MODERN_PROFILE}`), {recursive: true});
      await writeFile(join(staged, `${form}-${MODERN_PROFILE}`, "index.html"), "");
    }
    await mkdir(join(staged, `portable-${MODERN_PROFILE}`));
    await writeFile(join(staged, `portable-${MODERN_PROFILE}`, `Gaius-${MODERN_PROFILE}.html`), "");
    const row = planCases({staged, profiles: [MODERN_PROFILE], manifests: {[MODERN_PROFILE]: manifest}})
      .find(item => item.id === "unsupported-browser");
    assert.equal(row.frozenFallback, true);
    assert.equal(row.unsupportedVariants.length, 4);
    assert.deepEqual(row.preflight, []);
    const spec = {...row, ...row.unsupportedVariants[0], unsupportedVariant: false,
      runtime: "js", gpu: "webgl2", fallback: "runtime", reenter: "reload", preflight: []};
    const env = buildHarnessEnv(spec, {out: join(staged, "result"), seed: "1"}, {});
    assert.equal(env.ACCEPT_INJECT, "no-wasmgc");
    assert.equal(env.REENTER, "reload");
    assert.equal(env.UNSUPPORTED_BROWSER_ACCEPTANCE, undefined);
    const report = completeReport(MODERN_PROFILE, "webgl2");
    report.selection.runtime = {selected: "js", fallback: true, features: {gc: false}};
    report.runtimes = {world: {client: "js", server: {runtime: "js"}}};
    report.selection.injection = {mode: spec.inject, applied: true};
    assert.deepEqual(assessCase(spec, report, 0), []);
    report.selection.runtime.features.gc = true;
    assert.match(assessCase(spec, report, 0).join(" "), /injected runtime capability/);
  } finally { await rm(staged, {recursive: true, force: true}); }
});

test("portable quit uses HTTP while portable-file retains file routing and all 30 cases", () => {
  const plan = planCases({staged: "/unused", profiles: PROFILE_ORDER, manifests: {}});
  assert.equal(plan.length, 30);
  assert.equal(new Set(plan.map(row => `${row.profile}/${row.id}`)).size, 30);
  for (const profile of PROFILE_ORDER) {
    const file = plan.find(row => row.profile === profile && row.id === "portable-file");
    const http = plan.find(row => row.profile === profile && row.id === "quit-game-portable");
    assert.equal(file.portableHttp, undefined);
    assert.equal(http.portableHttp, true);
    const options = {out: "/unused-out", seed: "1"};
    const fileEnv = buildHarnessEnv(file, options, {PAGE_URL: "https://wrong.invalid/"});
    assert.equal(fileEnv.PAGE_URL, undefined);
    assert.equal(fileEnv.ARTIFACT, file.artifact);
    const pageUrl = "http://127.0.0.1:12345/portable.html";
    const env = buildHarnessEnv({...http, pageUrl}, options, {PAGE_URL: "https://wrong.invalid/"});
    assert.equal(env.PAGE_URL, pageUrl);
    assert.equal(env.ARTIFACT, http.artifact);
    assert.equal(env.QUIT_GAME_ACCEPTANCE, "1");
    assert.equal(env.REENTER, "reload");
  }
});

test("one-file HTTP server records every path, rejects siblings, and gates report evidence", async () => {
  const directory = await mkdtemp(join(tmpdir(), "release-portable-http-"));
  let server;
  try {
    const artifact = join(directory, "portable page.html");
    await writeFile(artifact, "<!doctype html><title>portable</title>");
    await writeFile(join(directory, "singleplayer-server-worker.js"), "must not be served");
    server = await servePortable(artifact);
    assert.match(server.pageUrl, /^http:\/\/127\.0\.0\.1:\d+\/portable%20page\.html$/);
    const page = await fetch(server.pageUrl + "?runtime=wasm");
    assert.equal(page.status, 200);
    assert.match(page.headers.get("content-type"), /text\/html/);
    assert.match(await page.text(), /<title>portable<\/title>/);
    const spec = {profile: MODERN_PROFILE, id: "quit-game-portable", artifact,
      portableHttp: true, quitGame: true, runtime: "wasm", gpu: "auto", preflight: []};
    const report = completeReport();
    report.checks.quitGame = {ok: true};
    assert.match(assessCase(spec, report, 0).join("\n"), /missing portable HTTP/);
    report.portableHttp = server.evidence;
    assert.deepEqual(assessCase(spec, report, 0), []);
    for (const path of ["/singleplayer-server-worker.js", "/runtime/webgpu/shader.js", "/", "/favicon.ico"]) {
      const response = await fetch(new URL(path, server.pageUrl));
      assert.equal(response.status, 404);
      await response.text();
    }
    assert.deepEqual(server.evidence.requestedPaths, ["/portable%20page.html",
      "/singleplayer-server-worker.js", "/runtime/webgpu/shader.js", "/", "/favicon.ico"]);
    const reasons = assessCase(spec, report, 0);
    assert.match(reasons.join("\n"), /portable HTTP requested sibling paths/);
    assert.match(reasons.join("\n"), /runtime\/webgpu/);
    const markdown = renderMarkdown({settings: RELEASE_SETTINGS, rows: [{...spec,
      verdict: "FAIL", reasons, portableHttp: server.evidence}], pending: []});
    assert.ok(markdown.includes(JSON.stringify(server.evidence.requestedPaths)));
    report.portableHttp = {pagePath: server.evidence.pagePath, requestedPaths: []};
    assert.match(assessCase(spec, report, 0).join("\n"), /missing portable HTTP/);
  } finally {
    if (server) await server.close();
    await rm(directory, {recursive: true, force: true});
  }
});
