#!/usr/bin/env node
// Executes the WebAssembly GC HostLoop.managedBlockYield (the production source, compiled with
// tiny stand-ins for the TeaVM runtime classes it names and a fake clock) and compares it with
// the previous implementation, which read System.nanoTime() on every call of a busy
// BlockableEventLoop.managedBlock loop: the loop must still give the event loop a turn after
// two milliseconds of work, a handful of calls later at the most, with far fewer clock reads.
// Also the unit contract of ClockStride (what it learns, when it asks for a read on every call).
//
//   GAIUS_JAVA_HOME=/path/to/jdk node port/scripts/host-loop-clock-smoke.mjs
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const hostLoopPath = "port/overrides/classlib/src/target-wasmgc/java/dev/gaius/browser/HostLoop.java";
const stridePath = "port/overrides/classlib/src/main/java/dev/gaius/browser/ClockStride.java";
const hostLoop = await readFile(path.join(repositoryRoot, hostLoopPath), "utf8");
const stride = await readFile(path.join(repositoryRoot, stridePath), "utf8");

function selectTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const tool = path.join(home, "bin", name);
    if (spawnSync(tool, ["-version"]).status === 0) return tool;
  }
  return name;
}

assert.equal((hostLoop.match(/System\.nanoTime\(\)/g) || []).length, 2,
  "HostLoop reads System.nanoTime() in managedBlockYield and managedBlockIdle only");
const instrumented = hostLoop.replaceAll("System.nanoTime()", "dev.gaius.browser.FakeClock.nanoTime()");

