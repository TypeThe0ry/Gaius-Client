#!/usr/bin/env python3
"""Build a self-contained WasmGC-only Gaius HTML file for offline singleplayer use."""

from __future__ import annotations

import base64
import gzip
import hashlib
import html
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path


def _native_external_path(value: str) -> Path:
    """Accept Git-Bash /c/... paths when running Windows Python."""
    if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", value):
        value = f"{value[1].upper()}:{value[2:]}"
    return Path(value).expanduser()

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))
import gaius_build_identity as build_identity

COMPILER_PROFILE_PATH = SCRIPT_DIRECTORY / "teavm-compiler-profile.py"
COMPILER_PROFILE_SPEC = importlib.util.spec_from_file_location(
    "gaius_teavm_compiler_profile", COMPILER_PROFILE_PATH
)
if COMPILER_PROFILE_SPEC is None or COMPILER_PROFILE_SPEC.loader is None:
    raise RuntimeError(f"could not load TeaVM compiler profile helper: {COMPILER_PROFILE_PATH}")
compiler_profile = importlib.util.module_from_spec(COMPILER_PROFILE_SPEC)
COMPILER_PROFILE_SPEC.loader.exec_module(compiler_profile)


MANIFEST_NAME = "Gaius.manifest.json"
MANIFEST_KIND = "gaius-portable-artifact"
MANIFEST_SCHEMA_VERSION = 2

# Generated-artifact markers survive TeaVM renaming/minifying. These are also
# the markers checked when a release reuses compiled JavaScript.
PORTABLE_SIGNATURES = (
    (
        "client-finite-long-patch",
        "classes.js",
        b"/*gaius-java-finite-long-cast*/",
    ),
    (
        "client-target-attestation",
        "classes.js",
        b"target-attestation",
    ),
    (
        "server-input-pump",
        "singleplayer-server.js",
        b"/*gaius-integrated-server-input-coroutine*/",
    ),
)


def write_text_atomically(target: Path, text: str) -> None:
    """Replace the portable artifact only after its complete contents are durable."""
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            # LF on every host: release gates and the Pages builder match this text exactly.
            newline="\n",
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, target)
        temporary_name = None
        # Windows cannot open a directory for fsync; the replace is atomic anyway.
        if os.name != "nt":
            directory_fd = os.open(target.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def _prepare_text(target: Path, text: str) -> str:
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            # LF on every host: release gates and the Pages builder match this text exactly.
            newline="\n",
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
        result = temporary_name
        temporary_name = None
        return result
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def drop_site_notice_link(document: str) -> str:
    """A site shell links its sibling NOTICE file; a portable page carries the text instead."""
    return document.replace('  <p><a href="NOTICE">Third-party notices and licenses</a></p>\n', "")


def license_text(root: Path) -> str:
    """The notice and license terms of the project root as one plain-text document."""
    files = [root / "NOTICE", root / "LICENSE", *sorted((root / "LICENSES").glob("*.txt"))]
    sections = []
    for path in files:
        title = path.relative_to(root).as_posix()
        content = path.read_text(encoding="utf-8")
        sections.append(f"{title}\n{'=' * len(title)}\n{content}")
    return "\n\n".join(sections)


def add_license_notice(document: str, root: Path) -> str:
    """Embed the full notice and license terms in an inert block of a page that works offline.

    The block is a non-rendered text/plain script, so it never covers or lays out against the
    game; it stays readable in the page source and next to the page as THIRD-PARTY-NOTICES.txt.
    """
    terms = license_text(root).replace("</", r"<\/")
    notice = f'<script type="text/plain" id="gaius-license-notice">\n{terms}\n</script>'
    document = drop_site_notice_link(document)
    return document.replace("</body>", notice + "</body>", 1) if "</body>" in document else document + notice


def write_license_sidecar(output: Path, root: Path) -> Path:
    """Ship the notices as a plain file beside the portable page."""
    sidecar = output.parent / "THIRD-PARTY-NOTICES.txt"
    output.parent.mkdir(parents=True, exist_ok=True)
    sidecar.write_text(license_text(root) + "\n", encoding="utf-8", newline="\n")
    return sidecar


def _backup_path(path: Path) -> str | None:
    if not path.exists():
        return None
    descriptor, backup = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".rollback",
    )
    os.close(descriptor)
    os.unlink(backup)
    try:
        try:
            os.link(path, backup)
        except OSError:
            shutil.copy2(path, backup)
            with open(backup, "rb") as stream:
                os.fsync(stream.fileno())
        return backup
    except BaseException:
        try:
            os.unlink(backup)
        except FileNotFoundError:
            pass
        raise


def _fsync_directory(directory: Path) -> None:
    # Windows cannot open a directory for fsync.
    if os.name == "nt":
        return
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def publish_portable_pair(
    output: Path,
    portable: str,
    manifest_path: Path,
    manifest_text: str,
) -> None:
    """Publish HTML first and its sidecar last as the commit marker."""
    if output.parent != manifest_path.parent:
        raise RuntimeError("portable HTML and manifest must share a directory")
    html_temporary: str | None = None
    manifest_temporary: str | None = None
    html_backup: str | None = None
    manifest_backup: str | None = None
    html_replaced = False
    manifest_replaced = False
    try:
        html_temporary = _prepare_text(output, portable)
        manifest_temporary = _prepare_text(manifest_path, manifest_text)
        html_backup = _backup_path(output)
        manifest_backup = _backup_path(manifest_path)

        os.replace(html_temporary, output)
        html_temporary = None
        html_replaced = True
        _fsync_directory(output.parent)

        os.replace(manifest_temporary, manifest_path)
        manifest_temporary = None
        manifest_replaced = True
        _fsync_directory(output.parent)
    except BaseException:
        if manifest_replaced:
            if manifest_backup is None:
                manifest_path.unlink(missing_ok=True)
            else:
                os.replace(manifest_backup, manifest_path)
                manifest_backup = None
        if html_replaced:
            if html_backup is None:
                output.unlink(missing_ok=True)
            else:
                os.replace(html_backup, output)
                html_backup = None
        _fsync_directory(output.parent)
        raise
    finally:
        for temporary in (
            html_temporary,
            manifest_temporary,
            html_backup,
            manifest_backup,
        ):
            if temporary:
                try:
                    os.unlink(temporary)
                except FileNotFoundError:
                    pass


# Embedded payload encoding (gaius-b64-v1). Every asset is compressed once (gzip, the only
# codec every browser's DecompressionStream reads) and stored once as strict ASCII base64 in
# inert <script> elements. ASCII survives charset sniffing, BOM insertion, CRLF conversion,
# editor re-saves, zip/chat transfers and mobile browsers without relying on C1 Unicode code
# points. A decoder Worker turns the parts back into bytes, verifies the indexed SHA-256 and
# inflates them off the main thread. The older gaius-b7-v1 decoder remains available for old
# downloaded pages, but new pages never emit its non-ASCII U+0080..U+0083 escapes.
PAYLOAD_ENCODING = "gaius-b64-v1"
PAYLOAD_TYPE = "text/x-gaius-b64"
B64_PART_CHARS = 4 * 1024 * 1024


def encode_payload(data: bytes) -> str:
    """Return the portable payload as strict ASCII base64."""
    return base64.b64encode(data).decode("ascii")


def decode_payload(text: str, length: int) -> bytes:
    """Decode strict ASCII base64 and reject truncation or non-ASCII corruption."""
    try:
        value = base64.b64decode(text.encode("ascii"), validate=True)
    except (UnicodeEncodeError, ValueError) as exc:
        raise ValueError("gaius-b64 payload is not valid ASCII base64") from exc
    if len(value) != length:
        raise ValueError("gaius-b64 payload length does not match its declared length")
    return value
B7_ESCAPES = (0x00, 0x0A, 0x0D, 0x3C)
# Characters per payload element: a multiple of 8 (one 7-byte block encodes to 8 characters).
B7_PART_CHARS = 4 * 1024 * 1024


def _byte_table(function) -> bytes:
    return bytes(function(value) & 0xFF for value in range(256))


_B7_SHIFTS = (
    # (column, table) pairs whose OR is one 7-bit group; see decode_b7 for the inverse.
    ((0, _byte_table(lambda b: b >> 1)),),
    ((0, _byte_table(lambda b: (b & 1) << 6)), (1, _byte_table(lambda b: b >> 2))),
    ((1, _byte_table(lambda b: (b & 3) << 5)), (2, _byte_table(lambda b: b >> 3))),
    ((2, _byte_table(lambda b: (b & 7) << 4)), (3, _byte_table(lambda b: b >> 4))),
    ((3, _byte_table(lambda b: (b & 15) << 3)), (4, _byte_table(lambda b: b >> 5))),
    ((4, _byte_table(lambda b: (b & 31) << 2)), (5, _byte_table(lambda b: b >> 6))),
    ((5, _byte_table(lambda b: (b & 63) << 1)), (6, _byte_table(lambda b: b >> 7))),
    ((6, _byte_table(lambda b: b & 127)),),
)
_B7_TO_TEXT = bytes(
    0x80 + B7_ESCAPES.index(value) if value in B7_ESCAPES else value
    for value in range(256)
)


def encode_b7(data: bytes) -> str:
    """Encode bytes as gaius-b7 text (whole 7-byte blocks; the decoder truncates the padding).

    Column-wise byte translation and big-integer ORs keep the work in C: a 100 MB payload
    encodes in seconds without third-party modules.
    """
    padded = data + b"\0" * ((-len(data)) % 7)
    blocks = len(padded) // 7
    if blocks == 0:
        return ""
    columns = [padded[index::7] for index in range(7)]
    output = bytearray(blocks * 8)
    for group, parts in enumerate(_B7_SHIFTS):
        value = 0
        for column, table in parts:
            value |= int.from_bytes(columns[column].translate(table), "big")
        output[group::8] = value.to_bytes(blocks, "big")
    return bytes(output).translate(_B7_TO_TEXT).decode("latin-1")


_B7_FROM_TEXT = {0x80 + index: value for index, value in enumerate(B7_ESCAPES)}


def decode_b7(text: str, length: int) -> bytes:
    """Reference decoder (the page's decoder Worker does the same in JavaScript)."""
    groups = bytes(_B7_FROM_TEXT.get(ord(char), ord(char)) for char in text)
    output = bytearray()
    for start in range(0, len(groups) - 7, 8):
        g = groups[start:start + 8]
        output += bytes((
            (g[0] << 1) | (g[1] >> 6),
            ((g[1] & 63) << 2) | (g[2] >> 5),
            ((g[2] & 31) << 3) | (g[3] >> 4),
            ((g[3] & 15) << 4) | (g[4] >> 3),
            ((g[4] & 7) << 5) | (g[5] >> 2),
            ((g[5] & 3) << 6) | (g[6] >> 1),
            ((g[6] & 1) << 7) | g[7],
        ))
    if length > len(output):
        raise ValueError("gaius-b7 text is shorter than its declared length")
    return bytes(output[:length])


class PortablePayload:
    """The embedded assets of one portable page, in document (and decode-priority) order."""

    def __init__(self) -> None:
        self.assets: dict[str, dict[str, object]] = {}
        self.elements: list[str] = []

    def add(self, name: str, data: bytes, *, gzipped: bool) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or name in self.assets:
            raise RuntimeError(f"invalid or duplicate portable payload name: {name}")
        text = encode_payload(data)
        parts = 0
        for start in range(0, len(text), B64_PART_CHARS):
            self.elements.append(
                f'  <script type="{PAYLOAD_TYPE}" data-gaius-asset="{name}" data-part="{parts}">'
                + text[start:start + B64_PART_CHARS]
                + "</script>\n"
            )
            parts += 1
        self.assets[name] = {
            "bytes": len(data),
            "parts": parts,
            "gzip": gzipped,
            "sha256": sha256_bytes(data),
        }

    def index(self) -> dict[str, object]:
        return {"encoding": PAYLOAD_ENCODING, "assets": self.assets}

    def html(self) -> str:
        return "".join(self.elements)


def parse_portable_payload(html: str) -> dict[str, bytes]:
    """Decode every embedded asset of a portable page (tests and audits)."""
    pattern = re.compile(
        r'<script type="' + re.escape(PAYLOAD_TYPE)
        + r'" data-gaius-asset="([A-Za-z0-9_-]+)" data-part="(\d+)">([^<]*)</script>'
    )
    texts: dict[str, list[str]] = {}
    for match in pattern.finditer(html):
        parts = texts.setdefault(match.group(1), [])
        if int(match.group(2)) != len(parts):
            raise ValueError(f"portable payload {match.group(1)} parts are out of order")
        parts.append(match.group(3))
    index_marker = "const portablePayload = "
    start = html.index(index_marker) + len(index_marker)
    # The index holds names, numbers and hashes only, so ';' ends it whatever the line endings.
    end = html.index(";", start)
    index = json.loads(html[start:end])
    decoded: dict[str, bytes] = {}
    for name, text_parts in texts.items():
        encoding = index.get("encoding")
        decoder = decode_payload if encoding == "gaius-b64-v1" else decode_b7
        decoded[name] = decoder("".join(text_parts), int(index["assets"][name]["bytes"]))
    return decoded


def require(path: Path) -> Path:
    if not path.is_file():
        raise FileNotFoundError(f"missing portable asset: {path}")
    return path


def require_nonempty(path: Path) -> Path:
    require(path)
    if path.stat().st_size == 0:
        raise RuntimeError(f"portable asset is empty: {path}")
    return path


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_portable_no_relative_urls(html: str) -> None:
    """Portable pages must not leave sibling fetches behind for file://."""
    # The bodies of the inert payload elements are 7-bit encoded bytes, not markup: an
    # attribute-like sequence can occur in them by chance, so they are not scanned.
    html = re.sub(r'(<script type="' + re.escape(PAYLOAD_TYPE) + r'"[^>]*>)[^<]*(</script>)',
                  r'\1\2', html)
    for match in re.finditer(r'\b(?:src|href)\s*=\s*["\']([^"\']+)["\']', html,
                             flags=re.IGNORECASE):
        value = match.group(1).strip()
        if value and not value.startswith(('#', 'data:', 'blob:', 'http:', 'https:')):
            raise RuntimeError(f"portable page contains relative URL: {value}")


