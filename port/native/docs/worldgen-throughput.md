# Worldgen 离线吞吐与下一步接管分析

日期：2026-10-08。原始基线：`628b7747`；统一基准与旧版 IR 支持：`69130cd5`；优化版本为本文所在提交。所有原始数据、日志、参考输入及构建产物保存在仓库之外的输出目录。

## 结论

- 保留一项已验证的 Rust 优化：surface noise 条件缓存从逐方块整表清零改为代次失效，不改变计算、访问顺序或 RNG。
- 同进程、逐任务交替 wasm A/B 显示：baseline surface 耗时降低 22.1% / 9.3% / 6.0%，SIMD surface 降低 22.5% / 10.1% / 4.0%，分别对应下表三个 profile。
- `combined` 配对耗时仅降低约 2.9–4.1%，不是完整浏览器 chunk 吞吐，也没有证明 2x 目标。native 独立进程测量和长时复测存在明显共享机负载波动，原始回归数据没有删除。
- 三套 JVM chunk 参考一致，优化前后 36 份完整结果帧逐字节相同；baseline/SIMD noise wasm 各验证 461 个 job、12,036 个采样点。
- aquifers 与 ore veins 已在 Rust 中。下一步更值得测量的是 Java carvers、其重复 noise/aquifer 上下文、features/矿石放置、structure starts，以及结果安装和 Worker 调度。
- JavaScript-target 构建输出未修改；没有运行完整 TeaVM、Chrome、浏览器 Worker 验收或发布。

## 1. 输入、口径与可复跑入口

所有 profile 从 `port/versions/*.json` 枚举；`kernelSemantics` 决定计算语义，新增 `worldgenReference` 仅选择 JVM harness、exporter 和兼容适配器，没有在内核中加入 Minecraft 版本判断。

使用官方本地 `client-named.jar` 导出的 overworld IR 和 `.ref`：seed `12345`，chunks `(0,0)`、`(7,-3)`、`(-20,11)`，每 chunk 16×384×16。输入哈希见 `summary.json`，导出 source 哈希见 `reference/<profile>/provenance.json`。参考源码仅复制、适配到输出目录；共享 `port/work` 仅读取。

机器：Intel Core i5-14600K，14 cores / 20 logical processors；Windows 11；Rust/Cargo 1.98、Node 24.19.0、Java 25。本机与其他任务共享，没有修改 affinity、优先级或电源设置。所有本任务基准串行执行，没有本任务自己的 cargo/JVM 与吞吐计时重叠。

### 计时范围

- `biomes`：1536 个 quart biome 结果。
- `terrain`：实际 `TERRAIN` noise-fill job，包含密度求值、aquifer、方块填充、heightmaps 等，不是一个纯 noise-point microbenchmark。double-router ore veins 在这里执行。
- `surface`：同一已填充 chunk 的 surface/material 规则；float-sampler ore veins 在 material/surface 内执行。native core 的输入 chunk clone 在计时外。
- `combined`：现有 `TERRAIN` 的 `FLAG_SURFACE | FLAG_BIOMES`，用于观察三个步骤合为一个 job 的本地上限；没有改变浏览器状态机或新增批处理 ABI。
- native `core` 直接调用 `Generator`；native `framed` 包括解码、内核调用、编码、descriptor 释放，以及一次结果 `to_vec` copy。Node `framed` 包括 alloc/copy-in/run/status-read/release/dealloc，但计时内不 copy-out；完整字节比较在计时前后执行。因此两者不能按绝对时间作同口径跨语言结论。
- IR 编译、冷 LOAD_GENERATOR、文件 I/O、真实 Worker postMessage/transfer、Java 结果安装、carvers、features、structures、lighting 和完整 chunk status 链不在 Rust/Node 计时内。

独立进程基准：三次 pass，前后顺序在第二次反转，baseline/SIMD 顺序也反转；每 pass 7 轮，每轮 6 次重复×3 chunks。表中是 21 轮 ms/chunk 中位数，各阶段、各路径、各产物有 378 个计时 job；不是同时生成 378 个 chunks。另做 float sampler 长时反向复测，记录在 `followup-*.tsv` 和 `followup-summary.json`。

为降低共享机时间漂移，另做同进程配对 wasm：两个实例各自 LOAD；每个 job 紧邻调用 before/after，交替谁先运行。21 轮×18 jobs；同一输入结果在计时前后均核对。配对降低率先对同轮计算 `1 - after/before`，再取中位数；Q1/Q3 是离散轮次四分位数，不是置信区间。它可能不同于两列独立中位数的直接比值。

