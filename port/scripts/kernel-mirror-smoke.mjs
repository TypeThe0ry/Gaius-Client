import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import {loadProfiles, loadManifest, enableTable} from "../tools/kernelmirror/kernelmirror.mjs";

const root = fileURLToPath(new URL("../../", import.meta.url));
const manifest = loadManifest(fileURLToPath(new URL("../native/kernel-mirror.toml", import.meta.url)));
const quarantined = loadProfiles(root).filter(profile => profile.kernelMirror?.quarantined?.mesher);
assert.ok(quarantined.length > 0, "a streaming mesher quarantine must remain in profile metadata");
for (const profile of quarantined) {
    const baseline = readFileSync(new URL(`../mc/profiles/${profile.id}/kernel-mirror.lock`, import.meta.url), "utf8");
    const decision = enableTable(profile, manifest, baseline, JSON.parse(baseline));
    assert.equal(decision.kernels.mesher.enabled, false);
    assert.ok(decision.kernels.mesher.reasons.includes("PROFILE_DEFAULT_OFF"));
    assert.equal(decision.kernels.light.enabled, true, "the quarantine preserves light acceleration");
    assert.equal(decision.kernels.worldgen.enabled, true, "the quarantine preserves world generation");
}

const web = new URL("../web/kernels/", import.meta.url);
let workers = 0;
const context = vm.createContext({console, performance, URLSearchParams, WebAssembly, Blob,
    ArrayBuffer, Uint8Array, DataView, Map, Set, Promise, setTimeout, clearTimeout, setInterval, clearInterval,
    navigator: {hardwareConcurrency: 2}, URL: {createObjectURL: () => "blob:test", revokeObjectURL() {}},
    Worker: class { constructor() { workers++; throw new Error("disabled kernels must not spawn workers"); } },
});
const load = (name) => vm.runInContext(readFileSync(new URL(name, web), "utf8"), context, {filename: name});
load("kernel-mirror.js");
const mirror = context.GaiusKernelMirror;
assert.equal(mirror.enabled("mesher"), false);
const table = {schemaVersion: 1, profile: "reviewed-profile", kernels: Object.fromEntries(
    ["noise", "mesher", "light", "worldgen", "shaderxlat"].map((name) => [name, {enabled: true}]))};
mirror.bind(table, table.profile);
assert.equal(mirror.enabled("noise"), true);
assert.equal(mirror.enabled("mesher", "another-profile"), false);
assert.equal(mirror.allowed("mesh_section", "noise"), false);
assert.equal(mirror.allowed("unknown", "mesher"), false);
table.kernels.mesher.enabled = false;
table.kernels.light.enabled = false;
table.kernels.worldgen.enabled = false;
load("kernel-policy.js");
load("kernel-runtime.js");
const runtime = context.GaiusKernelRuntime.create({workerSource: "test", size: 1,
    kernels: {mesher: {kinds: ["mesh_section"], variants: {baseline: {bytes: new Uint8Array([0])}}}}});
assert.equal(runtime.available("mesher"), false);
let vanilla = 0;
const fallback = async (promise) => promise.catch((error) => {
    assert.equal(error.code, "needs-vanilla");
    assert.match(error.message, /NEEDS_VANILLA/);
    vanilla++;
    return "vanilla";
});
assert.equal(await fallback(runtime.submit("mesh_section", new ArrayBuffer(0))), "vanilla");
assert.equal(runtime.stats.submitted, 0);
runtime.terminate();
load("kernel-pool.js");
const pool = await context.GaiusKernelPool.create({size: 1, workerSource: "test",
    module: new WebAssembly.Module(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]))});
