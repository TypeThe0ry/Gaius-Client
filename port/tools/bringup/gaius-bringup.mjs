#!/usr/bin/env node
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, dirname, join, relative, resolve, sep} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {chainFor, diffManifests} from "../patchkit/patchkit.mjs";
import {computeLock, diffLocks, loadManifest, serialize, sha256} from "../kernelmirror/kernelmirror.mjs";
import {resolveSources} from "../sourcesets/sourcesets.mjs";
import {apiDiffReport} from "./api-diff.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const ID = /^[0-9A-Za-z][0-9A-Za-z.+-]*$/u;
const json = (path) => JSON.parse(readFileSync(path, "utf8"));
const lines = (path) => readFileSync(path, "utf8").split(/\r?\n/u).filter(Boolean);
const save = (path, value) => writeFileSync(path, serialize(value));
const hashFile = (path, algorithm = "sha256") => createHash(algorithm).update(readFileSync(path)).digest("hex");

export function setDiff(before, after) {
    const old = new Set(before);
    const current = new Set(after);
    return {added: [...current].filter((entry) => !old.has(entry)).sort(),
        removed: [...old].filter((entry) => !current.has(entry)).sort(),
        unchanged: [...old].filter((entry) => current.has(entry)).length};
}

export function choosePrevious(id, profiles, releaseTime = null) {
    const candidates = profiles.filter((profile) => profile.id !== id && profile.bringup?.provisional !== true);
    if (releaseTime) {
        const dated = candidates.filter((profile) => Date.parse(profile.official?.releaseTime) <= Date.parse(releaseTime))
            .sort((left, right) => Date.parse(left.official.releaseTime) - Date.parse(right.official.releaseTime));
        if (dated.length) return dated.at(-1);
    }
    const compare = (left, right) => left.id.localeCompare(right.id, "en", {numeric: true});
    const earlier = candidates.filter((profile) => compare(profile, {id}) < 0).sort(compare);
    const result = earlier.at(-1) ?? candidates.sort(compare)[0];
    if (!result) throw new Error("No previous profile; supply a reviewed baseline");
    return result;
}

export function draftProfile(id, data, metadata, template, renderSeam = template.renderSeam) {
    if (!ID.test(id)) throw new Error("Invalid profile id");
    const draft = structuredClone(template);
    draft.bringup = {...draft.bringup, provisional: true};
    Object.assign(draft, {id, releaseType: metadata.type, bringupAllowed: true,
        protocolVersion: data.version.protocol_version, worldVersion: data.version.world_version,
        javaVersion: data.version.java_version ?? data.javaRelease, classFileVersion: data.maxMajor,
        renderSeam, inputSeam: data.classes?.includes("com/mojang/blaze3d/platform/SDLEventHandler") ? "sdl3" : template.inputSeam,
        clientDistribution: metadata.downloads?.client_mappings ? "obfuscated-with-mappings" : "named"});
    const pack = data.version.pack_version;
    draft.packVersions = pack.resource_major === undefined ? pack : {
        resource: {major: pack.resource_major, minor: pack.resource_minor},
        data: {major: pack.data_major, minor: pack.data_minor}};
    draft.storage = {schema: template.storage.schema, databaseName: `gaius-fs-v${template.storage.schema}-${id}`,
        prefix: `gaius.fs.v${template.storage.schema}:${id}:`, opfsDirectory: `regions-v${template.storage.schema}-${id}`};
    draft.official = {releaseTime: metadata.releaseTime, clientSha1: metadata.downloads.client.sha1,
        clientMappingsSha1: metadata.downloads.client_mappings?.sha1 ?? null,
        serverSha1: metadata.downloads.server?.sha1 ?? null, assetIndexId: metadata.assetIndex?.id,
        assetIndexSha1: metadata.assetIndex?.sha1};
    draft.libraries = (metadata.libraries ?? []).map((library) => library.name).sort();
    draft.kernelMirror = {lockSha256: "0".repeat(64), enabled: Object.fromEntries(
        Object.keys(template.kernelMirror.enabled).map((name) => [name, false]))};
    return draft;
}

export function detectSeam(classes, seams) {
    const found = new Set(classes);
    const matches = seams.filter((seam) => {
        const required = [...seam.fingerprint.implements, ...seam.fingerprint.extends,
            ...seam.fingerprint.instantiates, ...seam.fingerprint.watch];
        return required.length > 0 && required.every((name) => found.has(name));
    });
    if (matches.length !== 1) throw new Error(`Expected one known SPI generation, found ${matches.length}`);
    return matches[0].generation;
}

export function usageEntries(text) {
    let section = "";
    return text.split(/\r?\n/u).flatMap((line) => {
        if (line.startsWith("[")) section = line.split("]")[0] + "]";
        return /^[CMF] /u.test(line) ? [`${section} ${line}`] : [];
    });
}

// ExtRefs writes library sections only for LWJGL or jars declaring native methods.
// Compare group:artifact and descriptors separately from a coordinate's version.
export function nativeEntryDiff(before, after, requiredEntries = []) {
    const entries = (text) => {
        let library = null;
        return text.split(/\r?\n/u).flatMap((line) => {
            if (line.startsWith("[")) library = /^\[library ([^\]]+)\]/u.exec(line)?.[1]?.split(":").slice(0, 2).join(":") ?? null;
            return library && line.startsWith("M ") ? [`${library} ${line.slice(2)}`] : [];
        });
    };
    const current = entries(after);
    const missingRequired = requiredEntries.filter((required) => !current.some((entry) => entry.slice(entry.indexOf(" ") + 1).split("(")[0] === required));
    return {...setDiff(entries(before), current), count: current.length,
        libraries: [...new Set(current.map((entry) => entry.split(" ")[0]))].sort(), missingRequired};
}

export function apiLockTargets(root, libraries) {
    const collect = (path) => existsSync(path) ? readdirSync(path, {withFileTypes: true})
        .sort((left, right) => left.name.localeCompare(right.name)).flatMap((entry) => entry.isDirectory()
            ? collect(join(path, entry.name)) : entry.name === "api.lock" ? [join(path, entry.name)] : []) : [];
    return collect(join(root, "port/overrides/libraries")).map((lock) => {
        const coordinate = /^library (.+)$/mu.exec(readFileSync(lock, "utf8"))?.[1];
        if (!coordinate) throw new Error(`Missing library coordinate in ${lock}`);
        const candidates = libraries.filter((library) => library.name.startsWith(`${coordinate}:`)
            && !library.name.split(":")[3]?.startsWith("natives"));
        const library = candidates.find((candidate) => candidate.name.split(":").length === 3) ?? candidates[0];
        return {lock, coordinate, library: library?.name ?? null, jar: library?.path ?? null};
    });
}

export function relocationDiff(before, after) {
    const diff = setDiff(before, after);
    const bySimple = new Map();
    for (const name of diff.added) {
        const simple = name.split("/").at(-1);
        bySimple.set(simple, [...(bySimple.get(simple) ?? []), name]);
    }
    return {...diff, relocations: diff.removed.flatMap((name) => {
        const matches = bySimple.get(name.split("/").at(-1)) ?? [];
        return matches.length === 1 ? [{from: name, to: matches[0], confidence: "unique-simple-name-only"}] : [];
    })};
}

export function fingerprintEntries(text) {
    let section = "";
    return text.split(/\r?\n/u).flatMap((line) => {
        if (line.startsWith("[")) section = line;
        return /^[a-f0-9]{16} /u.test(line) ? [`${section} ${line}`] : [];
    });
}

export function inheritKernelGate(delta, previous, declarations) {
    for (const [name, kernel] of Object.entries(delta.kernels)) {
        if (previous.kernelMirror.enabled[name] !== true) {
            kernel.enabled = false;
            kernel.reasons.push("PREDECESSOR_DEFAULT_OFF");
        }
    }
    let changed = true;
    while (changed) {
        changed = false;
        for (const declaration of declarations) {
            const blocked = declaration.dependencies.filter((name) => !delta.kernels[name]?.enabled);
            const kernel = delta.kernels[declaration.name];
            if (kernel?.enabled && blocked.length) {
                kernel.enabled = false;
                kernel.reasons.push(`DEPENDENCY:${blocked.join(",")}`);
                changed = true;
            }
        }
    }
    return delta;
}