### 复跑命令（PowerShell）

从 worktree 根目录导出参考；所有编译和 scratch 输出都定向到 `$output`。

```powershell
$output = '<out>/wgperf'
node port/native/worldgen-perf-reference.mjs "$output/reference"
Set-Location port/native
$env:CARGO_TARGET_DIR = "$output/after-native"
cargo build --release --offline --locked -j 2 -p gaius-worldgen-wasm --example perf_ir
& "$output/after-native/release/examples/perf_ir.exe" "$output/reference/$profile" "$output/final-after-jobs/$profile" 7 6
foreach ($variant in @('baseline', 'simd')) {
    $env:CARGO_TARGET_DIR = "$output/after-$variant"
    $env:CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS = if ($variant -eq 'simd') {'-C target-feature=+simd128'} else {'-C target-feature=-simd128'}
    cargo build --release --offline --locked -j 2 --target wasm32-unknown-unknown -p gaius-worldgen-wasm -p gaius-noise-wasm
    node worldgen-perf.mjs "$output/after-$variant/wasm32-unknown-unknown/release/gaius_worldgen_wasm.wasm" "$output/final-before-jobs/$profile" 7 6
    node worldgen-perf-paired.mjs "$output/support-before-$variant/wasm32-unknown-unknown/release/gaius_worldgen_wasm.wasm" "$output/after-$variant/wasm32-unknown-unknown/release/gaius_worldgen_wasm.wasm" "$output/final-before-jobs/$profile" 21 6
}
node worldgen-perf-summary.mjs "$output"
```

`$profile` 必须取 manifest 的 `id`；汇总工具读取此次测量协议的 `final-{native,baseline,simd}-{before,after}-<id>-<pass>.tsv` 及 `paired-<variant>-<id>.tsv`。完整顺序脚本保存在输出目录 `measure.ps1`。不运行可能写入共享 work 或触发完整构建的聚合脚本。

构建前版从 `628b7747` 的 `git archive` 提取到输出目录，再复制统一基准、manifest 和 ScaledNoise 支持文件，保留原始 `surface.rs`；选择清单保存在 `baseline-source-files.json`。因此旧版前后比较是在同等 IR 支持下的性能比较，不声称原始浏览器已启用旧版内核。其它两个 profile 的原始算术没有因基准准备而更改。

## 2. 独立进程 native 与 wasm 吞吐

正百分比表示耗时降低，负值表示该批测量变慢。`jobs/s` 是单线程热实例的阶段吞吐，不是完整 chunks/s。原始数据按时间批次保留，不能只挑最快一次。

### Native core

| Profile | 阶段 | 前 ms/chunk | 后 ms/chunk | 后 jobs/s | 耗时降低 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 0.690 | 0.694 | 1441.1 | -0.5% |
| 1.21.11 | terrain | 9.049 | 9.260 | 108.0 | -2.3% |
| 1.21.11 | surface | 2.779 | 1.926 | 519.1 | 30.7% |
| 1.21.11 | combined | 14.430 | 11.567 | 86.5 | 19.8% |
| 26.2 | biomes | 0.673 | 0.678 | 1475.6 | -0.7% |
| 26.2 | terrain | 8.165 | 8.804 | 113.6 | -7.8% |
| 26.2 | surface | 5.463 | 5.058 | 197.7 | 7.4% |
| 26.2 | combined | 15.890 | 14.294 | 70.0 | 10.0% |
| 26.3 | biomes | 0.163 | 0.180 | 5555.2 | -10.1% |
| 26.3 | terrain | 3.145 | 3.346 | 298.9 | -6.4% |
| 26.3 | surface | 5.315 | 5.033 | 198.7 | 5.3% |
| 26.3 | combined | 8.673 | 10.241 | 97.6 | -18.1% |

### Native framed

