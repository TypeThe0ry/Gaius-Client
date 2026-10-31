import {runtimeSelectionMatches} from "../port/scripts/bench/harness-common.mjs";

// What a run asked for (?gpu=, ?runtime=, ?serverRuntime=) against what actually ran. A page that
// cannot honour a request falls back silently (WebGPU -> WebGL 2 when the shader toolchain or the
// device is missing, the Wasm client -> the JavaScript client, the Wasm server -> the JavaScript
// server), and the run then fails, if at all, only through an unrelated check such as
// noShaderErrors. These checks compare the request with the live state and the vanilla
// "Using graphics backend" log line, and carry the fallback reason.

// Evaluated in the page. Fields that a build does not have read as null.
export const BACKEND_STATE_EXPRESSION = `(()=>{const g=globalThis;const s=g.__gaiusGpuSelection||null;const w=g.GaiusWebGpu||null;
  const rt=g.__gaiusRuntimeSelection||null;const boot=g.__gaiusBoot||{};const tc=g.__gaiusWebGpuShaderToolchain;
  const cut=(v,n)=>v==null||v===''?null:String(v&&v.stack||v).slice(0,n);
  return {gpu:{selection:s?{requested:s.requested,source:s.source,selected:s.selected,fallback:s.fallback===true,reason:cut(s.reason,300),
      inject:s.inject||'',initMs:s.initMs??null}:null,
    backend:typeof g.__gaiusGpuBackend==='string'?g.__gaiusGpuBackend:null,
    webgpu:w?{status:w.status??null,failure:cut(w.failure,500),lost:cut(w.lostReason,300)}:null,
    toolchain:{declared:!!(tc&&typeof tc==='object'&&tc.loader),ready:!!g.__gaiusShaderToolchainReady}},
    runtime:rt?{requested:rt.requested,source:rt.source,selected:rt.selected,fallback:rt.fallback===true,late:rt.late===true,
      reason:cut(rt.reason,300),form:rt.form||null}:null,
    bootServerRuntime:boot.serverRuntime||null};})()`;

const BACKEND_LOG = /Using graphics backend\s+([A-Za-z0-9_.-]+)(?:,\s*using drivers:\s*(.*))?/;
const FALLBACK_LOG = /gpu-fallback|WebGPU is unavailable|WebGPU[^\n]{0,80}fall(?:ing)? ?back|fall(?:ing)? ?back[^\n]{0,60}(?:OpenGL|WebGL)|Failed to create backend|runtime-fallback|server-runtime-fallback|WebAssembly GC client did not start|shader toolchain[^\n]{0,120}(?:fail|unavailable|missing|timed out|could not|did not|no )/i;

// The graphics backend vanilla logged last ("Using graphics backend WebGPU|OpenGL, using drivers: ...").
// OpenGL is the WebGL 2 context in a browser. Other names are returned as they are.
export function graphicsBackendFromLog(lines) {
  let found = null;
  for (const line of lines || []) {
    const match = BACKEND_LOG.exec(String(line));
    if (!match) continue;
    const name = match[1].toLowerCase();
    found = {backend: name === "webgpu" ? "webgpu" : name === "opengl" ? "webgl2" : name, name: match[1], drivers: (match[2] || "").trim() || null,
      line: String(line).slice(0, 300)};
  }
  return found;
}

export function requestedFromQuery(pageUrl, env = {}) {
  const query = new URL(pageUrl).searchParams;
  const gpu = String(query.get("gpu") || "").toLowerCase();
  const server = String(query.get("serverRuntime") || env.SERVER_RUNTIME || "").toLowerCase();
  const expectedGpu = String(env.GAIUS_GPU_EXPECT || "").toLowerCase();
  return {gpu: expectedGpu || (gpu === "webgpu" || gpu === "webgl2" ? gpu : null),
    gpuSource: expectedGpu ? "GAIUS_GPU_EXPECT" : gpu ? "url" : null,
    serverRuntime: server === "wasm" || server === "js" ? server : null};
}

