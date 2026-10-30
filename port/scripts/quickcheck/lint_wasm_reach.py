#!/usr/bin/env python3
"""wasm reachability lint: JavaScript-target constructs reachable in a WebAssembly GC build.

  lint_wasm_reach.py [--profile P]... [--overlay-log P=LOG]... [--client-jar P=JAR]...
                     [--json F] [--enforce] [--self-test]

For every profile (port/versions/*.json) the lint computes, without building anything,
the Gaius classes a wasmgc build can reach and the bytecode the patchers weave into
vanilla classes, and flags what WebAssembly GC cannot compile or run:

  JSByRef    @JSByRef parameters (zero-copy JS views of Java arrays)
  Platform   org.teavm.platform (the TeaVM JavaScript platform API: Platform.startThread,
             PlatformRunnable, ...)
  Async      @Async methods (TeaVM coroutines; the JSPI build compiles none)
  JSBody>31  @JSBody functions with more than 31 arguments (wasmgc_rules.py rule d)

How reachability is approximated (the idea of a reachability walk, on sources):

* Source sets: port/src and port/overrides as the wasmgc build stages them for the
  profile (main, profile/library version and target-wasmgc roots; javasrc.py).
* Patchers: the patcher classes of the profile's chain (build-overlays.sh patchSet arms and
  extraPatchers), from their main methods through the calls in port/tools.  The domain classes
  (tools/patches/<domain>) hold the patch code that the steps call; the main method
  of a domain class (a standalone JVM check of that code) counts only once a step of the
  profile reaches the class.  Code that the wasmgc target never runs is left out: the bodies
  of patches whose spec names only the js target (PatchRegistry.run("<id>", "<KIND> js", ...)),
  the JavaScript branches of BuildTarget.wasmGc() (if/else, early return, ?:), and, with
  --overlay-log, the patches a wasmgc overlay build reported as PATCH_DROPPED or
  PATCH_TARGET_SKIPPED.
* Roots: classes named by string literals in that patcher code (the owners woven into
  vanilla classes), replacement classes of the overrides (everything outside dev.gaius),
  and the Worker main class.  Edges: imports, qualified names and same-package simple
  names of the Java sources (class level).
* Woven code: an org.teavm.platform literal in reachable patcher code that is an argument
  of an ASM node, a visit call, an interface list or a superclass is reported at the
  patcher lines.  With --client-jar, the patched client jar of a wasmgc overlay build is
  also scanned for org/teavm/platform references (exact, but without source lines).

Patches that the patcher drops at run time because the vanilla shape is missing cannot be
seen on the sources; without --overlay-log the lint over-approximates them (a finding of a
profile then names the patch to check).  Exceptions are the wasmgc-allowlist.txt entries
of rules c (JSByRef, Platform) and d (JSBody).

Warn mode (M1): findings are printed and the exit status is 0; --enforce exits 1 (M2).
"""

from __future__ import annotations

import argparse
import bisect
import json
import os
import re
import sys
import tempfile
import zipfile
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javasrc  # noqa: E402
import lint_vanilla_symbols  # noqa: E402

ROOT = javasrc.ROOT
ALLOWLIST = Path(__file__).resolve().parent / "wasmgc-allowlist.txt"
TOOLS = Path("port/tools/src/main/java/dev/gaius/tools")
WORKER_MAIN = "dev.gaius.browser.BrowserIntegratedServerMain"
JSBODY_MAX_ARGUMENTS = 31

JSBYREF = re.compile(r"@(?:org\.teavm\.jso\.)?JSByRef\b")
ASYNC = re.compile(r"@(?:org\.teavm\.interop\.)?Async\b")
# A class name, also inside a descriptor ("(Lorg/teavm/platform/PlatformRunnable;)V").
PLATFORM = re.compile(r"(?:(?<![\w$])|(?<=\bL))org[./]teavm[./]platform(?:[./][\w$]+)*")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
INTERNAL_NAME = re.compile(r"(?:^|L|\[)([A-Za-z_$][\w$]*(?:/[\w$]+)+)(?:;|$)")
DOTTED_NAME = re.compile(r"^([a-z_][\w]*(?:\.[a-z_][\w]*)+\.[A-Z][\w$]*)$")
WEAVING_CALL = re.compile(r"(?:Insn|InsnNode|Node|Handle|visit\w*|add|addAll|invoke\w*|emit\w*|"
                          r"call\w*|field\w*|set\w*)$")
NOT_WEAVING_CALL = re.compile(r"^(?:of|asList|contains|equals|startsWith|endsWith|matches|indexOf|"
                              r"println|format|requireOwner|check\w*|verify\w*|assert\w*)$")


# ---------------------------------------------------------------------------------------
# Patchers

def chain_selection(root: Path) -> dict[str, set[str]]:
    """patchSet -> patcher classes of its arm in build-overlays.sh."""
    text = javasrc.read_text(root / "port/scripts/build-overlays.sh")
    chains: dict[str, set[str]] = {}
    blocks = [m.group(1) for m in re.finditer(r'case\s+"\$patch_set"\s+in\n(.*?)\nesac', text, re.S)
              if "run_client_patcher" in m.group(1)]
    if not blocks:
        return chains
    current = None
    for line in blocks[0].splitlines():
        arm = re.match(r"\s*([\w.-]+)\)\s*$", line)
        if arm:
            current = arm.group(1)
            chains[current] = set()
            continue
        run = re.match(r"\s*run_client_patcher\s+(\w+)", line)
        if run and current:
            chains[current].add(run.group(1))
    return chains


