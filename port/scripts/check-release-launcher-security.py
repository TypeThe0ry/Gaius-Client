#!/usr/bin/env python3
"""Guard our launcher against accidental query-credential and lease regressions.

This is an allow-list gate for code we generate ourselves, not an obfuscation or
JavaScript security analyzer. It treats sensitive identifiers as tainted strings:
every occurrence, including comments and alternate access syntax, must match a
reviewed normalized context below and stay within its reviewed count.
T14/T15 artifacts are historical builds and are expected to fail this gate;
they are not release evidence. Fresh release generators must produce the pages
that pass.
"""

from __future__ import annotations

import argparse
import gzip
import re
import sys
from pathlib import Path


def normalized_statement(text: str, start: int, end: int) -> str:
    """Return the one statement containing an occurrence."""
    left = max((text.rfind(char, 0, start) for char in ";{}\n"), default=-1) + 1
    rights = [text.find(char, end) for char in ";{}\n"]
    right = min((value for value in rights if value >= 0), default=len(text)) + 1
    return " ".join(text[left:right].split())


# Each entry is exactly one statement occurrence. Duplicate statements are
# deliberately repeated in the table and consumed one-for-one.
ARRAY_TOKEN_STATEMENT = 'for (const key of ["bridge", "relay", "bridgeToken", "relayToken", "relayRegistry"]) params.delete(key);'
ARRAY_SCRUB_STATEMENT = 'for (const key of ["bridge", "relay", "bridgeToken", "relayToken", "relayRegistry"]) scrubbed.searchParams.delete(key);'
LINK_TOKEN_STATEMENT = 'const linkToken = urlParams.get("bridgeToken") || urlParams.get("relayToken") || "";'
LINK_KEYS_STATEMENT = 'const linkKeys = ["bridge", "relay", "bridgeToken", "relayToken"];'
SCRUB_TOKEN_STATEMENT = 'scrub(["bridgeToken", "relayToken"]);'
STATEMENT_ALLOWLIST = {
    "bridgeToken": (ARRAY_TOKEN_STATEMENT, ARRAY_SCRUB_STATEMENT, LINK_TOKEN_STATEMENT,
                     LINK_KEYS_STATEMENT, SCRUB_TOKEN_STATEMENT),
    "relayToken": (ARRAY_TOKEN_STATEMENT, ARRAY_SCRUB_STATEMENT, LINK_TOKEN_STATEMENT,
                    LINK_KEYS_STATEMENT, SCRUB_TOKEN_STATEMENT),
    "allowMultipleTabs": (),
    "location.search": (
        'const urlParams = new URLSearchParams(location.search);',
        'const params = new URLSearchParams(location.search || "");',
        'const gaiusDiagMode = new URLSearchParams(location.search).get("diag");',
        'const requestedBuildToken = new URLSearchParams(location.search).get("build");',
        'value = new URLSearchParams(w.location.search).get("touch");',
        'if (new URLSearchParams(location.search).get("gaiusRelay") === "0") return;',
        'if (root.location && root.location.search && typeof URLSearchParams === "function") {',
        'var params = new URLSearchParams(root.location.search);',
        'var pngSwitch = new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng");',
        '|| new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng") === "1";',
        'return new URLSearchParams(loc.search || "");',
    ),
    "URLSearchParams": (
        'const urlParams = new URLSearchParams(location.search);',
        'const params = new URLSearchParams(location.search || "");',
        'const gaiusDiagMode = new URLSearchParams(location.search).get("diag");',
        'const requestedBuildToken = new URLSearchParams(location.search).get("build");',
        'value = new URLSearchParams(w.location.search).get("touch");',
        'if (new URLSearchParams(location.search).get("gaiusRelay") === "0") return;',
        'if (root.location && root.location.search && typeof URLSearchParams === "function") {',
        'var params = new URLSearchParams(root.location.search);',
        'var pngSwitch = new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng");',
        '|| new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng") === "1";',
        'return new URLSearchParams(loc.search || "");',
    ),
    "searchParams": (
        'source.searchParams.set("v", version);',
        'bridge.searchParams.set("url", String(target));',
        ARRAY_SCRUB_STATEMENT,
        'for (const key of keys) scrubbed.searchParams.delete(key);',
        'bridge.searchParams.set("url",',
        'invite.searchParams.set("server", "client-" + brokerSessionId + ".gaius-local:25565");',
        'if (lanRelay) invite.searchParams.set("relay", lanRelay);',
        'for (const key of identityQueryKeys) scrubbed.searchParams.delete(key);',
        'next.searchParams.set("retry", String(Date.now()));',
        'bridge.searchParams.set("token", String(token));',
        'var token = new URL(base).searchParams.get("v");',
        'if (token) url.searchParams.set("v", token);',
        'token = new URL(base).searchParams.get("v") || "dev";',
    ),
}

