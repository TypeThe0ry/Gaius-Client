#!/usr/bin/env node
import {mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {choosePrevious, draftProfile} from "./gaius-bringup.mjs";

if (process.env.GITHUB_ACTIONS !== "true") throw new Error("Snapshot bootstrap is CI-only");
const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const out = resolve(process.argv[2]);
const read = (path) => JSON.parse(readFileSync(path, "utf8"));
const metadata = read(join(out, "metadata.json"));
const profiles = readdirSync(join(root, "port/versions")).filter((name) => name.endsWith(".json"))
    .map((name) => read(join(root, "port/versions", name)));
const existing = profiles.find((profile) => profile.id === metadata.id);
const reviewed = existing && existing.bringup?.provisional !== true ? existing : null;
const previous = reviewed ?? choosePrevious(metadata.id, profiles, metadata.releaseTime);
const distribution = metadata.downloads.client_mappings ? "obfuscated-with-mappings" : "named";
const template = reviewed ?? profiles.filter((profile) => profile.clientDistribution === distribution && profile.bringup?.provisional !== true)
    .sort((left, right) => Date.parse(left.official.releaseTime) - Date.parse(right.official.releaseTime)).at(-1);
if (!template) throw new Error(`No remap recipe for ${distribution}`);
const draft = draftProfile(metadata.id, {...read(join(out, "jar-stats.json")),
    version: read(join(out, "jar-version.json"))}, metadata, template);
const profile = join(root, "port/versions", `${draft.id}.json`);
if (!existing) writeFileSync(profile, JSON.stringify(draft, null, 2) + "\n");
const inputs = [...new Set([previous.id, template.bringup?.shader?.referenceProfile,
    template.bringup?.shader?.nativeLibrariesFrom].filter(Boolean))];
mkdirSync(out, {recursive: true});
writeFileSync(join(out, "selection.json"), JSON.stringify({id: draft.id, previous: previous.id, inputs, alreadyReviewed: !!reviewed}, null, 2) + "\n");
console.log(`Snapshot ${draft.id}; predecessor ${previous.id}; recipe ${template.id}; already-reviewed=${!!reviewed}`);
