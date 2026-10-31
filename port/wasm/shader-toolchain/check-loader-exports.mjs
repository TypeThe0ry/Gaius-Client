#!/usr/bin/env node
// Keeps the browser shader toolchain's loader and its module exports in step.
//
//  static  Every shaderc_*/spvc_* function the loader calls (M._name) is in the export list of
//          port/scripts/build-wasm-shader-toolchain.sh, every shaderc option setter in that list
//          has a loader option code (OPTION_EXPORTS), and every option code applyOption handles
//          names an exported setter. A missing export is the failure this guards against: without
//          shaderc_compile_options_set_auto_map_locations the blaze3d profiles (26.2, 1.21.11)
//          cannot compile a single stage.
//  modules (with --toolchain DIR, the build script's output) Starts the loader on those modules in
//          Node: a stage with explicit locations compiles with GlslCompiler's other options; one
//          without them is refused unless the auto map option is set, which compiles it when the
//          modules export the setter and otherwise fails the job with a message naming the export
//          while the shaderc instance stays in service.
//
// usage: node port/wasm/shader-toolchain/check-loader-exports.mjs [--toolchain DIR]
// Exit status: 0 pass, 1 failure, 2 usage or environment error.

import {existsSync, readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const require = createRequire(import.meta.url);
const loaderFile = join(here, "loader/gaius-shader-toolchain.js");
const buildScript = join(root, "port/scripts/build-wasm-shader-toolchain.sh");
const args = process.argv.slice(2);
let toolchain = null;
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--toolchain" && i + 1 < args.length) toolchain = resolve(args[++i]);
  else {
    console.error(`check-loader-exports: unknown argument ${args[i]}`);
    process.exit(2);
  }
}

const failures = [];
const fail = (message) => {
  failures.push(message);
  console.log(`FAIL ${message}`);
};

// ---- static ----------------------------------------------------------------------------------
const script = readFileSync(buildScript, "utf8");
const list = (name) => {
  const match = new RegExp(`^${name}=\\(([^)]*)\\)`, "m").exec(script);
  if (!match) {
    console.error(`check-loader-exports: no ${name}=(...) list in ${buildScript}`);
    process.exit(2);
  }
  return new Set(match[1].split(/\s+/).filter(Boolean));
};
const exports = {shaderc: list("shaderc_exports"), spvc: list("spvc_exports")};
const loader = readFileSync(loaderFile, "utf8");
const called = new Set([...loader.matchAll(/\bM\._((?:shaderc|spvc)_\w+)/g)].map((m) => m[1]));
for (const name of called) {
  const family = name.startsWith("shaderc_") ? "shaderc" : "spvc";
  if (!exports[family].has(name)) fail(`the loader calls ${name}, which ${family}_exports does not export`);
}
const optionExports = /var OPTION_EXPORTS = \{([^}]*)\}/.exec(loader);
if (!optionExports) fail("the loader has no OPTION_EXPORTS table");
const codes = new Map([...(optionExports ? optionExports[1] : "").matchAll(/(\d+):\s*"_(\w+)"/g)]
  .map((m) => [Number(m[1]), m[2]]));
for (const [code, name] of codes) {
  if (!exports.shaderc.has(name)) fail(`option ${code} needs ${name}, which shaderc_exports does not export`);
  if (!called.has(name)) fail(`option ${code} names ${name}, which applyOption never calls`);
}
const setters = [...exports.shaderc].filter((name) => /^shaderc_compile_options_set_/.test(name)
  && !/include_callbacks/.test(name));
for (const name of setters) {
  if (![...codes.values()].includes(name)) fail(`${name} is exported but has no loader option code`);
}
const optionConstants = new Map([...loader.matchAll(/var (OPTION_\w+) = (\d+);/g)].map((m) => [m[1], Number(m[2])]));
for (const [constant, code] of optionConstants) {
  if (!codes.has(code)) fail(`${constant} (${code}) has no OPTION_EXPORTS entry`);
  if (!new RegExp(`case ${constant}:`).test(loader)) fail(`applyOption does not handle ${constant}`);
}
console.log(`static: ${called.size} calls, ${exports.shaderc.size} shaderc + ${exports.spvc.size} spvc exports, ` +
  `${codes.size} option codes`);

