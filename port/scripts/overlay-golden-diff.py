#!/usr/bin/env python3
"""Golden snapshot / diff for Gaius overlay directories (G1 gate).

  snapshot <overlayDir> <out.json> [--exclude GLOB ...]
      Records the sha256 of every entry of every JAR/ZIP under overlayDir
      (entry contents only: timestamps, compression and entry order are
      ignored) plus the sha256 of every plain file.

  diff <base.json> <new.json> [--ignore GLOB ...] [--products]
       [--strict-added] [--allow-added GLOB ...] [--quiet]
       [--accept reviewed.json --profile PROFILE]
      Prints changed / removed / added keys. Exits 1 when anything was
      changed or removed, 0 when the new snapshot is identical or only adds
      keys, 2 on usage errors.
      --products compares only the build products that TeaVM consumes (the
      G1 scope): client-named-*-gaius.jar, libraries/**, and the TeaVM
      classlib/core overlay jars.  Intermediate directories such as
      tool-classes/ or client-patches/ are left out, so patcher source changes
      that do not change any product pass.
      --strict-added also fails on added keys unless they match an
      --allow-added GLOB (G1: new entries only from the reviewed whitelist).
      --accept uses exact reviewed before/after hashes and reasons, and
      requires --products --strict-added without ignores or globs. It also
      checks reviewed hashes when comparing an already promoted baseline.

Keys are "<relative/path>" for plain files and "<relative/path.jar>!<entry>"
for archive entries. GLOB patterns (fnmatch) are matched against these keys.

G1 for 26.2 (PLAN.md 0.3):
  build-overlays.sh (base) -> snapshot base.json
  build-overlays.sh (candidate) -> snapshot new.json
  overlay-golden-diff.py diff base.json new.json --products --strict-added
"""

import argparse
import datetime
import fnmatch
import hashlib
import json
import os
import re
import sys
import zipfile

ARCHIVE_SUFFIXES = (".jar", ".zip")
DIR_MARKER = "<dir>"
# The G1 product scope: what generate-pom.sh hands to TeaVM.
PRODUCT_PATTERNS = (
    "client-named-*-gaius.jar",
    "client-named-*-gaius.jar!*",
    "libraries/*",
    "teavm-classlib-*-gaius.jar",
    "teavm-classlib-*-gaius.jar!*",
    "teavm-core-*-gaius.jar",
    "teavm-core-*-gaius.jar!*",
)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def archive_entries(path):
    entries = {}
    seen = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            count = seen.get(name, 0)
            seen[name] = count + 1
            key = name if count == 0 else "%s#dup%d" % (name, count)
            if name.endswith("/"):
                entries[key] = DIR_MARKER
                continue
            digest = hashlib.sha256()
            with archive.open(info) as stream:
                for chunk in iter(lambda: stream.read(1 << 20), b""):
                    digest.update(chunk)
            entries[key] = digest.hexdigest()
    return entries


def excluded(key, patterns):
    return any(fnmatch.fnmatchcase(key, pattern) for pattern in patterns)


def snapshot(overlay_dir, out_path, excludes):
    overlay_dir = os.path.abspath(overlay_dir)
    if not os.path.isdir(overlay_dir):
        print("overlay directory does not exist: %s" % overlay_dir, file=sys.stderr)
        return 2
    files = {}
    archive_count = 0
    entry_count = 0
    for directory, subdirs, names in os.walk(overlay_dir):
        subdirs.sort()
        for name in sorted(names):
            path = os.path.join(directory, name)
            rel = os.path.relpath(path, overlay_dir).replace(os.sep, "/")
            if excluded(rel, excludes):
                continue
            record = {"sha256": sha256_file(path), "size": os.path.getsize(path)}
            if name.lower().endswith(ARCHIVE_SUFFIXES) and zipfile.is_zipfile(path):
                entries = {
                    key: value
                    for key, value in archive_entries(path).items()
                    if not excluded(rel + "!" + key, excludes)
                }
                record["type"] = "archive"
                record["entries"] = dict(sorted(entries.items()))
                archive_count += 1
                entry_count += len(entries)
            else:
                record["type"] = "file"
            files[rel] = record
    document = {
        "format": "gaius-overlay-golden/1",
        "overlayDir": overlay_dir.replace(os.sep, "/"),
        "createdUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "excludes": list(excludes),
        "summary": {
            "files": len(files),
            "archives": archive_count,
            "archiveEntries": entry_count,
        },
        "files": dict(sorted(files.items())),
    }
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(document, handle, indent=1, sort_keys=False)
        handle.write("\n")
    print("snapshot %s: %d files, %d archives, %d archive entries -> %s" % (
        overlay_dir, len(files), archive_count, entry_count, out_path))
    return 0


def flatten(document):
    """Map every comparable key to its content hash.

    Archive-level whole-file hashes are intentionally not compared: jar
    --update rewrites timestamps, so only entry contents are stable.
    """
    flat = {}
    for rel, record in document["files"].items():
        if record.get("type") == "archive":
            flat[rel] = "<archive>"
            for entry, value in record["entries"].items():
                flat[rel + "!" + entry] = value
        else:
            flat[rel] = record["sha256"]
    return flat


