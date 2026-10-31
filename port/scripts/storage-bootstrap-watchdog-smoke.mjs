// Regression smoke for page-level IndexedDB bootstrap hangs.
// Run with: node port/scripts/storage-bootstrap-watchdog-smoke.mjs
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
if (!source.includes("database.onversionchange = () =>")) {
  throw new Error("IndexedDB connections must close on versionchange");
}
if (!source.includes('storage-bootstrap-timeout')) {
  throw new Error("bootstrap timeout telemetry is missing");
}
if (!source.includes('storage-bootstrap-recovery')) {
  throw new Error("retryable bootstrap recovery is missing");
}
for (const event of ["storage-indexeddb-blocked", "storage-indexeddb-error",
  "storage-indexeddb-upgradeneeded", "storage-indexeddb-versionchange"]) {
  if (!source.includes(event)) throw new Error(`${event} telemetry is missing`);
}

const helperStart = source.indexOf("      const storageBootstrapTimeoutMs = 8000;");
const helperEnd = source.indexOf("\n      async function readIndexedDbFiles", helperStart);
if (helperStart < 0 || helperEnd < 0) throw new Error("watchdog helper not found");
const reports = [];
const context = vm.createContext({
  performance: {now: () => Date.now()},
  setTimeout,
  clearTimeout,
  Promise,
  report: (event, detail) => reports.push({event, detail}),
});
vm.runInContext(`${source.slice(helperStart, helperEnd)}; globalThis.storageBootstrapTimeout = storageBootstrapTimeout;`, context);

let settled = false;
try {
  await context.storageBootstrapTimeout(new Promise(() => {}), "fake-idb-transaction");
} catch (error) {
  settled = /fake-idb-transaction timed out/.test(String(error));
}
if (!settled) throw new Error("a never-settling IndexedDB operation was not bounded");
if (!reports.some(item => item.event === "storage-bootstrap-timeout" && /fake-idb-transaction/.test(item.detail))) {
  throw new Error("timeout telemetry did not identify the blocked phase");
}

// Exercise the late-callback guard: a transaction that completes after the bootstrap
// has timed out must be aborted and must not delete the localStorage source copy.
const txStart = source.indexOf("      function transactionDone(transaction)");
const txEnd = source.indexOf("\n      const storageBootstrapTimeoutMs", txStart);
const migrationStart = source.indexOf("      async function migrateLocalStorage");
const migrationEnd = source.indexOf("\n      function loadLocalStorageFallback", migrationStart);
if (txStart < 0 || txEnd < 0 || migrationStart < 0 || migrationEnd < 0) throw new Error("migration helpers not found");
const lateContext = vm.createContext({
  Promise, setTimeout, clearTimeout, Date, String, Object, Array, Map, Error,
  window: {localStorage: (() => {
    const values = new Map([["gaius:/late.txt", "old-value"]]);
    return {get length(){return values.size;}, key:i=>[...values.keys()][i] ?? null,
      getItem:k=>values.get(k) ?? null, removeItem:k=>values.delete(k), values};
  })()},
  prefix: "gaius:", files: Object.create(null), storeName: "files",
  normalize: value => value, isClientBootstrapPath: () => true,
  report: () => {},
});
const fakeTx = () => {
  const tx = {error:null, aborted:false, objectStore(){return {put(){}}}, abort(){this.aborted=true; if(this.onabort)this.onabort();}};
  setTimeout(() => { if (!tx.aborted && tx.oncomplete) tx.oncomplete(); }, 40);
  return tx;
};
lateContext.database = {transaction(){ return fakeTx(); }};
vm.runInContext(`${source.slice(txStart, txEnd)}\n${source.slice(migrationStart, migrationEnd)}; globalThis.migrateLocalStorage=migrateLocalStorage;`, lateContext);
const control = {active:true, transactions:new Set(), abort(){this.active=false; for(const tx of this.transactions)tx.abort();}};
const latePromise = lateContext.migrateLocalStorage(lateContext.database, control);
for (let i = 0; i < 100 && control.transactions.size === 0; i++) await new Promise(resolve => setTimeout(resolve, 0));
control.abort();
const lateResult = await latePromise;
if (lateContext.window.localStorage.values.has("gaius:/late.txt") === false) {
  throw new Error("late migration callback was allowed to delete its localStorage source");
}

console.log(JSON.stringify({ok: true, timeoutMs: 8000, telemetry: reports}));
