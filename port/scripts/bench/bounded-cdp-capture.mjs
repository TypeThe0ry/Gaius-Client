// Bounded Chrome DevTools capture for page/worker starvation investigations.
// The caller must call start() before navigation and trackTarget() for every
// attached page/worker target. No Runtime.evaluate is used for stopping.
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
  constructor({conn, out, durationMs = 150000, commandTimeoutMs = 5000, log = () => {}} = {}) {
    if (!conn || typeof conn.send !== "function") throw new TypeError("conn.send is required");
    this.conn = conn;
    this.out = out;
    this.durationMs = Math.max(1, Number(durationMs) || 150000);
    this.commandTimeoutMs = Math.max(100, Number(commandTimeoutMs) || 5000);
    this.log = log;
    this.targets = new Map(); // sessionId -> stable target identity
    this.started = false;
    this.stopped = false;
    this.traceComplete = null;
    this.traceCompletePromise = new Promise(resolve => { this.resolveTraceComplete = resolve; });
    this.timer = null;
    this.listener = message => this.#onMessage(message);
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

  #onMessage(message) {
    if (message?.method !== "Tracing.tracingComplete") return;
    this.traceComplete = message.params || {};
    this.resolveTraceComplete(this.traceComplete);
  }

  #listen() {
    if (Array.isArray(this.conn.listeners) && !this.conn.listeners.includes(this.listener)) {
      this.conn.listeners.push(this.listener);
    } else if (typeof this.conn.on === "function") {
      this.conn.on("Tracing.tracingComplete", this.listener);
    }
  }

  #unlisten() {
    if (Array.isArray(this.conn.listeners)) {
      const i = this.conn.listeners.indexOf(this.listener);
      if (i >= 0) this.conn.listeners.splice(i, 1);
    }
    if (typeof this.conn.off === "function") this.conn.off("Tracing.tracingComplete", this.listener);
  }

  async start() {
    if (this.started) return;
    this.started = true;
    this.#listen();
    await this.#send("Tracing.start", {
      categories: ["devtools.timeline", "v8.cpu_profiler", "disabled-by-default-v8.cpu_profiler"].join(","),
      options: "record-continuously",
      bufferUsageReportingInterval: 1000,
      transferMode: "ReturnAsStream",
    });
    this.timer = setTimeout(() => { this.stop("timer").catch(error => this.log("capture-stop-error", String(error))); }, this.durationMs);
    this.log("capture-start", `trace duration=${this.durationMs}ms categories=devtools.timeline,v8.cpu_profiler`);
  }

  async trackTarget({sessionId, targetId, type = "target", url = "", owner = ""} = {}) {
    if (!sessionId || this.stopped) return false;
    const identity = {sessionId, targetId: String(targetId || ""), type: String(type), url: String(url || ""), owner: String(owner || "")};
    this.targets.set(sessionId, identity);
    try {
      await this.#send("Profiler.enable", {}, sessionId);
      await this.#send("Profiler.start", {}, sessionId);
      this.log("capture-profiler-start", `${identity.owner || "?"} ${identity.type} ${identity.url} session=${sessionId}`);
      return true;
    } catch (error) {
      identity.error = String(error);
      this.log("capture-profiler-error", `${identity.owner || "?"} ${identity.type} ${identity.url}: ${identity.error}`);
      return false;
    }
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
        const part = await this.#send("IO.read", {handle, size: 1024 * 1024});
        const data = Buffer.from(part?.data || "", part?.base64Encoded ? "base64" : "utf8");
        bytes += data.length;
        if (bytes > 256 * 1024 * 1024) throw new Error("trace stream exceeds 256 MiB limit");
        await output.write(data);
        if (part?.eof) break;
      }
      return {file, stream: handle, bytes};
    } finally {
      await output.close();
      await this.#send("IO.close", {handle}).catch(() => {});
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
    const profilers = [...this.targets.values()];
    const profileResults = await Promise.all(profilers.map(identity => this.#stopProfiler(identity)));
    let trace = null;
    try {
      await this.#send("Tracing.end");
      const complete = await withTimeout(this.traceCompletePromise, this.commandTimeoutMs);
      if (complete?.stream) {
        trace = await this.#readStream(complete.stream);
      } else {
        this.log("capture-trace-error", "Tracing.tracingComplete did not provide a stream");
      }
    } catch (error) {
      this.log("capture-trace-error", String(error));
    } finally {
      this.#unlisten();
    }
    this.result = {reason, trace, profiles: profileResults, targets: profilers.map(x => x)};
    this.log("capture-stop", JSON.stringify({reason, trace, profiles: profileResults.length}));
    return this.result;
  }
}

export function captureIdentity({sessionId, targetId, type, url, owner}) {
  return {sessionId: String(sessionId || ""), targetId: String(targetId || ""), type: String(type || "target"), url: String(url || ""), owner: String(owner || "")};
}
