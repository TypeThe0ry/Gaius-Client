#!/usr/bin/env node
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {dirname, isAbsolute, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {compareSection} from "./mesh-golden-compare.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const requiredChecks = ["bootsToTitle", "worldEntered", "terrainRendered", "movesWithW", "breaksBlock",
  "placesBlock", "saveAndQuit", "worldListed", "chunksReload", "playerPositionPersisted",
  "placedBlockPersisted", "terrainBaseline", "noShaderErrors", "noWebGLErrors", "noUncaughtExceptions"];
const variants = ["simd", "baseline"];
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const verdict = (failures, blockers, metrics = {}) => ({status: failures.length ? "FAIL"
  : blockers.length ? "BLOCKED" : "PASS", failures, blockers, metrics});
const nonempty = (value) => Number.isSafeInteger(value) && value > 0;

export function evaluateSeeds(reports, seeds) {
  const failures = [];
  const blockers = [];
  const rows = reports.flatMap((report) => report.results || []);
  if (!reports.length || reports.some((report) => !/^[0-9a-f]{40}$/.test(report.vanilla?.sha1 || ""))) {
    blockers.push("vanilla source identity missing");
  }
  if (new Set(reports.map((report) => report.vanilla?.sha1).filter(Boolean)).size > 1) {
    failures.push("seed reports use different vanilla sources");
  }
  for (const seed of seeds) {
    const reference = rows.find((row) => String(row.seed) === seed && row.label === "vanilla" && row.mode === "terrain");
    if (!reference || !nonempty(reference.positions)) blockers.push(`seed ${seed}: no JVM terrain reference`);
    for (const row of rows.filter((candidate) => String(candidate.seed) === seed
      && (["js", "wasm"].includes(candidate.label) || ["js", "wasm"].includes(candidate.clientRuntime)))) {
      failures.push(...(row.problems || []).map((problem) => `seed ${seed} ${row.label}: ${problem}`));
    }
    const browserRows = [];
    for (const runtime of ["js", "wasm"]) {
      const row = rows.find((candidate) => String(candidate.seed) === seed
        && candidate.clientRuntime === runtime && candidate.serverRuntime === runtime);
      if (!row) { blockers.push(`seed ${seed}: no ${runtime} client/server evidence`); continue; }
      browserRows.push(row);
      const counts = row.counts || {};
      for (const [compared, matched] of [["isolatedTerrain", "isolatedTerrainMatched"], ["biomesCompared", "biomesMatched"]]) {
        if (!nonempty(counts[compared])) blockers.push(`seed ${seed} ${runtime}: no ${compared}`);
        else if (counts[matched] !== counts[compared]) failures.push(`seed ${seed} ${runtime}: ${matched} ${counts[matched]}/${counts[compared]}`);
      }
    }
    if (browserRows.length !== 2) continue;
    const names = browserRows.map((row) => row.label);
    const pair = rows.find((row) => String(row.seed) === seed
      && [names.join(" vs "), [...names].reverse().join(" vs ")].includes(row.label));
    if (!pair) { blockers.push(`seed ${seed}: no direct js/wasm comparison`); continue; }
    failures.push(...(pair.problems || []).map((problem) => `seed ${seed} js/wasm: ${problem}`));
    for (const kind of ["blocks", "biomes", "light"]) {
      const compared = pair.counts?.[`${kind}Compared`];
      const matched = pair.counts?.[`${kind}Matched`];
      if (!nonempty(compared)) blockers.push(`seed ${seed}: no shared ${kind} comparison`);
      else if (matched !== compared) failures.push(`seed ${seed}: shared ${kind} ${matched}/${compared}`);
    }
  }
  return verdict(failures, blockers, {seeds, reports: reports.length});
}

