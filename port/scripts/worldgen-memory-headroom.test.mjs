import assert from 'node:assert/strict';
import {test} from 'node:test';
import {linuxMemoryHeadroom} from './worldgen-correctness-support.mjs';

test('Linux admission reads available memory rather than free or total memory', () => {
  assert.equal(linuxMemoryHeadroom('MemTotal: 400000000 kB\nMemFree: 10 kB\nMemAvailable: 12345678 kB\n'),
    12345678 * 1024);
  assert.equal(linuxMemoryHeadroom('MemAvailable: 0 kB\n'), 0);
});
test('Linux admission fails closed on missing or invalid memory data', () => {
  for (const text of ['', 'MemFree: 12345 kB', 'MemAvailable: -1 kB',
    'MemAvailable: 9007199254740991 kB', 'MemAvailable: 123 MB']) {
    assert.throws(() => linuxMemoryHeadroom(text), /Cannot verify/);
  }
});
