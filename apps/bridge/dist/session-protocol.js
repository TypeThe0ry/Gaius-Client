import {createHash} from "node:crypto";
import {LOGIN_LAYOUT_VERSION, PROTOCOL_SCHEMA_VERSION, validateProtocolTable} from "./protocol-schema.js";

export const SESSION_PROTOCOL_FORMAT_VERSION = 1;
export const MAXIMUM_SESSION_PROTOCOL_BYTES = 16 * 1024;
export const MAXIMUM_CONNECT_CONTROL_BYTES = 64 * 1024;

// Strict exact-key contract: each declaration object (envelope, table and
// provenance) must carry exactly the listed keys, no more and no fewer. Unknown
// keys are rejected on purpose instead of ignored, so a table can never carry a
// field this relay does not understand into a session. Forward evolution goes
// through a version bump (formatVersion, schemaVersion or loginLayoutVersion)
// together with a relay release that knows the new layout. A relay without it
// closes such a connect with 1007; a client that must reach older relays sends
// no declaration (the session stays an opaque tunnel) instead of extra keys.
function requireFields(value, fields, label) {
    if (value === null || typeof value !== "object" || Array.isArray(value) ||
        Object.keys(value).length !== fields.length || fields.some(field => !Object.hasOwn(value, field))) {
        throw new TypeError(`Invalid ${label} fields`);
    }
}

export function parseSessionProtocolDeclaration(value) {
    if (value === undefined) return undefined;
    requireFields(value, ["formatVersion", "sha256", "table"], "session protocol declaration");
    if (value.formatVersion !== SESSION_PROTOCOL_FORMAT_VERSION ||
        typeof value.sha256 !== "string" || !/^[0-9a-f]{64}$/u.test(value.sha256) ||
        typeof value.table !== "string") {
        throw new TypeError("Invalid session protocol format or hash");
    }
    if (Buffer.byteLength(value.table, "utf8") > MAXIMUM_SESSION_PROTOCOL_BYTES) {
        throw new TypeError("Session protocol table exceeds 16 KiB");
    }
    const sha256 = createHash("sha256").update(value.table, "utf8").digest("hex");
    if (sha256 !== value.sha256) throw new TypeError("Session protocol hash mismatch");
    const table = JSON.parse(value.table);
    requireFields(table, ["schemaVersion", "name", "protocolVersion", "loginLayoutVersion",
        "source", "login", "configuration", "play"], "session protocol table");
    if (table.schemaVersion !== PROTOCOL_SCHEMA_VERSION || table.loginLayoutVersion !== LOGIN_LAYOUT_VERSION) {
        throw new TypeError("Unsupported session protocol layout");
    }
    if (typeof table.name !== "string" || table.name.length > 128) {
        throw new TypeError("Invalid session protocol name");
    }
    const provenanceFields = table.source?.kind === "vanilla-client-reports"
        ? ["kind", "clientSha1", "expectedServerSha1", "packetsSha256"]
        : ["kind", "serverSha1", "packetsSha256"];
    requireFields(table.source, provenanceFields, "session protocol provenance");
    if (provenanceFields.some(field => typeof table.source[field] !== "string")) {
        throw new TypeError("Invalid session protocol provenance values");
    }
    const profile = validateProtocolTable(table, table.protocolVersion);
    if (profile === undefined) throw new TypeError("Unsupported session protocol packet id range");
    return Object.freeze({formatVersion: value.formatVersion, sha256, profile});
}

export function createSessionProtocolDeclaration(table) {
    const text = JSON.stringify(table);
    const declaration = {formatVersion: SESSION_PROTOCOL_FORMAT_VERSION,
        sha256: createHash("sha256").update(text, "utf8").digest("hex"), table: text};
    parseSessionProtocolDeclaration(declaration);
    return Object.freeze(declaration);
}

export function sessionProtocolAcknowledgement(declaration) {
    if (declaration === undefined) return undefined;
    return {formatVersion: declaration.formatVersion, sha256: declaration.sha256,
        protocolVersion: declaration.profile.protocolVersion};
}
