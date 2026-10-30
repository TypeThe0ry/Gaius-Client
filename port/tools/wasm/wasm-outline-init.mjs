#!/usr/bin/env node
// Moves long straight-line runs of large functions into functions of their own.
//
//   node wasm-outline-init.mjs <module.wasm> [--out <outlined.wasm>] [--min-function <bytes>]
//                              [--min-run <bytes>] [--max-chunk <bytes>]   (default: in place)
//   node wasm-outline-init.mjs --self-test
//
// TeaVM's WebAssembly GC backend writes its one-time initialisation as huge straight-line
// functions: the module start function (teavm@initializer, 3.3 MB in singleplayer-server.wasm,
// 4.5 MB in classes.wasm), teavm@initReflection (370 KB / 552 KB) and the class initialiser of
// WasmGCResources (139 KB / 63 KB, thousands of "global.get; i32.const; i32.const; call"
// statements followed by a loop over the resources).  Each runs once, in Liftoff, but V8's
// dynamic tiering charges a function's tier-up budget with the code it executes, so these
// functions are queued for TurboFan right after (or during) their only call.  TurboFan on
// WasmGCResources.<clinit> of the server took 18 s of a background thread and 2.7 GB of
// compiler zone memory when the integrated server Worker started (2.58 GiB peak of V8's wasm
// allocator in the renderer, the largest single item of the browser's memory peak), and the
// client's initialisers took 0.75 GB at boot.  The code TurboFan produces never runs again.
//
// For every function of at least --min-function bytes (default 32 KiB) this tool finds runs of
// instructions that (1) use no locals, labels, branches, tables, memory or exceptions, and
// (2) leave the operand stack as they found it without consuming anything pushed before the
// run.  Runs of at least --min-run bytes (default 1 KiB) are cut at empty-stack points into
// chunks of at most --max-chunk bytes (default 16 KiB); every chunk becomes a new function of
// type [] -> [] and its bytes are replaced by a call.  The chunks run once each in Liftoff and
// stay far below the tier-up budget, the remaining caller is small, and nothing is compiled by
// TurboFan for nothing.  Semantics are unchanged: the moved instructions only touch globals,
// the operand stack they own and their callees; a trap or exception propagates through the new
// call to the same handlers.  New functions are appended after the existing ones (no index,
// export, element segment or name changes).  Functions with instructions the decoder does not
// know are left as they are.
import fs from "node:fs";
import { pathToFileURL } from "node:url";
import { encodeLeb, readLeb, sections, functionBodies } from "./wasm-binary.mjs";

const DEFAULTS = {minFunction: 32 * 1024, minRun: 1024, maxChunk: 16 * 1024, minArray: 256};

function readSleb(buf, pos) {
  let byte;
  do { byte = buf[pos++]; } while (byte & 0x80);
  return pos;
}

function skipValueType(buf, pos) {
  const type = buf[pos++];
  if (type === 0x63 || type === 0x64) return readSleb(buf, pos);
  return pos;
}

function skipBlockType(buf, pos) {
  if (buf[pos] === 0x40) return pos + 1;
  if (buf[pos] === 0x63 || buf[pos] === 0x64) return skipValueType(buf, pos);
  return readSleb(buf, pos);
}

function skipMemarg(buf, pos) {
  let align;
  [align, pos] = readLeb(buf, pos);
  if (align & 0x40) [, pos] = readLeb(buf, pos); // multi-memory index
  [, pos] = readLeb(buf, pos);
  return pos;
}

const leb = (buf, pos) => readLeb(buf, pos)[1];

