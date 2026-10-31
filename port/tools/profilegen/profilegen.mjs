#!/usr/bin/env node
// profilegen: keeps everything that lists the Minecraft version profiles generated from
// port/versions/*.json, so that a profile JSON is the only place that names a version.
//
//   node port/tools/profilegen/profilegen.mjs [--write]   regenerate stale outputs (default)
//   node port/tools/profilegen/profilegen.mjs --check     exit 1 when an output is stale
//   node port/tools/profilegen/profilegen.mjs --regions   print the generated line ranges (JSON)
//   ... [--root DIR]                                       repository root (default: this checkout)
//
// It first validates the profile fields it and the build read: inputSeam, shaderFront,
// renderSeam and kernelSemantics (the Rust kernels compile the same kernelSemantics into their
// semantics table in port/native/crates/gaius-noise/build.rs). Then it renders the storage
// allow-lists: every page, Worker and file system layer accepts exactly the storage identity
// (world version, schema, IndexedDB database, key prefix, OPFS directory) of a known profile.
// Those lists live inside hand-written JavaScript (some of it Java @JSBody text), so profilegen
// owns the row-shaped regions there: it finds each region by its row shape, refuses to touch a
// region holding anything else, and rewrites the rows from the profiles in file name order.
// The rendered text for the current profiles is byte-identical to what the sources held before
// the generator existed, so the TeaVM output does not change.

import {existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync} from "node:fs";
import {loadProfiles as mirrorProfiles, reviewedTable, serialize} from "../kernelmirror/kernelmirror.mjs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const INPUT_SEAMS = ["sdl3", "glfw"];
const SHADER_FRONTS = ["renderpearl", "blaze3d", "blaze3d-lenient"];
const FLOOR_MODES = ["cast-adjust", "math-floor"];
const SYNTHS = ["f64", "f32"];
const DENSITY_GRAPHS = ["router-f64", "sampler-f32"];
const RENDERPEARL_SEAM = /^renderpearl-\d+$/u;
const PROFILE_ID = /^[0-9A-Za-z][0-9A-Za-z.+-]*$/u;

// A row template lists the lines of one profile's row; {placeholders} take profile values.
// first/last select the variants of the first and the last row where a style has them.
const STYLES = {
    // `return (profile==='<id>' && ...) || (...);` with short variable names.
    "return-compact": {
        row: (first, last) => [
            `${first ? "return " : "  "}(profile==='{id}' && world==={world} && schema==={schema}`,
            "  && database==='{databaseName}'",
            "  && prefix==='{prefix}'",
            `  && opfs==='{opfsDirectory}')${last ? ";" : " ||"}`,
        ],
    },
    // `(profileId === '<id>' && worldVersion === ... ) || (...);` with long variable names.
    "or-verbose": {
        row: (first, last) => [
            "(profileId === '{id}' && worldVersion === {world} &&",
            "  storageSchema === {schema} &&",
            "  storageDatabaseName === '{databaseName}' &&",
            "  storagePrefix === '{prefix}' &&",
            `  storageOpfsDirectory === '{opfsDirectory}')${last ? ";" : " ||"}`,
        ],
    },
    // One `if (...) { return <signature>; }` per profile.
    "if-signature": {
        row: () => [
            "if (profile==='{id}' && world==={world} && schema==={schema}",
            "    && database==='{databaseName}'",
            "    && prefix==='{prefix}'",
            "    && opfs==='{opfsDirectory}') {",
            "  return profile+'|'+world+'|'+schema+'|'+database+'|'+prefix+'|'+opfs;",
            "}",
        ],
    },
    // The entries of a frozen object keyed by profile id.
    "object-entries": {
        row: () => [
            "\"{id}\": Object.freeze({",
            "  worldVersion: {world},",
            "  storageSchema: {schema},",
            "  storageDatabaseName: \"{databaseName}\",",
            "  storagePrefix: \"{prefix}\",",
            "  storageOpfsDirectory: \"{opfsDirectory}\",",
            "}),",
        ],
    },
    "capability-entries": {
        row: () => [
            '"{id}": Object.freeze({renderSeam: "{renderSeam}", patchSet: "{patchSet}", '
                + "terrainSectionUniformBlock: {terrainSectionUniformBlock}}),",
        ],
    },
};

// Every generated region: the file, its style and how many regions of that style it holds.
const OUTPUTS = [
    {path: "port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
        regions: {"return-compact": 1, "or-verbose": 2}},
    {path: "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
        regions: {"or-verbose": 1}},
    {path: "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java",
        regions: {"if-signature": 1}},
    {path: "port/web/singleplayer/server-worker-bootstrap.js",
        regions: {"object-entries": 1}},
    {path: "port/web/runtime/quality/quality-profile.js",
        regions: {"capability-entries": 1}},
];

const args = process.argv.slice(2);
const mode = args.includes("--check") ? "check" : args.includes("--regions") ? "regions" : "write";
const rootIndex = args.indexOf("--root");
const root = rootIndex >= 0
    ? resolve(args[rootIndex + 1] ?? fail("--root needs a directory"))
    : resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
