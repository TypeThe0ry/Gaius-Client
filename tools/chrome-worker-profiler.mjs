import {mkdir, writeFile} from 'node:fs/promises';
import {readFileSync} from 'node:fs';
import {dirname} from 'node:path';
import {installMacrotaskDiagnostic} from './worker-macrotask-diagnostic.mjs';
import {RUNTIME_GUARD_EXPRESSION} from './teavm-runtime-guards.mjs';

function snapshotFrameExpression(name) {
  switch (name) {
    case 'location':
      return 'String(globalThis.location&&globalThis.location.href)';
    case 'pending-runs':
      return 'globalThis.GaiusJspi&&GaiusJspi.pendingRuns()';
    case 'storage-stats':
      return 'globalThis.__gaiusStorageStats||null';
    case 'network-stats':
      return 'globalThis.__gaiusNetworkStats||null';
    case 'worker-status':
      return 'globalThis.__gaiusWorkerStatus||null';
    default:
      return null;
  }
}

// Diagnostic runs only: profiling overhead must not certify latency targets.
export async function startWorkerProfiler(cdp, outputPrefix, {
  captureProfile = true,
  runtimeConfig = null,
} = {}) {
  const entries = [];
  const pending = new Set();
  let stopping = false;
  let result;
  const traceMacrotasks = process.env.GAIUS_FILE_MACROTASK_TRACE === '1';
  const configuredKeys = runtimeConfig && typeof runtimeConfig === 'object'
    ? Object.keys(runtimeConfig)
    : [];
  const configureRuntime = configuredKeys.length > 0;
  // GAIUS_FILE_WORKER_INJECT=<file>: diagnostic script evaluated in every Worker before it runs.
  const injectPath = process.env.GAIUS_FILE_WORKER_INJECT || '';
  const injectSource = injectPath ? readFileSync(injectPath, 'utf8') : '';
  const pauseWorker = traceMacrotasks || configureRuntime || !!injectSource;
  const command = (entry, method, params = {}) =>
    cdp.send(method, params, 10000, entry.sessionId);
  const takeSnapshot = async (entry) => {
    const snapshot = {at: Date.now()};
    try {
      const state = await command(entry, 'Runtime.evaluate', {
        expression: 'JSON.stringify({pendingRuns:globalThis.GaiusJspi&&GaiusJspi.pendingRuns(),'
          + 'storage:globalThis.__gaiusStorageStats||null,network:globalThis.__gaiusNetworkStats||null,'
          + 'kernelClient:!!globalThis.__gaiusKernelClient,'
          + 'kernelStatus:globalThis.__gaiusKernelClient&&globalThis.__gaiusKernelClient.status(),'
          + 'worldgen:globalThis.__gaiusWorldgenStats||null,location:String(globalThis.location&&location.href)})',
        returnByValue: true,
      });
      snapshot.state = state.exceptionDetails ? state.exceptionDetails : JSON.parse(state.result.value);
    } catch (error) { snapshot.stateError = String(error.message || error); }
    try {
      await command(entry, 'Debugger.enable');
      const paused = new Promise((resolve) => {
        cdp.on('Debugger.paused', (event, eventSessionId) => {
          if (eventSessionId === entry.sessionId) resolve(event);
        });
        setTimeout(() => resolve(null), 3000);
      });
      await command(entry, 'Debugger.pause');
      const event = await paused;
      snapshot.paused = event ? (event.callFrames || []).slice(0, 40).map((frame) =>
        `${frame.functionName || '<anon>'} ${frame.url || ''}:${frame.location?.lineNumber}:`
          + `${frame.location?.columnNumber}`) : 'no pause within 3 s (idle)';
      if (event) {
        snapshot.asyncParent = event.asyncStackTrace ? event.asyncStackTrace.description : null;
        const frame = (event.callFrames || [])[0];
        // Keep the diagnostic selector finite: arbitrary environment text must never become
        // JavaScript sent to Debugger.evaluateOnCallFrame.
        const expression = snapshotFrameExpression(process.env.GAIUS_FILE_WORKER_SNAPSHOT_FRAME_EXPR || '');
        if (frame && expression) {
          try {
            const value = await command(entry, 'Debugger.evaluateOnCallFrame', {
              callFrameId: frame.callFrameId, expression, returnByValue: true,
            });
            snapshot.frameValue = value.exceptionDetails
              ? {exception: value.exceptionDetails.exception?.description || value.exceptionDetails.text}
              : value.result.value;
          } catch (error) { snapshot.frameError = String(error.message || error); }
        }
        await command(entry, 'Debugger.resume');
      }
      await command(entry, 'Debugger.disable');
    } catch (error) { snapshot.pauseError = String(error.message || error); }
    if (entry.started) {
      try {
        const {profile} = await command(entry, 'Profiler.stop');
        entry.started = false;
        entry.snapshotProfilePath = `${outputPrefix}.worker-snapshot-${entries.indexOf(entry)}.cpuprofile`;
        await mkdir(dirname(entry.snapshotProfilePath), {recursive: true});
        await writeFile(entry.snapshotProfilePath, JSON.stringify(profile));
      } catch (error) { snapshot.profileError = String(error.message || error); }
    }
    entry.snapshot = snapshot;
  };
  cdp.on('Target.attachedToTarget', ({sessionId, targetInfo}) => {
    if (stopping || targetInfo.type !== 'worker') return;
    const entry = {sessionId, targetId: targetInfo.targetId, url: targetInfo.url,
      started: false, saved: false, console: [], exceptions: []};
    // Worker failures do not bubble through the page Runtime domain. Keep a
    // bounded copy of the worker's console and exception events alongside the
    // optional CPU profile so a singleplayer disconnect can be attributed to
    // bootstrap, login, or transport code instead of being reported as a
    // generic timeout.
    cdp.on('Runtime.consoleAPICalled', (event, eventSessionId) => {
      if (eventSessionId !== entry.sessionId) return;
      if (entry.console.length >= 256) entry.console.shift();
      entry.console.push({
        type: event.type || 'log',
        text: (event.args || []).map((arg) =>
          arg.value ?? arg.description ?? '').join(' '),
      });
    });
    cdp.on('Runtime.exceptionThrown', (event, eventSessionId) => {
      if (eventSessionId !== entry.sessionId) return;
      if (entry.exceptions.length >= 64) entry.exceptions.shift();
      const details = event.exceptionDetails || {};
      entry.exceptions.push({
        text: details.text || 'exception',
        description: details.exception?.description || null,
        lineNumber: details.lineNumber ?? null,
        columnNumber: details.columnNumber ?? null,
      });
    });
    entries.push(entry);
    // GAIUS_FILE_WORKER_SNAPSHOT_MS=<ms>: diagnostic snapshot of the Worker while it still runs
    // (a stalled startup is terminated by the page watchdog before stop() could look at it):
    // its storage/network/scheduler globals, a paused call stack, and the CPU profile so far.
    const snapshotDelay = Number(process.env.GAIUS_FILE_WORKER_SNAPSHOT_MS || 0);
    if (snapshotDelay > 0) {
      setTimeout(() => {
        const job = takeSnapshot(entry).catch((error) => {
          entry.snapshotError = String(error.message || error);
        });
        pending.add(job);
        void job.finally(() => pending.delete(job));
      }, snapshotDelay);
    }
    const job = (async () => {
      try {
        await command(entry, 'Runtime.enable');
        if (configureRuntime) {
          const declarations = configuredKeys.map(key => {
            const value = JSON.stringify(runtimeConfig[key]);
            return `Object.defineProperty(globalThis,${JSON.stringify(key)},{` +
              `configurable:true,get:()=>${value},set:()=>{}});`;
          }).join('');
          const injected = await command(entry, 'Runtime.evaluate', {
            expression: `(()=>{${declarations}return ${JSON.stringify(runtimeConfig)}})()`,
            returnByValue: true,
          });
          if (injected.exceptionDetails) throw new Error('Worker runtime configuration failed');
          entry.runtimeConfig = injected.result?.value || runtimeConfig;
          entry.configured = true;
        }
        if (injectSource) {
          const injected = await command(entry, 'Runtime.evaluate', {expression: injectSource});
          if (injected.exceptionDetails) throw new Error('Worker diagnostic injection failed');
        }
        if (traceMacrotasks) {
          const injected = await command(entry, 'Runtime.evaluate', {
            expression: `(${installMacrotaskDiagnostic.toString()})()`,
          });
          if (injected.exceptionDetails) throw new Error('Macrotask trace injection failed');
        }
        if (captureProfile) {
          await command(entry, 'Profiler.enable');
          await command(entry, 'Profiler.setSamplingInterval', {interval: 1000});
          await command(entry, 'Profiler.start');
          entry.started = true;
        }
      } catch (error) { entry.error = String(error.message || error); }
      finally {
        if (pauseWorker) {
          try { await command(entry, 'Runtime.runIfWaitingForDebugger'); }
          catch (error) { entry.resumeError = String(error.message || error); }
        }
      }
    })();
    pending.add(job);
    void job.finally(() => pending.delete(job));
  });
  await cdp.send('Target.setAutoAttach', {
    autoAttach: true, waitForDebuggerOnStart: pauseWorker, flatten: true,
    filter: [{type: 'worker', exclude: false}, {exclude: true}],
  });
  return {
    async stop() {
      if (result) return result;
      stopping = true;
      await Promise.all([...pending]);
      for (const entry of entries) {
        // Worker-side network pump and input counters live in the Worker's
        // global scope; snapshot them so a stalled login can be attributed.
        try {
          const snapshot = await command(entry, 'Runtime.evaluate', {
            expression: 'JSON.stringify({network:globalThis.__gaiusNetworkStats||null,'
              + 'worldgen:globalThis.__gaiusWorldgenStats||null,'
              + `runtimeGuards:${RUNTIME_GUARD_EXPRESSION}})`,
            returnByValue: true,
          });
          entry.globals = JSON.parse(snapshot.result.value);
        } catch (error) { entry.globalsError = String(error.message || error); }
      }
      for (const [index, entry] of entries.entries()) {
        if (!captureProfile || !entry.started) continue;
        try {
          if (traceMacrotasks) {
            const snapshot = await command(entry, 'Runtime.evaluate', {
              expression: 'JSON.stringify({trace:globalThis.__gaiusMacrotaskDiagnostic,worldgen:globalThis.__gaiusWorldgenStats})',
              returnByValue: true,
            });
            entry.macrotaskTrace = JSON.parse(snapshot.result.value);
          }
          const {profile} = await command(entry, 'Profiler.stop');
          entry.path = `${outputPrefix}.worker-${index}.cpuprofile`;
          await mkdir(dirname(entry.path), {recursive: true});
          await writeFile(entry.path, JSON.stringify(profile));
          entry.samples = profile.samples?.length || 0;
          entry.saved = true;
        } catch (error) { entry.error = String(error.message || error); }
      }
      let detachError = null;
      try {
        await cdp.send('Target.setAutoAttach', {
          autoAttach: false, waitForDebuggerOnStart: false, flatten: true,
        });
      } catch (error) { detachError = String(error.message || error); }
      result = {diagnosticOnly: true, latencyAcceptanceEligible: false,
        captureProfile, runtimeConfig, entries, detachError,
        complete: entries.length > 0
          && entries.every(entry => (!captureProfile || entry.saved)
            && (!configureRuntime || entry.configured)
            && !entry.error && !entry.resumeError)
          && !detachError};
      return result;
    },
  };
}