// Type section: for every type index {kind, params, results, fields}.
export function parseTypes(buf, section) {
  const types = [];
  if (!section) return types;
  let pos = section.start, count;
  [count, pos] = readLeb(buf, pos);
  function compType() {
    const form = buf[pos++];
    if (form === 0x60) {
      let params, results;
      [params, pos] = readLeb(buf, pos);
      for (let i = 0; i < params; i++) pos = skipValueType(buf, pos);
      [results, pos] = readLeb(buf, pos);
      for (let i = 0; i < results; i++) pos = skipValueType(buf, pos);
      types.push({kind: "func", params, results});
    } else if (form === 0x5f) {
      let fields;
      [fields, pos] = readLeb(buf, pos);
      for (let i = 0; i < fields; i++) { pos = skipValueType(buf, pos); pos++; }
      types.push({kind: "struct", fields});
    } else if (form === 0x5e) {
      const element = buf[pos];
      pos = skipValueType(buf, pos);
      const mutable = buf[pos++] === 1;
      types.push({kind: "array", defaultable: element !== 0x64, mutable});
    } else {
      throw new Error(`unknown composite type 0x${form.toString(16)} at ${pos - 1}`);
    }
  }
  function subType() {
    if (buf[pos] === 0x50 || buf[pos] === 0x4f) {
      pos++;
      let supers;
      [supers, pos] = readLeb(buf, pos);
      for (let i = 0; i < supers; i++) [, pos] = readLeb(buf, pos);
    }
    compType();
  }
  for (let i = 0; i < count; i++) {
    if (buf[pos] === 0x4e) {
      pos++;
      let members;
      [members, pos] = readLeb(buf, pos);
      for (let j = 0; j < members; j++) subType();
    } else {
      subType();
    }
  }
  return types;
}

// Type index of every function: imports first, then the function section.  out.importedGlobals
// counts the imported globals (they come first in the global index space).
export function functionTypes(buf, list) {
  const out = [];
  out.importedGlobals = 0;
  const imports = list.find((s) => s.id === 2);
  if (imports) {
    let pos = imports.start, count;
    [count, pos] = readLeb(buf, pos);
    for (let i = 0; i < count; i++) {
      let length;
      [length, pos] = readLeb(buf, pos); pos += length;
      [length, pos] = readLeb(buf, pos); pos += length;
      const kind = buf[pos++];
      if (kind === 0) { let type; [type, pos] = readLeb(buf, pos); out.push(type); }
      else if (kind === 1) { pos = skipValueType(buf, pos); const flags = buf[pos++]; pos = leb(buf, pos); if (flags & 1) pos = leb(buf, pos); }
      else if (kind === 2) { const flags = buf[pos++]; pos = leb(buf, pos); if (flags & 1) pos = leb(buf, pos); }
      else if (kind === 3) { pos = skipValueType(buf, pos); pos++; out.importedGlobals++; }
      else if (kind === 4) { pos++; pos = leb(buf, pos); }
      else throw new Error(`unknown import kind ${kind}`);
    }
  }
  const functions = list.find((s) => s.id === 3);
  if (functions) {
    let pos = functions.start, count;
    [count, pos] = readLeb(buf, pos);
    for (let i = 0; i < count; i++) { let type; [type, pos] = readLeb(buf, pos); out.push(type); }
  }
  return out;
}

// [pops, pushes] of the numeric instructions 0x45..0xc4.
function numericEffect(op) {
  if (op === 0x45 || op === 0x50) return [1, 1];
  if ((op >= 0x46 && op <= 0x4f) || (op >= 0x51 && op <= 0x66)) return [2, 1];
  if ((op >= 0x67 && op <= 0x69) || (op >= 0x79 && op <= 0x7b) || (op >= 0x8b && op <= 0x91)
      || (op >= 0x99 && op <= 0x9f) || (op >= 0xa7 && op <= 0xc4)) return [1, 1];
  return [2, 1]; // 0x6a..0x78, 0x7c..0x8a, 0x92..0x98, 0xa0..0xa6
}

