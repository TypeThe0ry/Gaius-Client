# Disclaimer

**Gaius is not an official Minecraft product. It is not approved by or
associated with Mojang or Microsoft.**

Minecraft is a trademark of Mojang Synergies AB. Gaius uses the name only to
say which game versions it works with. All game code, textures, sounds and
other game content belong to Mojang and Microsoft.

## What this repository contains

Only material written by the Gaius project (with upstream license references
recorded in `NOTICE` and `LICENSES/`): the Gaius patcher, Gaius's own patches,
browser runtime, UI and Rust kernels, version profiles that pin the official
files by hash, documentation and issue templates. Third-party-derived overrides
(TeaVM/Harmony, Gson, SLF4J, jopt-simple, LWJGL, mojang-logging), vendored
`fake-indexeddb`, and prebuilt shader toolchain artifacts are excluded from
this repository and must be generated locally at build time or fetched from
upstream public URLs with SHA verification (see [README](README.md)).

This repository does **not** contain, and will not accept, any of the
following:

- Minecraft code, whether original, decompiled, deobfuscated or remapped;
- game assets (textures, sounds, models, fonts, language files and so on);
- official jars, libraries or mappings;
- builds of the game in any form (patched jars, compiled `.wasm` or `.js`,
  packed assets, site folders, HTML files);
- third-party download links to game files, or links to distributed Gaius builds.

## Owning the game and the Minecraft EULA

Gaius is meant for players who own Minecraft: Java Edition. The patcher reads
game files from your local official launcher installation or locally supplied
inputs. The patcher collects game jars and assets locally. Online preparation
for 1.21.11 can fetch its mapping file from Mojang when not supplied locally. Before it reads any game file it asks you to
accept its [terms of use](patcher/TERMS.md), which include that you own the game and that you follow the
[Minecraft End User License Agreement](https://www.minecraft.net/eula) and the
[Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines).

Separate maintenance scripts use official Mojang/Minecraft endpoints for
version metadata and, depending on the command, client or server jars,
mappings, libraries, or assets. `port/scripts/build-teavm.sh` can fetch missing
assets unless run with `GAIUS_ASSETS_OFFLINE=true`. The scripts run locally and
contain no embedded game files. Some build tools have their own dependency
setup steps.

The prebuilt 26.3 shader toolchain is kept outside this repository. Patcher is
intended to obtain it from a public upstream distribution during build setup.
The archive URL and accompanying license/notice bundle are pending verification.
Current builds require a matching local copy. The [README](README.md) lists the
pinned compiler sources and their recorded licenses.

A build made with the patcher contains content from your copy of the game. It
is for your personal use only: do not share, upload, host publicly, sell or
otherwise distribute it. Gaius does not distribute game files or ready-made builds, and
nobody is authorised to distribute builds on behalf of Gaius. A site or file
that offers a ready-made Gaius build is not run by the Gaius project.

## No warranty

Gaius's own code is provided under the MIT License, "as is", without warranty
of any kind. You use it at your own risk.

## Rights holders

If you believe something in this repository, its issues or its discussions
infringes your rights, contact the maintainer, TypeThe0ry, through GitHub or
the Gaius Discord (https://dsc.gg/gaius). We remove reported material as soon
as we can and do not need a formal notice to act. You can also use
[GitHub's DMCA process](https://docs.github.com/en/site-policy/content-removal-policies/dmca-takedown-policy).

---

# 免责声明

**Gaius 不是 Minecraft 官方产品，未经 Mojang 或 Microsoft 认可，也与其无关。**

Minecraft 是 Mojang Synergies AB 的商标。Gaius 使用这个名字只为说明兼容的游戏
版本。所有游戏代码、纹理、声音及其他游戏内容归 Mojang 和 Microsoft 所有。

## 本仓库包含什么

只包含 Gaius 项目自己编写的内容（外部构建依赖的上游许可证参考列于 `NOTICE` 和
`LICENSES/`）：Gaius 补丁工具、Gaius 自己的补丁、浏览器运行时、界面和 Rust 内核、
按哈希锁定官方文件的版本配置、文档和 issue 模板。所有第三方派生覆盖（TeaVM/Harmony、
Gson、SLF4J、jopt-simple、LWJGL、mojang-logging）、随仓 `fake-indexeddb` 与着色器
预构建产物均不包含在本仓库中，须在构建时本地生成或从上游公开地址拉取并校验 SHA
（详见 [README](README.md)）。

本仓库**不包含**、也不会接受以下任何内容：

- Minecraft 代码，无论是原始、反编译、反混淆还是重映射后的代码；
- 游戏资源（纹理、声音、模型、字体、语言文件等）；
- 官方 jar、库或映射表；
- 任何形式的游戏构建（打过补丁的 jar、编译后的 `.wasm` 或 `.js`、打包的资源、
  站点文件夹、HTML 文件）；
- 游戏文件的第三方下载链接，或已分发的 Gaius 构建链接。

## 拥有正版游戏与 Minecraft EULA

Gaius 面向拥有 Minecraft: Java Edition 的玩家。补丁工具从本地官方启动器安装或
用户提供的本地输入读取游戏 JAR 和资源。在线准备 1.21.11 时，若未提供本地映射表，
补丁工具可从 Mojang 获取。读取游戏文件前，它会要求你
接受[使用条款](patcher/TERMS.md)，其中包括你拥有正版游戏，并遵守
[Minecraft 最终用户许可协议](https://www.minecraft.net/eula)和
[Minecraft 使用准则](https://www.minecraft.net/usage-guidelines)。

独立维护脚本可从 Mojang/Minecraft 官方域名获取版本元数据，部分命令还会获取客户端
或服务端 JAR、映射表、库或资源。`port/scripts/build-teavm.sh` 会在缺少资源时尝试从
官方域名获取，设置 `GAIUS_ASSETS_OFFLINE=true` 可禁用此回退。这些脚本在本机运行，
不内嵌游戏文件。部分构建工具有单独的依赖准备步骤。

用补丁工具生成的构建含有来自你本人游戏副本的内容，只供你个人使用：不得分享、
上传、公开托管、出售或以其他方式分发。Gaius 不分发游戏文件或成品构建，也没有授权任何人
代表 Gaius 分发构建。提供现成 Gaius 构建的网站或文件都不是 Gaius 项目运营的。

26.3 的着色器预构建包不随本仓库分发。计划由 Patcher 在构建准备阶段从上游公开地址
获取，整包地址及随包许可证与署名材料待核清。当前构建需要用户本地提供匹配的副本。
组件的固定上游来源和许可证见 [README](README.md)。

## 不提供担保

Gaius 自己的代码以 MIT 许可证"按原样"提供，不附带任何形式的担保，使用风险由你
自行承担。

## 权利人联系方式

如果你认为本仓库、其 issue 或讨论中的内容侵犯了你的权利，请通过 GitHub 或
Gaius Discord（https://dsc.gg/gaius）联系维护者 TypeThe0ry。我们会尽快删除被
报告的内容，无需正式通知即可处理。你也可以使用
[GitHub 的 DMCA 流程](https://docs.github.com/en/site-policy/content-removal-policies/dmca-takedown-policy)。
