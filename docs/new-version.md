# 新 Minecraft 版本接入手册

## 1. 输入与边界

`port/versions/<id>.json` 是版本选择入口。协议、世界版本、Java release、
库坐标、seam、patchSet、source sets、kernelSemantics 和 shader harness
配方均由 profile 数据决定；不要在 Java、Rust、JS 中新增 Minecraft 版本分支。

先由现有 `fetch-version.sh`、`remap-client.sh` 准备输入，再开始快速检查。
本机不下载；缺少 REQUIRED 阶段的输入或离线依赖时记为 blocking，不代为下载。
仅用于可选差异审核的 native entry、relay、lint、API、golden 或 corpus 基线证据
缺失时记为 review，保留旧表或关闭对应 kernel，并写明下一步。
新版本目录至少包含：

| 路径 | 用途 |
|---|---|
| `port/work/<id>/client-named.jar` | Mojang names、内嵌 `version.json`、class headers |
| `port/work/<id>/client-original.jar` | 用 launcher metadata 的 SHA-1 校验官方输入 |
| `port/work/<id>/version.json` | launcher metadata、releaseTime、坐标及 library SHA-1 |
| `port/work/<id>/libraries/` | metadata 中 artifact.path 对应的离线 jar/native 库 |
| `port/work/<previous>/` | 前一版本 jar 和 metadata，用于迁移比较 |

`port/work` 可能是共享 junction：只读。不要递归遍历它、修改它或清理它。
离线 Maven cache 需要本仓库配置的 TeaVM JSO/interop 及现有 ASM artifacts；
离线 Cargo cache 需要 `port/native/Cargo.lock` 的包。缺失即 blocking。
设置 `GAIUS_JAVA_HOME` / `JAVA_HOME` 选择能处理目标 class release 的 JDK；
设置 `GAIUS_MAVEN_REPOSITORY` 可使用指定离线 cache。

## 2. 快速路径：不编译 TeaVM

```bash
node port/tools/bringup/gaius-bringup.mjs <id> --fast
node port/tools/bringup/gaius-bringup.mjs <id> --fast --from <previous> --out <output-directory>
```

`--from` 固定迁移基线；省略时按官方 releaseTime 选择最近的已有 profile，
无日期时才按数字感知的 ID 顺序选择。snapshot ID 不要求是纯数字版本。
临时 draft 标记 `bringup.provisional=true`，不能充当前一版本或已审核配方；
`bringupAllowed` 仅表示允许带跳过列表的调试模式，不是审核状态，不能混用。
`--root` / `--work` 用于隔离复制目录；输出拒绝指向共享 work 或其 junction 目标。

默认生成 `port/mc/profiles/<id>/bringup-report.json` 和 `.md`，以及
`profile.draft.json`、独立 `bringup-evidence-*` 目录和离线 Cargo target。
不会安装/覆盖 `port/versions/<id>.json`，不会重写旧 lock，不运行 profilegen
`--write`，不会改变默认 JavaScript dist。

依次执行：

1. JarProbe 从 jar 内 `version.json`、class major 读取协议、世界/pack 版本及
   Java release；从 launcher metadata 提取库坐标、官方 SHA-1，建立独立存储命名空间。
   通过 `seam.json` 中全部 SPI 类的存在性匹配唯一已知 seam generation。
   继承匹配 generation 的最近已审核配方，kernel 初始全部关闭。
2. 确认 named Minecraft entry 存在，校验 original jar 与每个 library 的 SHA-1，
   执行 draft profile schema/seam 检查。比较 named/original 的 version metadata；官方
   named distribution 还按排序后的 class 内容 SHA-256 验证等价，允许剥离 jar 签名。
   此项不是重新执行 remapper；obfuscated distribution 不宣称逐方法映射证明。
3. 编译小型 seam adapter，运行 seamprobe；比较 aggregate signature/body 与
   16-hex 明细指纹。另列前一 jar 消失的类、唯一 simple-name relocation 建议，
   以及前一 patch manifests 的改写 owner 消失情况。建议不是自动重命名证明。
4. 运行 extrefs，比较 section + coordinate + class/method/field usage；不把
   profile ID、section 计数或文本 header 误当成 usage 变化。
