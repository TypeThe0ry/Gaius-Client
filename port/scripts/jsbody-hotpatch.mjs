#!/usr/bin/env node
// Hot-patches the Java-embedded JavaScript (@JSBody scripts) of a built WasmGC client, to check a
// change to BrowserOpenGL.java (or any other JSBody source) in the browser without a TeaVM build.
//
//   node port/scripts/jsbody-hotpatch.mjs <base.java> <changed.java> <out-preamble.js>
//     [--other-java <dir>]...   Java source roots whose scripts must not share a key with a patched one
//
//   git show <build-commit>:port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java > base.java
//   node port/scripts/jsbody-hotpatch.mjs base.java <worktree>/.../BrowserOpenGL.java hotpatch.js \
//     --other-java <worktree>/port/overrides --other-java <worktree>/port/src
//   PREAMBLE_FILE=hotpatch.js DIST=<staged acceptance site> node port/scripts/minecraft-263-singleplayer-cdp.mjs
//
// base.java must be the source the client was built from. The i-th script of both files is compared;
// every script that differs is swapped in when the WebAssembly runtime builds its JS function
// (createFunctionN -> new Function(..., body)). The runtime passes large scripts through a printer
// (a block, extra parentheses), so a script is matched on the first 600 characters of its text without
// whitespace, parentheses, braces and semicolons (extended until no other script shares them).
// The page reports the swaps as globalThis.__hotpatch: {bodies, hits, applied}; a script the
// page never builds (a path the run does not reach) shows as a missing hit. Only the main thread is
// patched (the preamble does not run in Workers).
import {readFileSync, writeFileSync, readdirSync, statSync} from "node:fs";
import {join} from "node:path";

const BACKSLASH = String.fromCharCode(92);

function unescapeTextBlock(raw) {
  let out = "";
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c !== BACKSLASH) { out += c; continue; }
    const n = raw[++i];
    if (n === "n") out += "\n";
    else if (n === "t") out += "\t";
    else if (n === "r") out += "\r";
    else if (n === "s") out += " ";
    else if (n === "\n") { /* line continuation */ }
    else if (n === "\r") { if (raw[i + 1] === "\n") i++; }
    else out += n;
  }
  return out;
}

function bodies(path) {
  const src = readFileSync(path, "utf8").replace(/\r\n/g, "\n");
  const out = [];
  const marker = 'script = """';
  let at = 0;
  for (;;) {
    const start = src.indexOf(marker, at);
    if (start < 0) break;
    const contentStart = start + marker.length;
    let end = contentStart;
    for (;;) { // the closing delimiter is the first unescaped text-block quote
      end = src.indexOf('"""', end);
      let slashes = 0;
      for (let k = end - 1; src[k] === BACKSLASH; k--) slashes++;
      if (slashes % 2 === 0) break;
      end += 3;
    }
    out.push(unescapeTextBlock(src.slice(contentStart, end)));
    at = end + 3;
  }
  return out;
}

const args = process.argv.slice(2);
const others = [];
const positional = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--other-java") others.push(args[++i]);
  else positional.push(args[i]);
}
const [basePath, changedPath, outPath] = positional;
if (!basePath || !changedPath || !outPath) {
  console.error("usage: jsbody-hotpatch.mjs <base.java> <changed.java> <out-preamble.js> [--other-java <dir>]...");
  process.exit(2);
}
const before = bodies(basePath);
const after = bodies(changedPath);
if (before.length !== after.length) throw new Error(`script count differs: ${before.length} vs ${after.length}`);

const NORM = "/[\\s(){};]+/g";
const norm = text => text.replace(/[\s(){};]+/g, "");
const sameFile = (a, b) => a.split(BACKSLASH).join("/") === b.split(BACKSLASH).join("/");
const known = before.map(norm);
const walk = dir => {
  for (const name of readdirSync(dir)) {
    const file = join(dir, name);
    if (statSync(file).isDirectory()) walk(file);
    else if (name.endsWith(".java") && !sameFile(file, changedPath) && !sameFile(file, basePath)) {
      for (const body of bodies(file)) known.push(norm(body));
    }
  }
};
others.forEach(walk);

const entries = [];
const lengths = new Set();
for (let i = 0; i < before.length; i++) {
  if (norm(before[i]) === norm(after[i])) continue;
  const unique = n => known.every((other, j) => j === i || other.slice(0, n) !== known[i].slice(0, n));
  let length = 600;
  while (length < known[i].length && !unique(length)) length += 200;
  if (!unique(length)) length = known[i].length;
  lengths.add(length);
  entries.push([`${length}:${known[i].slice(0, length)}`, after[i]]);
}
console.log(`scripts=${before.length} patched=${entries.length}`);

writeFileSync(outPath, `(() => {
  if (globalThis.__hotpatch) return;
  const MAP = new Map(${JSON.stringify(entries)});
  const LENGTHS = ${JSON.stringify([...lengths])};
  const H = globalThis.__hotpatch = {bodies: MAP.size, hits: 0, applied: []};
  const norm = text => text.replace(${NORM}, "");
  const patch = args => {
    const last = args[args.length - 1];
    if (typeof last !== "string" || last.length < 40) return false;
    const text = norm(last);
    for (const candidate of text[0] === "{" ? [text, text.slice(1)] : [text]) {
      for (const length of LENGTHS) {
        const replacement = MAP.get(length + ":" + candidate.slice(0, length));
        if (replacement !== undefined) {
          args[args.length - 1] = replacement;
          H.hits++;
          H.applied.push([last.length, last.slice(0, 80)]);
          return true;
        }
      }
    }
    return false;
  };
  globalThis.Function = new Proxy(Function, {
    construct(target, args, newTarget) {
      const patched = patch(args);
      const made = Reflect.construct(target, args, newTarget);
      if (!patched) return made;
      const label = String(args[args.length - 1]).slice(0, 60).replace(/\\s+/g, " ");
      return function () {
        try { return made.apply(this, arguments); }
        catch (error) { console.error("HOTPATCH-EXC " + label + " :: " + (error && error.stack || error)); throw error; }
      };
    },
    apply(target, thisArg, args) { patch(args); return Reflect.apply(target, thisArg, args); }
  });
})();
`);