def load_version_profile(root: Path) -> tuple[dict, str, bytes]:
    config_path = require(root / "port" / "config.json")
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"could not read version config: {config_path}") from exc

    relative_profile = (
        os.environ.get("GAIUS_VERSION_PROFILE_PATH")
        or config.get("versionProfile")
    )
    if not isinstance(relative_profile, str) or not relative_profile:
        raise RuntimeError("port/config.json versionProfile must be a non-empty string")

    versions_directory = (root / "port" / "versions").resolve()
    profile_path = (root / "port" / relative_profile).resolve()
    try:
        profile_path.relative_to(versions_directory)
    except ValueError as exc:
        raise RuntimeError(
            "port/config.json versionProfile must point inside port/versions"
        ) from exc
    if profile_path.suffix != ".json":
        raise RuntimeError("port/config.json versionProfile must name a JSON profile")

    profile_bytes = require(profile_path).read_bytes()
    try:
        profile = json.loads(profile_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"could not read version profile: {profile_path}") from exc
    if not isinstance(profile, dict) or not isinstance(profile.get("id"), str):
        raise RuntimeError(f"version profile has no id: {profile_path}")
    if not profile["id"]:
        raise RuntimeError(f"version profile id is empty: {profile_path}")
    distribution = profile.get("clientDistribution")
    if distribution not in {"named", "obfuscated-with-mappings"}:
        raise RuntimeError(f"version profile clientDistribution is invalid: {profile_path}")
    if not isinstance(profile.get("worldVersion"), int) or profile["worldVersion"] < 0:
        raise RuntimeError(f"version profile worldVersion is invalid: {profile_path}")
    build_identity._validate_worldgen_telemetry_mode(profile)
    build_identity._validate_storage(profile)
    return profile, relative_profile, profile_bytes


def configured_build_root(root: Path) -> Path:
    """Return the target/state root for the active profile.

    The historical default remains port/target.  Supplying
    GAIUS_BUILD_ROOT makes every generated POM, resource list, Maven output,
    log and gap report live under that caller-owned directory.
    """
    raw = os.environ.get("GAIUS_BUILD_ROOT")
    if not raw:
        if os.environ.get("GAIUS_VERSION_PROFILE_PATH"):
            profile, _, _ = load_version_profile(root)
            return root / "port" / "target" / str(profile["id"])
        return root / "port" / "target"
    path = _native_external_path(raw)
    return (root / path).resolve() if not path.is_absolute() else path.resolve()


def configured_dist_directory(root: Path, profile_id: str) -> Path:
    raw = os.environ.get("GAIUS_DIST_DIRECTORY")
    if not raw and not (
        os.environ.get("GAIUS_BUILD_ROOT") or os.environ.get("GAIUS_VERSION_PROFILE_PATH")
    ):
        raw = os.environ.get("GAIUS_TARGET_DIRECTORY")
    if raw:
        path = _native_external_path(raw)
        return (root / path).resolve() if not path.is_absolute() else path.resolve()
    if os.environ.get("GAIUS_BUILD_ROOT") or os.environ.get("GAIUS_VERSION_PROFILE_PATH"):
        return root / "port" / "web" / "dist" / profile_id
    return root / "port" / "web" / "dist"


def launcher_argument(index: str, name: str) -> str | None:
    match = re.search(
        rf"[\"']{re.escape(name)}[\"']\s*,\s*[\"']([^\"']+)[\"']",
        index,
    )
    return match.group(1) if match is not None else None


def validate_launcher_profile(index: str, profile: dict) -> None:
    expected_version = profile["id"]
    actual_version = launcher_argument(index, "--version")
    if actual_version != expected_version:
        raise RuntimeError(
            f"portable launcher version {actual_version!r} does not match "
            f"active profile {expected_version!r}"
        )

    official = profile.get("official")
    expected_asset_index = (
        official.get("assetIndexId") if isinstance(official, dict) else None
    )
    if expected_asset_index is not None:
        actual_asset_index = launcher_argument(index, "--assetIndex")
        if actual_asset_index != str(expected_asset_index):
            raise RuntimeError(
                f"portable launcher asset index {actual_asset_index!r} does not match "
                f"active profile {expected_asset_index!r}"
            )


def launcher_global(index: str, name: str) -> object | None:
    match = re.search(
        rf"window\.{re.escape(name)}\s*=\s*(\"(?:[^\"\\]|\\.)*\"|'(?:[^'\\]|\\.)*'|-?\d+)\s*;",
        index,
    )
    if match is None:
        return None
    expression = match.group(1)
    if expression.startswith("'"):
        return bytes(expression[1:-1], "utf-8").decode("unicode_escape")
    return json.loads(expression)


def validate_launcher_storage(index: str, profile: dict) -> None:
    storage = build_identity._validate_storage(profile)
    expected = {
        "__gaiusProfileId": profile["id"],
        "__gaiusWorldVersion": profile["worldVersion"],
        "__gaiusStorageSchema": storage["schema"],
        "__gaiusStorageDatabaseName": storage["databaseName"],
        "__gaiusStoragePrefix": storage["prefix"],
        "__gaiusStorageOpfsDirectory": storage["opfsDirectory"],
    }
    for name, expected_value in expected.items():
        actual = launcher_global(index, name)
        if actual != expected_value:
            raise RuntimeError(
                f"portable launcher {name} {actual!r} does not match profile {expected_value!r}"
            )


def compare_gzip_with_raw(raw_path: Path, compressed_path: Path) -> tuple[str, str]:
    """Verify that gzip expands to the exact current raw JavaScript bytes."""
    raw_hash = sha256_file(raw_path)
    compressed_hash = sha256_file(compressed_path)
    try:
        # GzipFile.read(size) is allowed to return a short chunk even when
        # more decompressed bytes remain. Comparing fixed-size reads from the
        # raw and gzip streams therefore produces false mismatches at arbitrary
        # buffer boundaries. Read both complete byte streams for an exact
        # identity check; this runs only during packaging and avoids accepting
        # a stale or truncated compressed asset.
        raw_bytes = raw_path.read_bytes()
        compressed_bytes = gzip.open(compressed_path, "rb").read()
        if raw_bytes != compressed_bytes:
            raise RuntimeError(
                "classes.js.gz does not expand to the current classes.js"
            )
    except (EOFError, OSError) as exc:
        raise RuntimeError(f"classes.js.gz is not a complete gzip: {compressed_path}") from exc
    return raw_hash, compressed_hash


def contains_marker(path: Path, marker: bytes) -> bool:
    overlap = b""
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            if marker in overlap + chunk:
                return True
            overlap = (overlap + chunk)[-len(marker) + 1:]
    return False


def required_signatures(profile: dict) -> tuple[tuple[str, str, bytes], ...]:
    # The named profile is the current TeaVM line. The legacy mapped profile
    # remains buildable without requiring patches introduced after that line.
    if profile.get("clientDistribution") == "named":
        return PORTABLE_SIGNATURES
    return ()


def verify_signatures(dist: Path, profile: dict, classes_hash: str) -> list[dict[str, object]]:
    verified: list[dict[str, object]] = []
    for name, asset, marker in required_signatures(profile):
        path = require_nonempty(dist / asset)
        if not contains_marker(path, marker):
            raise RuntimeError(f"portable {asset} is missing build signature {name}")
        signature: dict[str, object] = {
            "name": name,
            "asset": asset,
            "marker": marker.decode("ascii"),
            "verified": True,
        }
        if asset == "classes.js":
            signature["sha256"] = classes_hash
        verified.append(signature)
    return verified


# A client on the renderpearl render API (26.3+) compiles its shaders with the
# WebAssembly shader toolchain (PLAN D5, contract C7).  postprocess-index-html.py
# tags such a page with one loader <script> in <head>, and build-teavm.sh
# publishes the loader, the four emscripten modules and the toolchain manifest
# (sizes, sha256, pinned sources) next to index.html.  The portable page embeds
# all of them: the modules become Blob URLs published through
# window.__gaiusShaderToolchainUrls, and the loader runs inline after the
# portable bootstrap, so it finds the URLs once the embedded assets are ready.
# A 26.2 page has no loader tag and its portable output is unchanged.
SHADER_TOOLCHAIN_LOADER = "gaius-shader-toolchain.js"
SHADER_TOOLCHAIN_MANIFEST = "gaius-shader-toolchain.json"
SHADER_TOOLCHAIN_MANIFEST_SCHEMA = 1
SHADER_TOOLCHAIN_IDENTITY_ROLE = "shader-toolchain"
SHADER_TOOLCHAIN_MODULES = (
    ("shadercJs", "gaius-shaderc.js", "text/javascript"),
    ("shadercWasm", "gaius-shaderc.wasm", "application/wasm"),
    ("spvcJs", "gaius-spvc.js", "text/javascript"),
    ("spvcWasm", "gaius-spvc.wasm", "application/wasm"),
)
# The token order of postprocess-index-html.py (SHADER_TOOLCHAIN_FILES).
SHADER_TOOLCHAIN_FILES = (SHADER_TOOLCHAIN_LOADER,) + tuple(
    name for _key, name, _mime in SHADER_TOOLCHAIN_MODULES
)
SHADER_TOOLCHAIN_CLASSES_MARKER = b"__gaiusShaderToolchain"
SHADER_TOOLCHAIN_TAG = re.compile(
    r'  <script data-gaius-shader-toolchain data-profile="([^"]*)" '
    r'src="gaius-shader-toolchain\.js\?v=([0-9a-f]+)"></script>\n'
)


def shader_toolchain_token(directory: Path) -> str:
    """The loader URL token of postprocess-index-html.py (content_token)."""
    digest = hashlib.sha256()
    for name in SHADER_TOOLCHAIN_FILES:
        with (directory / name).open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()[:16]


def gzip_bytes(value: bytes) -> bytes:
    """Deterministic gzip for assets the dist does not ship compressed."""
    return gzip.compress(value, compresslevel=9, mtime=0)


def load_shader_toolchain(
    dist: Path,
    index: str,
    profile: dict,
    classes_js: Path,
    root: Path,
    common_identity: dict[str, object],
    frozen: bool = False,
    carried_from: Path | None = None,
) -> dict[str, object] | None:
    """Verify and load the shader toolchain of a page that carries its loader tag.

    Returns None for a page without the tag (26.2).  A client whose classes.js
    calls the toolchain but whose page does not load it can never start, so
    that combination is an error rather than a plain portable build.
    """
    tags = SHADER_TOOLCHAIN_TAG.findall(index)
    if not tags:
        if contains_marker(classes_js, SHADER_TOOLCHAIN_CLASSES_MARKER):
            raise RuntimeError(
                "portable classes.js calls the WebAssembly shader toolchain but "
                "index.html has no gaius-shader-toolchain.js loader"
            )
        return None
    if len(tags) != 1:
        raise RuntimeError("portable index.html has more than one shader toolchain loader")
    tag_profile, token = tags[0]
    if tag_profile != profile["id"]:
        raise RuntimeError(
            f"portable shader toolchain loader profile {tag_profile!r} does not match "
            f"active profile {profile['id']!r}"
        )
    for name in SHADER_TOOLCHAIN_FILES + (SHADER_TOOLCHAIN_MANIFEST,):
        require_nonempty(dist / name)
    if shader_toolchain_token(dist) != token:
        raise RuntimeError(
            "portable shader toolchain files do not match the loader token of index.html"
        )
    manifest_path = dist / SHADER_TOOLCHAIN_MANIFEST
    try:
        toolchain_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"shader toolchain manifest is invalid: {manifest_path}") from exc
    if (
        not isinstance(toolchain_manifest, dict)
        or toolchain_manifest.get("schema") != SHADER_TOOLCHAIN_MANIFEST_SCHEMA
        or not isinstance(toolchain_manifest.get("files"), dict)
        or not isinstance(toolchain_manifest.get("sources"), dict)
        or not isinstance(toolchain_manifest.get("emscripten"), str)
    ):
        raise RuntimeError(f"shader toolchain manifest is incompatible: {manifest_path}")
    recorded_files = toolchain_manifest["files"]
    file_records: dict[str, dict[str, object]] = {}
    for name in SHADER_TOOLCHAIN_FILES:
        path = dist / name
        record = recorded_files.get(name)
        actual = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
        if record != actual:
            raise RuntimeError(
                f"shader toolchain file {name} does not match {SHADER_TOOLCHAIN_MANIFEST}"
            )
        file_records[name] = actual
    loader = (dist / SHADER_TOOLCHAIN_LOADER).read_text(encoding="utf-8")
    lowered = loader.lower()
    if "</script" in lowered or "<!--" in lowered:
        raise RuntimeError("shader toolchain loader cannot be inlined into the portable page")
    modules: dict[str, dict[str, object]] = {}
    payload: dict[str, bytes] = {}
    for key, name, _mime in SHADER_TOOLCHAIN_MODULES:
        raw = (dist / name).read_bytes()
        compressed = gzip_bytes(raw)
        payload[key] = compressed
        modules[name] = {
            "rawSha256": file_records[name]["sha256"],
            "rawBytes": file_records[name]["bytes"],
            "gzipSha256": sha256_bytes(compressed),
            "gzipBytes": len(compressed),
        }
    return {
        "version": token,
        "loader": loader,
        "payload": payload,
        "manifest": {
            "version": token,
            "loader": {"name": SHADER_TOOLCHAIN_LOADER, **file_records[SHADER_TOOLCHAIN_LOADER]},
            "manifest": {
                "name": SHADER_TOOLCHAIN_MANIFEST,
                "sha256": sha256_file(manifest_path),
                "bytes": manifest_path.stat().st_size,
                "emscripten": toolchain_manifest["emscripten"],
                "sources": toolchain_manifest["sources"],
            },
            "modules": modules,
            "build": carried_toolchain_identity(dist, carried_from)
            if frozen and carried_from is not None
            and not build_identity.sidecar_path(manifest_path).is_file()
            else verified_component_identity(
                root, manifest_path, SHADER_TOOLCHAIN_IDENTITY_ROLE, common_identity, frozen=frozen
            ),
        },
    }


