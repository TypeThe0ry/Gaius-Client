#!/usr/bin/env node
// Frame pacing of the per-frame cooperative yield after a present.
//
//   node port/scripts/frame-pacing-yield-smoke.mjs                 # 26.2: BrowserGlfw.swapBuffers
//   node port/scripts/frame-pacing-yield-smoke.mjs --profile 26.3  # 26.3: BrowserSdl.SDL_GL_SwapWindow
//
// Both profiles run the same scheduler script (the SDL shim copies BrowserGlfw's), so the
// simulations below are shared; only the source contract differs.

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const profileIndex = process.argv.indexOf("--profile");
const profile = profileIndex >= 0 ? process.argv[profileIndex + 1] : CANONICAL_PROFILE.id;
assert.ok(profile === CANONICAL_PROFILE.id || profile === RENDERPEARL_PROFILE.id, `unsupported profile ${profile}`);
const sdl = profile === RENDERPEARL_PROFILE.id;
const sdlRoot = "../overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl/";
const sourcePath = new URL(
  sdl ? sdlRoot + "BrowserSdlDom.java"
    : "../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");
if (sdl) {
  // SDL_GL_SwapWindow is the frame boundary: present bookkeeping, then the yield, with the
  // interval stored by SDL_GL_SetSwapInterval.
  const entry = await readFile(new URL(sdlRoot + "BrowserSdl.java", import.meta.url), "utf8");
  const video = await readFile(new URL(sdlRoot + "BrowserSdlVideo.java", import.meta.url), "utf8");
  const swapWindow = entry.slice(entry.indexOf("public static boolean SDL_GL_SwapWindow"),
    entry.indexOf("// ------", entry.indexOf("public static boolean SDL_GL_SwapWindow")));
  assert.match(swapWindow, /BrowserSdlDom\.swapWindow\(BrowserSdlVideo\.swapInterval\(\)\);\s*return true;/,
    "SDL_GL_SwapWindow does not yield with the stored swap interval");
  assert.match(entry, /public static boolean SDL_GL_SetSwapInterval\(int interval\) \{\s*BrowserSdlVideo\.setSwapInterval\(interval\);/,
    "SDL_GL_SetSwapInterval does not store the interval");
  assert.match(video, /private static int swapInterval;/,
    "SDL swap interval is not retained for the asynchronous present boundary");
  // The present counter is the bookkeeping the game-loop boundary compares (loopBoundary).
  assert.match(source, /static void swapWindow\(int swapInterval\) \{\s*presentCount\+\+;\s*boolean hidden = swapBuffersJs\(\);\s*presentYield\(hidden, swapInterval\);\s*\}/,
    "BrowserSdlDom.swapWindow is not present bookkeeping followed by the yield");
}
function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf(
    '@JSBody(params = {"hidden", "interval", "resume"}, script = """',
    markerOffset,
  );
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

assert.match(source, /@Async\s+private static native void yieldAfterPresent\(boolean hidden, int interval\)/,
  "swapBuffers yield is not a TeaVM async continuation boundary");
// The present yields through presentYield: the JSPI WebAssembly GC build (HostLoop.jspi() is
// constant-folded) only records the swap interval and waits at the head of the Minecraft.run
// loop instead (HostLoop.awaitAnimationFrame, design D4); every other build takes the TeaVM
// async boundary above.
// On the SDL shim a frame presented from a JavaScript callback (no TeaVM thread) skips its yield:
// only TeaVM's refusal to suspend there is swallowed, any other failure propagates.
const presentYieldFallback = sdl
  ? String.raw`try \{\s*yieldAfterPresent\(hidden, interval\);\s*\} catch \(RuntimeException e\) \{\s*`
    + String.raw`if \(!isNonThreadingSuspension\(e\)\) \{\s*throw e;\s*\}\s*recordSkippedPresentYield\(\);\s*\}`
  : String.raw`yieldAfterPresent\(hidden, interval\);`;
assert.match(source,
  new RegExp(String.raw`private static void presentYield\(boolean hidden, int interval\) \{\s*if \(HostLoop\.jspi\(\)\) \{\s*`
    + String.raw`HostLoop\.framePresented\(hidden, interval\);\s*return;\s*\}\s*` + presentYieldFallback + String.raw`\s*\}`),
  "presentYield does not fall back to the TeaVM async yield outside the JSPI build");
if (!sdl) {
  assert.match(source, /private static int swapInterval;/,
    "GLFW swap interval is not retained for the asynchronous present boundary");
}
assert.match(source, /requestAnimationFrame/, "visible pacing does not use rAF");
assert.match(source, /new MessageChannel\(\)/, "visible pacing does not yield through MessageChannel");
assert.match(source, /setTimeout\(\(\) => finish\('timer'\), 50\)/,
  "hidden pacing is not kept low-frequency");
assert.match(source, /watchdog=setTimeout\(\(\) => finish\('watchdog'\), 100\)/,
  "a suspended visible rAF can starve its TeaVM continuation");
assert.match(source, /else if \(synchronizedToDisplay && typeof requestAnimationFrame==='function'\)/,
  "swapInterval(0) still waits for display refresh");
assert.match(source, /telemetry\.uncappedYieldCount=/,
  "uncapped scheduling is not observable in release telemetry");
assert.match(source, /telemetry\.vsyncYieldCount=/,
  "VSync scheduling is not observable in release telemetry");
assert.match(source, /telemetry\.swapInterval=Number\(interval\)\|\|0/,
  "the effective GLFW swap interval is not observable in release telemetry");
assert.match(source, /scheduler=\{tasks:new Map\(\),channel:null,nextTaskId:1\}/,
  "MessageChannel continuations are not stored as cancellable tokens");
assert.match(source, /scheduler\.tasks\.delete\(taskId\)/,
  "watchdog recovery cannot remove a stalled MessageChannel continuation");
assert.doesNotMatch(source, /scheduleFairYield|__gaiusUncappedYieldSequence|\(sequence & 3\)/,
  "uncapped pacing still mixes a fixed scheduler.yield cadence into MessageChannel presents");
assert.doesNotMatch(source, /scheduler=\{queue:\[\],channel:null\}/,
  "frame pacing still retains an unbounded callback queue");
if (sdl) {
  assert.doesNotMatch(source.slice(source.indexOf("static void swapWindow")), /sleepForBrowserMillis|Thread\.sleep/,
    "SDL_GL_SwapWindow still uses a clamp-prone fixed timer");
} else {
  assert.doesNotMatch(
    source.slice(source.indexOf("public static void swapBuffers"), source.indexOf("public static void swapInterval")),
    /sleepForBrowserMillis|Thread\.sleep/,
    "swapBuffers still uses a clamp-prone fixed timer",
  );
  const waitEventsTimeout = source.slice(
    source.indexOf("public static void waitEventsTimeout"),
    source.indexOf("public static void postEmptyEvent"),
  );
  assert.match(waitEventsTimeout, /sleepForBrowserMillis\(Math\.max\(1L, Math\.min\(7L, millis - 1L\)\)\)/,
    "waitEventsTimeout semantics changed");
}

const frameYieldScript = jsBodyBefore(
  "private static native void scheduleFrameYield(boolean hidden, int interval, FrameYieldCallback resume);",
);
// TeaVM parses @JSBody scripts as JavaScript 1.8: no for...of loops.
assert.doesNotMatch(frameYieldScript, /\bfor\s*\((?:const|let|var)\s+\w+\s+of\b/,
  "scheduleFrameYield uses for...of, which TeaVM's @JSBody parser rejects");

class VirtualBrowser {
  constructor({refreshRate = 120, timerClamp = 4, messageDelay = 0.01} = {}) {
    this.now = 0;
    this.refreshMillis = 1000 / refreshRate;
    this.timerClamp = timerClamp;
    this.messageDelay = messageDelay;
    this.nextOrder = 0;
    this.events = [];
    this.sideTasksCompleted = 0;
    this.rafRequests = 0;
  }

  schedule(at, callback, kind) {
    const event = {at, callback, kind, order: this.nextOrder++, canceled: false};
    this.events.push(event);
    return event.order;
  }

  setTimeout(callback, delay = 0) {
    return this.schedule(
      this.now + Math.max(this.timerClamp, Number(delay) || 0),
      callback,
      "timer",
    );
  }

  clearTimeout(handle) {
    const event = this.events.find(candidate => candidate.order === handle);
    if (event) event.canceled = true;
  }

  requestAnimationFrame(callback) {
    this.rafRequests++;
    const frame = (Math.floor(this.now / this.refreshMillis) + 1) * this.refreshMillis;
    this.schedule(frame, () => callback(frame), "raf");
    return this.nextOrder;
  }

  postMessage(callback) {
    this.schedule(this.now + this.messageDelay, callback, "message");
  }

  runNext() {
    this.events.sort((left, right) => left.at - right.at || left.order - right.order);
    let event = this.events.shift();
    while (event?.canceled) event = this.events.shift();
    assert.ok(event, "virtual browser event queue starved");
    this.now = Math.max(this.now, event.at);
    event.callback();
  }

  runUntil(predicate, limit = 100000) {
    for (let step = 0; step < limit && !predicate(); step++) this.runNext();
    assert.ok(predicate(), "virtual browser did not make bounded progress");
  }
}

function createMessageChannelClass(browser) {
  return class VirtualMessageChannel {
    constructor() {
      let closed = false;
      this.port1 = {
        onmessage: null,
        close: () => {
          closed = true;
        },
      };
      this.port2 = {
        postMessage: data => browser.postMessage(() => {
          if (!closed) this.port1.onmessage?.({data});
        }),
        close: () => {
          closed = true;
        },
      };
    }
  };
}

function createDeadMessageChannelClass(stats) {
  return class DeadMessageChannel {
    constructor() {
      stats.created++;
      let closed = false;
      this.port1 = {
        onmessage: null,
        close: () => {
          if (!closed) stats.closed++;
          closed = true;
        },
      };
      this.port2 = {
        postMessage: () => {
          stats.posts++;
        },
        close: () => {
          closed = true;
        },
      };
    }
  };
}

function createThrowingMessageChannelClass(stats) {
  return class ThrowingMessageChannel {
    constructor() {
      stats.created++;
      let closed = false;
      this.port1 = {
        onmessage: null,
        close: () => {
          if (!closed) stats.closed++;
          closed = true;
        },
      };
      this.port2 = {
        postMessage: () => {
          stats.posts++;
          throw new Error("synthetic MessageChannel post failure");
        },
        close: () => {
          closed = true;
        },
      };
    }
  };
}

function createConstructorThrowingMessageChannelClass(stats) {
  return class ConstructorThrowingMessageChannel {
    constructor() {
      stats.created++;
      throw new Error("synthetic MessageChannel constructor failure");
    }
  };
}

function percentile(values, fraction) {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))];
}

