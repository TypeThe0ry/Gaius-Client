<div align="center">

<img src="docs/assets/gaius-icon.svg" alt="Gaius" width="96" height="96">

# Gaius（简体中文）

[English](README.md) · [贡献指南](CONTRIBUTING.md) · [免责声明](DISCLAIMER.md)

</div>

> **Gaius 不是 Minecraft 官方产品，未经 Mojang 或 Microsoft 认可，也与其无关。**
> 你必须拥有 Minecraft: Java Edition 并遵守
> [Minecraft EULA](https://www.minecraft.net/eula)。本仓库不包含游戏代码、游戏
> 文件或构建，Gaius 也不提供下载。详见[免责声明](DISCLAIMER.md)。

Gaius 是兼容 Minecraft: Java Edition 26.3、26.2 和 1.21.11 的非官方浏览器客户端
（WebAssembly + WebGPU，WebGL2 回退，仅支持 Chrome）。本仓库包含 Gaius 自己的源码、
**Gaius 补丁工具**（Patcher）和 issue 追踪。源码对应 Gaius v0.4.0-pre.6。

## 补丁工具

补丁工具从你自己的启动器安装中读取官方游戏文件，逐个校验 SHA-1，打上 Gaius 的
补丁并编译成浏览器版本，全部在你的电脑上完成。它从不下载游戏，生成的构建只供你
本人使用。先在官方启动器中安装并启动一次对应版本；读取任何游戏文件之前，补丁
工具会要求你接受[使用条款](patcher/TERMS.md)。**请不要分享、上传或托管你的构建。**
完整说明见 [patcher/README.md](patcher/README.md)（英文）。

## Issue

- **问题反馈**、**功能建议**，以及置顶 *Progress updates* issue 中的**开发进度**。
- 请不要发布游戏文件、构建、反编译的游戏代码或它们的链接，也不要索要或提供构建。
  发帖前请阅读 [issue 规范](CONTRIBUTING.md)。

## 许可

Gaius 自己的代码使用 MIT 许可证（见 [LICENSE](LICENSE)）。权利人联系方式见
[免责声明](DISCLAIMER.md#rights-holders)。