assert.equal(await fallback(pool.submit("mesh_section", new ArrayBuffer(0))), "vanilla");
pool.terminate();
load("light-job.js");
let dispatched = 0;
const host = context.GaiusLightJob.installHost({available: () => true, submit() { dispatched++; }}, {enabled: true});
assert.equal(host.enabled, false, "an on-switch cannot override a disabled mirror");
host.submit(new ArrayBuffer(0), 0, () => assert.fail("disabled light completed"), (message) => {
    assert.match(message, /NEEDS_VANILLA/); vanilla++;
});
load("worldgen-job.js");
const rawPool = {submit() { dispatched++; return Promise.reject(new Error("disabled dispatch")); }};
const untouchedGenerator = {inlineLeft: 3};
assert.equal(await fallback(context.GaiusWorldgenJob.load(rawPool, untouchedGenerator)), "vanilla");
assert.equal(await fallback(context.GaiusWorldgenJob.terrain(rawPool, untouchedGenerator)), "vanilla");
assert.equal(await fallback(context.GaiusWorldgenJob.biomes(rawPool, untouchedGenerator)), "vanilla");
assert.equal(await fallback(context.GaiusWorldgenJob.surface(rawPool, untouchedGenerator)), "vanilla");
assert.equal(untouchedGenerator.inlineLeft, 3);
load("worldgen-kernel.js");
context.__gaiusKernelClient = {available: () => true, submit() { dispatched++; }};
assert.equal(context.GaiusWorldgenKernel.usable(), false);
const worldgenResult = () => assert.fail("disabled worldgen completed");
const worldgenError = (message) => {
    assert.match(message, /^transient:needs-vanilla:NEEDS_VANILLA/); vanilla++;
};
context.GaiusWorldgenKernel.submitTerrain(1, 0, 0, 0, null, worldgenResult, worldgenError);
context.GaiusWorldgenKernel.submitBiomes(1, 0, 0, worldgenResult, worldgenError);
context.GaiusWorldgenKernel.submitSurface(1, new Uint8Array(0), 0, 0, null, worldgenResult, worldgenError);
context.GaiusWorldgenKernel.submitSurfaceStored(1, 1, 0, 0, null, worldgenResult, worldgenError);
load("mesh-kernel-job.js");
assert.equal(context.__gaiusMeshKernel.ready(), false);
assert.equal(context.__gaiusMeshKernel.setTable(null, 0, 0), false);
load("mesh-job.js");
assert.equal(context.GaiusMeshJob.enabled(), false);
const rawMesh = Object.create(context.GaiusMeshJob.MeshTableSession.prototype);
const rawSubmit = () => { dispatched++; return Promise.reject(new Error("disabled dispatch")); };
assert.equal(await fallback(rawMesh.preload(rawSubmit)), "vanilla");
assert.equal(await fallback(rawMesh.mesh(rawSubmit)), "vanilla");
const workerSource = readFileSync(new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url), "utf8");
const createWorkerClient = vm.runInContext(`(function(root) {${workerSource.slice(workerSource.indexOf("function createKernelClient(port)"))}
return createKernelClient; })(globalThis)`, context);
for (const connect of [context.GaiusKernelRuntime.connect, createWorkerClient]) {
    const port = {start() {}, postMessage() { dispatched++; }};
    const client = connect(port);
    port.onmessage({data: {type: "status", status: {enabled: true, kernels: {mesher: {available: true}}}}});
    assert.equal(client.available("mesher"), false);
    assert.equal(client.available(), false);
    assert.equal(await fallback(client.submit("mesh_section", new ArrayBuffer(0))), "vanilla");
}
table.kernels.shaderxlat.enabled = false;
vm.runInContext(readFileSync(new URL("../web/runtime/webgpu/gpu-glue.js", import.meta.url), "utf8"), context);
const gpu = context.GaiusWebGpu;
assert.equal(gpu.installTranslator({}), false);
assert.equal(gpu.hasTranslator(), false);
assert.equal(gpu.translateSpirv(new Uint8Array(0), "", 0), -1);
assert.match(gpu.translateError, /NEEDS_VANILLA/);
assert.equal(gpu.prefetchSpirv(new Uint8Array(0), "", 0), false);
const java = fileURLToPath(new URL("../src/main/java/dev/gaius/browser/kernel/KernelMirror.java", import.meta.url));
const body = [...readFileSync(java, "utf8").matchAll(/"((?:\\.|[^"\\])*)"/gu)]
    .map((match) => JSON.parse('"' + match[1] + '"')).slice(1).join("");
const javaMirror = vm.runInContext(`(function(kernel) {${body}})`, context);
assert.equal(javaMirror("mesher"), false);
assert.equal(javaMirror("noise"), true);
assert.equal(dispatched, 0);
assert.equal(workers, 0);
assert.equal(vanilla, 15);
mirror.bind(table, "unreviewed-profile");
assert.equal(mirror.enabled("noise"), false);
context.__gaiusKernelSemantics = {schemaVersion: 1, profile: "reviewed-profile", kernels: {noise: null}};
assert.equal(mirror.enabled("noise"), false);
console.log("kernel-mirror-smoke: pool/runtime/ports/hosts/raw submitters/Java JSBody fail closed; 15 vanilla fallbacks, 0 worker dispatches PASS");
