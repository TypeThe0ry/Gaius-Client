#!/usr/bin/env python3
"""Check every Java @JSBody literal with TeaVM 0.15's JavaScript parser."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_SOURCE = ROOT / "port" / "tools" / "jsbodycheck" / "JsBodyParse.java"
JSBODY = "@JSBody"


def java_literal(text: str, at: int) -> tuple[str, int]:
    if text.startswith('"""', at):
        end = text.find('"""', at + 3)
        if end < 0:
            raise ValueError("unterminated text block")
        value = text[at + 3:end]
        if value.startswith("\n"):
            value = value[1:]
        lines = value.splitlines(True)
        indent = min((len(x) - len(x.lstrip(" ")) for x in lines if x.strip()), default=0)
        value = "".join(x[indent:] if x.strip() else x for x in lines)
        return value, end + 3
    if at >= len(text) or text[at] != '"':
        raise ValueError("expected Java string literal")
    i, out = at + 1, []
    while i < len(text):
        c = text[i]
        if c == '"':
            return "".join(out), i + 1
        if c != "\\":
            out.append(c); i += 1; continue
        i += 1
        if i >= len(text): raise ValueError("unterminated Java string")
        escapes = {"n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", '"': '"', "'": "'", "\\": "\\"}
        if text[i] in escapes:
            out.append(escapes[text[i]]); i += 1; continue
        if text[i] == "u":
            while i < len(text) and text[i] == "u": i += 1
            out.append(chr(int(text[i:i + 4], 16))); i += 4; continue
        if text[i] in "01234567":
            j = i + 1
            while j < min(i + 3, len(text)) and text[j] in "01234567": j += 1
            out.append(chr(int(text[i:j], 8))); i = j; continue
        out.append(text[i]); i += 1
    raise ValueError("unterminated Java string")


def annotation_end(source: str, start: int) -> int:
    open_at = source.find("(", start)
    if open_at < 0: return start + len(JSBODY)
    depth, i, quote = 1, open_at + 1, None
    while i < len(source) and depth:
        if quote:
            if source.startswith('"""', i) and quote == '"""': i += 3; quote = None; continue
            if source[i] == "\\": i += 2; continue
            if source[i] == quote: quote = None
        elif source.startswith('"""', i): quote = '"""'; i += 3; continue
        elif source[i] == '"': quote = '"'
        elif source[i] == '(' : depth += 1
        elif source[i] == ')' : depth -= 1
        i += 1
    return i


def resolve_script(source: str, expression_at: int, expression_end: int) -> str:
    """Resolve literals and same-file String constants joined by Java ``+``."""
    def name_value(name: str, stack: set[str]) -> str:
        if name in stack: raise ValueError(f"cyclic String constant {name}")
        declaration = re.search(r"\b(?:static\s+final\s+)?String\s+" + re.escape(name) + r"\s*=", source)
        if not declaration: raise ValueError(f"cannot resolve String constant {name}")
        first = declaration.end()
        while first < len(source) and source[first].isspace(): first += 1
        block_start = first if source.startswith('"""', first) else -1
        if block_start >= 0:
            block_end = source.find('"""', block_start + 3)
            end = source.find(";", block_end + 3) if block_end >= 0 else -1
        else:
            end, quote = declaration.end(), None
            while end < len(source):
                if quote:
                    if source[end] == "\\": end += 2; continue
                    if source[end] == quote: quote = None
                elif source[end] == '"': quote = '"'
                elif source[end] == ';': break
                end += 1
        if end < 0: raise ValueError(f"unterminated String constant {name}")
        return eval_terms(source[declaration.end():end], stack | {name})

    def eval_terms(expression: str, stack: set[str]) -> str:
        values, i = [], 0
        while i < len(expression):
            if expression[i] == '"':
                value, i = java_literal(expression, i); values.append(value); continue
            match = re.match(r"[A-Za-z_$][\w$]*", expression[i:])
            if match:
                token = match.group(0)
                # Java comments can sit between concatenated literals. Unknown words
                # in those comments are ignored; a real unresolved-only expression
                # still fails through the empty-values guard below.
                declaration = re.search(r"\b(?:static\s+final\s+)?String\s+" + re.escape(token) + r"\s*=", source)
                if declaration: values.append(name_value(token, stack))
                i += len(token); continue
            i += 1
        if not values: raise ValueError("script expression has no string literals")
        return "".join(values)

    return eval_terms(source[expression_at:expression_end], set())


