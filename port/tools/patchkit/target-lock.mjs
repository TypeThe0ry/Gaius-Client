import {createHash} from "node:crypto";
import {existsSync, readFileSync} from "node:fs";
import {join} from "node:path";

const HASH = /^[a-f0-9]{64}$/;
const key = (row) => `${row.className}.${row.method}${row.descriptor}`;
const canonicalTargets = (entry) => (entry.methodTargets ?? []).map((row) => [
  row.className, row.method, row.descriptor, row.operation, row.targetHash, row.vanillaPresent,
]).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right), "en"));

export function targetDigest(entry) {
  return createHash("sha256").update(JSON.stringify(canonicalTargets(entry))).digest("hex");
}

export function readAcknowledgements(root) {
  const path = join(root, "port/tools/patchkit/acknowledgements.json");
  if (!existsSync(path)) return [];
  const document = JSON.parse(readFileSync(path, "utf8"));
  if (document.schema !== 1 || !Array.isArray(document.acknowledgements)) {
    throw new Error(`${path}: invalid acknowledgement schema`);
  }
  const seen = new Set();
  for (const entry of document.acknowledgements) {
    for (const field of ["profile", "target", "id", "reviewer", "reason"]) {
      if (typeof entry[field] !== "string" || !entry[field].trim()) {
        throw new Error(`${path}: acknowledgement needs ${field}`);
      }
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(entry.profile)
        || !["js", "wasmgc"].includes(entry.target)
        || !/^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$/.test(entry.id)) {
      throw new Error(`${path}: acknowledgement needs an exact profile, target and patch id`);
    }
    if (!HASH.test(entry.previousTargetDigest) || !HASH.test(entry.currentTargetDigest)
        || entry.previousTargetDigest === entry.currentTargetDigest) {
      throw new Error(`${path}: acknowledgement needs two distinct exact target digests`);
    }
    const identity = JSON.stringify([entry.profile, entry.target, entry.id,
      entry.previousTargetDigest, entry.currentTargetDigest]);
    if (seen.has(identity)) throw new Error(`${path}: duplicate acknowledgement for ${entry.id}`);
    seen.add(identity);
  }
  return document.acknowledgements;
}

export function targetChanges(before, after, manifest, acknowledgements = []) {
  if (!Object.hasOwn(before, "methodTargets")) return {changed: false};
  const previousTargetDigest = targetDigest(before);
  const currentTargetDigest = targetDigest(after);
  const bodyChanged = previousTargetDigest !== currentTargetDigest;
  const writes = (entry) => new Map((entry.methodTargets ?? [])
    .filter((row) => row.method === "<clinit>").map((row) => [key(row),
      JSON.stringify([row.vanillaStaticWrites, row.staticWrites])]));
  const previousWrites = writes(before);
  const currentWrites = writes(after);
  const staticWritesChanged = [...new Set([...previousWrites.keys(), ...currentWrites.keys()])]
    .some((name) => previousWrites.get(name) !== currentWrites.get(name));
  const sensitive = (row) => row.vanillaPresent;
  const needsAcknowledgement = bodyChanged
    && [...(before.methodTargets ?? []), ...(after.methodTargets ?? [])].some(sensitive);
  const acknowledged = needsAcknowledgement && acknowledgements.some((entry) =>
    entry.profile === manifest.profile && entry.target === manifest.target && entry.id === after.id
    && entry.previousTargetDigest === previousTargetDigest
    && entry.currentTargetDigest === currentTargetDigest);
  return {changed: bodyChanged || staticWritesChanged, bodyChanged, staticWritesChanged,
    needsAcknowledgement, acknowledged, previousTargetDigest, currentTargetDigest,
    description: [bodyChanged ? `targetHash ${previousTargetDigest} -> ${currentTargetDigest}` : null,
      staticWritesChanged ? "<clinit> static-write set changed (hard gate)" : null,
      acknowledged ? "reviewed acknowledgement matches" : null].filter(Boolean).join("; ")};
}

export function validateTargets(manifest) {
  const problems = [];
  if (manifest.schema !== 2 || manifest.targetHashAlgorithm !== "sha256-asm-symbolic-v1") {
    return ["unsupported target fingerprint schema or algorithm"];
  }
  for (const entry of manifest.patches) {
    if (!Array.isArray(entry.methodTargets)) {
      problems.push(`${entry.id}: missing methodTargets`);
      continue;
    }
    const seen = new Set();
    for (const row of entry.methodTargets) {
      if (!row.className || !row.method || typeof row.descriptor !== "string"
          || !["rewrite", "injected", "removed", "override", "structure"].includes(row.operation)
          || !HASH.test(row.targetHash) || typeof row.vanillaPresent !== "boolean") {
        problems.push(`${entry.id}: invalid method target ${key(row)}`);
      }
      if (seen.has(key(row))) problems.push(`${entry.id}: duplicate method target ${key(row)}`);
      seen.add(key(row));
      if (row.method === "<clinit>") {
        for (const field of ["vanillaStaticWrites", "staticWrites"]) {
          if (!Array.isArray(row[field]) || row[field].some((value) => typeof value !== "string")
              || JSON.stringify(row[field]) !== JSON.stringify([...new Set(row[field])].sort())) {
            problems.push(`${entry.id}: invalid ${field} for ${key(row)}`);
          }
        }
      }
    }
    for (const path of [...(entry.rewrites ?? []), ...(entry.adds ?? [])]) {
      if (path.endsWith(".class") && !entry.methodTargets.some((row) => `${row.className}.class` === path)) {
        problems.push(`${entry.id}: written class has no target fingerprint: ${path}`);
      }
    }
  }
  return problems;
}
