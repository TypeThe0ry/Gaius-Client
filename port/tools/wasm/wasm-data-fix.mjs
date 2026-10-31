#!/usr/bin/env node
// Re-encodes the i32.const offsets of active data segments that TeaVM wrote as
// unsigned LEB128.
//
//   node wasm-data-fix.mjs <module.wasm> [--out <fixed.wasm>]   (default: in place)
//   node wasm-data-fix.mjs --self-test
//
// TeaVM 0.15's WasmBinaryRenderer.renderInitializer emits `i32.const` with
// writeLEB (unsigned) instead of writeSignedLEB.  An offset in [2^20, 2^21),
// [2^27, 2^28) and so on then has bit 6 of its last byte set and decodes as a
// negative i32: the 26.2 server's second segment at 2016950 read as -80202, and
// V8 rejects the module at instantiation (data segment out of bounds) although
// WebAssembly.validate accepts it.  Offsets are never negative (linear memory
// is at most 2 GiB here), so a negative value is re-read as the unsigned number
// TeaVM meant and written back as signed LEB128.  Only the data section changes;
// the code section and every custom section keep their bytes.
import fs from "node:fs";
import { encodeLeb, readLeb, sections } from "./wasm-binary.mjs";

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

function decodeSigned(bytes) {
  let value = 0n, shift = 0n, byte = 0;
  for (const b of bytes) { byte = b; value |= BigInt(b & 0x7f) << shift; shift += 7n; }
  if (byte & 0x40) value -= 1n << shift;
  return value;
}

// Returns {module, fixes: [{segment, written, offset}]}; module is the input when nothing changed.
export function fixDataOffsets(buf) {
  const data = sections(buf).find((s) => s.id === 11);
  if (!data) return { module: buf, fixes: [] };
  const out = [];
  const fixes = [];
  let pos = data.start;
  const copy = (from, to) => { for (let i = from; i < to; i++) out.push(buf[i]); };
  let count;
  [count, pos] = readLeb(buf, pos);
  out.push(...encodeLeb(count));
  for (let segment = 0; segment < count; segment++) {
    const segmentStart = pos;
    let flags;
    [flags, pos] = readLeb(buf, pos);
    if (flags === 2) [, pos] = readLeb(buf, pos);
    if (flags === 0 || flags === 2) {
      if (buf[pos] === 0x41) {
        copy(segmentStart, pos + 1);
        const lebStart = pos + 1;
        let lebEnd = lebStart;
        while (buf[lebEnd] & 0x80) lebEnd++;
        lebEnd++;
        const leb = buf.subarray(lebStart, lebEnd);
        const written = decodeSigned(leb);
        if (written < 0n) {
          const [offset] = readLeb(buf, lebStart);
          if (offset > 0x7fffffff) {
            throw new Error(`data segment ${segment}: offset ${offset} does not fit an i32`);
          }
          out.push(...encodeSignedLeb(offset));
          fixes.push({ segment, written: Number(written), offset });
        } else {
          copy(lebStart, lebEnd);
        }
        pos = lebEnd;
        if (buf[pos] !== 0x0b) throw new Error(`data segment ${segment}: offset expression is not a single i32.const`);
        out.push(0x0b);
        pos++;
      } else {
        // Another constant expression (global.get, extended const): copy up to its end.
        const exprStart = pos;
        while (buf[pos] !== 0x0b) pos++;
        pos++;
        copy(segmentStart, exprStart);
        copy(exprStart, pos);
      }
    } else if (flags === 1) {
      copy(segmentStart, pos);
    } else {
      throw new Error(`data segment ${segment}: unknown flags ${flags}`);
    }
    const lengthStart = pos;
    let length;
    [length, pos] = readLeb(buf, pos);
    copy(lengthStart, pos + length);
    pos += length;
  }
  if (pos !== data.end) throw new Error(`data section: parsed ${pos - data.start} of ${data.size} bytes`);
  if (fixes.length === 0) return { module: buf, fixes };
  const header = [11, ...encodeLeb(out.length)];
  const module = new Uint8Array(data.headerStart + header.length + out.length + (buf.length - data.end));
  module.set(buf.subarray(0, data.headerStart), 0);
  module.set(header, data.headerStart);
  module.set(out, data.headerStart + header.length);
  module.set(buf.subarray(data.end), data.headerStart + header.length + out.length);
  return { module, fixes };
}

