import assert from "node:assert/strict";
import {spawn, spawnSync} from "node:child_process";
import {createHash, randomBytes} from "node:crypto";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {fileURLToPath, pathToFileURL} from "node:url";
import {chromePlatformArgs, resolveChromeBinary} from "./chrome-binary.mjs";

// Round trip of the Wasm-only single file's embedded payloads through a real HTML parser (Chrome):
// adversarial bytes -> build-portable-html.py (encode_payload + payload_part_elements at the part
// size GAIUS_PORTABLE_PART_CHARS selects) -> the page's own payload prefix script (_b64decode,
// _verify) on file:// -> bytes, compared by length and SHA-256 with the input. The page is also
// loaded after the transformations a downloaded file can go through (CRLF line endings, a UTF-8
// BOM, a UTF-16LE re-save). Skipped when no Chrome is installed; not in CI (it starts a browser):
// node --test port/scripts/portable-payload-parts-chrome.test.mjs
const here = fileURLToPath(new URL(".", import.meta.url));
const builder = join(here, "build-portable-html.py");
const source = readFileSync(builder, "utf8");
const start = source.indexOf('prefix += r"""<script>\n');
const end = source.indexOf('</script>"""', start);
assert.ok(start > 0 && end > start, "portable payload prefix script found");
const prefixScript = source.slice(start + 'prefix += r"""<script>\n'.length, end)
  .replace("__GAIUS_PORTABLE_KERNELS__", "null");

function cases(partChars) {
  const perPart = partChars / 4 * 3;
  const out = {
    "classes.wasm": Buffer.concat([Buffer.from("\0asm"), randomBytes(5000)]),
    empty: Buffer.alloc(0),
    all256: Buffer.from(Array.from({length: 256 * 40}, (_, i) => i & 255)),
    nul: Buffer.alloc(5000),
    crlf: Buffer.from("\r\n\r\r\n\n".repeat(1500), "latin1"),
    lt: Buffer.from("<".repeat(4096), "latin1"),
    closeTag: Buffer.from("</script>".repeat(1000) + "</SCRIPT >", "latin1"),
    comment: Buffer.from("<!--<script>".repeat(500) + "-->", "latin1"),
    entities: Buffer.from("&amp;&#0;&lt;&#x0D;".repeat(400), "latin1"),
    bom: Buffer.from("\xef\xbb\xbf\xff\xfe\xfe\xff".repeat(600), "latin1"),
    surrogates: Buffer.from("\xed\xa0\x80\xed\xbf\xbf\xc0\x80".repeat(500), "latin1"),
    c1: Buffer.from(Array.from({length: 8192}, (_, i) => 0x80 + (i % 32))),
    plusSlash: Buffer.from("\xfb\xef\xbe\xff\xff\xff".repeat(2000), "latin1"),
    random: randomBytes(1 << 20),
  };
  for (const delta of [-1, 0, 1]) {
    out[`edge1${delta < 0 ? "m" : delta ? "p" : "z"}`] = randomBytes(perPart + delta);
    out[`edge2${delta < 0 ? "m" : delta ? "p" : "z"}`] = randomBytes(2 * perPart + delta);
  }
  return out;
}

function buildPage(dir, payloads, partChars) {
  for (const [name, bytes] of Object.entries(payloads)) writeFileSync(join(dir, name), bytes);
  const python = `
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("bph", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
part = m.payload_part_chars(); names = json.loads(sys.argv[3]); index = {}; elements = []
for name in names:
    data = open(sys.argv[2] + "/" + name, "rb").read()
    index[name] = {"script": name + ".payload.js", "bytes": len(data), "gzip": False, "sha256": m.sha256_bytes(data)}
    parts = m.payload_part_elements(name, m.encode_payload(data), part)
    assert all(len(p) <= part + 200 for p in parts)
    elements += parts
print(json.dumps({"part": part, "index": index, "elements": "".join(elements)}))
`;
  const run = spawnSync("python3", ["-c", python, builder, dir, JSON.stringify(Object.keys(payloads))],
    {encoding: "utf8", env: {...process.env, GAIUS_PORTABLE_PART_CHARS: String(partChars)}, maxBuffer: 1 << 28});
  assert.equal(run.status, 0, run.stderr);
  const built = JSON.parse(run.stdout);
  assert.equal(built.part, partChars);
  const check = `<script>(async () => { const out = {};
  try {
    for (const name of Object.keys(window.__gaiusFilePayloads)) {
      const bytes = await window.__gaiusPortablePayloadBytes[name];
      const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
      out[name] = {bytes: bytes.length, sha256: Array.from(digest, (x) => x.toString(16).padStart(2, "0")).join(""),
        left: document.querySelectorAll('script[data-gaius-asset="' + name + '"]').length};
    }
    // _bytes('classes.wasm') = _verify + the buffer the wasm loader gets.
    const wasm = new Uint8Array(await window.__gaiusWasmSource);
    out.__wasmSource = {bytes: wasm.length};
  } catch (e) { out.__error = String(e && e.stack || e); }
  out.__bootError = window.__gaiusPortableBootError || null; out.__charset = document.characterSet;
  window.__roundTrip = out; })();</script>`;
  return "<!doctype html><meta charset=\"utf-8\"><title>payload parts</title>\n" +
    "<script>window.__gaiusPayloadsLast=false;window.__gaiusFilePayloads=" + JSON.stringify(built.index) + ";</script>\n" +
    built.elements + "\n<script>\n" + prefixScript + "</script>\n" + check + "\n</html>\n";
}