function onePercentLowFps(frameTimes) {
  const sampleCount = Math.max(1, Math.ceil(frameTimes.length * 0.01));
  const slowest = [...frameTimes].sort((left, right) => right - left).slice(0, sampleCount);
  return 1000 / (slowest.reduce((total, value) => total + value, 0) / slowest.length);
}

function displayedFrameTimes(presentTimes, refreshRate, duration) {
  const refreshMillis = 1000 / refreshRate;
  const displayed = [];
  let presentIndex = 0;
  let generation = 0;
  let displayedGeneration = 0;
  for (let at = refreshMillis; at <= duration; at += refreshMillis) {
    while (presentIndex < presentTimes.length && presentTimes[presentIndex] <= at + 1e-6) {
      generation++;
      presentIndex++;
    }
    if (generation !== displayedGeneration) {
      displayed.push(at);
      displayedGeneration = generation;
    }
  }
  return displayed.slice(1).map((at, index) => at - displayed[index]);
}

async function simulateVisibleYield(refreshRate, frameCount = 720) {
  const browser = new VirtualBrowser({refreshRate, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  const presentTimes = [];
  let completed = 0;
  const present = () => {
    presentTimes.push(browser.now);
    scheduleYield(false, 1, () => {
      completed++;
      browser.now += 4.4;
      if (completed < frameCount) present();
    });
  };

  for (let at = 1; at < frameCount * browser.refreshMillis; at += 1) {
    browser.schedule(at, () => browser.sideTasksCompleted++, "side-task");
  }
  present();
  browser.runUntil(() => completed === frameCount);

  assert.equal(telemetry.yieldRequestCount, frameCount);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.visibleYieldCount, frameCount);
  assert.equal(telemetry.hiddenYieldCount || 0, 0);
  assert.equal(telemetry.swapInterval, 1);
  assert.equal(telemetry.vsyncYieldCount, frameCount);
  assert.equal(telemetry.uncappedYieldCount || 0, 0);
  assert.equal(telemetry.presentToRafCount, frameCount);
  assert.equal(telemetry.messageChannelYieldCount, frameCount);
  assert.ok(telemetry.longestPresentToRafMillis <= browser.refreshMillis + 1e-6,
    `rAF delay exceeded one ${refreshRate} Hz interval: ${telemetry.longestPresentToRafMillis}`);
  assert.ok(telemetry.longestYieldResumeDelayMillis < browser.refreshMillis + 0.1,
    `visible resume was starved: ${telemetry.longestYieldResumeDelayMillis}`);
  assert.ok(browser.sideTasksCompleted > frameCount * 5,
    "input/audio/MessagePort-like tasks were starved by the render loop");
  return presentTimes;
}

async function simulateUncappedYield(frameCount = 1440) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4, messageDelay: 0.01});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  const presentTimes = [];
  const workPattern = [4.0, 4.4, 4.7, 4.2, 5.0, 4.5, 4.1, 4.8];
  let completed = 0;
  const present = () => {
    presentTimes.push(browser.now);
    scheduleYield(false, 0, () => {
      completed++;
      browser.now += workPattern[completed % workPattern.length];
      if (completed < frameCount) present();
    });
  };

  for (let at = 1; at < frameCount * 5; at += 1) {
    browser.schedule(at, () => browser.sideTasksCompleted++, "side-task");
  }
  present();
  browser.runUntil(() => completed === frameCount);

  const frameTimes = presentTimes.slice(1).map((at, index) => at - presentTimes[index]);
  const averageFps = 1000 / (frameTimes.reduce((total, value) => total + value, 0) / frameTimes.length);
  const onePercentLow = onePercentLowFps(frameTimes);
  assert.equal(browser.rafRequests, 0, "uncapped world pacing unexpectedly waited for rAF");
  assert.equal(telemetry.swapInterval, 0);
  assert.equal(telemetry.uncappedYieldCount, frameCount);
  assert.equal(telemetry.vsyncYieldCount || 0, 0);
  assert.equal(telemetry.presentToRafCount || 0, 0);
  assert.equal(telemetry.messageChannelYieldCount, frameCount);
  assert.equal(telemetry.timerYieldCount || 0, 0);
  assert.equal(telemetry.fairYieldCount || 0, 0);
  assert.equal(telemetry.schedulerYieldCount || 0, 0);
  assert.equal(telemetry.yieldRequestCount, frameCount);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.cancelledMessageTaskCount || 0, 0);
  assert.equal(window.__gaiusFrameYieldScheduler.tasks.size, 0);
  assert.ok(browser.sideTasksCompleted > frameCount,
    "uncapped render loop starved timer/input/MessagePort-like tasks");
  assert.ok(averageFps >= 120,
    `uncapped scheduler failed the 120 FPS average contract: ${averageFps.toFixed(1)} FPS`);
  assert.ok(onePercentLow >= 60,
    `uncapped scheduler failed the 60 FPS 1% low contract: ${onePercentLow.toFixed(1)} FPS`);
  return {averageFps, onePercentLow};
}

