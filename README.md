# Gaius-Client

Gaius is an unofficial browser client compatible with Minecraft: Java Edition
26.3, 26.2 and 1.21.11 (WebAssembly and WebGPU). This repository holds Gaius's
own source code (the browser runtime and UI, the Rust kernels, the bytecode
patches and the build scripts), the **Gaius Patcher**, which builds a personal
copy of Gaius on your own computer from your own copy of the game, and the
Gaius issue tracker. The source matches Gaius release v0.4.0-pre.6.

https://dsc.gg/gaius

> **Not an official Minecraft product. Not approved by or associated with
> Mojang or Microsoft.** You must own Minecraft: Java Edition and follow the
> [Minecraft EULA](https://www.minecraft.net/eula). This repository contains
> no game code, no game files and no builds. Gaius does not distribute
> game files or ready-made builds.
> See the [disclaimer](DISCLAIMER.md).
>
> **Gaius 不是 Minecraft 官方产品，未经 Mojang 或 Microsoft 认可，也与其无关。**
> 你必须拥有 Minecraft: Java Edition 并遵守
> [Minecraft EULA](https://www.minecraft.net/eula)。本仓库不包含游戏代码、游戏
> 文件或构建。Gaius 不分发游戏文件或成品构建。详见[免责声明](DISCLAIMER.md)。

## Gaius Patcher

The patcher reads the official game files from your own launcher
installation, checks each one against a pinned SHA-1, applies Gaius's patches
and compiles the result for the browser, all on your machine. The patcher
collects game inputs locally, and its output is for your own use only.
Separate maintenance scripts can contact official Mojang/Minecraft endpoints
for metadata, game files and assets. See the [disclaimer](DISCLAIMER.md)
for the scope of those scripts.

```sh
java patcher/GaiusPatcher.java doctor      # check Java, memory, disk and your installation
java patcher/GaiusPatcher.java terms       # read the terms of use
java patcher/GaiusPatcher.java fetch 26.3  # collect and verify the files for one version
java patcher/GaiusPatcher.java prepare 26.3  # apply Gaius's source patches to your own classes
```

Install and start each version once in the official launcher first. Before
it reads any game file, the patcher asks you to accept its
[terms of use](patcher/TERMS.md). The patcher also implements `build` for
the TeaVM JavaScript pipeline. The public source export has not yet passed
an end-to-end build check. See [patcher/README.md](patcher/README.md) for
commands and input requirements.

**可复现构建门禁状态：NOT-YET（进行中）**

The reproducible-build gate is still in progress (`NOT-YET`). Byte-for-byte
reproduction of the release builds has yet to be verified.

**Do not share, upload or host your build.** If friends want to play, each of
them runs the patcher with their own copy of the game.

### Excluded third-party overrides, fixtures and shader toolchain

No third-party-derived overrides, vendored third-party test packages, or
prebuilt shader toolchain binaries are included in this repository (no
exceptions). Each external component must be generated locally at build time or
fetched from its public upstream source with SHA verification. Any item whose
public download URL, local generation recipe, or accompanying notice bundle is
not yet verified is marked **pending verification** and excluded.

| Component | Acquisition method | Upstream source | Recorded license |
| --- | --- | --- | --- |
| TeaVM 0.15 / Apache Harmony classlib overrides (excluded) | Local build-time generation / patching from pinned upstream JAR (`org.teavm:teavm-classlib:0.15.0`); local generation recipe **pending verification** | [TeaVM](https://github.com/konsoletyper/teavm), [Apache Harmony](https://harmony.apache.org/), Maven Central `org.teavm:teavm-classlib:0.15.0` | Apache-2.0 |
| Gson overrides (`UnsafeAllocator`, `SqlTypesSupport`, excluded) | Local build-time generation / patching from pinned upstream JAR; local generation recipe **pending verification** | [Gson](https://github.com/google/gson) | Apache-2.0 |
| SLF4J helper override (`Util`, excluded) | Local build-time generation / patching from pinned upstream JAR; local generation recipe **pending verification** | [SLF4J](https://github.com/qos-ch/slf4j) | MIT |
| jopt-simple override (`Columns`, excluded) | Local build-time generation / patching from pinned upstream JAR; local generation recipe **pending verification** | [jopt-simple](https://github.com/jopt-simple/jopt-simple) | MIT |
| LWJGL 3.4.3 overrides (`AL`, `ALC`, `ALUtil`, `GL`, `GLCapabilities`, `STBImage`, `BrowserMemoryBackend`, excluded) | Local build-time generation / patching from pinned upstream JARs; local generation recipe **pending verification** | [LWJGL 3](https://github.com/LWJGL/lwjgl3) | BSD-3-Clause |
| `mojang-logging` (`LogUtils`, excluded) | Local build-time generation from user's local library JAR; local generation recipe **pending verification** | Official Minecraft library `com.mojang:logging` | MIT |
| `fake-indexeddb` 6.2.7 (test-only fixture for `indexeddb-storage-atomicity-test.mjs`, excluded) | Fetch upstream npm tarball locally and verify SHA-256 `602ec82593f3491c1fcd491b8374bc4fce74ca490745dc88fd9b8ae1f66eda81` | [npm tarball](https://registry.npmjs.org/fake-indexeddb/-/fake-indexeddb-6.2.7.tgz) | Apache-2.0 |
| 26.3 shader toolchain prebuilt bundle (excluded) | Build locally from pinned upstream sources (`port/scripts/build-wasm-shader-toolchain.sh`) or supply a verified local copy (`--shader-toolchain` / `--toolchain-dir`); public prebuilt bundle URL and notice bundle **pending verification** (`status: planned`) | See pinned shader compiler sources below | Bundle notice **pending verification**; component licenses below |

Pinned upstream shader compiler sources and component license records:

| Source | Recorded license |
| --- | --- |
| [shaderc](https://github.com/google/shaderc/tree/2c8cae778eec0283b44acbe7ed1a386865d78799) | Apache-2.0 |
| [glslang](https://github.com/KhronosGroup/glslang/tree/168d452a4f460d24b588fed08477a81c44ee27a1) | BSD-3-Clause core, with BSD-2-Clause, MIT, Apache-2.0, NVIDIA terms and GPL-3.0-or-later with the Bison exception for the generated parser |
| [SPIRV-Tools](https://github.com/KhronosGroup/SPIRV-Tools/tree/b707790a898e44038547df54580022fc1cf89c3d) | Apache-2.0 |
| [SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers/tree/29981f65241605e08b0ede4cfeb999fe3b723c6a) | MIT for the headers and grammars used |
| [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross/tree/6c09849fe88c48eaed08413aa022aaa136a3a057) | Apache-2.0 OR MIT for the compiled core, C API and GLSL backend. MIT for the Khronos headers. |
| [Emscripten 6.0.10](https://github.com/emscripten-core/emscripten/tree/d6c521a7f05449857c76bd99e396895583cf2083) | MIT OR NCSA for Emscripten's own code, with separate upstream terms for bundled runtime components |

The SDK archive URLs and SHA-256 values for Linux and Windows are recorded in
[the toolchain lock](patcher/toolchain.lock). Those entries are for the
Emscripten SDK, a separate input from the Gaius shader toolchain ZIP.

## Gaius Patcher（中文）

补丁工具从你自己的启动器安装中读取官方游戏文件，逐个校验 SHA-1，打上 Gaius 的
补丁。补丁工具实现了 TeaVM JavaScript 的 `build` 命令，公开导出树尚未通过端到端
构建验证。先在官方启动器中安装并启动一次对应版本。补丁工具在读取游戏文件前会要求
你接受[使用条款](patcher/TERMS.md)。独立维护脚本可从 Mojang/Minecraft 官方域名
获取元数据、游戏文件和资源，范围见[免责声明](DISCLAIMER.md)。**生成的构建只供本人
使用，请不要分享、上传或托管。**

本仓库**不包含任何第三方派生覆盖、随仓第三方测试依赖或着色器预构建包（不做例外）**，
均改为构建时本地生成，或从上游公开地址拉取并校验 SHA；尚未核清公开下载地址、本地生成配方或随包许可证材料的均标为**待核清**并排除：

- **TeaVM 0.15 / Apache Harmony 类库覆盖**（Apache-2.0，上游 `https://github.com/konsoletyper/teavm`、`https://harmony.apache.org/`、Maven `org.teavm:teavm-classlib:0.15.0`）：构建时基于上游 JAR 本地生成，本地生成配方**待核清**，已排除。
- **Gson 覆盖**（Apache-2.0，上游 `https://github.com/google/gson`）：构建时本地生成，配方**待核清**，已排除。
- **SLF4J 覆盖**（MIT，上游 `https://github.com/qos-ch/slf4j`）：构建时本地生成，配方**待核清**，已排除。
- **jopt-simple 覆盖**（MIT，上游 `https://github.com/jopt-simple/jopt-simple`）：构建时本地生成，配方**待核清**，已排除。
- **LWJGL 3.4.3 覆盖**（BSD-3-Clause，上游 `https://github.com/LWJGL/lwjgl3`）：构建时本地生成，配方**待核清**，已排除。
- **mojang-logging（`LogUtils`）**（MIT，上游 `com.mojang:logging`）：构建时基于用户本地库 JAR 生成，配方**待核清**，已排除。
- **fake-indexeddb 6.2.7**（Apache-2.0，上游 `https://registry.npmjs.org/fake-indexeddb/-/fake-indexeddb-6.2.7.tgz`，SHA-256 `602ec82593f3491c1fcd491b8374bc4fce74ca490745dc88fd9b8ae1f66eda81`）：仅用于 `indexeddb-storage-atomicity-test.mjs` 测试，运行该测试时从上游拉取并校验 SHA-256，已排除。
- **26.3 着色器工具链预构建包**：构建时通过 `port/scripts/build-wasm-shader-toolchain.sh` 从固定上游源码本地编译，或提供通过 `patcher/toolchain.lock` 校验的本地副本；整包公开下载地址及随包许可证与署名材料**待核清**，已排除。组件的固定上游地址及许可证见上表，Emscripten SDK 下载地址及校验值见工具链锁。

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
(26.3 / 26.2 / 1.21.11), the form of your local build, the graphics backend shown in the debug screen (WebGPU / WebGL2), steps to
reproduce, and a screenshot or the browser console log if possible.

## License and rights holders

Gaius's own code is MIT licensed (see [LICENSE](LICENSE)). To report content
that infringes your rights, see [Rights holders](DISCLAIMER.md#rights-holders).
