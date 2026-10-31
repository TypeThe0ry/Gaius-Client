import {readFile} from "node:fs/promises";
import {resolve} from "node:path";

const root = resolve(process.cwd(), "port");
const patcher = await readFile(resolve(root, "tools/src/main/java/dev/gaius/tools/patches/network/NetworkPatches.java"), "utf8");
const client = await readFile(resolve(root, "tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java"), "utf8");
const server = await readFile(resolve(root, "src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java"), "utf8");
if (!patcher.includes('"exceptionCaught"')) throw new Error("Connection exceptionCaught hook is missing");
if (!patcher.includes('"reportConnectionException"')) throw new Error("Connection diagnostic target is missing");
if (!client.includes("patchConnectionExceptionDiagnostics")) throw new Error("Connection diagnostic patch is not registered");
if (!server.includes('describeWithStack(exception)')) {
  throw new Error("Connection diagnostic report does not include cause-chain stack");
}
if (!server.includes("gaius-client")) throw new Error("Connection diagnostics are not page-visible");
console.log("connection-exception-diagnostics-smoke: PASS");
