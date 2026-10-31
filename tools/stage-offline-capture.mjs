import {createHash} from "node:crypto";

export const OFFLINE_CAPTURE_BOOT_SOURCE = '(' + function installOfflineCapture() {
  globalThis.__gaiusCaptureOffline = true;
  const local = value => {
    const url = new URL(typeof value === "string" || value instanceof URL ? value : value.url, location.href);
    return !["http:", "https:"].includes(url.protocol) || ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  };
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url, ...args) {
    if (!local(url)) throw new Error("offline capture blocked external XHR");
    return open.call(this, method, url, ...args);
  };
  const fetch = globalThis.fetch;
  globalThis.fetch = function(resource, ...args) {
    if (!local(resource)) return Promise.reject(new Error("offline capture blocked external fetch"));
    return fetch.call(this, resource, ...args);
  };
}.toString() + ')();';

export function stageOfflineCapture(source) {
  const pattern = /(?:\$rt_java\.)?[\w$]+_MojangBlockListSupplier_createBlockList = \$this => \{/g;
  const matches = [...source.matchAll(pattern)];
  if (matches.length !== 1) throw new Error("offline capture requires one blocklist provider");
  const match = matches[0], at = match.index + match[0].length;
  const hook = '\n    if(globalThis.__gaiusCaptureOffline)return null;';
  if (source.slice(at, at + hook.length) === hook) throw new Error("offline capture already staged");
  source = source.slice(0, at) + hook + source.slice(at);
  const suspension = '        throw new Error("Suspension point reached from non-threading context "';
  if (source.includes(suspension)) source = source.replace(suspension, '        console.error("[capture coroutine] " + new Error().stack);\n' + suspension);
  return {source, receipt: {mode: "diagnostic-response-only", policy: "local-only-no-remote-blocklist",
    stagedSha256: createHash("sha256").update(source).digest("hex")}};
}
