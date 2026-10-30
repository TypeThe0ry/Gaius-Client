#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const path = process.env.GAIUS_PORTABLE_SOURCE
  ? new URL(process.env.GAIUS_PORTABLE_SOURCE, import.meta.url)
  : new URL("./build-portable-html.py", import.meta.url);
const source = await readFile(path, "utf8");
assert.match(source, /const useSynchronousDecoder = \(\) =>/,
  "portable decoder must define a synchronous recovery path");
assert.match(source, /worker\.onerror = \(event\) => \{[\s\S]*?useSynchronousDecoder\(\);/,
  "worker errors must switch to the synchronous decoder");
assert.match(source, /for \(const \[id, messages\] of sent\)[\s\S]{0,220}pending\.has\(id\)[\s\S]{0,180}post\(message\)/,
  "only pending messages must be replayed after worker failure");
assert.match(source, /const deliver = \(message\) => \{\s*sent\.delete\(message\.id\);/,
  "completed decoder tasks must release their encoded messages");
assert.match(source, /let synchronous = false;[\s\S]{0,180}if \(synchronous\) return;/,
  "worker failure recovery must switch to the synchronous decoder once");
console.log("portable decoder fallback checks passed");
