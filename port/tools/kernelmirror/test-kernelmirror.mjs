import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {tmpdir} from "node:os";
import {computeLock, diffLocks, enableTable, evaluateProfile, inspectMethods, loadManifest,
    reviewedTable, serialize, sha256} from "./kernelmirror.mjs";

const directory = resolve(process.env.KERNELMIRROR_TEST_DIR ?? join(tmpdir(), "gaius-kernelmirror-test"));
mkdirSync(directory, {recursive: true});
const javaHome = process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME;
const executable = (name) => javaHome ? join(javaHome, "bin", process.platform === "win32" ? name + ".exe" : name) : name;
const methods = [
    {owner: "MirrorFixture", name: "sample", descriptor: "(I)Ljava/lang/String;"},
    {owner: "MirrorFixture", name: "lambda", descriptor: "()Ljava/util/function/IntUnaryOperator;"},
];
const source = (value, prefix = "", caught = "IllegalArgumentException", operation = "+") => `
public class MirrorFixture {
    ${prefix}
    public static String sample(int input) {
        try {
            switch (input) {
                case -1: return "negative";
                case 2: return "two";
                default: if (input > 0) return "value=" + input; return "${value}";
            }
        } catch (${caught} failure) { return "exception"; }
    }
    public static java.util.function.IntUnaryOperator lambda() { return value -> value ${operation} 1; }
}
`;
function hashes(label, text, debug) {
    const output = join(directory, label);
    mkdirSync(output, {recursive: true});
    const path = join(output, "MirrorFixture.java");
    writeFileSync(path, text);
    execFileSync(executable("javac"), ["-J-Xmx3g", debug, "-d", output, path]);
    const jar = join(output, "fixture.jar");
    execFileSync(executable("jar"), ["-J-Xmx3g", "--create", "--file", jar, "-C", output, "MirrorFixture.class"]);
    return inspectMethods(jar, methods, javaHome);
}
const lookup = (values, name) => [...values.values()].find((value) => value.name === name).targetHash;
const original = hashes("original", source("zero"), "-g:none");
const debug = hashes("debug", "\n\n" + source("zero"), "-g");
assert.equal(lookup(original, "sample"), lookup(debug, "sample"));
assert.equal(lookup(original, "lambda"), lookup(debug, "lambda"));
const constants = Array.from({length: 320}, (_, index) => `public static String extra${index}() { return "extra${index}"; }`).join("\n");
const reindexed = hashes("reindexed", source("zero", constants), "-g");
assert.equal(lookup(original, "sample"), lookup(reindexed, "sample"), "CP indices, ldc_w and switch offsets are normalized");
assert.equal(lookup(original, "lambda"), lookup(reindexed, "lambda"), "bootstrap CP references are normalized");
const changed = hashes("changed", source("changed"), "-g:none");
assert.notEqual(lookup(original, "sample"), lookup(changed, "sample"));
const exception = hashes("exception", source("zero", "", "RuntimeException"), "-g:none");
assert.notEqual(lookup(original, "sample"), lookup(exception, "sample"));
const bootstrap = hashes("bootstrap", source("zero").replace('"value=" + input', '"other=" + input'), "-g:none");
assert.notEqual(lookup(original, "sample"), lookup(bootstrap, "sample"));
const absent = inspectMethods(join(directory, "original/fixture.jar"), [
    {...methods[0], descriptor: "(J)Ljava/lang/String;"},
    {...methods[0], name: "missing"},
    {...methods[0], owner: "MissingFixture"},
], javaHome);
assert.deepEqual([...absent.values()].map((method) => method.targetHash), [null, null, null]);
assert.deepEqual([...absent.values()][0].alternatives, [methods[0].descriptor]);
assert.deepEqual([...absent.values()][1].alternatives, []);

