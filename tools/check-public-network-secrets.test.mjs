import assert from "node:assert/strict";
import {test} from "node:test";
import {publicIpv4InLine} from "./check-public-network-secrets.mjs";

// The addresses are assembled at run time so this file does not trip the scanner it tests.
const dotted = (...parts) => parts.join(".");
const [dns, a, b, doc, version] = [dotted(8, 8, 8, 8), dotted(1, 2, 3, 4), dotted(5, 6, 7, 8),
  dotted(203, 1, 2, 3), dotted(25, 0, 4, 1)];

test("public IPv4 literals are reported", () => {
  assert.deepEqual(publicIpv4InLine(`relay at ${dns}:25565`), [dns]);
  assert.deepEqual(publicIpv4InLine(`a ${a} b ${b}`), [a, b]);
  assert.deepEqual(publicIpv4InLine(`jdk ${dns}`), [dns]);
  assert.deepEqual(publicIpv4InLine(`java host ${doc}`), [doc]);
});

test("private, loopback and documentation ranges stay allowed", () => {
  for (const ip of ["10.0.0.1", "127.0.0.1", "192.168.1.5", "172.16.0.1", "203.0.113.9", "0.0.0.0"]) {
    assert.deepEqual(publicIpv4InLine(`host ${ip}`), []);
  }
});

test("dotted JDK versions written as such are not addresses", () => {
  for (const line of [
    "$env:GAIUS_JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot'",
    "Environment: Windows, Temurin OpenJDK 25.0.4.1, Python 3.12.7.",
    "JDK 25.0.4.1",
    "JAVA_HOME=/opt/java-25.0.4.101",
    "Temurin 21.0.9.10",
  ]) assert.deepEqual(publicIpv4InLine(line), [], line);
});

test("a version-like number without a JDK name is still reported", () => {
  assert.deepEqual(publicIpv4InLine(`server ${version} answered`), [version]);
  assert.deepEqual(publicIpv4InLine(`jdk ${dns} and ${version}`), [dns, version]);
});

test("browser user-agent versions are not addresses", () => {
  for (const line of [
    `"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${dotted(141, 0, 0, 0)} Safari/537.36"`,
    `HeadlessChrome/${dotted(141, 0, 7390, 54)}`,
    `Edg/${dotted(141, 0, 0, 0)}`,
  ]) assert.deepEqual(publicIpv4InLine(line), [], line);
  assert.deepEqual(publicIpv4InLine(`Chrome at ${dns}`), [dns]);
  assert.deepEqual(publicIpv4InLine(`Chrome ${dns}`), [dns]);
});