// A module with one 64-page memory and active data segments at the given offsets,
// each offset written the way TeaVM 0.15 writes it (unsigned LEB128).
function teaVmStyleModule(offsets) {
  const bytes = [0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00];
  const section = (id, payload) => bytes.push(id, ...encodeLeb(payload.length), ...payload);
  section(5, [0x01, 0x00, 0x40]);
  const data = [...encodeLeb(offsets.length)];
  offsets.forEach((offset, i) => data.push(0x00, 0x41, ...encodeLeb(offset), 0x0b, 0x02, 0xa0 + i, 0xb0 + i));
  section(11, data);
  return new Uint8Array(bytes);
}

async function selfTest() {
  const failures = [];
  const offsets = [0, 2016950, 2162712, 1048576];
  const broken = teaVmStyleModule(offsets);
  let brokenRejected = false;
  try { await WebAssembly.instantiate(broken); } catch { brokenRejected = true; }
  if (!brokenRejected) failures.push("the unsigned-LEB module instantiated; the control does not reproduce the bug");
  const { module, fixes } = fixDataOffsets(broken);
  if (fixes.map((f) => f.offset).join() !== "2016950,1048576") failures.push(`fixed ${JSON.stringify(fixes)}`);
  const { instance } = await WebAssembly.instantiate(module).catch((e) => { failures.push(`fixed module: ${e.message}`); return {}; });
  if (instance) {
    // No export: re-instantiate with an exported memory to read the bytes back.
    const withExport = (() => {
      const s = sections(module);
      const parts = [module.subarray(0, 8)];
      for (const x of s) {
        parts.push(module.subarray(x.headerStart, x.end));
        if (x.id === 5) parts.push(new Uint8Array([7, 5, 1, 1, 0x6d, 0x02, 0x00]));
      }
      return Buffer.concat(parts);
    })();
    const memory = (await WebAssembly.instantiate(withExport)).instance.exports.m;
    const view = new Uint8Array(memory.buffer);
    offsets.forEach((offset, i) => {
      if (view[offset] !== 0xa0 + i || view[offset + 1] !== 0xb0 + i) failures.push(`segment ${i} not at ${offset}`);
    });
  }
  if (fixDataOffsets(module).fixes.length !== 0) failures.push("a fixed module is changed again");
  const clean = teaVmStyleModule([0, 2162712]);
  if (fixDataOffsets(clean).module !== clean) failures.push("a module without wrapped offsets was rewritten");
  if (failures.length) {
    for (const failure of failures) console.error(`self-test FAILED: ${failure}`);
    return 1;
  }
  console.log("wasm-data-fix self-test passed: wrapped offsets 2016950 and 1048576 re-encoded, segments land at their offsets, clean modules untouched");
  return 0;
}

async function main(argv) {
  if (argv[0] === "--self-test") return selfTest();
  const input = argv[0];
  if (!input) {
    console.error("usage: wasm-data-fix.mjs <module.wasm> [--out <fixed.wasm>] | --self-test");
    return 2;
  }
  const outIndex = argv.indexOf("--out");
  const output = outIndex >= 0 ? argv[outIndex + 1] : input;
  const buf = new Uint8Array(fs.readFileSync(input));
  const { module, fixes } = fixDataOffsets(buf);
  for (const fix of fixes) {
    console.log(`wasm-data-fix: data segment ${fix.segment} offset ${fix.offset} (written as ${fix.written})`);
  }
  if (module !== buf || output !== input) fs.writeFileSync(output, module);
  console.log(`wasm-data-fix: ${fixes.length} offset(s) re-encoded`);
  return 0;
}

process.exitCode = await main(process.argv.slice(2));
