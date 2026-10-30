import {readFileSync, writeFileSync, mkdirSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {createSessionProtocolDeclaration} from "../../apps/bridge/dist/session-protocol.js";
import {loadVersionProfiles} from "../../apps/bridge/scripts/gen-protocol-ids.mjs";

const repository = fileURLToPath(new URL("../../", import.meta.url));

export function buildRelayProtocolDeclaration(root, profileId) {
    const [profile] = loadVersionProfiles(root, profileId);
    const table = JSON.parse(readFileSync(join(root, "apps", "bridge", "protocols",
        profile.protocolVersion + ".json"), "utf8"));
    const sourceHash = table.source?.kind === "vanilla-client-reports"
        ? table.source.expectedServerSha1 : table.source?.serverSha1;
    if (table.protocolVersion !== profile.protocolVersion || table.name !== profile.id ||
        table.loginLayoutVersion !== profile.relayProtocol.loginLayoutVersion ||
        sourceHash !== profile.official.serverSha1 ||
        (table.source?.kind === "vanilla-client-reports" && table.source.clientSha1 !== profile.official.clientSha1)) {
        throw new Error("Relay protocol table does not match the selected profile");
    }
    return createSessionProtocolDeclaration(table);
}

export function relayProtocolScript(declaration) {
    const literal = JSON.stringify(declaration).replaceAll("<", "\\u003c")
        .replaceAll("\u2028", "\\u2028").replaceAll("\u2029", "\\u2029");
    return "Object.defineProperty(globalThis, '__gaiusRelayProtocolDeclaration', {value: Object.freeze(" +
        literal + "), configurable: false, writable: false});\n";
}

export function stageRelayProtocol(root, profileId, output) {
    const declaration = buildRelayProtocolDeclaration(root, profileId);
    mkdirSync(output, {recursive: true});
    writeFileSync(join(output, "relay-protocol.js"), relayProtocolScript(declaration), "utf8");
    return declaration;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const args = process.argv.slice(2);
    if (args.length !== 4 || args[0] !== "--profile" || args[2] !== "--out") {
        throw new Error("Usage: node port/scripts/build-relay-protocol.mjs --profile <id> --out <dist>");
    }
    const declaration = stageRelayProtocol(repository, args[1], resolve(args[3]));
    console.log(`Relay session protocol: ${Buffer.byteLength(declaration.table)} bytes, SHA-256 ${declaration.sha256}`);
}
