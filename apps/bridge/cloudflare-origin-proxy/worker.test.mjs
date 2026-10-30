import assert from "node:assert/strict";

import { proxyRequest } from "./worker.js";

let upstreamRequest = null;
const upstreamFetch = async (request) => {
  upstreamRequest = request;
  return new Response(JSON.stringify({ ok: true }), {
    headers: { "Content-Type": "application/json" },
  });
};

const pagesManifest = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/relay-node/v1",
  { headers: { Origin: "https://typethe0ry.github.io" } },
), upstreamFetch);
assert.equal(pagesManifest.status, 200);
assert.equal(pagesManifest.headers.get("Access-Control-Allow-Origin"),
  "https://typethe0ry.github.io");
assert.equal(pagesManifest.headers.get("X-Gaius-Relay-Proxy"), "pages-origin-v1");
assert.equal(upstreamRequest.url, "https://ellan.site/relay-node/v1");
assert.equal(upstreamRequest.headers.get("Origin"), "null");

const pagesTunnel = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/tunnel",
  {
    headers: {
      Origin: "https://typethe0ry.github.io",
      Upgrade: "websocket",
    },
  },
), upstreamFetch);
assert.equal(pagesTunnel.status, 200);
assert.equal(upstreamRequest.url, "https://ellan.site/tunnel");
assert.equal(upstreamRequest.headers.get("Origin"), "null");
assert.equal(upstreamRequest.headers.get("Upgrade"), "websocket");

const pagesResourcePack = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/proxy/resource-pack?url=https%3A%2F%2Fexample.test%2Fpack.zip&stream=1",
  {
    headers: {
      Origin: "https://typethe0ry.github.io",
      "X-Gaius-Resource-Pack": "1",
      Range: "bytes=0-15",
    },
  },
), async (request) => {
  upstreamRequest = request;
  return new Response("pack-bytes", {
    status: 206,
    headers: { "Content-Length": "10", "Content-Range": "bytes 0-9/10" },
  });
});
assert.equal(pagesResourcePack.status, 206);
assert.equal(pagesResourcePack.headers.get("Access-Control-Allow-Origin"),
  "https://typethe0ry.github.io");
assert.equal(pagesResourcePack.headers.get("X-Gaius-Relay-Proxy"), "pages-origin-v1");
assert.equal(upstreamRequest.url,
  "https://ellan.site/proxy/resource-pack?url=https%3A%2F%2Fexample.test%2Fpack.zip&stream=1");
assert.equal(upstreamRequest.headers.get("Origin"), "null");
assert.equal(upstreamRequest.headers.get("Range"), "bytes=0-15");
assert.equal(upstreamRequest.headers.get("X-Gaius-Resource-Pack"), "1");

const fileTunnel = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/tunnel",
  { headers: { Origin: "null", Upgrade: "websocket" } },
), upstreamFetch);
assert.equal(fileTunnel.status, 200);

const rejected = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/tunnel",
  { headers: { Origin: "https://attacker.example", Upgrade: "websocket" } },
), upstreamFetch);
assert.equal(rejected.status, 403);

const missing = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/private",
), upstreamFetch);
assert.equal(missing.status, 404);

const preflight = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/relay-node/v1",
  { method: "OPTIONS", headers: { Origin: "https://typethe0ry.github.io" } },
), upstreamFetch);
assert.equal(preflight.status, 204);
assert.equal(preflight.headers.get("Access-Control-Allow-Origin"),
  "https://typethe0ry.github.io");

console.log("Cloudflare Pages Relay origin proxy tests passed");
const authenticatedPreflight = await proxyRequest(new Request(
  "https://pages-relay.ellan.site/relay-node/v1",
  { method: "OPTIONS", headers: {
    Origin: "https://typethe0ry.github.io",
    "Access-Control-Request-Method": "GET",
    "Access-Control-Request-Headers": "authorization,x-gaius-bridge-token",
  } },
), upstreamFetch);
assert.equal(authenticatedPreflight.status, 204);
const allowedRequestHeaders = authenticatedPreflight.headers.get("Access-Control-Allow-Headers").toLowerCase();
assert.match(allowedRequestHeaders, /authorization/);
assert.match(allowedRequestHeaders, /x-gaius-bridge-token/);
