#!/usr/bin/env node

import {constants as zlibConstants, createBrotliCompress} from "node:zlib";
import {createReadStream, createWriteStream} from "node:fs";
import {rename, unlink} from "node:fs/promises";
import {pipeline} from "node:stream/promises";

const input = process.argv[2];
if (!input || input.includes("\0")) {
  process.stderr.write("usage: compress-brotli.mjs <file>\n");
  process.exit(2);
}

const output = input + ".br";
const temporary = output + `.part-${process.pid}`;
try {
  await pipeline(
    createReadStream(input),
    createBrotliCompress({
      params: {
        [zlibConstants.BROTLI_PARAM_QUALITY]: 11,
      },
    }),
    createWriteStream(temporary, {mode: 0o644}),
  );
  await rename(temporary, output);
} finally {
  await unlink(temporary).catch((error) => {
    if (error?.code !== "ENOENT") throw error;
  });
}
