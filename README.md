# Gaius-Client

Gaius is an unofficial browser client compatible with Minecraft: Java Edition
26.3, 26.2 and 1.21.11 (WebAssembly and WebGPU). This repository holds Gaius's
own source code (the browser runtime and UI, the Rust kernels, the bytecode
patches and the build scripts), the **Gaius Patcher**, which builds a personal
copy of Gaius on your own computer from your own copy of the game, and the
Gaius issue tracker. The source matches Gaius release v0.4.0-pre.5.

https://dsc.gg/gaius

> **Not an official Minecraft product. Not approved by or associated with
> Mojang or Microsoft.** You must own Minecraft: Java Edition and follow the
> [Minecraft EULA](https://www.minecraft.net/eula). This repository contains
> no game code, no game files and no builds, and Gaius provides no downloads.
> See the [disclaimer](DISCLAIMER.md).
>
> **Gaius 不是 Minecraft 官方产品，未经 Mojang 或 Microsoft 认可，也与其无关。**
> 你必须拥有 Minecraft: Java Edition 并遵守
> [Minecraft EULA](https://www.minecraft.net/eula)。本仓库不包含游戏代码、游戏
> 文件或构建，Gaius 也不提供下载。详见[免责声明](DISCLAIMER.md)。

## Gaius Patcher

The patcher reads the official game files from your own launcher
installation, checks each one against a pinned SHA-1, applies Gaius's patches
and compiles the result for the browser, all on your machine. It never
downloads the game, and the build it writes is for your own use only.

```sh
java patcher/GaiusPatcher.java doctor      # check Java, memory, disk and your installation
java patcher/GaiusPatcher.java terms       # read the terms of use
java patcher/GaiusPatcher.java fetch 26.3  # collect and verify the files for one version
java patcher/GaiusPatcher.java prepare 26.3  # apply Gaius's source patches to your own classes
```

Install and start each version once in the official launcher first. Before
it reads any game file, the patcher asks you to accept its
[terms of use](patcher/TERMS.md). Today it implements `doctor`, `fetch` and
`prepare`; `build` is being wired up. Full instructions: [patcher/README.md](patcher/README.md).

**Do not share, upload or host your build.** If friends want to play, each of
them runs the patcher with their own copy of the game.

## Gaius Patcher（中文）

补丁工具从你自己的启动器安装中读取官方游戏文件，逐个校验 SHA-1，打上 Gaius 的
补丁并编译成浏览器版本，全部在你的电脑上完成。它从不下载游戏，生成的构建只供你
本人使用。先在官方启动器中安装并启动一次对应版本；读取任何游戏文件之前，补丁
工具会要求你接受[使用条款](patcher/TERMS.md)。**请不要分享、上传或托管你的构建。**

## Issues

- **Bug reports**: something does not work, crashes, renders wrong, or behaves
  differently from the Java Edition.
- **Feature requests**: ideas and suggestions.
- **Progress updates**: posted in the pinned *Progress updates* issue.

Never post game files, builds, decompiled game code or links to any of them,
and do not ask for or offer builds. Read the [issue rules](CONTRIBUTING.md)
before you post.

- **问题反馈**、**功能建议**，以及置顶 *Progress updates* issue 中的**开发进度**。
- 请不要发布游戏文件、构建、反编译的游戏代码或它们的链接，也不要索要或提供构建。
  发帖前请阅读 [issue 规范](CONTRIBUTING.md)。

When reporting a bug, include the browser and version, the Minecraft profile
(26.3 / 26.2 / 1.21.11), whether you use the site or the single-file page,
the graphics backend shown in the debug screen (WebGPU / WebGL2), steps to
reproduce, and a screenshot or the browser console log if possible.

## License and rights holders

Gaius's own code is MIT licensed (see [LICENSE](LICENSE)). To report content
that infringes your rights, see [Rights holders](DISCLAIMER.md#rights-holders).
