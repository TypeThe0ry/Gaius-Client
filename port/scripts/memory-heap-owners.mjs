import assert from "node:assert/strict";
import {createReadStream, writeFileSync} from "node:fs";

const [input, output] = process.argv.slice(2);
assert.ok(input && output, "usage: node memory-heap-owners.mjs snapshot output.json");

let pending = "";
let phase = "header";
let fields = null;
let nodeTypes = null;
let node = [];
let token = "";
let nodeCount = 0;
let selfBytes = 0;
let stringIndex = 0;
let inString = false;
let escaped = false;
let captureString = false;
let stringToken = "";
const byType = new Map();
const owners = new Map();
const ownerIds = new Map();

function account(value) {
  node.push(value);
  if (node.length !== fields.length) return;
  const type = String(nodeTypes[node[fields.indexOf("type")]]);
  const nameIndex = node[fields.indexOf("name")];
  const bytes = node[fields.indexOf("self_size")];
  const typeTotal = byType.get(type) || {count: 0, selfBytes: 0};
  typeTotal.count++;
  typeTotal.selfBytes += bytes;
  byType.set(type, typeTotal);
  if (type === "native" || (type === "array" && bytes >= 1024 * 1024)) {
    const key = `${type}:${nameIndex}`;
    const owner = owners.get(key) || {type, nameIndex, count: 0, selfBytes: 0};
    owner.count++;
    owner.selfBytes += bytes;
    owners.set(key, owner);
    const records = ownerIds.get(nameIndex) || [];
    if (!records.includes(owner)) records.push(owner);
    ownerIds.set(nameIndex, records);
  }
  nodeCount++;
  selfBytes += bytes;
  node = [];
}

function parseNodes() {
  let position = 0;
  while (position < pending.length) {
    const ch = pending[position++];
    if (ch >= "0" && ch <= "9") {
      token += ch;
    } else if (ch === "," || ch === "]") {
      if (token) {
        account(Number(token));
        token = "";
      }
      if (ch === "]") {
        phase = "edges";
        pending = pending.slice(position);
        return;
      }
    }
  }
  pending = "";
}

function parseStrings() {
  let position = 0;
  while (position < pending.length) {
    const ch = pending[position];
    if (!inString) {
      if (ch === "]") {
        phase = "done";
        pending = pending.slice(position + 1);
        return;
      }
      if (ch !== '"') {
        position++;
        continue;
      }
      inString = true;
      escaped = false;
      captureString = ownerIds.has(stringIndex);
      stringToken = captureString ? '"' : "";
      position++;
      continue;
    }

    position++;
    if (captureString) stringToken += ch;
    if (escaped) {
      escaped = false;
    } else if (ch === "\\") {
      escaped = true;
    } else if (ch === '"') {
      if (captureString) {
        const value = JSON.parse(stringToken);
        for (const owner of ownerIds.get(stringIndex)) owner.name = value.slice(0, 180);
      }
      stringIndex++;
      inString = false;
      captureString = false;
      stringToken = "";
    }
  }
  pending = pending.slice(position);
}

for await (const chunk of createReadStream(input, {encoding: "utf8", highWaterMark: 65536})) {
  pending += chunk;
  while (phase !== "done") {
    if (phase === "header") {
      const marker = pending.indexOf('"nodes":[');
      if (marker < 0) break;
      const header = JSON.parse(pending.slice(0, marker).replace(/,\s*$/, "") + "}");
      fields = header.snapshot.meta.node_fields;
      nodeTypes = header.snapshot.meta.node_types[fields.indexOf("type")];
      pending = pending.slice(marker + 9);
      phase = "nodes";
    } else if (phase === "nodes") {
      parseNodes();
      if (phase === "nodes") break;
    } else if (phase === "edges") {
      const marker = pending.indexOf('"strings":[');
      if (marker < 0) {
        pending = pending.slice(-10);
        break;
      }
      pending = pending.slice(marker + 11);
      phase = "strings";
    } else if (phase === "strings") {
      parseStrings();
      if (phase === "strings") break;
    }
  }
}

assert.equal(phase, "done", "incomplete heap snapshot");
const ownerRows = [...owners.values()].map(owner => ({
  type: owner.type,
  name: owner.name || `(unresolved string ${owner.nameIndex})`,
  count: owner.count,
  selfBytes: owner.selfBytes,
})).sort((a, b) => b.selfBytes - a.selfBytes).slice(0, 100);
const result = {
  nodeCount,
  selfBytes,
  byType: Object.fromEntries([...byType].sort((a, b) => b[1].selfBytes - a[1].selfBytes)),
  nativeAndLargeArrayOwners: ownerRows,
  accounting: "V8 self sizes only; native JSArrayBufferData is backing-store evidence, not a complete retained-object dominator graph",
};
writeFileSync(output, JSON.stringify(result, null, 2));
console.log(JSON.stringify({nodeCount, selfMiB: selfBytes / 1048576,
  topTypes: Object.entries(result.byType).slice(0, 6), owners: ownerRows.slice(0, 8)}));
