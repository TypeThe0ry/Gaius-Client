import {readFile, writeFile} from "node:fs/promises";
const [leftPath, rightPath, ledgerPath, outPath] = process.argv.slice(2);
const load = async p => (await readFile(p, "utf8")).trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const left = await load(leftPath), right = await load(rightPath), ledger = await load(ledgerPath);
const rows = ledger.filter(x => x.status === "difference").slice(0, 20).map(x => ({
  index: x.index, tick: x.tick, kind: x.kind, leftSequence: x.leftSequence, rightSequence: x.rightSequence,
  left: left[x.leftSequence ?? -1] ?? null, right: right[x.rightSequence ?? -1] ?? null,
  classification: x.kind === "packet" ? "packet-wire-or-packet-fields" : x.kind.startsWith("chunk-broadcast") ? "chunk-broadcast-order-or-membership" :
    x.kind === "entity" || x.kind === "entity-identity" ? "entity-state-or-identity" : x.kind.startsWith("settle") ? "timing-counter" : "tick-boundary"
}));
await writeFile(outPath, JSON.stringify(rows, null, 2) + "\n");
console.log(JSON.stringify({rows: rows.length, out: outPath}));