// Cheap uncapped frames (the title screen) used to present hundreds of times per refresh and
// starve the compositor. In menus (no level) they must now present once per display refresh,
// waiting for rAF in between. In a world they stay uncapped (no rAF wait): the terrain section
// scheduler works on a per-frame budget, so holding world frames slowed chunk rendering.
async function simulateCheapUncappedYield(refreshRate = 60, frameCount = 900, {menu = false} = {}) {
  const limit = menu ? 1 : Infinity;
  const browser = new VirtualBrowser({refreshRate, timerClamp: 4, messageDelay: 0.01});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: !menu}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  const presentTimes = [];
  let completed = 0;
  const present = () => {
    presentTimes.push(browser.now);
    scheduleYield(false, 0, () => {
      completed++;
      browser.now += 1.5;
      if (completed < frameCount) present();
    });
  };
  present();
  browser.runUntil(() => completed === frameCount);

  const perRefresh = new Map();
  for (const at of presentTimes) {
    const slot = Math.floor(at / browser.refreshMillis + 1e-9);
    perRefresh.set(slot, (perRefresh.get(slot) || 0) + 1);
  }
  const busiestRefresh = Math.max(...perRefresh.values());
  const elapsed = presentTimes.at(-1) - presentTimes[0];
  const averageFps = 1000 * (presentTimes.length - 1) / elapsed;
  if (menu) {
    assert.ok(busiestRefresh <= limit,
      `cheap menu frames presented ${busiestRefresh} times in one ${refreshRate} Hz refresh`);
    assert.ok(averageFps <= refreshRate * limit + 1,
      `cheap menu frames were not held to the display: ${averageFps.toFixed(1)} FPS`);
    assert.ok(averageFps >= refreshRate * limit * 0.8,
      `cheap menu frames were throttled below one per refresh: ${averageFps.toFixed(1)} FPS`);
    assert.ok(telemetry.uncappedRafWaitCount >= frameCount - 1, "cheap menu frames did not wait for rAF");
  } else {
    assert.equal(browser.rafRequests, 0, "world frames waited for rAF");
    assert.equal(telemetry.uncappedRafWaitCount || 0, 0, "world frames waited for rAF");
    assert.ok(averageFps > 400,
      `cheap world frames were held to the display: ${averageFps.toFixed(1)} FPS`);
  }
  assert.equal(telemetry.uncappedYieldCount, frameCount);
  assert.equal(telemetry.messageChannelYieldCount, frameCount);
  assert.equal(telemetry.watchdogYieldCount || 0, 0);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(window.__gaiusUncappedPresentPacer?.waiters.length ?? 0, 0);
  return averageFps;
}

