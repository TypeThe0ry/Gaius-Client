import assert from "node:assert/strict";
import {createReadStream} from "node:fs";
import {cp, readFile, readdir, realpath, stat} from "node:fs/promises";
import {dirname, isAbsolute, join, relative, resolve, sep} from "node:path";
import {createHash} from "node:crypto";

export function containedCapturePath(root, path) {
  const resolved = resolve(path), child = relative(resolve(root), resolved);
  assert.ok(child && !isAbsolute(child) && child !== ".." && !child.startsWith(".." + sep), "snapshot must stay inside the output directory");
  return resolved;
}

async function hashProfile(directory) {
  const root = await realpath(directory), digest = createHash("sha256");
  let files = 0, bytes = 0, entries = 0;
  const visit = async path => {
    const children = await readdir(path, {withFileTypes: true});
    children.sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
    for (const child of children) {
      assert.ok(++entries <= 20000, "snapshot profile entry bound exceeded");
      assert.ok(!child.isSymbolicLink(), "snapshot profile must not contain links");
      const childPath = containedCapturePath(root, await realpath(join(path, child.name)));
      if (child.isDirectory()) { await visit(childPath); continue; }
      assert.ok(child.isFile(), "snapshot profile must contain ordinary files");
      bytes += (await stat(childPath)).size; files++;
      assert.ok(bytes <= 2 * 1024 * 1024 * 1024, "snapshot profile byte bound exceeded");
      const fileHash = createHash("sha256");
      for await (const data of createReadStream(childPath, {highWaterMark: 65536})) fileHash.update(data);
      digest.update(JSON.stringify([relative(root, childPath).split(sep).join("/"), fileHash.digest("hex")]) + "\n");
    }
  };
  await visit(root);
  assert.ok(files > 0, "snapshot profile must not be empty");
  return {profileSha256: digest.digest("hex"), profileFiles: files, profileBytes: bytes};
}

export async function readCaptureWorldSnapshot(output, source, seed, profile, {scratchRoot = null} = {}) {
  const root = await realpath(dirname(output));
  const directory = containedCapturePath(root, await realpath(source));
  const text = await readFile(join(directory, "report.json"), "utf8");
  const report = JSON.parse(text), snapshot = report.capture?.worldSnapshot;
  assert.ok(report.verdict === "PASS" && report.capture.complete && snapshot?.saved, "snapshot requires a saved successful capture");
  assert.equal(report.seed, seed, "snapshot seed");
  assert.equal(report.profile, profile, "snapshot profile");
  const origin = new URL(snapshot.origin);
  assert.ok(origin.protocol === "http:" && origin.hostname === "127.0.0.1" && origin.port, "snapshot requires the original loopback origin");
  // A profile kept on the scratch volume (CAPTURE_SCRATCH) is named relative to that root and
  // must stay inside it; otherwise it lives inside the source capture directory.
  let chromeProfile;
  if (typeof snapshot.scratchProfile === "string") {
    assert.ok(scratchRoot, "snapshot profile on a scratch volume requires CAPTURE_SCRATCH");
    const scratch = await realpath(scratchRoot);
    chromeProfile = containedCapturePath(scratch, await realpath(join(scratch, snapshot.scratchProfile)));
  } else {
    chromeProfile = containedCapturePath(directory, await realpath(join(directory, snapshot.chromeProfile)));
  }
  return {chromeProfile, port: Number(origin.port), identity: {sourceSha256: createHash("sha256").update(text).digest("hex"), origin: origin.origin, ...await hashProfile(chromeProfile)}};
}

export async function copyCaptureWorldProfile(snapshot, output, destination) {
  containedCapturePath(await realpath(output), await realpath(destination));
  assert.equal((await readdir(destination)).length, 0, "snapshot destination must be empty");
  for (const name of await readdir(snapshot.chromeProfile)) {
    await cp(join(snapshot.chromeProfile, name), join(destination, name), {recursive: true, force: false, errorOnExist: true});
  }
  assert.deepEqual(await hashProfile(destination), Object.fromEntries(["profileSha256", "profileFiles", "profileBytes"].map(key => [key, snapshot.identity[key]])), "snapshot copy must match the source bytes");
}