def carried_toolchain_identity(dist: Path, wasm_dist: Path) -> dict[str, object]:
    """Identity of a toolchain a frozen input carries unchanged from the Wasm client dist.

    The 26.2 and 1.21.11 frozen JavaScript clients predate the shader toolchain, and their Wasm
    clients ship it without an identity sidecar; staging copies the files next to the frozen
    index.html. Accept them only when every file is byte-identical to the Wasm client's copy.
    """
    files = {}
    for name in SHADER_TOOLCHAIN_FILES + (SHADER_TOOLCHAIN_MANIFEST,):
        carried = dist / name
        original = wasm_dist / name
        if not original.is_file() or carried.read_bytes() != original.read_bytes():
            raise RuntimeError(f"portable shader toolchain file {name} does not match the Wasm client dist")
        files[name] = sha256_file(carried)
    return {"role": SHADER_TOOLCHAIN_IDENTITY_ROLE, "carriedFromWasmClient": True, "files": files}


def shader_toolchain_bootstrap(toolchain: dict[str, object] | None, profile: dict) -> tuple[str, str, str]:
    """The three portable-bootstrap fragments of the toolchain (empty without one)."""
    if toolchain is None:
        return "", "", ""
    version = json.dumps(toolchain["version"], ensure_ascii=True)
    profile_attribute = json.dumps(profile["id"], ensure_ascii=True)
    globals_fragment = (
        "      // The inline shader toolchain loader below (contract C7) reads the\n"
        "      // Blob URLs from this object once __gaiusPortableAssetsReady resolves.\n"
        f"      window.__gaiusShaderToolchainUrls = {{version: {version}}};\n"
    )
    ready_lines = ["        const shaderToolchainBlobs = await Promise.all([\n"]
    for key, _name, mime in SHADER_TOOLCHAIN_MODULES:
        ready_lines.append(
            f"          decodeAsset({json.dumps(shader_toolchain_asset(key))}, "
            f"{{gunzip: true, blobType: {json.dumps(mime)}}}),\n"
        )
    ready_lines.append("        ]);\n")
    ready_lines.append("        Object.assign(window.__gaiusShaderToolchainUrls, {\n")
    for index, (key, _name, _mime) in enumerate(SHADER_TOOLCHAIN_MODULES):
        ready_lines.append(
            f"          {key}: URL.createObjectURL(shaderToolchainBlobs[{index}]),\n"
        )
    ready_lines.append("        });\n")
    loader_fragment = (
        f"  <script data-gaius-shader-toolchain data-profile={profile_attribute} "
        f"data-gaius-portable-inline=\"1\">\n"
        f"{toolchain['loader']}"
        + ("" if isinstance(toolchain.get("loader"), str) and toolchain["loader"].endswith("\n") else "\n")
        + "  </script>\n"
    )
    return globals_fragment, "".join(ready_lines), loader_fragment


def shader_toolchain_asset(key: str) -> str:
    return f"shader-{key}"


# The v0.4 runtime the launcher includes through one tag (port/web/boot/gaius-boot.js). A
# portable page has no sibling files, so the tag is replaced by the boot script and every
# module it would load, inlined in load order; the kernel worker becomes an inert text block
# the kernel runtime starts from a Blob URL.
BOOT_TAG = '  <script data-gaius-boot="v1" src="gaius-boot.js"></script>\n'
BOOT_SOURCE = Path("port/web/boot/gaius-boot.js")
KERNEL_WORKER_SOURCE = Path("port/web/kernels/kernel-worker.js")
KERNEL_RUNTIME_MODULES = ("kernels/kernel-mirror.js", "kernels/kernel-policy.js", "kernels/kernel-runtime.js")
QUALITY_MODULES = (
    "runtime/quality/gpu-caps.js",
    "runtime/quality/quality-profile.js",
    "runtime/quality/gl-pass.js",
    "runtime/quality/upscaler.js",
    "runtime/quality/post-chain.js",
    "runtime/quality/quality-runtime.js",
)
# The WebGPU backend's page scripts (gaius-boot.js WEBGPU_SCRIPTS), inlined after the quality layer,
# by their source; the translator module (WEBGPU_TRANSLATOR) travels in the payload when the dist
# staged one (stage-web-runtime.py) and reaches the boot script as window.__gaiusPortableWebGpu.
WEBGPU_MODULES = {
    "runtime/webgpu/gpu-glue.js": Path("port/web/runtime/webgpu/gpu-glue.js"),
    "runtime/webgpu/shaderxlat-job.js": Path("port/native/golden/shaders/shaderxlat-job.js"),
    "runtime/webgpu/quality-post.js": Path("port/web/runtime/webgpu/quality-post.js"),
}
WEBGPU_TRANSLATOR = "runtime/webgpu/gaius-shaderxlat.wasm"
WEBGPU_TRANSLATOR_ASSET = "webgpu-shaderxlat"
# Scripts the integrated server Worker imports: the worldgen job codec and facade, and the kernel
# policy and runtime for ?worldgenKernelHost=worker. Inert text on the page, never executed there.
SERVER_WORKER_MODULES = (
    "kernels/kernel-mirror.js",
    "kernels/kernel-policy.js",
    "kernels/kernel-runtime.js",
    "kernels/worldgen-job.js",
    "kernels/worldgen-kernel.js",
)


def kernel_job_modules(web: Path) -> list[str]:
    """Kernel job codecs (port/web/kernels/*-job.js), whatever kernels exist."""
    return sorted(
        f"kernels/{path.name}" for path in (web / "kernels").glob("*-job.js") if path.is_file()
    )


def inline_safe(text: str, label: str) -> str:
    lowered = text.lower()
    if "</script" in lowered or "<!--" in lowered:
        raise RuntimeError(f"{label} cannot be inlined into the portable page")
    return text if text.endswith("\n") else text + "\n"


def runtime_bootstrap(
    root: Path, *, wasm_client: dict[str, object] | None = None, dist: Path | None = None
) -> tuple[str, dict[str, str]]:
    """The inline replacement of the launcher's gaius-boot.js tag and its module hashes.

    With wasm_client (load_wasm_client) the page announces its WebAssembly GC client to the
    boot script (window.__gaiusPortableRuntimes) and carries the client's runtime scripts as inert
    text the portable bootstrap turns into Blob URLs.
    """
    web = root / "port" / "web"
    boot = root / BOOT_SOURCE
    if not boot.is_file():
        return "", {}
    modules: list[tuple[str, str]] = []
    for name in KERNEL_RUNTIME_MODULES + tuple(kernel_job_modules(web)) + QUALITY_MODULES:
        path = web / name
        if name == "kernels/kernel-mirror.js" and dist is not None and (dist / name).is_file():
            path = dist / name
        if path.is_file():
            modules.append((name, inline_safe(path.read_text(encoding="utf-8"), name)))
    for name, relative in WEBGPU_MODULES.items():
        path = root / relative
        if path.is_file():
            modules.append((name, inline_safe(path.read_text(encoding="utf-8"), name)))
    hashes = {name: sha256_bytes(source.encode("utf-8")) for name, source in modules}
    inline_names = json.dumps({name: True for name, _source in modules}, ensure_ascii=True,
                              sort_keys=True, separators=(",", ":"))
    runtimes = ""
    if wasm_client is not None:
        wasm_runtimes = {"client": WASM_CLIENT_MODULE}
        if wasm_client.get("server") is not None:
            wasm_runtimes["server"] = WASM_SERVER_MODULE
        announced = json.dumps({"wasm": wasm_runtimes}, ensure_ascii=True,
                               sort_keys=True, separators=(",", ":"))
        runtimes = f"    window.__gaiusPortableRuntimes = {announced};\n"
    parts = [
        '  <script data-gaius-boot="v1" data-gaius-portable-inline="1">\n'
        "    window.__gaiusBootPortable = true;\n"
        f"    window.__gaiusBootInline = {inline_names};\n"
        f"{runtimes}"
        "  </script>\n"
    ]
    if wasm_client is not None:
        relay_protocol = wasm_client.get("relayProtocol")
        if relay_protocol is not None:
            hashes["wasm/relay-protocol.js"] = sha256_bytes(relay_protocol.encode("utf-8"))
            parts.append('  <script data-gaius-relay-protocol>\n' + relay_protocol + "  </script>\n")
        for name, source in wasm_client["scripts"].items():
            hashes[f"wasm/{name}"] = sha256_bytes(source.encode("utf-8"))
            parts.append(
                f'  <script type="text/plain" data-gaius-wasm-script="{name}">\n{source}  </script>\n'
            )
    for name, source in modules:
        parts.append(f'  <script data-gaius-boot-module="{name}">\n{source}  </script>\n')
    # The integrated server Worker imports these from the page's gaius-kernel-port message
    # (gaius-boot.js serverWorkerKernelScripts): a Worker started from a Blob has no sibling
    # files, so the page carries them as inert text the boot script hands over as sources.
    for name in SERVER_WORKER_MODULES:
        path = web / name
        if not path.is_file():
            continue
        source = inline_safe(path.read_text(encoding="utf-8"), name)
        hashes[f"server-worker/{name}"] = sha256_bytes(source.encode("utf-8"))
        parts.append(
            f'  <script type="text/plain" data-gaius-worker-script="{name}">\n{source}  </script>\n'
        )
    worker = root / KERNEL_WORKER_SOURCE
    if worker.is_file():
        worker_text = inline_safe(worker.read_text(encoding="utf-8"), "kernel-worker.js")
        hashes["kernels/kernel-worker.js"] = sha256_bytes(worker_text.encode("utf-8"))
        parts.append(
            '  <script type="text/plain" id="gaius-kernel-worker-source">\n' + worker_text + "  </script>\n"
        )
    boot_text = inline_safe(boot.read_text(encoding="utf-8"), "gaius-boot.js")
    hashes["gaius-boot.js"] = sha256_bytes(boot_text.encode("utf-8"))
    parts.append('  <script data-gaius-boot="v1" data-gaius-portable-inline="1">\n' + boot_text + "  </script>\n")
    return "".join(parts), hashes


# The parts of runtime_bootstrap() a dual portable page needs in front of its bootstrap: the
# runtime descriptor tag and the relay protocol declaration, never the boot source itself.
PORTABLE_RUNTIME_DESCRIPTOR = re.compile(
    r'  <script data-gaius-boot="v1" data-gaius-portable-inline="1">\n'
    r'    window\.__gaiusBootPortable = true;\n.*?</script>\n'
    r'|  <script data-gaius-relay-protocol>\n.*?</script>\n',
    re.DOTALL,
)


def load_portable_kernels(dist: Path, payload: "PortablePayload") -> tuple[dict, dict]:
    """Embeds the wasm kernels listed in dist/kernels/kernels.json (both builds of each).

    Returns the page index ({name: {kinds, memory, variants: {variant: asset}}}) and the
    manifest record; both are empty when the dist carries no kernels (the page then keeps
    every vanilla path).
    """
    manifest_path = dist / "kernels" / "kernels.json"
    if not manifest_path.is_file():
        return {}, {}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"kernel manifest is invalid: {manifest_path}") from exc
    kernels = manifest.get("kernels") if isinstance(manifest, dict) else None
    if not isinstance(kernels, dict):
        raise RuntimeError(f"kernel manifest has no kernels: {manifest_path}")
    index: dict[str, dict[str, object]] = {}
    record: dict[str, dict[str, object]] = {}
    for name in sorted(kernels):
        entry = kernels[name]
        if not re.fullmatch(r"[a-z0-9_]+", name) or not isinstance(entry, dict):
            raise RuntimeError(f"kernel manifest entry is invalid: {name}")
        variants: dict[str, str] = {}
        variant_records: dict[str, dict[str, object]] = {}
        for variant in ("simd", "baseline"):
            spec = (entry.get("variants") or {}).get(variant)
            if not isinstance(spec, dict):
                continue
            path = require_nonempty(dist / "kernels" / str(spec.get("file", "")))
            raw = path.read_bytes()
            if spec.get("sha256") not in (None, sha256_bytes(raw)):
                raise RuntimeError(f"kernel {path.name} does not match kernels.json")
            asset = f"kernel-{name}-{variant}"
            compressed = gzip_bytes(raw)
            payload.add(asset, compressed, gzipped=True)
            variants[variant] = asset
            variant_records[variant] = {
                "rawSha256": sha256_bytes(raw), "rawBytes": len(raw),
                "gzipSha256": sha256_bytes(compressed), "gzipBytes": len(compressed),
            }
        if not variants:
            continue
        index[name] = {"kinds": list(entry.get("kinds") or []), "variants": variants,
                       "sha256": {variant: record["rawSha256"] for variant, record in variant_records.items()}}
        if isinstance(entry.get("memory"), dict):
            index[name]["memory"] = entry["memory"]
        record[name] = variant_records
    return index, record


def load_portable_webgpu(dist: Path, payload: "PortablePayload") -> tuple[str, dict]:
    """Embeds the WebGPU translator the dist staged. Returns the bootstrap line that announces it
    (window.__gaiusPortableWebGpu) and the manifest record; both are empty without one."""
    path = dist / WEBGPU_TRANSLATOR
    if not path.is_file():
        return "", {}
    raw = require_nonempty(path).read_bytes()
    compressed = gzip_bytes(raw)
    payload.add(WEBGPU_TRANSLATOR_ASSET, compressed, gzipped=True)
    raw_sha256 = sha256_bytes(raw)
    line = (
        "      window.__gaiusPortableWebGpu = {translator: {sha256: "
        + json.dumps(raw_sha256) + ", load: () => decodeAsset("
        + json.dumps(WEBGPU_TRANSLATOR_ASSET) + ", {gunzip: true})}};\n"
    )
    record = {"rawSha256": raw_sha256, "rawBytes": len(raw),
              "gzipSha256": sha256_bytes(compressed), "gzipBytes": len(compressed)}
    return line, record


def manifest_path_for(output: Path) -> Path:
    if output.name == "Gaius.html":
        return output.with_name(MANIFEST_NAME)
    if output.suffix:
        return output.with_name(f"{output.stem}.manifest.json")
    return output.with_name(f"{output.name}.manifest.json")


