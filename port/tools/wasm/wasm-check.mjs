#!/usr/bin/env node
// CI gate (a) of the WebAssembly GC build (v0.4.0 WP1): every module must compile in V8
// with zero invalid functions.
//
//   node wasm-check.mjs [--max N] [--json out.json] <file.wasm>...
//   node wasm-check.mjs --inject <definedIndex>[,<definedIndex>...] --out <copy.wasm> <file.wasm>
//   node wasm-check.mjs --self-test
//
// V8 reports only the first function that fails to compile, so a failing module is
// scanned: the reported function's body is replaced with `unreachable` and the module is
// compiled again, until it compiles or --max functions (default 500) were listed.  The
// exit status is 0 only when every module compiled as built (0 invalid functions); a file
// that is not a module, or a module error that names no function, also fails.
//
// --inject writes a copy whose listed defined functions have an invalid body (the
// positive control of the gate), and --self-test runs the gate on in-memory modules with
// and without injected functions.  Both need no build.
import { readFileSync, writeFileSync } from "node:fs";
import {
  INVALID_BODY,
  UNREACHABLE_BODY,
  functionBodies,
  functionNames,
  importedFunctionCount,
  isWasm,
  replaceFunctionBody,
  encodeLeb,
  sampleModule,
  sections,
  arrayNewFixedInstructions,
} from "./wasm-binary.mjs";

const FUNCTION_ERROR = /Compiling function #(\d+)(?::"([^"]*)")? failed: (.*)/;

async function compile(bytes) {
  // The TeaVM WebAssembly GC runtime compiles with the JS string builtins; a module that
  // imports wasm:js-string must be checked the same way.
  return WebAssembly.compile(bytes, { builtins: ["js-string"] });
}

// {valid, invalidFunctions: [{index, definedIndex, name, error}], moduleError}
export async function scanModule(input, max = 500) {
  let bytes = input;
  let imported;
  let names;
  try {
    imported = importedFunctionCount(bytes);
    names = functionNames(bytes);
  } catch (error) {
    // Not even the section table decodes: report V8's own message when it has one.
    try {
      await compile(bytes);
    } catch (compileError) {
      return { valid: false, invalidFunctions: [], moduleError: String(compileError.message || compileError) };
    }
    return { valid: false, invalidFunctions: [], moduleError: String(error.message || error) };
  }
  const invalidFunctions = [];
  for (let round = 0; round <= max; round++) {
    try {
      await compile(bytes);
      return { valid: invalidFunctions.length === 0, invalidFunctions, moduleError: null };
    } catch (error) {
      const message = String(error && error.message ? error.message : error);
      const match = FUNCTION_ERROR.exec(message);
      if (!match) {
        return { valid: false, invalidFunctions, moduleError: message };
      }
      const index = Number(match[1]);
      const definedIndex = index - imported;
      if (round === max || definedIndex < 0) {
        invalidFunctions.push({ index, definedIndex, name: match[2] ?? names.get(index) ?? null, error: match[3] });
        return { valid: false, invalidFunctions, moduleError: round === max ? `stopped after ${max} functions` : message };
      }
      invalidFunctions.push({ index, definedIndex, name: match[2] ?? names.get(index) ?? null, error: match[3] });
      bytes = replaceFunctionBody(bytes, definedIndex, UNREACHABLE_BODY);
    }
  }
  return { valid: false, invalidFunctions, moduleError: "scan limit reached" };
}

// V8 decodes instructions and reports the fixed-array limit with its function index.
// Searching raw bytes for fb 08 also matches floating constants and LEB immediates.
// Reuse the validation scan so every reported length is an actual instruction.
export async function scanArrayNewFixed(input) {
  return arrayNewFixedInstructions(input).map((item) => ({
    ...item, index: item.definedIndex, name: null, limit: 10000,
  })).filter((item) => item.length > item.limit);
}

function arrayLengthViolations(result) {
  const byFunction = new Map();
  for (const fn of result.invalidFunctions) {
    const match = /Requested length (\d+) for array\.new_fixed too large, maximum is (\d+)/.exec(fn.error);
    if (!match) continue;
    const item = {index: fn.index, definedIndex: fn.definedIndex, name: fn.name,
      length: Number(match[1]), limit: Number(match[2])};
    const previous = byFunction.get(fn.index);
    if (!previous || item.length > previous.length) byFunction.set(fn.index, item);
  }
  return [...byFunction.values()];
}

