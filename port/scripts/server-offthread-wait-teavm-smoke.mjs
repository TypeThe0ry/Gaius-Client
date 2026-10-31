#!/usr/bin/env node
// Compiles BrowserFuturePump.joinFromWorker with the Gaius browser CompletableFuture
// (port/src/main/java/org/teavm/classlib/java/util/concurrent) and the JavaScript HostLoop and
// TModernRuntimeSupport yield with TeaVM 0.15, and runs it in Node. joinFromWorker replaces the
// join of vanilla ServerChunkCache.getChunk called off the server thread
// (CompletableFuture.supplyAsync(lookup, mainThreadProcessor).join(), patched by
// MinecraftClientPatcher.patchServerChunkCacheBrowserOffThreadWait): world generation reaches it
// when a structure places an entity whose variant spawn condition (StructureCheck) asks the
// ServerLevel about its position. The fixture checks
//   - that the browser CompletableFuture.join still throws on an incomplete future (why the
//     patch exists),
//   - that a generation thread waiting in joinFromWorker resumes with the value once the server
//     thread has run the lookup from its mailbox, a few server ticks later, and the lookup ran
//     exactly once on the server thread,
//   - that a plain JavaScript callback, which has no thread to suspend, does not spin in
//     joinFromWorker: its join reports the incomplete future as before.
// Exit status 0 when every check passes.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {delimiter, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const concurrent = resolve(root, "port/src/main/java/org/teavm/classlib/java/util/concurrent");
const runtimeSupport = resolve(root,
  "port/overrides/classlib/src/main/java/org/teavm/classlib/java/lang/TModernRuntimeSupport.java");
const hostLoopSource = resolve(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/HostLoop.java");
const pumpSource = resolve(root, "port/src/main/java/dev/gaius/browser/BrowserFuturePump.java");
const repository = process.env.M2_REPO || join(homedir(), ".m2", "repository");
const {teaVMVersion} = JSON.parse(await readFile(resolve(root, "port/config.json"), "utf8"));
const pomScript = await readFile(resolve(root, "port/scripts/generate-pom.sh"), "utf8");
const allBarriers = pomScript.match(/teavm_all_barrier_groups="([^"]+)"/)[1];
const workerBarriers = pomScript.match(/singleplayer-worker\)[\s\S]*?default_async_barrier="([^"]+)"/)[1];
assert.ok(!workerBarriers.split(",").includes("iterator"), "Worker iterators must be able to wait for chunks");
assert.ok(pomScript.match(/client\)[\s\S]*?default_async_barrier="\$teavm_all_barrier_groups"/),
  "the client barrier defaults must remain unchanged");
const jars = [];
for (const artifact of await readdir(join(repository, "org", "teavm"))) {
  const directory = join(repository, "org", "teavm", artifact, teaVMVersion);
  try {
    for (const name of await readdir(directory)) {
      if (name.endsWith(".jar") && !name.includes("-sources") && !name.includes("-javadoc")) {
        jars.push(join(directory, name));
      }
    }
  } catch { /* only installed TeaVM 0.15 artifacts */ }
}
assert.ok(jars.some(name => name.includes("teavm-tooling")), "TeaVM 0.15 tooling must be installed");
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const suffix = process.platform === "win32" ? ".exe" : "";
const java = javaHome ? join(javaHome, "bin", `java${suffix}`) : "java";
const javac = javaHome ? join(javaHome, "bin", `javac${suffix}`) : "javac";
const temp = await mkdtemp(join(tmpdir(), "gaius-offthread-wait-"));