def verified_component_identity(
    root: Path,
    artifact: Path,
    role: str,
    expected: dict[str, object],
    frozen: bool = False,
) -> dict[str, object]:
    sidecar = build_identity.sidecar_path(artifact)
    if frozen:
        record = json.loads(sidecar.read_text(encoding="utf-8"))
        build_identity.check_frozen(root, role, artifact, sidecar)
        frozen_profile = record.get("profile", {})
        expected_profile = expected.get("profile", {})
        for key in ("id", "path", "protocolVersion", "worldVersion", "storage"):
            if frozen_profile.get(key) != expected_profile.get(key):
                raise RuntimeError(f"portable component {artifact.name} has incompatible profile {key}")
    else:
        record = build_identity.verify_sidecar(root, role, artifact, sidecar,
                                               expected_common=expected)
    return {
        "role": role,
        "identitySha256": record["identitySha256"],
        "compatibilitySha256": record["compatibilitySha256"],
        "sidecarSha256": sha256_file(sidecar),
        "sidecarBytes": sidecar.stat().st_size,
    }


def verified_compiler_profile(
    root: Path,
    artifact: Path,
    role: str,
    pom: Path,
    resources: list[Path],
    frozen: bool = False,
) -> dict[str, object]:
    sidecar = compiler_profile.default_output(artifact)
    if frozen and (not sidecar.is_file() or not pom.is_file()):
        # Frozen release dists may retain artifact identity sidecars without
        # the build workspace and compiler POM used to create them.
        return {"frozen": True, "sidecar": str(sidecar)}
    expected = compiler_profile.create_record(
        root,
        role,
        artifact,
        pom,
        resources,
        True,
    )
    try:
        actual = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"compiler profile is missing or invalid: {sidecar}") from exc
    if actual != expected:
        raise RuntimeError(f"compiler profile does not match current inputs: {sidecar}")
    return {
        "profileSha256": expected["profileSha256"],
        "sidecarSha256": sha256_file(sidecar),
        "sidecarBytes": sidecar.stat().st_size,
        "optimizationLevel": expected["compiler"]["optimizationLevel"],
        "minifying": expected["compiler"]["minifying"],
        "shortFileNames": expected["compiler"]["shortFileNames"],
        "assertionsRemoved": expected["compiler"]["assertionsRemoved"],
    }


# --- file:// payloads and the WebAssembly GC client (v0.4.0 design D13) ----------------------------
#
# A dist directory opened from disk cannot fetch its own files, but its page can still run
# sibling scripts. write_file_payloads() puts each binary file the page needs next to it as
# <file>.payload.js: the file's bytes (gzip-compressed unless the file already is) as base64
# in globalThis.__gaiusFilePayloadData[<file>]. The page lists them in
# window.__gaiusFilePayloads (postprocess-index-html.py runtime_config_script), and gaius-boot.js
# decodes them (DecompressionStream, no fetch) into the globals the launcher reads for the
# portable page. The portable page itself carries the WebAssembly GC client as one more gaius-b7
# payload (a denser base64 its decoder Worker already inflates) and passes the inflated bytes
# to TeaVM's load(bytes).

FILE_PAYLOAD_STORE = "__gaiusFilePayloadData"
FILE_PAYLOAD_SUFFIX = ".payload.js"
# What the launcher, the shader toolchain and the WebAssembly GC client read from disk.
FILE_PAYLOAD_FILES = (
    "classes.wasm",
    "vanilla-assets.pack.gz",
    "gaius-shaderc.wasm",
    "gaius-shaderc.js",
    "gaius-spvc.wasm",
    "gaius-spvc.js",
    "gaius-hotpath.wasm",
    # ?gpu=webgpu: the SPIR-V -> WGSL translator gaius-boot.js compiles (WEBGPU_TRANSLATOR).
    "runtime/webgpu/gaius-shaderxlat.wasm",
    # The integrated server Worker of a page opened from disk cannot read its siblings either:
    # BrowserSingleplayerClient hands it its bootstrap, the server of the selected runtime (with
    # the WebAssembly GC runtime scripts) and the worldgen kernel scripts, read from these
    # payloads when a world opens (gaius-boot.js filePayload).
    "singleplayer-server-worker.js",
    "singleplayer-server.wasm",
    "gaius-wasm-gc-runtime.js",
    "gaius-jspi.js",
    "gaius-wasm-worker.js",
    "kernels/worldgen-job.js",
    "kernels/worldgen-kernel.js",
)
WASM_MAGIC = b"\0asm"
# postprocess-index-html.py runtime_config_script
RUNTIME_CONFIG_BLOCK = re.compile(r'  <script data-gaius-runtimes="v1">\n.*?  </script>\n', re.DOTALL)
RELAY_PROTOCOL_BLOCK = re.compile(r'  <script data-gaius-relay-protocol>\n.*?  </script>\n', re.DOTALL)
WASM_CLIENT_MODULE = "classes.wasm"
WASM_CLIENT_ASSET = "wasmclient"
# The WebAssembly GC integrated server (?serverRuntime=wasm), embedded when the wasm dist has it:
# the portable bootstrap passes its gzip bytes to the Worker (serverWasmGzipData) and the
# Worker imports the runtime scripts from their inert copies (gaius-boot.js serverRuntimeConfig).
WASM_SERVER_MODULE = "singleplayer-server.wasm"
WASM_SERVER_ASSET = "wasmserver"
WASM_SERVER_SCRIPTS = ("gaius-wasm-worker.js",)
# The client's runtime scripts, from the wasm dist: the patched TeaVM loader (build-wasmgc.sh),
# the JSPI thread host and the client loader (stage-web-runtime.py).
WASM_CLIENT_SCRIPTS = ("gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-boot.js")


def file_payload_script(name: str, payload: bytes) -> str:
    """The <file>.payload.js text (ASCII): a getter for the base64 text kept in a comment.

    gaius-boot.js reads globalThis.__gaiusFilePayloadData[<file>] once, deletes it and decodes
    the text. A string literal holding the text would be a second copy of it on the JS heap next
    to the script source, garbage that stays until the next full collection: about 180 MB more
    at the first title screen of the 26.3 release dist opened from disk, for 110 MB of payload
    text. The getter slices the text out of the function's source instead (the source is the
    only copy). Base64 has no "*/".
    """
    store = f"globalThis.{FILE_PAYLOAD_STORE}"
    key = json.dumps(name, ensure_ascii=True)
    encoded = base64.b64encode(payload).decode("ascii")
    chunks = [encoded[i:i + 4 * 1024 * 1024] for i in range(0, len(encoded), 4 * 1024 * 1024)]
    parts = "".join(f"a.push(\"{chunk}\");" for chunk in chunks)
    return (f"(function(s,k,a){{{parts}Object.defineProperty(s,k,{{configurable:true,enumerable:true,"
            f"get:function(){{return a.join(\"\");}}}});}})"
            f"({store}||({store}={{}}),{key},[]);\n")


def compressed_copy(path: Path, raw: bytes) -> bytes:
    """The gzip bytes of a file: its .gz sibling when that inflates to the file, else new ones."""
    sibling = path.with_name(path.name + ".gz")
    if sibling.is_file():
        compressed = sibling.read_bytes()
        try:
            if gzip.decompress(compressed) == raw:
                return compressed
        except (OSError, EOFError, ValueError):
            pass
    return gzip_bytes(raw)


def write_file_payloads(
    dist: Path, names: tuple[str, ...] = FILE_PAYLOAD_FILES
) -> dict[str, dict[str, object]]:
    """Writes <file>.payload.js next to every listed file the dist has; returns the page index."""
    index: dict[str, dict[str, object]] = {}
    for name in names:
        path = dist / name
        if not path.is_file() or path.stat().st_size == 0:
            continue
        raw = path.read_bytes()
        already_compressed = name.endswith(".gz")
        payload = raw if already_compressed else compressed_copy(path, raw)
        script = f"{name}{FILE_PAYLOAD_SUFFIX}"
        write_text_atomically(dist / script, file_payload_script(name, payload))
        index[name] = {
            "script": script,
            "bytes": len(raw),
            # Whether the payload is the gzip of the file (a .gz file is carried as it is).
            "gzip": not already_compressed,
            "sha256": sha256_bytes(raw),
        }
    return index


def load_wasm_client(wasm_dist: Path, wasm_server_dist: Path | None = None) -> dict[str, object]:
    """The WebAssembly GC client embedded by a portable page."""
    module_path = require_nonempty(wasm_dist / WASM_CLIENT_MODULE)
    module = module_path.read_bytes()
    if module[:4] != WASM_MAGIC:
        raise RuntimeError(f"{module_path} is not a WebAssembly module")
    compressed = compressed_copy(module_path, module)
    scripts: dict[str, str] = {}
    for name in WASM_CLIENT_SCRIPTS:
        source = require_nonempty(wasm_dist / name).read_text(encoding="utf-8")
        scripts[name] = inline_safe(source, name)
    relay_protocol = None
    relay_protocol_path = wasm_dist / "relay-protocol.js"
    if relay_protocol_path.is_file():
        relay_protocol = inline_safe(require_nonempty(relay_protocol_path).read_text(encoding="utf-8"),
                                     "relay-protocol.js")
    server = None
    server_path = (wasm_server_dist or wasm_dist) / WASM_SERVER_MODULE
    if server_path.is_file() and server_path.stat().st_size > 0:
        server_module = server_path.read_bytes()
        if server_module[:4] != WASM_MAGIC:
            raise RuntimeError(f"{server_path} is not a WebAssembly module")
        server_compressed = compressed_copy(server_path, server_module)
        for name in WASM_SERVER_SCRIPTS:
            source = require_nonempty((wasm_server_dist or wasm_dist) / name).read_text(encoding="utf-8")
            scripts[name] = inline_safe(source, name)
        server = {
            "gzip": server_compressed,
            "manifest": {
                "rawSha256": sha256_bytes(server_module),
                "rawBytes": len(server_module),
                "gzipSha256": sha256_bytes(server_compressed),
                "gzipBytes": len(server_compressed),
            },
        }
    return {
        "gzip": compressed,
        "scripts": scripts,
        "relayProtocol": relay_protocol,
        "server": server,
        "manifest": {
            "rawSha256": sha256_bytes(module),
            "rawBytes": len(module),
            "gzipSha256": sha256_bytes(compressed),
            "gzipBytes": len(compressed),
            "scripts": {
                name: sha256_bytes(source.encode("utf-8")) for name, source in scripts.items()
            },
        },
    }


# Applied to PORTABLE_BOOTSTRAP for a page with a WebAssembly GC client. The portable bootstrap
# runs before boot selection and prepares only the embedded Wasm payload.
PORTABLE_WASM_CLIENT = r'''      // Runtime selection is published by gaius-boot.js after this bootstrap runs.
      // Prepare the embedded Wasm payload first; boot performs the Wasm capability gate.
      const portableWasmClient = !!(window.__gaiusPortableRuntimes &&
        window.__gaiusPortableRuntimes.wasm);
      if (portableWasmClient) {
        // Keep logical dist names available to boot paths that inspect the site manifest.
        // The aliases point at compressed portable payloads, so these lookups never fetch a
        // content-hashed sibling file from file://.
        const portablePayloadBytes = window.__gaiusPortablePayloadBytes ||
          (window.__gaiusPortablePayloadBytes = {});
        portablePayloadBytes["classes.wasm"] =
          decodeAsset("__GAIUS_WASM_CLIENT_ASSET__", {gunzip: true, parallel: true});
        if (portablePayload.assets && portablePayload.assets.__GAIUS_WASM_SERVER_ASSET__) {
          portablePayloadBytes["singleplayer-server.wasm"] =
            decodeAsset("__GAIUS_WASM_SERVER_ASSET__", {gunzip: false});
        }
        const wasmScriptUrl = (name) => {
          const element = document.querySelector('script[type="text/plain"][data-gaius-wasm-script="' + name + '"]');
          if (!element || !element.textContent) throw new Error("portable WebAssembly GC script is missing: " + name);
          return URL.createObjectURL(new Blob([element.textContent], {type: "text/javascript"}));
        };
        window.__gaiusWasmSource = decodeAsset("__GAIUS_WASM_CLIENT_ASSET__", {gunzip: true, parallel: true});
        window.__gaiusWasmRuntimeUrl = wasmScriptUrl("gaius-wasm-gc-runtime.js");
        window.__gaiusJspiUrl = wasmScriptUrl("gaius-jspi.js");
        window.__gaiusClassesUrl = wasmScriptUrl("gaius-wasm-boot.js");
      }
'''
PORTABLE_CLASSES_DECODE = (
    '      const classesBlobPromise = decodeAsset("classes", {gunzip: true, blobType: "text/javascript", parallel: true});\n'
)
PORTABLE_CLASSES_URL = "        window.__gaiusClassesUrl = URL.createObjectURL(classesBlob);\n"


def apply_portable_wasm_client(bootstrap: str) -> str:
    """The bootstrap of a page that embeds the WebAssembly GC client (see PORTABLE_WASM_CLIENT)."""
    for anchor in (PORTABLE_CLASSES_DECODE, PORTABLE_CLASSES_URL):
        if bootstrap.count(anchor) != 1:
            raise RuntimeError("portable bootstrap WebAssembly GC anchor was not found exactly once")
    block = (PORTABLE_WASM_CLIENT
             .replace("__GAIUS_WASM_CLIENT_ASSET__", WASM_CLIENT_ASSET)
             .replace("__GAIUS_WASM_SERVER_ASSET__", WASM_SERVER_ASSET))
    bootstrap = bootstrap.replace(
        PORTABLE_CLASSES_DECODE,
        block + PORTABLE_CLASSES_DECODE.replace(
            "const classesBlobPromise = decodeAsset(",
            "const classesBlobPromise = portableWasmClient ? Promise.resolve(null) : decodeAsset(",
        ),
        1,
    )
    return bootstrap.replace(
        PORTABLE_CLASSES_URL,
        "        if (classesBlob) window.__gaiusClassesUrl = URL.createObjectURL(classesBlob);\n",
        1,
    )