export function verifyPacketLedger(report, bytes) {
  const logical = report.logicalParity;
  if (!Buffer.isBuffer(bytes) || sha256(bytes) !== logical?.ledger?.sha256) {
    return {ok: false, problem: "packet ledger missing or SHA-256 changed"};
  }
  try {
    const text = bytes.toString("utf8");
    if (!text.endsWith("\n")) throw new Error("packet ledger lacks a complete final row");
    const rows = text.slice(0, -1).split("\n").map(line => JSON.parse(line));
    const indexes = {browser: new Set(), jvm: new Set()};
    const counts = {equal: 0, explained: 0, difference: 0, unresolved: 0};
    const byId = new Map();
    for (const row of rows) {
      if (!Object.hasOwn(indexes, row.side) || !Number.isSafeInteger(row.index)
          || row.index < 0 || row.index >= logical[row.side] || indexes[row.side].has(row.index)
          || !Object.hasOwn(counts, row.status) || typeof row.reason !== "string" || !row.reason
          || !/^[a-f0-9]{64}$/.test(row.wireHash ?? "")) throw new Error("invalid or duplicate packet ledger row");
      indexes[row.side].add(row.index);
      counts[row.status]++;
      byId.set(`${row.side}/${row.index}`, row);
    }
    if (rows.length !== logical.accounted || indexes.browser.size !== logical.browser
        || indexes.jvm.size !== logical.jvm || Object.keys(counts).some(name => counts[name] !== logical[name])) {
      throw new Error("packet ledger coverage/counts do not match its report");
    }
    for (const row of rows) {
      if (row.counterpart === null && row.status !== "equal") continue;
      const other = byId.get(`${row.side === "browser" ? "jvm" : "browser"}/${row.counterpart}`);
      if (!other || other.counterpart !== row.index || other.status !== row.status
          || other.phase !== row.phase || other.key !== row.key
          || (row.status !== "difference" && other.name !== row.name)
          || (row.status === "equal" && other.wireHash !== row.wireHash)) {
        throw new Error("packet ledger counterpart binding differs");
      }
    }
    return {ok: true};
  } catch (error) { return {ok: false, problem: error.message}; }
}

export function evaluatePackets(report, verification = {}) {
  if (!report) return verdict([], ["packet report missing"]);
  const logical = report.logicalParity;
  if (logical) {
    const digest = value => typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
    const counts = [logical.browser, logical.jvm, logical.accounted, logical.equal, logical.explained,
      logical.difference, logical.unresolved];
    const complete = logical.schema === 1 && counts.every(value => Number.isSafeInteger(value) && value >= 0)
      && logical.browser > 0 && logical.jvm > 0 && logical.accounted === logical.browser + logical.jvm
      && logical.accounted === logical.equal + logical.explained + logical.difference + logical.unresolved
      && digest(logical.ledger?.sha256) && ["capture", "captureMetadata", "jvm", "table", "profile"]
        .every(name => digest(report.sourceChecks?.sha256?.[name]));
    if (!complete) return verdict([], ["incomplete logical packet ledger or source binding"]);
    if (verification.ledger?.ok !== true || verification.sources !== true) {
      return verdict([], [verification.ledger?.problem ?? "logical packet ledger/source files not verified"]);
    }
    if (report.sourceChecks?.pass !== true) return verdict([
      "packet session identity/completion/simulation-condition checks failed",
      ...(report.sourceChecks?.problems ?? [])], [],
      {accounted: logical.accounted, difference: logical.difference, unresolved: logical.unresolved});
    if (logical.pass === true && logical.difference === 0 && logical.unresolved === 0 && report.verdict === "PARITY") {
      return verdict([], [], {accounted: logical.accounted, equal: logical.equal, explained: logical.explained});
    }
    if (report.byteParity?.pass !== true) {
      const failures = logical.difference ? ["logical packet content/history differs"] : [];
      if (!logical.unresolved && !failures.length) failures.push("logical verdict/pass flag is inconsistent");
      return verdict(failures,
        logical.unresolved ? ["exact tick/entity evidence missing for remaining packet differences"] : [],
        {accounted: logical.accounted, difference: logical.difference, unresolved: logical.unresolved});
    }
  }
  const bytes = report.byteParity;
  if (!bytes || !nonempty(bytes.compared)) return verdict([], ["no exact decoded packet-byte evidence"]);
  const pass = bytes.pass === true && bytes.equal === bytes.compared && bytes.unequal === 0
    && bytes.browserOnly === 0 && bytes.jvmOnly === 0 && report.verdict === "PARITY";
  return verdict(pass ? [] : ["packet ids, payload bytes or stream order differ"], [], {
    compared: bytes.compared, equal: bytes.equal, unequal: bytes.unequal,
    browserOnly: bytes.browserOnly, jvmOnly: bytes.jvmOnly});
}

