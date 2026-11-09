# 抗锯齿模组（Anti Aliasing Mod）

[[**English**](../README.md)] | [**简体中文**]

## 介绍

作为 DXRMC 项目开发中的附属品，本模组把抗锯齿功能单独拿了出来并适配了 Minecraft 默认的 Blaze3D 渲染抽象层。

渲染实现使用 Blaze3D 抽象，不直接调用 OpenGL。代理执行的客户端检查使用 OpenGL；用户已在原版 Vulkan 和 Sodium + Vulkan 环境验证抗锯齿可正常运行。Vulkan 下 Iris 未启用时不查询其可选 API，也不应用光影暂停策略。

#### 当前实现状态

- **FXAA 已实现**：使用方向判定、边缘端点搜索和子像素处理，平滑世界与手持物品轮廓，可能轻微软化细节。
- **SSAA 已实现**：世界先按每轴 1.5×、2×、3× 或 4× 分辨率渲染，再进行面积覆盖低通降采样；默认 2×。
- **SMAA 1x 已实现**：颜色边缘检测、Area / Search 查找纹理权重计算、邻域混合，提供官方低 / 中 / 高 / 极高预设；默认高。
- **TAA 已实现**：世界投影亚像素抖动、世界深度与相机矩阵重投影、逐物体运动矢量、历史双缓冲、邻域约束和颜色变化拒绝。运动矢量覆盖实体／方块实体的模型部件动画及物品／方块烘焙几何。默认 Halton (2, 3) + 16 样本，在手持物品之前解析，手持物品与 GUI 不进入历史。

整体路线划分为以下五阶段：

| 阶段 | 目标 | 当前状态 |
| --- | --- | --- |
| 第一阶段 | 原版 FXAA 与公共扩展预留 | 已实现，验证范围见方案文档 |
| 第二阶段 | 原版 SSAA 与 SMAA 1x | 已实现，实际验证与限制见方案文档 |
| 第三阶段 | Iris / Sodium 适配 | 已实现，支持版本与边界见下文 |
| 第四阶段 | TAA | 重投影、物体运动与五种采样序列已完成声明范围内验收；Iris 光影开启时明确暂停 |
| 第五阶段 | 粒子、天气与世界效果时域接入 | 设计已记录，待实现：独立透明历史、效果运动及原版顺序无关透明兼容；见[完整架构方案](plan/TEMPORAL_EFFECTS_PLAN_ZH.md) |

已验证原版、Sodium 及下述支持范围内的 Iris 1.11.4 渲染路径；TAA 在未使用光影时运行，独立验收范围和限制见方案文档第 16 节。

在「选项 → 视频设置 → 抗锯齿」选择模式，下一有效世界帧生效；选择关闭即停用。设置通过原版 `options.txt` 的 `antialiasing` 键保存：关闭 = 0、FXAA = 1、SMAA = 2、TAA = 3、SSAA = 4，缺失或非法值回到关闭。FXAA / SSAA / SMAA 在原版实体视角特效之后、HUD / GUI 之前输出；TAA 在世界深度仍有效、手持物品之前解析。字体、准星与菜单不会被本模组过滤。

选择画质预设时联动抗锯齿：流畅（Fast）→ FXAA、精美（Fancy）→ SMAA、极佳（Fabulous）→ TAA；手动切换抗锯齿模式后，画质预设变为自定义。

#### FXAA 参数配置

Fabric 安装 **Mod Menu** 后，在「模组 → Anti Aliasing Mod → 配置」打开设置；NeoForge 使用内置模组列表的配置按钮。两端共用同一页面，可切换抗锯齿模式，并即时调整 FXAA 参数。

安装 Sodium 后，也可从「选项 → 视频设置 → Anti Aliasing Mod → 抗锯齿」进入同一配置页，关闭后返回 Sodium 视频设置。与 Iris 同装时保留该入口，此入口不依赖 Mod Menu。FXAA 参数如下：