export function safeOutput(out, work) {
    const fold = (value) => process.platform === "win32" ? value.toLowerCase() : value;
    const inside = (value, source) => fold(value) === fold(source) || fold(value).startsWith(fold(source + sep));
    if (inside(resolve(out), resolve(work))) throw new Error("Output must not be inside port/work or its junction target");
    let ancestor = resolve(out);
    const suffix = [];
    while (!existsSync(ancestor)) {
        suffix.unshift(ancestor.split(sep).at(-1));
        ancestor = dirname(ancestor);
    }
    const target = resolve(realpathSync(ancestor), ...suffix);
    const source = realpathSync(work);
    if (inside(target, source)) {
        throw new Error("Output must not be inside port/work or its junction target");
    }
    return target;
}

export function fullExitCode(items) {
    return items.some((entry) => entry.required === true && entry.status === "FAIL") ? 2 : 0;
}

export function fullStatus(error, required) {
    const unavailable = /not found|Missing|does not exist|ENOENT/u.test(String(error?.message ?? error));
    return unavailable && !required ? {status: "SKIPPED", required: false} : {status: "FAIL", required};
}

export function classifyPatchOutcomes(manifest) {
    const failed = manifest.patches.filter((patch) => ["FAILED", "BRINGUP_SKIPPED", "DISABLED"].includes(patch.outcome));
    return {
        classification: failed.some((patch) => patch.kind === "REQUIRED") ? "blocking" : failed.length ? "review" : "automatic",
        required: failed.filter((patch) => patch.kind === "REQUIRED"),
        optional: failed.filter((patch) => ["FEATURE", "PERF", "TELEMETRY"].includes(patch.kind)),
        unknown: failed.filter((patch) => !["REQUIRED", "FEATURE", "PERF", "TELEMETRY"].includes(patch.kind)),
    };
}

export function patchClassification(manifest, delta) {
    const outcomes = classifyPatchOutcomes(manifest);
    if (outcomes.required.length) return "blocking";
    return outcomes.optional.length || outcomes.unknown.length || manifest.steps.some((step) => step.status !== "ok")
        || ["review", "failed", "new", "gone", "target-changed", "renamed"].some((name) => delta[name].length)
        ? "review" : "automatic";
}

export function disableKernels(draft, reason) {
    const disabled = Object.keys(draft.kernelMirror.enabled);
    for (const name of disabled) draft.kernelMirror.enabled[name] = false;
    return {disabled, autoDisabled: true, keepVanilla: true, reason};
}

export function shaderCorpusDiff(before, after) {
    const canonical = (value) => {
        if (Array.isArray(value)) return value.map(canonical);
        if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort()
            .filter((key) => key !== "totalMs").map((key) => [key, canonical(value[key])]));
        return value;
    };
    const index = (corpus) => {
        const result = new Map();
        for (const pipeline of corpus.pipelines) {
            const id = pipeline.name ?? pipeline.location;
            if (typeof id !== "string" || !id.length || result.has(id)) throw new Error(`Missing or duplicate pipeline ID: ${id}`);
            result.set(id, JSON.stringify(canonical(pipeline)));
        }
        return result;
    };
    const old = index(before);
    const current = index(after);
    const names = setDiff([...old.keys()], [...current.keys()]);
    const changed = [...current.keys()].filter((name) => old.has(name) && old.get(name) !== current.get(name)).sort();
    return {...names, changed, unchanged: names.unchanged - changed.length};
}

export function humanAction(stage, detail = {}, context = {}) {
    const {id = "<id>", from = "<previous>", out = "<out>", seam = "<generation>", protocol = "<protocol>"} = context;
    const version = `port/versions/${id}.json`;
    const profile = `port/mc/profiles/${id}`;
    const actions = {
        profile: `${out}/profile.draft.json：审阅官方 hashes、libraries、renderSeam/patchSet（决定选用的 source sets）、storage 和 bringup 配方；确认后将审核数据安装到 ${version}，执行 node port/tools/profilegen/profilegen.mjs --check。`,
        remap: `port/work/${id}/version.json、client-original.jar、client-named.jar：由输入维护者修复离线输入/映射及 SHA 校验，再重跑；共享输入只读，不由 bring-up 修改。`,
        seamprobe: `port/mc/seams/${seam}/seam.json：依据 classes.diff.json/spi.diff.json 确认 REQUIRED SPI；在该 seam 的 src/main/java 中按 owner 修复适配器，重跑 seamprobe。`,
        extrefs: `${profile}/usage.lock：审阅 usage.diff.json 后用 bash port/tools/extrefs/extrefs.sh usage ${id} --write 更新；外部库变动还需审阅 ${version} 的 libraries。`,
        patchkit: `port/tools/src/main/java/dev/gaius/tools/PatchRegistry.java：按 patch.<target>.diff.json 定位 patch owner/registration 修复 REQUIRED；FEATURE/PERF/TELEMETRY 保持禁用并记录原因，审核后更新 ${profile}/patch-manifest.js.json、patch-manifest.wasmgc.json。`,
        kernelmirror: `port/native/kernel-mirror.toml、${profile}/kernel-mirror.lock、kernel-semantics.json：按 kernel-mirror.diff.json 审阅方法与依赖；变化或无法验证的 kernel 保持关闭、继续 vanilla Java，不凭相同 lock 启用。`,
        shaders: `${version} 的 bringup.shader：核对 source/main/arguments/nativeLibrariesFrom；按该 source 修复 port/native/golden/shaders/java/dev/gaius/shaderxlat 中的 dumper，重新取得 native translation 证据。`,
        sourcesets: `${version} 的 renderSeam/patchSet（决定选用的 source sets）、port/mc/shared/sourcesets.json 和各 set 的 relocations.tsv：按 sourcesets.json 证据修正路径和搬移规则，再交给 coordinator 编译验证。`,
        "native-entry": `port/src/main/java/org/lwjgl/system/BrowserSharedLibrary.java、port/tools/src/main/java/dev/gaius/tools/LwjglSdlBrowserPatcher.java：按 native-entry.json 中的 owner 更新 entry adapter；审阅 port/overrides/libraries 下的 api.lock，保留旧 adapter 直到 native entry 检查通过。`,
        "relay-protocol": `apps/bridge/protocols/${protocol}.json、${version} 的 relayProtocol：用已审核 vanilla protocol report 更新表和 source hashes；运行 node port/scripts/build-relay-protocol.mjs --profile ${id} --out ${out}/relay-checked；临时保留 ${from} 的表，不把旧表宣称支持新协议。`,
        "version-lint": `port/scripts/quickcheck/version-literals-exempt.txt：仅登记有理由的数据文件豁免；按 version-literals.json findings.file 修复 verification 脚本，运行 python port/scripts/quickcheck/lint_version_literals.py --enforce。`,
        apidiff: `port/overrides 下的 api.lock：按 apidiff.json 定位 library/owner，修复对应 override；确认 API 后运行 bash port/tools/extrefs/extrefs.sh apilock ${id} --write，审阅 ${version} 的 libraries。`,
        "kernel-golden": `port/native/fixtures/${id}/*.jsonl、${profile}/kernel-semantics.json：审阅 kernel-golden.diff.json，运行 bash port/native/golden/run-golden.sh --verify ${id}；缺失或不一致时关闭 kernel，补齐 golden 与 Rust parity 后再审核启用。`,
        "shader-corpus": `${version} 的 bringup.shader、port/native/golden/shaders/java/dev/gaius/shaderxlat：对照 shader-corpus-baseline.json 和 previous-shader-corpus/corpus.json 审阅新增/消失/变化 pipelines 与 SPIR-V；缺失证据保留前版 corpus，修复 recipe/dumper 后重跑。`,
    };
    const base = stage.replace(/-full$/u, "");
    const extra = detail.actions?.map(({file, action}) => `${file}：${action}`).join("；");
    return (actions[base] ?? `${version}：查看该阶段 evidence 与日志，完成文件级修复后重跑 gaius-bringup。`) + (extra ? ` 具体文件动作：${extra}` : "");
}

export function shellPath(path) {
    return process.platform === "win32" ? path.replaceAll("\\", "/") : path;
}

