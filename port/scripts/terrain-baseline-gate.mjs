export const TERRAIN_BASELINE_LIMITS = Object.freeze({
  minimumTerrainFraction: 0.5,
  maximumSkyFraction: 0.2,
  meanDiff: 36,
  changedFraction: 0.3,
});

export function terrainBaselinePass({before, after, diff, beforeAudit, afterAudit, beforeLoaded, afterLoaded,
  beforeExpectedLoaded, afterExpectedLoaded}) {
  const validImage = image => image && !image.error
    && Number.isFinite(image.other) && image.other >= TERRAIN_BASELINE_LIMITS.minimumTerrainFraction
    && Number.isFinite(image.sky) && image.sky < TERRAIN_BASELINE_LIMITS.maximumSkyFraction;
  const validRenderedTerrain = (audit, loaded, expectedLoaded) => {
    const loadedComplete = Number.isFinite(Number(loaded)) && Number.isFinite(Number(expectedLoaded))
      && Number(expectedLoaded) > 0 && Number(loaded) >= Number(expectedLoaded);
    const auditComplete = !audit || (Number(audit.visible) >= 20 && Number(audit.uncompiled) === 0);
    return loadedComplete && auditComplete;
  };
  const stableRaster = diff && !diff.error
    && Number.isFinite(diff.meanDiff) && diff.meanDiff < TERRAIN_BASELINE_LIMITS.meanDiff
    && Number.isFinite(diff.changedFraction) && diff.changedFraction < TERRAIN_BASELINE_LIMITS.changedFraction;
  return Boolean(validImage(before) && validImage(after)
    && validRenderedTerrain(beforeAudit, beforeLoaded, beforeExpectedLoaded)
    && validRenderedTerrain(afterAudit, afterLoaded, afterExpectedLoaded)
    && stableRaster);
}
