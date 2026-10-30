import { readFileSync } from 'node:fs';

// Shared by the real-browser runner and its independent evidence validator.
export const expectedResourcePack = Object.freeze(process.env.RESOURCE_PACK_EXPECTATION_FILE
  ? JSON.parse(readFileSync(process.env.RESOURCE_PACK_EXPECTATION_FILE, 'utf8')) : {
    originalUrl: 'https://jihulab.com/-/project/356228/uploads/f3b0d7bcd078e180e45bd14b0c1e722e/resource_pack.zip',
    fixedMirrorUrl: null,
    bytes: 62_505_042,
    sha1: '46efabcdbc73928ef3f68df1ece583a413069dc1',
    sha256: '380bd6c8b3690404b334d2ecdca0bdf8fc1be554780c11710ad939413330f4d7',
  });

if (!Number.isSafeInteger(expectedResourcePack.bytes) || expectedResourcePack.bytes <= 0
    || !/^[0-9a-f]{40}$/.test(expectedResourcePack.sha1)
    || !/^[0-9a-f]{64}$/.test(expectedResourcePack.sha256)
    || !['http:', 'https:'].includes(new URL(expectedResourcePack.originalUrl).protocol)) {
  throw new Error('Invalid resource pack expectation: require URL, byte length, SHA1 and SHA256');
}
