#!/usr/bin/env node

import {CANONICAL_PROFILE, LEGACY_PROFILE, RENDERPEARL_PROFILE, profileIdPattern} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const persistencePath = resolve(
  repositoryRoot,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java",
);
const workerPath = resolve(
  repositoryRoot,
  "port/web/singleplayer/server-worker-bootstrap.js",
);
const integratedServerPath = resolve(
  repositoryRoot,
  "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
);
const profileIds = [LEGACY_PROFILE.id, CANONICAL_PROFILE.id, RENDERPEARL_PROFILE.id];
const profilePaths = profileIds.map(id => resolve(
  repositoryRoot,
  "port/versions/" + id + ".json",
));
const [persistence, worker, integratedServer, ...profileSources] = await Promise.all([
  readFile(persistencePath, "utf8"),
  readFile(workerPath, "utf8"),
  readFile(integratedServerPath, "utf8"),
  ...profilePaths.map(path => readFile(path, "utf8")),
]);

assert.match(persistence, /private static int currentDataVersion\(\)/);
assert.match(persistence, /private static String storagePrefix\(\)/);
assert.match(persistence, /runtimeWorldVersion\(\)/);
assert.match(persistence, /runtimeStoragePrefix\(\)/);
assert.match(persistence, /runtimeStorageConfigurationSignature\(\)/);
assert.match(persistence, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(LEGACY_PROFILE)}`));
assert.match(persistence, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(CANONICAL_PROFILE)}`));
assert.match(persistence, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(RENDERPEARL_PROFILE)}`));
assert.match(persistence, /LEGACY_DATA_VERSION = 4671/);
assert.match(
  persistence,
  /private static int currentDataVersion\(\)[\s\S]*?if \(value <= 0\) \{[\s\S]*?throw new IllegalStateException/,
);
assert.match(
  persistence,
  /private static String storagePrefix\(\)[\s\S]*?value == null[\s\S]*?gaius\.fs\.v1:[\s\S]*?throw new IllegalStateException/,
);
assert.match(
  persistence,
  /public static void mount\(\)[\s\S]*?String prefix = storagePrefix\(\);[\s\S]*?currentDataVersion\(\);[\s\S]*?mounted = true;/,
);
assert.doesNotMatch(persistence, /PREFIX\s*=\s*["']gaius\.fs\.v1:/);
assert.doesNotMatch(persistence, /(?:getItem|removeItem|setItem)\(\s*["']gaius\.fs\.v1:/);
assert.doesNotMatch(
  persistence,
  /(?:migrat|import)[\s\S]{0,160}gaius\.fs\.v1:/i,
);

assert.match(worker, /configureStorage\(message\)/);
assert.match(worker, /const storageProfiles = Object\.freeze\(/);
assert.match(worker, /storage configuration does not match profile/);
assert.match(worker, /worldVersion: 4671/);
assert.match(worker, /worldVersion: 4903/);
assert.match(worker, /worldVersion: 5023/);
assert.match(worker, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(LEGACY_PROFILE)}`));
assert.match(worker, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(CANONICAL_PROFILE)}`));
assert.match(worker, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(RENDERPEARL_PROFILE)}`));
assert.match(worker, /indexedDB\.open\(databaseName, schema\)/);
assert.match(worker, /__gaiusStorageDatabaseName/);
assert.match(worker, /__gaiusStorageOpfsDirectory/);
assert.doesNotMatch(worker, /indexedDB\.open\(\s*["']gaius-fs-v1/);
assert.doesNotMatch(worker, /getDirectoryHandle\(\s*["']regions["']/);
assert.doesNotMatch(worker, /deleteDatabase\s*\(/);
assert.doesNotMatch(worker, /(?:migrat|import)[\s\S]{0,160}(?:gaius-fs-v1|gaius\.fs\.v1:)/i);

assert.match(integratedServer, /__gaiusStorageDatabaseName/);
assert.match(integratedServer, /storageMatchesProfile/);
assert.match(integratedServer, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(LEGACY_PROFILE)}`));
assert.match(integratedServer, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(CANONICAL_PROFILE)}`));
assert.match(integratedServer, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(RENDERPEARL_PROFILE)}`));
assert.match(integratedServer, /indexedDB\.open\(storageDatabaseName, storageSchema\)/);
assert.doesNotMatch(integratedServer, /indexedDB\.open\(\s*["']gaius-fs-v1/);
assert.doesNotMatch(integratedServer, /deleteDatabase\s*\(/);

const clientPath = resolve(
  repositoryRoot,
  "port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
);
const client = await readFile(clientPath, "utf8");
assert.match(client, /storageConfigurationValid\(\)/);
assert.match(client, /storageMatchesProfile/);
assert.match(client, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(LEGACY_PROFILE)}`));
assert.match(client, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(CANONICAL_PROFILE)}`));
assert.match(client, new RegExp(String.raw`gaius-fs-v2-${profileIdPattern(RENDERPEARL_PROFILE)}`));

const expectedWorldVersions = new Map([
  [LEGACY_PROFILE.id, 4671],
  [CANONICAL_PROFILE.id, 4903],
  [RENDERPEARL_PROFILE.id, 5023],
]);

const profiles = profileSources.map((source, index) => {
  const profile = JSON.parse(source);
  const id = profileIds[index];
  const expectedWorldVersion = expectedWorldVersions.get(id);
  const expectedStorage = {
    schema: 2,
    databaseName: "gaius-fs-v2-" + id,
    prefix: "gaius.fs.v2:" + id + ":",
    opfsDirectory: "regions-v2-" + id,
  };
  assert.equal(profile.id, id);
  assert.equal(profile.worldVersion, expectedWorldVersion);
  assert.deepEqual(profile.storage, expectedStorage);
  assert.equal(profile.storage.schema, 2);
  assert.match(profile.storage.databaseName, /^gaius-fs-v2-/);
  assert.match(profile.storage.prefix, /^gaius\.fs\.v2:/);
  assert.match(profile.storage.opfsDirectory, /^regions-v2-/);
  return {
    id,
    worldVersion: profile.worldVersion,
    storageSchema: profile.storage.schema,
    databaseName: profile.storage.databaseName,
    prefix: profile.storage.prefix,
    opfsDirectory: profile.storage.opfsDirectory,
  };
});

// The six storage allow-lists are executed, not only grepped: every profile's own
// version-profile storage must pass all of them, and a row that mixes one profile id
// with another profile's world version, names or schema must fail all of them.
function jsBodyBefore(source, declaration, label) {
  const end = source.indexOf(declaration);
  assert.ok(end >= 0, `${label}: missing ${declaration}`);
  const start = source.lastIndexOf('script = """', end);
  assert.ok(start >= 0, `${label}: no JSBody script before ${declaration}`);
  const bodyStart = source.indexOf("\n", start) + 1;
  const bodyEnd = source.indexOf('"""', bodyStart);
  assert.ok(bodyEnd > bodyStart && bodyEnd < end, `${label}: malformed JSBody script`);
  const body = source.slice(bodyStart, bodyEnd);
  assert.ok(!body.includes("\\"), `${label}: JSBody escapes are not modelled by this smoke`);
  return body;
}

function storageMatchExpressions(source, label, expectedCount) {
  const marker = "const storageMatchesProfile =";
  const expressions = [];
  for (let from = source.indexOf(marker); from >= 0; from = source.indexOf(marker, from + 1)) {
    const end = source.indexOf(";", from);
    assert.ok(end > from, `${label}: unterminated storageMatchesProfile`);
    expressions.push(source.slice(from + marker.length, end));
  }
  assert.equal(expressions.length, expectedCount, `${label}: storageMatchesProfile table count`);
  return expressions;
}

function storageGlobals(config) {
  return {
    __gaiusProfileId: config.profileId,
    __gaiusWorldVersion: config.worldVersion,
    __gaiusStorageSchema: config.storageSchema,
    __gaiusStorageDatabaseName: config.storageDatabaseName,
    __gaiusStoragePrefix: config.storagePrefix,
    __gaiusStorageOpfsDirectory: config.storageOpfsDirectory,
  };
}

const matchParameters = ["profileId", "worldVersion", "storageSchema", "storageDatabaseName",
  "storagePrefix", "storageOpfsDirectory"];
const storageTables = [
  ...[
    ["BrowserSingleplayerClient.storageMatchesProfile#1", client],
    ["BrowserSingleplayerClient.storageMatchesProfile#2", client],
    ["BrowserIntegratedServerMain.storageMatchesProfile", integratedServer],
  ].map(([label, source], index, all) => {
    const expressions = storageMatchExpressions(source, label,
      all.filter(([, other]) => other === source).length);
    const ordinal = all.slice(0, index).filter(([, other]) => other === source).length;
    const test = new Function(...matchParameters, `return (${expressions[ordinal]});`);
    return {label, accepts: config => test(...matchParameters.map(name => config[name])) === true};
  }),
  (() => {
    const test = new Function("globalThis", jsBodyBefore(client,
      "private static native boolean storageConfigurationValid();",
      "BrowserSingleplayerClient.storageConfigurationValid"));
    return {
      label: "BrowserSingleplayerClient.storageConfigurationValid",
      accepts: config => test(storageGlobals(config)) === true,
    };
  })(),
  (() => {
    const test = new Function("globalThis", jsBodyBefore(persistence,
      "private static native String runtimeStorageConfigurationSignature();",
      "BrowserFilePersistence.runtimeStorageConfigurationSignature"));
    return {
      label: "BrowserFilePersistence.runtimeStorageConfigurationSignature",
      accepts: config => {
        const signature = test(storageGlobals(config));
        if (!signature) return false;
        assert.equal(signature, [config.profileId, config.worldVersion, config.storageSchema,
          config.storageDatabaseName, config.storagePrefix, config.storageOpfsDirectory].join("|"));
        return true;
      },
    };
  })(),
  (() => {
    const start = worker.indexOf("const storageProfiles = Object.freeze({");
    const end = worker.indexOf("\n});", start);
    assert.ok(start >= 0 && end > start, "server-worker-bootstrap.js storageProfiles table");
    const table = new Function(
      `${worker.slice(start, end + "\n});".length)}\nreturn storageProfiles;`)();
    return {
      label: "server-worker-bootstrap.js storageProfiles",
      accepts: config => {
        const expected = Object.hasOwn(table, config.profileId) ? table[config.profileId] : null;
        return Boolean(expected) && config.worldVersion === expected.worldVersion
          && config.storageSchema === expected.storageSchema
          && config.storageDatabaseName === expected.storageDatabaseName
          && config.storagePrefix === expected.storagePrefix
          && config.storageOpfsDirectory === expected.storageOpfsDirectory;
      },
    };
  })(),
];
assert.equal(storageTables.length, 6);

const storageConfigs = profiles.map(profile => ({
  profileId: profile.id,
  worldVersion: profile.worldVersion,
  storageSchema: profile.storageSchema,
  storageDatabaseName: profile.databaseName,
  storagePrefix: profile.prefix,
  storageOpfsDirectory: profile.opfsDirectory,
}));
let tableChecks = 0;
for (const config of storageConfigs) {
  for (const table of storageTables) {
    assert.equal(table.accepts(config), true, `${table.label} rejects ${config.profileId} storage`);
    tableChecks++;
  }
  const mixed = [{...config, storageSchema: 1}];
  for (const other of storageConfigs) {
    if (other === config) continue;
    for (const field of matchParameters.slice(1)) {
      if (other[field] !== config[field]) mixed.push({...config, [field]: other[field]});
    }
  }
  for (const candidate of mixed) {
    for (const table of storageTables) {
      assert.equal(table.accepts(candidate), false,
        `${table.label} accepts a mixed ${config.profileId} row: ${JSON.stringify(candidate)}`);
      tableChecks++;
    }
  }
}

function storageIdentity(profile, worldId) {
  return {
    database: profile.databaseName,
    key: profile.prefix + "/gaius/saves/" + worldId + "/level.dat",
    opfs: "gaius/" + profile.opfsDirectory + "/" + worldId + ".regions",
  };
}

const identities = profiles.map(profile => storageIdentity(profile, "smoke-world"));
assert.equal(new Set(identities.map(identity => identity.database)).size, profiles.length);
assert.equal(new Set(identities.map(identity => identity.key.split("/gaius/")[0])).size, profiles.length);
assert.equal(new Set(identities.map(identity => identity.opfs)).size, profiles.length);
assert.ok(identities.every(identity => identity.opfs.startsWith("gaius/")));
assert.equal(new Set(profiles.map(profile => profile.prefix)).size, profiles.length);
for (const [index, identity] of identities.entries()) {
  const profile = profiles[index];
  assert.ok(identity.key.startsWith(profile.prefix + "/gaius/"));
  assert.ok(identity.opfs.startsWith("gaius/" + profile.opfsDirectory + "/"));
}

// A tiny in-memory persistence model makes the isolation assertion independent
// of a browser and proves that a write in one profile cannot be read by the other.
const databases = new Map();
for (const [index, profile] of profiles.entries()) {
  const identity = identities[index];
  const database = databases.get(identity.database) || new Map();
  database.set(identity.key, profile.id);
  databases.set(identity.database, database);
}
for (const [index, profile] of profiles.entries()) {
  const identity = identities[index];
  assert.equal(databases.get(identity.database).get(identity.key), profile.id);
  for (const other of profiles) {
    if (other === profile) continue;
    assert.equal(databases.get(identity.database).get(
      other.prefix + "/gaius/saves/smoke-world/level.dat",
    ), undefined);
  }
}

console.log(JSON.stringify({
  ok: true,
  profiles: profiles.map((profile, index) => ({
    id: profile.id,
    worldVersion: profile.worldVersion,
    databaseName: identities[index].database,
    prefix: profile.prefix,
    opfsPath: identities[index].opfs,
  })),
  checks: [
    "dynamic Java storage version and prefix",
    "dynamic Worker IndexedDB and profile OPFS directory",
    "dynamic integrated-server fallback IndexedDB",
    "cross-profile in-memory key isolation",
    `six storage allow-lists executed (${tableChecks} accept/reject checks)`,
    "no unconditional legacy open/delete path",
  ],
}, null, 2));