// Decodes one instruction at pos.  Returns {pos, simple, pops, pushes}: simple instructions only
// read and write the operand stack, globals and callees; everything else ends a run.
function decode(buf, pos, types, funcTypes) {
  const op = buf[pos++];
  const plain = (pops, pushes) => ({pos, simple: true, pops, pushes});
  const other = () => ({pos, simple: false});
  if (op >= 0x45 && op <= 0xc4) { const [a, b] = numericEffect(op); return plain(a, b); }
  switch (op) {
    case 0x00: case 0x05: case 0x0a: case 0x0b: case 0x0f: case 0x19: return other();
    case 0x01: return plain(0, 0);
    case 0x02: case 0x03: case 0x04: case 0x06: pos = skipBlockType(buf, pos); return other();
    case 0x07: case 0x08: case 0x09: case 0x0c: case 0x0d: case 0x12: case 0x14: case 0x15: case 0x18:
    case 0x20: case 0x21: case 0x22: case 0x25: case 0x26: case 0x3f: case 0x40: case 0xd5: case 0xd6:
      pos = leb(buf, pos); return other();
    case 0x0e: { let n; [n, pos] = readLeb(buf, pos); for (let i = 0; i <= n; i++) pos = leb(buf, pos); return other(); }
    case 0x10: {
      let index; [index, pos] = readLeb(buf, pos);
      const type = types[funcTypes[index]];
      if (!type || type.kind !== "func") throw new Error(`call of function ${index} without a function type`);
      return plain(type.params, type.results);
    }
    case 0x11: case 0x13: pos = leb(buf, pos); pos = leb(buf, pos); return other();
    case 0x1a: return plain(1, 0);
    case 0x1b: return plain(3, 1);
    case 0x1c: { let n; [n, pos] = readLeb(buf, pos); for (let i = 0; i < n; i++) pos = skipValueType(buf, pos); return plain(3, 1); }
    case 0x1f: {
      pos = skipBlockType(buf, pos);
      let n; [n, pos] = readLeb(buf, pos);
      for (let i = 0; i < n; i++) { const kind = buf[pos++]; if (kind === 0 || kind === 1) pos = leb(buf, pos); pos = leb(buf, pos); }
      return other();
    }
    case 0x23: pos = leb(buf, pos); return plain(0, 1);
    case 0x24: pos = leb(buf, pos); return plain(1, 0);
    case 0x41: case 0x42: pos = readSleb(buf, pos); return plain(0, 1);
    case 0x43: pos += 4; return plain(0, 1);
    case 0x44: pos += 8; return plain(0, 1);
    case 0xd0: pos = readSleb(buf, pos); return plain(0, 1);
    case 0xd1: return plain(1, 1);
    case 0xd2: pos = leb(buf, pos); return plain(0, 1);
    case 0xd3: return plain(2, 1);
    case 0xd4: return plain(1, 1);
    default: break;
  }
  if (op >= 0x28 && op <= 0x3e) { pos = skipMemarg(buf, pos); return other(); }
  if (op === 0xfb) {
    let sub; [sub, pos] = readLeb(buf, pos);
    let a, b;
    switch (sub) {
      case 0: { [a, pos] = readLeb(buf, pos); const t = types[a]; if (!t || t.kind !== "struct") throw new Error("struct.new of a non-struct type"); return plain(t.fields, 1); }
      case 1: pos = leb(buf, pos); return plain(0, 1);
      case 2: case 3: case 4: pos = leb(buf, leb(buf, pos)); return plain(1, 1);
      case 5: pos = leb(buf, leb(buf, pos)); return plain(2, 0);
      case 6: pos = leb(buf, pos); return plain(2, 1);
      case 7: pos = leb(buf, pos); return plain(1, 1);
      case 8: pos = leb(buf, pos); [b, pos] = readLeb(buf, pos); return plain(b, 1);
      case 9: case 10: pos = leb(buf, leb(buf, pos)); return plain(2, 1);
      case 11: case 12: case 13: pos = leb(buf, pos); return plain(2, 1);
      case 14: pos = leb(buf, pos); return plain(3, 0);
      case 15: return plain(1, 1);
      case 16: pos = leb(buf, pos); return plain(4, 0);
      case 17: pos = leb(buf, leb(buf, pos)); return plain(5, 0);
      case 18: case 19: pos = leb(buf, leb(buf, pos)); return plain(4, 0);
      case 20: case 21: case 22: case 23: pos = readSleb(buf, pos); return plain(1, 1);
      case 24: case 25: pos += 1; pos = leb(buf, pos); pos = readSleb(buf, pos); pos = readSleb(buf, pos); return other();
      case 26: case 27: case 28: case 29: case 30: return plain(1, 1);
      default: throw new Error(`unknown 0xfb opcode ${sub}`);
    }
  }
  if (op === 0xfc) {
    let sub; [sub, pos] = readLeb(buf, pos);
    if (sub <= 7) return plain(1, 1);
    if (sub === 8 || sub === 10 || sub === 12 || sub === 14) { pos = leb(buf, leb(buf, pos)); return other(); }
    if (sub === 9 || sub === 11 || sub === 13 || (sub >= 15 && sub <= 17)) { pos = leb(buf, pos); return other(); }
    throw new Error(`unknown 0xfc opcode ${sub}`);
  }
  throw new Error(`unsupported opcode 0x${op.toString(16)}`);
}

