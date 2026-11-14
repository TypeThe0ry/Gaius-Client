// Differential test of Gaius' LinkedBlockingQueue wrapper (TLinkedBlockingQueue, the class behind
// java.util.concurrent.LinkedBlockingQueue in the TeaVM builds): new LinkedBlockingQueue<>(collection)
// is unbounded in the JDK; the wrapper took the size of the collection as its capacity.
// The wrapper is plain Java over java.util.ArrayDeque, so HotSpot runs it as written.
//
//   node port/scripts/classlib-linkedblockingqueue-collection.test.mjs
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;

function run(command, args) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 64 << 20});
  assert.equal(result.status, 0, `${command} failed\n${(result.stdout || "").slice(-6000)}${(result.stderr || "").slice(-6000)}`);
  return result.stdout;
}

const out = mkdtempSync(join(tmpdir(), "gaius-lbq-"));
try {
  const classes = join(out, "classes");
  mkdirSync(classes);
  run(exe("javac"), ["-nowarn", "-proc:none", "-d", classes,
    join(root, "port/src/main/java/org/teavm/classlib/java/util/concurrent/TLinkedBlockingQueue.java"),
    join(root, "port/scripts/fixtures/LinkedBlockingQueueCollectionCheck.java")]);
  const result = run(exe("java"), ["-cp", classes, "LinkedBlockingQueueCollectionCheck"]);
  console.log(result.trim());
  assert.match(result, /LINKED_BLOCKING_QUEUE_COLLECTION_OK/);
  console.log("CLASSLIB_LINKEDBLOCKINGQUEUE_COLLECTION_TEST PASS");
} finally {
  rmSync(out, {recursive: true, force: true});
}
