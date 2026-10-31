import {loadManifest, loadProfiles} from "../tools/sourcesets/sourcesets.mjs";
import {basename} from "node:path";

export const VERSION_PROFILES = Object.freeze(loadProfiles()
  .filter((profile) => basename(profile.__path) === `${profile.id}.json`).map(Object.freeze));

function selectProfile(predicate, role) {
  const matches = VERSION_PROFILES.filter(predicate);
  if (matches.length !== 1) throw new Error(`Expected one ${role} verification profile, found ${matches.length}`);
  return matches[0];
}

export const CANONICAL_PROFILE = selectProfile(
  (profile) => profile.renderSeam === loadManifest().canonicalRenderSeam, "canonical render seam",
);
export const LEGACY_PROFILE = selectProfile(
  (profile) => profile.clientDistribution === "obfuscated-with-mappings", "mapped legacy client",
);
export const RENDERPEARL_PROFILE = selectProfile(
  (profile) => profile.shaderFront === "renderpearl", "renderpearl shader front",
);

export function escapeRegExp(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/[.*+?^${}()|[\]]/g, "\\$&");
}

export function profileIdPattern(profile) {
  return escapeRegExp(profile.id);
}