// Chunks [start, end) (absolute offsets) of one function body.  Cut points are kept per operand
// stack height: two points of the same height delimit a movable segment as long as the stack
// did not drop below that height between them (a drop discards the points above it).  Segments
// of a deeper level sit inside one statement of a shallower level, so a shallower chunk that
// fits --max-chunk wins, and a shallower statement that is too large leaves room for the
// chunks inside it.
export function findChunks(buf, body, types, funcTypes, options) {
  let pos = body.start, groups;
  [groups, pos] = readLeb(buf, pos);
  for (let i = 0; i < groups; i++) { pos = leb(buf, pos); pos = skipValueType(buf, pos); }
  const candidates = [];
  function flush(points) {
    if (!points || points.length < 2) return;
    let i = 0;
    while (i < points.length - 1) {
      let j = i + 1;
      while (j + 1 < points.length && points[j + 1] - points[i] <= options.maxChunk) j++;
      if (points[j] - points[i] >= options.minRun) candidates.push([points[i], points[j]]);
      i = j;
    }
  }
  let levels = null, height = 0, top = 0;
  const flushAll = () => { if (levels) for (const points of levels.values()) flush(points); levels = null; };
  while (pos < body.end) {
    const at = pos;
    const instruction = decode(buf, pos, types, funcTypes);
    pos = instruction.pos;
    if (!instruction.simple) { flushAll(); continue; }
    if (!levels) { levels = new Map([[0, [at]]]); height = 0; top = 0; }
    const low = height - instruction.pops;
    for (let h = top; h > low; h--) { flush(levels.get(h)); levels.delete(h); }
    height = low + instruction.pushes;
    top = Math.max(low, height);
    let points = levels.get(height);
    if (!points) levels.set(height, points = []);
    points.push(pos);
  }
  if (pos !== body.end) throw new Error("decoder ran past the function body");
  flushAll();
  candidates.sort((x, y) => x[0] - y[0] || (y[1] - y[0]) - (x[1] - x[0]));
  const chunks = [];
  let lastEnd = -1;
  for (let i = 0; i < candidates.length; i++) {
    const [a, z] = candidates[i];
    if (a < lastEnd) continue;
    if (z - a > options.maxChunk) {
      let inner = false;
      for (let j = i + 1; j < candidates.length && candidates[j][0] < z; j++) {
        if (candidates[j][1] <= z) { inner = true; break; }
      }
      if (inner) continue;
    }
    chunks.push([a, z]);
    lastEnd = z;
  }
  return chunks;
}

function encodeSleb(value) {
  const out = [];
  for (;;) {
    const byte = value & 0x7f;
    value = Math.floor(value / 128);
    if ((value === 0 && (byte & 0x40) === 0) || (value === -1 && (byte & 0x40) !== 0)) { out.push(byte); return out; }
    out.push(byte | 0x80);
  }
}