const kernels = [
    {name: "noise", dependencies: [], mirrorsVanilla: true, rationale: "test"},
    {name: "worldgen", dependencies: ["noise"], mirrorsVanilla: true, rationale: "test"},
];
const method = {kernel: "noise", owner: "MirrorFixture", name: "sample", descriptor: "(I)Ljava/lang/String;", targetHash: lookup(original, "sample")};
const other = {...method, kernel: "worldgen"};
const baseline = {schemaVersion: 1, normalization: "gaius-kernel-mirror-bytecode-v1", profile: "test-profile",
    kernelSemantics: {code: 0}, kernels, methods: [method, other]};
const changedLock = {...baseline, methods: [{...method, targetHash: lookup(changed, "sample")}, other]};
assert.equal(diffLocks(baseline, baseline).methods[0].status, "UNCHANGED");
const body = diffLocks(baseline, changedLock);
assert.equal(body.methods[0].status, "BODY");
assert.equal(body.kernels.noise.enabled, false);
assert.equal(body.kernels.worldgen.enabled, false);
assert.match(body.kernels.worldgen.reasons.join(), /DEPENDENCY/);
assert.equal(diffLocks(baseline, {...baseline, methods: [other]}).methods[0].status, "GONE");
const signature = {...baseline, methods: [{...method, targetHash: null, alternatives: ["(J)Ljava/lang/String;"]}, other]};
assert.equal(diffLocks(baseline, signature).methods[0].status, "SIGNATURE");
const text = serialize(baseline);
const profile = {id: baseline.profile, kernelMirror: {lockSha256: sha256(text), enabled: {noise: true, worldgen: true}}};
assert.equal(enableTable(profile, {kernels}, text, baseline).kernels.noise.enabled, true);
assert.equal(enableTable(profile, {kernels}, text, changedLock).kernels.noise.enabled, false);
assert.equal(enableTable(profile, {kernels}, text + "\n", baseline).kernels.noise.enabled, false);
assert.equal(enableTable({id: "new-profile"}, {kernels}, text, baseline).kernels.noise.enabled, false);
assert.equal(enableTable(profile, {kernels}, text, null, "Missing jar").kernels.noise.enabled, false);
const oreKernels = [...kernels, {name: "ore", dependencies: ["worldgen"], mirrorsVanilla: true, rationale: "test"}];
const oreMethod = {...method, kernel: "ore"};
const oreLock = {...baseline, kernels: oreKernels, methods: [...baseline.methods, oreMethod]};
const oreText = serialize(oreLock);
const oreProfile = {id: baseline.profile, kernelMirror: {lockSha256: sha256(oreText),
    enabled: {noise: true, worldgen: true, ore: true}}};
assert.equal(enableTable(oreProfile, {kernels: oreKernels}, oreText, oreLock).kernels.ore.enabled, true);
for (const changedOre of [{...oreMethod, targetHash: lookup(changed, "sample")},
    {...oreMethod, targetHash: null, alternatives: ["(J)Ljava/lang/String;"]}, null]) {
    const currentOre = {...oreLock, methods: [...baseline.methods, ...(changedOre ? [changedOre] : [])]};
    const result = enableTable(oreProfile, {kernels: oreKernels}, oreText, currentOre);
    assert.equal(result.kernels.ore.enabled, false);
    assert.equal(result.kernels.worldgen.enabled, true);
}
const changedDependency = {...oreLock, methods: [...changedLock.methods, oreMethod]};
assert.equal(enableTable(oreProfile, {kernels: oreKernels}, oreText, changedDependency).kernels.ore.enabled, false);
const disabledOre = {...oreProfile, kernelMirror: {...oreProfile.kernelMirror,
    enabled: {...oreProfile.kernelMirror.enabled, ore: false}}};
assert.deepEqual(enableTable(disabledOre, {kernels: oreKernels}, oreText, oreLock).kernels.ore.reasons,
    ["PROFILE_DEFAULT_OFF"]);
