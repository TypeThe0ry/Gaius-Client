#!/usr/bin/env python3
"""Patch TeaVM JavaScript output for browser-only runtime compatibility.

TeaVM 0.15 emits a helper for Java double/float -> long conversion in the form:

    val => BigInt.asIntN(64, BigInt(val >= 0 ? Math.floor(val) : Math.ceil(val)))

Java casts NaN to 0 and saturates infinities, but JavaScript's BigInt(number)
throws for NaN/Infinity. Minecraft can naturally feed NaN into math paths while
recovering camera/interpolation state; on the JVM this is non-fatal, but in the
browser it crashes the client. This post-process keeps the generated output
semantically closer to Java and prevents those fatal browser exceptions.

It also makes TeaVM's current-thread slot a module-level binding.  Every
coroutine check ($rt_suspending/$rt_resuming, i.e. after almost every call in an
async method) reads the slot through $rt_nativeThread.  When the top-level name
budget (maxTopLevelNames) is spent, TeaVM emits the slot as a property of the
additional scope object ("A.EeY=null;let F=()=>A.EeY"), which V8 keeps in
dictionary mode, so each check pays a hash lookup.  The patched TeaVM core pins
runtime names to top-level bindings (gaius.teavm.pinRuntimeNames); this step
rewrites the slot when it is still scoped and only marks it otherwise.  The slot
and the getter names are taken from the runtime's own TeaVMThread.run and
$rt_nativeThread definitions, not from fixed minified names.
"""

from __future__ import annotations

import os
import re
import sys
import tempfile
from pathlib import Path


LONG_MAX = "9223372036854775807"
LONG_MIN = "-9223372036854775808"
PATCH_MARKER = "/*gaius-java-finite-long-cast*/"
INTEGRATED_SERVER_PUMP_MARKER = "/*gaius-integrated-server-input-coroutine*/"

TO_LONG_PATTERN = re.compile(
    r"(?P<arg>[A-Za-z_$][A-Za-z0-9_$]*)\s*=>\s*"
    r"BigInt\.asIntN\(\s*64\s*,\s*BigInt\(\s*"
    r"(?P=arg)\s*>=\s*0\s*\?\s*Math\.floor\(\s*(?P=arg)\s*\)\s*"
    r":\s*Math\.ceil\(\s*(?P=arg)\s*\)\s*"
    r"\)\s*\)"
)

SAFE_TO_LONG_PATTERN = re.compile(
    r"(?P<arg>[A-Za-z_$][A-Za-z0-9_$]*)\s*=>\s*"
    r"(?:/\*gaius-java-finite-long-cast\*/)?\s*"
    r"BigInt\.asIntN\(\s*64\s*,\s*!\s*Number\.isFinite\(\s*(?P=arg)\s*\)\s*\?\s*"
    r"\(\s*(?P=arg)\s*!==\s*(?P=arg)\s*\?\s*BigInt\(\s*0\s*\)"
)

INTEGRATED_SERVER_EXPORT_PATTERN = re.compile(
    r"(?P<exports>[A-Za-z_$][A-Za-z0-9_$]*)"
    r"\.pumpIntegratedServerNetworkInput\s*=\s*"
    r"(?P<function>[A-Za-z_$][A-Za-z0-9_$]*)\s*;"
)

# A declaration of the thread starter on a scope object ("runtime.$rt_startThread=").
RUNTIME_THREAD_START_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_$.])(?P<call>[A-Za-z_$][A-Za-z0-9_$]*\.\$rt_startThread)\s*="
)

# The thread starter call in TeaVM 0.15 runtime.js $rt_mainStarter:
#     $rt_startThread(() => { f.call(null, javaArgs); }, callback);
# Template locals (f, javaArgs, callback) keep their names in minified output. The
# starter itself is a scope property past the top-level name budget ("A.HGZ("), a
# top-level binding when it fits the budget or gaius.teavm.pinRuntimeNames pins it
# ("Xab("), and keeps its own name in readable output ("$rt_startThread(").
RUNTIME_THREAD_START_CALL_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_$.])"
    r"(?P<call>(?:[A-Za-z_$][A-Za-z0-9_$]*\.)?[A-Za-z_$][A-Za-z0-9_$]*)"
    r"\(\s*\(\s*\)\s*=>\s*\{\s*f\.call\(\s*null\s*,\s*javaArgs\s*\)\s*;?\s*\}"
    r"\s*,\s*callback\s*\)"
)

THREAD_SLOT_MARKER = "/*gaius-module-thread-slot*/"
THREAD_SLOT_BINDING = "$gaiusRtThread"
# TeaVM 0.15 thread.js references $rt_currentNativeThread six times: the
# declaration, the $rt_nativeThread getter, the check in start and in resume,
# and the set/clear pair in run.
THREAD_SLOT_REFERENCES = 6
IDENTIFIER = r"[A-Za-z_$][A-Za-z0-9_$]*"

