#!/usr/bin/env node
// Run the launcher's real database-open function against controlled request events.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/launcher/world-archive.js", import.meta.url), "utf8");
const start = source.indexOf("  function openDatabase() {");
const end = source.indexOf("\n  function transactionDone(", start);
assert.ok(start >= 0 && end > start, "database open function not found");
const openSource = source.slice(start, end);
function harness(hasIndexedDB = true) {
  const request = {};
  const context = {STORE_NAME: "files", storageConfig: () => ({databaseName: "test", schema: 2})};
  if (hasIndexedDB) context.indexedDB = {open(name, schema) {
    assert.equal(name, "test"); assert.equal(schema, 2); return request;
  }};
  vm.createContext(context);
  vm.runInContext(openSource + "\nglobalThis.testOpen = openDatabase;", context);
  return {context, request};
}
{
  const {context} = harness(false);
  await assert.rejects(context.testOpen(), /IndexedDB is unavailable/);
}
{
  const {context, request} = harness();
  let closed = 0;
  const database = {close() { closed++; }};
  const promise = context.testOpen();
  request.result = database;
  request.onsuccess();
  assert.equal(await promise, database);
  database.onversionchange();
  assert.equal(closed, 1);
}
{
  const {context, request} = harness();
  let aborted = 0, created = 0, closed = 0;
  const promise = context.testOpen();
  request.onblocked();
  await assert.rejects(promise, /blocked by another Gaius tab/);
  request.result = {objectStoreNames: {contains() { return false; }},
    createObjectStore() { created++; }, close() { closed++; }};
  request.transaction = {abort() { aborted++; }};
  request.onupgradeneeded();
  assert.equal(aborted, 1);
  assert.equal(created, 0);
  // This adversarial event checks cleanup even if a host reports late success.
  request.onsuccess();
  assert.equal(closed, 1);
}
{
  const {context, request} = harness();
  let closed = 0;
  const promise = context.testOpen();
  request.error = new Error("open failed");
  request.onerror();
  await assert.rejects(promise, /open failed/);
  request.result = {close() { closed++; }};
  request.onsuccess();
  assert.equal(closed, 1);
}
{
  const {context, request} = harness();
  let created = 0;
  const promise = context.testOpen();
  request.result = {objectStoreNames: {contains() { return false; }},
    createObjectStore(name, options) { assert.equal(name, "files"); assert.equal(options.keyPath, "path"); created++; },
    close() {}};
  request.onupgradeneeded();
  request.onsuccess();
  await promise;
  assert.equal(created, 1);
}
console.log("world archive database open smoke PASS");
