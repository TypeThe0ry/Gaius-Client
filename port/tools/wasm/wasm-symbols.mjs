#!/usr/bin/env node
// Moves the name section of a WebAssembly GC module into a separate symbol file (v0.4.0 WP8).
//
//   node wasm-symbols.mjs split <module.wasm> --out <module.wasm.symbols.wasm>
//   node wasm-symbols.mjs info <module.wasm.symbols.wasm>
//   node wasm-symbols.mjs --self-test
//
// TeaVM's WebAssembly GC "obfuscation" (minifying) is one flag of its binary renderer: the
// module is written without the name section and is otherwise byte-identical.  A release
// therefore compiles with the name section and splits it off here: the shipped module is the
// obfuscated one, and the symbol file keeps the names for wasm-symbolize.mjs.
//
// The symbol file is itself a WebAssembly module with three custom sections and no code:
//   gaius.module   JSON {file, bytes, sha256} of the stripped (shipped) module
//   gaius.bodies   LEB128: imported function count, defined function count, then the code
//                  offset of every defined function body as a delta from the previous one
//   name           the module's name section, unchanged
// so a stack address (a byte offset of the shipped module) maps to a function without the
// module, and wasm-function[N] indices map to names directly.
import { createHash } from "node:crypto";
import { readFileSync, renameSync, writeFileSync } from "node:fs";
import { basename } from "node:path";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  encodeLeb,
  functionBodies,
  functionNames,
  importedFunctionCount,
  readLeb,
  sampleModule,
  sections,
  withoutCustomSections,
} from "./wasm-binary.mjs";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function customSection(name, payload) {
  const nameBytes = encoder.encode(name);
  const body = [...encodeLeb(nameBytes.length), ...nameBytes, ...payload];
  return [0, ...encodeLeb(body.length), ...body];
}

function customPayload(buf, section) {
  let pos = section.start;
  let length;
  [length, pos] = readLeb(buf, pos);
  return buf.subarray(pos + length, section.end);
}

// {stripped, symbols}: the module without its name section and the symbol file.
export function splitSymbols(module, file) {
  const list = sections(module);
  const nameSection = list.find((s) => s.id === 0 && s.name === "name");
  if (!nameSection) throw new Error(`${file} has no name section (was it built with GAIUS_MINIFYING=true?)`);
  const stripped = withoutCustomSections(module, (s) => s.name === "name");
  const strippedList = sections(stripped);
  const imported = importedFunctionCount(stripped, strippedList);
  const bodies = functionBodies(stripped, strippedList);
  const offsets = [...encodeLeb(imported), ...encodeLeb(bodies.length)];
  let previous = 0;
  for (const body of bodies) {
    offsets.push(...encodeLeb(body.sizeStart - previous));
    previous = body.sizeStart;
  }
  const info = {
    file,
    bytes: stripped.length,
    sha256: createHash("sha256").update(stripped).digest("hex"),
  };
  const bytes = [0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    ...customSection("gaius.module", [...encoder.encode(JSON.stringify(info))]),
    ...customSection("gaius.bodies", offsets)];
  const symbols = new Uint8Array(bytes.length + (nameSection.end - nameSection.headerStart));
  symbols.set(bytes, 0);
  symbols.set(module.subarray(nameSection.headerStart, nameSection.end), bytes.length);
  return { stripped, symbols, info };
}

// {info, imported, bodyStarts, names, functionAt(address), nameOf(index)} of a symbol file.
export function readSymbols(buf) {
  const list = sections(buf);
  const moduleSection = list.find((s) => s.id === 0 && s.name === "gaius.module");
  const bodiesSection = list.find((s) => s.id === 0 && s.name === "gaius.bodies");
  if (!moduleSection || !bodiesSection) throw new Error("not a Gaius wasm symbol file");
  const info = JSON.parse(decoder.decode(customPayload(buf, moduleSection)));
  const payload = customPayload(buf, bodiesSection);
  let pos = 0;
  let imported;
  let count;
  [imported, pos] = readLeb(payload, pos);
  [count, pos] = readLeb(payload, pos);
  const bodyStarts = new Array(count);
  let offset = 0;
  for (let i = 0; i < count; i++) {
    let delta;
    [delta, pos] = readLeb(payload, pos);
    offset += delta;
    bodyStarts[i] = offset;
  }
  const names = functionNames(buf, list);
  return {
    info,
    imported,
    bodyStarts,
    names,
    nameOf: (index) => names.get(index) ?? null,
    // The function whose body holds a code address, as an absolute function index.
    functionAt(address) {
      let low = 0;
      let high = bodyStarts.length - 1;
      if (high < 0 || address < bodyStarts[0]) return -1;
      while (low < high) {
        const middle = (low + high + 1) >> 1;
        if (bodyStarts[middle] <= address) low = middle;
        else high = middle - 1;
      }
      return imported + low;
    },
  };
}

// The sample module of wasm-binary.mjs plus a name section naming function i "f<i>".
export function namedSampleModule(count) {
  const base = sampleModule(count);
  const sub = [...encodeLeb(count)];
  for (let i = 0; i < count; i++) {
    const name = encoder.encode(`f${i}`);
    sub.push(...encodeLeb(i), ...encodeLeb(name.length), ...name);
  }
  return { base, named: new Uint8Array([...base, ...customSection("name", [1, ...encodeLeb(sub.length), ...sub])]) };
}

function selfTest() {
  const failures = [];
  const { base, named } = namedSampleModule(4);
  const { stripped, symbols } = splitSymbols(named, "sample.wasm");
  if (stripped.length !== base.length || !stripped.every((b, i) => b === base[i])) {
    failures.push("the stripped module is not the module without its name section");
  }
  const read = readSymbols(symbols);
  const bodies = functionBodies(base);
  for (let i = 0; i < 4; i++) {
    const index = read.functionAt(bodies[i].start + 1);
    if (index !== i || read.nameOf(index) !== `f${i}`) failures.push(`function ${i}: got ${index} ${read.nameOf(index)}`);
  }
  if (read.info.sha256 !== createHash("sha256").update(base).digest("hex")) failures.push("sha256 mismatch");
  for (const failure of failures) console.error(`self-test FAILED: ${failure}`);
  if (!failures.length) console.log("wasm-symbols self-test passed: split, offsets and names round-trip");
  return failures.length ? 1 : 0;
}

function main(argv) {
  if (argv[0] === "--self-test") return selfTest();
  if (argv[0] === "split" && argv[1] && argv[2] === "--out" && argv[3]) {
    const file = argv[1];
    const { stripped, symbols, info } = splitSymbols(new Uint8Array(readFileSync(file)), basename(file));
    writeFileSync(argv[3], symbols);
    const temporary = `${file}.strip.${process.pid}.tmp`;
    writeFileSync(temporary, stripped);
    renameSync(temporary, file);
    console.log(`${file}: name section moved to ${argv[3]} (${symbols.length} bytes); module ${info.bytes} bytes, `
      + `sha256 ${info.sha256}`);
    return 0;
  }
  if (argv[0] === "info" && argv[1]) {
    const read = readSymbols(new Uint8Array(readFileSync(argv[1])));
    console.log(JSON.stringify({ ...read.info, importedFunctions: read.imported,
      definedFunctions: read.bodyStarts.length, names: read.names.size }, null, 1));
    return 0;
  }
  console.error("usage: wasm-symbols.mjs split <module.wasm> --out <symbols.wasm> | info <symbols.wasm> | --self-test");
  return 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(`wasm-symbols: ${error.stack || error}`);
    process.exitCode = 2;
  }
}
