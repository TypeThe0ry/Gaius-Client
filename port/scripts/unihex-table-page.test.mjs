// The page side of the unihex glyph table: gaius-boot.js parses gaius-unihex.bin and hashes each
// listed zip in the archive the vanilla pack reads; the JS bodies of
// dev.gaius.browser.UnihexGlyphTable read it. Both are exercised from their production sources.
//
//   node --test port/scripts/unihex-table-page.test.mjs
//   UNIHEX_TABLE_FILE=<gaius-unihex.bin> UNIHEX_TABLE_ZIPS=<dir with the zips> node --test ...
//     also checks a table that UnihexTableBuilder wrote against the zips it was built from.
import assert from "node:assert/strict";
import {createHash, webcrypto} from "node:crypto";
import {readFileSync, existsSync} from "node:fs";
import {basename, join} from "node:path";
import test from "node:test";
import vm from "node:vm";

const boot = readFileSync(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
const java = readFileSync(new URL("../src/main/java/dev/gaius/browser/UnihexGlyphTable.java", import.meta.url), "utf8");

function functionSource(name) {
  const start = boot.indexOf(`function ${name}(`);
  assert.ok(start >= 0, `gaius-boot.js defines ${name}`);
  const asyncStart = boot.lastIndexOf("async ", start) === start - 6 ? start - 6 : start;
  let depth = 0;
  for (let index = boot.indexOf("{", start); index < boot.length; index++) {
    if (boot[index] === "{") depth++;
    else if (boot[index] === "}" && --depth === 0) return boot.slice(asyncStart, index + 1);
  }
  throw new Error(`unbalanced ${name}`);
}

const constants = /const UNIHEX_TABLE_MAGIC = "[^"]+";/.exec(boot)[0];
const pageCode = [constants, functionSource("parseUnihexTable"), functionSource("archiveEntry"),
  functionSource("verifyUnihexTable")].join("\n");

function page(globals = {}) {
  const context = vm.createContext({TextDecoder, Uint8Array, DataView, Array, Object, Error, ...globals});
  context.global = context;
  context.crypto = webcrypto;
  vm.runInContext(pageCode, context);
  return context;
}

function jsBody(name) {
  const declaration = new RegExp(`private static native \\S+ ${name}\\(([^)]*)\\)`).exec(java);
  assert.ok(declaration, `UnihexGlyphTable declares ${name}`);
  const open = java.lastIndexOf('script = """', declaration.index);
  const close = java.lastIndexOf('"""', declaration.index);
  assert.ok(open >= 0 && close > open, `UnihexGlyphTable has the ${name} body`);
  const body = java.slice(open + 'script = """'.length, close);
  const params = declaration[1].split(",").map((part) => part.trim().split(/\s+/).pop()).filter(Boolean);
  return (context, ...args) => vm.runInContext(`(function(${params.join(",")}){${body}})`, context)(...args);
}

/** Plain data out of the page's realm (deepEqual compares prototypes). */
const plain = (value) => JSON.parse(JSON.stringify(value));

const sha256 = (bytes) => new Uint8Array(createHash("sha256").update(bytes).digest());

/** A table in the documented GAIUSUH1 layout (UnihexTableBuilder.encode), without glyph data. */
function table(zips) {
  const paths = zips.map((zip) => Buffer.from(zip.path, "utf8"));
  let header = 20;
  for (const path of paths) header = ((header + 2 + path.length + 3) & ~3) + 56;
  const bytes = new Uint8Array(header);
  const view = new DataView(bytes.buffer);
  bytes.set(Buffer.from("GAIUSUH1", "latin1"), 0);
  view.setUint32(8, 1, true);
  view.setUint32(12, zips.length, true);
  view.setUint32(16, header, true);
  let cursor = 20;
  zips.forEach((zip, index) => {
    view.setUint16(cursor, paths[index].length, true);
    bytes.set(paths[index], cursor + 2);
    cursor = (cursor + 2 + paths[index].length + 3) & ~3;
    view.setUint32(cursor, zip.bytes.length, true);
    bytes.set(sha256(zip.bytes), cursor + 4);
    cursor += 56;
  });
  return bytes;
}

function archive(files) {
  const index = {};
  const parts = [];
  let offset = 0;
  for (const [name, data] of Object.entries(files)) {
    index[name] = [offset, data.length];
    parts.push(data);
    offset += data.length;
  }
  const header = new Uint8Array(12);
  const bytes = new Uint8Array(header.length + offset);
  let cursor = header.length;
  for (const part of parts) {
    bytes.set(part, cursor);
    cursor += part.length;
  }
  return {bytes, index, dataOffset: header.length};
}

const A = new Uint8Array([1, 2, 3, 4, 5]);
const B = new Uint8Array([9, 8, 7]);
const PATH_A = "assets/minecraft/font/unifont.zip";
const PATH_B = "assets/minecraft/font/unifont_jp.zip";

