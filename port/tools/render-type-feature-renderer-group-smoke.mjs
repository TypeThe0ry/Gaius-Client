#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const patcherSource = await readFile(
  join(root, "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java"),
  "utf8",
);
const renderPatchesSource = await readFile(
  join(root, "port/tools/src/main/java/dev/gaius/tools/patches/render/RenderPatches.java"),
  "utf8",
);
const classification = await readFile(join(root, "port/tools/patchkit/classification.tsv"), "utf8");

const removedCachePatch = "patchRenderTypeFeatureRendererGroupVertexBuilderCache";
assert.doesNotMatch(patcherSource, new RegExp(removedCachePatch));
assert.doesNotMatch(renderPatchesSource, /gaius\$lastVertexBuilder/);
assert.doesNotMatch(classification, new RegExp(removedCachePatch));

class StagedVertexBuffer {
  constructor() {
    this.currentDraw = null;
    this.currentBuilder = null;
    this.switches = 0;
  }

  getVertexBuilder(draw) {
    if (this.currentDraw !== draw) {
      this.currentDraw = draw;
      this.currentBuilder = {draw, sequence: ++this.switches};
    }
    return this.currentBuilder;
  }
}

class GroupWithRemovedCache {
  constructor(stagedBuffer, draw) {
    this.stagedBuffer = stagedBuffer;
    this.draw = draw;
    this.lastRenderType = null;
    this.lastConsumer = null;
  }

  getVertexBuilder(renderType) {
    if (this.lastConsumer !== null
        && this.lastRenderType === renderType
        && renderType.canConsolidateConsecutiveGeometry) {
      return this.lastConsumer;
    }
    this.lastRenderType = renderType;
    this.lastConsumer = this.stagedBuffer.getVertexBuilder(this.draw);
    return this.lastConsumer;
  }
}

class GroupWithoutCache {
  constructor(stagedBuffer, draw) {
    this.stagedBuffer = stagedBuffer;
    this.draw = draw;
  }

  getVertexBuilder() {
    return this.stagedBuffer.getVertexBuilder(this.draw);
  }
}

const renderType = {canConsolidateConsecutiveGeometry: true};
const stagedBuffer = new StagedVertexBuffer();
const cachedGroupA = new GroupWithRemovedCache(stagedBuffer, "draw-A");
const cachedGroupB = new GroupWithRemovedCache(stagedBuffer, "draw-B");

const cachedA1 = cachedGroupA.getVertexBuilder(renderType);
const cachedB1 = cachedGroupB.getVertexBuilder(renderType);
const cachedA2 = cachedGroupA.getVertexBuilder(renderType);

assert.notStrictEqual(cachedA1, cachedB1, "interleaved groups must switch the shared builder");
assert.strictEqual(cachedA2, cachedA1,
  "the old Group-local cache must reproduce the stale consumer");
assert.notStrictEqual(cachedA2, stagedBuffer.currentBuilder,
  "the stale consumer must differ from the shared buffer's current builder");

const safeBuffer = new StagedVertexBuffer();
const safeGroupA = new GroupWithoutCache(safeBuffer, "draw-A");
const safeGroupB = new GroupWithoutCache(safeBuffer, "draw-B");
const safeA1 = safeGroupA.getVertexBuilder();
const safeB1 = safeGroupB.getVertexBuilder();
const safeA2 = safeGroupA.getVertexBuilder();

assert.notStrictEqual(safeA1, safeB1);
assert.notStrictEqual(safeA2, safeA1,
  "the safe path must obtain a fresh builder after another Group interleaves");
assert.strictEqual(safeA2, safeBuffer.currentBuilder);
assert.equal(safeBuffer.switches, 3);

console.log("RenderTypeFeatureRenderer Group interleave smoke passed", JSON.stringify({
  staleConsumerReproduced: true,
  safeBuilderSwitches: safeBuffer.switches,
  cachePatchAbsent: true,
}));
