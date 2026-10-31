"""Java source helpers shared by the WebAssembly GC rules and the lints in this directory.

Nothing here compiles Java.  The helpers keep byte offsets and line numbers stable:
comments and literals are replaced by spaces (newlines are kept), so a match in a masked
text points at the same line of the original file.

* ``strip_comments``/``mask_literals``: the two masks.
* ``method_spans``/``enclosing_method``: method and constructor bodies of a file.
* ``jsbody_declarations``: every ``@JSBody`` with the argument count TeaVM sees.
* ``target_source_files``/``wasm_source_files``: the Java files a TeaVM target compiles,
  with the same replacement rules as generate-pom.sh and build-overlays.sh
  (``<x>/src/main/java``, the port/mc/shared source sets the profile selects for port/src and
  the client overrides (port/tools/sourcesets/sourcesets.mjs; relocations only rename imports and
  are not applied here), library ``<x>/src/versions/<range>/java`` and ``<x>/src/target-<t>/java``).
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def rel(path: Path, root: Path = ROOT) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _mask(text: str, keep_literals: bool) -> str:
    out = list(text)
    i = 0
    n = len(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, end):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            end = n if end < 0 else end
            blank(i, end)
            i = end
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            blank(i, end)
            i = end
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            while end > 0 and text[end - 1] == "\\":
                end = text.find('"""', end + 1)
            end = n if end < 0 else end + 3
            if not keep_literals:
                blank(i + 3, max(i + 3, end - 3))
            i = end
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            end = min(j + 1, n)
            if not keep_literals:
                blank(i + 1, max(i + 1, end - 1))
            i = end
        else:
            i += 1
    return "".join(out)


def strip_comments(text: str) -> str:
    """Comments become spaces; string, text block and char literals stay."""
    return _mask(text, keep_literals=True)


def mask_literals(text: str) -> str:
    """Comments and the contents of literals become spaces (the delimiters stay)."""
    return _mask(text, keep_literals=False)


