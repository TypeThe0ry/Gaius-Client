#!/usr/bin/env python3
import importlib.util
import gzip
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROFILE_DATA = tuple(
    json.loads(path.read_text(encoding="utf-8"))
    for path in sorted((ROOT / "port/versions").glob("*.json"))
)
PROFILE_IDS = tuple(profile["id"] for profile in PROFILE_DATA)
RENDERPEARL_PROFILE = next(profile["id"] for profile in PROFILE_DATA
                            if profile.get("renderSeam") == "renderpearl-1")
LEGACY_PROFILE = next(profile["id"] for profile in PROFILE_DATA
                       if profile.get("renderSeam") == "blaze3d-backend-1")
SCRIPT = ROOT / "port/scripts/check-release-launcher-security.py"
SPEC = importlib.util.spec_from_file_location("release_launcher_security", SCRIPT)
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ReleaseLauncherSecurityTest(unittest.TestCase):
    def test_stale_launcher_is_rejected_for_all_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stale = ('const token = urlParams.get("bridgeToken") || '
                     'urlParams.get("relayToken");\n'
                     'if (urlParams.get("allowMultipleTabs") === "1") return true;\n')
            for profile in PROFILE_IDS:
                profile_dir = root / profile
                profile_dir.mkdir()
                (profile_dir / "index.html").write_text(stale, encoding="utf-8")
                (profile_dir / "Gaius.html").write_text(stale, encoding="utf-8")
            result = subprocess.run([sys.executable, str(SCRIPT), *[
                str(root / profile) for profile in PROFILE_IDS]],
                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("allowMultipleTabs", result.stderr)

    def test_current_template_render_passes_for_site_and_portable(self):
        template = ROOT / "port/web/launcher/index.template.html"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            site = root / f"site-{RENDERPEARL_PROFILE}"
            site.mkdir()
            rendered = template.read_text(encoding="utf-8")
            (site / "index.html").write_text(rendered, encoding="utf-8")
            (site / f"Gaius-{RENDERPEARL_PROFILE}.html").write_text(rendered, encoding="utf-8")
            result = subprocess.run([sys.executable, str(SCRIPT), str(site)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_fresh_postprocessed_pages_pass_for_all_profiles(self):
        profile_files = sorted((ROOT / "port/versions").glob("*.json"))
        wanted = set(PROFILE_IDS)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for profile_file in profile_files:
                profile = json.loads(profile_file.read_text(encoding="utf-8"))
                profile_id = str(profile.get("id", ""))
                if profile_id not in wanted:
                    continue
                profile_dir = root / profile_id
                profile_dir.mkdir()
                index = profile_dir / "index.html"
                classes = profile_dir / "classes.js"
                shutil.copyfile(ROOT / "port/web/launcher/index.template.html", index)
                classes.write_text(
                    "window.__gaiusRuntimeInjection = {profile: '" + profile_id + "'};\n",
                    encoding="utf-8",
                )
                result = subprocess.run([
                    sys.executable, str(ROOT / "port/scripts/postprocess-index-html.py"),
                    str(index), str(classes), profile_id,
                    str(profile["official"]["assetIndexId"]),
                ], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                shutil.copyfile(index, profile_dir / f"Gaius-{profile_id}.html")
            self.assertEqual({p.name for p in root.iterdir()}, wanted)
            result = subprocess.run([sys.executable, str(SCRIPT), str(root)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_reviewed_runtime_query_entries_pass_but_new_query_reads_fail(self):
        reviewed = (
            'if (new URLSearchParams(location.search).get("gaiusRelay") === "0") return;\n'
            'if (root.location && root.location.search && typeof URLSearchParams === "function") {\n'
            'var params = new URLSearchParams(root.location.search);\n'
            'var pngSwitch = new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng");\n'
            '|| new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng") === "1";\n'
            'return new URLSearchParams(loc.search || "");\n'
            'var token = new URL(base).searchParams.get("v");\n'
            'if (token) url.searchParams.set("v", token);\n'
            'token = new URL(base).searchParams.get("v") || "dev";\n'
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / f"Gaius-{RENDERPEARL_PROFILE}.html"
            path.write_text(reviewed, encoding="utf-8")
            self.assertEqual(CHECK.scan([Path(directory)]), [])
            path.write_text(reviewed +
                            'new URLSearchParams(location.search).get("unreviewed");\n',
                            encoding="utf-8")
            self.assertTrue(CHECK.scan([Path(directory)]))

    def test_minified_illegal_read_next_to_consent_declaration_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / f"Gaius-{RENDERPEARL_PROFILE}.html"
            path.write_text(
                "function confirmGaiusLinkBridge(){const linkToken=urlParams.get(\"bridgeToken\")||"
                "urlParams.get(\"relayToken\")||\"\";send(urlParams.get(\"bridgeToken\"));}",
                encoding="utf-8")
            self.assertTrue(CHECK.scan([Path(directory)]))

    def test_query_alias_and_alternate_access_samples_fail(self):
        samples = (
            'const p = new URLSearchParams(location.search); const linkToken = p.get("bridgeToken") || p.get("relayToken") || "";',
            'urlParams.get(`bridgeToken`);',
            'params["get"]("bridgeToken");',
            'new URL(location.href).searchParams.get("bridgeToken");',
            'new URLSearchParams(location.search).get("relayToken");',
            'const params = new URLSearchParams(location.search); params.get("bridgeToken");',
            'p.get("allowMultipleTabs") === "1";',
            'params["get"]("allowMultipleTabs") === "1";',
        )
        for index, sample in enumerate(samples):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / f"Gaius-{RENDERPEARL_PROFILE}.html"
                path.write_text(sample, encoding="utf-8")
                self.assertTrue(CHECK.scan([Path(directory)]), sample)

    def test_gzip_only_adversarial_launcher_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / f"Gaius-{LEGACY_PROFILE}.html.gz"
            payload = 'const token=urlParams.get("relayToken");'
            path.write_bytes(gzip.compress(payload.encode("utf-8")))
            errors = CHECK.scan([Path(directory)])
            self.assertTrue(any("relayToken" in error for error in errors), errors)

    def test_launcher_directory_without_html_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            errors = CHECK.scan([Path(directory)])
            self.assertTrue(any("no .html or .html.gz" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
