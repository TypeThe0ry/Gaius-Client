#!/usr/bin/env node
// Attribute WebAssembly code-section body bytes to TeaVM function names.
//
//   node wasm-attribution.mjs --module classes.wasm --symbols classes.wasm.symbols.wasm
//       [--json report.json] [--top N]
//
// The shipped module has its name section in a sidecar symbol module.  This report joins
// the sidecar's function indices to the module's defined function bodies and aggregates
// the body bytes by class, package, and a small set of review categories.  It deliberately
// reports code-section attribution only: type/global/data sections are not owned by one
// Java class and are kept as separate totals rather than guessed allocations.
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { functionBodies, sections } from "./wasm-binary.mjs";
import { readSymbols } from "./wasm-symbols.mjs";

function sha256(bytes) { return createHash("sha256").update(bytes).digest("hex"); }

function category(name) {
  if (name.startsWith("teavm@") || name.startsWith("org.teavm.")) return "TeaVM runtime";
  if (/datafix|DataFix|Schema|FileFixerUpper/.test(name)) return "DataFixer";
  if (/jdk\.jfr|JvmProfiler|JfrCommand|\.jfr\./.test(name)) return "JFR";
  if (/telemetry|Telemetry|Browser.*Scheduler|GpuBufferPool/.test(name)) return "browser telemetry";
  if (/org\.lwjgl\.|lwjgl|GLFW|OpenGL|OpenAL|Shaderc|SPVC|FreeType|STB/.test(name)) return "LWJGL/browser graphics";
  if (/slf4j|log4j|Logger|Logging/.test(name)) return "logging";
  if (/^net\.minecraft\.server\.|ServerLevel|MinecraftServer|IntegratedServer/.test(name)) return "integrated server";
  if (/^net\.minecraft\.client\./.test(name)) return "Minecraft client";
  if (/^net\.minecraft\./.test(name)) return "Minecraft shared";
  if (/^java\.|^javax\.|^jdk\./.test(name)) return "Java class library";
  return "other libraries";
}

function add(map, key, bytes, count = 1) {
  const row = map.get(key) || { name: key, bytes: 0, functions: 0 };
  row.bytes += bytes; row.functions += count; map.set(key, row);
}

function sorted(map) { return [...map.values()].sort((a, b) => b.bytes - a.bytes || a.name.localeCompare(b.name)); }

export function attribute(moduleBytes, symbolBytes) {
  const moduleSections = sections(moduleBytes);
  const bodies = functionBodies(moduleBytes, moduleSections);
  const symbols = readSymbols(symbolBytes);
  const digest = sha256(moduleBytes);
  if (symbols.info.sha256 !== digest) {
    throw new Error(`module / symbols sha256 mismatch: ${digest} != ${symbols.info.sha256}`);
  }
  const classes = new Map(), packages = new Map(), categories = new Map();
  let namedFunctions = 0, unnamedFunctions = 0, codeBodyBytes = 0;
  for (let i = 0; i < bodies.length; i++) {
    const functionIndex = symbols.imported + i;
    const name = symbols.nameOf(functionIndex) || `<unnamed function ${functionIndex}>`;
    const bodyBytes = bodies[i].size;
    codeBodyBytes += bodyBytes;
    if (name[0] === "<") unnamedFunctions++; else namedFunctions++;
    const owner = name.includes("::") ? name.slice(0, name.indexOf("::")) : name;
    const packageName = owner.includes(".") ? owner.slice(0, owner.lastIndexOf(".")) : owner;
    add(classes, owner, bodyBytes);
    add(packages, packageName, bodyBytes);
    add(categories, category(name), bodyBytes);
  }
  const code = moduleSections.find((s) => s.id === 10);
  const data = moduleSections.find((s) => s.id === 11);
  return {
    module: { bytes: moduleBytes.length, sha256: digest },
    symbols: { file: symbols.info.file, bytes: symbolBytes.length, definedFunctions: bodies.length,
      importedFunctions: symbols.imported, namedFunctions, unnamedFunctions },
    sections: { codeBytes: code ? code.end - code.headerStart : 0, codeBodyBytes,
      dataBytes: data ? data.end - data.headerStart : 0 },
    categories: sorted(categories), packages: sorted(packages), classes: sorted(classes),
  };
}

function usage() {
  console.error("usage: node wasm-attribution.mjs --module MODULE --symbols SYMBOLS [--json OUT] [--top N]");
  return 2;
}

function main(argv) {
  let modulePath, symbolsPath, jsonPath, top = 30;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--module") modulePath = argv[++i];
    else if (argv[i] === "--symbols") symbolsPath = argv[++i];
    else if (argv[i] === "--json") jsonPath = argv[++i];
    else if (argv[i] === "--top") top = Number(argv[++i]);
    else return usage();
  }
  if (!modulePath || !symbolsPath || !Number.isInteger(top) || top < 1) return usage();
  const report = attribute(new Uint8Array(readFileSync(modulePath)), new Uint8Array(readFileSync(symbolsPath)));
  console.log(`${modulePath}: ${report.module.bytes} bytes; code bodies ${report.sections.codeBodyBytes}; `
    + `${report.symbols.namedFunctions}/${report.symbols.definedFunctions} named`);
  for (const [label, rows] of [["category", report.categories], ["package", report.packages], ["class", report.classes]]) {
    console.log(`\nTop ${label} contributors:`);
    for (const row of rows.slice(0, top)) console.log(`  ${row.bytes}\t${row.functions}\t${row.name}`);
  }
  if (jsonPath) writeFileSync(jsonPath, JSON.stringify({ module: resolve(modulePath), symbols: resolve(symbolsPath), ...report }, null, 2) + "\n");
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (error) { console.error(`wasm-attribution: ${error.stack || error}`); process.exitCode = 2; }
}
