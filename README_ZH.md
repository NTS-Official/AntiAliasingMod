# 抗锯齿模组（Anti Aliasing Mod）

[[**English**](README.md)] | [**简体中文**]

## 介绍

作为 DXRMC 项目开发中的附属品，本模组把抗锯齿功能单独拿了出来并适配了 Minecraft 默认的 Blaze3D 渲染抽象层。

于是我们便可以在 Minecraft 默认的 OpenGL 与 Vulkan 后端中使用一些抗锯齿功能。

#### 目前包含的抗锯齿功能：
* FXAA：快速近似抗锯齿，直接对锯齿边做采样平均，对性能几乎没有影响。
* SMAA：子像素增强抗锯齿，将边缘的单个像素细分为多个子像素再进行平均值采样，以达成模糊锯齿边缘的效果，会有较小的性能损失。
* TAA：时序重建抗锯齿技术，利用历史帧导出的运动矢量计算模糊，达成极佳的抗锯齿效果，缺点是画面会变糊（如果你在基岩版用过 TAAU 的话，就会知道这一点）。

## 跨加载器工作区

本仓库已改造为基于 **Architectury** 的多加载器工作区：一份共用代码同时产出 Fabric 与 NeoForge 两个 jar。

```
.
├── build.gradle              # 根工程：Architectury 插件、Java/Kotlin 公共配置、元数据展开
├── settings.gradle           # 插件仓库 + include common / fabric / neoforge
├── gradle.properties         # 所有版本号与模组元数据集中在此
├── common/                   # 与加载器无关的代码与共享资源
│   └── src/main/
│       ├── kotlin/           # AAMod、AAModClient、VideoAntiAliasingSettings
│       ├── java/             # mixin + PlatformHelper（@ExpectPlatform）
│       └── resources/        # mixin 配置、assets/aamod/lang、图标
├── fabric/                   # Fabric 入口、fabric.mod.json、PlatformHelperImpl
└── neoforge/                 # NeoForge 入口、neoforge.mods.toml、PlatformHelperImpl
```

几条约定：

- `common/` 里**不要**出现 `net.fabricmc.*`（注解除外）或 `net.neoforged.*` 的引用。凡是加载器相关的代码都放到 `fabric/` 或 `neoforge/`。
- 共享资源（语言文件、图标、mixin 配置）放 `common/src/main/resources`，构建时由 `shadowCommon` 配置打进两个平台 jar。
- 平台模块消费 common 的两种产物：普通工程引用（编译与开发运行时）和 `transformProductionFabric` / `transformProductionNeoForge`（打包产物）。
- 平台差异走 `@ExpectPlatform`：在 `common/.../PlatformHelper.java` 声明静态桩方法，再分别在
  `fabric/` 里写 `com.nts.aamod.platform.fabric.PlatformHelperImpl`、在 `neoforge/` 里写
  `com.nts.aamod.aamod.platform.neoforge.PlatformHelperImpl`，方法签名保持一致。
  Architectury 会在构建期把桩方法体改写成对「原包 + 平台名子包」实现类的转调，调用方始终只引用桩类。

## 环境要求

| 项目 | 值 |
| --- | --- |
| JDK | 25（`--release 25` 编译） |
| Minecraft | 26.2 |
| Gradle | 9.7.1（已带 wrapper） |
| Architectury Loom | 1.17-SNAPSHOT，**`dev.architectury.loom-no-remap`** |
| architectury-plugin | 3.5-SNAPSHOT |

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
NeoForge 侧开发运行时由 `forgeRuntimeLibrary("thedarkcolour:kotlinforforge-neoforge:...")` 提供，
玩家侧依赖写在 `neoforge.mods.toml` 里。入口类都是普通 Java 类，因此模组元数据里不再需要 Kotlin 语言适配器。

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