# Bare property/destructuring forms are tainted too. The statement must still
# be present in the reviewed table, so aliases cannot borrow an allowed entry.
PROPERTY_PATTERNS = {
    "search": re.compile(r"(?:\b(?:location|window|globalThis|self)\s*(?:\?\.|\.)\s*search\b|\[\s*['\"]search['\"]\s*\]|\{\s*search\s*\}\s*=\s*location)"),
    "searchParams": re.compile(r"(?:\b(?:location|window|globalThis|self)\s*(?:\?\.|\.)\s*searchParams\b|\[\s*['\"]searchParams['\"]\s*\]|\{\s*searchParams\s*\}\s*=)"),
}

# Counts are for one generated launcher and catch duplicated/new uses.
TAINT_ALLOWLIST = {
    "bridgeToken": 5, "relayToken": 5, "allowMultipleTabs": 0,
    "location.search": 9, "URLSearchParams": 10, "searchParams": 12,
}


def read_html(path: Path) -> str:
    data = path.read_bytes()
    if path.name.endswith(".html.gz"):
        data = gzip.decompress(data)
    return data.decode("utf-8", errors="replace")


def violations(path: Path) -> list[str]:
    text = read_html(path)
    errors: list[str] = []
    for identifier, maximum in TAINT_ALLOWLIST.items():
        matches = list(re.finditer(re.escape(identifier), text))
        if len(matches) > maximum:
            errors.append(f"{path}: {identifier} occurs {len(matches)} times; maximum is {maximum}")
        allowed = list(STATEMENT_ALLOWLIST[identifier])
        for match in matches:
            statement = normalized_statement(text, match.start(), match.end())
            if statement in allowed:
                allowed.remove(statement)
            else:
                line = text.count("\n", 0, match.start()) + 1
                errors.append(f"{path}:{line}: unreviewed {identifier} statement: {statement}")
    for identifier, pattern in PROPERTY_PATTERNS.items():
        maximum = 9 if identifier == "search" else (12 if identifier == "searchParams" else 10)
        matches = list(pattern.finditer(text))
        if len(matches) > maximum:
            errors.append(f"{path}: {identifier} property occurs {len(matches)} times; maximum is {maximum}")
        allowed = list(STATEMENT_ALLOWLIST["location.search" if identifier == "search" else "searchParams"])
        for match in matches:
            statement = normalized_statement(text, match.start(), match.end())
            if statement in allowed:
                allowed.remove(statement)
            else:
                line = text.count("\n", 0, match.start()) + 1
                errors.append(f"{path}:{line}: unreviewed {identifier} property statement: {statement}")
    return errors


def scan_files(paths: list[Path]) -> tuple[list[Path], list[str]]:
    files: list[Path] = []
    errors: list[str] = []
    for root in paths:
        if root.is_file():
            if not (root.name.endswith(".html") or root.name.endswith(".html.gz")):
                errors.append(f"{root}: expected .html or .html.gz launcher")
                continue
            files.append(root)
        elif root.is_dir():
            found = sorted([*root.rglob("*.html"), *root.rglob("*.html.gz")])
            if not found:
                errors.append(f"{root}: no .html or .html.gz launchers found")
            files.extend(found)
        else:
            raise FileNotFoundError(root)
    return list(dict.fromkeys(files)), errors


def scan(paths: list[Path]) -> list[str]:
    files, errors = scan_files(paths)
    return errors + [error for path in files for error in violations(path)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path,
                        help="staged dist directories and/or portable HTML files")
    arguments = parser.parse_args()
    try:
        errors = scan(arguments.paths)
    except (OSError, ValueError) as error:
        print(f"release launcher security scan failed: {error}", file=sys.stderr)
        return 2
    if errors:
        print("Release launcher security scan failed:", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1
    files, _ = scan_files(arguments.paths)
    print(f"Release launcher security scan passed ({len(files)} HTML files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