const files = new Map([
  ["dev/gaius/browser/HostLoop.java", instrumented],
  ["dev/gaius/browser/ClockStride.java", stride],
  ["dev/gaius/browser/FakeClock.java", `
package dev.gaius.browser;
public final class FakeClock {
    public static long now = 1_000_000_000L;
    public static int reads;
    public static int eventLoopTurns;
    public static long nanoTime() { reads++; return now; }
}
`],
  // The pieces of the TeaVM runtime and of JspiHost that HostLoop names, as stand-ins.
  ["dev/gaius/browser/JspiHost.java", `
package dev.gaius.browser;
import org.teavm.interop.AsyncCallback;
final class JspiHost {
    static void startTask(Runnable task) { }
    static boolean inActivation() { return true; }
    static AsyncCallback<Object> newResult() { return new AsyncCallback<>(); }
    static void awaitResult(AsyncCallback<Object> result) { }
    static void awaitFrame(int interval) { }
}
`],
  ["org/teavm/interop/AsyncCallback.java", `
package org.teavm.interop;
public class AsyncCallback<T> { public void complete(T value) { } }
`],
  ["org/teavm/interop/Import.java", `
package org.teavm.interop;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface Import { String module() default ""; String name() default ""; }
`],
  ["org/teavm/interop/PlatformMarker.java", `
package org.teavm.interop;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target(ElementType.METHOD)
public @interface PlatformMarker { String[] value() default {}; }
`],
  ["org/teavm/runtime/EventQueue.java", `
package org.teavm.runtime;
public final class EventQueue {
    public interface Event { void run(); }
    public static int offer(Event event) { dev.gaius.browser.FakeClock.eventLoopTurns++; return 0; }
    public static int offer(Event event, long time) { return 0; }
}
`],
  ["dev/gaius/browser/ManagedBlockHarness.java", `
package dev.gaius.browser;

public final class ManagedBlockHarness {
    private static final long SLICE = 2_000_000L;
    private static long seed;

    private static double uniform() {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return ((seed >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static long cost(String model) {
        switch (model) {
            case "constant": return 10_000L;
            case "uniform": return 2_000L + (long) (28_000L * uniform());
            default: return uniform() < 0.02 ? 400_000L : 8_000L;
        }
    }

    /** The loop of BlockableEventLoop.managedBlock: one task per call while the predicate is false. */
    private static int callsUntilYield(String model, int maxCalls) {
        long last = FakeClock.nanoTime();
        for (int call = 1; call <= maxCalls; call++) {
            FakeClock.now += cost(model);
            long next = HostLoop.managedBlockYield(false, last);
            if (next != last) {
                return call;
            }
        }
        throw new AssertionError("no yield");
    }

    /** What the loop did before: one clock read per call. */
    private static int referenceCalls(String model, int maxCalls) {
        long last = 1_000_000_000L;
        long now = last;
        for (int call = 1; call <= maxCalls; call++) {
            now += cost(model);
            if (now - last >= SLICE) {
                return call;
            }
        }
        throw new AssertionError("no reference yield");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** The contract of ClockStride itself, in units of 0.01 per call. */
    private static void strideContract() {
        ClockStride stride = new ClockStride();
        check(stride.observe(0.0, 10.0) == 1, "nothing is known at the first read: read on every call");
        // Two reads, one call apart, 0.01 per call.
        check(stride.observe(0.01, 10.0) == 1 + ClockStride.MAX_SKIP, "far from the deadline: the longest stride");
        check(Math.abs(stride.cost() - 0.01) < 1e-12, "cost of a call: " + stride.cost());
        int interval = 1 + ClockStride.MAX_SKIP;
        // A deadline within four calls of cost is checked on every call; more room, more calls.
        check(stride.observe(0.01 + 0.01 * interval, 0.03) == 1, "3 calls left: read every call");
        check(stride.observe(0.02 + 0.01 * interval, 0.0399) == 1, "under 4 calls of cost left: every call");
        check(stride.observe(0.03 + 0.01 * interval, 0.09) == 3, "9 calls left: 2 may pass");
        check(stride.observe(0.04 + 0.01 * interval, -1.0) == 1, "a passed deadline: every call");
        // The cost rises at once and decays by DECAY per read.
        ClockStride rising = new ClockStride();
        rising.observe(0.0, 10.0);
        rising.observe(0.01, 10.0);
        rising.observe(0.01 + 1.0 * (1 + ClockStride.MAX_SKIP), 10.0);
        check(Math.abs(rising.cost() - 1.0) < 1e-12, "the cost must rise to the latest average at once");
        double at = 0.01 + 1.0 * (1 + ClockStride.MAX_SKIP);
        int next = 1 + ClockStride.MAX_SKIP;
        // Intervals that take no time: the peak falls by DECAY per read.
        for (int read = 1; read <= 5; read++) {
            next = rising.observe(at, 10.0);
        }
        check(Math.abs(rising.cost() - Math.pow(ClockStride.DECAY, 5)) < 1e-12,
                "the peak must fall by DECAY per read: " + rising.cost());
        // reset() drops the position (time spent outside the calls is no cost) but keeps the cost.
        double kept = rising.cost();
        rising.reset();
        check(rising.observe(1000.0, 10.0) > 1 && Math.abs(rising.cost() - kept) < 1e-12,
                "an interval across a reset must not be measured");
        // An axis that moves backwards is a new origin: the interval says nothing.
        double before = rising.cost();
        rising.observe(5.0, 10.0);
        check(Math.abs(rising.cost() - before) < 1e-12, "a backwards axis changed the cost");
        rising.forget();
        check(rising.cost() == 0.0 && rising.observe(0.0, 10.0) == 1, "forget() must drop everything");
        // A frozen clock never lets more than MAX_SKIP calls pass.
        ClockStride frozen = new ClockStride();
        frozen.observe(7.0, 1.0);
        check(frozen.observe(7.0, 1.0) == 1 + ClockStride.MAX_SKIP, "a frozen clock: the longest stride, no more");
    }

    public static void main(String[] args) {
        strideContract();
        int worstLate = 0;
        for (String model : new String[] {"constant", "uniform", "rare-heavy"}) {
            int reads = 0;
            int referenceReads = 0;
            int late = 0;
            for (int slice = 0; slice < 400; slice++) {
                long sliceSeed = 0x9E3779B97F4A7C15L ^ (model.hashCode() * 31L + slice);
                FakeClock.now = 1_000_000_000L;
                FakeClock.reads = 0;
                seed = sliceSeed;
                for (int warm = 0; warm < 4; warm++) uniform();
                int expected = referenceCalls(model, 100_000);
                seed = sliceSeed;
                for (int warm = 0; warm < 4; warm++) uniform();
                int actual = callsUntilYield(model, 100_000);
                if (actual < expected || actual - expected > ClockStride.MAX_SKIP) {
                    throw new AssertionError(model + ": yielded on call " + actual + ", reference " + expected);
                }
                if (model.equals("constant") && actual != expected) {
                    throw new AssertionError("constant cost: yielded on call " + actual + ", reference " + expected);
                }
                late = Math.max(late, actual - expected);
                reads += FakeClock.reads - 1; // the loop's own entry read
                referenceReads += expected;
            }
            if (reads * 3 > referenceReads) {
                throw new AssertionError(model + ": " + reads + " clock reads instead of " + referenceReads);
            }
            worstLate = Math.max(worstLate, late);
            System.out.println("MANAGED_BLOCK_STRIDE " + model + " reads=" + referenceReads + "->" + reads
                    + " lateCalls<=" + late);
        }

        // Two loops that alternate (a nested managedBlock) never use each other's countdown:
        // each call of a loop other than the last one reads the clock.
        FakeClock.now = 1_000_000_000L;
        long outer = FakeClock.nanoTime();
        FakeClock.now += 500_000L;
        long inner = FakeClock.nanoTime();
        int outerYield = -1;
        int innerYield = -1;
        for (int call = 1; call <= 1000 && (outerYield < 0 || innerYield < 0); call++) {
            FakeClock.now += 10_000L;
            if (outerYield < 0 && HostLoop.managedBlockYield(false, outer) != outer) outerYield = call;
            if (innerYield < 0 && HostLoop.managedBlockYield(false, inner) != inner) innerYield = call;
        }
        // Every call reads the clock here, so both loops yield on the call a read per call would.
        // outer: 500 us old at the first call, 2 ms after 150 calls; inner: 2 ms after 200 calls.
        if (outerYield != 150 || innerYield != 200) {
            throw new AssertionError("alternating loops yielded on calls " + outerYield + "/" + innerYield);
        }

        // A predicate that is true never reads the clock and keeps the timestamp.
        FakeClock.reads = 0;
        if (HostLoop.managedBlockYield(true, 42L) != 42L || FakeClock.reads != 0) {
            throw new AssertionError("a satisfied predicate read the clock");
        }
        System.out.println("MANAGED_BLOCK_OK worstLateCalls<=" + worstLate);
    }
}
`],
]);

const root = await mkdtemp(path.join(tmpdir(), "gaius-host-loop-clock-"));
try {
  await Promise.all([...files].map(async ([relative, contents]) => {
    const target = path.join(root, "src", relative);
    await mkdir(path.dirname(target), {recursive: true});
    await writeFile(target, contents);
  }));
  const classes = path.join(root, "classes");
  await mkdir(classes);
  execFileSync(selectTool("javac"), ["--release", "21", "-proc:none", "-d", classes,
    ...[...files.keys()].map(relative => path.join(root, "src", relative))],
  {encoding: "utf8", timeout: 60_000});
  const output = execFileSync(selectTool("java"), ["-cp", classes, "dev.gaius.browser.ManagedBlockHarness"],
    {encoding: "utf8", timeout: 60_000});
  assert.match(output, /MANAGED_BLOCK_OK/, "managedBlockYield failed its executable regression");
  process.stdout.write(output);
} finally {
  await rm(root, {recursive: true, force: true});
}
