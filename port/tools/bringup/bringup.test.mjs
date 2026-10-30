import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test, {after} from "node:test";
import {choosePrevious, detectSeam, draftProfile, fingerprintEntries, inheritKernelGate,
    relocationDiff, run, safeOutput, setDiff, usageEntries, fullExitCode, fullStatus, shellPath,
    classifyPatchOutcomes, patchClassification, disableKernels, shaderCorpusDiff, humanAction} from "./gaius-bringup.mjs";
import {apiDiffReport, parseJavaApiErrors} from "./api-diff.mjs";

// GAIUS_BRINGUP_TEST_OUT names a disposable output directory; without it the tests use a
// private temporary directory that is removed afterwards. Every test builds its own fixtures
// there, so no jar under port/work is needed.
const ownsOut = !process.env.GAIUS_BRINGUP_TEST_OUT;
const out = process.env.GAIUS_BRINGUP_TEST_OUT || mkdtempSync(join(tmpdir(), "gaius-bringup-test-"));
mkdirSync(out, {recursive: true});
after(() => {
    if (ownsOut) rmSync(out, {recursive: true, force: true});
});

test("closest dated profile handles nonnumeric snapshot identifiers", () => {
    const profiles = [{id: "older", official: {releaseTime: "2025-01-01"}},
        {id: "current", bringupAllowed: true, official: {releaseTime: "2025-03-01"}},
        {id: "future", official: {releaseTime: "2025-06-01"}}];
    assert.equal(choosePrevious("snapshot", profiles, "2025-04-01").id, "current");
    assert.equal(choosePrevious("snapshot", [...profiles, {id: "unreviewed", bringup: {provisional: true},
        official: {releaseTime: "2025-03-31"}}], "2025-04-01").id, "current");
    assert.throws(() => choosePrevious("alone", [{id: "alone"}]), /No previous/u);
});

test("SPI detection needs every declared class and exactly one generation", () => {
    const seam = {generation: "adapter-1", fingerprint: {implements: ["api/Device"], extends: [], instantiates: [], watch: ["api/Pipeline"]}};
    assert.equal(detectSeam(["api/Device", "api/Pipeline"], [seam]), "adapter-1");
    assert.throws(() => detectSeam(["api/Device"], [seam]), /found 0/u);
    assert.throws(() => detectSeam(["api/Device", "api/Pipeline"], [seam, seam]), /found 2/u);
});

test("fingerprints preserve type and body sections, ignoring profile headers", () => {
    const hash = "a".repeat(16);
    assert.deepEqual(fingerprintEntries(`profile alias\n[types]\n${hash} api/Device\n[bodies]\n${hash} api/Device.run()V\n`),
        [`[types] ${hash} api/Device`, `[bodies] ${hash} api/Device.run()V`]);
});

test("checked-in SPI rows use truncated hashes, not aggregate header hashes", () => {
    const root = resolve(import.meta.dirname, "../../..");
    const config = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8"));
    const profile = JSON.parse(readFileSync(join(root, "port", config.versionProfile), "utf8"));
    const text = readFileSync(join(root, "port/mc/profiles", profile.id, "spi.fingerprint"), "utf8");
    const entries = fingerprintEntries(text);
    assert.ok(entries.length > 100);
    assert.ok(entries.some((entry) => entry.startsWith("[types]")));
    assert.ok(entries.some((entry) => entry.startsWith("[bodies]")));
    assert.equal(fingerprintEntries("signature " + "b".repeat(64)).length, 0);
});

test("usage comparison binds coordinates and kind, not section counts", () => {
    const before = "profile stable\n[library org.example:lib:1] 2\nC api/Device\nM api/Device.run()V\n";
    const after = "profile alias\n[library org.example:lib:1] 99\nC api/Device\nM api/Device.run()V\n";
    assert.deepEqual(setDiff(usageEntries(before), usageEntries(after)), {added: [], removed: [], unchanged: 2});
    assert.equal(setDiff(usageEntries(before), usageEntries(after.replace(":1]", ":2]"))).added.length, 2);
});

