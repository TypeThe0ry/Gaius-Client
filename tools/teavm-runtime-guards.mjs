// Acceptance checks for the TeaVM suspension guards that
// port/tools/src/main/java/dev/gaius/tools/TeaVMCoreBrowserPatcher.java adds
// to thread.js. A guard error is a JS Error that TeaVM wraps into a
// java.lang.RuntimeException, so Java code that catches Exception or
// Throwable can swallow it (a CompletableFuture that never completes, a
// skipped tick). The page or Worker then keeps running and an uncaught
// exception never shows up; the runs read the guard counters and scan the
// console text instead.

// Evaluated in a page or Worker global scope; the result is plain JSON.
export const RUNTIME_GUARD_EXPRESSION = '({'
  + 'noSuspendViolations:globalThis.__gaiusNoSuspendViolations|0,'
  + 'lastNoSuspendViolation:globalThis.__gaiusLastNoSuspendViolation'
  + '?String(globalThis.__gaiusLastNoSuspendViolation).slice(0,4000):null,'
  + 'clinitSuspensions:globalThis.__gaiusClinitSuspensions|0,'
  + 'firstClinitSuspension:globalThis.__gaiusFirstClinitSuspension'
  + '?String(globalThis.__gaiusFirstClinitSuspension).slice(0,4000):null'
  + '})';

// Console or exception text that fails a run. The first is the no-suspend
// guard; the second is TeaVM's monitorEnterSync on a contended monitor of a
// synchronized method that gaius.teavm.syncMonitors compiled synchronously,
// which never reaches the guard counter.
export const RUNTIME_GUARD_FAILURE_TEXTS = Object.freeze([
  'Gaius: a TeaVM suspension was reached inside a method',
  "Can't enter monitor from another thread synchronously",
]);

// Diagnostic only: valid when the class initializer was triggered from an
// async caller.
export const CLINIT_SUSPENSION_TEXT =
  'Gaius: a TeaVM suspension was reached inside a class initializer';

const MAX_FAILURES = 16;

function entryText(entry) {
  if (entry == null) return '';
  if (typeof entry === 'string') return entry;
  if (typeof entry !== 'object') return String(entry);
  const parts = [];
  for (const key of ['text', 'description', 'error', 'message']) {
    if (typeof entry[key] === 'string') parts.push(entry[key]);
  }
  if (Array.isArray(entry.args)) {
    for (const value of entry.args) parts.push(entryText(value));
  }
  return parts.join(' ');
}

// snapshots: RUNTIME_GUARD_EXPRESSION results (null entries are ignored).
// entries: console messages, exceptions or log entries in any of the shapes
// the CDP scripts record ({text}, {args}, {description}, plain strings).
// Returns failure descriptions; empty when the guards stayed quiet.
export function runtimeGuardFailures({snapshots = [], entries = [], label = ''} = {}) {
  const prefix = label ? `${label}: ` : '';
  const failures = [];
  for (const snapshot of snapshots) {
    const count = Number(snapshot?.noSuspendViolations) || 0;
    if (count > 0) {
      const last = String(snapshot.lastNoSuspendViolation || '').split('\n').slice(0, 6).join(' | ');
      failures.push(`${prefix}${count} TeaVM no-suspend guard violation(s); last: ${last}`);
    }
  }
  for (const entry of entries) {
    const text = entryText(entry);
    const needle = RUNTIME_GUARD_FAILURE_TEXTS.find((candidate) => text.includes(candidate));
    if (needle) failures.push(`${prefix}${text.replace(/\s+/g, ' ').slice(0, 600)}`);
  }
  const unique = [...new Set(failures)];
  return unique.length > MAX_FAILURES
    ? [...unique.slice(0, MAX_FAILURES), `${prefix}${unique.length - MAX_FAILURES} more`]
    : unique;
}

export function clinitSuspensionCount(snapshots = []) {
  return snapshots.reduce((maximum, snapshot) =>
    Math.max(maximum, Number(snapshot?.clinitSuspensions) || 0), 0);
}