export function evaluateWorld(report, client = "wasm", server = "wasm", writer = null) {
  if (!report) return verdict([], ["in-world report missing"]);
  const failures = [];
  const blockers = [];
  if (report.verdict !== "PASS" || report.error) failures.push("whole in-world harness did not pass");
  for (const name of requiredChecks) {
    if (!report.checks?.[name]) blockers.push(`check ${name} missing`);
    else if (report.checks[name].ok !== true) failures.push(`check ${name} failed`);
  }
  for (const half of ["world", "reentered"]) {
    const runtime = report.runtimes?.[half];
    const expected = half === "world" && writer ? writer : {client, server};
    if (!runtime) blockers.push(`${half}: runtime evidence missing`);
    else if (runtime.client !== expected.client || runtime.server?.runtime !== expected.server
      || runtime.server?.runtimeStarted !== true) failures.push(`${half}: expected ${expected.client}/${expected.server} runtime`);
  }
  return verdict(failures, blockers, {bootSeconds: report.bootSeconds, phases: report.phases});
}

export function evaluateLight(report) {
  if (!report) return verdict([], ["JVM light golden report missing"]);
  const failures = [];
  const blockers = [];
  if (report.reference !== "vanilla JVM LevelLightEngine") blockers.push("reference is not the vanilla JVM light engine");
  const results = report.results || [];
  for (const variant of variants) {
    const rows = results.filter((row) => row.variant === variant);
    if (!rows.length || rows.some((row) => !nonempty(row.cellsPerLayer))) blockers.push(`${variant}: no light cell comparisons`);
    if (rows.some((row) => row.mismatches?.block !== 0 || row.mismatches?.sky !== 0)) failures.push(`${variant}: light nibbles differ`);
  }
  if (report.verdict !== "PASS") failures.push("light golden verdict did not pass");
  if (!Object.keys(report.hashes || {}).length) blockers.push("source/fixture/artifact hashes missing");
  return verdict(failures, blockers, {cases: results.length, cellsPerLayer: results.reduce((sum, row) => sum + (row.cellsPerLayer || 0), 0),
    boundaryCondition: report.boundaryCondition});
}

export function evaluateMesh(report) {
  if (!report) return verdict([], ["JVM mesher golden report missing"]);
  if (report.reference !== "vanilla JVM SectionCompiler" || report.comparison !== "vertex-bytes") {
    return verdict([], ["native probe equality or approximate pixel A/B is not exact JVM mesher golden data"],
      {diagnosticVerdict: report.verdict});
  }
  const results = report.results || [];
  const blockers = [];
  const failures = [];
  const digest = (value) => typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
  const families = ["solid", "fluid", "waterlogged", "transparent", "connected", "variant", "biome-tint", "height-edge", "mixed", "empty"];
  const nativeRows = results.filter((row) => row.variant === "native");
  const nativeCases = new Map(nativeRows.map((row) => [row.name, row]));
  if (results.length !== report.sectionCount * 3 || results.some((row) => !["native", ...variants].includes(row.variant))) {
    blockers.push("native and both wasm variants must cover exactly the same section set");
  }
  if (!nonempty(report.sectionCount) || report.sectionCount < 300 || nativeCases.size !== report.sectionCount) {
    blockers.push("at least 300 distinct native JVM sections are required");
  }
  for (const variant of ["native", ...variants]) {
    const rows = results.filter((row) => row.variant === variant);
    if (rows.length !== report.sectionCount || new Set(rows.map((row) => row.name)).size !== report.sectionCount
      || rows.some((row) => !nativeCases.has(row.name))) blockers.push(`${variant}: section coverage incomplete or duplicated`);
    for (const family of families) if (rows.filter((row) => row.family === family).length < 30) blockers.push(`${variant}: ${family} corpus incomplete`);
    if (![false, true].every((value) => rows.some((row) => row.ambientOcclusion === value))) blockers.push(`${variant}: AO on/off missing`);
    if (![false, true].every((value) => rows.some((row) => row.cutoutLeaves === value))) blockers.push(`${variant}: cutout leaves on/off missing`);
    if (![0, 1, 2].every((value) => rows.some((row) => row.biomeBlendRadius === value))) blockers.push(`${variant}: biome blend coverage missing`);
    if (new Set(rows.filter((row) => row.family === "height-edge").map((row) => row.sectionY)).size < 2) blockers.push(`${variant}: height edges missing`);
    for (const row of rows) {
      if (!digest(row.resultSha256) || row.nativeEqual !== true) failures.push(`${variant}/${row.name}: native payload differs or lacks identity`);
      const native = nativeCases.get(row.name);
      if (native && (row.resultSha256 !== native.resultSha256
        || ["family", "ambientOcclusion", "cutoutLeaves", "biomeBlendRadius", "sectionY"].some((field) => row[field] !== native[field]))) {
        failures.push(`${variant}/${row.name}: native case identity differs`);
      }
      if (!Number.isSafeInteger(row.bytesCompared) || row.bytesCompared < 0 || row.bytesDifferent !== 0) failures.push(`${variant}/${row.name}: mesher bytes differ`);
      if (row.declinedState !== undefined) {
        const declaration = report.declarations?.[row.declinedState];
        if (!Number.isSafeInteger(row.declinedState) || row.declineVerified !== true || !row.reason
          || declaration?.reason !== row.reason || declaration?.kind !== row.declineKind
          || declaration?.semantics !== report.kernelSemantics?.code || row.bytesCompared !== 0
          || !["vertex-layout", "unsupported-state"].includes(row.declineKind)
          || row.declineKind === "vertex-layout" && report.kernelSemantics?.meshVertexStride === 28) {
          failures.push(`${variant}/${row.name}: unverified or unregistered decline`);
        }
      } else if (row.bytesCompared === 0 && row.family !== "empty") failures.push(`${variant}/${row.name}: nonempty case has no comparison`);
    }
    if (variant !== "native") {
      const module = report.modules?.[variant];
      if (!digest(module?.sha256) || !digest(module?.manifestSha256) || module?.manifestVerified !== true) blockers.push(`${variant}: manifest-bound wasm identity missing`);
      if (module?.artifactKind !== "shipped") blockers.push(`${variant}: built candidate is not shipped artifact evidence`);
    }
  }
  if (["vanillaSha256", "fixtureSha256", "profileSha256", "nativeSha256"].some((name) => !digest(report.sources?.[name]))
    || !Object.keys(report.sources?.harness || {}).length || Object.values(report.sources?.harness || {}).some((value) => !digest(value))) {
    blockers.push("mesher source/fixture/native provenance missing");
  }
  if (report.verdict !== "PASS") failures.push("mesher golden verdict did not pass");
  return verdict(failures, blockers, {sections: report.sectionCount, comparisons: results.length,
    bytesCompared: results.reduce((sum, row) => sum + (row.bytesCompared || 0), 0),
    declined: nativeRows.filter((row) => row.declinedState !== undefined).length, corpus: report.corpus});
}