// Large array literals: array.new_fixed T N whose N operands all lie in one straight-line run.
// TeaVM builds WasmGCResources' resource tables this way (N = 4096): every operand stays live on
// the operand stack until the last one is pushed, which is what kept TurboFan's register
// allocator busy for 14 s and gigabytes.  Returns [{start, ends, at, next, type, n}].
export function findArrayLiterals(buf, body, types, funcTypes, minArray) {
  let pos = body.start, groups;
  [groups, pos] = readLeb(buf, pos);
  for (let i = 0; i < groups; i++) { pos = leb(buf, pos); pos = skipValueType(buf, pos); }
  const found = [];
  // boundary.get(h): the latest position in the current run at which the operand stack held h
  // values (relative to the run's start) and has not dropped below h since.
  let boundary = null, height = 0, top = 0;
  while (pos < body.end) {
    const at = pos;
    const instruction = decode(buf, pos, types, funcTypes);
    pos = instruction.pos;
    if (!instruction.simple) { boundary = null; continue; }
    if (!boundary) { boundary = new Map([[0, at]]); height = 0; top = 0; }
    const low = height - instruction.pops;
    if (buf[at] === 0xfb && buf[at + 1] === 8 && instruction.pops >= minArray) {
      const [type] = readLeb(buf, at + 2);
      const t = types[type];
      const n = instruction.pops;
      const start = boundary.get(height - n);
      if (t && t.kind === "array" && t.mutable && t.defaultable && start !== undefined) {
        const ends = [];
        for (let k = 1; k <= n; k++) ends.push(k === n ? at : boundary.get(height - n + k));
        if (ends.every((e) => e !== undefined)) {
          while (found.length && found[found.length - 1].start >= start) found.pop(); // keep the outermost
          found.push({start, ends, at, next: pos, type, n});
        }
      }
    }
    for (let h = top; h > low; h--) boundary.delete(h);
    height = low + instruction.pushes;
    top = Math.max(low, height);
    boundary.set(height, pos);
  }
  return found;
}

// Rewrites every large array literal as array.new_default into a new global, one array.set per
// operand (straight-line statements the outliner can move) and a read of the global, which is
// cleared again so that it does not keep the array alive.
export function spillArrayLiterals(buf, options = {}) {
  const opts = {...DEFAULTS, ...options};
  const list = sections(buf);
  const bodies = functionBodies(buf, list);
  const funcTypes = functionTypes(buf, list);
  const types = parseTypes(buf, list.find((s) => s.id === 1));
  const globalSection = list.find((s) => s.id === 6);
  let definedGlobals = 0;
  if (globalSection) [definedGlobals] = readLeb(buf, globalSection.start);
  const firstGlobal = funcTypes.importedGlobals + definedGlobals;
  const newGlobals = [];
  const rewritten = new Map();
  let literals = 0;
  for (let i = 0; i < bodies.length; i++) {
    if (bodies[i].size < opts.minFunction) continue;
    let found;
    try { found = findArrayLiterals(buf, bodies[i], types, funcTypes, opts.minArray); } catch (_) { continue; }
    if (!found.length) continue;
    const pieces = [];
    let from = bodies[i].start;
    for (const literal of found) {
      const g = encodeLeb(firstGlobal + newGlobals.length), t = encodeLeb(literal.type);
      newGlobals.push(literal.type);
      pieces.push(buf.subarray(from, literal.start));
      pieces.push(Uint8Array.from([0x41, ...encodeSleb(literal.n), 0xfb, 7, ...t, 0x24, ...g]));
      let operand = literal.start;
      for (let k = 0; k < literal.n; k++) {
        pieces.push(Uint8Array.from([0x23, ...g, 0x41, ...encodeSleb(k)]));
        pieces.push(buf.subarray(operand, literal.ends[k]));
        pieces.push(Uint8Array.from([0xfb, 14, ...t]));
        operand = literal.ends[k];
      }
      pieces.push(Uint8Array.from([0x23, ...g, 0xd4, 0xd0, ...encodeSleb(literal.type), 0x24, ...g]));
      from = literal.next;
      literals++;
    }
    pieces.push(buf.subarray(from, bodies[i].end));
    let size = 0;
    for (const piece of pieces) size += piece.length;
    rewritten.set(i, [Uint8Array.from(encodeLeb(size)), ...pieces]);
  }
  if (!literals) return {module: buf, literals: 0};
  const parts = [buf.subarray(0, 8)];
  const entries = [];
  for (const type of newGlobals) entries.push(0x63, ...encodeSleb(type), 1, 0xd0, ...encodeSleb(type), 0x0b);
  const globals = (count, rest) => {
    const head = encodeLeb(count + newGlobals.length);
    parts.push(Uint8Array.from([6, ...encodeLeb(head.length + rest.length + entries.length), ...head]), rest,
      Uint8Array.from(entries));
  };
  let globalsWritten = false;
  for (const s of list) {
    // Without a global section, the new one goes right after the memory/tag sections.
    if (!globalSection && !globalsWritten && [7, 8, 9, 10, 11, 12].includes(s.id)) { globals(0, new Uint8Array(0)); globalsWritten = true; }
    if (s.id === 6) {
      let p = s.start, count;
      [count, p] = readLeb(buf, p);
      globals(count, buf.subarray(p, s.end));
      globalsWritten = true;
    } else if (s.id === 10) {
      const pieces = [Uint8Array.from(encodeLeb(bodies.length))];
      for (let i = 0; i < bodies.length; i++) {
        const replaced = rewritten.get(i);
        if (replaced) for (const piece of replaced) pieces.push(piece);
        else pieces.push(buf.subarray(bodies[i].sizeStart, bodies[i].end));
      }
      let total = 0;
      for (const piece of pieces) total += piece.length;
      parts.push(Uint8Array.from([10, ...encodeLeb(total)]));
      for (const piece of pieces) parts.push(piece);
    } else {
      parts.push(buf.subarray(s.headerStart, s.end));
    }
  }
  let length = 0;
  for (const part of parts) length += part.length;
  const out = new Uint8Array(length);
  let o = 0;
  for (const part of parts) { out.set(part, o); o += part.length; }
  return {module: out, literals};
}

