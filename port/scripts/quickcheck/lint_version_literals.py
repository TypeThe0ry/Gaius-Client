#!/usr/bin/env python3
"""P3 lint: Minecraft version literals outside the version profiles.

  lint_version_literals.py [--json [F]] [--enforce | --enforce-product] [--list] [--self-test]

A version literal is a quoted string ("...", '...' or `...`) whose whole content is a
Minecraft version: an id of port/versions/*.json, or a release, pre-release, release
candidate or snapshot id of the same shape (1.14 .. 1.29.x, 26.1 .. 29.x, 26w14a).
Escaped configured profile ids in regular-expression patterns are also checked. Version
numbers belong in port/versions/*.json and in files generated from them; every other
literal makes the next version a hand edit (and some of them reject it silently).

Scope: the tracked files of port/src, port/mc, port/gpu, port/tools, port/web (without the
web/dist build output), port/native, port/overrides, port/scripts and apps/bridge.  JSON files
are data and the version profiles themselves, and stay out like the 2026-10-03 inventory,
and so does Markdown documentation;
generated files (web/dist, the TeaVM platform smoke bundle in web/smoke, node_modules, target,
quickcheck baselines) and the frozen port/scripts/legacy are excluded, and so are the regions
port/tools/profilegen renders from the profiles inside hand-written sources (profilegen.mjs
--regions; counted as "generated").  Rust profile enum variants (V1_21_11, V26_3) are counted
separately.

version-literals-exempt.txt names exact data files with a mandatory reason, counted separately
as exempt_lines. It is not a product-code allowance. Missing exempt files fail enforcement.
Full-line comments and the lint's own configuration files are not executable literals.

Product code is what the build compiles or ships and the tools that build it (PRODUCT_PREFIXES:
port/src, port/mc, port/gpu, port/overrides, port/web, port/tools, port/native/crates; test
sources and the web smoke pages are not product code).  Verification code (port/scripts,
apps/bridge, golden-data and fixture tools) is reported only.

--enforce-product (CI since M1 wave 3) exits 1 on a product literal that
version-literals-allow.txt does not list, and on an allow-list entry that matches nothing.
Without it findings are printed (GitHub warning annotations on Actions) and the exit status is
0.  --enforce exits 1 on any literal of the whole scope (M6b exit criterion).
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import re
import subprocess
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javasrc  # noqa: E402

ROOT = javasrc.ROOT
SCOPE = ("port/src", "port/mc", "port/gpu", "port/tools", "port/web", "port/native", "port/overrides",
         "port/scripts", "apps/bridge")
EXCLUDED_PREFIXES = ("port/web/dist/", "port/web/smoke/platform-smoke-", "port/scripts/legacy/",
                     "port/scripts/quickcheck/baselines/")
PRODUCT_PREFIXES = ("port/src/", "port/mc/", "port/gpu/", "port/overrides/", "port/web/", "port/tools/",
                    "port/native/crates/")
NOT_PRODUCT_PREFIXES = ("port/web/smoke/",)
ALLOW_LIST = Path(__file__).resolve().parent / "version-literals-allow.txt"
EXEMPT_LIST = Path(__file__).resolve().parent / "version-literals-exempt.txt"
CONFIG_FILES = ("port/scripts/quickcheck/version-literals-allow.txt",
                "port/scripts/quickcheck/version-literals-exempt.txt")
EXCLUDED_PARTS = ("node_modules", "target")
EXCLUDED_SUFFIXES = (".json", ".md")
BINARY_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".wasm", ".jar", ".class", ".zip", ".gz",
                   ".br", ".bin", ".ogg", ".mp3", ".ttf", ".otf", ".woff", ".woff2", ".spv", ".pack", ".lock")
# The inventory of 2026-10-03: directories of port/ with files and lines.
INVENTORY_BASELINE = {"files": 74, "lines": 297, "rust_enum_lines": 36,
                       "scope": "port/src, port/tools/src, port/web (no dist), port/native/crates, "
                                "port/overrides, port/scripts"}
INVENTORY_SCOPES = {"port/src/main", "port/src/versions", "port/tools/src", "port/web", "port/native/crates",
                     "port/overrides", "port/scripts"}
SHAPES = (
    r"1\.(?:1[4-9]|2\d)(?:\.\d{1,2})?(?:-(?:pre|rc)\d{1,2})?",
    r"2[6-9]\.\d{1,2}(?:\.\d{1,2})?(?:-(?:pre|rc|snapshot-)\d{1,2})?",
    r"\d\dw\d\d[a-z]",
)
RUST_ENUM = re.compile(r"\bV1_\d+(?:_\d+)?\b|\bV2[6-9]_\d+(?:_\d+)?\b")


def literal_pattern(root: Path) -> re.Pattern:
    ids = sorted({re.escape(p) for p in javasrc.profiles(root)}, key=len, reverse=True)
    alternatives = "|".join(ids + list(SHAPES))
    return re.compile(r"([\"'`])(" + alternatives + r")\1")


def escaped_pattern(root: Path) -> re.Pattern:
    ids = sorted({re.escape(profile) for profile in javasrc.profiles(root)}, key=len, reverse=True)
    alternatives = [pattern.replace(r"\.", r"\\{1,2}\.") for pattern in ids
                    if r"\." in pattern]
    if not alternatives:
        return re.compile(r"(?!)")
    return re.compile(r"(?<![\w.\\])(?:" + "|".join(alternatives)
                      + r")(?![\w.]|\\{1,2}\.[0-9])")


def tracked_files(root: Path) -> list[str]:
    try:
        out = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", *SCOPE], capture_output=True,
                             check=True).stdout
        return [p for p in out.decode("utf-8", "replace").split("\0") if p]
    except (OSError, subprocess.CalledProcessError):
        files = []
        for scope in SCOPE:
            base = root / scope
            for dirpath, dirnames, filenames in os.walk(base):
                dirnames[:] = [d for d in dirnames if d not in EXCLUDED_PARTS and d != "work"]
                files.extend(javasrc.rel(Path(dirpath) / f, root) for f in filenames)
        return files


def in_scope(path: str) -> bool:
    if path in CONFIG_FILES:
        return False
    if path.startswith(EXCLUDED_PREFIXES) or path.endswith(EXCLUDED_SUFFIXES + BINARY_SUFFIXES):
        return False
    if path.startswith("port/work/"):
        return False
    return not any(part in EXCLUDED_PARTS for part in path.split("/"))


def scope_of(path: str) -> str:
    parts = path.split("/")
    if parts[0] == "apps" or len(parts) < 4:
        return "/".join(parts[:2])
    if parts[1] in ("src", "tools") or (parts[1] == "native" and parts[2] == "crates"):
        return "/".join(parts[:3])
    return "/".join(parts[:2])


def is_product(path: str) -> bool:
    return (path.startswith(PRODUCT_PREFIXES) and not path.startswith(NOT_PRODUCT_PREFIXES)
            and "/src/test/" not in path)


def read_allow_list(path: Path) -> list[dict]:
    """version-literals-allow.txt: "<path> | <line text, stripped> | <reason>" per product literal
    that stays until its owner removes it (a handoff); '#' starts a comment line."""
    entries = []
    if not path.is_file():
        return entries
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        fields = [field.strip() for field in raw.split(" | ")]
        if len(fields) != 3 or not all(fields):
            raise ValueError(f"{path.name}:{number}: expected '<path> | <line text> | <reason>'")
        entries.append({"path": fields[0], "text": fields[1], "reason": fields[2], "line": number})
    return entries


def generated_regions(root: Path) -> dict[str, list[tuple[int, int]]]:
    """Line ranges per path that port/tools/profilegen renders from port/versions/*.json."""
    tool = root / "port/tools/profilegen/profilegen.mjs"
    if not tool.is_file():
        return {}
    try:
        result = subprocess.run(["node", str(tool), "--regions", "--root", str(root)], capture_output=True,
                                text=True, encoding="utf-8", check=True, timeout=120)
        listed = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        print(f"[lint P3] profilegen --regions failed ({error}); generated regions are counted", file=sys.stderr)
        return {}
    regions: dict[str, list[tuple[int, int]]] = defaultdict(list)
    for region in listed.get("regions", []):
        regions[region["path"]].append((int(region["startLine"]), int(region["endLine"])))
    return regions


def read_exempt_list(path: Path) -> list[dict]:
    entries = []
    if not path.is_file():
        return entries
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        fields = [part.strip() for part in raw.split(" | ", 1)]
        if len(fields) != 2 or not all(fields):
            raise ValueError(f"{path.name}:{number}: expected '<path> | <reason>'")
        if (fields[0].startswith("/") or ".." in fields[0].split("/")
                or any(character in fields[0] for character in "\\:*?")):
            raise ValueError(f"{path.name}:{number}: exemptions must name one repository file")
        if any(entry["path"] == fields[0] for entry in entries):
            raise ValueError(f"{path.name}:{number}: duplicate exemption")
        entries.append({"path": fields[0], "reason": fields[1], "line": number})
    return entries


def scan(root: Path, allow_list: Path | None = None, exempt_list: Path | None = None) -> dict:
    pattern = literal_pattern(root)
    escaped = escaped_pattern(root)
    allowed_entries = read_allow_list(ALLOW_LIST if allow_list is None else allow_list)
    exemptions = read_exempt_list(EXEMPT_LIST if exempt_list is None else exempt_list)
    exempt_paths = {entry["path"] for entry in exemptions}
    exempted = []
    regions = generated_regions(root)
    findings = []
    generated = []
    rust = []
    for path in tracked_files(root):
        if not in_scope(path):
            continue
        full = root / path
        try:
            data = full.read_bytes()
        except OSError:
            continue
        if b"\0" in data[:4096]:
            continue
        text = data.decode("utf-8", "replace")
        block_comment = False
        for number, line in enumerate(text.splitlines(), 1):
            scan_line = line
            if block_comment:
                block_comment = "*/" not in line
                if block_comment:
                    continue
                scan_line = line.split("*/", 1)[1]
            stripped = scan_line.lstrip()
            if stripped.startswith("/*"):
                block_comment = "*/" not in scan_line
                if block_comment:
                    continue
                scan_line = scan_line.split("*/", 1)[1]
                stripped = scan_line.lstrip()
            if (stripped.startswith(("//", "<!--"))
                    or Path(path).suffix in (".sh", ".py", ".toml") and stripped.startswith("#")):
                continue
            versions = [m.group(2) for m in pattern.finditer(scan_line)]
            versions.extend(re.sub(r"\\+\.", ".", match.group()) for match in escaped.finditer(scan_line))
            if versions:
                finding = {"path": path, "line": number, "versions": versions, "text": line.strip()[:160],
                           "full": line.strip()}
                if path in exempt_paths:
                    exempted.append(finding)
                elif any(start <= number <= end for start, end in regions.get(path, ())):
                    generated.append(finding)
                else:
                    findings.append(finding)
            if path.endswith(".rs") and RUST_ENUM.search(line):
                rust.append({"path": path, "line": number, "text": line.strip()[:160]})
    product, allowed, used = [], [], set()
    for finding in findings:
        if not is_product(finding["path"]):
            continue
        entry = next((e for e in allowed_entries
                      if e["path"] == finding["path"] and e["text"] == finding["full"]), None)
        if entry is None:
            product.append(finding)
        else:
            allowed.append({**finding, "reason": entry["reason"]})
            used.add(entry["line"])
    stale_allow = [e for e in allowed_entries if e["line"] not in used]
    for finding in findings + generated:
        finding.pop("full", None)
    by_scope: dict[str, dict] = defaultdict(lambda: {"files": set(), "lines": 0})
    for finding in findings:
        bucket = by_scope[scope_of(finding["path"])]
        bucket["files"].add(finding["path"])
        bucket["lines"] += 1
    return {
        "findings": findings,
        "rust_enum": rust,
        "files": len({f["path"] for f in findings}),
        "lines": len(findings),
        "rust_enum_files": len({r["path"] for r in rust}),
        "rust_enum_lines": len(rust),
        "generated_lines": len(generated),
        "exemptions": exemptions,
        "exempt_lines": len(exempted),
        "stale_exempt": [entry for entry in exemptions if not (root / entry["path"]).is_file()],
        "product": product,
        "product_allowed": allowed,
        "stale_allow": stale_allow,
        "by_scope": {k: {"files": len(v["files"]), "lines": v["lines"]} for k, v in sorted(by_scope.items())},
        "baseline": INVENTORY_BASELINE,
    }


