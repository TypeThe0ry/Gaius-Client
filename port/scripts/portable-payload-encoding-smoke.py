#!/usr/bin/env python3
"""Check portable payload text survives common file handling transformations."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path


SCRIPT = Path(__file__).resolve()
SPEC = importlib.util.spec_from_file_location("build_portable_html", SCRIPT.with_name("build-portable-html.py"))
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load build-portable-html.py")
PORTABLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PORTABLE)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def decode_document(raw: bytes) -> str:
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        text = raw.decode("utf-16")
    else:
        text = raw.decode("utf-8-sig")
    # Old v0.4 pages use b7; normalize only the element type so the shared parser can audit
    # both generations without treating the legacy artifact as a new-format page.
    return text.replace('text/x-gaius-b7', PORTABLE.PAYLOAD_TYPE)


def inspect(label: str, raw: bytes) -> dict[str, object]:
    try:
        text = decode_document(raw)
        decoded = PORTABLE.parse_portable_payload(text)
        return {"label": label, "status": "pass", "assets": len(decoded),
                "htmlSha256": digest(raw)}
    except Exception as exc:  # noqa: BLE001 - the smoke records the concrete corruption error.
        return {"label": label, "status": "fail", "error": str(exc), "htmlSha256": digest(raw)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    artifact_bytes = args.artifact.stat().st_size
    with args.artifact.open("rb") as stream:
        sample = stream.read(4 * 1024 * 1024)
    encoding = "gaius-b64-v1" if b"gaius-b64-v1" in sample else "gaius-b7-v1"
    payload = PORTABLE.PortablePayload()
    payload.add("smoke", bytes(range(256)) * 40 + b"<\r\n\0", gzipped=True)
    text = payload.html() + "const portablePayload = " + json.dumps(payload.index()) + ";\n"
    original = text.encode("utf-8")
    results = [inspect("original", original)]
    results.append(inspect("crlf", text.replace("\n", "\r\n").encode("utf-8")))
    results.append(inspect("utf8-bom", b"\xef\xbb\xbf" + original))
    results.append(inspect("utf16le-resave", b"\xff\xfe" + text.encode("utf-16le")))
    # This models a server/editor that labels UTF-8 bytes as ISO-8859-1 before the browser
    # exposes textContent. ASCII base64 survives; the legacy b7 representation does not.
    latin1_roundtrip = original.decode("utf-8").encode("iso-8859-1")
    results.append(inspect("iso-8859-1-mislabeled", latin1_roundtrip))
    report = {"artifact": str(args.artifact), "artifactBytes": artifact_bytes,
              "encoding": encoding, "payloadChars": len(payload.elements[0]),
              "payloadAscii": all(ord(char) < 128 for char in text),
              "variants": results}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(item["status"] == "pass" for item in results[:4]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
