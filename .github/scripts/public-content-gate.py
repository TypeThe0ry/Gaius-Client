"""Inspect committed Git blobs and commit metadata; no game files or credentials needed."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import unquote


DENIED_PREFIXES = (
    "mc/", "mappings/", "port/work/", "port/web/dist/", "assets/minecraft/",
    "docs/resource-packs/", "docs/images/", "port/scripts/vendor/",
)
DENIED_SUFFIXES = {".jar", ".class", ".wasm", ".tiny", ".tiny2", ".srg", ".tsrg", ".csrg", ".mappings"}
BINARY_SUFFIXES = set(".zip .gz .br .xz .7z .epk .pack .tar .tgz .png .jpg .jpeg .gif .webp .ico "
                      ".ogg .mp3 .wav .nbt .mca .dat .bin .spv .so .dll .dylib .exe .ttf .otf .woff .woff2 .pdf".split())
VANILLA_PATH = re.compile(r"(?:^|/)(?:net/minecraft|com/mojang)(?:/|$)")
JAVA_GAP = r"(?:\s|/\*[^*]*\*+(?:[^/*][^*]*\*+)*/|//[^\r\n]*[\r\n])+"
PACKAGE = re.compile(rf"(?<![\w$])package{JAVA_GAP}(?:net(?:{JAVA_GAP})?\.(?:{JAVA_GAP})?minecraft"
                     rf"|com(?:{JAVA_GAP})?\.(?:{JAVA_GAP})?mojang)(?={JAVA_GAP}|[.;])")
JAVA_ESCAPE = re.compile(r"(?<!\\)\\u+([a-fA-F0-9]{4})")
GAME_HEADER = re.compile(r"^.{0,32}(?:copyright|spdx-filecopyrighttext|\(c\))[^\r\n]{0,100}\bmojang\b", re.I | re.M)
PRIVATE_WORD = re.compile(r"(?<![\w-])[a-z0-9_-]+(?:\.[a-z0-9_-]+)*(?![\w-])")
ABS_PATH = re.compile(r"/(?:[a-z0-9_.-]+/){1,2}[a-z0-9_.-]+")
OWNER_REPO = re.compile(r"(?<![a-z0-9_-])(?=([a-z0-9_-]+/[a-z0-9_-]+)(?![a-z0-9_-]))")
SHA = re.compile(r"(?<![a-f0-9])[a-f0-9]{7,40}(?![a-f0-9])")
NOTICE_HASH = "d33af50c4030cb03fb706cad61cb5252e09f6c751294ff7960b269bbee8c2997"
PRIVATE_SHA_CONTEXT = re.compile(
    r"\b(?:private(?:[ -](?:repo(?:sitory)?|source|upstream))?|internal(?:[ -]source)?)"
    r"\s+(?:git\s+)?(?:commit|revision|sha)(?:-?1)?\s*[:=]?\s*`?[a-f0-9]{7,40}\b"
    r"|[\'\"]show[\'\"]\s*,\s*[\'\"][a-f0-9]{7,40}(?:\^|~\d+)?[:\'\"]"
    r"|\breference\s*[:=]\s*[\'\"][a-f0-9]{7,40}[\'\"]"
)


def git(*args: str, repo: str = ".") -> bytes:
    return subprocess.check_output(["git", "--no-replace-objects", "-C", repo, *args])


def digest(value: str) -> str:
    return digest_bytes(value.encode())


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def policy(name: str) -> str:
    return Path(__file__).with_name(name).read_text(encoding="ascii")


def text_view(data: bytes) -> str:
    for marks, encoding in (((b"\xff\xfe\x00\x00", b"\x00\x00\xfe\xff"), "utf-32"),
                            ((b"\xff\xfe", b"\xfe\xff"), "utf-16")):
        if data.startswith(marks):
            return data.decode(encoding, errors="replace")
    return data.decode("utf-8", errors="replace")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision")
    parser.add_argument("--private-repo", help="local only: additionally check against private Git refs/reflogs")
    args = parser.parse_args()
    revision = git("rev-parse", "--verify", "--end-of-options", args.revision + "^{commit}").decode().strip()
    denied = set(policy("public-content-denied-paths.txt").splitlines())
    bridge_hashes = dict(line.split() for line in policy("public-content-bridges.txt").splitlines())
    markers: dict[str, set[str]] = {"word": set(), "path": set(), "repo": set(), "sha": set()}
    for line in policy("public-content-marker-hashes.txt").splitlines():
        kind, value = line.split()
        markers[kind].add(value)
    private: dict[str, set[str]] = {}
    if args.private_repo:
        for oid in git("rev-list", "--all", "--reflog", "HEAD", repo=args.private_repo).decode().splitlines():
            private.setdefault(oid[:7], set()).add(oid)
        if not private:
            raise RuntimeError("no private commit reference data")
    failed: Counter[str] = Counter()

    def check_markers(text: str, path: str = "") -> None:
        normalized = unquote(text).lower().replace("\\", "/")
        def blocked_word(word: str) -> bool:
            labels = word.split(".")
            return any(digest(".".join(labels[i:])) in markers["word"] for i in range(len(labels)))

        if any(blocked_word(hit[0]) for hit in PRIVATE_WORD.finditer(normalized)):
            failed["blocked private word or domain"] += 1
        if any(digest(candidate) in markers["path"]
               for hit in ABS_PATH.finditer(normalized)
               for candidate in (hit[0], hit[0].rsplit("/", 1)[0])):
            failed["blocked host path"] += 1
        if any(digest(hit[1]) in markers["repo"] for hit in OWNER_REPO.finditer(normalized)):
            failed["blocked repository marker"] += 1
        if (path == "port/tools/patchkit/acknowledgements.json" and SHA.search(normalized)) or PRIVATE_SHA_CONTEXT.search(normalized) or any(
            digest(hit[0]) in markers["sha"] or any(oid.startswith(hit[0]) for oid in private.get(hit[0][:7], ()))
            for hit in SHA.finditer(normalized)
        ):
            failed["private revision marker"] += 1

    # Parent and tree IDs are generated Git headers, not source citations.
    message = git("show", "--no-patch", "--format=%an <%ae>%n%cn <%ce>%n%B", revision)
    check_markers(text_view(message))
    listing = git("ls-tree", "-rlz", "--full-tree", revision)
    with subprocess.Popen(["git", "--no-replace-objects", "cat-file", "--batch"],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE) as proc:
        assert proc.stdin is not None and proc.stdout is not None
        for record in listing.split(b"\0")[:-1]:
            meta, filename = record.split(b"\t", 1)
            mode, kind, oid, size = meta.split()
            path = filename.decode("utf-8", errors="strict")
            low = path.lower()
            suffix = PurePosixPath(low).suffix
            check_markers(path)
            if mode not in (b"100644", b"100755") or kind != b"blob":
                failed["non-regular Git entry"] += 1
                continue
            if low.startswith(DENIED_PREFIXES) or low in denied or suffix in DENIED_SUFFIXES:
                failed["blocked path"] += 1
                continue
            # GitHub rejects blobs larger than 100 MiB; bound memory even during local use.
            length = int(size)
            if length > 100 * 1024 * 1024:
                failed["blob exceeds scan limit"] += 1
                continue
            proc.stdin.write(oid + b"\n")
            proc.stdin.flush()
            if proc.stdout.readline().split() != [oid, kind, size]:
                raise RuntimeError("could not read committed blob")
            data = proc.stdout.read(length)
            if len(data) != length or proc.stdout.read(1) != b"\n":
                raise RuntimeError("truncated committed blob")
            text = text_view(data)
            check_markers(text, path)
            java = JAVA_ESCAPE.sub(lambda match: chr(int(match[1], 16)), text)
            if (VANILLA_PATH.search(low) or PACKAGE.search(java)) and (
                bridge_hashes.get(path) != hashlib.sha256(data).hexdigest()
            ):
                failed["blocked game namespace"] += 1
            if GAME_HEADER.search(java) and not (path == "NOTICE" and digest_bytes(data) == NOTICE_HASH):
                failed["blocked game copyright header"] += 1
            if length > 5 * 1024 * 1024 and (
                b"\0" in data or "\ufffd" in text or suffix in BINARY_SUFFIXES
                or data.startswith((b"PK\x03\x04", b"\x1f\x8b"))
            ):
                failed["binary exceeds 5 MiB"] += 1
        proc.stdin.close()
        if proc.wait() != 0:
            raise RuntimeError("Git blob reader failed")
    if failed:
        # Avoid republishing matched text or private file names in public Actions logs.
        for rule, number in sorted(failed.items()):
            print(f"FAIL: {rule} ({number})")
        return 1
    print("PASS: public content subset")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