function balancedMethod(source, start) {
  const open = source.indexOf("{", start);
  assert.ok(open >= 0, `method body not found at ${start}`);
  let depth = 0;
  for (let index = open; index < source.length; index++) {
    if (source[index] === "{") depth++;
    else if (source[index] === "}" && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error("unterminated method body");
}

// The real yield of TModernRuntimeSupport, as worldgen-dispatcher-pump-teavm-smoke.mjs extracts it
// (the rest of the override needs ICU and time helpers): yieldToEventLoop, inClassInitializer and
// the @Async suspendToEventLoop, its callback overload, and postRunnableMacrotask.
const production = await readFile(runtimeSupport, "utf8");
const asyncStart = production.indexOf("    /**\n     * Suspends only the current TeaVM continuation");
assert.ok(asyncStart >= 0, "real yieldToEventLoop Javadoc not found");
const callbackAnchor = "\n\n    private static void suspendToEventLoop(int delayMillis, AsyncCallback<Void> callback)";
const callbackAt = production.indexOf(callbackAnchor, asyncStart);
assert.ok(callbackAt >= 0, "real suspendToEventLoop callback overload not found");
const callbackMethod = balancedMethod(production, callbackAt + 2);
const postAt = production.indexOf("    public static void postRunnableMacrotask(Runnable callback)");
assert.ok(postAt >= 0, "real postRunnableMacrotask not found");

const sources = {
  "org/teavm/classlib/java/lang/TModernRuntimeSupport.java": `package org.teavm.classlib.java.lang;
import dev.gaius.browser.HostLoop;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {}
${production.slice(asyncStart, callbackAt)}
${callbackMethod}
${balancedMethod(production, postAt)}
}`,
  "dev/gaius/browser/HostLoop.java": await readFile(hostLoopSource, "utf8"),
  "dev/gaius/browser/BrowserFuturePump.java": await readFile(pumpSource, "utf8"),
  "OffThreadWaitFixture.java": `
import dev.gaius.browser.BrowserFuturePump;
import dev.gaius.browser.HostLoop;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.StreamSupport;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
public class OffThreadWaitFixture {
    @JSBody(params = {"key", "value"}, script = "globalThis.__offThreadWait[key] = value;")
    static native void report(String key, String value);

    // The server thread's mailbox: BlockableEventLoop.execute from another thread only queues.
    static final ArrayDeque<Runnable> mailbox = new ArrayDeque<>();
    static final Executor mainThreadProcessor = mailbox::addLast;
    static int ticks;
    static int lookups;
    static int lookupTick = -1;
    static Object waited;
    static int resumedTick = -1;
    static String callbackJoin;
    static String generationFailure;

    static class ChunkIterator implements Iterator<String> {
        String chunk;

        public boolean hasNext() {
            chunk = BrowserFuturePump.joinFromWorker(CompletableFuture.supplyAsync(() -> {
                lookups++;
                lookupTick = ticks;
                return "chunk@" + ticks;
            }, mainThreadProcessor));
            return true;
        }

        public String next() {
            return chunk;
        }
    }

    public static void main(String[] args) {
        String plainJoin;
        try {
            CompletableFuture.supplyAsync(() -> "x", mainThreadProcessor).join();
            plainJoin = "returned";
        } catch (IllegalStateException e) {
            plainJoin = e.getMessage();
        }
        mailbox.clear();
        report("plainJoin", plainJoin);
        report("completedJoin", BrowserFuturePump.joinFromWorker(CompletableFuture.completedFuture("ready")));
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException("lookup failed"));
        try {
            BrowserFuturePump.joinFromWorker(failed);
            report("failedJoin", "returned");
        } catch (RuntimeException error) {
            report("failedJoin", error.getCause().getMessage());
        }

        // A generation thread asks the server thread for a chunk, as getChunk does off-thread.
        HostLoop.startThread(() -> {
            try {
                waited = StreamSupport.stream(Spliterators.spliteratorUnknownSize(new ChunkIterator(), 0), false)
                        .findFirst().orElseThrow();
            } catch (IllegalStateException error) {
                generationFailure = error.getMessage();
            }
            resumedTick = ticks;
        });
        // A plain timer callback cannot suspend: its wait must end at join.
        HostLoop.schedule(() -> {
            CompletableFuture<String> never = new CompletableFuture<>();
            try {
                BrowserFuturePump.joinFromWorker(never);
                callbackJoin = "returned";
            } catch (IllegalStateException e) {
                callbackJoin = e.getMessage();
            }
        }, 1);

        // The server thread: from its fifth tick on it runs its mailbox once per tick, and
        // yields between ticks like the integrated server's tick loop.
        while ((resumedTick < 0 || callbackJoin == null) && ticks < 2000) {
            ticks++;
            if (ticks >= 5) {
                Runnable task;
                while ((task = mailbox.pollFirst()) != null) task.run();
            }
            TModernRuntimeSupport.yieldToEventLoop(0);
        }
        report("ticks", String.valueOf(ticks));
        report("waited", String.valueOf(waited));
        report("lookups", String.valueOf(lookups));
        report("lookupTick", String.valueOf(lookupTick));
        report("resumedTick", String.valueOf(resumedTick));
        report("callbackJoin", String.valueOf(callbackJoin));
        report("generationFailure", String.valueOf(generationFailure));
    }
}`,
  "CompileOffThreadWaitFixture.java": `
import java.io.File;
import java.util.Properties;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.tooling.TeaVMTool;
public class CompileOffThreadWaitFixture {
    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setMainClass("OffThreadWaitFixture");
        tool.setTargetDirectory(new File(args[0]));
        tool.setTargetFileName("fixture.cjs");
        tool.setJsModuleType(JSModuleType.COMMON_JS);
        tool.setObfuscated(false);
        tool.setClassLoader(ClassLoader.getSystemClassLoader());
        Properties properties = new Properties();
        properties.setProperty("gaius.teavm.role", "singleplayer-worker");
        properties.setProperty("gaius.teavm.syncClinits", "true");
        properties.setProperty("gaius.teavm.syncMonitors", "true");
        properties.setProperty("gaius.teavm.asyncBarrier", args[1]);
        tool.getProperties().putAll(properties);
        tool.generate();
        for (var problem : tool.getProblemProvider().getSevereProblems())
            System.err.println(problem.getText() + " " + java.util.Arrays.toString(problem.getParams()));
        if (!tool.getProblemProvider().getSevereProblems().isEmpty())
            throw new AssertionError("TeaVM compilation failed");
    }
}`,
};
// The Gaius browser CompletableFuture and the types it adds to TeaVM's class library.
for (const name of ["TCompletableFuture", "TCompletionStage", "TFuture", "TTimeoutException"]) {
  sources[`org/teavm/classlib/java/util/concurrent/${name}.java`] = await readFile(join(concurrent, `${name}.java`), "utf8");
}
// The class library's LockSupport replacement: the future parks waiters through it, and without it
// TeaVM would read the JDK's LockSupport, which calls Thread.isVirtual.
sources["org/teavm/classlib/java/util/concurrent/locks/TLockSupport.java"] =
  await readFile(join(concurrent, "locks", "TLockSupport.java"), "utf8");

try {
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const file = join(temp, name);
    await mkdir(resolve(file, ".."), {recursive: true});
    await writeFile(file, content);
    files.push(file);
  }
  const toolClasses = join(temp, "tool-classes");
  const corePatches = join(temp, "core-patches");
  const asmJars = ["asm", "asm-tree"].map(name =>
    join(repository, "org/ow2/asm", name, "9.8", name + "-9.8.jar"));
  await mkdir(toolClasses, {recursive: true});
  const compileCore = jars.find(name => name.endsWith("teavm-core-" + teaVMVersion + ".jar"));
  execFileSync(javac, ["-J-Xmx1g", "--release", "21", "-proc:none", "-cp", [...asmJars, compileCore].join(delimiter),
    "-sourcepath", resolve(root, "port/tools/src/main/java"), "-d", toolClasses,
    resolve(root, "port/tools/src/main/java/dev/gaius/tools/TeaVMCoreBrowserPatcher.java")], {stdio: "pipe"});
  const stockCore = jars.find(name => name.endsWith("teavm-core-" + teaVMVersion + ".jar"));
  assert.ok(stockCore, "the cached TeaVM core must be available");
  execFileSync(java, ["-Xmx1g", "-cp", [toolClasses, ...asmJars, stockCore].join(delimiter),
    "dev.gaius.tools.TeaVMCoreBrowserPatcher", stockCore,
    join(corePatches, "org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class")],
    {stdio: "pipe"});
  const classpath = [corePatches, temp, ...jars].join(delimiter);
  execFileSync(javac, ["--release", "21", "-proc:none", "-cp", classpath, "-d", temp, ...files], {stdio: "pipe"});
  const runner = join(temp, "run.cjs");
  await writeFile(runner, `
const {MessageChannel: NativeMessageChannel} = require('node:worker_threads');
globalThis.__offThreadWait = {};
globalThis.MessageChannel = class extends NativeMessageChannel {
  constructor() { super(); this.port1.unref(); this.port2.unref(); }
};
require('./fixture.cjs').main([], (error) => {
  if (error) { console.error(error); process.exit(1); }
  console.log(JSON.stringify(globalThis.__offThreadWait));
  process.exit(0);
});
`);
  const compileAndRun = barriers => {
    execFileSync(java, ["-Xmx3g", "-cp", classpath, "CompileOffThreadWaitFixture", temp, barriers],
      {encoding: "utf8", timeout: 180000, stdio: "pipe"});
    const output = execFileSync(process.execPath, [runner], {encoding: "utf8", timeout: 60000});
    return JSON.parse(output.trim().split("\n").pop());
  };
  const blocked = compileAndRun(allBarriers);
  assert.equal(blocked.generationFailure, "blocking on an incomplete future is unavailable",
    "the old iterator barrier must reproduce the chunk wait failure: " + JSON.stringify(blocked));
  assert.equal(blocked.waited, "null", "a synchronously compiled iterator must not complete its wait");
  console.log("SERVER_ITERATOR_BARRIER_REPRO " + JSON.stringify(blocked));
  const result = compileAndRun(workerBarriers);
  assert.equal(result.generationFailure, "null", "the Worker iterator failed: " + JSON.stringify(result));
  assert.equal(result.completedJoin, "ready", "a completed lookup must return immediately");
  assert.equal(result.failedJoin, "lookup failed", "a failed lookup must preserve its cause");
  assert.equal(result.plainJoin, "blocking on an incomplete future is unavailable",
    `the browser CompletableFuture.join no longer throws on an incomplete future: ${result.plainJoin}`);
  assert.equal(result.lookups, "1", `the server thread must run the lookup exactly once: ${JSON.stringify(result)}`);
  assert.ok(Number(result.lookupTick) >= 5, `the lookup ran before the server thread's turn: ${JSON.stringify(result)}`);
  assert.equal(result.waited, `chunk@${result.lookupTick}`, `the waiting thread got another value: ${JSON.stringify(result)}`);
  assert.ok(Number(result.resumedTick) >= Number(result.lookupTick),
    `the waiting thread resumed before the lookup ran: ${JSON.stringify(result)}`);
  assert.equal(result.callbackJoin, "blocking on an incomplete future is unavailable",
    `a callback that cannot suspend must end its wait at join: ${JSON.stringify(result)}`);
  assert.ok(Number(result.ticks) < 2000, `the fixture did not finish: ${JSON.stringify(result)}`);
  console.log("SERVER_OFFTHREAD_WAIT_TEAVM_OK " + JSON.stringify(result));
} finally {
  await rm(temp, {recursive: true, force: true});
}
