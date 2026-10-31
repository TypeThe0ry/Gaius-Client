const DEFAULT_WRITE_TIMEOUT_MS = 5000;

/**
 * Write one final record and wait until both the Writable callback and a
 * backpressured drain have completed.  A direct process.exit() can discard a
 * buffered tail when stdout is redirected to a pipe, so callers must await
 * this helper before tearing down their worker/process.
 */
export function writeChunkAndDrain(stream, chunk, {
  timeoutMs = DEFAULT_WRITE_TIMEOUT_MS,
} = {}) {
  if (stream === null || typeof stream !== "object" ||
      typeof stream.write !== "function") {
    return Promise.reject(new TypeError("writeChunkAndDrain requires a Writable stream"));
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    return Promise.reject(new RangeError("writeChunkAndDrain timeoutMs must be positive"));
  }
  return new Promise((resolve, reject) => {
    let writeCompleted = false;
    let drainCompleted = true;
    let drainObserved = false;
    let writeCallReturned = false;
    let settled = false;
    let timer;

    const cleanup = () => {
      if (timer !== undefined) clearTimeout(timer);
      if (typeof stream.removeListener === "function") {
        stream.removeListener("error", onError);
        stream.removeListener("drain", onDrain);
      }
    };
    const complete = () => {
      if (!settled && writeCallReturned && writeCompleted && drainCompleted) {
        settled = true;
        cleanup();
        resolve();
      }
    };
    const fail = (error) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(error instanceof Error ? error : new Error(String(error)));
    };
    const onError = (error) => fail(error);
    const onDrain = () => {
      drainObserved = true;
      drainCompleted = true;
      complete();
    };
    const onWrite = (error) => {
      if (error !== undefined && error !== null) {
        fail(error);
        return;
      }
      writeCompleted = true;
      complete();
    };

    if (typeof stream.once === "function") {
      stream.once("error", onError);
      // Register before write(): a custom Writable may emit drain
      // synchronously while accepting the final chunk.
      stream.once("drain", onDrain);
    }
    timer = setTimeout(() => {
      fail(new Error(`Timed out waiting for final stdout write after ${timeoutMs} ms`));
    }, timeoutMs);
    try {
      const accepted = stream.write(chunk, onWrite);
      drainCompleted = accepted !== false || drainObserved;
      if (drainCompleted && typeof stream.removeListener === "function") {
        stream.removeListener("drain", onDrain);
      }
      writeCallReturned = true;
      complete();
    } catch (error) {
      fail(error);
    }
  });
}

/**
 * A successful smoke run with a missing final record is still a failed run.
 * Preserve every already-nonzero reason exactly as requested by the caller.
 */
export function effectiveExitCode(requestedCode, finalOutputFailed) {
  return finalOutputFailed && requestedCode === 0 ? 1 : requestedCode;
}

export const FINAL_OUTPUT_WRITE_TIMEOUT_MS = DEFAULT_WRITE_TIMEOUT_MS;
