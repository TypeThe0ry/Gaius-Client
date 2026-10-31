#!/usr/bin/env node
// patchkit: check mode, method targets and their reviewed lock diff.
//
//   node port/tools/patchkit/patchkit.mjs check [--profile <id>]... [--target js|wasmgc]...
//       [--out <dir>] [--write] [--compare [--warn]] [--bringup] [--fail <id>[,<id>]...]
//       [--keep] [--quiet]
//   node port/tools/patchkit/patchkit.mjs diff <old manifest> <new manifest> [--json <file>]
//   node port/tools/patchkit/patchkit.mjs lint
//   node port/tools/patchkit/patchkit.mjs --self-test
//   node port/tools/patchkit/patchkit.mjs --bytecode-test
//
// check runs the client patcher chain of build-overlays.sh (AbstractSpliteratorBrowserPatcher,
// MinecraftClientPatcher, the patchSet arm, the profile's extraPatchers) for every profile and
// TeaVM target on a scratch copy of port/work/<profile>/client-named.jar: javac and the patchers
// only, offline, no overlays, no TeaVM and no write outside the scratch directory (deleted unless
// --keep). PatchRegistry runs in check mode (gaius.patch.check): a failing patch is restored and
// recorded, and the chain goes on, so every outcome is listed. One line per registered patch id
// is printed (PATCHKIT <profile> <target> <OUTCOME> <KIND> <id> [reason]), then the manifest of
// each profile and target is generated from the registry's run log (not from census diffs) into
// <out>/<profile>/patch-manifest.<target>.json. --write also writes it to
// port/mc/profiles/<profile>/patch-manifest.<target>.json; --compare classifies it against that
// checked-in manifest (the lock diff below), and fails on a difference unless --warn.
// --fail injects failures (gaius.patch.fail), --bringup enables bring-up mode.
// Exit status: 0 when no REQUIRED patch failed, no step crashed and (with --compare and without
// --warn) the manifests are unchanged or exactly acknowledged. P7 write-set drift always fails;
// Unacknowledged vanilla target changes fail except in CI warn mode; --write always refuses
// them. 1 otherwise; 2 for usage errors.
//
// diff is the lock diff: every id of two manifests is unchanged, renamed (an id that is gone
// and a new id with the same patcher, kind and rewritten vanilla classes, or the same method
// name), target-changed (the same id with other rewritten classes, absent targets, outcome,
// kind, targets, profiles or method hashes), failed (now FAILED or DISABLED), new or gone.
// Vanilla-backed target changes require exact reviewed acknowledgements. Static-write changes
// cannot be acknowledged away. Exit status 0 when unchanged or only exactly acknowledged, 1 otherwise.
//
// lint checks the registrations of port/tools without any jar: every PatchRegistry.run names a
// valid spec, every id is in port/tools/patchkit/classification.tsv with the same kind and
// vice versa, port/tools/target-skips/<target>.txt lists exactly the ids whose spec leaves the
// other target out, and the chain of this tool matches build-overlays.sh. --self-test runs the
// manifest generator and the lock diff on fixtures, then lint.

import {spawnSync} from "node:child_process";
import {readAcknowledgements, targetChanges, validateTargets} from "./target-lock.mjs";
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync,
  writeFileSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, dirname, isAbsolute, join, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

export const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const toolsRoot = join(repositoryRoot, "port/tools/src/main/java");
const toolsPackage = join(toolsRoot, "dev/gaius/tools");
const ASM_VERSION = "9.8";
export const KINDS = ["REQUIRED", "FEATURE", "PERF", "TELEMETRY"];
export const OUTCOMES = ["APPLIED", "NOT_APPLICABLE", "DISABLED", "FAILED", "BRINGUP_SKIPPED"];
export const TARGETS = ["js", "wasmgc"];
const PATCH_ID = /^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$/;
const PROFILE_TERM = /^(?:\*|(?:>=|<=)?[0-9]+(?:\.[0-9]+)*)$/;
const CATEGORIES = ["unchanged", "renamed", "target-changed", "failed", "new", "gone"];

class UsageError extends Error {}

function nativePath(value) {
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
}

// ------------------------------------------------------------------ specs and registrations

/** Parses a PatchRegistry spec ("<KIND> [js|wasmgc]... [profiles]..."), as PatchRegistry.Spec does. */
export function parseSpec(text) {
  const tokens = String(text).trim().split(/\s+/);
  if (!KINDS.includes(tokens[0])) {
    throw new Error(`patch spec '${text}' must start with ${KINDS.join(", ")}`);
  }
  const targets = new Set();
  const terms = [];
  const flags = {};
  for (const token of tokens.slice(1)) {
    if (token === "NOT_APPLICABLE" || token === "OVERRIDE") {
      const name = token === "NOT_APPLICABLE" ? "notApplicable" : "wholeClass";
      if (flags[name]) throw new Error(`patch spec '${text}' names ${token} twice`);
      flags[name] = true;
      continue;
    }
    if (TARGETS.includes(token)) {
      if (targets.has(token)) throw new Error(`patch spec '${text}' names target ${token} twice`);
      targets.add(token);
      continue;
    }
    for (const term of token.split(",")) {
      if (!PROFILE_TERM.test(term)) {
        throw new Error(`patch spec '${text}': '${term}' is neither a target nor a profile term`);
      }
      if (terms.includes(term)) throw new Error(`patch spec '${text}' names ${term} twice`);
      terms.push(term);
    }
  }
  if (terms.includes("*") && terms.length > 1) throw new Error(`patch spec '${text}': '*' with other terms`);
  return {
    kind: tokens[0],
    targets: TARGETS.filter((target) => targets.size === 0 || targets.has(target)),
    profiles: terms.length === 0 ? "*" : terms.join(","),
    ...flags,
  };
}

function leadingVersion(text) {
  const match = /^([0-9]+(?:\.[0-9]+)*)/.exec(text);
  return match ? match[1].split(".").map(Number) : null;
}

function compareVersions(left, right) {
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    const a = left[index] ?? 0;
    const b = right[index] ?? 0;
    if (a !== b) return a < b ? -1 : 1;
  }
  return 0;
}

/** Whether a spec's profiles cover a profile id (the rule of PatchRegistry.Spec.coversProfile). */
export function coversProfile(profiles, profile) {
  if (profiles === "*" || !profile) return true;
  const version = leadingVersion(profile);
  if (!version) return false;
  return profiles.split(",").some((term) => {
    if (term.startsWith(">=")) return compareVersions(version, leadingVersion(term.slice(2))) >= 0;
    if (term.startsWith("<=")) return compareVersions(version, leadingVersion(term.slice(2))) <= 0;
    return compareVersions(version, leadingVersion(term)) === 0;
  });
}

/**
 * Whether the profile terms of a target-skips entry include every profile the spec covers. A
 * registration that a later generation's patcher chain runs as well (the 26.3 chain includes the
 * 26.2 patcher) is target-skipped there too: PatchRegistry checks the target before the profile.
 */
export function entryCoversSpec(entryProfiles, specProfiles) {
  if (entryProfiles === "*") return true;
  if (specProfiles === "*") return false;
  return specProfiles.split(",").every((term) => {
    const bound = term.startsWith(">=") || term.startsWith("<=");
    const samples = [bound ? term.slice(2) : term];
    if (term.startsWith(">=")) samples.push("9999");
    if (term.startsWith("<=")) samples.push("0");
    return samples.every((sample) => coversProfile(entryProfiles, sample));
  });
}

function javaSources(directory) {
  const files = [];
  for (const entry of readdirSync(directory, {withFileTypes: true})) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...javaSources(path));
    else if (entry.name.endsWith(".java")) files.push(path);
  }
  return files.sort();
}

// Patch code by domain (port/mc/patches/<domain>): the Java packages
// dev.gaius.tools.patches.<domain> under port/tools, which the -sourcepath of every port/tools
// compile (build-overlays.sh, build-teavm.sh, this tool) already picks up. The package
// dev.gaius.tools.patches.support holds the ASM helpers that several domains share; it is not
// a domain.
const DOMAIN_ROOT = "port/tools/src/main/java/dev/gaius/tools/patches";
const SUPPORT_PACKAGE = "support";

/** {simple class name: domain} of the classes under the domain packages. */
export function domainClasses(root = repositoryRoot) {
  const classes = new Map();
  const base = join(root, DOMAIN_ROOT);
  if (!existsSync(base)) return classes;
  for (const entry of readdirSync(base, {withFileTypes: true})) {
    if (!entry.isDirectory() || entry.name === SUPPORT_PACKAGE) continue;
    for (const file of javaSources(join(base, entry.name))) {
      const name = file.slice(file.lastIndexOf(file.includes("\\") ? "\\" : "/") + 1, -".java".length);
      classes.set(name, entry.name);
    }
  }
  return classes;
}

