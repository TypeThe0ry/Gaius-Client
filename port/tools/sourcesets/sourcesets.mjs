#!/usr/bin/env node
// sourcesets: the vanilla-facing source sets of port/mc/shared.
//
// A profile no longer has a source directory of its own. port/mc/shared/sourcesets.json names the
// source sets and the profile fields that select each of them (render seam generation, patch set),
// so a new profile JSON whose fields are already known compiles without a source change:
//
//   canonical      every profile; written against the canonical render seam generation
//                  (sourcesets.json "canonicalRenderSeam", the oldest generation of the support
//                  window that is not the LTS)
//   <generation>   the profiles of a newer render seam generation: files whose difference is
//                  more than a type relocation, excludes, and relocations.tsv (type relocations applied
//                  to every other file of the profile)
//   <compat>       the LTS compat set (patch set legacy-12111): its broken-link files and excludes
//
// Each set may hold two kinds of sources, laid over the shared root of that kind:
//
//   src     port/src/main/java                     (the TeaVM sources, staged by generate-pom.sh)
//   client  port/overrides/client/src/main/java    (client overrides, compiled by build-overlays.sh)
//
// Resolution for a profile, kind and TeaVM target: the shared root; then each selected set in
// manifest order, first dropping the relative paths of its <kind>/excludes.txt (each must name a
// file resolved so far) and then adding its <kind>/java files (a file replaces the one with the same
// relative path); then the target root (port/src/target-<t>/java,
// port/overrides/client/src/target-<t>/java). Finally the relocations of every selected set are
// applied to the code (never comments or string literals) of each file that set did not provide.
//
//   node port/tools/sourcesets/sourcesets.mjs list  --profile <id|path> --kind src|client [--target js|wasmgc]
//   node port/tools/sourcesets/sourcesets.mjs stage --profile <id|path> --kind src|client [--target t] --out DIR
//   node port/tools/sourcesets/sourcesets.mjs path  --profile <id|path> --kind src|client [--target t] <relative>
//   node port/tools/sourcesets/sourcesets.mjs check       every profile, kind and target resolves; no
//                                                         stale exclude or relocation line
//   node port/tools/sourcesets/sourcesets.mjs self-test
//   ... [--root DIR]  repository root (default: this checkout)
//
// A profile is a port/versions/*.json id or a path to a profile JSON (GAIUS_VERSION_PROFILE).

import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync,
    writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, isAbsolute, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";

export const MANIFEST = "port/mc/shared/sourcesets.json";
export const KINDS = Object.freeze({
    src: Object.freeze({base: "port/src/main/java", target: (t) => `port/src/target-${t}/java`}),
    client: Object.freeze({
        base: "port/overrides/client/src/main/java",
        target: (t) => `port/overrides/client/src/target-${t}/java`,
    }),
});
export const TARGETS = Object.freeze(["js", "wasmgc"]);
const SET_NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/u;
const JAVA_NAME = /^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+$/u;
const DEFAULT_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

export class SourceSetError extends Error {}

function fail(message) {
    throw new SourceSetError(message);
}

// ---------------------------------------------------------------------------------------------
// Profiles and the manifest

/** Every port/versions/*.json profile, in file name order. */
export function loadProfiles(root = DEFAULT_ROOT) {
    const directory = join(root, "port/versions");
    return readdirSync(directory).filter((name) => name.endsWith(".json")).sort()
        .map((name) => ({...JSON.parse(readFileSync(join(directory, name), "utf8")),
            __path: join(directory, name)}));
}

/** The profile for an id of port/versions/*.json or a path to a profile JSON file. */
export function loadProfile(reference, root = DEFAULT_ROOT) {
    if (typeof reference === "object" && reference !== null) return reference;
    const text = String(reference ?? "");
    if (!text) fail("a profile id or profile JSON path is required");
    if (text.endsWith(".json") || text.includes("/") || text.includes("\\")) {
        const path = isAbsolute(text) ? text : resolve(text);
        if (!existsSync(path)) fail(`no profile JSON at ${path}`);
        return {...JSON.parse(readFileSync(path, "utf8")), __path: path};
    }
    const found = loadProfiles(root).find((profile) => profile.id === text);
    if (!found) fail(`no port/versions profile has id ${text}`);
    return found;
}

