#!/usr/bin/env python3
"""Fast regression tests for the TeaVM JavaScript postprocessor."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("postprocess-teavm-js.py")
SPEC = importlib.util.spec_from_file_location("postprocess_teavm_js", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
POSTPROCESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POSTPROCESS)


TEAVM_LONG_HELPER = (
    '"use strict";'
    "let Bi=BigInt(0),P3=val=>BigInt.asIntN(64,"
    "BigInt(val>=0?Math.floor(val):Math.ceil(val)));"
)


# TeaVM 0.15 thread.js in the shape of a minified bundle whose top-level name budget is
# spent: the current-thread slot is a property of the scope object A (A.EeY), read by the
# $rt_nativeThread getter F.  The six slot references are the start and resume checks,
# the set/clear pair in run, the declaration and the getter.  A.EeYZ and x.A.EeY are
# other names and must survive unchanged.
MINIFIED_THREAD_RUNTIME = (
    "BON.prototype.start=function(callback){if(this.status!==3){throw new Error("
    '"Thread already started");}if\n(A.EeY!==null){throw new Error('
    '"Another thread is running");}this.status=0;this.run();};'
    "BON.prototype.resume=function(){if(A.EeY!==null){throw new Error("
    '"Another thread is running");}this.status=2;this.run();};'
    "BON.prototype.run=function(){A.EeY=this;let result;try {result=this.runner();}"
    "catch(e){result=e;}finally {A.EeY=null;}};"
    "A.RGF=(runner,callback)=>(new BON(runner)).start(callback);A.EeY=null;"
    'let F=()=>A.EeY,H=()=>{throw new Error("Invalid recorded state");};'
    "A.EeYZ=1;let x={A:{EeY:2}};x.A.EeY=3;function J(){return F();}"
)

# The same runtime in a named (non-minified) build.
NAMED_THREAD_RUNTIME = """
TeaVMThread.prototype.start = function(callback) {
    if ($rt_java.$rt_currentNativeThread !== null) {
        throw new Error("Another thread is running");
    }
    this.run();
};
TeaVMThread.prototype.resume = function() {
    if ($rt_java.$rt_currentNativeThread !== null) {
        throw new Error("Another thread is running");
    }
    this.run();
};
TeaVMThread.prototype.run = function() {
    $rt_java.$rt_currentNativeThread = this;
    let result;
    try {
        result = this.runner();
    } catch (e){
        result = e;
    } finally {
        $rt_java.$rt_currentNativeThread = null;
    }
};
$rt_java.$rt_currentNativeThread = null;
let $rt_nativeThread = () => $rt_java.$rt_currentNativeThread,
$rt_invalidPointer = () => {
    throw new Error("Invalid recorded state");
};
"""

# A build with gaius.teavm.pinRuntimeNames: the slot already has its own top-level
# binding (Xq) in a let list.
PINNED_THREAD_RUNTIME = (
    "BON.prototype.start=function(callback){if(Xq!==null){throw new Error("
    '"Another thread is running");}this.run();};'
    "BON.prototype.run=function(){Xq=this;let result;try {result=this.runner();}"
    "catch(e){result=e;}finally {Xq=null;}};"
    "let Xq=null,F=()=>Xq,H=()=>{};"
)


def run_postprocess(target: Path) -> int:
    with contextlib.redirect_stdout(io.StringIO()):
        return POSTPROCESS.main([str(SCRIPT), str(target)])


def run_postprocess_capturing(target: Path) -> tuple[int, str, str]:
    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        status = POSTPROCESS.main([str(SCRIPT), str(target)])
    return status, stdout.getvalue(), stderr.getvalue()


def assert_parses_as_javascript(test: unittest.TestCase, directory: str, text: str) -> None:
    """node --check of a rewritten bundle, when Node.js is available."""
    node = shutil.which("node")
    if node is None:
        return
    script = Path(directory) / "syntax-check.js"
    script.write_text(text, encoding="utf-8")
    result = subprocess.run(
        [node, "--check", str(script)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    test.assertEqual(result.returncode, 0, result.stderr)


class PostprocessTeaVMJSTest(unittest.TestCase):
    def test_real_teavm_helper_is_patched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text(TEAVM_LONG_HELPER, encoding="utf-8")

            self.assertEqual(run_postprocess(target), 0)
            result = target.read_text(encoding="utf-8")

            self.assertIn(POSTPROCESS.PATCH_MARKER, result)
            self.assertIn("!Number.isFinite(val)?", result)
            self.assertNotIn(
                "BigInt.asIntN(64,BigInt(val>=0?Math.floor(val):Math.ceil(val)))",
                result,
            )

            self.assertEqual(run_postprocess(target), 0)
            self.assertEqual(target.read_text(encoding="utf-8"), result)

    def test_unminified_worker_runtime_gets_input_pump(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "singleplayer-server.js"
            target.write_text(
                TEAVM_LONG_HELPER
                + "let runtime={};runtime.$rt_startThread=()=>{};"
                + "let worker = {};\n"
                + "worker.pumpIntegratedServerNetworkInput = pump;",
                encoding="utf-8",
            )

            self.assertEqual(run_postprocess(target), 0)
            result = target.read_text(encoding="utf-8")
            self.assertIn(POSTPROCESS.INTEGRATED_SERVER_PUMP_MARKER, result)
            self.assertIn("runtime.$rt_startThread(", result)

    def test_minified_worker_runtime_gets_input_pump(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "singleplayer-server.js"
            target.write_text(
                TEAVM_LONG_HELPER
                + "A.WPL=f=>(args,callback)=>{let javaArgs=args;"
                + "A.HGZ(()=>{f.call(null,javaArgs);},callback);};"
                + "let B={},F=()=>{};B.pumpIntegratedServerNetworkInput=F;",
                encoding="utf-8",
            )

            self.assertEqual(run_postprocess(target), 0)
            result = target.read_text(encoding="utf-8")
            self.assertIn(POSTPROCESS.INTEGRATED_SERVER_PUMP_MARKER, result)
            self.assertIn("A.HGZ(", result)

    def test_pinned_minified_worker_thread_starter_gets_input_pump(self) -> None:
        # gaius.teavm.pinRuntimeNames (or a starter inside the top-level name budget)
        # gives $rt_startThread its own top-level binding: the call has no scope prefix.
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "singleplayer-server.js"
            target.write_text(
                TEAVM_LONG_HELPER
                + "let Xab=(runner,callback)=>0;"
                + "A.WPL=f=>(args,callback)=>{let javaArgs=args;"
                + "Xab(()=>{f.call(null,javaArgs);},callback);};"
                + "let B={},F=()=>{};B.pumpIntegratedServerNetworkInput=F;",
                encoding="utf-8",
            )

            status, stdout, stderr = run_postprocess_capturing(target)
            self.assertEqual(status, 0, stderr)
            self.assertIn("Injected TeaVM server Worker coroutine input pump", stdout)
            result = target.read_text(encoding="utf-8")
            shim = result[result.index(POSTPROCESS.INTEGRATED_SERVER_PUMP_MARKER):]
            self.assertIn("Xab(\n                () => B.pumpIntegratedServerNetworkInput()", shim)
            assert_parses_as_javascript(self, directory, result)

            self.assertEqual(run_postprocess(target), 0)
            self.assertEqual(target.read_text(encoding="utf-8"), result)

    def test_readable_worker_thread_starter_binding_gets_input_pump(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "singleplayer-server.js"
            target.write_text(
                TEAVM_LONG_HELPER
                + "let $rt_startThread = (r, c) => 0;\n"
                + "let $rt_mainStarter = f => (args, callback) => {\n"
                + "    let javaArgs = args;\n"
                + "    $rt_startThread(() => {\n"
                + "        f.call(null, javaArgs);\n"
                + "    }, callback);\n"
                + "};\n"
                + "let worker = {}, pump = () => {};\n"
                + "worker.pumpIntegratedServerNetworkInput = pump;",
                encoding="utf-8",
            )

            status, _, stderr = run_postprocess_capturing(target)
            self.assertEqual(status, 0, stderr)
            result = target.read_text(encoding="utf-8")
            shim = result[result.index(POSTPROCESS.INTEGRATED_SERVER_PUMP_MARKER):]
            self.assertIn(
                "$rt_startThread(\n                () => worker.pumpIntegratedServerNetworkInput()",
                shim,
            )
            assert_parses_as_javascript(self, directory, result)

    def test_worker_thread_starter_shapes(self) -> None:
        pattern = POSTPROCESS.RUNTIME_THREAD_START_CALL_PATTERN
        cases = {
            "A.HGZ(()=>{f.call(null,javaArgs);},callback);": "A.HGZ",
            ";Xab(()=>{f.call(null,javaArgs);},callback);": "Xab",
            "runtime.$rt_startThread(() => { f.call(null, javaArgs); }, callback);":
                "runtime.$rt_startThread",
            "$rt_startThread(() => {\n f.call(null, javaArgs);\n }, callback);": "$rt_startThread",
        }
        for text, call in cases.items():
            with self.subTest(text):
                match = POSTPROCESS.find_anchored(pattern, text, "javaArgs", before=192, after=256)
                self.assertIsNotNone(match)
                self.assertEqual(match.group("call"), call)
        # The instance main starter passes the instance first; it is not the thread starter
        # call that the shim reuses, and a property call is not matched by its tail.
        for text in (
            "Xab(()=>{f.call(null,instance,javaArgs);},callback);",
            "x.A.HGZ(()=>{f.call(null,javaArgs);},callback);",
        ):
            with self.subTest(text):
                match = POSTPROCESS.find_anchored(pattern, text, "javaArgs", before=192, after=256)
                self.assertIsNone(match)

    def test_worker_without_thread_starter_fails_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "singleplayer-server.js"
            original = (
                TEAVM_LONG_HELPER
                + "A.WPL=f=>(args,callback)=>{let javaArgs=args;"
                + "Xab(()=>{g(javaArgs);},callback);};"
                + "let B={},F=()=>{};B.pumpIntegratedServerNetworkInput=F;"
            )
            target.write_text(original, encoding="utf-8")

            status, _, stderr = run_postprocess_capturing(target)

            self.assertEqual(status, 1)
            self.assertIn("TeaVM native thread starter was not found", stderr)
            self.assertEqual(target.read_text(encoding="utf-8"), original)

    def test_atomic_write_replaces_complete_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text("old release", encoding="utf-8")

            POSTPROCESS.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), "new release")
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_replace_failure_preserves_original_and_cleans_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            original = "old release"
            target.write_text(original, encoding="utf-8")

            with mock.patch.object(
                POSTPROCESS.os,
                "replace",
                side_effect=OSError("ENOSPC"),
            ):
                with self.assertRaises(OSError):
                    POSTPROCESS.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), original)
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_fsync_failure_preserves_original_and_cleans_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            original = "old release"
            target.write_text(original, encoding="utf-8")

            with mock.patch.object(
                POSTPROCESS.os,
                "fsync",
                side_effect=OSError("ENOSPC"),
            ):
                with self.assertRaises(OSError):
                    POSTPROCESS.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), original)
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_minified_scoped_thread_slot_becomes_module_binding(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text(TEAVM_LONG_HELPER + MINIFIED_THREAD_RUNTIME, encoding="utf-8")

            status, stdout, _ = run_postprocess_capturing(target)
            self.assertEqual(status, 0)
            result = target.read_text(encoding="utf-8")

            binding = POSTPROCESS.THREAD_SLOT_BINDING
            marker = POSTPROCESS.THREAD_SLOT_MARKER
            self.assertIn(f"A.EeY into the module-level binding {binding}", stdout)
            self.assertIn("(6 references, getter F)", stdout)
            self.assertEqual(POSTPROCESS.find_tokens(result, "A.EeY"), [])
            self.assertEqual(len(POSTPROCESS.find_tokens(result, binding)), 6)
            self.assertIn(f"let {binding}={marker}null;let F=()=>{binding},H=", result)
            self.assertIn(f"if\n({binding}!==null)", result)
            self.assertIn(f"run=function(){{{binding}=this;let result;", result)
            self.assertIn(f"finally {{{binding}=null;}}", result)
            # Names that share the prefix or end in the slot path are other names.
            self.assertIn("A.EeYZ=1;", result)
            self.assertIn("x.A.EeY=3;", result)
            self.assertEqual(result.count(marker), 1)
            assert_parses_as_javascript(self, directory, result)

            status, stdout, _ = run_postprocess_capturing(target)
            self.assertEqual(status, 0)
            self.assertIn(f"{binding} is already a marked module-level binding", stdout)
            self.assertEqual(target.read_text(encoding="utf-8"), result)

    def test_named_scoped_thread_slot_becomes_module_binding(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text(TEAVM_LONG_HELPER + NAMED_THREAD_RUNTIME, encoding="utf-8")

            status, stdout, _ = run_postprocess_capturing(target)
            self.assertEqual(status, 0)
            result = target.read_text(encoding="utf-8")

            binding = POSTPROCESS.THREAD_SLOT_BINDING
            marker = POSTPROCESS.THREAD_SLOT_MARKER
            self.assertIn(
                f"$rt_java.$rt_currentNativeThread into the module-level binding {binding}",
                stdout,
            )
            self.assertIn("(6 references, getter $rt_nativeThread)", stdout)
            self.assertEqual(
                POSTPROCESS.find_tokens(result, "$rt_java.$rt_currentNativeThread"), [])
            self.assertEqual(len(POSTPROCESS.find_tokens(result, binding)), 6)
            self.assertIn(
                f"let {binding}={marker}null;\nlet $rt_nativeThread = () => {binding},",
                result,
            )
            self.assertIn(f"\n    {binding} = this;\n", result)
            assert_parses_as_javascript(self, directory, result)

            self.assertEqual(run_postprocess(target), 0)
            self.assertEqual(target.read_text(encoding="utf-8"), result)

    def test_pinned_thread_slot_is_only_marked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text(TEAVM_LONG_HELPER + PINNED_THREAD_RUNTIME, encoding="utf-8")

            status, stdout, _ = run_postprocess_capturing(target)
            self.assertEqual(status, 0)
            result = target.read_text(encoding="utf-8")

            marker = POSTPROCESS.THREAD_SLOT_MARKER
            self.assertIn("Xq is a module-level binding (getter F); marked it", stdout)
            self.assertNotIn(POSTPROCESS.THREAD_SLOT_BINDING, result)
            # Apart from the long helper, the marker is the only change.
            start = result.index("BON.prototype.start")
            self.assertEqual(
                result[start:],
                PINNED_THREAD_RUNTIME.replace("let Xq=null,", f"let Xq={marker}null,"),
            )
            assert_parses_as_javascript(self, directory, result)

            status, stdout, _ = run_postprocess_capturing(target)
            self.assertEqual(status, 0)
            self.assertIn("Xq is already a marked module-level binding", stdout)
            self.assertEqual(target.read_text(encoding="utf-8"), result)

    def test_scoped_thread_getter_is_accepted(self) -> None:
        # The top-level name budget ran out exactly at the $rt_nativeThread getter.
        runtime = PINNED_THREAD_RUNTIME.replace("let Xq=null,F=()=>Xq,H=()=>{};",
                                                "let Xq=null;A.F=()=>Xq;let H=()=>{};")
        self.assertNotEqual(runtime, PINNED_THREAD_RUNTIME)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text(TEAVM_LONG_HELPER + "let A={};" + runtime, encoding="utf-8")

            status, stdout, stderr = run_postprocess_capturing(target)
            self.assertEqual(status, 0, stderr)
            self.assertIn("Xq is a module-level binding (getter A.F); marked it", stdout)
            result = target.read_text(encoding="utf-8")
            self.assertIn(f"let Xq={POSTPROCESS.THREAD_SLOT_MARKER}null;A.F=()=>Xq;", result)
            assert_parses_as_javascript(self, directory, result)

    def test_thread_slot_shape_mismatch_fails_without_writing(self) -> None:
        cases = {
            # A seventh reference to the scoped slot.
            "extra reference": MINIFIED_THREAD_RUNTIME + "function K(){return A.EeY;}",
            # Five references: the resume check reads another name.
            "missing reference": MINIFIED_THREAD_RUNTIME.replace(
                "resume=function(){if(A.EeY!==null)", "resume=function(){if(B!==null)"),
            # The $rt_nativeThread getter no longer follows the declaration.
            "no getter": MINIFIED_THREAD_RUNTIME.replace(
                "A.EeY=null;let F=()=>A.EeY,", "A.EeY=null;let F=()=>G(),"),
            # TeaVMThread.prototype.run no longer has the expected prologue.
            "run shape": MINIFIED_THREAD_RUNTIME.replace(
                "run=function(){A.EeY=this;let result;", "run=function(){A.EeY=this;var result;"),
            # The rewrite target name is already taken.
            "binding in use": MINIFIED_THREAD_RUNTIME
            + f"let {POSTPROCESS.THREAD_SLOT_BINDING}=0;",
        }
        for name, runtime in cases.items():
            with self.subTest(name), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "classes.js"
                original = TEAVM_LONG_HELPER + runtime
                self.assertNotEqual(original, TEAVM_LONG_HELPER + MINIFIED_THREAD_RUNTIME)
                target.write_text(original, encoding="utf-8")

                status, _, stderr = run_postprocess_capturing(target)

                self.assertEqual(status, 1)
                self.assertIn("TeaVM current-thread slot rewrite failed", stderr)
                self.assertEqual(target.read_text(encoding="utf-8"), original)

    def test_main_exits_with_status_1_on_thread_slot_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            original = TEAVM_LONG_HELPER + MINIFIED_THREAD_RUNTIME + "A.EeY.x=1;"
            target.write_text(original, encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(target)],
                capture_output=True,
                text=True,
                timeout=60,
            )

            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("expected 6 references", result.stderr)
            self.assertEqual(target.read_text(encoding="utf-8"), original)


if __name__ == "__main__":
    unittest.main()
