import assert from "node:assert/strict";
import {createReadStream, writeFileSync} from "node:fs";

const input = process.argv[2];
assert.ok(input, "usage: node memory-heap-summary.mjs snapshot output.json");
let pending = "", phase = "header", fields, types, node = [], stringIndex = 0, nodeRecords = [];
let nodeCount = 0, selfBytes = 0;
const byName = new Map(), byType = new Map(), names = new Map();
for await (const chunk of createReadStream(input, {encoding: "utf8", highWaterMark: 65536})) {
  pending += chunk;
  if (phase === "header") {
    const start = pending.indexOf('"nodes":[');
    if (start < 0) continue;
    const header = JSON.parse(pending.slice(0, start).replace(/,\s*$/, "") + "}");
    fields = header.snapshot.meta.node_fields;
    types = header.snapshot.meta.node_types[fields.indexOf("type")];
    pending = pending.slice(start + 9); phase = "nodes";
  }
  if (phase === "nodes") {
    let position = 0;
    while (position < pending.length) {
      while (/[,\s]/.test(pending[position] || "!") && position < pending.length) position++;
      if (pending[position] === "]") {pending = pending.slice(position + 1); phase = "skip"; break;}
      const match = /^[0-9]+/.exec(pending.slice(position));
      if (!match || position + match[0].length === pending.length) break;
      position += match[0].length; node.push(Number(match[0]));
      if (node.length === fields.length) {
        const name = node[fields.indexOf("name")], type = types[node[fields.indexOf("type")]];
        const bytes = node[fields.indexOf("self_size")];
        if (types[node[fields.indexOf("type")]] === "array" && bytes > 1024 * 1024) {
          nodeRecords.push({index: nodeCount, name, bytes});
        }
        const record = byName.get(name) || {count: 0, selfBytes: 0};
        record.count++; record.selfBytes += bytes; byName.set(name, record);
        byType.set(type, (byType.get(type) || 0) + bytes);
        nodeCount++; selfBytes += bytes; node = [];
      }
    }
    if (phase === "nodes") pending = pending.slice(position);
  }
  if (phase === "skip") {
    const start = pending.indexOf('"strings":[');
    if (start < 0) {pending = pending.slice(-32); continue;}
    pending = pending.slice(start + 11); phase = "strings";
  }
  if (phase === "strings") {
    let position = 0;
    while (position < pending.length) {
      while (/[,\s]/.test(pending[position] || "!") && position < pending.length) position++;
      if (pending[position] === "]") {phase = "done"; break;}
      if (pending[position] !== '"') break;
      let end = position + 1;
      for (; end < pending.length; end++) {
        if (pending[end] === "\\") {end++; continue;}
        if (pending[end] === '"') break;
      }
      if (end >= pending.length) break;
      if (byName.has(stringIndex)) names.set(stringIndex, JSON.parse(pending.slice(position, end + 1)).slice(0, 180));
      stringIndex++; position = end + 1;
    }
    pending = pending.slice(position);
  }
}
assert.equal(phase, "done", "incomplete heap snapshot");
const summary = {nodeCount, selfBytes, byType: Object.fromEntries(byType),
  largestNames: [...byName].map(([index, record]) => ({name: names.get(index), ...record}))
    .sort((first, second) => second.selfBytes - first.selfBytes).slice(0, 45),
  largestArrayBuffers: nodeRecords.sort((a,b)=>b.bytes-a.bytes).slice(0,25),
  accounting: "V8 self sizes, not retained ownership; native backing stores are separate from Runtime.getHeapUsage.usedSize"};
writeFileSync(process.argv[3], JSON.stringify(summary, null, 2));
console.log(JSON.stringify({nodeCount, selfMiB: selfBytes / 1048576, largestNames: summary.largestNames.slice(0, 8)}));