async function simulateSchedulerIsolation(frameCount = 64) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4, messageDelay: 0.01});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  let schedulerYieldAttempts = 0;
  window.scheduler = {yield: () => {
    schedulerYieldAttempts++;
    return {then: resolve => browser.schedule(browser.now + browser.messageDelay, resolve, "scheduler")};
  }};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  const present = () => scheduleYield(false, 0, () => {
    completed++;
    if (completed < frameCount) present();
  });
  present();
  browser.runUntil(() => completed === frameCount);

  assert.equal(schedulerYieldAttempts, 0,
    "visible uncapped pacing unexpectedly entered the browser scheduler.yield path");
  assert.equal(telemetry.fairYieldCount || 0, 0);
  assert.equal(telemetry.schedulerYieldCount || 0, 0);
  assert.equal(telemetry.timerYieldCount || 0, 0);
  assert.equal(telemetry.messageChannelYieldCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
}

async function simulateDeadMessageChannel(frameCount = 2048) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const channelStats = {created: 0, closed: 0, posts: 0};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Map,
    Math,
    MessageChannel: createDeadMessageChannelClass(channelStats),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  const present = () => scheduleYield(false, 0, () => {
    completed++;
    if (completed < frameCount) present();
  });
  present();
  browser.runUntil(() => completed === frameCount);

  const messageAttempts = frameCount;
  const scheduler = window.__gaiusFrameYieldScheduler;
  assert.equal(telemetry.yieldRequestCount, frameCount);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.cancelledMessageTaskCount, messageAttempts);
  assert.equal(telemetry.messageChannelRebuildCount, messageAttempts);
  assert.equal(telemetry.watchdogYieldCount, messageAttempts);
  assert.equal(telemetry.fairYieldCount || 0, 0);
  assert.equal(scheduler.tasks.size, 0,
    "dead MessageChannel retained completed TeaVM continuations");
  assert.equal(scheduler.channel, null,
    "dead MessageChannel was not retired after watchdog recovery");
  assert.equal(channelStats.created, messageAttempts);
  assert.equal(channelStats.closed, messageAttempts);
  assert.equal(channelStats.posts, messageAttempts);
}