| 参数 | 范围 | 默认值 | 作用 |
| --- | --- | --- | --- |
| 子像素混合强度 | 0–1 | 0.7500 | 越高越能平滑细小轮廓，也可能软化细节 |
| 相对边缘阈值 | 0–1 | 0.1660 | 越低处理的边缘越多，可能增加采样开销 |
| 最小亮度阈值 | 0–1 | 0.0833 | 越低越能覆盖暗部和低对比边缘 |
| 边缘搜索步数 | 1–5 | 5 | 每个方向包含初始采样的搜索次数，越高越有利于长边缘 |

顶部抗锯齿模式选项始终居中，不提供重置按钮；其余参数右侧均有独立的「重置」按钮，仅在该项偏离默认值时显示，恢复默认后自动隐藏，点击只重置对应项。无重置按钮时设置条自身居中，有按钮时设置条与按钮整体居中。抗锯齿模式默认关闭，FXAA 参数默认值见上表。参数在 FXAA 实际运行时生效，关闭页面时随原版选项保存，无须重启或重载资源。保存键为 `aamod_fxaa_subpixel`、`aamod_fxaa_edge_threshold`、`aamod_fxaa_minimum_threshold`、`aamod_fxaa_search_steps`；缺失、非有限数和越界值恢复对应默认值。Mod Menu 只提供 Fabric 配置入口，未安装时视频设置中的模式选项和 FXAA 仍可使用。

同一页面还提供 SSAA 世界分辨率倍率和 SMAA 质量预设。保存键 `aamod_ssaa_scale` 的 1–4 分别表示 1.5× / 2× / 3× / 4×；`aamod_smaa_quality` 的 0–3 分别表示低 / 中 / 高 / 极高。缺失或非法值恢复为 2× 和高。这两项也分别提供独立重置按钮。

SSAA 像素与附件开销随倍率平方增长。请求尺寸超过设备限制或处理失败时保留用户设置，显示失败状态；切换模式或重载资源后重试。SMAA 上游来源、转换说明与许可随 jar 打包在 `assets/aamod/licenses/`。

五阶段路线、架构、接入点、生命周期和前四阶段验证记录见 [抗锯齿五阶段实施与扩展方案](plan/AA_IMPLEMENTATION_PLAN_ZH.md)。第五阶段专项方案定义最终架构与验收门槛，尚未实现。

#### TAA 参数和边界

共享配置页和原版视频设置的画质分组提供「TAA 抖动序列」和「TAA 抖动样本数」。可选 Halton (2, 3)、Hammersley、Owen 扰动 Sobol、Rank-1 / R2 及固定渐进式泊松点表；每种均支持 8 / 16 / 32，默认 Halton + 16。共享配置页的这两项也有独立重置按钮。

保存键为 `aamod_taa_sequence`（按上述顺序 0–4）和 `aamod_taa_samples`（8 / 16 / 32），缺失或非法值恢复 0 / 16。切换任一项会清空历史，随后重新收敛；模式、尺寸、投影 / FOV、相机传送、世界 / 维度、资源和外部管线变化也会重置历史。仅 TAA 修改世界投影，剔除视锥使用稳定的一像素裕量。

两个配置入口另提供「TAA 后锐化强度」滑条，范围 0–1，默认 0（关闭），共享配置页有独立重置按钮。保存键为 `aamod_taa_sharpness`，缺失或非法值恢复 0。锐化仅处理解析后的世界输出，按局部对比调整强度并限制过冲，不将锐化结果写回时域历史；调节后下一有效世界帧生效，不清空历史，也不影响手持物品和 GUI。

F3 选项页的「抗锯齿模组」分组中提供 `taa_diagnostics`，使用原版「关闭 / 叠加层 / 常显」控制并保存到 `debug-profile.json`；原版默认和性能配置预设均设为随 F3 叠加层显示。内容包含运行状态、历史 epoch、已提交历史数、jitter 帧索引、锐化强度、最近一次重置原因，以及捕获顶点、匹配／拒绝批次和预算状态，不再占用快捷栏上方的提示行。提交数与匹配批次为 CPU 计数，不能代表实际采纳历史的像素比例。