export function verifyMeshEvidence(report, shippedDist) {
  const failures = [];
  const blockers = [];
  if (!report) return verdict([], ["mesher report missing"]);
  try {
    const source = report.sources;
    const pinned = (path, digest) => {
      assert.equal(sha256(readFileSync(path)), digest, `mesh evidence changed: ${path}`);
    };
    assert(/^[\w.-]+$/.test(report.profile));
    const profilePath = join(root, "port/versions", `${report.profile}.json`);
    const profile = JSON.parse(readFileSync(profilePath));
    assert.deepEqual(report.kernelSemantics, profile.kernelSemantics);
    pinned(profilePath, source.profileSha256);
    assert.equal(resolve(source.vanillaPath), resolve(root, "port/work", profile.id, "client-named.jar"));
    pinned(source.vanillaPath, source.vanillaSha256);
    pinned(source.nativePath, source.nativeSha256);
    pinned(source.fixtureManifestPath, source.fixtureSha256);
    const fixture = JSON.parse(readFileSync(source.fixtureManifestPath));
    assert.equal(fixture.profile, profile.id);
    assert.equal(fixture.vanillaSha256, source.vanillaSha256);
    assert.equal(fixture.profileSha256, source.profileSha256);
    assert.deepEqual(fixture.sources, source.harness);
    assert.deepEqual(fixture.kernelSemantics, profile.kernelSemantics);
    for (const sources of [source.harness, report.verifierSources]) {
      assert(Object.keys(sources).length > 0);
      for (const [name, digest] of Object.entries(sources)) {
        assert(name.startsWith("port/") && !name.includes("..") && !name.includes(":"));
        pinned(join(root, name), digest);
      }
    }
    const directory = dirname(source.fixtureManifestPath);
    for (const [name, digest] of Object.entries(fixture.files)) {
      assert(!name.includes("/") && !name.includes("\\") && name !== "..");
      pinned(join(directory, name), digest);
    }
    const declarations = JSON.parse(readFileSync(join(directory, "declines.json")));
    assert.deepEqual(report.declarations, declarations);
    const sections = JSON.parse(readFileSync(join(directory, "sections.json")));
    assert.equal(report.sectionCount, sections.length);
    for (const name of ["table.bin", "sections.json", "declines.json", ...sections.flatMap((section) =>
      [`${section.name}.job`, ...["solid", "cutout", "translucent"].flatMap((layer) =>
        [`${section.name}.${layer}.vertices`, `${section.name}.${layer}.indices`])])]) {
      assert(fixture.files[name], `mesh evidence lacks a fixture digest: ${name}`);
    }
    const table = readFileSync(join(directory, "table.bin"));
    assert.equal(table.readUInt16LE(6), profile.kernelSemantics.code);
    const nativeRows = new Map(report.results.filter((row) => row.variant === "native").map((row) => [row.name, row]));
    for (const section of sections) {
      assert(/^section-\d+$/.test(section.name));
      const row = nativeRows.get(section.name);
      assert(row, `native result missing: ${section.name}`);
      for (const [field, value] of Object.entries(section)) assert.equal(row[field], value, `section metadata ${field}`);
      const job = readFileSync(join(directory, `${section.name}.job`));
      assert.equal(Boolean(job.readUInt32LE(12) & 1), section.ambientOcclusion);
      assert.equal(Boolean(job.readUInt32LE(12) & 2), section.cutoutLeaves);
      assert.equal(job.readUInt32LE(80), section.biomeBlendRadius);
      assert.equal(job.readInt32LE(20), section.sectionY);
      const result = readFileSync(join(source.nativeOutput, `${section.name}.native`));
      assert.equal(sha256(result), row.resultSha256);
      const references = Object.fromEntries(["solid", "cutout", "translucent"].map((layer) => [layer, {
        vertices: readFileSync(join(directory, `${section.name}.${layer}.vertices`)),
        indices: readFileSync(join(directory, `${section.name}.${layer}.indices`))}]));
      const actual = compareSection(result, references, job, table, profile.kernelSemantics.meshVertexStride);
      for (const [field, value] of Object.entries(actual)) assert.deepEqual(row[field], value, `native comparison ${field}`);
    }
    for (const variant of variants) {
      const module = report.modules[variant];
      pinned(module.path, module.sha256); pinned(module.manifestPath, module.manifestSha256);
      const manifest = JSON.parse(readFileSync(module.manifestPath));
      const entry = (manifest.kernels.mesh || manifest.kernels.mesher).variants[variant];
      assert.equal(entry.sha256, module.sha256); assert.equal(entry.bytes, module.bytes);
      assert.equal(resolve(dirname(module.manifestPath), entry.file || entry.path), resolve(module.path));
      if (!shippedDist) blockers.push(`${variant}: release dist binding missing`);
      else {
        const dist = resolve(shippedDist);
        const expected = existsSync(join(dist, "kernels.json")) ? dist : join(dist, "kernels");
        assert.equal(resolve(module.manifestPath), join(expected, "kernels.json"), "mesh artifact not from selected release");
      }
    }
  } catch (error) { failures.push(error.message); }
  return verdict(failures, blockers);
}

