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
import stat
import sys
import tempfile
from pathlib import Path

# make-wasm-page.py loads this file with spec_from_file_location, which does not put the script
# directory on sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from relay_artifact_gate import load_nodes  # noqa: E402
from launcher_icons import embedded_links, replace_links  # noqa: E402


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


def drop_site_boot_preloads(document: str) -> str:
    """The portable shell embeds these scripts and must never fetch their site URLs."""
    document = re.sub(r'<link\b(?=[^>]*\bdata-gaius-preload="boot")[^>]*>\n?', "", document)
    return re.sub(r'<script\b(?=[^>]*\bdata-gaius-preload="wasm")[^>]*>.*?</script>\n?',
                  "", document, flags=re.DOTALL)


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
# Characters per payload element. Blink appends every parser chunk (~0.7 MB) of a <script> body
# to its one growing Text node by copying the whole node (script text has no 64 Ki node-length
# split), so a part's parse cost grows with its size: the 180 MB 26.3 page with 4 Mi-char parts
# reached its payload bootstrap ~0.54 s later on Linux and ~65 ms later on Windows (12 pairs each,
# WI-9) than with 64 Ki parts. GAIUS_PORTABLE_PART_CHARS (a multiple of 8, at least 4096)
# overrides it for every single-file path; LEGACY_B64_PART_CHARS is the pre-WI-9 layout for A/B
# runs or a rollback. The payload text, its encoding, the index hashes and the decoders do not
# depend on the part size.
B64_PART_CHARS = 64 * 1024
LEGACY_B64_PART_CHARS = 4 * 1024 * 1024
PART_CHARS_ENV = "GAIUS_PORTABLE_PART_CHARS"


def payload_part_chars() -> int:
    """Characters per payload element of a single-file page (B64_PART_CHARS unless overridden)."""
    raw = os.environ.get(PART_CHARS_ENV, "").strip()
    if not raw:
        return B64_PART_CHARS
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{PART_CHARS_ENV} must be an integer, got {raw!r}") from exc
    # Whole base64 quanta keep every part independently decodable (the Worker decoder decodes
    # part by part); a multiple of 8 also keeps b7 blocks whole.
    if value < 4096 or value % 8:
        raise RuntimeError(f"{PART_CHARS_ENV} must be a multiple of 8 and at least 4096, got {value}")
    return value


def payload_part_elements(key: str, text: str, part_chars: int) -> list[str]:
    """The inert <script> elements that carry one Wasm-only payload's base64 text, in order."""
    return [
        f'<script type="{PAYLOAD_TYPE}" data-gaius-asset="{key}" data-part="{part}">'
        + text[start:start + part_chars] + "</script>"
        for part, start in enumerate(range(0, len(text), part_chars))
    ]


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
        part_chars = payload_part_chars()
        for start in range(0, len(text), part_chars):
            self.elements.append(
                f'  <script type="{PAYLOAD_TYPE}" data-gaius-asset="{name}" data-part="{parts}">'
                + text[start:start + part_chars]
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


def embed_launcher_icons(page: str) -> str:
    """Replace online icon/manifest URLs with the small self-contained owner artwork."""
    return replace_links(page, embedded_links())


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
# A Wasm client can also carry the compiler independently of the JavaScript
# shell's loader tag; the portable page must embed that dependency as well.
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
    wasm_dist: Path | None = None,
) -> dict[str, object] | None:
    """Verify the compiler required by the page or its embedded Wasm client.

    The JavaScript shell may not use the compiler even when the Wasm client
    does. Follow the Wasm distribution's compiler declaration (the staged
    loader), as make-wasm-page.py does, when the shell has no loader tag.
    """
    tags = SHADER_TOOLCHAIN_TAG.findall(index)
    if not tags and wasm_dist is not None and (
        (wasm_dist / SHADER_TOOLCHAIN_LOADER).is_file()
        or contains_marker(wasm_dist / "classes.wasm", SHADER_TOOLCHAIN_CLASSES_MARKER)
    ):
        dist = wasm_dist
        # These are current Wasm inputs, never frozen JavaScript sidecars.
        frozen = False
        carried_from = None
        for name in SHADER_TOOLCHAIN_FILES + (SHADER_TOOLCHAIN_MANIFEST,):
            require_nonempty(dist / name)
        tags = [(profile["id"], shader_toolchain_token(dist))]
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
        "pageLoader": bool(SHADER_TOOLCHAIN_TAG.search(index)),
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
    "runtime/webgpu/shader-pack-store.js": Path("port/web/runtime/webgpu/shader-pack-store.js"),
    "runtime/webgpu/shader-pack-compat.js": Path("port/web/runtime/webgpu/shader-pack-compat.js"),
    "runtime/webgpu/shader-pack.js": Path("port/web/runtime/webgpu/shader-pack.js"),
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