test("class relocation suggestions are not asserted when names are ambiguous", () => {
    const delta = relocationDiff(["old/Device", "old/Shared"], ["new/Device", "one/Shared", "two/Shared"]);
    assert.deepEqual(delta.relocations, [{from: "old/Device", to: "new/Device", confidence: "unique-simple-name-only"}]);
});

test("predecessor default-off propagates transitively to dependent kernels", () => {
    const delta = {kernels: Object.fromEntries(["noise", "mesh", "world"].map((name) => [name, {enabled: true, reasons: []}]))};
    const previous = {kernelMirror: {enabled: {noise: false, mesh: true, world: true}}};
    inheritKernelGate(delta, previous, [{name: "world", dependencies: ["mesh"]},
        {name: "mesh", dependencies: ["noise"]}, {name: "noise", dependencies: []}]);
    assert.ok(Object.values(delta.kernels).every((kernel) => !kernel.enabled));
    assert.deepEqual(delta.kernels.world.reasons, ["DEPENDENCY:mesh"]);
});

test("output is rejected before creating a path under shared inputs", () => {
    const root = mkdtempSync(join(out, "path-test-"));
    const work = join(root, "work");
    mkdirSync(work);
    assert.throws(() => safeOutput(join(work, "new", "report"), work), /Output must not/u);
    assert.equal(safeOutput(join(root, "results"), work), resolve(root, "results"));
});

test("jar-derived draft never inherits a reviewed kernel approval", () => {
    const template = {id: "baseline", renderSeam: "adapter-1", inputSeam: "glfw", storage: {schema: 2},
        kernelMirror: {lockSha256: "a".repeat(64), enabled: {noise: true}}, kernelSemantics: {code: 1}};
    const metadata = {type: "snapshot", releaseTime: "2025-04-01", downloads: {client: {sha1: "b".repeat(40)},
        server: {sha1: "c".repeat(40)}}, assetIndex: {id: "assets", sha1: "d".repeat(40)}, libraries: [{name: "org.example:lib:1"}]};
    const data = {maxMajor: 65, javaRelease: 21, version: {protocol_version: 100, world_version: 200,
        pack_version: {resource_major: 1, resource_minor: 0, data_major: 2, data_minor: 0}}};
    const draft = draftProfile("new-snapshot", data, metadata, template);
    assert.equal(draft.protocolVersion, 100);
    assert.equal(draft.storage.databaseName, "gaius-fs-v2-new-snapshot");
    assert.equal(draft.kernelMirror.enabled.noise, false);
    assert.equal(draft.bringup.provisional, true);
    assert.equal(template.kernelMirror.enabled.noise, true);
});

test("a child library junction does not permit output under the input path", () => {
    const root = mkdtempSync(join(out, "junction-test-"));
    const work = join(root, "work");
    const cache = join(root, "cache");
    mkdirSync(work);
    mkdirSync(cache);
    symlinkSync(cache, join(work, "libraries"), "junction");
    assert.throws(() => safeOutput(join(work, "libraries/report"), work), /Output must not/u);
});

test("missing jars still produce an explicit blocking report", () => {
    const root = mkdtempSync(join(out, "missing-test-"));
    mkdirSync(join(root, "port/versions"), {recursive: true});
    mkdirSync(join(root, "port/work"), {recursive: true});
    writeFileSync(join(root, "port/versions/base.json"), JSON.stringify({id: "base"}));
    const reportOut = join(root, "report");
    const report = run({id: "snapshot", mode: "fast", root, work: join(root, "port/work"), out: reportOut, from: "base", draftOnly: false});
    assert.equal(report.exitCode, 2);
    assert.equal(report.noBlockingItems, false);
    assert.equal(JSON.parse(readFileSync(join(reportOut, "bringup-report.json"))).claims.teavm, false);
});

