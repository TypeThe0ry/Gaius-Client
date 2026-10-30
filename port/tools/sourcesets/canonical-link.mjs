#!/usr/bin/env node
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {createHash} from "node:crypto";
import {spawnSync} from "node:child_process";
import {homedir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {compilerExcludes, loadManifest, loadProfiles, stageSources, TARGETS} from "./sourcesets.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

function javaTool(name) {
    const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
    return home ? join(home, "bin", name + (process.platform === "win32" ? ".exe" : "")) : name;
}

function run(name, args, log) {
    const result = spawnSync(javaTool(name), args, {encoding: "utf8", maxBuffer: 32 * 1024 * 1024});
    writeFileSync(log, (result.stdout ?? "") + (result.stderr ?? "") + (result.error?.message ?? ""));
    if (result.error) throw result.error;
    return result.status;
}

function files(directory, suffix) {
    const result = [];
    for (const entry of readdirSync(directory, {withFileTypes: true})) {
        const path = join(directory, entry.name);
        if (entry.isDirectory()) result.push(...files(path, suffix));
        else if (entry.isFile() && entry.name.endsWith(suffix)) result.push(path);
    }
    return result.sort();
}

function nativePath(path) {
    return process.platform === "win32" ? path.replace(/^\/([a-z])\//iu, (_, drive) => `${drive.toUpperCase()}:/`) : path;
}

function digest(path) {
    return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function quote(path) {
    return JSON.stringify(path.replaceAll("\\", "/"));
}

export function parseIssues(text, suggestions) {
    const hints = new Map(suggestions.trim().split(/\r?\n/u).filter(Boolean).map(line => line.split("\t")));
    return text.trim().split(/\r?\n/u).filter(Boolean).map(line => {
        const [symbol, count, ...referrers] = line.split("\t");
        return {symbol, count: Number(count), referrers, suggestion: hints.get(symbol)
            ?? "inspect the selected ABI and use relocation or VersionOps"};
    });
}

function checkProfile(profile, target, overlay, out, root) {
    mkdirSync(out, {recursive: true});
    const work = mkdtempSync(join(out, "attempt-"));
    const staged = stageSources(profile, "src", target, join(work, "sources"), root);
    const manifest = loadManifest(root);
    const named = join(root, "port/work", profile.id, "client-named.jar");
    const client = join(overlay, `client-named-${profile.id}-gaius.jar`);
    const config = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8"));
    const repository = process.env.GAIUS_MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
    const teavm = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-core", "teavm-platform"]
        .map(artifact => join(repository, "org/teavm", artifact, config.teaVMVersion,
            `${artifact}-${config.teaVMVersion}.jar`));
    const rawLibraries = readFileSync(join(root, "port/work", profile.id, "classpath.txt"), "utf8").trim()
        .split(/;|:(?=\/|[A-Za-z]:\/)/u).filter(Boolean).map(nativePath);
    const overlaid = files(join(overlay, "libraries"), ".jar");
    const classpath = [...new Set([client, join(overlay, `teavm-classlib-${config.teaVMVersion}-gaius.jar`),
        ...overlaid, ...rawLibraries, ...teavm])];
    const asm = join(repository, "org/ow2/asm/asm/9.8/asm-9.8.jar");
    for (const path of [named, asm, ...classpath]) {
        if (!existsSync(path)) throw new Error(`missing local input ${path}; no downloads are performed`);
    }
    const classes = join(work, "classes");
    mkdirSync(classes);
    const excluded = new Set(compilerExcludes(profile, target, root));
    const sources = [...staged.files.keys()].filter(path => path.endsWith(".java") && !excluded.has(path))
        .map(path => join(work, "sources", path));
    const argfile = join(work, "compile.args");
    writeFileSync(argfile, ["--release", String(profile.javaVersion), "-encoding", "UTF-8", "-proc:none", "-classpath",
        quote(classpath.join(delimiter)), "-d", quote(classes), ...sources.map(quote)].join("\n") + "\n");
    const compileExit = run("javac", ["-J-Xmx3g", "-J-Duser.language=en", "-J-Duser.country=US", `@${argfile}`], join(work, "compile.log"));
    const report = {format: "gaius-canonical-link/1", profile: profile.id, target, sets: staged.sets,
        vanilla: {path: named, sha256: digest(named)}, overlay: {path: client, sha256: digest(client)},
        compiledSources: sources.length, excludedSources: [...excluded], compileExit, issues: [], work};
    if (compileExit === 0) {
        const subjects = join(work, "subjects");
        mkdirSync(subjects);
        const canonicalTypes = new Set([...staged.files].filter(([path, info]) => path.endsWith(".java")
            && !excluded.has(path) && (info.origin === manifest.canonical
            || /(?:VersionOps|MeshOps)\.java$/u.test(info.path))).map(([path]) => path.slice(0, -5)));
        const clientSources = stageSources(profile, "client", target, join(work, "client-sources"), root);
        const clientTypes = new Set([...clientSources.files].filter(([, info]) => info.origin === manifest.canonical)
            .map(([path]) => path.slice(0, -5)));
        for (const [directory, types] of [[classes, canonicalTypes], [join(overlay, "client-override-classes"), clientTypes]]) {
            for (const path of files(directory, ".class")) {
                const rel = path.slice(directory.length + 1).replaceAll("\\", "/");
                if (!types.has(rel.replace(/(?:\$.*)?\.class$/u, ""))) continue;
                const destination = join(subjects, rel);
                mkdirSync(dirname(destination), {recursive: true});
                copyFileSync(path, destination);
            }
        }
        report.canonicalClasses = files(subjects, ".class").length;
        if (!report.canonicalClasses) throw new Error(`no canonical classes selected in ${subjects}`);
        const issues = join(work, "issues.tsv");
        const suggestions = join(work, "suggestions.tsv");
        const args = ["-Xmx3g", "-cp", asm, join(ROOT, "port/scripts/link-gap/LinkGapScanner.java"),
            "--subject", subjects, "--classpath", classes, "--out", issues, "--suggestions", suggestions];
        for (const path of classpath) args.push("--classpath", path);
        report.linkExit = run("java", args, join(work, "link.log"));
        if (report.linkExit === 0) {
            report.issues = parseIssues(readFileSync(issues, "utf8"), readFileSync(suggestions, "utf8"));
            report.scanned = readFileSync(join(work, "link.log"), "utf8").trim();
        }
    } else {
        report.suggestion = "read compile.log: use relocations for type moves; use a generation VersionOps for changed contracts";
    }
    writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
    const passed = report.compileExit === 0 && report.linkExit === 0 && report.issues.length === 0;
    console.log(`CANONICAL_LINK ${profile.id} ${target} ${passed ? "PASS" : "FAIL"} `
        + `sources=${sources.length} issues=${report.issues.length} report=${join(out, "report.json")}`);
    for (const issue of report.issues.slice(0, 8)) console.log(`${issue.symbol}: ${issue.suggestion}`);
    return passed;
}

function main(argv) {
    const options = {profiles: [], target: "js", root: ROOT};
    for (let index = 0; index < argv.length; index++) {
        const key = argv[index].slice(2);
        if (!["profile", "target", "root", "out", "overlay-root", "overlay-dir"].includes(key) || !argv[index + 1]) {
            throw new Error("usage: canonical-link.mjs --out DIR --overlay-root DIR [--profile ID] [--target js|wasmgc]");
        }
        const value = argv[++index];
        if (key === "profile") options.profiles.push(value);
        else options[key] = value;
    }
    if (!options.out || (!options["overlay-root"] && !options["overlay-dir"]) || !TARGETS.includes(options.target)) {
        throw new Error("provide --out, --overlay-root (profile-target directories) or --overlay-dir, and a valid target");
    }
    const profiles = loadProfiles(options.root).filter(profile => !options.profiles.length || options.profiles.includes(profile.id));
    if (!profiles.length || options.profiles.some(id => !profiles.some(profile => profile.id === id))
        || (options["overlay-dir"] && profiles.length !== 1)) throw new Error("invalid profile selection");
    let passed = true;
    for (const profile of profiles) {
        const overlay = options["overlay-dir"] ?? join(options["overlay-root"], `${profile.id}-${options.target}`);
        passed = checkProfile(profile, options.target, resolve(overlay), join(resolve(options.out), profile.id, options.target),
            resolve(options.root)) && passed;
    }
    return passed ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        process.exitCode = main(process.argv.slice(2));
    } catch (error) {
        console.error(`canonical-link: ${error.message}`);
        process.exitCode = 2;
    }
}