// Returns {module, functions, chunks, movedBytes}; module is the input when nothing changed.
export function outline(input, options = {}) {
  const opts = {...DEFAULTS, ...options};
  const spilled = spillArrayLiterals(input, opts);
  const buf = spilled.module;
  const list = sections(buf);
  const bodies = functionBodies(buf, list);
  const funcTypes = functionTypes(buf, list);
  const imported = funcTypes.length - bodies.length;
  const typeSection = list.find((s) => s.id === 1);
  const types = parseTypes(buf, typeSection);
  const plan = new Map();
  const skipped = [];
  for (let i = 0; i < bodies.length; i++) {
    if (bodies[i].size < opts.minFunction) continue;
    try {
      const chunks = findChunks(buf, bodies[i], types, funcTypes, opts);
      if (chunks.length) plan.set(i, chunks);
    } catch (error) {
      skipped.push({index: imported + i, reason: error.message});
    }
  }
  if (!plan.size) return {module: buf, functions: 0, chunks: 0, movedBytes: 0, skipped, literals: spilled.literals};
  let voidType = types.findIndex((t) => t.kind === "func" && t.params === 0 && t.results === 0);
  const addType = voidType < 0;
  if (addType) voidType = types.length;
  const parts = [buf.subarray(0, 8)];
  const bytes = (array) => Uint8Array.from(array);
  const newBodies = [];
  let next = imported + bodies.length, chunkCount = 0, movedBytes = 0;
  const rewritten = new Map();
  for (const [i, chunks] of plan) {
    const body = bodies[i];
    const pieces = [];
    let from = body.start;
    for (const [a, z] of chunks) {
      pieces.push(buf.subarray(from, a), bytes([0x10, ...encodeLeb(next++)]));
      newBodies.push(buf.subarray(a, z));
      from = z;
      chunkCount++;
      movedBytes += z - a;
    }
    pieces.push(buf.subarray(from, body.end));
    let size = 0;
    for (const piece of pieces) size += piece.length;
    rewritten.set(i, [bytes(encodeLeb(size)), ...pieces]);
  }
  for (const s of list) {
    if (s.id === 1 && addType) {
      let p = s.start, count;
      [count, p] = readLeb(buf, p);
      const head = encodeLeb(count + 1);
      const size = head.length + (s.end - p) + 3;
      parts.push(bytes([1, ...encodeLeb(size), ...head]), buf.subarray(p, s.end), bytes([0x60, 0, 0]));
    } else if (s.id === 3) {
      let p = s.start, count;
      [count, p] = readLeb(buf, p);
      const tail = [];
      for (let i = 0; i < newBodies.length; i++) tail.push(...encodeLeb(voidType));
      const head = encodeLeb(count + newBodies.length);
      const size = head.length + (s.end - p) + tail.length;
      parts.push(bytes([3, ...encodeLeb(size), ...head]), buf.subarray(p, s.end), bytes(tail));
    } else if (s.id === 10) {
      const pieces = [bytes(encodeLeb(bodies.length + newBodies.length))];
      for (let i = 0; i < bodies.length; i++) {
        const replaced = rewritten.get(i);
        if (replaced) for (const piece of replaced) pieces.push(piece);
        else pieces.push(buf.subarray(bodies[i].sizeStart, bodies[i].end));
      }
      for (const code of newBodies) pieces.push(bytes([...encodeLeb(code.length + 2), 0]), code, bytes([0x0b]));
      let total = 0;
      for (const piece of pieces) total += piece.length;
      parts.push(bytes([10, ...encodeLeb(total)]));
      for (const piece of pieces) parts.push(piece);
    } else {
      parts.push(buf.subarray(s.headerStart, s.end));
    }
  }
  let length = 0;
  for (const part of parts) length += part.length;
  const out = new Uint8Array(length);
  let at = 0;
  for (const part of parts) { out.set(part, at); at += part.length; }
  return {module: out, functions: plan.size, chunks: chunkCount, movedBytes, skipped, literals: spilled.literals,
    largest: [...plan.keys()].map((i) => ({index: imported + i, bytes: bodies[i].size, chunks: plan.get(i).length}))
      .sort((x, y) => y.bytes - x.bytes).slice(0, 8)};
}