@dataclass
class Patcher:
    name: str
    path: Path
    text: str
    stripped: str
    masked: str
    spans: list
    methods: dict = field(default_factory=dict)
    js_regions: list = field(default_factory=list)
    # Single static imports ("import static a.b.Owner.member;"): member -> Owner, a class of
    # port/tools, so that the calls of the domain classes into shared patcher helpers are followed.
    static_imports: dict = field(default_factory=dict)
    # A class of the domain packages (tools/patches/): patch code that the steps call.
    in_domain: bool = False


STATIC_IMPORT = re.compile(r"^\s*import\s+static\s+[\w$.]*?([A-Z][\w$]*)\s*\.\s*([A-Za-z_$][\w$]*)\s*;", re.M)


def load_patchers(root: Path) -> dict[str, Patcher]:
    patchers = {}
    base = root / TOOLS
    for path in sorted(base.rglob("*.java")):
        text = javasrc.read_text(path)
        masked = javasrc.mask_literals(text)
        patcher = Patcher(path.stem, path, text, javasrc.strip_comments(text), masked,
                          javasrc.method_spans(masked))
        for span in patcher.spans:
            patcher.methods.setdefault(span.name, []).append(span)
        patcher.static_imports = {m.group(2): m.group(1) for m in STATIC_IMPORT.finditer(masked)}
        patcher.in_domain = path.relative_to(base).parts[0] == "patches"
        patchers[path.stem] = patcher
    return patchers


def applicable_patchers(profile: str, patchers: dict[str, Patcher], root: Path) -> set[str]:
    data = javasrc.profile_json(profile, root)
    chains = chain_selection(root)
    in_any_chain = set().union(*chains.values()) if chains else set()
    own_chain = chains.get(data.get("patchSet", ""), set())
    all_extras = set()
    for other in javasrc.profiles(root):
        all_extras.update(javasrc.profile_json(other, root).get("extraPatchers", []))
    extras = set(data.get("extraPatchers", []))
    excluded = (in_any_chain - own_chain) | (all_extras - extras)
    # The domain classes stay in: their code is live only where a step of the chain calls it.
    return {name for name in patchers if name not in excluded}


def _statement_end(masked: str, start: int) -> int:
    depth = 0
    for k in range(start, len(masked)):
        ch = masked[k]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            if depth == 0:
                return k
            depth -= 1
        elif ch == ";" and depth == 0:
            return k
    return len(masked)


def _block_or_statement(masked: str, pos: int) -> tuple[int, int]:
    while pos < len(masked) and masked[pos].isspace():
        pos += 1
    if masked[pos:pos + 1] == "{":
        return pos, javasrc.matching(masked, pos)
    return pos, _statement_end(masked, pos)


def _enclosing_block_end(masked: str, pos: int) -> int:
    depth = 0
    for k in range(pos, len(masked)):
        if masked[k] == "{":
            depth += 1
        elif masked[k] == "}":
            if depth == 0:
                return k
            depth -= 1
    return len(masked)


def js_only_regions(patcher: Patcher, skipped_ids: set[str]) -> list[tuple[int, int]]:
    """Offsets of patcher code that a wasmgc overlay build does not run."""
    masked, stripped = patcher.masked, patcher.stripped
    regions = []
    for match in re.finditer(r"\b(?:PatchRegistry\s*\.\s*)?(run)\s*\(", masked):
        if not match.group(0).startswith("PatchRegistry"):
            continue
        close = javasrc.matching(masked, match.end() - 1)
        if close < 0:
            continue
        literal = LITERAL.match(stripped, _skip_space(masked, match.end()))
        patch_id = literal.group(1) if literal else None
        if ((patch_id and _dropped(patch_id, skipped_ids))
                or (literal and _spec_leaves_out_wasmgc(stripped, masked, literal.end()))):
            regions.append((match.start(), close))
    return regions + guard_regions(masked, PATCHER_GUARD)


# Expressions that are true in a WebAssembly GC build and false in the JavaScript one.
PATCHER_GUARD = re.compile(r"(!?)\s*BuildTarget\s*\.\s*wasmGc\s*\(\s*\)")
SOURCE_GUARD = re.compile(r"(!?)\s*HostLoop\s*\.\s*(?:isWasmGc|jspi)\s*\(\s*\)")


