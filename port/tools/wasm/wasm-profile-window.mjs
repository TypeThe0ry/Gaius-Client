#!/usr/bin/env node
// Time-window view of a symbolized server Worker CPU profile (server.symbolized.cpuprofile from
// wasm-profile.mjs), and tick-stall detection in a TP_BACK_PROBE report.
//
//   wasm-profile-window.mjs window PROFILE FROM_S TO_S [--match REGEX] [--top N] [--json]
//       Busy and idle milliseconds of the samples whose time since the profile start lies in
//       [FROM_S, TO_S], and the functions with the most inclusive time (each function counted
//       once per sample, so recursion does not inflate it) with their self time.
//   wasm-profile-window.mjs stalls REPORT.json [--min-s 1] [--json]
//       The intervals in which the server tick counter of the report's per-leg series did not
//       advance for at least --min-s seconds, per leg (times are seconds since the leg's
//       command, the same clock as the profile saved for that leg).
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

/** Inclusive/self milliseconds per function name within [fromS, toS] of the profile. */
export function profileWindow(profile, fromS, toS, match = null) {
  const byId = new Map(profile.nodes.map(node => [node.id, node]));
  const parent = new Map();
  for (const node of profile.nodes) for (const child of node.children || []) parent.set(child, node.id);
  const inclusive = new Map(), self = new Map();
  let busyMs = 0, idleMs = 0, clock = 0;
  for (let i = 0; i < profile.samples.length; i++) {
    clock += Number(profile.timeDeltas?.[i]) || 0;
    const seconds = clock / 1e6;
    if (seconds < fromS || seconds > toS) continue;
    const dt = (Number(profile.timeDeltas?.[i]) || 0) / 1000;
    const leaf = byId.get(profile.samples[i]);
    if (!leaf) continue;
    const leafName = leaf.callFrame.functionName;
    if (leafName === '(idle)') { idleMs += dt; continue; }
    busyMs += dt;
    self.set(leafName, (self.get(leafName) || 0) + dt);
    const seen = new Set();
    for (let id = leaf.id; id !== undefined; id = parent.get(id)) {
      const name = byId.get(id).callFrame.functionName;
      if (seen.has(name)) continue;
      seen.add(name);
      inclusive.set(name, (inclusive.get(name) || 0) + dt);
    }
  }
  const rows = [...inclusive].filter(([name]) => !match || match.test(name))
    .map(([name, inclusiveMs]) => ({name, inclusiveMs, selfMs: self.get(name) || 0}))
    .sort((a, b) => b.inclusiveMs - a.inclusiveMs);
  return {fromS, toS, busyMs, idleMs, rows};
}

/** Stalls of the server tick counter in a TP_BACK_PROBE report ({tpBackProbe: {series}}). */
export function tickStalls(report, minSeconds = 1) {
  const legs = new Map();
  for (const sample of report.tpBackProbe?.series || []) {
    if (!legs.has(sample.leg)) legs.set(sample.leg, []);
    legs.get(sample.leg).push(sample);
  }
  const result = [];
  for (const [leg, samples] of legs) {
    const stalls = [];
    let last = null;
    let maxTickMs = 0;
    for (const sample of samples) {
      const tick = sample.w?.tick;
      if (!tick || tick.n == null) continue;
      maxTickMs = Math.max(maxTickMs, Number(tick.max) || 0);
      if (last && tick.n === last.n) continue;
      if (last && sample.t - last.t >= minSeconds) {
        stalls.push({fromS: last.t, toS: sample.t, seconds: +(sample.t - last.t).toFixed(2), tickFrom: last.n, tickTo: tick.n});
      }
      last = {t: sample.t, n: tick.n};
    }
    result.push({leg, stalls, maxTickMs});
  }
  return result;
}

function main(argv) {
  const [mode, file, ...rest] = argv;
  const flag = (name, fallback) => { const i = rest.indexOf(name); return i >= 0 ? rest[i + 1] : fallback; };
  const json = rest.includes('--json');
  if (mode === 'window') {
    const [fromS, toS] = rest.filter(arg => !arg.startsWith('--')).slice(0, 2).map(Number);
    if (!Number.isFinite(fromS) || !Number.isFinite(toS)) throw new Error('usage: window PROFILE FROM_S TO_S [--match REGEX] [--top N] [--json]');
    const match = flag('--match', null) ? new RegExp(flag('--match')) : null;
    const top = Number(flag('--top', 40));
    const view = profileWindow(JSON.parse(readFileSync(file, 'utf8')), fromS, toS, match);
    view.rows = view.rows.slice(0, top);
    if (json) { console.log(JSON.stringify(view, null, 2)); return; }
    console.log(`window ${fromS}-${toS}s busy=${view.busyMs.toFixed(0)}ms idle=${view.idleMs.toFixed(0)}ms`);
    for (const row of view.rows) console.log(`${row.inclusiveMs.toFixed(0).padStart(8)} ${row.selfMs.toFixed(0).padStart(7)}  ${row.name.slice(0, 140)}`);
  } else if (mode === 'stalls') {
    const stalls = tickStalls(JSON.parse(readFileSync(file, 'utf8')), Number(flag('--min-s', 1)));
    if (json) { console.log(JSON.stringify(stalls, null, 2)); return; }
    for (const {leg, stalls: list, maxTickMs} of stalls) {
      console.log(`${leg}: max tick ${maxTickMs.toFixed(0)} ms, ${list.length} stall(s)`);
      for (const stall of list) console.log(`  ${stall.fromS}s -> ${stall.toS}s  ${stall.seconds}s  tick ${stall.tickFrom} -> ${stall.tickTo}`);
    }
  } else {
    throw new Error('usage: wasm-profile-window.mjs window|stalls FILE ...');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
