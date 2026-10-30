#!/usr/bin/env python3
"""Package or plan the content-hashed Pages release layout offline.

Single-profile packaging:
    build-pages-site.py <wasm-dist> <output> --wasm-dist <dist>
                        --wasm-gzip-only --no-js-fallback [--archive <tar.gz>] [--dry-run]

Profile-driven matrix planning and packaging:
    build-pages-site.py --matrix <output> --artifact-root <project output>...
                        [--versions-dir <profiles>] [--dry-run --project-missing]
                        [--previous-site <site>...] [--release-tag <tag>] [--json <report>]
    build-pages-site.py --write-matrix <manifest> [--versions-dir <profiles>]

Verified release-asset assembly, shared with the Pages workflow:
    build-pages-site.py --publish-release <assets> <output> --tag <tag>
                        [--previous-release <assets>] [--json <report>]
    build-pages-site.py --check-total <site>... [--extra-file <metadata>...]
                        [--limit-bytes <lower limit>] [--json <report>]

Enabled profiles come from port/versions/*.json. Sites use WasmGC with
feature-detected WebGPU/WebGL2. Gzip-only client/server modules use virtual immutable wasm URLs;
the Service Worker decompresses and caches application/wasm responses for
compileStreaming, returning cached immutable responses unchanged. Without SW
control, the page and Worker use their inflation path. The JavaScript build may
run internally for resource generation, but release sites do not emit its
client or server modules. The hard published-tree size limit is 900,000,000 bytes. Dry runs
write no artifacts; only an explicitly requested report is saved. Projections
are labelled size evidence and never release acceptance.
"""

from __future__ import annotations

import argparse
import gzip
import re
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import struct
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSIONS_DIR = ROOT / "port" / "versions"


def normalized_release_version() -> str:
    version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    return re.sub(r"-pre\.(\d+)(?=$|[+])", r"-pre\1", version)
WEB = ROOT / "port" / "web"
BOOT_SCRIPT = WEB / "boot" / "gaius-boot.js"
SERVICE_WORKER = WEB / "sw" / "gaius-sw.js"
# A reload hops through this page (no opener policy) so the launcher returns in a new renderer
# process; RELOAD_RELAY_SCRIPT is inlined at the top of index.html to start the hop.
RELOAD_RELAY_PAGE = WEB / "launcher" / "gaius-relay.html"
RELOAD_RELAY_SCRIPT = WEB / "launcher" / "reload-relay.js"
SITE_SCHEMA = 1
HASH_LENGTH = 16
VANILLA_MAGIC = b"GAIUSVP1"
BOOT_TAG = '  <script data-gaius-boot="v1" src="gaius-boot.js"></script>\n'
PAGES_HEADERS = b"/*\n  Referrer-Policy: no-referrer\n"
# postprocess-index-html.py runtime_config_script
RUNTIME_CONFIG_BLOCK = re.compile(r'  <script data-gaius-runtimes="v1">\n.*?  </script>\n', re.DOTALL)
SHADER_TOOLCHAIN_TAG = re.compile(
    r'  <script data-gaius-shader-toolchain data-profile="([^"]*)" '
    r'src="gaius-shader-toolchain\.js\?v=([0-9a-f]+)"></script>\n'
)
WEBGPU_SHADER_TOOLCHAIN_CONFIG = re.compile(
    r'window\.__gaiusWebGpuShaderToolchain\s*=\s*(\{[^;\n]+\});'
)
SHADER_TOOLCHAIN_MODULES = (
    ("shadercJs", "gaius-shaderc.js"),
    ("shadercWasm", "gaius-shaderc.wasm"),
    ("spvcJs", "gaius-spvc.js"),
    ("spvcWasm", "gaius-spvc.wasm"),
)
QUALITY_MODULES = (
    "gpu-caps.js", "quality-profile.js", "gl-pass.js", "upscaler.js", "post-chain.js",
    "quality-runtime.js",
)
# gaius-boot.js WEBGPU_SCRIPTS and WEBGPU_TRANSLATOR, under runtime/webgpu/ of the dist.
WEBGPU_MODULES = ("gpu-glue.js", "shaderxlat-job.js", "quality-post.js")
WEBGPU_TRANSLATOR = "gaius-shaderxlat.wasm"
# Small interface sounds stay in the core pack: the title screen clicks before the sound pack
# has arrived on a slow line.
CORE_SOUND_PREFIXES = ("assets/minecraft/sounds/random/", "assets/minecraft/sounds/ui/")
ARCHIVE_MTIME = 315532800  # 1980-01-01, the zip epoch: stable and accepted everywhere
# The loader scripts of the WebAssembly GC runtime, staged from this tree.
WASM_RUNTIME_SCRIPTS = ("gaius-wasm-boot.js", "gaius-jspi.js", "gaius-wasm-worker.js")
# The WebAssembly GC modules of a wasm dist (client, singleplayer server).
WASM_MODULES = ("classes.wasm", "singleplayer-server.wasm")
# A boot script that loads gzip-only modules (window.__gaiusSite.inflate) says so with this token.
BOOT_INFLATE_MARKER = "gaius-site-inflate-v1"
# The size gate of --check-total: GitHub Pages allows 1 GB; the rest is headroom.
DEFAULT_LIMIT_BYTES = 900_000_000
MAX_RAW_WASM_BYTES = 4 * 1024 * 1024
# pages.yml keeps the content-hashed files of the release deployed before, except the client
# scripts and modules and the asset packs (vanilla-assets.pack.<hash>.gz, vanilla-sounds.<hash>.pack).
KEPT_NAME = re.compile(r"\.[0-9a-f]{16}\.[A-Za-z0-9]+$")
NOT_KEPT_PREFIXES = ("classes.", "vanilla-assets.pack.", "vanilla-sounds.")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def hashed_name(logical: str, data: bytes) -> str:
    """classes.js -> classes.<hash>.js; vanilla-assets.pack.gz -> vanilla-assets.pack.<hash>.gz."""
    path = Path(logical)
    digest = sha256_bytes(data)[:HASH_LENGTH]
    stem, dot, extension = path.name.rpartition(".")
    name = f"{stem}.{digest}.{extension}" if dot else f"{path.name}.{digest}"
    return (path.parent / name).as_posix() if path.parent != Path(".") else name


def deterministic_gzip(data: bytes) -> bytes:
    return gzip.compress(data, compresslevel=9, mtime=0)


