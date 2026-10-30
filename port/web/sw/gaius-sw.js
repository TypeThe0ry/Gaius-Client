// Gaius Service Worker: serves a multi-file Gaius site (port/scripts/build-pages-site.py) with
// the headers static hosting cannot set, and keeps its content-hashed assets for warm boots.
//
// 1. Cross-origin isolation. GitHub Pages cannot send COOP/COEP, so every same-origin document
//    and worker response this worker serves gets
//      Cross-Origin-Opener-Policy: same-origin
//      Cross-Origin-Embedder-Policy: credentialless (default) | require-corp
//    (and Cross-Origin-Resource-Policy: same-origin). Every other response goes to the page as
//    the object the network or the cache returned: a same-origin subresource needs no CORP
//    under either embedder policy, and only an unchanged response keeps what Chrome ties to
//    it, the CacheStorage entry or the HTTP cache entry that V8's code cache for scripts and
//    WebAssembly modules lives with (a copy with added headers is a new, uncached body). Once a page
//    loads under this worker, crossOriginIsolated is true and the kernel runtime may use shared
//    WebAssembly memory. The mode comes from this script's own URL: gaius-sw.js?coep=
//    credentialless | require-corp | off. Cross-origin requests are never touched
//    (credentialless already lets no-cors subresources load without credentials).
// 2. Immutable cache. A file name with a content hash (name.<16-64 hex>.ext) never changes, so
//    it is served cache-first from "gaius-immutable-v1" (query strings ignored) and fetched and
//    stored once on a miss. Chrome keeps V8 code caches for scripts and wasm stored this way,
//    which is what makes a warm boot fast. The page sends {type: "gaius-sw-prune", generation,
//    keep: [urls]} after it booted; entries under this worker's scope outside the last two
//    generations are deleted (sibling profiles on the same origin share the cache and prune
//    their own scopes). Generations are ordered by when this scope first saw them, prunes run
//    one at a time, and a generation that dropped out is remembered as retired: a tab still
//    open on it can neither push it back in front of a newer one nor delete anything. Entries
//    over 4 MB are not stored while less than 1 GB of quota is left. Prune and status messages
//    are taken only from a window under this worker's scope (any same-origin page can message
//    a registered worker), and a prune must name a string generation and a bounded url list.
//    Cache names are shared by every profile (and worker version) on the origin. Immutable
//    caches are never deleted by name pattern: only caches listed in RETIRED_CACHES lose this
//    scope's entries, and such a cache is deleted once no scope has entries left in it.
//    Generation-bound v2 shell caches are the exception: activation removes older names in this
//    worker's scope namespace while leaving sibling profile namespaces alone.
// 3. Gzip-only WebAssembly modules. The site ships the big WebAssembly GC modules only as
//    <name>.wasm.<hash>.gz (build-pages-site.py) and the page asks for <name>.<hash>.wasm.
//    When the host has no such file (404), the worker fetches the .gz sibling and serves it
//    inflated as application/wasm while it stores the same bytes under the .wasm URL; later
//    boots get that CacheStorage entry, for which Chrome keeps the V8 code cache next to the
//    entry. Pruning keeps such an entry while its .gz is kept.
// 4. Everything else same-origin is network-first; navigations and small files fall back to
//    generation-bound shell cache when the network fails, so a cached site still starts offline.
// 5. A CacheStorage failure never fails a request: it is fetched from the network instead.
// 6. gaius-relay.html is left to the network untouched: the launcher's reload relay depends on
//    that page arriving without Cross-Origin-Opener-Policy.
//
// file:// pages have no Service Worker; the portable single-file build never registers one.
// The policy functions are exposed as self.GaiusServiceWorker for the node smoke.
(function (scope) {
  "use strict";

  const SW_VERSION = 3;
  const IMMUTABLE_CACHE = "gaius-immutable-v1";
  const SHELL_CACHE_PREFIX = "gaius-shell-v2-";
  const META_CACHE = "gaius-shell-meta-v1";
  // Cache names an earlier worker version used and this one replaced. A version that renames
  // IMMUTABLE_CACHE or the shell cache prefix lists the old names here.
  const RETIRED_CACHES = ["gaius-shell-v1"];
  const META_PATH = "__gaius-sw/generations.json";
  const RELOAD_RELAY_PAGE = /(?:^|\/)gaius-relay\.html$/;
  const HASHED_NAME = /\.[0-9a-f]{16,64}\.[A-Za-z0-9]+$/;
  // <name>.<hash>.wasm, whose gzip sibling is <name>.wasm.<hash>.gz.
  const HASHED_WASM = /^(.*\/)?([^/]+)\.([0-9a-f]{16,64})\.wasm$/;
  // An inflated module is about four times its .gz (the quota check needs a size up front).
  const INFLATE_ESTIMATE = 4;
  const COEP_MODES = ["credentialless", "require-corp", "off"];
  const SHELL_MAX_BYTES = 4 * 1024 * 1024;
  const NULL_BODY_STATUS = [101, 103, 204, 205, 304];
  const GENERATIONS_KEPT = 2;
  const RETIRED_GENERATIONS_REMEMBERED = 16;
  // Bounds of a prune message (a site lists a few hundred hashed assets).
  const MAX_GENERATION_ID = 128;
  const MAX_KEPT_URLS = 4096;
  const MAX_KEPT_URL_LENGTH = 2048;
  const LARGE_ENTRY_BYTES = 4 * 1024 * 1024;
  const QUOTA_RESERVE_BYTES = 1024 * 1024 * 1024;

  function configFromUrl(href) {
    let coep = "credentialless";
    let generation = null;
    try {
      const url = new URL(href);
      const value = url.searchParams.get("coep");
      if (COEP_MODES.indexOf(value) >= 0) coep = value;
      const supplied = url.searchParams.get("generation");
      if (supplied && /^[A-Za-z0-9_-]{1,128}$/.test(supplied)) generation = supplied;
    } catch (_) {
      // keep the fail-closed default
    }
    return {coep, generation};
  }

  // FNV-1a over the exact scope path. The readable part alone is lossy (/Gaius/26.3/ and /Gaius_26_3/
  // sanitise alike, long paths are cut), and generation cleanup deletes by prefix, so the hash leads
  // the namespace: one scope's prefix is never a prefix of another scope's.
  function scopeHash(text) {
    let hash = 0x811c9dc5;
    for (let i = 0; i < text.length; i++) {
      hash ^= text.charCodeAt(i);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    return hash.toString(16).padStart(8, "0");
  }

  function shellScopePrefix(scopeUrl) {
    let scopePath = "";
    let scopeKey = "scope";
    try {
      scopePath = new URL(scopeUrl).pathname;
      scopeKey = scopePath.replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 64) || scopeKey;
    } catch (_) {
      // use the bounded default for a malformed or test scope
    }
    return SHELL_CACHE_PREFIX + scopeHash(scopePath) + "-" + scopeKey + "-";
  }

  function shellCacheName(generation, scopeUrl) {
    if (typeof generation !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(generation)) return null;
    return shellScopePrefix(scopeUrl) + generation;
  }

  function stripSearch(href) {
    const url = new URL(href);
    url.search = "";
    url.hash = "";
    return url.href;
  }

  function isImmutable(url) {
    return HASHED_NAME.test(url.pathname);
  }

  // The .gz sibling of a hashed .wasm URL (without query), or null for any other URL.
  function gzipSibling(href) {
    let url;
    try {
      url = new URL(href);
    } catch (_) {
      return null;
    }
    const match = HASHED_WASM.exec(url.pathname);
    if (!match) return null;
    url.pathname = (match[1] || "") + match[2] + ".wasm." + match[3] + ".gz";
    url.search = "";
    url.hash = "";
    return url.href;
  }

  // Documents and workers carry the embedder policy; everything else is passed through.
  function isPolicyContainer(request) {
    if (request.mode === "navigate") return true;
    const destination = request.destination || "";
    return destination === "document" || destination === "iframe" || destination === "worker"
      || destination === "sharedworker" || destination === "serviceworker";
  }

  function withIsolationHeaders(response, request, config) {
    if (!response || config.coep === "off" || !isPolicyContainer(request)) return response;
    if (response.type === "opaque" || response.type === "opaqueredirect" || response.type === "error"
        || response.status === 0) {
      return response;
    }
    const headers = new Headers(response.headers);
    if (isPolicyContainer(request)) {
      headers.set("Cross-Origin-Embedder-Policy", config.coep);
      headers.set("Cross-Origin-Opener-Policy", "same-origin");
    }
    if (!headers.has("Cross-Origin-Resource-Policy")) headers.set("Cross-Origin-Resource-Policy", "same-origin");
    const body = NULL_BODY_STATUS.indexOf(response.status) >= 0 ? null : response.body;
    return new Response(body, {status: response.status, statusText: response.statusText, headers});
  }

  // passthrough: not ours (the browser fetches it normally)
  // navigate:    network-first, shell fallback, isolation headers
  // immutable:   cache-first, store on miss
  // network:     network-first, small responses kept in the shell cache as an offline fallback
  // range:       network only (partial responses are never cached)
  function strategyFor(request, scopeOrigin) {
    if (request.method !== "GET") return "passthrough";
    let url;
    try {
      url = new URL(request.url);
    } catch (_) {
      return "passthrough";
    }
    if (url.origin !== scopeOrigin) return "passthrough";
    if (request.cache === "only-if-cached" && request.mode !== "same-origin") return "passthrough";
    if (request.headers && request.headers.get && request.headers.get("range")) return "range";
    // The reload relay must reach the page without the opener policy this worker adds to
    // navigations (index.html data-gaius-reload-relay): it is the hop that changes process.
    if (RELOAD_RELAY_PAGE.test(url.pathname)) return "passthrough";
    if (request.mode === "navigate") return "navigate";
    if (isImmutable(url)) return "immutable";
    return "network";
  }

  function cacheable(response) {
    return !!response && response.status === 200 && (response.type === "basic" || response.type === "default");
  }

  // A host that answers a missing path with its HTML page and status 200 (an SPA rewrite) must
  // not get that page stored under a content-hashed script or module name: the immutable cache
  // would serve it on every later boot. Such a response counts as a missing file.
  function htmlStandIn(url, response) {
    if (!response || !response.headers || /\.html?$/i.test(url.pathname)) return false;
    const type = String(response.headers.get("Content-Type") || "").toLowerCase();
    return type.indexOf("text/html") === 0;
  }

  function smallEnough(response) {
    const length = Number(response.headers.get("Content-Length"));
    return Number.isFinite(length) && length > 0 && length <= SHELL_MAX_BYTES;
  }

  function createWorker(deps) {
    const caches = deps.caches;
    const fetchFn = deps.fetch;
    const config = deps.config;
    const scopeOrigin = deps.scopeOrigin;
    const scopeUrl = deps.scopeUrl;
    const estimate = deps.estimate;
    const Inflate = deps.DecompressionStream;
    const Stream = deps.ReadableStream || scope.ReadableStream
      || (typeof ReadableStream === "function" ? ReadableStream : null);
    const RequestCtor = deps.Request;
    const retiredCaches = Array.isArray(deps.retiredCaches) ? deps.retiredCaches : RETIRED_CACHES;
    const shellCache = shellCacheName(config && config.generation, scopeUrl);
    const CACHE_OP_TIMEOUT_MS = 3000;
    const timer = typeof deps.setTimeout === "function" ? deps.setTimeout
      : (typeof scope.setTimeout === "function" ? scope.setTimeout
        : (typeof setTimeout === "function" ? setTimeout : null));
    const bounded = (promise, fallback = null) => {
      const operation = Promise.resolve(promise);
      if (!timer) return operation.catch(() => fallback);
      return Promise.race([
        operation.catch(() => fallback),
        new Promise(resolve => timer(() => resolve(fallback), CACHE_OP_TIMEOUT_MS)),
      ]);
    };
    const openCache = (name) => bounded(caches.open(name), null);
    const cacheKeys = (cache) => bounded(cache.keys(), []);
    const cacheMatch = (cache, request, options) => bounded(cache.match(request, options), null);
    const cacheDelete = (cache, request) => bounded(cache.delete(request), false);
    const putQueues = new WeakMap();
    // A document and a prewarm path can request the same inflated module together. Keep one
    // cache-fill promise so the later request reuses the stored bytes instead of inflating twice.
    const inflightInflates = new Map();
    async function putAtomic(cache, url, response) {
      let queue = putQueues.get(cache);
      if (!queue) { queue = new Map(); putQueues.set(cache, queue); }
      const previous = queue.get(url) || Promise.resolve();
      let release;
      const current = new Promise(resolve => { release = resolve; });
      queue.set(url, current);
      await previous.catch(() => {});
      const temp = url + (url.indexOf("?") >= 0 ? "&" : "?") + "gaius-temp=" + Math.random().toString(36).slice(2);
      try {
        const staged = await bounded(Promise.resolve(cache.put(temp, response.clone())).then(() => true), false);
        if (!staged) { await cacheDelete(cache, temp); return false; }
        const check = await cacheMatch(cache, temp);
        if (!check) { await cacheDelete(cache, temp); return false; }
        const committed = await bounded(Promise.resolve(cache.put(url, check.clone())).then(() => true), false);
        await cacheDelete(cache, temp);
        return !!committed;
      } finally {
        release();
        if (queue.get(url) === current) queue.delete(url);
      }
    }
    // Sibling profiles (<repo>/26.2/, <repo>/26.3/, ...) register their own workers on this
    // origin and share its CacheStorage; this worker only manages entries under its scope.
    const scopePrefix = scopeUrl ? stripSearch(scopeUrl) : "";
    const inScope = (request) => stripSearch(typeof request === "string" ? request : request.url).indexOf(scopePrefix) === 0;
    const waitUntil = (event, promise) => {
      if (event && typeof event.waitUntil === "function") event.waitUntil(promise.catch(() => {}));
      else promise.catch(() => {});
    };

    // Peek at only the first two bytes and replay the consumed chunks. A proxy may strip
    // Content-Encoding after decoding a .gz response; magic bytes decide whether to inflate.
    function sniffGzipStream(body) {
      if (!body || typeof body.getReader !== "function" || typeof Stream !== "function") {
        return Promise.resolve({stream: body, gzip: null});
      }
      const reader = body.getReader();
      const chunks = [];
      const magic = [];
      function finish(done) {
        let queue = chunks.slice();
        let reading = false;
        const stream = new Stream({
          pull(controller) {
            if (reading) return;
            while (queue.length && controller.desiredSize > 0) controller.enqueue(queue.shift());
            if (queue.length) return;
            if (done) { controller.close(); return; }
            reading = true;
            return reader.read().then((part) => {
              reading = false;
              if (part.done) { done = true; controller.close(); }
              else controller.enqueue(part.value);
            }, (error) => { reading = false; controller.error(error); });
          },
          cancel(reason) { return reader.cancel(reason); },
        });
        return {stream, gzip: magic.length >= 2 && magic[0] === 0x1f && magic[1] === 0x8b};
      }
      function read() {
        return reader.read().then((part) => {
          if (part.done) return finish(true);
          const chunk = part.value instanceof Uint8Array ? part.value : new Uint8Array(part.value);
          chunks.push(chunk);
          for (let i = 0; i < chunk.length && magic.length < 2; i++) magic.push(chunk[i]);
          return magic.length >= 2 ? finish(false) : read();
        });
      }
      return read();
    }

    // Large entries are not stored while the origin is short of quota: world saves (IndexedDB,
    // OPFS) share it, and a cache miss only costs a download.
    async function roomForBytes(length) {
      if (!(length > LARGE_ENTRY_BYTES) || typeof estimate !== "function") return true;
      try {
        const quota = await estimate();
        if (!quota || !(quota.quota > 0)) return true;
        return quota.quota - (quota.usage || 0) - length >= QUOTA_RESERVE_BYTES;
      } catch (_) {
        return true;
      }
    }

    function roomFor(response) {
      return roomForBytes(Number(response.headers.get("Content-Length")));
    }

    // A gzip-only module: fetch <name>.wasm.<hash>.gz and stream the inflated body straight to
    // compileStreaming. A tee branch is stored under the .wasm URL, so the first warm boot can
    // reuse the browser's streamed Wasm code cache without materialising the module in JS memory.
    // Without room for the entry the module is only served. null: no usable .gz.
    async function inflated(event, request, cache) {
      const source = gzipSibling(request.url);
      if (!source || typeof Inflate !== "function") return null;
      const key = stripSearch(request.url);
      const previous = inflightInflates.get(key);
      if (previous) {
        const stored = await previous.catch(() => false);
        if (stored) {
          const hit = await cacheMatch(cache, key);
          if (hit) return {response: hit, source: "inflated-inflight-cache"};
        }
      }
      let finishInflight;
      const inflight = new Promise(resolve => { finishInflight = resolve; });
      inflightInflates.set(key, inflight);
      const clearInflight = () => {
        if (inflightInflates.get(key) === inflight) inflightInflates.delete(key);
      };
      try {
        const gzip = await fetchFn(typeof RequestCtor === "function" ? new RequestCtor(source) : source);
        if (!cacheable(gzip) || !gzip.body || htmlStandIn(new URL(source), gzip)) {
          finishInflight(false); clearInflight(); return null;
        }
        // Keep validators stable across SW restarts. V8's streaming Wasm cache keys
        // include the response identity; the content-hashed request URL is the
        // immutable identity for a module, so derive a deterministic ETag from it.
        const validator = '"gaius-wasm-' + encodeURIComponent(stripSearch(request.url)) + '"';
        const module = (body) => new Response(body, {
          status: 200, statusText: "OK", headers: {
            "Content-Type": "application/wasm", "Cache-Control": "public, max-age=31536000, immutable",
            "ETag": validator, "Last-Modified": "Thu, 01 Jan 1970 00:00:00 GMT",
          },
        });
        const sniffed = await sniffGzipStream(gzip.body);
        // The build host serves the sibling as application/gzip. If an intermediary already
        // decoded it, magic bytes make the body pass through unchanged.
        const isGzip = sniffed.gzip === null
          ? /\.gz(?:[?#]|$)/i.test(source) && !(gzip.headers.get("Content-Encoding") || "").trim()
          : sniffed.gzip;
        const decoded = isGzip ? sniffed.stream.pipeThrough(new Inflate("gzip")) : sniffed.stream;
        const branches = typeof decoded.tee === "function" ? decoded.tee() : [decoded, null];
        const pageBody = branches[0];
        if (!(await roomForBytes(Number(gzip.headers.get("Content-Length")) * INFLATE_ESTIMATE))) {
          finishInflight(false); clearInflight();
          return {response: module(pageBody), source: "inflated-uncached"};
        }
        // A failed store (quota, a damaged .gz) only means the next boot inflates again.
        if (branches[1]) {
          const stored = putAtomic(cache, key, module(branches[1]));
          stored.then(value => finishInflight(!!value), () => finishInflight(false));
          waitUntil(event, stored.then(() => true, () => false).finally(clearInflight));
        } else {
          finishInflight(false); clearInflight();
        }
        return {response: module(pageBody), source: isGzip ? "inflated" : "decoded"};
      } catch (error) {
        finishInflight(false); clearInflight();
        throw error;
      }
    }

    async function immutable(event, request) {
      const cache = await openCache(IMMUTABLE_CACHE);
      if (!cache) return {response: await fetchFn(request), source: "network-cache-timeout"};
      const hit = await cacheMatch(cache, request, {ignoreSearch: true});
      if (hit) return {response: hit, source: "cache"};
      const response = await fetchFn(request);
      const missing = response && (response.status === 404 || htmlStandIn(new URL(request.url), response));
      if (cacheable(response) && !missing) {
        const copy = response.clone();
        waitUntil(event, roomFor(copy).then((room) => room ? putAtomic(cache, stripSearch(request.url), copy) : null));
      } else if (missing) {
        const module = await inflated(event, request, cache);
        if (module) return module;
      }
      return {response, source: "network"};
    }

    async function networkFirst(event, request, keepAlways) {
      try {
        const response = await fetchFn(request);
        if (shellCache && cacheable(response) && (keepAlways || smallEnough(response))) {
          const copy = response.clone();
          waitUntil(event, openCache(shellCache).then((cache) => cache ? putAtomic(cache, stripSearch(request.url), copy) : null));
        }
        return {response, source: "network"};
      } catch (error) {
        if (!shellCache) throw error;
        const cache = await openCache(shellCache);
        if (!cache) throw error;
        const hit = await cacheMatch(cache, request, {ignoreSearch: true});
        if (hit) return {response: hit, source: "shell"};
        throw error;
      }
    }

    // Returns {response, source, strategy} or null for requests the worker leaves alone.
    async function handle(event) {
      const request = event.request;
      const strategy = strategyFor(request, scopeOrigin);
      if (strategy === "passthrough") return null;
      let result;
      if (strategy === "immutable") result = await immutable(event, request);
      else if (strategy === "navigate") result = await networkFirst(event, request, true);
      else if (strategy === "range") result = {response: await fetchFn(request), source: "network"};
      else result = await networkFirst(event, request, false);
      return {
        response: withIsolationHeaders(result.response, request, config),
        source: result.source,
        strategy,
      };
    }

    async function readMeta() {
      const cache = await openCache(META_CACHE);
      if (!cache) return {generations: [], retired: []};
      const hit = await cacheMatch(cache, new URL(META_PATH, scopeUrl).href);
      const meta = {generations: [], retired: []};
      if (!hit) return meta;
      try {
        const stored = await hit.json();
        if (stored && Array.isArray(stored.generations)) {
          meta.generations = stored.generations.filter((entry) => entry && typeof entry.id === "string"
            && Array.isArray(entry.keep));
        }
        if (stored && Array.isArray(stored.retired)) {
          meta.retired = stored.retired.filter((id) => typeof id === "string");
        }
      } catch (_) {
        // an unreadable record starts over
      }
      return meta;
    }

    async function writeMeta(meta) {
      const cache = await openCache(META_CACHE);
      if (!cache) return;
      await putAtomic(cache, new URL(META_PATH, scopeUrl).href, new Response(JSON.stringify(meta), {
        headers: {"Content-Type": "application/json"},
      }));
    }

    // Keeps the hashed assets of the last GENERATIONS_KEPT site generations (a tab still running
    // the previous release keeps working) and deletes every other immutable entry. Prunes run
    // one at a time: two tabs pruning at once would otherwise each write a record without the
    // other's generation and delete the other's files.
    let pruneQueue = Promise.resolve();
    function prune(generation, keep) {
      const run = pruneQueue.then(() => pruneNow(generation, keep));
      pruneQueue = run.catch(() => {});
      return run;
    }

    async function pruneNow(generation, keep) {
      const id = String(generation || "");
      const urls = (Array.isArray(keep) ? keep : []).map((href) => stripSearch(new URL(href, scopeUrl).href));
      const meta = await readMeta();
      const known = meta.generations.find((entry) => entry.id === id);
      const union = new Set();
      const keptUnion = () => {
        for (const entry of meta.generations) for (const href of entry.keep) union.add(href);
      };
      if (!known && meta.retired.indexOf(id) >= 0) {
        // A tab still open on a generation that newer ones replaced. Its files are no longer
        // kept, and its late prune must neither evict a newer generation nor delete anything.
        keptUnion();
        return {deleted: 0, kept: union.size, generations: meta.generations.length, retired: true};
      }
      // A generation keeps the place it got when this scope first saw it.
      if (known) known.keep = urls;
      else meta.generations.push({id, keep: urls});
      while (meta.generations.length > GENERATIONS_KEPT) {
        const gone = meta.generations.shift();
        if (meta.retired.indexOf(gone.id) < 0) meta.retired.push(gone.id);
      }
      while (meta.retired.length > RETIRED_GENERATIONS_REMEMBERED) meta.retired.shift();
      keptUnion();
      const cache = await openCache(IMMUTABLE_CACHE);
      if (!cache) return {deleted: 0, kept: union.size, generations: meta.generations.length};
      let deleted = 0;
      for (const request of await cacheKeys(cache)) {
        const href = stripSearch(typeof request === "string" ? request : request.url);
        // Another profile's entries belong to that profile's worker and generations.
        if (!inScope(request)) continue;
        // An inflated module lives as long as the .gz it came from.
        const gzip = gzipSibling(href);
        if (!union.has(href) && !(gzip && union.has(gzip))) {
          await cacheDelete(cache, request);
          deleted++;
        }
      }
      await writeMeta(meta);
      return {deleted, kept: union.size, generations: meta.generations.length};
    }

    async function status() {
      const cache = await openCache(IMMUTABLE_CACHE);
      if (!cache) return {type: "gaius-sw-status", version: SW_VERSION, coep: config.coep, immutableEntries: 0};
      const keys = (await cacheKeys(cache)).filter(inScope);
      return {type: "gaius-sw-status", version: SW_VERSION, coep: config.coep, immutableEntries: keys.length};
    }

    // Every profile (and every worker version) on the origin shares CacheStorage. Retired caches
    // lose this scope's entries and go once empty; v2 shell names are scoped by profile and are
    // pruned by generation during activation.
    async function cleanupOldCaches() {
      const names = await bounded(caches.keys(), []);
      const currentScopePrefix = shellScopePrefix(scopeUrl);
      // v2 shell caches are generation-bound. Once this worker activates, only its current
      // generation is useful for this scope; sibling profile scopes have different prefixes.
      for (const name of names) {
        if (name.indexOf(currentScopePrefix) !== 0 || name === shellCache) continue;
        await bounded(caches.delete(name), false);
      }
      for (const name of retiredCaches) {
        if (names.indexOf(name) < 0) continue;
        const cache = await openCache(name);
        if (!cache) continue;
        for (const request of await cacheKeys(cache)) {
          if (inScope(request)) await cacheDelete(cache, request);
        }
        if ((await cacheKeys(cache)).length === 0) await bounded(caches.delete(name), false);
      }
    }

    async function primeShell() {
      if (!shellCache) return false;
      const requestUrl = scopeUrl;
      try {
        const response = await fetchFn(requestUrl);
        if (!cacheable(response)) return false;
        const cache = await openCache(shellCache);
        if (!cache) return false;
        await putAtomic(cache, stripSearch(requestUrl), response);
        return true;
      } catch (_) {
        // An install without a verified generation-bound shell must not activate.
        return false;
      }
    }

    // A message is taken only from a window under this worker's own scope. Any same-origin page
    // can reach this worker through navigator.serviceWorker.getRegistration(<scope>): another
    // site on a shared origin (<user>.github.io) or a sibling profile must not prune this
    // profile's cache or read its status.
    function trustedSource(event) {
      const source = event && event.source;
      if (!source || source.type !== "window" || typeof source.url !== "string") return false;
      if (typeof event.origin === "string" && event.origin !== "" && event.origin !== scopeOrigin) return false;
      try {
        const url = new URL(source.url);
        return url.origin === scopeOrigin && stripSearch(url.href).indexOf(scopePrefix) === 0;
      } catch (_) {
        return false;
      }
    }

    function validPrune(message) {
      return typeof message.generation === "string" && message.generation.length <= MAX_GENERATION_ID
        && Array.isArray(message.keep) && message.keep.length <= MAX_KEPT_URLS
        && message.keep.every((href) => typeof href === "string" && href.length <= MAX_KEPT_URL_LENGTH);
    }

    // Returns the work for event.waitUntil, or null when the message is not taken.
    function message(event) {
      const data = event && event.data;
      if (!data || typeof data !== "object" || !trustedSource(event)) return null;
      const reply = (value) => {
        const port = event.ports && event.ports[0];
        if (port) port.postMessage(value);
        else if (typeof event.source.postMessage === "function") event.source.postMessage(value);
      };
      if (data.type === "gaius-sw-prune" && validPrune(data)) {
        return prune(data.generation, data.keep).then((result) => reply(Object.assign({type: "gaius-sw-pruned"}, result)));
      }
      if (data.type === "gaius-sw-status") return status().then(reply);
      return null;
    }

    return {handle, primeShell, prune, status, cleanupOldCaches, message, config, shellCache};
  }

  const api = {
    SW_VERSION, IMMUTABLE_CACHE, SHELL_CACHE_PREFIX, META_CACHE, shellCacheName, HASHED_NAME,
    configFromUrl, isImmutable, strategyFor, withIsolationHeaders, createWorker, stripSearch, gzipSibling,
  };
  scope.GaiusServiceWorker = api;

  // Install the handlers only inside a real (or simulated) Service Worker global scope.
  if (typeof scope.addEventListener !== "function" || !scope.caches || !scope.registration) return;

  const scopeUrl = scope.registration.scope;
  const worker = createWorker({
    caches: scope.caches,
    fetch: (request) => scope.fetch(request),
    config: configFromUrl(scope.location.href),
    scopeOrigin: new URL(scopeUrl).origin,
    scopeUrl,
    estimate: scope.navigator && scope.navigator.storage && typeof scope.navigator.storage.estimate === "function"
      ? () => scope.navigator.storage.estimate() : null,
    DecompressionStream: scope.DecompressionStream,
    Request: scope.Request,
  });
  api.worker = worker;

  scope.addEventListener("install", (event) => {
    event.waitUntil(worker.primeShell().then((primed) => {
      if (!primed) throw new Error("Gaius shell could not be primed");
      return scope.skipWaiting();
    }));
  });

  scope.addEventListener("activate", (event) => {
    event.waitUntil(worker.cleanupOldCaches().then(() => scope.clients.claim()));
  });

  scope.addEventListener("fetch", (event) => {
    if (strategyFor(event.request, new URL(scopeUrl).origin) === "passthrough") return;
    event.respondWith(worker.handle(event)
      .then((result) => result ? result.response : scope.fetch(event.request))
      // A CacheStorage failure (quota, a damaged store, storage blocked by policy) never fails
      // the request: it goes to the network as if this worker kept no cache.
      .catch(() => scope.fetch(event.request)
        .then((response) => withIsolationHeaders(response, event.request, worker.config))));
  });

  scope.addEventListener("message", (event) => {
    const work = worker.message(event);
    if (work) event.waitUntil(work.catch(() => {}));
  });
})(self);
