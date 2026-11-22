"""Render and verify the public README files from a source tree."""

import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import stat
import subprocess
import sys
from urllib.parse import quote, unquote, urlsplit

CANONICAL = {
    "public/README.md": "README.md",
    "public/README.zh-CN.md": "README.zh-CN.md",
    "public/assets/gaius-icon.svg": "docs/assets/gaius-icon.svg",
}
README_PATHS = {"README.md", "README.zh-CN.md"}
PROFILES = "port/versions"
PROFILE_ID = re.compile(r"[0-9]+(?:\.[0-9]+)+")
RELEASE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9]+(?:[.+-][A-Za-z0-9]+)*)?")
TOKEN = re.compile(r"@([A-Z][A-Z_]+)@")


class PathTree:
    def __init__(self, root):
        self.root = Path(root)

    def _mode(self, path):
        node = self.root
        parts = PurePosixPath(path).parts
        if any(part in ("/", "..", ".git") for part in parts):
            return 0
        try:
            for part in parts:
                if not stat.S_ISDIR(node.lstat().st_mode):
                    return 0
                node /= part
            return node.lstat().st_mode
        except (FileNotFoundError, NotADirectoryError):
            return 0

    def files(self, directory):
        if not stat.S_ISDIR(self._mode(directory)):
            return []
        return [f"{directory}/{p.name}" for p in (self.root / directory).glob("*.json")]

    def read(self, path):
        if not stat.S_ISREG(self._mode(path)):
            raise ValueError(f"missing or not a regular file: {path}")
        return (self.root / path).read_bytes()

    def contains(self, path):
        mode = self._mode(path)
        return stat.S_ISREG(mode) or stat.S_ISDIR(mode)


class GitTree:
    def __init__(self, repo, ref):
        self.repo = repo
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_OPTIONAL_LOCKS="0")
        self.commit = self._git("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").strip().decode("ascii")
        self.entries = {}
        for row in self._git("ls-tree", "-rtz", self.commit).split(b"\0"):
            if row:
                meta, path = row.split(b"\t", 1)
                mode, _, oid = meta.decode("ascii").split()
                self.entries[os.fsdecode(path)] = (mode, oid)

    def _git(self, *args):
        cmd = ["git", "--no-replace-objects", "-C", str(self.repo), *args]
        proc = subprocess.run(cmd, env=self.env, capture_output=True)
        if proc.returncode:
            raise ValueError(proc.stderr.decode("utf-8", "replace").strip() or f"Git failed: {args[0]}")
        return proc.stdout

    def files(self, directory):
        return [path for path in self.entries if posixpath.dirname(path) == directory and path.endswith(".json")]

    def read(self, path):
        mode, oid = self.entries.get(path, (None, None))
        if mode not in ("100644", "100755"):
            raise ValueError(f"missing or not a regular file: {path}")
        return self._git("cat-file", "blob", oid)

    def contains(self, path):
        return path == "." or self.entries.get(path, (None,))[0] in ("040000", "100644", "100755")


def versions(tree):
    release = tree.read("VERSION").decode("utf-8").strip()
    if not RELEASE.fullmatch(release):
        raise ValueError("invalid Gaius version in VERSION")
    ids = []
    for path in tree.files(PROFILES):
        profile = json.loads(tree.read(path))
        version = PurePosixPath(path).stem
        if not PROFILE_ID.fullmatch(version) or not isinstance(profile, dict) or profile.get("id") != version:
            raise ValueError(f"invalid or mismatched Minecraft version: {path}")
        ids.append(version)
    if not ids:
        raise ValueError(f"no Minecraft profiles in {PROFILES}")
    return release, sorted(ids, key=lambda item: tuple(map(int, item.split("."))), reverse=True)


