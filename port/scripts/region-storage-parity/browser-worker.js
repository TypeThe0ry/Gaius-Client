"use strict";
const probeParameters = new URL(globalThis.location.href).searchParams;
const probeRuntime = probeParameters.get("runtime");
const probeMode = probeParameters.get("mode");
const probeProfile = probeParameters.get("profile");
const probeWorld = probeParameters.get("world");
const probeDirectory = `/gaius/saves/${probeWorld}/region`;
if (probeParameters.get("backend") === "indexeddb") {
  Object.defineProperty(navigator.storage, "getDirectory", {value: undefined});
}
importScripts(`/bootstrap/${probeProfile}/${probeRuntime}.js`);
globalThis.__gaiusStartIntegratedServerPump = () => {};
globalThis.stopIntegratedServer = () => {};
globalThis.isIntegratedServerStopped = () => true;
const probeFiles = Object.create(null);
const probeConsoleLog = console.log.bind(console);
const probeConsoleError = console.error.bind(console);
console.error = (...values) => {
  postMessage({type: "probe-error", detail: values.join(" ").slice(0, 2000)});
  probeConsoleError(...values);
};
console.log = (...values) => {
  const text = values.join(" ");
  const region = text.match(/^REGION (r\.\d+\.0\.mca) ([A-Za-z0-9+/=]+)$/);
  if (region) {
    probeFiles[region[1]] = region[2];
    return;
  }
  if (text.startsWith("REGION_STORAGE_PASS ")) {
    postMessage({type: "probe-complete", runtime: probeRuntime, mode: probeMode, summary: text,
      backend: globalThis.__gaiusFsBackend, stats: {...globalThis.__gaiusStorageStats}, files: probeFiles});
  }
  probeConsoleLog(...values);
};
console.info = console.log;
globalThis.addEventListener("unhandledrejection", event => {
  postMessage({type: "probe-error", detail: String(event.reason?.stack || event.reason)});
});
postMessage({type: "probe-phase", detail: "worker-loaded"});
