#!/usr/bin/env node
// G4 gate: a build-overlays.sh log must not contain silent skips.
//
// Every patch registration ends in one PatchRegistry outcome:
// applied; not applicable, either registered as dropped
// (PatchRegistry prints "PATCH_DROPPED <id>" after asserting that the old
// target is really absent), outside the profiles of its spec
// ("PATCH_NOT_APPLICABLE <id> profile=<profile>") or outside its targets
// (below); disabled, a failed patch that is not REQUIRED and whose files were
// restored ("PATCH_DISABLED <id> kind=<kind> error=<message>"); or failed, a
// REQUIRED patch, which also fails the build ("PATCH_FAILED <id> ...").  In a
// GAIUS_BRINGUP=1 build of an unfinished profile a patch may also be skipped,
// which prints "BRINGUP_SKIP <id>", but only when <id> is listed in
// port/tools/bringup/<profile>.txt.  Each TeaVM target skips the patches whose
// spec names only the other one, which print
// "PATCH_TARGET_SKIPPED <id> target=<target>": a wasmgc build
// (GAIUS_TEAVM_TARGET=wasmgc) the JavaScript-only patches ("<KIND> js"), a js
// build the WebAssembly GC-only ones ("<KIND> wasmgc"); exactly the ids that
// port/tools/target-skips/<target>.txt lists for the profile.
//
//   node port/scripts/check-build-log-skips.mjs --profile 26.3 build.log
//   node port/scripts/check-build-log-skips.mjs --profile 26.2 build.log
//
// Checks:
//   - any other line containing "Skipped"/"Skipping" is an unregistered skip;
//   - BRINGUP_SKIP ids must be listed, and bring-up skips are refused for a
//     profile whose bringupAllowed is false and for logs of non-bring-up builds;
//   - PATCH_TARGET_SKIPPED ids must equal the target's list for the profile
//     (target-skips/js.txt for a js log, target-skips/wasmgc.txt for a wasmgc log);
//   - a PATCH_FAILED line, or a disabled REQUIRED patch, fails the gate;
//     PATCH_DISABLED lines of other kinds are warnings (errors with
//     --no-disabled);
//   - PATCH_SUMMARY / BRINGUP_STEPS counters must agree with the lines seen.
// Options:
//   --allow <regex>   exempt matching lines from the unregistered-skip rule
//   --strict-list     also fail when a listed bring-up id was never skipped
//                     (default: reported as a warning)
//   --no-disabled     also fail when a patch was disabled
//   --target <name>   the TeaVM target of the logs (default: the target that the
//                     logs name in "Adding <target> classlib overrides" or
//                     PATCH_TARGET_SKIPPED lines, else js)
// Exit status: 0 pass, 1 gate failure, 2 usage error.

