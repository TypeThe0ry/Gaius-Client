import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const source = await readFile(new URL(
  "../overrides/libraries/lwjgl/src/target-wasmgc/java/org/lwjgl/system/BrowserMemory.java",
  import.meta.url), "utf8");
assert.match(source, /gaiusMemoryTier.*low.*512 \* 1024/s,
  "low memory tier must cap the reusable native pool at 512 KiB");
assert.match(source, /__gaiusIsLowMemoryTier/,
  "native pool must use the shared boot low-tier decision");
assert.match(source, /return 2 \* 1024 \* 1024/,
  "default native pool cap must remain 2 MiB");
console.log("browser memory low pool configuration smoke passed");
