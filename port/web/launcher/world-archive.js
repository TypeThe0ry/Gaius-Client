// Gaius world archives: exports a browser world as a Java Edition save .zip and imports one.
// Injected by port/scripts/postprocess-index-html.py (apply_gaius_world_archive). The game drives
// it from the world selection screen (dev.gaius.browser.BrowserWorldTransfer): it starts an
// operation and then polls window.__gaiusWorldArchive.status until the state is "done",
// "error" or "cancelled".
//
// Storage layout (port/web/launcher/index.template.html installPersistentFsBootstrap and
// port/web/singleplayer/server-worker-bootstrap.js): every file of a world is an IndexedDB record
// {path: "/gaius/saves/<id>/<relative path>", value} of the profile's database, except the region
// files (.mca/.mcc) of a world opened with OPFS, which live in the world's append-only OPFS
// container gaius/<opfs directory>/<container>.regions. Export reads both (an OPFS region wins over
// a stale IndexedDB copy, like the Worker's migration); import writes IndexedDB records only, which
// the Worker migrates into OPFS the first time it opens the world.
//
// Archives are read and written one entry at a time: an import never holds more than one
// decompressed file, an export keeps only the compressed entries (as Blob parts).
(function installGaiusWorldArchive() {
  "use strict";
  const w = typeof window !== "undefined" ? window : globalThis;
  if (w.__gaiusWorldArchive) return;

  const SAVES_ROOT = "/gaius/saves/";
  const STORE_NAME = "files";
  const MAX_ENTRIES = 65535;
  const MAX_ARCHIVE_BYTES = 0xffffffff;
  const MAX_ENTRY_BYTES = 0x7fffffff;
  const MAX_PATH_LENGTH = 1024;
  // The world's own client-bootstrap files (index.template.html isClientBootstrapPath): the page
  // mirror holds them so the title client can list the world without opening its Worker.
  const BOOTSTRAP_FILES = new Set([
    "level.dat", "level.dat_old", "icon.png", "data/minecraft/world_gen_settings.dat"
  ]);
  // Files that belong to one running session, never to a saved world.
  const SKIPPED_FILES = new Set(["session.lock"]);

  // OPFS container records (server-worker-bootstrap.js scanOpfsRegionStore).
  const OPFS_V1_MAGIC = 0x47525331;
  const OPFS_V1_VERSION = 1;
  const OPFS_V1_HEADER = 24;
  const OPFS_V1_LIVE = 1;
  const OPFS_V1_DELETED = 2;
  const OPFS_V2_MAGIC = 0x47525332;
  const OPFS_V2_VERSION = 2;
  const OPFS_V2_HEADER = 48;
  const OPFS_V2_STATE = 3;
  const OPFS_V2_COMMIT = 0x434f4d54;
  const OPFS_MAX_RANGES = 64;

  const status = {
    state: "idle",       // idle | picking | running | done | error | cancelled
    kind: "",            // export | import
    message: "",
    error: "",
    done: 0,
    total: 0,
    worldId: "",
    fileName: "",
    sequence: 0
  };

  function setStatus(fields) {
    Object.assign(status, fields);
    status.sequence++;
  }

  function busy() {
    return status.state === "running" || status.state === "picking";
  }

  // --- CRC-32 -------------------------------------------------------------------------------

  let crcTable = null;
  function crc32(bytes, crc = 0) {
    if (!crcTable) {
      crcTable = new Uint32Array(256);
      for (let index = 0; index < 256; index++) {
        let value = index;
        for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
        crcTable[index] = value >>> 0;
      }
    }
    crc = (crc ^ 0xffffffff) >>> 0;
    for (let index = 0; index < bytes.length; index++) {
      crc = crcTable[(crc ^ bytes[index]) & 0xff] ^ (crc >>> 8);
    }
    return (crc ^ 0xffffffff) >>> 0;
  }

  // --- paths --------------------------------------------------------------------------------

  // A safe relative path inside a world, or null: no absolute paths, drive letters, "..",
  // empty or control-character segments.
  function safeRelativePath(name) {
    const raw = String(name || "").replace(/\\/g, "/");
    if (!raw || raw.length > MAX_PATH_LENGTH || raw.startsWith("/") || /^[A-Za-z]:/.test(raw) ||
        /[\u0000-\u001f]/.test(raw)) {
      return null;
    }
    const parts = raw.split("/");
    const kept = [];
    for (let index = 0; index < parts.length; index++) {
      const part = parts[index];
      if (part === "" && index === parts.length - 1) continue; // directory entry
      if (part === "" || part === "." || part === "..") return null;
      kept.push(part);
    }
    return kept.length ? kept.join("/") : null;
  }

  function isTraversal(name) {
    const raw = String(name || "").replace(/\\/g, "/");
    return raw.startsWith("/") || /^[A-Za-z]:/.test(raw) ||
      raw.split("/").some(part => part === "..");
  }

  // Vanilla's FileUtil.findAvailableName shape: illegal characters become "_", a taken name
  // gets " (n)".
  function sanitizeWorldId(name) {
    let value = String(name || "").replace(/\.zip$/i, "")
      .replace(/[^A-Za-z0-9 _.()+\- -￿]/g, "_")
      .replace(/[\u0000-\u001f\u007f]/g, "_")
      .trim();
    value = value.replace(/^\.+/, "_").replace(/\.+$/, "").slice(0, 64).trim();
    if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])$/i.test(value)) value = "_" + value;
    return value || "World";
  }

  function uniqueWorldId(base, taken) {
    const lower = new Set(Array.from(taken, id => String(id).toLowerCase()));
    if (!lower.has(base.toLowerCase())) return base;
    for (let counter = 1; counter < 10000; counter++) {
      const candidate = base + " (" + counter + ")";
      if (!lower.has(candidate.toLowerCase())) return candidate;
    }
    throw new Error("Could not find a free world name for " + base);
  }

  function isRegionPath(path) {
    return !String(path).includes("/.gaius-backups/") &&
      (String(path).endsWith(".mca") || String(path).endsWith(".mcc"));
  }

  // --- storage ------------------------------------------------------------------------------

  function storageConfig() {
    const databaseName = String(w.__gaiusStorageDatabaseName || "");
    const opfsDirectory = String(w.__gaiusStorageOpfsDirectory || "");
    const schema = Number(w.__gaiusStorageSchema || 2) || 2;
    if (!databaseName) throw new Error("Browser world storage is not configured");
    return {databaseName, opfsDirectory, schema};
  }

  function openDatabase() {
    const {databaseName, schema} = storageConfig();
    return new Promise((resolve, reject) => {
      if (typeof indexedDB === "undefined") {
        reject(new Error("IndexedDB is unavailable in this browser"));
        return;
      }
      const request = indexedDB.open(databaseName, schema);
      request.onupgradeneeded = () => {
        const database = request.result;
        if (!database.objectStoreNames.contains(STORE_NAME)) {
          database.createObjectStore(STORE_NAME, {keyPath: "path"});
        }
      };
      request.onsuccess = () => {
        const database = request.result;
        database.onversionchange = () => { try { database.close(); } catch (_) {} };
        resolve(database);
      };
      request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
      request.onblocked = () => reject(new Error("IndexedDB is blocked by another Gaius tab"));
    });
  }

  function transactionDone(transaction) {
    return new Promise((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error("IndexedDB transaction failed"));
      transaction.onabort = () => reject(transaction.error || new Error("IndexedDB transaction aborted"));
    });
  }

  async function listKeys(database, prefix) {
    const transaction = database.transaction(STORE_NAME, "readonly");
    const store = transaction.objectStore(STORE_NAME);
    const keys = [];
    const range = typeof IDBKeyRange !== "undefined" && prefix
      ? IDBKeyRange.bound(prefix, prefix + "￿") : undefined;
    await new Promise((resolve, reject) => {
      const request = range ? store.openKeyCursor(range) : store.openKeyCursor();
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) { resolve(); return; }
        const key = String(cursor.primaryKey || "");
        if (!prefix || key.startsWith(prefix)) keys.push(key);
        cursor.continue();
      };
      request.onerror = () => reject(request.error || new Error("IndexedDB key scan failed"));
    });
    await transactionDone(transaction);
    return keys;
  }

  async function readRecord(database, path) {
    const transaction = database.transaction(STORE_NAME, "readonly");
    const request = transaction.objectStore(STORE_NAME).get(path);
    let record = null;
    request.onsuccess = () => { record = request.result || null; };
    await transactionDone(transaction);
    return record;
  }

  async function putRecord(database, path, bytes) {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    transaction.objectStore(STORE_NAME).put({path, value: bytes, updatedAt: Date.now()});
    await transactionDone(transaction);
  }

  async function deleteRecords(database, paths) {
    if (!paths.length) return;
    const transaction = database.transaction(STORE_NAME, "readwrite");
    const store = transaction.objectStore(STORE_NAME);
    for (const path of paths) store.delete(path);
    await transactionDone(transaction);
  }

  function base64Bytes(value) {
    if (typeof Uint8Array.fromBase64 === "function") return Uint8Array.fromBase64(value);
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  async function recordBytes(value) {
    if (typeof value === "string") return base64Bytes(value);
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    if (value && value.encoding === "gzip") {
      return inflate(toBytes(value.bytes), "gzip");
    }
    return null;
  }

  function toBytes(value) {
    if (value instanceof Uint8Array) return value;
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    return null;
  }

  async function inflate(bytes, format) {
    if (typeof DecompressionStream !== "function") {
      throw new Error("This browser cannot decompress archives (DecompressionStream is unavailable)");
    }
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream(format));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  async function inflateBlob(blob) {
    if (typeof DecompressionStream !== "function") {
      throw new Error("This browser cannot decompress archives (DecompressionStream is unavailable)");
    }
    const stream = blob.stream().pipeThrough(new DecompressionStream("deflate-raw"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  async function deflateRaw(bytes) {
    if (typeof CompressionStream !== "function") return null;
    const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("deflate-raw"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  function opfsContainerName(worldId) {
    const value = String(worldId || "world");
    let hash = 0x811c9dc5;
    const bytes = new TextEncoder().encode(value);
    for (let index = 0; index < bytes.length; index++) {
      hash ^= bytes[index];
      hash = Math.imul(hash, 0x01000193);
    }
    const suffix = value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 48) || "world";
    return suffix + "-" + (hash >>> 0).toString(16).padStart(8, "0") + ".regions";
  }

  // The world's OPFS container as a File, or null when the world has none.
  async function opfsContainerFile(worldId) {
    const {opfsDirectory} = storageConfig();
    if (!opfsDirectory || typeof navigator === "undefined" || !navigator.storage ||
        typeof navigator.storage.getDirectory !== "function") {
      return null;
    }
    let handle;
    try {
      const root = await navigator.storage.getDirectory();
      const gaius = await root.getDirectoryHandle("gaius", {create: false});
      const regions = await gaius.getDirectoryHandle(opfsDirectory, {create: false});
      handle = await regions.getFileHandle(opfsContainerName(worldId), {create: false});
    } catch (error) {
      if (error && (error.name === "NotFoundError" || error.name === "TypeMismatchError")) return null;
      if (error && error.name === "SecurityError") return null; // file:// pages have no OPFS
      throw error;
    }
    try {
      return await handle.getFile();
    } catch (error) {
      throw new Error("World storage is in use by another Gaius session; close it first");
    }
  }

  async function opfsContainerExists(worldId) {
    try {
      return (await opfsContainerFile(worldId)) !== null;
    } catch (_) {
      return true;
    }
  }

  async function readSlice(file, offset, length) {
    return new Uint8Array(await file.slice(offset, offset + length).arrayBuffer());
  }

  function opfsChecksum(pathBytes, metadata, payload, logicalSize, generation) {
    const scalar = new Uint8Array(8);
    const view = new DataView(scalar.buffer);
    view.setUint32(0, logicalSize, true);
    view.setUint32(4, generation, true);
    // Chained crc32() equals the CRC of the concatenation, which updateOpfsCrc32 computes.
    let crc = crc32(pathBytes, 0);
    crc = crc32(scalar, crc);
    crc = crc32(metadata, crc);
    crc = crc32(payload, crc);
    return crc >>> 0;
  }

  function nextGeneration(previous) {
    return (Number(previous && (previous.opfsSequence ?? previous.generation)) || 0) + 1;
  }

  // Scans the container like the Worker (scanOpfsRegionStore): the committed records up to the
  // first invalid one. Returns path -> region entry.
  async function scanOpfsContainer(file, worldPrefix) {
    const index = new Map();
    const decoder = new TextDecoder();
    const size = file.size;
    let offset = 0;
    while (offset + 8 <= size) {
      const prefix = await readSlice(file, offset, 8);
      const prefixView = new DataView(prefix.buffer);
      const magic = prefixView.getUint32(0, true);
      const version = prefixView.getUint32(4, true);
      let recordLength = 0;
      if (magic === OPFS_V1_MAGIC && version === OPFS_V1_VERSION && offset + OPFS_V1_HEADER <= size) {
        const header = await readSlice(file, offset, OPFS_V1_HEADER);
        const view = new DataView(header.buffer);
        const state = view.getUint32(8, true);
        const pathLength = view.getUint32(12, true);
        const dataLength = view.getUint32(16, true);
        const length = view.getUint32(20, true);
        if ((state === OPFS_V1_LIVE || state === OPFS_V1_DELETED) && pathLength > 0 &&
            pathLength <= 4096 && length === OPFS_V1_HEADER + pathLength + dataLength &&
            offset + length <= size) {
          const path = decoder.decode(await readSlice(file, offset + OPFS_V1_HEADER, pathLength));
          if (path.startsWith(worldPrefix) && isRegionPath(path)) {
            if (state === OPFS_V1_DELETED) {
              index.delete(path);
            } else {
              const sequence = nextGeneration(index.get(path));
              const dataOffset = offset + OPFS_V1_HEADER + pathLength;
              index.set(path, {baseOffset: dataOffset, baseLength: dataLength,
                logicalSize: dataLength, generation: 0, opfsSequence: sequence, patches: []});
            }
          }
          recordLength = length;
        }
      } else if (magic === OPFS_V2_MAGIC && version === OPFS_V2_VERSION &&
          offset + OPFS_V2_HEADER <= size) {
        const header = await readSlice(file, offset, OPFS_V2_HEADER);
        const view = new DataView(header.buffer);
        const state = view.getUint32(8, true);
        const pathLength = view.getUint32(12, true);
        const logicalSize = view.getUint32(16, true);
        const rangeCount = view.getUint32(20, true);
        const metadataLength = view.getUint32(24, true);
        const payloadLength = view.getUint32(28, true);
        const length = view.getUint32(32, true);
        const generation = view.getUint32(36, true);
        const checksum = view.getUint32(40, true);
        const commit = view.getUint32(44, true);
        if (state === OPFS_V2_STATE && commit === OPFS_V2_COMMIT && pathLength > 0 &&
            pathLength <= 4096 && rangeCount <= OPFS_MAX_RANGES && metadataLength === rangeCount * 8 &&
            generation !== 0 &&
            length === OPFS_V2_HEADER + pathLength + metadataLength + payloadLength &&
            offset + length <= size) {
          const body = await readSlice(file, offset + OPFS_V2_HEADER, length - OPFS_V2_HEADER);
          const pathBytes = body.subarray(0, pathLength);
          const metadata = body.subarray(pathLength, pathLength + metadataLength);
          const payload = body.subarray(pathLength + metadataLength);
          const ranges = opfsChecksum(pathBytes, metadata, payload, logicalSize, generation) === checksum
            ? decodeRanges(metadata, logicalSize, payloadLength) : null;
          if (ranges) {
            const path = decoder.decode(pathBytes);
            let valid = true;
            if (path.startsWith(worldPrefix) && isRegionPath(path)) {
              const previous = index.get(path);
              if (generation !== nextGeneration(previous)) {
                valid = false;
              } else {
                const dataOffset = offset + OPFS_V2_HEADER + pathLength + metadataLength;
                // A whole-file record (one range covering the file) starts a new chain, like
                // appendOpfsPatch(..., wholeFile = true); applying it over the old chain is equal.
                const patches = previous ? previous.patches.slice() : [];
                patches.push({dataOffset, payloadLength, logicalSize, ranges});
                index.set(path, {
                  baseOffset: previous ? previous.baseOffset : 0,
                  baseLength: previous ? previous.baseLength : 0,
                  logicalSize, generation, opfsSequence: generation, patches
                });
              }
            }
            if (valid) recordLength = length;
          }
        }
      }
      if (recordLength <= 0) break;
      offset += recordLength;
    }
    return index;
  }

  function decodeRanges(metadata, logicalSize, payloadLength) {
    if (metadata.byteLength % 8 !== 0) return null;
    const view = new DataView(metadata.buffer, metadata.byteOffset, metadata.byteLength);
    const ranges = [];
    let previousEnd = 0;
    let payloadOffset = 0;
    for (let index = 0; index < metadata.byteLength / 8; index++) {
      const offset = view.getUint32(index * 8, true);
      const length = view.getUint32(index * 8 + 4, true);
      if (length === 0 || offset < previousEnd || offset + length > logicalSize ||
          payloadOffset + length > payloadLength) {
        return null;
      }
      ranges.push({offset, length, payloadOffset});
      previousEnd = offset + length;
      payloadOffset += length;
    }
    return payloadOffset === payloadLength ? ranges : null;
  }

  async function materializeRegion(file, entry) {
    let bytes = entry.baseLength > 0
      ? await readSlice(file, entry.baseOffset, entry.baseLength) : new Uint8Array(0);
    for (const patch of entry.patches) {
      if (bytes.byteLength !== patch.logicalSize) {
        const resized = new Uint8Array(patch.logicalSize);
        resized.set(bytes.subarray(0, Math.min(bytes.byteLength, resized.byteLength)));
        bytes = resized;
      }
      const payload = await readSlice(file, patch.dataOffset, patch.payloadLength);
      for (const range of patch.ranges) {
        bytes.set(payload.subarray(range.payloadOffset, range.payloadOffset + range.length), range.offset);
      }
    }
    if (bytes.byteLength !== entry.logicalSize) {
      const resized = new Uint8Array(entry.logicalSize);
      resized.set(bytes.subarray(0, Math.min(bytes.byteLength, resized.byteLength)));
      bytes = resized;
    }
    return bytes;
  }

  // --- zip writer ---------------------------------------------------------------------------

  function dosDateTime(date) {
    const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
    const day = ((Math.max(1980, date.getFullYear()) - 1980) << 9) |
      ((date.getMonth() + 1) << 5) | date.getDate();
    return {time, day};
  }

  function createZipWriter() {
    const parts = [];
    const central = [];
    const encoder = new TextEncoder();
    const stamp = dosDateTime(new Date());
    let offset = 0;
    let count = 0;
    return {
      // Adds one file; stored files keep their bytes, the others are deflated when smaller.
      async add(name, bytes, store) {
        if (count >= MAX_ENTRIES) throw new Error("The world has too many files to export");
        const nameBytes = encoder.encode(name);
        const crc = crc32(bytes);
        let data = bytes;
        let method = 0;
        if (!store && bytes.byteLength > 0) {
          const deflated = await deflateRaw(bytes);
          if (deflated && deflated.byteLength < bytes.byteLength) {
            data = deflated;
            method = 8;
          }
        }
        if (offset + 30 + nameBytes.byteLength + data.byteLength > MAX_ARCHIVE_BYTES) {
          throw new Error("The world is too large to export (over 4 GB)");
        }
        const local = new Uint8Array(30 + nameBytes.byteLength);
        const view = new DataView(local.buffer);
        view.setUint32(0, 0x04034b50, true);
        view.setUint16(4, 20, true);
        view.setUint16(6, 0x0800, true);
        view.setUint16(8, method, true);
        view.setUint16(10, stamp.time, true);
        view.setUint16(12, stamp.day, true);
        view.setUint32(14, crc, true);
        view.setUint32(18, data.byteLength, true);
        view.setUint32(22, bytes.byteLength, true);
        view.setUint16(26, nameBytes.byteLength, true);
        view.setUint16(28, 0, true);
        local.set(nameBytes, 30);
        parts.push(local, data);
        const header = new Uint8Array(46 + nameBytes.byteLength);
        const centralView = new DataView(header.buffer);
        centralView.setUint32(0, 0x02014b50, true);
        centralView.setUint16(4, 20, true);
        centralView.setUint16(6, 20, true);
        centralView.setUint16(8, 0x0800, true);
        centralView.setUint16(10, method, true);
        centralView.setUint16(12, stamp.time, true);
        centralView.setUint16(14, stamp.day, true);
        centralView.setUint32(16, crc, true);
        centralView.setUint32(20, data.byteLength, true);
        centralView.setUint32(24, bytes.byteLength, true);
        centralView.setUint16(28, nameBytes.byteLength, true);
        centralView.setUint32(42, offset, true);
        header.set(nameBytes, 46);
        central.push(header);
        offset += local.byteLength + data.byteLength;
        count++;
      },
      finish() {
        let centralSize = 0;
        for (const header of central) centralSize += header.byteLength;
        if (offset + centralSize + 22 > MAX_ARCHIVE_BYTES) {
          throw new Error("The world is too large to export (over 4 GB)");
        }
        const end = new Uint8Array(22);
        const view = new DataView(end.buffer);
        view.setUint32(0, 0x06054b50, true);
        view.setUint16(8, count, true);
        view.setUint16(10, count, true);
        view.setUint32(12, centralSize, true);
        view.setUint32(16, offset, true);
        return new Blob(parts.concat(central, [end]), {type: "application/zip"});
      }
    };
  }

  // --- zip reader ---------------------------------------------------------------------------

  async function readZipDirectory(file) {
    const size = file.size;
    if (size < 22) throw new Error("The file is not a zip archive");
    const tailLength = Math.min(size, 65535 + 22);
    const tail = await readSlice(file, size - tailLength, tailLength);
    const tailView = new DataView(tail.buffer);
    let end = -1;
    for (let index = tail.byteLength - 22; index >= 0; index--) {
      if (tailView.getUint32(index, true) === 0x06054b50) { end = index; break; }
    }
    if (end < 0) throw new Error("The file is not a zip archive");
    let entryCount = tailView.getUint16(end + 10, true);
    let centralSize = tailView.getUint32(end + 12, true);
    let centralOffset = tailView.getUint32(end + 16, true);
    if (end >= 20 && tailView.getUint32(end - 20, true) === 0x07064b50) {
      const zip64Offset = Number(tailView.getBigUint64(end - 20 + 8, true));
      const record = await readSlice(file, zip64Offset, 56);
      const recordView = new DataView(record.buffer);
      if (recordView.getUint32(0, true) !== 0x06064b50) throw new Error("The zip archive is damaged");
      entryCount = Number(recordView.getBigUint64(32, true));
      centralSize = Number(recordView.getBigUint64(40, true));
      centralOffset = Number(recordView.getBigUint64(48, true));
    }
    if (centralOffset + centralSize > size || entryCount > 1000000) {
      throw new Error("The zip archive is damaged");
    }
    const directory = await readSlice(file, centralOffset, centralSize);
    const view = new DataView(directory.buffer);
    const utf8 = new TextDecoder("utf-8", {fatal: false});
    const entries = [];
    let cursor = 0;
    for (let index = 0; index < entryCount; index++) {
      if (cursor + 46 > directory.byteLength || view.getUint32(cursor, true) !== 0x02014b50) {
        throw new Error("The zip archive is damaged");
      }
      const flags = view.getUint16(cursor + 8, true);
      const method = view.getUint16(cursor + 10, true);
      const crc = view.getUint32(cursor + 16, true);
      let compressedSize = view.getUint32(cursor + 20, true);
      let uncompressedSize = view.getUint32(cursor + 24, true);
      const nameLength = view.getUint16(cursor + 28, true);
      const extraLength = view.getUint16(cursor + 30, true);
      const commentLength = view.getUint16(cursor + 32, true);
      let localOffset = view.getUint32(cursor + 42, true);
      const nameBytes = directory.subarray(cursor + 46, cursor + 46 + nameLength);
      const name = utf8.decode(nameBytes);
      let extra = cursor + 46 + nameLength;
      const extraEnd = extra + extraLength;
      while (extra + 4 <= extraEnd) {
        const id = view.getUint16(extra, true);
        const length = view.getUint16(extra + 2, true);
        if (id === 0x0001) {
          let field = extra + 4;
          if (uncompressedSize === 0xffffffff) { uncompressedSize = Number(view.getBigUint64(field, true)); field += 8; }
          if (compressedSize === 0xffffffff) { compressedSize = Number(view.getBigUint64(field, true)); field += 8; }
          if (localOffset === 0xffffffff) { localOffset = Number(view.getBigUint64(field, true)); }
        }
        extra += 4 + length;
      }
      entries.push({name, flags, method, crc, compressedSize, uncompressedSize, localOffset});
      cursor = extraEnd + commentLength;
    }
    return entries;
  }

  async function readZipEntry(file, entry) {
    if (entry.flags & 0x0001) throw new Error("Encrypted zip archives are not supported");
    if (entry.method !== 0 && entry.method !== 8) {
      throw new Error("Unsupported zip compression method " + entry.method + " in " + entry.name);
    }
    if (entry.uncompressedSize > MAX_ENTRY_BYTES) throw new Error("File too large: " + entry.name);
    const local = await readSlice(file, entry.localOffset, 30);
    const view = new DataView(local.buffer);
    if (view.getUint32(0, true) !== 0x04034b50) throw new Error("The zip archive is damaged");
    const dataOffset = entry.localOffset + 30 + view.getUint16(26, true) + view.getUint16(28, true);
    if (dataOffset + entry.compressedSize > file.size) throw new Error("The zip archive is damaged");
    const blob = file.slice(dataOffset, dataOffset + entry.compressedSize);
    const bytes = entry.method === 0
      ? new Uint8Array(await blob.arrayBuffer())
      : await inflateBlob(blob);
    if (bytes.byteLength !== entry.uncompressedSize || crc32(bytes) !== entry.crc) {
      throw new Error("The zip archive is damaged (" + entry.name + ")");
    }
    return bytes;
  }

  // Picks the world inside an archive: the shallowest level.dat, at the root or in one folder.
  function planImport(entries, fileName) {
    const files = [];
    for (const entry of entries) {
      if (isTraversal(entry.name)) {
        throw new Error("The archive contains an unsafe path: " + entry.name);
      }
      const path = safeRelativePath(entry.name);
      if (path === null) {
        if (/\/$/.test(String(entry.name).replace(/\\/g, "/"))) continue;
        throw new Error("The archive contains an unsafe path: " + entry.name);
      }
      if (/\/$/.test(String(entry.name).replace(/\\/g, "/"))) continue;
      if (path.startsWith("__MACOSX/") || path.split("/").pop() === ".DS_Store") continue;
      files.push({path, entry});
    }
    const roots = files.filter(file => file.path.split("/").pop() === "level.dat")
      .map(file => file.path.slice(0, file.path.length - "level.dat".length));
    if (!roots.length) throw new Error("The archive does not contain a world (no level.dat)");
    const depth = root => root ? root.split("/").length - 1 : 0;
    const shallowest = Math.min(...roots.map(depth));
    if (shallowest > 1) throw new Error("The archive does not contain a world at its top level");
    const candidates = roots.filter(root => depth(root) === shallowest);
    if (candidates.length > 1) throw new Error("The archive contains more than one world");
    const root = candidates[0];
    const selected = [];
    for (const file of files) {
      if (!file.path.startsWith(root)) continue;
      const relative = file.path.slice(root.length);
      if (!relative || SKIPPED_FILES.has(relative) || relative.startsWith(".gaius-backups/")) continue;
      selected.push({relative, entry: file.entry});
    }
    // level.dat last: the world shows up in the list only once every other file is stored.
    selected.sort((left, right) => (left.relative === "level.dat") - (right.relative === "level.dat"));
    const folder = root ? root.slice(0, -1) : String(fileName || "").replace(/\.zip$/i, "");
    return {base: sanitizeWorldId(folder), files: selected};
  }

  async function takenWorldIds(database) {
    const ids = new Set();
    for (const key of await listKeys(database, SAVES_ROOT)) {
      const rest = key.slice(SAVES_ROOT.length);
      const slash = rest.indexOf("/");
      if (slash > 0) ids.add(rest.slice(0, slash));
    }
    const mirror = w.__gaiusPersistentFiles;
    if (mirror) {
      for (const key of Object.keys(mirror)) {
        if (!key.startsWith(SAVES_ROOT)) continue;
        const rest = key.slice(SAVES_ROOT.length);
        const slash = rest.indexOf("/");
        if (slash > 0) ids.add(rest.slice(0, slash));
      }
    }
    return ids;
  }

  // --- operations ---------------------------------------------------------------------------

  async function flushPageWrites() {
    if (typeof w.__gaiusFsFlush === "function") await w.__gaiusFsFlush();
  }

  // Builds the archive of one world: "<worldId>/<relative path>" entries.
  async function buildWorldZip(worldId, progress) {
    const worldPrefix = SAVES_ROOT + worldId + "/";
    await flushPageWrites();
    const database = await openDatabase();
    try {
      const keys = (await listKeys(database, worldPrefix))
        .filter(key => !key.includes("/.gaius-backups/"));
      const container = await opfsContainerFile(worldId);
      const regions = container ? await scanOpfsContainer(container, worldPrefix) : new Map();
      const paths = new Set(keys);
      for (const path of regions.keys()) paths.add(path);
      const ordered = Array.from(paths).sort();
      if (!ordered.some(path => path === worldPrefix + "level.dat")) {
        throw new Error("The world has no level.dat in browser storage");
      }
      const writer = createZipWriter();
      let done = 0;
      progress(0, ordered.length);
      for (const path of ordered) {
        const relative = path.slice(worldPrefix.length);
        if (!relative || SKIPPED_FILES.has(relative)) { progress(++done, ordered.length); continue; }
        let bytes;
        if (regions.has(path)) {
          bytes = await materializeRegion(container, regions.get(path));
        } else {
          const record = await readRecord(database, path);
          bytes = record ? await recordBytes(record.value) : null;
        }
        if (bytes) await writer.add(worldId + "/" + relative, bytes, isRegionPath(path));
        progress(++done, ordered.length);
      }
      return writer.finish();
    } finally {
      try { database.close(); } catch (_) {}
    }
  }

  async function importArchive(file, progress) {
    const entries = await readZipDirectory(file);
    const plan = planImport(entries, file && file.name);
    const database = await openDatabase();
    const written = [];
    try {
      const taken = await takenWorldIds(database);
      let worldId = uniqueWorldId(plan.base, taken);
      // A leftover OPFS container (a world deleted while another tab held it) must not lend its
      // regions to the imported world.
      while (await opfsContainerExists(worldId)) {
        taken.add(worldId);
        worldId = uniqueWorldId(plan.base, taken);
      }
      const worldPrefix = SAVES_ROOT + worldId + "/";
      let done = 0;
      progress(0, plan.files.length, worldId);
      for (const item of plan.files) {
        const bytes = await readZipEntry(file, item.entry);
        const path = worldPrefix + item.relative;
        await putRecord(database, path, bytes.slice());
        written.push(path);
        if (BOOTSTRAP_FILES.has(item.relative) && w.__gaiusPersistentFiles) {
          w.__gaiusPersistentFiles[path] = bytes.slice();
        }
        progress(++done, plan.files.length, worldId);
      }
      return worldId;
    } catch (error) {
      try { await deleteRecords(database, written); } catch (_) {}
      const mirror = w.__gaiusPersistentFiles;
      if (mirror) for (const path of written) delete mirror[path];
      throw error;
    } finally {
      try { database.close(); } catch (_) {}
    }
  }

  function download(blob, fileName) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    anchor.style.display = "none";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  function describe(error) {
    return String(error && error.message || error || "Unknown error");
  }

  function startExport(worldId, levelName) {
    worldId = String(worldId || "");
    if (busy() || !worldId) return false;
    const fileName = sanitizeWorldId(levelName || worldId) + ".zip";
    setStatus({state: "running", kind: "export", message: "", error: "", done: 0, total: 0,
      worldId, fileName});
    buildWorldZip(worldId, (done, total) => setStatus({done, total}))
      .then(blob => {
        download(blob, fileName);
        setStatus({state: "done"});
      }, error => setStatus({state: "error", error: describe(error)}));
    return true;
  }

  function startImportFile(file) {
    setStatus({state: "running", kind: "import", message: "", error: "", done: 0, total: 0,
      worldId: "", fileName: String(file && file.name || "")});
    return importArchive(file, (done, total, worldId) => setStatus({done, total, worldId}))
      .then(worldId => setStatus({state: "done", worldId}),
        error => setStatus({state: "error", error: describe(error)}));
  }

  function startImport() {
    if (busy()) return false;
    setStatus({state: "picking", kind: "import", message: "", error: "", done: 0, total: 0,
      worldId: "", fileName: ""});
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".zip,application/zip,application/x-zip-compressed";
    input.style.display = "none";
    let settled = false;
    input.addEventListener("change", () => {
      if (settled) return;
      settled = true;
      const file = input.files && input.files[0];
      input.remove();
      if (!file) { setStatus({state: "cancelled"}); return; }
      startImportFile(file);
    });
    input.addEventListener("cancel", () => {
      if (settled) return;
      settled = true;
      input.remove();
      setStatus({state: "cancelled"});
    });
    document.body.appendChild(input);
    input.click();
    return true;
  }

  // The picker gives no signal on browsers without the "cancel" event; the game's Cancel button
  // ends the wait instead.
  function cancelPicking() {
    if (status.state === "picking") setStatus({state: "cancelled"});
  }

  w.__gaiusWorldArchive = {
    status,
    startExport,
    startImport,
    cancelPicking,
    // Used by tests and by tools that already hold a File/Blob.
    importFile: file => busy() ? Promise.resolve(false) : startImportFile(file).then(() => true),
    buildWorldZip,
    internals: {crc32, safeRelativePath, isTraversal, sanitizeWorldId, uniqueWorldId,
      readZipDirectory, readZipEntry, planImport, createZipWriter, scanOpfsContainer,
      materializeRegion, opfsContainerName}
  };
})();
