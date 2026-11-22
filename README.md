<div align="center">

<img src="docs/assets/gaius-icon.svg" alt="Gaius" width="128" height="128">

# Gaius

**An unofficial browser client for Minecraft: Java Edition, built on WebAssembly and WebGPU.**

[![Release](https://img.shields.io/badge/release-v0.4.0--pre.6-5a2a22?style=flat-square)](VERSION)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.3%20%7C%2026.2%20%7C%201.21.11-3f7d3a?style=flat-square)](#supported-versions)
[![Browser](https://img.shields.io/badge/browser-Chrome-4285f4?style=flat-square&logo=googlechrome&logoColor=white)](#requirements)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![Discord](https://img.shields.io/badge/discord-join-5865f2?style=flat-square&logo=discord&logoColor=white)](https://dsc.gg/gaius)

[Features](#features) ·
[Supported versions](#supported-versions) ·
[Get started](#get-started) ·
[Gaius Patcher](#gaius-patcher) ·
[FAQ](#faq) ·
[Contributing](CONTRIBUTING.md) ·
[简体中文](README.zh-CN.md)

</div>

> **Not an official Minecraft product. Not approved by or associated with
> Mojang or Microsoft.** You must own Minecraft: Java Edition and follow the
> [Minecraft EULA](https://www.minecraft.net/eula). This repository contains
> no game code, no game files and no builds, and Gaius provides no downloads.
> Some build tools can fetch required inputs directly from upstream sources.
> See the [disclaimer](DISCLAIMER.md).

## What is Gaius?

Gaius runs Minecraft: Java Edition in your browser. The game code is compiled
to WebAssembly, rendered with WebGPU (with a WebGL2 fallback), and keeps the
behaviour of the Java Edition: world generation, saves, ticks and rendering
are meant to match the original.

This repository holds Gaius's own source code (the browser runtime and UI, the
Rust kernels, the bytecode patches and the build scripts), the
[**Gaius Patcher**](#gaius-patcher), which builds a personal copy of Gaius on
your own computer from your own copy of the game, and the Gaius issue tracker.
The source matches Gaius release **v0.4.0-pre.6**.

## Features

- **Faithful to the Java Edition.** Same world generation, saves and game
  logic; no reduced render distance, entities or particles.
- **Modern graphics stack.** WebGPU renderer with a WebGL2 fallback.
- **Three game versions.** 26.3, 26.2 and 1.21.11, each with its own pinned
  version profile.
- **Multiplayer through relays.** Connect to Java servers from the browser
  through a relay (see [docs/relay-nodes.md](docs/relay-nodes.md)).
- **Touch controls and quality presets** for phones, tablets and Chromebooks.
- **Two ways to run.** A site folder or a single self-contained HTML file.
- **Built for new versions.** Game-specific code lives in per-version
  profiles; adding a version only needs a thin adapter layer
  ([docs/new-version.md](docs/new-version.md)).
- **Open source.** Everything Gaius wrote is MIT licensed.

Mods are not supported.

## Supported versions

| Minecraft | Status |
| --- | --- |
| 26.3 | Supported |
| 26.2 | Supported |
| 1.21.11 | Supported |

## Requirements

- A recent desktop or mobile **Chrome** with WebAssembly GC and JavaScript
  Promise Integration (JSPI) support. Other browsers are not supported.
- WebGPU is used when available; otherwise Gaius falls back to WebGL2.
- A legitimate copy of Minecraft: Java Edition installed with the official
  launcher.

## Get started

**This public tree cannot build the game on its own.** The overlay build also
requires the omitted light and Unihex generator sources. See the
[local overlay recipe](patcher/recipes/README.md#local-bridge-generation).

Gaius provides no downloads, so you build your own copy with the patcher
below once you have all the build inputs:

1. Install and start each version you want once in the official Minecraft
   launcher.
2. Run the Gaius Patcher on your computer (see the next section).
3. Open the site folder or the single HTML file it writes in Chrome.

## Gaius Patcher

The patcher reads the official game files from your own launcher
installation, checks each one against a pinned SHA-1, applies Gaius's patches
and compiles the result for the browser, all on your machine. It never
downloads the game, and the build it writes is for your own use only.

```sh
java patcher/GaiusPatcher.java doctor        # check Java, memory, disk and your installation
java patcher/GaiusPatcher.java terms         # read the terms of use
java patcher/GaiusPatcher.java fetch 26.3    # collect and verify the files for one version
java patcher/GaiusPatcher.java prepare 26.3  # apply Gaius's source patches to your own classes
```

Before it reads any game file, the patcher asks you to accept its
[terms of use](patcher/TERMS.md). Today it implements `doctor`, `fetch` and
`prepare`; `build` is being wired up. Full instructions:
[patcher/README.md](patcher/README.md).

**Do not share, upload or host your build.** If friends want to play, each of
them runs the patcher with their own copy of the game.

## FAQ

**Where can I download Gaius?**
Nowhere. Gaius provides no downloads, hosted builds or game accounts. Build
your own copy with the patcher. Requests for builds are closed.

**Is it legal?**
Gaius contains no Mojang code or assets; the patcher works on files from your
own purchased copy, for your personal use. Read the
[disclaimer](DISCLAIMER.md) and the [terms of use](patcher/TERMS.md).

**Does it support mods?**
No.

**Which browsers work?**
Chrome only.

**How do I report a bug?**
Open an issue with the bug report form and include the browser and version,
the Minecraft profile (26.3 / 26.2 / 1.21.11), whether you use the site or the
single-file page, the graphics backend shown in the debug screen (WebGPU /
WebGL2), steps to reproduce, and a screenshot or the browser console log if
possible. Never post game files, builds, decompiled game code or links to any
of them. Read the [issue rules](CONTRIBUTING.md) first.

## Contributing

Bug reports, feature requests and pull requests are welcome; please read
[CONTRIBUTING.md](CONTRIBUTING.md) first. Questions and chat: the
[Gaius Discord](https://dsc.gg/gaius). Progress is posted in the pinned
*Progress updates* issue.

## License and rights holders

Gaius's own code is MIT licensed (see [LICENSE](LICENSE); third-party notices
are in [NOTICE](NOTICE) and [LICENSES/](LICENSES)). To report content that
infringes your rights, see [Rights holders](DISCLAIMER.md#rights-holders).
