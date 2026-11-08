# 抗锯齿模组（Anti Aliasing Mod）

[[**English**](README.md)] | [**简体中文**]

## 介绍

作为 DXRMC 项目开发中的附属品，本模组把抗锯齿功能单独拿了出来并适配了 Minecraft 默认的 Blaze3D 渲染抽象层。

渲染实现使用 Blaze3D 抽象，不直接调用 OpenGL。目前实际客户端验证使用 OpenGL；Vulkan 尚未完成运行验证。

#### 当前实现状态

- **FXAA 已实现**：使用方向判定、边缘端点搜索和子像素处理，平滑世界与手持物品轮廓，可能轻微软化细节。
- **SSAA 已实现**：世界先按每轴 1.5×、2×、3× 或 4× 分辨率渲染，再进行面积覆盖低通降采样；默认 2×。
- **SMAA 1x 已实现**：颜色边缘检测、Area / Search 查找纹理权重计算、邻域混合，提供官方低 / 中 / 高 / 极高预设；默认高。
- **TAA 尚未实现**：保留设置编号并明确提示，不执行其它算法替代。

后续按以下四阶段推进：

| 阶段 | 目标 | 当前状态 |
| --- | --- | --- |
| 第一阶段 | 原版 FXAA 与公共扩展预留 | 已实现，验证范围见方案文档 |
| 第二阶段 | 原版 SSAA 与 SMAA 1x | 已实现，实际验证与限制见方案文档 |
| 第三阶段 | Iris / Sodium 适配 | 待实现，按算法分别验证兼容性 |
| 第四阶段 | TAA | 待实现，包含 jitter、历史重投影及多种采样序列 |

目前只验证未安装 Iris / Sodium 的原版渲染路径。

在「选项 → 视频设置 → 抗锯齿」选择模式，下一有效世界帧生效；选择关闭即停用。设置通过原版 `options.txt` 的 `antialiasing` 键保存：关闭 = 0、FXAA = 1、SMAA = 2、TAA = 3、SSAA = 4，缺失或非法值回到关闭。三种已实现算法均在原版实体视角特效之后、HUD / GUI 之前输出，字体、准星与菜单不会被本模组过滤。

已有画质预设映射保留：流畅 → 关闭、精美 → SMAA、极佳 → TAA；TAA 仍显示尚未实现。

#### FXAA 参数配置

Fabric 安装 **Mod Menu** 后，在「模组 → Anti Aliasing Mod → 配置」打开设置；NeoForge 使用内置模组列表的配置按钮。两端共用同一页面，可切换抗锯齿模式，并即时调整以下 FXAA 参数：

| 参数 | 范围 | 默认值 | 作用 |
| --- | --- | --- | --- |
| 子像素混合强度 | 0–1 | 0.7500 | 越高越能平滑细小轮廓，也可能软化细节 |
| 相对边缘阈值 | 0–1 | 0.1660 | 越低处理的边缘越多，可能增加采样开销 |
| 最小亮度阈值 | 0–1 | 0.0833 | 越低越能覆盖暗部和低对比边缘 |
| 边缘搜索步数 | 1–5 | 5 | 每个方向包含初始采样的搜索次数，越高越有利于长边缘 |

点击「恢复 FXAA 默认值」只重置这四项参数。参数在 FXAA 下生效，关闭页面时随原版选项保存，无须重启或重载资源。保存键为 `aamod_fxaa_subpixel`、`aamod_fxaa_edge_threshold`、`aamod_fxaa_minimum_threshold`、`aamod_fxaa_search_steps`；缺失、非有限数和越界值恢复对应默认值。Mod Menu 只提供 Fabric 配置入口，未安装时视频设置中的模式选项和 FXAA 仍可使用。

同一页面还提供 SSAA 世界分辨率倍率和 SMAA 质量预设。保存键 `aamod_ssaa_scale` 的 1–4 分别表示 1.5× / 2× / 3× / 4×；`aamod_smaa_quality` 的 0–3 分别表示低 / 中 / 高 / 极高。缺失或非法值恢复为 2× 和高。「恢复 FXAA 默认值」仅重置 FXAA。

SSAA 像素与附件开销随倍率平方增长。请求尺寸超过设备限制或处理失败时保留用户设置，显示失败状态；切换模式或重载资源后重试。SMAA 上游来源、转换说明与许可随 jar 打包在 `assets/aamod/licenses/`。

四阶段路线、架构、接入点、生命周期和前两阶段验证记录见 [抗锯齿四阶段实施与扩展方案](AA_IMPLEMENTATION_PLAN_ZH.md)。

第四阶段 TAA 的多种手动切换采样方案见 [时域采样方案](TEMPORAL_SAMPLING_PLAN_ZH.md)，包含 Halton、Hammersley、Owen-scrambled Sobol、Rank-1/R2 和渐进式泊松蓝噪声。该方案不依赖神经超分。

## 跨加载器工作区

本仓库已改造为基于 **Architectury** 的多加载器工作区：一份共用代码同时产出 Fabric 与 NeoForge 两个 jar。

```
.
├── build.gradle              # 根工程：Architectury 插件、Java/Kotlin 公共配置、元数据展开
├── settings.gradle           # 插件仓库 + include common / fabric / neoforge
├── gradle.properties         # 所有版本号与模组元数据集中在此
├── common/                   # 与加载器无关的代码与共享资源
│   └── src/main/
│       ├── kotlin/           # Kotlin 代码与 mixin、抗锯齿控制器和 PlatformHelper
│       └── resources/        # mixin 配置、assets/aamod/lang、图标
├── fabric/                   # Fabric 入口、fabric.mod.json、PlatformHelperImpl
└── neoforge/                 # NeoForge 入口、neoforge.mods.toml、PlatformHelperImpl
```

