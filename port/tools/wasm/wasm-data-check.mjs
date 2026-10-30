#!/usr/bin/env node
import fs from "node:fs";

const bytes = fs.readFileSync(process.argv[2]);
let at = 8;
const readU = () => {
  let value = 0, shift = 0;
  for (;;) {
    const byte = bytes[at++];
    value += (byte & 0x7f) * 2 ** shift;
    if (!(byte & 0x80)) return value;
    shift += 7;
  }
};
const readS = () => {
  let value = 0, shift = 0, byte;
  do { byte = bytes[at++]; value |= (byte & 0x7f) << shift; shift += 7; } while (byte & 0x80);
  if (shift < 32 && (byte & 0x40)) value |= (~0 << shift);
  return value >>> 0;
};
let importedPages = 0;
let memoryPages = 256;
let teaVmMinimum = 0;
while (at < bytes.length) {
  const id = bytes[at++];
  const size = readU();
  const end = at + size;
  if (id === 2) {
    const count = readU();
    for (let i = 0; i < count; i++) {
      const moduleLength = readU(); at += moduleLength;
      const nameLength = readU(); at += nameLength;
      const kind = bytes[at++];
      if (kind === 2) {
        const flags = readU();
        const min = readU();
        if (flags & 1) readU();
        importedPages = min;
        memoryPages = Math.max(memoryPages, importedPages, 256);
      } else if (kind === 0) readU();
      else if (kind === 1) { at += 1; readU(); if (bytes[at - 1] === 1) readU(); }
      else if (kind === 3) { at += 2; }
    }
  } else if (id === 5) {
    const count = readU();
    for (let i = 0; i < count; i++) {
      const flags = readU();
      const min = readU();
      if (flags & 1) readU();
      memoryPages = Math.max(memoryPages, min);
    }
  } else if (id === 0) {
    // TeaVM's runtime creates env.memory with max(teavm min, 256) pages.
    // Reading this custom section keeps the check aligned with the actual host
    // memory supplied before instantiation, instead of assuming 256 pages.
    const sectionStart = at;
    const nameLength = readU();
    const name = new TextDecoder().decode(bytes.subarray(at, at + nameLength));
    at += nameLength;
    if (name === "teavm.memoryRequirements") {
      try {
        const requirements = JSON.parse(new TextDecoder().decode(bytes.subarray(at, end)));
        teaVmMinimum = Number.isInteger(requirements.min) ? requirements.min : 0;
        memoryPages = Math.max(memoryPages, teaVmMinimum, 256);
      } catch (_) {
        // A malformed optional custom section does not change Wasm parsing.
      }
    }
    at = sectionStart;
  } else if (id === 11) {
    const count = readU();
    for (let i = 0; i < count; i++) {
      const flags = readU();
      if (flags === 0 || flags === 2) {
        if (bytes[at++] !== 0x41) throw new Error("unsupported data offset expression");
        const offset = readS();
        if (bytes[at++] !== 0x0b) throw new Error("malformed data offset expression");
        if (flags === 2) readU();
        const length = readU();
        if (offset + length > memoryPages * 65536) {
          console.error(`active data segment out of bounds: offset=${offset} length=${length} memory=${memoryPages * 65536}`);
          process.exitCode = 1;
        }
        at += length;
      } else {
        const length = readU(); at += length;
      }
    }
  }
  at = end;
}
if (importedPages > 0) memoryPages = Math.max(memoryPages, importedPages, teaVmMinimum, 256);
