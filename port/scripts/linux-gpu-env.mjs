import {existsSync} from "node:fs";

export function linuxGpuPci(env, platform = process.platform) {
  if (platform !== "linux" || !env.GAIUS_CHROME_GPU_PCI) return null;
  if (env.GAIUS_LINUX_GPU_ENV === "0") {
    throw new Error("GAIUS_CHROME_GPU_PCI requires the Linux GPU environment");
  }
  const raw = String(env.GAIUS_CHROME_GPU_PCI).toLowerCase();
  if (!/^(?:[0-9a-f]{4}:)?[0-9a-f]{2}:[0-9a-f]{2}\.[0-7]$/.test(raw)) {
    throw new Error("GAIUS_CHROME_GPU_PCI must be a PCI address, for example 0000:23:00.0");
  }
  return raw.length === 7 ? `0000:${raw}` : raw;
}

function pinnedBackend(env) {
  const backend = env.GAIUS_CHROME_GPU_BACKEND || "zink-gl";
  if (!["angle-vulkan-gl", "vulkan", "zink", "zink-gl"].includes(backend)) {
    throw new Error("GAIUS_CHROME_GPU_BACKEND must be angle-vulkan-gl, vulkan, zink or zink-gl");
  }
  return backend;
}

export function linuxGpuEnvironment(env, platform = process.platform, exists = existsSync) {
  const out = {...env};
  const pci = linuxGpuPci(env, platform);
  if (platform !== "linux" || out.GAIUS_LINUX_GPU_ENV === "0") return out;
  delete out.__GLX_VENDOR_LIBRARY_NAME;
  delete out.VK_DRIVER_FILES;
  out.__EGL_VENDOR_LIBRARY_FILENAMES = out.GAIUS_NVIDIA_EGL_VENDOR
    || "/usr/share/glvnd/egl_vendor.d/10_nvidia.json";
  if (pci) {
    out.GAIUS_CHROME_GPU_BACKEND = pinnedBackend(env);
    const zink = out.GAIUS_CHROME_GPU_BACKEND.startsWith("zink");
    const icd = out.GAIUS_NVIDIA_VULKAN_ICD || "/usr/share/vulkan/icd.d/nvidia_icd.json";
    for (const path of [`/sys/bus/pci/devices/${pci}`, icd,
      "/usr/share/vulkan/implicit_layer.d/VkLayer_MESA_device_select.json"]) {
      if (!exists(path)) throw new Error(`GPU selection requires ${path}`);
    }
    out.VK_DRIVER_FILES = icd;
    delete out.VK_ICD_FILENAMES;
    delete out.MESA_VK_DEVICE_SELECT;
    delete out.NODEVICE_SELECT;
    delete out.VK_LOADER_LAYERS_DISABLE;
    out.VK_INSTANCE_LAYERS = [...new Set([
      ...(out.VK_INSTANCE_LAYERS || "").split(":").filter(Boolean), "VK_LAYER_MESA_device_select",
    ])].join(":");
    out.DRI_PRIME = `pci-${pci.replaceAll(":", "_").replace(".", "_")}${zink ? "" : "!"}`;
    out.MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE = "1";
    if (zink) {
      const vendor = out.GAIUS_MESA_EGL_VENDOR || "/usr/share/glvnd/egl_vendor.d/50_mesa.json";
      if (!exists(vendor)) throw new Error(`GPU selection requires ${vendor}`);
      out.__EGL_VENDOR_LIBRARY_FILENAMES = vendor;
      out.MESA_LOADER_DRIVER_OVERRIDE = "zink";
      out.GALLIUM_DRIVER = "zink";
    }
  }
  return out;
}

// Native NVIDIA EGL ignores the Vulkan device selection. The default pinned
// route exposes that device through Zink EGL and keeps Chrome GL compositing.
// Other renderer/compositor combinations remain explicit diagnostic modes.
export function linuxGpuArguments(args, env, platform = process.platform) {
  if (!linuxGpuPci(env, platform)) return [...args];
  if (pinnedBackend(env) === "angle-vulkan-gl") {
    if (args.includes("--disable-gpu") || args.includes("--disable-gpu-compositing")
      || args.some(arg => arg.startsWith("--enable-features=")
      && arg.slice(18).split(",").some(feature =>
        ["Vulkan", "DefaultANGLEVulkan", "VulkanFromANGLE"].includes(feature.split(/[<:]/)[0])))) {
      throw new Error("GPU PCI selection requires Chrome GL compositing with ANGLE Vulkan");
    }
    const disabled = args.filter(arg => arg.startsWith("--disable-features="))
      .flatMap(arg => arg.slice(19).split(",")).filter(Boolean);
    return [...args.filter(arg => !/^--(?:use-gl|use-angle|use-vulkan|disable-features)=/.test(arg)),
      "--use-gl=angle", "--use-angle=vulkan", "--use-vulkan=none", "--enable-unsafe-webgpu",
      `--disable-features=${[...new Set([...disabled, "Vulkan"])].join(",")}`];
  }
  if (pinnedBackend(env).startsWith("zink")) {
    const readback = pinnedBackend(env) === "zink";
    if (args.includes("--disable-gpu") || (!readback && args.includes("--disable-gpu-compositing"))
      || args.some(arg => arg.startsWith("--enable-features=")
      && arg.slice(18).split(",").some(feature =>
        ["Vulkan", "DefaultANGLEVulkan", "VulkanFromANGLE"].includes(feature.split(/[<:]/)[0])))) {
      throw new Error("Zink GPU selection conflicts with disabled GPU support or Chrome Vulkan compositing");
    }
    return [...args.filter(arg => !/^--(?:use-gl|use-angle|use-vulkan)=/.test(arg)),
      "--use-gl=angle", "--use-angle=gl-egl", ...(readback ? ["--disable-gpu-compositing"] : []),
      "--enable-unsafe-webgpu"];
  }
  if (args.includes("--disable-gpu") || args.some(arg =>
    arg.startsWith("--disable-features=") && arg.slice(19).split(",").includes("Vulkan"))) {
    throw new Error("GPU PCI selection conflicts with disabled Vulkan/GPU support");
  }
  const features = args.filter(arg => arg.startsWith("--enable-features="))
    .flatMap(arg => arg.slice(18).split(",")).filter(Boolean);
  if (!features.some(feature => feature.split(/[<:]/)[0] === "Vulkan")) features.push("Vulkan");
  return [...args.filter(arg => !/^--(?:use-gl|use-angle|use-vulkan|enable-features)=/.test(arg)),
    "--use-gl=angle", "--use-angle=vulkan", "--use-vulkan=native", "--enable-unsafe-webgpu",
    `--enable-features=${[...new Set(features)].join(",")}`];
}

export function linuxGpuProcessEvidence(xml, gpuPids, expectedPci) {
  const wanted = new Set(gpuPids.map(Number));
  const processes = [];
  for (const [, id, body] of xml.matchAll(/<gpu id="([^"]+)">([\s\S]*?)<\/gpu>/g)) {
    const pci = id.toLowerCase().replace(/^00000000:/, "0000:");
    for (const [, process] of body.matchAll(/<process_info>([\s\S]*?)<\/process_info>/g)) {
      const pid = Number(process.match(/<pid>(\d+)<\/pid>/)?.[1]);
      if (wanted.has(pid)) processes.push({pid, pci});
    }
  }
  return {expectedPci, gpuPids: [...wanted], processes,
    ok: wanted.size > 0 && [...wanted].every(pid => processes.some(row => row.pid === pid))
      && processes.every(row => row.pci === expectedPci)};
}
