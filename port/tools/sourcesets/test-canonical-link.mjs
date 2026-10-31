#!/usr/bin/env node
import assert from "node:assert/strict";
import {mkdirSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {homedir} from "node:os";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {parseIssues} from "./canonical-link.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const outIndex = process.argv.indexOf("--out");
assert.ok(outIndex > 0 && process.argv[outIndex + 1], "provide --out DIR for retained test evidence");
mkdirSync(process.argv[outIndex + 1], {recursive: true});
const work = mkdtempSync(join(resolve(process.argv[outIndex + 1]), "link-test-"));
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const javaTool = name => home ? join(home, "bin", name + (process.platform === "win32" ? ".exe" : "")) : name;
const repository = process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository");
const asm = join(repository, "org/ow2/asm/asm/9.8/asm-9.8.jar");
function run(name, args, label) {
    const result = spawnSync(javaTool(name), args, {encoding: "utf8"});
    writeFileSync(join(work, `${label}.log`), (result.stdout ?? "") + (result.stderr ?? ""));
    assert.equal(result.status, 0, `${label}: ${result.error ?? result.stderr}`);
}
function compile(label, sources, classpath) {
    const directory = join(work, label);
    mkdirSync(directory);
    const paths = Object.entries(sources).map(([path, text]) => {
        const file = join(directory, "src", path);
        mkdirSync(dirname(file), {recursive: true});
        writeFileSync(file, text + "\n");
        return file;
    });
    const classes = join(directory, "classes");
    mkdirSync(classes);
    run("javac", ["-J-Xmx3g", "--release", "21", "-d", classes, ...(classpath ? ["-cp", classpath] : []), ...paths], label);
    return classes;
}
const original = compile("original", {
    "oldpkg/Moved.java": "package oldpkg; public class Moved {}",
    "api/Api.java": "package api; public class Api { public static int oldField; public static void oldMethod() {} }",
});
const candidate = compile("candidate", {
    "newpkg/Moved.java": "package newpkg; public class Moved {}",
    "api/Api.java": "package api; public class Api { public static int newField; public static void newMethod() {} }",
});
const broken = compile("broken", {"Use.java": "public class Use { Object value = new oldpkg.Moved(); int call() { api.Api.oldMethod(); return api.Api.oldField; } }"}, original);
const fixed = compile("fixed", {"Use.java": "public class Use { Object value = new newpkg.Moved(); int call() { api.Api.newMethod(); return api.Api.newField; } }"}, candidate);
function scan(label, subject) {
    const issues = join(work, `${label}.tsv`);
    const hints = join(work, `${label}-suggestions.tsv`);
    run("java", ["-Xmx3g", "-cp", asm, join(root, "port/scripts/link-gap/LinkGapScanner.java"),
        "--subject", subject, "--classpath", candidate, "--out", issues, "--suggestions", hints], label);
    return parseIssues(readFileSync(issues, "utf8"), readFileSync(hints, "utf8"));
}
const issues = scan("broken-scan", broken);
assert.equal(issues.length, 3);
assert.ok(issues.some(issue => issue.symbol.startsWith("missing-class ") && issue.suggestion.includes("newpkg.Moved")));
assert.ok(issues.some(issue => issue.symbol.startsWith("missing-method ") && issue.suggestion.includes("newMethod")));
assert.ok(issues.every(issue => !issue.suggestion.includes("<init>")));
assert.ok(issues.some(issue => issue.symbol.startsWith("missing-field ") && issue.suggestion.includes("newField")));
assert.deepEqual(scan("fixed-scan", fixed), []);
console.log(`canonical-link self-test passed: 3 injected symbol gaps, 0 repaired gaps; ${work}`);