def wasm_only_runtime_modules(site: Path, manifest: dict) -> tuple[list[tuple[str, str]], dict[str, str]]:
    """The page scripts a Wasm-only single file inlines in front of its boot script.

    The staged site serves the kernel runtime, the quality layer and the WebGPU glue as separate
    assets; the single file has no sibling to load them from, and gaius-boot.js (loadScript) only
    skips the load for a name listed in window.__gaiusBootInline. Returns the (name, source) pairs
    in load order and their SHA-256 values; names the site does not carry are left out, exactly as
    the multi-file site would.
    """
    assets = manifest.get("assets", {})
    names = list(KERNEL_RUNTIME_MODULES) + [
        name for name in manifest.get("modules", []) if isinstance(name, str)
    ] + list(QUALITY_MODULES) + list(WEBGPU_MODULES)
    modules: list[tuple[str, str]] = []
    seen: set[str] = set()
    for name in names:
        logical = assets.get(name)
        if name in seen or not isinstance(logical, str) or not (site / logical).is_file():
            continue
        seen.add(name)
        modules.append((name, inline_safe((site / logical).read_text(encoding="utf-8"), name)))
    return modules, {name: sha256_bytes(source.encode("utf-8")) for name, source in modules}


def wasm_only_kernel_index(site: Path, manifest: dict) -> tuple[dict, list[str]]:
    """The wasm kernels a Wasm-only single file carries, as the page index plus payload names.

    The staged site lists each kernel as {kinds, memory, sha256, variants: {variant: file}}; the
    single file embeds every variant as a file payload under its logical asset name and the
    bootstrap turns the index into window.__gaiusPortableKernels (load() reads the payload).
    Variants whose file is missing are left out; a kernel with none is omitted, and an empty
    result means the page keeps every vanilla path, exactly as a site without kernels does.
    """
    assets = manifest.get("assets", {})
    kernels = manifest.get("kernels") if isinstance(manifest.get("kernels"), dict) else {}
    index: dict[str, dict] = {}
    names: list[str] = []
    for name in sorted(kernels):
        entry = kernels[name]
        if not re.fullmatch(r"[a-z0-9_]+", name) or not isinstance(entry, dict):
            raise RuntimeError(f"kernel manifest entry is invalid: {name}")
        variants: dict[str, str] = {}
        for variant in ("simd", "baseline"):
            key = f"kernels/{name}.{variant}.wasm"
            logical = assets.get(key)
            if isinstance(logical, str) and (site / logical).is_file():
                variants[variant] = key
                names.append(key)
        if not variants:
            continue
        index[name] = {"kinds": list(entry.get("kinds") or []), "variants": variants,
                       "sha256": {variant: (entry.get("sha256") or {}).get(variant) for variant in variants}}
        if isinstance(entry.get("memory"), dict):
            index[name]["memory"] = entry["memory"]
    return index, names


def wasm_only_server_worker_scripts(site: Path, manifest: dict) -> str:
    """Inert copies of the scripts the integrated server Worker imports, for a Wasm-only single file.

    gaius-boot.js (serverWorkerKernelScripts) hands a portable page's Worker the text of these
    <script type="text/plain" data-gaius-worker-script> elements; without them the Worker starts with
    no kernel scripts and generates the world on the vanilla path (kernel-mirror, the worldgen job codec
    and the worldgen facade, plus the policy and runtime for ?worldgenKernelHost=worker).
    """
    assets = manifest.get("assets", {})
    tags = []
    for name in SERVER_WORKER_MODULES:
        logical = assets.get(name)
        if not isinstance(logical, str) or not (site / logical).is_file():
            continue
        source = inline_safe((site / logical).read_text(encoding="utf-8"), name)
        tags.append(f'<script type="text/plain" data-gaius-worker-script="{name}">\n{source}</script>')
    return "".join(tags)


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


def verified_pack_identity(
    root: Path,
    artifact: Path,
    expected: dict[str, object],
    wasm_dist: Path | None,
) -> dict[str, object]:
    if wasm_dist is not None:
        module = wasm_dist / WASM_CLIENT_MODULE
        sidecar = build_identity.sidecar_path(module)
        if sidecar.is_file():
            # Staging may use newer glue or have no original build workspace.
            # The compiled resource list and pack must share the selected
            # client's inputs, rather than the staging checkout's overlays.
            verified_component_identity(root, module, "wasm-client", expected, frozen=True)
            expected = json.loads(sidecar.read_text(encoding="utf-8"))
    return verified_component_identity(root, artifact, "vanilla-assets", expected)


