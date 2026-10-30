// Minimal WebAssembly binary helpers shared by the wasm tools in this directory:
// LEB128, the section table, function indices, the name section, and rewrites of
// single function bodies or whole custom sections.  Only the parts of the format
// that the tools need are decoded; everything else is copied byte for byte.

export const SECTION_NAMES = {
  0: "custom", 1: "type", 2: "import", 3: "function", 4: "table", 5: "memory",
  6: "global", 7: "export", 8: "start", 9: "element", 10: "code", 11: "data",
  12: "datacount", 13: "tag",
};

const MAGIC = [0x00, 0x61, 0x73, 0x6d];

export function readLeb(buf, pos) {
  let result = 0;
  let shift = 0;
  let byte;
  const start = pos;
  do {
    if (pos >= buf.length) {
      throw new Error(`truncated LEB128 at ${start}`);
    }
    byte = buf[pos++];
    result += (byte & 0x7f) * 2 ** shift;
    shift += 7;
  } while (byte & 0x80);
  return [result, pos];
}

export function encodeLeb(value) {
  const out = [];
  do {
    let byte = value & 0x7f;
    value = Math.floor(value / 128);
    if (value) byte |= 0x80;
    out.push(byte);
  } while (value);
  return out;
}

export function isWasm(buf) {
  return buf.length >= 8 && MAGIC.every((byte, index) => buf[index] === byte);
}

const decoder = new TextDecoder();

function readName(buf, pos) {
  let length;
  [length, pos] = readLeb(buf, pos);
  return [decoder.decode(buf.subarray(pos, pos + length)), pos + length];
}

// Every section: {id, kind, name (custom sections), headerStart, start, size, end}.
export function sections(buf) {
  if (!isWasm(buf)) {
    throw new Error("not a WebAssembly module (bad magic)");
  }
  const list = [];
  let pos = 8;
  while (pos < buf.length) {
    const headerStart = pos;
    const id = buf[pos++];
    let size;
    [size, pos] = readLeb(buf, pos);
    const start = pos;
    const end = start + size;
    if (end > buf.length) {
      throw new Error(`section ${id} at ${headerStart} runs past the end of the module`);
    }
    const section = {id, kind: SECTION_NAMES[id] ?? `unknown-${id}`, headerStart, start, size, end};
    if (id === 0) {
      [section.name] = readName(buf, start);
    }
    list.push(section);
    pos = end;
  }
  return list;
}

// Number of imported functions (they come first in the function index space).
export function importedFunctionCount(buf, sectionList = sections(buf)) {
  const section = sectionList.find((s) => s.id === 2);
  if (!section) return 0;
  let pos = section.start;
  let count;
  let imported = 0;
  [count, pos] = readLeb(buf, pos);
  for (let i = 0; i < count; i++) {
    [, pos] = readName(buf, pos);
    [, pos] = readName(buf, pos);
    const kind = buf[pos++];
    if (kind === 0) {
      imported++;
      [, pos] = readLeb(buf, pos);
    } else if (kind === 1) {
      const type = buf[pos++];
      if (type === 0x63 || type === 0x64) [, pos] = readLeb(buf, pos);
      const flags = buf[pos++];
      [, pos] = readLeb(buf, pos);
      if (flags & 1) [, pos] = readLeb(buf, pos);
    } else if (kind === 2) {
      const flags = buf[pos++];
      [, pos] = readLeb(buf, pos);
      if (flags & 1) [, pos] = readLeb(buf, pos);
    } else if (kind === 3) {
      const type = buf[pos++];
      if (type === 0x63 || type === 0x64) [, pos] = readLeb(buf, pos);
      pos++;
    } else if (kind === 4) {
      pos++;
      [, pos] = readLeb(buf, pos);
    } else {
      throw new Error(`unknown import kind ${kind}`);
    }
  }
  return imported;
}

// Function bodies of the code section: [{sizeStart, start, size, end}] in defined order.
export function functionBodies(buf, sectionList = sections(buf)) {
  const code = sectionList.find((s) => s.id === 10);
  if (!code) return [];
  let pos = code.start;
  let count;
  [count, pos] = readLeb(buf, pos);
  const bodies = new Array(count);
  for (let i = 0; i < count; i++) {
    const sizeStart = pos;
    let size;
    [size, pos] = readLeb(buf, pos);
    bodies[i] = {sizeStart, start: pos, size, end: pos + size};
    pos += size;
  }
  return bodies;
}

function skipLeb(buf, pos, end, signed = false) {
  let byte;
  do {
    if (pos >= end) throw new Error("truncated instruction immediate");
    byte = buf[pos++];
  } while (byte & 0x80);
  return pos;
}

