#!/usr/bin/env node
// Turns stack traces of a release (name-section-free) TeaVM WebAssembly GC module back into
// Java frames (v0.4.0 WP8).
//
//   node wasm-symbolize.mjs [--symbols <module.wasm.symbols.wasm>] [--debug <module.wasm.teadbg>]
//                           [--module <module.wasm>] [--deobfuscator <jar|wasm>] [--runtime <jar|mjs>]
//                           [--json] [trace.txt|-]
//   node wasm-symbolize.mjs --self-test
//
// A release module ships without its name section; build-wasmgc.sh --release keeps the
// section in symbols/<module>.symbols.wasm (wasm-symbols.mjs split) and TeaVM's line tables
// in symbols/<module>.teadbg.  Without a deobfuscator the running module prints Java frames as
//     at java.lang.Throwable$FakeClass.fakeMethod(Throwable.java:<code address>)
// and V8 prints wasm frames as ...wasm-function[<index>]:0x<code address>.  Each such frame
// is replaced by
//   * the frames TeaVM's own WebAssembly GC deobfuscator (teavm-wasm-gc-deobfuscator, the
//     module its runtime loads for stackDeobfuscator) finds in the .teadbg tables, with
//     source lines and inlined calls; TeaVM 0.15 records these tables for SIMPLE builds, an
//     ADVANCED build keeps them for very few functions;
//   * otherwise the function that holds the address, from the symbol file:
//     at <class>.<method>(wasm-function[<index>]:0x<address>).
// Every other line is copied unchanged.  The exit status is 1 when a frame stays unresolved.
//
// The deobfuscator module and the TeaVM runtime (an ES module in teavm-core) are read from
// the local Maven repository (GAIUS_MAVEN_REPOSITORY or ~/.m2/repository) for the TeaVM
// version of port/config.json unless --deobfuscator/--runtime name them.  --module checks
// that the symbols describe that module (its sha256).
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";
import { inflateRawSync } from "node:zlib";
import { functionBodies } from "./wasm-binary.mjs";
import { namedSampleModule, readSymbols, splitSymbols } from "./wasm-symbols.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const FAKE_FRAME = /^(\s*)at java\.lang\.Throwable\$FakeClass\.fakeMethod\(Throwable\.java:(\d+)\)\s*$/;
const V8_FRAME = /^(\s*)at .*wasm-function\[(\d+)\]:0x([0-9a-fA-F]+).*$/;

// Reads one entry of a zip archive (stored or deflated) through the central directory.
export function readZipEntry(path, name) {
  const zip = readFileSync(path);
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error(`${path}: not a zip archive`);
  const entries = zip.readUInt16LE(eocd + 10);
  let pos = zip.readUInt32LE(eocd + 16);
  for (let i = 0; i < entries; i++) {
    if (zip.readUInt32LE(pos) !== 0x02014b50) throw new Error(`${path}: bad central directory`);
    const method = zip.readUInt16LE(pos + 10);
    const compressed = zip.readUInt32LE(pos + 20);
    const nameLength = zip.readUInt16LE(pos + 28);
    const extraLength = zip.readUInt16LE(pos + 30);
    const commentLength = zip.readUInt16LE(pos + 32);
    const local = zip.readUInt32LE(pos + 42);
    const entryName = zip.toString("utf8", pos + 46, pos + 46 + nameLength);
    if (entryName === name) {
      const dataStart = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
      const data = zip.subarray(dataStart, dataStart + compressed);
      if (method === 0) return Buffer.from(data);
      if (method === 8) return inflateRawSync(data);
      throw new Error(`${path}!${name}: unsupported compression ${method}`);
    }
    pos += 46 + nameLength + extraLength + commentLength;
  }
  throw new Error(`${path}: no entry ${name}`);
}

function mavenArtifact(artifact) {
  const repository = process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2", "repository");
  const version = JSON.parse(readFileSync(join(ROOT, "port/config.json"), "utf8")).teaVMVersion;
  return join(repository, "org", "teavm", artifact, version, `${artifact}-${version}.jar`);
}

async function loadRuntime(option, scratch) {
  const source = option && option.endsWith(".mjs") ? readFileSync(option)
    : readZipEntry(option || mavenArtifact("teavm-core"), "org/teavm/backend/wasm/wasm-gc-module-runtime.js");
  const file = join(scratch, "wasm-gc-module-runtime.mjs");
  writeFileSync(file, source);
  return import(pathToFileURL(file).href);
}

