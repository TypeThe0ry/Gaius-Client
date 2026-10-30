// Runs sections of the suite's TeaVM WebAssembly GC build in node:
//   node --experimental-wasm-jspi run-wasm.cjs <gaius-wasm-gc-runtime.js> <gaius-jspi.js|-> <classes.wasm> <section>...
// With a gaius-jspi.js (the product's JSPI mode, gaius.teavm.jspi=true) the main thread starts as a
// WebAssembly.promising activation through GaiusJspi, as port/web/runtime/wasmgc/gaius-wasm-boot.js
// does in the browser; with "-" the module's main export is called directly (a coroutine build).
// Exit codes: 0 main returned; 4 main trapped or threw ("TRAP <text>"); 5 the module did not
// compile or instantiate ("MODULE_INVALID <text>").
"use strict";
const [runtime, jspiScript, wasm, ...sections] = process.argv.slice(2);
const describe = error => String(error && error.stack || error).split("\n").slice(0, 8).join(" | ");
let finished = false;
function finish(code, line) {
  if (finished) return;
  finished = true;
  if (line) console.log(line);
  // Let console.log drain before exiting.
  setTimeout(() => process.exit(code), 20);
}
process.on("uncaughtException", error => finish(4, "TRAP " + describe(error)));
process.on("unhandledRejection", error => finish(4, "TRAP " + describe(error)));
require(runtime);
const useJspi = jspiScript && jspiScript !== "-";
if (useJspi) require(jspiScript);
(async () => {
  let jspi = null;
  let teavm;
  try {
    teavm = await globalThis.TeaVM.wasmGC.load(wasm, {
      nodejs: true,
      installImports(imports) {
        if (!useJspi) return;
        const scheduler = globalThis.GaiusJspi.createScheduler();
        jspi = globalThis.GaiusJspi.installJspi(imports, scheduler, () => teavm.instance.exports,
          error => finish(4, "TRAP thread " + describe(error)));
        globalThis.GaiusJspi.routeEventQueue(imports, scheduler);
      }
    });
  } catch (error) {
    finish(5, "MODULE_INVALID " + describe(error));
    return;
  }
  if (useJspi) {
    jspi.startMain(sections).then(() => finish(0), error => finish(4, "TRAP " + describe(error)));
  } else {
    try {
      // A coroutine build may suspend and resume from the event loop: node exits when it is done.
      teavm.exports.main(sections);
    } catch (error) {
      finish(4, "TRAP " + describe(error));
    }
  }
})();
