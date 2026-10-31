#!/usr/bin/env node

import {readFile} from "node:fs/promises";
import {isIP} from "node:net";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const path = resolve(root, "relay-nodes.json");
const registry = JSON.parse(await readFile(path, "utf8"));

function fail(message) {
    throw new Error(`relay-nodes.json: ${message}`);
}

function normalizedHostname(url) {
    const host = url.hostname.toLowerCase();
    return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}

function isPrivateHost(host) {
    if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return true;
    }
    const version = isIP(host);
    if (version === 4) {
        const octets = host.split(".").map(Number);
        return octets[0] === 10 || octets[0] === 127 || octets[0] === 0 ||
            (octets[0] === 169 && octets[1] === 254) ||
            (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
            (octets[0] === 192 && octets[1] === 168);
    }
    if (version === 6) {
        return host === "::" || host === "::1" || host.startsWith("fe8") ||
            host.startsWith("fe9") || host.startsWith("fea") ||
            host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd");
    }
    return false;
}

if (registry?.kind !== "gaius-relay-registry") fail("kind must be gaius-relay-registry");
if (registry?.protocolVersion !== 1) fail("protocolVersion must be 1");
if (!Array.isArray(registry?.nodes)) fail("nodes must be an array");
if (registry.nodes.length > 64) fail("nodes must contain at most 64 entries");
if (registry.registries !== undefined && !Array.isArray(registry.registries)) {
    fail("registries must be an array when present");
}
if ((registry.registries?.length ?? 0) > 16) {
    fail("registries must contain at most 16 entries");
}
if (typeof registry.updatedAt !== "string" || !Number.isFinite(Date.parse(registry.updatedAt))) {
    fail("updatedAt must be an ISO-8601 timestamp");
}

const seen = new Set();
for (let index = 0; index < registry.nodes.length; index++) {
    const node = registry.nodes[index];
    if (!node || typeof node !== "object" || Array.isArray(node)) {
        fail(`nodes[${index}] must be an object`);
    }
    if (Object.hasOwn(node, "token")) {
        fail(`nodes[${index}] must not publish a bearer token`);
    }
    if (node.id !== undefined &&
            (typeof node.id !== "string" ||
                !/^[a-z0-9][a-z0-9._-]{0,63}$/u.test(node.id))) {
        fail(`nodes[${index}].id is invalid`);
    }
    if (typeof node.name !== "string" || node.name.trim().length === 0 ||
            node.name.trim().length > 80 || /[\r\n]/u.test(node.name)) {
        fail(`nodes[${index}].name must contain 1-80 single-line characters`);
    }
    if (node.priority !== undefined &&
            (!Number.isInteger(node.priority) || node.priority < -10000 || node.priority > 10000)) {
        fail(`nodes[${index}].priority must be an integer between -10000 and 10000`);
    }
    let url;
    try {
        url = new URL(node.url);
    } catch {
        fail(`nodes[${index}].url is invalid`);
    }
    if (url.protocol !== "wss:") fail(`nodes[${index}].url must use wss`);
    if (url.pathname !== "/tunnel" || url.search || url.hash || url.username || url.password) {
        fail(`nodes[${index}].url must be an origin plus /tunnel without credentials or query data`);
    }
    const host = normalizedHostname(url);
    if (!host || isPrivateHost(host)) fail(`nodes[${index}].url must use a public host`);
    const key = url.href.toLowerCase();
    if (seen.has(key)) fail(`nodes[${index}].url duplicates an earlier node`);
    seen.add(key);
}

const seenRegistries = new Set();
for (let index = 0; index < (registry.registries?.length ?? 0); index++) {
    let url;
    try {
        url = new URL(registry.registries[index]);
    } catch {
        fail(`registries[${index}] is invalid`);
    }
    if (url.protocol !== "https:" || url.username || url.password || url.hash) {
        fail(`registries[${index}] must use HTTPS without credentials or a fragment`);
    }
    const host = normalizedHostname(url);
    if (!host || isPrivateHost(host)) fail(`registries[${index}] must use a public host`);
    const key = url.href.toLowerCase();
    if (seenRegistries.has(key)) fail(`registries[${index}] duplicates an earlier registry`);
    seenRegistries.add(key);
}

console.log(JSON.stringify({
    ok: true,
    nodes: registry.nodes.length,
    registries: registry.registries?.length ?? 0,
    path,
}));