# The portable bootstrap. A plain string with __GAIUS_PORTABLE_*__ placeholders (no f-string, so
# the JavaScript keeps its own braces). It runs after the payload elements were parsed.
PORTABLE_BOOTSTRAP = r'''  <script data-gaius-portable="1">
    (() => {
      const portableManifest = __GAIUS_PORTABLE_MANIFEST__;
      const portablePayload = __GAIUS_PORTABLE_PAYLOAD__;
      const portableKernelIndex = __GAIUS_PORTABLE_KERNELS__;
      const workerSource = __GAIUS_PORTABLE_WORKER_SOURCE__;
      const embeddedRelayNodes = __GAIUS_PORTABLE_RELAY_NODES__;

      // Portable payload decoder. New pages use strict ASCII gaius-b64-v1; the b7 branch keeps
      // already-downloaded v0.4 pages readable. Each asset is SHA-256 checked before gzip
      // inflation completes, so charset/editor corruption is reported with the asset name.
      function gaiusPortableDecoder(scope) {
        const table = new Uint8Array(256);
        for (let code = 0; code < 128; code++) table[code] = code;
        table[0x80] = 0x00;
        table[0x81] = 0x0A;
        table[0x82] = 0x0D;
        table[0x83] = 0x3C;
        const jobs = new Map();
        function decodePart(text, job) {
          let out;
          if (job.encoding === "gaius-b64-v1") {
            const binary = scope.atob(text);
            out = new Uint8Array(binary.length);
            for (let index = 0; index < binary.length; index++) out[index] = binary.charCodeAt(index);
          } else {
            const blocks = text.length >> 3;
            out = new Uint8Array(blocks * 7);
            for (let i = 0, j = 0; i < blocks * 8; i += 8, j += 7) {
              const g0 = table[text.charCodeAt(i)];
              const g1 = table[text.charCodeAt(i + 1)];
              const g2 = table[text.charCodeAt(i + 2)];
              const g3 = table[text.charCodeAt(i + 3)];
              const g4 = table[text.charCodeAt(i + 4)];
              const g5 = table[text.charCodeAt(i + 5)];
              const g6 = table[text.charCodeAt(i + 6)];
              const g7 = table[text.charCodeAt(i + 7)];
              out[j] = (g0 << 1) | (g1 >> 6);
              out[j + 1] = ((g1 & 63) << 2) | (g2 >> 5);
              out[j + 2] = ((g2 & 31) << 3) | (g3 >> 4);
              out[j + 3] = ((g3 & 15) << 4) | (g4 >> 3);
              out[j + 4] = ((g4 & 7) << 5) | (g5 >> 2);
              out[j + 5] = ((g5 & 3) << 6) | (g6 >> 1);
              out[j + 6] = ((g6 & 1) << 7) | g7;
            }
          }
          const remaining = Math.max(0, job.bytes - job.received);
          if (job.encoding === "gaius-b64-v1" && out.length > remaining) {
            throw new Error("portable asset " + job.name + " exceeds its declared length");
          }
          const count = Math.min(out.length, remaining);
          const chunk = count === out.length ? out : out.subarray(0, count);
          job.received += count;
          job.hashChunks.push(chunk);
          return chunk;
        }
        async function verify(job) {
          if (!job.sha256) return;
          if (!scope.crypto || !scope.crypto.subtle) throw new Error("portable asset " + job.name + " sha256 verification unavailable");
          const joined = new Uint8Array(job.hashBytes);
          let at = 0;
          for (const chunk of job.hashChunks) { joined.set(chunk, at); at += chunk.length; }
          const digest = new Uint8Array(await scope.crypto.subtle.digest("SHA-256", joined));
          let actual = "";
          for (const byte of digest) actual += byte.toString(16).padStart(2, "0");
          if (actual !== job.sha256) {
            throw new Error("portable asset " + job.name + " sha256 mismatch (expected " + job.sha256 + ", got " + actual + ")");
          }
        }
        async function finish(job) {
          let value;
          if (job.received !== job.bytes) throw new Error("portable asset is truncated");
          await verify(job);
          if (job.writer) {
            await job.writer.close();
            value = await job.result;
          } else {
            const joined = new Uint8Array(job.received);
            let at = 0;
            for (const chunk of job.chunks) {
              joined.set(chunk, at);
              at += chunk.length;
            }
            value = job.blobType ? new Blob([joined], {type: job.blobType}) : joined.buffer;
          }
          return value;
        }
        scope.onmessage = (event) => {
          const message = event.data;
          const job = jobs.get(message.id);
          try {
            if (message.type === "begin") {
              const next = {name: message.name, encoding: message.encoding, sha256: message.sha256,
                bytes: message.bytes, received: 0, hashBytes: 0, hashChunks: [], chunks: [], blobType: message.blobType,
                writer: null, result: null};
              if (message.gunzip) {
                const stream = new DecompressionStream("gzip");
                next.writer = stream.writable.getWriter();
                const response = new Response(stream.readable);
                next.result = message.blobType
                  ? response.blob().then((blob) => new Blob([blob], {type: message.blobType}))
                  : response.arrayBuffer();
                next.result.catch(() => {});
              }
              jobs.set(message.id, next);
            } else if (message.type === "part" && job) {
              const bytes = decodePart(message.text, job);
              job.hashBytes += bytes.byteLength;
              if (job.writer) job.writer.write(bytes).catch(() => {});
              else job.chunks.push(bytes);
            } else if (message.type === "end" && job) {
              jobs.delete(message.id);
              finish(job).then((value) => {
                scope.postMessage({type: "done", id: message.id, value},
                  value instanceof ArrayBuffer ? [value] : []);
              }, (error) => {
                scope.postMessage({type: "failed", id: message.id, message: String(error && error.message || error)});
              });
            }
          } catch (error) {
            jobs.delete(message.id);
            scope.postMessage({type: "failed", id: message.id, message: String(error && error.message || error)});
          }
        };
      }

      const decoder = (() => {
        const pending = new Map();
        const deliver = (message) => {
          sent.delete(message.id);
          const entry = pending.get(message.id);
          if (!entry) return;
          pending.delete(message.id);
          if (message.type === "done") entry.resolve(message.value);
          else entry.reject(new Error("Could not unpack the portable Gaius build: " + message.message));
        };
        let post = null;
        const sent = new Map();
        let synchronous = false;
        let worker = null;
        let workerUrl = null;
        const useSynchronousDecoder = () => {
          if (synchronous) return;
          synchronous = true;
          const scope = {atob: globalThis.atob.bind(globalThis), crypto: globalThis.crypto,
            postMessage: (message) => Promise.resolve().then(() => deliver(message))};
          gaiusPortableDecoder(scope);
          post = (message) => {
            const copy = {...message};
            const result = scope.onmessage({data: copy});
            return result;
          };
          for (const [id, messages] of sent) {
            if (!pending.has(id)) continue;
            for (const message of messages) post(message);
          }
        };
        try {
          if (typeof DecompressionStream !== "function") {
            throw new Error("This browser cannot open the portable Gaius build");
          }
          workerUrl = URL.createObjectURL(new Blob(
            ["(" + gaiusPortableDecoder.toString() + ")(self);"], {type: "text/javascript"}));
          worker = new Worker(workerUrl, {name: "gaius-portable-decoder"});
          worker.onmessage = (event) => deliver(event.data);
          worker.onerror = (event) => {
            try { worker.terminate(); } catch (_) {}
            try { URL.revokeObjectURL(workerUrl); } catch (_) {}
            useSynchronousDecoder();
          };
          post = (message) => worker.postMessage(message);
        } catch (_) {
          useSynchronousDecoder();
        }
        let nextId = 1;
        let queue = Promise.resolve();
        const parallelRuns = new Set();
        const cache = new Map();
        // Parts are read in priority order, one asset after another; each part leaves the DOM
        // as soon as it was handed to the decoder.
        function decode(name, options) {
          if (cache.has(name)) return cache.get(name);
          const info = portablePayload.assets[name];
          if (!info) return Promise.reject(new Error("portable asset is missing: " + name));
          const id = nextId++;
          const result = new Promise((resolve, reject) => pending.set(id, {resolve, reject}));
          const run = async () => {
            try {
              const messages = sent.get(id) || [];
              const begin = {type: "begin", id, name, encoding: portablePayload.encoding || "gaius-b7-v1",
                sha256: info.sha256 || null, bytes: info.bytes, gunzip: !!options.gunzip, blobType: options.blobType || null};
              messages.push(begin); sent.set(id, messages); post(begin);
              const parts = document.querySelectorAll('script[data-gaius-asset="' + name + '"]');
              if (parts.length !== info.parts) throw new Error("portable asset " + name + " has missing parts");
              // Unpacked bytes for the boot screen (boot-art.js reads window.__gaiusLoadBytes).
              const meters = window.__gaiusLoadBytes || (window.__gaiusLoadBytes = {});
              const meter = meters["portable:" + name] = {loaded: 0, total: info.bytes};
              for (let index = 0; index < parts.length; index++) {
                const element = parts[index];
                const text = element.textContent;
                meter.loaded = Math.min(info.bytes, meter.loaded + (text.length >> 3) * 7);
                element.textContent = "";
                if (element.parentNode) element.parentNode.removeChild(element);
                const part = {type: "part", id, text};
                messages.push(part); post(part);
                if ((index & 3) === 3) {
                  await new Promise((resolve) => setTimeout(resolve, 0));
                }
              }
              const end = {type: "end", id};
              messages.push(end); post(end);
            } catch (error) {
              deliver({type: "failed", id, message: String(error && error.message || error)});
            }
          };
          // Client code and the core asset pack are independent. Let their DOM-to-worker
          // transfers overlap, while keeping server and optional payloads behind a barrier so
          // they cannot compete with title-critical decoding or retain two completed buffers.
          if (options.parallel === true) {
            const active = Promise.resolve().then(run).then(() => result.catch(() => {}));
            parallelRuns.add(active);
            active.then(() => parallelRuns.delete(active), () => parallelRuns.delete(active));
          } else {
            queue = queue.then(() => Promise.all([...parallelRuns])).then(run, run);
          }
          cache.set(name, result);
          return result;
        }
        return {decode};
      })();
      const decodeAsset = (name, options) => decoder.decode(name, options || {});

      const configuredRelayNodes = Array.isArray(window.__gaiusBridgeUrls)
        ? window.__gaiusBridgeUrls
        : (window.__gaiusBridgeUrls ? [window.__gaiusBridgeUrls] : []);
      window.__gaiusBridgeUrls = embeddedRelayNodes.concat(configuredRelayNodes);
      window.__gaiusPortableManifest = portableManifest;
      window.__gaiusPortablePayload = portablePayload;
      window.__gaiusPortableBuild = true;
      __GAIUS_PORTABLE_TOOLCHAIN_GLOBALS__
      __GAIUS_PORTABLE_WEBGPU__
      if (portableKernelIndex) {
        const kernels = {};
        Object.keys(portableKernelIndex).forEach((name) => {
          const entry = portableKernelIndex[name];
          const variants = {};
          Object.keys(entry.variants).forEach((variant) => {
            const asset = entry.variants[variant];
            variants[variant] = {load: () => decodeAsset(asset, {gunzip: true}), sha256: entry.sha256 && entry.sha256[variant]};
          });
          kernels[name] = {kinds: entry.kinds, memory: entry.memory, variants};
        });
        window.__gaiusPortableKernels = kernels;
      }
      const portableBridgeTrace = window.__gaiusPortableBridgeTrace ||
        (window.__gaiusPortableBridgeTrace = []);
      const portablePendingLocalPorts = window.__gaiusPortablePendingLocalPorts ||
        (window.__gaiusPortablePendingLocalPorts = new Map());
      const tracePortableBridge = (event, detail) => {
        portableBridgeTrace.push(Object.assign({event, at: Date.now()}, detail || {}));
        if (portableBridgeTrace.length > 256) portableBridgeTrace.splice(0, portableBridgeTrace.length - 256);
      };
      const consumePortablePendingLocalPorts = () => {
        const bridge = window.__gaiusNettyBridge || window.__gaiusNettyBridgeBootstrapState;
        if (!bridge || typeof bridge.registerLocalPort !== "function") return false;
        let consumed = false;
        portablePendingLocalPorts.forEach((pending, sessionId) => {
          if (!pending || !pending.port) {
            portablePendingLocalPorts.delete(sessionId);
            return;
          }
          let result = false;
          try {
            result = bridge.registerLocalPort(
              String(sessionId), pending.port, String(pending.launchGeneration || ""));
          } catch (error) {
            tracePortableBridge("registerLocalPort-throw", {
              sessionId: String(sessionId),
              launchGeneration: String(pending.launchGeneration || ""),
              error: String(error && (error.stack || error.message) || error),
            });
          }
          tracePortableBridge("registerLocalPort-result", {
            sessionId: String(sessionId),
            launchGeneration: String(pending.launchGeneration || ""),
            result: !!result,
            bridgeReady: true,
          });
          if (result) {
            portablePendingLocalPorts.delete(sessionId);
            consumed = true;
          }
        });
        return consumed;
      };
      // Keep the generated transport untouched, but normalize the one
      // integrated-server wildcard endpoint at the bridge boundary.  Some
      // TeaVM socket paths resolve the synthetic local name to 0.0.0.0 before
      // invoking the JS bridge; the page registry already contains the active
      // session and its MessagePort by this point.
      // The bridge last recorded as patched: the pump below re-checks it, and tracing every
      // check would flood the 256-entry diagnostic ring with identical entries.
      let portableBridgeTraced = null;
      const installLocalBridgeHostPatch = () => {
        const wrap = (bridge) => {
          if (!bridge || typeof bridge.open !== "function") return false;
          if (bridge.__gaiusLocalHostPatch) return true;
          const originalOpen = bridge.open;
          bridge.open = function(id, host, port) {
            let effectiveHost = host;
            if ((String(host) === "0.0.0.0" || String(host).toLowerCase() === "localhost") && Number(port) === 25565) {
              let session = "";
              const workers = window.__gaiusSingleplayerWorkers;
              if (workers && typeof workers.forEach === "function") workers.forEach((worker, key) => {
                if (!session && /^[a-f0-9]{32}$/.test(String(key || "")) && worker && !worker.__gaiusTerminal) session = String(key);
              });
              const direct = String(window.__gaiusServerSessionId || "");
              if (!session && /^[a-f0-9]{32}$/.test(direct)) session = direct;
              if (!session) {
                const ports = window.__gaiusLocalServerPorts;
                if (ports && typeof ports.forEach === "function") ports.forEach((value, key) => { if (!session && /^[a-f0-9]{32}$/.test(String(key || ""))) session = String(key); });
              }
              if (session) effectiveHost = "client-" + session + ".gaius-local";
            }
            tracePortableBridge("bridge-open", {id,host:String(host),port:Number(port),effectiveHost:String(effectiveHost),session:String(window.__gaiusServerSessionId||""),workers:window.__gaiusSingleplayerWorkers?.size||0,ports:window.__gaiusLocalServerPorts?.size||0});
            return originalOpen.call(this, id, effectiveHost, port);
          };
          bridge.__gaiusLocalHostPatch = true;
          return true;
        };
        let bridge = window.__gaiusNettyBridge;
        if (!bridge && window.__gaiusNettyBridgeBootstrapState && typeof window.__gaiusNettyBridgeBootstrapState.open === "function") {
          try { window.__gaiusNettyBridge = window.__gaiusNettyBridgeBootstrapState; bridge = window.__gaiusNettyBridge; } catch (_) {}
        }
        if (wrap(bridge)) {
          if (portableBridgeTraced !== bridge) {
            portableBridgeTraced = bridge;
            tracePortableBridge("bridge-init", {bridgeReady: true});
          }
          consumePortablePendingLocalPorts();
          return true;
        }
        const bootstrapState = window.__gaiusNettyBridgeBootstrapState;
        if (wrap(bootstrapState)) {
          if (portableBridgeTraced !== bootstrapState) {
            portableBridgeTraced = bootstrapState;
            tracePortableBridge("bridge-bootstrap", {bridgeReady: true});
          }
          consumePortablePendingLocalPorts();
          return true;
        }
        if (!window.__gaiusPortableBridgeAccessorInstalled) {
          try {
            const descriptor = Object.getOwnPropertyDescriptor(window, "__gaiusNettyBridge");
            if (!descriptor || descriptor.configurable) {
              let value = descriptor ? (descriptor.get ? descriptor.get.call(window) : descriptor.value) : undefined;
              Object.defineProperty(window, "__gaiusNettyBridge", {
                configurable: true, enumerable: descriptor ? descriptor.enumerable : true,
                get() { return value; },
                set(next) { value = next; wrap(next); }
              });
              window.__gaiusPortableBridgeAccessorInstalled = true;
            }
          } catch (_) {}
        }
        const ready = !!wrap(window.__gaiusNettyBridge);
        if (ready) consumePortablePendingLocalPorts();
        return ready;
      };
      // Poll until the bridge is patched and every pending local port is registered. Once the
      // accessor below owns __gaiusNettyBridge, a replacement bridge is wrapped when assigned, so
      // the pump stops instead of waking the page every 10 ms for the rest of the session; a
      // newly pending port (integrated-server start) re-arms it.
      let portableBridgePump = null;
      const pumpPortableBridge = () => {
        const ready = installLocalBridgeHostPatch();
        consumePortablePendingLocalPorts();
        if (ready && window.__gaiusPortableBridgeAccessorInstalled === true &&
            portablePendingLocalPorts.size === 0 && portableBridgePump !== null) {
          clearInterval(portableBridgePump);
          portableBridgePump = null;
        }
      };
      const armPortableBridgePump = () => {
        if (portableBridgePump === null) portableBridgePump = setInterval(pumpPortableBridge, 10);
      };
      installLocalBridgeHostPatch();
      armPortableBridgePump();
      // Decode order follows need: the client script and its wasm gate the boot, then the asset
      // pack is inflated off this thread so the launcher gets the raw pack. The singleplayer
      // server is only needed when a world opens, so keep its decode lazy until the Worker
      // start message (the Promise property remains for callers that explicitly await it).
      const classesBlobPromise = decodeAsset("classes", {gunzip: true, blobType: "text/javascript", parallel: true});
      const wasmBlobPromise = decodeAsset("wasm", {gunzip: true, blobType: "application/wasm"});
      window.__gaiusVanillaAssetsCompressedPromise = decodeAsset("vanilla", {gunzip: true, parallel: true})
        .then((buffer) => new Uint8Array(buffer));
      // The page must transfer the embedded server bytes to its Worker. A
      // page-created blob:null URL cannot be fetched from a dedicated Worker
      // when the launcher is opened directly via file://. With ?serverRuntime=wasm
      // (gaius-boot.js) a page that embeds singleplayer-server.wasm passes the
      // module's gzip bytes instead, decoded when the first world opens.
      // A function: read when a world starts. On a dual page gaius-boot.js (which publishes
      // __gaiusBoot.serverRuntime) runs after this bootstrap.
      const portableWasmServer = () => !!(window.__gaiusBoot && window.__gaiusBoot.serverRuntime === "wasm" &&
        portablePayload.assets && portablePayload.assets.__GAIUS_WASM_SERVER_ASSET__);
      let portableServerGzipDataPromise = null;
      const portableServerGzipData = () => {
        if (portableWasmServer()) return null;
        if (!portableServerGzipDataPromise) {
          portableServerGzipDataPromise = decodeAsset("server", {gunzip: false});
        }
        return portableServerGzipDataPromise;
      };
      Object.defineProperty(window, "__gaiusSingleplayerServerGzipDataPromise", {
        configurable: true,
        enumerable: true,
        get: portableServerGzipData,
        set: (value) => { portableServerGzipDataPromise = value; },
      });
      // Bridge the generated Java launcher without recompiling TeaVM: defer
      // only the integrated-server start message until the transferable gzip
      // buffer is ready. The original MessagePort remains untransferred until
      // the native postMessage call below.
      if (typeof Worker === "function" && Worker.prototype &&
          typeof Worker.prototype.postMessage === "function") {
        const nativeWorkerPostMessage = Worker.prototype.postMessage;
        Worker.prototype.postMessage = function(message, transfer) {
          if (window.__gaiusPortableBuild === true && message &&
              message.type === "start" && (portableWasmServer() ||
              (window.__gaiusSingleplayerServerGzipDataPromise &&
              typeof window.__gaiusSingleplayerServerGzipDataPromise.then === "function"))) {
            const worker = this;
            const originalTransfer = Array.isArray(transfer) ? transfer.slice() : [];
            const sessionId = String(message.sessionId || "");
            const launchGeneration = String(message.launchGeneration || "");
            const pagePort = worker && worker.__gaiusClientPort;
            if (/^[a-f0-9]{32}$/.test(sessionId) && pagePort && /^[1-9][0-9]*$/.test(launchGeneration)) {
              const ports = window.__gaiusLocalServerPorts ||
                (window.__gaiusLocalServerPorts = new Map());
              const existing = ports.get(sessionId);
              if (!existing) ports.set(sessionId, pagePort);
              window.__gaiusServerSessionId = sessionId;
              window.__gaiusServerLaunchGeneration = launchGeneration;
              portablePendingLocalPorts.set(sessionId, {port: pagePort, launchGeneration});
              tracePortableBridge("start-pre-register", {
                sessionId, launchGeneration, hasPagePort: true,
                existingPort: !!existing, pending: portablePendingLocalPorts.size,
              });
              consumePortablePendingLocalPorts();
              if (portablePendingLocalPorts.size > 0) armPortableBridgePump();
            } else {
              tracePortableBridge("start-pre-register", {
                sessionId, launchGeneration, hasPagePort: !!pagePort,
                pending: portablePendingLocalPorts.size,
              });
            }
            const serverData = portableWasmServer()
              ? decodeAsset("__GAIUS_WASM_SERVER_ASSET__", {gunzip: false})
              : window.__gaiusSingleplayerServerGzipDataPromise;
            serverData.then((buffer) => {
              // Transferring detaches the buffer, and the decoder resolves to the
              // same buffer for every integrated-server start of this page, so
              // each start transfers its own copy (a second start in the same
              // session otherwise fails with DataCloneError).
              const transferBuffer = buffer instanceof ArrayBuffer ? buffer.slice(0) : buffer;
              const payload = Object.assign({}, message, portableWasmServer()
                ? {serverWasmGzipData: transferBuffer}
                : {serverScriptGzipData: transferBuffer, serverScriptGzipUrl: null});
              const transferList = originalTransfer.slice();
              if (transferBuffer instanceof ArrayBuffer) transferList.push(transferBuffer);
              nativeWorkerPostMessage.call(worker, payload, transferList);
              tracePortableBridge("start-post-register", {sessionId, launchGeneration, transferred: true});
            }, () => {
              nativeWorkerPostMessage.call(worker, message, originalTransfer);
              tracePortableBridge("start-post-register", {sessionId, launchGeneration, transferred: false});
            });
            return;
          }
          return nativeWorkerPostMessage.call(this, message, transfer);
        };
      }
      window.__gaiusPortableAssetsReady = (async () => {
        const [classesBlob, wasmBlob] = await Promise.all([classesBlobPromise, wasmBlobPromise]);
        window.__gaiusClassesUrl = URL.createObjectURL(classesBlob);
        window.__gaiusHotpathWasmUrl = URL.createObjectURL(wasmBlob);
        window.__gaiusSingleplayerWorkerUrl = URL.createObjectURL(new Blob(
          [workerSource],
          {type: "text/javascript"},
        ));
        __GAIUS_PORTABLE_TOOLCHAIN_READY__
      })();
    })();
  </script>
'''