function argumentsFor(argv) {
    const options = {id: argv[0], mode: "fast", root: ROOT, from: null, work: null, out: null, draftOnly: false, build: false};
    if (!ID.test(options.id ?? "")) throw new Error("usage: gaius-bringup.mjs <id> --fast [--from id] [--work DIR] [--out DIR]");
    for (let index = 1; index < argv.length; index++) {
        const name = argv[index];
        if (name === "--fast" || name === "--full") options.mode = name.slice(2);
        else if (name === "--draft-only") options.draftOnly = true;
        else if (name === "--build") options.build = true;
        else if (["--root", "--from", "--work", "--out"].includes(name)) {
            const value = argv[++index];
            if (!value || value.startsWith("--")) throw new Error(`${name} needs a value`);
            options[name.slice(2)] = name === "--from" ? value : resolve(value);
        } else throw new Error(`Unknown argument ${name}`);
    }
    if (options.from && !ID.test(options.from)) throw new Error("Invalid --from profile");
    options.work ??= join(options.root, "port/work");
    options.out ??= join(options.root, "port/mc/profiles", options.id);
    return options;
}

function javaTool(name) {
    for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
        const path = join(home, "bin", name + (process.platform === "win32" ? ".exe" : ""));
        if (existsSync(path)) return path;
    }
    return name;
}

function javaSources(path) {
    return readdirSync(path, {withFileTypes: true}).sort((left, right) => left.name.localeCompare(right.name))
        .flatMap((entry) => entry.isDirectory() ? javaSources(join(path, entry.name))
            : entry.name.endsWith(".java") ? [join(path, entry.name)] : []);
}