// address -> [{className, method, file, line}] from TeaVM's line tables.
export async function createTableLookup({ debug, deobfuscator, runtime }) {
  const scratch = mkdtempSync(join(tmpdir(), "gaius-symbolize-"));
  try {
    const { load } = await loadRuntime(runtime, scratch);
    const path = deobfuscator || mavenArtifact("teavm-wasm-gc-deobfuscator");
    const bytes = path.endsWith(".wasm") ? readFileSync(path)
      : readZipEntry(path, "org/teavm/backend/wasm/deobfuscator.wasm");
    const factory = await load(new Uint8Array(bytes), { nodejs: true });
    const tables = readFileSync(debug);
    const instance = factory.exports.createFromExternalFile(
      new Int8Array(tables.buffer, tables.byteOffset, tables.length));
    if (!instance) throw new Error(`${debug}: the deobfuscator rejected the tables`);
    return (address) => Array.from(instance.deobfuscate([address]) ?? [], (frame) => ({
      className: frame.className, method: frame.method, file: frame.file ?? null, line: frame.line ?? -1,
    }));
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

export function frameText(frame) {
  if (frame.wasmName !== undefined) {
    return `at ${frame.wasmName}(wasm-function[${frame.index}]:0x${frame.address.toString(16)})`;
  }
  const location = frame.file ? `${frame.file}${frame.line >= 0 ? `:${frame.line}` : ""}` : "Unknown Source";
  return `at ${frame.className}.${frame.method}(${location})`;
}

// Frames of one stack entry: line tables first, then the function from the symbol file.
export function resolveFrame(address, index, tables, symbols) {
  const fromTables = tables ? tables(address) : [];
  if (fromTables.length) return fromTables;
  if (symbols) {
    const functionIndex = index ?? symbols.functionAt(address);
    const name = functionIndex >= 0 ? symbols.nameOf(functionIndex) : null;
    if (name) return [{ wasmName: name, index: functionIndex, address }];
  }
  return [];
}

export function symbolizeText(text, resolver) {
  const lines = [];
  const frames = [];
  for (const line of text.split(/\r?\n/)) {
    const fake = FAKE_FRAME.exec(line);
    const v8 = fake ? null : V8_FRAME.exec(line);
    if (!fake && !v8) {
      lines.push(line);
      continue;
    }
    const indent = (fake || v8)[1];
    const address = fake ? Number(fake[2]) : parseInt(v8[3], 16);
    const found = resolver(address, v8 ? Number(v8[2]) : null);
    frames.push({ address, frames: found });
    if (found.length === 0) {
      lines.push(`${line}  [no symbol for 0x${address.toString(16)}]`);
    } else {
      for (const frame of found) lines.push(`${indent}${frameText(frame)}`);
    }
  }
  return { text: lines.join("\n"), frames };
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

// Symbol-file path only (the line tables need TeaVM's deobfuscator module): a split sample
// module, one TeaVM fake frame and one V8 frame per function, plus an unknown address.
function selfTest() {
  const { named } = namedSampleModule(3);
  const { stripped, symbols } = splitSymbols(named, "sample.wasm");
  const read = readSymbols(symbols);
  const bodies = functionBodies(stripped);
  const lines = ["java.lang.IllegalStateException: boom"];
  bodies.forEach((body, i) => {
    lines.push(`\tat java.lang.Throwable$FakeClass.fakeMethod(Throwable.java:${body.start + 2})`);
    lines.push(`    at f (wasm://wasm/0123abcd:wasm-function[${i}]:0x${(body.start + 1).toString(16)})`);
  });
  lines.push("\tat java.lang.Throwable$FakeClass.fakeMethod(Throwable.java:3)");
  const result = symbolizeText(lines.join("\n"), (address, index) => resolveFrame(address, index, null, read));
  const out = result.text.split("\n");
  const failures = [];
  bodies.forEach((body, i) => {
    if (!out[1 + 2 * i].startsWith(`\tat f${i}(wasm-function[${i}]:0x`)) failures.push(`fake frame ${i}: ${out[1 + 2 * i]}`);
    if (!out[2 + 2 * i].startsWith(`    at f${i}(wasm-function[${i}]:0x`)) failures.push(`V8 frame ${i}: ${out[2 + 2 * i]}`);
  });
  if (!out[0].endsWith("boom") || !out.at(-1).includes("[no symbol for 0x3]")) failures.push(`passthrough: ${out}`);
  for (const failure of failures) console.error(`self-test FAILED: ${failure}`);
  if (!failures.length) console.log("wasm-symbolize self-test passed: TeaVM and V8 frames resolve through the symbol file");
  return failures.length ? 1 : 0;
}

async function main(argv) {
  if (argv[0] === "--self-test") return selfTest();
  const options = { symbols: null, debug: null, module: null, deobfuscator: null, runtime: null, json: false,
    input: "-" };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--symbols") options.symbols = argv[++i];
    else if (arg === "--debug") options.debug = argv[++i];
    else if (arg === "--module") options.module = argv[++i];
    else if (arg === "--deobfuscator") options.deobfuscator = argv[++i];
    else if (arg === "--runtime") options.runtime = argv[++i];
    else if (arg === "--json") options.json = true;
    else if (arg.startsWith("--")) throw new Error(`unknown option ${arg}`);
    else options.input = arg;
  }
  if (!options.symbols && !options.debug) {
    console.error("usage: wasm-symbolize.mjs [--symbols <module.wasm.symbols.wasm>] [--debug <module.wasm.teadbg>] "
      + "[--module <module.wasm>] [--deobfuscator <jar|wasm>] [--runtime <jar|mjs>] [--json] [trace.txt|-]");
    return 2;
  }
  const symbols = options.symbols ? readSymbols(new Uint8Array(readFileSync(options.symbols))) : null;
  if (options.module) {
    const digest = sha256(options.module);
    if (symbols && symbols.info.sha256 !== digest) {
      throw new Error(`${options.module} (sha256 ${digest}) is not the module ${options.symbols} describes `
        + `(${symbols.info.file}, ${symbols.info.sha256})`);
    }
    const recorded = options.debug && join(dirname(options.debug), `${basename(options.module)}.sha256`);
    if (recorded && existsSync(recorded) && readFileSync(recorded, "utf8").trim() !== digest) {
      throw new Error(`${options.module} (sha256 ${digest}) is not the module ${options.debug} describes`);
    }
  }
  const tables = options.debug ? await createTableLookup(options) : null;
  const text = readFileSync(options.input === "-" ? 0 : options.input, "utf8");
  const result = symbolizeText(text, (address, index) => resolveFrame(address, index, tables, symbols));
  if (options.json) {
    console.log(JSON.stringify(result.frames, null, 1));
  } else {
    process.stdout.write(result.text.endsWith("\n") ? result.text : `${result.text}\n`);
  }
  return result.frames.some((f) => f.frames.length === 0) ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then((status) => { process.exitCode = status; }, (error) => {
    console.error(`wasm-symbolize: ${error.stack || error}`);
    process.exitCode = 2;
  });
}
