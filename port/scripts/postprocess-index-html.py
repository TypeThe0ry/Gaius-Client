#!/usr/bin/env python3
"""Patch the ignored dist index page after TeaVM emits classes.js.

The browser client keeps the HTML launcher in port/web/dist, which is ignored
with the rest of the generated assets. Keep browser startup fixes reproducible
by applying them from this tracked script after every successful TeaVM build.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from pathlib import Path


def native_external_path(value: str) -> Path:
    """Accept Git-Bash /c/... paths when Python runs on Windows."""
    if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", value):
        value = f"{value[1].upper()}:{value[2:]}"
    return Path(value).expanduser()


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise RuntimeError(f"index.html patch point was not found: {label}")


def normalize_release_version(version: str) -> str:
    """Use the release filename/tag spelling for prereleases (pre2, never pre.2)."""
    return re.sub(r"-pre\.(\d+)(?=$|[+])", r"-pre\1", version.strip())


def patch_release_version(text: str) -> str:
    version = normalize_release_version((Path(__file__).resolve().parents[2] / "VERSION").read_text(
        encoding="utf-8"
    ))
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", version):
        raise RuntimeError("VERSION must contain a release version")
    text, count = re.subn(
        r'(<span id="gaius-shell-version">)[^<]*(</span>)',
        lambda match: f"{match[1]}VERSION {version}{match[2]}",
        text,
    )
    if count != 1:
        raise RuntimeError("launcher must contain exactly one release version label")
    return text


def migrate_raf_frame_samples(text: str) -> str:
    """Replace the legacy shifting FPS sample array with a bounded ring."""
    text = text.replace(
        "        fps.rafFrameTimes = [];\n"
        "        fps.rafLastFrameAt = 0;\n",
        "        fps.rafFrameTimes = new Float32Array(4096);\n"
        "        fps.rafFrameWriteIndex = 0;\n"
        "        fps.rafFrameCount = 0;\n"
        "        fps.rafLastFrameAt = 0;\n",
    )
    text = text.replace(
        "          const samples = fps.rafFrameTimes || (fps.rafFrameTimes = []);\n"
        "          samples.push(frameMs);\n"
        "          if (samples.length > 2048) samples.splice(0, samples.length - 2048);\n",
        "          let samples = fps.rafFrameTimes;\n"
        "          if (!(samples instanceof Float32Array) || samples.length !== 4096) {\n"
        "            samples = new Float32Array(4096);\n"
        "            fps.rafFrameTimes = samples;\n"
        "            fps.rafFrameWriteIndex = 0;\n"
        "            fps.rafFrameCount = 0;\n"
        "          }\n"
        "          const writeIndex = (Number(fps.rafFrameWriteIndex) || 0) % samples.length;\n"
        "          samples[writeIndex] = frameMs;\n"
        "          fps.rafFrameWriteIndex = (writeIndex + 1) % samples.length;\n"
        "          fps.rafFrameCount = Math.min(samples.length, (Number(fps.rafFrameCount) || 0) + 1);\n",
    )
    text = text.replace(
        "        const samples = fps.rafFrameTimes || [];\n"
        "        if (samples.length > 0) {\n"
        "          const ordered = samples.slice().sort((left, right) => left - right);\n",
        "        const samples = fps.rafFrameTimes;\n"
        "        const sampleCount = samples instanceof Float32Array\n"
        "          ? Math.min(samples.length, Number(fps.rafFrameCount) || 0)\n"
        "          : 0;\n"
        "        if (sampleCount > 0) {\n"
        "          const ordered = Array.from(samples.subarray(0, sampleCount))\n"
        "            .sort((left, right) => left - right);\n",
    )
    text = text.replace(
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n",
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const slowestCount = Math.max(1, Math.ceil(ordered.length * 0.01));\n"
        "          let slowestTotalMs = 0;\n"
        "          for (let index = ordered.length - slowestCount; index < ordered.length; index++) {\n"
        "            slowestTotalMs += ordered[index];\n"
        "          }\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((slowestCount * 1000 / slowestTotalMs) * 10) / 10;\n",
    )
    return text


def migrate_text_shader_diagnostics(text: str) -> str:
    """Teach the lightmap diagnostic about the Minecraft 26.2 text shader."""
    sample_call = (
        '.replace("vertexColor = Color * sample_lightmap(Sampler2, UV2);", '
        '"vertexColor = Color;")'
    )
    legacy_call = (
        '.replace("vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);", '
        '"vertexColor = Color;")'
    )
    legacy_count = text.count(legacy_call)
    sample_count = text.count(sample_call)
    if ("installLightweightTextShaderPatch" in text
            and "__gaiusTextShaderTelemetry" not in text):
        raise RuntimeError("index.html text diagnostic is missing bounded telemetry")
    if legacy_count == 0:
        return text
    if sample_count == legacy_count:
        return text
    if sample_count != 0:
        raise RuntimeError("index.html has a partially migrated text lightmap diagnostic")
    for indentation in ("              ", "                "):
        old = indentation + legacy_call
        new = (
            indentation + sample_call + "\n"
            + indentation
            + '.replace("vertexColor = Color * sample_lightmap(Sampler2, UV2)", '
            + '"vertexColor = Color")\n'
            + old
        )
        text = text.replace(old, new, 1)
    if text.count(sample_call) != legacy_count:
        raise RuntimeError("index.html text lightmap diagnostic migration was incomplete")
    return text


def content_token(*paths: Path) -> str:
    digest = hashlib.sha256()
    found = False
    for path in paths:
        if not path.is_file():
            continue
        found = True
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()[:16] if found else "dev"

GAIUS_SHELL_MARKER = 'data-gaius-shell="v2"'

GAIUS_SHELL_CSS = r'''
    /* Gaius Client shell v2: browser launcher controls only. */
    :root {
      --gaius-shell-bg: #0b1116;
      --gaius-shell-panel: #111a21;
      --gaius-shell-panel-strong: #17232c;
      --gaius-shell-line: #31414c;
      --gaius-shell-muted: #9aaab4;
      --gaius-shell-text: #f1f5f7;
      --gaius-shell-accent: #9bd36a;
      --gaius-shell-accent-strong: #c3ec8f;
      --gaius-shell-danger: #f08a7b;
    }

    html,
    body {
      background: var(--gaius-shell-bg);
      color: var(--gaius-shell-text);
    }

    #mc-canvas {
      position: fixed;
      inset: 0;
      width: 100vw;
      height: 100vh;
      background: #05080a;
    }

    #boot-screen {
      z-index: 10;
      background: var(--gaius-shell-bg);
    }

    #boot-screen::before {
      position: absolute;
      inset: 18px;
      content: "";
      border: 1px solid rgba(155, 211, 106, 0.18);
      pointer-events: none;
    }

    #gaius-shell-header {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      z-index: 12;
      display: flex;
      align-items: center;
      min-height: 56px;
      box-sizing: border-box;
      padding: 0 24px;
      border-bottom: 2px solid var(--gaius-shell-line);
      background: var(--gaius-shell-bg);
      pointer-events: none;
      transition: opacity 140ms linear, visibility 140ms linear;
    }

    #gaius-shell-brand {
      display: flex;
      align-items: baseline;
      gap: 10px;
      font: 800 15px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #gaius-shell-brand strong {
      color: var(--gaius-shell-accent-strong);
      font-size: 19px;
    }

    #gaius-shell-mode,
    #gaius-shell-version {
      color: var(--gaius-shell-muted);
      font: 700 11px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #gaius-shell-mode {
      margin-left: auto;
      margin-right: 18px;
      color: var(--gaius-shell-accent);
    }

    #gaius-shell-footer {
      position: fixed;
      left: 24px;
      right: 24px;
      bottom: 18px;
      z-index: 12;
      display: flex;
      justify-content: space-between;
      gap: 16px;
      color: var(--gaius-shell-muted);
      font: 11px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      pointer-events: none;
      transition: opacity 140ms linear, visibility 140ms linear;
    }

    #gaius-shell-footer strong {
      color: var(--gaius-shell-text);
      font-weight: 700;
    }

    html[data-gaius-shell-view="canvas"] #gaius-shell-header,
    html[data-gaius-shell-view="canvas"] #gaius-shell-footer {
      visibility: hidden;
      opacity: 0;
    }

    #boot-brand {
      top: 40%;
      z-index: 11;
      max-width: calc(100vw - 32px);
      color: var(--gaius-shell-text);
      font: 900 72px/0.84 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-shadow: 0 4px 0 #27343b;
    }

    #boot-brand span {
      margin-top: 14px;
      color: var(--gaius-shell-accent);
      font-size: 12px;
      font-weight: 700;
      line-height: 1;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #boot-progress {
      top: 58%;
      z-index: 11;
      width: min(480px, calc(100vw - 48px));
      height: 14px;
      padding: 2px;
      border: 2px solid var(--gaius-shell-text);
      border-radius: 0;
      background: transparent;
    }

    #boot-progress-bar {
      min-width: 2px;
      background: var(--gaius-shell-accent);
      transition: width 160ms linear;
    }

    #boot-progress-text {
      top: calc(58% + 28px);
      z-index: 11;
      color: var(--gaius-shell-muted);
      font: 700 12px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: center;
      text-shadow: none;
    }

    #status {
      top: calc(58% + 62px);
      z-index: 11;
      width: min(660px, calc(100vw - 48px));
      max-height: 22vh;
      box-sizing: border-box;
      padding: 0;
      border: 0;
      border-radius: 0;
      background: transparent;
      color: var(--gaius-shell-muted);
      font: 12px/1.45 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      letter-spacing: 0;
      text-align: center;
    }

    #status[data-state="error"] {
      max-height: 25vh;
      overflow: auto;
      padding: 12px 14px;
      border: 2px solid var(--gaius-shell-danger);
      border-left-width: 5px;
      background: var(--gaius-shell-panel);
      color: var(--gaius-shell-text);
      text-align: left;
    }

    #gaius-error-actions {
      position: fixed;
      top: calc(58% + 190px);
      left: 50%;
      z-index: 12;
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 8px;
      width: min(660px, calc(100vw - 48px));
      transform: translateX(-50%);
    }

    #gaius-error-actions[hidden],
    #gaius-error-details[hidden] {
      display: none;
    }

    #gaius-error-actions button {
      min-height: 36px;
      box-sizing: border-box;
      padding: 0 14px;
      border: 2px solid var(--gaius-shell-line);
      border-radius: 0;
      background: var(--gaius-shell-panel-strong);
      color: var(--gaius-shell-text);
      font: 700 12px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      cursor: pointer;
    }

    #gaius-error-actions button:first-child {
      border-color: var(--gaius-shell-accent);
      background: var(--gaius-shell-accent);
      color: #0b1116;
    }

    #gaius-error-actions button:hover,
    #gaius-error-actions button:focus-visible {
      outline: 2px solid var(--gaius-shell-text);
      outline-offset: 2px;
    }

    #gaius-error-actions button:disabled {
      cursor: wait;
      opacity: 0.65;
    }

    #gaius-error-details {
      position: fixed;
      top: calc(58% + 238px);
      left: 50%;
      z-index: 12;
      width: min(660px, calc(100vw - 48px));
      max-height: 20vh;
      box-sizing: border-box;
      margin: 0;
      padding: 10px 12px;
      overflow: auto;
      border: 1px solid var(--gaius-shell-line);
      background: #080d11;
      color: var(--gaius-shell-muted);
      font: 11px/1.4 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      letter-spacing: 0;
      transform: translateX(-50%);
      white-space: pre-wrap;
    }

    #profile-gate {
      z-index: 14;
      padding: 32px 16px;
      background: var(--gaius-shell-bg);
      color: var(--gaius-shell-text);
    }

    #profile-form {
      width: min(440px, calc(100vw - 32px));
      box-sizing: border-box;
      padding: 30px 32px 26px;
      border: 2px solid var(--gaius-shell-line);
      border-radius: 0;
      background: var(--gaius-shell-panel);
      box-shadow: 8px 8px 0 #06090c;
    }

    #profile-title {
      margin: 0;
      color: var(--gaius-shell-text);
      font: 900 52px/0.9 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: left;
    }

    #profile-kicker {
      margin: 10px 0 28px;
      color: var(--gaius-shell-accent);
      font: 700 11px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #profile-subtitle {
      margin: -16px 0 24px;
      color: var(--gaius-shell-muted);
      font: 13px/1.45 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-form label {
      margin-bottom: 8px;
      color: var(--gaius-shell-text);
      font: 700 12px/1.2 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-name {
      height: 46px;
      border: 2px solid #5a6b76;
      border-radius: 0;
      background: #0a1014;
      color: var(--gaius-shell-text);
      font: 18px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-name:focus {
      border-color: var(--gaius-shell-accent);
      box-shadow: 0 0 0 2px rgba(155, 211, 106, 0.18);
    }

    #profile-submit {
      height: 46px;
      margin-top: 14px;
      border: 2px solid var(--gaius-shell-accent);
      border-radius: 0;
      background: var(--gaius-shell-accent);
      color: #0b1116;
      font: 800 14px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #profile-submit:hover,
    #profile-submit:focus-visible {
      background: var(--gaius-shell-accent-strong);
      outline: 2px solid var(--gaius-shell-text);
      outline-offset: 2px;
    }

    #profile-error {
      min-height: 18px;
      margin: 9px 0 0;
      color: var(--gaius-shell-danger);
      font: 12px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-legal {
      margin: 22px 0 0;
      color: var(--gaius-shell-muted);
      font: 11px/1.5 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: left;
    }

    #profile-switch {
      top: 16px;
      right: 16px;
      left: auto;
      z-index: 30;
      min-height: 36px;
      padding: 0 12px;
      border: 2px solid var(--gaius-shell-line);
      border-radius: 0;
      background: rgba(11, 17, 22, 0.96);
      color: var(--gaius-shell-text);
      font: 700 12px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-switch:hover,
    #profile-switch:focus-visible {
      border-color: var(--gaius-shell-accent);
      background: var(--gaius-shell-panel-strong);
      outline: 2px solid var(--gaius-shell-text);
      outline-offset: 2px;
    }

    @media (max-width: 600px) {
      #gaius-shell-header {
        min-height: 48px;
        padding: 0 16px;
      }

      #gaius-shell-version {
        display: none;
      }

      #gaius-shell-footer {
        left: 16px;
        right: 16px;
        bottom: 12px;
      }

      #gaius-shell-footer span:last-child {
        display: none;
      }

      #boot-brand {
        font-size: 46px;
      }

      #profile-form {
        padding: 24px 20px 22px;
      }

      #profile-title {
        font-size: 44px;
      }

      #gaius-error-actions {
        top: calc(58% + 160px);
      }

      #gaius-error-details {
        top: calc(58% + 208px);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      #gaius-shell-header,
      #gaius-shell-footer,
      #boot-brand,
      #boot-progress-bar {
        animation: none;
        transition: none;
      }
    }
'''

GAIUS_SHELL_SCRIPT = r'''  <script>
    (function installGaiusClientShellV2() {
      const root = document.documentElement;
      const bootScreen = document.getElementById("boot-screen");
      const profileGate = document.getElementById("profile-gate");
      const statusBox = document.getElementById("status");
      const shellMode = document.getElementById("gaius-shell-mode");
      const errorActions = document.getElementById("gaius-error-actions");
      const retryButton = document.getElementById("gaius-retry");
      const detailsButton = document.getElementById("gaius-error-toggle");
      const detailsBox = document.getElementById("gaius-error-details");
      if (!root || !statusBox) return;

      function bootDiagnostics() {
        const diagnostics = [];
        const bootError = window.__gaiusBootError;
        if (bootError && bootError.stack) diagnostics.push(String(bootError.stack));
        const extra = window.__gaiusBootErrorDetails;
        if (Array.isArray(extra)) {
          for (const line of extra) diagnostics.push(String(line));
        }
        return diagnostics.join("\n");
      }

      function renderShell() {
        const profileOpen = !!(profileGate && !profileGate.hidden);
        const bootOpen = !!(bootScreen && !bootScreen.hidden);
        const failed = statusBox.dataset.state === "error";
        const view = profileOpen ? "profile" : (failed ? "error" : (bootOpen ? "boot" : "canvas"));
        root.dataset.gaiusShellView = view;
        if (shellMode) {
          const sessionMode = String(window.__gaiusSessionMode || "").toLowerCase();
          shellMode.textContent = sessionMode === "online"
            ? "ONLINE SESSION"
            : (location.protocol === "file:" ? "PORTABLE HTML" : "BROWSER CLIENT");
        }
        if (errorActions) errorActions.hidden = !failed;
        if (detailsButton) {
          detailsButton.hidden = !failed;
          detailsButton.textContent = detailsBox && detailsBox.dataset.open === "1"
            ? "Hide diagnostics"
            : "Show diagnostics";
        }
        if (detailsBox) {
          detailsBox.textContent = bootDiagnostics() || "No additional diagnostics were captured.";
          detailsBox.hidden = !failed || detailsBox.dataset.open !== "1";
        }
      }

      function retryGaiusStartup() {
        if (retryButton) {
          retryButton.disabled = true;
          retryButton.textContent = "Restarting...";
        }
        const next = new URL(location.href);
        next.searchParams.set("retry", String(Date.now()));
        location.replace(next.href);
      }

      if (retryButton) retryButton.addEventListener("click", retryGaiusStartup);
      if (detailsButton && detailsBox) {
        detailsButton.addEventListener("click", () => {
          detailsBox.dataset.open = detailsBox.dataset.open === "1" ? "0" : "1";
          renderShell();
        });
      }

      const observer = new MutationObserver(renderShell);
      observer.observe(statusBox, {attributes: true, childList: true, subtree: true});
      if (bootScreen) observer.observe(bootScreen, {attributes: true});
      if (profileGate) observer.observe(profileGate, {attributes: true});
      window.__gaiusShell = {
        version: 2,
        refresh: renderShell,
        retry: retryGaiusStartup
      };
      renderShell();
    })();
  </script>
'''

GAIUS_BOOT_ART_MARKER = 'data-gaius-boot-art="v1"'


def apply_gaius_boot_art(text: str) -> str:
    """Install the vanilla-style boot screen art (port/web/launcher/boot-art.{css,js}).

    Separate from apply_gaius_client_shell: the launcher template already carries the shell, so
    that step returns early. The art is a self-contained style and script; existing pages keep
    their boot flow and progress logic.
    """
    if GAIUS_BOOT_ART_MARKER in text:
        return text
    launcher = Path(__file__).resolve().parents[1] / "web" / "launcher"
    css = (launcher / "boot-art.css").read_text(encoding="utf-8")
    script = (launcher / "boot-art.js").read_text(encoding="utf-8")
    if "</script" in script.lower() or "</style" in css.lower():
        raise RuntimeError("boot art must not contain closing script or style tags")
    text = replace_required(
        text,
        "</head>\n",
        "  <style " + GAIUS_BOOT_ART_MARKER + ">\n" + css + "  </style>\n</head>\n",
        "Gaius boot art CSS",
    )
    return replace_required(
        text,
        "</body>\n",
        "  <script " + GAIUS_BOOT_ART_MARKER + ">\n" + script + "  </script>\n</body>\n",
        "Gaius boot art script",
    )


GAIUS_TOUCH_CONTROLS_MARKER = 'data-gaius-touch-controls="v1"'
GAIUS_TOUCH_VIEWPORT = (
    '  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, '
    'user-scalable=no, interactive-widget=resizes-visual">\n'
)


def apply_gaius_touch_controls(text: str) -> str:
    """Install the touchscreen controls (port/web/launcher/touch-controls.{css,js}).

    Like apply_gaius_boot_art, a self-contained style and script. The script stays inert until a
    touch screen is used; it drives the game through window.__gaiusInput, which the GLFW and SDL
    bridges install. Phones also need a viewport without user zoom. It deliberately stays inside the
    safe area (no viewport-fit=cover): the canvas fills the layout viewport and would otherwise be
    drawn under a notch or the home indicator.
    """
    if GAIUS_TOUCH_CONTROLS_MARKER in text:
        return text
    launcher = Path(__file__).resolve().parents[1] / "web" / "launcher"
    css = (launcher / "touch-controls.css").read_text(encoding="utf-8")
    script = (launcher / "touch-controls.js").read_text(encoding="utf-8")
    if "</script" in script.lower() or "</style" in css.lower():
        raise RuntimeError("touch controls must not contain closing script or style tags")
    text, viewport_count = re.subn(
        r'  <meta name="viewport" content="[^"]*">\n',
        lambda _match: GAIUS_TOUCH_VIEWPORT,
        text,
        count=1,
    )
    if viewport_count != 1:
        raise RuntimeError("index.html patch point was not found: viewport meta")
    text = replace_required(
        text,
        "</head>\n",
        "  <style " + GAIUS_TOUCH_CONTROLS_MARKER + ">\n" + css + "  </style>\n</head>\n",
        "Gaius touch controls CSS",
    )
    return replace_required(
        text,
        "</body>\n",
        "  <script " + GAIUS_TOUCH_CONTROLS_MARKER + ">\n" + script + "  </script>\n</body>\n",
        "Gaius touch controls script",
    )


GAIUS_WORLD_ARCHIVE_MARKER = 'data-gaius-world-archive="v1"'


def apply_gaius_world_archive(text: str) -> str:
    """Install the world archive script (port/web/launcher/world-archive.js).

    Self-contained like the touch controls: it defines window.__gaiusWorldArchive, which the
    world selection screen (BrowserWorldTransfer) uses to export a world as a Java Edition save
    .zip and to import one, and does nothing until the game calls it.
    """
    if GAIUS_WORLD_ARCHIVE_MARKER in text:
        return text
    launcher = Path(__file__).resolve().parents[1] / "web" / "launcher"
    script = (launcher / "world-archive.js").read_text(encoding="utf-8")
    if "</script" in script.lower():
        raise RuntimeError("world archive script must not contain closing script tags")
    return replace_required(
        text,
        "</body>\n",
        "  <script " + GAIUS_WORLD_ARCHIVE_MARKER + ">\n" + script + "  </script>\n</body>\n",
        "Gaius world archive script",
    )


def apply_gaius_client_shell(text: str) -> str:
    """Install the stable browser-client shell without changing game contracts."""
    if GAIUS_SHELL_MARKER in text:
        return text

    text = replace_required(
        text,
        "  </style>\n",
        GAIUS_SHELL_CSS + "  </style>\n",
        "Gaius Client shell v2 CSS",
    )

    shell_markup = '''  <div id="gaius-shell-header" data-gaius-shell="v2" aria-hidden="true">
    <div id="gaius-shell-brand"><strong>GAIUS</strong><span>CLIENT</span></div>
    <span id="gaius-shell-mode">BROWSER CLIENT</span>
    <span id="gaius-shell-version">VERSION DEV</span>
  </div>
'''
    brand_pattern = re.compile(
        r'(?m)^  <div id="boot-brand"[^>]*>.*?</div>\n',
        flags=re.DOTALL,
    )
    text, brand_count = brand_pattern.subn(
        lambda match: match.group(0) + shell_markup,
        text,
        count=1,
    )
    if brand_count != 1:
        raise RuntimeError("index.html patch point was not found: Gaius shell header")

    profile_title = '''      <h1 id="profile-title">GAIUS</h1>
      <p id="profile-kicker">Browser client | offline and online play</p>
      <p id="profile-subtitle">Choose a player name to continue.</p>
'''
    title_pattern = re.compile(
        r'(?m)^      <h1 id="profile-title">.*?</h1>\n',
        flags=re.DOTALL,
    )
    text, title_count = title_pattern.subn(profile_title, text, count=1)
    if title_count != 1:
        raise RuntimeError("index.html patch point was not found: Gaius profile heading")

    status_pattern = re.compile(
        r'(?ms)^  <pre id="status"[^>]*>.*?</pre>\n',
    )
    error_markup = '''  <div id="gaius-error-actions" hidden>
    <button id="gaius-retry" type="button">Retry startup</button>
    <button id="gaius-error-toggle" type="button">Show diagnostics</button>
  </div>
  <pre id="gaius-error-details" hidden></pre>
  <div id="gaius-shell-footer" data-gaius-shell="v2" aria-hidden="true">
    <span><strong>Gaius Client</strong> | independent browser software</span>
    <span>HTML runtime | local storage enabled</span>
  </div>
'''
    text, status_count = status_pattern.subn(
        lambda match: match.group(0) + error_markup,
        text,
        count=1,
    )
    if status_count != 1:
        raise RuntimeError("index.html patch point was not found: Gaius error actions")

    text = text.replace(
        "Gaius is an independent project and is not affiliated with Mojang Studios or Microsoft.",
        "Gaius is an independent browser client. It is not affiliated with Mojang Studios or Microsoft.",
        1,
    )
    text = text.replace(
        'script.onerror = () => reject(new Error("无法加载 " + src));',
        'script.onerror = () => reject(new Error("Could not load " + src));',
        1,
    )
    text = replace_required(
        text,
        "</body>\n",
        GAIUS_SHELL_SCRIPT + "</body>\n",
        "Gaius Client shell v2 controller",
    )
    return text


SHADER_TOOLCHAIN_MARKER = "data-gaius-shader-toolchain"
SHADER_TOOLCHAIN_FILES = (
    "gaius-shader-toolchain.js",
    "gaius-shaderc.js",
    "gaius-shaderc.wasm",
    "gaius-spvc.js",
    "gaius-spvc.wasm",
)


def classes_need_shader_toolchain(classes_js: Path) -> bool:
    """Whether the TeaVM client calls the browser shader toolchain (PLAN D5).

    BrowserShadercWasm and BrowserSpvcWasm reach it through
    window.__gaiusShaderToolchain, and TeaVM copies JSBody scripts verbatim, so
    the name is in classes.js exactly when the profile's client compiles
    shaders with shaderc/spvc (26.3 and later; never 26.2).
    """
    try:
        with classes_js.open("rb") as stream:
            tail = b""
            for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
                if b"__gaiusShaderToolchain" in tail + chunk:
                    return True
                tail = chunk[-64:]
    except OSError:
        return False
    return False


def patch_shader_toolchain_loader(text: str, classes_js: Path, index: Path, profile_id: str) -> str:
    """Loads the WebAssembly shader toolchain before main(args) (contract C7).

    Only for a client that needs it: the page loads gaius-shader-toolchain.js
    (port/wasm/shader-toolchain/loader), which sets the Promise
    window.__gaiusShaderToolchainReady, and the boot sequence awaits it right
    before it calls the TeaVM main.  The five toolchain files must sit next to
    index.html (build-wasm-shader-toolchain.sh --dist); a missing file is a
    warning, or an error when GAIUS_SHADER_TOOLCHAIN_STRICT=1 (releases).
    The profile id names the loader's IndexedDB result cache.
    """
    # WasmGC pages carry the WebGPU toolchain as a boot-managed optional asset.
    # The boot script loads it only when WebGPU is selected; inserting the old
    # JavaScript-client gate here would make every WasmGC WebGL2 boot require a
    # loader that build-pages-site deliberately removes from the document head.
    if classes_js.name == "classes.wasm" or not classes_need_shader_toolchain(classes_js):
        return text
    directory = index.parent
    missing = [name for name in SHADER_TOOLCHAIN_FILES if not (directory / name).is_file()]
    if missing:
        message = (
            "the client needs the WebAssembly shader toolchain but "
            + ", ".join(missing)
            + f" {'is' if len(missing) == 1 else 'are'} missing from {directory} "
            "(run port/scripts/build-wasm-shader-toolchain.sh --dist <dist>)"
        )
        if os.environ.get("GAIUS_SHADER_TOOLCHAIN_STRICT", "").strip() == "1":
            raise RuntimeError(message)
        print(f"warning: {message}", file=sys.stderr)
    token = content_token(*(directory / name for name in SHADER_TOOLCHAIN_FILES))
    loader = (
        f'  <script {SHADER_TOOLCHAIN_MARKER} data-profile="{profile_id}" '
        f'src="gaius-shader-toolchain.js?v={token}"></script>\n'
    )
    text = re.sub(
        rf'  <script {SHADER_TOOLCHAIN_MARKER}[^>]*></script>\n',
        "",
        text,
    )
    text = replace_required(text, "</head>\n", loader + "</head>\n", "shader toolchain loader")
    if "window.__gaiusShaderToolchainReady" not in text.split("</head>", 1)[1]:
        text = replace_required(
            text,
            "      bootTimings.vanillaAssetsReady = performance.now();\n"
            "      if (typeof main !== \"function\") {\n",
            "      bootTimings.vanillaAssetsReady = performance.now();\n"
            "      if (!window.__gaiusShaderToolchainReady) {\n"
            "        throw new Error(\"gaius-shader-toolchain.js did not load; this client cannot compile shaders without it\");\n"
            "      }\n"
            "      setBootProgress(Math.max(bootProgressValue, 64), \"Starting the shader compiler...\");\n"
            "      await window.__gaiusShaderToolchainReady;\n"
            "      bootTimings.shaderToolchainReady = performance.now();\n"
            "      if (typeof main !== \"function\") {\n",
            "shader toolchain boot gate",
        )
    return text


def validate_storage_profile(profile: dict) -> dict:
    profile_id = profile.get("id")
    if not isinstance(profile_id, str) or not profile_id:
        raise RuntimeError("version profile id must be a non-empty string")
    world_version = profile.get("worldVersion")
    if not isinstance(world_version, int) or isinstance(world_version, bool) or world_version < 0:
        raise RuntimeError(f"version profile {profile_id} has an invalid worldVersion")
    storage = profile.get("storage")
    if not isinstance(storage, dict):
        raise RuntimeError(f"version profile {profile_id} storage must be an object")
    schema = storage.get("schema")
    if not isinstance(schema, int) or isinstance(schema, bool) or schema != 2:
        raise RuntimeError(
            f"version profile {profile_id} storage.schema must be exactly 2 "
            f"(received {schema!r})"
        )
    expected = {
        "databaseName": f"gaius-fs-v2-{profile_id}",
        "prefix": f"gaius.fs.v2:{profile_id}:",
        "opfsDirectory": f"regions-v2-{profile_id}",
    }
    for key, expected_value in expected.items():
        value = storage.get(key)
        if not isinstance(value, str) or not value:
            raise RuntimeError(f"version profile {profile_id} storage.{key} must be non-empty")
        if value != expected_value:
            raise RuntimeError(
                f"version profile {profile_id} storage.{key} must be exactly "
                f"{expected_value!r} (received {value!r})"
            )
    return storage


def load_profile_for_version(minecraft_version: str) -> dict:
    root = Path(__file__).resolve().parents[2]
    versions_directory = (root / "port" / "versions").resolve()
    candidates: list[Path] = []
    configured = os.environ.get("GAIUS_VERSION_PROFILE_PATH", "").strip()
    if configured:
        configured_path = native_external_path(configured.replace("\\", "/"))
        if not configured_path.is_absolute():
            configured_path = root / "port" / configured_path
        candidates.append(configured_path)
    config_path = root / "port" / "config.json"
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        relative = config.get("versionProfile")
        if isinstance(relative, str) and relative:
            candidates.append(root / "port" / relative)
    except (OSError, ValueError, TypeError):
        pass
    candidates.extend(sorted(versions_directory.glob("*.json")))
    seen: set[Path] = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate in seen or not candidate.is_file():
            continue
        seen.add(candidate)
        try:
            profile = json.loads(candidate.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            continue
        if isinstance(profile, dict) and profile.get("id") == minecraft_version:
            validate_storage_profile(profile)
            return profile
    raise RuntimeError(f"version profile is missing for {minecraft_version}")


def patch_storage_persistence(text: str, profile: dict) -> str:
    """Apply the profile storage namespace without touching legacy data."""
    storage = validate_storage_profile(profile)
    schema = int(storage["schema"])
    prefix = json.dumps(storage["prefix"], ensure_ascii=True)
    database_name = json.dumps(storage["databaseName"], ensure_ascii=True)
    opfs_directory = json.dumps(storage["opfsDirectory"], ensure_ascii=True)

    # The persistence bootstrap is generated into dist/index.html. Restrict
    # replacements to that IIFE so unrelated launcher variables named `prefix`
    # cannot be changed accidentally.
    marker = "(function installPersistentFsBootstrap() {"
    start = text.find(marker)
    if start >= 0:
        end = text.find("</script>", start)
        if end < 0:
            raise RuntimeError("index.html persistence bootstrap is unterminated")
        end += len("</script>")
        block = text[start:end]
        block, prefix_count = re.subn(
            r'(\b(?:const|let|var)\s+prefix\s*=\s*)["\'][^"\']*["\']\s*;',
            rf'\g<1>{prefix};',
            block,
            count=1,
        )
        block, database_count = re.subn(
            r'(\b(?:const|let|var)\s+dbName\s*=\s*)["\'][^"\']*["\']\s*;',
            rf'\g<1>{database_name};',
            block,
            count=1,
        )
        block, opfs_count = re.subn(
            r'(\b(?:const|let|var)\s+opfsDirectory\s*=\s*)["\'][^"\']*["\']\s*;',
            rf'\g<1>{opfs_directory};',
            block,
            count=1,
        )
        if opfs_count == 0 and database_count:
            block = re.sub(
                r'(?m)^(\s*(?:const|let|var)\s+dbName\s*=.*\n)',
                rf'\1      const opfsDirectory = {opfs_directory};\n',
                block,
                count=1,
            )
        block = re.sub(
            r'(indexedDB\.open\(\s*dbName\s*,\s*)\d+(\s*\))',
            rf'\g<1>{schema}\g<2>',
            block,
        )
        text = text[:start] + block + text[end:]
        if prefix_count == 0 or database_count == 0:
            # A future bootstrap may use profile globals instead of local
            # constants. The globals below remain the authoritative contract.
            pass

    globals_block = (
        '  <script data-gaius-storage-profile="v2">\n'
        f'    window.__gaiusVersion = {json.dumps(normalize_release_version((Path(__file__).resolve().parents[2] / "VERSION").read_text(encoding="utf-8")), ensure_ascii=True)};\n'
        f'    window.__gaiusProfileId = {json.dumps(profile["id"], ensure_ascii=True)};\n'
        f'    window.__gaiusWorldVersion = {int(profile["worldVersion"])};\n'
        f'    window.__gaiusStorageSchema = {schema};\n'
        f'    window.__gaiusStorageDatabaseName = {database_name};\n'
        f'    window.__gaiusStoragePrefix = {prefix};\n'
        f'    window.__gaiusStorageOpfsDirectory = {opfs_directory};\n'
        '  </script>\n'
    )
    script_pattern = re.compile(
        r'  <script data-gaius-storage-profile="v2">.*?</script>\n',
        flags=re.DOTALL,
    )
    # These globals are consumed by the persistence bootstrap itself and by
    # BrowserFilePersistence during Java class initialization.  Put them before
    # that IIFE rather than at the end of <body>; an async boot can otherwise
    # observe an unconfigured profile before the trailing script executes.
    text = script_pattern.sub("", text)
    persistence = text.find(marker)
    insertion = text.rfind("<script", 0, persistence) if persistence >= 0 else -1
    if insertion < 0:
        raise RuntimeError("index.html persistence bootstrap script was not found")
    text = text[:insertion] + globals_block + text[insertion:]
    return patch_early_profile_id(text, profile)


EARLY_PROFILE_MARKER = 'data-gaius-profile-early="v1"'
EARLY_PROFILE_PATTERN = re.compile(
    r'  <script data-gaius-profile-early="v1">\n.*?  </script>\n',
    flags=re.DOTALL,
)
BOOT_SCRIPT_PREFIX = '  <script data-gaius-boot="v1"'


def patch_early_profile_id(text: str, profile: dict) -> str:
    """Sets window.__gaiusProfileId, __gaiusRenderSeam and __gaiusPatchSet in <head>, before the
    boot script tag.

    The graphics quality layer that gaius-boot.js loads (or a portable page inlines in place of
    that tag) caps the canvas ratio by the profile's render seam generation, and on a portable
    page it runs before the storage globals in <body> exist. Page code decides by these profile
    fields, never by comparing version ids. A page from an older template gets the block
    inserted.
    """
    block = (
        f'  <script {EARLY_PROFILE_MARKER}>\n'
        "    // The graphics quality layer the boot script loads caps the canvas ratio per profile, so the\n"
        "    // profile id must exist before any of it runs (postprocess-index-html.py fills it in).\n"
        f'    window.__gaiusProfileId = {json.dumps(profile["id"], ensure_ascii=True)};\n'
        f'    window.__gaiusRenderSeam = {json.dumps(profile.get("renderSeam") or "none", ensure_ascii=True)};\n'
        f'    window.__gaiusPatchSet = {json.dumps(profile.get("patchSet") or "", ensure_ascii=True)};\n'
        f'    window.__gaiusTerrainSectionUniformBlock = {json.dumps(profile.get("terrainSectionUniformBlock", False))};\n'
        # Release default of the browser worldgen kernel; ?worldgenKernel= still overrides it.
        f'    window.__gaiusWorldgenKernelDefault = {json.dumps(profile.get("worldgenKernelDefault", True) is not False)};\n'
        "  </script>\n"
    )
    text = EARLY_PROFILE_PATTERN.sub("", text)
    head_end = text.find("</head>")
    if head_end < 0:
        raise RuntimeError("index.html has no </head> for the early profile id")
    boot = text.find(BOOT_SCRIPT_PREFIX, 0, head_end)
    insertion = boot if boot >= 0 else head_end
    return text[:insertion] + block + text[insertion:]


RUNTIME_CONFIG_MARKER = 'data-gaius-runtimes="v1"'
RUNTIME_CONFIG_PATTERN = re.compile(
    r'  <script data-gaius-runtimes="v1">\n.*?  </script>\n', re.DOTALL
)
def runtime_config_script(
    runtimes: dict | None = None,
    file_payloads: dict | None = None,
    webgpu_shader_toolchain: dict | None = None,
) -> str:
    """The WasmGC page configuration gaius-boot.js reads at parse time.

    runtimes       window.__gaiusRuntimes, the WasmGC client/server modules
    file_payloads  window.__gaiusFilePayloads: the gzip+base64 script payloads that stand in for
                   the binary files when the dist is opened from disk (file://), as written by
                   build-portable-html.py write_file_payloads: {file: {script, bytes, gzip, sha256}}
    webgpu_shader_toolchain
                   window.__gaiusWebGpuShaderToolchain: {loader, version}, the shader toolchain
                   that gaius-boot.js loads for ?gpu=webgpu when the profile's WebGPU front-end
                   compiles GLSL in the page (make-wasm-page.py)
    """
    lines = []
    if runtimes:
        lines.append(f"    window.__gaiusRuntimes = {_inline_json(runtimes)};\n")
    if file_payloads:
        lines.append(f"    window.__gaiusFilePayloads = {_inline_json(file_payloads)};\n")
    if webgpu_shader_toolchain:
        lines.append(
            f"    window.__gaiusWebGpuShaderToolchain = {_inline_json(webgpu_shader_toolchain)};\n")
    return f"  <script {RUNTIME_CONFIG_MARKER}>\n" + "".join(lines) + "  </script>\n"


def _inline_json(value: object) -> str:
    text = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    # Inline in a classic script: no element end tag can appear.
    return text.replace("</", "<\\/")


def apply_runtime_config(text: str, script: str) -> str:
    """Puts runtime config before boot, or at the end of head for older page shells."""
    text = RUNTIME_CONFIG_PATTERN.sub("", text)
    head_end = text.find("</head>")
    boot = text.find(BOOT_SCRIPT_PREFIX, 0, head_end if head_end >= 0 else len(text))
    if boot < 0:
        if head_end < 0:
            raise RuntimeError("index.html has no </head> for the runtime configuration")
        boot = head_end
    return text[:boot] + script + text[boot:]


def profile_from_index(text: str) -> str:
    match = re.search(r'window\.__gaiusProfileId\s*=\s*"([^"\\]+)"\s*;', text)
    if not match:
        raise RuntimeError("index.html has no __gaiusProfileId")
    return match.group(1)



def patch_index(
    index: Path,
    classes_js: Path,
    minecraft_version: str,
    asset_index_id: str | None = None,
    profile: dict | None = None,
) -> bool:
    asset_index_id = asset_index_id or minecraft_version
    build_token = content_token(classes_js)
    vanilla_assets_token = content_token(index.parent / "vanilla-assets.pack.gz")
    singleplayer_token = content_token(
        index.parent / "singleplayer-server-worker.js",
        index.parent / "singleplayer-server.js",
    )
    text = index.read_text(encoding="utf-8")
    original = text
    text = migrate_text_shader_diagnostics(text)
    selected_profile = profile or load_profile_for_version(minecraft_version)
    # The historical replacement templates below are intentionally written
    # against IndexedDB version 1. Normalize an already profile-patched page
    # before applying those idempotent migrations; patch_storage_persistence()
    # restores the selected profile schema at the end.
    text = re.sub(
        r"indexedDB\.open\(\s*dbName\s*,\s*\d+\s*\)",
        "indexedDB.open(dbName, 1)",
        text,
    )

    vanilla_assets_loader = '''    const vanillaAssetsToken = "__VANILLA_ASSETS_TOKEN__";
    function hasGaiusVanillaAssetsMagic(bytes) {
      const magic = "GAIUSVP1";
      if (!(bytes instanceof Uint8Array) || bytes.length < 12) return false;
      for (let index = 0; index < magic.length; index++) {
        if (bytes[index] !== magic.charCodeAt(index)) return false;
      }
      return true;
    }

    async function gunzipGaiusVanillaAssets(bytes) {
      if (hasGaiusVanillaAssetsMagic(bytes)) return bytes;
      if (typeof DecompressionStream !== "function") {
        throw new Error("This browser cannot decompress the Gaius vanilla asset pack");
      }
      const stream = new Blob([bytes])
        .stream()
        .pipeThrough(new DecompressionStream("gzip"));
      const unpacked = new Uint8Array(await new Response(stream).arrayBuffer());
      if (!hasGaiusVanillaAssetsMagic(unpacked)) {
        throw new Error("The Gaius vanilla asset pack has an invalid header");
      }
      return unpacked;
    }

    async function decodeGaiusVanillaAssets(source) {
      const compressed = source instanceof Uint8Array
        ? source
        : new Uint8Array(await source.arrayBuffer());
      const bytes = await gunzipGaiusVanillaAssets(compressed);
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      const indexLength = view.getUint32(8, true);
      const indexStart = 12;
      const dataOffset = indexStart + indexLength;
      if (indexLength <= 0 || dataOffset > bytes.length) {
        throw new Error("The Gaius vanilla asset index is truncated");
      }
      let index;
      try {
        index = JSON.parse(new TextDecoder().decode(bytes.subarray(indexStart, dataOffset)));
      } catch (error) {
        throw new Error("The Gaius vanilla asset index is invalid: " + String(error));
      }
      if (!index || typeof index !== "object" || Array.isArray(index)) {
        throw new Error("The Gaius vanilla asset index is not an object");
      }
      const payloadLength = bytes.length - dataOffset;
      let resourceCount = 0;
      for (const name in index) {
        if (!Object.prototype.hasOwnProperty.call(index, name)) continue;
        const range = index[name];
        if (!Array.isArray(range) || range.length !== 2 ||
            !Number.isSafeInteger(range[0]) || !Number.isSafeInteger(range[1]) ||
            range[0] < 0 || range[1] < 0 || range[0] + range[1] > payloadLength) {
          throw new Error("The Gaius vanilla asset range is invalid: " + name);
        }
        resourceCount++;
      }
      if (resourceCount < 1000) {
        throw new Error("The Gaius vanilla asset pack is incomplete");
      }
      const root = {bytes, index, dataOffset, resourceCount};
      window.__gaiusVanillaAssets = root;
      bootTimings.vanillaAssetsDecoded = performance.now();
      return root;
    }

    async function loadGaiusVanillaAssets() {
      bootTimings.vanillaAssetsRequestStart = performance.now();
      const portableSource = window.__gaiusVanillaAssetsCompressedPromise;
      if (portableSource) {
        return decodeGaiusVanillaAssets(await portableSource);
      }
      const source = new URL(
        urlParams.get("vanillaAssets") || "vanilla-assets.pack.gz",
        location.href
      );
      const version = urlParams.get("fresh") === "1" || urlParams.get("cache") === "0"
        ? vanillaAssetsToken + "-fresh-" + Date.now()
        : vanillaAssetsToken;
      source.searchParams.set("v", version);
      const response = await fetch(source, {cache: "force-cache"});
      if (!response.ok) {
        throw new Error("Could not load the Gaius vanilla asset pack: HTTP " + response.status);
      }
      return decodeGaiusVanillaAssets(await response.blob());
    }

    window.__gaiusDecodeVanillaAssets = decodeGaiusVanillaAssets;
    window.__gaiusVanillaAssetsReady = window.__gaiusVanillaAssetsReady ||
      loadGaiusVanillaAssets();
    window.__gaiusVanillaAssetsReady.catch(() => {});
'''.replace("__VANILLA_ASSETS_TOKEN__", vanilla_assets_token)

    if "function decodeGaiusVanillaAssets(source)" not in text:
        text = replace_required(
            text,
            "    const urlParams = new URLSearchParams(location.search);\n",
            "    const urlParams = new URLSearchParams(location.search);\n"
            + vanilla_assets_loader,
            "vanilla asset pack loader",
        )
    else:
        text, token_count = re.subn(
            r'    const vanillaAssetsToken = "[^"]+";',
            f'    const vanillaAssetsToken = "{vanilla_assets_token}";',
            text,
            count=1,
        )
        if token_count != 1:
            raise RuntimeError("index.html patch point was not found: vanilla asset token")

    text = text.replace('<html lang="zh-CN">', '<html lang="en">', 1)
    # Keep the canvas backing ratio tied to actual browser display changes. Older generated
    # pages contained a frame-rate governor that changed __gaiusMaxDpr and therefore vanilla's
    # GUI scale input while the viewport was unchanged; remove that block from every staged page.
    text = re.sub(
        r'    // Whether the world render scale answers low frame rates:.*?'
        r'    function maybeDegradeResolutionForFps\(\) \{.*?'
        r'    \}\n    requestAnimationFrame\(function gaiusFpsTick',
        '    function maybeDegradeResolutionForFps() {\n'
        "      // Window framebuffer size is vanilla's input to auto GUI scale. Keep its DPR fixed\n"
        '      // between actual browser resize or device-pixel-ratio events.\n'
        '    }\n'
        '    requestAnimationFrame(function gaiusFpsTick',
        text,
        count=1,
        flags=re.S,
    )
    text = re.sub(
        r'    // A game-level SDL_SetWindowSize can run while a saved world is reopened\..*?'
        r'    // A backing store below the device',
        '    // A backing store below the device',
        text,
        count=1,
        flags=re.S,
    )
    text = text.replace(
        '    // render scale is unavailable, more on high and ultra. Once the tier is known (right before\n'
        '    // main) that cap replaces the 1x default above, and the frame-rate governor below steps\n'
        '    // down from it. URL overrides keep precedence; without the layer nothing changes.\n',
        '    // render scale is unavailable, more on high and ultra. Once the tier is known (right before\n'
        '    // main) that cap replaces the 1x default above. URL overrides keep precedence; without the\n'
        '    // layer nothing changes.\n',
        1,
    )
    text = re.sub(
        r'<title>Gaius (?:Minecraft|Client) [^<]+</title>',
        f'<title>Gaius Client {minecraft_version}</title>',
        text,
        count=1,
    )

    text = text.replace(
        '    const showPerfHud = urlParams.get("hud") !== "0";\n',
        '    const showPerfHud = urlParams.get("hud") === "1";\n',
    )
    if 'const showLauncherDetails = urlParams.get("debug") === "1" || showPerfHud;' not in text:
        text = replace_required(
            text,
            '    const showPerfHud = urlParams.get("hud") === "1";\n',
            '    const showPerfHud = urlParams.get("hud") === "1";\n'
            '    const showLauncherDetails = urlParams.get("debug") === "1" || showPerfHud;\n',
            "launcher detail visibility",
        )
    text = text.replace(
        '<pre id="status" data-state="running">',
        '<pre id="status" data-state="running" hidden>',
        1,
    )
    text = text.replace(
        "    const defaultMaxDpr = Math.min(1.5, rawDevicePixelRatio);\n",
        "    const defaultMaxDpr = Math.min(1.0, rawDevicePixelRatio);\n",
    )
    text = text.replace(
        "        if (nowMs - fps.worldEnteredAt < 60000) {\n",
        "        if (nowMs - fps.worldEnteredAt < 10000) {\n",
    )
    text = text.replace(
        "      if (fps.lastDprChangeAt && nowMs - fps.lastDprChangeAt < 10000) {\n",
        "      if (fps.lastDprChangeAt && nowMs - fps.lastDprChangeAt < 8000) {\n",
    )
    text = text.replace(
        "    setInterval(function gaiusFpsSample() {\n"
        "      const fps = window.__gaiusFps;\n"
        "      const measured = Number.isFinite(fps.gameFps) && fps.gameFps > 0\n"
        "        ? fps.gameFps\n"
        "        : fps.rafFps;\n"
        "      if (Number.isFinite(measured) && measured > 0) fps.fps = measured;\n"
        "      fps.lastSampleAt = performance.now();\n"
        "      maybeDegradeResolutionForFps();\n"
        "    }, 1000);\n",
        "    requestAnimationFrame(function gaiusFpsTick(now) {\n"
        "      const fps = window.__gaiusFps;\n"
        "      const inWorld = !!window.__gaiusMinecraftState?.level;\n"
        "      if (inWorld && !fps.rafMetricsWorldEnteredAt) {\n"
        "        fps.rafMetricsWorldEnteredAt = now;\n"
        "        fps.rafFrameTimes = new Float32Array(4096);\n"
        "        fps.rafFrameWriteIndex = 0;\n"
        "        fps.rafFrameCount = 0;\n"
        "        fps.rafLastFrameAt = 0;\n"
        "        fps.rafLongestFrameMs = 0;\n"
        "      } else if (!inWorld) {\n"
        "        fps.rafMetricsWorldEnteredAt = 0;\n"
        "      }\n"
        "      const previousFrameAt = fps.rafLastFrameAt;\n"
        "      if (Number.isFinite(previousFrameAt) && previousFrameAt > 0) {\n"
        "        const frameMs = now - previousFrameAt;\n"
        "        if (frameMs > 0 && frameMs <= 1000 && fps.rafMetricsWorldEnteredAt) {\n"
        "          let samples = fps.rafFrameTimes;\n"
        "          if (!(samples instanceof Float32Array) || samples.length !== 4096) {\n"
        "            samples = new Float32Array(4096);\n"
        "            fps.rafFrameTimes = samples;\n"
        "            fps.rafFrameWriteIndex = 0;\n"
        "            fps.rafFrameCount = 0;\n"
        "          }\n"
        "          const writeIndex = (Number(fps.rafFrameWriteIndex) || 0) % samples.length;\n"
        "          samples[writeIndex] = frameMs;\n"
        "          fps.rafFrameWriteIndex = (writeIndex + 1) % samples.length;\n"
        "          fps.rafFrameCount = Math.min(samples.length, (Number(fps.rafFrameCount) || 0) + 1);\n"
        "          fps.rafLongestFrameMs = Math.max(fps.rafLongestFrameMs || 0, frameMs);\n"
        "        }\n"
        "      }\n"
        "      fps.rafLastFrameAt = now;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n"
        "      if (elapsed >= 1000) {\n"
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        const samples = fps.rafFrameTimes;\n"
        "        const sampleCount = samples instanceof Float32Array\n"
        "          ? Math.min(samples.length, Number(fps.rafFrameCount) || 0)\n"
        "          : 0;\n"
        "        if (sampleCount > 0) {\n"
        "          const ordered = Array.from(samples.subarray(0, sampleCount))\n"
        "            .sort((left, right) => left - right);\n"
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n"
        "        }\n"
        "        fps.fps = fps.rafFps;\n"
        "        fps.frames = 0;\n"
        "        fps.lastSampleAt = now;\n"
        "        maybeDegradeResolutionForFps();\n"
        "      }\n"
        "      requestAnimationFrame(gaiusFpsTick);\n"
        "    });\n",
    )
    text = text.replace(
        "      const measured = Number.isFinite(fps.gameFps) && fps.gameFps > 0 ? fps.gameFps : fps.rafFps;\n",
        "      const measured = Number.isFinite(fps.rafFps) && fps.rafFps > 0 ? fps.rafFps : 0;\n",
    )
    text = text.replace(
        "      const shownFps = Number.isFinite(fps.gameFps) && fps.gameFps > 0 ? fps.gameFps : fps.rafFps;\n",
        "      const shownFps = Number.isFinite(fps.rafFps) && fps.rafFps > 0 ? fps.rafFps : 0;\n",
    )
    text = text.replace(
        "      const fps = window.__gaiusFps;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n",
        "      const fps = window.__gaiusFps;\n"
        "      const inWorld = !!window.__gaiusMinecraftState?.level;\n"
        "      if (inWorld && !fps.rafMetricsWorldEnteredAt) {\n"
        "        fps.rafMetricsWorldEnteredAt = now;\n"
        "        fps.rafFrameTimes = new Float32Array(4096);\n"
        "        fps.rafFrameWriteIndex = 0;\n"
        "        fps.rafFrameCount = 0;\n"
        "        fps.rafLastFrameAt = 0;\n"
        "        fps.rafLongestFrameMs = 0;\n"
        "      } else if (!inWorld) {\n"
        "        fps.rafMetricsWorldEnteredAt = 0;\n"
        "      }\n"
        "      const previousFrameAt = fps.rafLastFrameAt;\n"
        "      if (Number.isFinite(previousFrameAt) && previousFrameAt > 0) {\n"
        "        const frameMs = now - previousFrameAt;\n"
        "        if (frameMs > 0 && frameMs <= 1000 && fps.rafMetricsWorldEnteredAt) {\n"
        "          let samples = fps.rafFrameTimes;\n"
        "          if (!(samples instanceof Float32Array) || samples.length !== 4096) {\n"
        "            samples = new Float32Array(4096);\n"
        "            fps.rafFrameTimes = samples;\n"
        "            fps.rafFrameWriteIndex = 0;\n"
        "            fps.rafFrameCount = 0;\n"
        "          }\n"
        "          const writeIndex = (Number(fps.rafFrameWriteIndex) || 0) % samples.length;\n"
        "          samples[writeIndex] = frameMs;\n"
        "          fps.rafFrameWriteIndex = (writeIndex + 1) % samples.length;\n"
        "          fps.rafFrameCount = Math.min(samples.length, (Number(fps.rafFrameCount) || 0) + 1);\n"
        "          fps.rafLongestFrameMs = Math.max(fps.rafLongestFrameMs || 0, frameMs);\n"
        "        }\n"
        "      }\n"
        "      fps.rafLastFrameAt = now;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n",
    )
    text = text.replace(
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        fps.fps = fps.rafFps;\n",
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        const samples = fps.rafFrameTimes;\n"
        "        const sampleCount = samples instanceof Float32Array\n"
        "          ? Math.min(samples.length, Number(fps.rafFrameCount) || 0)\n"
        "          : 0;\n"
        "        if (sampleCount > 0) {\n"
        "          const ordered = Array.from(samples.subarray(0, sampleCount))\n"
        "            .sort((left, right) => left - right);\n"
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n"
        "        }\n"
        "        fps.fps = fps.rafFps;\n",
    )
    text = migrate_raf_frame_samples(text)
    text = text.replace(
        "      const lowTarget = Math.max(45, Math.min(targetFps * 0.55, targetFps - 50));\n"
        "      const recoveredTarget = Math.max(90, Math.min(targetFps, lowTarget + 30));\n",
        "      const lowTarget = Math.max(45, Math.min(targetFps * 0.70, targetFps - 20));\n"
        "      const recoveredTarget = Math.max(lowTarget + 10, Math.min(targetFps, lowTarget + 20));\n",
    )
    text = text.replace(
        "      if (fps.lowSamples < 12 || window.__gaiusMaxDpr <= minDpr) return;\n"
        "      window.__gaiusMaxDpr = Math.max(\n"
        "        minDpr,\n"
        "        1.0\n"
        "      );\n",
        "      if (fps.lowSamples < 3 || window.__gaiusMaxDpr <= minDpr) return;\n"
        "      const nextMaxDpr = Math.round((window.__gaiusMaxDpr - 0.25) * 4) / 4;\n"
        "      window.__gaiusMaxDpr = Math.max(minDpr, nextMaxDpr);\n",
    )
    text = text.replace(
        '      const wasmUrl = new URL(urlParams.get("hotpathWasm") || "gaius-hotpath.wasm", location.href);\n',
        "",
    )
    if "window.__gaiusHotpathWasmUrl || new URL" not in text:
        text = replace_required(
            text,
            "      state.readyPromise = (async () => {\n"
            "        const response = await fetch(wasmUrl, { cache: \"force-cache\" });\n",
            "      state.readyPromise = (async () => {\n"
            "        await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "        const wasmUrl = window.__gaiusHotpathWasmUrl || new URL(\n"
            "          urlParams.get(\"hotpathWasm\") || \"gaius-hotpath.wasm\",\n"
            "          location.href\n"
            "        );\n"
            "        const response = await fetch(wasmUrl, { cache: \"force-cache\" });\n",
            "portable Wasm asset URL",
        )

    if 'id="boot-screen"' not in text:
        text = replace_required(
            text,
            "  </style>\n",
            "    /* Gaius boot screen */\n"
            "    #boot-screen {\n"
            "      position: fixed;\n"
            "      inset: 0;\n"
            "      z-index: 10;\n"
            "      background: #101511;\n"
            "      pointer-events: none;\n"
            "    }\n"
            "\n"
            "    #boot-brand {\n"
            "      position: fixed;\n"
            "      left: 50%;\n"
            "      top: 38%;\n"
            "      z-index: 11;\n"
            "      transform: translate(-50%, -50%);\n"
            "      color: #fff;\n"
            "      text-align: center;\n"
            "      font: 900 72px/0.82 Arial, Helvetica, sans-serif;\n"
            "      letter-spacing: 0;\n"
            "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.28);\n"
            "      pointer-events: none;\n"
            "      animation: gaius-logo-enter 420ms ease-out both;\n"
            "    }\n"
            "\n"
            "    #boot-brand span {\n"
            "      display: block;\n"
            "      margin-top: 16px;\n"
            "      font-size: 0.28em;\n"
            "      line-height: 1;\n"
            "      letter-spacing: 0;\n"
            "    }\n"
            "\n"
            "    #boot-progress {\n"
            "      left: 50%;\n"
            "      right: auto;\n"
            "      top: 58%;\n"
            "      z-index: 11;\n"
            "      width: min(460px, calc(100vw - 48px));\n"
            "      height: 10px;\n"
            "      box-sizing: border-box;\n"
            "      padding: 2px;\n"
            "      transform: translateX(-50%);\n"
            "      border: 2px solid #fff;\n"
            "      border-radius: 0;\n"
            "      background: transparent;\n"
            "    }\n"
            "\n"
            "    #boot-progress-bar {\n"
            "      min-width: 2px;\n"
            "      background: #54d68b;\n"
            "      transition: width 160ms linear;\n"
            "    }\n"
            "\n"
            "    #boot-progress-text {\n"
            "      left: 24px;\n"
            "      right: 24px;\n"
            "      top: calc(58% + 24px);\n"
            "      z-index: 11;\n"
            "      color: #fff;\n"
            "      text-align: center;\n"
            "      font-size: 12px;\n"
            "      text-shadow: none;\n"
            "    }\n"
            "\n"
            "    #status {\n"
            "      left: 50%;\n"
            "      right: auto;\n"
            "      top: calc(58% + 56px);\n"
            "      z-index: 11;\n"
            "      width: min(640px, calc(100vw - 48px));\n"
            "      max-height: 28vh;\n"
            "      transform: translateX(-50%);\n"
            "      border: 0;\n"
            "      border-radius: 0;\n"
            "      background: transparent;\n"
            "      color: rgba(255, 255, 255, 0.9);\n"
            "      text-align: center;\n"
            "      font: 12px/1.4 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;\n"
            "    }\n"
            "\n"
            "    #status[data-state=\"error\"] {\n"
            "      padding: 14px 16px;\n"
            "      border: 2px solid rgba(255, 255, 255, 0.82);\n"
            "      background: rgba(0, 0, 0, 0.76);\n"
            "      color: #fff;\n"
            "      text-align: left;\n"
            "    }\n"
            "\n"
            "    @media (max-width: 600px) {\n"
            "      #boot-brand { font-size: 44px; }\n"
            "    }\n"
            "\n"
            "    @keyframes gaius-logo-enter {\n"
            "      from { opacity: 0; transform: translate(-50%, -46%) scale(0.96); }\n"
            "      to { opacity: 1; transform: translate(-50%, -50%) scale(1); }\n"
            "    }\n"
            "  </style>\n",
            "Gaius boot screen CSS",
        )
        text = replace_required(
            text,
            '  <canvas id="mc-canvas" tabindex="0"></canvas>\n',
            '  <canvas id="mc-canvas" tabindex="0"></canvas>\n'
            '  <div id="boot-screen" aria-hidden="true"></div>\n'
            '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>\n',
            "Gaius boot screen markup",
        )
        text = replace_required(
            text,
            '    const statusBox = document.getElementById("status");\n',
            '    const statusBox = document.getElementById("status");\n'
            '    const bootScreen = document.getElementById("boot-screen");\n'
            '    const bootBrand = document.getElementById("boot-brand");\n',
            "Gaius boot screen elements",
        )
        text = replace_required(
            text,
            "      statusBox.hidden = true;\n",
            "      statusBox.hidden = true;\n"
            "      if (bootScreen) bootScreen.hidden = true;\n"
            "      if (bootBrand) bootBrand.hidden = true;\n",
            "Gaius boot screen cleanup",
        )

    text = text.replace("/* Minecraft-style boot screen */", "/* Gaius boot screen */", 1)
    text = text.replace("      background: #ef323d;\n", "      background: #101511;\n", 1)
    text = text.replace(
        "      font: 900 clamp(42px, 8vw, 92px)/0.72 Arial, Helvetica, sans-serif;\n",
        "      font: 900 72px/0.82 Arial, Helvetica, sans-serif;\n",
        1,
    )
    text = text.replace(
        "      font: 900 72px/0.72 Arial, Helvetica, sans-serif;\n",
        "      font: 900 72px/0.82 Arial, Helvetica, sans-serif;\n",
        1,
    )
    text = text.replace(
        "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.16);\n",
        "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.28);\n"
        "      animation: gaius-logo-enter 420ms ease-out both;\n",
        1,
    )
    text = text.replace(
        '  <div id="boot-brand" aria-hidden="true">MOJANG<span>STUDIOS</span></div>',
        '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>',
        1,
    )
    text = text.replace(
        "      background: linear-gradient(90deg, #38bdf8, #22c55e);\n",
        "      background: #54d68b;\n",
        1,
    )
    text = text.replace("      background: #fff;\n", "      background: #54d68b;\n", 1)
    if "@keyframes gaius-logo-enter" not in text:
        text = replace_required(
            text,
            "  </style>\n",
            "    @keyframes gaius-logo-enter {\n"
            "      from { opacity: 0; transform: translate(-50%, -46%) scale(0.96); }\n"
            "      to { opacity: 1; transform: translate(-50%, -50%) scale(1); }\n"
            "    }\n"
            "\n"
            "    @media (max-width: 600px) {\n"
            "      #boot-brand { font-size: 44px; }\n"
            "    }\n"
            "  </style>\n",
            "Gaius boot animation",
        )

    # Minecraft's own early loading screens include the Mojang Studios splash.
    # Keep the Gaius shell above those transient screens and only reveal the
    # canvas once a real menu or world is ready.
    boot_screen_pattern = re.compile(
        r'(?P<indent>[ \t]+)if \(state && state\.screen\) \{\n'
        r'(?P=indent)  const screen = String\(state\.screen\)\.split\("\."\)\.pop\(\);\n'
        r'(?P=indent)  setBootProgress\(100, "Minecraft screen ready: " \+ screen\);\n'
        r'(?P=indent)  hideBootOverlay\(\);\n'
        r'(?P=indent)  return;\n'
        r'(?P=indent)\}'
    )
    boot_screen_replacement = (
        r'\g<indent>if (state && (state.overlay || state.screen)) {\n'
        r'\g<indent>  const overlay = state.overlay ? String(state.overlay).split(".").pop() : "";\n'
        r'\g<indent>  const screen = state.screen ? String(state.screen).split(".").pop() : "";\n'
        r'\g<indent>  const keepGaiusBootVisible = [\n'
        r'\g<indent>    "GenericMessageScreen",\n'
        r'\g<indent>    "ProgressScreen",\n'
        r'\g<indent>    "LevelLoadingScreen",\n'
        r'\g<indent>    "ReceivingLevelScreen"\n'
        r'\g<indent>  ].includes(screen) || overlay === "LoadingOverlay";\n'
        r'\g<indent>  if (keepGaiusBootVisible) {\n'
        r'\g<indent>    setBootProgress(Math.max(bootProgressValue, 92), "Loading game resources...");\n'
        r'\g<indent>    return;\n'
        r'\g<indent>  }\n'
        r'\g<indent>  if (screen) {\n'
        r'\g<indent>    setBootProgress(100, "Client screen ready: " + screen);\n'
        r'\g<indent>    hideBootOverlay();\n'
        r'\g<indent>    return;\n'
        r'\g<indent>  }\n'
        r'\g<indent>}'
    )
    if "const keepGaiusBootVisible = [" not in text:
        text, boot_screen_count = boot_screen_pattern.subn(
            boot_screen_replacement,
            text,
            count=1,
        )
        if boot_screen_count != 1:
            raise RuntimeError("index.html patch point was not found: Gaius boot screen lifetime")

    if "function showBootOverlay(label)" not in text:
        text = replace_required(
            text,
            "    function hideBootOverlay() {\n",
            "    function showBootOverlay(label) {\n"
            "      const restarting = !!(bootScreen && bootScreen.hidden);\n"
            "      if (restarting) {\n"
            "        bootProgressValue = 8;\n"
            "        bootLastProgressAt = performance.now();\n"
            "        bootWarnedStall = false;\n"
            "        if (bootProgressBar) bootProgressBar.style.width = \"8%\";\n"
            "      }\n"
            "      if (bootScreen) bootScreen.hidden = false;\n"
            "      if (bootBrand) bootBrand.hidden = false;\n"
            "      if (profileGate) profileGate.hidden = true;\n"
            "      if (bootProgress) bootProgress.hidden = false;\n"
            "      if (bootProgressText) bootProgressText.hidden = false;\n"
            "      statusBox.hidden = !showLauncherDetails;\n"
            "      statusBox.dataset.state = \"running\";\n"
            "      statusBox.textContent = label || \"Loading...\";\n"
            "    }\n"
            "\n"
            "    window.__gaiusShowBootOverlay = showBootOverlay;\n"
            "\n"
            "    function hideBootOverlay() {\n",
            "reusable Gaius loading overlay",
        )

    text = text.replace(
        "      statusBox.hidden = false;\n"
        "      statusBox.dataset.state = \"running\";\n"
        "      statusBox.textContent = label || \"Loading...\";\n",
        "      statusBox.hidden = !showLauncherDetails;\n"
        "      statusBox.dataset.state = \"running\";\n"
        "      statusBox.textContent = label || \"Loading...\";\n",
        1,
    )
    text = text.replace(
        "      statusBox.hidden = false;\n"
        "      statusBox.dataset.state = state;\n"
        "      statusBox.textContent = message;\n",
        "      statusBox.hidden = state !== \"error\" && state !== \"waiting\" && !showLauncherDetails;\n"
        "      statusBox.dataset.state = state;\n"
        "      statusBox.textContent = message;\n",
        1,
    )
    text = text.replace(
        '      if (statusBox.dataset.state !== "running" || statusBox.hidden) return;\n',
        '      if (statusBox.dataset.state !== "running") return;\n',
        1,
    )

    if "showBootOverlay(loadingLabel);" not in text:
        loading_screen_pattern = re.compile(
            r'(?P<indent>[ \t]+)if \(keepGaiusBootVisible\) \{\n'
            r'(?P=indent)  setBootProgress\(Math\.max\(bootProgressValue, 92\), '
            r'"Loading game resources\.\.\."\);\n'
        )
        loading_screen_replacement = (
            r'\g<indent>if (keepGaiusBootVisible) {\n'
            r'\g<indent>  const loadingLabel = screen === "ReceivingLevelScreen"\n'
            r'\g<indent>    ? "Connecting to world..."\n'
            r'\g<indent>    : (screen === "LevelLoadingScreen"\n'
            r'\g<indent>      ? "Loading world..."\n'
            r'\g<indent>      : "Loading game resources...");\n'
            r'\g<indent>  showBootOverlay(loadingLabel);\n'
            r'\g<indent>  setBootProgress(Math.max(bootProgressValue, 35), loadingLabel);\n'
        )
        text, loading_screen_count = loading_screen_pattern.subn(
            loading_screen_replacement,
            text,
            count=1,
        )
        if loading_screen_count != 1:
            raise RuntimeError(
                "index.html patch point was not found: reopen Gaius loading overlay"
            )

    # Launchers with the in-game Edit Profile screen (window.__gaiusProfile) have no HTML
    # player-name gate, switch button or overlay; only migrate older launchers.
    modern_profile = "window.__gaiusProfile = {" in text
    if not modern_profile and 'id="profile-gate"' not in text:
        profile_css = '''
    #profile-gate {
      position: fixed;
      inset: 0;
      z-index: 14;
      display: grid;
      place-items: center;
      box-sizing: border-box;
      padding: 32px 24px;
      background: #101511;
      color: #f4f7f5;
    }

    #profile-gate[hidden] {
      display: none;
    }

    #profile-form {
      width: min(380px, 100%);
      margin: 0;
    }

    #profile-title {
      margin: 0 0 34px;
      font: 900 54px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: center;
    }

    #profile-form label {
      display: block;
      margin-bottom: 8px;
      color: #d6ddd8;
      font: 600 13px/1.2 Arial, Helvetica, sans-serif;
    }

    #profile-name {
      width: 100%;
      height: 46px;
      box-sizing: border-box;
      padding: 0 12px;
      border: 2px solid #66756c;
      border-radius: 4px;
      outline: none;
      background: #171d19;
      color: #fff;
      font: 18px/1 Arial, Helvetica, sans-serif;
    }

    #profile-name:focus {
      border-color: #54d68b;
    }

    #profile-submit {
      width: 100%;
      height: 46px;
      margin-top: 14px;
      border: 0;
      border-radius: 4px;
      background: #54d68b;
      color: #08100b;
      font: 700 15px/1 Arial, Helvetica, sans-serif;
      cursor: pointer;
    }

    #profile-submit:hover,
    #profile-submit:focus-visible {
      background: #72e7a2;
      outline: 2px solid #fff;
      outline-offset: 2px;
    }

    #profile-error {
      min-height: 18px;
      margin: 8px 0 0;
      color: #ff9b9b;
      font: 12px/1.4 Arial, Helvetica, sans-serif;
    }

    #profile-legal {
      margin: 24px 0 0;
      color: #9eaaa2;
      font: 11px/1.45 Arial, Helvetica, sans-serif;
      text-align: center;
    }
'''
        text = replace_required(
            text,
            "  </style>\n",
            profile_css + "  </style>\n",
            "player-name gate CSS",
        )
        text = replace_required(
            text,
            '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>\n',
            '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>\n'
            '  <div id="profile-gate" hidden>\n'
            '    <form id="profile-form" novalidate>\n'
            '      <h1 id="profile-title">GAIUS</h1>\n'
            '      <label for="profile-name">Player name</label>\n'
            '      <input id="profile-name" name="username" type="text" maxlength="16" '
            'pattern="[A-Za-z0-9_]{1,16}" autocomplete="username" spellcheck="false">\n'
            '      <button id="profile-submit" type="submit">Play</button>\n'
            '      <p id="profile-error" role="alert"></p>\n'
            '      <p id="profile-legal">Gaius is an independent project and is not affiliated '
            'with Mojang Studios or Microsoft.</p>\n'
            '    </form>\n'
            '  </div>\n',
            "player-name gate markup",
        )

    if not modern_profile and 'const profileGate = document.getElementById("profile-gate");' not in text:
        text = replace_required(
            text,
            '    const bootBrand = document.getElementById("boot-brand");\n',
            '    const bootBrand = document.getElementById("boot-brand");\n'
            '    const profileGate = document.getElementById("profile-gate");\n'
            '    const profileForm = document.getElementById("profile-form");\n'
            '    const profileName = document.getElementById("profile-name");\n'
            '    const profileError = document.getElementById("profile-error");\n',
            "player-name gate elements",
        )

    if not modern_profile and 'id="profile-switch"' not in text:
        profile_switch_css = '''
    #profile-switch {
      position: fixed;
      left: 16px;
      top: 16px;
      z-index: 9;
      min-height: 36px;
      box-sizing: border-box;
      padding: 0 14px;
      border: 1px solid #7b8980;
      border-radius: 4px;
      background: rgba(16, 21, 17, 0.94);
      color: #f4f7f5;
      font: 600 13px/1 Arial, Helvetica, sans-serif;
      cursor: pointer;
    }

    #profile-switch[hidden] {
      display: none;
    }

    #profile-switch:hover,
    #profile-switch:focus-visible {
      border-color: #54d68b;
      background: #1b241e;
      outline: 2px solid #fff;
      outline-offset: 2px;
    }

    #profile-switch:disabled {
      cursor: wait;
      opacity: 0.65;
    }
'''
        text = replace_required(
            text,
            "  </style>\n",
            profile_switch_css + "  </style>\n",
            "player-name switch CSS",
        )
        text = replace_required(
            text,
            '  <pre id="status" data-state="running" hidden>',
            '  <button id="profile-switch" type="button" hidden>'
            'Change player name</button>\n'
            '  <pre id="status" data-state="running" hidden>',
            "player-name switch button",
        )

    if not modern_profile and 'const profileSwitch = document.getElementById("profile-switch");' not in text:
        text = replace_required(
            text,
            '    const profileError = document.getElementById("profile-error");\n',
            '    const profileError = document.getElementById("profile-error");\n'
            '    const profileSwitch = document.getElementById("profile-switch");\n',
            "player-name switch element",
        )
    if not modern_profile and "if (profileGate) profileGate.hidden = true;" not in text:
        text = replace_required(
            text,
            "      if (bootBrand) bootBrand.hidden = true;\n",
            "      if (bootBrand) bootBrand.hidden = true;\n"
            "      if (profileGate) profileGate.hidden = true;\n",
            "player-name gate cleanup",
        )

    # Existing options are user data. Older launchers deleted options.txt during
    # IndexedDB preload, which made every video-setting change look unsaved.
    text = text.replace(
        '          installBrowserPerformanceOptions("indexeddb browser performance profile");\n',
        "",
    )
    text = text.replace(
        '          installBrowserPerformanceOptions("localStorage browser performance profile");\n',
        "",
    )
    text = re.sub(
        r'\n      function installBrowserPerformanceOptions\(reason\) \{.*?\n      \}\n',
        "\n",
        text,
        count=1,
        flags=re.DOTALL,
    )

    if '<link rel="icon" href="data:,">' not in text:
        text, favicon_count = re.subn(
            r'(  <meta name="viewport" content="[^"]*">\n)',
            lambda match: match.group(1) + '  <link rel="icon" href="data:,">\n',
            text,
            count=1,
        )
        if favicon_count != 1:
            raise RuntimeError("index.html patch point was not found: favicon")

    if "__gaiusBootTimings" not in text:
        text = replace_required(
            text,
            '    const perfHud = document.getElementById("perf-hud");\n',
            '    const perfHud = document.getElementById("perf-hud");\n'
            '    const bootTimings = window.__gaiusBootTimings = {\n'
            '      pageStart: performance.now()\n'
            '    };\n',
            "boot timings declaration",
        )

    if "bootTimings.classesLoaded" not in text:
        text = replace_required(
            text,
            "        script.onload = resolve;\n",
            "        script.onload = () => {\n"
            "          bootTimings.classesLoaded = performance.now();\n"
            "          resolve();\n"
            "        };\n",
            "script load timing",
        )

    if "function waitForPaint()" not in text:
        text = replace_required(
            text,
            "    function loadScript(src) {\n"
            "      return new Promise((resolve, reject) => {\n"
            "        const script = document.createElement(\"script\");\n"
            "        script.src = src;\n"
            "        script.onload = () => {\n"
            "          bootTimings.classesLoaded = performance.now();\n"
            "          resolve();\n"
            "        };\n"
            "        script.onerror = () => reject(new Error(\"无法加载 \" + src));\n"
            "        document.body.appendChild(script);\n"
            "      });\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "    function loadScript(src) {\n"
            "      return new Promise((resolve, reject) => {\n"
            "        const script = document.createElement(\"script\");\n"
            "        script.src = src;\n"
            "        script.onload = () => {\n"
            "          bootTimings.classesLoaded = performance.now();\n"
            "          resolve();\n"
            "        };\n"
            "        script.onerror = () => reject(new Error(\"无法加载 \" + src));\n"
            "        document.body.appendChild(script);\n"
            "      });\n"
            "    }\n"
            "\n"
            "    function waitForPaint() {\n"
            "      return new Promise(resolve => {\n"
            "        requestAnimationFrame(() => setTimeout(resolve, 0));\n"
            "      });\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "paint yield helper",
        )

    if "async function acquireGaiusRuntimeLease()" not in text:
        text = replace_required(
            text,
            "    function waitForPaint() {\n"
            "      return new Promise(resolve => {\n"
            "        requestAnimationFrame(() => setTimeout(resolve, 0));\n"
            "      });\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "    function waitForPaint() {\n"
            "      return new Promise(resolve => {\n"
            "        requestAnimationFrame(() => setTimeout(resolve, 0));\n"
            "      });\n"
            "    }\n"
            "\n"
            "    // One Gaius runtime per browser profile: a Web Lock, or a localStorage lease where Web Locks\n"
            "    // are missing. A page that replaces the runtime in the same tab (reload, navigation) can ask\n"
            "    // before the old document's lock is gone: the old page releases it on pagehide, but a lock\n"
            "    // also lives until its document is destroyed, which may come later. So a lease held elsewhere\n"
            "    // is waited for (runtimeLeaseHandoffMs) before the page reports another tab; a tab that keeps\n"
            "    // running keeps its lease and the wait ends with that error.\n"
            "    const runtimeLeaseHandoffMs = 30000;\n"
            "    function releaseGaiusRuntimeLeaseOnPagehide(release, cancel) {\n"
            "      // A browser reload destroys the document immediately.  Ask the integrated server to save\n"
            "      // before releasing the lock so the replacement page does not race its OPFS handle.\n"
            "      let lifecycleStopPromise = null;\n"
            "      let documentResourcesTerminated = false;\n"
            "      function terminateGaiusDocumentResources(reason) {\n"
            "        if (documentResourcesTerminated) return;\n"
            "        documentResourcesTerminated = true;\n"
            "        try {\n"
            "          window.__gaiusWasmTerminated = true;\n"
            "          if (typeof window.__gaiusTerminateWasm === \"function\") window.__gaiusTerminateWasm();\n"
            "        } catch (_) {}\n"
            "        try {\n"
            "          if (typeof window.__gaiusTerminateGraphics === \"function\") {\n"
            "            window.__gaiusTerminateGraphics(reason || \"unload\");\n"
            "          }\n"
            "        } catch (_) {}\n"
            "      }\n"
            "      function beginGaiusLifecycleTeardown(reason) {\n"
            "        if (lifecycleStopPromise) return;\n"
            "        cancel();\n"
            "        requestGaiusLifecycleSave(reason || \"page-lifecycle\");\n"
            "        // Release document-owned Wasm/graphics state immediately. Kernel teardown stays behind\n"
            "        // the storage barrier below because its server thread must finish save-all first.\n"
            "        terminateGaiusDocumentResources(reason || \"page-lifecycle\");\n"
            "        // Queue save-all before stop. The Worker serializes the stop behind that server-thread\n"
            "        // task, then waits for the persistent storage close barrier.\n"
            "        // Release the document-scoped lock immediately; the successor observes the saving lease.\n"
            "        // Waiting for a Worker acknowledgement here deadlocks the replacement document when\n"
            "        // renderer teardown pauses the old page. The Worker keeps its own storage close barrier.\n"
            "        lifecycleStopPromise = stopGaiusRuntimeForLeaseLoss(\"Saving before page reload...\")\n"
            "          .then(finishGaiusPagehideTeardown, closeGaiusPageStorage);\n"
            "        // Keep IndexedDB open: queued pending.then() writes still need this connection.\n"
            "        // A successor using the same version can open it; onversionchange covers upgrades.\n"
            "        // Keep the cross-document lease marked as saving until the old Worker has had the\n"
            "        // bounded shutdown window. Removing it here lets a successor open the same OPFS world\n"
            "        // before the final save reaches storage.\n"
            "        release({defer: true});\n"
            "      }\n"
            "      // Chromium reloads can deliver beforeunload/unload without pagehide. The save/stop barrier\n"
            "      // must therefore start from every teardown path, otherwise a successor can wait forever for\n"
            "      // the old singleplayer Worker and its OPFS connection.\n"
            "      addEventListener(\"beforeunload\", () => beginGaiusLifecycleTeardown(\"beforeunload\"));\n"
            "      addEventListener(\"unload\", () => beginGaiusLifecycleTeardown(\"unload\"));\n"
            "      addEventListener(\"pagehide\", () => beginGaiusLifecycleTeardown(\"pagehide\"));\n"
            "      addEventListener(\"pageshow\", event => {\n"
            "        if (event.persisted) location.reload();\n"
            "      });\n"
            "    }\n"
            "\n"
            "    // A runtime that loses its localStorage lease mid-game (another tab claimed it, or the lease\n"
            "    // can no longer be written) stops the way leaving a world does before the page reloads: each\n"
            "    // singleplayer Worker is asked to stop, which saves the world, and the page's own storage\n"
            "    // writes drain. A Worker that does not answer is given up after runtimeLeaseStopMs.\n"
            "    const runtimeLeaseStopMs = 35000;\n"
            "    async function stopGaiusRuntimeForLeaseLoss(message = \"Gaius is being reloaded. Saving and stopping here...\") {\n"
            "      if (typeof setStatus === \"function\") {\n"
            "        try {\n"
            "          setStatus(\"running\", message);\n"
            "        } catch (_) {\n"
            "          // the status line is optional\n"
            "        }\n"
            "      }\n"
            "      const workers = window.__gaiusSingleplayerWorkers;\n"
            "      const stops = [];\n"
            "      const refreshes = [];\n"
            "      if (workers && typeof workers.forEach === \"function\") {\n"
            "        workers.forEach(worker => {\n"
            "          if (!worker) return;\n"
            "          if ((worker.__gaiusTerminal || worker.__gaiusStopped) &&\n"
            "              worker.__gaiusStorageClosed === true) {\n"
            "            try { if (typeof worker.terminate === \"function\") worker.terminate(); } catch (_) {}\n"
            "            return;\n"
            "          }\n"
            "          stops.push(new Promise((resolve, reject) => {\n"
            "            if (worker.__gaiusTerminal || worker.__gaiusStopped) {\n"
            "              reject(new Error(\"Singleplayer Worker ended before persistent storage was closed\"));\n"
            "              return;\n"
            "            }\n"
            "            let timer = 0;\n"
            "            let settled = false;\n"
            "            const onMessage = event => {\n"
            "              const type = event && event.data && event.data.type;\n"
            "              if (type === \"storage-closed\") worker.__gaiusStorageClosed = true;\n"
            "              else if (type === \"stopped\") {\n"
            "                if (worker.__gaiusStorageClosed === true) done();\n"
            "                else done(new Error(\"Worker reported stopped before persistent storage close\"));\n"
            "              }\n"
            "              else if (type === \"storage-flushing\") arm(Math.min(runtimeLeaseStopMs, 15000));\n"
            "            };\n"
            "            const done = error => {\n"
            "              if (settled) return;\n"
            "              settled = true;\n"
            "              clearTimeout(timer);\n"
            "              try {\n"
            "                worker.removeEventListener(\"message\", onMessage);\n"
            "              } catch (_) {\n"
            "                // a terminated Worker has no listeners left\n"
            "              }\n"
            "              if (!error && worker.__gaiusStorageClosed === true) {\n"
            "                try { if (typeof worker.terminate === \"function\") worker.terminate(); } catch (_) {}\n"
            "              }\n"
            "              const refresh = worker.__gaiusStorageRefresh;\n"
            "              if (refresh && typeof refresh.then === \"function\") refreshes.push(Promise.resolve(refresh));\n"
            "              if (error) reject(error);\n"
            "              else resolve();\n"
            "            };\n"
            "            const arm = delay => {\n"
            "              clearTimeout(timer);\n"
            "              timer = setTimeout(() => done(new Error(\n"
            "                \"Singleplayer Worker did not confirm persistent storage close\")), delay);\n"
            "            };\n"
            "            arm(runtimeLeaseStopMs);\n"
            "            try {\n"
            "              worker.addEventListener(\"message\", onMessage);\n"
            "              worker.__gaiusStopRequested = true;\n"
            "              worker.postMessage({type: \"stop\"});\n"
            "            } catch (error) {\n"
            "              console.warn(\"Could not stop the singleplayer Worker\", error);\n"
            "              done(error);\n"
            "            }\n"
            "          }));\n"
            "        });\n"
            "      }\n"
            "      const results = await Promise.allSettled(stops);\n"
            "      const failed = results.find(result => result.status === \"rejected\");\n"
            "      if (failed) throw failed.reason;\n"
            "      // Every Worker has reported storage-closed and stopped, so page-owned kernel Wasm/SAB\n"
            "      // resources can be released before a page-side refresh or flush that may be paused at unload.\n"
            "      if (typeof window.__gaiusTerminateKernels === \"function\") {\n"
            "        try { window.__gaiusTerminateKernels(\"pagehide\"); } catch (_) {}\n"
            "      }\n"
            "      const refreshResults = await Promise.allSettled(refreshes);\n"
            "      const refreshFailed = refreshResults.find(result => result.status === \"rejected\");\n"
            "      if (refreshFailed) throw refreshFailed.reason;\n"
            "      try {\n"
            "        const flush = typeof window.__gaiusFsFlush === \"function\" ? window.__gaiusFsFlush() : null;\n"
            "        if (flush && typeof flush.then === \"function\") await flush;\n"
            "      } catch (error) {\n"
            "        throw error;\n"
            "      }\n"
            "    }\n"
            "\n"
            "    function finishGaiusPagehideTeardown() {\n"
            "      if (typeof window.__gaiusFsClose !== \"function\") return;\n"
            "      try {\n"
            "        if (typeof window.__gaiusTerminateKernels === \"function\") {\n"
            "          window.__gaiusTerminateKernels(\"page\" + \"hide\");\n"
            "        }\n"
            "      } catch (_) {}\n"
            "      closeGaiusPageStorage();\n"
            "    }\n"
            "\n"
            "    function closeGaiusPageStorage() {\n"
            "      try {\n"
            "        if (typeof window.__gaiusFsClose === \"function\") window.__gaiusFsClose();\n"
            "      } catch (_) {}\n"
            "    }\n"
            "\n"
            "    // A hidden/background page queues vanilla save-all without releasing the runtime lease.\n"
            "    let gaiusLifecycleSaveAt = 0;\n"
            "    function requestGaiusLifecycleSave(reason) {\n"
            "      const now = Date.now();\n"
            "      // visibilitychange:hidden and pagehide commonly arrive together. Coalesce that pair while\n"
            "      // still allowing a later background transition to request another save.\n"
            "      if (now - gaiusLifecycleSaveAt < 500) return;\n"
            "      gaiusLifecycleSaveAt = now;\n"
            "      const workers = window.__gaiusSingleplayerWorkers;\n"
            "      if (!workers || typeof workers.forEach !== \"function\") return;\n"
            "      workers.forEach(worker => {\n"
            "        if (!worker || worker.__gaiusTerminal || worker.__gaiusStopped) return;\n"
            "        try {\n"
            "          worker.postMessage({type: \"save-all\", reason: String(reason || \"page-lifecycle\")});\n"
            "        } catch (error) {\n"
            "          const events = window.__gaiusMinecraftEvents ||\n"
            "            (window.__gaiusMinecraftEvents = []);\n"
            "          events.push({\n"
            "            event: \"singleplayer:save-all-request-error\",\n"
            "            detail: String(error && (error.message || error) || error),\n"
            "            at: now\n"
            "          });\n"
            "          if (events.length > 500) events.splice(0, events.length - 500);\n"
            "        }\n"
            "      });\n"
            "    }\n"
            "    if (typeof document !== \"undefined\" && document.addEventListener) {\n"
            "      document.addEventListener(\"visibilitychange\", () => {\n"
            "        if (document.visibilityState === \"hidden\") requestGaiusLifecycleSave(\"visibilitychange\");\n"
            "      });\n"
            "    }\n"
            "\n"
            "    async function acquireGaiusRuntimeLease() {\n"
            "      if (window.__gaiusRuntimeLeaseHeld) {\n"
            "        return true;\n"
            "      }\n"
            "      let active = true;\n"
            "      let released = false;\n"
            "      let releaseLease = () => {};\n"
            "      const release = (options = {}) => {\n"
            "        if (released) return;\n"
            "        released = true;\n"
            "        active = false;\n"
            "        window.__gaiusRuntimeLeaseHeld = false;\n"
            "        try {\n"
            "          releaseLease(options.defer === true);\n"
            "        } catch (error) {\n"
            "          console.warn(\"Could not release the runtime lease\", error);\n"
            "        }\n"
            "      };\n"
            "      const cancel = () => {\n"
            "        active = false;\n"
            "        window.__gaiusRuntimeLeaseHeld = false;\n"
            "      };\n"
            "      releaseGaiusRuntimeLeaseOnPagehide(release, cancel);\n"
            "      window.__gaiusReleaseRuntimeLease = release;\n"
            "      const lockName = \"gaius-client-runtime-v1\";\n"
            "      // Web Locks are document-scoped. Chromium may defer the resolver microtask until the old\n"
            "      // renderer is destroyed, so a reload can leave the successor waiting for tens of seconds.\n"
            "      // The synchronous localStorage owner/heartbeat path below is the reload-safe lease.\n"
            "      const useWebLocks = false;\n"
            "      if (useWebLocks && navigator.locks && typeof navigator.locks.request === \"function\") {\n"
            "        const requestLock = options => new Promise(resolve => {\n"
            "          let settled = false;\n"
            "          const finish = value => {\n"
            "            if (settled) return;\n"
            "            settled = true;\n"
            "            resolve(value);\n"
            "          };\n"
            "          try {\n"
            "            navigator.locks.request(lockName, options, lock => {\n"
            "              if (!lock || !active) {\n"
            "                finish(false);\n"
            "                return;\n"
            "              }\n"
            "              window.__gaiusRuntimeLeaseHeld = true;\n"
            "              finish(true);\n"
            "              return new Promise(release => {\n"
            "                releaseLease = release;\n"
            "              });\n"
            "            }).catch(error => {\n"
            "              if (options.signal && options.signal.aborted) {\n"
            "                finish(false);\n"
            "                return;\n"
            "              }\n"
            "              console.warn(\"Web Locks runtime lease is unavailable\", error);\n"
            "              finish(null);\n"
            "            });\n"
            "          } catch (error) {\n"
            "            console.warn(\"Web Locks runtime lease failed\", error);\n"
            "            finish(null);\n"
            "          }\n"
            "        });\n"
            "        let acquired = await requestLock({mode: \"exclusive\", ifAvailable: true});\n"
            "        if (!active) return false;\n"
            "        if (acquired === false && typeof AbortController === \"function\") {\n"
            "          bootTimings.runtimeLeaseWaitStart = performance.now();\n"
            "          if (typeof setStatus === \"function\") {\n"
            "            try {\n"
            "              setStatus(\"waiting\", \"Waiting for the previous session to save before starting Gaius...\", 8, \"Waiting for the previous session to save...\");\n"
            "            } catch (_) {}\n"
            "          }\n"
            "          const handoff = new AbortController();\n"
            "          releaseLease = () => handoff.abort();\n"
            "          const timer = setTimeout(() => handoff.abort(), runtimeLeaseHandoffMs);\n"
            "          acquired = await requestLock({mode: \"exclusive\", signal: handoff.signal});\n"
            "          clearTimeout(timer);\n"
            "        }\n"
            "        if (!active) return false;\n"
            "        if (acquired !== null) return acquired;\n"
            "      }\n"
            "\n"
            "      const leaseKey = \"gaius.runtimeLease.v1\";\n"
            "      const leaseStaleMs = 10000;\n"
            "      const leaseId = (crypto.randomUUID ? crypto.randomUUID() :\n"
            "        Date.now().toString(36) + Math.random().toString(36).slice(2));\n"
            "      let storage;\n"
            "      const readLease = () => {\n"
            "        try {\n"
            "          return JSON.parse(storage.getItem(leaseKey) || \"null\");\n"
            "        } catch (error) {\n"
            "          return null;\n"
            "        }\n"
            "      };\n"
            "      const writeLease = () => {\n"
            "        storage.setItem(leaseKey, JSON.stringify({id: leaseId, at: Date.now()}));\n"
            "      };\n"
            "      const heldElsewhere = () => {\n"
            "        const current = readLease();\n"
            "        if (!current || current.id === leaseId) return false;\n"
            "        const holdMs = current.saving === true ? runtimeLeaseStopMs : leaseStaleMs;\n"
            "        return Date.now() - Number(current.at || 0) < holdMs;\n"
            "      };\n"
            "      let heartbeat;\n"
            "      releaseLease = (defer = false) => {\n"
            "        clearInterval(heartbeat);\n"
            "        const owned = readLease();\n"
            "        if (!owned || owned.id !== leaseId) return;\n"
            "        if (defer) {\n"
            "          storage.setItem(leaseKey, JSON.stringify({id: leaseId, at: Date.now(), saving: true}));\n"
            "          setTimeout(() => {\n"
            "            const pending = readLease();\n"
            "            if (pending && pending.id === leaseId && pending.saving === true) storage.removeItem(leaseKey);\n"
            "          }, runtimeLeaseStopMs);\n"
            "          return;\n"
            "        }\n"
            "        storage.removeItem(leaseKey);\n"
            "      };\n"
            "      let stopping = null;\n"
            "      const yieldRuntime = () => {\n"
            "        release();\n"
            "        if (!stopping) {\n"
            "          stopping = stopGaiusRuntimeForLeaseLoss().catch(error => {\n"
            "            console.warn(\"Could not stop Gaius cleanly\", error);\n"
            "          }).then(() => location.reload());\n"
            "        }\n"
            "      };\n"
            "      const checkOwnership = () => {\n"
            "        const owned = readLease();\n"
            "        if (owned && owned.id === leaseId) return true;\n"
            "        yieldRuntime();\n"
            "        return false;\n"
            "      };\n"
            "      const renewLease = () => {\n"
            "        if (!active || !checkOwnership()) return;\n"
            "        try {\n"
            "          writeLease();\n"
            "        } catch (error) {\n"
            "          yieldRuntime();\n"
            "        }\n"
            "      };\n"
            "      try {\n"
            "        storage = window.localStorage;\n"
            "        if (!storage) throw new Error(\"localStorage is unavailable\");\n"
            "        const probeKey = leaseKey + \".probe.\" + leaseId;\n"
            "        storage.setItem(probeKey, \"1\");\n"
            "        if (storage.getItem(probeKey) !== \"1\") throw new Error(\"localStorage lease probe failed\");\n"
            "        storage.removeItem(probeKey);\n"
            "        // A live tab renews its lease every 3 s; one that went away stops renewing and its lease\n"
            "        // turns stale, so waiting a stale period plus the handoff is enough for either outcome.\n"
            "        const waitUntil = Date.now() + Math.max(leaseStaleMs, runtimeLeaseStopMs) + runtimeLeaseHandoffMs;\n"
            "        while (heldElsewhere()) {\n"
            "          if (!active) return false;\n"
            "          if (Date.now() >= waitUntil) {\n"
            "            const message = \"The previous session is still running or saving. Retry opening the world.\";\n"
            "            window.__gaiusRuntimeLeaseError = message;\n"
            "            if (typeof setStatus === \"function\") setStatus(\"error\", message, 0, \"Retry opening the world\");\n"
            "            return false;\n"
            "          }\n"
            "          if (typeof setStatus === \"function\") {\n"
            "            setStatus(\"waiting\", \"Waiting for the previous session to finish saving...\",\n"
            "              8, \"Waiting for world storage to become free...\");\n"
            "          }\n"
            "          await new Promise(resolve => setTimeout(resolve, 250));\n"
            "        }\n"
            "        if (!active) return false;\n"
            "        writeLease();\n"
            "        await new Promise(resolve => setTimeout(resolve, 80));\n"
            "        const claimed = readLease();\n"
            "        if (!active || !claimed || claimed.id !== leaseId) return false;\n"
            "        heartbeat = setInterval(renewLease, 3000);\n"
            "        addEventListener(\"storage\", event => {\n"
            "          if (active && (event.key === leaseKey || event.key === null)) checkOwnership();\n"
            "        });\n"
            "        window.__gaiusRuntimeLeaseHeld = true;\n"
            "        return true;\n"
            "      } catch (error) {\n"
            "        console.warn(\"Cross-tab runtime lease is unavailable\", error);\n"
            "        const message = \"Gaius cannot start because the required localStorage runtime lease is unavailable. Enable browser storage, then retry by reloading this page.\";\n"
            "        window.__gaiusRuntimeLeaseError = message;\n"
            "        if (typeof setStatus === \"function\") setStatus(\"error\", message);\n"
            "        return false;\n"
            "      }\n"
            "    }"
        )

    # Keep the generated launcher lease/lifecycle block byte-for-byte aligned with the tracked
    # template. This avoids a second hand-maintained copy drifting when reload behavior changes.
    launcher_template = (Path(__file__).resolve().parents[1] / "web" / "launcher" /
                         "index.template.html").read_text(encoding="utf-8")
    lease_start = launcher_template.index("    // One Gaius runtime per browser profile:")
    boot_iife_marker = "    if (!unsupportedBrowser) (async () => {"
    lease_end = launcher_template.index(boot_iife_marker, lease_start)
    lease_source = launcher_template[lease_start:lease_end]
    generated_start = text.index("    // One Gaius runtime per browser profile:")
    generated_end = text.index(boot_iife_marker, generated_start)
    text = text[:generated_start] + lease_source + text[generated_end:]

    # Migrate launchers that already contain the lease block from an earlier
    # postprocess pass. Reload in headless Chromium can deliver unload without
    # pagehide, so document-owned Wasm and graphics resources must be retired
    # from both paths.
    if (
        "function releaseGaiusRuntimeLeaseOnPagehide(release, cancel)" in text
        and "let documentResourcesTerminated = false;" not in text
    ):
        text = replace_required(
            text,
            "      let lifecycleStopPromise = null;\n"
            "      addEventListener(\"pagehide\", () => {\n",
            "      let lifecycleStopPromise = null;\n"
            "      let documentResourcesTerminated = false;\n"
            "      function terminateGaiusDocumentResources(reason) {\n"
            "        if (documentResourcesTerminated) return;\n"
            "        documentResourcesTerminated = true;\n"
            "        try {\n"
            "          window.__gaiusWasmTerminated = true;\n"
            "          if (typeof window.__gaiusTerminateWasm === \"function\") window.__gaiusTerminateWasm();\n"
            "        } catch (_) {}\n"
            "        try {\n"
            "          if (typeof window.__gaiusTerminateGraphics === \"function\") {\n"
            "            window.__gaiusTerminateGraphics(reason || \"unload\");\n"
            "          }\n"
            "        } catch (_) {}\n"
            "      }\n"
            "      addEventListener(\"beforeunload\", () => terminateGaiusDocumentResources(\"beforeunload\"));\n"
            "      addEventListener(\"unload\", () => terminateGaiusDocumentResources(\"unload\"));\n"
            "      addEventListener(\"pagehide\", () => {\n",
            "existing launcher lifecycle teardown migration",
        )
        old_teardown = (
            "        try {\n"
            "          window.__gaiusWasmTerminated = true;\n"
            "          if (typeof window.__gaiusTerminateWasm === \"function\") window.__gaiusTerminateWasm();\n"
            "        } catch (_) {}\n"
            "        try {\n"
            "          if (typeof window.__gaiusTerminateGraphics === \"function\") {\n"
            "            window.__gaiusTerminateGraphics(\"pagehide\");\n"
            "          }\n"
            "        } catch (_) {}\n"
        )
        if old_teardown in text:
            text = replace_required(
                text,
                old_teardown,
                "        terminateGaiusDocumentResources(\"pagehide\");\n",
                "existing launcher lifecycle teardown body migration",
            )
        else:
            text = replace_required(
                text,
                "        requestGaiusLifecycleSave(\"pagehide\");\n",
                "        requestGaiusLifecycleSave(\"pagehide\");\n"
                "        terminateGaiusDocumentResources(\"pagehide\");\n",
                "existing launcher lifecycle teardown call migration",
            )

    # Migrate launchers that already have the resource teardown but still start the
    # Worker/storage barrier only from pagehide. Chromium reloads can omit pagehide,
    # so the same barrier must be started by beforeunload and unload as well.
    if (
        "function releaseGaiusRuntimeLeaseOnPagehide(release, cancel)" in text
        and "function beginGaiusLifecycleTeardown(reason)" not in text
    ):
        text = replace_required(
            text,
            "      addEventListener(\"beforeunload\", () => terminateGaiusDocumentResources(\"beforeunload\"));\n"
            "      addEventListener(\"unload\", () => terminateGaiusDocumentResources(\"unload\"));\n"
            "      addEventListener(\"pagehide\", () => {\n"
            "        if (lifecycleStopPromise) return;\n"
            "        cancel();\n"
            "        requestGaiusLifecycleSave(\"pagehide\");\n"
            "        terminateGaiusDocumentResources(\"pagehide\");\n",
            "      function beginGaiusLifecycleTeardown(reason) {\n"
            "        if (lifecycleStopPromise) return;\n"
            "        cancel();\n"
            "        requestGaiusLifecycleSave(reason || \"page-lifecycle\");\n"
            "        terminateGaiusDocumentResources(reason || \"page-lifecycle\");\n",
            "existing launcher lifecycle barrier migration",
        )
        text = replace_required(
            text,
            "        release({defer: true});\n"
            "      });\n"
            "      addEventListener(\"unload\", release);\n",
            "        release({defer: true});\n"
            "      }\n"
            "      // Chromium reloads can deliver beforeunload/unload without pagehide. The save/stop barrier\n"
            "      // must therefore start from every teardown path, otherwise a successor can wait forever for\n"
            "      // the old singleplayer Worker and its OPFS connection.\n"
            "      addEventListener(\"beforeunload\", () => beginGaiusLifecycleTeardown(\"beforeunload\"));\n"
            "      addEventListener(\"unload\", () => beginGaiusLifecycleTeardown(\"unload\"));\n"
            "      addEventListener(\"pagehide\", () => beginGaiusLifecycleTeardown(\"pagehide\"));\n",
            "existing launcher lifecycle barrier footer migration",
        )

    stable_build_block = (
        f'      const fallbackBuildToken = "{build_token}";\n'
        '      const requestedBuildToken = new URLSearchParams(location.search).get("build");\n'
        '      let buildToken = requestedBuildToken && requestedBuildToken.trim()\n'
        '        ? requestedBuildToken.trim()\n'
        '        : fallbackBuildToken;\n'
        '      if (urlParams.get("fresh") === "1" || urlParams.get("cache") === "0") {\n'
        '        buildToken += "-fresh-" + Date.now();\n'
        '      }\n'
        '      bootTimings.buildToken = buildToken;\n'
        '      bootTimings.classesStart = performance.now();'
    )
    old_fresh_block = (
        '      const requestedBuildToken = new URLSearchParams(location.search).get("build") || "20260629090000";\n'
        '      const buildToken = requestedBuildToken + "-fresh-" + Date.now();'
    )
    if old_fresh_block in text:
        text = text.replace(old_fresh_block, stable_build_block, 1)
    else:
        text, count = re.subn(
            r'      const fallbackBuildToken = "[^"]+";\n'
            r'      const requestedBuildToken = new URLSearchParams\(location\.search\)\.get\("build"\);\n'
            r'      let buildToken = requestedBuildToken && requestedBuildToken\.trim\(\)\n'
            r'        \? requestedBuildToken\.trim\(\)\n'
            r'        : fallbackBuildToken;\n'
            r'      if \(urlParams\.get\("fresh"\) === "1" \|\| urlParams\.get\("cache"\) === "0"\) \{\n'
            r'        buildToken \+= "-fresh-" \+ Date\.now\(\);\n'
            r'      \}\n'
            r'      bootTimings\.buildToken = buildToken;\n'
            r'      bootTimings\.classesStart = performance\.now\(\);',
            stable_build_block,
            text,
            count=1,
        )
        if count == 0 and all(
            marker in text
            for marker in (
                '      const requestedBuildToken = new URLSearchParams(location.search).get("build");',
                "      bootTimings.buildToken = buildToken;",
                "      bootTimings.classesStart = performance.now();",
            )
        ):
            text, count = re.subn(
                r'      const fallbackBuildToken = "[^"]+";',
                f'      const fallbackBuildToken = "{build_token}";',
                text,
                count=1,
            )
        if count == 0:
            raise RuntimeError("index.html patch point was not found: stable build token")

    # Keep this block as one canonical unit. The launcher template deliberately
    # keeps portable Blob URLs intact, while the generated profile adds the
    # content hash used for normal HTTP/file launches. Replacing the complete
    # region on every pass is simpler and safer than recognizing every
    # historical form of the block. The old mixed form left the template's
    # conditional URL block beside the generated block, which declared
    # `singleplayerBuildToken` twice in the same async scope and made portable
    # releases fail before the game could start.
    singleplayer_build_block = (
        f'      const singleplayerBuildToken = "{singleplayer_token}" +\n'
        '        (urlParams.get("fresh") === "1" || urlParams.get("cache") === "0"\n'
        '          ? "-fresh-" + Date.now()\n'
        '          : "");\n'
        '      // Portable Gaius.html embeds these payloads as Blob URLs. Keep those\n'
        '      // URLs; replacing them with file:/// sibling paths makes the downloaded\n'
        '      // single-file client exit before its Worker can start.\n'
        '      if (window.__gaiusPortableBuild !== true) {\n'
        '        window.__gaiusSingleplayerWorkerUrl = new URL(\n'
        '          "singleplayer-server-worker.js?v=" + encodeURIComponent(singleplayerBuildToken),\n'
        '          location.href\n'
        '        ).href;\n'
        '      }\n'
    )
    singleplayer_anchor = '      bootTimings.buildToken = buildToken;\n'
    classes_anchor = '      bootTimings.classesStart = performance.now();'
    singleplayer_start = text.find(singleplayer_anchor)
    singleplayer_end = (
        text.find(classes_anchor, singleplayer_start + len(singleplayer_anchor))
        if singleplayer_start >= 0
        else -1
    )
    if singleplayer_start < 0 or singleplayer_end < 0:
        raise RuntimeError(
            "index.html patch point was not found: singleplayer content build token"
        )
    singleplayer_insert_at = singleplayer_start + len(singleplayer_anchor)
    text = (
        text[:singleplayer_insert_at]
        + singleplayer_build_block
        + text[singleplayer_end:]
    )

    if "bootTimings.fsReady" not in text:
        text = replace_required(
            text,
            "      await window.__gaiusFsReady;\n",
            "      await window.__gaiusFsReady;\n"
            "      bootTimings.fsReady = performance.now();\n",
            "fs ready timing",
        )

    # Do not hydrate region files into the title-screen filesystem. The small
    # world metadata needed while opening a saved world remains available for
    # the client (notably data/minecraft/world_gen_settings.dat); the selected
    # world's complete data is loaded by the dedicated integrated-server
    # Worker.
    if "function isClientBootstrapPath(path)" not in text:
        text = replace_required(
            text,
            "      function openDatabase() {\n",
            "      function isClientBootstrapPath(path) {\n"
            "        path = normalize(path);\n"
            "        const savesRoot = \"/gaius/saves/\";\n"
            "        if (!path.startsWith(savesRoot)) return true;\n"
            "        const worldSeparator = path.indexOf(\"/\", savesRoot.length);\n"
            "        if (worldSeparator < 0 || worldSeparator + 1 >= path.length) return false;\n"
            "        const relative = path.slice(worldSeparator + 1);\n"
            "        return relative === \"level.dat\" || relative === \"level.dat_old\" ||\n"
            "          relative === \"icon.png\" ||\n"
            "          relative === \"data/minecraft/world_gen_settings.dat\";\n"
            "      }\n\n"
            "      function openDatabase() {\n",
            "client IndexedDB bootstrap filter",
        )
    elif 'relative === "data/minecraft/world_gen_settings.dat"' not in text:
        # Migrate an already-postprocessed launcher from the old metadata
        # allowlist. This keeps the patch effective on an existing dist tree;
        # the guard above alone would otherwise leave world-gen settings out
        # forever after the first postprocess pass.
        text = replace_required(
            text,
            '          relative === "icon.png";\n',
            '          relative === "icon.png" ||\n'
            '          relative === "data/minecraft/world_gen_settings.dat";\n',
            "client IndexedDB bootstrap world-gen metadata migration",
        )
    if '              files[normalize(value.path)] = value.value;\n' in text:
        text = replace_required(
            text,
            '            if (value && typeof value.path === "string" && typeof value.value === "string") {\n'
            '              files[normalize(value.path)] = value.value;\n'
            "            }\n",
            '            if (value && typeof value.path === "string" && typeof value.value === "string") {\n'
            '              const path = normalize(value.path);\n'
            '              if (isClientBootstrapPath(path)) files[path] = value.value;\n'
            "            }\n",
            "client IndexedDB read filter",
        )
    if "              files[path] = value;\n              migrated++;\n" in text:
        text = replace_required(
            text,
            "              files[path] = value;\n"
            "              migrated++;\n",
            "              if (isClientBootstrapPath(path)) files[path] = value;\n"
            "              migrated++;\n",
            "client IndexedDB migration filter",
        )
    if '                files[normalize(key.substring(prefix.length))] = value;\n' in text:
        text = replace_required(
            text,
            '              if (typeof value === "string") {\n'
            '                files[normalize(key.substring(prefix.length))] = value;\n'
            "                restored++;\n"
            "              }\n",
            '              if (typeof value === "string") {\n'
            '                const path = normalize(key.substring(prefix.length));\n'
            '                if (isClientBootstrapPath(path)) files[path] = value;\n'
            "                restored++;\n"
            "              }\n",
            "client localStorage fallback filter",
        )

    if "function copyPersistentBytes(value)" not in text:
        text = replace_required(
            text,
            "      function openDatabase() {\n",
            "      function copyPersistentBytes(value) {\n"
            "        if (value instanceof Uint8Array) return value.slice();\n"
            "        if (value instanceof ArrayBuffer) return new Uint8Array(value.slice(0));\n"
            "        if (ArrayBuffer.isView(value)) {\n"
            "          return new Uint8Array(value.buffer, value.byteOffset, value.byteLength).slice();\n"
            "        }\n"
            "        return null;\n"
            "      }\n\n"
            "      function isDownloadedPackBinaryPath(path) {\n"
            "        path = normalize(path);\n"
            "        return path.startsWith(\"/gaius/downloads/\") && !path.endsWith(\"/log.json\");\n"
            "      }\n\n"
            "      function persistentValueByteLength(value) {\n"
            "        if (typeof value === \"string\") {\n"
            "          const padding = value.endsWith(\"==\") ? 2 : (value.endsWith(\"=\") ? 1 : 0);\n"
            "          return Math.max(0, Math.floor(value.length * 3 / 4) - padding);\n"
            "        }\n"
            "        if (value instanceof ArrayBuffer) return value.byteLength;\n"
            "        if (ArrayBuffer.isView(value)) return value.byteLength;\n"
            "        return 0;\n"
            "      }\n\n"
            "      function openDatabase() {\n",
            "binary IndexedDB persistence helpers",
        )

    legacy_open_database = '''      function openDatabase() {
        return new Promise((resolve, reject) => {
          if (!("indexedDB" in window)) {
            reject(new Error("IndexedDB is not available"));
            return;
          }
          const request = indexedDB.open(dbName, 1);
          request.onupgradeneeded = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains(storeName)) {
              database.createObjectStore(storeName, { keyPath: "path" });
            }
          };
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
          request.onblocked = () => reject(new Error("IndexedDB open blocked"));
        });
      }
'''
    bounded_open_database = '''      function openDatabase() {
        return new Promise((resolve, reject) => {
          if (!("indexedDB" in window)) {
            reject(new Error("IndexedDB is not available"));
            return;
          }
          let settled = false;
          const timeout = setTimeout(() => {
            if (settled) return;
            settled = true;
            reject(new Error("IndexedDB open timed out"));
          }, 8000);
          const fail = error => {
            if (settled) return;
            settled = true;
            clearTimeout(timeout);
            reject(error);
          };
          const request = indexedDB.open(dbName, 1);
          request.onupgradeneeded = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains(storeName)) {
              database.createObjectStore(storeName, { keyPath: "path" });
            }
          };
          request.onsuccess = () => {
            const database = request.result;
            if (settled) {
              database.close();
              return;
            }
            settled = true;
            clearTimeout(timeout);
            database.onversionchange = () => {
              try { database.close(); } catch (_) {}
              if (db === database) db = null;
            };
            resolve(database);
          };
          request.onerror = () => fail(request.error || new Error("IndexedDB open failed"));
          request.onblocked = () => fail(new Error("IndexedDB open blocked"));
        });
      }
'''
    if legacy_open_database in text:
        text = text.replace(legacy_open_database, bounded_open_database, 1)
    elif bounded_open_database not in text:
        # Newer train inputs already carry the bounded open implementation with
        # the profile-specific schema version. Leave that implementation intact.
        pass

    legacy_indexeddb_bootstrap = '''      async function readIndexedDbFiles(database) {
        const transaction = database.transaction(storeName, "readonly");
        const store = transaction.objectStore(storeName);
        await new Promise((resolve, reject) => {
          const request = store.openCursor();
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) {
              resolve();
              return;
            }
            const value = cursor.value;
            if (value && typeof value.path === "string" && typeof value.value === "string") {
              const path = normalize(value.path);
              if (isClientBootstrapPath(path)) files[path] = value.value;
            }
            cursor.continue();
          };
          request.onerror = () => reject(request.error || new Error("IndexedDB cursor failed"));
        });
      }
'''
    value_cursor_indexeddb_bootstrap = '''      async function readIndexedDbFiles(database) {
        const transaction = database.transaction(storeName, "readonly");
        const store = transaction.objectStore(storeName);
        const downloadedPacks = [];
        await new Promise((resolve, reject) => {
          const request = store.openCursor();
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) {
              resolve();
              return;
            }
            const value = cursor.value;
            if (value && typeof value.path === "string") {
              const path = normalize(value.path);
              const storedValue = value.value;
              const supported = typeof storedValue === "string" ||
                storedValue instanceof ArrayBuffer || ArrayBuffer.isView(storedValue);
              if (supported && isClientBootstrapPath(path)) {
                if (isDownloadedPackBinaryPath(path)) {
                  downloadedPacks.push({
                    path,
                    value: storedValue,
                    updatedAt: Number(value.updatedAt) || 0
                  });
                } else {
                  const bytes = copyPersistentBytes(storedValue);
                  files[path] = bytes === null ? storedValue : bytes;
                }
              }
            }
            cursor.continue();
          };
          request.onerror = () => reject(request.error || new Error("IndexedDB cursor failed"));
        });

        downloadedPacks.sort((left, right) => right.updatedAt - left.updatedAt ||
          left.path.localeCompare(right.path));
        const maximumDownloadedPacks = 4;
        const maximumDownloadedPackBytes = 256 * 1024 * 1024;
        const evicted = [];
        let kept = 0;
        let keptBytes = 0;
        for (const entry of downloadedPacks) {
          const byteLength = persistentValueByteLength(entry.value);
          const withinBudget = kept < maximumDownloadedPacks &&
            (kept === 0 || keptBytes + byteLength <= maximumDownloadedPackBytes);
          if (withinBudget) {
            const bytes = copyPersistentBytes(entry.value);
            files[entry.path] = bytes === null ? entry.value : bytes;
            kept++;
            keptBytes += byteLength;
          } else {
            evicted.push(entry.path);
          }
        }
        if (evicted.length > 0) {
          const cleanup = database.transaction(storeName, "readwrite");
          const cleanupStore = cleanup.objectStore(storeName);
          for (const path of evicted) cleanupStore.delete(path);
          await transactionDone(cleanup);
          report("storage-download-cache-pruned", evicted.length + " files");
        }
      }
'''
    key_cursor_indexeddb_bootstrap = '''      async function readIndexedDbFiles(database) {
        const transaction = database.transaction(storeName, "readonly");
        const store = transaction.objectStore(storeName);
        const downloadedPacks = [];
        let scannedKeys = 0;
        let hydratedRecords = 0;
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            reject(new Error("IndexedDB bootstrap timed out"));
            try {
              transaction.abort();
            } catch (error) {
              // The transaction may have completed between the timer and abort.
            }
          }, 8000);
          transaction.oncomplete = () => {
            clearTimeout(timeout);
            resolve();
          };
          transaction.onerror = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction failed"));
          };
          transaction.onabort = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction aborted"));
          };
          const request = store.openKeyCursor();
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) return;
            scannedKeys++;
            const primaryKey = cursor.primaryKey;
            const path = normalize(primaryKey);
            if (isClientBootstrapPath(path)) {
              const read = store.get(primaryKey);
              read.onsuccess = () => {
                const value = read.result;
                if (value && typeof value.path === "string") {
                  const storedValue = value.value;
                  const supported = typeof storedValue === "string" ||
                    storedValue instanceof ArrayBuffer || ArrayBuffer.isView(storedValue);
                  if (supported) {
                    hydratedRecords++;
                    if (isDownloadedPackBinaryPath(path)) {
                      downloadedPacks.push({
                        path,
                        value: storedValue,
                        updatedAt: Number(value.updatedAt) || 0
                      });
                    } else {
                      const bytes = copyPersistentBytes(storedValue);
                      files[path] = bytes === null ? storedValue : bytes;
                    }
                  }
                }
              };
            }
            cursor.continue();
          };
          request.onerror = () => reject(
            request.error || new Error("IndexedDB key cursor failed"));
        });
        report("storage-key-scan", scannedKeys + " keys, hydrated=" + hydratedRecords);

        downloadedPacks.sort((left, right) => right.updatedAt - left.updatedAt ||
          left.path.localeCompare(right.path));
        const maximumDownloadedPacks = 4;
        const maximumDownloadedPackBytes = 256 * 1024 * 1024;
        const evicted = [];
        let kept = 0;
        let keptBytes = 0;
        for (const entry of downloadedPacks) {
          const byteLength = persistentValueByteLength(entry.value);
          const withinBudget = kept < maximumDownloadedPacks &&
            (kept === 0 || keptBytes + byteLength <= maximumDownloadedPackBytes);
          if (withinBudget) {
            const bytes = copyPersistentBytes(entry.value);
            files[entry.path] = bytes === null ? entry.value : bytes;
            kept++;
            keptBytes += byteLength;
          } else {
            evicted.push(entry.path);
          }
        }
        if (evicted.length > 0) {
          const cleanup = database.transaction(storeName, "readwrite");
          const cleanupStore = cleanup.objectStore(storeName);
          for (const path of evicted) cleanupStore.delete(path);
          await transactionDone(cleanup);
          report("storage-download-cache-pruned", evicted.length + " files");
        }
      }
'''
    untimed_key_cursor_indexeddb_bootstrap = key_cursor_indexeddb_bootstrap.replace(
        '''          const timeout = setTimeout(() => {
            reject(new Error("IndexedDB bootstrap timed out"));
            try {
              transaction.abort();
            } catch (error) {
              // The transaction may have completed between the timer and abort.
            }
          }, 8000);
          transaction.oncomplete = () => {
            clearTimeout(timeout);
            resolve();
          };
          transaction.onerror = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction failed"));
          };
          transaction.onabort = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction aborted"));
          };
''',
        '''          transaction.oncomplete = () => resolve();
          transaction.onerror = () => reject(
            transaction.error || new Error("IndexedDB bootstrap transaction failed"));
          transaction.onabort = () => reject(
            transaction.error || new Error("IndexedDB bootstrap transaction aborted"));
''',
        1,
    )
    if value_cursor_indexeddb_bootstrap in text:
        text = text.replace(
            value_cursor_indexeddb_bootstrap,
            key_cursor_indexeddb_bootstrap,
            1,
        )
    elif untimed_key_cursor_indexeddb_bootstrap in text:
        text = text.replace(
            untimed_key_cursor_indexeddb_bootstrap,
            key_cursor_indexeddb_bootstrap,
            1,
        )
    elif "screenshots.set(path, -1);" in text:
        # The current launcher: its key scan also lists screenshots without reading them
        # (pruneScreenshots); the shapes above are older pages.
        pass
    else:
        text = replace_required(
            text,
            legacy_indexeddb_bootstrap,
            key_cursor_indexeddb_bootstrap,
            "key-only IndexedDB bootstrap",
        )

    legacy_storage_fallback = '''        } catch (error) {
          const restored = loadLocalStorageFallback();
          window.__gaiusFsBackend = "localStorage";
          report("storage-indexeddb-unavailable", (error && error.message ? error.message : String(error)) + ", fallbackFiles=" + restored);
        }
'''
    bounded_storage_fallback = '''        } catch (error) {
          if (db) {
            try {
              db.close();
            } catch (closeError) {
              // The failed transaction may already have closed the database.
            }
            db = null;
          }
          const restored = loadLocalStorageFallback();
          window.__gaiusFsBackend = "localStorage";
          report("storage-indexeddb-unavailable", (error && error.message ? error.message : String(error)) + ", fallbackFiles=" + restored);
        }
'''
    if legacy_storage_fallback in text:
        text = text.replace(legacy_storage_fallback, bounded_storage_fallback, 1)
    if "window.__gaiusFsClose = function()" not in text:
        fs_ready_anchor = "      window.__gaiusFsReady = (async () => {\n"
        if fs_ready_anchor in text:
            text = text.replace(
                fs_ready_anchor,
                "      // Explicit in-page shutdown may close this connection after pending writes drain.\n"
                "      // pagehide keeps it open for queued writes; onversionchange closes it for upgrades.\n"
                "      window.__gaiusFsClose = function() {\n"
                "        const database = db;\n"
                "        db = null;\n"
                "        if (!database) return;\n"
                "        try { database.onversionchange = null; } catch (_) {}\n"
                "        try { database.close(); } catch (_) {}\n"
                "      };\n\n" + fs_ready_anchor,
                1,
            )
    if "window.__gaiusFsClose" not in text:
        raise RuntimeError("index.html patch point was not found: IndexedDB startup fallback closes unusable database")

    text = replace_required(
        text,
        '''      async function putIndexedDbFile(path, value) {
        const transaction = db.transaction(storeName, "readwrite");
        transaction.objectStore(storeName).put({ path: normalize(path), value: String(value || "") });
        await transactionDone(transaction);
      }
''',
        '''      async function putIndexedDbFile(path, value) {
        const transaction = db.transaction(storeName, "readwrite");
        const bytes = copyPersistentBytes(value);
        const storedValue = bytes === null ? String(value || "") : bytes;
        transaction.objectStore(storeName).put({
          path: normalize(path),
          value: storedValue,
          updatedAt: Date.now()
        });
        await transactionDone(transaction);
      }
''',
        "binary IndexedDB writer",
    )

    if "window.__gaiusFsPutBytes = function(path, value)" not in text:
        text = replace_required(
            text,
            '''      window.__gaiusFsDelete = function(path) {
''',
            '''      window.__gaiusFsPutBytes = function(path, value) {
        path = normalize(path);
        const bytes = copyPersistentBytes(value);
        if (bytes === null || !db) return false;
        files[path] = bytes;
        pending = pending.then(() => putIndexedDbFile(path, bytes)).catch(error => {
          report("storage-indexeddb-write-error", path + ": " + (error && error.message ? error.message : String(error)));
        });
        return true;
      };

      window.__gaiusFsDelete = function(path) {
''',
            "binary IndexedDB browser writer",
        )

    if "bootTimings.beforeClassesPaint" not in text:
        text = replace_required(
            text,
            "      setStatus(\"running\", \"浏览器存储已就绪（\" + (window.__gaiusFsBackend || \"unknown\") + \"），正在加载 classes.js…\", 24, \"加载 1.21.11 TeaVM 主程序…\");\n"
            "      await loadScript(\"classes.js?v=\" + encodeURIComponent(buildToken));\n",
            "      setStatus(\"running\", \"浏览器存储已就绪（\" + (window.__gaiusFsBackend || \"unknown\") + \"），正在加载 classes.js…\", 24, \"加载 1.21.11 TeaVM 主程序…\");\n"
            "      // Start the large TeaVM bundle and both asset sources in parallel.\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n"
            "        (\"classes.js?v=\" + encodeURIComponent(buildToken));\n"
            "      const classesReady = loadScript(classesUrl);\n"
            "      const portableReady = window.__gaiusPortableAssetsReady || Promise.resolve();\n"
            "      const vanillaReady = window.__gaiusVanillaAssetsReady || Promise.resolve();\n"
            "      await waitForPaint();\n"
            "      bootTimings.beforeClassesPaint = performance.now();\n"
            "      setBootProgress(Math.max(bootProgressValue, 30), \"Loading game assets...\");\n"
            "      await Promise.all([portableReady, vanillaReady, classesReady]);\n"
            "      bootTimings.vanillaAssetsReady = performance.now();\n",
            "paint before classes",
        )

    if "const classesUrl = window.__gaiusClassesUrl" not in text:
        text = replace_required(
            text,
            "      await loadScript(\"classes.js?v=\" + encodeURIComponent(buildToken));\n",
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n"
            "        (\"classes.js?v=\" + encodeURIComponent(buildToken));\n"
            "      await loadScript(classesUrl);\n",
            "portable TeaVM client asset URL",
        )

    if "bootTimings.vanillaAssetsReady" not in text:
        text = replace_required(
            text,
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n",
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      setBootProgress(Math.max(bootProgressValue, 30), \"Loading game assets...\");\n"
            "      await window.__gaiusVanillaAssetsReady;\n"
            "      bootTimings.vanillaAssetsReady = performance.now();\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n",
            "vanilla assets before TeaVM client",
        )

    if "bootTimings.mainStart" not in text:
        text = replace_required(
            text,
            "\t        setStatus(\"running\", \"已加载 classes.js，调用 net.minecraft.client.main.Main.main(args)…\\n\" + window.__gaiusDefaultArgs.join(\" \"), 68, \"启动 Minecraft 客户端…\");\n"
            "\t        main(window.__gaiusDefaultArgs);\n"
            "\t        setBootProgress(82, \"等待 Minecraft 首帧/主界面…\");\n",
            "\t        setStatus(\"running\", \"已加载 classes.js，调用 net.minecraft.client.main.Main.main(args)…\\n\" + window.__gaiusDefaultArgs.join(\" \"), 68, \"启动 Minecraft 客户端…\");\n"
            "\t        await waitForPaint();\n"
            "\t        bootTimings.mainStart = performance.now();\n"
            "\t        main(window.__gaiusDefaultArgs);\n"
            "\t        bootTimings.mainReturned = performance.now();\n"
            "\t        setBootProgress(82, \"等待 Minecraft 首帧/主界面…\");\n",
            "main timing",
        )

    text = re.sub(
        r'\n\s*"--disableMultiplayer",',
        "",
        text,
        count=1,
    )

    session_block = '''    function createGaiusProxyUrl(target, kind) {
      // Hosted pages (for example GitHub Pages) have no bridge on their own
      // :8080, so without an explicit bridge use the bundled relay registry;
      // only local development hosts keep the local :8080 fallback.
      const pageHost = String(location.hostname || "").toLowerCase();
      const localDevelopmentHost = pageHost === "localhost" ||
        pageHost.endsWith(".localhost") || pageHost.startsWith("127.") ||
        pageHost === "[::1]" || pageHost === "::1";
      let registryRelay = null;
      if (!localDevelopmentHost && Array.isArray(window.__gaiusBridgeUrls)) {
        let bestPriority = -Infinity;
        for (const node of window.__gaiusBridgeUrls) {
          const url = typeof node === "string" ? node : node && node.url;
          if (typeof url !== "string" || !url.trim()) continue;
          const rawPriority = node && typeof node === "object" ? Number(node.priority) : 0;
          const priority = Number.isFinite(rawPriority) ? rawPriority : 0;
          if (priority > bestPriority) {
            bestPriority = priority;
            registryRelay = url.trim();
          }
        }
      }
      const configured = urlParams.get("bridge") || urlParams.get("relay") ||
        window.__gaiusBridgeUrl || registryRelay;
      let bridge;
      if (configured && String(configured).trim()) {
        bridge = new URL(String(configured).trim(), location.href);
      } else {
        const scheme = location.protocol === "https:" ? "https:" : "http:";
        const rawHost = String(window.__gaiusBridgeHost || location.hostname || "127.0.0.1");
        const host = rawHost.includes(":") &&
          !(rawHost.startsWith("[") && rawHost.endsWith("]"))
          ? "[" + rawHost + "]"
          : rawHost;
        const port = window.__gaiusBridgePort || "8080";
        bridge = new URL(scheme + "//" + host + ":" + port + "/");
      }
      if (bridge.protocol === "ws:") bridge.protocol = "http:";
      if (bridge.protocol === "wss:") bridge.protocol = "https:";
      if (bridge.protocol !== "http:" && bridge.protocol !== "https:") {
        throw new Error("Unsupported Gaius bridge protocol: " + bridge.protocol);
      }
      bridge.pathname = "/proxy/" + String(kind);
      bridge.hash = "";
      bridge.search = "";
      bridge.searchParams.set("url", String(target));
      const token = urlParams.get("bridgeToken") || urlParams.get("relayToken") || window.__gaiusBridgeToken;
      if (token && String(token).length) bridge.searchParams.set("token", String(token));
      return bridge.href;
    }

    async function loadGaiusMinecraftProfile(accessToken) {
      const response = await fetch(createGaiusProxyUrl(
        "https://api.minecraftservices.com/minecraft/profile",
        "auth"
      ), {
        method: "GET",
        headers: {
          "accept": "application/json",
          "authorization": "Bearer " + accessToken
        },
        cache: "no-store"
      });
      if (!response.ok) {
        throw new Error("Minecraft profile request failed with HTTP " + response.status);
      }
      const profile = await response.json();
      const username = String(profile && profile.name || "").trim();
      const uuid = String(profile && profile.id || "").replace(/-/g, "").toLowerCase();
      if (!/^[A-Za-z0-9_]{1,16}$/.test(username) || !/^[0-9a-f]{32}$/.test(uuid)) {
        throw new Error("Minecraft profile response did not contain a valid Java profile");
      }
      return {username, uuid};
    }

    function requestGaiusPlayerName(initialName) {
      if (!profileGate || !profileForm || !profileName || !profileError) {
        throw new Error("The Gaius player-name screen is unavailable");
      }
      profileName.value = String(initialName || "").trim();
      profileError.textContent = "";
      profileGate.hidden = false;
      if (bootBrand) bootBrand.hidden = true;
      if (bootProgress) bootProgress.hidden = true;
      if (bootProgressText) bootProgressText.hidden = true;
      statusBox.hidden = true;
      return new Promise(resolve => {
        const submit = event => {
          event.preventDefault();
          const username = profileName.value.trim();
          if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) {
            profileError.textContent = "Use 1-16 letters, numbers, or underscores.";
            profileName.focus();
            return;
          }
          profileForm.removeEventListener("submit", submit);
          try {
            localStorage.setItem("gaius.playerName", username);
          } catch (error) {
            console.warn("Could not remember the Gaius player name", error);
          }
          profileGate.hidden = true;
          if (bootBrand) bootBrand.hidden = false;
          if (bootProgress) bootProgress.hidden = false;
          if (bootProgressText) bootProgressText.hidden = false;
          statusBox.hidden = !showLauncherDetails;
          setBootProgress(Math.max(bootProgressValue, 3), "Preparing the browser runtime...");
          resolve(username);
        };
        profileForm.addEventListener("submit", submit);
        requestAnimationFrame(() => {
          profileName.focus();
          profileName.select();
        });
      });
    }

    function changeGaiusPlayerName() {
      if (profileSwitch) profileSwitch.disabled = true;
      try {
        sessionStorage.removeItem("gaius.session");
      } catch (error) {
        console.warn("Could not clear the current Gaius session", error);
      }
      window.__gaiusSession = {};
      const next = new URL(location.href);
      for (const key of ["username", "uuid", "accessToken", "xuid", "clientId"]) {
        next.searchParams.delete(key);
      }
      try {
        if (typeof window.__gaiusReleaseRuntimeLease === "function") {
          window.__gaiusReleaseRuntimeLease();
        }
      } finally {
        location.replace(next.href);
      }
    }

    function updateGaiusProfileSwitch() {
      if (!profileSwitch) return;
      const state = window.__gaiusMinecraftState;
      const screen = state && state.screen
        ? String(state.screen).split(".").pop()
        : "";
      profileSwitch.hidden = !profileGate || !profileGate.hidden || screen !== "TitleScreen";
    }

    if (profileSwitch) {
      profileSwitch.addEventListener("click", changeGaiusPlayerName);
      setInterval(updateGaiusProfileSwitch, 250);
      updateGaiusProfileSwitch();
    }
    window.__gaiusChangePlayerName = changeGaiusPlayerName;

    async function buildGaiusSessionArgs() {
      let stored = {};
      try {
        const value = sessionStorage.getItem("gaius.session");
        stored = value ? JSON.parse(value) : {};
      } catch (error) {
        console.warn("Ignoring invalid Gaius sessionStorage data", error);
      }
      const injected = window.__gaiusSession && typeof window.__gaiusSession === "object"
        ? window.__gaiusSession
        : {};
      const queried = {};
      for (const key of ["username", "uuid", "accessToken", "xuid", "clientId"]) {
        const value = urlParams.get(key);
        if (value !== null && value.trim()) queried[key] = value.trim();
      }
      const session = Object.assign({}, stored, injected, queried);
      const quickPlayServer = String(urlParams.get("server") || "").trim();
      if (quickPlayServer.length > 512) {
        throw new Error("Quick-play server address is too long");
      }
      const accessToken = String(session.accessToken || "").trim();
      const online = accessToken.length > 0 && accessToken !== "0";
      let username = String(session.username || "").trim();
      let uuid = String(session.uuid || (online ? "" : "00000000000040008000000000000001"))
        .replace(/-/g, "")
        .toLowerCase();
      if (username && !/^[A-Za-z0-9_]{1,16}$/.test(username)) {
        throw new Error("Player name must use 1-16 letters, numbers, or underscores");
      }
      if (uuid && !/^[0-9a-f]{32}$/.test(uuid)) {
        throw new Error("Online session UUID must contain 32 hexadecimal digits");
      }
      if (online && (!username || !uuid)) {
        const profile = await loadGaiusMinecraftProfile(accessToken);
        if (!username) username = profile.username;
        if (!uuid) uuid = profile.uuid;
        session.username = username;
        session.uuid = uuid;
        sessionStorage.setItem("gaius.session", JSON.stringify(session));
      }
      if (!online && !username) {
        let rememberedName = "";
        try {
          rememberedName = localStorage.getItem("gaius.playerName") || "";
        } catch (error) {
          console.warn("Could not read the remembered Gaius player name", error);
        }
        username = await requestGaiusPlayerName(rememberedName);
        session.username = username;
        session.uuid = uuid;
        sessionStorage.setItem("gaius.session", JSON.stringify(session));
      }
      if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) {
        throw new Error("Player name must use 1-16 letters, numbers, or underscores");
      }
      if (!/^[0-9a-f]{32}$/.test(uuid)) {
        throw new Error("Online session UUID must contain 32 hexadecimal digits");
      }
      const args = [
        "--version", "__GAIUS_VERSION__",
        "--versionType", "release",
        "--accessToken", online ? accessToken : "0",
        "--username", username,
        "--uuid", uuid,
        "--gameDir", "/gaius",
        "--assetsDir", "/gaius/assets",
        "--assetIndex", "__GAIUS_ASSET_INDEX__",
        "--resourcePackDir", "/gaius/resourcepacks",
        "--width", String(Math.max(854, window.innerWidth || 854)),
        "--height", String(Math.max(480, window.innerHeight || 480))
      ];
      if (online) {
        if (session.xuid) args.push("--xuid", String(session.xuid));
        if (session.clientId) args.push("--clientId", String(session.clientId));
      } else {
        args.push("--offlineDeveloperMode");
      }
      if (quickPlayServer) args.push("--quickPlayMultiplayer", quickPlayServer);
      window.__gaiusSessionMode = online ? "online" : "offline";
      return args;
    }

    window.__gaiusConfigureSession = session => {
      if (!session || typeof session !== "object") throw new TypeError("Session must be an object");
      sessionStorage.setItem("gaius.session", JSON.stringify(session));
    };
    window.__gaiusClearSession = () => sessionStorage.removeItem("gaius.session");
    window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs();
    window.__gaiusDefaultArgsPromise.catch(() => {});
    const identityQueryKeys = ["username", "uuid", "accessToken", "xuid", "clientId"];
    if (identityQueryKeys.some(key => urlParams.has(key))) {
      const scrubbed = new URL(location.href);
      for (const key of identityQueryKeys) scrubbed.searchParams.delete(key);
      history.replaceState(history.state, "", scrubbed.pathname + scrubbed.search + scrubbed.hash);
    }'''
    # A current launcher already owns the complete User Profile flow, including
    # local custom-skin persistence and the descriptor consumed by the client.
    # Do not replace that block with the legacy compatibility block below: doing
    # so leaves the upload controls in the HTML but drops their runtime wiring.
    if "customGaiusSkinDescriptor" in text and "window.__gaiusSkinDescriptor" in text:
        count = 1
    elif "function createGaiusProxyUrl(target, kind)" in text:
        text, count = re.subn(
            r'    function createGaiusProxyUrl\(target, kind\) \{.*?'
            r'(?:    const identityQueryKeys = \["username", "uuid", "accessToken", "xuid", "clientId"\];\n)?'
            r'    if \((?:identityQueryKeys\.some\(key => urlParams\.has\(key\)\)'
            r'|urlParams\.has\("accessToken"\))\) \{\n'
            r'      const scrubbed = new URL\(location\.href\);\n'
            r'(?:      for \(const key of identityQueryKeys\) scrubbed\.searchParams\.delete\(key\);'
            r'|      scrubbed\.searchParams\.delete\("accessToken"\);)\n'
            r'      history\.replaceState\(history\.state, "", scrubbed\.pathname \+ scrubbed\.search \+ scrubbed\.hash\);\n'
            r'    \}',
            lambda _match: session_block,
            text,
            count=1,
            flags=re.DOTALL,
        )
    else:
        text, count = re.subn(
            r'    window\.__gaiusDefaultArgs = \[\n.*?\n    \];',
            lambda _match: session_block,
            text,
            count=1,
            flags=re.DOTALL,
        )
    if count == 0:
        raise RuntimeError("index.html patch point was not found: browser session arguments")

    # Keep previously postprocessed launchers current when relay aliases are added later.
    text = text.replace(
        'const configured = urlParams.get("bridge") || window.__gaiusBridgeUrl;',
        'const configured = urlParams.get("bridge") || urlParams.get("relay") || window.__gaiusBridgeUrl;',
        1,
    )
    text = text.replace(
        'const token = urlParams.get("bridgeToken") || window.__gaiusBridgeToken;',
        'const token = urlParams.get("bridgeToken") || urlParams.get("relayToken") || window.__gaiusBridgeToken;',
        1,
    )

    if "window.__gaiusDefaultArgsPromise.catch(() => {});" not in text:
        text = replace_required(
            text,
            "    window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs();\n",
            "    window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs();\n"
            "    window.__gaiusDefaultArgsPromise.catch(() => {});\n",
            "online session rejection handler",
        )

    if "const quickPlayServer = String(urlParams.get(\"server\")" not in text:
        text = text.replace(
            "      const session = Object.assign({}, stored, injected, queried);\n",
            "      const session = Object.assign({}, stored, injected, queried);\n"
            "      const quickPlayServer = String(urlParams.get(\"server\") || \"\").trim();\n"
            "      if (quickPlayServer.length > 512) {\n"
            "        throw new Error(\"Quick-play server address is too long\");\n"
            "      }\n",
            1,
        )
        text = text.replace(
            "      window.__gaiusSessionMode = online ? \"online\" : \"offline\";\n",
            "      if (quickPlayServer) args.push(\"--quickPlayMultiplayer\", quickPlayServer);\n"
            "      window.__gaiusSessionMode = online ? \"online\" : \"offline\";\n",
            1,
        )
        if ("const quickPlayServer = String(urlParams.get(\"server\")" not in text
                or "--quickPlayMultiplayer" not in text):
            raise RuntimeError("index.html patch point was not found: quick-play multiplayer")

    text = text.replace(
        'window.__gaiusDefaultArgs.join(" ")',
        'window.__gaiusDisplayArgs.join(" ")',
    )

    if "bootTimings.sessionReady" not in text:
        text = replace_required(
            text,
            "      bootTimings.fsReady = performance.now();\n",
            "      bootTimings.fsReady = performance.now();\n"
            "      window.__gaiusDefaultArgs = await window.__gaiusDefaultArgsPromise;\n"
            "      window.__gaiusDisplayArgs = window.__gaiusDefaultArgs.map((value, index, args) =>\n"
            "        index > 0 && args[index - 1] === \"--accessToken\" ? \"<redacted>\" : value\n"
            "      );\n"
            "      bootTimings.sessionReady = performance.now();\n",
            "online session resolution",
        )

    if "bootTimings.runtimeLeaseReady" not in text:
        text = replace_required(
            text,
            "      bootTimings.sessionReady = performance.now();\n",
            "      bootTimings.sessionReady = performance.now();\n"
            "      if (!await acquireGaiusRuntimeLease()) {\n"
            "        throw new Error(window.__gaiusRuntimeLeaseError ||\n"
            "          \"Gaius is already running in another tab. Close that tab, then reload this page.\");\n"
            "      }\n"
            "      bootTimings.runtimeLeaseReady = performance.now();\n",
            "single active Gaius runtime acquisition",
        )

    if "window.__gaiusBootError = error;" not in text:
        text = replace_required(
            text,
            "    })().catch(error => {\n"
            "      setStatus(\"error\", [\n",
            "    })().catch(error => {\n"
            "      window.__gaiusBootError = error;\n"
            "      setStatus(\"error\", [\n",
            "boot error diagnostics",
        )

    if "window.__gaiusBootErrorDetails" not in text:
        text = replace_required(
            text,
            "      window.__gaiusBootError = error;\n"
            "      setStatus(\"error\", [\n",
            "      window.__gaiusBootError = error;\n"
            "      const bootErrorDetails = [];\n"
            "      const primitiveFields = value => Object.fromEntries(\n"
            "        Object.getOwnPropertyNames(value).slice(0, 80).flatMap(key => {\n"
            "          const field = value[key];\n"
            "          return field == null || [\"string\", \"number\", \"boolean\", \"bigint\"].includes(typeof field)\n"
            "            ? [[key, String(field)]] : [];\n"
            "        })\n"
            "      );\n"
            "      for (const symbol of Object.getOwnPropertySymbols(error || {})) {\n"
            "        const javaError = error[symbol];\n"
            "        if (!javaError || typeof javaError !== \"object\") continue;\n"
            "        bootErrorDetails.push(\"Java fields: \" + JSON.stringify(primitiveFields(javaError)));\n"
            "        const constructor = javaError.constructor;\n"
            "        for (const metadataSymbol of Object.getOwnPropertySymbols(constructor || {})) {\n"
            "          const metadata = constructor[metadataSymbol];\n"
            "          if (metadata && typeof metadata === \"object\") {\n"
            "            const fields = primitiveFields(metadata);\n"
            "            if (Object.keys(fields).length) bootErrorDetails.push(\"Java class: \" + JSON.stringify(fields));\n"
            "          }\n"
            "        }\n"
            "      }\n"
            "      window.__gaiusBootErrorDetails = bootErrorDetails;\n"
            "      setStatus(\"error\", [\n",
            "boot Java exception diagnostics",
        )

        text = replace_required(
            text,
            "        error && error.stack ? error.stack : String(error)\n"
            "      ].join(\"\\n\"));\n",
            "        error && error.stack ? error.stack : String(error),\n"
            "        ...bootErrorDetails\n"
            "      ].join(\"\\n\"));\n",
            "boot Java exception diagnostic display",
        )

    english_launcher_text = {
        "正在启动官方 Minecraft Java Edition 1.21.11 TeaVM 客户端…":
            "Starting Gaius Client 1.21.11...",
        "0% 初始化…": "0% Initializing...",
        'let bootProgressLabel = "初始化…";':
            'let bootProgressLabel = "Initializing...";',
        "初始化浏览器运行环境…": "Preparing the browser runtime...",
        "已进入世界": "World ready",
        "Minecraft 界面已加载：": "Minecraft screen ready: ",
        "检测到启动阶段超过 30 秒没有进度变化；浏览器仍在运行，但可能卡在资源加载、世界生成或主线程长任务中。":
            "Startup has made no visible progress for 30 seconds. The browser is still running, "
            "but resource loading, world generation, or a long main-thread task may be stalled.",
        "浏览器运行时错误：": "Browser runtime error:",
        "浏览器 Promise 未处理异常：": "Unhandled browser promise rejection:",
        "无法加载 ": "Could not load ",
        "正在加载浏览器持久化存储 IndexedDB…":
            "Loading persistent browser storage...",
        "加载浏览器持久化存储…": "Loading browser storage...",
        "浏览器存储已就绪（": "Browser storage is ready (",
        "），正在加载 classes.js…": "); loading classes.js...",
        "加载 1.21.11 TeaVM 主程序…": "Loading the 1.21.11 client runtime...",
        "TeaVM 产物未暴露 main(args)，无法启动。":
            "The TeaVM build did not expose main(args).",
        "已加载 classes.js，调用 net.minecraft.client.main.Main.main(args)…":
            "classes.js loaded; calling net.minecraft.client.main.Main.main(args)...",
        "启动 Minecraft 客户端…": "Starting Gaius Client...",
        "等待 Minecraft 首帧/主界面…": "Waiting for the first client frame...",
        "启动 Minecraft TeaVM 客户端失败：": "Gaius Client failed to start:",
    }
    for source, replacement in english_launcher_text.items():
        text = text.replace(source, replacement)

    text = re.sub(
        r'Starting Gaius Client [A-Za-z0-9._+-]+\.\.\.',
        f'Starting Gaius Client {minecraft_version}...',
        text,
        count=1,
    )
    text = re.sub(
        r'Loading the [A-Za-z0-9._+-]+ client runtime\.\.\.',
        f'Loading the {minecraft_version} client runtime...',
        text,
        count=1,
    )
    text, version_argument_count = re.subn(
        r'("--version", ")[^"]+("\s*,)',
        rf'\g<1>{minecraft_version}\g<2>',
        text,
        count=1,
    )
    if version_argument_count != 1:
        raise RuntimeError("index.html patch point was not found: Minecraft version argument")
    text, asset_index_argument_count = re.subn(
        r'("--assetIndex", ")[^"]+("\s*,)',
        rf'\g<1>{asset_index_id}\g<2>',
        text,
        count=1,
    )
    if asset_index_argument_count != 1:
        raise RuntimeError("index.html patch point was not found: asset index argument")

    text = patch_storage_persistence(text, selected_profile)
    text = apply_gaius_client_shell(text)
    text = apply_gaius_boot_art(text)
    text = apply_gaius_touch_controls(text)
    text = apply_gaius_world_archive(text)
    text = patch_release_version(text)
    text = patch_shader_toolchain_loader(text, classes_js, index, minecraft_version)

    # A page an earlier run wrote with CRLF (Windows newline translation) is rewritten as LF:
    # the Pages and portable builders match its tags byte-for-byte.
    if text != original or b"\r\n" in index.read_bytes():
        index.write_text(text, encoding="utf-8", newline="\n")
        return True
    return False


def version_defaults() -> tuple[str, str]:
    minecraft_version = os.environ.get("GAIUS_MINECRAFT_VERSION", "").strip()
    asset_index_id = os.environ.get("GAIUS_ASSET_INDEX_ID", "").strip()
    metadata_path = os.environ.get("GAIUS_VERSION_METADATA", "").strip()
    if not minecraft_version:
        try:
            root = Path(__file__).resolve().parents[2]
            config = json.loads((root / "port" / "config.json").read_text(encoding="utf-8"))
            relative_profile = (
                os.environ.get("GAIUS_VERSION_PROFILE_PATH")
                or config.get("versionProfile")
            )
            profile = json.loads(
                (root / "port" / relative_profile).read_text(encoding="utf-8")
            )
            minecraft_version = str(profile.get("id") or "").strip()
            if not metadata_path:
                metadata_path = str(root / "port" / "work" / minecraft_version / "version.json")
        except (OSError, ValueError, TypeError, AttributeError):
            minecraft_version = ""
    # No hard-coded fallback: main() refuses to patch without a version.
    if not asset_index_id and metadata_path:
        try:
            metadata = json.loads(native_external_path(metadata_path).read_text(encoding="utf-8"))
            asset_index_id = str(
                metadata.get("assetIndex", {}).get("id") or metadata.get("assets") or ""
            ).strip()
        except (OSError, ValueError, TypeError):
            asset_index_id = ""
    return minecraft_version, asset_index_id or minecraft_version


def main(argv: list[str]) -> int:
    if len(argv) not in (3, 4, 5):
        print(
            "usage: postprocess-index-html.py <index.html> <classes.js> "
            "[<minecraft-version> [<asset-index-id>]]",
            file=sys.stderr,
        )
        return 2

    index = Path(argv[1])
    classes_js = Path(argv[2])
    minecraft_version, asset_index_id = version_defaults()
    explicit_asset_index = len(argv) == 5
    if len(argv) in (4, 5):
        minecraft_version = argv[3].strip()
        asset_index_id = argv[4].strip() if explicit_asset_index else ""
    if not minecraft_version:
        print(
            "postprocess-index-html.py: cannot determine the Minecraft version; pass it"
            " or set GAIUS_MINECRAFT_VERSION / GAIUS_VERSION_PROFILE_PATH",
            file=sys.stderr,
        )
        return 2
    try:
        profile = load_profile_for_version(minecraft_version)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    if not explicit_asset_index:
        official = profile.get("official")
        profile_asset_index = official.get("assetIndexId") if isinstance(official, dict) else None
        if isinstance(profile_asset_index, (str, int)) and str(profile_asset_index):
            asset_index_id = str(profile_asset_index)
    if not asset_index_id:
        asset_index_id = minecraft_version
    identifier = re.compile(r"^[A-Za-z0-9._+-]+$")
    if not identifier.fullmatch(minecraft_version) or not identifier.fullmatch(asset_index_id):
        print("invalid Minecraft version or asset index identifier", file=sys.stderr)
        return 2
    if not index.exists():
        print(f"missing index.html: {index}", file=sys.stderr)
        return 1
    if not classes_js.exists():
        print(f"missing classes.js: {classes_js}", file=sys.stderr)
        return 1

    changed = patch_index(index, classes_js, minecraft_version, asset_index_id, profile)
    status = "Patched" if changed else "Index already patched"
    print(f"{status}: {index}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