const root = join(directory, "integration");
const versionDirectory = join(root, "port/versions");
const lockDirectory = join(root, "port/mc/profiles", baseline.profile);
mkdirSync(versionDirectory, {recursive: true});
mkdirSync(lockDirectory, {recursive: true});
mkdirSync(join(root, "port/native"), {recursive: true});
const manifestPath = join(root, "port/native/kernel-mirror.toml");
const manifestText = "schemaVersion = 1\n" + kernels.map((kernel) => "\n[[kernels]]\n"
    + Object.entries(kernel).map(([key, value]) => `${key} = ${JSON.stringify(value)}\n`).join("")).join("")
    + [method, other].map((record) => "\n[[methods]]\n" + Object.entries({kernel: record.kernel,
        owner: record.owner, name: record.name, descriptor: record.descriptor, profiles: [baseline.profile]})
        .map(([key, value]) => `${key} = ${JSON.stringify(value)}\n`).join("")).join("");
writeFileSync(manifestPath, manifestText);
const manifest = loadManifest(manifestPath);
const originalOptions = {jar: join(directory, "original/fixture.jar"), javaHome, manifest};
const changedOptions = {...originalOptions, jar: join(directory, "changed/fixture.jar")};
const fixtureProfile = {id: baseline.profile, kernelSemantics: baseline.kernelSemantics};
const lockedText = serialize(computeLock(root, fixtureProfile, manifest, originalOptions));
const lockPath = join(lockDirectory, "kernel-mirror.lock");
writeFileSync(lockPath, lockedText);
fixtureProfile.kernelMirror = {lockSha256: sha256(lockedText), enabled: {noise: true, worldgen: true}};
const profilePath = join(versionDirectory, baseline.profile + ".json");
writeFileSync(profilePath, serialize(fixtureProfile));
assert.equal(evaluateProfile(root, fixtureProfile, originalOptions).table.kernels.noise.enabled, true);
assert.equal(evaluateProfile(root, fixtureProfile, changedOptions).table.kernels.noise.enabled, false);
assert.equal(reviewedTable(root, fixtureProfile).kernels.noise.enabled, true);
const cli = fileURLToPath(new URL("kernelmirror.mjs", import.meta.url));
execFileSync(process.execPath, [cli, "generate", "--root", root, "--jar", changedOptions.jar,
    ...(javaHome ? ["--java-home", javaHome] : [])]);
assert.notEqual(readFileSync(lockPath, "utf8"), lockedText);
assert.deepEqual(JSON.parse(readFileSync(profilePath, "utf8")), fixtureProfile, "regeneration never approves changes");
assert.equal(evaluateProfile(root, fixtureProfile, changedOptions).table.kernels.noise.enabled, false);
assert.equal(reviewedTable(root, fixtureProfile).kernels.noise.enabled, false);
fixtureProfile.kernelMirror.lockSha256 = sha256(readFileSync(lockPath, "utf8"));
assert.equal(evaluateProfile(root, fixtureProfile, changedOptions).table.kernels.noise.enabled, true);
writeFileSync(manifestPath, manifestText + `\n[[methods]]\nkernel = "noise"\nowner = "MirrorFixture"\n`
    + `name = "lambda"\ndescriptor = "()Ljava/util/function/IntUnaryOperator;"\nprofiles = ["${baseline.profile}"]\n`);
assert.equal(reviewedTable(root, fixtureProfile).kernels.noise.enabled, false, "new mirrors require review");
assert.equal(evaluateProfile(root, {id: "new-profile", kernelSemantics: baseline.kernelSemantics}, changedOptions)
    .table.kernels.noise.enabled, false);
writeFileSync(join(directory, "results.json"), serialize({normalization: "PASS", diff: "PASS", approval: "PASS",
    missingSignatures: "PASS", regeneration: "PASS", manifestExpansion: "PASS", newProfile: "PASS"}));
console.log("kernelmirror: normalization, CP/bootstrap/debug, body/exception, four diff states, missing signatures, regeneration and fail-closed approvals PASS");
