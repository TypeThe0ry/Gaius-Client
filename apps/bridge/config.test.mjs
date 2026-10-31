import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import test from "node:test";

const moduleUrl = new URL("./dist/config.js", import.meta.url).href;
function load(env) {
    const childEnv = {...process.env, ...env};
    for (const [name, value] of Object.entries(env)) {
        if (value === undefined) delete childEnv[name];
    }
    return spawnSync(process.execPath, ["--input-type=module", "-e",
        `import {loadConfig} from ${JSON.stringify(moduleUrl)}; loadConfig();`], {
        env: childEnv, encoding: "utf8",
    });
}

test("null origin requires an explicit deployment opt-in", () => {
    const rejected = load({GAIUS_ALLOWED_ORIGINS: "https://play.example,null",
        GAIUS_ALLOW_NULL_ORIGIN: undefined});
    assert.notEqual(rejected.status, 0);
    assert.match(rejected.stderr, /GAIUS_ALLOW_NULL_ORIGIN=1/);

    const accepted = load({GAIUS_ALLOWED_ORIGINS: "https://play.example,null",
        GAIUS_ALLOW_NULL_ORIGIN: "1"});
    assert.equal(accepted.status, 0, accepted.stderr);
});

test("wildcard origin permits null without a separate opt-in and must stand alone", () => {
    const wildcard = load({GAIUS_ALLOWED_ORIGINS: "*", GAIUS_ALLOW_NULL_ORIGIN: undefined});
    assert.equal(wildcard.status, 0, wildcard.stderr);

    const mixed = load({GAIUS_ALLOWED_ORIGINS: "*,https://play.example"});
    assert.notEqual(mixed.status, 0);
    assert.match(mixed.stderr, /wildcard must be the only list entry/);
});

test("legacy query token compatibility is enabled by default and can be disabled", () => {
    const defaultConfig = load({GAIUS_BRIDGE_TOKEN: "bridge-token", GAIUS_ALLOW_LEGACY_QUERY_TOKEN: undefined});
    assert.equal(defaultConfig.status, 0, defaultConfig.stderr);
    const disabledConfig = load({GAIUS_BRIDGE_TOKEN: "bridge-token", GAIUS_ALLOW_LEGACY_QUERY_TOKEN: "0"});
    assert.equal(disabledConfig.status, 0, disabledConfig.stderr);
});
