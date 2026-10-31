// Shared pieces of the browser harnesses: minecraft-title-screen-cdp.mjs,
// browser-file-entry-acceptance.mjs, minecraft-263-singleplayer-cdp.mjs,
// minecraft-263-multiplayer-cdp.mjs and the scripts in this directory.
//
//   chromeIsolationArgs()        Chrome switches that keep a run on this machine
//   RUNTIME_TELEMETRY_EXPRESSION one snapshot of runtime telemetry that reads the same on the
//                                TeaVM JavaScript build and the WebAssembly GC (JSPI) build
//   runtimeTelemetryFailures()   the failures such a snapshot proves
//   pageForRuntime()             the page of a dist for --runtime js|wasm

import {existsSync, readFileSync} from "node:fs";
import {join} from "node:path";

// Only browser console records can prove a shader failure. Harness CHECK records may
// contain pipeline diagnostics and the word FAIL when a terrain coverage gate fails.
export function isShaderConsoleError(line) {
  return /\b(?:console\.|log\.)/.test(line)
    && ((/shader|glsl|spir|pipeline|program/i.test(line)
      && /error|fail|couldn't|could not|invalid/i.test(line))
      || /compil\w* (error|fail)/i.test(line));
}

// A closed local port: Chrome sends every proxied request there and gets a connection
// refusal. Loopback destinations are never proxied, so the served dist and the local bridge
// keep working. (--host-resolver-rules would do the same, but Chrome shows its unsupported-flag
// infobar, which shrinks the viewport by 56 px.)
export const CLOSED_PROXY = "http://127.0.0.1:9";

// Features that make background requests on a fresh profile. One --disable-features switch
// only: Chrome keeps the last value of a repeated switch.
export const DISABLED_FEATURES = Object.freeze([
  "Translate", "MediaRouter", "DialMediaRouteProvider", "OptimizationHints",
  "AutofillServerCommunication", "CertificateTransparencyComponentUpdater",
  "InterestFeedContentSuggestions", "PrivacySandboxSettings4",
]);

// Switches that stop the background traffic of a headless Chrome on a fresh profile: GCM
// (Chrome 154 still checks in and logs "Failed to log in to GCM" with --disable-background-
// networking alone), sync, component and extension updates, safe-browsing pings, domain
// reliability and metrics uploads; and, unless GAIUS_HARNESS_NETWORK=allow, every non-loopback
// request goes to CLOSED_PROXY. Multiplayer tests use the local bridge on 127.0.0.1 only.
export function chromeIsolationArgs({allowNetwork = process.env.GAIUS_HARNESS_NETWORK === "allow",
    disableFeatures = []} = {}) {
  const features = [...new Set([...DISABLED_FEATURES, ...disableFeatures])];
  const args = [
    "--no-first-run", "--no-default-browser-check",
    "--disable-background-networking", "--disable-component-update", "--disable-sync",
    "--disable-default-apps", "--disable-extensions",
    "--disable-component-extensions-with-background-pages",
    "--disable-client-side-phishing-detection", "--disable-domain-reliability", "--no-pings",
    "--metrics-recording-only", "--disable-breakpad",
    `--disable-features=${features.join(",")}`,
    // GCM reads its endpoints from these switches; the closed port refuses them at once.
    `--gcm-checkin-url=${CLOSED_PROXY}/checkin`,
    `--gcm-registration-url=${CLOSED_PROXY}/register`,
    `--gcm-mcs-endpoint=${CLOSED_PROXY.replace("http:", "https:")}`,
  ];
  if (!allowNetwork) {
    args.push(`--proxy-server=${CLOSED_PROXY}`, "--proxy-bypass-list=127.0.0.1;localhost;[::1]");
  }
  return args;
}

// Chrome log lines that mean a background service still reached for the network.
export const CHROME_BACKGROUND_NETWORK_PATTERN = /google_apis[\\/]gcm|Failed to log in to GCM|registration_request\.cc/;

// Evaluated in a page (or Worker) global scope; the result is plain JSON. Fields that a
// runtime does not have read as null, never as a different meaning:
//   runtime          "wasm" | "js" | null (the launcher marks the WebAssembly GC page)
//   phases           launcher phases (both runtimes) and the wasm loader phases
//   suspension       TeaVM JavaScript suspension guards (the WebAssembly GC build runs JSPI and
//                    has no coroutine guards: null there)
//   threads          the JSPI scheduler and the first failed Java thread (wasm), null on js
//   bootError        the launcher or loader boot error, either runtime
//   memory           JS heap, wasm linear memory and BrowserMemory live/peak bytes
export const RUNTIME_TELEMETRY_EXPRESSION = `(()=>{const g=globalThis;const num=v=>typeof v==='number'&&isFinite(v)?v:null;
  const txt=(v,n)=>v==null?null:String(v&&v.stack||v).slice(0,n||4000);
  const pick=o=>{if(!o||typeof o!=='object')return null;const r={};for(const k of Object.keys(o)){const v=o[k];if(typeof v==='number'||typeof v==='string'||typeof v==='boolean')r[k]=v;}return r;};
  const wasm=g.__gaiusRuntime==='wasm'||!!g.__gaiusWasmTimings;
  const js=!wasm&&(typeof g.main==='function'||!!g.__gaiusBootTimings);
  const sched=g.__gaiusJspiScheduler;const mem=g.__gaiusMemoryTelemetry&&g.__gaiusMemoryTelemetry.browserMemory;
  let linear=null;try{linear=g.__gaiusWasmMemory&&g.__gaiusWasmMemory.buffer?g.__gaiusWasmMemory.buffer.byteLength:null;}catch(e){}
  const pm=g.performance&&g.performance.memory;
  return {runtime:wasm?'wasm':js?'js':null,profile:typeof g.__gaiusProfileId==='string'?g.__gaiusProfileId:null,
    phases:{launcher:pick(g.__gaiusBootTimings),wasm:pick(g.__gaiusWasmTimings)},
    suspension:wasm?null:{noSuspendViolations:g.__gaiusNoSuspendViolations|0,lastNoSuspendViolation:txt(g.__gaiusLastNoSuspendViolation),
      clinitSuspensions:g.__gaiusClinitSuspensions|0,firstClinitSuspension:txt(g.__gaiusFirstClinitSuspension)},
    threads:wasm?{scheduler:sched?pick(sched.stats||sched):null,threadError:txt(g.__gaiusThreadError)}:null,
    bootError:txt(g.__gaiusBootError),
    memory:{usedJSHeapSize:pm?num(pm.usedJSHeapSize):null,totalJSHeapSize:pm?num(pm.totalJSHeapSize):null,
      linearMemoryBytes:num(linear),browserMemoryLiveBytes:mem?num(mem.liveBytes):null,browserMemoryPeakBytes:mem?num(mem.peakLiveBytes):null}};})()`;

// Failures that a RUNTIME_TELEMETRY_EXPRESSION snapshot proves, on either runtime: a TeaVM
// no-suspend guard violation (js), a failed Java thread (wasm), a boot error (both). Clinit
// suspensions are diagnostic on js (see tools/teavm-runtime-guards.mjs) and are not failures.
export function runtimeTelemetryFailures(snapshot, label = "") {
  const prefix = label ? `${label}: ` : "";
  const failures = [];
  if (!snapshot || typeof snapshot !== "object") return failures;
  const violations = Number(snapshot.suspension?.noSuspendViolations) || 0;
  if (violations > 0) {
    failures.push(`${prefix}${violations} TeaVM no-suspend guard violation(s); last: `
      + String(snapshot.suspension.lastNoSuspendViolation || "").split("\n").slice(0, 6).join(" | "));
  }
  if (snapshot.threads?.threadError) {
    failures.push(`${prefix}a Java thread failed: ${String(snapshot.threads.threadError).split("\n").slice(0, 6).join(" | ")}`);
  }
  if (snapshot.bootError) {
    failures.push(`${prefix}boot error: ${String(snapshot.bootError).split("\n").slice(0, 6).join(" | ")}`);
  }
  return failures;
}

// The page of a site-layout dist for one runtime. A dist from the WP5 launcher selects the
// runtime with ?runtime=; the M0 wasm dists carry their own index-wasm.html (index.html there is
// the same wasm page).
export function pageForRuntime(dist, runtime) {
  if (runtime === "wasm") {
    return existsSync(join(dist, "index-wasm.html")) ? "index-wasm.html" : "index.html?runtime=wasm";
  }
  if (runtime === "js") {
    return existsSync(join(dist, "index-wasm.html")) || !existsSync(join(dist, "classes.js"))
      ? "index.html?runtime=js" : "index.html";
  }
  return "index.html";
}

// The page of a dist for one runtime opened from disk (file://). For wasm (WP6): the portable
// Gaius.html of build-portable-html.py --wasm-dist (its manifest names the wasm client), else the
// dual-runtime dist's own index.html, which make-wasm-page.py --file-payloads gives the payload
// scripts it reads instead of fetching siblings (its default runtime is wasm). Gaius-wasm.html
// and index-wasm-file.html are the M0 spike forms.
export function fileArtifactForRuntime(dist, runtime) {
  if (runtime === "wasm") {
    if (existsSync(join(dist, "Gaius-wasm.html"))) return join(dist, "Gaius-wasm.html");
    if (portableHasWasm(dist)) return join(dist, "Gaius.html");
    const index = join(dist, "index.html");
    if (existsSync(index) && readFileSync(index, "utf8").includes("__gaiusFilePayloads")) return index;
    if (existsSync(join(dist, "index-wasm-file.html"))) return join(dist, "index-wasm-file.html");
    return join(dist, "Gaius.html");
  }
  return join(dist, "Gaius.html");
}

function portableHasWasm(dist) {
  try {
    return Boolean(JSON.parse(readFileSync(join(dist, "Gaius.manifest.json"), "utf8")).wasmClient);
  } catch {
    return false;
  }
}

// --runtime js|wasm (or GAIUS_RUNTIME); null when neither is given.
export function runtimeArgument(argv = process.argv, env = process.env) {
  const index = argv.indexOf("--runtime");
  const inline = argv.find(value => value.startsWith("--runtime="));
  const value = index >= 0 ? argv[index + 1] : inline ? inline.slice("--runtime=".length) : env.GAIUS_RUNTIME || null;
  if (value == null || value === "") return null;
  if (value !== "js" && value !== "wasm") throw new Error(`--runtime must be js or wasm, not ${value}`);
  return value;
}

export function runtimePageUrl(pageUrl, requestedRuntime, explicitQuery = "") {
  const url = new URL(pageUrl);
  const query = new URLSearchParams(explicitQuery);
  for (const [name, value] of query) url.searchParams.set(name, value);
  if (requestedRuntime && !query.has("runtime")) {
    url.searchParams.set("runtime", requestedRuntime);
  }
  return url.href;
}

export function runtimeSelectionMatches(requestedRuntime, observedRuntime) {
  return (observedRuntime === "js" || observedRuntime === "wasm")
    && (!requestedRuntime || requestedRuntime === observedRuntime);
}
