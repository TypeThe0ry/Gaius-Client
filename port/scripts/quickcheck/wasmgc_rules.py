#!/usr/bin/env python3
"""CI gates of the WebAssembly GC build (v0.4.0 WP1, DECISIONS 6.3).

  wasmgc_rules.py source   [--json F] [--copy-rule off|report|enforce]     rules c, d, e, h
  wasmgc_rules.py artifact --wasm F [--wasm F...] [--log L...] [--json F]   rules a, b
  wasmgc_rules.py patch-summary --profile P --log OVERLAYS_LOG [--write-baseline]   rule f
  wasmgc_rules.py pom --profile P [--overlay-dir D] [--write-baseline]      rule g
  wasmgc_rules.py self-test                                                every rule red/green

Rules (each one fails the command with exit status 1):

  a  every wasm artifact compiles in V8 with zero invalid functions
     (port/tools/wasm/wasm-check.mjs scans past the first failure).
  b  the TeaVM log of a JSPI build reports "jspi: async analysis skipped, 0 coroutine
     methods" (GaiusTeaVMOptions): no method was compiled as a coroutine.
  c  the wasmgc source roots (<x>/src/target-wasmgc/java under port/src and
     port/overrides) use neither @JSByRef nor org.teavm.platform.
  d  every @JSBody that can compile into a wasm build fits TeaVM's WebAssembly GC JS
     function tables: imports + receiver + parameters <= 31.  WasmGCJSFunctions (teavm-jso-impl
     0.15) keeps 32 constructor/caller slots indexed by the argument count, so 31 is the
     largest count that compiles (reportMinecraftState failed with 33 parameters in M0).
     Checked on the sources, before overlays and TeaVM run.
  e  lwjgl override methods do not copy Java arrays into new typed arrays
     (copyFromJavaArray, or the JsArrays int8/int16/int32/float32 views, which copy on
     wasm): per-frame paths pass linear-memory ranges (design D7, WP4).  Code that only the
     JavaScript build compiles does not count: the else branch of an
     `if (JsArrays.LINEAR_MEMORY) {` guard, the rest of the enclosing block after such a
     guard that ends in return or throw, and private helpers called only from there.
     Methods that are not per frame are listed in the allow-list.  Enforced by default;
     GAIUS_WASMGC_COPY_RULE=report or --copy-rule report only lists the call sites.
  f  the PATCH_SUMMARY lines (and the dropped/target-skipped/bring-up ids) of a js-target
     overlay build equal baselines/js-patch-summary.json: the JavaScript build is the frozen
     fallback, its patch set changes only on purpose.
  g  the js POMs that generate-pom.sh writes for the client and Worker roles (development
     and release option sets) equal baselines/js-pom/<profile>/<config>.xml after the
     checkout, overlay, build and dist paths are replaced by placeholders.
  h  no Java array of a JS object type (org.teavm.jso types such as JSString or Int8Array,
     and the JSObject interfaces of the sources) in a file that can compile into a wasm
     build: TeaVM 0.15 compiles a store of null into such an array as ref.null extern into an
     array of the wrapper type, which V8 rejects (BrowserOpenGL's state report strings, M1).
     Keep JS values on the JS side or in a JSArray.  Checked on the sources.

Exceptions to c, d, e and h live in wasmgc-allowlist.txt next to this script
("<rule> | <path> | <symbol> | <reason>", "#" starts a comment line); an entry that matches
nothing is reported as stale but does not fail the gate.
"""

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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javasrc  # noqa: E402

HERE = Path(__file__).resolve().parent
ROOT = javasrc.ROOT
ALLOWLIST = HERE / "wasmgc-allowlist.txt"
BASELINES = HERE / "baselines"
PATCH_BASELINE = BASELINES / "js-patch-summary.json"
POM_BASELINES = BASELINES / "js-pom"
WASM_CHECK = ROOT / "port" / "tools" / "wasm" / "wasm-check.mjs"

JSPI_LOG_LINE = "jspi: async analysis skipped, 0 coroutine methods"
# GaiusTeaVMOptions.wasmClinitGuard prints this once it guards the first dynamic class
# initializer (JVM class initialization semantics, WP2).
CLINIT_LOG_LINE = "jspi: class initializers wait for their initializing thread (JVM semantics)"
JSPI_LOG_LINES = (JSPI_LOG_LINE, CLINIT_LOG_LINE)
JSBODY_MAX_ARGUMENTS = 31
# Calls that copy a Java array into a new typed array in the wasm build.  JsArrays.float64 is
# not one: on WebAssembly GC it stages the doubles in a cached view of linear memory (WP3).
COPY_CALL = re.compile(r"\b(?:copyFromJavaArray|JsArrays\s*\.\s*(?:int8|int16|int32|float32))\s*\(")
# JsArrays.LINEAR_MEMORY is a compile-time constant, true in the wasmgc build (WP4): javac drops
# the else branch of this guard, and the rest of the enclosing block when the guarded block
# ends in return or throw, so that code only exists in the JavaScript build.
LINEAR_GUARD = re.compile(r"\bif\s*\(\s*JsArrays\s*\.\s*LINEAR_MEMORY\s*\)\s*\{")
ABRUPT_STATEMENT = re.compile(r"(?:return|throw)\b")
JSBYREF = re.compile(r"@(?:org\.teavm\.jso\.)?JSByRef\b|\bimport\s+org\.teavm\.jso\.JSByRef\b")
# A class name, also inside a descriptor ("(Lorg/teavm/platform/PlatformRunnable;)V").
PLATFORM = re.compile(r"(?:(?<![\w$])|(?<=\bL))org[./]teavm[./]platform(?:[./][\w$]+)*")
# h: the JS object types of a file (its org.teavm.jso imports), JSObject subtypes declared in
# the sources, and array types of them ("JSString[]", "new Int8Array[", "org.teavm.jso.X[]").
JSO_IMPORT = re.compile(r"\bimport\s+org\.teavm\.jso(?:\.[a-z][\w$]*)*\.([A-Z][\w$]*)\s*;")
JSO_TYPE_DECLARATION = re.compile(
    r"\binterface\s+([A-Z][\w$]*)\s*(?:<[^{]*?>)?\s+extends\s+([^{]+)\{")
