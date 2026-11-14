import assert from "node:assert/strict";
import vm from "node:vm";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const source = await readFile(`${root}/port/src/main/java/dev/gaius/browser/BrowserChunkPipelineTelemetry.java`, "utf8");
const initializeMethod = "private static native void initializeEnabledStateJs();";
const initializeAt = source.indexOf(initializeMethod);
const initializeMarker = "@JSBody(script = \"\"\"";
const initializeStart = source.lastIndexOf(initializeMarker, initializeAt);
assert.notEqual(initializeStart, -1, "initializeEnabledStateJs JSBody is missing");
const initializeBodyStart = initializeStart + initializeMarker.length;
const initializeBodyEnd = source.indexOf('""")', initializeBodyStart);
const initializeBody = source.slice(initializeBodyStart, initializeBodyEnd);
assert.match(initializeBody, /\bconst\s+root\s*=/, "initializer root local must use const");
assert.match(initializeBody, /\bconst\s+causal\s*=/, "initializer state local must use const causal");
assert.match(initializeBody, /\bconst\s+events\s*=/, "initializer callback local must use const");
assert.match(initializeBody, /\bconst\s+removed\s*=/, "initializer callback local must use const");
const method = "private static native void setEpochsJs(int levelEpoch, int resourceEpoch);";
const methodAt = source.indexOf(method);
assert.notEqual(methodAt, -1, "setEpochsJs is missing");
const marker = '@JSBody(params = {"levelEpoch", "resourceEpoch"}, script = """';
const start = source.lastIndexOf(marker, methodAt);
assert.notEqual(start, -1, "setEpochsJs JSBody is missing");
const bodyStart = start + marker.length;
const bodyEnd = source.indexOf('""")', bodyStart);
assert.ok(bodyEnd > bodyStart, "setEpochsJs JSBody is unterminated");
const body = source.slice(bodyStart, bodyEnd);
assert.match(body, /\bconst\s+root\s*=/, "top-level JSBody root local must use const");
assert.match(body, /\bconst\s+causal\s*=/, "top-level JSBody state local must use const");
assert.match(body, /\bconst\s+nextLevel\s*=[^;]*\bnextResource\s*=/,
  "setEpochsJs level and resource locals must use const");
assert.doesNotMatch(body, /\b(?:var|let)\s+(?:root|causal|nextLevel|nextResource)\b/,
  "setEpochsJs wrapper locals must retain lexical declarations");
const state = {levelEpoch: 0, resourceEpoch: 0, latestBySection: new Map(), uniforms: new Map()};
const context = {globalThis: {__gaiusChunkPipelineTelemetry: {causal: state}}, Number};
const setEpochs = new vm.Script(`(function(levelEpoch, resourceEpoch) {${body}\n})`).runInNewContext(context);
setEpochs(1, 1);
assert.deepEqual([state.levelEpoch, state.resourceEpoch], [1, 1],
  "setEpochsJs fixture did not preserve both parameters");
assert.throws(() => new vm.Script("function wrapper(b,c){const c=globalThis.value;}"), SyntaxError,
  "a let/const local renamed to c collides with the short wrapper parameter");
assert.doesNotThrow(() => new vm.Script("function wrapper(b,c){const causal=globalThis.value;}"),
  "a descriptive lexical local remains legal beside short wrapper parameters");
console.log("jsbody-wrapper-collision-smoke: PASS");
