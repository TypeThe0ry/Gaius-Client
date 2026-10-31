// Gaius kernel worker: hosts wasm kernel instances and runs the jobs that kernel-pool.js or
// kernel-runtime.js post to it. Both start this script from a URL or a Blob URL of its source,
// so it must stay a self-contained classic worker script with no imports.
//
// Kernel ABI (wasm32, little-endian):
//   memory                      exported linear memory (or an imported env.memory, see below)
//   alloc(len) -> ptr           reserves len bytes for the job payload; 0 when out of memory
//   run_<kind>(ptr, len) -> hdr runs one kernel; hdr points at three u32: status, data_ptr,
//                               data_len. Status 0: data is the result bytes. Otherwise data is
//                               a UTF-8 message (it may be empty).
//   dealloc(ptr, len)           optional, releases the payload after the run
//   release(hdr)                optional, releases the result after it was copied out
//   reset()                     optional, called after every job (arena allocators)
//   trim() / <name>_trim()      optional, drop cached state and scratch buffers (memory pressure)
//
// One worker can host several kernels (mesh, light, worldgen, noise), one instance each, so
// the runtime keeps a single pool of generic workers and loads a kernel where it is needed.
// A module that imports env.memory gets the WebAssembly.Memory sent with its load message
// (a shared memory when the page is cross-origin isolated); otherwise such an import fails.
//
// Messages in:
//   {type: "init", module | bytes}                      single-kernel pool (kernel-pool.js)
//   {type: "load", kernel, module | bytes, memory?}     multi-kernel runtime (kernel-runtime.js)
//   {type: "unload", kernel}
//   {type: "job", id, kernel?, kind, payload}           kernel defaults to the init module
//   {type: "cancel", id}
//   {type: "trim"}                                      call the trim exports of every instance
// Messages out:
//   {type: "ready", kinds}, {type: "init-error", message}
//   {type: "loaded", kernel, kinds, memoryBytes}, {type: "load-error", kernel, message}
//   {type: "unloaded", kernel}, {type: "trimmed", kernel, memoryBytes}
//   {type: "result", id, result, execMs, kernel, memoryBytes}
//   {type: "error", id, code, status?, message, execMs?, kernel}
//   {type: "cancelled", id}
(function (scope) {
  "use strict";

  const now = () => (scope.performance && scope.performance.now ? scope.performance.now() : Date.now());
  const LEGACY = "";
  const queue = [];
  const kernels = new Map();  // kernel name -> {module, compiling, instance, instancing, memory}
  let draining = false;

  const post = (message, transfer) => scope.postMessage(message, transfer || []);

  // Mesh results are the large ones (vertex data of a whole section). The page sends their
  // buffers back once Java has copied the vertices out ({type: "recycle"}), and the next mesh
  // result is written into a pooled buffer instead of a new one, so meshing does not leave a
  // trail of dead ArrayBuffers for the page's collector. New buffers are rounded up to 64 KiB so
  // later results fit; the result header names the payload length, so the decoder ignores the tail.
  const RECYCLE_KIND = "mesh_section";
  const POOL_MAX_BUFFERS = 16;
  const POOL_MAX_BYTES = 16 * 1024 * 1024;
  const pool = [];
  let poolBytes = 0;

  function recycle(buffer) {
    if (Object.prototype.toString.call(buffer) !== "[object ArrayBuffer]" || buffer.byteLength === 0) return;
    if (pool.length >= POOL_MAX_BUFFERS || poolBytes + buffer.byteLength > POOL_MAX_BYTES) return;
    pool.push(buffer);
    poolBytes += buffer.byteLength;
  }

  function resultBuffer(kind, length) {
    if (kind !== RECYCLE_KIND) return new ArrayBuffer(length);
    let best = -1;
    for (let i = 0; i < pool.length; i++) {
      if (pool[i].byteLength >= length && (best < 0 || pool[i].byteLength < pool[best].byteLength)) best = i;
    }
    if (best >= 0) {
      const buffer = pool[best];
      pool[best] = pool[pool.length - 1];
      pool.pop();
      poolBytes -= buffer.byteLength;
      return buffer;
    }
    return new ArrayBuffer(Math.max(length, Math.ceil(length / 65536) * 65536));
  }
  const describe = (error) => (error && error.message ? error.message : String(error));

  // Between jobs the worker yields one task so cancel messages can reach the queue.
  const channel = typeof scope.MessageChannel === "function" ? new scope.MessageChannel() : null;
  const yieldWaiters = [];
  if (channel) channel.port1.onmessage = () => yieldWaiters.shift()();
  const yieldTurn = () => new Promise((resolve) => {
    if (channel) {
      yieldWaiters.push(resolve);
      channel.port2.postMessage(0);
    } else {
      setTimeout(resolve, 0);
    }
  });

  function decodeText(bytes) {
    if (typeof scope.TextDecoder === "function") return new scope.TextDecoder().decode(bytes);
    let text = "";
    for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
    return text;
  }

  function byteBuffer(value) {
    if (Object.prototype.toString.call(value) === "[object ArrayBuffer]") return value;
    if (ArrayBuffer.isView(value)) {
      return new Uint8Array(value.buffer, value.byteOffset, value.byteLength).slice().buffer;
    }
    throw new TypeError("kernel bytes must be an ArrayBuffer or typed-array view");
  }

  function validateWasmBytes(value) {
    const bytes = new Uint8Array(byteBuffer(value));
    if (bytes.byteLength < 4 || bytes[0] !== 0 || bytes[1] !== 0x61 || bytes[2] !== 0x73 || bytes[3] !== 0x6d) {
      throw new TypeError("kernel bytes do not have the WebAssembly magic header");
    }
    return bytes.buffer;
  }

  // Kernels are built without imports; an imported memory is satisfied from the load message
  // and anything else the module imports fails loudly when called.
  function buildImports(wasmModule, memory) {
    const imports = {};
    for (const entry of WebAssembly.Module.imports(wasmModule)) {
      const space = imports[entry.module] || (imports[entry.module] = {});
      if (entry.kind === "memory" && memory) {
        space[entry.name] = memory;
        continue;
      }
      if (entry.kind !== "function") throw new Error(`unsupported kernel import ${entry.module}.${entry.name} (${entry.kind})`);
      space[entry.name] = () => {
        throw new Error(`kernel called unsupported import ${entry.module}.${entry.name}`);
      };
    }
    return imports;
  }

  function memoryOf(exports, state) {
    const memory = exports.memory || state.memory;
    return memory && memory.buffer ? memory : null;
  }

  async function instantiate(state) {
    if (state.module === null) state.module = await state.compiling;
    const created = await WebAssembly.instantiate(state.module, buildImports(state.module, state.memory));
    const exports = created.exports;
    if (!memoryOf(exports, state) || typeof exports.alloc !== "function") {
      throw new Error("kernel module must export memory (or import env.memory) and alloc");
    }
    state.instance = created;
  }

  function ensureInstance(state) {
    if (state.instance !== null) return Promise.resolve();
    if (state.instancing === null) instancingOf(state);
    return state.instancing;
  }

  function instancingOf(state) {
    state.instancing = instantiate(state).finally(() => { state.instancing = null; });
    return state.instancing;
  }

  function kernelKinds(wasmModule) {
    const kinds = [];
    for (const entry of WebAssembly.Module.exports(wasmModule)) {
      if (entry.kind === "function" && entry.name.startsWith("run_")) kinds.push(entry.name.slice(4));
    }
    return kinds;
  }

  function newState(message) {
    const state = {module: null, compiling: null, instance: null, instancing: null,
      memory: message.memory || null, generation: Number(message.generation) || 0};
    if (message.module) state.module = message.module;
    else if (message.bytes) state.compiling = WebAssembly.compile(validateWasmBytes(message.bytes));
    else throw new Error("load message carries no kernel module");
    return state;
  }

  async function init(message) {
    try {
      const state = newState(message);
      kernels.set(LEGACY, state);
      await ensureInstance(state);
      post({type: "ready", kinds: kernelKinds(state.module)});
      scheduleDrain();
    } catch (error) {
      kernels.delete(LEGACY);
      post({type: "init-error", message: describe(error)});
    }
  }

  async function load(message) {
    const name = String(message.kernel || "");
    let state = null;
    try {
      state = newState(message);
      kernels.set(name, state);
      await ensureInstance(state);
      const exports = state.instance.exports;
      const memory = memoryOf(exports, state);
      // An unload can arrive while instantiation is suspended. Do not announce an instance
      // that is no longer the state registered for this generation.
      if (kernels.get(name) !== state) return;
      post({type: "loaded", kernel: name, generation: state.generation,
        kinds: kernelKinds(state.module), memoryBytes: memory.buffer.byteLength});
      scheduleDrain();
    } catch (error) {
      const current = state !== null && kernels.get(name) === state;
      if (current) kernels.delete(name);
      if (!current) return;
      failQueued(name, "kernel-load-failed", describe(error));
      post({type: "load-error", kernel: name, generation: state ? state.generation : 0,
        message: describe(error)});
    }
  }

  function failQueued(name, code, message) {
    for (let i = queue.length - 1; i >= 0; i--) {
      const job = queue[i];
      if ((job.kernel || LEGACY) !== name) continue;
      queue.splice(i, 1);
      post({type: "error", id: job.id, code, message, kernel: name});
    }
  }

  function runJob(job, state) {
    const name = job.kernel || LEGACY;
    const exports = state.instance.exports;
    const entry = exports["run_" + job.kind];
    if (typeof entry !== "function") {
      post({type: "error", id: job.id, code: "unknown-kind", message: `kernel module has no export run_${job.kind}`, kernel: name});
      return;
    }
    const started = now();
    try {
      const memory = memoryOf(exports, state);
      const input = new Uint8Array(job.payload);
      const length = input.byteLength;
      const ptr = exports.alloc(length) >>> 0;
      // A null pointer means memory.grow failed. The memory from address 0 holds the module's
      // stack, statics and allocator state, so writing the payload there would corrupt the
      // instance: treat it like a trap, which drops the instance.
      if (ptr === 0 && length > 0) {
        throw new Error(`kernel ${job.kind} is out of memory (cannot allocate a ${length} byte payload)`);
      }
      // alloc may grow memory, which replaces memory.buffer: always read it after the call.
      new Uint8Array(memory.buffer, ptr, length).set(input);
      job.payload = null;
      const header = entry(ptr, length) >>> 0;
      // A null descriptor means the kernel could not allocate its result: treat it like a trap.
      if (header === 0) throw new Error(`kernel ${job.kind} returned no result (out of memory)`);
      const view = new DataView(memory.buffer);
      const status = view.getUint32(header, true);
      const dataPtr = view.getUint32(header + 4, true);
      const dataLength = view.getUint32(header + 8, true);
      const bytes = new Uint8Array(resultBuffer(job.kind, dataLength), 0, dataLength);
      bytes.set(new Uint8Array(memory.buffer, dataPtr, dataLength));
      if (typeof exports.release === "function") exports.release(header);
      if (typeof exports.dealloc === "function") exports.dealloc(ptr, length);
      if (typeof exports.reset === "function") exports.reset();
      const execMs = now() - started;
      const memoryBytes = memory.buffer.byteLength;
      if (status === 0) {
        post({type: "result", id: job.id, result: bytes.buffer, execMs, kernel: name, memoryBytes}, [bytes.buffer]);
      } else {
        const message = decodeText(bytes) || `kernel ${job.kind} failed with status ${status}`;
        post({type: "error", id: job.id, code: "kernel-error", status, message, execMs, kernel: name});
      }
    } catch (error) {
      // A trap leaves the instance (allocator, statics) in an unknown state: drop it and
      // instantiate the same module again before the next job of this kernel.
      state.instance = null;
      post({type: "error", id: job.id, code: "kernel-trap", message: describe(error), execMs: now() - started, kernel: name});
    }
  }

  // Runs between jobs (message handlers never interleave with runJob). A trim that traps drops
  // the instance like a trapping job does.
  function trimAll() {
    kernels.forEach((state, name) => {
      if (state.instance === null) return;
      const exports = state.instance.exports;
      try {
        for (const key of Object.keys(exports)) {
          if ((key === "trim" || key.endsWith("_trim")) && typeof exports[key] === "function") exports[key]();
        }
      } catch (_) {
        state.instance = null;
        return;
      }
      const memory = memoryOf(exports, state);
      post({type: "trimmed", kernel: name, memoryBytes: memory ? memory.buffer.byteLength : 0});
    });
  }

  function scheduleDrain() {
    if (draining || queue.length === 0 || kernels.size === 0) return;
    draining = true;
    yieldTurn().then(drain);
  }

  async function drain() {
    try {
      while (queue.length > 0) {
        const job = queue[0];
        const name = job.kernel || LEGACY;
        const state = kernels.get(name);
        if (!state) {
          queue.shift();
          post({type: "error", id: job.id, code: "kernel-not-loaded", message: `kernel ${name || "(init)"} is not loaded`, kernel: name});
          continue;
        }
        if (state.instance === null) {
          try {
            await ensureInstance(state);
          } catch (error) {
            if (name === LEGACY) throw error;
            // The instance may have been replaced while instantiation was suspended. Only the
            // still-current generation may invalidate the worker and its queued jobs.
            if (kernels.get(name) !== state) continue;
            kernels.delete(name);
            failQueued(name, "kernel-load-failed", describe(error));
            post({type: "load-error", kernel: name, generation: state.generation,
              message: describe(error)});
            continue;
          }
        }
        // A cancel or an unload may have arrived while the instance was being created.
        if (queue[0] !== job) continue;
        queue.shift();
        runJob(job, state);
        if (queue.length > 0) await yieldTurn();
      }
    } catch (error) {
      // The init module no longer instantiates: let the pool replace this worker.
      post({type: "init-error", message: describe(error)});
    } finally {
      draining = false;
    }
  }

  scope.onmessage = (event) => {
    const message = event.data;
    if (!message) return;
    if (message.type === "init") {
      init(message);
    } else if (message.type === "load") {
      load(message);
    } else if (message.type === "unload") {
      const name = String(message.kernel || "");
      const current = kernels.get(name);
      const generation = Number(message.generation) || 0;
      const matches = !current || generation === 0 || current.generation === generation;
      if (matches && current) kernels.delete(name);
      if (matches) failQueued(name, "kernel-unloaded", `kernel ${name} was unloaded`);
      post({type: "unloaded", kernel: name, generation});
    } else if (message.type === "job") {
      queue.push(message);
      scheduleDrain();
    } else if (message.type === "trim") {
      trimAll();
    } else if (message.type === "recycle") {
      recycle(message.buffer);
    } else if (message.type === "cancel") {
      const index = queue.findIndex((job) => job.id === message.id);
      if (index >= 0) {
        queue.splice(index, 1);
        post({type: "cancelled", id: message.id});
      }
    }
  };
})(self);
