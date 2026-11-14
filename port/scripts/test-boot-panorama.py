#!/usr/bin/env python3
"""Check cached vanilla cubemap validation used by the early shell panorama."""

import base64
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from verification_profiles import RENDERPEARL_ID


SPEC = importlib.util.spec_from_file_location(
    "postprocess", Path(__file__).with_name("postprocess-index-html.py")
)
POSTPROCESS = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(POSTPROCESS)


class BootPanoramaTest(unittest.TestCase):
    def test_reads_all_faces_and_rejects_corrupt_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = root / RENDERPEARL_ID / "assets"
            objects = assets / "objects"
            index = {}
            expected = []
            for face in range(6):
                content = (f"face-{face}" * (face + 1)).encode("ascii")
                digest = hashlib.sha1(content).hexdigest()
                path = objects / digest[:2] / digest
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                name = f"minecraft/textures/gui/title/background/panorama_{face}.png"
                index[name] = {"hash": digest, "size": len(content)}
                expected.append(content)
            (assets / "indexes").mkdir(parents=True)
            (assets / "indexes" / "34.json").write_text(
                json.dumps({"objects": index}), encoding="utf-8"
            )

            faces = POSTPROCESS.load_boot_panorama_faces(RENDERPEARL_ID, "34", root)
            self.assertEqual(len(faces), 6)
            self.assertEqual(
                [base64.b64decode(value.removeprefix("data:image/png;base64,")) for value in faces],
                expected,
            )

            (objects / next(iter(index.values()))["hash"][:2] / next(iter(index.values()))["hash"]).write_bytes(b"corrupt")
            self.assertEqual(POSTPROCESS.load_boot_panorama_faces(RENDERPEARL_ID, "34", root), [])

    def test_html_gets_a_separate_inline_faces_block(self):
        faces = ["data:image/png;base64," + base64.b64encode(bytes([face])).decode("ascii")
                 for face in range(6)]
        result = POSTPROCESS.apply_gaius_boot_art(
            "<html><head>\n</head>\n<body>\n</body>\n", faces
        )
        self.assertIn('data-gaius-boot-panorama="v1"', result)
        self.assertEqual(result.count("data:image/png;base64,"), 6)


if __name__ == "__main__":
    unittest.main()