def reviewed_entries(path, profile, base, new):
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    if document.get("schemaVersion") != 1 or document.get("target") != "js":
        raise ValueError("expected a schemaVersion=1 JS review")
    profiles = document.get("profiles")
    if not isinstance(profiles, dict) or profile not in profiles:
        raise ValueError("profile is absent from the reviewed accept file")
    entries = profiles[profile].get("entries")
    if not isinstance(entries, list) or not entries:
        raise ValueError("review must contain exact entries")
    accepted = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"key", "beforeSha256", "afterSha256", "reason"}:
            raise ValueError("review entry must declare its exact key, before/after hashes and reason")
        key = entry.get("key")
        before = entry.get("beforeSha256")
        after = entry.get("afterSha256")
        reason = entry.get("reason")
        if (not isinstance(key, str) or any(marker in key for marker in "*?[]")
                or key in accepted or not excluded(key, PRODUCT_PATTERNS)):
            raise ValueError("invalid, duplicate or out-of-scope reviewed key: %s" % key)
        if (not isinstance(after, str) or not re.fullmatch("[0-9a-f]{64}", after)
                or before is not None and (not isinstance(before, str)
                                          or not re.fullmatch("[0-9a-f]{64}", before))
                or before == after or not isinstance(reason, str) or not reason.strip()):
            raise ValueError("review needs distinct exact hashes and a reason: %s" % key)
        if new.get(key) != after:
            raise ValueError("reviewed candidate hash is stale or missing: %s" % key)
        if base.get(key) not in (before, after):
            raise ValueError("reviewed baseline hash does not match: %s" % key)
        accepted.add(key)
    return accepted


def diff(base_path, new_path, ignores, quiet, products=False, strict_added=False,
         allow_added=(), accept_path=None, profile=None):
    with open(base_path, encoding="utf-8") as handle:
        base = flatten(json.load(handle))
    with open(new_path, encoding="utf-8") as handle:
        new = flatten(json.load(handle))
    base = {k: v for k, v in base.items() if not excluded(k, ignores)}
    new = {k: v for k, v in new.items() if not excluded(k, ignores)}
    if products:
        base = {k: v for k, v in base.items() if excluded(k, PRODUCT_PATTERNS)}
        new = {k: v for k, v in new.items() if excluded(k, PRODUCT_PATTERNS)}
    accepted = set()
    if accept_path:
        if not products or not strict_added or ignores or allow_added or not profile:
            print("RESULT FAIL (--accept requires --profile, --products, --strict-added and no ignores/allow-added)")
            return 1
        try:
            accepted = reviewed_entries(accept_path, profile, base, new)
        except (ValueError, TypeError, KeyError, AttributeError) as error:
            print("RESULT FAIL (review: %s)" % error)
            return 1
    changed = sorted(k for k in base.keys() & new.keys() if base[k] != new[k])
    removed = sorted(base.keys() - new.keys())
    added = sorted(new.keys() - base.keys())
    for label, keys in (("CHANGED", changed), ("REMOVED", removed), ("ADDED", added)):
        if quiet and label == "ADDED":
            continue
        for key in keys:
            if label == "CHANGED":
                print("%s %s  %s -> %s" % (label, key, base[key][:12], new[key][:12]))
            else:
                print("%s %s" % (label, key))
    unexpected_added = [
        key for key in added if strict_added and key not in accepted
        and not excluded(key, allow_added)
    ]
    print("SUMMARY compared=%d changed=%d removed=%d added=%d ignore=%s scope=%s" % (
        len(base.keys() | new.keys()), len(changed), len(removed), len(added),
        ",".join(ignores) or "-", "products" if products else "all"))
    unexpected_changed = [key for key in changed if key not in accepted]
    reviewed_count = len(accepted.intersection(changed + added))
    if accept_path:
        print("REVIEW profile=%s acceptedDeltas=%d unreviewedChanged=%d unreviewedAdded=%d" % (
            profile, reviewed_count, len(unexpected_changed), len(unexpected_added)))
    if unexpected_changed or removed:
        print("RESULT FAIL (changed or removed entries)")
        return 1
    if unexpected_added:
        for key in unexpected_added:
            print("UNEXPECTED-ADDED %s" % key)
        print("RESULT FAIL (added entries outside --allow-added)")
        return 1
    print("RESULT PASS" + (" (exact reviewed deltas)" if reviewed_count
                          else " (additions only)" if added else " (identical)"))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    snap = commands.add_parser("snapshot")
    snap.add_argument("overlay_dir")
    snap.add_argument("out_json")
    snap.add_argument("--exclude", action="append", default=[],
                      help="fnmatch glob on keys to leave out of the snapshot")
    compare = commands.add_parser("diff")
    compare.add_argument("base_json")
    compare.add_argument("new_json")
    compare.add_argument("--ignore", action="append", default=[],
                         help="fnmatch glob on keys to leave out of the comparison")
    compare.add_argument("--quiet", action="store_true",
                         help="do not list individual ADDED keys")
    compare.add_argument("--products", action="store_true",
                         help="compare only the G1 product artifacts")
    compare.add_argument("--strict-added", action="store_true",
                         help="fail on added keys that match no --allow-added glob")
    compare.add_argument("--allow-added", action="append", default=[],
                         help="fnmatch glob of keys that may be added with --strict-added")
    compare.add_argument("--accept", help="reviewed JS entries with exact before/after SHA256 and reasons")
    compare.add_argument("--profile", help="exact profile key in the reviewed accept file")
    args = parser.parse_args(argv)
    if args.command == "snapshot":
        return snapshot(args.overlay_dir, args.out_json, args.exclude)
    return diff(args.base_json, args.new_json, args.ignore, args.quiet,
                args.products, args.strict_added, args.allow_added, args.accept, args.profile)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