/** The end (exclusive) of the call whose "(" is at open: string, char and comment aware. */
function callEnd(text, open) {
  let depth = 0;
  for (let index = open; index < text.length; index++) {
    const ch = text[index];
    if (ch === "\"" || ch === "'") {
      for (index++; index < text.length && text[index] !== ch; index++) {
        if (text[index] === "\\") index++;
      }
    } else if (ch === "/" && text[index + 1] === "/") {
      index = text.indexOf("\n", index);
      if (index < 0) return text.length;
    } else if (ch === "/" && text[index + 1] === "*") {
      index = text.indexOf("*/", index + 2) + 1;
      if (index <= 0) return text.length;
    } else if (ch === "(") {
      depth++;
    } else if (ch === ")" && --depth === 0) {
      return index + 1;
    }
  }
  return text.length;
}

/**
 * The PatchRegistry.run registrations of port/tools: {id, spec, file, line, domain}. A
 * registration without a spec (the deprecated two-argument run) has spec null; runJsOnly/
 * runWasmOnly are reported as such (they no longer exist). domain is the domain package of the
 * patch code: the registration's own file when it lies in a domain package, else the domain of
 * the first domain class the registered body calls (null when the code is still in a patcher).
 */
export function sourceRegistrations(root = repositoryRoot) {
  const registrations = [];
  const classes = domainClasses(root);
  const domainCall = classes.size === 0 ? null
    : new RegExp(`\\b(${[...classes.keys()].map((name) => name.replaceAll("$", "\\$")).join("|")})\\s*\\.`);
  for (const file of javaSources(join(root, "port/tools/src/main/java"))) {
    const text = readFileSync(file, "utf8");
    const relative = file.slice(root.length + 1).replaceAll("\\", "/");
    const filePackage = relative.startsWith(`${DOMAIN_ROOT}/`)
      ? relative.slice(DOMAIN_ROOT.length + 1).split("/")[0] : null;
    const fileDomain = filePackage === SUPPORT_PACKAGE ? null : filePackage;
    const call = /PatchRegistry\s*\.\s*(run|runPart|runJsOnly|runWasmOnly)\s*\(\s*"([^"]*)"\s*,\s*(?:"([^"]*)"\s*,)?/g;
    for (const match of text.matchAll(call)) {
      const before = text.slice(0, match.index);
      const lineStart = before.lastIndexOf("\n") + 1;
      const prefix = text.slice(lineStart, match.index);
      // Javadoc mentions such as {@code PatchRegistry.run("<Domain>.<method>", ...)}.
      if (/^\s*\*|\{@code\s*$|\/\//.test(prefix) || !PATCH_ID.test(match[2])) continue;
      const open = text.indexOf("(", match.index);
      const body = text.slice(match.index + match[0].length, callEnd(text, open));
      const called = domainCall?.exec(body);
      registrations.push({
        id: match[2], method: match[1], spec: match[3] ?? null,
        file: relative,
        line: before.split("\n").length,
        domain: fileDomain ?? (called ? classes.get(called[1]) : null),
      });
    }
  }
  return registrations;
}

/** Whether a spec names its TeaVM targets instead of taking the default (every target). */
export function specNamesTargets(text) {
  return String(text).trim().split(/\s+/).slice(1).some((token) => TARGETS.includes(token));
}

/** port/tools/patchkit/classification.tsv: Map id -> {kind, census, wasm, reason}. */
export function readClassification(root = repositoryRoot) {
  const path = join(root, "port/tools/patchkit/classification.tsv");
  const rows = new Map();
  let header = null;
  readFileSync(path, "utf8").split(/\r?\n/).forEach((line, index) => {
    if (!line || line.startsWith("#")) return;
    const fields = line.split("\t");
    if (!header) {
      header = fields;
      if (header.join(",") !== "id,kind,census,wasm,reason") {
        throw new Error(`${path}:${index + 1}: header must be id, kind, census, wasm, reason`);
      }
      return;
    }
    const [id, kind, census, wasm, reason] = fields;
    if (fields.length !== 5 || !PATCH_ID.test(id) || !KINDS.includes(kind)
        || !["same", "js-only", "wasm-only"].includes(wasm) || !reason || !census) {
      throw new Error(`${path}:${index + 1}: malformed row ${JSON.stringify(line)}`);
    }
    if (rows.has(id)) throw new Error(`${path}:${index + 1}: duplicate id ${id}`);
    rows.set(id, {kind, census, wasm, reason});
  });
  return rows;
}

/**
 * A patchkitNotApplicable value of a version profile: the reason (every target) or
 * {targets, reason} for a patch that the profile's generation applies on some targets only.
 * Returns {reason, targets} or null when malformed (VersionProfile.notApplicable).
 */
export function notApplicableDeclaration(value) {
  if (typeof value === "string") return value.trim() ? {reason: value, targets: [...TARGETS]} : null;
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const keys = Object.keys(value).sort();
  if (keys.join(",") !== "reason,targets" || typeof value.reason !== "string" || !value.reason.trim()) return null;
  const targets = value.targets;
  if (!Array.isArray(targets) || targets.length === 0 || new Set(targets).size !== targets.length
      || targets.some((target) => !TARGETS.includes(target))) return null;
  return {reason: value.reason, targets: [...targets]};
}

/** port/tools/target-skips/<target>.txt: Map id -> {profiles, reason}. */
export function readTargetSkips(target, root = repositoryRoot) {
  const entries = new Map();
  const path = join(root, "port/tools/target-skips", `${target}.txt`);
  readFileSync(path, "utf8").split(/\r?\n/).forEach((raw, index) => {
    const line = raw.replace(/#.*$/, "").trim();
    if (!line) return;
    const fields = line.split("|").map((field) => field.trim());
    if (fields.length !== 3 || !PATCH_ID.test(fields[0]) || !fields[2]) {
      throw new Error(`${path}:${index + 1} must be "<patchId> | <profiles> | <reason>"`);
    }
    entries.set(fields[0], {profiles: fields[1], reason: fields[2]});
  });
  return entries;
}

// --------------------------------------------------------------------------- the chain

/** The client patcher chain of build-overlays.sh for a version profile. */
export function chainFor(profile, root = repositoryRoot, profileFile = null) {
  const data = JSON.parse(readFileSync(profileFile ?? join(root, "port/versions", `${profile}.json`), "utf8"));
  const steps = [
    {patcher: "AbstractSpliteratorBrowserPatcher", args: ["jar", "out"]},
    {patcher: "MinecraftClientPatcher", args: ["jar", "out", "version"]},
  ];
  if (data.patchSet === "modern") {
    steps.push({patcher: "MinecraftChunkDrawTelemetryPatcher", args: ["version", "jar", "out"]},
      {patcher: "Minecraft262BrowserPatcher", args: ["jar", "out", "version"]},
      {patcher: "MinecraftServerWorkerPatcher", args: ["jar", "out"]});
  } else if (data.patchSet === "legacy-12111") {
    steps.push({patcher: "Minecraft12111BrowserPatcher", args: ["jar", "out"]},
      {patcher: "MinecraftServerWorkerPatcher", args: ["jar", "out"]});
  } else {
    throw new UsageError(`port/versions/${profile}.json: unknown patchSet ${data.patchSet}`);
  }
  for (const extra of data.extraPatchers ?? []) {
    steps.push({patcher: extra, args: ["jar", "out", "version"]});
  }
  return {steps, clientSha1: data.official?.clientSha1 ?? null};
}

/**
 * The same chain read from build-overlays.sh: the two leading patchers, the run_client_patcher
 * steps of each patchSet arm and the extraPatchers loop, with their argument order.
 */
export function buildOverlaysChain(root = repositoryRoot) {
  const text = readFileSync(join(root, "port/scripts/build-overlays.sh"), "utf8");
  const start = text.indexOf("\nrun_client_patcher() {");
  if (start < 0) throw new Error("build-overlays.sh: run_client_patcher() not found");
  const lines = text.slice(start).split("\n");
  const argName = (token) => ({'"$client_output"': "jar", '"$client_patch_classes"': "out",
    '"$version"': "version"})[token] ?? token;
  const chain = {leading: [], modern: [], "legacy-12111": [], extra: null};
  let section = "function";
  for (let index = 1; index < lines.length; index++) {
    const line = lines[index];
    if (section === "function") {
      if (line === "}") section = "leading";
      continue;
    }
    if (/^case "\$patch_set" in$/.test(line)) {
      section = "case";
      continue;
    }
    const arm = /^ {2}([a-z0-9.-]+)\)$/.exec(line);
    if (arm && (section === "case" || section in chain)) {
      section = arm[1];
      continue;
    }
    if (line === "esac") {
      section = "after";
      continue;
    }
    if (/^# WebGPU core/.test(line)) break;
    let command = line.trim();
    while (command.endsWith("\\") && index + 1 < lines.length) {
      command = command.slice(0, -1).trim() + " " + lines[++index].trim();
    }
    command = command.replace(/\s+/g, " ");
    let match = /^java .*dev\.gaius\.tools\.([A-Za-z0-9_]+) (.*)$/.exec(command);
    if (match && section === "leading") {
      chain.leading.push({patcher: match[1], args: match[2].split(" ").map(argName)});
      continue;
    }
    match = /^run_client_patcher (\S+) (.*)$/.exec(command);
    if (!match) continue;
    if (match[1] === '"$extra_patcher"') {
      chain.extra = match[2].split(" ").map(argName);
    } else if (section === "modern" || section === "legacy-12111") {
      chain[section].push({patcher: match[1], args: match[2].split(" ").map(argName)});
    }
  }
  return chain;
}

function compareChains(root = repositoryRoot) {
  const shell = buildOverlaysChain(root);
  const problems = [];
  const expected = {
    leading: [{patcher: "AbstractSpliteratorBrowserPatcher", args: ["jar", "out"]},
      {patcher: "MinecraftClientPatcher", args: ["jar", "out", "version"]}],
    modern: [{patcher: "MinecraftChunkDrawTelemetryPatcher", args: ["version", "jar", "out"]},
      {patcher: "Minecraft262BrowserPatcher", args: ["jar", "out", "version"]},
      {patcher: "MinecraftServerWorkerPatcher", args: ["jar", "out"]}],
    "legacy-12111": [{patcher: "Minecraft12111BrowserPatcher", args: ["jar", "out"]},
      {patcher: "MinecraftServerWorkerPatcher", args: ["jar", "out"]}],
    extra: ["jar", "out", "version"],
  };
  for (const key of Object.keys(expected)) {
    if (JSON.stringify(shell[key]) !== JSON.stringify(expected[key])) {
      problems.push(`build-overlays.sh ${key} steps ${JSON.stringify(shell[key])} differ from patchkit's `
        + JSON.stringify(expected[key]));
    }
  }
  return problems;
}

// ----------------------------------------------------------------------------- zip listing

/** The entry names of a zip file (central directory, zip64 aware). */
export function zipEntryNames(path) {
  const buffer = readFileSync(path);
  let eocd = -1;
  for (let index = buffer.length - 22; index >= Math.max(0, buffer.length - 65557); index--) {
    if (buffer.readUInt32LE(index) === 0x06054b50) {
      eocd = index;
      break;
    }
  }
  if (eocd < 0) throw new Error(`${path}: not a zip file`);
  let count = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);
  if ((count === 0xffff || offset === 0xffffffff) && eocd >= 20
      && buffer.readUInt32LE(eocd - 20) === 0x07064b50) {
    const zip64 = Number(buffer.readBigUInt64LE(eocd - 20 + 8));
    count = Number(buffer.readBigUInt64LE(zip64 + 32));
    offset = Number(buffer.readBigUInt64LE(zip64 + 48));
  }
  const names = new Set();
  let position = offset;
  for (let index = 0; index < count; index++) {
    if (buffer.readUInt32LE(position) !== 0x02014b50) throw new Error(`${path}: bad central directory`);
    const nameLength = buffer.readUInt16LE(position + 28);
    const extraLength = buffer.readUInt16LE(position + 30);
    const commentLength = buffer.readUInt16LE(position + 32);
    names.add(buffer.toString("utf8", position + 46, position + 46 + nameLength));
    position += 46 + nameLength + extraLength + commentLength;
  }
  return names;
}

// ------------------------------------------------------------------------------- manifests

function sorted(values) {
  return [...values].sort();
}

/**
 * Builds a manifest from the run-log records of one profile and target.
 * records: the JSON objects PatchRegistry appended (in order); steps: [{patcher, registry,
 * summary[], status}]; vanilla: the entry names of the vanilla client jar (rewrites vs adds).
 */
export function buildManifest({profile, target, clientSha1 = null, steps, records, vanilla}) {
  const patches = new Map();
  const problems = [];
  for (const record of records) {
    if (record.profile && record.profile !== profile) {
      problems.push(`${record.id}: recorded for profile ${record.profile}, expected ${profile}`);
    }
    if (record.target && record.target !== target) {
      problems.push(`${record.id}: recorded for target ${record.target}, expected ${target}`);
    }
    const entry = {
      id: record.id,
      patcher: record.patcher ?? null,
      kind: record.kind ?? null,
      targets: record.targets ?? null,
      profiles: record.profiles ?? null,
      outcome: record.outcome,
    };
    if (record.reason) entry.reason = record.reason;
    if (record.parent) entry.parent = record.parent;
    if (record.parts?.length) entry.parts = sorted(record.parts);
    if (record.classes?.length && record.outcome !== "DISABLED" && record.outcome !== "FAILED") {
      const rewrites = record.classes.filter((name) => vanilla.has(name));
      const adds = record.classes.filter((name) => !vanilla.has(name));
      if (rewrites.length) entry.rewrites = sorted(rewrites);
      if (adds.length) entry.adds = sorted(adds);
    }
    if (record.absent?.length) entry.absent = sorted(record.absent);
    if (record.error) entry.error = record.error;
    if (record.notApplicableDeclared) entry.notApplicableDeclared = true;
    if (record.wholeClass) entry.wholeClass = true;
    const methodTargets = new Map();
    for (const row of record.methodTargets ?? []) {
      const key = `${row.className}.${row.method}${row.descriptor}`;
      if (methodTargets.has(key) && JSON.stringify(methodTargets.get(key)) !== JSON.stringify(row)) {
        problems.push(`${record.id}: conflicting method fingerprints for ${key}`);
      }
      methodTargets.set(key, row);
    }
    entry.methodTargets = [...methodTargets.values()]
      .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right), "en"));
    if (record.undeclared || !record.kind) entry.undeclared = true;
    if (patches.has(record.id)) {
      const previous = patches.get(record.id);
      if (JSON.stringify(previous) !== JSON.stringify(entry)) {
        problems.push(`${record.id}: registered twice with different results (${previous.outcome}, ${entry.outcome})`);
      }
      continue;
    }
    patches.set(record.id, entry);
  }
  const list = [...patches.values()].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const outcomes = Object.fromEntries(OUTCOMES.map((outcome) => [outcome, 0]));
  const kinds = Object.fromEntries(KINDS.map((kind) => [kind, {}]));
  for (const entry of list) {
    outcomes[entry.outcome] = (outcomes[entry.outcome] ?? 0) + 1;
    if (entry.kind) kinds[entry.kind][entry.outcome] = (kinds[entry.kind][entry.outcome] ?? 0) + 1;
  }
  return {
    manifest: {
      schema: 2,
      targetHashAlgorithm: "sha256-asm-symbolic-v1",
      profile,
      target,
      clientSha1,
      generator: "port/tools/patchkit/patchkit.mjs check (PatchRegistry run log)",
      steps,
      outcomes,
      kinds,
      patches: list,
    },
    problems,
  };
}

