import assert from "node:assert/strict";
import test from "node:test";
import {filterAllowedTargets, isHostAllowed, isOriginAllowed, isPrivateNetworkAddress} from "./dist/policy.js";

test("file URL origin spelling is accepted only by the explicit null-origin allow-list", () => {
    assert.equal(isOriginAllowed("file://", ["null"]), true);
    assert.equal(isOriginAllowed("file://", ["https://play.example"]), false);
    assert.equal(isOriginAllowed("null", ["null"]), true);
});

test("wildcard origin policy accepts browser, opaque, and file origins but not a missing Origin", () => {
    for (const origin of ["https://gaius.fun", "https://random.example", "http://localhost:8080", "null", "file://"]) {
        assert.equal(isOriginAllowed(origin, ["*"]), true, origin);
    }
    assert.equal(isOriginAllowed(undefined, ["*"]), false);
});

test("SRV targets remain inside the configured host allow-list", () => {
    const targets = [
        {host: "mc.example.com", port: 25565},
        {host: "attacker.example.net", port: 25565},
    ];
    assert.deepEqual(
        filterAllowedTargets(targets, ["mc.example.com"]),
        [targets[0]]
    );
    assert.equal(isHostAllowed("attacker.example.net", ["mc.example.com"]), false);
});

test("private address policy blocks IPv4 transition forms", () => {
    for (const address of [
        "::1",
        "::ffff:127.0.0.1",
        "::7f00:1",
        "64:ff9b::7f00:1",
        "64:ff9b::a9fe:a9fe",
        "2002:7f00:1::",
        // Teredo's final IPv4 value is one's-complement encoded.
        "2001:0:4136:e378:8000:63bf:ffff:fffe",
    ]) {
        assert.equal(isPrivateNetworkAddress(address), true, address);
    }
});

test("private address policy covers HTTP SSRF literal targets", () => {
    for (const address of ["0.0.0.0", "10.0.0.8", "127.0.0.1", "169.254.169.254", "192.168.1.1", "[::1]"]) {
        assert.equal(isPrivateNetworkAddress(address), true, address);
    }
});

test("private address policy keeps ordinary public addresses usable", () => {
    for (const address of ["::ffff:101:101", "64:ff9b::808:808", "2002:808:808::"]) {
        assert.equal(isPrivateNetworkAddress(address), false, address);
    }
});