for (const arg of args) {
    if (arg.startsWith("--") && !["--write", "--check", "--regions", "--root"].includes(arg)) {
        fail(`unknown option ${arg}`);
    }
}

const profiles = loadProfiles(join(root, "port/versions"));
let stale = 0;
const regionReport = [];
if (existsSync(join(root, "port/native/kernel-mirror.toml")) && mode !== "regions") {
    for (const profile of mirrorProfiles(root)) {
        const path = join(root, "port/mc/profiles", profile.id, "kernel-semantics.json");
        const text = serialize(reviewedTable(root, profile));
        if (existsSync(path) && readFileSync(path, "utf8") === text) continue;
        stale++;
        if (mode === "write") { mkdirSync(dirname(path), {recursive: true}); writeFileSync(path, text); }
        else console.error(`profilegen: ${path} is stale (run profilegen)`);
    }
}
for (const output of OUTPUTS) {
    const path = join(root, output.path);
    const text = readFileSync(path, "utf8");
    const {text: rendered, regions} = renderFile(text, output, profiles);
    for (const region of regions) regionReport.push({path: output.path, ...region});
    if (rendered === text) continue;
    stale++;
    if (mode === "write") {
        writeFileSync(path, rendered);
        console.log(`profilegen: regenerated ${output.path}`);
    } else if (mode === "check") {
        console.error(`profilegen: ${output.path} is stale (run node port/tools/profilegen/profilegen.mjs)`);
    }
}
if (mode === "regions") {
    console.log(JSON.stringify({profiles: profiles.map((p) => p.id), regions: regionReport}, null, 2));
} else if (mode === "check" && stale > 0) {
    process.exit(1);
} else {
    console.log(`profilegen: ${profiles.length} profiles (${profiles.map((p) => p.id).join(", ")}), `
        + `${regionReport.length} regions in ${OUTPUTS.length} files, `
        + (mode === "write" ? `${stale} rewritten` : "all current"));
}

function loadProfiles(directory) {
    const names = readdirSync(directory).filter((name) => name.endsWith(".json")).sort();
    if (names.length === 0) fail(`${directory} holds no version profile`);
    const result = names.map((name) => validateProfile(
        JSON.parse(readFileSync(join(directory, name), "utf8")), `port/versions/${name}`));
    const ids = new Set();
    const byCode = new Map();
    const byTuple = new Map();
    for (const profile of result) {
        if (ids.has(profile.id)) fail(`profile id ${profile.id} is declared twice`);
        ids.add(profile.id);
        const {code, tuple} = profile.kernelSemantics;
        if (byCode.has(code) && byCode.get(code).tuple !== tuple) {
            fail(`${profile.where}: kernelSemantics.code ${code} already names other semantics `
                + `in profile ${byCode.get(code).id}`);
        }
        if (byTuple.has(tuple) && byTuple.get(tuple).code !== code) {
            fail(`${profile.where}: profile ${byTuple.get(tuple).id} declares the same `
                + `kernelSemantics with code ${byTuple.get(tuple).code}`);
        }
        byCode.set(code, {tuple, id: profile.id});
        byTuple.set(tuple, {code, id: profile.id});
    }
    return result;
}