5. patchkit 用 draft 和当前 run 的 usage lock，在独立副本上检查 js/wasmgc
   两个 target，并分别与前一已审核 manifest 做 diff；不构建整个客户端。
6. kernelmirror 校验前一 lock 的 profile SHA-256，重算前一/当前 seam 配方的镜像方法并
   比较 lock；方法、语义或依赖变化自动关闭对应 kernel，继续走 vanilla Java。
   前一 profile 已关闭的 kernel 不因新 lock 自动打开；依赖关闭传递到下游。
7. 按 profile 的 `bringup.shader` 配方在 JVM dump 实际 shader corpus，必要时
   做 frontend link；用 native Rust translator 执行 pass/fail。Cargo 使用
   `--offline --locked -j 2`；Java heap 不超过 `-Xmx3g`。
8. sourcesets 对 src/client × js/wasmgc 解析，输出文件/来源/relocation 清单。
   解析成功不等于 Java 编译成功。

以下轻量审核同样属于 `--fast`；无需 TeaVM 游戏构建或 Chrome：

| 阶段 | 离线证据和工具 | 未通过或缺失时的分类与降级 |
|---|---|---|
| `native-entry` | 复用 extrefs `usage.lock`，按 native-library section 生成入口差异与 seam `requiredEntries` 检查，写入 `native-entry.json` | optional 变化或证据缺失为 review，保留已有 adapter；仅已声明 REQUIRED 入口缺失为 blocking；不宣称 native/browser smoke 通过 |
| `relay-protocol` | `port/scripts/build-relay-protocol.mjs` 校验 profile 与 `apps/bridge/protocols/<protocol>.json` 并生成独立 `relay-protocol.js` 及 `relay-protocol.json` | review；保留前一已审核表，新 profile 不据此开启联机支持 |
| `version-lint` | `port/scripts/quickcheck/lint_version_literals.py --enforce`，覆盖验证脚本，写入 `version-literals.json` | review；报告实际命中文件，修复 profile 派生默认值或有理由的精确豁免 |
| `apidiff` | 复用 extrefs `apilock` 检查 library override 的 `api.lock`，附带 section、coordinate、class/method/field usage 差异，生成 `apidiff.json` | API 变化或基线/工具证据缺失为 review；按 owner 定位 adapter、patch 或库配方 |
| `kernel-golden` | 复用 `port/native/golden/run-golden.sh --verify <id>` 和 `port/native/fixtures/<id>/`，在独立 `GOLDEN_BUILD_ROOT` 重新生成 fixtures，逐文件比较前版 hashes，写入 `kernel-golden.diff.json` | 差异、验证失败或缺失 fixture 为 review/auto-disabled；无法定位或验证全部影响时保守关闭所有 kernel，保持 vanilla Java |
| `shader-corpus` | 用既有 dump/link 配方离线提取前一版本 corpus，与本轮 JVM corpus 的 pipeline/module/layout 摘要形成 `shader-corpus-baseline.json` | 差异或 baseline 缺失为 review；保留已审核 corpus，补齐证据后再审核 |

只有 REQUIRED 项失败会阻塞；可选审核失败不会因统一异常处理变成 blocking。
patchkit 中仅 `REQUIRED` 失败可阻塞，`PERF`、`FEATURE`、`TELEMETRY` 失败记为
review/disabled。kernel 被 decline 时记为 review/auto-disabled，继续 vanilla Java。
所有分类均保留失败原因，并尽可能继续独立阶段。
子进程按剩余 20 分钟预算限时，所有日志和证据留在输出目录。
文件读取、报告写入和系统进程终止开销不保证是硬实时 20 分钟。
`--draft-only` 只做 profile/remap bootstrap，报告明确显示余下检查待完成。

## 3. automatic / review / blocking 决策

| 分类 | 含义及动作 |
|---|---|
| automatic | 数据提取、校验或检查完成，无迁移差异；保留证据，不据此发布 |
| review | 库/usage/API/patch/corpus 变化、body-only SPI 变化、可选证据缺失、可选检查失败、kernel 自动关闭、同 jar 别名等；按报告的文件与动作逐项审核 |
| blocking | REQUIRED 阶段失败：未知/歧义 seam、SPI signature/generation 变化、已声明 REQUIRED native 入口缺失、REQUIRED patch 失败、必需 jar/cache 缺失、输入校验或必需 shader translation 失败；修复后重跑 |

