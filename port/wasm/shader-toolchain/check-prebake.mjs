#!/usr/bin/env node
// Round trip of the baked shaderc result set (api.exportCache / api.primeCache) on the built modules.
//
//   node port/wasm/shader-toolchain/check-prebake.mjs --toolchain DIR
//
// DIR holds gaius-shaderc.{js,wasm} and gaius-spvc.{js,wasm} (build-wasm-shader-toolchain.sh output).
// A compiled job is exported, a fresh toolchain of the same version is primed with it and serves the
// job from the set with the same bytes and without running shaderc; a set of another toolchain
// version, a truncated set and a job with changed inputs are not served from it.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const at = process.argv.indexOf("--toolchain");
if (at < 0 || !process.argv[at + 1]) {
  console.error("usage: check-prebake.mjs --toolchain DIR");
  process.exit(2);
}
const dir = resolve(process.argv[at + 1]);
const {createToolchain} = require(join(here, "loader/gaius-shader-toolchain.js"));
const modules = async (version) => createToolchain({
  shadercFactory: require(join(dir, "gaius-shaderc.js")), spvcFactory: require(join(dir, "gaius-spvc.js")),
  shadercModule: await WebAssembly.compile(readFileSync(join(dir, "gaius-shaderc.wasm"))),
  spvcModule: await WebAssembly.compile(readFileSync(join(dir, "gaius-spvc.wasm"))), cacheVersion: version});
const utf8 = (text) => new TextEncoder().encode(text);
const source = (extra = "") => utf8("#version 450\nlayout(location = 0) in vec3 Position;\nlayout(location = 0) out vec3 color;\n"
  + extra + "void main() {\n    color = Position;\n    gl_Position = vec4(Position, 1.0);\n}\n");
const compile = (api, text) => {
  const id = api.shadercBegin();
  try {
    api.shadercOption(id, 1, 0, 4202496);
    api.shadercOption(id, 2, 1, 0);
    api.shadercOption(id, 4, 0, 0);
    api.shadercOption(id, 5, 0, 0);
    const status = api.shadercCompile(id, text, 0, utf8("prebake.vsh"), utf8("main"));
    return {status, length: api.shadercOutputLength(id)};
  } finally {
    api.shadercEnd(id);
  }
};

const first = await modules("prebake-test");
const live = compile(first, source());
assert.equal(live.status, 0);
assert.ok(live.length > 0);
const baked = first.exportCache();
assert.equal(first.stats().cache.entries, 1);

const second = await modules("prebake-test");
assert.equal(second.primeCache(baked), 1, "a set of the same version primes its entries");
assert.equal(second.primeCache(baked), 0, "priming twice adds nothing");
const hit = compile(second, source());
assert.deepEqual(hit, live, "the primed job returns the live result");
assert.equal(second.stats().cache.hits, 1);
assert.equal(second.stats().cache.primed, 1);
const changed = compile(second, source("// changed input\n"));
assert.equal(changed.status, 0);
assert.equal(second.stats().cache.hits, 1, "a job with other inputs compiles instead of hitting");

const other = await modules("another-version");
assert.equal(other.primeCache(baked), 0, "another toolchain version is ignored");
const cut = await modules("prebake-test");
assert.equal(cut.primeCache(baked.subarray(0, baked.length - 5)), 0, "a truncated set adds no entry");
assert.equal(cut.primeCache(new Uint8Array([1, 2, 3])), 0, "garbage adds nothing");
console.log(`check-prebake: pass (${live.length} bytes of SPIR-V round-tripped)`);