function section(id, payload) {
  return [id, ...encodeLeb(payload.length), ...payload];
}

function selfTest() {
  // Types: 0 [] -> [], 1 struct (mut i32), 2 [] -> [i32], 3 [i32] -> [i32].
  // Globals: 0 mut i32 = 0, 1 mut (ref null 1) = null.
  // Function 0 (start): 60 statements on global 0 and a struct in global 1 (no locals).
  // Function 1 "get": global 0 + field of global 1.
  // Function 2 "mix" (i32) -> i32: a local, a block with a loop, and straight-line statements
  // inside the block before and after the loop; returns the local plus global 0.
  const statements = (count, base) => {
    const out = [];
    for (let k = 1; k <= count; k++) {
      out.push(0x23, 0, 0x41, (base + k) & 0x3f, 0x6a, 0x24, 0);
      out.push(0x41, k & 0x3f, 0xfb, 0, 1, 0x24, 1);
    }
    return out;
  };
  const startBody = [0, ...statements(60, 0), 0x0b];
  const getBody = [0, 0x23, 0, 0x23, 1, 0xfb, 2, 1, 0, 0x6a, 0x0b];
  const mixBody = [1, 1, 0x7f,
    0x02, 0x40, ...statements(30, 5),
    0x03, 0x40, 0x20, 0, 0x41, 1, 0x6b, 0x22, 0, 0x0d, 0, 0x0b,
    ...statements(30, 9), 0x0b,
    0x20, 1, 0x23, 0, 0x6a, 0x0b];
  const bytes = [0x00, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
    ...section(1, [4, 0x60, 0, 0, 0x5f, 1, 0x7f, 1, 0x60, 0, 1, 0x7f, 0x60, 1, 0x7f, 1, 0x7f]),
    ...section(3, [3, 0, 2, 3]),
    ...section(6, [2, 0x7f, 1, 0x41, 0, 0x0b, 0x63, 1, 1, 0xd0, 1, 0x0b]),
    ...section(7, [2, 3, 0x67, 0x65, 0x74, 0, 1, 3, 0x6d, 0x69, 0x78, 0, 2]),
    ...section(8, [0]),
    ...section(10, [3, ...encodeLeb(startBody.length), ...startBody, ...encodeLeb(getBody.length), ...getBody,
      ...encodeLeb(mixBody.length), ...mixBody]),
  ];
  const input = Uint8Array.from(bytes);
  const run = (module) => {
    const exports = new WebAssembly.Instance(new WebAssembly.Module(module)).exports;
    return [exports.get(), exports.mix(5), exports.get()];
  };
  const before = run(input);
  const result = outline(input, {minFunction: 64, minRun: 32, maxChunk: 64});
  const after = run(result.module);
  const failures = [];
  if (JSON.stringify(before) !== JSON.stringify(after)) failures.push(`results changed: ${before} -> ${after}`);
  if (result.functions !== 2) failures.push(`expected 2 outlined functions, got ${result.functions}`);
  if (result.chunks < 10) failures.push(`expected at least 10 chunks, got ${result.chunks}`);
  // An array literal of 300 elements ("global.get 0; i32.const k; i32.add" each) stored in a
  // local; returns element 299 + element 7 + length = 48 + 12 + 300.
  const elements = [];
  for (let k = 0; k < 300; k++) elements.push(0x23, 0, 0x41, k & 0x3f, 0x6a);
  const arrayCode = [1, 1, 0x63, 1, ...elements, 0xfb, 8, 1, ...encodeLeb(300), 0x21, 0,
    0x20, 0, 0x41, ...encodeSleb(299), 0xfb, 11, 1, 0x20, 0, 0x41, 7, 0xfb, 11, 1, 0x6a,
    0x20, 0, 0xfb, 15, 0x6a, 0x0b];
  const literalModule = Uint8Array.from([0x00, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
    ...section(1, [3, 0x60, 0, 0, 0x5e, 0x7f, 1, 0x60, 0, 1, 0x7f]),
    ...section(3, [1, 2]),
    ...section(6, [1, 0x7f, 1, 0x41, 5, 0x0b]),
    ...section(7, [1, 3, 0x61, 0x72, 0x72, 0, 0]),
    ...section(10, [1, ...encodeLeb(arrayCode.length), ...arrayCode])]);
  const callArr = (module) => new WebAssembly.Instance(new WebAssembly.Module(module)).exports.arr();
  const literalResult = outline(literalModule, {minFunction: 64, minRun: 32, maxChunk: 128, minArray: 64});
  const literalBefore = callArr(literalModule), literalAfter = callArr(literalResult.module);
  if (literalResult.literals !== 1) failures.push(`expected 1 spilled array literal, got ${literalResult.literals}`);
  if (literalBefore !== 360 || literalAfter !== 360) failures.push(`array literal results ${literalBefore} -> ${literalAfter}, expected 360`);
  if (literalResult.chunks < 5) failures.push(`expected the spilled literal to be outlined, got ${literalResult.chunks} chunks`);
  const untouched = outline(input, {minFunction: 1 << 20});
  if (untouched.module !== input) failures.push("a module without large functions was rewritten");
  if (failures.length) {
    for (const failure of failures) console.error(`self-test FAILED: ${failure}`);
    process.exit(1);
  }
  console.log(`wasm-outline-init self-test passed: ${result.chunks} chunks from ${result.functions} functions, results ${after.join("/")} unchanged; array literal spilled into ${literalResult.chunks} chunks, result ${literalAfter}`);
}