function selfTest() {
  assert.equal(evaluateSeeds([], ["1"]).status, "BLOCKED");
  const reports = [{vanilla: {sha1: "a".repeat(40)}, results: [{seed: "1", label: "vanilla", mode: "terrain", positions: 1},
    ...["js", "wasm"].map((runtime) => ({seed: "1", label: runtime, clientRuntime: runtime, serverRuntime: runtime,
      counts: {isolatedTerrain: 1, isolatedTerrainMatched: 1, biomesCompared: 1, biomesMatched: 1}, problems: []})),
    {seed: "1", label: "js vs wasm", counts: {blocksCompared: 1, blocksMatched: 1, biomesCompared: 1,
      biomesMatched: 1, lightCompared: 1, lightMatched: 1}, problems: []}]}];
  assert.equal(evaluateSeeds(reports, ["1"]).status, "PASS");
  assert.equal(evaluateSeeds(reports, ["1", "2"]).status, "BLOCKED");
  assert.equal(evaluateSeeds([...reports, {vanilla: {sha1: "b".repeat(40)}, results: []}], ["1"]).status, "FAIL");
  assert.equal(evaluateSeeds([{vanilla: {sha1: "a".repeat(40)}, results:
    [{seed: "1", label: "wasm", problems: ["capture failed"]}]}], ["1"]).status, "FAIL");
  reports[0].results[1].counts.biomesMatched = 0;
  assert.equal(evaluateSeeds(reports, ["1"]).status, "FAIL");
  assert.equal(evaluatePackets({verdict: "PARITY"}).status, "BLOCKED");
  assert.equal(evaluatePackets({verdict: "PARITY", byteParity: {pass: true, compared: 1, equal: 1, unequal: 0,
    browserOnly: 0, jvmOnly: 0}}).status, "PASS");
  assert.equal(evaluatePackets({verdict: "PARITY", byteParity: {pass: true, compared: 1, equal: 1, unequal: 0,
    browserOnly: 1, jvmOnly: 0}}).status, "FAIL");
  assert.equal(evaluateLight({verdict: "PASS", results: []}).status, "BLOCKED");
  assert.equal(evaluateMesh({verdict: "PASS"}).status, "BLOCKED");
  assert.equal(evaluateWorld({verdict: "PASS", checks: {}}).status, "BLOCKED");
  const world = {verdict: "PASS", checks: Object.fromEntries(requiredChecks.map((name) => [name, {ok: true}])),
    runtimes: Object.fromEntries(["world", "reentered"].map((name) => [name,
      {client: "wasm", server: {runtime: "wasm", runtimeStarted: true}}]))};
  assert.equal(evaluateWorld(world).status, "PASS");
  assert.equal(evaluateWorld({...world, verdict: "FAIL"}).status, "FAIL");
  console.log("M2_CORRECTNESS_SELF_TEST PASS");
}

