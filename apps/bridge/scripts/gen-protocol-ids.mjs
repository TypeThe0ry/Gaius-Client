import {createHash} from "node:crypto";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, writeFileSync} from "node:fs";
import {delimiter, dirname, isAbsolute, join, relative, resolve, sep} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {LOGIN_LAYOUT_VERSION, PROTOCOL_SCHEMA_VERSION, RELAY_KEYS,
    validateProtocolTable} from "../dist/protocol-schema.js";

const repository = fileURLToPath(new URL("../../../", import.meta.url));

export function parseOptions(args) {
    const options = {};
    const switches = new Set(["all", "check", "skip-missing", "help"]);
    const values = new Set(["profile", "server-root", "server-jar", "client-work", "java", "out"]);
    for (let index = 0; index < args.length; index++) {
        const key = args[index].replace(/^--/u, "");
        if (!args[index].startsWith("--") || (!switches.has(key) && !values.has(key)) ||
            Object.hasOwn(options, key)) throw new Error(`Invalid option: ${args[index]}`);
        if (switches.has(key)) options[key] = true;
        else {
            const value = args[++index];
            if (!value || value.startsWith("--")) throw new Error(`--${key} requires a value`);
            options[key] = value;
        }
    }
    if (options.help) return options;
    if (Boolean(options.all) === Boolean(options.profile)) {
        throw new Error("Select exactly one of --all or --profile <id>");
    }
    if (options["server-jar"] && (options.all || options["server-root"])) {
        throw new Error("--server-jar requires a single profile and no --server-root");
    }
    if (options["client-work"] && (options.all || options["server-root"] || options["server-jar"])) {
        throw new Error("--client-work requires a single profile and no server override");
    }
    if (options["skip-missing"] && !options.check) {
        throw new Error("--skip-missing is only allowed with --check");
    }
    if (!options.out) throw new Error("--out <scratch-directory> is required");
    if (options.profile && !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/u.test(options.profile)) {
        throw new Error("Invalid profile id");
    }
    return options;
}

export function loadVersionProfiles(root, selected) {
    const directory = join(root, "port", "versions");
    const files = readdirSync(directory).filter(name => name.endsWith(".json")).sort();
    const profiles = files.map(filename => {
        const profile = JSON.parse(readFileSync(join(directory, filename), "utf8"));
        if (filename !== `${profile.id}.json` ||
            !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/u.test(profile.id) ||
            !Number.isSafeInteger(profile.protocolVersion) || profile.protocolVersion < 0 || profile.protocolVersion > 0x7fffffff ||
            !Number.isSafeInteger(profile.javaVersion) || profile.javaVersion < 1 ||
            !/^[0-9a-f]{40}$/u.test(profile.official?.serverSha1 ?? "") ||
            profile.relayProtocol?.loginLayoutVersion !== LOGIN_LAYOUT_VERSION) {
            throw new Error(`Invalid relay metadata in ${filename}; audit the login layout before enabling inspection`);
        }
        return profile;
    });
    if (!profiles.length || new Set(profiles.map(profile => profile.protocolVersion)).size !== profiles.length) {
        throw new Error("Profiles must have unique protocol numbers");
    }
    if (!selected) return profiles;
    const profile = profiles.find(candidate => candidate.id === selected);
    if (!profile) throw new Error(`Unknown profile: ${selected}`);
    return [profile];
}

export function canonicalJson(value) {
    if (Array.isArray(value)) return value.map(canonicalJson);
    if (value !== null && typeof value === "object") {
        return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonicalJson(value[key])]));
    }
    return value;
}

export function tableFromReport(profile, report, provenance) {
    const table = {
        schemaVersion: PROTOCOL_SCHEMA_VERSION,
        name: profile.id,
        protocolVersion: profile.protocolVersion,
        loginLayoutVersion: profile.relayProtocol.loginLayoutVersion,
        source: {
            ...(typeof provenance === "string" ? {kind: "vanilla-server-reports", serverSha1: provenance} : provenance),
            packetsSha256: createHash("sha256").update(JSON.stringify(canonicalJson(report))).digest("hex"),
        },
    };
    for (const [state, keys] of Object.entries(RELAY_KEYS)) {
        table[state] = Object.fromEntries(Object.entries(keys).map(([key, [phase, direction, identifier]]) => {
            const id = report[phase]?.[direction]?.[identifier]?.protocol_id;
            if (!Number.isInteger(id) || id < 0) {
                throw new Error(`Missing or invalid packet ${phase}/${direction}/${identifier} for ${profile.id}`);
            }
            return [key, id];
        }));
    }
    if (!validateProtocolTable(table, profile.protocolVersion)) {
        throw new Error(`Unsupported relay wire contract for ${profile.id}; leave this protocol opaque`);
    }
    return table;
}