| Profile | 阶段 | 前 ms/chunk | 后 ms/chunk | 后 jobs/s | 耗时降低 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 0.699 | 0.691 | 1447.2 | 1.1% |
| 1.21.11 | terrain | 9.233 | 9.193 | 108.8 | 0.4% |
| 1.21.11 | surface | 3.190 | 2.149 | 465.3 | 32.6% |
| 1.21.11 | combined | 12.560 | 11.661 | 85.8 | 7.2% |
| 26.2 | biomes | 0.679 | 0.677 | 1477.5 | 0.4% |
| 26.2 | terrain | 8.301 | 8.554 | 116.9 | -3.0% |
| 26.2 | surface | 5.704 | 5.041 | 198.4 | 11.6% |
| 26.2 | combined | 18.450 | 13.929 | 71.8 | 24.5% |
| 26.3 | biomes | 0.166 | 0.169 | 5923.4 | -1.6% |
| 26.3 | terrain | 3.231 | 3.170 | 315.4 | 1.9% |
| 26.3 | surface | 5.170 | 5.031 | 198.8 | 2.7% |
| 26.3 | combined | 8.554 | 9.623 | 103.9 | -12.5% |

### Node wasm baseline

| Profile | 阶段 | 前 ms/chunk | 后 ms/chunk | 后 jobs/s | 耗时降低 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 0.907 | 0.914 | 1094.4 | -0.8% |
| 1.21.11 | terrain | 11.600 | 11.766 | 85.0 | -1.4% |
| 1.21.11 | surface | 2.797 | 2.153 | 464.4 | 23.0% |
| 1.21.11 | combined | 15.640 | 16.055 | 62.3 | -2.7% |
| 26.2 | biomes | 0.902 | 1.002 | 998.2 | -11.0% |
| 26.2 | terrain | 10.705 | 11.033 | 90.6 | -3.1% |
| 26.2 | surface | 7.919 | 6.807 | 146.9 | 14.0% |
| 26.2 | combined | 21.856 | 19.114 | 52.3 | 12.5% |
| 26.3 | biomes | 0.238 | 0.257 | 3884.7 | -8.2% |
| 26.3 | terrain | 4.118 | 4.293 | 233.0 | -4.2% |
| 26.3 | surface | 7.194 | 6.987 | 143.1 | 2.9% |
| 26.3 | combined | 11.742 | 12.052 | 83.0 | -2.6% |

### Node wasm SIMD

| Profile | 阶段 | 前 ms/chunk | 后 ms/chunk | 后 jobs/s | 耗时降低 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21.11 | biomes | 1.012 | 1.020 | 980.3 | -0.8% |
| 1.21.11 | terrain | 12.533 | 11.478 | 87.1 | 8.4% |
| 1.21.11 | surface | 2.783 | 2.059 | 485.7 | 26.0% |
| 1.21.11 | combined | 16.222 | 16.665 | 60.0 | -2.7% |
| 26.2 | biomes | 0.987 | 1.023 | 977.3 | -3.6% |
| 26.2 | terrain | 10.750 | 11.485 | 87.1 | -6.8% |
| 26.2 | surface | 7.561 | 7.326 | 136.5 | 3.1% |
| 26.2 | combined | 20.814 | 20.650 | 48.4 | 0.8% |
| 26.3 | biomes | 0.267 | 0.275 | 3635.3 | -3.1% |
| 26.3 | terrain | 4.147 | 4.124 | 242.5 | 0.6% |
| 26.3 | surface | 6.791 | 6.672 | 149.9 | 1.8% |
| 26.3 | combined | 10.735 | 10.912 | 91.6 | -1.7% |

未修改 biomes 或 terrain 求值代码，但独立批次中仍出现 1–11% 浮动；native float `combined` 甚至显示 18.1% 变慢。反向长时复测中 native core `combined` 仍为 3.7% 变慢，SIMD surface 一次显示 30.0% 变慢，而同批未修改 SIMD terrain 也变慢 10.4%。这些结果说明独立进程阶段差分不能可靠归因；它们也不是可以忽略的浏览器验收证据。后续用逐任务配对判断变化方向，不把原始表覆写成好看的数字。

## 3. 配对 wasm A/B：优化的可归因收益

### Surface

| Profile | wasm | 前 ms/chunk | 后 ms/chunk | 配对耗时降低中位数 | 降低率 Q1–Q3 | 更快轮数 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1.21.11 | baseline | 2.795 | 2.173 | 22.1% | 19.9%–24.2% | 21/21 |
| 26.2 | baseline | 8.020 | 7.359 | 9.3% | 7.5%–10.3% | 21/21 |
| 26.3 | baseline | 8.476 | 7.958 | 6.0% | 4.2%–8.2% | 19/21 |
| 1.21.11 | simd | 2.714 | 2.135 | 22.5% | 21.1%–22.7% | 21/21 |
| 26.2 | simd | 7.548 | 6.783 | 10.1% | 8.3%–11.7% | 21/21 |
| 26.3 | simd | 7.576 | 7.053 | 4.0% | 1.0%–7.6% | 17/21 |

