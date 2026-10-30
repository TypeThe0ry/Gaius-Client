#!/usr/bin/env node
// Static contract smoke for the browser resource-pack bridge. The TeaVM build is owned by the
// build machine; this check keeps the Java/JS boundary and the vanilla screen hooks reviewable.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const bridge = await readFile(resolve(root, "port/src/main/java/dev/gaius/browser/BrowserUserResourcePacks.java"), "utf8");
const persistence = await readFile(resolve(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java"), "utf8");
const patcher = await readFile(resolve(root, "port/tools/src/main/java/dev/gaius/tools/patches/ui/UiPatches.java"), "utf8");
assert.match(bridge, /input\.multiple\s*=\s*true/);
assert.match(bridge, /accept\s*=\s*'\.zip,application\/zip,application\/x-zip-compressed'/);
assert.match(bridge, /file\.size > 256 \* 1024 \* 1024/);
assert.match(bridge, /new Int8Array\(bytes\.buffer\)/);
assert.match(bridge, /dataTransfer\.items/);
assert.match(bridge, /createReader\(\)/);
assert.match(bridge, /Path\.of\(IMPORT_ROOT \+ root\)/);
assert.match(bridge, /screen\.onFilesDrop\(roots\)/);
assert.match(persistence, /USER_RESOURCE_PACK_ROOT/);
assert.match(persistence, /installUserResourcePack/);
assert.match(persistence, /isUserResourcePackFile\(normalized\)/);
assert.match(patcher, /patchPackSelectionScreenBrowserImport/);
assert.match(patcher, /BrowserUserResourcePacks/);
console.log("BROWSER_USER_RESOURCE_PACKS_SMOKE_OK");