退出码：`0` 全 automatic，`1` 有 review 且无 blocking，`2` 有 blocking。
`noBlockingItems=true` 与退出 `0` 不同：同 jar 新 ID 会有 alias review，仍可零阻塞。
shader native translation 通过不证明 WebGPU 渲染；lock 相同不证明 golden parity；
fast 报告始终把 TeaVM、Chrome、parity、promotion 的 claims 标为 false。
`status=FAIL` 本身不表示 blocking；必须同时检查 `required` 与 `classification`。
`auto-disabled` 描述降级动作，报告分类仍为 review，不计入 REQUIRED failures。

修复流程：先适配 seam 和 source sets，再处理 REQUIRED patches；FEATURE/PERF 等
失效时按 patchkit 策略禁用，不把失败静默当作正常。镜像方法变化时保持 kernel
关闭，更新实现后用目标版本 vanilla JVM golden fixtures 做 bit-exact parity。
不要用容差近似、旧版本 fixtures 或新 lock 代替 parity。

审核后才安装 draft、审核 kernelSemantics / source set 配方，使用各工具显式
refresh 检查 lock/manifest。用 profilegen 的正常审核流程维护版本数据和派生表；
fast 工具不会把未审核版本加入 JS allowlist，也不会自行提升为 release。

## 4. `--full`：审核后的构建和实际 acceptance

`gaius-bringup.mjs <id> --full --from <previous>` 是 release-day 入口：先执行全部
fast 步骤，再执行审核所需的 patchkit lock diff、SPI fingerprint、apidiff/extrefs、kernel
golden、shader corpus、版本字面量/可达性 lint、relay 表生成复检。上述轻量摘要已纳入
fast；full 的额外执行不改变 fast 对可选证据缺失的 review 和自动降级语义。报告 JSON/MD 的
每项都带 `PASS`、`REVIEW`、`AUTO_DISABLED`、`FAIL` 或 `SKIPPED`、实际原因和 `required`；退出码只有
required 项失败时为 `2`，资源缺失按声明的 optional gate 记 SKIPPED，不伪装成 PASS。
patchkit 的差异分类保留 auto-carry、review target-changed 和 required-failed；golden
差异会为对应 kernel 自动 decline，继续 vanilla Java。`--build` 才启用 WasmGC
client/server 构建（设置了 `GAIUS_SLOT` 时经该 slot.sh 包装串行）和 `WebAssembly.validate`
检查；默认不构建、不启动 Chrome。

下面为 full 构建的命令模板，不要与其他完整构建并发运行。
`ID` 来自已审核 profile；`OUT` 是独立完整构建目录；`GAIUS_SLOT` 可选，指向构建机上串行化
构建/Chrome 的包装脚本（接口 `build|chrome <tag> -- <command>`，与 `gaius-bringup.mjs --build`
读取的变量相同），未设置时下面的 `slot` 函数直接运行命令。
先确认 Maven、Rust、Binaryen、字体/资源等已缓存；现有构建脚本可能获取缺少的
字体或 Maven wrapper，故无下载权限时必须先补齐缓存，否则停止。