### Combined

| Profile | wasm | 前 ms/chunk | 后 ms/chunk | 配对耗时降低中位数 | 降低率 Q1–Q3 | 更快轮数 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1.21.11 | baseline | 17.577 | 16.420 | 3.6% | 1.4%–5.8% | 19/21 |
| 26.2 | baseline | 19.009 | 18.343 | 4.1% | 2.8%–5.1% | 19/21 |
| 26.3 | baseline | 13.532 | 12.894 | 2.9% | 0.6%–4.7% | 17/21 |
| 1.21.11 | simd | 17.127 | 16.157 | 3.9% | 1.9%–5.2% | 20/21 |
| 26.2 | simd | 19.364 | 18.157 | 3.9% | 3.3%–6.1% | 20/21 |
| 26.3 | simd | 11.408 | 10.879 | 3.2% | 2.5%–5.4% | 18/21 |

六组 surface 配对方向均为正，两个 router profile 的 21/21 轮都更快；float sampler 的收益更小且有噪声。未修改的 biomes/terrain 配对中位降低率约 -1.1% 到 +1.3%，接近零。六组 `combined` 的配对降低率仅 2.9–4.1%，有部分轮次变慢：继续保留真实浏览器整体回归风险，不能宣称端到端稳定加速。

SIMD 没有自动带来 2x。现有 `simd.rs` 主要向量化 f32 buffer add/sub/mul 等；noise lookup、递归 IR、随机分支及多数 double 路径仍然标量。此次没有添加 SIMD 运算，没有改变浮点结合顺序、用倒数乘法替代除法、或启用 FMA/fast-math。baseline 与 SIMD 在不同时间段运行，不能直接按两列时间断言 SIMD 的稳定相对收益。

## 4. 热点、保留优化与放弃的方案

源码热点位于 `crates/gaius-worldgen/src/surface.rs` 的 `Context`、`update_xz`、`update_y`、`Condition::NoiseThreshold`。原缓存按整个 surface condition 数量分配 `Vec<Option<f64>>`；每个石质方块推进 Y 时，即使没有访问绝大多数 noise 条件，也逐项写入 `None`。

改为 `NoiseCache { values, stamps, generation }`：读取只认当前代次，失效只增加 generation。普通 Y 更新从 O(condition_count) 写入变为 O(1)，X/Z 切换仍分别失效二维和三维缓存；generation 回绕时清 stamps。保留 -0 和 NaN payload 的位模式测试、过期槽位和回绕测试，避免 2^32 次推进后的误命中。

这是失效路径 allocation-free，不是整个 job allocation-free。每个缓存从一个 Vec 变为两个 Vec，surface 初始化多两个分配；理论槽位 payload 从 Option<f64> 的 16 bytes 变为 f64+u32 的 12 bytes，Vec capacity/alignment 与 allocator 元数据另计。没有新增长期共享缓存、跨 chunk mutable state 或跨 Worker 数据依赖。

热点定位依据源码循环及 A/B，不是采样 flamegraph；本次没有获得硬件性能计数器、真实 Worker trace 或各函数 CPU share。

另曾尝试 double-router Y-independent column 复用与插值步长复用，结果仍一致，但 pilot terrain core 从约 8.2 ms 上升到 9–9.7 ms，frame 路径没有稳定收益，已全部撤回。输出目录仅保存 `rejected-interpolation-column-prototype.patch`，未纳入提交。原因是现有 marker/arena 已缓存部分结果，额外通用节点判断和缓存索引可能超过所省求值；不能凭“缓存应该更快”提交。

现有 wasm generator cache 与 arena scratch pool 已复用 IR 和部分 buffer；这次没有重新造全局 cache。biomes/terrain 分配清除、noise SIMD lane 扩展、跨 chunk noise columns 和通用 job batching 都没有足够收益证据，不扩大 patch。

### Job framing / batching 边界

首个参考 chunk 的请求大小：BIOMES 48 bytes，TERRAIN 88 bytes，SURFACE 18,760 bytes，combined 88 bytes；结果分别为 6,168 / 18,688 / 26,892 / 33,036 bytes。它们是已有稀疏/palette 编码后的尺寸，不是直接传递完整 int volume。

