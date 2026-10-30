import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const patchFile = `${root}/tools/src/main/java/dev/gaius/tools/patches/telemetry/TickPacketParityPatches.java`;
const recorderFile = `${root}/src/main/java/dev/gaius/parity/TickPacketRecorder.java`;
const patcherFile = `${root}/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java`;
const patch = await readFile(patchFile, "utf8");
const recorder = await readFile(recorderFile, "utf8");
const patcher = await readFile(patcherFile, "utf8");

const methodStart = patch.indexOf("public static void applyBrowserPacketApplyInline");
assert.notEqual(methodStart, -1, "parity apply inline patch is registered in its patch domain");
const methodEnd = patch.indexOf("/**", methodStart);
const method = patch.slice(methodStart, methodEnd < 0 ? patch.length : methodEnd);
assert.match(method, /"isApplying", "\(\)Z"/);
assert.match(method, /IFEQ, normalDispatch/);
assert.match(method, /new InsnNode\(Opcodes\.RETURN\)/,
  "synchronous replay application returns before Worker thread handoff");
assert.doesNotMatch(method, /recordInlineDecodedPacket/,
  "an injected parity packet is not a decoded Netty packet boundary");
assert.match(method, /method\.instructions\.insert\(code\)/,
  "the guarded inline branch must precede PacketUtils' Worker force-queue guard");
assert.match(method, /recorderChecks != 1/,
  "the bytecode verifier requires one recorder-scoped check");
assert.match(patcher,
  /if \(BuildTarget\.wasmGc\(\) && TickPacketParityPatches\.requested\(\)\)[\s\S]*?applyBrowserPacketApplyInline/,
  "only an opted-in WasmGC parity build installs this branch");
assert.match(recorder, /public static boolean isApplying\(\) \{ return enabled && applying; \}/,
  "the guard is false outside enabled recorder application");

function dispatch({parityEnabled, applying, workerServer, queued}) {
  if (parityEnabled && applying) return "inline";
  if (workerServer && !queued) return "schedule";
  if (workerServer && queued) return "queued-return";
  return "vanilla";
}

assert.equal(dispatch({parityEnabled: true, applying: true, workerServer: true, queued: false}), "inline");
assert.equal(dispatch({parityEnabled: false, applying: true, workerServer: true, queued: false}), "schedule",
  "release builds retain the ordinary Worker scheduling path");
assert.equal(dispatch({parityEnabled: true, applying: false, workerServer: true, queued: false}), "schedule",
  "live packets retain the ordinary Worker scheduling path");
assert.equal(dispatch({parityEnabled: true, applying: true, workerServer: true, queued: true}), "inline",
  "the replay application is synchronous even if entered while draining");

process.stdout.write("packet parity apply inline patch: pass (4 dispatch cases)\n");
