#!/usr/bin/env node
// Size report of a TeaVM WebAssembly GC module (v0.4.0 WP8).
//
//   node wasm-size.mjs [--json out.json] [--no-compress] [--leb] [--compare <file.br>]... <file.wasm>
//
// Prints the section table and, for the module as built and for the module without its
// name section and TeaVM debug sections, the raw, gzip -9 and brotli sizes.  Brotli uses
// the settings of the dist pipeline: port/scripts/compress-dist.sh runs `brotli -q 11`,
// whose window is 24 bits (the 17.1 MB classes.js.br of the 26.3 release is that output;
// its node fallback compress-brotli.mjs keeps zlib's 22 bits, about 1.5% larger), so the
// number is the one a release would serve.  --compare names reference .br files
// (classes.js.br of the JavaScript release) whose sizes are printed next to it.
//
// Also counts the bytes that non-minimal LEB128 encodings of function body sizes waste in
// the code section (TeaVM writes each size as a fixed 5-byte LEB); --leb also measures the
// module rewritten with the shortest encodings.
import { readFileSync, statSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { constants, brotliCompressSync, gzipSync } from "node:zlib";
import {
  encodeLeb, functionBodies, sections, withMinimalBodySizes, withoutCustomSections,
} from "./wasm-binary.mjs";

// Custom sections that only carry names or TeaVM debug information; the module runs
// without them (TeaVM reads teavm.memoryRequirements and teavm.imports, which are kept).
// The names are those TeaVM 0.15 writes for GAIUS_WASM_DEBUG_INFO_LOCATION=EMBEDDED
// (teavm_str, teavm_file, teavm_pkg, teavm_cls, teavm_mtd, teavm_line) and its other
// debug sections.
export function isSymbolSection(section) {
  return section.name === "name" || section.name === "sourceMappingURL"
    || /^teavm_(str|file|pkg|cls|mtd|line|var|type|cfg)\w*$/.test(section.name)
    || /^teavm\.debug/.test(section.name);
}

export function brotliSize(bytes) {
  return brotliCompressSync(bytes, {
    params: {
      [constants.BROTLI_PARAM_QUALITY]: 11,
      [constants.BROTLI_PARAM_LGWIN]: 24,
    },
  }).length;
}

export function gzipSize(bytes) {
  return gzipSync(bytes, { level: 9 }).length;
}

export function lebWaste(buf, sectionList = sections(buf)) {
  let wasted = 0;
  const bodies = functionBodies(buf, sectionList);
  for (const body of bodies) {
    wasted += (body.start - body.sizeStart) - encodeLeb(body.size).length;
  }
  return { functions: bodies.length, wastedBytes: wasted };
}

export function sizeReport(bytes, { compress = true, leb = false } = {}) {
  const sectionList = sections(bytes);
  const table = sectionList.map((s) => ({
    id: s.id,
    kind: s.kind,
    name: s.name ?? null,
    bytes: s.end - s.headerStart,
  }));
  const stripped = withoutCustomSections(bytes, isSymbolSection);
  const result = {
    bytes: bytes.length,
    sections: table,
    nameSectionBytes: table.filter((s) => s.name === "name").reduce((sum, s) => sum + s.bytes, 0),
    symbolSectionBytes: bytes.length - stripped.length,
    strippedBytes: stripped.length,
    codeSizeLeb: lebWaste(bytes, sectionList),
  };
  if (compress) {
    result.gzip = gzipSize(bytes);
    result.brotli = brotliSize(bytes);
    if (stripped.length !== bytes.length) {
      result.strippedGzip = gzipSize(stripped);
      result.strippedBrotli = brotliSize(stripped);
    } else {
      result.strippedGzip = result.gzip;
      result.strippedBrotli = result.brotli;
    }
  }
  if (leb) {
    // What the shortest LEB128 body sizes (a TeaVMCoreBrowserPatcher change) would save.
    const minimal = withMinimalBodySizes(stripped);
    result.minimalLeb = { bytes: minimal.length, valid: WebAssembly.validate(minimal) };
    if (compress) {
      result.minimalLeb.gzip = gzipSize(minimal);
      result.minimalLeb.brotli = brotliSize(minimal);
    }
  }
  return result;
}

function mb(value) {
  return `${(value / 1e6).toFixed(2)} MB`;
}

function main(argv) {
  let json = null;
  let compress = true;
  let leb = false;
  const compare = [];
  const files = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--json") json = argv[++i];
    else if (argv[i] === "--no-compress") compress = false;
    else if (argv[i] === "--leb") leb = true;
    else if (argv[i] === "--compare") compare.push(argv[++i]);
    else if (argv[i].startsWith("--")) throw new Error(`unknown option ${argv[i]}`);
    else files.push(argv[i]);
  }
  if (files.length !== 1) {
    console.error("usage: wasm-size.mjs [--json out.json] [--no-compress] [--leb] [--compare file.br]... <file.wasm>");
    return 2;
  }
  const bytes = new Uint8Array(readFileSync(files[0]));
  const report = { file: files[0], ...sizeReport(bytes, { compress, leb }) };
  report.compare = compare.map((file) => ({ file, bytes: statSync(file).size }));
  console.log(`${files[0]}: ${mb(report.bytes)} raw`);
  for (const s of report.sections) {
    console.log(`  ${String(s.id).padStart(2)} ${(s.name ? `custom "${s.name}"` : s.kind).padEnd(32)} ${String(s.bytes).padStart(12)}`);
  }
  console.log(`  name section ${mb(report.nameSectionBytes)}; all symbol sections ${mb(report.symbolSectionBytes)}`);
  console.log(`  function body sizes: ${report.codeSizeLeb.functions} functions, ${report.codeSizeLeb.wastedBytes} bytes of non-minimal LEB128`);
  if (compress) {
    console.log(`  as built:            raw ${mb(report.bytes)}, gzip-9 ${mb(report.gzip)}, brotli-11 ${mb(report.brotli)}`);
    console.log(`  without symbols:     raw ${mb(report.strippedBytes)}, gzip-9 ${mb(report.strippedGzip)}, brotli-11 ${mb(report.strippedBrotli)}`);
  }
  if (report.minimalLeb) {
    const m = report.minimalLeb;
    console.log(`  minimal body sizes:  raw ${mb(m.bytes)}${m.brotli ? `, gzip-9 ${mb(m.gzip)}, brotli-11 ${mb(m.brotli)}` : ""}`
      + ` (${m.valid ? "validates" : "INVALID"})`);
  }
  for (const c of report.compare) {
    const verdict = compress ? (report.strippedBrotli <= c.bytes ? "<=" : ">") : "?";
    console.log(`  compare ${c.file}: ${mb(c.bytes)} (module without symbols ${verdict} reference)`);
  }
  if (json) writeFileSync(json, JSON.stringify(report, null, 1) + "\n");
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(`wasm-size: ${error.stack || error}`);
    process.exitCode = 2;
  }
}