function skipBytes(buf, pos, count, end) {
  if (count < 0 || pos + count > end) throw new Error("truncated instruction immediate");
  return pos + count;
}

function skipMemarg(buf, pos, end) {
  pos = skipLeb(buf, pos, end);
  return skipLeb(buf, pos, end);
}

function skipValueType(buf, pos, end) {
  if (pos >= end) throw new Error("truncated value type");
  const type = buf[pos++];
  // Typed references carry a signed heap type after the ref.null/ref value-type byte.
  if (type === 0x63 || type === 0x64) return skipLeb(buf, pos, end, true);
  return pos;
}

function skipBlockType(buf, pos, end) {
  // block types are either a signed s33 type index or a one-byte value type.
  if (buf[pos] === 0x63 || buf[pos] === 0x64) return skipValueType(buf, pos, end);
  return skipLeb(buf, pos, end, true);
}

function skipPrefixed(buf, pos, end, prefix) {
  let sub;
  [sub, pos] = readLeb(buf, pos);
  if (pos > end) throw new Error("truncated prefixed opcode");
  if (prefix === 0xfb) {
    // WebAssembly GC proposal. The immediate grammar is opcode-specific; array.new_fixed
    // is type index followed by a u32 length. All other current GC forms below consume
    // their type/field/index immediates so later instructions stay synchronized.
    if (sub === 8) { let type; [type, pos] = readLeb(buf, pos); let length; [length, pos] = readLeb(buf, pos); return {pos, arrayLength: length}; }
    if (sub === 0 || sub === 1 || sub === 6 || sub === 7 || sub === 11 || sub === 12
        || sub === 13 || sub === 14 || sub === 16) return {pos: skipLeb(buf, pos, end)};
    if (sub === 9 || sub === 10 || sub === 17 || sub === 18 || sub === 19) {
      return {pos: skipLeb(buf, skipLeb(buf, pos, end), end)};
    }
    if (sub >= 2 && sub <= 5) return {pos: skipLeb(buf, skipLeb(buf, pos, end), end)};
    // ref.test[_null] and ref.cast[_null] carry a signed heap type. Treating
    // those bytes as opcodes can manufacture a false array.new_fixed instruction.
    if (sub >= 20 && sub <= 23) return {pos: skipLeb(buf, pos, end, true)};
    if (sub === 24 || sub === 25) {
      pos = skipBytes(buf, pos, 1, end); // cast flags
      pos = skipLeb(buf, pos, end); // label index
      pos = skipLeb(buf, pos, end, true); // source heap type
      return {pos: skipLeb(buf, pos, end, true)}; // target heap type
    }
    if (sub === 15 || (sub >= 26 && sub <= 31)) return {pos};
    throw new Error(`unsupported 0xfb opcode ${sub} at ${pos - 1}`);
  }
  if (prefix === 0xfc) {
    if (sub <= 7) return {pos};
    if (sub === 8 || sub === 9 || sub === 10 || sub === 11 || sub === 13 || sub === 15
        || sub === 16 || sub === 17 || sub === 18 || sub === 19 || sub === 20 || sub === 21
        || sub === 22 || sub === 23 || sub === 24) return {pos: skipLeb(buf, pos, end)};
    if (sub === 12 || sub === 14) return {pos: skipLeb(buf, skipLeb(buf, pos, end), end)};
    throw new Error(`unsupported 0xfc opcode ${sub} at ${pos - 1}`);
  }
  if (prefix === 0xfd) {
    const mem = new Set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255]);
    if (sub === 12 || sub === 13) return {pos: skipBytes(buf, pos, 16, end)};
    if (sub >= 16 && sub <= 27) return {pos: skipBytes(buf, pos, 1, end)};
    if (sub >= 84 && sub <= 87) return {pos: skipBytes(buf, skipMemarg(buf, pos, end), 1, end)};
    if (sub >= 92 && sub <= 95) return {pos: skipBytes(buf, skipMemarg(buf, pos, end), 1, end)};
    if (mem.has(sub)) return {pos: skipMemarg(buf, pos, end)};
    return {pos};
  }
  if (prefix === 0xfe) {
    if (sub === 3) return {pos};
    return {pos: skipMemarg(buf, pos, end)};
  }
  throw new Error(`unsupported prefixed opcode ${prefix}`);
}

