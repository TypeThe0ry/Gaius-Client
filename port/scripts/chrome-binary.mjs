import {accessSync, constants, existsSync} from "node:fs";
import {delimiter, isAbsolute, join, resolve, win32} from "node:path";

export const LINUX_GPU_FLAGS_ENV = "GAIUS_CHROME_GPU_FLAGS_JSON";
export const DEFAULT_LINUX_GPU_FLAGS = Object.freeze([
  "--enable-unsafe-webgpu",
  "--enable-features=Vulkan",
  "--use-angle=vulkan",
]);

export function chromePlatformArgs({platform = process.platform, userId = process.getuid?.()} = {}) {
  if (platform !== "linux") return [];
  // Without a basic password store headless Chrome waits 15-25 s on the desktop keyring before
  // its first network request, which inflates every boot measurement.
  return [...(userId === 0 ? ["--no-sandbox"] : []), "--password-store=basic"];
}

function availableFile(file, platform, exists, access) {
  if (!exists(file)) return false;
  if (platform === "win32") return true;
  try {
    access(file, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function availableCommand(command, {env, platform, exists, access}) {
  const pathApi = platform === "win32" ? win32 : {isAbsolute, resolve, join};
  if (pathApi.isAbsolute(command) || /[\\/]/.test(command)) {
    return availableFile(pathApi.resolve(command), platform, exists, access);
  }
  const pathEntries = String(env.PATH || "").split(platform === "win32" ? ";" : delimiter);
  const extensions = platform === "win32"
    ? String(env.PATHEXT || ".COM;.EXE;.BAT;.CMD").split(";")
    : [""];
  return pathEntries.some(directory => extensions.some(extension =>
    availableFile(pathApi.join(directory || ".", `${command}${extension}`), platform, exists, access)));
}

export function resolveChromeBinary({
  env = process.env,
  platform = process.platform,
  overrides = [],
  exists = existsSync,
  access = accessSync,
} = {}) {
  const options = {env, platform, exists, access};
  const override = [...overrides, env.GAIUS_CHROME_BIN]
    .find(value => value != null && String(value).trim() !== "");
  if (override != null) {
    const binary = String(override).trim();
    if (!availableCommand(binary, options)) {
      throw new Error(`Chrome executable override not found or not executable: ${binary}`);
    }
    return binary;
  }

  const candidates = platform === "win32"
    ? ["C:/Program Files/Google/Chrome/Application/chrome.exe"]
    : platform === "linux"
      ? ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium"]
      : platform === "darwin"
        ? ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "google-chrome"]
        : ["google-chrome"];
  const binary = candidates.find(candidate => availableCommand(candidate, options));
  if (binary) return binary;
  throw new Error(`Chrome executable not found for ${platform}; set GAIUS_CHROME_BIN to its path`);
}

export function chromeGpuArgs({env = process.env, platform = process.platform} = {}) {
  if (platform !== "linux") return [];
  const raw = env[LINUX_GPU_FLAGS_ENV];
  if (raw == null || raw === "") return [...DEFAULT_LINUX_GPU_FLAGS];
  let flags;
  try {
    flags = JSON.parse(raw);
  } catch (error) {
    throw new Error(`${LINUX_GPU_FLAGS_ENV} must be a JSON array of strings: ${error.message}`);
  }
  if (!Array.isArray(flags) || flags.some(flag => typeof flag !== "string" || !flag.startsWith("--"))) {
    throw new Error(`${LINUX_GPU_FLAGS_ENV} must be a JSON array of Chrome flags`);
  }
  return flags;
}

export function chromeLaunchArgs(options = {}) {
  return [...chromePlatformArgs(options), ...chromeGpuArgs(options)];
}