def frozen_compiler_inputs(
    root: Path, artifact: Path, role: str, pom: Path, resources: list[Path],
) -> tuple[Path, list[Path], dict[str, object] | None]:
    """Bind a staged copy to the retained compiler inputs at its POM target."""
    configuration = compiler_profile.read_teavm_configuration(pom)
    target = Path(configuration["targetDirectory"]).expanduser()
    if not target.is_absolute():
        target = pom.resolve().parent / target
    logical = target / artifact.name
    # Resolve dot segments without following an origin redirected into scratch.
    if Path(os.path.abspath(logical)) == Path(os.path.abspath(artifact)):
        return artifact, resources, None

    # Only the caller's original POM selects the origin; retained paths are
    # assertions to check, never paths from which to load arbitrary inputs.
    if role not in compiler_profile.EXPECTED_MAIN_CLASSES:
        raise RuntimeError(f"unsupported relocated compiler role: {role}")
    compiler_profile.validate_artifact_target(role, logical, pom, configuration)
    compiler_profile.validate_release_configuration(configuration)
    sidecar = compiler_profile.default_output(artifact)
    original_sidecar = compiler_profile.default_output(logical)
    # A staged dist may carry the Wasm pack. The retained JS compiler profile
    # still binds its original dist-local resources and generated resource lists.
    original_resources = [
        logical.parent / path.name if path.parent.absolute() == artifact.parent.absolute() else path
        for path in resources
    ]
    for path in (artifact, sidecar, pom, logical, original_sidecar, *resources, *original_resources):
        if any(part.is_symlink() for part in (path, *path.parents)):
            raise RuntimeError(f"relocated compiler input must not use symlinks: {path}")
    try:
        actual = json.loads(sidecar.read_text(encoding="utf-8"))
        if (
            not isinstance(actual, dict)
            or actual.get("role") != role
            or actual.get("compiler") != configuration
            or actual.get("pom") != compiler_profile.file_record(root, pom)
            or not isinstance(actual.get("artifact"), dict)
            or actual["artifact"].get("path") != compiler_profile.relative_name(root, logical)
        ):
            raise RuntimeError(f"compiler profile does not name the original POM target: {sidecar}")
        original = json.loads(original_sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"retained compiler profile is missing or invalid: {sidecar}") from exc
    if compiler_profile.canonical_json(actual) != compiler_profile.canonical_json(original):
        raise RuntimeError(f"staged compiler profile differs from retained original: {sidecar}")
    return logical, original_resources, actual