combined 可减少一次 terrain→surface 的本地结果编解码和多个往返，但还包含两个步骤的计算。热缓存、core/framed 计时差异和 shared-load 使简单相减出现负“frame overhead”，不能据此给出精确序列化份额。Node 不包含消息传输开销。

浏览器 double-router 的 NOISE 和 SURFACE 属于不同 status，SURFACE 输入还需要实际邻区 biome ring、structure beard 和失效规则，不能无条件合并；float-sampler terrain 已带 `FLAG_SURFACE`，BIOMES 仍是独立 status。本次只测现有 combined flag，没有改浏览器 job 排序。后续 batching 应先做同 kind、独立 chunk 的小批次，保留逐 job decline/error/cancellation 与尾延迟上限。

## 5. 同 chunk 的 JVM 参考阶段时间

3 次 warm iteration，5 次 measured round×2 repeats×3 chunks，共 30 个样本/阶段/profile。文件序列化在计时外。double-router 调用 vanilla createBiomes/fillFromNoise/buildSurface；float-sampler density/material API 按现有参考 harness 构建同 chunk。邻区 biome resolver 有 warm cache，因此不能外推冷启动或真实 region 状态。

| Profile | biomes ms | terrain ms | surface ms | 三阶段中位数之和 ms | 三阶段内 B/T/S 比例 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1.21.11 | 0.8595 | 8.0627 | 3.3256 | 12.2478 | 7.0% / 65.8% / 27.2% |
| 26.2 | 1.2851 | 10.1123 | 8.7455 | 20.1429 | 6.4% / 50.2% / 43.4% |
| 26.3 | 0.2751 | 4.7417 | 20.1847 | 25.2015 | 1.1% / 18.8% / 80.1% |

float sampler 另有 `density` 诊断 pass：中位 2.5041 ms，范围 2.3867–3.3405 ms。terrain 自己再次执行相关密度求值；不能把诊断 pass 与 terrain 相加冒充 chunk 总时间。各阶段中位数之和也不是逐 chunk 总时间的中位数。

这里的 surface 比例 27.2% / 43.4% / 80.1% 仅在三项已测工作内；现代 profile 的这部分已经能由 Rust 接管，不能把它当作“还留在 Java Worker 的成本”。JVM 也不是 TeaVM wasm-GC/JSPI，跨 JVM/Node 数值不构成真实 Worker 的加速比。

现有离线参考没有完整 `ServerLevel/WorldGenRegion/ChunkStatus` 依赖链。carvers、实际邻区 features、structure starts、lighting 和调度没有运行；用空邻区、空 structures 或随意假实现计时会改变任务语义。因此这些阶段的全 chunk 时间份额保持未知，而不是填一个推测百分比。

## 6. Hooks 与仍在 Java Worker 的工作

| Profile / 路径 | 可走 Rust | 仍在 Java / 未接管 |
| --- | --- | --- |
| 1.21.11 | 本次只证明离线 IR/kernel 能匹配 golden | 浏览器 hook 仍关闭，chunk 生成仍按 vanilla 路径 |
| 26.2 router | BIOMES、NOISE（density/aquifers/ore veins/heightmaps/postprocess）、独立 SURFACE | CARVERS、region/structure 输入采集、结果安装、features、structures、lighting、status 链 |
| 26.3 sampler | BIOMES、terrain+surface/material（含 aquifers/ore veins） | kernel 填完后新建 Java NoiseChunk 执行 carvers，其 noise/aquifer/material 按需求值仍是 Java；其它 region/status 工作同上 |
| decline/fallback | 无 | 不支持的 IR、非 ProtoChunk、非空 Blender、upgrading/retrogen、开关关闭、失效 token 或 Worker 失败时执行 vanilla |

证据入口（均未修改）：

