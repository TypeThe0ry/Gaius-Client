"""Shared validation for the built-in relay declarations shipped in artifacts."""

from __future__ import annotations

import json
from pathlib import Path


def _read_registry(path: Path) -> dict:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"could not read relay registry: {path}") from error
    if (document.get("kind") != "gaius-relay-registry"
            or document.get("protocolVersion") != 1
            or not isinstance(document.get("nodes"), list)):
        raise RuntimeError(f"relay registry is incompatible: {path}")
    return document


def canonical_nodes(root: Path) -> list[dict]:
    """Read the repository registry, the only source of built-in relay data."""
    nodes = _read_registry(root / "relay-nodes.json")["nodes"]
    if not nodes:
        raise RuntimeError("relay-nodes.json has no built-in relay")
    return nodes


def load_nodes(path: Path, root: Path, limit: int = 64) -> list[dict]:
    """Validate a copied registry and retain the canonical built-ins."""
    nodes = _read_registry(path)["nodes"]
    if not nodes:
        raise RuntimeError(f"relay registry has no built-in relay: {path}")
    bundled = nodes[:limit]
    required = canonical_nodes(root)
    if nodes != required:
        raise RuntimeError(f"relay registry differs from repository source: {path}")
    for expected in required:
        if expected not in bundled:
            identity = expected.get("id") or expected.get("url") or "unknown"
            raise RuntimeError(f"relay registry is missing built-in relay {identity}: {path}")
    return required


def assert_artifact(path: Path, root: Path) -> None:
    """Fail a release gate when an HTML artifact has no embedded built-in relay."""
    text = path.read_text(encoding="utf-8")
    if "__gaiusBridgeUrls" not in text:
        raise RuntimeError(f"artifact has no built-in relay declaration: {path}")
    for node in canonical_nodes(root):
        for key in ("id", "name", "url", "priority"):
            value = node.get(key)
            if value is None or json.dumps(value, ensure_ascii=True) not in text:
                raise RuntimeError(f"artifact is missing built-in relay {node.get('id', 'unknown')}: {path}")