def matching(masked: str, open_index: int) -> int:
    """Index of the bracket that closes the one at open_index (in a masked text), or -1."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    opener = masked[open_index]
    closer = pairs[opener]
    depth = 0
    for k in range(open_index, len(masked)):
        ch = masked[k]
        if ch == opener:
            depth += 1
        elif ch == closer:
            depth -= 1
            if depth == 0:
                return k
    return -1


_NOT_METHODS = {
    "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else",
    "do", "throw", "case", "assert", "super", "this",
}
_METHOD_HEAD = re.compile(r"([A-Za-z_$][\w$]*)\s*\(")


@dataclass
class MethodSpan:
    name: str
    start: int  # offset of the name
    body_start: int  # offset of "{"
    body_end: int  # offset of the matching "}"
    static: bool
    line: int


_DECLARATION_TAIL = re.compile(r"\s*(?:throws\s+[\w$.\s,<>?]+?)?\s*\{")


def method_spans(masked: str) -> list[MethodSpan]:
    """Method and constructor bodies of a masked file (lambdas and initializers excluded)."""
    spans: list[MethodSpan] = []
    for match in _METHOD_HEAD.finditer(masked):
        name = match.group(1)
        if name in _NOT_METHODS:
            continue
        k = match.start() - 1
        while k >= 0 and masked[k].isspace():
            k -= 1
        prev = masked[k] if k >= 0 else ""
        # A declaration follows a type, a modifier, an annotation or the previous member; a
        # call follows ".", "new", an operator, "(", "," or "->".
        if prev == "." or (prev == ">" and k > 0 and masked[k - 1] == "-"):
            continue
        if prev and prev in "=+-*/%&|^!?:,(<[":
            continue
        if prev == "w" and masked[max(0, k - 2):k + 1] == "new" and (k < 3 or not masked[k - 3].isalnum()):
            continue
        open_paren = match.end() - 1
        close_paren = matching(masked, open_paren)
        if close_paren < 0:
            continue
        head = _DECLARATION_TAIL.match(masked, close_paren + 1, min(len(masked), close_paren + 400))
        if not head:
            continue
        body_start = head.end() - 1
        body_end = matching(masked, body_start)
        if body_end < 0:
            continue
        stmt_start = max(masked.rfind(";", 0, match.start()), masked.rfind("{", 0, match.start()),
                         masked.rfind("}", 0, match.start())) + 1
        modifiers = masked[stmt_start:match.start()]
        if re.search(r"\b(?:return|new|throw|else)\b", modifiers):
            continue
        spans.append(MethodSpan(name, match.start(), body_start, body_end,
                                bool(re.search(r"\bstatic\b", modifiers)), line_of(masked, match.start())))
    return spans


def enclosing_method(spans: list[MethodSpan], offset: int) -> MethodSpan | None:
    best = None
    for span in spans:
        if span.body_start < offset < span.body_end and (best is None or span.body_start > best.body_start):
            best = span
    return best


def outer_class_name(path: Path) -> str:
    return path.stem


# ---------------------------------------------------------------------------------------
# @JSBody declarations

_JSBODY = re.compile(r"@(?:org\.teavm\.jso\.)?JSBody\b")


def _top_level_count(masked: str, start: int, end: int) -> int:
    """Number of comma-separated items between start and end (generics and brackets aware)."""
    segment = masked[start:end]
    if not segment.strip():
        return 0
    depth = 0
    count = 1
    for ch in segment:
        if ch in "(<[{":
            depth += 1
        elif ch in ")>]}":
            depth -= 1
        elif ch == "," and depth == 0:
            count += 1
    return count


def _attribute_count(masked: str, start: int, end: int, attribute: str, element: str) -> int:
    match = re.compile(r"\b" + attribute + r"\s*=\s*").search(masked, start, end)
    if not match:
        return 0
    pos = match.end()
    if masked[pos:pos + 1] == "{":
        close = matching(masked, pos)
        inner = masked[pos + 1:close]
        if element == "string":
            return len(re.findall(r'"[^"\n]*"', inner))
        return len(re.findall(r"@(?:org\.teavm\.jso\.)?JSBodyImport\b", inner))
    return 1


@dataclass
class JSBody:
    path: Path
    line: int
    method: str
    static: bool
    params: int  # @JSBody(params = ...) entries
    java_params: int  # parameters of the native method
    imports: int  # @JSBodyImport entries

    @property
    def arguments(self) -> int:
        """Arguments of the WebAssembly GC JS function: imports, the receiver and the parameters."""
        return self.imports + (0 if self.static else 1) + max(self.params, self.java_params)


def jsbody_declarations(path: Path, text: str | None = None) -> list[JSBody]:
    text = read_text(path) if text is None else text
    masked = mask_literals(text)
    found: list[JSBody] = []
    for match in _JSBODY.finditer(masked):
        pos = match.end()
        while pos < len(masked) and masked[pos].isspace():
            pos += 1
        params = imports = 0
        if masked[pos:pos + 1] == "(":
            close = matching(masked, pos)
            if close < 0:
                continue
            params = _attribute_count(masked, pos, close, "params", "string")
            imports = _attribute_count(masked, pos, close, "imports", "import")
            pos = close + 1
        # Skip further annotations and modifiers up to the method name.
        header = re.compile(r"\s*(?:@[\w$.]+\s*(?:\([^)]*\))?|\b(?:public|protected|private|static|final|"
                            r"native|synchronized|abstract|strictfp|default)\b)")
        modifiers = ""
        while True:
            step = header.match(masked, pos)
            if not step or step.end() == pos:
                break
            modifiers += step.group(0)
            pos = step.end()
        head = re.compile(r"\s*(?:<[^>]*>\s*)?[\w$.<>\[\]?, ]+?\s+([A-Za-z_$][\w$]*)\s*\(").match(masked, pos)
        if not head:
            continue
        open_paren = head.end() - 1
        close_paren = matching(masked, open_paren)
        java_params = _top_level_count(masked, open_paren + 1, close_paren) if close_paren > 0 else 0
        found.append(JSBody(path, line_of(masked, match.start()), head.group(1),
                            bool(re.search(r"\bstatic\b", modifiers)), params, java_params, imports))
    return found


# ---------------------------------------------------------------------------------------
# Source sets

def profiles(root: Path = ROOT) -> list[str]:
    ids = []
    for path in sorted((root / "port" / "versions").glob("*.json")):
        try:
            ids.append(json.loads(read_text(path))["id"])
        except (ValueError, KeyError):
            continue
    return ids


def profile_json(profile: str, root: Path = ROOT) -> dict:
    direct = root / "port" / "versions" / f"{profile}.json"
    if direct.is_file():
        return json.loads(read_text(direct))
    for path in sorted((root / "port" / "versions").glob("*.json")):
        data = json.loads(read_text(path))
        if data.get("id") == profile:
            return data
    raise FileNotFoundError(f"no port/versions profile with id {profile}")


def read_excludes(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    entries = set()
    for line in read_text(path).splitlines():
        entry = line.split("#", 1)[0].strip()
        if entry:
            entries.add(entry)
    return entries


def _java_files(directory: Path) -> dict[str, Path]:
    files: dict[str, Path] = {}
    if directory.is_dir():
        for dirpath, _dirnames, filenames in os.walk(directory):
            for name in filenames:
                if name.endswith(".java"):
                    full = Path(dirpath) / name
                    files[full.relative_to(directory).as_posix()] = full
    return files


def library_versions(profile: str, root: Path = ROOT) -> dict[str, set[str]] | None:
    """Maven artifact versions per artifact id of a profile's libraries, from the fetched
    port/work/<profile>/version.json (None when that file is not there)."""
    metadata = root / "port" / "work" / profile / "version.json"
    if not metadata.is_file():
        return None
    versions: dict[str, set[str]] = {}
    for library in json.loads(read_text(metadata)).get("libraries", []):
        parts = str(library.get("name", "")).split(":")
        if len(parts) >= 3:
            versions.setdefault(parts[1], set()).add(parts[2])
    return versions


# The groups whose profile-specific files come from the port/mc/shared source sets, by kind.
SOURCE_SET_KINDS = {"port/src": "src", "port/overrides/client": "client"}


def source_sets(root: Path = ROOT) -> list[dict]:
    """The sets of port/mc/shared/sourcesets.json in manifest order (empty without a manifest)."""
    manifest = root / "port" / "mc" / "shared" / "sourcesets.json"
    if not manifest.is_file():
        return []
    return list(json.loads(read_text(manifest)).get("sets", []))


def set_selects(source_set: dict, profile: dict) -> bool:
    """Whether a source set's selector takes the profile (sourcesets.mjs selects)."""
    selector = source_set.get("selects")
    if selector == "*":
        return True
    for key, values in (selector or {}).items():
        value: object = profile
        for part in key.split("."):
            value = value.get(part) if isinstance(value, dict) else None
        if value not in values:
            return False
    return True