async function simulateThrowingMessageChannel(frameCount = 128) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const channelStats = {created: 0, closed: 0, posts: 0};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Map,
    Math,
    MessageChannel: createThrowingMessageChannelClass(channelStats),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  const present = () => scheduleYield(false, 0, () => {
    completed++;
    if (completed < frameCount) present();
  });
  present();
  browser.runUntil(() => completed === frameCount);

  const failedPosts = frameCount;
  const scheduler = window.__gaiusFrameYieldScheduler;
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.messageChannelPostFailureCount, failedPosts);
  assert.equal(telemetry.cancelledMessageTaskCount, failedPosts);
  assert.equal(telemetry.messageChannelRebuildCount, failedPosts);
  assert.equal(telemetry.watchdogYieldCount || 0, 0);
  assert.equal(telemetry.timerYieldCount, frameCount);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(scheduler.tasks.size, 0);
  assert.equal(scheduler.channel, null);
  assert.equal(channelStats.created, failedPosts);
  assert.equal(channelStats.closed, failedPosts);
  assert.equal(channelStats.posts, failedPosts);
}

async function simulateUnavailableMessageChannel({constructorThrows = false} = {}) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const channelStats = {created: 0};
  const MessageChannel = constructorThrows
    ? createConstructorThrowingMessageChannelClass(channelStats)
    : undefined;
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Map,
    Math,
    MessageChannel,
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  scheduleYield(false, 0, () => completed++);
  browser.runUntil(() => completed === 1);

  assert.equal(telemetry.yieldRequestCount, 1);
  assert.equal(telemetry.yieldCompletionCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.messageChannelYieldCount || 0, 0);
  assert.equal(telemetry.messageChannelCreateFailureCount || 0, constructorThrows ? 1 : 0);
  assert.equal(telemetry.messageChannelPostFailureCount || 0, 0);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(window.__gaiusFrameYieldScheduler.tasks.size, 0);
  assert.equal(channelStats.created, constructorThrows ? 1 : 0);
}