function field(profile, key) {
    let value = profile;
    for (const part of key.split(".")) value = value?.[part];
    return value;
}

/** port/mc/shared/sourcesets.json, validated. */
export function loadManifest(root = DEFAULT_ROOT) {
    const path = join(root, MANIFEST);
    if (!existsSync(path)) fail(`${MANIFEST} is missing`);
    const manifest = JSON.parse(readFileSync(path, "utf8"));
    if (!Array.isArray(manifest.sets) || manifest.sets.length === 0) fail(`${MANIFEST}: sets must be a list`);
    const names = new Set();
    for (const set of manifest.sets) {
        if (typeof set.name !== "string" || !SET_NAME.test(set.name)) {
            fail(`${MANIFEST}: set name ${JSON.stringify(set.name)} is not a lower-case dashed name`);
        }
        if (names.has(set.name)) fail(`${MANIFEST}: set ${set.name} is declared twice`);
        names.add(set.name);
        if (set.selects !== "*") {
            if (set.selects === null || typeof set.selects !== "object" || Array.isArray(set.selects)
                || Object.keys(set.selects).length === 0) {
                fail(`${MANIFEST}: set ${set.name} selects must be "*" or an object of profile fields`);
            }
            for (const [key, values] of Object.entries(set.selects)) {
                if (!Array.isArray(values) || values.length === 0 || values.some((v) => typeof v !== "string")) {
                    fail(`${MANIFEST}: set ${set.name} selects.${key} must be a list of values`);
                }
            }
        }
        if (!existsSync(join(root, "port/mc/shared", set.name))) {
            fail(`${MANIFEST}: set ${set.name} has no directory port/mc/shared/${set.name}`);
        }
    }
    if (!names.has(manifest.canonical)) fail(`${MANIFEST}: canonical names no set`);
    if (manifest.sets.find((set) => set.name === manifest.canonical).selects !== "*") {
        fail(`${MANIFEST}: the canonical set must select every profile ("*")`);
    }
    if (typeof manifest.canonicalRenderSeam !== "string") fail(`${MANIFEST}: canonicalRenderSeam is missing`);
    const bindings = new Set();
    for (const binding of manifest.profileFields ?? []) {
        if (!KINDS[binding.kind] || typeof binding.path !== "string" || !binding.path.endsWith(".java")
            || binding.path.startsWith("/") || binding.path.includes("\\") || binding.path.split("/").includes("..")
            || !/^__GAIUS_[A-Z_]+__$/u.test(binding.token ?? "") || !JAVA_NAME.test(binding.field ?? "")) {
            fail(`${MANIFEST}: invalid profileFields binding ${JSON.stringify(binding)}`);
        }
        const key = `${binding.kind}:${binding.path}:${binding.token}`;
        if (bindings.has(key)) fail(`${MANIFEST}: duplicate profileFields binding ${key}`);
        bindings.add(key);
    }
    for (const rule of manifest.compileExcludes ?? []) {
        if ((rule.selects !== "*" && (!rule.selects || Object.entries(rule.selects).some(([key, values]) =>
            !/^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$/u.test(key) || !Array.isArray(values) || !values.length)))
            || (rule.targets && (!Array.isArray(rule.targets) || rule.targets.some(target => !TARGETS.includes(target))))
            || !Array.isArray(rule.paths) || !rule.paths.length || rule.paths.some(path =>
                typeof path !== "string" || !path.endsWith(".java") || path.startsWith("/")
                || path.includes("\\") || path.split("/").includes(".."))) {
            fail(`${MANIFEST}: invalid compileExcludes rule ${JSON.stringify(rule)}`);
        }
    }
    return manifest;
}

/** Whether a set's selector takes the profile. */
export function selects(set, profile) {
    if (set.selects === "*") return true;
    return Object.entries(set.selects).every(([key, values]) => values.includes(field(profile, key)));
}

/** The sets of the manifest a profile selects, in manifest order. */
export function selectedSets(manifest, profile) {
    return manifest.sets.filter((set) => selects(set, profile));
}