function parseArgs(argv) {
  const options = { max: 500, json: null, inject: null, out: null, selfTest: false, files: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--max") options.max = Number(argv[++i]);
    else if (arg === "--json") options.json = argv[++i];
    else if (arg === "--inject") options.inject = argv[++i].split(",").map(Number);
    else if (arg === "--out") options.out = argv[++i];
    else if (arg === "--self-test") options.selfTest = true;
    else if (arg.startsWith("--")) throw new Error(`unknown option ${arg}`);
    else options.files.push(arg);
  }
  if (!Number.isInteger(options.max) || options.max < 1) throw new Error("--max needs a positive integer");
  return options;
}

export function injectInvalid(bytes, definedIndices) {
  let out = bytes;
  for (const index of definedIndices) {
    out = replaceFunctionBody(out, index, INVALID_BODY);
  }
  return out;
}

async function checkFiles(files, max) {
  const results = [];
  for (const file of files) {
    let bytes;
    try {
      bytes = new Uint8Array(readFileSync(file));
    } catch (error) {
      results.push({ file, bytes: 0, valid: false, invalidFunctions: [], moduleError: String(error.message) });
      continue;
    }
    if (!isWasm(bytes)) {
      results.push({ file, bytes: bytes.length, valid: false, invalidFunctions: [], moduleError: "not a WebAssembly module" });
      continue;
    }
    const started = Date.now();
    const result = await scanModule(bytes, max);
    let arrayNewFixed = [];
    let arrayScanError = null;
    try {
      arrayNewFixed = await scanArrayNewFixed(bytes);
      const importedCount = importedFunctionCount(bytes);
      for (const item of arrayNewFixed) {
        item.index += importedCount;
        item.name = functionNames(bytes).get(item.index) ?? null;
      }
    } catch (error) {
      arrayScanError = `array.new_fixed scan failed: ${error.message || error}`;
    }
    const nameSection = sections(bytes).find((s) => s.id === 0 && s.name === "name");
    results.push({
      file,
      bytes: bytes.length,
      nameSectionBytes: nameSection ? nameSection.end - nameSection.headerStart : 0,
      compileSeconds: (Date.now() - started) / 1000,
      ...result,
      arrayNewFixed,
      moduleError: arrayScanError ?? result.moduleError,
      valid: result.valid && !arrayScanError && arrayNewFixed.length === 0,
    });
  }
  return results;
}

function report(result) {
  if (result.valid) {
    console.log(`${result.file}: ${result.bytes} bytes, compiles in V8, 0 invalid functions`);
    return;
  }
  console.log(`${result.file}: ${result.bytes} bytes, INVALID: ${result.invalidFunctions.length} invalid functions`
    + (result.moduleError ? ` (${result.moduleError})` : ""));
  for (const f of result.invalidFunctions) {
    console.log(`  #${f.index}${f.name ? ` ${f.name}` : ""} :: ${f.error}`);
  }
  for (const f of result.arrayNewFixed ?? []) {
    console.log(`  #${f.index}${f.name ? ` ${f.name}` : ""} :: array.new_fixed length ${f.length} exceeds ${f.limit}`);
  }
}

