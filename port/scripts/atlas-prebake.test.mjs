// The page side of the atlas prebake: gaius-boot.js's GAIUSAP1 envelope check and the JS bodies
// of BrowserAtlasPrebakeJs, run on their production source text.
//
//   node --test port/scripts/atlas-prebake.test.mjs
//   ATLAS_PREBAKE_PAYLOAD=<gaius-atlas-prebake.bin.gz> ATLAS_PREBAKE_PROFILE=<profile> node --test ...
//     also checks a payload built by port/tools/atlasprebake/atlasprebake.py
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";
import {gzipSync} from "node:zlib";

const boot = readFileSync(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
const java = readFileSync(new URL("../src/target-wasmgc/java/dev/gaius/browser/BrowserAtlasPrebakeJs.java",
  import.meta.url), "utf8");

function functionSource(name) {
  const start = boot.indexOf(name.startsWith("async ") ? `  ${name}(` : `  function ${name}(`);
  assert.ok(start >= 0, `gaius-boot.js has ${name}`);
  let depth = 0;
  for (let index = boot.indexOf("{", start); index < boot.length; index++) {
    if (boot[index] === "{") depth++;
    if (boot[index] === "}" && --depth === 0) return boot.slice(start, index + 1);
  }
  throw new Error(`unterminated ${name}`);
}

const parserSource = functionSource("gunzipStream") + "\n" + functionSource("async function parseAtlasPrebake")
  + "\nglobalThis.parseAtlasPrebake = parseAtlasPrebake;";

function parser(profile) {
  const context = {
    Uint8Array, DataView, Error, Promise, TextDecoder, Response, Blob, DecompressionStream, crypto,
    __gaiusProfileId: profile,
  };
  context.global = context;
  context.globalThis = context;
  context.ATLAS_PREBAKE_MAGIC = boot.match(/const ATLAS_PREBAKE_MAGIC = "([A-Z0-9]+)";/)[1];
  vm.runInNewContext(parserSource, context);
  return context.parseAtlasPrebake;
}

function payload(profile, body, {magic = "GAIUSAP1", version = 1, digest} = {}) {
  const meta = Buffer.from("{}", "ascii");
  const name = Buffer.from(profile, "ascii");
  const headerLength = 52 + 2 + name.length + 2 + meta.length;
  const header = Buffer.alloc(headerLength);
  header.write(magic, 0, "ascii");
  header.writeUInt32LE(version, 8);
  header.writeUInt32LE(headerLength, 12);
  header.writeUInt32LE(body.length, 16);
  (digest || createHash("sha256").update(body).digest()).copy(header, 20);
  header.writeUInt16LE(name.length, 52);
  name.copy(header, 54);
  header.writeUInt16LE(meta.length, 54 + name.length);
  meta.copy(header, 56 + name.length);
  return Buffer.concat([header, body]);
}

const body = Buffer.from(Array.from({length: 4096}, (_, index) => (index * 31 + 7) & 0xff));

test("a well-formed payload of the page's profile is verified", async () => {
  const result = await parser("profile-a")(gzipSync(payload("profile-a", body)));
  assert.equal(result.status, "verified");
  assert.equal(result.profile, "profile-a");
  assert.equal(result.bytes.length, payload("profile-a", body).length);
});

test("a changed body byte, magic, version, length or profile is not verified", async () => {
  const changed = payload("profile-a", body);
  changed[changed.length - 1] ^= 1;
  assert.equal((await parser("profile-a")(gzipSync(changed))).status, "mismatch");
  assert.equal((await parser("profile-a")(gzipSync(changed))).bytes, null);
  await assert.rejects(parser("profile-a")(gzipSync(payload("profile-a", body, {magic: "GAIUSAP2"}))), /magic/);
  await assert.rejects(parser("profile-a")(gzipSync(payload("profile-a", body, {version: 2}))), /version/);
  await assert.rejects(parser("profile-a")(gzipSync(payload("profile-a", body).subarray(0, 4000))), /length/);
  await assert.rejects(parser("profile-a")(gzipSync(payload("profile-b", body))), /profile mismatch/);
  await assert.rejects(parser("profile-a")(gzipSync(Buffer.alloc(20))), /truncated/);
});

function jsBody(name) {
  const body = String.raw`((?:(?!""")[\s\S])*)`;
  const pattern = new RegExp(String.raw`script = """` + body + String.raw`"""\)\s*private static native \w+ ` + name + String.raw`\(`);
  const match = pattern.exec(java);
  assert.ok(match, `BrowserAtlasPrebakeJs.${name}`);
  const params = new RegExp(String.raw`params = \{([^}]*)\}, script = """` + body
    + String.raw`"""\)\s*private static native \w+ ` + name + String.raw`\(`).exec(java);
  const names = params ? params[1].split(",").map(value => value.trim().replace(/"/g, "")) : [];
  return (context, ...values) => vm.runInNewContext(`(function(${names.join(",")}){${match[1]}})(${
    values.map(value => JSON.stringify(value)).join(",")})`, context);
}

test("Java sees the bytes only for a verified payload of its profile", () => {
  const length = jsBody("lengthJs");
  const bytes = new Uint8Array(10);
  assert.equal(length({__gaiusAtlasPrebake: {status: "verified", profile: "profile-a", bytes}, __gaiusProfileId: "profile-a",
    Uint8Array}), 10);
  for (const status of ["absent", "loading", "invalid", "unverified", "mismatch"]) {
    assert.equal(length({__gaiusAtlasPrebake: {status, profile: "profile-a", bytes}, __gaiusProfileId: "profile-a",
      Uint8Array}), -1);
  }
  assert.equal(length({__gaiusAtlasPrebake: {status: "verified", profile: "profile-b", bytes}, __gaiusProfileId: "profile-a",
    Uint8Array}), -1);
  assert.equal(length({__gaiusAtlasPrebake: {status: "verified", profile: "profile-a", bytes}, Uint8Array}), -1);
  assert.equal(length({Uint8Array}), -1);
  const mode = jsBody("modeJs");
  assert.equal(mode({__gaiusAtlasPrebake: {mode: 2}}), 2);
  assert.equal(mode({}), 0);
});

test("copies and comparisons stay inside the payload", () => {
  const copy = jsBody("copyToJs");
  const equal = jsBody("equalsAtJs");
  const memory = new Uint8Array(64);
  const context = {
    __gaiusAtlasPrebake: {bytes: Uint8Array.from([1, 2, 3, 4, 5, 6, 7, 8])},
    __gaiusLinear: {refresh() { return {u8: memory}; }},
  };
  context.globalThis = context;
  assert.equal(copy(context, 2, 4, 16), true);
  assert.deepEqual([...memory.subarray(16, 20)], [3, 4, 5, 6]);
  assert.equal(equal(context, 2, 4, 16), true);
  memory[18] ^= 1;
  assert.equal(equal(context, 2, 4, 16), false);
  assert.equal(copy(context, 6, 4, 0), false);
  assert.equal(copy(context, -1, 2, 0), false);
  assert.equal(equal(context, 7, 2, 0), false);
});

test("the built payload, when given, is a verified payload of its profile", {skip: !process.env.ATLAS_PREBAKE_PAYLOAD || !process.env.ATLAS_PREBAKE_PROFILE},
  async () => {
    const profile = process.env.ATLAS_PREBAKE_PROFILE;
    const result = await parser(profile)(readFileSync(process.env.ATLAS_PREBAKE_PAYLOAD));
    assert.equal(result.status, "verified");
    assert.equal(result.profile, profile);
  });