function identity(entry) {
  return JSON.stringify({kind: entry.kind, targets: entry.targets, profiles: entry.profiles,
    outcome: entry.outcome, reason: entry.reason ?? null, parent: entry.parent ?? null,
    parts: entry.parts ?? [], rewrites: entry.rewrites ?? [], adds: entry.adds ?? [],
    absent: entry.absent ?? [], undeclared: entry.undeclared ?? false,
    wholeClass: entry.wholeClass ?? false, notApplicableDeclared: entry.notApplicableDeclared ?? false});
}

function listChange(name, before = [], after = []) {
  const added = after.filter((value) => !before.includes(value));
  const removed = before.filter((value) => !after.includes(value));
  if (!added.length && !removed.length) return null;
  return `${name} ${removed.map((value) => `-${value}`).concat(added.map((value) => `+${value}`)).join(" ")}`;
}

function describeChange(before, after) {
  const changes = [];
  for (const field of ["kind", "profiles", "outcome", "reason", "parent", "wholeClass", "notApplicableDeclared"]) {
    if ((before[field] ?? null) !== (after[field] ?? null)) {
      changes.push(`${field} ${before[field] ?? "-"} -> ${after[field] ?? "-"}`);
    }
  }
  if (JSON.stringify(before.targets) !== JSON.stringify(after.targets)) {
    changes.push(`targets ${(before.targets ?? []).join(",")} -> ${(after.targets ?? []).join(",")}`);
  }
  for (const field of ["rewrites", "adds", "absent", "parts"]) {
    const change = listChange(field, before[field], after[field]);
    if (change) changes.push(change);
  }
  if ((before.undeclared ?? false) !== (after.undeclared ?? false)) {
    changes.push(after.undeclared ? "now undeclared" : "now declared");
  }
  return changes.join("; ");
}