def read_pack(data: bytes) -> tuple[dict[str, list[int]], bytes]:
    if data[:8] != VANILLA_MAGIC or len(data) < 12:
        raise RuntimeError("vanilla asset pack has no GAIUSVP1 header")
    index_length = struct.unpack("<I", data[8:12])[0]
    index = json.loads(data[12:12 + index_length].decode("utf-8"))
    return index, data[12 + index_length:]


def write_pack(entries: list[tuple[str, bytes]]) -> bytes:
    index: dict[str, list[int]] = {}
    payload = io.BytesIO()
    for name, body in entries:
        index[name] = [payload.tell(), len(body)]
        payload.write(body)
    index_bytes = json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return VANILLA_MAGIC + struct.pack("<I", len(index_bytes)) + index_bytes + payload.getvalue()


def split_vanilla_pack(compressed: bytes) -> tuple[bytes, bytes, int]:
    """Returns (core pack gzip, sounds pack raw, sound count)."""
    index, payload = read_pack(gzip.decompress(compressed))
    core: list[tuple[str, bytes]] = []
    sounds: list[tuple[str, bytes]] = []
    for name in sorted(index, key=lambda key: index[key][0]):
        offset, length = index[name]
        body = payload[offset:offset + length]
        if len(body) != length:
            raise RuntimeError(f"vanilla asset pack range is truncated: {name}")
        is_sound = name.startswith("assets/minecraft/sounds/") and name.endswith(".ogg")
        if is_sound and not name.startswith(CORE_SOUND_PREFIXES):
            sounds.append((name, body))
        else:
            core.append((name, body))
    # OGG is already compressed: the sounds pack ships raw and needs no inflate on the page.
    return deterministic_gzip(write_pack(core)), write_pack(sounds), len(sounds)


class Site:
    def __init__(self, output: Path, dry_run: bool = False) -> None:
        self.output = output
        self.dry_run = dry_run
        self.assets: dict[str, str] = {}
        self.files: dict[str, bytes] = {}
        self.sizes: dict[str, int] = {}
        self.digests: dict[str, str] = {}
        self.runtime_bytes = {"wasm": 0, "shared": 0}

    def add(self, logical: str, data: bytes, *, hashed: bool = True) -> str:
        url = hashed_name(logical, data) if hashed else logical
        if url in self.files and self.files[url] != data:
            raise RuntimeError(f"site path collision: {url}")
        if url not in self.sizes:
            self.runtime_bytes[runtime_group(logical)] += len(data)
        self.sizes[url] = len(data)
        self.digests[url] = sha256_bytes(data)
        if not self.dry_run:
            self.files[url] = data
        if hashed:
            self.assets[logical] = url
        return url

    def write(self) -> None:
        if self.dry_run:
            return
        if self.output.exists():
            shutil.rmtree(self.output)
        for url, data in sorted(self.files.items()):
            target = self.output / url
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    def archive(self, path: Path) -> None:
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
            for url, data in sorted(self.files.items()):
                info = tarfile.TarInfo(url)
                info.size = len(data)
                info.mtime = ARCHIVE_MTIME
                info.mode = 0o644
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                archive.addfile(info, io.BytesIO(data))
        path.write_bytes(deterministic_gzip(buffer.getvalue()))


def license_text(root: Path) -> str:
    """The notice and license terms as one document, as build-portable-html.py writes them."""
    files = [root / "NOTICE", root / "LICENSE", *sorted((root / "LICENSES").glob("*.txt"))]
    sections = []
    for path in files:
        title = path.relative_to(root).as_posix()
        content = path.read_text(encoding="utf-8")
        sections.append(f"{title}\n{'=' * len(title)}\n{content}")
    return "\n\n".join(sections)


def require(path: Path) -> bytes:
    if not path.is_file() or path.stat().st_size == 0:
        raise FileNotFoundError(f"missing site input: {path}")
    return path.read_bytes()


def check_profile_asset(path: Path, profile: str, role: str) -> None:
    """Bind a profile-specific Wasm asset to its build identity sidecar."""
    sidecar = path.with_name(path.name + ".build.json")
    if not sidecar.is_file():
        raise RuntimeError(f"missing build identity sidecar for {role}: {sidecar}")
    try:
        record = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"invalid build identity sidecar for {role}: {sidecar}") from exc
    if record.get("profile", {}).get("id") != profile:
        raise RuntimeError(
            f"{role} profile mismatch: expected {profile}, "
            f"got {record.get('profile', {}).get('id')}: {path}"
        )
    artifact = record.get("artifact", {})
    if artifact.get("name") != path.name or artifact.get("bytes") != path.stat().st_size \
            or artifact.get("sha256") != sha256_bytes(path.read_bytes()):
        raise RuntimeError(f"{role} artifact does not match its build identity: {path}")


def check_shader_manifest(directory: Path) -> None:
    manifest_path = directory / "gaius-shader-toolchain.json"
    if not manifest_path.is_file():
        return
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for name, expected in (manifest.get("files") or {}).items():
        path = directory / name
        if not path.is_file() or path.stat().st_size != expected.get("bytes") \
                or sha256_bytes(path.read_bytes()) != expected.get("sha256"):
            raise RuntimeError(f"shader toolchain manifest mismatch: {path}")


def resolve_asset(dist: Path, logical: str, index_html: str) -> Path:
    """Resolve a frozen dist's unhashed or manifest-rewritten asset name."""
    direct = dist / logical
    if direct.is_file() and direct.stat().st_size:
        return direct
    stem, suffix = logical.rsplit(".", 1)
    candidates = sorted(dist.glob(f"{stem}.*.{suffix}"))
    if candidates:
        return candidates[0]
    marker = re.search(rf"[\"']([^\"']*{re.escape(stem)}[^\"']*\.{re.escape(suffix)})[\"']", index_html)
    if marker:
        path = dist / Path(marker.group(1)).name
        if path.is_file() and path.stat().st_size:
            return path
    raise FileNotFoundError(f"missing site input: {direct} (hashed {logical} not found)")


