# Gaius 桥接类描述

`BrowserBridges` 在 overlay 构建中用 ASM 9.10.1 生成 JVM 21 classfile。每个描述保留原 Gaius 桥的类名、方法顺序、签名、控制流和访问标记。方法体各用一个生成函数，分支标签、异常表、栈图和调试行号都随该方法维护。

| Profile | `client` | `src` |
| --- | --- | --- |
| 26.3 | 现代模型依赖调度器 | 字形加载器（RenderPearl）、气候索引和光照访问、mesh/state/section 桥 |
| 26.2 | 现代模型依赖调度器、噪声图映射 | 字形加载器（Blaze3D）、气候索引和光照访问、mesh/state/section 桥、路由世界生成导出器 |
| 1.21.11 | 旧版模型依赖调度器 | 字形加载器（Blaze3D 与 LWJGL 的 `memFree(Buffer)`）、气候索引和光照访问 |

JS 与 WasmGC 使用同样的桥接类选择。`client` 放进客户端 overlay JAR，`src` 由 Maven resources 阶段复制到 Client 与 Worker 各自的 `maven/classes`。`LegacyRouterWorldgenExport` 仅供 JVM golden 对照使用。

`SourceFile` 与行号沿用迁移前的桥，方便对照旧诊断。ASM 会重排常量池和属性。核对行为时可对两个 classfile 分别运行 `ClassReader.accept(new ClassWriter(0), 0)`，然后比较生成的字节。保持 `flags=0` 可检查方法体、栈图、签名和调试信息。对桥接行为的修改还应运行相关 JVM 或浏览器门禁。
