import {runReadbackPackCases} from './gpu-readback-pack-cases.mjs';

/** Import after gpu-glue.js in a secure browser context; the caller owns Chrome slot allocation. */
export async function runReadbackPackBrowser() {
  const adapter = await navigator.gpu?.requestAdapter();
  if (!adapter) throw new Error('No WebGPU adapter');
  const device = await adapter.requestDevice();
  const errors = [];
  device.addEventListener('uncapturederror', event => errors.push(event.error.message));
  const gpu = globalThis.GaiusWebGpu;
  gpu.device = device;
  gpu.queue = device.queue;
  device.pushErrorScope('validation');
  let result;
  let failure;
  try {
    result = await runReadbackPackCases(gpu, device, device.queue);
  } catch (error) {
    failure = error;
  } finally {
    const validation = await device.popErrorScope();
    if (validation) errors.push(validation.message);
    device.destroy();
  }
  if (failure || errors.length) throw new Error([failure?.stack, ...errors].filter(Boolean).join('\n'));
  return {...result, validationErrors: errors, adapter: adapter.info?.description || ''};
}