def check_runtime_provenance(dist: Path, wasm_dist: Path) -> None:
    """Reject a Wasm server built from a different client source/profile input."""
    client_meta = wasm_dist / "classes.wasm.build.json"
    server_meta = wasm_dist / "singleplayer-server.wasm.build.json"
    if not server_meta.is_file():
        return
    if not client_meta.is_file():
        # Older WasmGC builds did not emit a client identity sidecar.  The
        # server sidecar still binds the worker to the selected profile; do
        # do not compare it with an internal resource-generation build.
        return
    client = json.loads(client_meta.read_text(encoding="utf-8"))
    server = json.loads(server_meta.read_text(encoding="utf-8"))
    for key in ("profile", "source"):
        left = client.get(key, {}).get("sha256")
        right = server.get(key, {}).get("sha256")
        if left and right and left != right:
            raise RuntimeError(
                f"Wasm server provenance mismatch in {key}: client {left}, server {right}")


def profile_of(index_html: str) -> str:
    match = re.search(r'window\.__gaiusProfileId = "([^"]+)";', index_html)
    if not match:
        raise RuntimeError("index.html has no __gaiusProfileId")
    return match.group(1)


def add_kernels(site: Site, dist: Path, kernel_dist: Path | None = None) -> dict[str, dict[str, object]]:
    """Add kernels from the runtime that owns the page's client module.

    A release site uses the WasmGC dist as ``wasm_dist``. Kernels carry the job
    ABI and must therefore come from the same build as ``classes.wasm``; the
    ``dist`` is used only for internal resource staging when no WasmGC dist is supplied.
    """
    kernel_dist = kernel_dist or dist
    manifest_path = kernel_dist / "kernels" / "kernels.json"
    if not manifest_path.is_file():
        return {}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    kernels: dict[str, dict[str, object]] = {}
    for name, entry in sorted((manifest.get("kernels") or {}).items()):
        variants: dict[str, str] = {}
        hashes: dict[str, str] = {}
        for variant in ("simd", "baseline"):
            spec = (entry.get("variants") or {}).get(variant)
            if not isinstance(spec, dict):
                continue
            data = require(kernel_dist / "kernels" / str(spec["file"]))
            if spec.get("sha256") not in (None, sha256_bytes(data)):
                raise RuntimeError(f"kernel {spec['file']} does not match kernels.json")
            variants[variant] = site.add(f"kernels/{spec['file']}", data)
            hashes[variant] = sha256_bytes(data)
        if variants:
            kernels[name] = {"kinds": list(entry.get("kinds") or []), "variants": variants, "sha256": hashes}
            if isinstance(entry.get("memory"), dict):
                kernels[name]["memory"] = entry["memory"]
    return kernels


def add_webgpu(site: Site, dist: Path, fill_missing_page_modules: bool = False) -> list[str]:
    """The WebGPU page files the dist staged; the translator also as .gz (the boot's gzip fallback)."""
    added: list[str] = []
    for name in WEBGPU_MODULES:
        path = dist / "runtime" / "webgpu" / name
        if fill_missing_page_modules and name == "quality-post.js" and not path.is_file() \
                and (dist / "runtime/webgpu/gpu-glue.js").is_file():
            path = WEB / "runtime/webgpu/quality-post.js"
        if path.is_file():
            site.add(f"runtime/webgpu/{name}", path.read_bytes())
            added.append(f"runtime/webgpu/{name}")
    translator = dist / "runtime" / "webgpu" / WEBGPU_TRANSLATOR
    if translator.is_file():
        module = translator.read_bytes()
        site.add(f"runtime/webgpu/{WEBGPU_TRANSLATOR}", module)
        site.add(f"runtime/webgpu/{WEBGPU_TRANSLATOR}.gz", deterministic_gzip(module))
        added.append(f"runtime/webgpu/{WEBGPU_TRANSLATOR}")
    return added


def add_png_kernel(site: Site, wasm_dist: Path) -> dict[str, object]:
    """The Rust PNG decoder of a wasm dist (stage-web-runtime.py --png), switched on for the page.

    STBImage (WebAssembly GC target) hands each PNG to it and keeps the Java decoder for
    anything it declines; port/native/golden/png/check.py proves equal pixels for every PNG of
    the client jars. Returns the page globals gaius-wasm-boot.js reads, none without the module."""
    module = wasm_dist / "gaius-png.wasm"
    script = WEB / "runtime" / "wasmgc" / "png-kernel.js"
    if not module.is_file():
        return {}
    data = module.read_bytes()
    if data[:4] != b"\0asm":
        raise RuntimeError(f"{module} is not a WebAssembly module")
    return {
        "__gaiusPngKernelEnabled": True,
        "__gaiusPngScriptUrl": site.add("png-kernel.js", require(script)),
        "__gaiusPngWasmUrl": site.add("gaius-png.wasm", data),
    }


def boot_inflates(boot: bytes) -> bool:
    return BOOT_INFLATE_MARKER.encode("ascii") in boot


def inflate_url(gzip_url: str) -> str:
    """classes.wasm.<hash>.gz -> classes.<hash>.wasm (gaius-sw.js gzipSibling is the inverse)."""
    match = re.fullmatch(r"(.*)\.wasm\.([0-9a-f]+)\.gz", gzip_url)
    if not match:
        raise RuntimeError(f"not a hashed module .gz: {gzip_url}")
    return f"{match.group(1)}.{match.group(2)}.wasm"


def add_wasm_runtime(site: Site, wasm_dist: Path, gzip_only: bool,
                     inputs: dict[str, Path] | None = None) -> tuple[list[str], dict[str, str]]:
    """The WebAssembly GC client and server of a wasm dist (make-wasm-page.py).

    Returns the modules added and, for a gzip-only site, logical name -> inflated URL."""
    added: list[str] = []
    inflate: dict[str, str] = {}
    for name in WASM_MODULES:
        path = inputs[name] if inputs and name in inputs else wasm_dist / name
        if name != "classes.wasm" and not path.is_file():
            continue
        module = require(path)
        if module[:4] != b"\0asm":
            raise RuntimeError(f"{path} is not a WebAssembly module")
        if gzip_only:
            inflate[name] = inflate_url(site.add(f"{name}.gz", deterministic_gzip(module)))
        else:
            site.add(name, module)
            if name == "classes.wasm":
                # Inflated on the page when the host does not compress on the wire (gaius-boot.js).
                site.add("classes.wasm.gz", deterministic_gzip(module))
        added.append(name)
    site.add("gaius-wasm-gc-runtime.js", require(wasm_dist / "gaius-wasm-gc-runtime.js"))
    for name in WASM_RUNTIME_SCRIPTS:
        site.add(name, require(WEB / "runtime" / "wasmgc" / name))
    return added, inflate


