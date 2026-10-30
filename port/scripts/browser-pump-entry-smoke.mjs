import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { memberText, patcherSource } from "./patcher-sources.mjs";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const networkPath = path.join(
  repository,
  "port",
  "src",
  "main",
  "java",
  "dev",
  "gaius",
  "browser",
  "BrowserClientNetwork.java",
);

// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = patcherSource("MinecraftClientPatcher");
const network = fs.readFileSync(networkPath, "utf8");

function block(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0 && end > start, `missing block: ${startMarker}`);
  return source.slice(start, end);
}

const connectionPatch = memberText(patcher, "patchConnectionBrowserWebSocket");
assert.ok(connectionPatch, "missing block: patchConnectionBrowserWebSocket");
assert.doesNotMatch(
  connectionPatch,
  /io\/netty\/channel\/browser\/BrowserWebSocketChannel[\s\S]*pumpAll/,
  "Connection.tick must not inject a second raw global transport pump",
);
assert.doesNotMatch(
  connectionPatch,
  /\.name\(\s*\"pumpAll\"\s*\)/,
  "Connection patch must not retain a raw pumpAll method call",
);
assert.match(
  connectionPatch,
  /Bootstrap[\s\S]*connect[\s\S]*writeComputeFrames\(node, output\)/,
  "Connection WebSocket connect replacement must remain installed",
);

const helper = memberText(patcher, "pumpBrowserChannels");
assert.ok(helper, "missing block: pumpBrowserChannels");
assert.match(helper, /dev\/gaius\/browser\/BrowserClientNetwork/);
assert.match(helper, /pumpBrowserChannelsAtFrameBoundary/);
assert.doesNotMatch(helper, /io\/netty\/channel\/browser\/BrowserWebSocketChannel/);

const networkWrapper = block(
  network,
  "public static void pumpBrowserChannelsAtFrameBoundary()",
  "public static void beginClientPacketFrame()",
);
assert.match(networkWrapper, /if\s*\(pumping\)/);
assert.match(networkWrapper, /recordJavaPumpSkipped\(\)/);
assert.match(networkWrapper, /BrowserWebSocketChannel\.pumpAllAndReportProgress\(\)/);
assert.match(networkWrapper, /BrowserWebSocketChannel\.hasPumpableInput\(\)/);
assert.match(networkWrapper, /requestInboundPumpContinuation\(\)/);
assert.match(networkWrapper, /finally\s*\{/);

const continuationHelper = block(
  network,
  "/**\n     * Requests the existing browser wakeup scheduler",
  "public static void beginClientPacketFrame()",
);
assert.match(continuationHelper, /bridge\.inboundPump\('frame-continuation'\)/);
assert.match(continuationHelper, /typeof bridge\.inboundPump !== 'function'/);

const asyncPump = block(
  network,
  "private static boolean pumpInbound()",
  "public static void pumpBrowserChannelsAtFrameBoundary()",
);
assert.match(asyncPump, /BrowserWebSocketChannel\.pumpAllAndReportProgress\(\)/);
assert.match(asyncPump, /boolean continuationHint/);
// One JSPI activation drains the transport slices while the hint says more input is ready.
assert.match(asyncPump, /!continuationHint\s*\|\|\s*!BrowserWebSocketChannel\.hasPumpableInput\(\)/);
assert.match(asyncPump, /HostLoop\.yieldToEventLoop\(0\)/);

const runTickHook = block(
  patcher,
  'method.name.equals("runTick") && method.desc.equals("(Z)V")',
  '} else if (method.name.equals("debugClientMetricsStart")',
);
assert.match(runTickHook, /MethodInsnNode pumpClientChannels = pumpBrowserChannels\(\)/);
assert.match(runTickHook, /beginClientPacketFrame/);
assert.match(runTickHook, /processClientPacketsAtScheduledFrameBoundary/);

const serverHook = block(
  patcher,
  'method.name.equals("processPacketsAndTick")',
  'method.name.equals("pollTask")',
);
assert.match(serverHook, /browserPackets\.add\(pumpBrowserChannels\(\)\)/);
assert.match(serverHook, /tickIntegratedServerDistances/);

const result = {
  smoke: "browser-pump-entry",
  rawConnectionPump: false,
  frameBoundaryWrapper: true,
  frameContinuationHint: true,
  clientRunTickHook: true,
  serverFrameHook: true,
  result: "pass",
};
console.log(JSON.stringify(result));