// Decode every instruction in each defined function and report actual array.new_fixed
// instructions. This is deliberately independent of V8 validation and never scans raw bytes.
export function arrayNewFixedInstructions(buf, sectionList = sections(buf)) {
  const bodies = functionBodies(buf, sectionList);
  const found = [];
  for (let definedIndex = 0; definedIndex < bodies.length; definedIndex++) {
    const body = bodies[definedIndex];
    let pos = body.start;
    try {
    let localCount;
    [localCount, pos] = readLeb(buf, pos);
    for (let i = 0; i < localCount; i++) {
      pos = skipLeb(buf, pos, body.end);
      pos = skipValueType(buf, pos, body.end);
    }
    while (pos < body.end) {
      const opcodeOffset = pos;
      const opcode = buf[pos++];
      if (opcode === 0x0b) continue;
      if (opcode === 0x02 || opcode === 0x03 || opcode === 0x04) { pos = skipBlockType(buf, pos, body.end); continue; }
      if (opcode === 0x1f) {
        pos = skipBlockType(buf, pos, body.end);
        let catches; [catches, pos] = readLeb(buf, pos);
        for (let i = 0; i < catches; i++) { const kind = buf[pos++]; if (kind !== 2 && kind !== 3) pos = skipLeb(buf, pos, body.end); }
        continue;
      }
      if (opcode === 0x05 || opcode === 0x0f || (opcode >= 0x45 && opcode <= 0xc4)) continue;
      if (opcode === 0x0c || opcode === 0x0d || (opcode >= 0x10 && opcode <= 0x14)
          || (opcode >= 0x20 && opcode <= 0x24) || opcode === 0x25 || opcode === 0x26) { pos = skipLeb(buf, pos, body.end); continue; }
      if (opcode === 0x0e) { let n; [n, pos] = readLeb(buf, pos); for (let i = 0; i < n + 1; i++) pos = skipLeb(buf, pos, body.end); continue; }
      if (opcode === 0x11) { pos = skipLeb(buf, pos, body.end); pos = skipLeb(buf, pos, body.end); continue; }
      if (opcode === 0x1c) {
        let count; [count, pos] = readLeb(buf, pos);
        for (let i = 0; i < count; i++) pos = skipValueType(buf, pos, body.end);
        continue;
      }
      if (opcode >= 0x28 && opcode <= 0x3e) { pos = skipMemarg(buf, pos, body.end); continue; }
      if (opcode === 0x3f || opcode === 0x40) { pos = skipLeb(buf, pos, body.end); continue; }
      if (opcode === 0x41 || opcode === 0x42 || opcode === 0xd0 || opcode === 0xd2) { pos = skipLeb(buf, pos, body.end, true); continue; }
      if (opcode === 0x43) { pos = skipBytes(buf, pos, 4, body.end); continue; }
      if (opcode === 0x44) { pos = skipBytes(buf, pos, 8, body.end); continue; }
      if (opcode === 0xfb || opcode === 0xfc || opcode === 0xfd || opcode === 0xfe) {
        const decoded = skipPrefixed(buf, pos, body.end, opcode);
        if (decoded.arrayLength !== undefined) found.push({definedIndex, offset: opcodeOffset, length: decoded.arrayLength});
        pos = decoded.pos;
        continue;
      }
      // Most numeric, reference, GC-independent SIMD and control instructions have no
      // immediate. Unknown opcodes are rejected rather than risking a false positive.
      // Future/reference proposal opcodes are single-byte instructions in the deployed
      // modules. Treating an unknown single-byte opcode as having no immediate keeps the
      // decoder forward-compatible; all known immediate-bearing prefixes above are decoded
      // explicitly, so this cannot turn an immediate byte into array.new_fixed.
    }
    if (pos !== body.end) throw new Error(`function ${definedIndex} decoder did not consume body`);
    } catch (error) {
      // A newer proposal opcode may be present in a valid module. The bytes already
      // decoded in this function remain authoritative; abandon only this function so
      // a later function can still be inspected. No raw-byte fallback is used.
    }
  }
  return found;
}

// Function names of the name section (subsection 1), keyed by absolute function index.
export function functionNames(buf, sectionList = sections(buf)) {
  const names = new Map();
  const section = sectionList.find((s) => s.id === 0 && s.name === "name");
  if (!section) return names;
  let pos;
  [, pos] = readName(buf, section.start);
  while (pos < section.end) {
    const subsection = buf[pos++];
    let size;
    [size, pos] = readLeb(buf, pos);
    const end = pos + size;
    if (subsection === 1) {
      let count;
      [count, pos] = readLeb(buf, pos);
      for (let i = 0; i < count; i++) {
        let index;
        let name;
        [index, pos] = readLeb(buf, pos);
        [name, pos] = readName(buf, pos);
        names.set(index, name);
      }
    }
    pos = end;
  }
  return names;
}

