#!/usr/bin/env node
import {createHash} from "node:crypto";
import {execFileSync} from "node:child_process";
import {existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const TOOL = dirname(fileURLToPath(import.meta.url));
const NORMALIZATION = "gaius-kernel-mirror-bytecode-v1";
const PROFILE_ID = /^[0-9A-Za-z][0-9A-Za-z.+-]*$/u;

export const serialize = (value) => JSON.stringify(value, null, 2) + "\n";
export const sha256 = (value) => createHash("sha256").update(value).digest("hex");
export const methodKey = (method) => `${method.kernel}|${method.owner}|${method.name}|${method.descriptor}`;
const signatureKey = (method) => `${method.kernel}|${method.owner}|${method.name}`;

export function loadManifest(path) {
    const manifest = {kernels: [], methods: []};
    let section = manifest;
    for (const [index, original] of readFileSync(path, "utf8").split(/\r?\n/u).entries()) {
        const line = original.trim();
        if (!line || line.startsWith("#")) continue;
        const header = line.match(/^\[\[(kernels|methods)\]\]$/u);
        if (header) {
            section = {};
            manifest[header[1]].push(section);
            continue;
        }
        const assignment = line.match(/^([A-Za-z][A-Za-z0-9]*)\s*=\s*(.+)$/u);
        if (!assignment || Object.hasOwn(section, assignment[1])) {
            throw new Error(`${path}:${index + 1}: invalid or duplicate TOML field`);
        }
        try { section[assignment[1]] = JSON.parse(assignment[2]); }
        catch { throw new Error(`${path}:${index + 1}: use one-line quoted strings, numbers or arrays`); }
    }
    if (manifest.schemaVersion !== 1 || !manifest.kernels.length) throw new Error("Invalid mirror manifest schema");
    const names = new Set();
    for (const kernel of manifest.kernels) {
        if (!/^[a-z][a-z0-9-]*$/u.test(kernel.name) || names.has(kernel.name)
                || !Array.isArray(kernel.dependencies) || typeof kernel.rationale !== "string"
                || typeof kernel.mirrorsVanilla !== "boolean") throw new Error("Invalid kernel declaration");
        names.add(kernel.name);
    }
    for (const kernel of manifest.kernels) {
        if (kernel.dependencies.some((name) => !names.has(name) || name === kernel.name)) {
            throw new Error(`Invalid dependencies for ${kernel.name}`);
        }
    }
    const identities = new Set();
    const type = "(?:[BCDFIJSZ]|L[^;()\\s]+;|\\[+(?:[BCDFIJSZ]|L[^;()\\s]+;))";
    const descriptor = new RegExp(`^\\(${type}*\\)(?:${type}|V)$`, "u");
    for (const method of manifest.methods) {
        if (!names.has(method.kernel) || !/^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$/u.test(method.owner)
                || !/^(?:[A-Za-z_$][\w$]*|<init>|<clinit>)$/u.test(method.name)
                || !descriptor.test(method.descriptor) || !Array.isArray(method.profiles) || !method.profiles.length
                || method.profiles.some((profile) => !PROFILE_ID.test(profile))
                || new Set(method.profiles).size !== method.profiles.length) throw new Error(`Invalid mirror: ${methodKey(method)}`);
        if (identities.has(methodKey(method))) throw new Error(`Duplicate mirror: ${methodKey(method)}`);
        identities.add(methodKey(method));
    }
    return manifest;
}

export function loadProfiles(root) {
    return readdirSync(join(root, "port/versions")).filter((file) => file.endsWith(".json")).sort().map((file) => {
        const profile = JSON.parse(readFileSync(join(root, "port/versions", file), "utf8"));
        if (!PROFILE_ID.test(profile.id) || file !== `${profile.id}.json`) throw new Error(`Invalid profile ${file}`);
        return profile;
    });
}

export function inspectMethods(jar, methods, javaHome, options = {}) {
    const unique = [...new Set(methods.map((method) => `${method.owner}\t${method.name}\t${method.descriptor}`))];
    const java = javaHome ? join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
    const heap = options.javaHeap ?? process.env.GAIUS_KERNELMIRROR_JAVA_HEAP ?? "3g";
    if (!/^[1-9]\d*[mg]$/u.test(heap)) throw new Error("Invalid kernelmirror Java heap");
    const records = JSON.parse(execFileSync(java, [`-Xmx${heap}`, join(TOOL, "ClassHasher.java"), jar], {
        input: unique.join("\n") + "\n", encoding: "utf8", maxBuffer: 32 * 1024 * 1024,
        timeout: options.timeout, env: options.env,
    }));
    return new Map(records.map((method) => [`${method.owner}|${method.name}|${method.descriptor}`, method]));
}

export function computeLock(root, profile, manifest, options = {}) {
    const jar = options.jar ?? join(options.work ?? join(root, "port/work"), profile.id, "client-named.jar");
    if (!existsSync(jar)) throw new Error(`Missing named client jar: ${jar}`);
    const methods = manifest.methods.filter((method) => method.profiles.includes(profile.id))
        .sort((left, right) => methodKey(left).localeCompare(methodKey(right), "en"));
    const inspected = inspectMethods(jar, methods, options.javaHome, options);
    const kernels = [...manifest.kernels].sort((left, right) => left.name.localeCompare(right.name, "en"));
    const declarations = methods.map(({kernel, owner, name, descriptor}) => ({kernel, owner, name, descriptor}));
    return {
        schemaVersion: 1, normalization: NORMALIZATION, profile: profile.id,
        kernelSemantics: profile.kernelSemantics,
        manifestHash: sha256(serialize({kernels, methods: declarations})), kernels,
        methods: declarations.map((method) => {
            const evidence = inspected.get(`${method.owner}|${method.name}|${method.descriptor}`);
            if (!evidence) throw new Error(`Missing hasher evidence: ${methodKey(method)}`);
            const result = {...method, targetHash: evidence.targetHash};
            if (!evidence.targetHash) result.alternatives = evidence.alternatives.sort();
            return result;
        }),
    };
}

function validateLock(lock) {
    if (lock?.schemaVersion !== 1 || lock.normalization !== NORMALIZATION
            || !PROFILE_ID.test(lock.profile) || !Array.isArray(lock.methods) || !Array.isArray(lock.kernels)) {
        throw new Error("Invalid kernel mirror lock");
    }
    const identities = new Set();
    for (const method of lock.methods) {
        if (identities.has(methodKey(method)) || method.targetHash !== null && !/^[a-f0-9]{64}$/u.test(method.targetHash)) {
            throw new Error("Invalid or duplicate locked method");
        }
        identities.add(methodKey(method));
    }
    return lock;
}

export function diffLocks(previous, current) {
    validateLock(previous);
    validateLock(current);
    const before = new Map(previous.methods.map((method) => [methodKey(method), method]));
    const after = new Map(current.methods.map((method) => [methodKey(method), method]));
    const methods = [];
    for (const identity of [...new Set([...before.keys(), ...after.keys()])].sort()) {
        const oldMethod = before.get(identity);
        const newMethod = after.get(identity);
        const method = newMethod ?? oldMethod;
        let status;
        if (!newMethod || newMethod.targetHash === null) {
            status = newMethod?.alternatives?.length || current.methods.some((candidate) =>
                signatureKey(candidate) === signatureKey(method) && candidate.targetHash !== null)
                ? "SIGNATURE" : "GONE";
        } else if (!oldMethod || oldMethod.targetHash === null) status = "SIGNATURE";
        else status = oldMethod.targetHash === newMethod.targetHash ? "UNCHANGED" : "BODY";
        methods.push({kernel: method.kernel, owner: method.owner, name: method.name,
            descriptor: method.descriptor, status});
    }
    const kernels = {};
    for (const kernel of current.kernels) {
        const oldKernel = previous.kernels.find((candidate) => candidate.name === kernel.name);
        const reasons = [];
        if (serialize(oldKernel ?? null) !== serialize(kernel)) reasons.push("DECLARATION");
        if (serialize(previous.kernelSemantics) !== serialize(current.kernelSemantics)) reasons.push("SEMANTICS");
        for (const method of methods.filter((method) => method.kernel === kernel.name && method.status !== "UNCHANGED")) {
            reasons.push(`${method.status}:${method.owner}.${method.name}${method.descriptor}`);
        }
        if (kernel.mirrorsVanilla && !current.methods.some((method) => method.kernel === kernel.name)) {
            reasons.push("EMPTY_MIRROR_SET");
        }
        kernels[kernel.name] = {enabled: reasons.length === 0, reasons};
    }
    disableDependencies(kernels, current.kernels);
    return {schemaVersion: 1, previousProfile: previous.profile, profile: current.profile, methods, kernels};
}

function disableDependencies(table, declarations) {
    let changed = true;
    while (changed) {
        changed = false;
        for (const kernel of declarations) {
            const blocked = kernel.dependencies.filter((name) => table[name]?.enabled !== true);
            if (table[kernel.name]?.enabled && blocked.length) {
                table[kernel.name].enabled = false;
                table[kernel.name].reasons.push(`DEPENDENCY:${blocked.join(",")}`);
                changed = true;
            }
        }
    }
}

export function enableTable(profile, manifest, baselineText, current, error = null) {
    const approval = profile.kernelMirror;
    let verified = false;
    let comparison;
    if (baselineText && approval?.lockSha256 === sha256(baselineText)) {
        try {
            const baseline = validateLock(JSON.parse(baselineText));
            verified = baseline.profile === profile.id && baseline.methods.every((method) => method.targetHash !== null);
            if (verified && current) comparison = diffLocks(baseline, current);
        } catch (failure) { error = failure.message; }
    }
    const kernels = {};
    for (const kernel of manifest.kernels) {
        const reasons = [];
        if (!verified) reasons.push("UNVERIFIED_LOCK");
        if (!current) reasons.push(error ?? "MISSING_JAR");
        if (approval?.enabled?.[kernel.name] !== true) reasons.push("PROFILE_DEFAULT_OFF");
        if (comparison) reasons.push(...(comparison.kernels[kernel.name]?.reasons ?? ["UNLOCKED_KERNEL"]));
        kernels[kernel.name] = {enabled: reasons.length === 0, reasons};
    }
    disableDependencies(kernels, manifest.kernels);
    return {schemaVersion: 1, profile: profile.id, kernels};
}

export function evaluateProfile(root, profile, options = {}) {
    const manifest = options.manifest ?? loadManifest(join(root, "port/native/kernel-mirror.toml"));
    const path = join(root, "port/mc/profiles", profile.id, "kernel-mirror.lock");
    const baselineText = existsSync(path) ? readFileSync(path, "utf8") : null;
    let current = null;
    let error = null;
    try { current = computeLock(root, profile, manifest, options); }
    catch (failure) { error = failure.message; }
    return {table: enableTable(profile, manifest, baselineText, current, error), current, error};
}

export function reviewedTable(root, profile) {
    const manifest = loadManifest(join(root, "port/native/kernel-mirror.toml"));
    const path = join(root, "port/mc/profiles", profile.id, "kernel-mirror.lock");
    const text = existsSync(path) ? readFileSync(path, "utf8") : null;
    let baseline = null;
    try { baseline = text ? validateLock(JSON.parse(text)) : null; } catch { baseline = null; }
    const locked = new Map((baseline?.methods ?? []).map((method) => [methodKey(method), method]));
    const current = baseline ? {...baseline, kernelSemantics: profile.kernelSemantics,
        kernels: [...manifest.kernels].sort((left, right) => left.name.localeCompare(right.name, "en")),
        methods: manifest.methods.filter((method) => method.profiles.includes(profile.id)).map((method) => ({
            kernel: method.kernel, owner: method.owner, name: method.name, descriptor: method.descriptor,
            targetHash: locked.get(methodKey(method))?.targetHash ?? null,
        }))} : null;
    return enableTable(profile, manifest, text, current);
}

function main() {
    const args = process.argv.slice(2);
    const mode = args.shift() ?? "generate";
    const options = {};
    const operands = [];
    const values = new Set(["--root", "--profile", "--out-dir", "--work", "--java-home", "--java-heap", "--jar"]);
    for (let index = 0; index < args.length; index++) {
        const argument = args[index];
        const key = argument.slice(2).replace(/-([a-z])/gu, (_, character) => character.toUpperCase());
        if (["--check", "--warn"].includes(argument)) options[key] = true;
        else if (values.has(argument)) {
            const value = args[++index];
            if (!value || value.startsWith("--")) throw new Error(`${argument} needs a value`);
            options[key] = value;
        } else if (argument.startsWith("--")) throw new Error(`Unknown option ${argument}`);
        else operands.push(argument);
    }
    const root = resolve(options.root ?? join(TOOL, "../../.."));
    options.work ??= process.env.GAIUS_WORK;
    options.javaHome ??= process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME;
    if (mode === "diff") {
        if (operands.length !== 2) throw new Error("diff needs two locks or profile IDs");
        const read = (operand) => validateLock(JSON.parse(readFileSync(existsSync(operand) ? operand
            : join(root, "port/mc/profiles", operand, "kernel-mirror.lock"), "utf8")));
        console.log(serialize(diffLocks(read(operands[0]), read(operands[1]))).trimEnd());
        return;
    }
    if (!["generate", "gate", "table"].includes(mode) || operands.length) throw new Error("Expected generate, gate, table or diff");
    const manifest = loadManifest(join(root, "port/native/kernel-mirror.toml"));
    const profiles = loadProfiles(root).filter((profile) => !options.profile || profile.id === options.profile);
    if (!profiles.length) throw new Error(`Unknown profile ${options.profile}`);
    if (mode === "table") {
        if (profiles.length !== 1) throw new Error("table requires --profile");
        console.log(serialize(evaluateProfile(root, profiles[0], {...options, manifest}).table).trimEnd());
        return;
    }
    let failures = 0;
    for (const profile of profiles) {
        try {
            let value;
            if (mode === "generate") value = computeLock(root, profile, manifest, options);
            else {
                const result = evaluateProfile(root, profile, {...options, manifest});
                value = result.table;
                if (result.error) console.warn(`kernelmirror: ${profile.id}: ${result.error}; vanilla fallback`);
            }
            const missing = value.methods?.filter((method) => method.targetHash === null) ?? [];
            if (missing.length && !options.warn) throw new Error(`Missing mirrors: ${missing.map(methodKey).join(", ")}`);
            const path = join(options.outDir ?? join(root, "port/mc/profiles"), profile.id,
                mode === "generate" ? "kernel-mirror.lock" : "kernel-semantics.json");
            const text = serialize(value);
            const unchanged = existsSync(path) && readFileSync(path, "utf8") === text;
            if (mode === "generate" && !unchanged && existsSync(path)) {
                const comparison = diffLocks(JSON.parse(readFileSync(path, "utf8")), value);
                const counts = {};
                for (const method of comparison.methods) counts[method.status] = (counts[method.status] ?? 0) + 1;
                console.log(`kernelmirror: ${profile.id}: ${JSON.stringify(counts)}`);
                for (const [name, decision] of Object.entries(comparison.kernels)) {
                    console.log(`kernelmirror: ${profile.id}: ${name} enabled=${decision.enabled}`);
                }
            }
            if (!options.check && !unchanged) { mkdirSync(dirname(path), {recursive: true}); writeFileSync(path, text); }
            console.log(`kernelmirror: ${profile.id}: ${mode}, ${unchanged ? "unchanged" : options.check ? "STALE" : "written"}`);
            if (missing.length || options.check && !unchanged) failures++;
        } catch (failure) { failures++; console.error(`kernelmirror: ${profile.id}: ${failure.message}`); }
    }
    if (failures) {
        if (options.warn) console.warn(`kernelmirror: WARN: ${failures} profile differences/errors; no approvals changed`);
        else process.exitCode = 1;
    }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try { main(); }
    catch (failure) {
        console.error(`kernelmirror: ${failure.message}`);
        process.exitCode = process.argv.includes("--warn") ? 0 : 1;
    }
}
