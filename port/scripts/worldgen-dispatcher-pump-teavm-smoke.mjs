import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

// Compile the production BrowserWorldgenDispatcherScheduler (pump, turn budget and JSBody
// telemetry) with TeaVM 0.15 and run it in Node. The scheduler's continuation hop must be a
// suspended TeaVM thread resumed through TModernRuntimeSupport's MessageChannel path (the
// JavaScript HostLoop): only the first hop of a dispatcher chain may pay the clamped
// setTimeout(0) of HostLoop.startThread (Platform.startThread in the JavaScript build).
// The dispatcher below models the patched AbstractConsecutiveExecutor.run() turn protocol;
// worldgen-priority-jvm-smoke.mjs checks the real patched bytecode against it.
const root = fileURLToPath(new URL('../../', import.meta.url));
const scheduler = resolve(root, 'port/src/main/java/dev/gaius/browser/BrowserWorldgenDispatcherScheduler.java');
const runtimeSupport = resolve(root,
  'port/overrides/classlib/src/main/java/org/teavm/classlib/java/lang/TModernRuntimeSupport.java');
const hostLoopSource = resolve(root,
  'port/overrides/classlib/src/main/java/dev/gaius/browser/HostLoop.java');
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const jars = [];
for (const artifact of await readdir(join(repository, 'org', 'teavm'))) {
  const directory = join(repository, 'org', 'teavm', artifact, '0.15.0');
  try {
    for (const name of await readdir(directory)) {
      if (name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc')) {
        jars.push(join(directory, name));
      }
    }
  } catch { /* only installed TeaVM 0.15 artifacts */ }
}
assert.ok(jars.some(name => name.includes('teavm-tooling')), 'TeaVM 0.15 tooling must be installed');
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const suffix = process.platform === 'win32' ? '.exe' : '';
const java = javaHome ? join(javaHome, 'bin', `java${suffix}`) : 'java';
const javac = javaHome ? join(javaHome, 'bin', `javac${suffix}`) : 'javac';
const temp = await mkdtemp(join(tmpdir(), 'gaius-dispatcher-pump-'));

