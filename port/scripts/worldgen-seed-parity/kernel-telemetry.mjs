// Read the page pool and the server facade separately: the server can use either host.
export const KERNEL_TELEMETRY_EXPRESSION = `(()=>{const g=globalThis;return {
  runtime:g.__gaiusKernels?.telemetry?.()||null,
  worldgen:g.GaiusWorldgenKernel?.snapshot?.()||null,
  semantics:g.__gaiusKernelSemantics||null};})()`;

export function kernelActivityFailures(server) {
  const kernel = server?.worldgen?.kernel;
  if (!kernel || !(kernel.jobs > 0) || !(kernel.completed > 0)) return ["No completed worldgen kernel jobs"];
  const kinds = kernel.kinds || {};
  // BIOMES is intentionally declined when vanilla's thread-local multi-noise history is
  // required. TERRAIN and SURFACE must still execute, and no kind may time out or fail.
  const designed = kinds.BIOMES || {};
  const designedFallbacks = designed.fallback || 0;
  const designedTransient = designed.transient || 0;
  const failures = [];
  for (const [name, stat] of Object.entries(kinds)) {
    const allowed = name === "BIOMES";
    if ((stat.timeout || 0) !== 0) failures.push(`Worldgen ${name} timeouts: ${stat.timeout}`);
    if ((stat.failure || 0) !== 0) failures.push(`Worldgen ${name} failures: ${stat.failure}`);
    if (!allowed && (stat.fallback || 0) !== 0) failures.push(`Worldgen ${name} fallbacks: ${stat.fallback}`);
    if (!allowed && (stat.transient || 0) !== 0) failures.push(`Worldgen ${name} transient: ${stat.transient}`);
  }
  const residualRing = Math.max(0, (kernel.ringFallbacks || 0) - designedFallbacks);
  const residualTransient = Math.max(0, (kernel.transient || 0) - designedTransient);
  const residualFallbacks = Math.max(0, (kernel.fallbacks || 0) - designedFallbacks);
  if (residualFallbacks !== 0) failures.push(`Worldgen fallbacks outside BIOMES: ${residualFallbacks}`);
  if (residualRing !== 0) failures.push(`Worldgen ring fallbacks outside BIOMES: ${residualRing}`);
  if (residualTransient !== 0) failures.push(`Worldgen transient refusals outside BIOMES: ${residualTransient}`);
  if ((kernel.timeouts || 0) !== 0) failures.push(`Worldgen timeouts: ${kernel.timeouts}`);
  if ((kernel.failures || 0) !== 0) failures.push(`Worldgen failures: ${kernel.failures}`);
  return failures;
}
