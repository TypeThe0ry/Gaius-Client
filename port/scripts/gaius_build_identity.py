#!/usr/bin/env python3
"""Create and verify deterministic identities for Gaius build artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path


def _native_external_path(value: str) -> Path:
    """Accept Git-Bash /c/... paths when running Windows Python."""
    if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", value):
        value = f"{value[1].upper()}:{value[2:]}"
    return Path(value).expanduser()


IDENTITY_KIND = "gaius-build-identity"
IDENTITY_SCHEMA_VERSION = 2
INPUT_POLICY = "gaius-runtime-inputs-v1"
PROTOCOL_POLICY = "gaius-browser-protocol-v1"
OVERLAY_POLICY = "gaius-active-overlay-inputs-v1"
WORLDGEN_TELEMETRY_MODES = frozenset(("task-pulsed", "checkpoint-only"))

SOURCE_DIRECTORIES = (
    "port/src/main",
    # Vanilla-facing source sets, selected by profile fields (generate-pom.sh stages them over
    # port/src/main/java, build-overlays.sh over the client overrides; port/tools/sourcesets).
    "port/mc/shared",
    "port/tools/sourcesets",
    "port/overrides",
    # The WebGPU core (every profile) and the render seams (selected by the profile's
    # renderSeam), compiled into the client overlay by build-overlays.sh.
    "port/gpu",
    "port/mc/seams",
    "port/tools/src/main",
    "port/wasm/hotpath",
    # The v0.4 page runtime every distribution ships next to (or inlined into) the launcher:
    # boot script, Service Worker, kernel runtime/worker/job codecs and the quality layer.
    "port/web/boot",
    "port/web/sw",
    "port/web/kernels",
    "port/web/runtime",
)
SOURCE_FILES = (
    "VERSION",
    "port/config.json",
    "port/web/singleplayer/index.html",
    "port/web/singleplayer/server-worker-bootstrap.js",
    "port/scripts/gaius_build_identity.py",
    "port/scripts/teavm-compiler-profile.py",
    "port/scripts/version-profile.sh",
    "port/scripts/build-overlays.sh",
    "port/scripts/remap-client.sh",
    "port/scripts/generate-pom.sh",
    "port/scripts/build-teavm.sh",
    "port/scripts/build-teavm-server-worker.sh",
    "port/scripts/teavm-publication-gate.sh",
    "port/scripts/build-teavm-release.sh",
    "port/scripts/build-version-release.sh",
    "port/scripts/postprocess-teavm-js.py",
    "port/scripts/postprocess-index-html.py",
    "port/web/launcher/index.template.html",
    "port/web/launcher/boot-art.css",
    "port/web/launcher/boot-art.js",
    "port/web/launcher/touch-controls.css",
    "port/web/launcher/touch-controls.js",
    "port/web/launcher/world-archive.js",
    "port/scripts/build-vanilla-assets-pack.py",
    "port/scripts/build-wasm-hotpath.sh",
    "port/scripts/generate-wasm-hotpath.py",
)
# WebAssembly GC artifacts (build-wasmgc.sh): classes.wasm and singleplayer-server.wasm.  Their
# sidecars carry the common identity above plus a "wasm" record: the TeaVM settings, the
# binaryen step (wasm-opt-release.sh report), the symbol file, and the inputs that only the
# WebAssembly GC build reads, hashed under their own policy so the identity of every
# JavaScript sidecar stays what it was.
WASM_ROLES = ("wasm-client", "wasm-singleplayer-worker")
WASM_INPUT_POLICY = "gaius-wasmgc-build-inputs-v1"
WASM_BUILD_POLICY = "gaius-wasmgc-build-v1"
WASM_SOURCE_FILES = (
    "port/scripts/build-wasmgc.sh",
    "port/scripts/wasm-opt-release.sh",
    "port/scripts/overlay-lock.sh",
    "port/scripts/stage-web-runtime.py",
    "port/tools/wasm/wasm-binary.mjs",
    "port/tools/wasm/wasm-symbols.mjs",
)
# The profile's patch manifests (patch-manifest.wasmgc.json: the wasmgc patch chain), SPI
# fingerprint and usage lock; the wasmgc-only source set when a tree has one.
WASM_SOURCE_DIRECTORIES = (
    "port/src/target-wasmgc",
)
WASM_BUILD_KEYS = (
    "target", "role", "release", "optimization", "assertionsRemoved", "telemetryStripped",
    "wasmOpt", "symbols",
)
PROTOCOL_FILES = (
    "port/config.json",
    "port/web/singleplayer/server-worker-bootstrap.js",
    "port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
    "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
    "port/src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
    "port/src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java",
    "port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java",
    "port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserInlineEventLoop.java",
    "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
    "port/scripts/postprocess-teavm-js.py",
)
# The patch code of MinecraftClientPatcher that moved into the domain packages
# (port/tools/patchkit/README.md "Domains"): the network and integrated-server patches among it
# keep defining the client/Worker protocol.
PROTOCOL_DIRECTORIES = (
    "port/tools/src/main/java/dev/gaius/tools/patches",
)


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_profile(root: Path) -> tuple[dict, str, Path, bytes]:
    config_path = root / "port" / "config.json"
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        relative_profile = (
            os.environ.get("GAIUS_VERSION_PROFILE_PATH")
            or config["versionProfile"]
        )
    except (OSError, UnicodeDecodeError, ValueError, KeyError) as exc:
        raise RuntimeError(f"could not load active version profile from {config_path}") from exc
    if not isinstance(relative_profile, str) or not relative_profile:
        raise RuntimeError("port/config.json versionProfile must be a non-empty string")

    versions = (root / "port" / "versions").resolve()
    profile_path = (root / "port" / relative_profile).resolve()
    try:
        profile_path.relative_to(versions)
    except ValueError as exc:
        raise RuntimeError("versionProfile must point inside port/versions") from exc
    if profile_path.suffix != ".json" or not profile_path.is_file():
        raise RuntimeError(f"active version profile is missing or invalid: {profile_path}")

    profile_bytes = profile_path.read_bytes()
    try:
        profile = json.loads(profile_bytes.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise RuntimeError(f"active version profile is not valid JSON: {profile_path}") from exc
    if not isinstance(profile, dict) or not isinstance(profile.get("id"), str):
        raise RuntimeError(f"active version profile has no id: {profile_path}")
    if profile.get("clientDistribution") not in {"named", "obfuscated-with-mappings"}:
        raise RuntimeError(f"active version profile has invalid clientDistribution: {profile_path}")
    if not isinstance(profile.get("protocolVersion"), int):
        raise RuntimeError(f"active version profile has no integer protocolVersion: {profile_path}")
    if not isinstance(profile.get("worldVersion"), int) or profile["worldVersion"] < 0:
        raise RuntimeError(f"active version profile has no valid worldVersion: {profile_path}")
    _validate_worldgen_telemetry_mode(profile)
    _validate_storage(profile)
    return profile, Path(relative_profile).as_posix(), profile_path, profile_bytes


def _validate_worldgen_telemetry_mode(profile: dict, *, required: bool = False) -> str | None:
    """Validate the explicit worldgen evidence mode when a profile declares it.

    Older fixture profiles used by the artifact helper predate this field.  They
    remain usable for identity-only tests, but an actual version profile is
    required to declare one of the two modes by check-version-profile.mjs.
    """
    mode = profile.get("worldgenTelemetryMode")
    if mode is None and not required:
        return None
    if mode not in WORLDGEN_TELEMETRY_MODES:
        raise RuntimeError(
            f"active version profile {profile.get('id', '<unknown>')} "
            "worldgenTelemetryMode must be 'task-pulsed' or 'checkpoint-only' "
            f"(received {mode!r})"
        )
    return mode


def _validate_storage(profile: dict) -> dict[str, object]:
    storage = profile.get("storage")
    if not isinstance(storage, dict):
        raise RuntimeError(
            f"active version profile {profile.get('id', '<unknown>')} storage must be an object"
        )
    schema = storage.get("schema")
    profile_id = profile.get("id")
    if not isinstance(profile_id, str) or not profile_id:
        raise RuntimeError("active version profile id must be a non-empty string")
    if not isinstance(schema, int) or isinstance(schema, bool) or schema != 2:
        raise RuntimeError(
            f"active version profile {profile_id} storage.schema must be exactly 2 "
            f"(received {schema!r})"
        )
    expected = {
        "databaseName": f"gaius-fs-v2-{profile_id}",
        "prefix": f"gaius.fs.v2:{profile_id}:",
        "opfsDirectory": f"regions-v2-{profile_id}",
    }
    for key, expected_value in expected.items():
        value = storage.get(key)
        if not isinstance(value, str) or not value:
            raise RuntimeError(f"active version profile storage.{key} must be non-empty")
        if value != expected_value:
            raise RuntimeError(
                f"active version profile {profile_id} storage.{key} must be exactly "
                f"{expected_value!r} (received {value!r})"
            )
    return storage


def _input_paths(root: Path, relative_profile: str, *, protocol: bool) -> list[Path]:
    relative_paths = list(PROTOCOL_FILES if protocol else SOURCE_FILES)
    relative_paths.append(f"port/{relative_profile}")
    paths: dict[str, Path] = {}
    for relative in relative_paths:
        path = root / relative
        if path.is_file():
            paths[path.relative_to(root).as_posix()] = path
    for relative in PROTOCOL_DIRECTORIES if protocol else SOURCE_DIRECTORIES:
        directory = root / relative
        if not directory.is_dir():
            continue
        for path in directory.rglob("*"):
            if path.is_file():
                paths[path.relative_to(root).as_posix()] = path
    return [paths[name] for name in sorted(paths)]


def _hash_named_input_set(
    inputs: list[tuple[str, Path]],
    policy: str,
) -> dict[str, object]:
    digest = hashlib.sha256()
    digest.update(policy.encode("ascii") + b"\0")
    total_bytes = 0
    for relative, path in inputs:
        size = path.stat().st_size
        file_hash = sha256_file(path)
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(size).encode("ascii"))
        digest.update(b"\0")
        digest.update(file_hash.encode("ascii"))
        digest.update(b"\n")
        total_bytes += size
    return {
        "policy": policy,
        "sha256": digest.hexdigest(),
        "fileCount": len(inputs),
        "bytes": total_bytes,
    }


def _hash_input_set(root: Path, paths: list[Path], policy: str) -> dict[str, object]:
    return _hash_named_input_set(
        [(path.relative_to(root).as_posix(), path) for path in paths],
        policy,
    )


def _overlay_inputs(root: Path, profile: dict) -> list[tuple[str, Path]]:
    try:
        config = json.loads((root / "port" / "config.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError) as exc:
        raise RuntimeError("could not read port/config.json for overlay identity") from exc
    teavm_version = config.get("teaVMVersion")
    if not isinstance(teavm_version, str) or not teavm_version:
        raise RuntimeError("port/config.json teaVMVersion must be a non-empty string")

    version = profile["id"]
    work = root / "port" / "work" / version
    configured_overlay = os.environ.get("GAIUS_OVERLAY_DIRECTORY")
    if configured_overlay:
        overlays = _native_external_path(configured_overlay)
    elif os.environ.get("GAIUS_BUILD_ROOT") or os.environ.get("GAIUS_VERSION_PROFILE_PATH"):
        # An isolated build root implies a version-scoped overlay unless the
        # caller explicitly supplied another directory.  This keeps sidecar
        # identity hashes aligned with the POM and TeaVM inputs.
        overlays = root / "port" / "work" / "overlays" / profile["id"]
    else:
        overlays = root / "port" / "work" / "overlays"
    if not overlays.is_absolute():
        overlays = root / overlays
    overlays = overlays.resolve()
    metadata_candidates = (work / "version.json", work / "client-version.json")
    candidates = [
        *metadata_candidates,
        overlays / f"client-named-{version}-gaius.jar",
        overlays / f"teavm-classlib-{teavm_version}-gaius.jar",
        overlays / f"teavm-core-{teavm_version}-gaius.jar",
        # build-overlays.sh writes BRINGUP only for bring-up builds, so the
        # identity of overlays built without skipped patches is unchanged.
        overlays / "BRINGUP",
    ]
    metadata_path = metadata_candidates[0]
    if metadata_path.is_file():
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, ValueError) as exc:
            raise RuntimeError(f"active version metadata is invalid: {metadata_path}") from exc
        if not isinstance(metadata, dict) or metadata.get("id") != version:
            raise RuntimeError(
                f"active version metadata does not match profile {version}: {metadata_path}"
            )
        for library in metadata.get("libraries", []):
            if not isinstance(library, dict):
                continue
            downloads = library.get("downloads")
            artifact = downloads.get("artifact") if isinstance(downloads, dict) else None
            relative = artifact.get("path") if isinstance(artifact, dict) else None
            if not isinstance(relative, str) or not relative:
                continue
            relative_path = Path(relative)
            if relative_path.is_absolute() or ".." in relative_path.parts:
                raise RuntimeError(f"active library metadata path is unsafe: {relative}")
            candidates.append(overlays / "libraries" / relative_path)

    isolated = bool(
        os.environ.get("GAIUS_BUILD_ROOT")
        or os.environ.get("GAIUS_VERSION_PROFILE_PATH")
    )
    logical_overlay_root = Path("port/work/overlays")
    if isolated:
        logical_overlay_root /= version

    unique: dict[str, Path] = {}
    for path in candidates:
        if not path.is_file():
            continue
        if path in metadata_candidates:
            logical_name = path.relative_to(root).as_posix()
        else:
            try:
                overlay_relative = path.relative_to(overlays)
            except ValueError:
                logical_name = path.relative_to(root).as_posix()
            else:
                # Overlay identities describe their logical profile-scoped input
                # names, not the host-specific staging directory.  Cluster and CI
                # builds deliberately place overlays outside the checkout; hashing
                # an absolute path would either fail or make identical artifacts
                # produce different identities on every worker.
                logical_name = (logical_overlay_root / overlay_relative).as_posix()
        unique[logical_name] = path
    return [(name, unique[name]) for name in sorted(unique)]


def current_build_identity(root: Path) -> dict[str, object]:
    root = root.resolve()
    profile, relative_profile, _profile_path, profile_bytes = _load_profile(root)
    worldgen_telemetry_mode = _validate_worldgen_telemetry_mode(profile)
    source = _hash_input_set(
        root,
        _input_paths(root, relative_profile, protocol=False),
        INPUT_POLICY,
    )
    protocol = _hash_input_set(
        root,
        _input_paths(root, relative_profile, protocol=True),
        PROTOCOL_POLICY,
    )
    overlay = _hash_named_input_set(_overlay_inputs(root, profile), OVERLAY_POLICY)
    protocol["minecraftProtocolVersion"] = profile["protocolVersion"]
    profile_identity = {
        "id": profile["id"],
        "path": relative_profile,
        "sha256": sha256_bytes(profile_bytes),
        "clientDistribution": profile["clientDistribution"],
        "protocolVersion": profile["protocolVersion"],
        "worldVersion": profile["worldVersion"],
        "worldgenTelemetryMode": worldgen_telemetry_mode,
        "storage": profile["storage"],
    }
    compatibility_payload = {
        "schemaVersion": IDENTITY_SCHEMA_VERSION,
        "profile": profile_identity,
        "sourceSha256": source["sha256"],
        "protocolSha256": protocol["sha256"],
        "overlaySha256": overlay["sha256"],
    }
    return {
        "schemaVersion": IDENTITY_SCHEMA_VERSION,
        "profile": profile_identity,
        "worldVersion": profile["worldVersion"],
        "worldgenTelemetryMode": worldgen_telemetry_mode,
        "storage": profile["storage"],
        "source": source,
        "protocol": protocol,
        "overlay": overlay,
        "compatibilitySha256": sha256_bytes(
            canonical_json(compatibility_payload).encode("ascii")
        ),
    }


def _wasm_input_paths(root: Path, profile_id: str) -> list[Path]:
    paths: dict[str, Path] = {}
    for relative in WASM_SOURCE_FILES:
        path = root / relative
        if path.is_file():
            paths[relative] = path
    for relative in (*WASM_SOURCE_DIRECTORIES, f"port/mc/profiles/{profile_id}"):
        directory = root / relative
        if not directory.is_dir():
            continue
        for path in directory.rglob("*"):
            if path.is_file():
                paths[path.relative_to(root).as_posix()] = path
    return [paths[name] for name in sorted(paths)]


def wasm_inputs(root: Path) -> dict[str, object]:
    root = root.resolve()
    profile, _relative, _path, _bytes = _load_profile(root)
    return _hash_input_set(root, _wasm_input_paths(root, profile["id"]), WASM_INPUT_POLICY)


def _wasm_record(root: Path, build: object) -> dict[str, object]:
    if not isinstance(build, dict):
        raise RuntimeError("the WebAssembly GC build record must be an object")
    missing = [key for key in WASM_BUILD_KEYS if key not in build]
    if missing or build.get("target") != "wasmgc":
        raise RuntimeError(f"the WebAssembly GC build record is incomplete: missing {missing or ['target=wasmgc']}")
    record = {key: build[key] for key in WASM_BUILD_KEYS}
    record["policy"] = WASM_BUILD_POLICY
    record["inputs"] = wasm_inputs(root)
    return record


def sidecar_path(artifact: Path) -> Path:
    return artifact.with_name(f"{artifact.name}.build.json")


def _write_text_atomically(target: Path, text: str) -> None:
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
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
        if os.name == "nt":
            return
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


def create_sidecar(
    root: Path,
    role: str,
    artifact: Path,
    wasm_build: object | None = None,
) -> dict[str, object]:
    artifact = artifact.resolve()
    if not artifact.is_file() or artifact.stat().st_size == 0:
        raise RuntimeError(f"build artifact is missing or empty: {artifact}")
    if (role in WASM_ROLES) != (wasm_build is not None):
        raise RuntimeError(
            f"role {role} {'needs' if role in WASM_ROLES else 'takes no'} WebAssembly GC build record"
        )
    identity = current_build_identity(root)
    record: dict[str, object] = {
        "kind": IDENTITY_KIND,
        "schemaVersion": IDENTITY_SCHEMA_VERSION,
        "role": role,
        "profile": identity["profile"],
        "worldVersion": identity["worldVersion"],
        "worldgenTelemetryMode": identity["worldgenTelemetryMode"],
        "storage": identity["storage"],
        "source": identity["source"],
        "protocol": identity["protocol"],
        "overlay": identity["overlay"],
        "compatibilitySha256": identity["compatibilitySha256"],
        "artifact": {
            "name": artifact.name,
            "sha256": sha256_file(artifact),
            "bytes": artifact.stat().st_size,
        },
    }
    if wasm_build is not None:
        record["wasm"] = _wasm_record(root, wasm_build)
        wasm_opt = record["wasm"]["wasmOpt"]
        if isinstance(wasm_opt, dict) and wasm_opt.get("applied") is True:
            output = wasm_opt.get("output")
            if not isinstance(output, dict) or output.get("sha256") is None:
                raise RuntimeError("the wasm-opt report names no output module")
    record["identitySha256"] = sha256_bytes(canonical_json(record).encode("ascii"))
    return record


def write_sidecar(
    root: Path,
    role: str,
    artifact: Path,
    output: Path | None = None,
    wasm_build: object | None = None,
) -> Path:
    artifact = artifact.resolve()
    output = (output or sidecar_path(artifact)).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    record = create_sidecar(root, role, artifact, wasm_build)
    _write_text_atomically(output, f"{canonical_json(record)}\n")
    return output


def verify_sidecar(
    root: Path,
    role: str,
    artifact: Path,
    sidecar: Path | None = None,
    expected_common: dict[str, object] | None = None,
) -> dict[str, object]:
    artifact = artifact.resolve()
    sidecar = (sidecar or sidecar_path(artifact)).resolve()
    if not artifact.is_file() or artifact.stat().st_size == 0:
        raise RuntimeError(f"build artifact is missing or empty: {artifact}")
    try:
        record = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError) as exc:
        raise RuntimeError(f"build identity sidecar is missing or invalid: {sidecar}") from exc
    if not isinstance(record, dict):
        raise RuntimeError(f"build identity sidecar is not an object: {sidecar}")

    expected_common = expected_common or current_build_identity(root)
    for key in (
        "profile",
        "worldVersion",
        "worldgenTelemetryMode",
        "storage",
        "source",
        "protocol",
        "overlay",
        "compatibilitySha256",
    ):
        if record.get(key) != expected_common[key]:
            raise RuntimeError(f"build identity {key} does not match current inputs: {sidecar}")
    if (
        record.get("kind") != IDENTITY_KIND
        or record.get("schemaVersion") != IDENTITY_SCHEMA_VERSION
        or record.get("role") != role
    ):
        raise RuntimeError(f"build identity contract or role does not match: {sidecar}")
    expected_artifact = {
        "name": artifact.name,
        "sha256": sha256_file(artifact),
        "bytes": artifact.stat().st_size,
    }
    if record.get("artifact") != expected_artifact:
        raise RuntimeError(f"build identity artifact hash does not match: {artifact}")
    wasm = record.get("wasm")
    if role in WASM_ROLES:
        if (
            not isinstance(wasm, dict)
            or wasm.get("policy") != WASM_BUILD_POLICY
            or wasm.get("target") != "wasmgc"
            or any(key not in wasm for key in WASM_BUILD_KEYS)
        ):
            raise RuntimeError(f"build identity has no WebAssembly GC build record: {sidecar}")
        if wasm.get("inputs") != wasm_inputs(root):
            raise RuntimeError(f"build identity wasm inputs do not match current inputs: {sidecar}")
    elif wasm is not None:
        raise RuntimeError(f"build identity of role {role} carries a WebAssembly GC build record: {sidecar}")
    identity_hash = record.get("identitySha256")
    unsigned = dict(record)
    unsigned.pop("identitySha256", None)
    if identity_hash != sha256_bytes(canonical_json(unsigned).encode("ascii")):
        raise RuntimeError(f"build identity self hash does not match: {sidecar}")
    return record


# Compatibility fields of a frozen build (one built from an earlier tree and published again):
# sidecar key -> what changed when it differs from the current tree.
FROZEN_COMPATIBILITY = (
    ("storage", "storage"),
    ("worldVersion", "save"),
    ("profile.protocolVersion", "minecraft-protocol"),
    ("protocol.sha256", "protocol"),
)


def _field(record: dict, dotted: str) -> object:
    value: object = record
    for key in dotted.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    return value


def check_frozen(
    root: Path,
    role: str,
    artifact: Path,
    sidecar: Path | None = None,
) -> dict[str, object]:
    """Integrity of a frozen artifact's sidecar, and which compatibility fields differ now.

    The artifact must still be the one its sidecar names (bytes and sha256) and the sidecar
    must be intact (self hash, kind, schema, role, profile id); its source and overlay identity
    are those of the tree it was built from and are not compared.
    """
    artifact = artifact.resolve()
    sidecar = (sidecar or sidecar_path(artifact)).resolve()
    try:
        record = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError) as exc:
        raise RuntimeError(f"build identity sidecar is missing or invalid: {sidecar}") from exc
    if not isinstance(record, dict):
        raise RuntimeError(f"build identity sidecar is not an object: {sidecar}")
    unsigned = dict(record)
    identity_hash = unsigned.pop("identitySha256", None)
    if identity_hash != sha256_bytes(canonical_json(unsigned).encode("ascii")):
        raise RuntimeError(f"build identity self hash does not match: {sidecar}")
    if (
        record.get("kind") != IDENTITY_KIND
        or record.get("schemaVersion") != IDENTITY_SCHEMA_VERSION
        or record.get("role") != role
    ):
        raise RuntimeError(f"build identity contract or role does not match: {sidecar}")
    if not artifact.is_file() or record.get("artifact") != {
        "name": artifact.name,
        "sha256": sha256_file(artifact),
        "bytes": artifact.stat().st_size,
    }:
        raise RuntimeError(f"frozen artifact is not the one its sidecar names: {artifact}")
    current = current_build_identity(root)
    if _field(record, "profile.id") != _field(current, "profile.id"):
        raise RuntimeError(f"frozen artifact belongs to profile {_field(record, 'profile.id')}: {artifact}")
    differences = [
        {"field": field, "trigger": trigger, "frozen": _field(record, field), "current": _field(current, field)}
        for field, trigger in FROZEN_COMPATIBILITY
        if _field(record, field) != _field(current, field)
    ]
    return {
        "artifact": artifact.name,
        "role": role,
        "compatibilitySha256": record.get("compatibilitySha256"),
        "currentCompatibilitySha256": current["compatibilitySha256"],
        "differences": differences,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("write", "verify", "frozen"):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--root", type=Path, required=True)
        subparser.add_argument("--role", required=True)
        subparser.add_argument("--artifact", type=Path, required=True)
        subparser.add_argument("--sidecar", type=Path)
        if command == "write":
            subparser.add_argument(
                "--wasm-build",
                type=Path,
                help="JSON record of the WebAssembly GC build (roles wasm-client, wasm-singleplayer-worker)",
            )
    common = subparsers.add_parser("common")
    common.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "common":
        print(canonical_json(current_build_identity(args.root)))
        return 0
    if args.command == "write":
        wasm_build = None
        if args.wasm_build is not None:
            try:
                wasm_build = json.loads(args.wasm_build.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, ValueError) as exc:
                raise RuntimeError(f"WebAssembly GC build record is missing or invalid: {args.wasm_build}") from exc
        output = write_sidecar(args.root, args.role, args.artifact, args.sidecar, wasm_build)
        print(f"Build identity: {output}")
        return 0
    if args.command == "frozen":
        print(canonical_json(check_frozen(args.root, args.role, args.artifact, args.sidecar)))
        return 0
    record = verify_sidecar(args.root, args.role, args.artifact, args.sidecar)
    print(record["compatibilitySha256"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