def build(dist: Path, output: Path, archive: Path | None, split_sounds: bool,
          wasm_dist: Path | None = None, wasm_gzip_only: bool | None = None,
          js_gzip_copies: bool = True, dry_run: bool = False,
          wasm_inputs: dict[str, Path] | None = None,
          no_js_fallback: bool = False, versions_dir: Path | None = None) -> dict[str, object]:
    """wasm_gzip_only: None follows the boot script (BOOT_INFLATE_MARKER), True/False force it.

    js_gzip_copies=False leaves out development JavaScript gzip copies. Release pages always
    use the WasmGC-only path."""
    dist = dist.resolve()
    if no_js_fallback and wasm_dist is None:
        raise RuntimeError("WasmGC-only site builds require --wasm-dist")
    if wasm_dist is not None:
        check_runtime_provenance(dist, wasm_dist.resolve())
    index_path = dist / "index.html"
    # postprocess-index-html.py on Windows once wrote CRLF; the tags below are matched as LF.
    index_html = require(index_path).decode("utf-8").replace("\r\n", "\n")
    # Preserve the WasmGC WebGPU toolchain declaration before removing the
    # generic runtime-config block. The declaration is moved into site globals
    # below; dropping it here makes the manifest omit the shader compiler and
    # breaks every WebGPU preflight on a staged Wasm-only site.
    lazy_toolchain_config = WEBGPU_SHADER_TOOLCHAIN_CONFIG.search(index_html)
    profile = profile_of(index_html)
    profile_path = (versions_dir or VERSIONS_DIR) / f"{profile}.json"
    profile_config = json.loads(require(profile_path).decode("utf-8"))
    postprocess_path = ROOT / "port" / "scripts" / "postprocess-index-html.py"
    postprocess_spec = importlib.util.spec_from_file_location("gaius_postprocess_index_html", postprocess_path)
    if postprocess_spec is None or postprocess_spec.loader is None:
        raise RuntimeError("could not load postprocess-index-html.py")
    postprocess_module = importlib.util.module_from_spec(postprocess_spec)
    postprocess_spec.loader.exec_module(postprocess_module)
    if lazy_toolchain_config is None and no_js_fallback and wasm_dist is not None:
        toolchain_files = tuple(postprocess_module.SHADER_TOOLCHAIN_FILES)
        if all((wasm_dist / name).is_file() for name in toolchain_files):
            token = postprocess_module.content_token(*(wasm_dist / name for name in toolchain_files))
            lazy_toolchain_config = {
                "loader": f"gaius-shader-toolchain.js?v={token}",
                "version": token,
            }
    index_html = postprocess_module.patch_early_profile_id(index_html, profile_config)
    # A wasm dist's page names its own runtime default and disk payloads (make-wasm-page.py);
    # the site keeps the product default and lists its runtimes in window.__gaiusSite.
    index_html = RUNTIME_CONFIG_BLOCK.sub("", index_html)
    if BOOT_TAG not in index_html:
        raise RuntimeError("index.html has no gaius-boot.js tag (rebuild it from the current launcher template)")
    boot = require(BOOT_SCRIPT)
    if wasm_gzip_only and not boot_inflates(boot):
        raise RuntimeError(f"{BOOT_SCRIPT} cannot load gzip-only modules (no {BOOT_INFLATE_MARKER})")
    gzip_only = boot_inflates(boot) if wasm_gzip_only is None else wasm_gzip_only
    site = Site(output.resolve(), dry_run)
    # Keep license terms at stable paths so offline copies and archived sites retain them.
    site.add("NOTICE", require(ROOT / "NOTICE"), hashed=False)
    site.add("LICENSE", require(ROOT / "LICENSE"), hashed=False)
    # Keep the Pages deployment policy alongside the generated site. The launcher
    # also carries the meta equivalent for hosts that do not apply _headers.
    site.add("_headers", PAGES_HEADERS, hashed=False)
    for license_path in sorted((ROOT / "LICENSES").glob("*.txt")):
        site.add(f"LICENSES/{license_path.name}", license_path.read_bytes(), hashed=False)
    # The same terms as one plain-text file, which the in-game About Gaius screen reads
    # (build-portable-html.py embeds this text in the portable page instead).
    site.add("THIRD-PARTY-NOTICES.txt", (license_text(ROOT) + "\n").encode("utf-8"), hashed=False)

    if not no_js_fallback:
        for name in ("classes.js", "singleplayer-server.js", "gaius-hotpath.wasm"):
            site.add(name, resolve_asset(dist, name, index_html).read_bytes())
    elif wasm_dist is not None:
        # The Wasm-only site still uses the native hotpath loader.  It is a
        # runtime asset, so keep it when the internal resource build
        # client/server payloads are intentionally omitted.
        hotpath = wasm_dist.resolve() / "gaius-hotpath.wasm"
        if hotpath.is_file():
            site.add("gaius-hotpath.wasm", hotpath.read_bytes())
    if js_gzip_copies and not no_js_fallback:
        for name in ("classes.js.gz", "singleplayer-server.js.gz"):
            site.add(name, resolve_asset(dist, name, index_html).read_bytes())
    # One Worker bootstrap serves the WasmGC server. A gzip-only site comes with a Wasm
    # dist of this tree; its bootstrap (make-wasm-page.py stages this tree's) is the one the
    # Wasm server needs.
    worker_dist = wasm_dist.resolve() if wasm_dist is not None and gzip_only \
        and (wasm_dist / "singleplayer-server-worker.js").is_file() else dist
    site.add("singleplayer-server-worker.js", require(worker_dist / "singleplayer-server-worker.js"))
    # The relay registry keeps its name: pages and tools fetch it by that name.
    if (dist / "relay-nodes.json").is_file():
        site.add("relay-nodes.json", (dist / "relay-nodes.json").read_bytes(), hashed=False)

    pack_dist = wasm_dist.resolve() if no_js_fallback and wasm_dist is not None else dist
    vanilla_path = pack_dist / "vanilla-assets.pack.gz"
    if no_js_fallback and wasm_dist is not None:
        check_profile_asset(vanilla_path, profile, "vanilla-assets.pack.gz")
    vanilla = require(vanilla_path)
    sound_count = 0
    if split_sounds:
        core, sounds, sound_count = split_vanilla_pack(vanilla)
        site.add("vanilla-assets.pack.gz", core)
        if sound_count:
            site.add("vanilla-sounds.pack", sounds)
    else:
        site.add("vanilla-assets.pack.gz", vanilla)

    # Runtime modules: boot script, kernel runtime and job codecs, kernel worker, quality layer.
    site.add("gaius-boot.js", boot)
    modules: list[str] = []
    for path in sorted((WEB / "kernels").glob("*.js")):
        if path.name in ("kernel-pool.js",):
            continue
        # Wasm sites carry profile-bound kernel semantics in the Wasm client
        # dist. Using the JavaScript dist here drops that preamble and makes
        # the WebGPU shader mirror appear disabled at runtime.
        staged = (wasm_dist.resolve() if wasm_dist is not None else dist) / "kernels" / path.name
        if not staged.is_file():
            staged = dist / "kernels" / path.name
        site.add(f"kernels/{path.name}", require(staged) if path.name == "kernel-mirror.js" and staged.is_file()
                 else path.read_bytes())
        if path.name.endswith("-job.js"):
            modules.append(f"kernels/{path.name}")
    semantics_dist = wasm_dist.resolve() if no_js_fallback and wasm_dist is not None else dist
    if (semantics_dist / "kernels/kernel-semantics.json").is_file():
        semantics = require(semantics_dist / "kernels/kernel-semantics.json")
        if no_js_fallback:
            document = json.loads(semantics.decode("utf-8"))
            if document.get("profile") != profile:
                raise RuntimeError("kernel semantics profile mismatch")
        site.add("kernels/kernel-semantics.json", semantics)
    for name in QUALITY_MODULES:
        path = WEB / "runtime" / "quality" / name
        if path.is_file():
            site.add(f"runtime/quality/{name}", path.read_bytes())
    add_webgpu(site, wasm_dist.resolve() if wasm_dist is not None else dist, wasm_dist is not None)
    # A WasmGC dist without a manifest deliberately ships no native kernels;
    # vanilla Java remains the correctness fallback.  When a manifest exists,
    # it is authoritative and is read from the same dist as classes.wasm.
    kernels = add_kernels(site, dist, wasm_dist.resolve() if wasm_dist is not None else None)
    wasm, inflate = add_wasm_runtime(site, wasm_dist.resolve(), gzip_only, wasm_inputs) if wasm_dist is not None else ([], {})
    # The Service Worker keeps a stable URL so the browser can update it.
    site.add("gaius-sw.js", require(SERVICE_WORKER), hashed=False)
    site.add("gaius-relay.html", require(RELOAD_RELAY_PAGE), hashed=False)
    reload_relay_script = ('  <script data-gaius-reload-relay="v1">\n' +
                           require(RELOAD_RELAY_SCRIPT).decode("utf-8") + "  </script>\n")

    site_globals: dict[str, object] = {}
    if wasm_dist is not None:
        site_globals.update(add_png_kernel(site, wasm_dist.resolve()))
    toolchain = SHADER_TOOLCHAIN_TAG.search(index_html)
    lazy_toolchain = lazy_toolchain_config
    token = None
    if toolchain is not None:
        token = toolchain.group(2)
    elif lazy_toolchain is not None:
        config = lazy_toolchain if isinstance(lazy_toolchain, dict) else json.loads(lazy_toolchain.group(1))
        token = config.get("version")
        if not isinstance(token, str) or re.fullmatch(r"[0-9a-f]+", token) is None:
            raise RuntimeError("WebGPU shader toolchain has no valid version token")
        if config.get("loader") != f"gaius-shader-toolchain.js?v={token}":
            raise RuntimeError("WebGPU shader toolchain has an unrecognised loader")
    if token is not None:
        # The toolchain payload is owned by the matching Wasm client dist.
        toolchain_dist = wasm_dist.resolve() if no_js_fallback and wasm_dist is not None else dist
        if not all((toolchain_dist / name).is_file() for _, name in SHADER_TOOLCHAIN_MODULES) \
                or not (toolchain_dist / "gaius-shader-toolchain.js").is_file():
            if wasm_dist is None:
                raise FileNotFoundError("shader toolchain payload is missing from JS and Wasm dists")
            toolchain_dist = wasm_dist.resolve()
        if no_js_fallback:
            check_shader_manifest(toolchain_dist)
        loader_url = site.add("gaius-shader-toolchain.js", require(toolchain_dist / "gaius-shader-toolchain.js"))
        urls: dict[str, str] = {"version": token}
        for key, name in SHADER_TOOLCHAIN_MODULES:
            urls[key] = site.add(name, require(toolchain_dist / name))
        site_globals["__gaiusShaderToolchainUrls"] = urls
        if toolchain is not None or lazy_toolchain is not None:
            if no_js_fallback:
                # WasmGC release pages let gaius-boot.js run the capability gate before
                # loading the shader compiler. Keep its config and assets, but do not put a
                # heavyweight loader in the document head.
                if toolchain is not None:
                    index_html = index_html.replace(toolchain.group(0), "", 1)
                site_globals["__gaiusWebGpuShaderToolchain"] = {
                    "loader": f"gaius-shader-toolchain.js?v={token}", "version": token,
                }
            elif toolchain is not None:
                index_html = index_html.replace(
                    toolchain.group(0),
                    f'  <script data-gaius-shader-toolchain data-profile="{toolchain.group(1)}" src="{loader_url}"></script>\n',
                    1,
                )
    elif 'src="gaius-shader-toolchain.js' in index_html:
        # An unrewritten loader reference would 404 on the site (only hashed copies ship).
        raise RuntimeError("index.html references gaius-shader-toolchain.js but its loader tag is not recognised")

    boot_url = site.assets["gaius-boot.js"]
    # Phase-2 relay declarations are page bootstrap data.  Keep the exact
    # generated bytes and place them before the client boot script so the
    # browser transport can snapshot the declaration on its first socket.
    relay_protocol_path = None
    for candidate in ((wasm_dist.resolve() / "relay-protocol.js") if wasm_dist is not None else None,
                      dist / "relay-protocol.js"):
        if candidate is not None and candidate.is_file():
            relay_protocol_path = candidate
            break
    relay_protocol_script = ""
    if relay_protocol_path is not None:
        relay_protocol_script = ('  <script data-gaius-relay-protocol>\n' +
                                 require(relay_protocol_path).decode("utf-8") +
                                 "  </script>\n")

    def make_descriptor(site_id: str) -> dict[str, object]:
        descriptor: dict[str, object] = {
            "schema": SITE_SCHEMA,
            "id": site_id,
            "profile": profile,
            "assets": site.assets,
            "modules": modules,
            "kernels": kernels,
            "serviceWorker": "gaius-sw.js",
        }
        if no_js_fallback and wasm_dist is not None:
            descriptor["profileAssets"] = {
                "vanillaAssetsPackSha256": sha256_bytes(vanilla),
            }
        if inflate:
            # Logical module -> the URL gaius-sw.js inflates from the .gz in assets["<module>.gz"].
            descriptor["inflate"] = inflate
            descriptor["runtimePolicy"] = "auto"
        if no_js_fallback:
            descriptor["runtimePolicy"] = "wasm-only"
        return descriptor

    def render_index(descriptor: dict[str, object]) -> str:
        lines = [f"    window.__gaiusSite = {json.dumps(descriptor, sort_keys=True, separators=(',', ':'))};\n"]
        for name, value in site_globals.items():
            lines.append(f"    window.{name} = {json.dumps(value, sort_keys=True, separators=(',', ':'))};\n")
        site_script = '  <script data-gaius-site="v1">\n' + "".join(lines) + "  </script>\n"
        rendered = index_html.replace(
            BOOT_TAG, reload_relay_script + site_script + relay_protocol_script +
            f'  <script data-gaius-boot="v1" src="{boot_url}"></script>\n', 1
        )
        # License terms ship as sibling files (NOTICE, LICENSE, LICENSES/); the game page itself
        # carries no overlay or link so nothing covers the play area.
        return rendered

    # Hash every published file, including the stable Service Worker and the generated shell.
    # The empty id is a canonical placeholder that avoids making the generation self-referential.
    placeholder = make_descriptor("")
    placeholder_index = render_index(placeholder).encode("utf-8")
    placeholder_descriptor = (json.dumps(placeholder, indent=2, sort_keys=True) + "\n").encode("utf-8")
    manifest = [{"path": path, "bytes": site.sizes[path], "sha256": site.digests[path]}
                for path in sorted(site.sizes)]
    manifest.extend([
        {"path": "index.html", "bytes": len(placeholder_index), "sha256": sha256_bytes(placeholder_index)},
        {"path": "gaius-site.json", "bytes": len(placeholder_descriptor),
         "sha256": sha256_bytes(placeholder_descriptor)},
    ])
    site_id = sha256_bytes(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8"))[:HASH_LENGTH]
    descriptor = make_descriptor(site_id)
    index_html = render_index(descriptor)
    site.add("index.html", index_html.encode("utf-8"), hashed=False)
    site.add("gaius-site.json", (json.dumps(descriptor, indent=2, sort_keys=True) + "\n").encode("utf-8"),
             hashed=False)
    total = sum(site.sizes.values())
    if total > DEFAULT_LIMIT_BYTES:
        raise RuntimeError(f"site exceeds hard size limit: {total} > {DEFAULT_LIMIT_BYTES}")
    site.write()
    if archive is not None and not dry_run:
        site.archive(archive.resolve())
    return {"profile": profile, "id": site_id, "files": len(site.sizes), "bytes": total,
            "sounds": sound_count, "kernels": sorted(kernels), "wasm": wasm,
            "wasmGzipOnly": bool(inflate), "runtimeBytes": site.runtime_bytes,
            "keptByNextDeployBytes": sum(size for name, size in site.sizes.items() if kept_by_next_deploy(name)),
            "layout": site.sizes}


def runtime_group(logical: str) -> str:
    if logical in (*WASM_MODULES, *(name + ".gz" for name in WASM_MODULES),
                    *WASM_RUNTIME_SCRIPTS, "gaius-wasm-gc-runtime.js", "png-kernel.js", "gaius-png.wasm"):
        return "wasm"
    return "shared"


def kept_by_next_deploy(relative: str) -> bool:
    """Whether pages.yml keeps this file of the deployed release next to the following one."""
    name = relative.rsplit("/", 1)[-1]
    return (bool(KEPT_NAME.search(relative)) and not name.startswith(NOT_KEPT_PREFIXES)
            and not (name.startswith("singleplayer-server.") and name.endswith(".js")))


def site_size(site: Path) -> dict[str, object]:
    if not (site / "gaius-site.json").is_file():
        raise RuntimeError(f"not a Gaius site (no gaius-site.json): {site}")
    descriptor = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))
    files = sorted(path for path in site.rglob("*") if path.is_file())
    sizes = {path.relative_to(site).as_posix(): path.stat().st_size for path in files}
    kept = sum(size for name, size in sizes.items() if kept_by_next_deploy(name))
    largest = sorted(sizes.items(), key=lambda item: (-item[1], item[0]))[:8]
    raw_wasm = sorted(((name, size) for name, size in sizes.items() if name.endswith(".wasm")),
                      key=lambda item: (-item[1], item[0]))
    logical_names = {url: logical for logical, url in descriptor.get("assets", {}).items()}
    runtime_bytes = {"wasm": 0, "shared": 0}
    for name, size in sizes.items():
        runtime_bytes[runtime_group(logical_names.get(name, name))] += size
    return {
        "site": site.as_posix(),
        "profile": descriptor.get("profile"),
        "id": descriptor.get("id"),
        "files": len(sizes),
        "bytes": sum(sizes.values()),
        "keptByNextDeployBytes": kept,
        "wasmGzipOnly": bool(descriptor.get("inflate")),
        "runtimeBytes": runtime_bytes,
        "largest": [{"file": name, "bytes": size} for name, size in largest],
        "rawWasm": [{"file": name, "bytes": size} for name, size in raw_wasm],
        "rawWasmOverLimit": [{"file": name, "bytes": size} for name, size in raw_wasm
                              if size > MAX_RAW_WASM_BYTES],
    }


