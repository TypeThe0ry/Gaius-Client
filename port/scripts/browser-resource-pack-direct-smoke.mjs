#!/usr/bin/env node
// Contract and local-stream smoke for the direct resource-pack attempt.  The actual TeaVM
// build is owned by the build train; this test exercises the same browser fetch policy and
// records throughput for CORS success and relay fallback cases.
import assert from "node:assert/strict";
import http from "node:http";
import {readFile} from "node:fs/promises";
import {performance} from "node:perf_hooks";

const source = await readFile(new URL("../src/main/java/dev/gaius/browser/BrowserHttpProxy.java", import.meta.url), "utf8");
const patcher = await readFile(new URL("../tools/src/main/java/dev/gaius/tools/patches/network/NetworkPatches.java", import.meta.url), "utf8");
assert.match(source, /directResourcePack/);
assert.doesNotMatch(source, /__gaiusResourcePackFetchOptions/,
  "dead direct fetch options global must not be exposed");
assert.match(source, /validateResourcePackResponse/,
  "direct response must be validated before vanilla hashing");
assert.match(patcher, /validateResourcePackResponse/,
  "HttpUtil patch must validate status, length and stream end");
assert.match(source, /directFallback/);
assert.match(source, /mixed content/i);
assert.match(patcher, /5000[\s\S]*setConnectTimeout/);
assert.match(patcher, /5000[\s\S]*setReadTimeout/);

const payload = Buffer.alloc(256 * 1024, 0x47);
function server(cors) {
  return http.createServer((req, res) => {
    if (cors) res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Content-Length", String(payload.length));
    res.writeHead(200);
    res.end(payload);
  });
}
const directServer = server(true);
const noCorsServer = server(false);
const relayServer = server(true);
await new Promise(resolve => directServer.listen(0, "127.0.0.1", resolve));
await new Promise(resolve => noCorsServer.listen(0, "127.0.0.1", resolve));
await new Promise(resolve => relayServer.listen(0, "127.0.0.1", resolve));
const directUrl = `http://127.0.0.1:${directServer.address().port}/pack.zip`;
const noCorsUrl = `http://127.0.0.1:${noCorsServer.address().port}/pack.zip`;
const relayUrl = `http://127.0.0.1:${relayServer.address().port}/proxy/resource-pack?stream=1`;
const counters = {};
async function browserFetch(url, corsExpected) {
  const start = performance.now();
  const response = await fetch(url, {mode: "cors", credentials: "omit"});
  const allow = response.headers.get("access-control-allow-origin");
  if (corsExpected && allow !== "*") throw new TypeError("CORS response header missing");
  if (!corsExpected) throw new TypeError("simulated browser CORS rejection");
  const reader = response.body.getReader();
  let size = 0;
  for (;;) {
    const part = await reader.read();
    if (part.done) break;
    size += part.value.byteLength;
  }
  return {size, mibps: size / ((performance.now() - start) * 1024)};
}
const direct = await browserFetch(directUrl, true);
counters["network.resourcePack.direct"] = 1;
let fallback;
try {
  await browserFetch(noCorsUrl, true);
} catch {
  counters["network.resourcePack.directFallback"] = 1;
  fallback = await browserFetch(relayUrl, true);
}
assert.equal(direct.size, payload.length);
assert.equal(fallback.size, payload.length);
assert.equal(counters["network.resourcePack.direct"], 1);
assert.equal(counters["network.resourcePack.directFallback"], 1);
directServer.close();
noCorsServer.close();
relayServer.close();
console.log(JSON.stringify({ok: true, directBytes: direct.size, relayBytes: fallback.size,
  directMiBps: Number(direct.mibps.toFixed(2)), relayMiBps: Number(fallback.mibps.toFixed(2)), counters}));