TAA 通过实际前后帧矩阵与深度重建静态场景的相机运动；对捕获到的实体／方块实体模型部件及原版物品／方块烘焙几何，使用对应顶点的前帧位置，支持平移、旋转、缩放及模型部件动画。独立运动 pass 核验世界深度与纹理透明裁剪后，由 TAA 实际消费向量。新出现、无法匹配、半透明及自定义捕获几何拒绝历史；Fabric FRAPI／Indigo 和 Sodium 扩展物品／方块网格同样提供拒绝覆盖，尚无这类扩展网格的精确顶点历史，Sodium 下的 ModelPart 动画仍有精确对应。粒子、天气和其它未捕获效果仍依赖深度 / 颜色拒绝，可能残留拖影或无法累积。天空像素使用当前帧；手持物品、HUD、菜单和之后的屏幕效果不累积。它不提高渲染分辨率，也不与 SSAA 叠加。历史加 RGBA32F 运动纹理共占 44 字节 / 输出像素，1080p 约 87.0 MiB、4K 约 348.0 MiB，另有顶点／uniform／query 缓冲及驱动开销。每帧最多捕获 262,144 顶点、8,192 批次，超限时关闭当帧历史混合。参考版本、支持边界和验证记录见 [逐物体运动向量方案](plan/OBJECT_MOTION_VECTORS_PLAN_ZH.md)。

#### Iris 光影兼容

仅在安装 Iris 时，共享配置页显示 Iris 兼容分组、光影包抗锯齿策略及实时状态；未安装时整组隐藏，模式提示也不显示 Iris 说明。策略同样提供独立重置按钮，默认自动：

- **自动（默认）**：Iris 光影实际使用中时暂停本模组抗锯齿，保留用户请求模式；不修改光影包自身选项，也不会替光影包开启抗锯齿。
- **光影后叠加**：允许 FXAA / SMAA 在 Iris 最终颜色转换与原版世界特效之后、HUD 之前处理；可能与光影自身抗锯齿重复平滑。
- **光影开启时暂不支持 SSAA / TAA**：明确暂停并显示原因；TAA 即使选择「光影后叠加」也暂停，适配器未协调光影自身的 jitter、深度和历史。未使用光影时，即使安装了 Iris，四种算法仍可正常使用。

OpenGL 光影适配器支持 **Iris 1.11.4、sRGB 输出及 RGBA8 主颜色目标**。该路径下其它 Iris 版本、其它输出颜色空间、最终输出未就绪及 API 查询失败时明确暂停；阴影通道不执行最终颜色处理。原版 Vulkan 下先读取实际设备后端，直接使用原版渲染提供者，不加载 Iris 适配器、不查询 API，也不执行 Iris 版本校验，避免仅因安装了未启用的 Iris 而拦截抗锯齿。切包、管线重建时使本模组资源失效；光影管线准备期间也暂停处理，避免首帧误启动分辨率控制。

策略保存为 `aamod_iris_policy`：0 = 自动，1 = 光影后叠加，缺失或非法值恢复自动。配置页实时更新兼容状态，模式选项提示中显示具体暂停原因。

双端光影运行验证使用 Complementary Reimagined r5.9.3。NeoForge 开发环境的绘制校验会触发 Iris 顶点格式数组异常，移除本模组仍可复现。第四阶段验收与 `runClientWithIris` 一样使用加载器属性 `neoforge.disableGlValidation=true`，保持全局 IDE 标记不变；第三阶段使用的是临时生产模式验证程序。证据与独立的退出问题见方案文档。

第四阶段 TAA 的多种手动切换采样方案见 [时域采样方案](plan/TEMPORAL_SAMPLING_PLAN_ZH.md)，包含 Halton、Hammersley、Owen-scrambled Sobol、Rank-1/R2 和渐进式泊松蓝噪声。该方案不依赖神经超分。

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

