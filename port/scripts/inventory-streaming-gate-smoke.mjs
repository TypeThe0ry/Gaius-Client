import assert from "node:assert/strict";
import {inventoryFootprint, inventoryStreamingGatePass} from "./inventory-streaming-gate.mjs";

const footprint = inventoryFootprint({heightAboveTerrain: 24, width: 800, height: 600, renderDistance: 8});
assert.equal(footprint.insideRenderDistance, true);
assert.ok(footprint.visibleRadiusBlocks < 40);
assert.equal(inventoryStreamingGatePass({
  openScreen: "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
  holes: {hole: 0.009}, loaded: 289, renderDistance: 8, expectedLoaded: 289, heightAboveTerrain: 40,
}), true);
// The pre-release failure had a large missing region even though the inventory opened.
assert.equal(inventoryStreamingGatePass({
  openScreen: "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
  holes: {hole: 0.6296}, loaded: 208, renderDistance: 8, expectedLoaded: 289, heightAboveTerrain: 40,
}), false);
// A missing strip inside the render distance must still fail even if the
// loaded-chunk counter has reached the nominal square.
assert.equal(inventoryStreamingGatePass({
  openScreen: "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
  holes: {hole: 0.08}, loaded: 289, renderDistance: 8, expectedLoaded: 289, heightAboveTerrain: 40,
}), false);
assert.equal(inventoryStreamingGatePass({
  openScreen: "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
  holes: {hole: 0.009}, loaded: 208, renderDistance: 8, expectedLoaded: 289, heightAboveTerrain: 40,
}), false);
assert.equal(inventoryStreamingGatePass({
  openScreen: "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
  holes: {hole: 0.009}, loaded: 289, renderDistance: 2, expectedLoaded: 289, heightAboveTerrain: 40,
}), false);
assert.equal(inventoryStreamingGatePass({
  openScreen: "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
  holes: {hole: -0.1}, loaded: 289, renderDistance: 8, expectedLoaded: 289, heightAboveTerrain: 40,
}), false);
assert.equal(inventoryStreamingGatePass({
  openScreen: "TitleScreen", holes: {hole: 0}, loaded: 289, renderDistance: 8, expectedLoaded: 289,
}), false);
console.log("inventory streaming gate smoke: PASS");
