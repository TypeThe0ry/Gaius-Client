#!/usr/bin/env node
import {readFileSync, writeFileSync} from "node:fs";
import {createHash} from "node:crypto";
import {readSymbols} from "../tools/wasm/wasm-symbols.mjs";

const [input, output, symbolsPath] = process.argv.slice(2);
if (!input || !output || !symbolsPath) throw new Error("usage: node symbolize-cpuprofile-wasm.mjs <input.cpuprofile> <output.cpuprofile> <symbols.wasm>");
const symbols = readSymbols(new Uint8Array(readFileSync(symbolsPath)));
const profile = JSON.parse(readFileSync(input, "utf8"));
const re = /^wasm-function\[(\d+)\]$/;
let resolved = 0, unresolved = 0;
for (const node of profile.nodes || []) {
  const frame = node.callFrame || {};
  const match = re.exec(String(frame.functionName || ""));
  if (!match) continue;
  const index = Number(match[1]);
  const name = symbols.nameOf(index);
  frame.wasmFunctionIndex = index;
  frame.javaFunctionName = name || null;
  if (name) { frame.functionName = name; resolved++; } else unresolved++;
}
profile.gaiusSymbolization = {symbols: symbolsPath, symbolModule: symbols.info, resolved, unresolved};
writeFileSync(output, JSON.stringify(profile));
console.log(JSON.stringify(profile.gaiusSymbolization));