def verified_compiler_profile(
    root: Path,
    artifact: Path,
    role: str,
    pom: Path,
    resources: list[Path],
    frozen: bool = False,
) -> dict[str, object]:
    sidecar = compiler_profile.default_output(artifact)
    if frozen:
        # Inspect only explicit ancestors, before a missing leaf can mean legacy.
        for parent in reversed(pom.absolute().parents):
            if parent == Path(parent.anchor):
                continue
            try:
                mode = parent.lstat().st_mode
            except FileNotFoundError:
                break
            except (OSError, ValueError) as exc:
                raise RuntimeError(f"compiler POM ancestor is inaccessible: {parent}") from exc
            if stat.S_ISLNK(mode):
                raise RuntimeError(f"compiler POM ancestors must not use symlinks: {parent}")
            if not stat.S_ISDIR(mode):
                raise RuntimeError(f"compiler POM ancestor is not a directory: {parent}")
        try:
            mode = pom.lstat().st_mode
        except FileNotFoundError:
            # Legacy frozen dists may outlive their compiler workspace.
            # An existing POM, including an invalid path, must be validated.
            return {"frozen": True, "sidecar": sidecar.name}
        except (OSError, ValueError) as exc:
            raise RuntimeError(f"compiler POM is inaccessible: {pom}") from exc
        if stat.S_ISLNK(mode):
            raise RuntimeError(f"compiler POM must not use symlinks: {pom}")
    logical, compiler_resources, retained = (
        frozen_compiler_inputs(root, artifact, role, pom, resources)
        if frozen else (artifact, resources, None)
    )
    expected = compiler_profile.create_record(
        root,
        role,
        logical,
        pom,
        compiler_resources,
        True,
        artifact_input=artifact if logical != artifact else None,
    )
    try:
        actual = retained if retained is not None else json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"compiler profile is missing or invalid: {sidecar}") from exc
    if actual != expected or (
        frozen
        and compiler_profile.canonical_json(actual) != compiler_profile.canonical_json(expected)
    ):
        raise RuntimeError(f"compiler profile does not match current inputs: {sidecar}")
    return compiler_profile.release_summary(root, logical, pom, expected)


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
    # The unihex glyph table of that pack (UnihexGlyphTable); without it the client parses the zips.
    "gaius-unihex.bin.gz",
    "gaius-shaderc.wasm",
    "gaius-shaderc.js",
    "gaius-spvc.wasm",
    "gaius-spvc.js",
    # Optional: the shader results a release run recorded (bake-shader-cache.mjs); the loader primes its cache.
    "gaius-shader-prebake.bin",
    # Optional: the vanilla mip chains and atlas layouts BrowserAtlasPrebake replays for identical
    # inputs (port/tools/atlasprebake, staged by stage-web-runtime.py --atlas-prebake).
    "gaius-atlas-prebake.bin.gz",
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
      let frozenClientUrlPromise;
      window.__gaiusPortableLoadJsClient = () => {
        if (!frozenClientUrlPromise) {
          frozenClientUrlPromise = decodeAsset("classes", {gunzip: true, blobType: "text/javascript", parallel: true})
            .then((blob) => URL.createObjectURL(blob));
        }
        return frozenClientUrlPromise;
      };
      window.__gaiusPortableReleaseJsFallback = () => {
        const selection = window.__gaiusRuntimeSelection;
        if (!selection || selection.selected !== "wasm" || selection.mainStarted !== true
            || !window.__gaiusBoot || window.__gaiusBoot.serverRuntime !== "wasm") return false;
        if (!decoder.release(["classes", "server"])) return false;
        const discardedUrl = frozenClientUrlPromise;
        frozenClientUrlPromise = null;
        if (discardedUrl) Promise.resolve(discardedUrl).then((url) => URL.revokeObjectURL(url), () => {});
        portableServerGzipDataPromise = null;
        Object.defineProperty(window, "__gaiusSingleplayerServerGzipDataPromise", {
          configurable: true, enumerable: true, writable: true, value: null,
        });
        window.__gaiusPortableLoadJsClient = undefined;
        window.__gaiusPortableJsFallbackReleased = true;
        return true;
      };
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
          const result = new Promise((resolve, reject) => pending.set(id, {resolve, reject, name}));
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
              // Yield to the page every 16 Mi characters (four of the former 4 Mi-char parts), not
              // every few parts, so the cadence does not depend on the part size.
              let sinceYield = 0;
              for (let index = 0; index < parts.length; index++) {
                const element = parts[index];
                const text = element.textContent;
                meter.loaded = Math.min(info.bytes, meter.loaded + (text.length >> 3) * 7);
                element.textContent = "";
                if (element.parentNode) element.parentNode.removeChild(element);
                const part = {type: "part", id, text};
                messages.push(part); post(part);
                sinceYield += text.length;
                if (sinceYield >= 16 * 1024 * 1024) {
                  sinceYield = 0;
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
        function release(names) {
          const selected = new Set(names);
          if ([...pending.values()].some((entry) => selected.has(entry.name))) return false;
          for (const name of selected) {
            const parts = document.querySelectorAll('script[data-gaius-asset="' + name + '"]');
            for (const element of parts) {
              element.textContent = "";
              if (element.parentNode) element.parentNode.removeChild(element);
            }
            cache.delete(name);
            delete portablePayload.assets[name];
          }
          return true;
        }
        return {decode, release};
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
          wasm_server_dist: Path | None = None, frozen_js: bool = False) -> None:
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
    # Resource discovery is compiled into the active client.  Keep its pack
    # even when the portable page also embeds an older JavaScript fallback.
    pack_dist = wasm_dist.resolve() if wasm_dist is not None else dist
    vanilla_gzip = require_nonempty(pack_dist / "vanilla-assets.pack.gz")
    worker_path = require_nonempty(dist / "singleplayer-server-worker.js")
    relay_registry_path = require_nonempty(dist / "relay-nodes.json")
    frozen_js = bool(wasm_dist is not None and frozen_js)
    component_identities = {
        "classesJs": verified_component_identity(
            root, classes_js, "client", common_identity, frozen=frozen_js
        ),
        "singleplayerServerJs": verified_component_identity(
            root, server_js, "singleplayer-worker", common_identity, frozen=frozen_js
        ),
        "wasmHotpath": verified_component_identity(
            root, wasm_raw, "wasm-hotpath", common_identity, frozen=frozen_js
        ),
        "singleplayerWorkerBootstrap": verified_component_identity(
            root, worker_path, "worker-bootstrap", common_identity, frozen=frozen_js
        ),
        "vanillaAssetsPack": verified_pack_identity(
            root, vanilla_gzip, common_identity, wasm_dist
        ),
        "relayRegistry": verified_component_identity(
            root, relay_registry_path, "relay-registry", common_identity, frozen=frozen_js
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
            dist / "vanilla-assets.pack.gz",
        ], frozen=frozen_js,
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
        ], frozen=frozen_js,
    )
    signatures = verify_signatures(dist, profile, classes_hash)
    shader_toolchain = load_shader_toolchain(
        dist, index, profile, classes_js, root, common_identity, frozen=frozen_js,
        carried_from=wasm_dist.resolve() if frozen_js and wasm_dist is not None else None,
        wasm_dist=wasm_dist.resolve() if wasm_dist is not None else None,
    )
    worker = worker_path.read_text(encoding="utf-8")
    relay_nodes = load_nodes(relay_registry_path, root)

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
        if removed != int(shader_toolchain["pageLoader"]):
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
    portable = embed_launcher_icons(portable)
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
    frozen_js = "--frozen-js" in arguments
    if frozen_js:
        arguments.remove("--frozen-js")
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
        index = drop_site_boot_preloads((site / "index.html").read_text(encoding="utf-8").replace("\r\n", "\n"))
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
        # A single file embeds its payloads on any origin (https://gaius.fun/...): the payload table and
        # the embedded translator must not be gated on file:// (an older boot gates them and fetches siblings such as
        # singleplayer-server-worker.js that a host serving one file does not have).
        boot = boot.replace(
            'return isFile && (isPortable() || !site) && payloads',
            'return (isPortable() || (isFile && !site)) && payloads')
        boot = boot.replace(
            'if (isFile && (isPortable() || !site)) {\n      if (!hasFilePayload(WEBGPU_TRANSLATOR))',
            'if (isPortable() || (isFile && !site)) {\n      if (!hasFilePayload(WEBGPU_TRANSLATOR))')
        if "isFile && (isPortable() || !site)" in boot:
            raise RuntimeError("the staged boot still gates its file payloads on file://")
        boot = boot.replace(
            'const urls = {shadercJs: "gaius-shaderc.js", spvcJs: "gaius-spvc.js"};',
            'const urls = {};')
        boot = boot.replace(
            'const modules = [\n        filePayloadUrl("gaius-shaderc.wasm",',
            'const modules = [\n        filePayloadUrl("gaius-shaderc.js", "text/javascript").then((url) => { urls.shadercJs = url; }),\n        filePayloadUrl("gaius-spvc.js", "text/javascript").then((url) => { urls.spvcJs = url; }),\n        filePayloadUrl("gaius-shaderc.wasm",')

        site_manifest = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))
        runtime_modules, _runtime_hashes = wasm_only_runtime_modules(site, site_manifest)
        runtime_inline_names = json.dumps({name: True for name, _source in runtime_modules}, ensure_ascii=True,
                                          sort_keys=True, separators=(",", ":"))
        runtime_module_tags = "".join(
            f'<script data-gaius-boot-module="{name}">\n{source}</script>\n' for name, source in runtime_modules)
        index = index.replace(f'<script data-gaius-boot="v1" src="{boot_url.group(1)}"></script>',
                              runtime_module_tags + f'<script data-gaius-boot="v1">{boot}</script>')
        index = index.replace('loading classes.js...', 'loading WebAssembly GC client...')
        index = index.replace('classes.js loaded; calling', 'WebAssembly GC client loaded; calling')
        index = re.sub(r'const classesUrl = window\.__gaiusClassesUrl \|\|\s*\("classes\.js\?v=" \+ encodeURIComponent\(buildToken\)\);',
                       'const classesUrl = window.__gaiusClassesUrl;', index)
        client = require_nonempty(wasm_dist / "classes.wasm").read_bytes()
        server_module = server / "singleplayer-server.wasm" if server.is_dir() else None
        server_bytes = server_module.read_bytes() if server_module and server_module.is_file() else b""
        server_entry = ",server:'singleplayer-server.wasm'" if server_bytes else ""
        if site_manifest.get("profile") != profile:
            raise RuntimeError(
                f"Wasm-only site profile mismatch: expected {profile}, got {site_manifest.get('profile')}"
            )
        scripts = {name: require_nonempty(site / site_manifest["assets"][name]).read_text(encoding="utf-8")
                   for name in WASM_CLIENT_SCRIPTS}
        relay_nodes = load_nodes(site / "relay-nodes.json", root)
        kernel_index, kernel_payload_names = wasm_only_kernel_index(site, site_manifest)
        payload_names = list(FILE_PAYLOAD_FILES) + ["vanilla-sounds.pack"] + kernel_payload_names
        # The PNG decoder the site ships (build-pages-site.py add_png_kernel): the same module and
        # loader, switched on the same way, so STBImage decodes the panorama and atlases with it.
        png_module = wasm_dist / "gaius-png.wasm"
        png_script = wasm_dist / "png-kernel.js"
        png_kernel = png_module.is_file() and png_script.is_file() and png_module.read_bytes()[:4] == WASM_MAGIC
        if png_kernel:
            payload_names.insert(payload_names.index("gaius-hotpath.wasm") + 1, "gaius-png.wasm")
        payload_scripts = []
        payload_index = {}
        payload_elements = []
        # Experimental ordering: the page shell and boot script first, payloads after it, each
        # asset followed by a marker script that releases its decoder.
        payloads_last = os.environ.get("GAIUS_PORTABLE_PAYLOADS_LAST") == "1"
        part_chars = payload_part_chars()
        embedded_files: set[Path] = set()
        for name in payload_names:
            logical = site_manifest.get("assets", {}).get(name)
            candidate = None
            if name in {"classes.wasm", "singleplayer-server.wasm"}:
                # Embed the gzip sibling (about a quarter of the raw module): the page parses,
                # base64-decodes and hashes far fewer bytes, and the boot loader inflates it while
                # the module compiles.
                gz_logical = site_manifest.get("assets", {}).get(name + ".gz")
                if gz_logical and (site / gz_logical).is_file():
                    logical = gz_logical
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
            payload_elements.extend(payload_part_elements(key, encode_payload(stored), part_chars))
            if payloads_last:
                payload_elements.append(f'<script>window.__gaiusPayloadParsed("{key}")</script>')
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
        prefix = ("<script>window.__gaiusPayloadsLast=" + ("true" if payloads_last else "false") + ";window.__gaiusBridgeUrls=" + json.dumps(relay_nodes, ensure_ascii=True,
                                                                  separators=(',', ':')) + ";"
                  "window.__gaiusPortableBuild=true;window.__gaiusPortableWasmOnly=true;"
                  "window.__gaiusBootInline=" + runtime_inline_names + ";"
                  "window.__gaiusPortableVanillaSourceSha256=" + json.dumps(source_hash) + ";"
                  "window.__gaiusPortableRuntimes="
                  "{wasm:{client:'classes.wasm'" + server_entry + "}};window.__gaiusRuntimes="
                  "{wasm:{client:'classes.wasm'" + server_entry + "}};window.__gaiusFilePayloads="
                  + json.dumps(payload_index, separators=(',', ':')) + ";</script>"
                  + ("" if payloads_last else "".join(payload_elements)))
        prefix += r"""<script>
try {
  const _waits={};
  const _wait=(n)=>_waits[n]||(_waits[n]=(()=>{let r;const p=new Promise(f=>{r=f});return {p,r}})());
  window.__gaiusPayloadParsed=(n)=>_wait(n).r(true);
  window.__gaiusPayloadWait=(n)=>_wait(n).p;
  if (!window.__gaiusPayloadsLast) for (const _n of Object.keys(window.__gaiusFilePayloads)) _wait(_n).r(true);
  function _b64decode(name) {
    const info=window.__gaiusFilePayloads[name], nodes=document.querySelectorAll('script[data-gaius-asset="'+name+'"]');
    let text=''; nodes.forEach(n=>{text+=n.textContent; n.remove()});
    let out;
    try {
      if (typeof Uint8Array.fromBase64 === 'function') out=Uint8Array.fromBase64(text);
      else { const binary=atob(text); out=new Uint8Array(binary.length); for(let i=0;i<binary.length;i++) out[i]=binary.charCodeAt(i); }
    } catch (e) { throw new Error('portable asset '+name+' is not valid ASCII base64; the HTML was re-encoded or corrupted'); }
    if (out.length !== info.bytes) throw new Error('portable asset '+name+' length mismatch (expected '+info.bytes+', got '+out.length+')');
    return out;
  }
  async function _verify(name,b){const i=window.__gaiusFilePayloads[name]; if(!i.sha256)return b; const d=await crypto.subtle.digest('SHA-256',b), h=new Uint8Array(d); let a=''; for(const v of h)a+=v.toString(16).padStart(2,'0'); if(a!==i.sha256)throw new Error('portable asset '+name+' sha256 mismatch (expected '+i.sha256+', got '+a+')'); return b;}
  async function _bytes(name){const i=window.__gaiusFilePayloads[name], b=await _verify(name,await window.__gaiusPortablePayloadBytes[name]); if(!i.gzip)return b.buffer; const ds=new DecompressionStream('gzip'), w=ds.writable.getWriter(), r=new Response(ds.readable).arrayBuffer(); await w.write(b); await w.close(); return r;}
  function _blob(name,type){return _bytes(name).then(b=>{const url=URL.createObjectURL(new Blob([b],{type})); (window.__gaiusPortablePayloadUrls||(window.__gaiusPortablePayloadUrls={}))[name]=url; return url;});}
  window.__gaiusPortablePayloadBytes={};
  for (const _name of Object.keys(window.__gaiusFilePayloads)) {
    Object.defineProperty(window.__gaiusPortablePayloadBytes, _name, {configurable: true, enumerable: true,
      get() {
        const value = window.__gaiusPayloadWait(_name).then(() => _b64decode(_name));
        value.catch(()=>{});
        Object.defineProperty(window.__gaiusPortablePayloadBytes, _name,
          {configurable: true, enumerable: true, value});
        return value;
      }});
  }
  // The WebGPU translator is a file payload of the single file; announce it the way the boot
  // script reads an embedded translator, so any origin (https://gaius.fun/...) compiles it from the
  // document instead of fetching the relative runtime/webgpu/ URL (404 on a host serving one file).
  if (window.__gaiusFilePayloads['runtime/webgpu/gaius-shaderxlat.wasm']) {
    window.__gaiusPortableWebGpu = {translator: {sha256: window.__gaiusFilePayloads['runtime/webgpu/gaius-shaderxlat.wasm'].sha256,
      load: () => _bytes('runtime/webgpu/gaius-shaderxlat.wasm')}};
  }
  const _optional=(n,t)=>window.__gaiusFilePayloads[n]?_blob(n,t):Promise.resolve(null);
  function _lazyPromise(name, create) {
    Object.defineProperty(window, name, {configurable: true, get() {
      const promise = create();
      Object.defineProperty(window, name, {configurable: true, enumerable: true, writable: true, value: promise});
      return promise;
    }});
  }
  window.__gaiusWasmModuleCacheKey = 'wasmgc-js-string-v1:' + window.__gaiusFilePayloads['classes.wasm'].sha256;
  const _kernelIndex=__GAIUS_PORTABLE_KERNELS__;
  if (_kernelIndex) {
    const _kernels={};
    for (const _kn of Object.keys(_kernelIndex)) {
      const _e=_kernelIndex[_kn], _v={};
      for (const _variant of Object.keys(_e.variants)) {
        const _asset=_e.variants[_variant];
        _v[_variant]={load:()=>_bytes(_asset), sha256:_e.sha256&&_e.sha256[_variant]};
      }
      _kernels[_kn]={kinds:_e.kinds, memory:_e.memory, variants:_v};
    }
    window.__gaiusPortableKernels=_kernels;
  }
  // The client module compiles while the page is still starting (storage, assets, GPU, the boot
  // shim), not after it, and off the main thread: a dedicated Worker decodes the base64 text,
  // checks the payload hash, inflates the gzip bytes straight into compileStreaming and posts the
  // Module back. gaius-wasm-boot.js takes the Module as its source (same compile options) and
  // does not compile again. Any Worker failure falls back to the page path below, which repeats
  // the length and hash checks and reports their errors.
  // ?gaiusPortableCompile=late keeps the previous order (bytes handed over when the shim asks),
  // =main compiles early on the page thread.
  const _compileMode = (() => { try { return new URLSearchParams(location.search).get('gaiusPortableCompile') || ''; } catch (e) { return ''; } })();
  const _compileOptions = {builtins: ['js-string']};
  function _streamCompile(b) {
    const body = new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip'));
    return WebAssembly.compileStreaming(new Response(body, {headers: {'Content-Type': 'application/wasm'}}), _compileOptions);
  }
  function _pageCompile() {
    const raw = window.__gaiusPortablePayloadBytes['classes.wasm'];
    const checked = raw.then(b => _verify('classes.wasm', b));
    const module = Promise.all([checked, raw.then(_streamCompile)]).then(r => r[1]);
    module.catch(()=>{});
    return module;
  }
  // Start-function warm-up (?gaiusStartWarm=0 turns it off). Chrome compiles Wasm functions lazily,
  // on their first call, on the calling thread, and every isolate that holds the same Module shares
  // the compiled code. TeaVM's start function (teavm@initializer with its ~300 outlined chunks,
  // initReflection, initStrings: ~5 MB of bytecode, ~40 MB of Liftoff code) therefore used to be
  // compiled on the page thread inside instantiate. After posting the Module, the Worker now
  // instantiates it once itself with the same TeaVM runtime (its own realm, its own imports; the
  // start function calls no gaiusJspi/gaiusGpu import, those are inert stubs there), so that code
  // is already compiled when the page instantiates. The page's instance, imports and start
  // function are unchanged; only who compiles the code first differs. gaius-wasm-boot.js calls
  // __gaiusStartWarmStop right before the page instantiates: the Worker is terminated then (what
  // it compiled so far stays in the Module), so the two threads never compile the same functions
  // at the same time, and nothing waits for the warm-up.
  const _warmMode = (() => { try { return new URLSearchParams(location.search).get('gaiusStartWarm') || ''; } catch (e) { return ''; } })();
  const _workerSource = "onmessage=async(e)=>{const d=e.data;" +
    "if(d.warm!==undefined){const t=performance.now();try{importScripts(URL.createObjectURL(new Blob([d.warm],{type:'text/javascript'})));" +
    "const m=self.__gaiusModule;await TeaVM.wasmGC.load(m,{installImports(i){for(const x of WebAssembly.Module.imports(m))" +
    "if(x.module==='gaiusJspi'||x.module==='gaiusGpu')(i[x.module]??={})[x.name]??=()=>0;}});" +
    "postMessage({warmed:performance.now()-t});}catch(err){postMessage({warmError:String(err&&err.message||err)});}return;}" +
    "try{" +
    "let b;if(typeof Uint8Array.fromBase64==='function')b=Uint8Array.fromBase64(d.text);else{const s=atob(d.text);b=new Uint8Array(s.length);for(let i=0;i<s.length;i++)b[i]=s.charCodeAt(i);}" +
    "if(b.length!==d.bytes)throw new Error('length');const h=crypto.subtle.digest('SHA-256',b);" +
    "const m=WebAssembly.compileStreaming(new Response(new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip')),{headers:{'Content-Type':'application/wasm'}}),{builtins:['js-string']});" +
    "const v=new Uint8Array(await h);let a='';for(const x of v)a+=x.toString(16).padStart(2,'0');if(d.sha&&a!==d.sha)throw new Error('sha256');" +
    "self.__gaiusModule=await m;postMessage({module:self.__gaiusModule});}catch(err){postMessage({error:String(err&&err.message||err)});}};";
  function _workerCompile(info) {
    return window.__gaiusPayloadWait('classes.wasm').then(() => new Promise((resolve, reject) => {
      const nodes = document.querySelectorAll('script[data-gaius-asset="classes.wasm"]');
      let text = ''; nodes.forEach(n => { text += n.textContent; });
      const url = URL.createObjectURL(new Blob([_workerSource], {type: 'text/javascript'}));
      const worker = new Worker(url);
      URL.revokeObjectURL(url);
      let compiled = false, stopped = false;
      const stop = (mark) => {
        if (stopped) return;
        stopped = true;
        worker.terminate();
        window.__gaiusStartWarmStop = null;
        if (mark) performance.mark(mark);
      };
      const warm = () => {
        if (stopped) return;
        // The inert runtime copy is complete once the parser has moved past it.
        const runtime = document.querySelector('script[type="text/plain"][data-gaius-wasm-script="gaius-wasm-gc-runtime.js"]');
        if (!runtime || (!runtime.nextSibling && document.readyState === 'loading')) {
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', warm, {once: true});
          else stop();
          return;
        }
        performance.mark('gaius:startWarmBegin');
        setTimeout(() => stop('gaius:startWarmTimeout'), 20000);
        worker.postMessage({warm: runtime.textContent});
      };
      worker.onmessage = (e) => {
        const data = e.data || {};
        if (compiled) {
          window.__gaiusStartWarm = data.warmed !== undefined ? {ms: data.warmed} : {error: data.warmError};
          stop(data.warmed !== undefined ? 'gaius:startWarmed' : 'gaius:startWarmFailed');
          return;
        }
        if (data.module instanceof WebAssembly.Module) {
          compiled = true;
          nodes.forEach(n => n.remove());
          if (_warmMode === '0') stop();
          else window.__gaiusStartWarmStop = () => stop('gaius:startWarmStopped');
          resolve(data.module);
          warm();
        } else { stop(); reject(new Error(data.error || 'no module')); }
      };
      worker.onerror = (e) => {
        if (compiled) { window.__gaiusStartWarm = {error: String(e && e.message || 'worker error')}; stop('gaius:startWarmFailed'); return; }
        stop();
        reject(new Error(e && e.message || 'worker error'));
      };
      worker.postMessage({text, bytes: info.bytes, sha: info.sha256});
    }));
  }
  const _earlyCompile = (() => {
    try {
      const info = window.__gaiusFilePayloads['classes.wasm'];
      if (!info || !info.gzip || _compileMode === 'late') return null;
      if (typeof WebAssembly.compileStreaming !== 'function' || typeof DecompressionStream !== 'function' ||
          typeof Response !== 'function') return null;
      performance.mark('gaius:portableCompileStart');
      const module = (_compileMode === 'main' || typeof Worker !== 'function' ? _pageCompile()
        : _workerCompile(info).catch(() => { performance.mark('gaius:portableCompileFallback'); return _pageCompile(); }))
        .then(m => { performance.mark('gaius:portableCompiled'); return m; });
      module.catch(()=>{});
      return module;
    } catch (e) { return null; }
  })();
  if (_earlyCompile) window.__gaiusWasmSource = _earlyCompile;
  else _lazyPromise('__gaiusWasmSource', () => _bytes('classes.wasm'));
  if (window.__gaiusFilePayloads['gaius-png.wasm']) {
    window.__gaiusPngKernelEnabled = true;
    _lazyPromise('__gaiusPngWasmBytes', () => _bytes('gaius-png.wasm'));
  }
  _lazyPromise('__gaiusVanillaAssetsCompressedPromise', () =>
    window.__gaiusPortablePayloadBytes['vanilla-assets.pack.gz'].then(b => _verify('vanilla-assets.pack.gz', b)));
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
        if png_kernel:
            inert += "<script>" + inline_safe(png_script.read_text(encoding="utf-8"), "png-kernel.js") + "</script>"
        if server_bytes:
            worker_script = require_nonempty(server / "gaius-wasm-worker.js").read_text(encoding="utf-8")
            inert += f'<script type="text/plain" data-gaius-wasm-script="gaius-wasm-worker.js">{worker_script}</script>'
        output.parent.mkdir(parents=True, exist_ok=True)
        prefix = prefix.replace("__GAIUS_PORTABLE_KERNELS__", json.dumps(
            kernel_index or None, ensure_ascii=True, sort_keys=True, separators=(",", ":")))
        inert += wasm_only_server_worker_scripts(site, site_manifest)
        kernel_worker = site_manifest.get("assets", {}).get("kernels/kernel-worker.js")
        if kernel_index and isinstance(kernel_worker, str) and (site / kernel_worker).is_file():
            inert += ('<script type="text/plain" id="gaius-kernel-worker-source">\n'
                      + inline_safe((site / kernel_worker).read_text(encoding="utf-8"), "kernel-worker.js")
                      + "</script>")
        portable = "<!doctype html><meta charset=\"utf-8\">" + prefix + inert + index
        if payloads_last:
            portable += "".join(payload_elements)
        portable = embed_launcher_icons(drop_site_notice_link(portable))
        validate_portable_no_relative_urls(portable)
        output.write_text(add_license_notice(portable, root), encoding="utf-8", newline="\n")
        write_license_sidecar(output, root)
        print(f"Portable Wasm-only HTML: {output} ({output.stat().st_size} bytes)")
    else:
        build(dist, output, wasm_dist=wasm_dist, wasm_server_dist=wasm_server_dist, frozen_js=frozen_js)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