PORTABLE_LAUNCHER_GUARD = re.compile(
    r'''(?ms)^[ \t]*<script\b[^>]*>(?:(?!</script\s*>).)*?'''
    r'''if\s*\(\s*typeof\s+Error\s*===\s*["']function["']\s*\)'''
)


def portable_launcher_marker(document: str) -> str:
    """Return the launcher guard anchor across generated HTML formatting variants."""
    matches = list(PORTABLE_LAUNCHER_GUARD.finditer(document))
    if len(matches) != 1:
        raise RuntimeError(
            "portable launcher insertion point was not found: expected exactly one "
            '<script> containing a typeof Error === "function" guard '
            f"(found {len(matches)})"
        )
    return matches[0].group(0)


def arrange_portable_boot(portable: str, marker: str, runtime_inline: str) -> str:
    """Orders a WasmGC portable page around its embedded launcher and payload bootstrap."""
    # The internal resource shell can contain the boot source after the launcher's own startup
    # block. Move the real boot source directly in front of the launcher marker so the capability
    # gate runs after the portable payloads have been announced.
    boot_pattern = re.compile(
        r'\s*<script data-gaius-boot="v1"[^>]*>.*?</script>\s*', re.DOTALL
    )
    boot_tags = boot_pattern.findall(portable)
    boot_source = next((tag for tag in boot_tags if "const clientRuntime = selectRuntime" in tag), "")
    if not boot_source:
        raise RuntimeError("portable Wasm page has no complete boot source")
    portable = boot_pattern.sub("\n", portable)
    relay_pattern = re.compile(
        r'\s*<script data-gaius-relay-protocol>.*?</script>\s*', re.DOTALL
    )
    portable = relay_pattern.sub("\n", portable)
    portable = portable.replace(marker, boot_source + marker, 1)
    source_matches = [
        match for match in boot_pattern.finditer(portable)
        if "const clientRuntime = selectRuntime" in match.group(0)
    ]
    for match in reversed(source_matches[:-1]):
        portable = portable[:match.start()] + portable[match.end():]
    portable_bootstrap_tag = '  <script data-gaius-portable="1">\n'
    if portable_bootstrap_tag not in portable:
        raise RuntimeError("portable Wasm bootstrap tag was not found")
    # Only the runtime descriptor (window.__gaiusPortableRuntimes, read by the bootstrap) and
    # the relay declaration removed above go in front of the bootstrap. Re-inserting the
    # whole runtime_inline here put a second gaius-boot.js before the bootstrap; the first
    # copy wins (its __gaiusBoot guard), so boot ran before window.__gaiusPortableWebGpu and
    # the Wasm runtime URLs existed and cached "translator unavailable". The modules and the
    # inert scripts are already on the page from the BOOT_TAG replacement.
    descriptor = "".join(
        match.group(0) for match in PORTABLE_RUNTIME_DESCRIPTOR.finditer(runtime_inline)
    )
    if "window.__gaiusPortableRuntimes" not in descriptor:
        raise RuntimeError("portable Wasm runtime descriptor was not found")
    portable = portable.replace(
        portable_bootstrap_tag, descriptor + portable_bootstrap_tag, 1
    )
    boot_sources = [
        match.start() for match in boot_pattern.finditer(portable)
        if "const clientRuntime = selectRuntime" in match.group(0)
    ]
    if len(boot_sources) != 1:
        raise RuntimeError(
            f"portable Wasm page carries {len(boot_sources)} boot sources, expected 1")
    if boot_sources[0] < portable.index(portable_bootstrap_tag):
        raise RuntimeError("portable Wasm boot source precedes the portable bootstrap")
    return portable