function simulateFixedTimer(frameCount = 720) {
  const workPattern = [4.1, 4.4, 4.7, 4.3, 4.6, 4.2, 4.8, 4.5];
  const timerJitter = [0, 0.2, 0.8, 0.1, 1.1, 0.4, 0.6, 0.3];
  const presents = [0];
  let now = 0;
  for (let frame = 1; frame < frameCount; frame++) {
    now += 4 + timerJitter[frame % timerJitter.length];
    now += workPattern[frame % workPattern.length];
    presents.push(now);
  }
  return presents;
}

async function simulateHiddenYield() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(true, 0, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 50);
  assert.equal(telemetry.hiddenYieldCount, 1);
  assert.equal(telemetry.swapInterval, 0);
  assert.equal(telemetry.uncappedYieldCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.watchdogYieldCount || 0, 0);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.presentToRafCount || 0, 0);
}

async function simulateStalledRafYield() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: () => 1,
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(false, 1, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 100);
  assert.equal(telemetry.yieldCompletionCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.watchdogYieldCount, 1);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.presentToRafCount || 0, 0);
}

async function simulateStalledMessageYield() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4, messageDelay: 200});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(false, 0, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 100);
  assert.equal(telemetry.presentToRafCount || 0, 0);
  assert.equal(telemetry.uncappedYieldCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.watchdogYieldCount, 1);
  assert.equal(telemetry.messageChannelYieldCount || 0, 0);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  browser.runNext();
  assert.equal(telemetry.cancelledMessageTaskCount, 1);
  assert.equal(telemetry.messageChannelRebuildCount, 1);
  assert.equal(window.__gaiusFrameYieldScheduler.tasks.size, 0);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0,
    "retired MessageChannel delivered a late callback");
}

async function simulateOverlappingYields() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry, __gaiusMinecraftState: {level: true}};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  scheduleYield(false, 0, () => completed++);
  scheduleYield(false, 0, () => completed++);
  assert.equal(telemetry.pendingYieldCount, 2);
  assert.equal(telemetry.maxPendingYieldCount, 2,
    "overlapping continuations did not raise the historical pending high-water mark");
  browser.runUntil(() => completed === 2);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.yieldCompletionCount, 2);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
}