test("full report status rules distinguish skipped and required failures", () => {
    assert.deepEqual(fullStatus(new Error("tool not found"), false), {status: "SKIPPED", required: false});
    assert.deepEqual(fullStatus(new Error("tool not found"), true), {status: "FAIL", required: true});
    assert.equal(fullExitCode([{status: "SKIPPED", required: false}]), 0);
    assert.equal(fullExitCode([{status: "FAIL", required: false}]), 0);
    assert.equal(fullExitCode([{status: "FAIL", required: true}]), 2);
    assert.equal(fullExitCode([{status: "PASS", required: true}, {status: "SKIPPED", required: false}]), 0);
});

test("only REQUIRED patch failures are blockers; optional failures stay review", () => {
    const result = classifyPatchOutcomes({patches: [
        {id: "required", kind: "REQUIRED", outcome: "FAILED"},
        {id: "feature", kind: "FEATURE", outcome: "BRINGUP_SKIPPED"},
        {id: "perf", kind: "PERF", outcome: "FAILED"},
        {id: "telemetry", kind: "TELEMETRY", outcome: "FAILED"},
    ]});
    assert.deepEqual(result.required.map((patch) => patch.id), ["required"]);
    assert.deepEqual(result.optional.map((patch) => `${patch.kind}:${patch.id}`), ["FEATURE:feature", "PERF:perf", "TELEMETRY:telemetry"]);
    assert.deepEqual(result.unknown, []);
    assert.equal(result.classification, "blocking");
    assert.match(humanAction("patchkit"), /FEATURE\/PERF\/TELEMETRY 保持禁用/u);
});

test("actual patch gate keeps every optional failure and crash in review", () => {
    const delta = Object.fromEntries(["review", "failed", "new", "gone", "target-changed", "renamed"].map((name) => [name, []]));
    for (const kind of ["FEATURE", "PERF", "TELEMETRY"]) {
        for (const outcome of ["FAILED", "BRINGUP_SKIPPED", "DISABLED"]) {
            const manifest = {patches: [{id: "optional", kind, outcome}], steps: [{status: "failed"}]};
            assert.equal(patchClassification(manifest, delta), "review", `${kind}/${outcome}`);
            assert.equal(fullExitCode([{required: false, status: "FAIL"}]), 0);
        }
    }
    assert.equal(patchClassification({patches: [{kind: "REQUIRED", outcome: "FAILED"}], steps: []}, delta), "blocking");
    assert.equal(patchClassification({patches: [{kind: "unknown", outcome: "FAILED"}], steps: []}, delta), "review");
    assert.equal(patchClassification({patches: [], steps: []}, delta), "automatic");
    delta["target-changed"] = ["ack"];
    assert.equal(patchClassification({patches: [], steps: []}, delta), "review");
});

test("unverified goldens disable all kernels and preserve vanilla fallback", () => {
    const draft = {kernelMirror: {enabled: {noise: true, mesh: true, light: false}}};
    assert.deepEqual(disableKernels(draft, "golden mismatch"), {
        disabled: ["noise", "mesh", "light"], autoDisabled: true, keepVanilla: true, reason: "golden mismatch"});
    assert.ok(Object.values(draft.kernelMirror.enabled).every((enabled) => enabled === false));
});

test("shader baseline compares pipeline data and ignores timing and key order", () => {
    const before = {pipelines: [{name: "same", shaders: [{spirv: "old", type: "vertex"}]}, {name: "gone"}], summary: {totalMs: 10}};
    const after = {pipelines: [{shaders: [{type: "vertex", spirv: "old"}], name: "same"}, {name: "new"}], summary: {totalMs: 20}};
    assert.deepEqual(shaderCorpusDiff(before, after), {added: ["new"], removed: ["gone"], changed: [], unchanged: 1});
    after.pipelines[0].shaders[0].spirv = "changed";
    assert.deepEqual(shaderCorpusDiff(before, after).changed, ["same"]);
    assert.equal(shaderCorpusDiff(before, after).unchanged, 0);
    const location = {pipelines: [{location: "legacy:pipeline", shaders: []}]};
    assert.equal(shaderCorpusDiff(location, location).unchanged, 1);
    assert.throws(() => shaderCorpusDiff({pipelines: [{shaders: []}]}, location), /Missing or duplicate/u);
    assert.throws(() => shaderCorpusDiff({pipelines: [{name: "duplicate"}, {name: "duplicate"}]}, location), /Missing or duplicate/u);
});