# TeaVMThread.prototype.run = function() { <slot> = this; let result; try {
# result = this.runner(); ...  Template locals (result) keep their names in
# minified output, and "this.runner()" occurs only here.
THREAD_RUN_PATTERN = re.compile(
    r"\.prototype\.run\s*=\s*function\s*\(\s*\)\s*\{\s*"
    rf"(?P<slot>{IDENTIFIER}(?:\.{IDENTIFIER})?)\s*=\s*this\s*;\s*"
    r"let\s+result\s*;\s*try\s*\{\s*result\s*=\s*this\.runner\(\)\s*;"
)


def patched_to_long(match: re.Match[str]) -> str:
    arg = match.group("arg")
    return (
        f"{arg}=>{PATCH_MARKER}BigInt.asIntN(64,"
        f"!Number.isFinite({arg})?"
        f"({arg}!=={arg}?BigInt(0):"
        f"({arg}>0?BigInt(\"{LONG_MAX}\"):BigInt(\"{LONG_MIN}\")))"
        f":BigInt({arg}>=0?Math.floor({arg}):Math.ceil({arg})))"
    )


def integrated_server_pump_shim(exports: str, runtime_start_call: str) -> str:
    return f"""
{INTEGRATED_SERVER_PUMP_MARKER}
let $gaiusIntegratedServerPumpRunning = false;
let $gaiusIntegratedServerPumpPending = false;
let $gaiusIntegratedServerPumpDispatchScheduled = false;
let $gaiusIntegratedServerPumpRetryTimer = 0;
let $gaiusIntegratedServerPumpRetryCount = 0;
const $gaiusIntegratedServerPumpMaxRetries = 4;
const $gaiusScheduleIntegratedServerPump = typeof queueMicrotask === 'function'
    ? queueMicrotask
    : callback => Promise.resolve().then(callback);
const $gaiusScheduleIntegratedServerPumpRetry = (callback, delay) => {{
    if (typeof setTimeout === 'function') return setTimeout(callback, delay);
    $gaiusScheduleIntegratedServerPump(callback);
    return 1;
}};
{exports}.__gaiusStartIntegratedServerPump = () => {{
    const stats = globalThis.__gaiusNetworkStats;
    if (stats) {{
        stats.integratedServerPumpRequests =
            Number(stats.integratedServerPumpRequests) || 0;
        stats.integratedServerPumpStarts =
            Number(stats.integratedServerPumpStarts) || 0;
        stats.integratedServerPumpFailures =
            Number(stats.integratedServerPumpFailures) || 0;
        stats.integratedServerPumpCoalesced =
            Number(stats.integratedServerPumpCoalesced) || 0;
        stats.integratedServerPumpRetrySchedules =
            Number(stats.integratedServerPumpRetrySchedules) || 0;
        stats.integratedServerPumpRetryExhaustions =
            Number(stats.integratedServerPumpRetryExhaustions) || 0;
        stats.integratedServerPumpRequests =
            stats.integratedServerPumpRequests + 1;
    }}
    if ($gaiusIntegratedServerPumpRunning ||
        $gaiusIntegratedServerPumpDispatchScheduled ||
        $gaiusIntegratedServerPumpRetryTimer) {{
        $gaiusIntegratedServerPumpPending = true;
        if (stats) {{
            stats.integratedServerPumpCoalesced =
                stats.integratedServerPumpCoalesced + 1;
        }}
        return;
    }}
    $gaiusIntegratedServerPumpRetryCount = 0;
    let run;
    const fail = error => {{
        $gaiusIntegratedServerPumpRunning = false;
        $gaiusIntegratedServerPumpPending = true;
        globalThis.__gaiusIntegratedServerPumpError =
            String(error && (error.stack || error) || error);
        if (stats) {{
            stats.integratedServerPumpFailures =
                stats.integratedServerPumpFailures + 1;
        }}
        if ($gaiusIntegratedServerPumpRetryCount <
            $gaiusIntegratedServerPumpMaxRetries) {{
            $gaiusIntegratedServerPumpRetryCount++;
            if (stats) {{
                stats.integratedServerPumpRetrySchedules =
                    stats.integratedServerPumpRetrySchedules + 1;
            }}
            const delay = Math.min(
                8,
                1 << Math.min(3, $gaiusIntegratedServerPumpRetryCount - 1)
            );
            $gaiusIntegratedServerPumpRetryTimer =
                $gaiusScheduleIntegratedServerPumpRetry(() => {{
                    $gaiusIntegratedServerPumpRetryTimer = 0;
                    if ($gaiusIntegratedServerPumpPending) run();
                }}, delay);
        }} else {{
            $gaiusIntegratedServerPumpPending = false;
            if (stats) {{
                stats.integratedServerPumpRetryExhaustions =
                    stats.integratedServerPumpRetryExhaustions + 1;
            }}
        }}
    }};
    run = () => {{
        $gaiusIntegratedServerPumpPending = false;
        $gaiusIntegratedServerPumpRunning = true;
        if (stats) {{
            stats.integratedServerPumpStarts =
                stats.integratedServerPumpStarts + 1;
        }}
        try {{
            {runtime_start_call}(
                () => {exports}.pumpIntegratedServerNetworkInput(),
                result => {{
                    if (result instanceof Error) {{
                        fail(result);
                        return;
                    }}
                    $gaiusIntegratedServerPumpRunning = false;
                    $gaiusIntegratedServerPumpRetryCount = 0;
                    if ($gaiusIntegratedServerPumpPending) {{
                        $gaiusIntegratedServerPumpDispatchScheduled = true;
                        $gaiusScheduleIntegratedServerPump(() => {{
                            $gaiusIntegratedServerPumpDispatchScheduled = false;
                            if ($gaiusIntegratedServerPumpPending) run();
                        }});
                    }}
                }}
            );
        }} catch (error) {{
            fail(error);
        }}
    }};
    run();
}};
"""