async function selfTest() {
  const failures = [];
  const clean = sampleModule(6);
  const cleanResult = await scanModule(clean);
  if (!cleanResult.valid || cleanResult.invalidFunctions.length !== 0) {
    failures.push(`clean module: expected valid, got ${JSON.stringify(cleanResult)}`);
  }
  const broken = injectInvalid(clean, [1, 4]);
  const brokenResult = await scanModule(broken);
  const found = brokenResult.invalidFunctions.map((f) => f.definedIndex).sort((a, b) => a - b);
  if (brokenResult.valid || JSON.stringify(found) !== "[1,4]") {
    failures.push(`two injected functions: expected [1,4], got ${JSON.stringify(found)} (${brokenResult.moduleError})`);
  }
  const truncated = clean.subarray(0, clean.length - 3);
  const truncatedResult = await scanModule(truncated);
  if (truncatedResult.valid) {
    failures.push("truncated module: expected a module error");
  }
  const synthetic = (...lengths) => {
    const body = [0];
    for (const length of lengths) {
      for (let i = 0; i < length; i++) body.push(0xd0, 0x6d); // ref.null eq
      body.push(0xfb, 0x08, 1, ...encodeLeb(length), 0x1a); // fixed array, drop
    }
    body.push(0x0b);
    const section = (id, payload) => [id, ...encodeLeb(payload.length), ...payload];
    return new Uint8Array([
      0, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
      ...section(1, [2, 0x60, 0, 0, 0x5e, 0x6d, 1]),
      ...section(3, [1, 0]),
      ...section(10, [1, ...encodeLeb(body.length), ...body]),
    ]);
  };
  const atLimit = await scanArrayNewFixed(synthetic(10000));
  const overLimit = await scanArrayNewFixed(synthetic(10001));
  const chunkModule = synthetic(4096, 4096, 1809);
  const chunked = await scanArrayNewFixed(chunkModule);
  if (atLimit.length !== 0 || overLimit.length !== 1 || overLimit[0].length !== 10001
      || chunked.length !== 0 || !(await scanModule(chunkModule)).valid) {
    failures.push(`array.new_fixed limit scan failed: at=${JSON.stringify(atLimit)}, over=${JSON.stringify(overLimit)}`);
  }
  // An f64 constant contains the bytes of an oversized array instruction; it is valid.
  const literalBytes = replaceFunctionBody(clean, 0,
    new Uint8Array([0, 0x44, 0xfb, 0x08, 1, 0x91, 0x4e, 0, 0, 0, 0x1a, 0x41, 0, 0x0b]));
  if (!(await scanModule(literalBytes)).valid || (await scanArrayNewFixed(literalBytes)).length) {
    failures.push("array.new_fixed bytes inside an f64 constant were mistaken for an instruction");
  }
  // A typed-ref local can contain fb 08 as its heap-type index. The
  // ref.cast_null immediate followed by global.get 20159 reproduces the
  // misaligned scan that reported a fictitious 20159-element fixed array.
  const typedReference = (() => {
    const section = (id, payload) => [id, ...encodeLeb(payload.length), ...payload];
    const types = [0x60, 0, 0]; // () -> ()
    for (let i = 1; i <= 1147; i++) types.push(0x5f, 0); // empty structs
    const globals = [...encodeLeb(20160)];
    for (let i = 0; i < 20160; i++) globals.push(0x7f, 0, 0x41, 0, 0x0b);
    const body = [1, 1, 0x63, 0xfb, 0x08, // one (ref null 1147) local
      0x00, 0x20, 0, // unreachable, local.get 0
      0xfb, 0x17, 0xfb, 0x08, 0x1a, // ref.cast_null 1147, drop
      0x23, ...encodeLeb(20159), 0x1a, 0x0b]; // global.get 20159, drop
    return new Uint8Array([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
      ...section(1, [...encodeLeb(1148), ...types]),
      ...section(3, [1, 0]),
      ...section(6, globals),
      ...section(10, [1, ...encodeLeb(body.length), ...body])]);
  })();
  if (!(await scanModule(typedReference)).valid || (await scanArrayNewFixed(typedReference)).length) {
    failures.push("typed-reference immediates were mistaken for array.new_fixed");
  }
  if (failures.length) {
    for (const failure of failures) console.error(`self-test FAILED: ${failure}`);
    return 1;
  }
  console.log("wasm-check self-test passed: clean module valid, 2 injected functions found, truncated module rejected, fixed-array boundaries and immediate false positives checked");
  return 0;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.selfTest) {
    return selfTest();
  }
  if (options.inject) {
    if (options.files.length !== 1 || !options.out) throw new Error("--inject needs one input file and --out");
    const bytes = new Uint8Array(readFileSync(options.files[0]));
    writeFileSync(options.out, injectInvalid(bytes, options.inject));
    console.log(`wrote ${options.out} with ${options.inject.length} invalid function bodies`);
    return 0;
  }
  if (options.files.length === 0) {
    console.error("usage: wasm-check.mjs [--max N] [--json out.json] <file.wasm>... | --inject i,j --out f <file.wasm> | --self-test");
    return 2;
  }
  const results = await checkFiles(options.files, options.max);
  results.forEach(report);
  if (options.json) {
    writeFileSync(options.json, JSON.stringify(results, null, 1) + "\n");
  }
  return results.every((r) => r.valid) ? 0 : 1;
}

main().then((status) => { process.exitCode = status; }, (error) => {
  console.error(`wasm-check: ${error.stack || error}`);
  process.exitCode = 2;
});
