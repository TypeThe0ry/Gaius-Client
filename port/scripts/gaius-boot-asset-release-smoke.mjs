import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const source = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
assert.match(source, /global\.__gaiusVanillaAssetsCompressedPromise = null/,
  "decoded asset completion must release the compressed payload reference");
assert.match(source, /ready\.then\(\(value\) => \{/,
  "release must run only after the ready promise fulfills");
assert.match(source, /global\.__gaiusVanillaAssetsCompressedPromise = null[\s\S]*?resolve\(value\)/,
  "the decoded asset value must still be published after release");
console.log("gaius boot asset release smoke passed");