def extract(root: Path, out: Path) -> list[dict[str, object]]:
    index: list[dict[str, object]] = []
    n = 0
    for path in sorted(root.rglob("*.java")):
        # Check the JavaScript build inputs. WasmGC-only sources intentionally
        # have a separate syntax surface and are not consumed by the frozen JS build.
        if "target-wasmgc" in path.parts or any(part in {"target", "work", "classes"} for part in path.parts): continue
        source = path.read_text(encoding="utf-8", errors="replace")
        cursor = 0
        while True:
            at = source.find(JSBODY, cursor)
            if at < 0: break
            end = annotation_end(source, at)
            annotation = source[at:end]
            match = re.search(r"\bscript\s*=\s*", annotation)
            if not match: cursor = end; continue
            literal_at = at + match.end()
            while literal_at < len(source) and source[literal_at].isspace(): literal_at += 1
            try: script = resolve_script(source, literal_at, end)
            except ValueError as exc: raise ValueError(f"{path}:{source.count(chr(10), 0, at) + 1}: {exc}") from exc
            params_match = re.search(r"\bparams\s*=\s*", annotation)
            names: list[str] = []
            if params_match:
                p = at + params_match.end()
                while p < len(source) and source[p].isspace(): p += 1
                if source[p] == '"': names.append(java_literal(source, p)[0])
                elif source[p] == '{':
                    q = p + 1
                    while q < len(source) and source[q] != '}':
                        if source[q] == '"':
                            value, q = java_literal(source, q); names.append(value)
                        else: q += 1
            tail = source[end:end + 1200]
            method = re.search(r"\bnative\s+[\w.$<>\[\], ?]+\s+(\w+)\s*\(([^)]*)\)", tail)
            # A Java object such as Runnable is passed to @JSBody as an opaque TeaVM
            # reference, not a JS object. Calling methods on it from the script (for
            # example ``task.run()`` from setTimeout) crashes WasmGC/JS. JSFunctor and
            # JSObject parameters are the only callback-like values allowed here.
            signature = re.search(r"\bnative\s+[^;{]+?\(([^)]*)\)", tail, re.S)
            if signature:
                for param in names:
                    if re.search(r"\b" + re.escape(param) + r"\s*\.\s*run\s*\(", script):
                        declaration = re.search(r"([\w.$<>\[\], ?]+?)\s+" + re.escape(param) + r"\b", signature.group(1))
                        ptype = declaration.group(1).strip() if declaration else "?"
                        if "Runnable" in ptype and "JSObject" not in ptype and "Callback" not in ptype and "Functor" not in ptype:
                            raise ValueError(f"@JSBody invokes {param} as a JS object, but its Java parameter type is {ptype}")
            n += 1
            name = f"{n:04d}.js"
            (out / name).write_text("(function(" + ",".join(names) + "){\n" + script + "\n})", encoding="utf-8")
            index.append({"file": name, "source": path.relative_to(root).as_posix(),
                "line": source.count("\n", 0, at) + 1,
                "method": method.group(1) if method else "?",
                "java_params": method.group(2) if method else "",
                "js_params": names})
            cursor = end
    (out / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    return index


def callback_abi_failures(index: list[dict[str, object]], bodies: Path) -> dict[str, str]:
    """Reject Java Runnable objects crossing directly into JS callbacks."""
    failures: dict[str, str] = {}
    for item in index:
        if "Runnable" not in str(item.get("java_params", "")):
            continue
        script = (bodies / str(item["file"])).read_text(encoding="utf-8")
        for name in item.get("js_params", []):
            direct_timer = re.search(
                r"\b(?:setTimeout|queueMicrotask|requestAnimationFrame)\s*\(\s*"
                + re.escape(name) + r"\b", script)
            direct_run = re.search(r"\b" + re.escape(name) + r"\.run\s*\(", script)
            if direct_timer or direct_run:
                failures[str(item["file"])] = (
                    f"Java Runnable parameter {name!r} crosses JSBody callback ABI; "
                    "wrap it in a JSFunctor callback")
                break
    return failures


def statement_iife_failures(index: list[dict[str, object]], bodies: Path) -> dict[str, str]:
    """Reject immediately-invoked function expressions in statement position.

    TeaVM's JSBody minifier drops the parentheses around a statement-level IIFE; the
    resulting anonymous function statement is a SyntaxError when the WasmGC runtime builds
    the import with new Function()."""
    failures: dict[str, str] = {}
    pattern = re.compile(r"(?:^|[;{}])\s*\(\s*function\b")
    for item in index:
        script = (bodies / str(item["file"])).read_text(encoding="utf-8")
        # extract() wraps every body in "(function(params){" ... "})"; inspect the body only.
        body = script.split("\n", 1)[1].rsplit("\n", 1)[0] if "\n" in script else script
        if pattern.search(body):
            failures[str(item["file"])] = (
                "statement-level (function(...){...})(...) is minified into an anonymous "
                "function statement; use a named factory function instead")
    return failures


def jars() -> list[Path]:
    repos = [os.environ.get("M2_REPO"), os.environ.get("MAVEN_REPOSITORY"), os.environ.get("MAVEN_REPO"), str(Path.home() / ".m2" / "repository")]
    names = ["teavm-core", "teavm-relocated-libs-rhino"]
    version = os.environ.get("GAIUS_TEAVM_VERSION", "0.15.0")
    for repo in repos:
        if not repo: continue
        found = [Path(repo) / "org" / "teavm" / name / version / f"{name}-{version}.jar" for name in names]
        if all(x.is_file() for x in found): return found
    return []


def run_check(root: Path) -> int:
    found = jars()
    if not found:
        print("jsbodycheck: TeaVM jars not found (set M2_REPO or populate ~/.m2)", file=sys.stderr); return 2
    with tempfile.TemporaryDirectory(prefix="gaius-jsbody-") as temp:
        temp_path = Path(temp); bodies = temp_path / "bodies"; classes = temp_path / "classes"; bodies.mkdir(); classes.mkdir()
        index = extract(root, bodies)
        javac = shutil.which("javac"); java = shutil.which("java")
        if not javac or not java: print("jsbodycheck: javac and java are required", file=sys.stderr); return 2
        cp = os.pathsep.join(str(x) for x in found)
        subprocess.run([javac, "-cp", cp, "-d", str(classes), str(JAVA_SOURCE)], check=True)
        proc = subprocess.run([java, "-cp", os.pathsep.join([str(classes), cp]), "JsBodyParse", str(bodies)], text=True, capture_output=True)
        failures = {}
        for line in proc.stdout.splitlines():
            if "\t" in line and not line.startswith("SUMMARY"):
                name, message = line.split("\t", 1); failures[name] = message
        failures.update(callback_abi_failures(index, bodies))
        failures.update(statement_iife_failures(index, bodies))
        for item in index:
            if item["file"] in failures:
                print(f"{item['source']}:{item['line']}: {item['method']}: {failures[item['file']]}")
        print(f"jsbodycheck: checked {len(index)} scripts, failed {len(failures)}")
        return 1 if proc.returncode or failures else 0


def self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="gaius-jsbody-fixture-") as temp:
        root = Path(temp); (root / "Fixture.java").write_text('''import org.teavm.jso.JSBody; class Fixture { @JSBody(script = "const x = 1; for (const y of [x]) {}") static native void bad(); @JSBody(params = "task", script = "setTimeout(task, 0);") static native void timer(Runnable task); @JSBody(script = "var x = 1; return x;") static native int good(); }''', encoding="utf-8")
        bodies = root / "bodies"; bodies.mkdir(); items = extract(root, bodies)
        assert len(items) == 3 and items[0]["method"] == "bad"
        abi = callback_abi_failures(items, bodies)
        assert len(abi) == 1 and "Runnable" in next(iter(abi.values()))
        assert run_check(root) == 1, "negative fixture unexpectedly passed"
        bad_root = root / "bad-object"; bad_root.mkdir()
        (bad_root / "Fixture.java").write_text('''import org.teavm.jso.JSBody; class Fixture { @JSBody(params = "task", script = "task.run();") static native void bad(Runnable task); }''', encoding="utf-8")
        bad_bodies = bad_root / "bodies"; bad_bodies.mkdir()
        try:
            extract(bad_root, bad_bodies)
        except ValueError as exc:
            assert "invokes task as a JS object" in str(exc)
        else:
            raise AssertionError("opaque Java callback misuse was not rejected")
        print("jsbodycheck extractor self-test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--self-test", action="store_true"); parser.add_argument("root", nargs="?", type=Path, default=ROOT)
    args = parser.parse_args()
    return self_test() if args.self_test else run_check(args.root.resolve())


if __name__ == "__main__": sys.exit(main())