function methodName(id) {
  return id.slice(id.lastIndexOf(".") + 1);
}

/** The lock diff of two manifests (see the header). */
export function diffManifests(previous, current, acknowledgements = []) {
  const before = new Map(previous.patches.map((entry) => [entry.id, entry]));
  const after = new Map(current.patches.map((entry) => [entry.id, entry]));
  const result = Object.fromEntries(CATEGORIES.map((category) => [category, []]));
  for (const [id, entry] of after) {
    const old = before.get(id);
    if (!old) continue;
    const failedNow = entry.outcome === "FAILED" || entry.outcome === "DISABLED";
    const targets = targetChanges(old, entry, current, acknowledgements);
    if (failedNow) {
      result.failed.push({id, kind: entry.kind, from: old.outcome, to: entry.outcome,
        error: entry.error ?? null, ...targets});
    } else if (identity(old) === identity(entry) && !targets.changed) {
      result.unchanged.push({id, kind: entry.kind});
    } else {
      result["target-changed"].push({id, kind: entry.kind,
        change: [describeChange(old, entry), targets.description].filter(Boolean).join("; "),
        metadataChanged: identity(old) !== identity(entry), ...targets});
    }
  }
  const gone = [...before.keys()].filter((id) => !after.has(id));
  const added = [...after.keys()].filter((id) => !before.has(id));
  const matched = new Set();
  for (const id of gone) {
    const old = before.get(id);
    const candidates = added.filter((other) => !matched.has(other)).map((other) => after.get(other))
      .filter((entry) => entry.kind === old.kind && entry.patcher === old.patcher);
    const sameClasses = candidates.filter((entry) => (old.rewrites?.length ?? 0) > 0
      && JSON.stringify(entry.rewrites ?? []) === JSON.stringify(old.rewrites));
    const sameName = candidates.filter((entry) => methodName(entry.id) === methodName(old.id));
    const pick = sameClasses.length === 1 ? sameClasses[0] : sameName.length === 1 ? sameName[0] : null;
    if (pick) {
      matched.add(pick.id);
      const change = describeChange({...old, id: pick.id}, pick);
      const targets = targetChanges(old, pick, current, acknowledgements);
      if (pick.outcome === "FAILED" || pick.outcome === "DISABLED") {
        result.failed.push({id: pick.id, kind: pick.kind, from: old.outcome, to: pick.outcome,
          ...targets});
      } else {
        result.renamed.push({id: pick.id, from: id, kind: pick.kind,
          change: [change, targets.description].filter(Boolean).join("; ") || null, ...targets});
      }
    } else {
      result.gone.push({id, kind: old.kind, outcome: old.outcome,
        ...targetChanges(old, {id, methodTargets: []}, current, acknowledgements)});
    }
  }
  for (const id of added) {
    if (!matched.has(id)) {
      const entry = after.get(id);
      const targets = previous.schema === 2
        ? targetChanges({methodTargets: []}, entry, current, acknowledgements) : {};
      const initializer = {staticWritesChanged: targets.staticWritesChanged === true};
      if (entry.outcome === "FAILED" || entry.outcome === "DISABLED") {
        result.failed.push({id, kind: entry.kind, from: null, to: entry.outcome, ...initializer});
      } else {
        result.new.push({id, kind: entry.kind, outcome: entry.outcome, ...initializer});
      }
    }
  }
  const changes = [...result["target-changed"], ...result.renamed, ...result.gone];
  const hardBlocking = Object.values(result).flat()
    .filter((entry) => entry.staticWritesChanged).map((entry) => entry.id);
  const blocking = result.failed.filter((entry) => entry.kind === "REQUIRED")
    .map((entry) => entry.id);
  blocking.push(...hardBlocking, ...changes.filter((entry) => entry.needsAcknowledgement
    && !entry.acknowledged).map((entry) => entry.id));
  const review = [...result["target-changed"], ...result.gone, ...result.renamed]
    .filter((entry) => entry.kind === "REQUIRED").map((entry) => entry.id);
  return {...result, blocking: sorted(blocking), hardBlocking: sorted(hardBlocking), review: sorted(review)};
}

export function checkStatus(failures, differences, writeBlocks, options) {
  return failures > 0 || (writeBlocks > 0 && (options.write || !options.warn))
    || (differences > 0 && !options.warn) ? 1 : 0;
}

export function formatDiff(diff, label) {
  const lines = [];
  for (const category of CATEGORIES.slice(1)) {
    for (const entry of diff[category]) {
      let text = `${category} ${entry.id}`;
      if (entry.kind) text += ` (${entry.kind})`;
      if (category === "renamed") text += ` from ${entry.from}${entry.change ? `: ${entry.change}` : ""}`;
      if (category === "target-changed") text += `: ${entry.change}`;
      if (category === "failed") text += `: ${entry.from} -> ${entry.to}${entry.error ? `: ${entry.error}` : ""}`;
      if (category === "new" || category === "gone") text += ` ${entry.outcome}`;
      lines.push(`  ${text}`);
    }
  }
  const counts = CATEGORIES.map((category) => `${category}=${diff[category].length}`).join(" ");
  lines.push(`LOCK_DIFF ${label} ${counts} blocking=${diff.blocking.length} review=${diff.review.length}`);
  return lines.join("\n");
}

export function diffIsClean(diff) {
  return CATEGORIES.slice(1).every((category) => diff[category].length === 0
    || (category === "target-changed" && diff[category].every((entry) => entry.acknowledged
      && !entry.staticWritesChanged && !entry.metadataChanged)));
}

// --------------------------------------------------------------------------------- check

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const candidate = join(nativePath(home), "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

function mavenRepository() {
  const configured = process.env.GAIUS_MAVEN_REPOSITORY;
  if (configured) {
    const path = nativePath(configured);
    return isAbsolute(path) ? path : join(repositoryRoot, path);
  }
  return join(homedir(), ".m2/repository");
}

function authlibJar(profile, work = join(repositoryRoot, "port/work")) {
  const base = join(work, profile, "libraries/com/mojang/authlib");
  if (!existsSync(base)) return null;
  for (const version of readdirSync(base).sort().reverse()) {
    const jar = join(base, version, `authlib-${version}.jar`);
    if (existsSync(jar)) return jar;
  }
  return null;
}

function readRunLog(path) {
  if (!existsSync(path)) return [];
  return readFileSync(path, "utf8").split("\n").filter(Boolean).map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`${path}:${index + 1}: ${error.message}`);
    }
  });
}