function main(argv) {
  if (argv[0] === "--self-test") return selfTest();
  const file = argv[0];
  if (!file) {
    console.error("usage: wasm-outline-init.mjs <module.wasm> [--out <file>] [--min-function <bytes>] [--min-run <bytes>] [--max-chunk <bytes>] | --self-test");
    process.exit(2);
  }
  let outFile = file;
  const options = {};
  for (let i = 1; i < argv.length; i++) {
    const value = () => { const v = Number(argv[++i]); if (!Number.isInteger(v) || v < 16) { console.error(`${argv[i - 1]} needs an integer >= 16`); process.exit(2); } return v; };
    if (argv[i] === "--out") outFile = argv[++i];
    else if (argv[i] === "--min-function") options.minFunction = value();
    else if (argv[i] === "--min-run") options.minRun = value();
    else if (argv[i] === "--max-chunk") options.maxChunk = value();
    else { console.error(`unknown argument ${argv[i]}`); process.exit(2); }
  }
  const input = new Uint8Array(fs.readFileSync(file));
  const result = outline(input, options);
  if (result.module !== input || outFile !== file) fs.writeFileSync(outFile, result.module);
  const name = file.replace(/^.*[\\/]/, "");
  for (const skip of result.skipped) console.log(`wasm-outline-init: ${name} function ${skip.index} left as is (${skip.reason})`);
  if (result.chunks) {
    const largest = result.largest.map((f) => `${f.index}:${f.bytes}B/${f.chunks}`).join(" ");
    console.log(`wasm-outline-init: ${name} ${result.literals} array literals spilled; ${result.movedBytes} bytes of ${result.functions} functions -> ${result.chunks} new functions (largest ${largest})`);
  } else {
    console.log(`wasm-outline-init: ${name} unchanged`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