// Every reason the evidence gives for a fallback, most specific first.
export function fallbackReasons(state, lines) {
  const reasons = [];
  const gpu = state?.gpu;
  if (gpu?.selection?.fallback) reasons.push(`boot: ${gpu.selection.reason}`);
  if (gpu?.webgpu?.failure) reasons.push(`webgpu glue: ${gpu.webgpu.failure}`);
  if (gpu?.webgpu?.lost) reasons.push(`webgpu device lost: ${gpu.webgpu.lost}`);
  if (gpu?.selection?.requested === "webgpu" && gpu.toolchain && !gpu.toolchain.ready && !gpu.toolchain.declared) {
    reasons.push("page declares no shader toolchain");
  } else if (gpu?.toolchain?.declared && !gpu.toolchain.ready) {
    reasons.push("shader toolchain declared but not ready");
  }
  if (state?.runtime?.fallback) reasons.push(`client runtime: ${state.runtime.reason}`);
  for (const line of lines || []) {
    if (reasons.length >= 8) break;
    if (FALLBACK_LOG.test(String(line)) && /console\.|log\.|\[worker\]/.test(String(line))) reasons.push(`log: ${String(line).trim().slice(0, 240)}`);
  }
  return reasons;
}

// requested: {gpu, serverRuntime, runtime}; state: BACKEND_STATE_EXPRESSION result; serverRuntime:
// the Worker's ping reply ({runtime} | {timeout} | {error} | null); lines: console log lines.
// Returns {gpuBackendMatches, serverRuntimeMatches} as {ok, detail}.
export function backendConsistencyChecks({requested = {}, state = null, serverReply = null, lines = []} = {}) {
  const logged = graphicsBackendFromLog(lines);
  const selection = state?.gpu?.selection || null;
  const expectedGpu = requested.gpu || (selection && (selection.requested === "webgpu" || selection.requested === "webgl2") ? selection.requested : null);
  const pageBackend = state?.gpu?.backend || null;
  const reasons = fallbackReasons(state, lines);
  // The page value and the vanilla log name the same thing from two sides; if both exist they must agree.
  const evidenceConflict = pageBackend && logged && logged.backend !== pageBackend;
  const actualGpu = pageBackend || logged?.backend || null;
  const gpuDetail = {requested: requested.gpu || null, requestedSource: requested.gpuSource || (requested.gpu ? "url" : expectedGpu ? "page-default" : null),
    expected: expectedGpu, actual: actualGpu, pageBackend, logBackend: logged?.backend || null, logLine: logged?.line || null,
    selection, fallbackReasons: reasons};
  let gpuOk = true;
  if (evidenceConflict) { gpuOk = false; gpuDetail.problem = `page reports ${pageBackend}, the log says ${logged.backend}`; }
  else if (!actualGpu) { gpuOk = expectedGpu === null; gpuDetail.problem = expectedGpu === null ? null : "the graphics backend is not observable (no __gaiusGpuBackend, no 'Using graphics backend' line)"; }
  else if (expectedGpu && actualGpu !== expectedGpu) { gpuOk = false; gpuDetail.problem = `requested ${expectedGpu} but the game renders with ${actualGpu}`; }
  if (!gpuOk && gpuDetail.problem && reasons.length === 0) gpuDetail.fallbackReasons = ["no fallback reason was recorded"];

  const bootServer = state?.bootServerRuntime || null;
  const pinged = serverReply && typeof serverReply.runtime === "string" ? serverReply.runtime : null;
  const actualServer = pinged || bootServer;
  const expectedServer = requested.serverRuntime || bootServer;
  const serverDetail = {requested: requested.serverRuntime || null, bootDecision: bootServer, workerReported: pinged,
    confirmedByWorker: pinged !== null, actual: actualServer, workerReply: pinged === null ? serverReply : undefined,
    fallbackReasons: reasons.filter(r => /server/i.test(r))};
  let serverOk = true;
  if (!actualServer) { serverOk = false; serverDetail.problem = "the server runtime is not observable"; }
  else if (expectedServer && actualServer !== expectedServer) {
    serverOk = false;
    serverDetail.problem = `expected the ${expectedServer} server runtime but the ${actualServer} one runs`;
  }
  return {gpuBackendMatches: {ok: gpuOk, detail: gpuDetail}, serverRuntimeMatches: {ok: serverOk, detail: serverDetail}};
}

// The client runtime check with the fallback reason attached. observed is "js" | "wasm" | null.
export function clientRuntimeCheck({requested, observed, telemetry, state}) {
  const ok = runtimeSelectionMatches(requested, observed);
  const detail = {requested, observed, telemetry};
  if (state?.runtime) detail.selection = state.runtime;
  if (!ok) {
    detail.fallbackReasons = fallbackReasons({runtime: state?.runtime}, []);
    if (observed === null) detail.problem = "the client runtime is not observable";
    else detail.problem = `requested ${requested || "any"} but the ${observed} client runs`;
  }
  return {ok, detail};
}