function compileTools(classes) {
  const repository = mavenRepository();
  const asm = join(repository, `org/ow2/asm/asm/${ASM_VERSION}/asm-${ASM_VERSION}.jar`);
  const asmTree = join(repository, `org/ow2/asm/asm-tree/${ASM_VERSION}/asm-tree-${ASM_VERSION}.jar`);
  for (const jar of [asm, asmTree]) {
    if (!existsSync(jar)) throw new UsageError(`missing ${jar} (build-overlays.sh bootstraps ASM ${ASM_VERSION})`);
  }
  // The TeaVM core patcher compiles against teavm-core (its WasmGC resource intrinsic
  // helper), as in build-overlays.sh.
  const teaVMVersion = JSON.parse(readFileSync(join(repositoryRoot, "port/config.json"), "utf8")).teaVMVersion;
  const teaVMCore = join(repository, `org/teavm/teavm-core/${teaVMVersion}/teavm-core-${teaVMVersion}.jar`);
  if (!existsSync(teaVMCore)) throw new UsageError(`missing ${teaVMCore} (run a TeaVM build once to fetch TeaVM ${teaVMVersion})`);
  mkdirSync(classes, {recursive: true});
  const sources = readdirSync(toolsPackage).filter((name) => name.endsWith(".java"))
    .map((name) => join(toolsPackage, name));
  const result = spawnSync(jdkTool("javac"), ["-J-Duser.language=en", "-J-Xmx3g", "--release", "21",
    "-proc:none", "-nowarn", "-classpath", [asm, asmTree, teaVMCore].join(delimiter), "-sourcepath", toolsRoot,
    "-d", classes, ...sources], {encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
  if (result.status !== 0) {
    throw new UsageError(`javac of port/tools failed:\n${result.stdout}${result.stderr}`);
  }
  return [classes, asm, asmTree, teaVMCore].join(delimiter);
}

function seedClientPatchSources(jar, work) {
  const source = join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserClassInitialization.java");
  const classes = join(work, "client-source-classes");
  mkdirSync(classes, {recursive: true});
  const compiled = spawnSync(jdkTool("javac"), ["-J-Xmx512m", "--release", "21", "-proc:none",
    "-d", classes, source], {encoding: "utf8"});
  if (compiled.status !== 0) {
    throw new UsageError(`javac of client patch sources failed:\n${compiled.stdout}${compiled.stderr}`);
  }
  const updated = spawnSync(jdkTool("jar"), ["--update", "--file", jar, "-C", classes,
    "dev/gaius/browser/BrowserClassInitialization.class"], {encoding: "utf8"});
  if (updated.status !== 0) {
    throw new UsageError(`jar update with client patch sources failed:\n${updated.stdout}${updated.stderr}`);
  }
}

function bytecodeTest() {
  const scratch = mkdtempSync(join(tmpdir(), "gaius-patchkit-bytecode-"));
  try {
    const classes = join(scratch, "classes");
    const classpath = compileTools(classes);
    const sources = [
      join(repositoryRoot, "port/tools/src/test/java/dev/gaius/tools/PatchTargetsTest.java"),
      join(repositoryRoot, "port/tools/src/test/java/dev/gaius/tools/LoadingPresentationPatchesTest.java"),
      join(repositoryRoot, "port/tools/src/test/java/dev/gaius/tools/TitleScreenIconRowPatchTest.java"),
      join(repositoryRoot, "port/tools/src/test/java/dev/gaius/tools/BrowserTitleLayoutTest.java"),
      join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserTitleLayout.java"),
    ];
    const compiled = spawnSync(jdkTool("javac"), ["-J-Xmx3g", "--release", "21", "-classpath", classpath,
      "-d", classes, ...sources], {encoding: "utf8"});
    if (compiled.status !== 0) throw new UsageError(`bytecode tests did not compile: ${compiled.stderr}`);
    const tested = spawnSync(jdkTool("java"), ["-Xmx3g", "-classpath", classpath,
      "dev.gaius.tools.PatchTargetsTest", scratch], {encoding: "utf8"});
    process.stdout.write(tested.stdout ?? "");
    process.stderr.write(tested.stderr ?? "");
    return tested.status === 0 ? 0 : 1;
  } finally {
    rmSync(scratch, {recursive: true, force: true});
  }
}

function runChain({profile, target, classpath, scratch, options}) {
  const {steps, clientSha1} = chainFor(profile, repositoryRoot, options.profileFile);
  const vanillaJar = options.jars?.[profile] ?? join(options.work, profile, "client-named.jar");
  if (!existsSync(vanillaJar)) {
    throw new UsageError(`missing ${vanillaJar}: run fetch-version.sh and remap-client.sh for ${profile}`);
  }
  const work = join(scratch, `${profile}-${target}`);
  rmSync(work, {recursive: true, force: true});
  mkdirSync(join(work, "out"), {recursive: true});
  const jar = join(work, "client.jar");
  copyFileSync(vanillaJar, jar);
  seedClientPatchSources(jar, work);
  const runLog = join(work, "patch-run.jsonl");
  const authlib = authlibJar(profile, options.work);
  const profileBringupList = join(repositoryRoot, "port/tools/bringup", `${profile}.txt`);
  // Draft profile-file rehearsals intentionally have no checked-in list. Reuse the
  // reviewed predecessor list for the isolated run; the manifest still records every
  // current failure and no new profile is installed by this path.
  const bringupList = existsSync(profileBringupList) ? profileBringupList
    : options.profileFile ? join(repositoryRoot, "port/tools/bringup", "26.3.txt") : profileBringupList;
  const properties = [
    `-Dgaius.teavm.target=${target}`,
    `-Dgaius.profile=${profile}`,
    `-Dgaius.bringup=${options.bringup ? "true" : "false"}`,
    "-Dgaius.patch.check=true",
    `-Dgaius.patch.runlog=${runLog}`,
    `-Dgaius.patch.vanilla=${vanillaJar}`,
    `-Dgaius.patch.input=${jar}`,
  ];
  if (existsSync(bringupList)) properties.push(`-Dgaius.bringup.list=${bringupList}`);
  if (authlib) properties.push(`-Dgaius.authlib.jar=${authlib}`);
  if (options.fail) properties.push(`-Dgaius.patch.fail=${options.fail}`);
  const env = {...process.env};
  for (const name of ["GAIUS_BRINGUP", "GAIUS_BRINGUP_LIST", "GAIUS_PROFILE", "GAIUS_TEAVM_TARGET",
    "GAIUS_PATCH_VERBOSE", "GAIUS_PATCH_STRICT", "GAIUS_PATCH_CHECK", "GAIUS_PATCH_RUN_LOG",
    "GAIUS_PATCH_FAIL", "GAIUS_AUTHLIB_JAR", "GAIUS_USAGE_LOCK"]) {
    delete env[name];
  }
  if (options.bringup) env.GAIUS_BRINGUP = "1";
  // The patchers read port/versions/<id>.json (VersionProfile) and run in the scratch directory,
  // outside the checkout: name the profile file as version-profile.sh does for a real build.
  env.GAIUS_VERSION_PROFILE = join(repositoryRoot, "port/versions", `${profile}.json`);
  if (options.profileFile) {
    const data = JSON.parse(readFileSync(options.profileFile, "utf8"));
    const port = join(work, "profile/port");
    env.GAIUS_VERSION_PROFILE = join(port, "versions", `${profile}.json`);
    mkdirSync(dirname(env.GAIUS_VERSION_PROFILE), {recursive: true});
    copyFileSync(options.profileFile, env.GAIUS_VERSION_PROFILE);
    if (data.renderSeam !== "none") {
      const manifest = join(port, "mc/seams", data.renderSeam, "seam.json");
      mkdirSync(dirname(manifest), {recursive: true});
      copyFileSync(join(repositoryRoot, "port/mc/seams", data.renderSeam, "seam.json"), manifest);
    }
  }
  if (options.usageLock) env.GAIUS_USAGE_LOCK = options.usageLock;
  const stepResults = [];
  let crashed = null;
  for (const step of steps) {
    const args = step.args.map((arg) => ({jar, out: join(work, "out"), version: profile})[arg]);
    const result = spawnSync(jdkTool("java"), ["-Xmx2g", "-Duser.language=en", "-classpath", classpath,
      ...properties, `dev.gaius.tools.${step.patcher}`, ...args],
    {cwd: work, env, encoding: "utf8", maxBuffer: 256 * 1024 * 1024, timeout: 600_000});
    const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
    writeFileSync(join(work, `${step.patcher}.log`), output);
    const summary = (result.stdout ?? "").split(/\r?\n/).filter((line) => line.startsWith("PATCH_SUMMARY "));
    stepResults.push({patcher: step.patcher, registry: summary.length > 0,
      ...(summary.length ? {summary} : {}), status: result.status === 0 ? "ok" : "crashed"});
    if (result.status !== 0) {
      crashed = `${step.patcher} exited with ${result.status ?? result.signal}:\n`
        + output.split(/\r?\n/).filter(Boolean).slice(-25).join("\n");
      break;
    }
    const update = spawnSync(jdkTool("jar"), ["--update", "--file", jar, "-C", join(work, "out"), "."],
      {encoding: "utf8", timeout: 300_000});
    if (update.status !== 0) {
      throw new UsageError(`jar --update failed for ${profile} ${target}: ${update.stderr}`);
    }
  }
  const records = readRunLog(runLog);
  const vanilla = zipEntryNames(vanillaJar);
  const built = buildManifest({profile, target, clientSha1, steps: stepResults, records, vanilla});
  built.problems.push(...validateTargets(built.manifest));
  return {...built, crashed, work};
}

function parseCheckArguments(argv) {
  const options = {profiles: [], targets: [], out: null, write: false, compare: false, warn: false,
    bringup: false, fail: null, keep: false, quiet: false, jars: {}, profileFile: null,
    work: join(repositoryRoot, "port/work"), scratch: null, usageLock: null};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    const value = () => {
      const next = argv[++index];
      if (next === undefined || next.startsWith("--")) throw new UsageError(`${argument} needs a value`);
      return next;
    };
    switch (argument) {
      case "--profile": options.profiles.push(value()); break;
      case "--target": options.targets.push(value()); break;
      case "--out": options.out = resolve(nativePath(value())); break;
      case "--profile-file": options.profileFile = resolve(nativePath(value())); break;
      case "--work": options.work = resolve(nativePath(value())); break;
      case "--scratch": options.scratch = resolve(nativePath(value())); break;
      case "--usage-lock": options.usageLock = resolve(nativePath(value())); break;
      case "--write": options.write = true; break;
      case "--compare": options.compare = true; break;
      case "--warn": options.warn = true; break;
      case "--bringup": options.bringup = true; break;
      case "--fail": options.fail = value(); break;
      case "--keep": options.keep = true; break;
      case "--quiet": options.quiet = true; break;
      case "--jar": {
        const [profile, path] = value().split("=");
        if (!profile || !path) throw new UsageError("--jar needs <profile>=<path>");
        options.jars[profile] = resolve(nativePath(path));
        break;
      }
      default: throw new UsageError(`unknown argument ${argument}`);
    }
  }
  if (options.profileFile) {
    const data = JSON.parse(readFileSync(options.profileFile, "utf8"));
    if (options.profiles.length === 0) options.profiles = [data.id];
    if (options.profiles.length !== 1 || options.profiles[0] !== data.id || options.write || options.compare) {
      throw new UsageError("--profile-file needs its single profile and forbids --write/--compare");
    }
  }
  if (options.profiles.length === 0) {
    options.profiles = readdirSync(join(repositoryRoot, "port/versions"))
      .filter((name) => name.endsWith(".json")).map((name) => name.slice(0, -5)).sort(
        (a, b) => compareVersions(leadingVersion(a), leadingVersion(b)));
  }
  if (options.targets.length === 0) options.targets = [...TARGETS];
  for (const target of options.targets) {
    if (!TARGETS.includes(target)) throw new UsageError(`unknown target ${target}`);
  }
  for (const profile of options.profiles) {
    if (!/^[0-9A-Za-z][0-9A-Za-z.+-]*$/.test(profile)
        || !options.profileFile && !existsSync(join(repositoryRoot, "port/versions", `${profile}.json`))) {
      throw new UsageError(`unknown profile ${profile} (no port/versions/${profile}.json)`);
    }
  }
  if (options.warn && !options.compare) throw new UsageError("--warn needs --compare");
  return options;
}

function writeJson(path, value) {
  mkdirSync(dirname(path), {recursive: true});
  writeFileSync(path, JSON.stringify(value, null, 2) + "\n");
}

function check(argv) {
  const options = parseCheckArguments(argv);
  if (options.scratch) mkdirSync(options.scratch, {recursive: true});
  const scratch = mkdtempSync(join(options.scratch ?? tmpdir(), "gaius-patchkit-"));
  const out = options.out ?? join(scratch, "manifests");
  const outLabel = options.out ?? "-";
  let failures = 0;
  let differences = 0;
  let writeBlocks = 0;
  const pendingWrites = [];
  const acknowledgements = readAcknowledgements(repositoryRoot);
  try {
    const started = Date.now();
    const classpath = compileTools(join(scratch, "classes"));
    for (const profile of options.profiles) {
      for (const target of options.targets) {
        const begun = Date.now();
        const run = runChain({profile, target, classpath, scratch, options});
        const {manifest} = run;
        if (!options.quiet) {
          for (const entry of manifest.patches) {
            const reason = entry.reason ? ` ${entry.reason}` : "";
            const error = entry.error ? ` ${entry.error}` : "";
            console.log(`PATCHKIT ${profile} ${target} ${entry.outcome} ${entry.kind ?? "UNDECLARED"} `
              + `${entry.id}${reason}${error}`);
          }
        }
        for (const problem of run.problems) {
          console.log(`ERROR ${profile} ${target}: ${problem}`);
          failures++;
        }
        const undeclared = manifest.patches.filter((entry) => entry.undeclared).map((entry) => entry.id);
        if (undeclared.length) {
          console.log(`WARNING ${profile} ${target}: registered without a spec: ${undeclared.join(", ")}`);
        }
        const required = manifest.patches.filter((entry) => entry.outcome === "FAILED");
        failures += required.length;
        if (run.crashed) {
          console.log(`ERROR ${profile} ${target}: ${run.crashed}`);
          failures++;
        }
        const path = join(out, profile, `patch-manifest.${target}.json`);
        writeJson(path, manifest);
        const committed = join(repositoryRoot, "port/mc/profiles", profile, `patch-manifest.${target}.json`);
        let diff;
        if (existsSync(committed)) {
          const previous = JSON.parse(readFileSync(committed, "utf8"));
          if (previous.schema === 2) {
            for (const problem of validateTargets(previous)) {
              console.log(`ERROR ${profile} ${target} checked-in lock: ${problem}`);
              failures++;
            }
          }
          diff = diffManifests(previous, manifest, acknowledgements);
          writeBlocks += diff.blocking.length;
          failures += diff.hardBlocking.length;
        }
        if (options.compare) {
          if (!existsSync(committed)) {
            console.log(`${options.warn ? "WARNING" : "ERROR"} ${profile} ${target}: no checked-in `
              + `port/mc/profiles/${profile}/patch-manifest.${target}.json`);
            differences++;
          } else {
            if (!diffIsClean(diff)) {
              differences++;
              console.log((options.warn ? "WARNING " : "")
                + formatDiff(diff, `${profile} ${target} (checked-in -> generated)`));
            }
          }
        }
        if (options.write) {
          pendingWrites.push([committed, manifest]);
          if (diff?.blocking.length) console.log(`ERROR lock write blocked: ${diff.blocking.join(", ")}`);
        } else if (!options.compare && diff?.blocking.length) {
          console.log(`ERROR target review blocked: ${diff.blocking.join(", ")}`);
        }
        const counts = OUTCOMES.map((outcome) => `${outcome}=${manifest.outcomes[outcome]}`).join(" ");
        console.log(`PATCHKIT_RESULT ${profile} ${target} ${counts} steps=${manifest.steps.length} `
          + `${run.crashed ? "CRASHED" : "complete"} ${((Date.now() - begun) / 1000).toFixed(1)}s`);
        if (!options.keep) rmSync(run.work, {recursive: true, force: true});
      }
    }
    const status = checkStatus(failures, differences, writeBlocks, options);
    if (options.write && status === 0) {
      for (const [path, manifest] of pendingWrites) writeJson(path, manifest);
    }
    console.log(`PATCHKIT_CHECK ${status === 0 ? "PASS" : "FAIL"} failures=${failures} `
      + `manifestDifferences=${differences} manifests=${outLabel} ${((Date.now() - started) / 1000).toFixed(1)}s`);
    return status;
  } finally {
    if (!options.keep) {
      rmSync(scratch, {recursive: true, force: true});
    } else {
      console.log(`PATCHKIT_SCRATCH ${scratch}`);
    }
  }
}

// ------------------------------------------------------------------------------------ lint

export function lint(root = repositoryRoot) {
  const problems = [];
  const registrations = sourceRegistrations(root);
  const classification = readClassification(root);
  const ids = new Map();
  for (const registration of registrations) {
    const where = `${registration.file}:${registration.line}`;
    if (!["run", "runPart"].includes(registration.method)) {
      problems.push(`${where}: PatchRegistry.${registration.method} no longer exists; declare the target in the spec`);
      continue;
    }
    if (registration.spec === null) {
      problems.push(`${where}: ${registration.id} is registered without a spec (kind, targets, profiles)`);
      continue;
    }
    let spec;
    try {
      spec = parseSpec(registration.spec);
    } catch (error) {
      problems.push(`${where}: ${error.message}`);
      continue;
    }
    // Every patch declares where it runs (D16): a new profile then only touches the
    // per-generation tables (port/versions patchkitNotApplicable), never a spec's defaults.
    if (!specNamesTargets(registration.spec)) {
      problems.push(registration.domain
        ? `${where}: ${registration.id} is implemented in the ${registration.domain} domain package`
          + " and must name its targets (js, wasmgc) in its spec"
        : `${where}: ${registration.id} must name its targets (js, wasmgc) in its spec`);
    }
    const previous = ids.get(registration.id);
    if (previous && JSON.stringify(previous.spec) !== JSON.stringify(spec)) {
      problems.push(`${where}: ${registration.id} is registered twice with different specs`);
    }
    if (previous && previous.domain !== (registration.domain ?? null)) {
      problems.push(`${where}: ${registration.id} is registered twice with code in different domains`);
    }
    ids.set(registration.id, {spec, where, domain: registration.domain ?? null,
      namesTargets: specNamesTargets(registration.spec) && (previous?.namesTargets ?? true)});
    const row = classification.get(registration.id);
    if (!row) {
      problems.push(`${where}: ${registration.id} has no row in port/tools/patchkit/classification.tsv`);
    } else if (row.kind !== spec.kind) {
      problems.push(`${where}: ${registration.id} is ${spec.kind} but classification.tsv says ${row.kind}`);
    }
    if (row?.wasm === "wasm-only" && JSON.stringify(spec.targets) !== JSON.stringify(["wasmgc"])) {
      problems.push(`${where}: ${registration.id} is wasm-only in classification.tsv but targets ${spec.targets}`);
    }
  }
  for (const id of classification.keys()) {
    if (!ids.has(id)) problems.push(`classification.tsv lists ${id}, which port/tools does not register`);
  }
  const versionFiles = existsSync(join(root, "port/versions")) ? readdirSync(join(root, "port/versions")) : [];
  for (const file of versionFiles.filter((name) => name.endsWith(".json"))) {
    const profile = file.slice(0, -5);
    const declarations = JSON.parse(readFileSync(join(root, "port/versions", file), "utf8"))
      .patchkitNotApplicable ?? {};
    if (typeof declarations !== "object" || declarations === null || Array.isArray(declarations)) {
      problems.push(`${profile}: patchkitNotApplicable must be an object`);
      continue;
    }
    for (const [id, value] of Object.entries(declarations)) {
      const declaration = notApplicableDeclaration(value);
      if (!ids.get(id)?.spec.notApplicable || !declaration) {
        problems.push(`${profile}: ${id} needs a NOT_APPLICABLE spec and a nonempty reason`
          + " (or {targets, reason} with targets among js, wasmgc)");
      } else if (declaration.targets.some((target) => !ids.get(id).spec.targets.includes(target))) {
        problems.push(`${profile}: ${id} declares targets ${declaration.targets} outside its spec (${ids.get(id).spec.targets})`);
      }
    }
  }
  try { readAcknowledgements(root); } catch (error) { problems.push(error.message); }
  for (const [target, other] of [["js", "wasmgc"], ["wasmgc", "js"]]) {
    // target-skips/<target>.txt: the patches a <target> build skips (their spec leaves it out).
    const listed = readTargetSkips(target, root);
    const expected = [...ids].filter(([, value]) => !value.spec.targets.includes(target)).map(([id]) => id);
    for (const id of expected) {
      if (!listed.has(id)) problems.push(`port/tools/target-skips/${target}.txt must list ${id} (targets ${other} only)`);
    }
    for (const [id, entry] of listed) {
      const value = ids.get(id);
      if (!value) problems.push(`port/tools/target-skips/${target}.txt lists ${id}, which port/tools does not register`);
      else if (value.spec.targets.includes(target)) problems.push(`port/tools/target-skips/${target}.txt lists ${id}, whose spec includes ${target}`);
      else if (!entryCoversSpec(entry.profiles, value.spec.profiles)) {
        problems.push(`port/tools/target-skips/${target}.txt: ${id} profiles ${entry.profiles} do not cover its spec (${value.spec.profiles})`);
      }
    }
  }
  problems.push(...compareChains(root));
  const values = [...ids.values()];
  return {problems, registrations: ids.size,
    inDomains: values.filter((value) => value.domain).length,
    targetsUndeclared: values.filter((value) => !value.namesTargets).length};
}

// ------------------------------------------------------------------------------- self-test

function selfTest() {
  const assert = (condition, message) => {
    if (!condition) throw new Error(`self-test: ${message}`);
  };
  // Specs.
  assert(JSON.stringify(parseSpec("PERF js")) === JSON.stringify({kind: "PERF", targets: ["js"], profiles: "*"}), "PERF js");
  assert(parseSpec("REQUIRED >=9.2 8.21.11").profiles === ">=9.2,8.21.11", "profile union");
  // patchkitNotApplicable values: a reason (every target) or {targets, reason}.
  assert(JSON.stringify(notApplicableDeclaration("No seam")) === JSON.stringify({reason: "No seam", targets: ["js", "wasmgc"]}),
    "a bare reason covers every target");
  assert(JSON.stringify(notApplicableDeclaration({targets: ["js"], reason: "frozen"}))
    === JSON.stringify({reason: "frozen", targets: ["js"]}), "a per-target declaration");
  for (const bad of ["", " ", {targets: [], reason: "x"}, {targets: ["wasm"], reason: "x"}, {targets: ["js", "js"], reason: "x"},
    {targets: ["js"]}, {targets: ["js"], reason: "x", extra: 1}, ["js"], null]) {
    assert(notApplicableDeclaration(bad) === null, `malformed declaration ${JSON.stringify(bad)}`);
  }
  for (const bad of ["", "SOMETIMES", "PERF js js", "PERF >9.2", "PERF *,9.2", "PERF wasm"]) {
    let threw = false;
    try {
      parseSpec(bad);
    } catch {
      threw = true;
    }
    assert(threw, `spec '${bad}' must be refused`);
  }
  // Synthetic ids of the same shapes (a dotted release, a later snapshot, a three-part LTS).
  assert(coversProfile(">=9.3", "9.4-snapshot-2") && !coversProfile(">=9.3", "9.2"), ">= bound");
  assert(coversProfile("<=9.2", "8.21.11") && coversProfile("8.21.11,>=9.3", "9.3")
    && !coversProfile("8.21.11,>=9.3", "9.2"), "<= bound and union");
  // Manifest generation from run-log records.
  const vanilla = new Set(["a/A.class", "a/B.class"]);
  const records = [
    {patcher: "P", id: "P.one", kind: "REQUIRED", targets: ["js", "wasmgc"], profiles: "*", outcome: "APPLIED",
      classes: ["a/A.class", "dev/gaius/X.class"], profile: "9.1", target: "js"},
    {patcher: "P", id: "P.two.part", kind: "PERF", targets: ["js", "wasmgc"], profiles: "*", outcome: "NOT_APPLICABLE",
      reason: "dropped", parent: "P.two", absent: ["a/Gone"], profile: "9.1", target: "js"},
    {patcher: "P", id: "P.two", kind: "PERF", targets: ["js", "wasmgc"], profiles: "*", outcome: "NOT_APPLICABLE",
      reason: "dropped", parts: ["P.two.part"], classes: [], profile: "9.1", target: "js"},
    {patcher: "P", id: "P.three", kind: "TELEMETRY", targets: ["js", "wasmgc"], profiles: "*", outcome: "DISABLED",
      reason: "restored", classes: ["a/B.class"], error: "IllegalStateException: gone", profile: "9.1", target: "js"},
    {patcher: "P", id: "P.four", kind: "PERF", targets: ["wasmgc"], profiles: ">=9.1", outcome: "NOT_APPLICABLE",
      reason: "target", profile: "9.1", target: "js"},
  ];
  const {manifest, problems} = buildManifest({profile: "9.1", target: "js", steps: [], records, vanilla});
  assert(problems.length === 0, `manifest problems ${problems}`);
  assert(manifest.patches.map((entry) => entry.id).join() === "P.four,P.one,P.three,P.two,P.two.part", "sorted ids");
  const one = manifest.patches.find((entry) => entry.id === "P.one");
  assert(JSON.stringify(one.rewrites) === '["a/A.class"]' && JSON.stringify(one.adds) === '["dev/gaius/X.class"]',
    "rewrites and adds");
  assert(!manifest.patches.find((entry) => entry.id === "P.three").rewrites, "a disabled patch rewrites nothing");
  assert(manifest.outcomes.APPLIED === 1 && manifest.outcomes.NOT_APPLICABLE === 3 && manifest.outcomes.DISABLED === 1,
    "outcome counts");
  const twice = buildManifest({profile: "9.1", target: "js", steps: [], vanilla,
    records: [records[0], {...records[0], outcome: "NOT_APPLICABLE"}]});
  assert(twice.problems.length === 1, "an id with two different results is a problem");
  // Lock diff: all six categories.
  const entry = (id, extra = {}) => ({id, patcher: "P", kind: "PERF", targets: ["js", "wasmgc"], profiles: "*",
    outcome: "APPLIED", rewrites: [`a/${id}.class`], ...extra});
  const old = {patches: [entry("P.same"), entry("P.oldName", {rewrites: ["a/R.class"]}), entry("P.moved"),
    entry("P.breaks", {kind: "REQUIRED"}), entry("P.removed", {kind: "REQUIRED"}), entry("P.fades")]};
  const next = {patches: [entry("P.same"), entry("P.newName", {rewrites: ["a/R.class"]}),
    entry("P.moved", {rewrites: ["b/P.moved.class"]}), entry("P.breaks", {kind: "REQUIRED", outcome: "FAILED",
      error: "x"}), entry("P.fades", {outcome: "DISABLED"}), entry("P.added")]};
  const diff = diffManifests(old, next);
  const ids = (category) => diff[category].map((value) => value.id).join();
  assert(ids("unchanged") === "P.same", "unchanged");
  assert(ids("renamed") === "P.newName" && diff.renamed[0].from === "P.oldName", "renamed by classes");
  assert(ids("target-changed") === "P.moved" && /rewrites -a\/P\.moved\.class \+b\/P\.moved\.class/.test(
    diff["target-changed"][0].change), "target-changed");
  assert(ids("failed") === "P.breaks,P.fades", "failed");
  assert(ids("new") === "P.added" && ids("gone") === "P.removed", "new and gone");
  assert(diff.blocking.join() === "P.breaks" && diff.review.join() === "P.removed", "blocking and review");
  assert(diffIsClean(diffManifests(old, old)), "a successful manifest equals itself");
  const renamedByName = diffManifests({patches: [entry("A.patchX", {rewrites: []})]},
    {patches: [entry("A.patchX2", {rewrites: []}), entry("B.patchX", {rewrites: [], patcher: "P"})]});
  assert(renamedByName.renamed.length === 1 && renamedByName.renamed[0].id === "B.patchX", "renamed by method name");
  // Run-log JSON as PatchRegistry writes it, read back.
  const scratch = mkdtempSync(join(tmpdir(), "gaius-patchkit-self-"));
  try {
    const log = join(scratch, "run.jsonl");
    writeFileSync(log, `${JSON.stringify(records[0])}\n${JSON.stringify(records[1])}\n`);
    assert(readRunLog(log).length === 2, "run log lines");
    // lint on a fake repository: every kind of registration problem is reported.
    const fake = join(scratch, "repo");
    const write = (path, text) => {
      mkdirSync(dirname(join(fake, path)), {recursive: true});
      writeFileSync(join(fake, path), text);
    };
    write("port/tools/src/main/java/dev/gaius/tools/DemoPatcher.java", [
      "final class DemoPatcher {",
      "    /** {@code PatchRegistry.run(\"<Domain>.<method>\", ...)} is documentation. */",
      "    public static void main(String[] args) throws Exception {",
      "        PatchRegistry.run(\"DemoPatcher.ok\", \"PERF js\", () -> ok());",
      "        PatchRegistry.run(\"DemoPatcher.kind\", \"FEATURE\", () -> kind());",
      "        PatchRegistry.run(\"DemoPatcher.bad\", \"PERF >9.2\", () -> bad());",
      "        PatchRegistry.run(\"DemoPatcher.bare\", () -> bare());",
      "        PatchRegistry.runJsOnly(\"DemoPatcher.old\", () -> old());",
      "        PatchRegistry.run(\"DemoPatcher.unlisted\",",
      "                \"REQUIRED wasmgc\", () -> unlisted());",
      "        PatchRegistry.run(\"DemoPatcher.packaged\", \"PERF\", () -> DemoPatches.packaged(\")\"));",
      "        PatchRegistry.run(\"DemoPatcher.declared\", \"PERF js wasmgc\", () -> DemoPatches.declared());",
      "        PatchRegistry.run(\"DemoPatcher.helped\", \"PERF js wasmgc\", () -> DemoSupport.help());",
      "    }",
      "}", ""].join("\n"));
    write("port/tools/src/main/java/dev/gaius/tools/patches/support/DemoSupport.java", [
      "package dev.gaius.tools.patches.support;",
      "public final class DemoSupport {",
      "    public static void help() {",
      "    }",
      "}", ""].join("\n"));
    write("port/tools/src/main/java/dev/gaius/tools/patches/demo/DemoPatches.java", [
      "package dev.gaius.tools.patches.demo;",
      "public final class DemoPatches {",
      "    static void own() throws Exception {",
      "        PatchRegistry.run(\"DemoPatches.own\", \"FEATURE >=9.2\", () -> {});",
      "    }",
      "}", ""].join("\n"));
    write("port/tools/patchkit/classification.tsv", ["# fixture", "id\tkind\tcensus\twasm\treason",
      "DemoPatcher.ok\tPERF\t-\tjs-only\tfast", "DemoPatcher.kind\tREQUIRED\t-\tsame\tneeded",
      "DemoPatcher.gone\tPERF\t-\tsame\tno longer registered", ""].join("\n"));
    write("port/tools/target-skips/wasmgc.txt", "DemoPatcher.kind | * | not js-only\n");
    write("port/tools/target-skips/js.txt", "# none\n");
    write("port/scripts/build-overlays.sh", readFileSync(join(repositoryRoot, "port/scripts/build-overlays.sh"), "utf8"));
    const fakeLint = lint(fake).problems.join("\n");
    for (const expected of [
      /DemoPatcher\.kind is FEATURE but classification\.tsv says REQUIRED/,
      /'>9\.2' is neither a target nor a profile term/,
      /DemoPatcher\.bare is registered without a spec/,
      /PatchRegistry\.runJsOnly no longer exists/,
      /DemoPatcher\.unlisted has no row in port\/tools\/patchkit\/classification\.tsv/,
      /classification\.tsv lists DemoPatcher\.gone, which port\/tools does not register/,
      /target-skips\/wasmgc\.txt must list DemoPatcher\.ok/,
      /target-skips\/wasmgc\.txt lists DemoPatcher\.kind, whose spec includes wasmgc/,
      /target-skips\/js\.txt must list DemoPatcher\.unlisted/,
      /DemoPatcher\.packaged is implemented in the demo domain package and must name its targets/,
      /DemoPatches\.own is implemented in the demo domain package and must name its targets/,
      /DemoPatcher\.kind must name its targets \(js, wasmgc\) in its spec/]) {
      assert(expected.test(fakeLint), `lint must report ${expected}:\n${fakeLint}`);
    }
    assert(!/Domain/.test(fakeLint), "documentation mentions are not registrations");
    assert(!/DemoPatcher\.(declared|ok|unlisted) (is implemented|must name its targets)/.test(fakeLint),
      "patches with named targets pass the targets rule");
    const fakeResult = lint(fake);
    assert(fakeResult.inDomains === 3 && fakeResult.targetsUndeclared === 3,
      `domain and undeclared-target counts ${fakeResult.inDomains} ${fakeResult.targetsUndeclared}`);
    assert(!domainClasses(fake).has("DemoSupport"), "the support package is not a domain");
  } finally {
    rmSync(scratch, {recursive: true, force: true});
  }
  // The repository: registrations, classification, target skips and the chain.
  const result = lint();
  for (const problem of result.problems) console.log(`ERROR ${problem}`);
  assert(result.problems.length === 0, "repository lint");
  console.log(`patchkit self-test: OK (${result.registrations} registered patch ids)`);
  return 0;
}

// -------------------------------------------------------------------------------- main

function main(argv) {
  const [command, ...rest] = argv;
  switch (command) {
    case "check":
      return check(rest);
    case "diff": {
      const files = rest.filter((value, index) => !value.startsWith("--") && rest[index - 1] !== "--json");
      const jsonIndex = rest.indexOf("--json");
      if (files.length !== 2) throw new UsageError("diff needs <old manifest> <new manifest>");
      const [previous, current] = files.map((file) => JSON.parse(readFileSync(nativePath(file), "utf8")));
      if (previous.schema === 2 || current.schema === 2) {
        const problems = [previous, current].filter((value) => value.schema === 2).flatMap(validateTargets);
        if (problems.length) throw new UsageError(problems.join("\n"));
      }
      const diff = diffManifests(previous, current, readAcknowledgements(repositoryRoot));
      console.log(formatDiff(diff, `${files[0]} -> ${files[1]}`));
      if (jsonIndex >= 0) writeJson(resolve(nativePath(rest[jsonIndex + 1])), diff);
      return diffIsClean(diff) ? 0 : 1;
    }
    case "lint": {
      const result = lint();
      for (const problem of result.problems) console.log(`ERROR ${problem}`);
      console.log(`PATCHKIT_LINT ${result.problems.length === 0 ? "PASS" : "FAIL"} `
        + `registrations=${result.registrations} problems=${result.problems.length} `
        + `inDomains=${result.inDomains} targetsUndeclared=${result.targetsUndeclared}`);
      return result.problems.length === 0 ? 0 : 1;
    }
    case "--self-test":
      return selfTest();
    case "--bytecode-test":
      return bytecodeTest();
    default:
      throw new UsageError("usage: patchkit.mjs check|diff|lint|--self-test|--bytecode-test (see the header)");
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(`patchkit: ${error instanceof UsageError ? error.message : error.stack}`);
    process.exitCode = 2;
  }
}