def _set_root(root: Path, source_set: dict, kind: str) -> Path:
    return root / "port" / "mc" / "shared" / source_set["name"] / kind


@dataclass
class SourceGroup:
    """One overlay or source unit: shared, profile/version and target roots."""
    name: str
    base: Path  # the directory holding src/
    files: dict[str, Path] = field(default_factory=dict)  # relative path -> file that compiles


def _groups(root: Path) -> list[tuple[str, Path]]:
    groups = [("port/src", root / "port" / "src")]
    overrides = root / "port" / "overrides"
    for name in ("classlib", "client"):
        if (overrides / name / "src").is_dir():
            groups.append((f"port/overrides/{name}", overrides / name / "src"))
    libraries = overrides / "libraries"
    if libraries.is_dir():
        for library in sorted(libraries.iterdir()):
            if (library / "src").is_dir():
                groups.append((f"port/overrides/libraries/{library.name}", library / "src"))
    return groups


def target_source_files(target: str, profile: str | None, root: Path = ROOT) -> list[SourceGroup]:
    """The Java files of every source group that a TeaVM target compiles for a profile.

    ``profile=None`` takes every profile and library version directory (the union a rule
    that does not depend on the profile has to cover).  Later roots replace files of earlier
    roots with the same relative path: main, then the profile/library version, then the
    target root.  src/test and the roots of the other targets never compile.
    """
    result = []
    lib_versions = library_versions(profile, root) if profile else None
    for name, src in _groups(root):
        artifact = name.rsplit("/", 1)[1]
        if (name.startswith("port/overrides/libraries/lwjgl") and lib_versions is not None
                and artifact not in lib_versions):
            # build-overlays.sh skips an LWJGL module the profile does not ship (26.3 has
            # lwjgl-sdl instead of lwjgl-glfw); other override directories are not named
            # after their artifact and stay in.
            continue
        group = SourceGroup(name, src)
        main = src / "main" / "java"
        files = _java_files(main)
        kind = SOURCE_SET_KINDS.get(name)
        if kind is not None:
            profile_data = profile_json(profile, root) if profile is not None else None
            for source_set in source_sets(root):
                set_root = _set_root(root, source_set, kind)
                if profile_data is not None:
                    if not set_selects(source_set, profile_data):
                        continue
                    for excluded in read_excludes(set_root / "excludes.txt"):
                        files.pop(excluded, None)
                files.update(_java_files(set_root / "java"))
        version_roots: list[Path] = []
        versions_dir = src / "versions"
        if versions_dir.is_dir() and name.startswith("port/overrides/libraries/"):
            for version_dir in sorted(versions_dir.iterdir()):
                if not version_dir.is_dir():
                    continue
                artifact = name.rsplit("/", 1)[1]
                if profile is not None and lib_versions is not None:
                    if version_dir.name not in lib_versions.get(artifact, set()):
                        continue
                version_roots.append(version_dir)
        for version_dir in version_roots:
            if profile is not None:
                for excluded in read_excludes(version_dir / "excludes.txt"):
                    files.pop(excluded, None)
            files.update(_java_files(version_dir / "java"))
        files.update(_java_files(src / f"target-{target}" / "java"))
        group.files = files
        result.append(group)
    return result