JSO_QUALIFIED_ARRAY = re.compile(r"\borg\.teavm\.jso(?:\.[a-z][\w$]*)*\.([A-Z][\w$]*)\s*(?:<[^>]*>)?\s*\[")


class Report:
    def __init__(self, rule: str, title: str):
        self.rule = rule
        self.title = title
        self.violations: list[dict] = []
        self.notes: list[str] = []
        self.enforced = True

    @property
    def ok(self) -> bool:
        return not (self.enforced and self.violations)

    def to_json(self) -> dict:
        return {"rule": self.rule, "title": self.title, "ok": self.ok, "enforced": self.enforced,
                "violations": self.violations, "notes": self.notes}

    def print(self) -> None:
        state = "ok" if not self.violations else ("FAIL" if self.enforced else "report")
        print(f"[wasmgc rule {self.rule}] {self.title}: {state}"
              + (f" ({len(self.violations)})" if self.violations else ""))
        for violation in self.violations:
            print(f"  {violation['where']}: {violation['message']}")
        for note in self.notes:
            print(f"  note: {note}")


def write_json(path: str | None, reports: list[Report]) -> None:
    if path:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(json.dumps({"ok": all(r.ok for r in reports),
                                          "rules": [r.to_json() for r in reports]}, indent=1) + "\n",
                              encoding="utf-8")


def stale_notes(entries: list[javasrc.AllowEntry], rules: set[str]) -> list[str]:
    return [f"stale allow-list entry {ALLOWLIST.name}:{e.line} ({e.rule} | {e.path} | {e.symbol}): matches nothing"
            for e in entries if e.rule in rules and not e.used]


# ---------------------------------------------------------------------------------------
# c, d, e: sources

def rule_c(root: Path, entries: list[javasrc.AllowEntry]) -> Report:
    report = Report("c", "wasmgc source roots use no @JSByRef and no org.teavm.platform")
    roots = javasrc.target_roots("wasmgc", root)
    files = 0
    for source_root in roots:
        for path in sorted(source_root.rglob("*.java")):
            files += 1
            source = javasrc.read_text(path)
            text = javasrc.strip_comments(source)
            relative = javasrc.rel(path, root)
            # @JSByRef counts in code only; a platform name also counts in a string (reflection).
            for match in JSBYREF.finditer(javasrc.mask_literals(source)):
                if not javasrc.allowed(entries, "c", relative, "org.teavm.jso.JSByRef"):
                    report.violations.append({"where": f"{relative}:{javasrc.line_of(text, match.start())}",
                                              "message": "@JSByRef (not supported by WebAssembly GC)"})
            for match in PLATFORM.finditer(text):
                symbol = match.group(0).replace("/", ".")
                if not javasrc.allowed(entries, "c", relative, symbol):
                    report.violations.append({"where": f"{relative}:{javasrc.line_of(text, match.start())}",
                                              "message": f"{symbol} (TeaVM JavaScript platform API)"})
    report.notes.append(f"{files} files in {len(roots)} roots: "
                        + ", ".join(javasrc.rel(r, root) for r in roots))
    return report


def rule_d(root: Path, entries: list[javasrc.AllowEntry]) -> Report:
    report = Report("d", f"@JSBody imports + receiver + parameters <= {JSBODY_MAX_ARGUMENTS}")
    files = javasrc.wasm_source_files(None, root)
    count = 0
    largest = (0, "")
    for path in sorted(files):
        text = javasrc.read_text(path)
        if "JSBody" not in text:
            continue
        relative = javasrc.rel(path, root)
        for body in javasrc.jsbody_declarations(path, text):
            count += 1
            where = f"{relative}:{body.line}"
            if body.arguments > largest[0] and not javasrc.allowed(entries, "d", relative, body.method):
                largest = (body.arguments, f"{where} {body.method}")
            if body.arguments <= JSBODY_MAX_ARGUMENTS:
                continue
            if javasrc.allowed(entries, "d", relative, body.method):
                report.notes.append(f"allow-listed: {where} {body.method} ({body.arguments} arguments)")
                continue
            report.violations.append({
                "where": where,
                "message": f"{body.method}: {body.arguments} arguments ({body.params} params, "
                           f"{body.imports} imports{'' if body.static else ', receiver'}); WebAssembly GC "
                           f"allows {JSBODY_MAX_ARGUMENTS} (pack them into a typed array)"})
    report.notes.append(f"{count} @JSBody methods in {len(files)} files; largest outside the allow-list: "
                        f"{largest[0]} ({largest[1]})")
    return report


def jso_types(files: dict[Path, str] | list[Path]) -> set[str]:
    """Simple names of the JSObject subtypes the sources declare (transitively)."""
    declared: dict[str, set[str]] = {}
    for path in files:
        text = javasrc.mask_literals(javasrc.read_text(path))
        for match in JSO_TYPE_DECLARATION.finditer(text):
            supertypes = {name.split(".")[-1] for name in re.findall(r"[A-Za-z_$][\w$.]*", match.group(2))}
            declared.setdefault(match.group(1), set()).update(supertypes)
    known = {"JSObject"}
    changed = True
    while changed:
        changed = False
        for name, supertypes in declared.items():
            if name not in known and supertypes & known:
                known.add(name)
                changed = True
    return known - {"JSObject"}