- `port/tools/src/main/java/dev/gaius/tools/patches/worldgen/WorldgenKernelPatches.java`：`getLightDampening()I` 探测不通过时打印 kernel stays off，legacy 不被本次新 IR 支持自动启用。
- `port/mc/shared/canonical/src/java/dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks262.java` 与 `WorldgenKernel262.java`：NOISE 保存已解码 chunk token，SURFACE 读取 region biome ring 和 beard；Java 安装方块/heightmaps/postprocess。
- `port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks263.java`、`ChunkGeneratorKernelHooks263.java` 与 `WorldgenKernel263.java`：terrain 只带 SURFACE flag，单独 BIOMES hook；`carve` 创建新 NoiseChunk 调用 generateCarvers。
- `port/src/main/java/dev/gaius/browser/kernel/worldgen/BrowserWorldgenKernel.java` 与 `port/web/kernels/worldgen-kernel.js`：异步 facade、实例 load/cache、stored-noise token/byte budget、编解码、异常及 Java fallback 边界。
- `port/src/main/java/dev/gaius/browser/BrowserWorldgenDispatcherScheduler.java`：priority 0–2 的 bookkeeping 可在一次 turn 内排空，预算 2 ms / 最多 1024 个 runnable；priority 3 的可挂起 generation 结束该 turn，禁止再入。真实定时器、挂起和结果安装占比需浏览器 trace，离线 JVM 不可给出。

## 7. 下一批 kernel 候选：收益是假设，风险须先封装

优先顺序是“先观测真实 status 总时与 transferable bytes，再按 immutable snapshot/delta 设计小 job”，不是一次重写 ChunkGenerator。

| 候选 | 可保持 deterministic / bit-comparable 的边界 | 条件收益估算 | 主要风险 |
| --- | --- | --- | --- |
| Carvers | 输入 immutable chunk/state flags、carving mask、seed/RNG 状态、邻区 carver biome/config 和噪声/液体快照；输出按 vanilla 顺序的 block/mask/postprocess delta | 若实测占全 chunk 15–30%，内核快 2–3x，则总体约 1.08–1.25x | 邻区循环顺序、RNG 消耗、重复挖掘 mask、aquifer/液体、结构/blending/retrogen 上下文；不能只移植几何洞形 |
| Features 中的普通矿石放置 | 限定单一 placed feature，携带准确 placement RNG/邻区高度/可替换方块快照；返回带序号的 delta；Java 按既有顺序提交 | 若该子集占 10–25%，快 2–4x，则整体约 1.05–1.23x | 跨 chunk 写入与 feature 顺序、次序相关条件、Java heightmap 更新；普通矿石 features 不是已接管的 ore veins |
| Structure starts 的 placement/candidate 核心 | 先接管 seed 派生、候选坐标与纯几何判断；模板/registry/region 查询由 Java 提供快照；单独匹配候选/包围盒 | 若占 2–8%，纯核快 3x，则整体约 1.014–1.056x | terrain height/sample、template pool、piece RNG、structure references 和 region 写入；不能把纯 placement 的收益当整条 starts 的收益 |
| Aquifer / ore vein 内部继续优化 | 已在 Rust；先量 profile、去掉重复采样/复用仅当前 chunk 的不可变 noise grid，保留精确坐标、算术和随机分支次序 | 未量出子路径 share，暂不给总体收益；通常只能优化已接管阶段的一部分 | 混淆 carver 中残留 Java aquifer 与 terrain 已接管部分；过期 grid、取样顺序和 f32 rounding |
| Java 结果安装与同类 job 小批次 | 先量 decoding/palette resolve/ChunkSection 写入和消息成本；用现有合法 result 和 status 顺序减少重复编码/往返 | 当前帧已压缩，字节量不是证据；Node 不能确定真实获益 | 主 Worker 长任务、GC、队列优先级、取消/失败隔离、stored-noise token 生存期 |

这些百分比不是本次测得的 share，只是规划情景。Amdahl：`S = 1 / (1 - p + p/r)`，p 为真实全 chunk 占比，r 为该子集加速倍数。即使某个内核无限快，整体 2x 也至少要求可加速成本达到 50%；子集快 3x 时要求 p≥75%。当前 surface 优化远不足以达到该条件。

## 8. 一致性与验收

- 本地官方 jar 的 9 个参考 chunks：13,824 biome ids、noise+surface 两个阶段合计 1,769,472 block state words、4,608 最终 heightmap 值一致；float sampler 额外 294,912 个 density f32 位模式一致。这批 biome 没有 equal-fitness tie 宽容匹配。double-router `.ref` 没有 raw f64 density block，不宣称全图 f64 中间值全部逐位检查。
- 36 个优化前后完整 ABI 结果帧逐字节一致，Node baseline/SIMD 计时前后均与前版 native frame 比较。noise wasm 两变体各 461 jobs / 12,036 points exact；native noise golden 覆盖仓库全部 profile fixtures。
- 新增 generic ScaledNoise op 32：仅 double-router，按 `scale * abs(noise(x/scale,y/scale,z/scale))` 原顺序。实际 scale 只接受 finite positive constant 或各分支均如此的 IntervalSelect，不信任虚报 bounds；float sampler 明确 decline。它是旧版参考导出的兼容支撑，不是 surface 优化，不改变浏览器 eligibility。
- 旧版 adapter 根据本地官方 bytecode 复核 rarity mapper thresholds/scales，关键源码替换、反射和编译有失败检查，并以实际 chunk parity 验证；仅适配 overworld，不能外推 Nether legacy seed 或其它 dimension。

