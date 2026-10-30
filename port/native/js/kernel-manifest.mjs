#!/usr/bin/env node
// Writes kernels.json for the wasm kernel builds in a directory (build-wasm-variants.sh).
//
//   node kernel-manifest.mjs <dir> <name>=<crate> ...
//
// For every kernel it expects <name>.simd.wasm and <name>.baseline.wasm, checks that both
// compile and export memory (or import it), alloc and the same run_<kind> functions, and
// records size and sha256 of each build. Whether the baseline really avoids SIMD is decided by
// its RUSTFLAGS (-C target-feature=-simd128), not checked here.
import {createHash} from "node:crypto";
import {readFile, writeFile} from "node:fs/promises";
import path from "node:path";

const [dir, ...entries] = process.argv.slice(2);
if (!dir || entries.length === 0) {
  console.error("usage: kernel-manifest.mjs <dir> <name>=<crate> ...");
  process.exit(2);
}

function exportsOf(module) {
  const kinds = [];
  let memory = false;
  let alloc = false;
  for (const entry of WebAssembly.Module.exports(module)) {
    if (entry.kind === "memory" && entry.name === "memory") memory = true;
    if (entry.kind === "function" && entry.name === "alloc") alloc = true;
    if (entry.kind === "function" && entry.name.startsWith("run_")) kinds.push(entry.name.slice(4));
  }
  for (const entry of WebAssembly.Module.imports(module)) {
    if (entry.kind === "memory") memory = true;
  }
  return {kinds: kinds.sort(), memory, alloc};
}

const kernels = {};
for (const entry of entries) {
  const [name, crate] = entry.split("=");
  const variants = {};
  let kinds = null;
  for (const variant of ["simd", "baseline"]) {
    const file = `${name}.${variant}.wasm`;
    const bytes = await readFile(path.join(dir, file));
    const module = await WebAssembly.compile(bytes);
    const shape = exportsOf(module);
    if (!shape.memory || !shape.alloc || shape.kinds.length === 0) {
      throw new Error(`${file}: a kernel must export memory, alloc and run_<kind>`);
    }
    if (kinds && kinds.join(",") !== shape.kinds.join(",")) {
      throw new Error(`${file}: exports ${shape.kinds} but the other build exports ${kinds}`);
    }
    kinds = shape.kinds;
    variants[variant] = {
      file,
      bytes: bytes.length,
      sha256: createHash("sha256").update(bytes).digest("hex"),
    };
  }
  kernels[name] = {crate, kinds, variants};
  console.log(`${name.padEnd(12)} simd ${String(variants.simd.bytes).padStart(9)} B  baseline ${String(variants.baseline.bytes).padStart(9)} B  kinds ${kinds.join(", ")}`);
}

const manifest = {schema: 1, kernels};
await writeFile(path.join(dir, "kernels.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log(`kernels.json: ${Object.keys(kernels).length} kernels in ${dir}`);