def rule_h(root: Path, entries: list[javasrc.AllowEntry]) -> Report:
    report = Report("h", "no Java arrays of JS object types on the wasm path")
    files = javasrc.wasm_source_files(None, root)
    declared = jso_types(files)
    count = 0
    for path in sorted(files):
        source = javasrc.read_text(path)
        if "org.teavm.jso" not in source and not any(name in source for name in declared):
            continue
        masked = javasrc.mask_literals(source)
        relative = javasrc.rel(path, root)
        names = set(JSO_IMPORT.findall(masked)) | declared
        names.discard("JSBody")
        hits = [(m.start(), m.group(1)) for m in JSO_QUALIFIED_ARRAY.finditer(masked)]
        if names:
            pattern = re.compile(r"(?<![\w$.])(" + "|".join(sorted(map(re.escape, names))) + r")\s*(?:<[^>]*>)?\s*\[")
            hits += [(m.start(), m.group(1)) for m in pattern.finditer(masked)]
        for offset, name in sorted(set(hits)):
            count += 1
            if javasrc.allowed(entries, "h", relative, name):
                report.notes.append(f"allow-listed: {relative}:{javasrc.line_of(masked, offset)} {name}[]")
                continue
            report.violations.append({
                "where": f"{relative}:{javasrc.line_of(masked, offset)}",
                "message": f"array of the JS object type {name} (TeaVM 0.15 stores null into it as "
                           "ref.null extern: invalid wasm); keep the values on the JS side"})
    report.notes.append(f"{len(files)} files; {len(declared)} JSObject types declared in the sources; "
                        f"{count} arrays of JS object types")
    return report


def copy_mode(argument: str | None) -> str:
    mode = argument or os.environ.get("GAIUS_WASMGC_COPY_RULE", "enforce")
    if mode not in ("off", "report", "enforce"):
        raise SystemExit(f"GAIUS_WASMGC_COPY_RULE/--copy-rule: expected off, report or enforce, not {mode}")
    return mode


def _last_statement(masked: str, start: int, end: int) -> str:
    """The last top-level statement of the block body masked[start:end]."""
    depth = 0
    begin = start
    last = ""
    for k in range(start, end):
        ch = masked[k]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
            if depth == 0 and ch == "}":
                last = masked[begin:k + 1].strip() or last
                begin = k + 1
        elif ch == ";" and depth == 0:
            last = masked[begin:k + 1].strip() or last
            begin = k + 1
    return masked[begin:end].strip() or last


def _enclosing_block_end(masked: str, start: int) -> int:
    """Offset of the "}" that closes the block containing offset start."""
    depth = 0
    for k in range(start, len(masked)):
        if masked[k] == "{":
            depth += 1
        elif masked[k] == "}":
            if depth == 0:
                return k
            depth -= 1
    return len(masked)


def js_only_regions(masked: str) -> list[tuple[int, int]]:
    """Spans of a masked file that only the JavaScript build compiles (LINEAR_GUARD)."""
    regions = []
    for match in LINEAR_GUARD.finditer(masked):
        open_index = match.end() - 1
        close = javasrc.matching(masked, open_index)
        if close < 0:
            continue
        following = re.compile(r"\s*else\s*\{").match(masked, close + 1)
        if following:
            else_open = following.end() - 1
            regions.append((else_open, javasrc.matching(masked, else_open)))
        elif ABRUPT_STATEMENT.match(_last_statement(masked, open_index + 1, close)):
            regions.append((close + 1, _enclosing_block_end(masked, close + 1)))
    return regions


def js_only_helpers(masked: str, spans: list[javasrc.MethodSpan], regions: list[tuple[int, int]]) -> set[str]:
    """Private methods every call of which is JavaScript-only: in a JS-only region or in another
    such helper (javac keeps them, TeaVM drops them from the wasm build as unreachable)."""
    def modifiers(span: javasrc.MethodSpan) -> str:
        start = max(masked.rfind(";", 0, span.start), masked.rfind("{", 0, span.start),
                    masked.rfind("}", 0, span.start)) + 1
        return masked[start:span.start]

    by_name: dict[str, list[javasrc.MethodSpan]] = {}
    for span in spans:
        by_name.setdefault(span.name, []).append(span)
    candidates = {name for name, group in by_name.items()
                  if all(re.search(r"\bprivate\b", modifiers(span)) for span in group)}
    calls = {name: [m.start() for m in re.finditer(r"(?<![\w$.])" + re.escape(name) + r"\s*\(", masked)
                    if not any(m.start() == span.start for span in by_name[name])]
             for name in candidates}
    helpers: set[str] = set()
    changed = True
    while changed:
        changed = False
        for name in sorted(candidates - helpers):
            def js_only(offset: int) -> bool:
                if any(start < offset < end for start, end in regions):
                    return True
                span = javasrc.enclosing_method(spans, offset)
                return span is not None and span.name in helpers
            if all(js_only(offset) for offset in calls[name]):
                helpers.add(name)
                changed = True
    return helpers


def rule_e(root: Path, entries: list[javasrc.AllowEntry], mode: str) -> Report:
    report = Report("e", "lwjgl override methods do not copy Java arrays into typed arrays")
    report.enforced = mode == "enforce"
    if mode == "off":
        report.notes.append("rule e is off")
        return report
    files = javasrc.wasm_source_files(None, root)
    scanned = 0
    js_only_calls = 0
    for path in sorted(files):
        group = files[path]
        if not group.startswith("port/overrides/libraries/lwjgl"):
            continue
        scanned += 1
        text = javasrc.read_text(path)
        masked = javasrc.mask_literals(text)
        spans = javasrc.method_spans(masked)
        regions = js_only_regions(masked)
        helpers = js_only_helpers(masked, spans, regions)
        relative = javasrc.rel(path, root)
        for match in COPY_CALL.finditer(masked):
            span = javasrc.enclosing_method(spans, match.start())
            if any(start < match.start() < end for start, end in regions) or (span and span.name in helpers):
                js_only_calls += 1
                continue
            method = f"{javasrc.outer_class_name(path)}#{span.name if span else '<init>'}"
            if javasrc.allowed(entries, "e", relative, method):
                continue
            report.violations.append({"where": f"{relative}:{javasrc.line_of(masked, match.start())}",
                                      "message": f"{method}: {' '.join(match.group(0).split())[:-1]}"})
    if not report.enforced:
        report.notes.append("report mode (GAIUS_WASMGC_COPY_RULE=enforce or --copy-rule enforce makes it a gate)")
    report.notes.append(f"{scanned} lwjgl override files; {js_only_calls} copies only in the JavaScript build "
                        "(behind JsArrays.LINEAR_MEMORY)")
    return report


