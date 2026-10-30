#!/usr/bin/env node

import {execFileSync} from "node:child_process";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";

const ipv4 = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
// A four-component number written right after a JDK product name is a Java
// version string (jdk-25.0.4.101, Temurin OpenJDK 25.0.4.1), not an address.
const javaVersionPrefix = /(?:jdk|jre|java|temurin|corretto|zulu|graalvm)[\s_.=:@v-]*$/iu;
// A browser user-agent writes its version right after the product token
// (Chrome/141.0.0.0, Edg/141.0.0.0), which is not an address either.
const browserVersionPrefix = /(?:chrome|chromium|headlesschrome|crios|edg|edga|edgios|opr)\/$/iu;
const allowed = ip => {
  const octets = ip.split(".").map(Number);
  if (octets.some(value => value > 255)) return true;
  const [a, b] = octets;
  return a === 0 || a === 10 || a === 127 || a >= 224
    || (a === 100 && b >= 64 && b <= 127)
    || (a === 169 && b === 254)
    || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && (b === 0 || b === 168))
    || (a === 198 && (b === 18 || b === 19 || b === 51))
    || (a === 203 && b === 0);
};
const javaVersion = (ip, prefix) => {
  const [feature, interim] = ip.split(".").map(Number);
  return feature >= 9 && feature <= 40 && interim <= 9 && javaVersionPrefix.test(prefix);
};

export function publicIpv4InLine(line) {
  const found = [];
  for (const match of line.matchAll(ipv4)) {
    const ip = match[0];
    const prefix = line.slice(0, match.index);
    if (allowed(ip) || javaVersion(ip, prefix) || browserVersionPrefix.test(prefix)) continue;
    found.push(ip);
  }
  return found;
}

function scanTrackedFiles() {
  const tracked = execFileSync("git", ["ls-files", "-z"], {encoding: "utf8"})
    .split("\0").filter(Boolean);
  const findings = [];
  for (const path of tracked) {
    let text;
    try {
      const bytes = readFileSync(path);
      if (bytes.includes(0)) continue;
      text = bytes.toString("utf8");
    } catch {
      continue;
    }
    text.split(/\r?\n/u).forEach((line, index) => {
      for (const ip of publicIpv4InLine(line)) findings.push(`${path}:${index + 1}: ${ip}`);
    });
  }
  return {tracked: tracked.length, findings};
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const {tracked, findings} = scanTrackedFiles();
  if (findings.length) {
    console.error("Public IPv4 literals found in tracked files:");
    console.error(findings.join("\n"));
    process.exit(1);
  }
  console.log(`public network secret scan passed (${tracked} tracked files)`);
}