def report(result: dict, list_all: bool, enforce: bool, enforce_product: bool = False) -> None:
    mode = "enforce" if enforce else "enforce-product" if enforce_product else "warn"
    print(f"[lint P3 {mode}] version literals: {result['files']} files, {result['lines']} lines; "
          f"Rust profile enum: {result['rust_enum_files']} files, {result['rust_enum_lines']} lines "
          f"(inventory of 2026-10-03: {INVENTORY_BASELINE['files']} files / {INVENTORY_BASELINE['lines']} lines "
          f"+ {INVENTORY_BASELINE['rust_enum_lines']} enum lines on a narrower scope)")
    if result.get("generated_lines"):
        print(f"  not counted: {result['generated_lines']} lines in regions generated by port/tools/profilegen")
    for entry in result.get("exemptions", []):
        print(f"  data exemption {entry['path']}: {entry['reason']}")
    for scope, counts in result["by_scope"].items():
        print(f"  {scope:28} {counts['files']:4} files {counts['lines']:5} lines")
    narrow = [f for f in result["findings"] if scope_of(f["path"]) in INVENTORY_SCOPES]
    print(f"  on the inventory scope: {len({f['path'] for f in narrow})} files, {len(narrow)} lines")
    print(f"  product code: {len(result['product'])} lines"
          f" ({len(result['product_allowed'])} more allowed by {ALLOW_LIST.name})")
    for finding in result["product"]:
        print(f"  PRODUCT {finding['path']}:{finding['line']}: {', '.join(finding['versions'])}: {finding['text']}")
    for finding in result["product_allowed"]:
        print(f"  allowed {finding['path']}:{finding['line']}: {finding['reason']}")
    for entry in result["stale_allow"]:
        print(f"  STALE {ALLOW_LIST.name}:{entry['line']}: {entry['path']} no longer has that line")
    for entry in result["stale_exempt"]:
        print(f"  STALE {EXEMPT_LIST.name}:{entry['line']}: {entry['path']} does not exist")
    annotate = os.environ.get("GITHUB_ACTIONS") == "true"
    shown = result["findings"] if list_all else result["findings"][:40]
    for index, finding in enumerate(shown):
        print(f"  {finding['path']}:{finding['line']}: {', '.join(finding['versions'])}: {finding['text']}")
        if annotate and index < 10:
            level = "error" if enforce or (enforce_product and is_product(finding["path"])) else "warning"
            print(f"::{level} file={finding['path']},line={finding['line']}::P3 version literal "
                  f"{', '.join(finding['versions'])} outside port/versions/*.json")
    if len(shown) < len(result["findings"]):
        print(f"  ... {len(result['findings']) - len(shown)} more (--list or --json for all)")