import {existsSync, readFileSync, readdirSync} from "node:fs";
import {basename, dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {notApplicableDeclaration, parseSpec, sourceRegistrations} from "../tools/patchkit/patchkit.mjs";
import {escapeRegExp} from "./verification-profiles.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

// Profile-scoped allowances for optional LWJGL modules.  Keep these in source
// control so the gate documents why a known skip is expected; callers cannot
// accidentally broaden the rule with a generic "Skipping" exemption.
const profile262 = ["26", "2"].join(".");
const profile263 = ["26", "3"].join(".");
const profile12111 = ["1", "21", "11"].join(".");
const configuredAllowances = {
  [profile12111]: [
    {pattern: `^Skipping LWJGL module lwjgl-(sdl|vma|vulkan|shaderc|spvc): not in the Minecraft ${escapeRegExp(profile12111)} libraries$`,
      reason: "1.21.11 does not ship these optional LWJGL libraries"},
  ],
  [profile262]: [
    {pattern: `^Skipping LWJGL module lwjgl-sdl: not in the Minecraft ${escapeRegExp(profile262)} libraries$`,
      reason: "26.2 does not ship the optional SDL library"},
    {pattern: `^Skipping LWJGL module lwjgl-(shaderc|spvc): the Minecraft ${escapeRegExp(profile262)} client has no renderpearl OpenGL path$`,
      reason: "26.2 has no renderpearl OpenGL shader path"},
  ],
  [profile263]: [
    {pattern: `^Skipping LWJGL module lwjgl-(glfw|tinyfd): not in the Minecraft ${escapeRegExp(profile263)} libraries$`,
      reason: "26.3 does not ship optional GLFW/tinyfd libraries"},
  ],
};

function usage(message) {
  console.error(`check-build-log-skips: ${message}`);
  process.exit(2);
}

let profile = process.env.GAIUS_MINECRAFT_VERSION || "";
let strictList = false;
let noDisabled = false;
let target = "";
const allow = [];
const logs = [];
for (let index = 2; index < process.argv.length; index++) {
  const argument = process.argv[index];
  if (argument === "--profile") {
    profile = process.argv[++index] ?? usage("--profile needs a value");
  } else if (argument === "--allow") {
    allow.push(new RegExp(process.argv[++index] ?? usage("--allow needs a value")));
  } else if (argument === "--strict-list") {
    strictList = true;
  } else if (argument === "--no-disabled") {
    noDisabled = true;
  } else if (argument === "--target") {
    target = process.argv[++index] ?? usage("--target needs a value");
    if (!/^[a-z0-9]+$/.test(target)) usage(`invalid --target ${target}`);
  } else if (argument.startsWith("--")) {
    usage(`unknown option ${argument}`);
  } else {
    logs.push(argument);
  }
}
if (!profile && process.env.GAIUS_VERSION_PROFILE_PATH) {
  profile = basename(process.env.GAIUS_VERSION_PROFILE_PATH).replace(/\.json$/, "");
}
if (!/^[0-9A-Za-z][0-9A-Za-z.+-]*$/.test(profile)) usage("--profile <id> is required");
if (logs.length === 0) usage("pass at least one build log");
for (const entry of configuredAllowances[profile] ?? []) allow.push(new RegExp(entry.pattern));

function readBringupList(id) {
  const path = resolve(root, "port/tools/bringup", `${id}.txt`);
  const entries = new Map();
  if (!existsSync(path)) return entries;
  readFileSync(path, "utf8").split(/\r?\n/).forEach((rawLine, index) => {
    const line = rawLine.replace(/#.*$/, "").trim();
    if (!line) return;
    const fields = line.split("|").map((field) => field.trim());
    if (fields.length !== 3 || !/^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$/.test(fields[0])
        || !/^P[1-9][a-z]?$/.test(fields[1]) || !fields[2]) {
      usage(`port/tools/bringup/${id}.txt:${index + 1} must be "<patchId> | <owner> | <reason>"`);
    }
    if (entries.has(fields[0])) usage(`port/tools/bringup/${id}.txt lists ${fields[0]} twice`);
    entries.set(fields[0], {owner: fields[1], reason: fields[2]});
  });
  return entries;
}

// port/tools/target-skips/<target>.txt: "<patchId> | <profiles> | <reason>", where
// <profiles> is "*" or the profile terms of the patch's PatchRegistry spec: a comma-separated
// union of profile ids, ">=<id>" and "<=<id>", compared on the leading dotted numbers as
// PatchRegistry.Spec.coversProfile does (">=26.2" covers 26.2, 26.3 and "26.4-snapshot-2").
function leadingVersion(text) {
  const match = /(\d+(?:\.\d+)*)/.exec(text);
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

function coversProfile(terms, profileId) {
  if (terms === null) return true;
  const version = leadingVersion(profileId);
  if (!version) return false;
  return terms.some((term) => {
    if (term.startsWith(">=")) return compareVersions(version, leadingVersion(term.slice(2))) >= 0;
    if (term.startsWith("<=")) return compareVersions(version, leadingVersion(term.slice(2))) <= 0;
    return compareVersions(version, leadingVersion(term)) === 0;
  });
}

function readTargetSkipList(name) {
  const path = resolve(root, "port/tools/target-skips", `${name}.txt`);
  const entries = new Map();
  if (!existsSync(path)) usage(`port/tools/target-skips/${name}.txt is missing for target ${name}`);
  readFileSync(path, "utf8").split(/\r?\n/).forEach((rawLine, index) => {
    const line = rawLine.replace(/#.*$/, "").trim();
    if (!line) return;
    const fields = line.split("|").map((field) => field.trim());
    if (fields.length !== 3 || !/^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$/.test(fields[0])
        || !/^(?:\*|(?:>=|<=)?\d+(?:\.\d+)+(?:,(?:>=|<=)?\d+(?:\.\d+)+)*)$/.test(fields[1]) || !fields[2]) {
      usage(`port/tools/target-skips/${name}.txt:${index + 1} must be "<patchId> | <profiles> | <reason>"`);
    }
    if (entries.has(fields[0])) usage(`port/tools/target-skips/${name}.txt lists ${fields[0]} twice`);
    entries.set(fields[0], {profiles: fields[1] === "*" ? null : fields[1].split(","),
      reason: fields[2]});
  });
  return entries;
}

// A profile whose port/versions JSON has "bringupAllowed": false never builds in bring-up mode.
function bringupAllowed(id) {
  const directory = resolve(root, "port/versions");
  for (const name of readdirSync(directory).filter((entry) => entry.endsWith(".json")).sort()) {
    const data = JSON.parse(readFileSync(resolve(directory, name), "utf8"));
    if (data.id === id) return data.bringupAllowed === true;
  }
  return usage(`no port/versions profile has id ${id}`);
}

const releaseProfile = !bringupAllowed(profile);
const registrations = sourceRegistrations(root);
const registeredParts = new Set(registrations.filter((entry) => entry.method === "runPart")
  .map((entry) => entry.id));
const declaredParts = new Map(registrations.filter((entry) => entry.spec
  && parseSpec(entry.spec).notApplicable).map((entry) => [entry.id, entry.method]));
const profileExclusions = JSON.parse(readFileSync(resolve(root, "port/versions", `${profile}.json`), "utf8"))
  .patchkitNotApplicable ?? {};
let declaredNotApplicableCount = 0;
// Declared exclusions seen in the logs; their targets are checked once the logs' target is known.
const declaredSeen = [];
const listed = readBringupList(profile);
const errors = [];
const warnings = [];
const skipped = new Map();
const dropped = new Map();
let summaryBringup = 0;
let summaryDropped = 0;
let summaryTargetSkipped = 0;
let summaryNotApplicable = 0;
let summaryDisabled = 0;
let summaries = 0;
let bringupBuild = false;
const targetSkipped = new Map();
const notApplicable = new Map();
const disabledPatches = new Map();
const disabledParts = new Set();
const failedPatches = new Map();
const targetsSeen = new Set();

for (const log of logs) {
  if (!existsSync(log)) usage(`log does not exist: ${log}`);
  const lines = readFileSync(log, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    const where = `${basename(log)}:${index + 1}`;
    if (/^Bring-up mode for Minecraft /.test(line)) {
      bringupBuild = true;
      return;
    }
    let targetMatch = /^Adding ([a-z0-9]+) classlib overrides from /.exec(line);
    if (targetMatch) {
      targetsSeen.add(targetMatch[1]);
      return;
    }
    targetMatch = /^PATCH_TARGET_SKIPPED (\S+) target=([a-z0-9]+)\s*$/.exec(line);
    if (targetMatch) {
      targetSkipped.set(targetMatch[1], (targetSkipped.get(targetMatch[1]) ?? 0) + 1);
      targetsSeen.add(targetMatch[2]);
      return;
    }
    let match = /^BRINGUP_SKIP (\S+)\s*$/.exec(line);
    if (match) {
      skipped.set(match[1], (skipped.get(match[1]) ?? 0) + 1);
      if (releaseProfile) {
        errors.push(`${where}: profile ${profile} (bringupAllowed false) skipped ${match[1]} in bring-up mode`);
      } else if (!listed.has(match[1])) {
        errors.push(`${where}: ${match[1]} was skipped but is not listed in port/tools/bringup/${profile}.txt`);
      }
      return;
    }
    match = /^PATCH_DROPPED (\S+)\s*$/.exec(line);
    if (match) {
      dropped.set(match[1], (dropped.get(match[1]) ?? 0) + 1);
      return;
    }
    match = /^PATCH_NOT_APPLICABLE (\S+) profile=(\S+)(?: summary=(part|applied))?\s*$/.exec(line);
    if (match) {
      if (match[2] !== profile) errors.push(`${where}: not-applicable profile ${match[2]} differs from ${profile}`);
      if (match[3]) {
        const method = declaredParts.get(match[1]);
        if (!profileExclusions[match[1]] || !method
            || (match[3] === "part") !== (method === "runPart")) {
          errors.push(`${where}: ${match[1]} has no matching NOT_APPLICABLE declaration`);
        } else {
          declaredSeen.push({id: match[1], where});
        }
        declaredNotApplicableCount++;
      }
      notApplicable.set(match[1], (notApplicable.get(match[1]) ?? 0) + 1);
      return;
    }
    // The error text may mention skipping; the line is a registered outcome, not a skip.
    match = /^PATCH_(DISABLED|FAILED) (\S+) kind=([A-Z]+) (?:summary=(part) )?error=(.*)$/.exec(line);
    if (match) {
      const error = match[5].trim();
      if (match[4]) {
        if (!registeredParts.has(match[2])) errors.push(`${where}: unknown patch part ${match[2]}`);
        if (match[1] === "DISABLED") disabledParts.add(match[2]);
      }
      (match[1] === "DISABLED" ? disabledPatches : failedPatches).set(match[2], match[3]);
      if (match[1] === "FAILED") {
        errors.push(`${where}: ${match[3]} patch ${match[2]} failed: ${error}`);
      } else if (match[3] === "REQUIRED") {
        errors.push(`${where}: REQUIRED patch ${match[2]} was disabled`);
      } else {
        (noDisabled ? errors : warnings).push(`${where}: ${match[3]} patch ${match[2]} was disabled: ${error}`);
      }
      return;
    }
    match = /^PATCH_SUMMARY applied=(\d+) dropped=(\d+) bringupSkipped=(\d+)(?: targetSkipped=(\d+))?(?: notApplicable=(\d+))?(?: disabled=(\d+))?(?: failed=(\d+))?\s*$/.exec(line);
    if (match) {
      summaries++;
      summaryDropped += Number(match[2]);
      summaryBringup += Number(match[3]);
      summaryTargetSkipped += Number(match[4] ?? 0);
      summaryNotApplicable += Number(match[5] ?? 0);
      summaryDisabled += Number(match[6] ?? 0);
      return;
    }
    if (/^PATCH_(?:SUMMARY|TARGET_SKIPPED|NOT_APPLICABLE|DISABLED|FAILED)\b/.test(line)) {
      errors.push(`${where}: unrecognized patch registry line: ${line.trim()}`);
      return;
    }
    match = /^BRINGUP_STEPS skipped=(\d+)\s*$/.exec(line);
    if (match) {
      summaries++;
      summaryBringup += Number(match[1]);
      return;
    }
    if (/\b(?:skipped|skipping)\b/i.test(line) && !allow.some((pattern) => pattern.test(line))) {
      errors.push(`${where}: unregistered skip: ${line.trim()}`);
    }
  });
}

const skipCount = [...skipped.values()].reduce((sum, count) => sum + count, 0);
const dropCount = [...dropped.values()].reduce((sum, count) => sum + count, 0);
if (skipCount > 0 && !bringupBuild) {
  errors.push(`${skipCount} bring-up skip(s) in a log without "Bring-up mode for Minecraft ${profile}"`);
}
if (summaries > 0 && summaryBringup !== skipCount) {
  errors.push(`summaries count ${summaryBringup} bring-up skips but the log has ${skipCount} BRINGUP_SKIP lines`);
}
if (summaries > 0 && summaryDropped !== dropCount) {
  errors.push(`summaries count ${summaryDropped} dropped patches but the log has ${dropCount} PATCH_DROPPED lines`);
}
if (!target) {
  target = targetsSeen.size === 1 ? [...targetsSeen][0] : "js";
}
if (targetsSeen.size > 1 || (targetsSeen.size === 1 && !targetsSeen.has(target))) {
  errors.push(`the logs name TeaVM target(s) ${[...targetsSeen].sort().join(",")}, expected ${target}`);
}
for (const {id, where} of declaredSeen) {
  const declaration = notApplicableDeclaration(profileExclusions[id]);
  if (!declaration?.targets.includes(target)) {
    errors.push(`${where}: ${id} is declared not applicable for ${declaration?.targets ?? "no"} target(s), not ${target}`);
  }
}
const targetSkipCount = [...targetSkipped.values()].reduce((sum, count) => sum + count, 0);
{
  const expected = new Map([...readTargetSkipList(target)]
    .filter(([, entry]) => coversProfile(entry.profiles, profile)));
  for (const [id, count] of targetSkipped) {
    if (!expected.has(id)) {
      errors.push(`${id} was skipped for ${target}, but port/tools/target-skips/${target}.txt does not list it for ${profile}`);
    } else if (count !== 1) {
      errors.push(`${id} was skipped for ${target} ${count} times`);
    }
  }
  for (const id of expected.keys()) {
    if (!targetSkipped.has(id)) {
      errors.push(`port/tools/target-skips/${target}.txt lists ${id} for ${profile}, but the ${target} build did not skip it`);
    }
  }
}
if (summaries > 0 && summaryTargetSkipped !== targetSkipCount) {
  errors.push(`summaries count ${summaryTargetSkipped} target skips but the log has ${targetSkipCount} PATCH_TARGET_SKIPPED lines`);
}
const notApplicableCount = [...notApplicable.values()].reduce((sum, count) => sum + count, 0);
if (summaries > 0 && summaryNotApplicable !== notApplicableCount - declaredNotApplicableCount) {
  errors.push(`summaries count ${summaryNotApplicable} not-applicable patches but the log has ${notApplicableCount - declaredNotApplicableCount} counted PATCH_NOT_APPLICABLE lines`);
}
if (summaries > 0 && summaryDisabled !== disabledPatches.size - disabledParts.size) {
  errors.push(`summaries count ${summaryDisabled} disabled patches but the log has ${disabledPatches.size - disabledParts.size} counted PATCH_DISABLED lines`);
}
if (bringupBuild) {
  const stale = [...listed.keys()].filter((id) => !skipped.has(id));
  for (const id of stale) {
    const message = `listed bring-up id ${id} (${listed.get(id).owner}) was never skipped; ` +
      "remove it if the patch now applies, or fix the id";
    (strictList ? errors : warnings).push(message);
  }
}

for (const warning of warnings) console.log(`WARNING ${warning}`);
for (const error of errors) console.log(`ERROR ${error}`);
const owners = {};
for (const id of skipped.keys()) {
  const owner = listed.get(id)?.owner ?? "unlisted";
  owners[owner] = (owners[owner] ?? 0) + 1;
}
console.log(`BUILD_LOG_SKIPS profile=${profile} bringupBuild=${bringupBuild} ` +
  (target === "js" ? "" : `target=${target} targetSkipped=${targetSkipped.size} `) +
  `bringupSkipped=${skipped.size} dropped=${dropped.size} ` +
  (notApplicable.size ? `notApplicable=${notApplicable.size} ` : "") +
  (disabledPatches.size ? `disabled=${disabledPatches.size} ` : "") +
  (failedPatches.size ? `failed=${failedPatches.size} ` : "") +
  `listed=${listed.size} ` +
  `owners=${Object.entries(owners).sort().map(([owner, count]) => `${owner}:${count}`).join(",") || "-"} ` +
  `errors=${errors.length} warnings=${warnings.length}`);
console.log(`BUILD_LOG_SKIPS_RESULT ${errors.length === 0 ? "PASS" : "FAIL"}`);
process.exit(errors.length === 0 ? 0 : 1);