```bash
export GAIUS_VERSION_PROFILE_PATH="versions/$ID.json"
export CARGO_NET_OFFLINE=true CARGO_BUILD_JOBS=2 MAVEN_ARGS=--offline
export GAIUS_TEAVM_MAX_HEAP=<heap>   # 按构建机内存选择；默认 14g。设置 MAVEN_OPTS 会覆盖编译器 JVM 参数
slot() { local kind="$1" tag="$2"; shift 3; if [ -n "${GAIUS_SLOT:-}" ]; then bash "$GAIUS_SLOT" "$kind" "$tag" -- "$@"; else "$@"; fi; }
export GAIUS_BUILD_ROOT="$OUT/js-build"
export GAIUS_DIST_DIRECTORY="$OUT/js-dist"
export GAIUS_OVERLAY_DIRECTORY="$OUT/js-overlays"
slot build "bringup-$ID-js" -- bash port/scripts/build-version-release.sh "$ID"

export GAIUS_WASMGC_RESOURCE_ROOT="$OUT/js-build"
export GAIUS_WASMGC_RESOURCE_DIST="$OUT/js-dist"
export GAIUS_BUILD_ROOT="$OUT/wasmgc-build"
export GAIUS_DIST_DIRECTORY="$OUT/wasmgc-dist"
export GAIUS_OVERLAY_DIRECTORY="$OUT/wasmgc-overlays"
slot build "bringup-$ID-wasm-client" -- bash port/scripts/build-wasmgc.sh --role client "$ID"
slot build "bringup-$ID-wasm-worker" -- bash port/scripts/build-wasmgc.sh --role singleplayer-worker "$ID"
bash port/scripts/run-python.sh port/scripts/build-portable-html.py \
  "$OUT/js-dist" "$OUT/wasmgc-dist/Gaius-wasm.html" --wasm-dist "$OUT/wasmgc-dist"

GAIUS_FILE_DIST="$OUT/js-dist" GAIUS_FILE_MODE=single GAIUS_FILE_OUTPUT="$OUT/acceptance-js.json" \
  slot chrome "bringup-$ID-js" -- node port/scripts/browser-file-entry-acceptance.mjs --runtime js
GAIUS_FILE_DIST="$OUT/wasmgc-dist" GAIUS_FILE_MODE=single GAIUS_FILE_OUTPUT="$OUT/acceptance-wasm.json" \
  slot chrome "bringup-$ID-wasm" -- node port/scripts/browser-file-entry-acceptance.mjs --runtime wasm
```

`MAVEN_ARGS=--offline` 要求现有 Maven 支持该环境变量；有 `GAIUS_SLOT` 时 build 与 Chrome
分别串行。完整客户端在较小的 heap 下可能 OOM，失败就报告，并按构建机容量调整
`GAIUS_TEAVM_MAX_HEAP`，不要在受限的共享机器上擅自提高。日志 BUILD_EXIT、wasm sidecar/hash、实际 runtime、
世界进入/terrain draw、worker tick、singleplayer 和 multiplayer/skin/LAN 的
产品验收均需要独立检查；title-only 或 HTTP 200 不足以验收。上述模板的单人
acceptance 不是全部发布 gates；按现有发布手册补充 multiplayer 和协议/世界兼容检查。

## 5. 六小时 snapshot CI

`.github/workflows/snapshot-bringup.yml` 每六小时 UTC 第 17 分触发，也可手动触发。
默认关闭：只有 repository variable `GAIUS_SNAPSHOT_BRINGUP_ENABLED=true` 才运行。
调度并非保证准点；CI 不提交、不推送、不发布。

CI-only 脚本读取 Mojang manifest 的 `latest.snapshot`，校验所选 metadata SHA-1，
获取对应 client 并校验 SHA-1；从 jar 建立 provisional profile，调用现有
`fetch-version.sh` / `remap-client.sh` 准备该版本、前一版本及 shader 配方依赖，
然后运行同一个离线 `--fast`。已审核 ID 不覆盖 profile，改为对自身审核基线复检。
`latest.snapshot` 有时指向已审核 release，不将它误报成新 profile。

workflow 恢复离线 Maven/Cargo caches，key 由 Cargo.lock/port config 决定；首次
启用前要有暖 cache，cache 冷启动/被清理会 blocking，不能宣称 snapshot 兼容。
可先设置 `GAIUS_SNAPSHOT_BRINGUP_RUNNER` 为已有暖缓存的 Linux runner label；
首次成功运行会保存相同 Linux cache key，随后恢复默认 `ubuntu-latest`。
该变量不设置时仍使用 Ubuntu runner，不猜测或写入任何机器身份。
未缓存或不支持的新 JDK/class format 同样需人工更新运行环境。
只有 CI acquisition 可联网；`--fast` 无下载动作。review 退出转换为 CI 成功并
保留报告，blocking 保留非零；两者都不提升 profile。初始化失败报告确保 acquisition
或 bootstrap 提前失败也有 artifact；实际 fast 结果替换该占位报告。

