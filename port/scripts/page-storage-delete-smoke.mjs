#!/usr/bin/env node
// Exercises the actual launcher deletion function with controlled storage doubles.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const start = source.indexOf("window.__gaiusFsDelete = function(path) {");
assert.notEqual(start, -1, "launcher delete function is missing");
const end = source.indexOf("\n      };", start);
assert.notEqual(end, -1, "launcher delete function is incomplete");
const implementation = source.slice(start, end + "\n      };".length);

function harness(storage, database = null) {
  const files = {"/world/level.dat": "old"};
  const context = {files, db: database, prefix: "test:", pending: Promise.resolve(),
    persistentWriteGeneration: 0, storageWriteFailure: null, normalize: path => path,
    deleteIndexedDbFile: async () => {}, report() {}};
  context.window = context;
  if (storage !== undefined) context.localStorage = storage;
  vm.createContext(context);
  vm.runInContext(implementation, context);
  return context;
}
{
  const c = harness(undefined);
  assert.equal(c.__gaiusFsDelete("/world/level.dat"), false);
  assert.equal(c.files["/world/level.dat"], "old");
}
{
  let removed;
  const c = harness({removeItem(key) { removed = key; }});
  assert.equal(c.__gaiusFsDelete("/world/level.dat"), true);
  assert.equal(removed, "test:/world/level.dat");
  assert.equal(Object.hasOwn(c.files, "/world/level.dat"), false);
}
{
  const c = harness({removeItem() { throw new Error("denied"); }});
  assert.equal(c.__gaiusFsDelete("/world/level.dat"), false);
  assert.equal(c.files["/world/level.dat"], "old");
}
{
  const c = harness(undefined);
  Object.defineProperty(c, "localStorage", {get() { throw new Error("denied"); }});
  assert.equal(c.__gaiusFsDelete("/world/level.dat"), false);
  assert.equal(c.files["/world/level.dat"], "old");
}
{
  const c = harness(undefined, {});
  let release;
  c.deleteIndexedDbFile = () => new Promise(resolve => { release = resolve; });
  assert.equal(c.__gaiusFsDelete("/world/level.dat"), true);
  await Promise.resolve();
  assert.equal(c.files["/world/level.dat"], "old");
  release();
  await c.pending;
  assert.equal(Object.hasOwn(c.files, "/world/level.dat"), false);
  assert.equal(c.persistentWriteGeneration, 1);
}
{
  const c = harness(undefined, {});
  c.deleteIndexedDbFile = async () => { throw new Error("transaction failed"); };
  assert.equal(c.__gaiusFsDelete("/world/level.dat"), true);
  await assert.rejects(c.pending, /transaction failed/);
  assert.equal(c.files["/world/level.dat"], "old");
  assert.match(c.storageWriteFailure.message, /transaction failed/);
}
console.log("page storage delete smoke PASS");