def render(tree, release, ids):
    def join(last, separator):
        return ids[0] if len(ids) == 1 else separator.join(ids[:-1]) + last + ids[-1]

    counts = ("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    values = {
        "RELEASE": release,
        "RELEASE_BADGE": quote(release.replace("-", "--"), safe=""),
        "MINECRAFT_BADGE": quote(" | ".join(ids), safe=""),
        "PROFILE_COUNT": counts[len(ids)] if len(ids) < len(counts) else str(len(ids)),
        "PROFILE_PLURAL": "" if len(ids) == 1 else "s",
        "PROFILE_EXAMPLE": ids[0],
        "PROFILE_ROWS": "\n".join(f"| {version} | Supported |" for version in ids),
        "PROFILES_EN": join(" and ", ", "),
        "PROFILES_ZH": join(" 和 ", "、"),
        "PROFILES_SLASH": " / ".join(ids),
    }
    rendered = {}
    for source, dest in CANONICAL.items():
        data = tree.read(source)
        if not data or data.startswith(b"version https://git-lfs.github.com/spec/v1"):
            raise ValueError(f"empty canonical file or Git LFS pointer: {source}")
        if dest in README_PATHS:
            text = data.decode("utf-8")
            try:
                data = TOKEN.sub(lambda m: values[m[1]], text).encode("utf-8")
            except KeyError as exc:
                raise ValueError(f"unknown README placeholder in {source}: {exc.args[0]}") from exc
        rendered[dest] = data
    return rendered


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attrs):
        for attr, value in attrs:
            if value and attr in ("href", "src", "poster"):
                self.links.append(value)
            elif value and attr == "srcset":
                # Split after each URL plus optional width/density. A data URL can itself contain commas.
                self.links.extend(url.rstrip(",") for url in re.findall(r"(?:^|(?<=,))\s*(\S+)(?:\s+[^,]+)?", value))


def broken_links(text, tree):
    from markdown_it import MarkdownIt

    # Render tokens so reference links, nested linked badges, and raw HTML are all checked,
    # while escaped Markdown and code spans/blocks do not become false links.
    html = HtmlLinks()
    html.feed(MarkdownIt("commonmark").enable("table").render(text))
    html.close()
    for url in dict.fromkeys(html.links):
        try:
            target = urlsplit(url)
            if target.scheme or target.netloc or url.startswith("//") or not target.path:
                continue
            path = unquote(target.path, errors="strict")
            # Check before normalization so missing/../existing cannot designate a valid link.
            parts = []
            for part in path.lstrip("/").split("/"):
                if part == "..":
                    if not parts:
                        break
                    parts.pop()
                elif part not in ("", "."):
                    if not tree.contains("/".join((*parts, part))):
                        break
                    parts.append(part)
            else:
                continue
        except (UnicodeError, ValueError):
            pass
        yield url


def check(source, candidate):
    try:
        expected_version = versions(source)
        files = render(source, *expected_version)
    except (ValueError, OSError) as exc:
        return [(".", f"cannot render canonical public README files: {exc}")]
    findings = []
    try:
        if versions(candidate) != expected_version:
            findings.append(("VERSION", "release or Minecraft profiles differ from the source commit"))
    except (ValueError, OSError) as exc:
        findings.append(("VERSION", str(exc)))
    for path, expected in files.items():
        try:
            actual = candidate.read(path)
            want, got = (hashlib.sha256(data).hexdigest() for data in (expected, actual))
            if want != got:
                findings.append((path, f"canonical SHA-256 mismatch (expected {want}, got {got})"))
            if path in README_PATHS:
                findings.extend((path, f"broken local link: {url}")
                                for url in broken_links(actual.decode("utf-8"), candidate))
        except (ValueError, OSError, ImportError) as exc:
            findings.append((path, str(exc)))
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-repo", required=True)
    parser.add_argument("--source-ref", required=True)
    parser.add_argument("--candidate-repo", required=True)
    parser.add_argument("--candidate-ref", required=True)
    args = parser.parse_args()
    try:
        findings = check(GitTree(args.source_repo, args.source_ref), GitTree(args.candidate_repo, args.candidate_ref))
    except (ValueError, OSError) as exc:
        findings = [(".", str(exc))]
    for path, detail in findings:
        print(f"PUBLIC_README_SYNC: FAIL: {path}: {detail}", file=sys.stderr)
    if not findings:
        print("PUBLIC_README_SYNC: PASS")
    return int(bool(findings))


if __name__ == "__main__":
    sys.exit(main())
