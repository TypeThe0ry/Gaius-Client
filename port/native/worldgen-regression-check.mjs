#!/usr/bin/env node
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync, readdirSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const json = path => JSON.parse(readFileSync(path, 'utf8'));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export const regressionCatalog = json(join(here, 'golden/worldgen/regressions.json'));

export function verifyWorldgenRegressions(root, catalog = regressionCatalog) {
  assert.equal(catalog.schemaVersion, 1);
  assert.equal(catalog.protocol, 'stored-biomes-6x6');
  assert.ok(Array.isArray(catalog.seeds) && catalog.seeds.length > 0, 'nonempty seeds');
  assert.equal(new Set(catalog.seeds).size, catalog.seeds.length, 'unique seeds');
  assert.ok(Array.isArray(catalog.chunkOrder) && catalog.chunkOrder.length > 0, 'nonempty chunk query order');
  assert.equal(new Set(catalog.chunkOrder).size, catalog.chunkOrder.length, 'unique chunk query order');
  assert.ok(Array.isArray(catalog.entries) && catalog.entries.length > 0, 'nonempty golden matrix');
  const profiles = readdirSync(join(here, '../versions')).filter(name => name.endsWith('.json'))
    .map(name => json(join(here, '../versions', name))).filter(profile => profile.worldgenReference);
  assert.ok(profiles.length > 0, 'nonempty profile discovery');
  const identities = new Set();
  let chunks = 0;
  for (const entry of catalog.entries) {
    const profile = profiles.find(candidate => candidate.id === entry.profile);
    assert.ok(profile, `unknown profile ${entry.profile}`);
    assert.ok(catalog.seeds.includes(entry.seed), `unexpected seed ${entry.seed}`);
    const identity = `${entry.profile}:${entry.seed}`;
    assert.ok(!identities.has(identity), `duplicate regression ${identity}`);
    identities.add(identity);
    const directory = join(root, `seed-${entry.seed}`, entry.profile);
    const provenance = json(join(directory, 'provenance.json'));
    assert.equal(provenance.profile, entry.profile, `${identity}: actual profile`);
    assert.equal(provenance.seed, entry.seed, `${identity}: actual seed`);
    assert.deepEqual(provenance, entry.provenance, `${identity}: JVM provenance`);
    // meshVertexStride is a mesher-only layout field added after these goldens were recorded;
    // every worldgen-relevant semantic must still match exactly.
    const {meshVertexStride, ...worldgenSemantics} = profile.kernelSemantics;
    assert.deepEqual(provenance.semantics, worldgenSemantics, `${identity}: profile semantics`);
    assert.deepEqual(provenance.chunks, catalog.chunkOrder, `${identity}: chunk query order`);
    assert.equal(sha256(readFileSync(join(directory, 'overworld.gwir'))), entry.irSha256,
      `${identity}: generator IR`);
    assert.deepEqual(readdirSync(directory).filter(name => name.endsWith('.ref')).sort(),
      entry.chunks.map(chunk => chunk.name), `${identity}: complete reference inventory`);
    assert.equal(entry.chunks.length, catalog.chunkOrder.length);
    for (const chunk of entry.chunks) {
      const bytes = readFileSync(join(directory, chunk.name));
      assert.equal(bytes.length, chunk.bytes, `${identity}/${chunk.name}: size`);
      assert.equal(sha256(bytes), chunk.sha256, `${identity}/${chunk.name}: vanilla golden`);
      chunks++;
    }
  }
  assert.equal(identities.size, profiles.length * catalog.seeds.length, 'complete profile/seed matrix');
  return {profiles: profiles.length, seeds: catalog.seeds.length, chunks};
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.ok(process.argv[2], 'usage: worldgen-regression-check.mjs <reference-matrix-directory>');
  const result = verifyWorldgenRegressions(resolve(process.argv[2]));
  console.log(`worldgen regression goldens: ${result.profiles} profiles, ${result.seeds} seeds, ${result.chunks} chunks matched`);
}