def self_test() -> int:
    failures = []
    # The versions are assembled at run time so that this file holds no version literal.
    profile, other, later = ".".join(("27", "4")), ".".join(("1", "20", "4")), ".".join(("28", "1"))
    snapshot = "26" + "w14a"
    regex_id = re.escape(profile)
    string_id = regex_id.replace("\\", "\\\\")
    numeric_pattern = r"\.".join(("26", "0"))
    with tempfile.TemporaryDirectory(prefix="gaius-p3-") as scratch_name:
        root = Path(scratch_name)
        (root / "port/versions").mkdir(parents=True)
        (root / "port/versions/x.json").write_text(json.dumps({"id": profile}), encoding="utf-8")
        quote, tick = '"', "'"
        files = {
            "port/scripts/a.sh": [f"profile={quote}{profile}{quote}", f"other={quote}{other}{quote}",
                                  f"snap={tick}{snapshot}{tick}",
                                  f"not={quote}{profile}.0.1{quote} n={quote}0.{profile}{quote}"],
            "port/web/b.js": [f"const v = `{profile}`;", f"const w = {tick}{profile}00{tick};",
                              f"// profile {quote}{profile}{quote}", f"/* profile {quote}{profile}{quote}",
                              f" */ const trailing = {quote}{profile}{quote};",
                              f"/* profile {quote}{profile}{quote} */"],
            "port/scripts/c.json": [f"{{{quote}v{quote}: {quote}{profile}{quote}}}"],
            "port/web/dist/d.js": [f"const v = {quote}{profile}{quote};"],
            "port/native/e.rs": [f"Profile::V{profile.replace('.', '_')} => 1,", f"let x = {quote}{later}{quote};"],
            "port/scripts/quickcheck/baselines/f.xml": [f"{quote}{profile}{quote}"],
            "port/src/g.java": [f"a = {quote}{profile}{quote};", f"b = {quote}{profile}{quote};"],
            "port/web/smoke/h.js": [f"const v = {quote}{profile}{quote};"],
            "port/scripts/i.mjs": [f"const regex = /{regex_id}/;",
                                   f"const string = {quote}{string_id}{quote};",
                                   f"const other = /{regex_id}00/;",
                                   f"const extended = /{regex_id}\\.0\\.1/;",
                                   f"// const ignored = /{regex_id}/;",
                                   f"const numeric = /compute={numeric_pattern}/;"],
            "port/web/smoke/platform-smoke-v4.js": [f"const v = {quote}{profile}{quote};"],
            "port/mc/shared/s/src/test/T.java": [f"t = {quote}{profile}{quote};"],
            "port/mc/k.java": [f"k = {quote}{profile}{quote};"],
            "port/native/inventory.toml": [f"profile = {quote}{profile}{quote}"],
            "exempt.txt": ["port/native/inventory.toml | fixture bytecode inventory"],
            "allow.txt": ["# comment", f"port/mc/k.java | k = {quote}{profile}{quote}; | handoff",
                          f"port/mc/k.java | gone = {quote}{profile}{quote}; | stale"],
            # A stand-in profilegen that reports line 2 of g.java as generated.
            "port/tools/profilegen/profilegen.mjs": [
                "console.log(JSON.stringify({regions: [{path: 'port/src/g.java', startLine: 2, endLine: 2}]}));"],
        }
        for path, lines in files.items():
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = scan(root, root / "allow.txt", root / "exempt.txt")
        got = sorted((f["path"], f["line"]) for f in result["findings"])
        want = [("port/mc/k.java", 1), ("port/mc/shared/s/src/test/T.java", 1), ("port/native/e.rs", 2),
                ("port/scripts/a.sh", 1), ("port/scripts/a.sh", 2), ("port/scripts/a.sh", 3),
                ("port/scripts/i.mjs", 1), ("port/scripts/i.mjs", 2),
                ("port/src/g.java", 1), ("port/web/b.js", 1), ("port/web/b.js", 5), ("port/web/smoke/h.js", 1)]
        if got != want:
            failures.append(f"findings {got}, expected {want}")
        product = sorted((f["path"], f["line"]) for f in result["product"])
        if product != [("port/src/g.java", 1), ("port/web/b.js", 1), ("port/web/b.js", 5)]:
            failures.append(f"product findings {product}")
        if [(f["path"], f["reason"]) for f in result["product_allowed"]] != [("port/mc/k.java", "handoff")]:
            failures.append(f"allowed {result['product_allowed']}")
        if [e["line"] for e in result["stale_allow"]] != [3]:
            failures.append(f"stale allow entries {result['stale_allow']}")
        if result["generated_lines"] != 1:
            failures.append(f"generated lines {result['generated_lines']}, expected 1")
        if result["exempt_lines"] != 1 or result["stale_exempt"]:
            failures.append("exact data exemption was not counted separately")
        (root / "exempt.txt").write_text("port/native/absent.toml | stale data\n", encoding="utf-8")
        if not scan(root, root / "allow.txt", root / "exempt.txt")["stale_exempt"]:
            failures.append("missing data exemption was not reported stale")
        if result["rust_enum_lines"] != 1:
            failures.append(f"rust enum lines {result['rust_enum_lines']}, expected 1")
    for failure in failures:
        print(f"self-test FAILED: {failure}", file=sys.stderr)
    if not failures:
        print("lint_version_literals self-test passed")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--json", nargs="?", const="-")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--enforce-product", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", default=str(ROOT))
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    result = scan(Path(args.root))
    if args.json == "-":
        with contextlib.redirect_stdout(sys.stderr):
            report(result, args.list, args.enforce, args.enforce_product)
        print(json.dumps(result, indent=1))
    else:
        report(result, args.list, args.enforce, args.enforce_product)
    if args.json and args.json != "-":
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    if args.enforce and (result["findings"] or result["stale_allow"] or result["stale_exempt"]):
        return 1
    return 1 if args.enforce_product and (result["product"] or result["stale_allow"] or result["stale_exempt"]) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
