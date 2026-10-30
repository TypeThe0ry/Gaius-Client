import {readFileSync} from 'node:fs';

const module = await WebAssembly.instantiate(readFileSync(process.argv[2]), {});
const exports = module.instance.exports;
const names = new Map([[0x0401, 'run_load_generator'], [0x0402, 'run_biomes'],
  [0x0403, 'run_terrain'], [0x0404, 'run_surface'], [0x0405, 'run_ore']]);
let pending = Buffer.alloc(0);
process.stdin.on('data', data => {
  pending = Buffer.concat([pending, data]);
  while (pending.length >= 4) {
    const length = pending.readUInt32LE(0);
    if (length < 16 || length > 16 * 1024 * 1024) throw new Error('Invalid job length');
    if (pending.length < length + 4) return;
    const frame = pending.subarray(4, length + 4);
    const kind = frame.readUInt16LE(6);
    const run = exports[names.get(kind)];
    if (!run) throw new Error('Unsupported job kind ' + kind);
    const pointer = exports.alloc(length) >>> 0;
    if (!pointer) throw new Error('Allocation failed');
    let descriptor = 0;
    try {
      new Uint8Array(exports.memory.buffer, pointer, length).set(frame);
      descriptor = run(pointer, length) >>> 0;
      if (!descriptor) throw new Error('Null descriptor');
      const header = new DataView(exports.memory.buffer, descriptor, 16);
      const status = header.getUint32(0, true);
      const resultPointer = header.getUint32(4, true);
      const resultLength = header.getUint32(8, true);
      const response = Buffer.allocUnsafe(8 + resultLength);
      response.writeUInt32LE(status, 0);
      response.writeUInt32LE(resultLength, 4);
      response.set(new Uint8Array(exports.memory.buffer, resultPointer, resultLength), 8);
      process.stdout.write(response);
    } finally {
      if (descriptor) exports.release(descriptor);
      exports.dealloc(pointer, length);
    }
    pending = pending.subarray(length + 4);
  }
});
