import {readFile, mkdir, writeFile} from 'node:fs/promises';

const [leftPath, rightPath, outPath] = process.argv.slice(2);
if (!leftPath || !rightPath || !outPath) throw new Error('usage: node tools/parity7-classify.mjs <left events> <right events> <out.json>');
const load = async path => (await readFile(path, 'utf8')).trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const strip = (value) => {
  if (Array.isArray(value)) return value.map(strip);
  if (!value || typeof value !== 'object') return value;
  const out = {};
  for (const [key, item] of Object.entries(value)) {
    if (['sequence', 'serverTasks'].includes(key)) continue;
    out[key] = strip(item);
  }
  return out;
};
const stable = value => JSON.stringify(value && typeof value === 'object' ?
  (Array.isArray(value) ? value.map(stable) : Object.fromEntries(Object.keys(value).sort().map(k => [k, value[k]]))) : value);
const left = await load(leftPath); const right = await load(rightPath);
const buckets = events => {
  const map = new Map();
  for (const event of events) {
    const key = `${event.kind}/${event.tick}`;
    const list = map.get(key) ?? []; list.push({event, sig: stable(strip(event))}); map.set(key, list);
  }
  return map;
};
const lb = buckets(left), rb = buckets(right);
let identical = 0, timingOnly = 0, content = 0, missing = 0, extra = 0;
const rows = [];
for (const key of new Set([...lb.keys(), ...rb.keys()])) {
  const a = lb.get(key) ?? [], b = rb.get(key) ?? [];
  const counts = new Map(); for (const x of b) counts.set(x.sig, (counts.get(x.sig) ?? 0) + 1);
  const unmatchedLeft = [];
  for (const x of a) {
    const n = counts.get(x.sig) ?? 0;
    if (n) { counts.set(x.sig, n - 1); identical++; }
    else unmatchedLeft.push({key, left: x.event});
  }
  const unmatchedRight = [];
  for (const [sig, n] of counts) for (let i = 0; i < n; i++) unmatchedRight.push(sig);
  const pair = Math.min(unmatchedLeft.length, unmatchedRight.length);
  content += pair;
  missing += Math.max(0, unmatchedLeft.length - pair);
  extra += Math.max(0, unmatchedRight.length - pair);
  for (let i = 0; i < pair; i++) rows.push({key, left: unmatchedLeft[i].left, status: 'different-content'});
}
// Identical content at another position is a timing/order shift; exact same position is equal.
const leftIndex = new Map(); const rightIndex = new Map();
for (let i=0;i<left.length;i++) leftIndex.set(stable(strip(left[i])), i);
for (let i=0;i<right.length;i++) rightIndex.set(stable(strip(right[i])), i);
for (const [sig, i] of leftIndex) if (rightIndex.has(sig) && rightIndex.get(sig) !== i) timingOnly++;
identical -= timingOnly;
const result = {left: left.length, right: right.length, exactContent: identical, timingOnly, differentContent: content, missing, extra,
  unexplained: content + missing + extra, files: {left: leftPath, right: rightPath}};
await mkdir(outPath.replace(/[\\/][^\\/]+$/, ''), {recursive: true});
await writeFile(outPath, JSON.stringify({summary: result, samples: rows.slice(0, 40)}, null, 2) + '\n');
console.log(JSON.stringify(result));