def write_text_atomically(target: Path, text: str) -> None:
    """Replace target only after the complete postprocessed file is durable."""
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, target)
        temporary_name = None
        # Windows cannot open a directory for fsync; the replace above is still
        # atomic, so durability of the directory entry is best-effort there.
        if os.name != "nt":
            directory_fd = os.open(target.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def find_anchored(
    pattern: re.Pattern[str],
    text: str,
    anchor_text: str,
    before: int = 192,
    after: int = 512,
) -> re.Match[str] | None:
    search_offset = 0
    while True:
        anchor = text.find(anchor_text, search_offset)
        if anchor < 0:
            return None
        match = pattern.search(
            text,
            max(0, anchor - before),
            min(len(text), anchor + after),
        )
        if match is not None and match.start() <= anchor < match.end():
            return match
        search_offset = anchor + len(anchor_text)


class RuntimeShapeError(Exception):
    """The generated TeaVM runtime does not have the expected shape."""


IDENTIFIER_CHARACTERS = frozenset(
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_$"
)


def find_tokens(text: str, name: str) -> list[int]:
    """Offsets of name as a whole identifier path that is not a property access.

    str.find keeps this linear and fast on the ~100 MB bundles, where a regex
    with a lookbehind scans every offset.
    """
    positions: list[int] = []
    start = 0
    end_limit = len(text)
    while True:
        index = text.find(name, start)
        if index < 0:
            return positions
        start = index + 1
        if index > 0:
            before = text[index - 1]
            if before in IDENTIFIER_CHARACTERS or before == ".":
                continue
        after_index = index + len(name)
        if after_index < end_limit and text[after_index] in IDENTIFIER_CHARACTERS:
            continue
        positions.append(index)


def module_level_thread_slot(text: str) -> tuple[str, str | None]:
    """Make TeaVM's current-thread slot a module-level binding.

    Returns the text and a log message, or no message when the output has no
    coroutine runtime (simpleThread.js, used by programs without threads).
    """
    if "this.runner()" not in text:
        return text, None
    run = find_anchored(THREAD_RUN_PATTERN, text, "this.runner()", before=512, after=256)
    if run is None:
        raise RuntimeShapeError("TeaVMThread.prototype.run was not found")
    slot = run.group("slot")
    # Anchored at a token offset of the slot: "<slot>=null;let <getter>=()=><slot>"
    # (scoped slot) or "<slot>=null,<getter>=()=><slot>" (binding in a let list). The
    # getter itself may be a scope property ("<slot>=null;A.F=()=><slot>") when the
    # top-level name budget ran out exactly at it; it is only used in log messages.
    declaration_pattern = re.compile(
        re.escape(slot)
        + r"\s*=\s*(?P<marker>"
        + re.escape(THREAD_SLOT_MARKER)
        + r")?null"
        + rf"(?P<separator>\s*[;,]\s*(?:let\s+)?)(?P<getter>{IDENTIFIER}(?:\.{IDENTIFIER})?)"
        + r"\s*=\s*\(\s*\)\s*=>\s*"
        + re.escape(slot)
        + r"(?![A-Za-z0-9_$])"
    )
    references = find_tokens(text, slot)
    declarations = [
        match
        for match in (declaration_pattern.match(text, offset) for offset in references)
        if match is not None
    ]
    if len(declarations) != 1:
        raise RuntimeShapeError(
            f"expected one current-thread slot declaration followed by the "
            f"$rt_nativeThread getter for {slot}, found {len(declarations)}"
        )
    declaration = declarations[0]
    getter = declaration.group("getter")
    if "." not in slot:
        # The patched TeaVM already emitted a binding (pinned runtime name).
        if declaration.group("marker"):
            return text, f"TeaVM current-thread slot {slot} is already a marked module-level binding"
        marked = (
            text[:declaration.start()]
            + f"{slot}={THREAD_SLOT_MARKER}null"
            + text[declaration.start("separator"):]
        )
        return marked, (
            f"TeaVM current-thread slot {slot} is a module-level binding "
            f"(getter {getter}); marked it"
        )

    if len(references) != THREAD_SLOT_REFERENCES:
        raise RuntimeShapeError(
            f"expected {THREAD_SLOT_REFERENCES} references to the current-thread "
            f"slot {slot}, found {len(references)}"
        )
    if ";" not in declaration.group("separator"):
        raise RuntimeShapeError(
            f"the current-thread slot declaration of {slot} is not a statement"
        )
    if find_tokens(text, THREAD_SLOT_BINDING):
        raise RuntimeShapeError(f"{THREAD_SLOT_BINDING} is already used by the output")
    edits: list[tuple[int, int, str]] = [(
        declaration.start(),
        declaration.start("separator"),
        f"let {THREAD_SLOT_BINDING}={THREAD_SLOT_MARKER}null",
    )]
    for reference in references:
        if reference == declaration.start():
            continue
        edits.append((reference, reference + len(slot), THREAD_SLOT_BINDING))
    pieces: list[str] = []
    cursor = 0
    for start, end, replacement in sorted(edits):
        pieces.append(text[cursor:start])
        pieces.append(replacement)
        cursor = end
    pieces.append(text[cursor:])
    patched = "".join(pieces)
    if find_tokens(patched, slot):
        raise RuntimeShapeError(f"a reference to {slot} survived the rewrite")
    return patched, (
        f"Rewrote the TeaVM current-thread slot {slot} into the module-level binding "
        f"{THREAD_SLOT_BINDING} ({len(references)} references, getter {getter})"
    )


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: postprocess-teavm-js.py <classes.js>", file=sys.stderr)
        return 2

    target = Path(argv[1])
    text = target.read_text(encoding="utf-8")
    patched = text
    messages: list[str] = []

    if PATCH_MARKER in patched or find_anchored(
        SAFE_TO_LONG_PATTERN,
        patched,
        "Number.isFinite",
    ) is not None:
        messages.append(f"TeaVM JS already contains finite-safe long conversion: {target}")
    else:
        match = find_anchored(TO_LONG_PATTERN, patched, "BigInt.asIntN")
        if match is None:
            print(
                f"TeaVM JS long conversion helper was not found in {target}; "
                "the generated runtime shape may have changed.",
                file=sys.stderr,
            )
            return 1
        patched = patched[:match.start()] + patched_to_long(match) + patched[match.end():]
        messages.append(
            f"Patched TeaVM JS finite-safe long conversion in {target} (1 occurrence)."
        )

    try:
        patched, thread_message = module_level_thread_slot(patched)
    except RuntimeShapeError as error:
        print(
            f"TeaVM current-thread slot rewrite failed in {target}: {error}; "
            "the generated runtime shape may have changed.",
            file=sys.stderr,
        )
        return 1
    if thread_message is not None:
        messages.append(f"{thread_message}: {target}")

    # ADVANCED output may still retain whitespace when diagnostics disable
    # minification. Match the semantic export instead of one formatted spelling.
    # The anchor keeps the search off the rest of the bundle: an unanchored
    # search of this pattern takes over a minute on the ~93 MB client.
    worker_export = find_anchored(
        INTEGRATED_SERVER_EXPORT_PATTERN,
        patched,
        ".pumpIntegratedServerNetworkInput",
        before=128,
        after=128,
    )
    if worker_export is not None:
        if INTEGRATED_SERVER_PUMP_MARKER in patched:
            messages.append(
                f"TeaVM server Worker already contains coroutine input pump: {target}"
            )
        else:
            runtime = find_anchored(
                RUNTIME_THREAD_START_CALL_PATTERN,
                patched,
                "javaArgs",
                before=192,
                after=256,
            )
            if runtime is None:
                runtime = find_anchored(
                    RUNTIME_THREAD_START_PATTERN,
                    patched,
                    "$rt_startThread",
                    before=128,
                    after=128,
                )
            if runtime is None:
                print(
                    f"TeaVM native thread starter was not found in {target}; "
                    "the generated runtime shape may have changed.",
                    file=sys.stderr,
                )
                return 1
            insert_at = worker_export.end()
            shim = integrated_server_pump_shim(
                worker_export.group("exports"),
                runtime.group("call"),
            )
            patched = patched[:insert_at] + shim + patched[insert_at:]
            messages.append(f"Injected TeaVM server Worker coroutine input pump in {target}.")

    if patched != text:
        write_text_atomically(target, patched)
    for message in messages:
        print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