def run_source(root: Path, json_path: str | None, copy: str | None, allowlist: Path = ALLOWLIST) -> int:
    entries = javasrc.read_allowlist(allowlist)
    reports = [rule_c(root, entries), rule_d(root, entries), rule_e(root, entries, copy_mode(copy)),
               rule_h(root, entries)]
    for note in stale_notes(entries, {"c", "d", "e", "h"}):
        reports[0].notes.append(note)
    for report in reports:
        report.print()
    write_json(json_path, reports)
    return 0 if all(r.ok for r in reports) else 1


# ---------------------------------------------------------------------------------------
# a, b: artifacts

def node() -> str:
    return os.environ.get("NODE", "node")


def rule_a(wasm_files: list[str], json_path: str | None) -> Report:
    report = Report("a", "wasm artifacts compile in V8 with 0 invalid functions")
    with tempfile.TemporaryDirectory() as scratch:
        out = Path(scratch) / "wasm-check.json"
        result = subprocess.run([node(), str(WASM_CHECK), "--json", str(out), *wasm_files],
                                capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if not out.is_file():
            report.violations.append({"where": "wasm-check.mjs", "message": f"exit {result.returncode}, no report"})
            return report
        for item in json.loads(out.read_text(encoding="utf-8")):
            if item.get("valid"):
                report.notes.append(f"{item['file']}: {item['bytes']} bytes, name section "
                                    f"{item.get('nameSectionBytes', 0)} bytes, compiled in "
                                    f"{item.get('compileSeconds', 0)} s")
                continue
            functions = item.get("invalidFunctions", [])
            message = f"{len(functions)} invalid functions" + (
                f" ({item['moduleError']})" if item.get("moduleError") else "")
            if functions:
                first = functions[0]
                message += f"; first #{first['index']} {first.get('name') or ''}: {first['error']}"
            report.violations.append({"where": item["file"], "message": message})
    return report


def rule_b(logs: list[str]) -> Report:
    report = Report("b", "TeaVM log reports " + " and ".join(f"'{line}'" for line in JSPI_LOG_LINES))
    for log in logs:
        try:
            text = Path(log).read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            report.violations.append({"where": log, "message": f"unreadable: {error}"})
            continue
        found = sorted(set(re.findall(r"jspi: [^\r\n]*", text)))
        for line in JSPI_LOG_LINES:
            if line not in text:
                report.violations.append({"where": log, "message": f"no '{line}' line" + (
                    f" (found: {'; '.join(found)})" if found else "; is it the TeaVM log of a wasmgc build?")})
    return report


def run_artifact(wasm: list[str], logs: list[str], json_path: str | None) -> int:
    reports = []
    if wasm:
        reports.append(rule_a(wasm, json_path))
    if logs:
        reports.append(rule_b(logs))
    if not reports:
        raise SystemExit("artifact: give --wasm and/or --log")
    for report in reports:
        report.print()
    write_json(json_path, reports)
    return 0 if all(r.ok for r in reports) else 1


# ---------------------------------------------------------------------------------------
# f: js PATCH_SUMMARY

PATCH_LINE = re.compile(r"^(PATCH_SUMMARY .*?|PATCH_DROPPED \S+|PATCH_TARGET_SKIPPED \S+.*?|BRINGUP_SKIP \S+)\s*$")
# Patches whose spec names only the wasmgc target (PatchRegistry.run("<id>", "<KIND> wasmgc",
# ...)) are target skips of the js build ("target=js"); a skip for any other target means the
# log is not a js overlay build.
TARGET_SKIP_TARGET = re.compile(r"^PATCH_TARGET_SKIPPED \S+ target=(\S+)", re.MULTILINE)


def patch_record(log_text: str) -> dict:
    record = {"summary": [], "dropped": [], "targetSkipped": [], "bringupSkipped": []}
    for line in log_text.splitlines():
        match = PATCH_LINE.match(line.rstrip("\r"))
        if not match:
            continue
        text = match.group(1)
        kind, _, rest = text.partition(" ")
        if kind == "PATCH_SUMMARY":
            record["summary"].append(text)
        elif kind == "PATCH_DROPPED":
            record["dropped"].append(rest)
        elif kind == "PATCH_TARGET_SKIPPED":
            record["targetSkipped"].append(rest.split()[0])
        elif kind == "BRINGUP_SKIP":
            record["bringupSkipped"].append(rest)
    for key in ("dropped", "targetSkipped", "bringupSkipped"):
        record[key] = sorted(record[key])
    return record


def load_patch_baseline(path: Path) -> dict:
    if not path.is_file():
        return {"comment": "", "profiles": {}}
    return json.loads(path.read_text(encoding="utf-8"))


def compare_patch_record(profile: str, actual: dict, expected: dict | None) -> Report:
    report = Report("f", f"{profile} js PATCH_SUMMARY equals the baseline")
    if expected is None:
        report.violations.append({"where": "baseline", "message": f"no {profile} entry in {PATCH_BASELINE.name}"})
        return report
    if not actual["summary"]:
        report.violations.append({"where": "log", "message": "no PATCH_SUMMARY line (not an overlay build log?)"})
    if actual["summary"] != expected["summary"]:
        report.violations.append({"where": "PATCH_SUMMARY", "message":
                                  f"expected {expected['summary']}, got {actual['summary']}"})
    for key in ("dropped", "targetSkipped", "bringupSkipped"):
        added = sorted(set(actual[key]) - set(expected.get(key, [])))
        removed = sorted(set(expected.get(key, [])) - set(actual[key]))
        if added or removed:
            report.violations.append({"where": key, "message": f"added {added}, removed {removed}"})
    return report


def run_patch_summary(profile: str, log: str, write: bool, baseline: Path = PATCH_BASELINE) -> int:
    text = Path(log).read_text(encoding="utf-8", errors="replace").replace("\r", "")
    actual = patch_record(text)
    data = load_patch_baseline(baseline)
    if write:
        if not actual["summary"]:
            raise SystemExit(f"{log} has no PATCH_SUMMARY line")
        targets = TARGET_SKIP_TARGET.findall(text)
        if len(targets) != len(actual["targetSkipped"]) or set(targets) - {"js"}:
            raise SystemExit(f"{log} skips patches for a target other than js: not a js overlay build")
        if not data.get("comment"):
            data["comment"] = ("PATCH_* lines of a GAIUS_TEAVM_TARGET=js build-overlays.sh run (wasmgc_rules.py "
                               "rule f); rewrite with --write-baseline only for a reviewed change of the "
                               "JavaScript patch set")
        data.setdefault("profiles", {})[profile] = actual
        data["profiles"] = dict(sorted(data["profiles"].items()))
        baseline.parent.mkdir(parents=True, exist_ok=True)
        baseline.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {javasrc.rel(baseline)} [{profile}]")
        return 0
    report = compare_patch_record(profile, actual, data.get("profiles", {}).get(profile))
    report.print()
    return 0 if report.ok else 1


# ---------------------------------------------------------------------------------------
# g: js POMs

RELEASE_OPTIONS = {
    "GAIUS_TEA_OPTIMIZATION_LEVEL": "ADVANCED", "GAIUS_SOURCE_MAPS": "false", "GAIUS_DEBUG_INFO": "false",
    "GAIUS_MINIFYING": "true", "GAIUS_SHORT_FILE_NAMES": "true", "GAIUS_ASSERTIONS_REMOVED": "true",
}
CLIENT_EXCLUDED = "org/apache/logging/log4j/log4j-core/,org/apache/logging/log4j/log4j-slf4j2-impl/"
WORKER_EXCLUDED = "com/microsoft/azure/msal4j/,com/azure/azure-json/"
POM_CONFIGS = ("client-dev", "client-release", "worker-dev", "worker-release")
CLEARED_ENV = (
    "GAIUS_TEAVM_TARGET", "GAIUS_TEAVM_ASYNC_DUMP", "GAIUS_TEAVM_ASYNC_DUMP_STOP", "GAIUS_TEAVM_JSPI",
    "GAIUS_POM", "GAIUS_TARGET_FILE", "GAIUS_TARGET_DIRECTORY", "GAIUS_MAIN_CLASS", "GAIUS_TEAVM_ROLE",
    "GAIUS_EXCLUDED_LIBRARY_PREFIXES", "GAIUS_MAVEN_DIRECTORY", "GAIUS_RESOURCE_DIRECTORY",
    "GAIUS_WASM_DEBUG_INFO_LOCATION", "GAIUS_WASM_ALLOW_NO_SYMBOLS", "GAIUS_BRINGUP", *RELEASE_OPTIONS,
    "GAIUS_TEAVM_STRIP_TELEMETRY",
)


def pom_environment(config: str, build: Path, dist: Path) -> dict[str, str]:
    """The generate-pom.sh environment of one option set (the T0 POM invariance sets:
    build-teavm.sh and build-teavm-server-worker.sh, development and release)."""
    env: dict[str, str] = {"GAIUS_TARGET_DIRECTORY": str(dist / "staged")}
    if config.startswith("client-"):
        env.update(GAIUS_TEAVM_ROLE="client", GAIUS_EXCLUDED_LIBRARY_PREFIXES=CLIENT_EXCLUDED,
                   GAIUS_RESOURCE_DIRECTORY=str(build / "generated-resources"))
    else:
        env.update(GAIUS_TEAVM_ROLE="singleplayer-worker",
                   GAIUS_MAIN_CLASS="dev.gaius.browser.BrowserIntegratedServerMain",
                   GAIUS_POM=str(build / "server-worker" / "generated-pom.xml"),
                   GAIUS_TARGET_FILE="singleplayer-server.js",
                   GAIUS_MAVEN_DIRECTORY=str(build / "server-worker" / "maven"),
                   GAIUS_RESOURCE_DIRECTORY=str(build / "server-worker" / "generated-resources"),
                   GAIUS_EXCLUDED_LIBRARY_PREFIXES=WORKER_EXCLUDED, **RELEASE_OPTIONS)
    if config.endswith("-release"):
        env.update(RELEASE_OPTIONS)
        env["GAIUS_TEAVM_STRIP_TELEMETRY"] = "true"
    return env


def find_bash() -> str:
    if os.name != "nt":
        return shutil.which("bash") or "bash"
    for candidate in (os.environ.get("GAIUS_BASH"), r"C:\Program Files\Git\bin\bash.exe",
                      r"C:\Program Files\Git\usr\bin\bash.exe", shutil.which("bash")):
        if candidate and Path(candidate).is_file() and "System32" not in candidate:
            return candidate
    return "bash"


def path_forms(path: Path) -> list[str]:
    """Spellings of a path that can appear in a POM: D:/x (cygpath -m), /d/x and the native one."""
    resolved = Path(os.path.abspath(path))
    forms = {resolved.as_posix(), str(resolved)}
    posix = resolved.as_posix()
    if re.match(r"^[A-Za-z]:/", posix):
        forms.add(f"/{posix[0].lower()}{posix[2:]}")
        forms.add(posix[0].upper() + posix[1:])
        forms.add(posix[0].lower() + posix[1:])
    return sorted(forms, key=len, reverse=True)


def normalize_pom(text: str, replacements: list[tuple[Path, str]]) -> str:
    pairs = []
    for path, token in replacements:
        pairs.extend((form, token) for form in path_forms(path))
    pairs.sort(key=lambda pair: len(pair[0]), reverse=True)
    for form, token in pairs:
        text = text.replace(form, token)
    return text.replace("\r\n", "\n")


def generate_pom(profile: str, config: str, overlay_dir: Path, scratch: Path, target_env: str | None,
                 root: Path) -> str:
    build = scratch / f"{config}-{target_env or 'unset'}" / "build"
    dist = scratch / f"{config}-{target_env or 'unset'}" / "dist"
    # Only the option set decides the POM: no role or compiler option leaks in from the caller.
    env = {k: v for k, v in os.environ.items()
           if k not in CLEARED_ENV and not k.startswith(("GAIUS_TEAVM_", "GAIUS_TEA_"))}
    env.update(pom_environment(config, build, dist))
    env.update(GAIUS_VERSION_PROFILE_PATH=f"versions/{profile}.json", GAIUS_OVERLAY_DIRECTORY=str(overlay_dir),
               GAIUS_BUILD_ROOT=str(build), GAIUS_DIST_DIRECTORY=str(dist))
    if target_env:
        env["GAIUS_TEAVM_TARGET"] = target_env
    result = subprocess.run([find_bash(), str(root / "port" / "scripts" / "generate-pom.sh")], cwd=root, env=env,
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"generate-pom.sh {profile} {config} exited {result.returncode}:\n{result.stderr}")
    pom_path = Path(result.stdout.strip().splitlines()[-1])
    if os.name == "nt" and re.match(r"^/[a-zA-Z]/", str(pom_path).replace("\\", "/")):
        posix = str(pom_path).replace("\\", "/")
        pom_path = Path(f"{posix[1].upper()}:{posix[2:]}")
    text = pom_path.read_text(encoding="utf-8")
    return normalize_pom(text, [(overlay_dir, "@OVERLAYS@"), (build, "@BUILD@"), (dist, "@DIST@"),
                                (root, "@ROOT@")])


def default_overlay_dir(profile: str, root: Path) -> Path:
    if os.environ.get("GAIUS_OVERLAY_DIRECTORY"):
        return Path(os.environ["GAIUS_OVERLAY_DIRECTORY"])
    return root / "port" / "work" / "overlays" / profile


def run_pom(profile: str, overlay_dir: str | None, write: bool, root: Path = ROOT,
            baselines: Path = POM_BASELINES) -> int:
    overlays = Path(overlay_dir) if overlay_dir else default_overlay_dir(profile, root)
    report = Report("g", f"{profile} generated js POMs equal the baselines")
    with tempfile.TemporaryDirectory(prefix="gaius-pom-") as scratch_name:
        scratch = Path(scratch_name)
        for config in POM_CONFIGS:
            baseline = baselines / profile / f"{config}.xml"
            texts = {}
            for target_env in (None, "js"):
                texts[target_env or "unset"] = generate_pom(profile, config, overlays, scratch, target_env, root)
            if texts["unset"] != texts["js"]:
                report.violations.append({"where": config, "message": "GAIUS_TEAVM_TARGET=js and unset differ"})
            if write:
                baseline.parent.mkdir(parents=True, exist_ok=True)
                baseline.write_text(texts["unset"], encoding="utf-8", newline="\n")
                print(f"wrote {javasrc.rel(baseline, root)}")
                continue
            if not baseline.is_file():
                report.violations.append({"where": config, "message": f"no baseline {javasrc.rel(baseline, root)}"})
                continue
            expected = baseline.read_text(encoding="utf-8")
            if texts["unset"] != expected:
                import difflib
                diff = list(difflib.unified_diff(expected.splitlines(), texts["unset"].splitlines(),
                                                 "baseline", "generated", lineterm="", n=1))
                report.violations.append({"where": config, "message": "differs from the baseline:\n    "
                                          + "\n    ".join(diff[:40])})
            else:
                report.notes.append(f"{config}: {len(expected.splitlines())} lines equal")
    if write:
        return 0 if report.ok else 1
    report.print()
    return 0 if report.ok else 1


# ---------------------------------------------------------------------------------------
# self-test: every rule red on an injected violation, green without it

def _write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    return path


def _jsbody(name: str, count: int, static: bool = True, imports: int = 0) -> str:
    params = ", ".join(f'"p{i}"' for i in range(count))
    java = ", ".join(f"int p{i}" for i in range(count))
    import_attr = ""
    if imports:
        import_attr = "imports = {" + ", ".join(
            f'@JSBodyImport(alias = "m{i}", fromModule = "m{i}.js")' for i in range(imports)) + "}, "
    return (f"    @JSBody({import_attr}params = {{{params}}}, script = \"return 0;\")\n"
            f"    {'static ' if static else ''}native int {name}({java});\n")


def self_test() -> int:
    failures: list[str] = []

    def expect(label: str, status: int, want: int) -> None:
        print(f"self-test {label}: exit {status} (want {want})")
        if status != want:
            failures.append(f"{label}: exit {status}, expected {want}")

    with tempfile.TemporaryDirectory(prefix="gaius-wasmgc-rules-") as scratch_name:
        scratch = Path(scratch_name)
        tree = scratch / "repo"
        clean_target = tree / "port/overrides/classlib/src/target-wasmgc/java/dev/gaius/browser/Clean.java"
        _write(clean_target, "package dev.gaius.browser;\n// org.teavm.platform in a comment is fine\n"
                             "public final class Clean {\n  static String s = \"@JSByRef in a string\";\n}\n")
        _write(tree / "port/src/main/java/dev/gaius/browser/Bodies.java",
               "package dev.gaius.browser;\nimport org.teavm.jso.JSBody;\npublic final class Bodies {\n"
               + _jsbody("staticMax", JSBODY_MAX_ARGUMENTS)
               + _jsbody("instanceMax", JSBODY_MAX_ARGUMENTS - 1, static=False)
               + _jsbody("importsMax", JSBODY_MAX_ARGUMENTS - 2, imports=2) + "}\n")
        gl = tree / "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserGL.java"
        _write(gl, "package org.lwjgl.opengl;\nfinal class BrowserGL {\n"
                   "  static void uniform4fv(int location, float[] value) {\n    send(location, value.length);\n  }\n"
                   "  static void send(int a, int b) {}\n}\n")
        _write(tree / "port/mc/shared/sourcesets.json",
               json.dumps({"canonical": "selftest", "canonicalRenderSeam": "none",
                           "sets": [{"name": "selftest", "selects": "*"}]}))
        empty = _write(scratch / "allow-empty.txt", "# empty\n")

        def source(allowlist: Path, copy: str = "report") -> int:
            return run_source(tree, None, copy, allowlist)

        expect("source clean", source(empty, "enforce"), 0)

        # c: @JSByRef and org.teavm.platform in a target-wasmgc root.
        bad_c = _write(tree / "port/src/target-wasmgc/java/dev/gaius/browser/BadRef.java",
                       "package dev.gaius.browser;\nimport org.teavm.jso.JSByRef;\nfinal class BadRef {\n"
                       "  static native void f(@JSByRef byte[] b);\n}\n")
        expect("c @JSByRef", source(empty), 1)
        bad_c.unlink()
        bad_p = _write(tree / "port/overrides/libraries/lwjgl/src/target-wasmgc/java/org/lwjgl/system/P.java",
                       "package org.lwjgl.system;\nfinal class P {\n  void f(Runnable r) {\n"
                       "    org.teavm.platform.Platform.startThread(r::run);\n  }\n}\n")
        expect("c org.teavm.platform", source(empty), 1)
        allow_p = _write(scratch / "allow-c.txt",
                         f"c | {javasrc.rel(bad_p, tree)} | org.teavm.platform.Platform | self-test\n")
        expect("c allow-listed", source(allow_p), 0)
        bad_p.unlink()

        # d: one argument over the limit, for a static, an instance and an importing body.
        for label, body in (("d static 32", _jsbody("tooMany", JSBODY_MAX_ARGUMENTS + 1)),
                            ("d instance 31+receiver", _jsbody("tooManyInstance", JSBODY_MAX_ARGUMENTS, False)),
                            ("d imports", _jsbody("tooManyImports", JSBODY_MAX_ARGUMENTS - 1, imports=2))):
            bad_d = _write(tree / "port/mc/shared/selftest/src/java/dev/gaius/browser/Big.java",
                           "package dev.gaius.browser;\nimport org.teavm.jso.JSBody;\nfinal class Big {\n"
                           + body + "}\n")
            expect(label, source(empty), 1)
        allow_d = _write(scratch / "allow-d.txt", f"d | {javasrc.rel(bad_d, tree)} | tooManyImports | self-test\n")
        expect("d allow-listed", source(allow_d), 0)
        bad_d.unlink()
        shadowed = _write(tree / "port/overrides/libraries/lwjgl/src/main/java/org/lwjgl/system/Shadow.java",
                          "package org.lwjgl.system;\nimport org.teavm.jso.JSBody;\nfinal class Shadow {\n"
                          + _jsbody("jsOnly", 40) + "}\n")
        _write(tree / "port/overrides/libraries/lwjgl/src/target-wasmgc/java/org/lwjgl/system/Shadow.java",
               "package org.lwjgl.system;\nfinal class Shadow {\n}\n")
        expect("d shadowed by target-wasmgc", source(empty), 0)
        shadowed.unlink()

        # e: a per-frame copy, report mode, enforce mode and the allow-list.
        _write(gl, javasrc.read_text(gl).replace("send(location, value.length);",
                                                 "send(location, JsArrays.float32(value).getLength());"))
        expect("e report mode", source(empty, "report"), 0)
        expect("e enforce", source(empty, "enforce"), 1)
        allow_e = _write(scratch / "allow-e.txt", f"e | {javasrc.rel(gl, tree)} | BrowserGL#uniform4fv | self-test\n")
        expect("e allow-listed", source(allow_e, "enforce"), 0)
        _write(gl, "package org.lwjgl.opengl;\nfinal class BrowserGL {\n"
                   "  static void uniform4fv(int location, float[] value) {\n"
                   "    if (JsArrays.LINEAR_MEMORY) {\n      if (value == null) {\n        return;\n      }\n"
                   "      send(location, JsArrays.stageFloat32(value, 0, value.length));\n      return;\n    }\n"
                   "    send(location, JsArrays.float32(value).getLength());\n  }\n"
                   "  static void uniform1iv(int location, int[] value) {\n"
                   "    if (JsArrays.LINEAR_MEMORY) {\n      send(location, 1);\n    } else {\n"
                   "      send(location, ints(value));\n    }\n  }\n"
                   "  private static int ints(int[] value) {\n    return JsArrays.int32(value).getLength();\n  }\n"
                   "  static void send(int a, int b) {}\n}\n")
        expect("e JS-only branch and helper", source(empty, "enforce"), 0)
        _write(gl, javasrc.read_text(gl).replace("send(location, 1);", "send(location, ints(value));"))
        expect("e helper called on the wasm path", source(empty, "enforce"), 1)
        _write(gl, javasrc.read_text(gl).replace("send(location, ints(value));\n    } else",
                                                 "send(location, 1);\n    } else")
               .replace("      return;\n    }\n    send(location, JsArrays.float32",
                        "    }\n    send(location, JsArrays.float32"))
        expect("e guard that does not return", source(empty, "enforce"), 1)

        # h: an array of an imported JS type, of a JSObject interface of the sources (also
        # behind another one), and the allow-list; text in comments and strings does not count.
        jso = tree / "port/src/main/java/dev/gaius/browser/StateStrings.java"
        _write(jso, "package dev.gaius.browser;\nimport org.teavm.jso.core.JSString;\n"
                    "final class StateStrings {\n  // JSString[] in a comment\n  static String s = \"JSString[]\";\n"
                    "  static JSString one;\n}\n")
        expect("h clean", source(empty), 0)
        _write(jso, javasrc.read_text(jso).replace("static JSString one;", "static JSString[] cache = new JSString[14];"))
        expect("h imported JS type", source(empty), 1)
        allow_h = _write(scratch / "allow-h.txt", f"h | {javasrc.rel(jso, tree)} | JSString | self-test\n")
        expect("h allow-listed", source(allow_h), 0)
        jso.unlink()
        _write(tree / "port/src/main/java/dev/gaius/browser/Handle.java",
               "package dev.gaius.browser;\nimport org.teavm.jso.JSObject;\ninterface Handle extends JSObject {\n}\n")
        _write(tree / "port/src/main/java/dev/gaius/browser/Texture.java",
               "package dev.gaius.browser;\ninterface Texture extends Handle {\n}\n")
        holder = _write(tree / "port/src/main/java/dev/gaius/browser/Holder.java",
                        "package dev.gaius.browser;\nfinal class Holder {\n  Texture[] textures;\n}\n")
        expect("h JSObject interface of the sources", source(empty), 1)
        _write(holder, "package dev.gaius.browser;\nfinal class Holder {\n  Texture texture;\n  int[] ids;\n}\n")
        expect("h single values", source(empty), 0)

        # b: the JSPI lines.
        good_log = _write(scratch / "good.log", f"[INFO] x\n[gaius-teavm] {JSPI_LOG_LINE}\n"
                          f"[gaius-teavm] {CLINIT_LOG_LINE}\n")
        bad_log = _write(scratch / "bad.log", "[INFO] x\n[gaius-teavm] async methods: 1200\n")
        no_clinit_log = _write(scratch / "no-clinit.log", f"[INFO] x\n[gaius-teavm] {JSPI_LOG_LINE}\n")
        expect("b JSPI lines", run_artifact([], [str(good_log)], None), 0)
        expect("b missing JSPI line", run_artifact([], [str(bad_log)], None), 1)
        expect("b missing class initializer line", run_artifact([], [str(no_clinit_log)], None), 1)

        # a: wasm-check self-test, then a module with an injected invalid function.
        status = subprocess.run([node(), str(WASM_CHECK), "--self-test"]).returncode
        expect("a wasm-check --self-test", status, 0)
        sample = scratch / "sample.wasm"
        injected = scratch / "injected.wasm"
        script = ("import { sampleModule } from './wasm-binary.mjs';\n"
                  "import { writeFileSync } from 'node:fs';\n"
                  f"writeFileSync({json.dumps(str(sample))}, sampleModule(5));\n")
        helper = WASM_CHECK.parent / f".self-test-{os.getpid()}.mjs"
        try:
            helper.write_text(script, encoding="utf-8")
            subprocess.run([node(), str(helper)], check=True)
        finally:
            helper.unlink(missing_ok=True)
        subprocess.run([node(), str(WASM_CHECK), "--inject", "3", "--out", str(injected), str(sample)], check=True)
        expect("a clean module", run_artifact([str(sample)], [], None), 0)
        expect("a injected invalid function", run_artifact([str(injected)], [], None), 1)

        # f: a js overlay log against a baseline, then one changed count and one new drop.
        baseline = scratch / "patch.json"
        log = _write(scratch / "overlays.log", "x\nPATCH_DROPPED A.b\nPATCH_SUMMARY applied=3 dropped=1 "
                                               "bringupSkipped=0\nPATCH_SUMMARY applied=2 dropped=0 bringupSkipped=0\n")
        run_patch_summary("selftest", str(log), True, baseline)
        expect("f equal", _patch_check("selftest", log, baseline), 0)
        _write(log, javasrc.read_text(log).replace("applied=2", "applied=1"))
        expect("f changed summary", _patch_check("selftest", log, baseline), 1)
        _write(log, javasrc.read_text(log).replace("applied=1", "applied=2") + "PATCH_DROPPED C.d\n")
        expect("f new drop", _patch_check("selftest", log, baseline), 1)
        # A wasmgc-only patch ("<KIND> wasmgc") is a js target skip and part of the js baseline; a wasmgc skip
        # means the log is not a js build and cannot become the baseline.
        js_skip = _write(scratch / "overlays-js-skip.log", "PATCH_TARGET_SKIPPED E.f target=js\n"
                         "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1\n")
        expect("f js target skip baseline", run_patch_summary("selftest-skip", str(js_skip), True, baseline), 0)
        expect("f js target skip equal", _patch_check("selftest-skip", js_skip, baseline), 0)
        wasm_skip = _write(scratch / "overlays-wasm-skip.log", "PATCH_TARGET_SKIPPED E.f target=wasmgc\n"
                           "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1\n")
        try:
            status = run_patch_summary("selftest-wasm", str(wasm_skip), True, baseline)
        except SystemExit:
            status = 1
        expect("f wasmgc target skip refused as a baseline", status, 1)

        # g: normalization and comparison (generate-pom.sh itself is exercised by `pom`).
        sample_root = scratch / "checkout"
        text = (f"<systemPath>{sample_root.as_posix()}/port/work/x.jar</systemPath>\n"
                f"<systemPath>{(scratch / 'ov').as_posix()}/client.jar</systemPath>\n")
        normalized = normalize_pom(text, [(scratch / "ov", "@OVERLAYS@"), (sample_root, "@ROOT@")])
        want = "<systemPath>@ROOT@/port/work/x.jar</systemPath>\n<systemPath>@OVERLAYS@/client.jar</systemPath>\n"
        expect("g normalize", 0 if normalized == want else 1, 0)
        expect("g changed POM", 0 if normalized.replace("x.jar", "y.jar") == want else 1, 1)
    if failures:
        for failure in failures:
            print(f"self-test FAILED: {failure}", file=sys.stderr)
        return 1
    print("wasmgc_rules self-test passed: every rule fails on its injected violation")
    return 0


def _patch_check(profile: str, log: Path, baseline: Path) -> int:
    report = compare_patch_record(profile, patch_record(javasrc.read_text(log)),
                                  load_patch_baseline(baseline)["profiles"].get(profile))
    report.print()
    return 0 if report.ok else 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    source = sub.add_parser("source")
    source.add_argument("--json")
    source.add_argument("--copy-rule", choices=("off", "report", "enforce"))
    source.add_argument("--root", default=str(ROOT))
    artifact = sub.add_parser("artifact")
    artifact.add_argument("--wasm", action="append", default=[])
    artifact.add_argument("--log", action="append", default=[])
    artifact.add_argument("--json")
    patch = sub.add_parser("patch-summary")
    patch.add_argument("--profile", required=True)
    patch.add_argument("--log", required=True)
    patch.add_argument("--write-baseline", action="store_true")
    pom = sub.add_parser("pom")
    pom.add_argument("--profile", required=True)
    pom.add_argument("--overlay-dir")
    pom.add_argument("--write-baseline", action="store_true")
    sub.add_parser("self-test")
    args = parser.parse_args(argv)
    if args.command == "source":
        return run_source(Path(args.root), args.json, args.copy_rule)
    if args.command == "artifact":
        return run_artifact(args.wasm, args.log, args.json)
    if args.command == "patch-summary":
        return run_patch_summary(args.profile, args.log, args.write_baseline)
    if args.command == "pom":
        return run_pom(args.profile, args.overlay_dir, args.write_baseline)
    return self_test()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