export function run(options) {
    const begun = Date.now();
    safeOutput(options.out, options.work);
    mkdirSync(options.out, {recursive: true});
    const evidence = mkdtempSync(join(options.out, "bringup-evidence-"));
    const report = {schemaVersion: 2, profile: options.id, mode: options.mode, build: options.build, recordedAt: new Date().toISOString(),
        previousProfile: null, items: [], stages: [], claims: {teavm: false, chrome: false, parity: false, promotion: false}};
    const item = (classification, stage, title, detail, path = null, status = null, required = classification === "blocking") => report.items.push({
        status: status ?? (classification === "blocking" ? "FAIL" : detail?.autoDisabled || detail?.optionalFailed?.length ? "AUTO_DISABLED" : classification === "review" ? "REVIEW" : "PASS"), required, classification, stage, title,
        detail, action: (classification === "automatic" ? "本项无需手改，保留证据；若后续发生差异，按以下文件动作处理。" : "") +
            humanAction(stage, detail, {id: options.id, from: report.previousProfile, out: shellPath(options.out), seam: draft?.renderSeam, protocol: draft?.protocolVersion}),
        evidence: path ? relative(options.out, path).replaceAll("\\", "/") : null});
    const command = (label, executable, args, extra = {}) => {
        const log = join(evidence, `${label}.log`);
        const start = Date.now();
        const remaining = 1_200_000 - (Date.now() - begun);
        if (remaining <= 0) throw new Error("Fast mode exceeded its 20 minute budget");
        const result = spawnSync(executable, args, {cwd: options.root, encoding: "utf8", maxBuffer: 64 * 1024 * 1024,
            timeout: remaining, env: {...process.env, TMPDIR: evidence, TMP: evidence, TEMP: evidence,
                CARGO_BUILD_JOBS: "2", CARGO_NET_OFFLINE: "true", ...extra.env}, ...Object.fromEntries(Object.entries(extra).filter(([key]) => key !== "env"))});
        writeFileSync(log, `${result.stdout ?? ""}${result.stderr ?? ""}${result.error ? result.error.message : ""}`);
        report.stages.push({label, command: [executable, ...args], log: relative(options.out, log).replaceAll("\\", "/"),
            exitCode: result.status, durationMs: Date.now() - start});
        if (result.status !== 0) throw new Error(`${label} exited ${result.status ?? result.error?.message}; see ${log}`);
        return result.stdout;
    };
    const stage = (name, action, failureClassification = "blocking", fallback = null) => {
        console.log(`BRINGUP_STAGE ${name}`);
        try { action(); } catch (error) {
            const detail = {reason: error.message, ...(fallback ? fallback(error) : {})};
            item(failureClassification, name, "Stage failed", detail, null,
                detail.autoDisabled ? "AUTO_DISABLED" : "FAIL", failureClassification === "blocking");
        }
    };
    const profiles = readdirSync(join(options.root, "port/versions")).filter((name) => name.endsWith(".json"))
        .map((name) => json(join(options.root, "port/versions", name)));
    const metadataPath = join(options.work, options.id, "version.json");
    const previous = options.from ? profiles.find((profile) => profile.id === options.from)
        : choosePrevious(options.id, profiles, existsSync(metadataPath) ? json(metadataPath).releaseTime : null);
    if (!previous) throw new Error(`Missing baseline profile ${options.from}`);
    report.previousProfile = previous.id;
    const baseline = join(options.root, "port/mc/profiles", previous.id);
    const work = join(options.work, options.id);
    const jar = join(work, "client-named.jar");
    const draftPath = join(options.out, "profile.draft.json");
    let draft;
    let metadata;
    let classes;
    let libraries;
    let template;
    const probe = (id, jarPath) => {
        const version = join(evidence, `${id}-jar-version.json`);
        const names = join(evidence, `${id}-classes.txt`);
        const stats = JSON.parse(command(`probe-${id}`, javaTool("java"), ["-Xmx3g",
            join(options.root, "port/tools/bringup/JarProbe.java"), jarPath, version, names]));
        return {version: json(version), classes: lines(names), ...stats};
    };
    stage("profile", () => {
        const data = probe(options.id, jar);
        classes = data.classes;
        metadata = json(join(work, "version.json"));
        const seams = readdirSync(join(options.root, "port/mc/seams"))
            .filter((name) => existsSync(join(options.root, "port/mc/seams", name, "seam.json")))
            .map((name) => json(join(options.root, "port/mc/seams", name, "seam.json")));
        const renderSeam = detectSeam(classes, seams);
        template = profiles.filter((profile) => profile.renderSeam === renderSeam && profile.bringup?.provisional !== true)
            .sort((left, right) => Date.parse(left.official.releaseTime) - Date.parse(right.official.releaseTime)).at(-1);
        if (!template) throw new Error(`No reviewed profile recipe for SPI ${renderSeam}`);
        draft = draftProfile(options.id, data, metadata, template, renderSeam);
        libraries = (metadata.libraries ?? []).filter((library) => library.downloads?.artifact?.path)
            .map((library) => ({...library, path: join(work, "libraries", library.downloads.artifact.path)}));
        // Match build-overlays/extrefs.sh: classifier natives and unavailable optional artifacts
        // are excluded from the Java usage classpath; the build resolves only existing plain jars.
        libraries = libraries.filter((library) => {
            const classifier = library.name.split(":")[3];
            return (!classifier || !classifier.startsWith("natives")) && existsSync(library.path);
        });
        save(draftPath, draft);
        report.inputs = {jar, jarSha256: hashFile(jar), metadataSha256: hashFile(join(work, "version.json")),
            jarId: data.version.id, recipeProfile: template.id, classCount: data.classCount, maxMajor: data.maxMajor};
        report.inputs.classContentSha256 = data.classContentSha256;
        item("automatic", "profile", "Draft profile extracted", {protocol: draft.protocolVersion,
            world: draft.worldVersion, java: draft.javaVersion, renderSeam, inputSeam: draft.inputSeam,
            libraryCoordinates: draft.libraries.length}, draftPath);
        const oldMetadata = json(join(options.work, previous.id, "version.json"));
        const libraryDiff = setDiff(oldMetadata.libraries.map((library) => library.name), draft.libraries);
        save(join(evidence, "libraries.diff.json"), libraryDiff);
        if (libraryDiff.added.length || libraryDiff.removed.length) item("review", "profile", "Library coordinates changed", libraryDiff,
            join(evidence, "libraries.diff.json"));
        if (draft.javaVersion !== previous.javaVersion) item("review", "profile", "Java release changed", {from: previous.javaVersion, to: draft.javaVersion});
        if (data.version.id !== options.id) item("review", "profile", "Aliased jars: dry-run only", {requested: options.id, embedded: data.version.id});
    });
    stage("remap", () => {
        if (!draft || !classes.includes("net/minecraft/client/Minecraft")) throw new Error("Missing named Minecraft entry; run remap-client.sh first");
        const original = join(work, "client-original.jar");
        if (!existsSync(original)) throw new Error("Missing client-original.jar for official SHA-1 verification");
        if (hashFile(original, "sha1") !== draft.official.clientSha1) throw new Error("Original jar SHA-1 differs from launcher metadata");
        const official = probe(`${options.id}-original`, original);
        if (JSON.stringify(official.version) !== JSON.stringify(json(join(evidence, `${options.id}-jar-version.json`)))) {
            throw new Error("Named jar version metadata differs from the official original");
        }
        if (draft.clientDistribution === "named" && official.classContentSha256 !== report.inputs.classContentSha256) {
            throw new Error("Official named distribution has modified class content; run remap-client.sh again");
        }
        const libraryHashes = libraries.map((library) => {
            if (!existsSync(library.path)) throw new Error(`Missing offline library ${library.name}`);
            const actual = hashFile(library.path, "sha1");
            if (actual !== library.downloads.artifact.sha1) throw new Error(`Library SHA-1 mismatch: ${library.name}`);
            return {coordinate: library.name, sha1: actual};
        });
        save(join(evidence, "library-hashes.json"), libraryHashes);
        if (draft.protocolVersion !== undefined && draft.worldVersion !== undefined && Number.isInteger(draft.javaVersion)) {
            command("profile-check", process.execPath, [join(options.root, "port/scripts/check-version-profile.mjs"), "--draft-profile", draftPath]);
        } else throw new Error("Jar version metadata incomplete");
        item("automatic", "remap", "Named jar and official input verified", {distribution: draft.clientDistribution,
            originalSha1: draft.official.clientSha1, classContentVerified: draft.clientDistribution === "named"});
    });
    if (!options.draftOnly && draft) {
        const maven = resolve(process.env.GAIUS_MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository"));
        const artifact = (group, name, version) => {
            const path = join(maven, group.replaceAll(".", "/"), name, version, `${name}-${version}.jar`);
            if (!existsSync(path)) throw new Error(`Missing offline dependency ${path}`);
            return path;
        };
        const classpath = () => {
            const missing = libraries.filter((library) => !existsSync(library.path));
            if (missing.length) throw new Error(`Missing ${missing.length} cached library jars; first ${missing[0].name}`);
            return [jar, ...libraries.map((library) => library.path)].join(delimiter);
        };
        stage("seamprobe", () => {
            const old = probe(previous.id, join(options.work, previous.id, "client-named.jar"));
            const classDiff = relocationDiff(old.classes, classes);
            save(join(evidence, "classes.diff.json"), classDiff);
            const owners = [...new Set(["js", "wasmgc"].flatMap((target) =>
                json(join(baseline, `patch-manifest.${target}.json`)).patches.flatMap((patch) => patch.rewrites ?? [])))]
                .map((owner) => owner.replace(/\.class$/u, ""));
            const missingOwners = owners.filter((owner) => !classes.includes(owner)).sort();
            const ownersPath = join(evidence, "patch-owners.diff.json");
            save(ownersPath, {missingOwners, relocationSuggestions: classDiff.relocations.filter((entry) => missingOwners.includes(entry.from))});
            item(missingOwners.length ? "review" : "automatic", "seamprobe", "Predecessor patch owner inventory",
                {missingOwners: missingOwners.length, relocationSuggestions: classDiff.relocations.filter((entry) => missingOwners.includes(entry.from)).length}, ownersPath);
            if (previous.renderSeam !== draft.renderSeam) item("blocking", "seamprobe", "Generation change detected: manual seam work required",
                {from: previous.renderSeam, to: draft.renderSeam, inputFrom: previous.inputSeam, inputTo: draft.inputSeam,
                    removedClasses: classDiff.removed.length, relocationSuggestions: classDiff.relocations.length,
                    meaning: "Predecessor adapters need migration; this checkout may already contain the repair"}, join(evidence, "classes.diff.json"));
            const teavm = json(join(options.root, "port/config.json")).teaVMVersion;
            const jso = ["teavm-interop", "teavm-jso", "teavm-jso-apis"].map((name) => artifact("org.teavm", name, teavm));
            const asm = ["asm", "asm-tree"].map((name) => artifact("org.ow2.asm", name, "9.8"));
            const core = join(evidence, "seam-core");
            const seamClasses = join(evidence, "seam-classes");
            mkdirSync(core, {recursive: true});
            mkdirSync(seamClasses, {recursive: true});
            const compile = (label, destination, cp, source) => command(label, javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "--release", "21",
                "-proc:none", "-nowarn", "-encoding", "UTF-8", "-cp", cp, "-d", destination, ...javaSources(source)]);
            const compileWithDiff = (label, destination, cp, source) => {
                try { return compile(label, destination, cp, source); }
                catch (error) {
                    const logPath = join(evidence, `${label}.log`);
                    const log = existsSync(logPath) ? readFileSync(logPath, "utf8") : String(error.message);
                    const diff = apiDiffReport("seam-api-diff", relative(options.out, logPath).replaceAll("\\", "/"), log,
                        "按 changes 中的 owner、constructor 和 abstract method 差异修复 renderpearl-1 adapter，再重跑 seamprobe。");
                    const diffPath = join(evidence, "seam-api-diff.json");
                    save(diffPath, diff);
                    error.apiDiff = {...diff, evidence: relative(options.out, diffPath).replaceAll("\\", "/")};
                    throw error;
                }
            };
            compileWithDiff("seam-core", core, jso.join(delimiter), join(options.root, "port/gpu/webgpu-core/src/main/java"));
            const seamDir = join(options.root, "port/mc/seams", draft.renderSeam);
            compileWithDiff("seam-compile", seamClasses, [core, ...jso, classpath()].join(delimiter), join(seamDir, "src/main/java"));
            const fingerprint = join(evidence, "spi.fingerprint");
            command("seamprobe", javaTool("java"), ["-Xmx3g", "-cp", asm.join(delimiter),
                join(options.root, "port/tools/seamprobe/SeamProbe.java"), "--jar", jar, "--seam", seamDir,
                "--seam-classes", seamClasses, "--profile", options.id, "--write", fingerprint]);
            const before = readFileSync(join(baseline, "spi.fingerprint"), "utf8");
            const after = readFileSync(fingerprint, "utf8");
            const field = (text, name) => text.match(new RegExp(`^${name} (.+)$`, "mu"))?.[1];
            const signatureChanged = field(before, "signature") !== field(after, "signature");
            const bodyChanged = field(before, "body") !== field(after, "body");
            const delta = {signatureChanged, bodyChanged, ...setDiff(fingerprintEntries(before), fingerprintEntries(after))};
            save(join(evidence, "spi.diff.json"), delta);
            item(signatureChanged ? "blocking" : bodyChanged ? "review" : "automatic", "seamprobe", "SPI fingerprint comparison",
                {signatureChanged, bodyChanged, added: delta.added.length, removed: delta.removed.length, unchanged: delta.unchanged},
                join(evidence, "spi.diff.json"));
        }, "blocking", (error) => error.apiDiff ? {failureType: "seam-api-change", apiDiff: error.apiDiff,
            degradation: "seam 编译阻塞；已保留精确 API 差异，后续独立阶段继续运行"} : null);
        stage("extrefs", () => {
            classpath();
            const asm = ["asm", "asm-tree"].map((name) => artifact("org.ow2.asm", name, "9.8"));
            const table = join(evidence, "libraries.tsv");
            writeFileSync(table, libraries.filter((library) => !library.name.split(":")[3]?.startsWith("natives"))
                .map((library) => `${library.name}\t${library.path}`).sort().join("\n") + "\n");
            const lock = join(evidence, "usage.lock");
            command("extrefs", javaTool("java"), ["-Xmx3g", "-cp", asm.join(delimiter), join(options.root, "port/tools/extrefs/ExtRefs.java"),
                "usage", "--profile", options.id, "--release", String(draft.javaVersion), "--client", jar, "--libraries", table, "--write", lock]);
            const delta = setDiff(usageEntries(readFileSync(join(baseline, "usage.lock"), "utf8")), usageEntries(readFileSync(lock, "utf8")));
            save(join(evidence, "usage.diff.json"), delta);
            item(delta.added.length || delta.removed.length ? "review" : "automatic", "extrefs", "External usage comparison",
                {added: delta.added.length, removed: delta.removed.length, unchanged: delta.unchanged}, join(evidence, "usage.diff.json"));
        });
        stage("patchkit", () => {
            let failure = null;
            let requiredFailed = false;
            let unknownFailed = false;
            try { command("patchkit", process.execPath, [join(options.root, "port/tools/patchkit/patchkit.mjs"), "check",
                "--profile-file", draftPath, "--work", options.work, "--scratch", evidence,
                "--usage-lock", join(evidence, "usage.lock"), "--out", join(evidence, "patchkit"), "--quiet"]); }
            catch (error) { failure = error; }
            for (const target of ["js", "wasmgc"]) {
                const path = join(evidence, "patchkit", options.id, `patch-manifest.${target}.json`);
                if (!existsSync(path)) {
                    throw new Error(`${target} patch manifest unavailable: ${failure?.message ?? `missing ${path}`}`);
                }
                const manifest = json(path);
                const delta = diffManifests(json(join(baseline, `patch-manifest.${target}.json`)), manifest);
                const deltaPath = join(evidence, `patch.${target}.diff.json`);
                save(deltaPath, delta);
                const outcomes = classifyPatchOutcomes(manifest);
                requiredFailed ||= outcomes.required.length > 0;
                unknownFailed ||= outcomes.unknown.length > 0;
                const crashed = manifest.steps.filter((step) => step.status !== "ok");
                item(patchClassification(manifest, delta), "patchkit", `Patch check + predecessor diff (${target})`,
                    {outcomes: manifest.outcomes, requiredFailed: outcomes.required.map((patch) => patch.id), optionalFailed: outcomes.optional.map((patch) => `${patch.kind}:${patch.id}`), unknownFailed: outcomes.unknown.map((patch) => patch.id), crashed,
                        differences: Object.fromEntries(["unchanged", "renamed", "target-changed", "failed", "new", "gone"].map((name) => [name, delta[name].length]))}, deltaPath);
            }
            if (failure && !requiredFailed && !unknownFailed) {
                item("review", "patchkit", "Patch command returned non-zero", {reason: failure.message,
                    degradation: "known optional patch failures are auto-disabled; keep the previous target table"}, null, "FAIL", false);
            }
        });
        stage("kernelmirror", () => {
            const manifest = loadManifest(join(options.root, "port/native/kernel-mirror.toml"));
            const baselineText = readFileSync(join(baseline, "kernel-mirror.lock"), "utf8");
            if (sha256(baselineText) !== previous.kernelMirror.lockSha256) throw new Error("Predecessor kernel lock is not hash-bound to its profile");
            const inherited = {...manifest, methods: manifest.methods.filter((method) => method.profiles.includes(previous.id)
                || method.profiles.includes(template.id)).map((method) => ({...method, profiles: [options.id]}))};
            const remaining = 1_200_000 - (Date.now() - begun);
            if (remaining <= 0) throw new Error("Fast mode exceeded its 20 minute budget");
            const lock = computeLock(options.root, draft, inherited, {jar, javaHome: process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME,
                timeout: remaining, env: {...process.env, TMPDIR: evidence, TMP: evidence, TEMP: evidence}});
            const delta = diffLocks(JSON.parse(baselineText), lock);
            inheritKernelGate(delta, previous, manifest.kernels);
            draft.kernelMirror = {lockSha256: sha256(serialize(lock)), enabled: Object.fromEntries(
                Object.entries(delta.kernels).map(([name, kernel]) => [name, kernel.enabled]))};
            save(draftPath, draft);
            save(join(evidence, "kernel-mirror.lock"), lock);
            save(join(evidence, "kernel-mirror.diff.json"), delta);
            const disabled = Object.entries(delta.kernels).filter(([, kernel]) => !kernel.enabled).map(([name]) => name);
            item(disabled.length ? "review" : "automatic", "kernelmirror", "Mirror gate (changed methods decline to Java)",
                {disabled, autoDisabled: disabled.length > 0, keepVanilla: true, methods: Object.fromEntries(["UNCHANGED", "BODY", "SIGNATURE", "GONE"].map((status) =>
                    [status, delta.methods.filter((method) => method.status === status).length]))}, join(evidence, "kernel-mirror.diff.json"));
        }, "review", (error) => { const detail = disableKernels(draft, error.message); save(draftPath, draft); return detail; });
        const shaderClasspath = (profileId) => {
            const cached = join(options.work, profileId);
            const data = json(join(cached, "version.json"));
            return [join(cached, "client-named.jar"), ...data.libraries
                .filter((library) => library.downloads?.artifact?.path && !library.name.split(":")[3]?.startsWith("natives"))
                .map((library) => join(cached, "libraries", library.downloads.artifact.path))].join(delimiter);
        };
        const dumpShader = (profile, profileId, label) => {
            const recipe = profile.bringup?.shader;
            if (!recipe) throw new Error("No profile-owned shader recipe; do not guess a version-specific harness");
            const native = join(options.root, "port/native");
            const sourceRoot = join(native, "golden/shaders/java/dev/gaius/shaderxlat");
            const corpus = join(evidence, `${label}corpus`);
            const shaderJar = join(options.work, profileId, "client-named.jar");
            const frontend = recipe.frontend ? join(evidence, `${label}frontend`) : corpus;
            const classesOut = join(evidence, `${label}classes`);
            mkdirSync(classesOut, {recursive: true});
            let cp = shaderClasspath(profileId);
            if (recipe.referenceProfile || recipe.nativeLibrariesFrom) {
                const baseWork = join(options.work, recipe.referenceProfile ?? profileId);
                const baseMetadata = json(join(baseWork, "version.json"));
                const base = baseMetadata.libraries.filter((library) => library.downloads?.artifact?.path && !library.name.startsWith("org.lwjgl:"))
                    .map((library) => join(baseWork, "libraries", library.downloads.artifact.path));
                const nativeWork = join(options.work, recipe.nativeLibrariesFrom ?? profileId);
                const nativeMetadata = json(join(nativeWork, "version.json"));
                cp = [join(baseWork, "client-named.jar"), ...base, ...nativeMetadata.libraries
                    .filter((library) => library.name.startsWith("org.lwjgl:") && library.downloads?.artifact?.path)
                    .map((library) => join(nativeWork, "libraries", library.downloads.artifact.path))].join(delimiter);
            }
            const nativeClassifier = process.platform === "win32" ? (process.arch === "arm64" ? "natives-windows-arm64" : "natives-windows")
                : process.platform === "darwin" ? (process.arch === "arm64" ? "natives-macos-arm64" : "natives-macos")
                    : "natives-linux";
            const nativeWork = join(options.work, recipe.nativeLibrariesFrom ?? profileId);
            const nativeMetadata = json(join(nativeWork, "version.json"));
            const nativeJars = nativeMetadata.libraries.filter((library) => library.name.startsWith("org.lwjgl:")
                && library.name.split(":")[3] === nativeClassifier && library.downloads?.artifact?.path)
                .map((library) => join(nativeWork, "libraries", library.downloads.artifact.path));
            if (!nativeJars.length || nativeJars.some((path) => !existsSync(path))) {
                throw new Error(`Missing offline LWJGL ${nativeClassifier} jars in ${nativeWork}`);
            }
            cp = [cp, ...nativeJars].join(delimiter);
            if (recipe.preDump) {
                const legacy = join(evidence, `${label}legacy`);
                command(`${label}legacy-compile`, javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "--release", String(profile.javaVersion), "-cp", shaderClasspath(profileId),
                    "-d", classesOut, join(sourceRoot, recipe.preDump.source)]);
                command(`${label}legacy-dump`, javaTool("java"), ["-Xmx3g", "-cp", [classesOut, shaderClasspath(profileId)].join(delimiter), recipe.preDump.main, shaderJar, legacy]);
            }
            try {
                command(`${label}compile`, javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "--release", String(profile.javaVersion), "-proc:none", "-nowarn",
                    "-cp", cp, "-d", classesOut, join(sourceRoot, recipe.source)]);
            } catch (error) {
                const logPath = join(evidence, `${label}compile.log`);
                const log = existsSync(logPath) ? readFileSync(logPath, "utf8") : String(error.message);
                const diff = apiDiffReport("shader-api-diff", relative(options.out, logPath).replaceAll("\\", "/"), log,
                    "按 changes 中的 shader API owner/signature 修复 dumper；没有当前 corpus 时不得宣称 shader parity。");
                const diffPath = join(evidence, "shader-api-diff.json");
                save(diffPath, diff);
                error.apiDiff = {...diff, evidence: relative(options.out, diffPath).replaceAll("\\", "/")};
                throw error;
            }
            const substitutions = {"{jar}": shaderJar, "{out}": frontend, "{legacy}": join(evidence, `${label}legacy`)};
            command(`${label}dump`, javaTool("java"), ["-Xmx3g", "--enable-native-access=ALL-UNNAMED",
                `-Dorg.lwjgl.system.SharedLibraryExtractPath=${join(evidence, `${label}natives`)}`, "-cp", [classesOut, cp].join(delimiter),
                recipe.main, ...recipe.arguments.map((argument) => substitutions[argument] ?? argument)]);
            const cargoTarget = safeOutput(join(options.out, "cargo-target"), options.work);
            command(`${label}build`, "cargo", ["build", "--offline", "--locked", "-j", "2", "--release", "-p", "gaius-shaderxlat",
                "--features", "corpus", "--bins", "--target-dir", cargoTarget], {cwd: native});
            const binary = (name) => join(cargoTarget, "release", name + (process.platform === "win32" ? ".exe" : ""));
            if (recipe.frontend) command(`${label}link`, binary("shaderxlat-link"), [frontend, corpus]);
            return {corpus, binary, recipe};
        };
        stage("shaders", () => {
            const {corpus, binary, recipe} = dumpShader(draft, options.id, "shader-");
            const out = join(evidence, "shader-translation");
            command("shader-translate", binary("shaderxlat-corpus"), [corpus, out, "--iterations", "1", ...(recipe.glDepthRemap ? ["--gl-depth-remap"] : [])]);
            const results = json(join(out, "results.json"));
            item("automatic", "shaders", "Native corpus translation passed (no browser claim)", results.summary, join(out, "results.json"));
        }, "blocking", (error) => error.apiDiff ? {failureType: "shader-api-change", apiDiff: error.apiDiff,
            currentCorpus: false, retainedPreviousCorpus: false,
            degradation: "shader dumper 编译阻塞；保留 API diff，不生成伪造 current corpus"} : null);
        stage("sourcesets", () => {
            const result = [];
            for (const kind of ["src", "client"]) {
                for (const target of ["js", "wasmgc"]) {
                    const sources = resolveSources(draftPath, kind, target, options.root);
                    result.push({kind, target, sets: sources.sets, count: sources.files.size,
                        files: [...sources.files].map(([path, info]) => ({path, origin: info.origin, relocations: info.relocations.length}))});
                }
            }
            save(join(evidence, "sourcesets.json"), result);
            item("automatic", "sourcesets", "Source sets resolved (not compiled)", result.map(({files, ...entry}) => entry), join(evidence, "sourcesets.json"));
        });
        stage("native-entry", () => {
            const usageLock = join(evidence, "usage.lock");
            const seamFile = join(options.root, "port/mc/seams", draft.renderSeam, "seam.json");
            const requiredEntries = json(seamFile).requiredEntries ?? [];
            const delta = nativeEntryDiff(readFileSync(join(baseline, "usage.lock"), "utf8"), readFileSync(usageLock, "utf8"), requiredEntries);
            const out = join(evidence, "native-entry.json");
            const changed = delta.added.length + delta.removed.length > 0;
            const actions = [{file: relative(options.root, seamFile).replaceAll("\\", "/"), action: "Repair only missing REQUIRED entries; review descriptor changes against the selected seam."},
                {file: "port/tools/src/main/java/dev/gaius/tools/LwjglSdlBrowserPatcher.java", action: "SDL tables are generated from usage.lock; implement new optional entries in BrowserSdl or retain their FEATURE stub."},
                {file: "port/overrides/libraries/", action: "Review changed native-library adapter entries; leave unsupported optional paths disabled until browser smoke passes."},
                {file: "port/scripts/lwjgl-browser-compat-smoke.sh", action: "After separately building overlays, run the native adapter smoke for this profile."}];
            const detail = {...delta, tool: "port/tools/extrefs/ExtRefs.java usage", usageLock,
                fullSmokeRun: false, keepPreviousTable: changed, actions,
                degradation: "Optional changes are review: retain previously supported adapter entries and disable unsupported FEATURE paths; built-overlay smoke is still required before promotion."};
            save(out, detail);
            item(delta.missingRequired.length ? "blocking" : changed ? "review" : "automatic", "native-entry", "Native library entry-point diff (ExtRefs)", detail, out);
        }, "review");
        stage("relay-protocol", () => {
            const out = join(evidence, "relay-protocol");
            let reason = null;
            let selected = options.id;
            const generate = (id, output, label) => command(label, process.execPath,
                [join(options.root, "port/scripts/build-relay-protocol.mjs"), "--profile", id, "--out", output]);
            try {
                const installed = profiles.find((profile) => profile.id === options.id);
                if (!installed || installed.protocolVersion !== draft.protocolVersion || installed.official.clientSha1 !== draft.official.clientSha1) {
                    throw new Error("No installed protocol profile matching draft metadata");
                }
                generate(options.id, out, "relay-protocol");
            } catch (error) {
                reason = error.message;
                selected = previous.id;
                try { generate(previous.id, out, "relay-protocol-previous"); }
                catch (fallback) { reason += `; previous table generation: ${fallback.message}`; }
            }
            const generated = join(out, "relay-protocol.js");
            const detail = {selectedProfile: selected, protocolVersion: selected === options.id ? draft.protocolVersion : previous.protocolVersion,
                requestedProtocolVersion: draft.protocolVersion, reason, keepPreviousTable: selected !== options.id,
                validForDraft: !reason, bytes: existsSync(generated) ? readFileSync(generated).length : null,
                source: `apps/bridge/protocols/${selected === options.id ? draft.protocolVersion : previous.protocolVersion}.json`,
                degradation: "keep predecessor table on failure; new-protocol relay remains unverified and must not be promoted"};
            const summary = join(evidence, "relay-protocol.json");
            save(summary, detail);
            item(reason ? "review" : "automatic", "relay-protocol", "Relay protocol table generated or predecessor retained", detail, summary);
        }, "review");
        stage("version-lint", () => {
            const tool = join(options.root, "port/scripts/quickcheck/lint_version_literals.py");
            if (!existsSync(tool)) throw new Error(`tool not found: ${tool}`);
            const out = join(evidence, "version-literals.json");
            let reason = null;
            try { command("version-literals", "python", [tool, "--enforce", "--root", options.root, "--json", out]); }
            catch (error) { reason = error.message; }
            item(reason ? "review" : "automatic", "version-lint", "Verification-script version lint", {tool, reason,
                degradation: "lint failure is review; no version is promoted"}, existsSync(out) ? out : null);
        }, "review");
        stage("apidiff", () => {
            const before = usageEntries(readFileSync(join(baseline, "usage.lock"), "utf8"));
            const after = usageEntries(readFileSync(join(evidence, "usage.lock"), "utf8"));
            const delta = setDiff(before, after);
            const out = join(evidence, "apidiff.json");
            const asm = ["asm", "asm-tree"].map((name) => artifact("org.ow2.asm", name, "9.8"));
            const checks = apiLockTargets(options.root, libraries).map((target, index) => {
                if (!target.jar) return {...target, status: "not-shipped"};
                try {
                    // Deliberately omit --version: a new version outside the checked-in range
                    // must still show the API diff rather than passing as unselected.
                    command(`apidiff-${index}`, javaTool("java"), ["-Xmx3g", "-cp", asm.join(delimiter),
                        join(options.root, "port/tools/extrefs/ExtRefs.java"), "apilock", "--jar", target.jar, "--check", target.lock]);
                    return {...target, status: "identical"};
                } catch (error) { return {...target, status: "review", error: error.message}; }
            });
            const oldMetadata = json(join(options.work, previous.id, "version.json"));
            const changedLibraries = setDiff(oldMetadata.libraries.map((library) => library.name), draft.libraries);
            const covered = new Set(checks.filter((check) => check.jar).map((check) => check.coordinate));
            const withoutLock = [...new Set([...changedLibraries.added, ...changedLibraries.removed]
                .filter((coordinate) => !coordinate.split(":")[3]?.startsWith("natives"))
                .map((coordinate) => coordinate.split(":").slice(0, 2).join(":")))].filter((coordinate) => !covered.has(coordinate)).sort();
            const detail = {...delta, tool: "port/tools/extrefs/ExtRefs.java apilock", checks, withoutLock,
                degradation: "API differences, absent API baselines and tool failures remain review; keep existing adapters and disable unsupported optional paths.",
                actions: checks.filter((check) => check.status === "review").map((check) => ({file: relative(options.root, check.lock).replaceAll("\\", "/"),
                    action: "Inspect the apidiff log, adapt the adjacent override source, then regenerate api.lock from reviewed overlays with extrefs.sh apilock --write."}))};
            if (withoutLock.length) detail.actions.push({file: "port/overrides/libraries/", action: `Review changed libraries without an api.lock: ${withoutLock.join(", ")}; add a baseline only where an override depends on their API.`});
            save(out, detail);
            item(delta.added.length || delta.removed.length || checks.some((check) => check.status === "review") || withoutLock.length ? "review" : "automatic", "apidiff", "Library override API checks + JDK/native usage diff", detail, out);
        }, "review");
        stage("kernel-golden", () => {
            const diff = json(join(evidence, "kernel-mirror.diff.json"));
            const changed = diff.methods.filter((method) => method.status !== "UNCHANGED");
            const out = join(evidence, "kernel-golden.diff.json");
            const script = join(options.root, "port/native/golden/run-golden.sh");
            const fixture = join(options.root, "port/native/fixtures", options.id);
            let verification = "not-run";
            let reason = null;
            if (existsSync(fixture)) {
                try {
                    command("kernel-golden", "bash", [script, "--verify", options.id], {env: {...process.env,
                        GAIUS_WORK: shellPath(options.work), GOLDEN_BUILD_ROOT: shellPath(join(evidence, "golden-build")),
                        GOLDEN_CLASSPATH_PREFIX: "", GOLDEN_VERIFY_TAG: ""}});
                    verification = "passed";
                } catch (error) {
                    verification = "failed";
                    reason = error.message;
                }
            } else reason = `No checked-in fixture for ${options.id}`;
            const statuses = Object.fromEntries(["BODY", "SIGNATURE", "GONE"].map((status) =>
                [status, changed.filter((method) => method.status === status).length]));
            const fixtureHashes = (directory) => Object.fromEntries(readdirSync(directory)
                .filter((file) => file.endsWith(".jsonl")).map((file) => [file, hashFile(join(directory, file))]));
            const previousFixture = join(options.root, "port/native/fixtures", previous.id);
            const predecessor = existsSync(previousFixture) ? fixtureHashes(previousFixture) : null;
            if (!predecessor) reason ??= "Missing predecessor fixtures; golden diff unverified";
            const regenerated = join(evidence, "golden-build", `verify-${options.id}`);
            const current = verification === "passed" ? fixtureHashes(regenerated) : null;
            const fixtureDiff = current && predecessor ? {...setDiff(Object.keys(predecessor), Object.keys(current)),
                changed: Object.keys(current).filter((file) => predecessor[file] && predecessor[file] !== current[file]).sort(),
                before: predecessor, after: current} : null;
            if (fixtureDiff) fixtureDiff.unchanged -= fixtureDiff.changed.length;
            if (!fixtureDiff || fixtureDiff.changed.length || fixtureDiff.added.length || fixtureDiff.removed.length) {
                disableKernels(draft, reason ?? "PREDECESSOR_GOLDEN_CHANGED"); save(draftPath, draft);
            }
            const disabled = Object.keys(draft.kernelMirror.enabled).filter((name) => !draft.kernelMirror.enabled[name]);
            save(out, {verification, reason, changed: changed.length, statuses, fixtureDiff, disabled,
                action: "changed or unverified kernels stay disabled; vanilla Java remains active"});
            item(disabled.length || verification !== "passed" ? "review" : "automatic", "kernel-golden", "Kernel golden verify and diff",
                {verification, reason, changed: changed.length, statuses, fixtureDiff, disabled, autoDisabled: disabled.length > 0, keepVanilla: true}, out);
        }, "review", (error) => { const detail = disableKernels(draft, error.message); save(draftPath, draft); return detail; });
        stage("shader-corpus", () => {
            const current = join(evidence, "shader-corpus", "corpus.json");
            const out = join(evidence, "shader-corpus-baseline.json");
            let baselineCorpus = null;
            let reason = null;
            let delta = null;
            try {
                baselineCorpus = join(dumpShader(previous, previous.id, "previous-shader-").corpus, "corpus.json");
                delta = shaderCorpusDiff(json(baselineCorpus), json(current));
            } catch (error) { reason = error.message; }
            const result = {baselineProfile: previous.id, baselinePath: baselineCorpus,
                baseline: baselineCorpus && existsSync(baselineCorpus) ? hashFile(baselineCorpus) : null,
                current: existsSync(current) ? hashFile(current) : null, delta, reason,
                failureType: !existsSync(current) ? "current-corpus-unavailable" : reason ? "baseline-comparison-failed" : null,
                retainedPreviousCorpus: Boolean(baselineCorpus && existsSync(baselineCorpus) && !existsSync(current)),
                degradation: "missing/changed baseline is review; retain previous shader corpus; no browser parity claim"};
            save(out, result);
            item(!existsSync(current) ? "blocking" : delta && !delta.added.length && !delta.removed.length && !delta.changed.length ? "automatic" : "review",
                "shader-corpus", "Shader corpus predecessor baseline diff", result, out);
        }, "review");
    }
    if (options.mode === "full" && !options.draftOnly) {
        const fullGate = (stageName, title, action, required = true) => {
            const started = Date.now();
            try {
                const detail = action() ?? {};
                const classification = detail.classification ?? "automatic";
                item(classification, stageName, title, {...detail, durationMs: Date.now() - started}, null, "PASS", required);
            } catch (error) {
                const unavailable = /not found|Missing|does not exist|ENOENT|does not hash to/u.test(error.message);
                item(unavailable && !required ? "review" : required ? "blocking" : "review", stageName, title,
                    {reason: error.message, durationMs: Date.now() - started}, null,
                    unavailable && !required ? "SKIPPED" : "FAIL", required);
            }
        };
        fullGate("patchkit-full", "Patchkit js/wasmgc lock diff", () => {
            const out = join(evidence, "patchkit-full");
            let checkFailure = null;
            try { command("patchkit-full", process.execPath, [join(options.root, "port/tools/patchkit/patchkit.mjs"), "check",
                "--profile-file", draftPath, "--work", options.work, "--scratch", evidence,
                "--usage-lock", join(evidence, "usage.lock"), "--out", out, "--quiet"]); }
            catch (error) { checkFailure = error; }
            const differences = {};
            for (const target of ["js", "wasmgc"]) {
                const current = json(join(out, options.id, `patch-manifest.${target}.json`));
                const previousManifest = json(join(baseline, `patch-manifest.${target}.json`));
                const diff = diffManifests(previousManifest, current);
                save(join(evidence, `full-patch.${target}.diff.json`), diff);
                const requiredFailed = classifyPatchOutcomes(current).required;
                differences[target] = {classification: patchClassification(current, diff), autoCarry: diff.unchanged.length,
                    reviewTargetChanged: diff["target-changed"].length, optionalFailed: classifyPatchOutcomes(current).optional.map((p) => p.id),
                    requiredFailed: requiredFailed.map((p) => p.id), failed: diff.failed.length};
                if (requiredFailed.length) throw new Error(`${target}: REQUIRED patch failures: ${requiredFailed.map((p) => p.id).join(",")}`);
            }
            if (checkFailure && Object.values(differences).some((entry) => entry.requiredFailed.length)) {
                throw new Error(`${checkFailure.message}; REQUIRED patch failures present`);
            }
            const review = checkFailure || Object.values(differences).some((entry) => entry.classification === "review");
            return {...differences, commandExit: checkFailure ? "review" : 0,
                classification: review ? "review" : "automatic",
                note: review ? "optional patch/lock review pending" : "clean"};
        });
        fullGate("seamprobe-full", "SPI fingerprint vs predecessor", () => {
            const generated = join(evidence, "spi.fingerprint");
            if (!existsSync(generated)) throw new Error("fast seamprobe did not generate spi.fingerprint");
            const before = fingerprintEntries(readFileSync(join(baseline, "spi.fingerprint"), "utf8"));
            const after = fingerprintEntries(readFileSync(generated, "utf8"));
            const delta = setDiff(before, after);
            save(join(evidence, "full-spi.diff.json"), delta);
            return {added: delta.added.length, removed: delta.removed.length, unchanged: delta.unchanged};
        });
        fullGate("apidiff", "Library/JDK usage API diff", () => {
            const generated = join(evidence, "usage.lock");
            if (!existsSync(generated)) throw new Error("extrefs usage.lock was not generated");
            const delta = setDiff(usageEntries(readFileSync(join(baseline, "usage.lock"), "utf8")), usageEntries(readFileSync(generated, "utf8")));
            save(join(evidence, "full-usage.diff.json"), delta);
            return {added: delta.added.length, removed: delta.removed.length, unchanged: delta.unchanged};
        }, false);
        fullGate("extrefs", "Native entry generation check", () => ({usageLock: hashFile(join(evidence, "usage.lock"))}), false);
        fullGate("goldens", "Kernel goldens regeneration and diff", () => {
            const script = join(options.root, "port/native/golden/run-golden.sh");
            if (!existsSync(script)) throw new Error("golden harness not found");
            try { command("golden-verify", "bash", [script, "--verify", options.id], {env: {
                GAIUS_WORK: shellPath(options.work), GOLDEN_BUILD_ROOT: shellPath(join(evidence, "golden-full-build")),
                GOLDEN_CLASSPATH_PREFIX: "", GOLDEN_VERIFY_TAG: ""}}); }
            catch (error) { disableKernels(draft, error.message); save(draftPath, draft); throw error; }
            return {automaticPerKernelDeclineOnDiff: true};
        }, false);
        fullGate("shader", "Shader corpus dump and SPIR-V/WGSL translation", () => {
            const script = join(options.root, "port/native/golden/shaders/run-corpus.sh");
            if (!existsSync(script)) throw new Error("shader corpus tool not found");
            const verified = draft.official?.clientSha1 === hashFile(jar, "sha1")
                && existsSync(join(options.root, "port/native/target/shader-corpus", "webgpu", "corpus.json"));
            const args = [script, "--mc", options.id];
            if (verified) args.push("--no-dump");
            args.push("--target-dir", shellPath(join(evidence, "shader-target")), "--report", shellPath(join(evidence, "shader-report.json")));
            command("shader-corpus", "bash", args);
            return {report: "shader-report.json", dumped: !verified};
        }, false);
        for (const [stageName, tool] of [["version-literals", join(options.root, "port/scripts/quickcheck/lint_version_literals.py")]]) {
            fullGate(stageName, "Generated verification table/lint", () => {
                if (!existsSync(tool)) throw new Error(`tool not found: ${tool}`);
                command(stageName, "python", [tool, "--enforce", "--root", options.root]); return {tool};
            }, false);
        }
        fullGate("relay-protocol", "Relay protocol table generation", () => {
            const out = join(evidence, "relay-protocol");
            command("relay-protocol", process.execPath, [join(options.root, "port/scripts/build-relay-protocol.mjs"), "--profile", options.id, "--out", out]);
            const generated = join(out, "relay-protocol.js");
            if (!existsSync(generated)) throw new Error("relay-protocol.js was not generated");
            return {bytes: readFileSync(generated).length};
        }, false);
        if (options.build) fullGate("wasmgc-build", "WasmGC client/server builds and WebAssembly.validate", () => {
            // GAIUS_SLOT=<path to slot.sh> runs each build inside an isolated build slot;
            // when unset the builds run directly in this checkout.
            const slot = process.env.GAIUS_SLOT || "";
            if (slot && !existsSync(slot)) throw new Error(`GAIUS_SLOT points at a missing file: ${slot}`);
            const dist = join(evidence, "wasmgc-dist");
            const build = (role) => {
                const buildArgs = ["bash", join(options.root, "port/scripts/build-wasmgc.sh"), "--role", role, options.id];
                command(`wasmgc-${role}-build`, "bash", slot ? [slot, "build", `bringup-${options.id}-${role}`, "--", ...buildArgs] : buildArgs.slice(1));
            };
            build("client");
            build("server");
            const validator = "const fs=require('fs'); const p=process.argv[1]; const b=fs.readFileSync(p); if(!WebAssembly.validate(b)) process.exit(1);";
            const wasm = readdirSync(dist, {withFileTypes: true}).flatMap((e) => e.isFile() && e.name.endsWith('.wasm') ? [join(dist, e.name)] : []);
            if (!wasm.length) throw new Error("no produced WasmGC bytes to validate");
            for (const file of wasm) command("wasm-validate", process.execPath, ["-e", validator, file]);
            return {validated: wasm.length};
        });
        else item("review", "wasmgc-build", "WasmGC builds", {reason: "--build not requested"}, null, "SKIPPED", false);
    }
    if (options.draftOnly) item("review", "profile", "Draft-only bootstrap; all fast checks still pending", {});
    report.durationMs = Date.now() - begun;
    report.counts = Object.fromEntries(["automatic", "review", "blocking"].map((classification) =>
        [classification, report.items.filter((entry) => entry.classification === classification).length]));
    report.requiredFailures = report.items.filter((entry) => entry.required && entry.status === "FAIL").length;
    report.exitCode = options.mode === "full" ? fullExitCode(report.items) : (report.counts.blocking ? 2 : report.counts.review ? 1 : 0);
    report.noBlockingItems = report.counts.blocking === 0;
    save(join(options.out, "bringup-report.json"), report);
    const markdown = [`# 版本接入 ${options.id}，基线 ${previous.id}`, "", `模式：${options.mode}；耗时 ${(report.durationMs / 1000).toFixed(1)} 秒；退出码 ${report.exitCode}。`,
        `automatic：${report.counts.automatic}；review：${report.counts.review}；blocking：${report.counts.blocking}。`, "",
        report.noBlockingItems ? "**没有阻塞项；review 仍需审核。**" : "**阻塞项需要修复 REQUIRED adapter/patch 或必需输入。**", "",
        "本报告仅建立接入草稿；不证明 TeaVM、Chrome、kernel parity、发布提升或 JavaScript 发布输出通过。", ""];
    for (const classification of ["blocking", "review", "automatic"]) {
        markdown.push(`## ${classification}`, "");
        for (const entry of report.items.filter((entry) => entry.classification === classification)) {
            markdown.push(`- **${entry.stage}: ${entry.title}** (${entry.status}, required=${entry.required})`, `  ${JSON.stringify(entry.detail)}`, `  人工动作：${entry.action}`,
                entry.evidence ? `  证据：\`${entry.evidence}\`` : "");
        }
        markdown.push("");
    }
    writeFileSync(join(options.out, "bringup-report.md"), markdown.join("\n") + "\n");
    console.log(`BRINGUP_RESULT ${options.id} automatic=${report.counts.automatic} review=${report.counts.review} blocking=${report.counts.blocking} ${(report.durationMs / 1000).toFixed(1)}s exit=${report.exitCode}`);
    return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try { process.exitCode = run(argumentsFor(process.argv.slice(2))).exitCode; }
    catch (error) { console.error(`bringup: ${error.message}`); process.exitCode = 2; }
}
