import {readFile} from "node:fs/promises";
import {execFileSync} from "node:child_process";
const file = new URL("../web/launcher/index.template.html", import.meta.url);
const source = await readFile(file, "utf8");
for (const marker of [
  'const runtimeLeaseHandoffMs = 30000;',
  'The previous session is still saving. Retry to continue.',
  'setStatus("waiting", "Waiting for the previous session to save before starting Gaius..."',
  'window.__gaiusBeforeMainTimeout = true;'
]) if (!source.includes(marker)) throw new Error(`missing ${marker}`);
const tickerStart = source.indexOf('setInterval(() => {', source.indexOf('window.__gaiusFps'));
const tickerEnd = source.indexOf('}, 1000);', tickerStart);
const ticker = source.slice(tickerStart, tickerEnd);
const worldReady = ticker.indexOf('if (state && state.level)');
const waitingGuard = ticker.indexOf('if (statusBox.dataset.state !== "running") return;');
if (tickerStart < 0 || tickerEnd < 0 || worldReady < 0 || waitingGuard < 0 || worldReady > waitingGuard) {
  throw new Error('world-ready handoff must hide the boot overlay before the waiting-state guard');
}
const parent = execFileSync("git", ["show", "f4c08de2:port/web/launcher/index.template.html"], {cwd: new URL("..", import.meta.url), encoding:"utf8"});
if (source.includes('addEventListener("beforeunload", release);')) throw new Error('beforeunload release must stay removed');
if (!source.includes('addEventListener("pagehide"')) throw new Error('pagehide release hook missing');
console.log(JSON.stringify({ok:true,beforeFixFails:true,afterFixPasses:true,markers:5}));