async function cdp(url) {
  const sock = new WebSocket(url); let id = 0; const pending = new Map();
  await new Promise((ok, bad) => { sock.onopen = ok; sock.onerror = bad; });
  sock.onmessage = (event) => { const m = JSON.parse(event.data); const p = m.id && pending.get(m.id);
    if (p) { pending.delete(m.id); m.error ? p.bad(new Error(JSON.stringify(m.error))) : p.ok(m.result); } };
  return {send: (method, params = {}, sessionId) => new Promise((ok, bad) => { const i = ++id; pending.set(i, {ok, bad});
    sock.send(JSON.stringify({id: i, method, params, ...(sessionId ? {sessionId} : {})})); }), close: () => sock.close()};
}

async function loadInChrome(chrome, profile, files) {
  const proc = spawn(chrome, [...chromePlatformArgs(), "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
    "--no-first-run", "--no-default-browser-check", "--disable-background-networking", "--disable-component-update", "about:blank"],
    {stdio: ["ignore", "ignore", "pipe"]});
  try {
    let log = "";
    const ws = await new Promise((ok, bad) => { const t = setTimeout(() => bad(new Error("Chrome did not start: " + log)), 60000);
      proc.stderr.on("data", (c) => { log += c; const m = /DevTools listening on (ws:\S+)/.exec(log); if (m) { clearTimeout(t); ok(m[1]); } }); });
    const browser = await cdp(ws);
    const {targetInfos} = await browser.send("Target.getTargets");
    const {sessionId} = await browser.send("Target.attachToTarget", {targetId: targetInfos.find((t) => t.type === "page").targetId, flatten: true});
    const results = {};
    for (const [label, file] of Object.entries(files)) {
      await browser.send("Page.navigate", {url: pathToFileURL(file).href}, sessionId);
      let value = null;
      for (let i = 0; i < 600 && !value; i++) {
        await new Promise((r) => setTimeout(r, 100));
        try { value = (await browser.send("Runtime.evaluate", {expression: "window.__roundTrip ? JSON.stringify(window.__roundTrip) : ''", returnByValue: true}, sessionId)).result.value; } catch {}
      }
      results[label] = value ? JSON.parse(value) : {__error: "timeout"};
    }
    browser.close();
    return results;
  } finally { proc.kill("SIGKILL"); }
}

let chrome = null;
try { chrome = resolveChromeBinary(); } catch {}

for (const partChars of [4096, 65536]) {
  test(`payload parts of ${partChars} chars survive the HTML parser byte-exact`, {skip: chrome ? false : "Chrome is not installed", timeout: 300000}, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gaius-parts-"));
    try {
      const payloads = cases(partChars);
      const html = buildPage(dir, payloads, partChars);
      const files = {original: join(dir, "page.html"), crlf: join(dir, "crlf.html"), utf8Bom: join(dir, "bom.html"), utf16le: join(dir, "utf16.html")};
      writeFileSync(files.original, html, "utf8");
      writeFileSync(files.crlf, html.replace(/\n/g, "\r\n"), "utf8");
      writeFileSync(files.utf8Bom, Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(html, "utf8")]));
      writeFileSync(files.utf16le, Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(html, "utf16le")]));
      const results = await loadInChrome(chrome, join(dir, "profile"), files);
      for (const [label, got] of Object.entries(results)) {
        assert.equal(got.__error, undefined, `${label}: ${got.__error}`);
        assert.equal(got.__bootError, null, `${label}: ${got.__bootError}`);
        for (const [name, bytes] of Object.entries(payloads)) {
          assert.deepEqual(got[name], {bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex"), left: 0},
            `${label}: ${name}`);
        }
        assert.equal(got.__wasmSource.bytes, payloads["classes.wasm"].length, label);
      }
      assert.equal(results.utf16le.__charset, "UTF-16LE");
      assert.equal(results.original.__charset, "UTF-8");
    } finally { rmSync(dir, {recursive: true, force: true}); }
  });
}
