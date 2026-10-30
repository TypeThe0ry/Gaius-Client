import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {test} from "node:test";

const source = await readFile(new URL("../tools/src/main/java/dev/gaius/tools/patches/serverworker/ServerWorkerPatches.java", import.meta.url), "utf8");

test("WasmGC PrepareSpawnTask keeps vanilla radius-three waits", () => {
  assert.match(source, /BuildTarget\.wasmGc\(\) \? Opcodes\.ICONST_3 : Opcodes\.ICONST_1/);
  assert.match(source, /int readyRadius = BuildTarget\.wasmGc\(\) \? 3 : 1/);
  assert.match(source, /BuildTarget\.wasmGc\(\) \? 3 : 0/);
  assert.match(source, /if \(!BuildTarget\.wasmGc\(\)\) \{\s*addPrepareSpawnEntityGate/s);
});