def check_total(sites: list[Path], limit: int, extras: list[Path] | None = None) -> dict[str, object]:
    limit = min(limit, DEFAULT_LIMIT_BYTES)
    reports = [site_size(site.resolve()) for site in sites]
    profiles = [report["profile"] for report in reports]
    if len(set(profiles)) != len(profiles):
        raise RuntimeError(f"two sites of the same profile: {profiles}")
    total = sum(int(report["bytes"]) for report in reports)
    kept = sum(int(report["keptByNextDeployBytes"]) for report in reports)
    extra_bytes = sum(path.stat().st_size for path in extras or [])
    raw_wasm_over_limit = [entry for report in reports for entry in report["rawWasmOverLimit"]]
    return {
        "limitBytes": limit,
        "sitesBytes": total,
        "keptByNextDeployBytes": kept,
        "extraBytes": extra_bytes,
        "worstCaseBytes": total + kept + extra_bytes,
        "ok": total + kept + extra_bytes <= limit and not raw_wasm_over_limit,
        "rawWasmOverLimit": raw_wasm_over_limit,
        "sites": reports,
    }


def main_check_total(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Size gate of the Pages sites of one release")
    parser.add_argument("sites", type=Path, nargs="+")
    parser.add_argument("--limit-bytes", type=int, default=DEFAULT_LIMIT_BYTES)
    parser.add_argument("--extra-file", type=Path, action="append", default=[])
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    report = check_total(args.sites, args.limit_bytes, args.extra_file)
    if args.json is not None:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for site in report["sites"]:
        print(f"Gaius site {site['profile']}: {site['bytes']} bytes in {site['files']} files, "
              f"{site['keptByNextDeployBytes']} kept by the next deploy"
              + (", gzip-only wasm" if site["wasmGzipOnly"] else ""))
    verdict = "within" if report["ok"] else "OVER"
    print(f"Pages total: {report['sitesBytes']} bytes + {report['keptByNextDeployBytes']} kept by the next "
          f"deploy + {report['extraBytes']} metadata bytes = {report['worstCaseBytes']} bytes, "
          f"{verdict} the limit of {report['limitBytes']}")
    if report["rawWasmOverLimit"]:
        print(f"Raw Wasm gate: {len(report['rawWasmOverLimit'])} file(s) exceed {MAX_RAW_WASM_BYTES} bytes")
        for item in report["rawWasmOverLimit"]:
            print(f"  {item['file']}: {item['bytes']} bytes")
    return 0 if report["ok"] else 1


def release_matrix(versions: Path) -> dict[str, object]:
    profiles = []
    for path in versions.glob("*.json"):
        profile = json.loads(path.read_text(encoding="utf-8"))
        if not profile.get("pages", {}).get("enabled", False):
            continue
        name = profile["id"]
        if path.stem != name or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) is None:
            raise RuntimeError(f"invalid Pages profile: {path}")
        if profile["pages"].get("runtimes") != ["wasm"]:
            raise RuntimeError(f"Pages profile must offer WasmGC only: {path}")
        profiles.append({"id": name, "releaseTime": profile["official"]["releaseTime"],
                         "runtimes": profile["pages"]["runtimes"], "path": name + "/",
                         "siteArchive": f"Gaius-site-{name}.tar.gz", "portableAsset": f"Gaius-{name}.html"})
    profiles.sort(key=lambda profile: profile["releaseTime"], reverse=True)
    if not profiles:
        raise RuntimeError(f"no enabled Pages profiles in {versions}")
    return {"schema": 1, "limitBytes": DEFAULT_LIMIT_BYTES, "retention": "previous-hashed",
            "profiles": profiles}