export function compilerExcludes(profileRef, target = "js", root = DEFAULT_ROOT) {
    if (!TARGETS.includes(target)) fail(`unknown target ${target}`);
    const profile = typeof profileRef === "string" ? loadProfile(profileRef, root) : profileRef;
    return [...new Set((loadManifest(root).compileExcludes ?? []).filter(rule => selects(rule, profile)
        && (!rule.targets || rule.targets.includes(target))).flatMap(rule => rule.paths))];
}

// ---------------------------------------------------------------------------------------------
// Files

function listFiles(directory) {
    const out = new Map();
    if (!existsSync(directory)) return out;
    const walk = (dir) => {
        for (const entry of readdirSync(dir, {withFileTypes: true})) {
            const full = join(dir, entry.name);
            if (entry.isDirectory()) walk(full);
            else if (entry.isFile()) out.set(relative(directory, full).replaceAll("\\", "/"), full);
        }
    };
    walk(directory);
    return out;
}

/** The entries of an excludes.txt: '#' starts a comment, blank lines are ignored. */
export function readExcludes(path) {
    if (!existsSync(path)) return [];
    const entries = [];
    readFileSync(path, "utf8").split(/\r?\n/u).forEach((line, index) => {
        const entry = line.replace(/#.*$/u, "").trim();
        if (!entry) return;
        if (entry.startsWith("/") || entry.split("/").includes("..")) {
            fail(`${path}:${index + 1}: ${entry} must be a relative path`);
        }
        entries.push({entry, line: index + 1});
    });
    return entries;
}

/**
 * The relocations of a set: "<from>\t<to>[\t<reason>]" lines naming a vanilla type by its
 * canonical and its new fully qualified name. Package moves and simple-name changes are
 * relocations; changed member contracts belong in generation ops.
 */
export function readRelocations(path) {
    if (!existsSync(path)) return [];
    const entries = [];
    const seen = new Set();
    readFileSync(path, "utf8").split(/\r?\n/u).forEach((line, index) => {
        const text = line.replace(/#.*$/u, "").trim();
        if (!text) return;
        const [from, to] = text.split("\t").map((part) => part.trim());
        const where = `${path}:${index + 1}`;
        if (!JAVA_NAME.test(from ?? "") || !JAVA_NAME.test(to ?? "")) {
            fail(`${where}: expected "<from type>\\t<to type>[\\t<reason>]" with dotted type names`);
        }
        if (from === to) fail(`${where}: ${from} relocates to itself`);
        if (seen.has(from)) fail(`${where}: ${from} is relocated twice`);
        seen.add(from);
        entries.push({from, to, where});
    });
    return entries;
}

/**
 * The files a profile compiles for one kind and target: relative path -> {path, origin,
 * relocations}. origin is "shared", a set name or "target"; relocations are the relocation
 * entries that apply to the file.
 */
export function resolveSources(profileRef, kind, target = "js", root = DEFAULT_ROOT) {
    const spec = KINDS[kind] ?? fail(`unknown kind ${kind} (expected ${Object.keys(KINDS).join(" or ")})`);
    if (!TARGETS.includes(target)) fail(`unknown target ${target} (expected ${TARGETS.join(" or ")})`);
    const profile = loadProfile(profileRef, root);
    const manifest = loadManifest(root);
    const sets = selectedSets(manifest, profile);
    const files = new Map();
    for (const [rel, path] of listFiles(join(root, spec.base))) files.set(rel, {path, origin: "shared"});
    for (const set of sets) {
        const setRoot = join(root, "port/mc/shared", set.name, kind);
        for (const {entry, line} of readExcludes(join(setRoot, "excludes.txt"))) {
            if (!files.has(entry)) {
                fail(`port/mc/shared/${set.name}/${kind}/excludes.txt:${line}: stale entry ${entry} `
                    + `(no such file before set ${set.name} for profile ${profile.id})`);
            }
            files.delete(entry);
        }
        for (const [rel, path] of listFiles(join(setRoot, "java"))) files.set(rel, {path, origin: set.name});
    }
    for (const [rel, path] of listFiles(join(root, spec.target(target)))) files.set(rel, {path, origin: "target"});
    const relocationSets = sets.map((set) => ({
        set: set.name,
        entries: readRelocations(join(root, "port/mc/shared", set.name, "relocations.tsv")),
    })).filter((item) => item.entries.length > 0);
    for (const [rel, info] of files) {
        info.relocations = relocationSets.filter((item) => item.set !== info.origin)
            .flatMap((item) => item.entries);
        info.profileFields = (manifest.profileFields ?? []).filter((binding) => binding.kind === kind
            && binding.path === rel).map((binding) => {
            const value = field(profile, binding.field);
            if (!Number.isSafeInteger(value) || value < 0 || value > 255) {
                fail(`profile ${profile.id}: ${binding.field} must be an integer 0..255`);
            }
            return {...binding, value};
        });
    }
    return {profile, sets: sets.map((set) => set.name), files: new Map([...files].sort(([a], [b]) => a < b ? -1 : 1))};
}

// ---------------------------------------------------------------------------------------------
// Relocation

/**
 * Splits Java source into code and non-code runs: comments, string and character literals and
 * text blocks are non-code. Returns [{code: boolean, text}] covering the whole text.
 */
export function javaRuns(text) {
    const runs = [];
    let start = 0;
    let i = 0;
    const n = text.length;
    const push = (end, code) => {
        if (end > start) runs.push({code, text: text.slice(start, end)});
        start = end;
    };
    while (i < n) {
        const c = text[i];
        if (c === "/" && text[i + 1] === "/") {
            push(i, true);
            const end = text.indexOf("\n", i);
            i = end < 0 ? n : end;
            push(i, false);
        } else if (c === "/" && text[i + 1] === "*") {
            push(i, true);
            const end = text.indexOf("*/", i + 2);
            i = end < 0 ? n : end + 2;
            push(i, false);
        } else if (text.startsWith('"""', i)) {
            push(i, true);
            let j = i + 3;
            while (j < n && !text.startsWith('"""', j)) j += text[j] === "\\" ? 2 : 1;
            i = Math.min(n, j + 3);
            push(i, false);
        } else if (c === '"' || c === "'") {
            push(i, true);
            let j = i + 1;
            while (j < n && text[j] !== c && text[j] !== "\n") j += text[j] === "\\" ? 2 : 1;
            i = Math.min(n, j + 1);
            push(i, false);
        } else {
            i++;
        }
    }
    push(n, true);
    return runs;
}

function escapeRegExp(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

/** Applies relocation entries to the code of a Java source; returns {text, counts: Map(from -> n)}. */
export function relocateText(text, entries) {
    const counts = new Map();
    if (entries.length === 0) return {text, counts};
    const patterns = entries.map((entry) => ({
        entry,
        pattern: new RegExp(`(?<![\\w$.])${escapeRegExp(entry.from)}(?![\\w$])`, "gu"),
    }));
    const codeOnly = javaRuns(text).map((run) => run.code ? run.text : " ".repeat(run.text.length)).join("");
    const renamedImports = entries.filter((entry) => entry.from.split(".").pop() !== entry.to.split(".").pop()
        && new RegExp(`\\bimport\\s+${escapeRegExp(entry.from)}\\s*;`, "u").test(codeOnly)).map((entry) => ({
        entry,
        simple: true,
        pattern: new RegExp(`(?<![\\w$.])${escapeRegExp(entry.from.split(".").pop())}(?![\\w$])`, "gu"),
    }));
    const out = javaRuns(text).map((run) => {
        if (!run.code) return run.text;
        let code = run.text;
        for (const {entry, pattern, simple} of [...patterns, ...renamedImports]) {
            code = code.replace(pattern, () => {
                counts.set(entry.from, (counts.get(entry.from) ?? 0) + 1);
                return simple ? entry.to.split(".").pop() : entry.to;
            });
        }
        return code;
    });
    return {text: out.join(""), counts};
}

/** The text a resolved file compiles as, with type relocations and profile fields applied. */
export function compiledText(info) {
    const text = readFileSync(info.path, "utf8");
    if (!info.path.endsWith(".java")) return text;
    let out = relocateText(text, info.relocations ?? []).text;
    for (const binding of info.profileFields ?? []) {
        let count = 0;
        const pattern = new RegExp(`\\b${binding.token}\\b`, "gu");
        out = javaRuns(out).map((run) => !run.code ? run.text : run.text.replace(pattern, () => {
            count++;
            return String(binding.value);
        })).join("");
        if (count !== 1) fail(`${info.path}: profile field token ${binding.token} occurs ${count} times, expected one`);
    }
    return out;
}

/**
 * Writes the resolved tree of a profile, kind and target under out (created; must not exist or be
 * empty). Files without relocation or profile binding are copied byte for byte. Returns the resolution with per-entry
 * relocation counts.
 */
export function stageSources(profileRef, kind, target, out, root = DEFAULT_ROOT) {
    const resolution = resolveSources(profileRef, kind, target, root);
    const counts = new Map();
    mkdirSync(out, {recursive: true});
    for (const [rel, info] of resolution.files) {
        const destination = join(out, rel);
        mkdirSync(dirname(destination), {recursive: true});
        if ((info.relocations.length || info.profileFields.length) && rel.endsWith(".java")) {
            const original = readFileSync(info.path, "utf8");
            const relocated = relocateText(original, info.relocations);
            for (const [from, n] of relocated.counts) counts.set(from, (counts.get(from) ?? 0) + n);
            const stagedText = compiledText(info);
            if (stagedText !== original) {
                writeFileSync(destination, stagedText);
                continue;
            }
        }
        copyFileSync(info.path, destination);
    }
    return {...resolution, counts};
}

/** The file a profile compiles for a relative path, or null: {path, text, origin, relocated}. */
export function sourceFile(profileRef, kind, rel, {target = "js", root = DEFAULT_ROOT} = {}) {
    const info = resolveSources(profileRef, kind, target, root).files.get(rel);
    if (!info) return null;
    const text = compiledText(info);
    return {path: info.path, origin: info.origin, text, relocated: text !== readFileSync(info.path, "utf8")};
}

// ---------------------------------------------------------------------------------------------
// check

/** Validates the manifest against every profile; returns a list of problems (empty when clean). */
export function check(root = DEFAULT_ROOT) {
    const problems = [];
    let manifest;
    try {
        manifest = loadManifest(root);
    } catch (error) {
        if (error instanceof SourceSetError) return [error.message];
        throw error;
    }
    const profiles = loadProfiles(root);
    for (const set of manifest.sets) {
        if (set.selects !== "*") {
            for (const key of Object.keys(set.selects)) {
                if (!profiles.some((profile) => field(profile, key) !== undefined)) {
                    problems.push(`${MANIFEST}: set ${set.name} selects on ${key}, which no profile has`);
                }
            }
            if (!profiles.some((profile) => selects(set, profile))) {
                problems.push(`${MANIFEST}: set ${set.name} selects no profile`);
            }
        }
        const directory = join(root, "port/mc/shared", set.name);
        for (const name of readdirSync(directory)) {
            if (!["src", "client", "relocations.tsv", "README.md"].includes(name)) {
                problems.push(`port/mc/shared/${set.name}/${name}: a set holds src/, client/, relocations.tsv and README.md only`);
            }
        }
        for (const kind of Object.keys(KINDS)) {
            const kindDir = join(directory, kind);
            if (!existsSync(kindDir)) continue;
            for (const name of readdirSync(kindDir)) {
                if (!["java", "excludes.txt"].includes(name)) {
                    problems.push(`port/mc/shared/${set.name}/${kind}/${name}: expected java/ and excludes.txt only`);
                }
            }
        }
    }
    for (const profile of profiles) {
        const sets = selectedSets(manifest, profile);
        if (profile.renderSeam === manifest.canonicalRenderSeam
            && sets.some((set) => set.name !== manifest.canonical)) {
            problems.push(`profile ${profile.id} has the canonical render seam ${manifest.canonicalRenderSeam} `
                + `but selects ${sets.map((set) => set.name).join(", ")}: the canonical set must compile as is`);
        }
    }
    // Every relocation line must rewrite something for some profile that selects its set.
    const used = new Map();
    const usedBindings = new Set();
    for (const profile of profiles) {
        for (const kind of Object.keys(KINDS)) {
            for (const target of TARGETS) {
                let resolution;
                try {
                    resolution = resolveSources(profile, kind, target, root);
                } catch (error) {
                    if (!(error instanceof SourceSetError)) throw error;
                    problems.push(`${profile.id} ${kind} ${target}: ${error.message}`);
                    continue;
                }
                for (const info of resolution.files.values()) {
                    for (const binding of info.profileFields) {
                        usedBindings.add(`${binding.kind}:${binding.path}:${binding.token}`);
                    }
                    try {
                        compiledText(info);
                    } catch (error) {
                        if (!(error instanceof SourceSetError)) throw error;
                        problems.push(error.message);
                    }
                    if (!info.relocations.length || !info.path.endsWith(".java")) continue;
                    const {counts} = relocateText(readFileSync(info.path, "utf8"), info.relocations);
                    for (const [from, n] of counts) used.set(from, (used.get(from) ?? 0) + n);
                }
            }
        }
    }
    for (const binding of manifest.profileFields ?? []) {
        const key = `${binding.kind}:${binding.path}:${binding.token}`;
        if (!usedBindings.has(key)) problems.push(`${MANIFEST}: stale profileFields binding ${key}`);
    }
    for (const set of manifest.sets) {
        const path = join(root, "port/mc/shared", set.name, "relocations.tsv");
        let entries = [];
        try {
            entries = readRelocations(path);
        } catch (error) {
            if (!(error instanceof SourceSetError)) throw error;
            problems.push(error.message);
        }
        for (const entry of entries) {
            if (!used.has(entry.from)) {
                problems.push(`${relative(root, entry.where).replaceAll("\\", "/")}: stale relocation ${entry.from} `
                    + "(no code of a selecting profile names it)");
            }
        }
    }
    return problems;
}

// ---------------------------------------------------------------------------------------------
// self-test

function selfTest() {
    const scratch = mkdtempSync(join(tmpdir(), "gaius-sourcesets-"));
    const failures = [];
    const expect = (label, actual, wanted) => {
        const a = JSON.stringify(actual);
        const w = JSON.stringify(wanted);
        if (a !== w) failures.push(`${label}: got ${a}, expected ${w}`);
    };
    const write = (path, text) => {
        mkdirSync(dirname(join(scratch, path)), {recursive: true});
        writeFileSync(join(scratch, path), text);
    };
    try {
        write("port/versions/a.json", JSON.stringify({id: "alpha", renderSeam: "gen-1", patchSet: "modern"}));
        write("port/versions/b.json", JSON.stringify({id: "beta", renderSeam: "gen-2", patchSet: "modern"}));
        write("port/versions/c.json", JSON.stringify({id: "gamma", renderSeam: "none", patchSet: "lts"}));
        write(MANIFEST, JSON.stringify({canonical: "canonical", canonicalRenderSeam: "gen-1", sets: [
            {name: "canonical", selects: "*"},
            {name: "gen-2", selects: {renderSeam: ["gen-2"]}},
            {name: "lts", selects: {patchSet: ["lts"]}},
        ]}));
        write("port/src/main/java/p/Shared.java",
            "import a.old.Payload;\n// a.old.Payload in a comment\nclass Shared { String s = \"a.old.Payload\"; "
            + "a.old.Payload p; a.old.PayloadX q; x.a.old.Payload r; }\n");
        write("port/src/main/java/p/Gone.java", "class Gone {}\n");
        write("port/mc/shared/canonical/src/java/p/Kernel.java", "class Kernel { a.old.Payload p; }\n");
        write("port/mc/shared/gen-2/src/java/p/Kernel.java", "class Kernel { a.old.Payload own; }\n");
        write("port/mc/shared/gen-2/src/excludes.txt", "# comment\np/Gone.java\n");
        write("port/mc/shared/gen-2/relocations.tsv", "a.old.Payload\ta.fresh.Payload\tmoved\n");
        write("port/mc/shared/lts/src/excludes.txt", "p/Kernel.java\n");
        write("port/src/target-wasmgc/java/p/Gone.java", "class Gone { int wasm; }\n");
        const root = scratch;
        const list = (id, target = "js") => [...resolveSources(id, "src", target, root).files]
            .map(([rel, info]) => `${rel}:${info.origin}`);
        expect("alpha js", list("alpha"), ["p/Gone.java:shared", "p/Kernel.java:canonical", "p/Shared.java:shared"]);
        expect("beta js", list("beta"), ["p/Kernel.java:gen-2", "p/Shared.java:shared"]);
        expect("beta wasmgc", list("beta", "wasmgc"),
            ["p/Gone.java:target", "p/Kernel.java:gen-2", "p/Shared.java:shared"]);
        expect("gamma js", list("gamma"), ["p/Gone.java:shared", "p/Shared.java:shared"]);
        const shared = sourceFile("beta", "src", "p/Shared.java", {root});
        expect("relocated code only", shared.text,
            "import a.fresh.Payload;\n// a.old.Payload in a comment\nclass Shared { String s = \"a.old.Payload\"; "
            + "a.fresh.Payload p; a.old.PayloadX q; x.a.old.Payload r; }\n");
        expect("set's own file is not relocated", sourceFile("beta", "src", "p/Kernel.java", {root}).relocated, false);
        expect("canonical profile unrelocated", sourceFile("alpha", "src", "p/Shared.java", {root}).relocated, false);
        const out = join(scratch, "staged");
        const staged = stageSources("beta", "src", "wasmgc", out, root);
        expect("stage counts", [...staged.counts], [["a.old.Payload", 2]]);
        expect("staged files", [...listFiles(out).keys()].sort(), ["p/Gone.java", "p/Kernel.java", "p/Shared.java"]);
        expect("clean check", check(root), []);
        write("port/mc/shared/lts/src/excludes.txt", "p/Missing.java\n");
        expect("stale exclude", check(root).some((p) => p.includes("stale entry p/Missing.java")), true);
        write("port/mc/shared/lts/src/excludes.txt", "p/Kernel.java\n");
        write("port/mc/shared/gen-2/relocations.tsv", "a.old.Payload\ta.fresh.Payload\nb.Unused\tc.Unused\n");
        expect("stale relocation", check(root).some((p) => p.includes("stale relocation b.Unused")), true);
        write("port/mc/shared/gen-2/relocations.tsv", "a.old.Payload\ta.fresh.Renamed\n");
        expect("class rename accepted", check(root), []);
        const renamed = relocateText("import a.old.Payload; class Demo { Payload.Inner value; "
            + "a.old.Payload other; String text = \"Payload\"; /* Payload */ }",
            [{from: "a.old.Payload", to: "a.fresh.Renamed"}]).text;
        expect("rename imported and qualified types, preserve literals", renamed,
            "import a.fresh.Renamed; class Demo { Renamed.Inner value; a.fresh.Renamed other; "
            + "String text = \"Payload\"; /* Payload */ }");
        const profileManifest = JSON.parse(readFileSync(join(root, MANIFEST), "utf8"));
        profileManifest.profileFields = [{kind: "src", path: "p/Shared.java", token: "__GAIUS_CODE__",
            field: "kernelSemantics.code"}];
        profileManifest.compileExcludes = [
            {selects: {patchSet: ["lts"]}, paths: ["p/Gone.java"]},
            {selects: "*", targets: ["wasmgc"], paths: ["p/Shared.java"]},
        ];
        write(MANIFEST, JSON.stringify(profileManifest));
        expect("compiler excludes selected by field", compilerExcludes("gamma", "js", root), ["p/Gone.java"]);
        expect("compiler excludes selected by target", compilerExcludes("alpha", "wasmgc", root), ["p/Shared.java"]);
        expect("unselected compiler excludes", compilerExcludes("alpha", "js", root), []);
        write("port/versions/a.json", JSON.stringify({id: "alpha", renderSeam: "gen-1", patchSet: "modern",
            kernelSemantics: {code: 7}}));
        write("port/src/main/java/p/Shared.java", "class Shared { int code = __GAIUS_CODE__; }\n");
        expect("profile field substitution", sourceFile("alpha", "src", "p/Shared.java", {root}).text,
            "class Shared { int code = 7; }\n");
        write("port/src/main/java/p/Shared.java", "class Shared { int code = 0; }\n");
        expect("stale profile token rejected", check(root).some((problem) => problem.includes("occurs 0 times")), true);
        profileManifest.profileFields[0].path = "p/Missing.java";
        write(MANIFEST, JSON.stringify(profileManifest));
        expect("stale profile binding rejected", check(root).some((problem) => problem.includes("stale profileFields")), true);
    } finally {
        rmSync(scratch, {recursive: true, force: true});
    }
    for (const failure of failures) console.error(`sourcesets self-test FAILED: ${failure}`);
    if (!failures.length) console.log("sourcesets self-test passed");
    return failures.length ? 1 : 0;
}

// ---------------------------------------------------------------------------------------------
// CLI

function main(argv) {
    const [command, ...rest] = argv;
    const options = {target: "js", positional: []};
    for (let i = 0; i < rest.length; i++) {
        const arg = rest[i];
        const value = () => rest[++i] ?? fail(`${arg} needs a value`);
        if (arg === "--profile") options.profile = value();
        else if (arg === "--kind") options.kind = value();
        else if (arg === "--target") options.target = value();
        else if (arg === "--out") options.out = value();
        else if (arg === "--root") options.root = resolve(value());
        else if (arg.startsWith("--")) fail(`unknown option ${arg}`);
        else options.positional.push(arg);
    }
    const root = options.root ?? DEFAULT_ROOT;
    switch (command) {
        case "list": {
            const {sets, files} = resolveSources(options.profile, options.kind, options.target, root);
            console.error(`sourcesets: ${options.kind} ${options.target}: sets ${sets.join(", ")}`);
            for (const [rel, info] of files) {
                const moved = info.relocations.length ? `\trelocated:${[...new Set(info.relocations.map((e) => e.from))].length}` : "";
                console.log(`${rel}\t${info.origin}\t${relative(root, info.path).replaceAll("\\", "/")}${moved}`);
            }
            return 0;
        }
        case "stage": {
            if (!options.out) fail("stage needs --out DIR");
            if (existsSync(options.out) && (!statSync(options.out).isDirectory() || readdirSync(options.out).length)) {
                fail(`stage --out ${options.out} must not exist or be empty`);
            }
            const staged = stageSources(options.profile, options.kind, options.target, options.out, root);
            const moved = [...staged.counts.values()].reduce((sum, n) => sum + n, 0);
            console.error(`sourcesets: staged ${staged.files.size} ${options.kind} files of profile `
                + `${staged.profile.id} (${options.target}; sets ${staged.sets.join(", ")}; ${moved} relocated names)`);
            return 0;
        }
        case "path": {
            if (options.positional.length !== 1) fail("path needs one relative path");
            const info = resolveSources(options.profile, options.kind, options.target, root).files.get(options.positional[0]);
            if (!info) fail(`${options.positional[0]} is not compiled for this profile`);
            console.log(info.path);
            return 0;
        }
        case "compiler-excludes":
            for (const path of compilerExcludes(options.profile, options.target, root)) console.log(path);
            return 0;
        case "check": {
            const problems = check(root);
            for (const problem of problems) console.error(`sourcesets: ${problem}`);
            if (!problems.length) {
                const manifest = loadManifest(root);
                console.log(`sourcesets: ${manifest.sets.length} sets, ${loadProfiles(root).length} profiles, all resolve`);
            }
            return problems.length ? 1 : 0;
        }
        case "self-test":
            return selfTest();
        default:
            fail("usage: sourcesets.mjs list|stage|path|check|self-test [--profile P] [--kind src|client] "
                + "[--target js|wasmgc] [--out DIR] [--root DIR]");
    }
    return 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        process.exitCode = main(process.argv.slice(2));
    } catch (error) {
        if (!(error instanceof SourceSetError)) throw error;
        console.error(`sourcesets: ${error.message}`);
        process.exitCode = 2;
    }
}