export function serializeTable(table) {
    return `${JSON.stringify(table, null, 2)}\n`;
}

function isWithin(directory, target) {
    const location = relative(directory, target);
    return location === "" || (location !== ".." && !location.startsWith(".." + sep) && !isAbsolute(location));
}

function resolveExistingPath(target) {
    const suffix = [];
    let existing = target;
    while (!existsSync(existing)) {
        const parent = dirname(existing);
        if (parent === existing) throw new Error("Scratch output has no existing filesystem root");
        suffix.unshift(relative(parent, existing));
        existing = parent;
    }
    return resolve(realpathSync(existing), ...suffix);
}

function serverJar(root, profile, options) {
    if (options["server-jar"]) {
        const path = resolve(options["server-jar"]);
        if (!existsSync(path)) throw new Error(`Explicit server jar is unavailable: ${path}`);
        return path;
    }
    const candidates = options["server-root"]
        ? [resolve(options["server-root"], profile.id, "server.jar")]
        : [join(root, "port", "work", profile.id, "server.jar"),
            join(root, "port", "target", profile.id, "multiplayer-smoke-server", "server.jar"),
            join(root, "port", "target", profile.id, "mp", "server", "server.jar"),
            join(root, "port", "target", profile.id, "server.jar")];
    return candidates.find(path => existsSync(path));
}

