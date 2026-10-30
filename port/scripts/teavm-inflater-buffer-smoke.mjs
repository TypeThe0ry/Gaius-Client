#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const supportSource = await readFile(new URL(
  "../overrides/jdk/21/src/main/java/org/teavm/classlib/java/util/zip/" +
    "TZipModernSupport.java",
  import.meta.url,
), "utf8");
const platformSmokeSource = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/PlatformSmoke.java",
  import.meta.url,
), "utf8");

function between(source, start, end) {
  const startOffset = source.indexOf(start);
  const endOffset = source.indexOf(end, startOffset + start.length);
  assert.ok(startOffset >= 0 && endOffset > startOffset,
    `could not extract ${start} .. ${end}`);
  return source.slice(startOffset, endOffset);
}

const setInputSource = between(
  supportSource,
  "public static void setInput(TInflater inflater, ByteBuffer buffer)",
  "public static int inflate(TInflater inflater, ByteBuffer buffer)",
);
const inflateSource = supportSource.slice(supportSource.indexOf(
  "public static int inflate(TInflater inflater, ByteBuffer buffer)"));

const inputAllocationOffset = setInputSource.indexOf("byte[] input = new byte[");
const inputFastPathOffset = setInputSource.indexOf("if (buffer.hasArray())");
assert.ok(inputFastPathOffset >= 0 && inputFastPathOffset < inputAllocationOffset,
  "heap input fast path must run before the fallback allocation");
assert.match(setInputSource,
  /int position = buffer\.position\(\);[\s\S]*int remaining = buffer\.remaining\(\);/,
  "heap input does not snapshot position and remaining exactly once");
assert.match(setInputSource,
  /inflater\.setInput\(\s*buffer\.array\(\),\s*buffer\.arrayOffset\(\) \+ position,\s*remaining\s*\);/,
  "heap input does not pass backing array + arrayOffset + position + remaining");
assert.match(setInputSource,
  /buffer\.position\(position \+ remaining\);\s*return;/,
  "heap input does not advance to the old limit after setInput succeeds");
assert.match(setInputSource,
  /byte\[\] input = new byte\[buffer\.remaining\(\)\];\s*buffer\.get\(input\);\s*inflater\.setInput\(input\);/,
  "direct/read-only input fallback no longer preserves the copy path");

const outputAllocationOffset = inflateSource.indexOf("byte[] output = new byte[");
const outputFastPathOffset = inflateSource.indexOf(
  "if (buffer.hasArray() && !buffer.isReadOnly())");
assert.ok(outputFastPathOffset >= 0 && outputFastPathOffset < outputAllocationOffset,
  "writable heap output fast path must run before the fallback allocation");
assert.match(inflateSource,
  /int count = inflater\.inflate\(\s*buffer\.array\(\),\s*buffer\.arrayOffset\(\) \+ position,\s*buffer\.remaining\(\)\s*\);/,
  "heap output does not use the three-argument backing-array inflate call");
assert.match(inflateSource,
  /buffer\.position\(position \+ count\);\s*return count;/,
  "heap output does not advance by the exact inflated count");
assert.match(inflateSource,
  /buffer\.hasArray\(\) && !buffer\.isReadOnly\(\)/,
  "read-only heap buffers must not enter the writable output fast path");
assert.match(inflateSource,
  /byte\[\] output = new byte\[buffer\.remaining\(\)\];\s*int count = inflater\.inflate\(output\);\s*buffer\.put\(output, 0, count\);/,
  "direct/read-only output fallback no longer preserves the copy path");

const compressionSmoke = between(
  platformSmokeSource,
  "private static void testNetworkCompression()",
  "private static void testNetworkPackedLongs()",
);
assert.match(compressionSmoke, /for \(int round = 0; round < 2; round\+\+\)/,
  "platform smoke no longer runs two consecutive compressed streams");
assert.match(compressionSmoke, /deflater\.reset\(\);\s*inflater\.reset\(\);/,
  "platform smoke no longer verifies inflater reuse through reset");
assert.match(compressionSmoke,
  /inputRoot\.slice\(\)[\s\S]*compressedBuffer\.position\(inputPosition\)/,
  "platform smoke no longer covers non-zero position on an array-offset input slice");
assert.match(compressionSmoke,
  /outputRoot\.slice\(\)[\s\S]*output\.position\(outputPosition\)/,
  "platform smoke no longer covers non-zero position on an array-offset output slice");
assert.match(compressionSmoke,
  /ByteBuffer\.allocateDirect\([\s\S]*inflater\.setInput\(compressedBuffer\)[\s\S]*ByteBuffer\.allocateDirect\([\s\S]*inflater\.inflate\(output\)/,
  "platform smoke no longer covers both direct-buffer fallback directions");
assert.match(compressionSmoke,
  /catch \(DataFormatException expected\)[\s\S]*malformedOutput\.position\(\) != outputStart/,
  "platform smoke no longer rejects malformed data without false output progress");
assert.match(compressionSmoke,
  /inflateNetworkArrayBaseline[\s\S]*Arrays\.equals\(input, baseline\)[\s\S]*Arrays\.equals\(baseline, actual\)/,
  "ByteBuffer output is no longer compared with the JVM byte-array inflater baseline");

console.log("TeaVM inflater ByteBuffer fast-path smoke passed");