def guard_regions(masked: str, guard: re.Pattern) -> list[tuple[int, int]]:
    """JavaScript-only code under a wasm guard: the other branch of an if/else, the rest of
    the block after an if that returns or throws, and the other side of a ?: expression."""
    regions = []
    for match in guard.finditer(masked):
        negated = match.group(1) == "!"
        before = masked[:match.start()].rstrip()
        after_pos = match.end()
        after = masked[after_pos:].lstrip()
        if before.endswith("(") and re.search(r"\bif\s*\($", before):
            open_paren = before.rfind("(")
            close_paren = javasrc.matching(masked, open_paren)
            then_start, then_end = _block_or_statement(masked, close_paren + 1)
            rest = masked[then_end + 1:]
            else_match = re.match(r"\s*else\b", rest)
            if else_match:
                else_start, else_end = _block_or_statement(masked, then_end + 1 + else_match.end())
                regions.append((then_start, then_end) if negated else (else_start, else_end))
            elif negated:
                regions.append((then_start, then_end))
            else:
                body = masked[then_start:then_end + 1].rstrip()
                if body.endswith("}"):
                    body = body[:-1].rstrip()
                cut = max(body.rfind(";", 0, len(body) - 1), body.rfind("{", 0, len(body) - 1),
                          body.rfind("}", 0, len(body) - 1))
                last_statement = body[cut + 1:] if body.endswith(";") else ""
                if re.match(r"\s*(?:return|throw)\b", last_statement):
                    regions.append((then_end + 1, _enclosing_block_end(masked, then_end + 1)))
        elif after.startswith("?"):
            question = _skip_space(masked, after_pos)
            colon = _expression_end(masked, question + 1, stop=":")
            if masked[colon:colon + 1] != ":":
                continue
            end = _expression_end(masked, colon + 1)
            regions.append((question, colon) if negated else (colon, end))
    return regions


def _spec_leaves_out_wasmgc(stripped: str, masked: str, pos: int) -> bool:
    """Whether PatchRegistry.run("<id>", "<spec>", ...) names targets without wasmgc ("PERF js")."""
    pos = _skip_space(masked, pos)
    if masked[pos:pos + 1] != ",":
        return False
    spec = LITERAL.match(stripped, _skip_space(masked, pos + 1))
    if not spec:
        return False
    tokens = spec.group(1).split()[1:]
    targets = [token for token in tokens if token in ("js", "wasmgc")]
    return bool(targets) and "wasmgc" not in targets


def _skip_space(text: str, pos: int) -> int:
    while pos < len(text) and text[pos].isspace():
        pos += 1
    return pos


def _expression_end(masked: str, start: int, stop: str = "") -> int:
    """First depth-0 ";" "," (or stop) after start, or the bracket that closes the expression."""
    depth = 0
    for k in range(start, len(masked)):
        ch = masked[k]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            if depth == 0:
                return k
            depth -= 1
        elif depth == 0 and (ch in ";," or (stop and ch == stop)):
            return k
    return len(masked)


def _dropped(patch_id: str, skipped_ids: set[str]) -> bool:
    return patch_id in skipped_ids or any(patch_id.startswith(s + ".") for s in skipped_ids)


def _in_regions(offset: int, regions: list[tuple[int, int]]) -> bool:
    if len(regions) > 16:
        # Live method ranges come merged: sorted and disjoint (see _merge).
        index = bisect.bisect_right(regions, (offset, float("inf"))) - 1
        return index >= 0 and regions[index][0] <= offset <= regions[index][1]
    return any(start <= offset <= end for start, end in regions)


