"""Shared owner artwork and launcher icon markup for online/offline packaging."""
from __future__ import annotations

import base64
import html
import re
from pathlib import Path

ICON_DIR = Path(__file__).resolve().parents[1] / "web/launcher/icons"
THEME_COLOR = "#5a2a22"
ICON_TYPES = {
    "favicon.ico": "image/x-icon",
    "favicon-16.png": "image/png",
    "favicon-32.png": "image/png",
    "icon.svg": "image/svg+xml",
    "apple-touch-icon.png": "image/png",
    "icon-192.png": "image/png",
    "icon-512.png": "image/png",
    "icon-maskable-512.png": "image/png",
}


def render_links(urls: dict[str, str], manifest: str | None = None) -> str:
    lines = [f'  <meta name="theme-color" content="{THEME_COLOR}">']
    for name, sizes in (("favicon.ico", "16x16 32x32 48x48"), ("favicon-16.png", "16x16"),
                        ("favicon-32.png", "32x32"), ("icon.svg", "any")):
        if name in urls:
            lines.append(f'  <link rel="icon" type="{ICON_TYPES[name]}" sizes="{sizes}" href="{html.escape(urls[name], quote=True)}">')
    if "apple-touch-icon.png" in urls:
        lines.append(f'  <link rel="apple-touch-icon" sizes="180x180" href="{html.escape(urls["apple-touch-icon.png"], quote=True)}">')
    if manifest:
        lines.append(f'  <link rel="manifest" href="{html.escape(manifest, quote=True)}">')
    return "\n".join(lines) + "\n"


def replace_links(text: str, links: str) -> str:
    # Replace both the historical empty favicon and icons from an already staged site.
    text = re.sub(r'[ \t]*<link\b(?=[^>]*\brel=[\"\'](?:icon|shortcut icon|apple-touch-icon|manifest)[\"\'])[^>]*>[^\S\n]*\n?',
                  "", text, flags=re.MULTILINE | re.IGNORECASE)
    text = re.sub(r'[ \t]*<meta\b(?=[^>]*\bname=[\"\']theme-color[\"\'])[^>]*>[^\S\n]*\n?',
                  "", text, flags=re.MULTILINE | re.IGNORECASE)
    if "</head>" not in text:
        raise RuntimeError("launcher has no head for icon links")
    return text.replace("</head>", links + "</head>", 1)


def embedded_links() -> str:
    return render_links({name: "data:" + ICON_TYPES[name] + ";base64," +
                         base64.b64encode((ICON_DIR / name).read_bytes()).decode("ascii")
                         for name in ("favicon-32.png", "icon.svg")})


def assert_site_icons(site: Path) -> None:
    """Bind staged icon bytes, manifest entries, page links and MIME headers."""
    import hashlib
    import json

    assets = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))["assets"]
    page = (site / "index.html").read_text(encoding="utf-8")
    headers = (site / "_headers").read_text(encoding="utf-8")
    for name, content_type in {**ICON_TYPES, "manifest.webmanifest": "application/manifest+json"}.items():
        url = assets[name]
        data = (site / url).read_bytes()
        if not data or hashlib.sha256(data).hexdigest()[:16] not in url:
            raise RuntimeError(f"invalid staged icon hash: {name}")
        if name in ICON_TYPES and data != (ICON_DIR / name).read_bytes():
            raise RuntimeError(f"staged icon differs from owner artwork: {name}")
        if f"/{url}\n  Content-Type: {content_type}\n" not in headers:
            raise RuntimeError(f"missing staged icon MIME header: {name}")
    expected_links = render_links(assets, assets["manifest.webmanifest"])
    if expected_links not in page or 'href="data:,"' in page:
        raise RuntimeError("staged launcher icon links are missing")
    manifest = json.loads((site / assets["manifest.webmanifest"]).read_text(encoding="utf-8"))
    if manifest.get("name") != "Gaius" or any(manifest.get(key) != THEME_COLOR
            for key in ("theme_color", "background_color")):
        raise RuntimeError("incorrect game web app manifest")
    expected = [("icon-192.png", "192x192", "any"), ("icon-512.png", "512x512", "any"),
                ("icon-maskable-512.png", "512x512", "maskable")]
    actual = [(item["src"], item["sizes"], item.get("purpose", "any")) for item in manifest["icons"]]
    if actual != [(assets[name], size, purpose) for name, size, purpose in expected]:
        raise RuntimeError("incorrect game manifest icon entries")
