"use strict";
postMessage({type: "probe-phase", detail: `loader-imported ${probeRuntime} ${probeMode}`});
if (probeRuntime === "js") {
  importScripts(`/probe/${probeProfile}/js/classes.js`);
  const probeMain = globalThis.main;
  globalThis.main = () => {
    postMessage({type: "probe-phase", detail: "js-main"});
    probeMain([probeMode, probeDirectory], result => {
      if (result) postMessage({type: "probe-error", detail: String(result.stack || result)});
      else if (probeMode === "write" && !Object.keys(probeFiles).length) {
        postMessage({type: "probe-error", detail: "Java main returned without region output"});
      }
    });
  };
} else {
  importScripts(`/probe/${probeProfile}/wasm/gaius-wasm-gc-runtime.js`,
    `/probe/${probeProfile}/wasm/gaius-jspi.js`);
  globalThis.main = () => {
    postMessage({type: "probe-phase", detail: "wasm-loading"});
    let probeTeaVM;
    let probeJspi;
    return globalThis.TeaVM.wasmGC.load(`/probe/${probeProfile}/wasm/classes.wasm`, {
      installImports(imports) {
        const scheduler = globalThis.GaiusJspi.createScheduler();
        probeJspi = globalThis.GaiusJspi.installJspi(imports, scheduler,
          () => probeTeaVM.instance.exports, error => {
            postMessage({type: "probe-error", detail: String(error?.stack || error)});
          });
        globalThis.GaiusJspi.routeEventQueue(imports, scheduler);
      },
    }).then(teavm => {
      probeTeaVM = teavm;
      postMessage({type: "probe-phase", detail: "wasm-main"});
      return probeJspi.startMain([probeMode, probeDirectory]);
    }).catch(error => postMessage({type: "probe-error", detail: String(error?.stack || error)}));
  };
}
