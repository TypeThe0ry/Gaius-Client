export function summarizeFlightReadiness(flight, samples, minimumChunks = 4) {
  const movement = samples.filter(sample => sample.phase === 'movement');
  const visuals = flight?.visualSamples || [];
  const emptySamples = movement.filter(sample => Number(sample.loadedChunkCount) === 0).length;
  const insufficientSamples = movement.filter(sample =>
    !Number.isFinite(Number(sample.loadedChunkCount))
      || Number(sample.loadedChunkCount) < minimumChunks).length;
  const failedVisualSamples = visuals.filter(sample => sample.terrainVisualPass !== true).length;
  // Holding forward is not evidence of traversal: a mountain can stop the
  // player while loaded-chunk counts and terrain pixels remain healthy.
  let travelled = 0;
  let stalledIntervals = 0;
  let invalidPositions = 0;
  for (let index = 1; index < visuals.length; index++) {
    const before = visuals[index - 1]?.player;
    const after = visuals[index]?.player;
    if (![before?.x, before?.z, after?.x, after?.z].every(Number.isFinite)) {
      invalidPositions++;
      continue;
    }
    const distance = Math.hypot(after.x - before.x, after.z - before.z);
    travelled += distance;
    if (distance < 1) stalledIntervals++;
  }
  const traversalPassed = invalidPositions === 0 && stalledIntervals === 0 && travelled >= 48;
  return {
    movementSamples: movement.length, emptySamples, insufficientSamples,
    visualSamples: visuals.length, failedVisualSamples,
    horizontalTravel: travelled, stalledIntervals, invalidPositions, traversalPassed,
    passed: flight?.altitudeCheckPassed === true && flight?.durationPassed === true
      && movement.length >= 45 && visuals.length >= 5
      && insufficientSamples === 0 && failedVisualSamples === 0 && traversalPassed,
    proves300msRenderTarget: false,
  };
}