function main(args) {
  if (args.length === 1 && args[0] === "--self-test") return selfTest();
  if (args.length !== 4 || args[0] !== "--config" || args[2] !== "--out") {
    throw new Error("usage: m2-correctness-gate.mjs --config <json> --out <directory> | --self-test");
  }
  const configPath = resolve(args[1]);
  const config = JSON.parse(readFileSync(configPath, "utf8"));
  assert.equal(config.schema, 1);
  assert.ok(Array.isArray(config.seeds) && config.seeds.length > 0
    && config.seeds.every((seed) => typeof seed === "string" && /^-?\d+$/.test(seed)));
  assert.equal(new Set(config.seeds).size, config.seeds.length, "seed set must be unique");
  const evidence = [{path: configPath, sha256: sha256(readFileSync(configPath))}];
  const pathOf = (path) => isAbsolute(path) ? path : resolve(dirname(configPath), path);
  const load = (path, profile) => {
    if (!path) return null;
    const absolute = pathOf(path);
    if (!existsSync(absolute)) return null;
    const bytes = readFileSync(absolute);
    const report = JSON.parse(bytes);
    if (report.profile !== undefined) assert.equal(report.profile, profile, `profile mismatch in ${absolute}`);
    evidence.push({path: absolute, bytes: bytes.length, sha256: sha256(bytes)});
    return report;
  };
  const profiles = readdirSync(join(root, "port/versions")).filter((name) => name.endsWith(".json"))
    .map((name) => JSON.parse(readFileSync(join(root, "port/versions", name), "utf8"))).map((profile) => profile.id).sort();
  const results = {};
  for (const profile of profiles) {
    const inputs = config.profiles?.[profile] || {};
    const packetReport = load(inputs.packets, profile);
    const packetVerification = {};
    if (packetReport?.logicalParity) {
      const ledger = packetReport.logicalParity.ledger;
      if (ledger?.file === "packet-ledger.jsonl") {
        const ledgerPath = join(dirname(pathOf(inputs.packets)), ledger.file);
        if (existsSync(ledgerPath)) {
          const bytes = readFileSync(ledgerPath);
          evidence.push({path: ledgerPath, bytes: bytes.length, sha256: sha256(bytes)});
          packetVerification.ledger = verifyPacketLedger(packetReport, bytes);
        }
      }
      const sources = {capture: join(packetReport.capture ?? "", "capture.jsonl"),
        captureMetadata: join(packetReport.capture ?? "", "capture.json"),
        jvm: join(packetReport.jvm ?? "", "jvm-stream.json"),
        table: join(packetReport.jvm ?? "", `protocol-${profile}.json`),
        profile: join(root, "port/versions", `${profile}.json`)};
      packetVerification.sources = Object.entries(sources).every(([name, path]) => {
        if (!existsSync(path)) return false;
        const bytes = readFileSync(path);
        const hash = sha256(bytes);
        evidence.push({path, bytes: bytes.length, sha256: hash});
        return hash === packetReport.sourceChecks?.sha256?.[name];
      });
    }
    const meshReport = load(inputs.mesh, profile);
    const mesh = evaluateMesh(meshReport);
    if (meshReport?.reference === "vanilla JVM SectionCompiler") {
      const evidenceCheck = verifyMeshEvidence(meshReport, inputs.build?.dist && pathOf(inputs.build.dist));
      mesh.failures.push(...evidenceCheck.failures); mesh.blockers.push(...evidenceCheck.blockers);
      mesh.status = verdict(mesh.failures, mesh.blockers).status;
    }
    const gates = {
      seeds: evaluateSeeds((inputs.seeds || []).map((path) => load(path, profile)).filter(Boolean), config.seeds),
      packets: evaluatePackets(packetReport, packetVerification),
      mesh,
      light: evaluateLight(load(inputs.light, profile)),
      inWorld: evaluateWorld(load(inputs.inWorld, profile)),
    };
    const storageFailures = [];
    const storageBlockers = [];
    for (const direction of ["wasm-to-js", "js-to-wasm"]) {
      if (!inputs.storage) { storageBlockers.push(`${direction}: report missing`); continue; }
      const directory = join(pathOf(inputs.storage), direction);
      const report = load(join(directory, "report.json"), profile);
      const [writer, reader] = direction.split("-to-");
      const result = evaluateWorld(report, reader, reader, {client: writer, server: writer});
      const consolePath = join(directory, "console.log");
      if (existsSync(consolePath)) {
        const bytes = readFileSync(consolePath);
        evidence.push({path: consolePath, bytes: bytes.length, sha256: sha256(bytes)});
        if (/Chunk \[-?\d+, ?-?\d+\] (?:header is truncated|stream is truncated|is allocated, but stream is missing)|Chunk found in invalid location|Failed to (?:load|store) chunk|Couldn't load chunk|Failed to read chunk/.test(bytes.toString("utf8"))) {
          result.failures.push("region file read/write error");
        }
      } else storageBlockers.push(`${direction}: console evidence missing`);
      storageFailures.push(...result.failures.map((failure) => `${direction}: ${failure}`));
      storageBlockers.push(...result.blockers.map((blocker) => `${direction}: ${blocker}`));
    }
    gates.storage = verdict(storageFailures, storageBlockers);
    const buildFailures = [];
    const buildBlockers = [];
    const modules = [];
    for (const name of ["classes.wasm", "singleplayer-server.wasm"]) {
      if (!inputs.build?.dist || !existsSync(join(pathOf(inputs.build.dist), name))) {
        buildBlockers.push(`${name} missing`); continue;
      }
      const path = join(pathOf(inputs.build.dist), name);
      const bytes = readFileSync(path);
      const valid = WebAssembly.validate(bytes);
      const item = {path, bytes: bytes.length, sha256: sha256(bytes), valid};
      evidence.push(item);
      modules.push(item);
      if (!valid) buildFailures.push(`${name} is invalid WebAssembly`);
    }
    if (!/^[0-9a-f]{40}$/.test(config.sourceRevision || "") || inputs.build?.revision !== config.sourceRevision) {
      buildBlockers.push("build is not bound to the requested source revision");
    }
    gates.build = verdict(buildFailures, buildBlockers, {modules});
    results[profile] = gates;
    for (const [name, gate] of Object.entries(gates)) console.log(`M2_GATE ${profile} ${name} ${gate.status}`);
  }
  const statuses = Object.values(results).flatMap((gates) => Object.values(gates).map((gate) => gate.status));
  const status = statuses.includes("FAIL") ? "FAIL" : statuses.includes("BLOCKED") ? "BLOCKED" : "PASS";
  const output = resolve(args[3]);
  mkdirSync(output, {recursive: true});
  writeFileSync(join(output, "m2-correctness.json"), JSON.stringify({schema: 1, verdict: status,
    sourceRevision: config.sourceRevision, observedAt: new Date().toISOString(), seeds: config.seeds,
    profiles: results, evidence}, null, 2) + "\n");
  console.log(`M2_CORRECTNESS ${status} ${output}`);
  process.exitCode = status === "PASS" ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(`M2_CORRECTNESS_ERROR ${error.stack}`); process.exitCode = 1; }
}
