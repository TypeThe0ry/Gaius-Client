#!/usr/bin/env python3
"""Compare Patcher artifacts and host-tool identities without loading large files into memory."""

import argparse
import hashlib
import json
from pathlib import Path

CHUNK_SIZE = 1024 * 1024


def artifacts(root: Path) -> dict[str, Path]:
    site = root / "site"
    portable = sorted(root.glob("Gaius-*.html"))
    expected = root / "expected-hashes.json"
    if not site.is_dir() or len(portable) != 1 or not expected.is_file():
        raise ValueError(f"{root}: expected site/, expected-hashes.json and exactly one portable HTML file")
    files = {path.relative_to(root).as_posix(): path
             for path in sorted(site.rglob("*")) if path.is_file()}
    if not files:
        raise ValueError(f"{root}: site/ is empty")
    files[portable[0].name] = portable[0]
    files[expected.name] = expected
    host_tools = root / "wasm-host-tools.json"
    if host_tools.is_file():
        files[host_tools.name] = host_tools
    return dict(sorted(files.items()))


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(CHUNK_SIZE):
            result.update(chunk)
    return result.hexdigest()


def content_difference(left: Path, right: Path) -> dict:
    """Record the first differing byte and bounded context for text or binary files."""
    offset = 0
    with left.open("rb") as first, right.open("rb") as second:
        while True:
            a, b = first.read(CHUNK_SIZE), second.read(CHUNK_SIZE)
            if a != b:
                common = min(len(a), len(b))
                index = next((i for i in range(common) if a[i] != b[i]), common)
                start, end = max(0, index - 96), index + 160
                return {"offset": offset + index,
                        "left": a[start:end].decode("utf-8", errors="backslashreplace"),
                        "right": b[start:end].decode("utf-8", errors="backslashreplace")}
            if not a:
                return {}
            offset += len(a)


def compare(roots: list[Path]) -> dict:
    inventories = [artifacts(root) for root in roots]
    hashes = [{name: digest(path) for name, path in files.items()} for files in inventories]
    names = sorted(set().union(*(files.keys() for files in inventories)))
    differences = []
    for name in names:
        values = [items.get(name) for items in hashes]
        if len(set(values)) == 1:
            continue
        detail = {"file": name, "sha256": values, "content": []}
        for index in range(1, len(roots)):
            if values[0] is not None and values[index] is not None and values[0] != values[index]:
                detail["content"].append({"run": index,
                                          **content_difference(inventories[0][name], inventories[index][name])})
        differences.append(detail)
    return {"identical": not differences, "roots": [str(root.resolve()) for root in roots],
            "files": hashes, "differences": differences}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("roots", type=Path, nargs="+", help="Patcher output directories")
    parser.add_argument("--output", type=Path, required=True, help="JSON comparison evidence")
    args = parser.parse_args()
    if len(args.roots) < 2:
        parser.error("at least two output directories are required")
    try:
        result = compare(args.roots)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"Compared {len(result['files'][0])} artifacts across {len(args.roots)} builds: "
          f"{len(result['differences'])} differences")
    return 0 if result["identical"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
