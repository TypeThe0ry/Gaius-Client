(() => {
  "use strict";

  const root = globalThis;
  if (root.__gaiusWebGpuMemoryTelemetry) return;
  const resources = new WeakMap();
  const live = new Map();
  const finalizer = typeof FinalizationRegistry === "function"
    ? new FinalizationRegistry(id => live.delete(id)) : null;
  let nextId = 1;
  let installed = false;
  let attempts = 0;

  // WebGPU does not expose allocation counters. These are conservative logical sizes from
  // descriptors, sufficient to attribute ownership without reading native driver memory.
  const formats = new Map();
  for (const name of ["r8unorm", "r8snorm", "r8uint", "r8sint"]) formats.set(name, [1, 1, 1]);
  for (const name of ["r16uint", "r16sint", "r16float", "rg8unorm", "rg8snorm", "rg8uint", "rg8sint"])
    formats.set(name, [1, 1, 2]);
  for (const name of ["r32uint", "r32sint", "r32float", "rg16uint", "rg16sint", "rg16float",
    "rgba8unorm", "rgba8unorm-srgb", "rgba8snorm", "rgba8uint", "rgba8sint",
    "bgra8unorm", "bgra8unorm-srgb", "rgb10a2unorm", "rgb10a2uint", "rg11b10ufloat",
    "rgb9e5ufloat", "depth24plus", "depth32float"])
    formats.set(name, [1, 1, 4]);
  for (const name of ["rg32uint", "rg32sint", "rg32float", "rgba16uint", "rgba16sint", "rgba16float",
    "depth24plus-stencil8"])
    formats.set(name, [1, 1, 8]);
  for (const name of ["rgba32uint", "rgba32sint", "rgba32float"]) formats.set(name, [1, 1, 16]);
  formats.set("depth16unorm", [1, 1, 2]);
  formats.set("depth32float-stencil8", [1, 1, 8]);
  for (const name of ["bc1-rgb-unorm", "bc1-rgb-unorm-srgb", "bc1-rgba-unorm", "bc1-rgba-unorm-srgb",
    "bc4-r-unorm", "bc4-r-snorm", "eac-r11unorm", "eac-r11snorm", "etc2-rgb8unorm",
    "etc2-rgb8unorm-srgb", "etc2-rgb8a1unorm", "etc2-rgb8a1unorm-srgb"])
    formats.set(name, [4, 4, 8]);
  for (const name of ["bc2-rgba-unorm", "bc2-rgba-unorm-srgb", "bc3-rgba-unorm", "bc3-rgba-unorm-srgb",
    "bc5-rg-unorm", "bc5-rg-snorm", "bc6h-rgb-ufloat", "bc6h-rgb-float", "bc7-rgba-unorm",
    "bc7-rgba-unorm-srgb", "eac-rg11unorm", "eac-rg11snorm", "etc2-rgba8unorm",
    "etc2-rgba8unorm-srgb"])
    formats.set(name, [4, 4, 16]);
  for (const block of ["4x4", "5x4", "5x5", "6x5", "6x6", "8x5", "8x6", "8x8", "10x5", "10x6",
    "10x8", "10x10", "12x10", "12x12"])
    for (const suffix of ["unorm", "unorm-srgb"])
      formats.set(`astc-${block}-${suffix}`, [...block.split("x").map(Number), 16]);

  function bufferSize(descriptor) {
    return Number.isFinite(descriptor?.size) && descriptor.size >= 0 ? descriptor.size : null;
  }

  function textureSize(descriptor) {
    const size = descriptor?.size;
    const width = Number(Array.isArray(size) ? size[0] : size?.width);
    const height = Number(Array.isArray(size) ? size[1] : size?.height ?? 1);
    const depth = Number(Array.isArray(size) ? size[2] : size?.depthOrArrayLayers ?? 1);
    const [blockWidth, blockHeight, blockBytes] = formats.get(String(descriptor?.format)) || [];
    if (![width, height, depth].every(Number.isFinite) || width < 1 || height < 1 || depth < 1 || !blockBytes)
      return null;
    let total = 0;
    const mips = Math.max(1, Number(descriptor.mipLevelCount) || 1);
    for (let level = 0; level < mips; level++) {
      const w = Math.max(1, Math.floor(width / 2 ** level));
      const h = Math.max(1, Math.floor(height / 2 ** level));
      const d = descriptor.dimension === "3d" ? Math.max(1, Math.floor(depth / 2 ** level)) : depth;
      total += Math.ceil(w / blockWidth) * Math.ceil(h / blockHeight) * d * blockBytes;
    }
    return total * Math.max(1, Number(descriptor.sampleCount) || 1);
  }

  function track(resource, kind, descriptor) {
    if (!resource || (typeof resource !== "object" && typeof resource !== "function")) return;
    const bytes = kind === "buffer" ? bufferSize(descriptor) : textureSize(descriptor);
    const id = nextId++;
    const row = {id, kind, bytes, label: String(descriptor?.label || "(unlabeled)"),
      format: kind === "texture" ? String(descriptor?.format || "") : ""};
    resources.set(resource, id);
    live.set(id, row);
    if (finalizer) finalizer.register(resource, id);
  }

  function wrapCreate(proto, method, kind) {
    const descriptor = Object.getOwnPropertyDescriptor(proto, method);
    if (!descriptor || typeof descriptor.value !== "function" || descriptor.value.__gaiusMemoryTrace) return false;
    const original = descriptor.value;
    const wrapped = function (options, ...args) {
      const resource = original.call(this, options, ...args);
      track(resource, kind, options);
      return resource;
    };
    Object.defineProperty(wrapped, "__gaiusMemoryTrace", {value: true});
    Object.defineProperty(proto, method, {...descriptor, value: wrapped});
    return true;
  }

  function wrapDestroy(proto) {
    if (!proto) return;
    const descriptor = Object.getOwnPropertyDescriptor(proto, "destroy");
    if (!descriptor || typeof descriptor.value !== "function" || descriptor.value.__gaiusMemoryTrace) return;
    const original = descriptor.value;
    const wrapped = function (...args) {
      const result = original.apply(this, args);
      const id = resources.get(this);
      if (id !== undefined) live.delete(id);
      return result;
    };
    Object.defineProperty(wrapped, "__gaiusMemoryTrace", {value: true});
    Object.defineProperty(proto, "destroy", {...descriptor, value: wrapped});
  }

  function snapshot() {
    const totals = {buffer: {count: 0, bytes: 0, unknown: 0}, texture: {count: 0, bytes: 0, unknown: 0}};
    const labels = new Map();
    for (const row of live.values()) {
      const total = totals[row.kind];
      total.count++;
      if (row.bytes === null) total.unknown++;
      else total.bytes += row.bytes;
      const key = `${row.kind}: ${row.label}`;
      const grouped = labels.get(key) || {count: 0, bytes: 0, unknown: 0};
      grouped.count++;
      if (row.bytes === null) grouped.unknown++;
      else grouped.bytes += row.bytes;
      labels.set(key, grouped);
    }
    return {installed, active: live.size, totals,
      byLabel: [...labels].map(([label, value]) => ({label, ...value}))
        .sort((a, b) => b.bytes - a.bytes).slice(0, 64)};
  }

  root.__gaiusWebGpuMemoryTelemetry = {snapshot};
  function install() {
    if (installed) return;
    attempts++;
    const device = root.GPUDevice?.prototype;
    if (device) {
      const buffers = wrapCreate(device, "createBuffer", "buffer");
      const textures = wrapCreate(device, "createTexture", "texture");
      if (buffers || textures) {
        wrapDestroy(root.GPUBuffer?.prototype);
        wrapDestroy(root.GPUTexture?.prototype);
        installed = true;
        return;
      }
    }
    if (attempts < 200) root.setTimeout(install, 50);
  }
  install();
})();