test("the page parses the table header the builder writes", () => {
  const context = page();
  const zips = context.parseUnihexTable(table([{path: PATH_A, bytes: A}, {path: PATH_B, bytes: B}]));
  assert.deepEqual(plain(zips.map((zip) => [zip.path, zip.length, zip.verified])), [[PATH_A, 5, false], [PATH_B, 3, false]]);
  assert.deepEqual(Array.from(zips[0].digest), Array.from(sha256(A)));
  const bad = table([{path: PATH_A, bytes: A}]);
  bad[0] ^= 1;
  assert.throws(() => context.parseUnihexTable(bad), /magic/);
  const version = table([{path: PATH_A, bytes: A}]);
  version[8] = 2;
  assert.throws(() => context.parseUnihexTable(version), /version/);
  assert.throws(() => context.parseUnihexTable(table([{path: PATH_A, bytes: A}]).subarray(0, 40)), /truncated/);
});

test("only zips whose archive bytes hash to the table's are verified", async () => {
  const other = new Uint8Array([1, 2, 3, 4, 6]);
  const context = page({__gaiusVanillaAssets: archive({[PATH_A]: A, [PATH_B]: other})});
  const state = {zips: context.parseUnihexTable(table([{path: PATH_A, bytes: A}, {path: PATH_B, bytes: B},
    {path: "assets/minecraft/font/missing.zip", bytes: B}]))};
  assert.equal(await context.verifyUnihexTable(state), 1);
  assert.deepEqual(plain(state.zips.map((zip) => zip.verified)), [true, false, false]);
});

test("the Java bodies read the verified table and re-check the archive range", async () => {
  const root = archive({[PATH_A]: A, [PATH_B]: B});
  const context = page({__gaiusVanillaAssets: root});
  const bytes = table([{path: PATH_A, bytes: A}, {path: PATH_B, bytes: B}]);
  const state = {status: "verifying", bytes, zips: context.parseUnihexTable(bytes)};
  await context.verifyUnihexTable(state);
  state.status = "verified";
  const status = jsBody("tableStatus");
  const length = jsBody("tableLength");
  const copy = jsBody("copyTable");
  const verified = jsBody("tableZipVerified");
  assert.equal(status(context), "absent");
  context.__gaiusUnihexTable = {status: "loading"};
  assert.equal(status(context), "loading");
  assert.equal(verified(context, 0), false);
  context.__gaiusUnihexTable = state;
  assert.equal(status(context), "verified");
  assert.equal(length(context), bytes.length);
  const target = new Int8Array(8);
  assert.equal(copy(context, 0, target), true);
  assert.equal(Buffer.from(target.buffer).toString("latin1"), "GAIUSUH1");
  assert.equal(copy(context, bytes.length - 4, new Int8Array(8)), false);
  assert.equal(copy(context, -1, new Int8Array(1)), false);
  assert.equal(verified(context, 0), true);
  assert.equal(verified(context, 1), true);
  assert.equal(verified(context, 2), false);
  // A merged sound pack copies the bytes but keeps the ranges of the core entries.
  root.bytes = new Uint8Array([...root.bytes, 42, 42]);
  assert.equal(verified(context, 0), true);
  // An index entry that now points elsewhere is no longer the hashed bytes.
  root.index[PATH_A] = [root.bytes.length - root.dataOffset - 2, 2];
  assert.equal(verified(context, 0), false);
  // A sidecar pack is read only after the core pack, and a different pack object is not the hashed one.
  delete root.index[PATH_B];
  context.__gaiusVanillaSounds = archive({[PATH_B]: B});
  assert.equal(verified(context, 1), false);
  const publish = jsBody("publishCounters");
  publish(context, 2, 1, "zip-unverified");
  publish(context, 3, 1, "");
  assert.deepEqual({hits: context.__gaiusUnihexLazy.hits, fallbacks: context.__gaiusUnihexLazy.fallbacks,
    table: context.__gaiusUnihexLazy.table, reasons: plain(context.__gaiusUnihexLazy.reasons)},
  {hits: 3, fallbacks: 1, table: "verified", reasons: ["zip-unverified"]});
});

const builtTable = process.env.UNIHEX_TABLE_FILE;
const builtZips = process.env.UNIHEX_TABLE_ZIPS;
test("a table from UnihexTableBuilder verifies against the zips it was built from",
  {skip: !(builtTable && builtZips && existsSync(builtTable)) && "set UNIHEX_TABLE_FILE and UNIHEX_TABLE_ZIPS"},
  async () => {
    const bytes = new Uint8Array(readFileSync(builtTable));
    const probe = page();
    const zips = probe.parseUnihexTable(bytes);
    assert.ok(zips.length > 0);
    const files = Object.fromEntries(zips.map((zip) => [zip.path, new Uint8Array(readFileSync(join(builtZips, basename(zip.path))))]));
    const context = page({__gaiusVanillaAssets: archive(files)});
    const state = {zips: context.parseUnihexTable(bytes)};
    assert.equal(await context.verifyUnihexTable(state), zips.length);
  });