几条约定：

- `common/` 里**不要**出现 `net.fabricmc.*`（注解除外）或 `net.neoforged.*` 的引用。凡是加载器相关的代码都放到 `fabric/` 或 `neoforge/`。
- 共享资源（语言文件、图标、mixin 配置）放 `common/src/main/resources`，构建时由 `shadowCommon` 配置打进两个平台 jar。
- 平台模块消费 common 的两种产物：普通工程引用（编译与开发运行时）和 `transformProductionFabric` / `transformProductionNeoForge`（打包产物）。
- 平台差异走 `@ExpectPlatform`：在 `common/.../PlatformHelper.kt` 声明静态桩方法，再分别在
  `fabric/` 里写 `com.nts.aamod.platform.fabric.PlatformHelperImpl`、在 `neoforge/` 里写
  `com.nts.aamod.platform.neoforge.PlatformHelperImpl`，方法签名保持一致。NeoForge 的旧包由静态转发桥保留。
  Architectury 会在构建期把桩方法体改写成对「原包 + 平台名子包」实现类的转调，调用方始终只引用桩类。

## 环境要求

| 项目 | 值 |
| --- | --- |
| JDK | 25（`--release 25` 编译） |
| Minecraft | 26.2 |
| Gradle | 9.7.1（已带 wrapper） |
| Architectury Loom | 1.17-SNAPSHOT，**`dev.architectury.loom-no-remap`** |
| architectury-plugin | 3.5-SNAPSHOT |

本机默认 JDK 27 保持不变。已验证用 JDK 27 启动 wrapper 的 `--version`，但实际配置构建时 Groovy 报 `Unsupported class file major version 71`。当前构建和客户端验证在命令进程中显式使用 JDK 25，Java `--release`、Kotlin JVM / JDK API 目标均保持 25。

PowerShell 可在当前终端执行 `$env:JAVA_HOME='你的 JDK 25 安装目录'` 后运行以下命令，无须更改系统默认 Java。

> **为什么是 `loom-no-remap`？** Minecraft 26.1 起游戏本体不再混淆，Mojang 也就不为 26.2
> 发布官方映射了。普通的 `dev.architectury.loom` 仍强制要求 `mappings` 非空，会直接报
> `Failed to find official mojang mappings for 26.2`。`loom-no-remap` 变体完全跳过重映射：
> 不写 `mappings`、没有 `remapJar`、依赖配置不带 `mod*` 前缀、
> `namedElements` 换成普通的 `project(":common")` 引用。
> 此时 `shadowJar` 是最终产物，`jar` 被标记为 `-raw`。

## 构建

```bash
./gradlew build                 # 两个平台一起构建
./gradlew :fabric:build         # 只构建 Fabric
./gradlew :neoforge:build       # 只构建 NeoForge
./gradlew :fabric:runClient     # Fabric 开发客户端
./gradlew :neoforge:runClient   # NeoForge 开发客户端
```

成品分别在 `fabric/build/libs/` 与 `neoforge/build/libs/`，文件名形如
`aamod-fabric-<version>.jar` / `aamod-neoforge-<version>.jar`。
中间的 `*-raw.jar` 不是可发布产物。

> 想增删目标平台：改 `gradle.properties` 的 `enabled_platforms`，并同步 `settings.gradle` 的 `include`。
>
> **两个注意点**：① `org.gradle.configuration-cache` 必须保持 `false`，Architectury 的
> `TransformingTask` 带 Gson 字段，无法被 Gradle 配置缓存序列化；
> ② NeoForge 侧依赖 `maven.neoforged.net`，若只构建 Fabric 可用
> `./gradlew :fabric:build --configure-on-demand` 跳过 NeoForge 子工程的配置。

## 运行时依赖

| 加载器 | 需要额外安装的模组 |
| --- | --- |
| Fabric | Fabric API、fabric-language-kotlin、Architectury API |
| NeoForge | Kotlin for Forge、Architectury API |

Kotlin 标准库**没有**被打包进 jar：Fabric 侧由 `fabric-language-kotlin` 提供，
NeoForge 侧开发运行时由 `localRuntime("thedarkcolour:kotlinforforge-neoforge:...")` 提供，
玩家侧依赖写在 `neoforge.mods.toml` 里。入口类使用 Kotlin 编写，Fabric 保留默认构造入口，NeoForge 使用 JavaFML 加载入口。

如果不需要 Architectury 的统一事件 / 注册 / 网络 API，可以删掉 `dev.architectury:architectury*` 依赖
以及 `fabric.mod.json` / `neoforge.mods.toml` 中的 `architectury` 依赖项；
Architectury 的**构建工具链**与 `@ExpectPlatform` 并不依赖运行时 API。

## 元数据模板

`fabric.mod.json` 和 `META-INF/neoforge.mods.toml` 中的占位符由 `processResources`
用 `gradle.properties` 的值展开（见根 `build.gradle` 的 `metadata` map）。
键名一律用 `mod_*` 前缀 —— 裸的 `name` / `group` 会和 Gradle 内置的
`Project.name` / `Project.group` 冲突。

## 许可证

GPL-3.0，详见 [LICENSE](LICENSE)。