// ---- modules ---------------------------------------------------------------------------------
if (toolchain) {
  for (const file of ["gaius-shaderc.js", "gaius-shaderc.wasm", "gaius-spvc.js", "gaius-spvc.wasm"]) {
    if (!existsSync(join(toolchain, file))) {
      console.error(`check-loader-exports: missing ${join(toolchain, file)}`);
      process.exit(2);
    }
  }
  const {createToolchain} = require(loaderFile);
  const api = await createToolchain({
    shadercFactory: require(join(toolchain, "gaius-shaderc.js")),
    spvcFactory: require(join(toolchain, "gaius-spvc.js")),
    shadercModule: await WebAssembly.compile(readFileSync(join(toolchain, "gaius-shaderc.wasm"))),
    spvcModule: await WebAssembly.compile(readFileSync(join(toolchain, "gaius-spvc.wasm"))),
    cacheVersion: "check-loader-exports"});
  api.setCacheEnabled(false);
  const utf8 = (text) => new TextEncoder().encode(text);
  const body = "void main() {\n    color = Position;\n    gl_Position = vec4(Position, 1.0);\n}\n";
  const source = utf8("#version 330\nin vec3 Position;\nout vec3 color;\n" + body);
  // Output locations need GLSL 410 (renderpearl's shaders declare them; 26.2's do not).
  const located = utf8("#version 450\nlayout(location = 0) in vec3 Position;\nlayout(location = 0) out vec3 color;\n" + body);
  // GlslCompiler's options: Vulkan 1.2, auto bind uniforms, [auto map locations], debug info, -O0.
  const compile = (autoMap, text = source) => {
    const id = api.shadercBegin();
    try {
      api.shadercOption(id, 1, 0, 4202496);
      api.shadercOption(id, 2, 1, 0);
      if (autoMap) api.shadercOption(id, 6, 1, 0);
      api.shadercOption(id, 4, 0, 0);
      api.shadercOption(id, 5, 0, 0);
      const status = api.shadercCompile(id, text, 0, utf8("check.vsh"), utf8("main"));
      return {status, bytes: api.shadercOutputLength(id), error: api.shadercErrorMessage(id)};
    } finally {
      api.shadercEnd(id);
    }
  };
  const plain = compile(false, located);
  if (plain.status !== 0 || plain.bytes === 0) fail(`a located stage should compile: ${JSON.stringify(plain)}`);
  const without = compile(false);
  if (without.status === 0 || !/location/i.test(without.error)) {
    fail(`without auto map locations shaderc should refuse unlocated inputs: ${JSON.stringify(without)}`);
  }
  const supported = api.shadercSupportsOption(6);
  const generation = api.stats().shaderc.generation;
  const withOption = compile(true);
  if (supported) {
    if (withOption.status !== 0 || withOption.bytes === 0) fail(`auto map locations: ${JSON.stringify(withOption)}`);
  } else {
    if (withOption.status === 0 || !/does not export shaderc_compile_options_set_auto_map_locations/.test(withOption.error)) {
      fail(`modules without the export: ${JSON.stringify(withOption)}`);
    }
    if (api.stats().shaderc.generation !== generation) fail("an unsupported option retired the shaderc instance");
    if (compile(false).error !== without.error) fail("the shaderc instance changed behaviour after an unsupported option");
  }
  console.log(`modules: auto map locations ${supported ? "exported, compiles" : "not exported (old modules), refused cleanly"}`);
}

console.log(failures.length ? `check-loader-exports: ${failures.length} failures` : "check-loader-exports: pass");
process.exit(failures.length ? 1 : 0);
