// Bounded Chrome DevTools capture for page/worker starvation investigations.
// The caller must call start() before the capture window and trackTarget() for
// every relevant page/worker target. No Runtime.evaluate is used for stopping.
import {mkdir, writeFile, open} from "node:fs/promises";
import {join} from "node:path";

async function withTimeout(promise, ms) {
  let timer;
  try { return await Promise.race([promise, new Promise(resolve => { timer = setTimeout(() => resolve(null), ms); })]); }
  finally { clearTimeout(timer); }
}

function safeName(value) {
  return String(value || "target").replace(/[^a-zA-Z0-9_.-]+/g, "_").slice(0, 100) || "target";
}

export class BoundedCdpCapture {
  constructor({conn, traceConn = conn, out, durationMs = 150000, commandTimeoutMs = 5000, log = () => {}} = {}) {
    if (!conn || typeof conn.send !== "function") throw new TypeError("conn.send is required");
    if (!traceConn || typeof traceConn.send !== "function") throw new TypeError("traceConn.send is required");
    this.conn = conn;
    this.traceConn = traceConn;
    this.out = out;
    this.durationMs = Math.max(1, Number(durationMs) || 150000);
    this.commandTimeoutMs = Math.max(100, Number(commandTimeoutMs) || 5000);
    this.categories = ["devtools.timeline", "v8.execute", "v8.cpu_profiler",
      "disabled-by-default-v8.cpu_profiler", "disabled-by-default-gpu.service",
      "disabled-by-default-devtools.timeline"];
    this.log = log;
    this.targets = new Map(); // sessionId -> stable target identity
    this.targetStarts = new Map(); // sessionId -> in-flight profiler start
    this.started = false;
    this.stopped = false;
    this.traceComplete = null;
    this.traceCompletePromise = new Promise(resolve => { this.resolveTraceComplete = resolve; });
    this.timer = null;
    this.startedAt = 0;
    this.listener = message => this.#onMessage(message);
    this.traceCompleteListener = (value, sessionId) => this.#onMessage(
      value?.method ? value : {method: "Tracing.tracingComplete", params: value, sessionId});
    this.traceDataListener = (value, sessionId) => this.#onMessage(
      value?.method ? value : {method: "Tracing.dataCollected", params: value, sessionId});
    this.traceEvents = [];
    this.traceEventBytes = 0;
    this.traceEventsTruncated = false;
  }

  async #send(method, params = {}, sessionId) {
    const request = Promise.resolve().then(() => this.conn.send(method, params, sessionId));
    let timer;
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`CDP ${method} timed out`)), this.commandTimeoutMs);
      timer.unref?.();
    });
    try {
      return await Promise.race([request, timeout]);
    } finally {
      clearTimeout(timer);
    }
  }

  async #sendTrace(method, params = {}) {
    const send = this.traceConn.sendRoot || this.traceConn.send;
    const request = Promise.resolve().then(() => send.call(this.traceConn, method, params));
    let timer;
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`CDP ${method} timed out`)), this.commandTimeoutMs);
      timer.unref?.();
    });
    try {
      return await Promise.race([request, timeout]);
    } finally {
      clearTimeout(timer);
    }
  }

  #onMessage(message) {
    if (message?.method === "Tracing.dataCollected") {
      const values = Array.isArray(message.params?.value) ? message.params.value : [];
      for (const value of values) {
        const bytes = Buffer.byteLength(JSON.stringify(value));
        if (this.traceEventBytes + bytes > 32 * 1024 * 1024) {
          this.traceEventsTruncated = true;
          break;
        }
        this.traceEvents.push(value);
        this.traceEventBytes += bytes;
      }
      return;
    }
    if (message?.method !== "Tracing.tracingComplete") return;
    this.traceComplete = message.params || {};
    this.resolveTraceComplete(this.traceComplete);
  }

  #listen() {
    const connections = this.traceConn === this.conn ? [this.traceConn] : [this.traceConn, this.conn];
    for (const connection of connections) {
      if (Array.isArray(connection.listeners) && !connection.listeners.includes(this.listener)) {
        connection.listeners.push(this.listener);
      } else if (typeof connection.on === "function") {
        connection.on("Tracing.tracingComplete", this.traceCompleteListener);
        connection.on("Tracing.dataCollected", this.traceDataListener);
      }
    }
  }

  #unlisten() {
    const connections = this.traceConn === this.conn ? [this.traceConn] : [this.traceConn, this.conn];
    for (const connection of connections) {
      if (Array.isArray(connection.listeners)) {
        const i = connection.listeners.indexOf(this.listener);
        if (i >= 0) connection.listeners.splice(i, 1);
      }
      if (typeof connection.off === "function") {
        connection.off("Tracing.tracingComplete", this.traceCompleteListener);
        connection.off("Tracing.dataCollected", this.traceDataListener);
      }
    }
  }

  async start() {
    if (this.started) return;
    this.started = true;
    this.startedAt = Date.now();
    this.#listen();
    await this.#sendTrace("Tracing.start", {
      categories: this.categories.join(","),
      options: "record-continuously",
      bufferUsageReportingInterval: 1000,
      // ReportEvents is the reliable mode for both a direct page websocket and a
      // flattened browser session. Keep a bounded dataCollected fallback below;
      // some Chrome builds omit the ReturnAsStream handle on a target session.
      transferMode: "ReportEvents",
    });
    this.timer = setTimeout(() => { this.stop("timer").catch(error => this.log("capture-stop-error", String(error))); }, this.durationMs);
    this.log("capture-start", `trace duration=${this.durationMs}ms categories=devtools.timeline,v8.cpu_profiler`);
  }

  async trackTarget({sessionId, targetId, type = "target", url = "", owner = ""} = {}) {
    if (sessionId === null || this.stopped) return false;
    const identity = {sessionId, targetId: String(targetId || ""), type: String(type), url: String(url || ""), owner: String(owner || "")};
    const key = sessionId ?? "";
    if (this.targets.has(key)) {
      const pending = this.targetStarts.get(key);
      return pending ? await pending : !this.targets.get(key).error;
    }
    this.targets.set(key, identity);
    const start = (async () => {
      try {
        await this.#send("Profiler.enable", {}, sessionId);
        await this.#send("Profiler.start", {}, sessionId);
        this.log("capture-profiler-start", `${identity.owner || "?"} ${identity.type} ${identity.url} session=${sessionId}`);
        return true;
      } catch (error) {
        identity.error = String(error);
        this.log("capture-profiler-error", `${identity.owner || "?"} ${identity.type} ${identity.url}: ${identity.error}`);
        return false;
      } finally {
        this.targetStarts.delete(key);
      }
    })();
    this.targetStarts.set(key, start);
    return await start;
  }

  async #stopProfiler(identity) {
    try {
      const result = await this.#send("Profiler.stop", {}, identity.sessionId);
      const file = join(this.out, `cpu-${safeName(identity.owner)}-${safeName(identity.type)}-${safeName(identity.targetId || identity.sessionId)}.json`);
      await writeFile(file, JSON.stringify({identity, profile: result?.profile || result}, null, 2));
      return {identity, file};
    } catch (error) {
      this.log("capture-profiler-stop-error", `${identity.owner || "?"} ${identity.type}: ${String(error)}`);
      return {identity, error: String(error)};
    }
  }

  async #readStream(handle) {
    const file = join(this.out, "trace.json");
    const output = await open(file, "w");
    let bytes = 0;
    try {
      for (;;) {
        const part = await this.#sendTrace("IO.read", {handle, size: 1024 * 1024});
        const data = Buffer.from(part?.data || "", part?.base64Encoded ? "base64" : "utf8");
        bytes += data.length;
        if (bytes > 256 * 1024 * 1024) throw new Error("trace stream exceeds 256 MiB limit");
        await output.write(data);
        if (part?.eof) break;
      }
      return {file, stream: handle, bytes};
    } finally {
      await output.close();
      await this.#sendTrace("IO.close", {handle}).catch(() => {});
    }
  }

  stop(reason = "manual") {
    if (!this.stopPromise) this.stopPromise = this.#stop(reason);
    return this.stopPromise;
  }

  async #stop(reason) {
    if (this.stopped) return this.result;
    this.stopped = true;
    await mkdir(this.out, {recursive: true});
    if (this.timer) clearTimeout(this.timer);
    await Promise.allSettled(this.targetStarts.values());
    const profilers = [...this.targets.values()];
    const profileResults = await Promise.all(profilers.map(identity => this.#stopProfiler(identity)));
    let trace = null;
    try {
      await this.#sendTrace("Tracing.end");
      const complete = await withTimeout(this.traceCompletePromise, this.commandTimeoutMs);
      if (complete?.stream) {
        trace = await this.#readStream(complete.stream);
      } else if (this.traceEvents.length > 0) {
        const file = join(this.out, "trace.json");
        const content = JSON.stringify({traceEvents: this.traceEvents});
        await writeFile(file, content);
        const bytes = Buffer.byteLength(content);
        trace = {file, stream: null, bytes};
        if (this.traceEventsTruncated) trace.truncated = true;
      } else {
        this.log("capture-trace-error", JSON.stringify({message: "Tracing.tracingComplete did not provide a stream",
          completeKeys: Object.keys(complete || {}),collectedEvents: this.traceEvents.length,
          collectedBytes: this.traceEventBytes,truncated: this.traceEventsTruncated}));
      }
    } catch (error) {
      this.log("capture-trace-error", String(error));
    } finally {
      this.#unlisten();
    }
    this.result = {reason, elapsedMs: this.startedAt ? Date.now() - this.startedAt : null,
      categories: this.categories.slice(), trace, profiles: profileResults, targets: profilers.map(x => x)};
    this.log("capture-stop", JSON.stringify({reason, trace, profiles: profileResults.length}));
    return this.result;
  }
}

export function captureIdentity({sessionId, targetId, type, url, owner}) {
  return {sessionId: String(sessionId || ""), targetId: String(targetId || ""), type: String(type || "target"), url: String(url || ""), owner: String(owner || "")};
}