def wasm_source_files(profile: str | None = None, root: Path = ROOT) -> dict[Path, str]:
    """Every Java file that can compile into a WebAssembly GC build -> its group name."""
    out: dict[Path, str] = {}
    for group in target_source_files("wasmgc", profile, root):
        for path in group.files.values():
            out[path] = group.name
    if profile is None:
        # Version files of other profiles replace the shared file only for their profile:
        # keep both (target_source_files(None) folded them into one dict by path).
        for name, src in _groups(root):
            target_files = _java_files(src / "target-wasmgc" / "java")
            kind = SOURCE_SET_KINDS.get(name)
            if kind is not None:
                for source_set in source_sets(root):
                    for relative, path in _java_files(_set_root(root, source_set, kind) / "java").items():
                        if relative not in target_files:
                            out[path] = name
            versions_dir = src / "versions"
            if versions_dir.is_dir() and name.startswith("port/overrides/libraries/"):
                for version_dir in versions_dir.iterdir():
                    for relative, path in _java_files(version_dir / "java").items():
                        if relative not in target_files:
                            out[path] = name
            for relative, path in _java_files(src / "main" / "java").items():
                if relative not in target_files:
                    out[path] = name
    return out


def target_roots(target: str, root: Path = ROOT) -> list[Path]:
    """Every <x>/src/target-<target>/java root (port/src and port/overrides)."""
    roots = []
    for _name, src in _groups(root):
        candidate = src / f"target-{target}" / "java"
        if candidate.is_dir():
            roots.append(candidate)
    return roots


# ---------------------------------------------------------------------------------------
# Allow-list

@dataclass
class AllowEntry:
    rule: str
    path: str
    symbol: str
    reason: str
    line: int
    used: bool = False


def read_allowlist(path: Path) -> list[AllowEntry]:
    entries = []
    if not path.is_file():
        return entries
    for number, raw in enumerate(read_text(path).splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) != 4 or not all(parts):
            raise ValueError(f"{path}:{number}: expected '<rule> | <path> | <symbol> | <reason>'")
        entries.append(AllowEntry(parts[0], parts[1], parts[2], parts[3], number))
    return entries


def allowed(entries: list[AllowEntry], rule: str, path: str, symbol: str) -> bool:
    for entry in entries:
        if entry.rule == rule and entry.path == path and (
                symbol == entry.symbol or symbol.startswith(entry.symbol + ".")):
            entry.used = True
            return True
    return False