const visiblePresents = await simulateVisibleYield(120);
const highRefreshPresents = await simulateVisibleYield(144);
const uncapped = await simulateUncappedYield();
const cheapUncapped60 = await simulateCheapUncappedYield(60);
const cheapUncapped144 = await simulateCheapUncappedYield(144);
const menuUncapped60 = await simulateCheapUncappedYield(60, 300, {menu: true});
const menuUncapped144 = await simulateCheapUncappedYield(144, 600, {menu: true});
await simulateSchedulerIsolation();
await simulateDeadMessageChannel();
await simulateThrowingMessageChannel();
await simulateUnavailableMessageChannel();
await simulateUnavailableMessageChannel({constructorThrows: true});
await simulateHiddenYield();
await simulateStalledRafYield();
await simulateStalledMessageYield();
await simulateOverlappingYields();
const fixedTimerPresents = simulateFixedTimer();
const duration = Math.max(visiblePresents.at(-1), fixedTimerPresents.at(-1)) + 20;
const cooperativeFrames = displayedFrameTimes(visiblePresents, 120, duration);
const fixedTimerFrames = displayedFrameTimes(fixedTimerPresents, 120, duration);
const highRefreshFrames = displayedFrameTimes(
  highRefreshPresents,
  144,
  highRefreshPresents.at(-1) + 20,
);
const cooperativeP99 = percentile(cooperativeFrames, 0.99);
const fixedTimerP99 = percentile(fixedTimerFrames, 0.99);
const highRefreshP99 = percentile(highRefreshFrames, 0.99);
const cooperativeOnePercentLow = onePercentLowFps(cooperativeFrames);
const fixedTimerOnePercentLow = onePercentLowFps(fixedTimerFrames);
const highRefreshOnePercentLow = onePercentLowFps(highRefreshFrames);

assert.ok(cooperativeP99 < 9,
  `cooperative 120 Hz pacing lost its 1% low: p99=${cooperativeP99.toFixed(3)} ms`);
assert.ok(fixedTimerP99 > 15,
  `timer-clamp control did not reproduce the 1% low regression: p99=${fixedTimerP99.toFixed(3)} ms`);
assert.ok(highRefreshP99 < 7.2,
  `cooperative 144 Hz pacing was capped or unstable: p99=${highRefreshP99.toFixed(3)} ms`);
assert.ok(cooperativeOnePercentLow > 115,
  `cooperative 120 Hz 1% low regressed: ${cooperativeOnePercentLow.toFixed(1)} FPS`);
assert.ok(fixedTimerOnePercentLow < 70,
  `fixed timer control did not regress: ${fixedTimerOnePercentLow.toFixed(1)} FPS`);
assert.ok(highRefreshOnePercentLow > 138,
  `cooperative 144 Hz 1% low regressed: ${highRefreshOnePercentLow.toFixed(1)} FPS`);

console.log(
  `Frame pacing yield smoke passed (${sdl ? "" + RENDERPEARL_PROFILE.id + " BrowserSdl.SDL_GL_SwapWindow" : "" + CANONICAL_PROFILE.id + " BrowserGlfw.swapBuffers"}):`,
  `uncapped avg=${uncapped.averageFps.toFixed(1)}fps,`,
  `uncapped 1% low=${uncapped.onePercentLow.toFixed(1)}fps,`,
  `cheap uncapped 60Hz=${cheapUncapped60.toFixed(1)}fps, 144Hz=${cheapUncapped144.toFixed(1)}fps,`,
  `menu 60Hz=${menuUncapped60.toFixed(1)}fps, 144Hz=${menuUncapped144.toFixed(1)}fps,`,
  `120Hz 1% low=${cooperativeOnePercentLow.toFixed(1)}fps,`,
  `144Hz 1% low=${highRefreshOnePercentLow.toFixed(1)}fps,`,
  `fixed-1ms/clamped 1% low=${fixedTimerOnePercentLow.toFixed(1)}fps`,
);
