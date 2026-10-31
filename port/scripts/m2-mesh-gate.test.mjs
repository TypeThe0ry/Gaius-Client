#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync, readdirSync} from "node:fs";
import {join, resolve} from "node:path";
import {evaluateMesh, verifyMeshEvidence} from "./m2-correctness-gate.mjs";

const families = ["solid", "fluid", "waterlogged", "transparent", "connected", "variant", "biome-tint", "height-edge", "mixed", "empty"];
const digest = "a".repeat(64);
function fixture() {
  const report = {schema: 1, profile: "fixture-profile", reference: "vanilla JVM SectionCompiler", comparison: "vertex-bytes",
    sectionCount: 300, verdict: "PASS", kernelSemantics: {code: 1, meshVertexStride: 28}, declarations: {},
    sources: {vanillaSha256: digest, fixtureSha256: digest, nativeSha256: digest, profileSha256: digest, harness: {source: digest}},
    modules: Object.fromEntries(["simd", "baseline"].map((variant) => [variant,
      {sha256: digest, manifestSha256: digest, manifestVerified: true, artifactKind: "shipped"}])), results: []};
  for (const variant of ["native", "simd", "baseline"]) for (let section = 0; section < 300; section++) {
    report.results.push({name: `section-${section}`, family: families[section % 10], variant,
      ambientOcclusion: Math.floor(section / 10) % 2 === 0, cutoutLeaves: Math.floor(section / 20) % 2 === 0,
      biomeBlendRadius: section % 3, sectionY: section % 2 ? -4 : 19, resultSha256: digest,
      nativeEqual: true, bytesCompared: section % 10 === 9 ? 0 : 112, bytesDifferent: 0});
  }
  for (const row of report.results.filter((row) => row.family === "height-edge")) row.sectionY = Number(row.name.slice(8)) % 20 === 7 ? -4 : 19;
  return report;
}
let checks = 0;
function expect(modify, status) {
  const report = fixture(); modify(report); assert.equal(evaluateMesh(report).status, status); checks++;
}
expect(() => {}, "PASS");
expect((report) => { report.sectionCount = 299; report.results = report.results.filter((row) => row.name !== "section-299"); }, "BLOCKED");
expect((report) => { report.results = report.results.filter((row) => row.variant !== "native"); }, "BLOCKED");
expect((report) => { report.results[1].name = report.results[0].name; }, "FAIL");
expect((report) => { report.results[300].bytesDifferent = 1; }, "FAIL");
expect((report) => { report.results[300].nativeEqual = false; }, "FAIL");
expect((report) => { report.results[300].resultSha256 = "b".repeat(64); }, "FAIL");
expect((report) => { report.modules = {}; }, "BLOCKED");
expect((report) => { report.modules.simd.artifactKind = "built-candidate"; }, "BLOCKED");
expect((report) => { report.results.push({...report.results[0], variant: "unknown"}); }, "BLOCKED");
expect((report) => { report.sources.harness = {}; }, "BLOCKED");
expect((report) => { for (const row of report.results) row.ambientOcclusion = true; }, "BLOCKED");
expect((report) => { for (const row of report.results) row.biomeBlendRadius = 0; }, "BLOCKED");
expect((report) => { for (const row of report.results) row.sectionY = 19; }, "BLOCKED");
expect((report) => { report.results[0].bytesCompared = 0; }, "FAIL");
expect((report) => { Object.assign(report.results[0], {declinedState: 7, reason: "unsupported", bytesCompared: 0}); }, "FAIL");
function fallback(report) {
  report.kernelSemantics.meshVertexStride = 32;
  report.declarations[7] = {kind: "vertex-layout", reason: "ABI cannot encode normals", semantics: 1};
  for (const row of report.results.filter((row) => row.family !== "empty")) Object.assign(row,
    {bytesCompared: 0, declinedState: 7, declineVerified: true, declineKind: "vertex-layout", reason: "ABI cannot encode normals"});
}
expect(fallback, "PASS");
expect((report) => { fallback(report); report.kernelSemantics.meshVertexStride = 28; }, "FAIL");
expect((report) => { fallback(report); report.declarations[7].semantics = 2; }, "FAIL");
expect((report) => { fallback(report); report.results[0].declineVerified = false; }, "FAIL");
expect((report) => { fallback(report); report.results[0].reason = "another reason"; }, "FAIL");
if (process.argv[2] === "--reports") {
  const output = resolve(process.argv[3]);
  for (const profile of readdirSync(output).filter((name) => /^[\w.-]+$/.test(name))) {
    let report;
    try { report = JSON.parse(readFileSync(join(output, profile, "report.json"))); }
    catch { continue; }
    assert.equal(evaluateMesh(report).status, "BLOCKED", "candidate modules must not pass the shipped M2 gate"); checks++;
    const verified = verifyMeshEvidence(report, join(output, "kernels"));
    assert.equal(verified.status, "PASS", verified.failures.join("\n")); checks++;
    for (const field of ["fixtureSha256", "nativeSha256", "profileSha256", "vanillaSha256"]) {
      const changed = structuredClone(report); changed.sources[field] = "b".repeat(64);
      assert.equal(verifyMeshEvidence(changed, join(output, "kernels")).status, "FAIL"); checks++;
    }
    const changed = structuredClone(report); changed.results[0].bytesDifferent = 1;
    assert.equal(verifyMeshEvidence(changed, join(output, "kernels")).status, "FAIL"); checks++;
  }
}
console.log(`M2_MESH_GATE_TEST PASS checks=${checks}`);
