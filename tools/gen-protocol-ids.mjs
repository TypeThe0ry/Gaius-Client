import {runGenerator} from "../apps/bridge/scripts/gen-protocol-ids.mjs";

try { runGenerator(process.argv.slice(2)); }
catch (error) { console.error(error.message); process.exitCode = 1; }
