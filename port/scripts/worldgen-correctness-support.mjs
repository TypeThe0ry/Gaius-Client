import {spawnSync} from 'node:child_process';
import {appendFileSync} from 'node:fs';
import {setTimeout} from 'node:timers/promises';
import {gunzipSync} from 'node:zlib';

export const MINIMUM_HEADROOM = 8 * 1024 ** 3;

export function commitHeadroom() {
  if (process.platform !== 'win32') throw new Error('LIGHT JVM admission requires Windows commit telemetry');
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    '$memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory; '
    + '[Console]::Write($memory.CommitLimit-$memory.CommittedBytes)'],
  {encoding: 'utf8', windowsHide: true, timeout: 30000});
  const observation = result.stdout?.trim();
  const bytes = Number(observation);
  if (result.status !== 0 || !observation || !Number.isSafeInteger(bytes) || bytes < 0) {
    throw new Error('Cannot verify Windows commit headroom: ' + result.stderr);
  }
  return bytes;
}

export async function waitJvmHeadroom(log) {
  for (;;) {
    const bytes = commitHeadroom();
    appendFileSync(log, JSON.stringify({at: new Date().toISOString(), bytes,
      minimumBytes: MINIMUM_HEADROOM, admitted: bytes >= MINIMUM_HEADROOM}) + '\n');
    if (bytes >= MINIMUM_HEADROOM) return;
    console.log(`Waiting for JVM admission: ${(bytes / 1024 ** 3).toFixed(2)} GiB commit headroom`);
    await setTimeout(60000);
  }
}

export function correctnessHarness(source) {
  const replace = (previous, next) => {
    if (source.split(previous).length !== 2) throw new Error('Correctness harness anchor mismatch: ' + previous);
    source = source.replace(previous, next);
  };
  replace('ChunkStatus status = "terrain".equals(options.mode()) ? ParityCompat.TERRAIN : ChunkStatus.FULL;',
    'ChunkStatus status = WorldgenCorrectness.stage();');
  replace('                List<ChunkAccess> completed = new ArrayList<>();',
    '                WorldgenCorrectness.prepareNeighbours(chunks, positions, status);\n'
    + '                List<ChunkAccess> completed = new ArrayList<>();');
  replace('                for (int index = 0; index < positions.size(); index++) {',
    '                WorldgenCorrectness.finishNeighbours(chunks, positions, status);\n'
    + '                for (int index = 0; index < positions.size(); index++) {');
  replace('                    ChunkAccess chunk = completed.get(index);',
    '                    ChunkAccess chunk = completed.get(index);\n'
    + '                    WorldgenCorrectness.snapshot(chunk);');
  return source;
}

export function readSnapshot(compressed) {
  const bytes = gunzipSync(compressed, {maxOutputLength: 8 * 1024 * 1024});
  let offset = 0;
  const integer = () => {
    if (offset + 4 > bytes.length) throw new Error('Truncated block snapshot');
    const value = bytes.readInt32BE(offset);
    offset += 4;
    return value;
  };
  if (integer() !== 0x47574331) throw new Error('Invalid block snapshot magic');
  const chunkX = integer(), chunkZ = integer(), minY = integer(), height = integer();
  const count = integer();
  if (height <= 0 || height > 4096 || count <= 0 || count > 65536) throw new Error('Invalid snapshot shape');
  const palette = [];
  for (let index = 0; index < count; index++) {
    const length = integer();
    if (length <= 0 || length > 4096 || offset + length > bytes.length) throw new Error('Invalid state name');
    palette.push(bytes.toString('utf8', offset, offset + length));
    offset += length;
  }
  const cells = new Int32Array(height * 256);
  for (let index = 0; index < cells.length; index++) {
    cells[index] = integer();
    if (cells[index] < 0 || cells[index] >= palette.length) throw new Error('Invalid palette index');
  }
  if (offset !== bytes.length) throw new Error('Trailing snapshot bytes');
  return {x: chunkX, z: chunkZ, minY, height, palette, cells};
}

export function firstBlockDifference(expected, actual) {
  for (const field of ['x', 'z', 'minY', 'height']) {
    if (expected[field] !== actual[field]) throw new Error('Snapshot identity mismatch: ' + field);
  }
  for (let index = 0; index < expected.cells.length; index++) {
    const before = expected.palette[expected.cells[index]];
    const after = actual.palette[actual.cells[index]];
    if (before !== after) return {x: expected.x * 16 + (index & 15),
      y: expected.minY + (index >>> 8), z: expected.z * 16 + ((index >>> 4) & 15),
      expected: before, actual: after};
  }
  return null;
}
