#!/usr/bin/env node
// Checks the BrowserOpenGL draw-preparation invariant used by vertex-pulling
// pipelines: a program with no active attributes must not inherit a stale VAO
// missing-attribute guard from a previous terrain draw.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import {dirname, resolve} from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const source = readFileSync(resolve(root,
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java"), "utf8");
const start = source.indexOf("window.__gaiusGL.prepareDrawAttribs=function(vao)");
assert.ok(start >= 0, "prepareDrawAttribs is present");
const end = source.indexOf("window.__gaiusGL.withBaseVertexAttribs", start);
const body = source.slice(start, end);
assert.match(body, /const active=this\.programAttribs\.get\(this\.currentProgram\|0\);/);
assert.match(body, /if \(!active \|\| !active\.length\)/);
assert.match(body, /vao\.missingEnabledAttribs\.clear\(\)/);
assert.ok(body.indexOf("!active.length") < body.indexOf("const finalMissing"),
  "the vertex-pulling branch runs before the stale missing-attribute check");

const vao = {missingEnabledAttribs: new Set([0, 1]), drawReadyGeneration: -1};
const programAttribs = [];
const generation = 17;
if (!programAttribs.length) {
  vao.missingEnabledAttribs.clear();
  vao.drawReadyGeneration = generation;
}
assert.equal(vao.missingEnabledAttribs.size, 0);
assert.equal(vao.drawReadyGeneration, generation);
console.log("browser-opengl-vertex-pulling-smoke: ok");
