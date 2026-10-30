#!/usr/bin/env python3
"""Check Gaius translation coverage and optional browser asset-pack language/font entries."""

from __future__ import annotations

import argparse
import gzip
import json
import re
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LANG_DIR = ROOT / "port/src/main/resources/assets/gaius/lang"
JAVA_DIR = ROOT / "port/src/main/java/dev/gaius/browser"
LOCALES = ("en_us", "zh_cn", "es_es")


def check_languages() -> set[str]:
    values = {locale: json.loads((LANG_DIR / f"{locale}.json").read_text(encoding="utf-8"))
              for locale in LOCALES}
    expected = set(values["en_us"])
    if not expected:
        raise AssertionError("English Gaius language resource is empty")
    for locale, mapping in values.items():
        missing = expected - set(mapping)
        extra = set(mapping) - expected
        if missing or extra:
            raise AssertionError(f"{locale} key mismatch: missing={sorted(missing)}, extra={sorted(extra)}")
        if any(not isinstance(value, str) or not value for value in mapping.values()):
            raise AssertionError(f"{locale} contains an empty or non-string translation")

    java_roots = (JAVA_DIR, ROOT / "port/overrides/classlib/src/main/java/dev/gaius/browser")
    java = "\n".join(path.read_text(encoding="utf-8")
                     for root in java_roots for path in root.glob("*.java"))
    keys = set(re.findall(r'BrowserI18n\.(?:text|string)\("(?:gaius\.)?([a-z0-9_.]+)"', java))
    keys |= set(re.findall(r'Component\.translatable\("(gaius\.[a-z0-9_.]+)"', java))
    keys = {key if key.startswith("gaius.") else "gaius." + key for key in keys}
    missing = keys - expected
    if missing:
        raise AssertionError(f"Java references missing translation keys: {sorted(missing)}")
    template = (ROOT / "port/web/launcher/index.template.html").read_text(encoding="utf-8")
    boot_text = template[template.index("const GAIUS_BOOT_TEXT = {"):template.index("function gaiusBootLocale()")]
    boot_locales = {
        locale: set(re.findall(r'(?:^|,\s*)([A-Za-z][A-Za-z0-9]*):\s*"', match.group(1)))
        for locale, match in ((locale, re.search(rf"{locale}: \{{(.*?)\}}", boot_text)) for locale in LOCALES)
        if match
    }
    if set(boot_locales) != set(LOCALES) or len({frozenset(keys) for keys in boot_locales.values()}) != 1:
        raise AssertionError("HTML boot translation keys differ across en_us/zh_cn/es_es")
    boot_references = set(re.findall(r'__gaiusTranslate\("([A-Za-z][A-Za-z0-9]*)"\)', template))
    missing_boot = boot_references - boot_locales["en_us"]
    if missing_boot:
        raise AssertionError(f"HTML references missing boot translation keys: {sorted(missing_boot)}")
    resolver = template[template.index("function gaiusBootLocale()"):template.index("if (typeof Error")]
    if not (resolver.index("/^(zh|es)") < resolver.index("navigator.language")):
        raise AssertionError("saved game language must take priority over navigator.language")
    if "window.__gaiusTranslate = function" not in resolver or "dataset.gaiusLocale" not in resolver:
        raise AssertionError("launcher does not expose its selected locale and translator")
    if "window.__gaiusTranslate" not in (ROOT / "port/web/launcher/boot-art.js").read_text(encoding="utf-8"):
        raise AssertionError("boot art does not use the launcher translator")
    print(f"Language keys: {len(expected)}; Java Gaius key references: {len(keys)}; locales: {', '.join(LOCALES)}")
    return expected


def check_pack(path: Path) -> None:
    with path.open("rb") as source:
        data = gzip.decompress(source.read())
    if data[:8] != b"GAIUSVP1":
        raise AssertionError(f"invalid asset pack header: {path}")
    index_size = struct.unpack_from("<I", data, 8)[0]
    index = json.loads(data[12:12 + index_size])
    required = {
        *(f"assets/minecraft/lang/{locale}.json" for locale in LOCALES),
        *(f"assets/gaius/lang/{locale}.json" for locale in LOCALES),
        "assets/minecraft/font/include/unifont.json",
        "assets/minecraft/font/unifont.zip",
    }
    missing = required - set(index)
    if missing:
        raise AssertionError(f"{path} is missing required language/font resources: {sorted(missing)}")
    print(f"Asset pack OK: {path} ({len(index)} resources)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", type=Path, action="append", default=[])
    args = parser.parse_args()
    check_languages()
    for pack in args.pack:
        check_pack(pack)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, UnicodeError, json.JSONDecodeError, struct.error) as exc:
        print(f"language resource smoke failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