def _merge(ranges: list[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[tuple[int, int]] = []
    for start, end in sorted(ranges):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def reachable_patcher_code(names: set[str], patchers: dict[str, Patcher], skipped_ids: set[str]) -> dict:
    """{patcher name: list of (start, end) live code ranges} reached from the main methods."""
    for name in names:
        patchers[name].js_regions = js_only_regions(patchers[name], skipped_ids)
    live: dict[str, set[int]] = {name: set() for name in names}  # indices into spans
    by_name: dict[str, dict[str, list[int]]] = {}
    for name in names:
        by_name[name] = {}
        for index, span in enumerate(patchers[name].spans):
            by_name[name].setdefault(span.name, []).append(index)
    queue = deque()
    # A step's main is a root; a domain class's main joins once the steps reach its class.
    domain_mains: dict[str, list[int]] = {}
    for name in names:
        mains = [index for index, span in enumerate(patchers[name].spans) if span.name == "main"]
        if patchers[name].in_domain:
            domain_mains[name] = mains
        else:
            queue.extend((name, index) for index in mains)
    call = re.compile(r"(?:\b([A-Z][\w$]*)\s*\.\s*)?\b([a-zA-Z_$][\w$]*)\s*\(|"
                      r"\b([A-Z][\w$]*|this)\s*::\s*([a-zA-Z_$][\w$]*)")
    while queue:
        name, index = queue.popleft()
        if index in live[name]:
            continue
        live[name].add(index)
        if name in domain_mains:
            queue.extend((name, main) for main in domain_mains.pop(name))
        patcher = patchers[name]
        span = patcher.spans[index]
        body = patcher.masked[span.body_start:span.body_end]
        for match in call.finditer(body):
            offset = span.body_start + match.start()
            if _in_regions(offset, patcher.js_regions):
                continue
            owner = match.group(1) or match.group(3)
            method = match.group(2) or match.group(4)
            target = name if owner in (None, "this") else owner
            if owner is None and method not in by_name[name] and method in patcher.static_imports:
                target = patcher.static_imports[method]
            if target not in live or method in javasrc._NOT_METHODS:
                continue
            for callee_index in by_name[target].get(method, ()):
                if callee_index not in live[target]:
                    queue.append((target, callee_index))
    return {name: _merge([(patchers[name].spans[i].body_start, patchers[name].spans[i].body_end)
                          for i in indices]) for name, indices in live.items()}


def _call_name_before(masked: str, offset: int) -> str:
    depth = 0
    for k in range(offset - 1, max(0, offset - 4000), -1):
        ch = masked[k]
        if ch == ")":
            depth += 1
        elif ch == "(":
            if depth == 0:
                end = k
                while end > 0 and masked[end - 1].isspace():
                    end -= 1
                start = end
                while start > 0 and (masked[start - 1].isalnum() or masked[start - 1] in "_$"):
                    start -= 1
                return masked[start:end]
            depth -= 1
        elif ch in ";{}" and depth == 0:
            return ""
    return ""


def _statement_text(masked: str, offset: int) -> str:
    start = max(masked.rfind(";", 0, offset), masked.rfind("{", 0, offset), masked.rfind("}", 0, offset)) + 1
    return masked[start:_statement_end(masked, offset)]


def string_constants(patcher: Patcher) -> dict[str, tuple[int, str]]:
    """{NAME: (offset after the declaration's "=", value)} of its static final String constants."""
    constants = {}
    for match in re.finditer(r"\bstatic\s+final\s+String\s+([A-Z_][A-Z0-9_]*)\s*=\s*", patcher.masked):
        literal = LITERAL.match(patcher.stripped, match.end())
        if literal:
            constants[match.group(1)] = (match.end(), literal.group(1))
    return constants


def woven_literals(name: str, patcher: Patcher, live: list[tuple[int, int]],
                   patchers: dict[str, Patcher] | None = None):
    """(offset, value, woven?) of string literals in live code and of the constants it reads:
    its own and the statically imported ones of other port/tools classes."""
    constants = string_constants(patcher)
    for member, owner in patcher.static_imports.items():
        if member not in constants and patchers and owner in patchers:
            imported = string_constants(patchers[owner]).get(member)
            if imported:
                constants[member] = (-1, imported[1])
    for match in LITERAL.finditer(patcher.stripped):
        offset = match.start()
        if not _in_regions(offset, live) or _in_regions(offset, patcher.js_regions):
            continue
        yield offset, match.group(1), _is_woven(patcher.masked, offset)
    if not constants:
        return
    uses = re.compile(r"\b(" + "|".join(re.escape(c) for c in sorted(constants, key=len, reverse=True)) + r")\b")
    for use in uses.finditer(patcher.masked):
        definition, value = constants[use.group(1)]
        if use.start() < definition and not patcher.masked[use.end():definition].replace("=", "").strip():
            continue  # the declaration itself
        if not _in_regions(use.start(), live) or _in_regions(use.start(), patcher.js_regions):
            continue
        yield use.start(), value, _is_woven(patcher.masked, use.start())


def _is_woven(masked: str, offset: int) -> bool:
    callee = _call_name_before(masked, offset)
    statement = _statement_text(masked, offset)
    if re.search(r"\b(?:superName|interfaces|owner|desc|signature)\s*(?:=|\.add)", statement):
        return True
    if callee and NOT_WEAVING_CALL.match(callee):
        return False
    return bool(callee and WEAVING_CALL.search(callee))


def _line_range(text: str, start: int, end: int) -> str:
    first, last = javasrc.line_of(text, start), javasrc.line_of(text, end)
    return f"{first}" if first == last else f"{first}-{last}"


# ---------------------------------------------------------------------------------------
# Gaius classes

@dataclass
class SourceClass:
    fqn: str
    path: Path
    group: str
    package: str


def index_sources(profile: str, root: Path) -> dict[str, SourceClass]:
    index = {}
    for group in javasrc.target_source_files("wasmgc", profile, root):
        for path in group.files.values():
            head = javasrc.read_text(path)[:4000]
            match = re.search(r"^\s*package\s+([\w.]+)\s*;", javasrc.strip_comments(head), re.M)
            package = match.group(1) if match else ""
            fqn = f"{package}.{path.stem}" if package else path.stem
            index[fqn] = SourceClass(fqn, path, group.name, package)
    return index


def class_of_name(name: str, index: dict[str, SourceClass]) -> str | None:
    name = name.replace("/", ".").split("$", 1)[0]
    while name:
        if name in index:
            return name
        if "." not in name:
            return None
        name = name.rsplit(".", 1)[0]
    return None


def references(source: SourceClass, index: dict[str, SourceClass], by_package: dict[str, dict[str, str]]):
    code = javasrc.mask_literals(javasrc.read_text(source.path))
    found = set()
    for match in re.finditer(r"\bimport\s+(static\s+)?([\w.]+?)(\.\*)?\s*;", code):
        name = match.group(2)
        if match.group(3) and not match.group(1):
            found.update(by_package.get(name, {}).values())
        else:
            target = class_of_name(name, index)
            if target:
                found.add(target)
    for match in re.finditer(r"\b((?:[a-z_][\w]*\.)+[A-Z][\w$]*)", code):
        target = class_of_name(match.group(1), index)
        if target:
            found.add(target)
    same_package = by_package.get(source.package, {})
    for token in set(re.findall(r"\b([A-Z][\w$]*)\b", code)):
        if token in same_package:
            found.add(same_package[token])
    found.discard(source.fqn)
    return found


def class_flags(source: SourceClass, entries, root: Path) -> list[dict]:
    text = javasrc.read_text(source.path)
    relative = javasrc.rel(source.path, root)
    flags = []
    code = javasrc.mask_literals(text)
    stripped = javasrc.strip_comments(text)
    for match in JSBYREF.finditer(code):
        if not javasrc.allowed(entries, "c", relative, "org.teavm.jso.JSByRef"):
            flags.append({"kind": "JSByRef", "where": f"{relative}:{javasrc.line_of(code, match.start())}",
                          "method": _call_name_before(code, match.start())})
    for match in ASYNC.finditer(code):
        head = DECLARED_METHOD.match(code, match.end())
        flags.append({"kind": "Async", "where": f"{relative}:{javasrc.line_of(code, match.start())}",
                      "method": head.group(1) if head else ""})
    for match in PLATFORM.finditer(stripped):
        symbol = match.group(0).replace("/", ".")
        if not javasrc.allowed(entries, "c", relative, symbol):
            flags.append({"kind": "Platform", "where": f"{relative}:{javasrc.line_of(stripped, match.start())}",
                          "detail": symbol})
    if "JSBody" in text:
        for body in javasrc.jsbody_declarations(source.path, text):
            if body.arguments > JSBODY_MAX_ARGUMENTS and not javasrc.allowed(entries, "d", relative, body.method):
                flags.append({"kind": "JSBody>31", "where": f"{relative}:{body.line}",
                              "detail": f"{body.method}: {body.arguments} arguments", "method": body.method})
    return flags


DECLARED_METHOD = re.compile(r"\s*(?:@[\w$.]+\s*)*(?:(?:public|protected|private|static|final|native|synchronized)\s+)*"
                             r"(?:<[^>]*>\s*)?[\w$.<>\[\]?, ]+?\s+([A-Za-z_$][\w$]*)\s*\(")
NOT_TYPES = {"return", "new", "throw", "else", "case", "yield", "assert", "await", "do"}


def _is_declaration(masked: str, offset: int) -> bool:
    """True when the identifier at offset is declared there (a type or modifier precedes it)."""
    k = offset - 1
    while k >= 0 and masked[k].isspace():
        k -= 1
    if k < 0:
        return False
    if masked[k] in ">]":
        return masked[k] == "]" or not (k > 0 and masked[k - 1] == "-")
    end = k + 1
    while k >= 0 and (masked[k].isalnum() or masked[k] in "_$"):
        k -= 1
    word = masked[k + 1:end]
    return bool(word) and word not in NOT_TYPES and not word[0].isdigit()


def _public_method(masked: str, method: str) -> bool:
    for match in re.finditer(r"\b" + re.escape(method) + r"\s*\(", masked):
        if _is_declaration(masked, match.start()):
            start = max(masked.rfind(";", 0, match.start()), masked.rfind("{", 0, match.start()),
                        masked.rfind("}", 0, match.start())) + 1
            if re.search(r"\b(?:public|protected)\b", masked[start:match.start()]):
                return True
    return False


# ---------------------------------------------------------------------------------------

def overlay_skips(log: Path) -> set[str]:
    ids = set()
    for line in javasrc.read_text(log).splitlines():
        match = re.match(r"^(?:PATCH_DROPPED|PATCH_TARGET_SKIPPED|BRINGUP_SKIP)\s+(\S+)", line.strip())
        if match:
            ids.add(match.group(1))
    return ids


def client_jar_platform(jar: Path) -> list[dict]:
    found = []
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith(".class") or name.startswith("META-INF/"):
                continue
            symbols = sorted({m.group(0).replace("/", ".") for value in
                              lint_vanilla_symbols.class_constants(archive.read(name))
                              for m in PLATFORM.finditer(value)})
            if symbols:
                found.append({"kind": "Platform", "where": f"{jar.name}!{name}", "detail": ", ".join(symbols)})
    return found


def analyze(profile: str, root: Path, patchers: dict[str, Patcher], entries, skipped_ids: set[str],
            client_jar: Path | None) -> dict:
    names = applicable_patchers(profile, patchers, root)
    live = reachable_patcher_code(names, patchers, skipped_ids)
    index = index_sources(profile, root)
    by_package: dict[str, dict[str, str]] = {}
    for fqn, source in index.items():
        by_package.setdefault(source.package, {})[fqn.rsplit(".", 1)[-1]] = fqn
    findings = []
    parent: dict[str, str] = {}
    queue = deque()

    def root_class(fqn: str, why: str) -> None:
        if fqn not in parent:
            parent[fqn] = why
            queue.append(fqn)

    # Members of Gaius classes that woven code calls: the other literals of a statement that
    # names the owner ("dev/gaius/browser/X", "method", "(descriptor)").
    woven_members: dict[str, set[str]] = {}
    for name in sorted(names):
        patcher = patchers[name]
        platform_hits: dict[int, list[int]] = {}
        statements: dict[int, list[str]] = {}
        for offset, value, woven in woven_literals(name, patcher, live[name], patchers):
            statement_start = max(patcher.masked.rfind(";", 0, offset), patcher.masked.rfind("{", 0, offset),
                                  patcher.masked.rfind("}", 0, offset)) + 1
            statements.setdefault(statement_start, []).append(value)
            if PLATFORM.search(value):
                if woven:
                    platform_hits.setdefault(statement_start, []).append(offset)
                continue
            for raw in INTERNAL_NAME.findall(value) or ([value] if DOTTED_NAME.match(value) else []):
                target = class_of_name(raw, index)
                if target:
                    root_class(target, f"woven by {javasrc.rel(patcher.path, root)}:{javasrc.line_of(patcher.stripped, offset)}")
        for values in statements.values():
            owners = {class_of_name(raw, index) for value in values for raw in INTERNAL_NAME.findall(value)}
            members = {value for value in values if re.fullmatch(r"[A-Za-z_$][\w$]*", value)}
            for owner in owners - {None}:
                woven_members.setdefault(owner, set()).update(members)
        for statement_start, offsets in sorted(platform_hits.items()):
            symbols = sorted({m.group(0).replace("/", ".") for o in offsets
                              for m in PLATFORM.finditer(LITERAL.match(patcher.stripped, o).group(1)
                                                         if LITERAL.match(patcher.stripped, o) else
                                                         patcher.stripped[o:o + 200])})
            span = javasrc.enclosing_method(patcher.spans, offsets[0])
            findings.append({"kind": "Platform", "woven": True,
                             "where": f"{javasrc.rel(patcher.path, root)}:{_line_range(patcher.stripped, min(offsets), max(offsets))}",
                             "detail": ", ".join(symbols) + (f" (woven by {name}.{span.name})" if span else "")})
    for fqn, source in index.items():
        if not fqn.startswith("dev.gaius."):
            root_class(fqn, f"replaces a library class ({source.group})")
    if WORKER_MAIN in index:
        root_class(WORKER_MAIN, "Worker main class")
    edges: dict[str, set[str]] = {}
    while queue:
        fqn = queue.popleft()
        edges[fqn] = references(index[fqn], index, by_package)
        for target in edges[fqn]:
            if target not in parent:
                parent[target] = fqn
                queue.append(target)
    code_cache: dict[str, tuple[str, list]] = {}

    def class_code(fqn: str) -> tuple[str, list]:
        if fqn not in code_cache:
            masked = javasrc.mask_literals(javasrc.read_text(index[fqn].path))
            code_cache[fqn] = (masked, guard_regions(masked, SOURCE_GUARD))
        return code_cache[fqn]

    def method_reached(owner: str, method: str) -> str | None:
        """Why a method of a reachable class is reached in the wasm build, or None."""
        if not method:
            return "unknown method"
        if method in woven_members.get(owner, ()):
            return "woven call"
        if not owner.startswith("dev.gaius.") and _public_method(class_code(owner)[0], method):
            return "public method of a library replacement"
        call = re.compile(r"(::\s*)?\b" + re.escape(method) + r"\b(\s*\()?")
        for caller in [owner] + sorted(c for c in parent if owner in edges.get(c, ())):
            masked, regions = class_code(caller)
            if method not in masked:
                continue
            for match in call.finditer(masked):
                if not (match.group(1) or match.group(2)) or _in_regions(match.start(), regions):
                    continue
                if not match.group(1) and _is_declaration(masked, match.start()):
                    continue
                return f"called from {caller.rsplit('.', 1)[-1]}:{javasrc.line_of(masked, match.start())}"
        return None

    guarded = []
    for fqn in sorted(parent):
        flags = class_flags(index[fqn], entries, root)
        if not flags:
            continue
        chain = [fqn]
        while chain[-1] in parent and parent[chain[-1]] in index and len(chain) < 6:
            chain.append(parent[chain[-1]])
        why = parent.get(chain[-1], "")
        via = " <- ".join(c.rsplit(".", 1)[-1] for c in chain) + (f" <- {why}" if why not in index else "")
        for flag in flags:
            method = flag.pop("method", None)
            if method is not None:
                reason = method_reached(fqn, method)
                if reason is None:
                    flag["detail"] = (flag.get("detail", "") + f" {method}: only JavaScript-guarded callers").strip()
                    guarded.append(flag)
                    continue
                flag["detail"] = (flag.get("detail", "") + f" {method} ({reason})").strip()
            flag["via"] = via
            findings.append(flag)
    if client_jar:
        findings.extend(client_jar_platform(client_jar))
    return {"profile": profile, "patchers": sorted(names), "reachableClasses": len(parent),
            "indexedClasses": len(index), "skippedPatchIds": sorted(skipped_ids), "findings": findings,
            "guarded": guarded}


def report(results: list[dict], enforce: bool) -> None:
    mode = "enforce" if enforce else "warn"
    annotate = os.environ.get("GITHUB_ACTIONS") == "true"
    for result in results:
        print(f"[lint wasm-reach {mode}] {result['profile']}: {len(result['findings'])} findings; "
              f"{result['reachableClasses']} of {result['indexedClasses']} Gaius classes reachable; "
              f"{len(result['patchers'])} patcher classes"
              + (f"; {len(result['skippedPatchIds'])} patch ids dropped/skipped by the overlay log"
                 if result["skippedPatchIds"] else "; no overlay log (runtime drops not seen)"))
        for flag in result.get("guarded", []):
            print(f"  guarded   {flag['kind']} {flag['where']} {flag.get('detail', '')}")
        for index, finding in enumerate(result["findings"]):
            line = f"  {finding['kind']:9} {finding['where']}"
            if finding.get("detail"):
                line += f" {finding['detail']}"
            if finding.get("via"):
                line += f"  [{finding['via']}]"
            print(line)
            if annotate and index < 10 and ":" in finding["where"] and "!" not in finding["where"]:
                path, lines = finding["where"].rsplit(":", 1)
                print(f"::{'error' if enforce else 'warning'} file={path},line={lines.split('-')[0]}::"
                      f"wasm-reach {result['profile']}: {finding['kind']} reachable in the wasmgc build")


def run(root: Path, profile_ids: list[str], logs: dict[str, Path], jars: dict[str, Path],
        allowlist: Path = ALLOWLIST) -> list[dict]:
    entries = javasrc.read_allowlist(allowlist)
    patchers = load_patchers(root)
    results = []
    for profile in profile_ids:
        skipped = overlay_skips(logs[profile]) if profile in logs else set()
        results.append(analyze(profile, root, patchers, entries, skipped, jars.get(profile)))
    return results


# ---------------------------------------------------------------------------------------

def self_test() -> int:
    failures = []
    with tempfile.TemporaryDirectory(prefix="gaius-reach-") as scratch_name:
        root = Path(scratch_name)

        def write(path: str, text: str) -> None:
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text(text, encoding="utf-8")

        write("port/versions/a.json", json.dumps({"id": "alpha", "patchSet": "old", "extraPatchers": []}))
        write("port/versions/b.json", json.dumps({"id": "beta", "patchSet": "new", "extraPatchers": []}))
        write("port/scripts/build-overlays.sh", 'case "$patch_set" in\n  new)\n    run_client_patcher NewPatcher \\\n'
                                                '    ;;\n  old)\n    run_client_patcher OldPatcher \\\n    ;;\nesac\n')
        write(f"{TOOLS.as_posix()}/SharedPatcher.java", """package dev.gaius.tools;
import dev.gaius.tools.patches.demo.DomainPatches;
public final class SharedPatcher {
    public static final String SHARED_OWNER = "dev/gaius/browser/Shared";
    public static void main(String[] args) throws Exception {
        PatchRegistry.run("SharedPatcher.patchNoise", () -> patchNoise());
        PatchRegistry.run("SharedPatcher.patchFast", "PERF js", () -> patchFast());
        PatchRegistry.run("SharedPatcher.patchDomain", "PERF js wasmgc", () -> DomainPatches.patchDomain());
    }
    static void patchNoise() {
        code.add(new MethodInsnNode(INVOKESTATIC, "dev/gaius/browser/Noise", "noise", "()V", false));
    }
    static void patchFast() {
        code.add(new MethodInsnNode(INVOKESTATIC, "dev/gaius/browser/Fast", "f", "()V", false));
    }
    public static void weaveShared() {
        code.add(new MethodInsnNode(INVOKESTATIC, "dev/gaius/browser/Woven", "f", "()V", false));
    }
}
""")
        # A domain class reaches the shared helpers and constants of a patcher by static imports.
        write(f"{TOOLS.as_posix()}/patches/demo/DomainPatches.java", """package dev.gaius.tools.patches.demo;
import static dev.gaius.tools.SharedPatcher.SHARED_OWNER;
import static dev.gaius.tools.SharedPatcher.weaveShared;
public final class DomainPatches {
    public static void patchDomain() {
        weaveShared();
        code.add(new MethodInsnNode(INVOKESTATIC, SHARED_OWNER, "f", "()V", false));
    }
}
""")
        for owner in ("Shared", "Woven", "OldOnly", "Standalone"):
            write(f"port/src/main/java/dev/gaius/browser/{owner}.java",
                  f"package dev.gaius.browser;\nimport org.teavm.jso.JSByRef;\nfinal class {owner} {{\n"
                  "  static native void f(@JSByRef int[] a);\n}\n")
        write(f"{TOOLS.as_posix()}/OldPatcher.java", """package dev.gaius.tools;
final class OldPatcher {
    public static void main(String[] args) {
        weave();
        StandalonePatches.weaveOld();
    }
    static void weave() {
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/teavm/platform/Platform",
                "startThread",
                "(Lorg/teavm/platform/PlatformRunnable;)V",
                false));
        node.interfaces.add("org/teavm/platform/PlatformRunnable");
    }
}
""")
        # A domain class with a standalone main: its main counts only where a step reaches the
        # class (the alpha chain), so its check code weaves nothing into beta.
        write(f"{TOOLS.as_posix()}/patches/demo/StandalonePatches.java", """package dev.gaius.tools.patches.demo;
public final class StandalonePatches {
    public static void main(String[] args) {
        check();
    }
    public static void weaveOld() {
        code.add(new MethodInsnNode(INVOKESTATIC, "dev/gaius/browser/OldOnly", "f", "()V", false));
    }
    static void check() {
        code.add(new MethodInsnNode(INVOKESTATIC, "dev/gaius/browser/Standalone", "f", "()V", false));
    }
}
""")
        write(f"{TOOLS.as_posix()}/NewPatcher.java", """package dev.gaius.tools;
import java.util.List;
final class NewPatcher {
    static final List<String> OWNERS = List.of("org/teavm/platform/Platform");
    public static void main(String[] args) {
        node.interfaces.add(BuildTarget.wasmGc() ? "java/lang/Runnable" : "org/teavm/platform/PlatformRunnable");
        start();
        check(OWNERS);
    }
    static Object start() {
        if (BuildTarget.wasmGc()) {
            return new MethodInsnNode(INVOKESTATIC, "dev/gaius/browser/HostLoop", "startThread", "()V", false);
        }
        return new MethodInsnNode(INVOKESTATIC, "org/teavm/platform/Platform", "startThread", "()V", false);
    }
}
""")
        write("port/src/main/java/dev/gaius/browser/Noise.java",
              "package dev.gaius.browser;\nfinal class Noise {\n  static double noise() { return Helper.f(null); }\n}\n")
        write("port/src/main/java/dev/gaius/browser/Helper.java",
              "package dev.gaius.browser;\nimport org.teavm.jso.JSBody;\nimport org.teavm.jso.JSByRef;\n"
              "final class Helper {\n  @JSBody(params = \"b\", script = \"return 0;\")\n"
              "  static native double f(@JSByRef byte[] b);\n}\n")
        write("port/src/main/java/dev/gaius/browser/Fast.java",
              "package dev.gaius.browser;\nimport org.teavm.jso.JSByRef;\nfinal class Fast {\n"
              "  static native void f(@JSByRef int[] a);\n}\n")
        write("port/src/main/java/dev/gaius/browser/HostLoop.java",
              "package dev.gaius.browser;\nfinal class HostLoop {\n  static void startThread() {}\n}\n")
        empty = root / "allow.txt"
        empty.write_text("", encoding="utf-8")
        results = {r["profile"]: r for r in run(root, ["alpha", "beta"], {}, {}, empty)}
        alpha = sorted((f["kind"], f["where"]) for f in results["alpha"]["findings"])
        domain_findings = [("JSByRef", "port/src/main/java/dev/gaius/browser/Shared.java:4"),
                           ("JSByRef", "port/src/main/java/dev/gaius/browser/Woven.java:4")]
        standalone_findings = [("JSByRef", "port/src/main/java/dev/gaius/browser/OldOnly.java:4"),
                               ("JSByRef", "port/src/main/java/dev/gaius/browser/Standalone.java:4")]
        want_alpha = sorted([("JSByRef", "port/src/main/java/dev/gaius/browser/Helper.java:6"),
                             ("Platform", f"{TOOLS.as_posix()}/OldPatcher.java:14"),
                             ("Platform", f"{TOOLS.as_posix()}/OldPatcher.java:10-12")]
                            + domain_findings + standalone_findings)
        if alpha != want_alpha:
            failures.append(f"alpha findings {alpha}, expected {want_alpha}")
        beta = sorted((f["kind"], f["where"]) for f in results["beta"]["findings"])
        want_beta = sorted([("JSByRef", "port/src/main/java/dev/gaius/browser/Helper.java:6")] + domain_findings)
        if beta != want_beta:
            failures.append(f"beta findings {beta}, expected {want_beta}")
        log = root / "overlays.log"
        log.write_text("PATCH_DROPPED SharedPatcher.patchNoise\nPATCH_DROPPED SharedPatcher.patchDomain\n",
                       encoding="utf-8")
        dropped = run(root, ["beta"], {"beta": log}, {}, empty)[0]
        if dropped["findings"]:
            failures.append(f"beta with the patch dropped: {dropped['findings']}")
    for failure in failures:
        print(f"self-test FAILED: {failure}", file=sys.stderr)
    if not failures:
        print("lint_wasm_reach self-test passed")
    return 1 if failures else 0


def parse_pairs(values: list[str], option: str) -> dict[str, Path]:
    pairs = {}
    for value in values:
        if "=" not in value:
            raise SystemExit(f"{option} expects PROFILE=PATH, got {value}")
        profile, path = value.split("=", 1)
        pairs[profile] = Path(path)
    return pairs


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--profile", action="append")
    parser.add_argument("--overlay-log", action="append", default=[])
    parser.add_argument("--client-jar", action="append", default=[])
    parser.add_argument("--json")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", default=str(ROOT))
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    root = Path(args.root)
    results = run(root, args.profile or javasrc.profiles(root), parse_pairs(args.overlay_log, "--overlay-log"),
                  parse_pairs(args.client_jar, "--client-jar"))
    report(results, args.enforce)
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(results, indent=1) + "\n", encoding="utf-8")
    return 1 if args.enforce and any(r["findings"] for r in results) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
