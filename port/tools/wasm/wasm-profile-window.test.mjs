import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {profileWindow, tickStalls} from './wasm-profile-window.mjs';

// root -> run -> {gen (self), save (self)}; samples every 100 ms from t=0.1 s:
// 0.1-0.3 gen, 0.4 idle, 0.5-0.6 save, 0.7 gen.
function profile() {
  const frame = functionName => ({functionName});
  return {
    nodes: [
      {id: 1, callFrame: frame('(root)'), children: [2, 5]},
      {id: 2, callFrame: frame('run'), children: [3, 4]},
      {id: 3, callFrame: frame('gen')},
      {id: 4, callFrame: frame('save')},
      {id: 5, callFrame: frame('(idle)')},
    ],
    startTime: 0,
    endTime: 700000,
    samples: [3, 3, 3, 5, 4, 4, 3],
    timeDeltas: [100000, 100000, 100000, 100000, 100000, 100000, 100000],
  };
}

test('window counts inclusive time once per sample and separates idle', () => {
  const view = profileWindow(profile(), 0, 1);
  assert.equal(Math.round(view.busyMs), 600);
  assert.equal(Math.round(view.idleMs), 100);
  const byName = new Map(view.rows.map(row => [row.name, row]));
  assert.equal(Math.round(byName.get('run').inclusiveMs), 600);
  assert.equal(Math.round(byName.get('run').selfMs), 0);
  assert.equal(Math.round(byName.get('gen').selfMs), 400);
  assert.equal(Math.round(byName.get('save').inclusiveMs), 200);
});

test('window keeps only the samples between the bounds and applies the name filter', () => {
  const view = profileWindow(profile(), 0.45, 0.65, /^save$/);
  assert.equal(Math.round(view.busyMs), 200);
  assert.deepEqual(view.rows.map(row => row.name), ['save']);
});

test('stalls report the intervals in which the tick counter stood still', () => {
  const sample = (leg, t, n, max = 0) => ({leg, t, w: {tick: {n, max}}});
  const stalls = tickStalls({tpBackProbe: {series: [
    sample('0-far', 0, 10), sample('0-far', 0.4, 12), sample('0-far', 0.8, 12), sample('0-far', 1.2, 12),
    sample('0-far', 3.5, 13, 5200), sample('0-far', 3.9, 21, 5200),
    {leg: '1-back', t: 0.2, w: null}, sample('1-back', 0.4, 30), sample('1-back', 0.8, 31),
  ]}}, 1);
  assert.deepEqual(stalls.map(entry => entry.leg), ['0-far', '1-back']);
  assert.deepEqual(stalls[0].stalls, [{fromS: 0.4, toS: 3.5, seconds: 3.1, tickFrom: 12, tickTo: 13}]);
  assert.equal(stalls[0].maxTickMs, 5200);
  assert.deepEqual(stalls[1].stalls, []);
});

test('command line prints a window and the stalls of a report', () => {
  const dir = mkdtempSync(join(tmpdir(), 'profile-window-test-'));
  try {
    writeFileSync(join(dir, 'p.cpuprofile'), JSON.stringify(profile()));
    writeFileSync(join(dir, 'r.json'), JSON.stringify({tpBackProbe: {series: [
      {leg: 'a', t: 0, w: {tick: {n: 1, max: 0}}}, {leg: 'a', t: 2, w: {tick: {n: 1, max: 0}}},
      {leg: 'a', t: 4, w: {tick: {n: 2, max: 3900}}},
    ]}}));
    const tool = fileURLToPath(new URL('./wasm-profile-window.mjs', import.meta.url));
    const window = spawnSync(process.execPath, [tool, 'window', join(dir, 'p.cpuprofile'), '0', '1', '--top', '2', '--json'], {encoding: 'utf8'});
    assert.equal(window.status, 0, window.stderr);
    assert.equal(JSON.parse(window.stdout).rows.length, 2);
    const stalls = spawnSync(process.execPath, [tool, 'stalls', join(dir, 'r.json'), '--json'], {encoding: 'utf8'});
    assert.equal(stalls.status, 0, stalls.stderr);
    assert.equal(JSON.parse(stalls.stdout)[0].stalls[0].seconds, 4);
  } finally {
    rmSync(dir, {recursive: true, force: true});
  }
});