function validateProfile(profile, where) {
    const id = profile?.id;
    if (typeof id !== "string" || !PROFILE_ID.test(id)) fail(`${where}: id must be a version id`);
    oneOf(profile.inputSeam, INPUT_SEAMS, `${where}: inputSeam`);
    oneOf(profile.shaderFront, SHADER_FRONTS, `${where}: shaderFront`);
    if (typeof profile.renderSeam !== "string") fail(`${where}: renderSeam must be a string`);
    if ((profile.shaderFront === "renderpearl") !== RENDERPEARL_SEAM.test(profile.renderSeam)) {
        fail(`${where}: shaderFront renderpearl goes with a renderpearl-<n> renderSeam and only `
            + `with one (shaderFront ${profile.shaderFront}, renderSeam ${profile.renderSeam})`);
    }
    const semantics = profile.kernelSemantics;
    if (profile.kernelMirror !== undefined) {
        const approval = profile.kernelMirror;
        if (!approval || !/^[a-f0-9]{64}$/u.test(approval.lockSha256) || !approval.enabled
                || typeof approval.enabled !== "object" || Array.isArray(approval.enabled)
                || Object.values(approval.enabled).some((enabled) => typeof enabled !== "boolean")) {
            fail(`${where}: kernelMirror needs lockSha256 and boolean enabled fields`);
        }
    }
    if (semantics === null || typeof semantics !== "object" || Array.isArray(semantics)) {
        fail(`${where}: kernelSemantics must be an object`);
    }
    if (!Number.isInteger(semantics.code) || semantics.code < 0 || semantics.code > 255) {
        fail(`${where}: kernelSemantics.code must be an integer 0..255`);
    }
    oneOf(semantics.floorMode, FLOOR_MODES, `${where}: kernelSemantics.floorMode`);
    oneOf(semantics.synth, SYNTHS, `${where}: kernelSemantics.synth`);
    oneOf(semantics.densityGraph, DENSITY_GRAPHS, `${where}: kernelSemantics.densityGraph`);
    if ((semantics.synth === "f32") !== (semantics.densityGraph === "sampler-f32")) {
        fail(`${where}: kernelSemantics synth f32 goes with densityGraph sampler-f32, f64 with router-f64`);
    }
    if (!Number.isInteger(semantics.irVersion) || semantics.irVersion < 1 || semantics.irVersion > 0xffff) {
        fail(`${where}: kernelSemantics.irVersion must be an integer 1..65535`);
    }
    const storage = profile.storage ?? {};
    const values = {
        id,
        world: profile.worldVersion,
        schema: storage.schema,
        databaseName: storage.databaseName,
        prefix: storage.prefix,
        opfsDirectory: storage.opfsDirectory,
        renderSeam: profile.renderSeam,
        patchSet: profile.patchSet ?? "",
        terrainSectionUniformBlock: profile.terrainSectionUniformBlock === true,
    };
    for (const key of ["world", "schema"]) {
        if (!Number.isInteger(values[key]) || values[key] < 0) fail(`${where}: ${key} must be an integer`);
    }
    for (const key of ["databaseName", "prefix", "opfsDirectory"]) {
        if (typeof values[key] !== "string" || !/^[0-9A-Za-z.:+_-]+$/u.test(values[key])) {
            fail(`${where}: storage.${key} must be a plain identifier`);
        }
    }
    const tuple = [semantics.floorMode, semantics.synth, semantics.densityGraph, semantics.irVersion].join("/");
    return {id, where, values, renderSeam: profile.renderSeam, kernelSemantics: {code: semantics.code, tuple}};
}

function oneOf(value, choices, what) {
    if (!choices.includes(value)) {
        fail(`${what} must be one of ${choices.join(", ")} (received ${JSON.stringify(value)})`);
    }
}

function fill(line, values) {
    return line.replace(/\{(\w+)\}/gu, (_, key) => {
        if (!(key in values)) throw new Error(`unknown placeholder ${key}`);
        return String(values[key]);
    });
}

// The regex for one template line: the literal text with every placeholder accepting any value.
function linePattern(line) {
    const parts = line.split(/\{\w+\}/u).map((part) => part.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&"));
    return new RegExp(`^(\\s*)${parts.join("[^'\"\\s|,;)]*")}$`, "u");
}

// Matches one row of `style` at lines[at] with indentation `indent`; returns its length and
// whether it was a last row, or null.
function matchRow(lines, at, style, first, indent) {
    for (const last of [false, true]) {
        const template = STYLES[style].row(first, last);
        const ok = template.every((line, offset) => {
            const match = linePattern(line).exec(lines[at + offset] ?? "\u0000");
            return match !== null && (indent === null || match[1] === indent);
        });
        if (ok) {
            const variable = STYLES[style].row(first, true).join() !== STYLES[style].row(first, false).join();
            return {length: template.length, last: variable ? last : null};
        }
    }
    return null;
}

function renderRows(style, indent, profiles) {
    const lines = [];
    profiles.forEach((profile, index) => {
        for (const line of STYLES[style].row(index === 0, index === profiles.length - 1)) {
            lines.push(indent + fill(line, profile.values));
        }
    });
    return lines;
}

function renderFile(text, output, profiles) {
    const lines = text.split("\n");
    const found = Object.fromEntries(Object.keys(output.regions).map((style) => [style, 0]));
    const regions = [];
    const result = [];
    let at = 0;
    while (at < lines.length) {
        let region = null;
        for (const style of Object.keys(output.regions)) {
            const head = matchRow(lines, at, style, true, null);
            if (head === null) continue;
            const indent = linePattern(STYLES[style].row(true, head.last ?? false)[0]).exec(lines[at])[1];
            let end = at;
            let first = true;
            for (;;) {
                const row = matchRow(lines, end, style, first, indent);
                if (row === null) break;
                end += row.length;
                first = false;
                if (row.last === true) break;
                if (row.last === null && matchRow(lines, end, style, false, indent) === null) break;
            }
            region = {style, start: at, end, indent};
            break;
        }
        if (region === null) {
            result.push(lines[at]);
            at++;
            continue;
        }
        const rendered = renderRows(region.style, region.indent, profiles);
        regions.push({style: region.style, startLine: result.length + 1, endLine: result.length + rendered.length});
        result.push(...rendered);
        found[region.style]++;
        at = region.end;
    }
    for (const [style, expected] of Object.entries(output.regions)) {
        if (found[style] !== expected) {
            fail(`${output.path}: expected ${expected} ${style} region(s), found ${found[style]}; `
                + "a region must hold nothing but generated rows");
        }
    }
    return {text: result.join("\n"), regions};
}

function fail(message) {
    console.error(`profilegen: ${message}`);
    process.exit(2);
}
