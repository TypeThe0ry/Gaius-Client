export const INVENTORY_HOLE_LIMIT = 0.02;

export function inventoryFootprint({heightAboveTerrain, width, height, verticalFovDegrees = 70, renderDistance}) {
  const halfVertical = Math.tan((verticalFovDegrees * Math.PI) / 360);
  const aspect = Number(width) / Number(height);
  const halfHorizontal = aspect * halfVertical;
  const visibleRadius = Number(heightAboveTerrain) * Math.hypot(halfVertical, halfHorizontal);
  const renderRadius = Number(renderDistance) * 16;
  return {
    verticalFovDegrees,
    heightAboveTerrain: Number(heightAboveTerrain),
    viewport: [Number(width), Number(height)],
    visibleRadiusBlocks: Number(visibleRadius.toFixed(2)),
    renderRadiusBlocks: renderRadius,
    insideRenderDistance: Number.isFinite(visibleRadius) && Number.isFinite(renderRadius)
      && visibleRadius < renderRadius,
  };
}
export function inventoryStreamingGatePass({openScreen, holes, loaded, renderDistance, expectedLoaded,
  heightAboveTerrain = 40, width = 800, height = 600}) {
  const hole = Number(holes?.hole);
  const loadedCount = Number(loaded), requiredCount = Number(expectedLoaded);
  const complete = Number.isFinite(loadedCount) && Number.isFinite(requiredCount)
    && requiredCount > 0 && loadedCount >= requiredCount;
  const footprint = inventoryFootprint({heightAboveTerrain, width, height, renderDistance});
  return /Inventory/.test(String(openScreen))
    && Number.isFinite(hole) && hole >= 0 && hole < INVENTORY_HOLE_LIMIT
    && complete
    && footprint.insideRenderDistance;
}