artifact 保存 JSON/MD、draft、diffs、locks、日志、translator results，不上传 jar、
native binary 或完整 Cargo target；保留 14 天。未知 shader ABI/SPI 记 blocking，
不猜 generation、不吞 REQUIRED 失败、不照搬新 kernel 语义。

## 6. 可复现验证与历史迁移范围

测试：

```bash
node --test port/tools/bringup/bringup.test.mjs port/tools/bringup/native-api.test.mjs
node port/tools/patchkit/patchkit.mjs --self-test
node port/tools/patchkit/patchkit.mjs lint
node port/tools/profilegen/profilegen.mjs --check
```

2026-10-08 的实际证明留在 `<out>/bringup`：

- `scratch` 是源码隔离复制，不是另一 git worktree；`26.4` 是 `26.3` 的独立 jar
  复制和只读 library junction，不是真实官方 `26.4`。命令为
  `node <output>/scratch/port/tools/bringup/gaius-bringup.mjs 26.4 --fast --from 26.3`。
  默认报告路径在 scratch 的 `port/mc/profiles/26.4`；期望零 blocking、alias review。
- 真实迁移命令为 `node port/tools/bringup/gaius-bringup.mjs 26.3 --fast --from 26.2
  --out <output>/migration`。它比较前一 lock/manifests 和前一 vanilla jar，而不是
  因当前 checkout 已修复就隐去历史 SPI、类迁移、依赖和 kernel 变化。
- 当前 checkout 的 patchers 已完成该迁移，因此此命令的 REQUIRED 失败为零，
  不应声称重新复现历史初始六个 patch 失败。前一 manifest owner inventory 只覆盖
  实际改写 owner；架构迁移记录的更广 precheck census（60 个 owner、32 个建议）
  不是同一统计口径。
- 实跑的稳定迁移数据：549 个旧 class 消失、194 个唯一名字 relocation 建议，
  前一 manifest 44 个 owner 消失/15 个建议；usage 新增 1537/消失 1365；
  kernel 镜像方法 UNCHANGED 3498、BODY 127、SIGNATURE 1895、GONE 1010，
  五个 kernel 全关闭；native shader 216/216 pipelines、175/175 modules 通过。

最后一轮 alias 用时 68.792 秒：automatic 10 / review 1 / blocking 0，exit 1；
真实迁移用时 64.205 秒：automatic 4 / review 6 / blocking 2，exit 2。
两项 blocking 分别为前一 SPI generation 迁移和 SPI signature 变化，不是工具执行失败。
`proof-summary.{json,md}` 汇总命令、计数、输入/报告 SHA 和验证日志；完整差异和
逐命令 exit 以对应最新 `bringup-report.json` 为准。
scratch 零阻塞不是新版官方 jar 或浏览器运行的支持证明。

已有 `check-version-profile.mjs --all` 的 legacy profile 本地 metadata asset-index
SHA-1 与审核 profile 不同，详见 output 的 `profile-check.log`；本任务没有刷新它，
这不是 fast mode 的通过项，也不阻塞上述两个隔离证明。

### 离线输入与人工工作

`--fast` 建立可审阅的迁移草稿，不是发布证明。fast 覆盖 seamprobe、usage/extrefs、
patchkit、kernel mirror、native shader translation、sourcesets，以及 native entry、
relay protocol、verification lint、apidiff、kernel golden 和 shader corpus 基线差异。
native entry 仅有摘要时不能宣称 native smoke 通过；corpus hash/diff、native translation
或 JVM fixture 验证均不能代替 kernel bit-exact parity 和浏览器 acceptance。
`--full` 执行完整审核门；只有显式的 `--build` 才编译 WasmGC。所有输入必须来自现有
离线缓存；REQUIRED 阶段缺少 jar、LWJGL native、Maven artifact、Cargo crate 或 shader
工具时记录 blocking，可选审核缺少证据时记录 review，不联网补齐。

Bring-up 的 JVM seamprobe 按平台选择 `natives-windows`、`natives-windows-arm64`、`natives-macos`、`natives-macos-arm64` 或 `natives-linux`。对应 classifier jar 必须已经在离线工作根的 libraries 中；工具通过 `-Dorg.lwjgl.system.SharedLibraryExtractPath` 解压到证据目录。缺少 native jar 时先修复输入，不要把 shader 或 JVM 阶段标成通过。