def discover_dists(roots: list[Path]) -> list[tuple[str, Path]]:
    found = []
    skipped = {".git", "work", "scratch", "repo", "suites", "fixtures", "tmp", "overlays", "cargo-target"}
    for root in roots:
        root = root.resolve()
        if root == Path(root.anchor) or not root.is_dir():
            raise RuntimeError(f"artifact root must be an existing project directory: {root}")
        for directory, children, names in os.walk(root, followlinks=False):
            base = Path(directory)
            children[:] = [name for name in children if name not in skipped
                           and not (base / name).is_symlink() and not (base / name).is_junction()]
            if "index.html" not in names:
                continue
            try:
                name = profile_of((base / "index.html").read_text(encoding="utf-8"))
            except (RuntimeError, UnicodeError):
                continue
            if "classes.wasm" in names:
                found.append((name, base))
    return found


def newest_dist(dists: list[tuple[str, Path]], profile: str | None, files: tuple[str, ...]) -> Path | None:
    candidates = [path for name, path in dists if (profile is None or name == profile)
                  and all((path / filename).is_file() and (path / filename).stat().st_size for filename in files)]
    return max(candidates, key=lambda path: (path.joinpath(files[0]).stat().st_mtime_ns, path.as_posix()), default=None)


def write_json(path: Path, report: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main_matrix(argv: list[str]) -> int:
    import gaius_pages_release as publication

    parser = argparse.ArgumentParser(description="Plan or package the profile-driven Pages release matrix")
    parser.add_argument("output", type=Path)
    parser.add_argument("--versions-dir", type=Path, default=ROOT / "port/versions")
    parser.add_argument("--artifact-root", type=Path, action="append", required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--project-missing", action="store_true")
    parser.add_argument("--previous-site", type=Path, action="append", default=[])
    parser.add_argument("--json", type=Path)
    parser.add_argument("--release-tag", default="v" + normalized_release_version())
    args = parser.parse_args(argv)
    if args.project_missing and not args.dry_run:
        parser.error("--project-missing is only allowed with --dry-run")
    if not args.dry_run and args.output.exists() and any(args.output.iterdir()):
        raise RuntimeError(f"refusing to overwrite matrix output: {args.output}")
    matrix = release_matrix(args.versions_dir)
    dists = discover_dists(args.artifact_root)
    reports = []
    wasm_files = ("classes.wasm", "gaius-wasm-gc-runtime.js")
    for profile in matrix["profiles"]:
        name = profile["id"]
        projected = []
        wasm_dist = newest_dist(dists, name, wasm_files)
        if wasm_dist is None:
            if not args.project_missing:
                raise RuntimeError(f"missing wasm client dist for {name}")
            wasm_dist = newest_dist(dists, None, wasm_files)
            projected.append({"runtime": "wasm", "reason": "missing wasm client dist", "basis": str(wasm_dist)})
        if wasm_dist is None:
            raise RuntimeError("no measured artifact available as a projection basis")
        server_dist = newest_dist(dists, name, ("singleplayer-server.wasm",))
        if server_dist is None:
            if not args.project_missing:
                raise RuntimeError(f"missing wasm server for {name}")
            server_dist = newest_dist(dists, None, ("singleplayer-server.wasm",))
            projected.append({"runtime": "wasm-server", "reason": "missing wasm server module",
                              "basis": str(server_dist)})
        if server_dist is None:
            raise RuntimeError("no measured wasm server available as a projection basis")
        server = server_dist / "singleplayer-server.wasm"
        output = args.output / name
        report = build(wasm_dist, output, None, True, wasm_dist, True, False, True,
                       {"singleplayer-server.wasm": server}, no_js_fallback=True,
                       versions_dir=args.versions_dir)
        if not projected and report["profile"] != name:
            raise RuntimeError(f"artifact profile mismatch for {name}")
        report.update({"profile": name, "path": profile["path"],
                       "inputs": {"wasmClient": wasm_dist.as_posix(),
                                  "wasmServer": server.as_posix()}, "projected": projected,
                       "artifactAcceptance": "not-verified"})
        reports.append(report)
    sites_bytes = sum(report["bytes"] for report in reports)
    if args.previous_site:
        previous = [site_size(path) for path in args.previous_site]
        kept = sum(report["keptByNextDeployBytes"] for report in previous)
        retention_status = "measured-conservative-no-deduplication"
    else:
        kept = sum(report["keptByNextDeployBytes"] for report in reports)
        retention_status = "projected-one-generation-reserve"
    matrix_bytes = len((json.dumps(matrix, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    root_bytes = sum(len(publication.redirect(profile["path"], profile["id"])) for profile in matrix["profiles"])
    root_bytes += len(publication.publication_hint(args.release_tag, "site", [profile["id"] for profile in matrix["profiles"]]))
    total = sites_bytes + kept + matrix_bytes + root_bytes
    report = {"schema": 1, "dryRun": args.dry_run, "limitBytes": DEFAULT_LIMIT_BYTES,
              "sitesBytes": sites_bytes, "retainedBytes": kept, "retentionStatus": retention_status,
              "matrixBytes": matrix_bytes, "rootBytes": root_bytes, "releaseTag": args.release_tag,
              "totalBytes": total, "ok": total <= DEFAULT_LIMIT_BYTES,
              "releaseReady": False, "profiles": reports}
    if not args.dry_run and report["ok"]:
        for profile, plan in zip(matrix["profiles"], reports):
            inputs = plan["inputs"]
            output = args.output / profile["id"]
            build(Path(inputs["wasmClient"]), output, None, True, Path(inputs["wasmClient"]), True, False,
                  wasm_inputs={"singleplayer-server.wasm": Path(inputs["wasmServer"])}, no_js_fallback=True)
            archive_site(output, args.output / profile["siteArchive"])
        write_json(args.output / "Gaius-pages-matrix.json", matrix)
    if args.json:
        write_json(args.json, report)
    for profile in reports:
        status = "PROJECTED" if profile["projected"] else "MEASURED"
        print(f"{profile['profile']} {status}: {profile['bytes']} bytes {profile['runtimeBytes']}")
        for projection in profile["projected"]:
            print(f"  PROJECTED {projection['runtime']}: {projection['reason']}; basis {projection['basis']}")
    print(f"Pages total: {sites_bytes} + retained {kept} ({retention_status}) + matrix {matrix_bytes} + root {root_bytes} = {total} bytes; "
          f"limit {DEFAULT_LIMIT_BYTES}; {'PASS' if report['ok'] else 'FAIL'} (size only, not release acceptance)")
    return 0 if report["ok"] else 1


def archive_site(site: Path, archive: Path) -> None:
    planned = Site(site)
    for path in sorted(site.rglob("*")):
        if path.is_file():
            planned.add(path.relative_to(site).as_posix(), path.read_bytes(), hashed=False)
    planned.archive(archive)


def main(argv: list[str]) -> int:
    if argv[:1] == ["--write-matrix"]:
        parser = argparse.ArgumentParser(description="Write the Pages matrix from version profiles")
        parser.add_argument("output", type=Path)
        parser.add_argument("--versions-dir", type=Path, default=ROOT / "port/versions")
        args = parser.parse_args(argv[1:])
        write_json(args.output, release_matrix(args.versions_dir))
        return 0
    if argv[:1] == ["--publish-release"]:
        import gaius_pages_release
        return gaius_pages_release.main(argv[1:], release_matrix(ROOT / "port/versions"),
                                       kept_by_next_deploy, runtime_group)
    if argv[:1] == ["--matrix"]:
        return main_matrix(argv[1:])
    if argv[:1] == ["--check-total"]:
        return main_check_total(argv[1:])
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dist", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--no-split-sounds", action="store_true")
    parser.add_argument("--wasm-dist", type=Path)
    parser.add_argument("--wasm-server-dist", type=Path,
                        help="directory containing the WasmGC singleplayer-server.wasm")
    parser.add_argument("--dry-run", action="store_true")
    form = parser.add_mutually_exclusive_group()
    form.add_argument("--wasm-gzip-only", dest="wasm_gzip_only", action="store_true", default=None)
    form.add_argument("--wasm-raw", dest="wasm_gzip_only", action="store_false")
    parser.add_argument("--no-js-gzip", action="store_true",
                        help="leave out legacy JavaScript gzip copies (development helper only)")
    parser.add_argument("--no-js-fallback", action="store_true",
                        help="WasmGC-only site; omit the legacy JavaScript client and server")
    parser.add_argument("--release", action="store_true",
                        help="release staging mode; requires WasmGC inputs and omits JavaScript assets")
    args = parser.parse_args(argv)
    wasm_inputs = None
    if args.wasm_server_dist is not None:
        wasm_inputs = {"singleplayer-server.wasm": args.wasm_server_dist / "singleplayer-server.wasm"}
    no_js_fallback = args.no_js_fallback or args.release
    report = build(args.dist, args.output, args.archive, not args.no_split_sounds, args.wasm_dist,
                   args.wasm_gzip_only, not args.no_js_gzip, args.dry_run, wasm_inputs,
                   no_js_fallback)
    print(f"Gaius site {report['profile']} ({report['id']}): {report['files']} files, "
          f"{report['bytes']} bytes, {report['sounds']} streamed sounds, kernels {report['kernels']}"
          + (f", WebAssembly GC {report['wasm']}" + (" (gzip only)" if report["wasmGzipOnly"] else "")
             if report["wasm"] else ""))
    if args.archive is not None and not args.dry_run:
        print(f"Gaius site archive: {args.archive} ({args.archive.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
