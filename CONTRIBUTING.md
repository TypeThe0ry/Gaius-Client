# Issue and community rules

These rules keep Gaius free of copyright problems. They apply to issues,
comments, pull requests and discussions in this repository. Please read the
[disclaimer](DISCLAIMER.md) first.

## Never post

- Game files: jars, libraries, assets (textures, sounds, models, fonts,
  language files), mappings or extracted parts of them.
- Builds of the game in any form: patched jars, compiled `.wasm` or `.js`,
  packed assets, site folders, HTML files, or archives that contain any of
  these.
- Minecraft code, whether decompiled, deobfuscated or remapped, even a few
  lines. Name the class or method instead.
- Links to any of the above, including file hosts, cloud drives and websites
  that host a build.
- Account details, access tokens, private server addresses or passwords.

## Do not ask for or offer builds

Gaius does not provide downloads, hosted builds or game accounts, and nobody
may offer them here. Requests for or offers of builds, download links,
"unblocked" sites or accounts are closed and locked without discussion.

## What you can post

- Bug reports and feature ideas, using the issue forms.
- Screenshots or short recordings that show a bug.
- Browser console logs (F12, Console) and the text of error messages, with
  private data removed.
- Gaius's own code.

## Moderation

Maintainers edit or delete content that breaks these rules, without notice.
An automatic check labels issues and comments that look like they contain
files or download links so that a maintainer can look at them. Repeated
violations lead to a block from the repository.

## Pull requests

A pull request may contain only work you wrote yourself and have the right to license under
the MIT License; by opening one you agree to license it that way. Patches
describe changes to the game; they must not contain copied game code or game
files. Pull requests must pass the repository checks, including
`patcher/tools/public_tree_check.py`, which refuses game files, copies of
official files and files in game packages.

---

# Issue 与社区规范

这些规范用来让 Gaius 远离版权问题，适用于本仓库中的 issue、评论、pull request
和讨论。请先阅读[免责声明](DISCLAIMER.md)。

## 禁止发布

- 游戏文件：jar、库、资源（纹理、声音、模型、字体、语言文件）、映射表，或从中
  提取的任何部分。
- 任何形式的游戏构建：打过补丁的 jar、编译后的 `.wasm` 或 `.js`、打包的资源、
  站点文件夹、HTML 文件，或包含上述内容的压缩包。
- Minecraft 代码，无论是反编译、反混淆还是重映射后的代码，哪怕只有几行。请改为
  写出类名或方法名。
- 以上任何内容的链接，包括文件分享站、网盘和托管构建的网站。
- 账号信息、访问令牌、私有服务器地址或密码。

## 不要索要或提供构建

Gaius 不提供下载、托管构建或游戏账号，任何人也不得在这里提供。索要或提供构建、
下载链接、"unblocked" 网站或账号的内容会被直接关闭并锁定。

## 可以发布

- 使用 issue 表单提交问题反馈和功能建议。
- 展示问题的截图或短视频。
- 浏览器控制台日志（F12 → Console）和错误信息文字，请先去掉隐私数据。
- Gaius 自己的代码。

## 管理

维护者会直接修改或删除违反规范的内容，不另行通知。自动检查会给看起来包含文件或
下载链接的 issue 和评论加标签，交给维护者处理。多次违规将被禁止参与本仓库。

## Pull request

pull request 只能包含你本人编写、并有权以 MIT
许可证授权的内容；提交即表示你同意以该许可证授权。补丁描述的是对游戏的修改，
不得包含复制的游戏代码或游戏文件。pull request 必须通过仓库检查，包括
`patcher/tools/public_tree_check.py`，它会拒绝游戏文件、官方文件的副本和游戏包
中的文件。