function javaExecutable(profile, options) {
    const configured = options.java ?? process.env[`GAIUS_JAVA_${profile.javaVersion}`] ??
        process.env.GAIUS_JAVA ?? process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME;
    if (!configured) return "java";
    const normalized = process.platform === "win32"
        ? configured.replace(/^\/([a-zA-Z])\//u, "$1:/") : configured;
    return existsSync(join(normalized, "bin"))
        ? join(normalized, "bin", process.platform === "win32" ? "java.exe" : "java") : normalized;
}

function generateReport(profile, jar, options, output) {
    const actual = createHash("sha1").update(readFileSync(jar)).digest("hex");
    const expected = options["client-work"] ? profile.official.clientSha1 : profile.official.serverSha1;
    if (actual !== expected) {
        throw new Error(`Jar SHA-1 mismatch for ${profile.id}: ${actual} != ${expected}`);
    }
    const java = javaExecutable(profile, options);
    const probe = spawnSync(java, ["-Xmx3g", "-version"], {encoding: "utf8", timeout: 30000, windowsHide: true});
    const major = /version "(?:1\.)?(\d+)/u.exec(`${probe.stdout ?? ""}${probe.stderr ?? ""}`);
    if (probe.status !== 0 || !major || Number(major[1]) < profile.javaVersion) {
        throw new Error(`Java ${profile.javaVersion}+ is required for ${profile.id}: ${probe.error ?? probe.stderr}`);
    }
    const directory = mkdtempSync(join(output, `${profile.id}-reports-`));
    let entrypoint = ["-DbundlerMainClass=net.minecraft.data.Main", "-jar", jar];
    if (options["client-work"]) {
        const listing = readFileSync(resolve(options["client-work"], "classpath.txt"), "utf8").trim();
        const separator = /^[a-zA-Z]:[\\/]/u.test(listing) ? ";" : ":";
        const libraries = listing.split(separator)
            .filter(entry => entry && !entry.includes("natives") && !/client-(?:named|original)\.jar$/u.test(entry))
            .map(entry => process.platform === "win32" ? entry.replace(/^\/([a-zA-Z])\//u, "$1:/") : entry);
        for (const library of libraries) {
            if (!existsSync(library)) throw new Error(`Local data-generator library unavailable: ${library}`);
        }
        entrypoint = ["-cp", [jar, ...libraries].join(delimiter), "net.minecraft.data.Main"];
    }
    const start = performance.now();
    const result = spawnSync(java, ["-Xmx3g", ...entrypoint,
        "--reports", "--output", join(directory, "reports-output")], {
        cwd: directory, encoding: "utf8", timeout: 300000, maxBuffer: 64 * 1024 * 1024, windowsHide: true,
    });
    writeFileSync(join(directory, "stdout.log"), result.stdout ?? "", "utf8");
    writeFileSync(join(directory, "stderr.log"), result.stderr ?? "", "utf8");
    const packets = join(directory, "reports-output", "reports", "packets.json");
    if (result.status !== 0 || !existsSync(packets)) {
        throw new Error(`Reports failed for ${profile.id} (exit ${result.status}): ${result.error ?? ""}\n` +
            `${result.stderr?.slice(-3000) ?? ""}\nSee ${directory}`);
    }
    console.log(`REPORT ${profile.id}: ${(performance.now() - start).toFixed(0)} ms; ${directory}`);
    const provenance = options["client-work"] ? {
        kind: "vanilla-client-reports", clientSha1: actual, expectedServerSha1: profile.official.serverSha1,
    } : actual;
    return tableFromReport(profile, JSON.parse(readFileSync(packets, "utf8")), provenance);
}

export function runGenerator(args, root = repository) {
    const options = parseOptions(args);
    if (options.help) {
        console.log("Usage: node apps/bridge/scripts/gen-protocol-ids.mjs (--all | --profile <id>) " +
            "--out <scratch-directory> [--server-root <dir> | --server-jar <jar>] [--java <exe-or-home>] " +
            "[--client-work <verified-client-work-dir>] [--check [--skip-missing]]");
        return {checked: 0, skipped: 0};
    }
    const profiles = loadVersionProfiles(root, options.profile);
    const tablesDirectory = join(root, "apps", "bridge", "protocols");
    if (options.check) {
        for (const profile of profiles) {
            const table = JSON.parse(readFileSync(join(tablesDirectory, `${profile.protocolVersion}.json`), "utf8"));
            if (!validateProtocolTable(table, profile.protocolVersion) || table.name !== profile.id ||
                table.loginLayoutVersion !== profile.relayProtocol.loginLayoutVersion ||
                (table.source.kind === "vanilla-server-reports"
                    ? table.source.serverSha1 !== profile.official.serverSha1
                    : table.source.clientSha1 !== profile.official.clientSha1 ||
                        table.source.expectedServerSha1 !== profile.official.serverSha1)) {
                throw new Error(`Committed protocol table disagrees with profile ${profile.id}`);
            }
        }
        if (options.all) {
            const expected = profiles.map(profile => `${profile.protocolVersion}.json`).sort();
            const actual = readdirSync(tablesDirectory).filter(name => /^\d+\.json$/u.test(name)).sort();
            if (JSON.stringify(expected) !== JSON.stringify(actual)) throw new Error("Protocol table file set drifted from profiles");
        }
    }
    const output = resolve(options.out);
    const work = resolve(root, "port", "work");
    const actualOutput = resolveExistingPath(output);
    if (isWithin(work, output) || isWithin(resolveExistingPath(work), actualOutput)) {
        throw new Error("Scratch output must not be inside port/work");
    }
    if (isWithin(resolve(root), output) || isWithin(resolveExistingPath(resolve(root)), actualOutput)) {
        throw new Error("Scratch output must be outside the repository, including the committed protocol tables");
    }
    mkdirSync(output, {recursive: true});
    const candidates = [];
    let skipped = 0;
    for (const profile of profiles) {
        const jar = options["client-work"]
            ? resolve(options["client-work"], "client-original.jar") : serverJar(root, profile, options);
        if (!jar) {
            if (!options["skip-missing"]) throw new Error(`Local server jar unavailable for ${profile.id}; no download attempted`);
            console.log(`SKIP ${profile.id}: local server jar unavailable (table validation passed)`);
            skipped++;
            continue;
        }
        const text = serializeTable(generateReport(profile, jar, options, output));
        const filename = `${profile.protocolVersion}.json`;
        writeFileSync(join(output, filename), text, "utf8");
        if (options.check && readFileSync(join(tablesDirectory, filename), "utf8") !== text) {
            throw new Error(`Protocol table drift: ${filename}; candidate saved in ${output}`);
        }
        candidates.push({filename, text});
    }
    if (!options.check) {
        mkdirSync(tablesDirectory, {recursive: true});
        for (const {filename, text} of candidates) writeFileSync(join(tablesDirectory, filename), text, "utf8");
    }
    console.log(`${options.check ? "CHECK" : "WRITE"}: ${candidates.length} tables; ${skipped} unavailable jars`);
    return {checked: candidates.length, skipped};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try { runGenerator(process.argv.slice(2)); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
}