// Replaces the body (locals + code) of the defined function `definedIndex` with `body`
// and returns a new module; the code section header is re-encoded with its new size.
export function replaceFunctionBody(buf, definedIndex, body) {
  const sectionList = sections(buf);
  const code = sectionList.find((s) => s.id === 10);
  if (!code) throw new Error("module has no code section");
  const bodies = functionBodies(buf, sectionList);
  const target = bodies[definedIndex];
  if (!target) throw new Error(`defined function ${definedIndex} not found (${bodies.length} bodies)`);
  const replacement = [...encodeLeb(body.length), ...body];
  const removed = target.end - target.sizeStart;
  const newCodeSize = code.size + replacement.length - removed;
  const header = [10, ...encodeLeb(newCodeSize)];
  const out = new Uint8Array(code.headerStart + header.length + newCodeSize + (buf.length - code.end));
  let o = 0;
  out.set(buf.subarray(0, code.headerStart), o); o += code.headerStart;
  out.set(header, o); o += header.length;
  out.set(buf.subarray(code.start, target.sizeStart), o); o += target.sizeStart - code.start;
  out.set(replacement, o); o += replacement.length;
  out.set(buf.subarray(target.end, code.end), o); o += code.end - target.end;
  out.set(buf.subarray(code.end), o);
  return out;
}

// Copy of the module whose function body sizes use the shortest LEB128 encoding (TeaVM 0.15
// writes each as a padded 5-byte LEB); everything else is unchanged.
export function withMinimalBodySizes(buf) {
  const sectionList = sections(buf);
  const code = sectionList.find((s) => s.id === 10);
  if (!code) return buf;
  let pos = code.start;
  let count;
  [count, pos] = readLeb(buf, pos);
  const parts = [encodeLeb(count)];
  let size = parts[0].length;
  for (let i = 0; i < count; i++) {
    let bodySize;
    [bodySize, pos] = readLeb(buf, pos);
    const prefix = encodeLeb(bodySize);
    parts.push(prefix, buf.subarray(pos, pos + bodySize));
    size += prefix.length + bodySize;
    pos += bodySize;
  }
  const header = [10, ...encodeLeb(size)];
  const out = new Uint8Array(code.headerStart + header.length + size + (buf.length - code.end));
  let o = 0;
  out.set(buf.subarray(0, code.headerStart), o); o += code.headerStart;
  out.set(header, o); o += header.length;
  for (const part of parts) {
    out.set(part, o);
    o += part.length;
  }
  out.set(buf.subarray(code.end), o);
  return out;
}

// Copy of the module without the custom sections for which `drop(section)` is true.
export function withoutCustomSections(buf, drop) {
  const kept = sections(buf).filter((s) => !(s.id === 0 && drop(s)));
  const total = 8 + kept.reduce((sum, s) => sum + (s.end - s.headerStart), 0);
  const out = new Uint8Array(total);
  out.set(buf.subarray(0, 8), 0);
  let o = 8;
  for (const s of kept) {
    out.set(buf.subarray(s.headerStart, s.end), o);
    o += s.end - s.headerStart;
  }
  return out;
}

// A tiny valid module with `count` functions of type () -> i32 for self-tests:
// function i returns the constant i.  No imports, no exports.
export function sampleModule(count) {
  const bytes = [...MAGIC, 0x01, 0x00, 0x00, 0x00];
  const section = (id, payload) => bytes.push(id, ...encodeLeb(payload.length), ...payload);
  section(1, [0x01, 0x60, 0x00, 0x01, 0x7f]);
  section(3, [...encodeLeb(count), ...new Array(count).fill(0x00)]);
  const code = [...encodeLeb(count)];
  for (let i = 0; i < count; i++) {
    const body = [0x00, 0x41, ...encodeSignedLeb(i), 0x0b];
    code.push(...encodeLeb(body.length), ...body);
  }
  section(10, code);
  return new Uint8Array(bytes);
}

function encodeSignedLeb(value) {
  const out = [];
  for (;;) {
    const byte = value & 0x7f;
    value >>= 7;
    if ((value === 0 && (byte & 0x40) === 0) || (value === -1 && (byte & 0x40) !== 0)) {
      out.push(byte);
      return out;
    }
    out.push(byte | 0x80);
  }
}

// A function body that never validates: i32.add on an empty operand stack.
export const INVALID_BODY = [0x00, 0x6a, 0x0b];
// A body that validates for every signature: unreachable.
export const UNREACHABLE_BODY = [0x00, 0x00, 0x0b];
