"""Exercise the locked release entry point using only synthetic tools and outputs."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.name == "posix" and shutil.which("bash") and shutil.which("jq"),
                     "requires Bash and jq on a POSIX host")
class LockedWasmTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="gaius-locked-wasm-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for relative in ("patcher", "port/scripts", "ambient", "pinned tools"):
            (self.root / relative).mkdir(parents=True)
        shutil.copy2(ROOT / "patcher/build-release-locked.sh", self.root / "patcher")
        shutil.copy2(ROOT / "port/scripts/build-wasm-hotpath.sh", self.root / "port/scripts")
        self.script("port/scripts/version-profile.sh", 'gaius_load_version_profile() { :; }\n'
                    'gaius_dist_directory() { printf "%s\\n" "$GAIUS_DIST_DIRECTORY"; }')
        self.script("port/scripts/build-version-release.sh", '''
            [[ "$GAIUS_SKIP_WASM_HOTPATH" == false && "$GAIUS_REQUIRE_WASM_HOTPATH" == true ]]
            [[ "$GAIUS_WASM_OPT_STRICT" == 1 ]]
            port/scripts/build-wasm-hotpath.sh
            printf '%s\\n' "$GAIUS_WASM_OPT" >release-optimizer
        ''')
        for name in ("clang", "wasm-ld", "wasm-opt"):
            self.script(f"ambient/{name}", '''
                if [[ "$1" == --version ]]; then echo "wasm-opt version 120"; exit; fi
                touch ambient-used
                exit 7
            ''')
        self.script("pinned tools/clang", '''
            if [[ "$1" == --version ]]; then echo "clang version 21.1.8 (test)"; exit; fi
            [[ "$1" == --no-default-config && "$2" == --no-wasm-opt ]]
            [[ "$3" == "-fuse-ld=$GAIUS_WASM_LD" && "$4" == -Wl,--keep-section=target_features ]]
            "$GAIUS_WASM_LD" "$@"
        ''')
        self.script("pinned tools/wasm-ld", '''
            if [[ "$1" == --version ]]; then echo "LLD 21.1.8 (test)"; exit; fi
            printf 'linked' >"${@: -1}"
        ''')
        self.script("pinned tools/wasm-opt", '''
            if [[ "$1" == --version ]]; then echo "wasm-opt version 133 (test)"; exit; fi
            [[ "$2" == -O3 && "$3" == -o && "$1" == "$4" && "$(cat "$1")" == linked ]]
            printf 'optimized' >"$4"
        ''')
        self.env = {**os.environ, "PATH": f"{self.root / 'ambient'}{os.pathsep}{os.environ['PATH']}",
                    "GAIUS_CLANG": str(self.root / "pinned tools/clang"),
                    "GAIUS_WASM_LD": str(self.root / "pinned tools/wasm-ld"),
                    "GAIUS_WASM_OPT": str(self.root / "pinned tools/wasm-opt"),
                    "GAIUS_LLVM_VERSION": "21.1.8", "GAIUS_WASM_OPT_VERSION": "133",
                    "GAIUS_LLVM_LIBRARY_DIR": "", "GAIUS_DIST_DIRECTORY": str(self.root / "dist"),
                    "GAIUS_WASM_HOST_TOOLS_REPORT": str(self.root / "wasm-host-tools.json")}

    def script(self, name, body):
        path = self.root / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body + "\n", encoding="utf-8")
        path.chmod(0o755)

    def run_release(self):
        return subprocess.run(["bash", "patcher/build-release-locked.sh", "fixture"], cwd=self.root,
                              env=self.env, capture_output=True, text=True)

    def test_hostile_path_does_not_select_an_ambient_tool(self):
        ambient = subprocess.check_output(["wasm-opt", "--version"], env=self.env, text=True).strip()
        self.assertEqual(ambient, "wasm-opt version 120")
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / "ambient-used").exists())
        self.assertEqual((self.root / "dist/gaius-hotpath.wasm").read_bytes(), b"optimized")
        self.assertEqual((self.root / "release-optimizer").read_text().strip(), self.env["GAIUS_WASM_OPT"])
        tools = json.loads((self.root / "wasm-host-tools.json").read_text())
        self.assertEqual(tools, {"clang": "clang version 21.1.8 (test)", "wasmLd": "LLD 21.1.8 (test)",
                                 "wasmOpt": "wasm-opt version 133 (test)"})
        self.assertTrue(all(identity in result.stdout for identity in tools.values()))

    def test_a_mismatched_pinned_optimizer_fails_before_compilation(self):
        self.script("pinned tools/wasm-opt", 'echo "wasm-opt version 120"')
        result = self.run_release()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reported 'wasm-opt version 120'; expected version 133", result.stderr)
        self.assertFalse((self.root / "dist").exists())


if __name__ == "__main__":
    unittest.main()