### 26.4 及后续版本：逐层人工动作

以 `node port/tools/bringup/gaius-bringup.mjs 26.4 --fast --from 26.3 --out <out>`
开始；真实官方输入必须已缓存，不能用复制旧 jar 的 alias dry run 证明新版本支持。
报告每项的 `action` 与下表对应：先读其 `evidence`，再按文件执行动作。
`<evidence>` 指本轮 `<out>/bringup-evidence-*`；`<id>` 与 `<previous>` 由命令固定。
没有差异的层只需保留证据；不要因此新增版本分支或刷新已审核锁。

| 层/报告阶段 | 人工检查的实际文件 | 动作与完成条件 |
|---|---|---|
| profile | `<out>/profile.draft.json`、`port/versions/<id>.json` | 核对 `official`、Java/protocol/world/pack 版本、libraries、storage、`renderSeam`、`inputSeam`、`patchSet`、`extraPatchers`、`kernelSemantics`、`shaderFront`、`bringup` 和 `pages`；审核后才安装 draft |
| remap/输入 | `port/work/<id>/client-original.jar`、`client-named.jar`、`version.json`、`libraries/` | 对照报告的 SHA 和缺失路径补齐或修复离线输入；工作根可能共享，只读使用，由输入维护者修复；重跑 remap/校验通过后继续 |
| seamprobe | `<evidence>/classes.diff.json`、`spi.diff.json`、`patch-owners.diff.json`，`port/mc/seams/<generation>/seam.json` 与 `src/main/java/` | 根据 class relocation 和 SPI 明细适配受影响 adapter；未知 generation 先声明 seam 配方；signature/generation REQUIRED 差异修复并重跑，不自动改名 |
| extrefs | `<evidence>/usage.diff.json`、`usage.lock`，`port/mc/profiles/<id>/usage.lock`、`port/versions/<id>.json` | 按 library coordinate 和 owner 审阅新增/消失 usage；修复库配方或对应 shim；审核后通过工具显式刷新 usage lock |
| native entry | `<evidence>/native-entry.json`、`usage.lock`，`port/mc/seams/<generation>/seam.json` 的 `requiredEntries`、`port/overrides/libraries/<library>/src/`、`port/tools/src/main/java/dev/gaius/tools/Lwjgl*Patcher.java` | 按 usage owner 定位 library override 和入口 patcher；补齐 REQUIRED 入口签名/重定向后重跑；optional 变化保留 review、旧 adapter 或 FEATURE stub；构建 overlay 后运行 `port/scripts/lwjgl-browser-compat-smoke.sh` 补实际 smoke |
| patchkit | `<evidence>/patch.js.diff.json`、`patch.wasmgc.diff.json`，`port/tools/src/main/java/dev/gaius/tools/patches/`、`port/tools/patchkit/classification.tsv`、`acknowledgements.json` | REQUIRED 失败修复对应 domain patch/registration 后重跑；目标变化逐项审核 ack；PERF/FEATURE/TELEMETRY 失败保持 disabled、记录原因，不提升为 blocking；审核后才刷新 `port/mc/profiles/<id>/patch-manifest.*.json` |
| kernel mirror | `<evidence>/kernel-mirror.diff.json`、`kernel-mirror.lock`，`port/native/kernel-mirror.toml`、`port/versions/<id>.json` 的 `kernelMirror` | 审阅方法/依赖变化和传递关闭原因；被 decline 的 kernel 保持关闭与 vanilla Java；不能仅因新 lock 生成成功就打开 |
| kernel golden | `<evidence>/kernel-golden.diff.json`，`port/native/fixtures/<id>/`、`port/native/golden/src/`、`port/native/crates/`、`port/mc/profiles/<id>/kernel-semantics.json` | 用目标版本 vanilla JVM 重新取得 fixtures，运行 `bash port/native/golden/run-golden.sh --verify <id>`；修复受影响 kernel 并取得 bit-exact parity 才可审核启用；缺失或 mismatch 保持 review/auto-disabled |
| shader translation | `<evidence>/shader-translation/results.json` 和 shader 日志，`port/versions/<id>.json` 的 `bringup.shader`、`port/native/golden/shaders/java/dev/gaius/shaderxlat/`、`port/native/crates/gaius-shaderxlat/` | 修复 profile-owned dump/link 配方或 translator，并补齐已有离线 natives；必需 translation 全通过后才能解除该 REQUIRED blocker；另做浏览器验收 |
| shader corpus baseline | `<evidence>/shader-corpus-baseline.json`、`shader-corpus/corpus.json`、`previous-shader-corpus/corpus.json`，`port/versions/<previous>.json` 的 `bringup.shader` | 按报告给出的 baseline 路径审阅 pipeline/module/layout 变化；修复前一版本离线输入或 dump/link 配方，可用 `port/native/golden/shaders/dump-corpus.sh` 和 `run-corpus.sh` 补齐 corpus；缺失基线保留 review 和已审核 corpus，不能认作 browser parity |
| sourcesets | `<evidence>/sourcesets.json`，`port/versions/<id>.json` 的 `renderSeam`/`patchSet`、`port/mc/shared/sourcesets.json` 的 sets/relocations 与 `port/mc/shared/` 下对应 source set | 修正缺失 source/relocation 或 profile 配方；解析通过后交给后续 Java/TeaVM 构建验证，不把解析当编译通过 |
| relay protocol | `<evidence>/relay-protocol/relay-protocol.js`，`apps/bridge/protocols/<protocol>.json`、`port/versions/<id>.json` 的 `relayProtocol`/`protocolVersion`/`official` | 用现有 `tools/gen-protocol-ids.mjs` 从离线报告生成并审核表，再运行 `node port/scripts/build-relay-protocol.mjs --profile <id> --out <evidence>/relay-protocol`；协议或 provenance 未核对时保留前表和 review，不启用新 profile 联机 |
| verification lint | `<evidence>/version-literals.log`，命中的 `port/scripts/` 或 `apps/bridge/` 文件、`port/scripts/verification-profiles.mjs`、`port/scripts/quickcheck/version-literals-exempt.txt` | 把硬编码默认 profile/矩阵改为 profile 数据派生；数据文件确需豁免时写精确路径与理由；重跑 `python port/scripts/quickcheck/lint_version_literals.py --enforce` |
| API diff | `<evidence>/apidiff.json`、`apidiff-*.log`、`usage.lock`，对应 `port/overrides/libraries/<library>/src/versions/<range>/api.lock` 与相邻 source、`port/tools/src/main/java/dev/gaius/tools/patches/platform/` 或 seam 文件 | 按 API owner 修复 library shim、JDK patch 或 seam；审核 overlay 后用 `bash port/tools/extrefs/extrefs.sh apilock <id> --write` 刷新相关 lock；对没有 API baseline 的变化库逐项审阅，不能用空摘要声明兼容 |
| profile 派生输出 | `port/tools/profilegen/profilegen.mjs --regions` 列出的文件，`port/versions/<id>.json` | draft 审核安装后运行 `node port/tools/profilegen/profilegen.mjs --write`，再 `--check`；核对派生表 diff，不能手改生成区域或让 fast 自动加入 allowlist |

如果新版本没有匹配的 seam generation，或 SPI、资源格式、shader harness 改变，
人工修改集中在对应 seam、配方和 REQUIRED patch。无法证明 bit-exact parity 的 kernel
必须保持关闭；缺失可选证据必须保留可执行的后续动作，不能标成已通过。

### Honest effort estimate

匹配既有 seam generation 且离线输入齐全时，`--fast`、报告审阅和 profile 元数据通常需要 30–90 分钟；独立的 `--full` 审核通常需要 1–3 小时。新的 renderpearl generation 若涉及 SPI 或 patch 变化，通常需要 1–3 个工程日完成 seam/patch 适配和审核，再加构建与浏览器 acceptance 窗口。缺少必需输入、REQUIRED patch 失败、shader ABI 变化或 TeaVM 内存失败会延长时间，直到 blocking 项解决。golden mismatch 的 kernel 保持关闭，补齐审核证据所需时间另计。
