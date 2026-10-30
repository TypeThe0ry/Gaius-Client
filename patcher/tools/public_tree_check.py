#!/usr/bin/env python3
"""Refuse a source tree that is not safe to publish.

Runs on the tree that export_public_tree.py produces (and in the public
repository's CI). A tree fails when any file:

  * sits under a net/minecraft or com/mojang package directory and is not
    listed in patcher/vanilla-package-allowlist.txt (Gaius-written classes
    that must live in a vanilla package, reviewed one by one);
  * is a binary or media type that could carry game code or assets, or is
    larger than 1 MiB, and is not listed in patcher/binary-allowlist.txt with
    its SHA-256;
  * has the SHA-1 of an entry of an official client jar or of an object in an
    official asset index given with --vanilla-jar / --asset-index;
  * contains a blocked word or phrase. The repository stores only unsalted
    SHA-256 digests of them (patcher/blocked-term-hashes.txt), so the list
    does not itself publish them. --extra-terms-file adds plain words from a
    file kept outside the repository. patcher/term-exceptions.txt can allow
    one ASCII word in one file (a technical term the build needs verbatim);
  * contains a public IPv4 literal that patcher/ipv4-allowlist.txt does not
    list for that file (network constants and version numbers that only look
    like addresses).
  * is a source patch (.gpatch) that holds anything but its header, hunk
    headers and added lines. Removed and context lines would be official
    code, so a source patch may never carry them.

Exit status 0 means every check passed; 1 lists every finding.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath

MAX_BYTES = 1 << 20
BLOCKED_SUFFIXES = {
    ".jar", ".class", ".wasm", ".zip", ".gz", ".br", ".xz", ".7z", ".epk",
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".ogg", ".mp3", ".wav",
    ".nbt", ".mca", ".dat", ".bin", ".pack",
}
VANILLA_PACKAGES = (("net", "minecraft"), ("com", "mojang"))
IPV4 = re.compile(r"(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\d.])")
ALLOWED_IPV4 = {"127.0.0.1", "0.0.0.0", "255.255.255.255"}
ASCII_TOKEN = re.compile(r"[a-z0-9]+")
WORD_START = re.compile(r"(?<![a-z0-9-])[a-z0-9]")
CJK_RUN = re.compile(r"[㐀-鿿豈-﫿]+")


def sha1_bytes(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def read_list(path: Path) -> list[str]:
    if not path.is_file():
        return []
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            lines.append(line)
    return lines


class BlockedTerms:
    """Matches words by digest.

    ascii:  a token ([a-z0-9]+) that starts with the word;
    cjk:    the word anywhere inside a run of CJK characters;
    phrase: the text (host names, paths) starting at a word boundary.
    """

    def __init__(self) -> None:
        self.ascii: dict[int, set[str]] = {}
        self.cjk: dict[int, set[str]] = {}
        self.phrase: dict[int, set[str]] = {}
        self.plain: list[str] = []

    def add_digest(self, length: int, digest: str, kind: str) -> None:
        table = {"cjk": self.cjk, "phrase": self.phrase}.get(kind, self.ascii)
        table.setdefault(length, set()).add(digest)

    def add_plain(self, word: str) -> None:
        self.plain.append(word.lower())

    @classmethod
    def load(cls, digest_file: Path, extra_file: Path | None) -> "BlockedTerms":
        terms = cls()
        for line in read_list(digest_file):
            parts = line.split()
            if len(parts) < 2:
                raise SystemExit(f"{digest_file}: malformed line: {line}")
            terms.add_digest(int(parts[0]), parts[1], parts[2] if len(parts) > 2 else "ascii")
        if extra_file is not None:
            for word in read_list(extra_file):
                terms.add_plain(word)
        return terms

    def find(self, text: str, allowed: frozenset[str] = frozenset()) -> str | None:
        lowered = text.lower()
        for word in self.plain:
            if word in lowered:
                return "a word from --extra-terms-file"
        if self.ascii:
            for token in ASCII_TOKEN.findall(lowered):
                for length, digests in self.ascii.items():
                    if len(token) < length:
                        continue
                    digest = sha256_text(token[:length])
                    if digest in digests and digest not in allowed:
                        return f"a blocked word (token starting '{token[:2]}...')"
        if self.phrase:
            for match in WORD_START.finditer(lowered):
                start = match.start()
                for length, digests in self.phrase.items():
                    if sha256_text(lowered[start:start + length]) in digests:
                        return f"a blocked phrase (starting '{lowered[start:start + 2]}...')"
        if self.cjk:
            for run in CJK_RUN.findall(text):
                for length, digests in self.cjk.items():
                    for start in range(0, len(run) - length + 1):
                        if sha256_text(run[start:start + length]) in digests:
                            return "a blocked CJK word"
        return None


def vanilla_hashes(jars: list[Path], indexes: list[Path], hash_files: list[Path]) -> set[str]:
    hashes: set[str] = set()
    for jar in jars:
        hashes.add(sha1_bytes(jar.read_bytes()))
        with zipfile.ZipFile(jar) as archive:
            for info in archive.infolist():
                if not info.is_dir():
                    hashes.add(sha1_bytes(archive.read(info)))
    for index in indexes:
        objects = json.loads(index.read_text(encoding="utf-8")).get("objects", {})
        for entry in objects.values():
            hashes.add(entry["hash"])
    for hash_file in hash_files:
        hashes.update(read_list(hash_file))
    # Tiny files (an empty file, "{}") collide with anything; they carry no content.
    hashes.discard(sha1_bytes(b""))
    return hashes


SOURCE_PATCH_HEADER = re.compile(r"gaius-source-patch 1\nbase [0-9a-f]{64}\nresult [0-9a-f]{64}\n")
HUNK_HEADER = re.compile(r"@@ \d{1,7} \d{1,7}")


def source_patch_problem(text: str) -> str | None:
    if not SOURCE_PATCH_HEADER.match(text) or not text.endswith("\n"):
        return "is not a gaius source patch"
    for number, line in enumerate(text.split("\n")[3:-1], start=4):
        if not line.startswith("+") and not HUNK_HEADER.fullmatch(line):
            return f"line {number} is neither a hunk header nor an added line"
    return None


def in_vanilla_package(path: PurePosixPath) -> bool:
    parts = path.parts
    return any(parts[i:i + 2] == package for package in VANILLA_PACKAGES for i in range(len(parts) - 1))


def check_tree(root: Path, args: argparse.Namespace) -> list[str]:
    patcher_dir = root / "patcher"
    package_allow = set(read_list(patcher_dir / "vanilla-package-allowlist.txt"))
    binary_allow = {}
    for line in read_list(patcher_dir / "binary-allowlist.txt"):
        path, digest = line.split()
        binary_allow[path] = digest
    terms = BlockedTerms.load(patcher_dir / "blocked-term-hashes.txt", args.extra_terms_file)
    term_allow: dict[str, set[str]] = {}
    for line in read_list(patcher_dir / "term-exceptions.txt"):
        path, digest = line.split()
        term_allow.setdefault(path, set()).add(digest)
    ipv4_allow = set()
    for line in read_list(patcher_dir / "ipv4-allowlist.txt"):
        path, address = line.split()
        ipv4_allow.add((path, address))
    vanilla = vanilla_hashes(args.vanilla_jar, args.asset_index, args.vanilla_hashes)
    findings: list[str] = []

    if args.require_hash_scan and not vanilla:
        findings.append("hash scan requested but no --vanilla-jar/--asset-index/--vanilla-hashes was given")

    for file in sorted(p for p in root.rglob("*") if p.is_file()):
        relative = PurePosixPath(file.relative_to(root).as_posix())
        if relative.parts[0] == ".git":
            continue
        data = file.read_bytes()
        rel = str(relative)

        if in_vanilla_package(relative) and rel not in package_allow:
            findings.append(f"{rel}: file in a vanilla package directory")

        allowed_binary = binary_allow.get(rel) == hashlib.sha256(data).hexdigest()
        if relative.suffix.lower() in BLOCKED_SUFFIXES and not allowed_binary:
            findings.append(f"{rel}: binary or media file type {relative.suffix}")
        if len(data) > MAX_BYTES and not allowed_binary:
            findings.append(f"{rel}: larger than 1 MiB ({len(data)} bytes)")

        if len(data) > 64 and sha1_bytes(data) in vanilla:
            findings.append(f"{rel}: identical to an official game file")

        name_hit = terms.find(rel)
        if name_hit:
            findings.append(f"{rel}: path contains {name_hit}")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            continue
        if relative.suffix == ".gpatch":
            problem = source_patch_problem(text)
            if problem:
                findings.append(f"{rel}: source patch {problem}")
        hit = terms.find(text, frozenset(term_allow.get(rel, ())))
        if hit:
            findings.append(f"{rel}: contains {hit}")
        if rel == "patcher/ipv4-allowlist.txt":
            continue
        for match in IPV4.finditer(text):
            address = match.group(0)
            if all(int(part) <= 255 for part in match.groups()) and address not in ALLOWED_IPV4:
                if not is_documentation_or_private(address) and (rel, address) not in ipv4_allow:
                    findings.append(f"{rel}: IPv4 literal {address}")
    return findings


def is_documentation_or_private(address: str) -> bool:
    a, b, *_ = (int(part) for part in address.split("."))
    return (
        a == 10
        or (a == 192 and b == 168)
        or (a == 172 and 16 <= b <= 31)
        or address.startswith(("192.0.2.", "198.51.100.", "203.0.113."))
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", type=Path, help="tree to check")
    parser.add_argument("--vanilla-jar", type=Path, action="append", default=[])
    parser.add_argument("--asset-index", type=Path, action="append", default=[])
    parser.add_argument("--vanilla-hashes", type=Path, action="append", default=[],
                        help="file with one SHA-1 per line")
    parser.add_argument("--extra-terms-file", type=Path, help="plain blocked words, kept outside the repository")
    parser.add_argument("--require-hash-scan", action="store_true")
    args = parser.parse_args(argv)

    findings = check_tree(args.root, args)
    if findings:
        print(f"public tree check FAILED with {len(findings)} finding(s):")
        for finding in findings:
            print(f"  {finding}")
        return 1
    print(f"public tree check passed: {args.root}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