def build(dist: Path, output: Path, root: Path | None = None, wasm_dist: Path | None = None,
          wasm_server_dist: Path | None = None) -> None:
    root = (root or Path(__file__).resolve().parents[2]).resolve()
    dist = dist.resolve()
    output = output.resolve()
    if not output.parent.is_dir():
        raise FileNotFoundError(f"portable output directory is missing: {output.parent}")

    profile, relative_profile, profile_bytes = load_version_profile(root)
    build_root = configured_build_root(root)
    common_identity = build_identity.current_build_identity(root)
    if (
        common_identity["profile"]["id"] != profile["id"]
        or common_identity["profile"]["path"] != Path(relative_profile).as_posix()
        or common_identity["profile"]["sha256"] != sha256_bytes(profile_bytes)
    ):
        raise RuntimeError("portable profile does not match the current build identity")
    index = require(dist / "index.html").read_text(encoding="utf-8")
    # A WasmGC dist's page (make-wasm-page.py) names its disk payloads; the portable page
    # announces the same WasmGC modules inline.
    index = RUNTIME_CONFIG_BLOCK.sub("", index)
    postprocess_spec = importlib.util.spec_from_file_location(
        "gaius_postprocess_index_html", SCRIPT_DIRECTORY / "postprocess-index-html.py")
    if postprocess_spec is None or postprocess_spec.loader is None:
        raise RuntimeError("could not load postprocess-index-html.py")
    postprocess_module = importlib.util.module_from_spec(postprocess_spec)
    postprocess_spec.loader.exec_module(postprocess_module)
    index = postprocess_module.patch_early_profile_id(index, profile)
    validate_launcher_profile(index, profile)
    validate_launcher_storage(index, profile)
    classes_js = require_nonempty(dist / "classes.js")
    classes_gzip = require_nonempty(dist / "classes.js.gz")
    classes_hash, classes_gzip_hash = compare_gzip_with_raw(classes_js, classes_gzip)
    server_js = require_nonempty(dist / "singleplayer-server.js")
    server_gzip = require_nonempty(dist / "singleplayer-server.js.gz")
    server_hash, server_gzip_hash = compare_gzip_with_raw(server_js, server_gzip)
    wasm_raw = require_nonempty(dist / "gaius-hotpath.wasm")
    wasm_gzip = require_nonempty(dist / "gaius-hotpath.wasm.gz")
    wasm_hash, wasm_gzip_hash = compare_gzip_with_raw(wasm_raw, wasm_gzip)
    vanilla_gzip = require_nonempty(dist / "vanilla-assets.pack.gz")
    worker_path = require_nonempty(dist / "singleplayer-server-worker.js")
    relay_registry_path = require_nonempty(dist / "relay-nodes.json")
    component_identities = {
        "classesJs": verified_component_identity(
            root, classes_js, "client", common_identity
        ),
        "singleplayerServerJs": verified_component_identity(
            root, server_js, "singleplayer-worker", common_identity
        ),
        "wasmHotpath": verified_component_identity(
            root, wasm_raw, "wasm-hotpath", common_identity
        ),
        "singleplayerWorkerBootstrap": verified_component_identity(
            root, worker_path, "worker-bootstrap", common_identity
        ),
        "vanillaAssetsPack": verified_component_identity(
            root, vanilla_gzip, "vanilla-assets", common_identity
        ),
        "relayRegistry": verified_component_identity(
            root, relay_registry_path, "relay-registry", common_identity
        ),
    }
    metadata = json.loads(
        (root / "port" / "work" / profile["id"] / "version.json").read_text(
            encoding="utf-8"
        )
    )
    asset_index_id = metadata.get("assetIndex", {}).get("id") or metadata.get("assets")
    if not isinstance(asset_index_id, str) or not asset_index_id:
        raise RuntimeError("active version metadata has no asset index")
    generated_resources = build_root / "generated-resources"
    client_compiler = verified_compiler_profile(
        root,
        classes_js,
        "client",
        build_root / "release-generated-pom.xml",
        [
            generated_resources / "dev/gaius/browser/minecraft-resources.txt",
            generated_resources / "dev/gaius/browser/minecraft-embedded-resources.txt",
            root / "port" / "work" / profile["id"] / "assets" / "indexes" / f"{asset_index_id}.json",
            generated_resources / "assets/minecraft/sounds.json",
            generated_resources / "assets/minecraft/font/include/unifont.json",
            generated_resources / "assets/minecraft/font/include/unifont_pua.json",
            vanilla_gzip,
        ],
    )
    worker_compiler = verified_compiler_profile(
        root,
        server_js,
        "singleplayer-worker",
        build_root / "server-worker" / "release-generated-pom.xml",
        [
            build_root
            / "server-worker"
            / "generated-resources/dev/gaius/browser/minecraft-resources.txt"
        ],
    )
    signatures = verify_signatures(dist, profile, classes_hash)
    shader_toolchain = load_shader_toolchain(
        dist, index, profile, classes_js, root, common_identity,
    )
    worker = worker_path.read_text(encoding="utf-8")
    relay_registry = json.loads(relay_registry_path.read_text(encoding="utf-8"))
    if (relay_registry.get("kind") != "gaius-relay-registry"
            or relay_registry.get("protocolVersion") != 1
            or not isinstance(relay_registry.get("nodes"), list)):
        raise RuntimeError("portable relay-nodes.json is incompatible")
    relay_nodes = relay_registry["nodes"][:64]

    # Document order is decode order: the client and its wasm first (they gate the boot), the
    # shader toolchain next, then the asset pack, the singleplayer server (only needed when a
    # world opens) and the kernels (decoded when the kernel runtime first loads them).
    payload = PortablePayload()
    payload.add("classes", classes_gzip.read_bytes(), gzipped=True)
    wasm_client = (load_wasm_client(wasm_dist.resolve(), wasm_server_dist.resolve()
                                    if wasm_server_dist is not None else None)
                   if wasm_dist is not None else None)
    if wasm_client is not None:
        index = RELAY_PROTOCOL_BLOCK.sub("", index)
        payload.add(WASM_CLIENT_ASSET, wasm_client["gzip"], gzipped=True)
    payload.add("wasm", wasm_gzip.read_bytes(), gzipped=True)
    if shader_toolchain is not None:
        for key, _name, _mime in SHADER_TOOLCHAIN_MODULES:
            payload.add(shader_toolchain_asset(key), shader_toolchain["payload"][key], gzipped=True)
    payload.add("vanilla", vanilla_gzip.read_bytes(), gzipped=True)
    payload.add("server", server_gzip.read_bytes(), gzipped=True)
    if wasm_client is not None and wasm_client["server"] is not None:
        payload.add(WASM_SERVER_ASSET, wasm_client["server"]["gzip"], gzipped=True)
    kernel_index, kernel_record = load_portable_kernels(dist, payload)
    webgpu_line, webgpu_record = load_portable_webgpu(dist, payload)
    runtime_inline, runtime_hashes = runtime_bootstrap(root, wasm_client=wasm_client, dist=dist)

    manifest = {
        "kind": MANIFEST_KIND,
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "artifact": output.name,
        "profile": profile["id"],
        "profilePath": Path(relative_profile).as_posix(),
        "profileSha256": sha256_bytes(profile_bytes),
        "worldVersion": profile["worldVersion"],
        "worldgenTelemetryMode": profile.get("worldgenTelemetryMode"),
        "storage": profile["storage"],
        "buildIdentity": common_identity,
        "payloadEncoding": PAYLOAD_ENCODING,
        "classesJs": {
            "rawSha256": classes_hash,
            "gzipSha256": classes_gzip_hash,
            "rawBytes": classes_js.stat().st_size,
            "gzipBytes": classes_gzip.stat().st_size,
            "build": component_identities["classesJs"],
            "compiler": client_compiler,
        },
        "singleplayerServerJs": {
            "rawSha256": server_hash,
            "gzipSha256": server_gzip_hash,
            "rawBytes": server_js.stat().st_size,
            "gzipBytes": server_gzip.stat().st_size,
            "build": component_identities["singleplayerServerJs"],
            "compiler": worker_compiler,
        },
        "wasmHotpath": {
            "rawSha256": wasm_hash,
            "gzipSha256": wasm_gzip_hash,
            "rawBytes": wasm_raw.stat().st_size,
            "gzipBytes": wasm_gzip.stat().st_size,
            "build": component_identities["wasmHotpath"],
        },
        "singleplayerWorkerBootstrap": {
            "sha256": sha256_file(worker_path),
            "bytes": worker_path.stat().st_size,
            "build": component_identities["singleplayerWorkerBootstrap"],
        },
        "vanillaAssetsPack": {
            "gzipSha256": sha256_file(vanilla_gzip),
            "gzipBytes": vanilla_gzip.stat().st_size,
            "build": component_identities["vanillaAssetsPack"],
        },
        "relayRegistry": {
            "sha256": sha256_file(relay_registry_path),
            "bytes": relay_registry_path.stat().st_size,
            "build": component_identities["relayRegistry"],
        },
        "signatures": signatures,
    }
    if shader_toolchain is not None:
        manifest["shaderToolchain"] = shader_toolchain["manifest"]
    if wasm_client is not None:
        manifest["wasmClient"] = wasm_client["manifest"]
        if wasm_client["server"] is not None:
            manifest["wasmServer"] = wasm_client["server"]["manifest"]
    if runtime_hashes:
        manifest["runtimeModules"] = runtime_hashes
    if kernel_record:
        manifest["kernels"] = kernel_record
    if webgpu_record:
        manifest["webgpuTranslator"] = webgpu_record
    manifest_source = json.dumps(
        manifest,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    )
    manifest_text = f"{manifest_source}\n"

    payload_index = json.dumps(payload.index(), ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    kernel_source = json.dumps(kernel_index or None, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    worker_source = json.dumps(worker, ensure_ascii=True)
    relay_nodes_source = json.dumps(relay_nodes, ensure_ascii=True, separators=(",", ":"))
    toolchain_globals, toolchain_ready, toolchain_loader = shader_toolchain_bootstrap(
        shader_toolchain, profile
    )
    bootstrap = (
        PORTABLE_BOOTSTRAP
        .replace("__GAIUS_WASM_SERVER_ASSET__", WASM_SERVER_ASSET)
        .replace("__GAIUS_PORTABLE_MANIFEST__", manifest_source)
        .replace("__GAIUS_PORTABLE_PAYLOAD__", payload_index)
        .replace("__GAIUS_PORTABLE_KERNELS__", kernel_source)
        .replace("__GAIUS_PORTABLE_WORKER_SOURCE__", worker_source)
        .replace("__GAIUS_PORTABLE_RELAY_NODES__", relay_nodes_source)
        .replace("      __GAIUS_PORTABLE_TOOLCHAIN_GLOBALS__\n", toolchain_globals)
        .replace("      __GAIUS_PORTABLE_WEBGPU__\n", webgpu_line)
        .replace("        __GAIUS_PORTABLE_TOOLCHAIN_READY__\n", toolchain_ready)
    )
    if "__GAIUS_PORTABLE_" in bootstrap:
        raise RuntimeError("portable bootstrap template has an unfilled placeholder")
    if wasm_client is not None:
        if not runtime_inline:
            raise RuntimeError("a WebAssembly GC portable page needs the boot script to pick its runtime")
        bootstrap = apply_portable_wasm_client(bootstrap)
    marker = portable_launcher_marker(index)
    if shader_toolchain is not None:
        # The page's own loader tag would run before the portable bootstrap
        # and fall back to sibling files; the inline copy after the bootstrap
        # awaits the embedded Blob URLs instead.
        index, removed = SHADER_TOOLCHAIN_TAG.subn("", index)
        if removed != 1:
            raise RuntimeError("shader toolchain loader tag was not replaced")
    if runtime_inline and BOOT_TAG in index:
        index = index.replace(BOOT_TAG, runtime_inline, 1)
    elif BOOT_TAG in index:
        # No boot sources next to this script: drop the tag, it would only 404 on file://.
        index = index.replace(BOOT_TAG, "", 1)
    # The payload elements precede the bootstrap, so the bootstrap finds every part when it runs.
    portable = index.replace(marker, payload.html() + bootstrap + toolchain_loader + marker, 1)
    if wasm_client is not None:
        portable = arrange_portable_boot(portable, marker, runtime_inline)
    manifest_path = manifest_path_for(output)
    if manifest_path == output:
        raise RuntimeError("portable manifest path collides with HTML output")
    publish_portable_pair(output, add_license_notice(portable, root), manifest_path, manifest_text)
    write_license_sidecar(output, root)
    print(f"Portable Gaius manifest: {manifest_path} ({manifest_path.stat().st_size} bytes)")
    print(f"Portable Gaius HTML: {output} ({output.stat().st_size} bytes)")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    arguments = sys.argv[1:]
    no_js_fallback = "--no-js-fallback" in arguments
    if no_js_fallback:
        arguments.remove("--no-js-fallback")
    # Test trains may pair a client and a server built from different commits; a release
    # never does.  Only this explicit switch accepts differing source identities.
    allow_mixed_provenance = "--allow-mixed-provenance" in arguments
    if allow_mixed_provenance:
        arguments.remove("--allow-mixed-provenance")
    wasm_dist = None
    site_shell = None
    wasm_server_dist = None
    if "--site-shell" in arguments:
        position = arguments.index("--site-shell")
        if position + 1 >= len(arguments):
            print("--site-shell needs a directory", file=sys.stderr)
            return 2
        site_shell = _native_external_path(arguments[position + 1]).resolve()
        del arguments[position:position + 2]
    if "--wasm-server-dist" in arguments:
        position = arguments.index("--wasm-server-dist")
        if position + 1 >= len(arguments):
            print("--wasm-server-dist needs a directory", file=sys.stderr)
            return 2
        wasm_server_dist = _native_external_path(arguments[position + 1]).resolve()
        del arguments[position:position + 2]
    if "--wasm-dist" in arguments:
        position = arguments.index("--wasm-dist")
        if position + 1 >= len(arguments):
            print("--wasm-dist needs a directory", file=sys.stderr)
            return 2
        wasm_dist = _native_external_path(arguments[position + 1]).resolve()
        del arguments[position:position + 2]
    if arguments:
        dist = Path(arguments[0]).resolve()
    else:
        profile, _, _ = load_version_profile(root)
        dist = configured_dist_directory(root, profile["id"])
    output = Path(arguments[1]).resolve() if len(arguments) > 1 else dist / "Gaius.html"
    if no_js_fallback:
        if wasm_dist is None:
            print("--no-js-fallback requires --wasm-dist", file=sys.stderr)
            return 2
        # Wasm-only staging is independent of the internal JavaScript resource build. The
        # launcher shell and shared assets are taken from the staged Wasm site; the portable
        # assembler itself still owns the final single-file encoding.
        profile = wasm_dist.name.removeprefix("wasm-").removesuffix("-client")
        site = site_shell or (wasm_dist.parent / f"site-{profile}")
        if not (site / "index.html").is_file():
            raise RuntimeError(f"Wasm-only staging needs the matching site layout: {site}")
        # The site builder is the provenance authority for client/server pairing.  Do not
        # compare against (or require) classes.js sidecars from a release dist.
        server = wasm_server_dist or (wasm_dist.parent / f"wasm-{profile}-server")
        if server.is_dir() and (server / "singleplayer-server.wasm.build.json").is_file():
            check = wasm_dist / "classes.wasm.build.json"
            other = server / "singleplayer-server.wasm.build.json"
            if check.is_file():
                left = json.loads(check.read_text(encoding="utf-8")); right = json.loads(other.read_text(encoding="utf-8"))
                # Built from the same commit, client and server share the profile and the
                # runtime source identity; a source mismatch means a mixed-commit pair.
                for key in ("profile",) if allow_mixed_provenance else ("profile", "source"):
                    if left.get(key, {}).get("sha256") and right.get(key, {}).get("sha256") \
                            and left[key]["sha256"] != right[key]["sha256"]:
                        raise RuntimeError(f"Wasm server provenance mismatch in {key}")
        # The staged site is already the complete wasm-only launcher layout.  Keep the
        # acceptance command deterministic by copying its entry page as the portable shell;
        # the site bootstrap owns runtime selection and the embedded Wasm payloads below.
        index = (site / "index.html").read_text(encoding="utf-8").replace("\r\n", "\n")
        index = re.sub(r'\s*<script[^>]*data-gaius-site="v1"[^>]*>.*?</script>\s*', "\n", index, flags=re.DOTALL)
        boot_url = re.search(r'src="([^"]*gaius-boot[^\"]*)"', index)
        if boot_url is None:
            raise RuntimeError(f"Wasm-only site has no boot script: {site}")
        boot = (site / boot_url.group(1)).read_text(encoding="utf-8")
        # The staged boot is frozen; apply the same file payload URL wiring as
        # port/web/boot/gaius-boot.js before placing it in the portable page.
        # Portable pages retain the site's metadata, but their assets are embedded;
        # make the boot payload lookup recognize that form as a file-payload page.
        boot = boot.replace(
            'return isFile && !site && payloads && typeof payloads === "object" &&',
            'return isFile && (isPortable() || !site) && payloads && typeof payloads === "object" &&')
        boot = boot.replace(
            'return isFile && !site && !isPortable() && payloads && typeof payloads === "object" ? payloads : null;',
            'return isFile && (isPortable() || !site) && payloads && typeof payloads === "object" ? payloads : null;')
        boot = boot.replace(
            'const entry = hasFilePayload(name) ? filePayloads()[name] : null;\n    const promise = !entry || !doc || !doc.createElement',
            'const entry = hasFilePayload(name) ? filePayloads()[name] : null;\n    const portableBytes = global.__gaiusPortablePayloadBytes && global.__gaiusPortablePayloadBytes[name];\n    if (portableBytes) { const promise = Promise.resolve(portableBytes); filePayloadLoads.set(name, promise); return promise; }\n    const promise = !entry || !doc || !doc.createElement')
        boot = boot.replace(
            'return Promise.resolve(body).then((blob) => global.URL.createObjectURL(new global.Blob([blob], {type})));',
            'return Promise.resolve(body).then((blob) => { const url = global.URL.createObjectURL(new global.Blob([blob], {type})); (global.__gaiusPortablePayloadUrls || (global.__gaiusPortablePayloadUrls = {}))[name] = url; return url; });')
        boot = boot.replace(
            'const urls = {shadercJs: "gaius-shaderc.js", spvcJs: "gaius-spvc.js"};',
            'const urls = {};')
        boot = boot.replace(
            'const modules = [\n        filePayloadUrl("gaius-shaderc.wasm",',
            'const modules = [\n        filePayloadUrl("gaius-shaderc.js", "text/javascript").then((url) => { urls.shadercJs = url; }),\n        filePayloadUrl("gaius-spvc.js", "text/javascript").then((url) => { urls.spvcJs = url; }),\n        filePayloadUrl("gaius-shaderc.wasm",')

        index = index.replace(f'<script data-gaius-boot="v1" src="{boot_url.group(1)}"></script>',
                              f'<script data-gaius-boot="v1">{boot}</script>')
        index = index.replace('loading classes.js...', 'loading WebAssembly GC client...')
        index = index.replace('classes.js loaded; calling', 'WebAssembly GC client loaded; calling')
        index = re.sub(r'const classesUrl = window\.__gaiusClassesUrl \|\|\s*\("classes\.js\?v=" \+ encodeURIComponent\(buildToken\)\);',
                       'const classesUrl = window.__gaiusClassesUrl;', index)
        client = require_nonempty(wasm_dist / "classes.wasm").read_bytes()
        server_module = server / "singleplayer-server.wasm" if server.is_dir() else None
        server_bytes = server_module.read_bytes() if server_module and server_module.is_file() else b""
        scripts = {name: require_nonempty(wasm_dist / name).read_text(encoding="utf-8")
                   for name in WASM_CLIENT_SCRIPTS}
        server_entry = ",server:'singleplayer-server.wasm'" if server_bytes else ""
        site_manifest = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))
        if site_manifest.get("profile") != profile:
            raise RuntimeError(
                f"Wasm-only site profile mismatch: expected {profile}, got {site_manifest.get('profile')}"
            )
        payload_names = list(FILE_PAYLOAD_FILES) + ["vanilla-sounds.pack"]
        payload_scripts = []
        payload_index = {}
        payload_elements = []
        embedded_files: set[Path] = set()
        for name in payload_names:
            logical = site_manifest.get("assets", {}).get(name)
            candidate = None
            if not logical and name in {"classes.wasm", "singleplayer-server.wasm"}:
                logical = site_manifest.get("assets", {}).get(name + ".gz")
            if not logical or not (site / logical).is_file():
                candidate = (wasm_dist / name) if (wasm_dist / name).is_file() else ((wasm_dist / (name + '.gz')) if (wasm_dist / (name + '.gz')).is_file() else ((server / name) if server and (server / name).is_file() else None))
                if candidate is not None:
                    logical = str(candidate.relative_to(site)) if candidate.is_relative_to(site) else candidate.name
                    raw = candidate.read_bytes()
                else:
                    continue
            else:
                raw = (site / logical).read_bytes()
            physical = (site / logical).resolve() if logical else candidate.resolve()
            if physical in embedded_files:
                continue
            embedded_files.add(physical)
            # Site assets are content-addressed and the wasm client is shipped as a
            # gzip sibling.  Keep that compressed representation as the one embedded
            # copy and tell the file payload loader to inflate it before compilation.
            compressed_asset = name.endswith(".wasm") and logical.endswith(".gz")
            key = name
            stored = raw if logical.endswith(".gz") or not compressed_asset else gzip_bytes(raw)
            payload_index[key] = {"script": key + FILE_PAYLOAD_SUFFIX, "bytes": len(stored),
                                  "gzip": compressed_asset, "sha256": sha256_bytes(stored)}
            text = encode_payload(stored)
            for part, start in enumerate(range(0, len(text), B64_PART_CHARS)):
                payload_elements.append(
                    f'<script type="{PAYLOAD_TYPE}" data-gaius-asset="{key}" data-part="{part}">' +
                    text[start:start + B64_PART_CHARS] + "</script>"
                )
        vanilla_url = site_manifest.get("assets", {}).get("vanilla-assets.pack.gz")
        vanilla_raw = (site / vanilla_url).read_bytes() if vanilla_url else b""
        expected_vanilla = (site / vanilla_url).read_bytes() if vanilla_url else b""
        wasm_vanilla = (wasm_dist / "vanilla-assets.pack.gz").read_bytes()
        source_hash = site_manifest.get("profileAssets", {}).get("vanillaAssetsPackSha256")
        if source_hash != hashlib.sha256(wasm_vanilla).hexdigest():
            raise RuntimeError("Wasm-only portable vanilla source does not match the Wasm profile dist")
        if not expected_vanilla:
            raise RuntimeError("Wasm-only portable site has no vanilla core asset")
        # The file payload getter is the sole copy of each asset.  In particular, do
        # not also emit base64 wasm globals: that used to double the 20 MB client and
        # handed a gzip response to compileStreaming on file://.
        prefix = ("<script>window.__gaiusPortableBuild=true;window.__gaiusPortableWasmOnly=true;"
                  "window.__gaiusPortableVanillaSourceSha256=" + json.dumps(source_hash) + ";"
                  "window.__gaiusPortableRuntimes="
                  "{wasm:{client:'classes.wasm'" + server_entry + "}};window.__gaiusRuntimes="
                  "{wasm:{client:'classes.wasm'" + server_entry + "}};window.__gaiusFilePayloads="
                  + json.dumps(payload_index, separators=(',', ':')) + ";</script>"
                  + "".join(payload_elements))
        prefix += r"""<script>
try {
  function _b64decode(name) {
    const info=window.__gaiusFilePayloads[name], nodes=document.querySelectorAll('script[data-gaius-asset="'+name+'"]');
    let text=''; nodes.forEach(n=>{text+=n.textContent; n.remove()});
    let binary; try { binary=atob(text); } catch (e) { throw new Error('portable asset '+name+' is not valid ASCII base64; the HTML was re-encoded or corrupted'); }
    if (binary.length !== info.bytes) throw new Error('portable asset '+name+' length mismatch (expected '+info.bytes+', got '+binary.length+')');
    const out=new Uint8Array(binary.length); for(let i=0;i<binary.length;i++) out[i]=binary.charCodeAt(i); return out;
  }
  async function _verify(name,b){const i=window.__gaiusFilePayloads[name]; if(!i.sha256)return b; const d=await crypto.subtle.digest('SHA-256',b), h=new Uint8Array(d); let a=''; for(const v of h)a+=v.toString(16).padStart(2,'0'); if(a!==i.sha256)throw new Error('portable asset '+name+' sha256 mismatch (expected '+i.sha256+', got '+a+')'); return b;}
  async function _bytes(name){const i=window.__gaiusFilePayloads[name], cached=window.__gaiusPortablePayloadBytes[name], b=await _verify(name,cached||_b64decode(name)); if(!i.gzip)return b.buffer; const ds=new DecompressionStream('gzip'), w=ds.writable.getWriter(), r=new Response(ds.readable).arrayBuffer(); await w.write(b); await w.close(); return r;}
  function _blob(name,type){return _bytes(name).then(b=>{const url=URL.createObjectURL(new Blob([b],{type})); (window.__gaiusPortablePayloadUrls||(window.__gaiusPortablePayloadUrls={}))[name]=url; return url;});}
  window.__gaiusPortablePayloadBytes={};
  for (const _name of Object.keys(window.__gaiusFilePayloads)) {
    Object.defineProperty(window.__gaiusPortablePayloadBytes, _name, {configurable: true, enumerable: true,
      get() {
         const value = _b64decode(_name);
        Object.defineProperty(window.__gaiusPortablePayloadBytes, _name,
          {configurable: true, enumerable: true, value});
        return value;
      }});
  }
  const _optional=(n,t)=>window.__gaiusFilePayloads[n]?_blob(n,t):Promise.resolve(null);
  function _lazyPromise(name, create) {
    Object.defineProperty(window, name, {configurable: true, get() {
      const promise = create();
      Object.defineProperty(window, name, {configurable: true, enumerable: true, writable: true, value: promise});
      return promise;
    }});
  }
  _lazyPromise('__gaiusWasmSource', () => _bytes('classes.wasm'));
  _lazyPromise('__gaiusVanillaAssetsCompressedPromise', () =>
    _verify('vanilla-assets.pack.gz', window.__gaiusPortablePayloadBytes['vanilla-assets.pack.gz']));
  _lazyPromise('__gaiusPortableAssetsReady', () => Promise.all([
    _optional('gaius-shaderc.js','text/javascript'), _optional('gaius-spvc.js','text/javascript'),
    _optional('gaius-shaderc.wasm','application/wasm'), _optional('gaius-spvc.wasm','application/wasm'),
    _optional('gaius-hotpath.wasm','application/wasm')
  ]).then(function(_u){
    window.__gaiusShaderToolchainUrls={shadercJs:_u[0],spvcJs:_u[1],shadercWasm:_u[2],spvcWasm:_u[3]};
    window.__gaiusHotpathWasmUrl=_u[4]; return true;
  }).catch(function(_e){ window.__gaiusPortableBootError=String(_e&&_e.stack||_e); console.error('portable asset URL initialization failed',_e); throw _e; }));
} catch (e) { window.__gaiusPortableBootError=String(e&&e.stack||e); console.error('portable payload initialization failed',e); }
</script>"""
        inert = "".join(f'<script type="text/plain" data-gaius-wasm-script="{name}">{text}</script>' for name, text in scripts.items())
        wasm_boot_url = "URL.createObjectURL(new Blob([document.querySelector('script[data-gaius-wasm-script=\\\"gaius-wasm-boot.js\\\"]').textContent],{type:'text/javascript'}))"
        inert += f"<script>window.__gaiusClassesUrl={wasm_boot_url};window.__gaiusWasmRuntimeUrl=URL.createObjectURL(new Blob([document.querySelector('script[data-gaius-wasm-script=\\\"gaius-wasm-gc-runtime.js\\\"]').textContent],{{type:'text/javascript'}}));window.__gaiusJspiUrl=URL.createObjectURL(new Blob([document.querySelector('script[data-gaius-wasm-script=\\\"gaius-jspi.js\\\"]').textContent],{{type:'text/javascript'}}));</script>"
        shader = site_manifest.get("assets", {}).get("gaius-shader-toolchain.js")
        if shader and (site / shader).is_file():
            index = re.sub(
                rf'<script[^>]+src="{re.escape(shader)}(?:\?[^" ]*)?"[^>]*></script>',
                "",
                index,
            )
            inert += "<script>" + (site / shader).read_text(encoding="utf-8") + "</script>"
        if server_bytes:
            worker_script = require_nonempty(server / "gaius-wasm-worker.js").read_text(encoding="utf-8")
            inert += f'<script type="text/plain" data-gaius-wasm-script="gaius-wasm-worker.js">{worker_script}</script>'
        output.parent.mkdir(parents=True, exist_ok=True)
        portable = "<!doctype html><meta charset=\"utf-8\">" + prefix + inert + index
        portable = drop_site_notice_link(portable)
        validate_portable_no_relative_urls(portable)
        output.write_text(add_license_notice(portable, root), encoding="utf-8", newline="\n")
        write_license_sidecar(output, root)
        print(f"Portable Wasm-only HTML: {output} ({output.stat().st_size} bytes)")
    else:
        build(dist, output, wasm_dist=wasm_dist, wasm_server_dist=wasm_server_dist)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