function balancedMethod(source, start) {
  const open = source.indexOf('{', start);
  assert.ok(open >= 0, `method body not found at ${start}`);
  let depth = 0;
  for (let index = open; index < source.length; index++) {
    if (source[index] === '{') depth++;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error('unterminated method body');
}

// Same verbatim extraction as teavm-macrotask-yield-smoke.mjs: keep the real yield and
// MessageChannel code, drop unrelated ICU/time helpers from the classlib override.
const production = await readFile(runtimeSupport, 'utf8');
const asyncStart = production.indexOf('    /**\n     * Suspends only the current TeaVM continuation');
assert.ok(asyncStart >= 0, 'real yieldToEventLoop Javadoc not found');
// yieldToEventLoop, inClassInitializer and the @Async suspendToEventLoop declaration, then the
// callback overload that TeaVM runs for it.
const callbackAnchor = '\n\n    private static void suspendToEventLoop(int delayMillis, AsyncCallback<Void> callback)';
const callbackAt = production.indexOf(callbackAnchor, asyncStart);
assert.ok(callbackAt >= 0, 'real suspendToEventLoop callback overload not found');
const asyncMethod = production.slice(asyncStart, callbackAt);
const callbackStart = callbackAt + 2;
const callbackMethod = balancedMethod(production, callbackStart);
// The MessageChannel helper and Platform.startThread live in the JavaScript HostLoop
// (dev.gaius.browser, classlib overlay), which compiles verbatim.
const hostLoop = await readFile(hostLoopSource, 'utf8');
const helperStart = hostLoop.indexOf('    @JSBody(params = "callback"');
const helperEnd = hostLoop.indexOf('private static native void postMacrotask(MacrotaskCallback callback);', helperStart);
assert.ok(helperStart >= 0 && helperEnd >= 0
    && hostLoop.slice(helperStart, helperEnd).includes('new MessageChannel()'),
  'real postMacrotask helper not found');

const bookkeepingCount = 3000;
try {
  const sources = {
    'org/teavm/classlib/java/lang/TModernRuntimeSupport.java': `package org.teavm.classlib.java.lang;
import dev.gaius.browser.HostLoop;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {}
${asyncMethod}
${callbackMethod}
}`,
    'dev/gaius/browser/HostLoop.java': hostLoop,
    // The dispatcher only needs the generation suspension predicate here; the full scheduler
    // is covered by worldgen-scheduler-smoke.mjs and is intentionally outside this TeaVM fixture.
    'dev/gaius/browser/BrowserWorldgenScheduler.java': `
package dev.gaius.browser;
public final class BrowserWorldgenScheduler {
    private BrowserWorldgenScheduler() {}
    public static boolean generationSuspended() { return true; }
}
`,
    'dev/gaius/browser/BrowserWorldgenDispatcherScheduler.java': await readFile(scheduler, 'utf8'),
    'DispatcherPumpFixture.java': `
import dev.gaius.browser.BrowserWorldgenDispatcherScheduler;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
public class DispatcherPumpFixture {
    @JSBody(params = {"key", "value"}, script = "globalThis.__pumpFixture[key] = value;")
    static native void report(String key, int value);

    static final class Entry {
        final int priority;
        final Runnable body;
        Entry(int priority, Runnable body) { this.priority = priority; this.body = body; }
    }

    /** Patched AbstractConsecutiveExecutor.run(): one turn, then a deferred re-registration. */
    static final class ModelDispatcher implements Runnable {
        @SuppressWarnings("unchecked")
        final ArrayDeque<Entry>[] queues = new ArrayDeque[4];
        final Executor executor = Runnable::run;
        boolean running;
        int activeTurns;
        int maxActiveTurns;
        int turns;
        int lastTurnMainTick = -1;
        int interleavedTurns;
        ModelDispatcher() { for (int i = 0; i < queues.length; i++) queues[i] = new ArrayDeque<>(); }
        void schedule(int priority, Runnable body) {
            queues[priority].addLast(new Entry(priority, body));
            if (!running) { running = true; executor.execute(this); }
        }
        int head() {
            for (int i = 0; i < queues.length; i++) if (!queues[i].isEmpty()) return i;
            return -1;
        }
        public void run() {
            maxActiveTurns = Math.max(maxActiveTurns, ++activeTurns);
            if (turns++ > 0 && mainTicks != lastTurnMainTick) interleavedTurns++;
            lastTurnMainTick = mainTicks;
            long started = BrowserWorldgenDispatcherScheduler.beginTurn();
            int executed = 0;
            int reason = BrowserWorldgenDispatcherScheduler.STOP_IDLE;
            while (true) {
                int priority = head();
                if (priority < 0) break;
                queues[priority].pollFirst().body.run();
                executed++;
                reason = BrowserWorldgenDispatcherScheduler.continueTurn(started, executed, priority, head());
                if (reason != BrowserWorldgenDispatcherScheduler.CONTINUE_TURN) break;
            }
            BrowserWorldgenDispatcherScheduler.endTurn(started, executed, reason);
            activeTurns--;
            running = false;
            if (head() >= 0) {
                running = true;
                BrowserWorldgenDispatcherScheduler.defer(executor, this);
            }
        }
    }

    static int mainTicks;
    static int bookkeeping;
    static int generation;
    static int suspensions;
    static int lateRan;
    static boolean suspended;
    static boolean lateScheduled;
    static boolean orderOk = true;

    static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) { }
    }

    public static void main(String[] args) {
        ModelDispatcher dispatcher = new ModelDispatcher();
        // Priority 3 stands for ChunkTaskDispatcher.pollTask. The first one runs inline from
        // schedule(), like the first worldgen registration, and queues a bookkeeping flood.
        dispatcher.schedule(3, () -> {
            generation++;
            for (int i = 0; i < ${bookkeepingCount}; i++) {
                int expected = i;
                dispatcher.schedule(0, () -> {
                    if (bookkeeping++ != expected) orderOk = false;
                    spin(20_000L);
                });
            }
            // A pumped generation turn suspends like ChunkGenerationTask.runUntilWait.
            dispatcher.schedule(3, () -> {
                generation++;
                for (int k = 0; k < 3; k++) {
                    suspended = true;
                    TModernRuntimeSupport.yieldToEventLoop(0);
                    suspended = false;
                    suspensions++;
                }
            });
        });
        while (generation < 2 || lateRan == 0 || dispatcher.running || dispatcher.head() >= 0) {
            mainTicks++;
            if (suspended && !lateScheduled) {
                lateScheduled = true;
                // Another macrotask schedules while the generation continuation is parked.
                dispatcher.schedule(1, () -> lateRan++);
                if (lateRan != 0) orderOk = false;
            }
            TModernRuntimeSupport.yieldToEventLoop(0);
        }
        report("bookkeeping", bookkeeping);
        report("generation", generation);
        report("suspensions", suspensions);
        report("lateRan", lateRan);
        report("maxActiveTurns", dispatcher.maxActiveTurns);
        report("turns", dispatcher.turns);
        report("interleavedTurns", dispatcher.interleavedTurns);
        report("mainTicks", mainTicks);
        report("orderOk", orderOk ? 1 : 0);
    }
}`,
    'CompileDispatcherPumpFixture.java': `
import java.io.File;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.tooling.TeaVMTool;
public class CompileDispatcherPumpFixture {
    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setMainClass("DispatcherPumpFixture");
        tool.setTargetDirectory(new File(args[0]));
        tool.setTargetFileName("fixture.cjs");
        tool.setJsModuleType(JSModuleType.COMMON_JS);
        tool.setObfuscated(false);
        tool.setClassLoader(ClassLoader.getSystemClassLoader());
        tool.generate();
        for (var problem : tool.getProblemProvider().getSevereProblems())
            System.err.println(problem.getText() + " " + java.util.Arrays.toString(problem.getParams()));
        if (!tool.getProblemProvider().getSevereProblems().isEmpty())
            throw new AssertionError("TeaVM compilation failed");
    }
}`,
  };
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const file = join(temp, name);
    await mkdir(resolve(file, '..'), {recursive: true});
    await writeFile(file, content);
    files.push(file);
  }
  const cp = [temp, ...jars].join(delimiter);
  execFileSync(javac, ['--release', '21', '-cp', cp, '-d', temp, ...files], {stdio: 'pipe'});
  execFileSync(java, ['-Xmx1g', '-cp', cp, 'CompileDispatcherPumpFixture', temp],
    {encoding: 'utf8', timeout: 120000});

  const runner = join(temp, 'run.cjs');
  await writeFile(runner, `
const {MessageChannel: NativeMessageChannel} = require('node:worker_threads');
const timerDelays = [];
const nativeSetTimeout = globalThis.setTimeout;
globalThis.__pumpFixture = {};
globalThis.setTimeout = (callback, delay) => {
  timerDelays.push(Number(delay));
  return nativeSetTimeout(callback, delay);
};
globalThis.MessageChannel = class extends NativeMessageChannel {
  constructor() {
    super();
    this.port1.unref();
    this.port2.unref();
  }
};
require('./fixture.cjs').main([], (error) => {
  if (error) { console.error(error); process.exit(1); }
  console.log(JSON.stringify({
    fixture: globalThis.__pumpFixture,
    dispatcher: globalThis.__gaiusWorldgenStats && globalThis.__gaiusWorldgenStats.dispatcher,
    flatKeys: Object.keys(globalThis.__gaiusWorldgenStats || {}),
    timerDelays,
  }));
  process.exit(0);
});
`);
  const output = execFileSync(process.execPath, [runner], {encoding: 'utf8', timeout: 60000});
  const {fixture, dispatcher, flatKeys, timerDelays} = JSON.parse(output.trim());
  assert.equal(fixture.orderOk, 1, 'bookkeeping FIFO order changed or late work ran re-entrantly');
  assert.equal(fixture.bookkeeping, bookkeepingCount, 'bookkeeping flood did not drain');
  assert.equal(fixture.generation, 2, 'generation polls did not both run');
  assert.equal(fixture.suspensions, 3, 'suspended generation continuation did not resume');
  assert.equal(fixture.lateRan, 1, 'work scheduled during a suspended generation was lost');
  assert.equal(fixture.maxActiveTurns, 1, 'dispatcher re-entered a suspended generation turn');
  assert.deepEqual(timerDelays, [0],
    'only the first hop of a dispatcher chain may use Platform.startThread\'s setTimeout(0)');
  assert.deepEqual(flatKeys, ['dispatcher'],
    'dispatcher telemetry must stay nested outside the flat worldgen scalar budget');
  assert.equal(dispatcher.threadHops, 1, 'dispatcher chain restarted a native thread');
  assert.equal(dispatcher.turns, fixture.turns, 'telemetry lost dispatcher turns');
  assert.equal(dispatcher.deferredHops, fixture.turns - 1,
    'every turn after the inline registration must follow exactly one deferred hop');
  assert.equal(dispatcher.messageHops, dispatcher.deferredHops - 1,
    'continuation hops did not use the MessageChannel macrotask path');
  assert.equal(dispatcher.runnables, bookkeepingCount + 3, 'telemetry lost dispatcher runnables');
  assert.equal(dispatcher.generationStops, 2, 'generation polls did not end their turns');
  // Each bookkeeping runnable spins 20 us; the 4 ms inline budget keeps the turn bounded.
  assert.ok(dispatcher.budgetStops >= 10 && dispatcher.maxTurnRunnables < 500
      && dispatcher.maxTurnRunnables >= 20,
    `bookkeeping turns ignored the inline budget: ${JSON.stringify(dispatcher)}`);
  assert.ok(dispatcher.maxBookkeepingTurnMillis >= 2 && dispatcher.maxBookkeepingTurnMillis < 25,
    `bookkeeping turn exceeded its bounded wall time: ${dispatcher.maxBookkeepingTurnMillis}`);
  assert.ok(fixture.interleavedTurns >= Math.floor((fixture.turns - 1) / 2),
    `pumped turns did not yield to other macrotasks: ${JSON.stringify(fixture)}`);
  console.log('WORLDGEN_DISPATCHER_PUMP_TEAVM_OK ' + JSON.stringify({
    turns: dispatcher.turns,
    runnablesPerTurn: dispatcher.runnablesPerTurn,
    maxTurnRunnables: dispatcher.maxTurnRunnables,
    threadHops: dispatcher.threadHops,
    messageHops: dispatcher.messageHops,
    timerDelays,
  }));
} finally {
  await rm(temp, {recursive: true, force: true});
}