test("javac API fixture reports removed nested types, constructor arity, and abstract methods", () => {
    const log = [
        "X.java:4: error: cannot find symbol",
        "  symbol:   class CreateInfo",
        "  location: class com.mojang.renderpearl.backend.api.BackendRenderPipeline",
        "X.java:5: error: constructor FrontendGpuDevice in class FrontendGpuDevice cannot be applied to given types;",
        "  required: GpuDeviceBackend,GpuDebugOptions",
        "  found:    GpuDeviceBackend",
        "X.java:6: error: Adapter is not abstract and does not override abstract method copyTextureToBuffer(Texture,int) in CommandEncoderBackend",
    ].join("\n");
    const changes = parseJavaApiErrors(log);
    assert.ok(changes.some((change) => change.category === "removed-type" && change.symbol === "CreateInfo"));
    assert.ok(changes.some((change) => change.category === "constructor-changed" && change.owner === "FrontendGpuDevice"));
    assert.ok(changes.some((change) => change.category === "added-abstract-method" && change.symbol.startsWith("copyTextureToBuffer")));
    const report = apiDiffReport("seam-api-diff", "seam-compile.log", log);
    assert.equal(report.actionable, true);
    assert.equal(report.errorCount, 3);
});

test("malformed javac output remains an actionable blocking diff", () => {
    const report = apiDiffReport("shader-api-diff", "shader-compile.log", "javac failed without diagnostics");
    assert.deepEqual(report.changes, []);
    assert.equal(report.actionable, false);
    assert.match(report.action, /修复 dumper|changed/u);
});

test("golden verifier rejects path traversal tags before generating or deleting outputs", () => {
    const build = mkdtempSync(join(out, "golden-tag-test-"));
    const result = spawnSync("bash", [resolve(import.meta.dirname, "../../native/golden/run-golden.sh"), "--verify", "test"], {
        // The tag is rejected before any input is read, so no JDK, checkout metadata or jar is needed.
        encoding: "utf8", env: {...process.env, GOLDEN_BUILD_ROOT: shellPath(build), GOLDEN_VERIFY_TAG: "/../../../escape",
            GOLDEN_JAVA_HOME: shellPath(build), GAIUS_WORK: shellPath(build)},
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /invalid golden verify tag/u);
});

test("every fast layer has a file-scoped human action", () => {
    for (const stage of ["native-entry", "relay-protocol", "version-lint", "apidiff", "kernel-golden", "shader-corpus"]) {
        assert.match(humanAction(stage), /port\//u, `${stage} action names a repository path`);
    }
    const context = {id: "next", from: "base", out: "/evidence/next", seam: "adapter-1", protocol: 42};
    assert.match(humanAction("profile", {}, context), /\/evidence\/next\/profile.draft.json/u);
    assert.match(humanAction("relay-protocol", {}, context), /apps\/bridge\/protocols\/42.json/u);
    assert.match(humanAction("seamprobe", {}, context), /port\/mc\/seams\/adapter-1\/seam.json/u);
});

test("shader dump paths use portable separators on Windows", () => {
    const value = shellPath("D:\\scratch\\shader-target");
    assert.equal(value.includes("\\"), process.platform !== "win32");
    assert.match(value, /shader-target$/u);
});

test("snapshot bootstrap refuses local execution before inspecting inputs", () => {
    const result = spawnSync(process.execPath, [join(import.meta.dirname, "snapshot-profile.mjs"), out], {
        encoding: "utf8", env: {...process.env, GITHUB_ACTIONS: "false"},
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Snapshot bootstrap is CI-only/u);
});
