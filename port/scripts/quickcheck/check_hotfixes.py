#!/usr/bin/env python3
"""Check durable hotfix markers before compile and after packaging.

Signatures are regression tripwires, not a replacement for behavioral tests.
--staged checks only entries tagged for boot/runtime, in every packaged profile.
"""
import argparse
import json
import re
import sys
from pathlib import Path


def load_signatures(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    fixes = data.get("hotfixes")
    if not isinstance(fixes, list) or not fixes:
        raise ValueError("hotfixes must be a nonempty list")
    ids = set()
    for fix in fixes:
        if not isinstance(fix.get("id"), str) or fix["id"] in ids:
            raise ValueError("hotfix ids must be unique strings")
        ids.add(fix["id"])
        if not fix.get("description") or not fix.get("commit") or not fix.get("signatures"):
            raise ValueError(f"{fix['id']}: description, commit and signatures required")
        for sig in fix["signatures"]:
            path = Path(sig.get("file", ""))
            if not sig.get("file") or path.is_absolute() or ".." in path.parts:
                raise ValueError(f"{fix['id']}: invalid source path")
            if ("regex" in sig) == ("substring" in sig):
                raise ValueError(f"{fix['id']}: exactly one regex or substring required")
            marker = sig.get("regex", sig.get("substring"))
            if not isinstance(marker, str) or not marker:
                raise ValueError(f"{fix['id']}: empty marker")
            if type(sig.get("must_exist")) is not bool:
                raise ValueError(f"{fix['id']}: must_exist must be boolean")
            if sig.get("staged") not in (None, "boot", "runtime"):
                raise ValueError(f"{fix['id']}: unknown staged category")
            if "regex" in sig:
                re.compile(sig["regex"])
    return fixes


def matches(text, sig):
    return bool(re.search(sig["regex"], text, re.MULTILINE)) if "regex" in sig else sig["substring"] in text


def check(fixes, root, staged=None):
    failures = []
    cache = {}
    for fix in fixes:
        for sig in fix["signatures"]:
            if staged is None:
                paths = [root / sig["file"]]
            elif not sig.get("staged"):
                continue
            else:
                # Each portable embeds both scripts. Site scripts retain their source paths.
                paths = []
                portable = sorted(staged.glob("portable-*/*.html"))
                sites = sorted(staged.glob("site-*"))
                if sig["staged"] == "boot":
                    paths.extend(portable)
                    for site in sites:
                        paths.extend(site.rglob("gaius-boot*.js"))
                else:
                    for site in sites:
                        paths.extend(site.rglob("gaius-wasm-boot*.js"))
                if not paths or not sites or not portable:
                    failures.append(f"{fix['id']}: missing staged site or portable artifacts")
                    continue
            for path in paths:
                if path not in cache:
                    cache[path] = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
                text = cache[path]
                if text is None or matches(text, sig) != sig["must_exist"]:
                    marker = sig.get("regex", sig.get("substring"))
                    failures.append(f"{fix['id']}: {path}: expected {'present' if sig['must_exist'] else 'absent'} {marker!r}")
    return failures


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--signatures", type=Path)
    parser.add_argument("--staged", type=Path)
    args = parser.parse_args(argv)
    try:
        fixes = load_signatures(args.signatures or args.root / "port/scripts/quickcheck/hotfix-signatures.json")
        failures = check(fixes, args.root, args.staged)
    except (ValueError, OSError, re.error) as error:
        print(f"Invalid hotfix signatures: {error}", file=sys.stderr)
        return 1
    if failures:
        print("Missing hotfix signatures:\n" + "\n".join("  " + item for item in failures), file=sys.stderr)
        return 1
    print(f"hotfix signatures OK ({len(fixes)} hotfixes, {'staged' if args.staged else 'source'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