- `../common` 里**不要**出现 `net.fabricmc.*`（注解除外）或 `net.neoforged.*` 的引用。凡是加载器相关的代码都放到 `fabric/` 或 `neoforge/`。
- 共享资源（语言文件、图标、mixin 配置）放 `../common/src/main/resources`，构建时由 `shadowCommon` 配置打进两个平台 jar。
- 平台模块消费 common 的两种产物：普通工程引用（编译与开发运行时）和 `transformProductionFabric` / `transformProductionNeoForge`（打包产物）。
- 平台差异走 `@ExpectPlatform`：在 `common/.../PlatformHelper.kt` 声明静态桩方法，再分别在
  `../fabric` 里写 `com.nts.aamod.platform.fabric.PlatformHelperImpl`、在 `neoforge/` 里写
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

成品分别在 `../fabric/build/libs` 与 `neoforge/build/libs/`，文件名形如
`aamod-fabric-<version>.jar` / `aamod-neoforge-<version>.jar`。
中间的 `*-raw.jar` 不是可发布产物。

> 想增删目标平台：改 `../gradle.properties` 的 `enabled_platforms`，并同步 `settings.gradle` 的 `include`。
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

**Sodium 与 Iris 均为可选依赖。** 本次接入目标为 Minecraft 26.2 的 Sodium 0.9.2、Iris 1.11.4，两端分别使用对应加载器版本。玩家需另行安装，发布 jar 不内置这两个模组或 Sodium API。视频设置适配使用 Sodium 公开 Config API；渲染支持范围见上文 Iris 光影兼容说明，不表示任意光影包、图形后端或未来版本均兼容。

开发客户端默认不加载二者，版本号与开关集中在 `../gradle.properties`。可按次启用，Windows 使用 `gradlew.bat`：

```bash
./gradlew :fabric:runClient -Penable_sodium=true
./gradlew :fabric:runClient -Penable_iris=true
./gradlew :neoforge:runClient -Penable_sodium=true
./gradlew :neoforge:runClient -Penable_iris=true
```

也可直接运行 `./gradlew :fabric:runClientWithIris` 或 `./gradlew :neoforge:runClientWithIris`，无需额外传入属性。
这两个任务继承普通客户端配置，仅向各自的运行类路径添加 Iris 和 Sodium；生成的 IntelliJ 配置通过 Gradle 任务启动。
NeoForge 的该任务还设置 `neoforge.disableGlValidation=true`，关闭会触发 Iris 崩溃的开发绘制校验；普通 `runClient` 保留原有校验设置。

`enable_iris=true` 会同时加入 Iris 必需的 Sodium；开关只影响开发运行时，不改变发布 jar 的打包依赖。

Kotlin 标准库**没有**被打包进 jar：Fabric 侧由 `fabric-language-kotlin` 提供，
NeoForge 侧开发运行时由 `localRuntime("thedarkcolour:kotlinforforge-neoforge:...")` 提供，
玩家侧依赖写在 `neoforge.mods.toml` 里。入口类使用 Kotlin 编写，Fabric 保留默认构造入口，NeoForge 使用 JavaFML 加载入口。

如果不需要 Architectury 的统一事件 / 注册 / 网络 API，可以删掉 `dev.architectury:architectury*` 依赖
以及 `fabric.mod.json` / `neoforge.mods.toml` 中的 `architectury` 依赖项；
Architectury 的**构建工具链**与 `@ExpectPlatform` 并不依赖运行时 API。

## 元数据模板

`fabric.mod.json` 和 `META-INF/neoforge.mods.toml` 中的占位符由 `processResources`
用 `../gradle.properties` 的值展开（见根 `build.gradle` 的 `metadata` map）。
键名一律用 `mod_*` 前缀 —— 裸的 `name` / `group` 会和 Gradle 内置的
`Project.name` / `Project.group` 冲突。

## 许可证

GPL-3.0，详见 [LICENSE](../LICENSE)。