验证命令（在 `port/native`，所有 cargo 都 `--offline --locked -j 2`；target 仍为输出目录）：

```powershell
cargo test --offline --locked -j 2 -p gaius-worldgen -p gaius-worldgen-wasm -p gaius-noise
$env:GAIUS_WORLDGEN_REFERENCE = "$output/reference/$profile"
cargo test --offline --locked -j 2 -p gaius-worldgen --test vanilla_reference -- --nocapture
Remove-Item Env:GAIUS_WORLDGEN_REFERENCE
cargo fmt --all -- --check
cargo clippy --offline --locked -j 2 -p gaius-worldgen -p gaius-worldgen-wasm -p gaius-noise --all-targets -- -D warnings
node --check worldgen-perf-reference.mjs
node --check worldgen-perf.mjs
node --check worldgen-perf-paired.mjs
node --check worldgen-perf-summary.mjs
node wasm-fixture-check.mjs "$output/after-$variant/wasm32-unknown-unknown/release/gaius_noise_wasm.wasm"
node worldgen-perf-summary.mjs "$output"
```

`vanilla_reference` 不带环境变量会跳过；真正一致性证据来自三个 manifest profile 分别带变量运行，而不是那个 skip-pass。未知 profile 在启动 javac 前报错。完整结果见 `tests-completion.log`、`parity-completion-<id>.log`、`fmt-completion.log`、`clippy-completion.log`、`noise-completion-<variant>.log`。

### 仍开放的验收

只有一个 seed、三个 overworld chunks；未覆盖多个 seed、Nether/End、完整邻区 features/structures、blending/retrogen、多 Worker cancellation、biome tie 与更广泛特殊地形。没有真实浏览器的 cold-load、JIT/GC、Worker 队列、结果安装和全 status trace；约 2x 的浏览器 chunk 目标未验证。受本任务明确禁止 Chrome/完整 TeaVM 的约束，不能把 Node 成功或 JVM golden 当作这些验收完成。

没有依赖下载，没有共享 work 写入、其他 worktree/branch 修改、push 或发布。新测量工具、IR compatibility 与 surface 优化可单独审阅；JavaScript-target 构建输出保持不变。

## 9. 全 status 实测与 ordinary ore 实验

2026-10-08 的后续测量不再把第 7 节的假设当作 share。每个 profile 用两个 seed、每个 seed 请求 400 个 FULL chunks，分别关闭和启用现有 Java hooks，并补测 native、Node wasm baseline/SIMD 与 ordinary ore 组合。方法、全部 status、ordered delta ABI、独立 vanilla golden、mirror gate 和复跑命令见 [worldgen-ore-throughput.md](worldgen-ore-throughput.md)。原始报告保存在仓库之外的输出目录。

ordinary ore 的 JVM exclusive CPU share 只有 2.65% / 3.10% / 2.94%；carvers 只有 1.78% / 2.19% / 1.62%。它们不是此前假设的 10–25%。剩余最大已识别 feature 是 SculkPatch，另外 34.63–47.95% 的 JVM process CPU 仍未归属，包括后台 JIT/GC 与未插桩路径。选 ordinary ore 是为了验证可独立复核的 geometry/snapshot/delta 边界，不是宣称选中了最大完整 Java 热点。

新 ore ABI 已经通过三 profile 的独立 JVM ordered golden 和两个 wasm 变体，但保持默认关闭，没有接入生产 browser hook。当时现有 worldgen hooks 的完整 chunk block 哈希存在不一致，连 OFF/OFF 也有少量边界漂移；这些是当时开放的正确性门槛，不能用局部 ore parity 抹掉。该问题已在同日的确定性复测中修复（FEATURES-before-FULL 屏障与完整 stored biome 上下文），worldgen 在两个有 JVM hook seam 的 profile 上重新获批，见 [worldgen-correctness.md](worldgen-correctness.md)。2x browser full-chunk 目标仍未达到或验证。
