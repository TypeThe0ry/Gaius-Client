import assert from "node:assert/strict";
import {mkdirSync, mkdtempSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {test} from "node:test";
import {buildManifest, checkStatus, diffIsClean, diffManifests, parseSpec} from "./patchkit.mjs";
import {readAcknowledgements, targetDigest, validateTargets} from "./target-lock.mjs";

const hash = (digit) => digit.repeat(64);
const target = (extra = {}) => ({className: "fixture/Example", method: "value", descriptor: "()I",
  operation: "rewrite", targetHash: hash("a"), vanillaPresent: true, ...extra});
const patch = (extra = {}) => ({id: "Fixture.patch", patcher: "Fixture", kind: "PERF", targets: ["js"],
  profiles: "*", outcome: "APPLIED", rewrites: ["fixture/Example.class"], methodTargets: [target()], ...extra});
const manifest = (...patches) => ({schema: 2, targetHashAlgorithm: "sha256-asm-symbolic-v1",
  profile: "fixture", target: "js", patches});
const acknowledgement = (before, after) => ({profile: "fixture", target: "js", id: after.id,
  previousTargetDigest: targetDigest(before), currentTargetDigest: targetDigest(after),
  reviewer: "test reviewer", reason: "test-only acknowledgement"});

test("body-only changes are blocking despite identical class lists", () => {
  const before = patch();
  const after = patch({methodTargets: [target({targetHash: hash("b")})]});
  const diff = diffManifests(manifest(before), manifest(after));
  assert.deepEqual(diff.blocking, [after.id]);
  assert.equal(diff["target-changed"][0].bodyChanged, true);
  const accepted = diffManifests(manifest(before), manifest(after), [acknowledgement(before, after)]);
  assert.deepEqual(accepted.blocking, []);
  assert.equal(diffIsClean(accepted), true);
  assert.equal(diffIsClean(diffManifests(manifest(before), manifest(after),
    [{...acknowledgement(before, after), target: "wasmgc"}])), false);
});

test("rewrite, whole-class override and static initializers require exact review digests", () => {
  for (const row of [target(), target({operation: "injected"}),
    target({method: "<class>", descriptor: "", operation: "override"}),
    target({method: "<clinit>", descriptor: "()V", vanillaStaticWrites: ["fixture/Example.x:I"],
      staticWrites: ["fixture/Example.x:I"]})]) {
    const before = patch({methodTargets: [row]});
    const after = patch({methodTargets: [{...row, targetHash: hash("c")}]});
    assert.equal(diffManifests(manifest(before), manifest(after)).blocking.length, 1);
    assert.equal(diffManifests(manifest(before), manifest(after), [acknowledgement(before, after)])
      .blocking.length, 0);
  }
});

test("static-write sets cannot be acknowledged away", () => {
  const row = target({method: "<clinit>", descriptor: "()V", vanillaStaticWrites: ["fixture/Example.x:I"],
    staticWrites: ["fixture/Example.x:I"]});
  const before = patch({methodTargets: [row]});
  const after = patch({methodTargets: [{...row, staticWrites: ["fixture/Example.y:I"]}]});
  const diff = diffManifests(manifest(before), manifest(after), [acknowledgement(before, after)]);
  assert.deepEqual(diff.hardBlocking, [after.id]);
  assert.equal(diffIsClean(diff), false);
  assert.deepEqual(validateTargets(manifest(after)), []);
  assert.equal(diffManifests(manifest(before), manifest()).hardBlocking.length, 1);
});

test("new and repeated REQUIRED failures stay blocking", () => {
  const failed = patch({kind: "REQUIRED", outcome: "FAILED", rewrites: [], methodTargets: []});
  for (const previous of [manifest(), manifest(failed)]) {
    const diff = diffManifests(previous, manifest(failed));
    assert.deepEqual(diff.blocking, [failed.id]);
    assert.equal(diff.failed.length, 1);
    assert.equal(diff.unchanged.length, 0);
  }
});

test("rename detection never hides changed targets or failures", () => {
  const before = patch();
  const after = patch({id: "Fixture.renamed", methodTargets: [target({targetHash: hash("d")})]});
  const diff = diffManifests(manifest(before), manifest(after));
  assert.equal(diff.renamed.length, 1);
  assert.deepEqual(diff.blocking, [after.id]);
  assert.equal(diffManifests(manifest(before), manifest({...after, outcome: "FAILED", kind: "PERF"}))
    .failed.length, 1);
});

test("normalization schema rejects missing targets and malformed static footprints", () => {
  assert.deepEqual(validateTargets(manifest(patch())), []);
  assert.match(validateTargets(manifest(patch({methodTargets: []}))).join(), /no target fingerprint/);
  assert.match(validateTargets(manifest(patch({methodTargets: [target({targetHash: "not-a-hash"})]}))).join(),
    /invalid method target/);
  const row = target({method: "<clinit>", vanillaStaticWrites: [], staticWrites: ["b", "a", "b"]});
  assert.match(validateTargets(manifest(patch({methodTargets: [row]}))).join(), /invalid staticWrites/);
});

test("declared exclusions and override specs use semantic flags, not version literals", () => {
  assert.equal(parseSpec("PERF NOT_APPLICABLE").notApplicable, true);
  assert.equal(parseSpec("REQUIRED OVERRIDE").wholeClass, true);
  assert.throws(() => parseSpec("PERF NOT_APPLICABLE NOT_APPLICABLE"));
});

test("schema-one initialization does not invent historical method hashes", () => {
  const before = patch();
  delete before.methodTargets;
  assert.equal(diffIsClean(diffManifests({schema: 1, patches: [before]}, manifest(patch()))), true);
  const after = patch({reason: "different metadata", methodTargets: [target({targetHash: hash("e")})]});
  assert.equal(diffIsClean(diffManifests(manifest(patch()), manifest(after),
    [acknowledgement(patch(), after)])), false);
});

test("acknowledgement files reject broad scopes, duplicate reviews and equal digests", () => {
  const root = mkdtempSync(join(tmpdir(), "gaius-patchkit-review-"));
  const directory = join(root, "port/tools/patchkit");
  mkdirSync(directory, {recursive: true});
  const path = join(directory, "acknowledgements.json");
  const entry = acknowledgement(patch(), patch({methodTargets: [target({targetHash: hash("f")})]}));
  const write = (entries) => writeFileSync(path, JSON.stringify({schema: 1, acknowledgements: entries}));
  try {
    write([entry]);
    assert.deepEqual(readAcknowledgements(root), [entry]);
    for (const field of ["profile", "target", "id"]) {
      write([{...entry, [field]: "*"}]);
      assert.throws(() => readAcknowledgements(root), /exact profile, target and patch id/);
    }
    write([entry, entry]);
    assert.throws(() => readAcknowledgements(root), /duplicate acknowledgement/);
    write([{...entry, currentTargetDigest: entry.previousTargetDigest}]);
    assert.throws(() => readAcknowledgements(root), /two distinct exact target digests/);
  } finally {
    rmSync(root, {recursive: true, force: true});
  }
});

test("manifest assembly rejects conflicting evidence instead of silently deduplicating it", () => {
  const assemble = (rows) => buildManifest({profile: "fixture", target: "js", steps: [],
    vanilla: new Set(["fixture/Example.class"]), records: [{...patch(), profile: "fixture", target: "js",
      classes: ["fixture/Example.class"], methodTargets: rows}]});
  assert.deepEqual(assemble([target(), target()]).problems, []);
  assert.match(assemble([target(), target({targetHash: hash("b")})]).problems.join(), /conflicting method/);
});

test("injected static initializers remain pinned without a vanilla body", () => {
  const row = target({method: "<clinit>", descriptor: "()V", operation: "injected", vanillaPresent: false,
    vanillaStaticWrites: [], staticWrites: ["fixture/Example.x:I"]});
  const before = patch({methodTargets: [row]});
  const after = patch({methodTargets: [{...row, staticWrites: ["fixture/Example.y:I"]}]});
  assert.deepEqual(diffManifests(manifest(before), manifest(after)).hardBlocking, [after.id]);
});

test("disabled and renamed failures retain the initializer hard gate in every check mode", () => {
  const row = target({method: "<clinit>", descriptor: "()V", operation: "injected", vanillaPresent: false,
    vanillaStaticWrites: [], staticWrites: ["fixture/Example.x:I"]});
  const before = patch({methodTargets: [row]});
  for (const outcome of ["DISABLED", "FAILED"]) {
    for (const id of [before.id, "Renamed.patch"]) {
      const after = patch({id, outcome, rewrites: [], methodTargets: []});
      assert.deepEqual(validateTargets(manifest(after)), []);
      const diff = diffManifests(manifest(before), manifest(after), [acknowledgement(before, after)]);
      assert.equal(diff.failed[0].staticWritesChanged, true);
      assert.deepEqual(diff.hardBlocking, [id]);
      assert.deepEqual(diff.blocking, [id]);
      for (const options of [{}, {compare: true}, {compare: true, warn: true},
        {write: true}, {write: true, warn: true}, {compare: true, write: true, warn: true}]) {
        assert.equal(checkStatus(diff.hardBlocking.length, 1, diff.blocking.length, options), 1);
      }
    }
  }
});

test("new initializer writers cannot be approved by refreshing a schema-two lock", () => {
  const row = target({method: "<clinit>", descriptor: "()V", operation: "injected", vanillaPresent: false,
    vanillaStaticWrites: [], staticWrites: ["fixture/Example.x:I"]});
  const before = patch({methodTargets: [row]});
  for (const outcome of ["APPLIED", "DISABLED", "FAILED"]) {
    for (const staticWrites of [row.staticWrites, ["fixture/Example.y:I"], []]) {
      const writer = patch({id: "Later.writer", patcher: "Later", outcome,
        methodTargets: [{...row, staticWrites}]});
      const current = manifest(before, writer);
      assert.deepEqual(validateTargets(current), []);
      const diff = diffManifests(manifest(before), current);
      assert.deepEqual(diff.hardBlocking, [writer.id]);
      assert.deepEqual(diff.blocking, [writer.id]);
      for (const options of [{}, {compare: true}, {compare: true, warn: true},
        {write: true}, {write: true, warn: true}, {compare: true, write: true, warn: true}]) {
        assert.equal(checkStatus(diff.hardBlocking.length, 1, diff.blocking.length, options), 1);
      }
      assert.equal(diffIsClean(diff), false);
    }
  }
  assert.deepEqual(diffManifests(manifest(before), manifest(before, patch({id: "Later.method"})))
    .hardBlocking, []);
  assert.deepEqual(diffManifests({schema: 1, patches: []}, manifest(before)).hardBlocking, []);
});
