# 抗锯齿五阶段实施与扩展方案

## 1. 文档状态与阶段划分

本文档整理五阶段设计、前四阶段实现和实际验证记录。第一阶段原始设计保留为历史基线；前四阶段具体落地和测试覆盖见第 12–16 节，第五阶段完整架构与验收边界见第 17 节及专项方案。

当前状态：第一阶段原版 FXAA、第二阶段原版 SSAA / SMAA 1x 与参数配置已实现；第三阶段已完成指定版本与支持边界内的 Iris / Sodium 适配；第四阶段已实现传统 TAA、五种采样序列和已接入几何的逐物体运动向量，并于 2026-11-09 完成声明范围内的工程检查与用户目视验收。开启光影时支持手动叠加 FXAA / SMAA，SSAA / TAA 明确暂停。三个渲染阶段、分辨率管理和兼容提供者继续作为扩展边界。实际验证范围和环境限制按第 12–16 节分别报告，早期阶段文字保留当时的实现状态。

第五阶段已建立粒子、天气及其它世界效果的完整时域接入方案，包含独立透明历史及原版顺序无关透明合成兼容，当前为设计、待实现。第四阶段的验收结论不扩展到这些新增能力。

| 阶段 | 目标 | 交付边界 |
| --- | --- | --- |
| 第一阶段 | 完成原版渲染路径的 FXAA，以及后续算法和兼容层需要的接入预留 | Fabric、NeoForge 共用实现，包含实际效果、模式切换、持久化、资源管理和原版路径验收 |
| 第二阶段 | 完成原版渲染路径的 SSAA 与 SMAA | SSAA 超采样分辨率管理与降采样、SMAA 1x 三遍处理、参数与持久化、两平台画质和资源验收 |
| 第三阶段 | 完成 Iris / Sodium 适配 | 按 FXAA / SSAA / SMAA 分别核实输入与支持范围，可选依赖隔离、光影策略、设置入口及组合验证 |
| 第四阶段 | 完成 TAA | 相机 jitter、历史缓冲、重投影、遮挡判定与历史约束、多采样序列及分算法兼容验收 |
| 第五阶段 | 完成粒子、天气与世界透明效果的时域接入 | 实际渲染帧运动、独立层历史、覆盖／响应性、原版六目标顺序无关透明及原绘制顺序兼容、资源预算和双端／用户验收；设计已记录，待实现 |

第一、二阶段的“原版”指未使用 Iris / Sodium 替换的 Minecraft 世界与 GUI 渲染路径，运行于现有 Fabric 或 NeoForge 加载器。原版路径验证通过后，Iris / Sodium 组合的支持结论仍需第三阶段分别给出；TAA 的兼容结论在第四阶段单独补充。

## 2. 实施前基线与工程约束

- Minecraft 版本为 26.2，工程使用 Architectury，包含 `common`、`fabric`、`neoforge` 三个模块。
- 实施前抗锯齿选项已有 `OFF=0`、`FXAA=1`、`SMAA=2`、`TAA=3`，均为占位；现在 FXAA / SSAA / SMAA 1x / TAA 具备实际渲染实现，旧编号保留，追加 `SSAA=4`。
- 核心实现及客户端 mixin 使用 Kotlin，接入原版调用点。
- 渲染使用 Blaze3D 抽象，不直接调用 OpenGL。
- 保留 `dev.architectury.loom-no-remap`、现有共享代码打包方式及关闭 Gradle configuration cache 的配置。
- 现有用户改动需要保留，尤其是设置类、构建脚本和已有中文文档。
- 本机默认 Java 环境按用户说明为 JDK 27，已确认 Temurin 27 安装可用。代理进程继承的 `PATH`、`JAVA_HOME` 可能与用户终端不同。
- 原计划显式选择 JDK 27，实际已确认 wrapper 的 launcher / daemon JVM 均为 27，但配置构建时 Groovy 报 `Unsupported class file major version 71`。当前使用命令进程内的 JDK 25 完成构建与运行验证，不修改系统默认 Java。
- Java `--release 25`、Kotlin JVM 25 及 `-Xjdk-release=25` 保持一致，限制字节码和 JDK API 目标；不因本机默认 JDK 为 27 提升模组最低 Java 版本。

## 3. 总体架构

```text
玩家选择与 Options 持久化
    ↓
AntiAliasingController
    ├─ 请求模式 / 本帧有效模式 / 暂停或失败原因
    ├─ 帧快照与渲染阶段调度
    ├─ 输入能力检查与资源失效通知
    ├─ RenderEnvironment / 兼容提供者边界
    └─ AntiAliasingMethod
        └─ FxaaMethod              第一阶段实现
        └─ SSAA / SMAA            第二阶段实现
        └─ TAA                    第四阶段实现
             └─ 世界效果与透明历史  第五阶段设计，待实现
```

第一阶段采用小型、明确的内部接口，不构建通用第三方插件系统，不为未实现算法分配纹理或执行空 pass。

### 3.1 公共组件职责

| 组件 | 职责 |
| --- | --- |
| `AntiAliasingController` | 根据设置和环境选择实现，建立帧状态，检查能力，在正确阶段调用，统一报告运行状态 |
| `AntiAliasingMethod` | 声明所需输入、工作阶段及生命周期；由算法持有自己的 GPU 资源 |
| `FrameContext` | 保存一帧内一致的模式、主视图身份、帧编号、渲染尺寸与可用输入 |
| `RenderEnvironment` | 提供实际颜色目标、尺寸、格式、颜色语义及当前渲染路径的能力描述 |
| `FxaaMethod` | FXAA 参数、着色器配置、两个全屏 pass、处理链与所属资源 |
| 兼容提供者边界 | 第一阶段提供原版环境；第三阶段接入 Iris 状态与策略，不让控制器直接依赖 Iris 类型 |

### 3.2 设置状态与运行状态分离

用户保存的请求模式不能被兼容判断或一次渲染失败覆盖。运行状态至少能表达关闭、运行中、尚未实现、缺少输入、兼容策略暂停以及资源加载失败。

例如选择 TAA 时，保存值仍为 TAA，但当前状态为“尚未实现”，不执行 FXAA，也不把配置改成 OFF。第三阶段因 Iris 自动策略暂缓算法时，同样保留玩家原选择。

## 4. 第一阶段：原版 FXAA

### 4.1 用户可见行为

| 操作或场景 | 预期结果 |
| --- | --- |
| 选择 FXAA | 下一次有效世界画面渲染时生效，无须重启或重新进入世界 |
| 选择 OFF | 停止执行 FXAA，恢复原版画面 |
| 选择 SMAA / TAA | 保留选项编号，明确提示尚未实现，不执行其它算法替代 |
| 启动或重启客户端 | 读取已保存模式；缺失或非法值回到 OFF |
| 打开设置、聊天和背包 | GUI 与文字不经过本模组 FXAA，保持清晰 |
| 窗口缩放或全屏切换 | 按真实渲染尺寸更新资源，无黑边、拉伸或旧画面残留 |
| 资源重载 | 旧处理链失效，在合适时机重新创建 |
| 离开世界或关闭客户端 | 停止无效处理，按生命周期释放资源 |

处理范围包括世界几何、透明物体、粒子及第一人称手持物品。FXAA 是单帧图像处理，会降低部分轮廓锯齿，也可能轻微软化细节；不承诺消除所有纹理闪烁或获得时域稳定性。

### 4.2 渲染接入位置

根据已只读核对的 Minecraft 26.2 源码，目标顺序为：

```text
GameRenderer.render()
    ↓
世界渲染，包括手持物品和屏幕效果
    ↓
实体轮廓合成
    ↓
原版实体视角后处理
    ↓
AntiAliasingController：最终颜色阶段
    └─ FXAA
    ↓
GUI / HUD / 菜单
```

- 在外层 `GameRenderer.render()` 的世界处理完成后、GUI 绘制前执行 FXAA。
- 不直接在 `renderLevel()` 返回时执行最终 FXAA，因为外层还有实体轮廓和原版实体后处理。
- 不占用原版 `postEffectId`，保留蜘蛛、苦力怕等实体视角特效。
- 仅处理本帧真正渲染过的主世界视图，主菜单、启动画面和无有效画面的帧跳过。
- 每个主视图帧最多执行一次；调用点与帧身份分开处理，避免未来递归视图或阴影视图重复运行。
- 注入目标应使用明确的方法描述符和调用语义定位，避免依赖源码行号；实施时核对两个加载器的实际转换结果。

### 4.3 算法内容

采用 FXAA 3.11 Quality 路径的算法结构，完整实现以下步骤：

1. 采样中心与周围像素，计算 RGB 亮度及局部对比度。
2. 对低于阈值的区域提前输出原色。
3. 根据邻域变化判断边缘主要沿水平还是垂直方向延伸。
4. 选择边缘法线，沿边缘两端进行有上限的质量搜索。
5. 利用端点距离和亮度符号关系计算有效边缘修正。
6. 计算亚像素修正，并与边缘修正合并。
7. 在修正位置进行双线性重采样，输出处理后的颜色。

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| Search steps | 5 | 每个方向含初始采样的搜索次数，可调整为 1–5 |
| Subpixel | 0.75 | 亚像素平滑强度 |
| Edge threshold | 0.166 | 相对对比度阈值 |
| Minimum threshold | 0.0833 | 最低亮度对比度阈值 |

按追加需求，这些值通过共享模组配置页面调整；前三个浮点参数范围为 0–1。Fabric 通过 Mod Menu 提供入口，NeoForge 使用内置模组列表。具体效果通过固定机位和固定场景对照验证，不以全屏模糊替代 FXAA。

### 4.4 GPU 数据流与颜色约定

```text
主颜色目标
    → FXAA pass → 临时颜色目标
    → 回写 pass → 主颜色目标
```

- 不在同一次绘制中同时采样与写入同一纹理。
- FXAA 采样使用双线性过滤及边缘钳制；回写保持像素对齐，避免额外平滑。
- texel 尺寸来自实际输入纹理，不能使用经过 GUI 缩放的逻辑尺寸。
- 亮度从 RGB 计算，不假设原版 alpha 通道已存储亮度，也不将亮度写回 alpha 破坏已有含义。
- 第一阶段针对已核对的原版 `RGBA8_UNORM` 最终颜色目标；实施时验证颜色编码和采样路径，避免重复 gamma 转换和整体明暗变化。
- FXAA 不依赖深度、历史、运动矢量或投影抖动，不修改相机矩阵。

### 4.5 后处理链与资源生命周期

使用 `PostChainConfig`、`PostChain`、`PostPass`、`FrameGraphBuilder` 以及 Blaze3D 资源分配接口。优先通过 `addToFrame()` 提交处理，不新增对已标记弃用的 `PostChain.process()` 便捷入口的依赖。

FXAA 自主管理处理链与相关资源，避免直接依赖 `ShaderManager.getPostChain()` 的失败恢复行为。着色器源码仍使用原版支持的资源与编译机制；实施时核对编译失败的实际传播路径，验证可选效果失败不会意外触发全局资源恢复。

| 生命周期事件 | 处理要求 |
| --- | --- |
| 首次启用 | 在资源就绪且线程正确时创建处理链，避免每帧编译 |
| 每帧运行 | 复用处理链和资源池，获取本帧有效输入，更新必要尺寸信息 |
| 尺寸 / 格式变化 | 使旧目标失效并按新描述创建，不保留旧纹理视图 |
| 关闭或切换算法 | 停止提交旧算法，释放不再需要的资源 |
| 资源重载成功应用 | 使旧链失效，后续按新资源重新创建 |
| 离开世界 | 重置世界相关状态，清理不再使用的资源 |
| 客户端关闭 | 关闭所属处理链、资源池、投影缓冲及其它 GPU 对象 |

GPU 创建、销毁和资源重载应用遵守渲染线程边界。只有资源所有者负责关闭资源，避免控制器与算法重复释放。

加载或编译失败时，记录明确日志，将当前资源版本的 FXAA 标记为失败，继续原版画面；不每帧重复尝试或刷日志。资源重载后允许重新尝试。运行期失败应阻止后续无效提交，并保证资源清理；不承诺所有底层 GPU 故障均可恢复。

显存统计以实际分配为准：原版 `PostChain` 临时目标可能附带深度附件，不能只按一张 RGBA 颜色纹理估算。

### 4.6 模式持久化与第一阶段 UI

将抗锯齿选项接入原版 `Options.processOptions(FieldAccess)` 的读取与保存流程，继续使用 `antialiasing` 键及现有模式编号。

- 替代目前未接通且实现不完整的手工文件读写路径，避免多个写入入口覆盖 `options.txt`。
- 通过合法值校验处理缺失、损坏和越界数据。
- 加载设置时抑制“切换到自定义画质预设”的副作用；玩家主动改变模式时保留该行为。
- 保留已有画质预设映射，不把 SMAA / TAA 静默映射成 FXAA。
- 设置回调只修改设置和待处理状态；GPU 资源操作由渲染侧执行。
- 第一阶段保留原版视频设置中的模式选项，增加共享模组配置页面，提供模式选择、四项 FXAA 参数和恢复默认按钮；中英文说明明确 FXAA 已实现而 SMAA / TAA 尚未实现。
- 第一阶段不添加 Iris 策略选项、Sodium 选项页或独立兼容设置页面。

## 5. 第一阶段必须完成的扩展预留

### 5.1 渲染阶段

| 阶段 | 第一阶段落地要求 | 后续用途 |
| --- | --- | --- |
| `FRAME_PREPARE`：帧状态准备、投影提取或确定前 | 找到语义明确的入口，建立一致的帧状态和主视图标识；FXAA 不修改投影 | SSAA 分辨率方案、TAA jitter、历史 epoch 和当前帧参数准备 |
| `WORLD_RESOLVE`：所需世界输入可用时 | 明确阶段入口及能力边界；无算法需要时不分配输入、不执行 pass | TAA 重投影与解析，以及确有需要的深度相关处理 |
| `FINAL_COLOR`：最终颜色完成、GUI 之前 | 实际接入控制器并执行 FXAA | SSAA 降采样、SMAA 空间处理、最终输出或后续明确顺序的合成 |

阶段名描述语义，不意味着 TAA 必须在原版所有后处理之后运行。后续时域解析点需要根据颜色空间、深度有效期和输入可用性确定；第一阶段应记录原版候选调用点与输入寿命，不能用错误位置的空回调假装完成预留。

尤其需要核对 Minecraft 26.2 的状态提取与实际渲染边界：投影预留必须早于 jitter 需要影响的矩阵提取，而不仅仅早于最后一次全屏绘制。第一阶段建立跨阶段快照边界，但不提前生成 TAA jitter。

### 5.2 算法输入与输出契约

算法声明需要的颜色、深度、历史、相机矩阵、投影控制等能力，控制器仅在能力满足时调度。第一阶段 FXAA 只请求最终颜色。

输入描述应能区分纹理尺寸、格式、颜色语义和有效期。对于后续 TAA，还需区分世界深度与第一人称物品使用的深度：不能把被清理或重用于手持物品的主深度目标当作完整世界深度。

输出与资源契约允许：

- 一个算法包含多个 pass，适配 SMAA 的边缘检测、权重计算、邻域混合。
- 算法读取独立查找纹理，并持有多个临时目标。
- 第二阶段为 SSAA 补充分辨率请求、实际世界渲染尺寸与最终输出尺寸的独立描述，以及降采样目标交接；现有最终颜色处理接口本身不代表已经支持超采样。
- 后续算法持有跨帧双缓冲历史，区别于只在单帧有效的临时目标。
- 后续引入当前 / 上一帧矩阵和 jitter 信息，而不让 FXAA 依赖这些数据。

### 5.3 失效通知与帧一致性

建立统一的失效原因：算法切换、目标尺寸变化、格式变化、资源重载、世界 / 维度变化、相机切换，以及未来兼容提供者的渲染管线变化。

FXAA 收到通知时只处理自己实际拥有的资源。后续 TAA 可据此清空历史并更新 epoch。不要第一阶段就分配历史纹理或实现尚不存在的运动数据管线。

同一帧采用一致的模式、环境与阶段参数。设置或管线变化在安全边界生效，避免未来出现“投影阶段启用 TAA、解析阶段却切换到 FXAA”的不一致。

### 5.4 兼容层预留

第一阶段提供原版 `RenderEnvironment` 与内部能力查询接口，并保留外部管线变化的失效入口。Iris 或 Sodium 类型不进入公共算法接口。

第三阶段可以替换或补充环境提供者及支持判定，而无需重写算法着色器和公共调度器。第一、二阶段不添加 Iris / Sodium 依赖、不调用其 API，也不设置未经验证的“兼容”标记。

### 5.5 与已有 TAA 方案的关系

第四阶段 TAA 采样器与历史设计参见 [TEMPORAL_SAMPLING_PLAN_ZH.md](TEMPORAL_SAMPLING_PLAN_ZH.md)。第一阶段时该文档仅为后续设计资料；现在已实现，实际职责、采样检查和历史重投影验证分别记录在该文档第 11 节及本文第 16 节。

本阶段的预留应容纳采样序列、jitter controller 和历史失效机制；具体实现、默认参数和时域画质验收留待第四阶段。

## 6. 第一阶段建议文件组织

```text
common/src/main/kotlin/com/nts/aamod/
    client/antialiasing/
        AntiAliasingController.kt
        AntiAliasingMethod.kt
        FrameContext.kt
        RenderEnvironment.kt
        fxaa/FxaaMethod.kt
    client/mixin/render/
        GameRendererMixin.kt
        其它确有需要的提取或重载桥接
    client/mixin/option/
        OptionsMixin.kt
    client/option/
        VideoAntiAliasingSettings.kt
        FxaaSettings.kt
    client/gui/AntiAliasingConfigScreen.kt

common/src/main/resources/
    aamod.client.mixins.json
    assets/aamod/post_effect/fxaa.json
    assets/aamod/shaders/post/
        FXAA 着色器及必要的顶点 / 回写资源
    assets/aamod/lang/en_us.json
    assets/aamod/lang/zh_cn.json
```

按现有代码习惯组织 Kotlin 源集；静态 mixin 使用私有 companion object 与 `@JvmStatic`。NeoForge 开发运行通过 Loom 显式注册主源集，确保扫描 Kotlin 输出中的模组入口。具体资源文件名服从 26.2 的后处理配置规范。

渲染核心留在 `common`。仅当实际生命周期要求加载器适配时才修改 Fabric / NeoForge 入口；不顺带迁移现有 NeoForge 包名前缀。

## 7. 第一阶段实施顺序与验收

### 7.1 实施顺序

1. 核对原版三个语义阶段、矩阵提取顺序、颜色 / 深度寿命和两个加载器的入口。
2. 建立小型控制器、算法契约、帧快照、能力和失效接口，接入必要阶段边界。
3. 接通模式持久化与运行状态，保留模式编号及已有预设语义。
4. 完成 FXAA Quality 着色器、后处理配置及两个全屏 pass。
5. 接入最终颜色阶段，完成尺寸变化、资源重载、停用、世界退出和关闭清理。
6. 更新翻译与说明，构建两平台产物，完成原版运行与画面对照验收。

### 7.2 工程验证

以下命令是工程验证入口；JDK 27 的实际失败与 JDK 25 的执行结果见第 12 节：

```powershell
./gradlew.bat --version
./gradlew.bat :fabric:build
./gradlew.bat :neoforge:build
```

检查最终分发 jar 中的着色器、后处理配置、mixin 和共享代码。检查 Java / Kotlin 输出目标为 25，两个加载器实际加载的注入点均成立。

仓库当前没有专用测试套件；第一阶段不为外观变化新增镜像实现式测试。构建、资源完整性、日志及客户端行为是主要验证手段；实际新增独立且必要的纯逻辑时再评估测试方式。

### 7.3 原版运行验收矩阵

| 类别 | 验收内容 |
| --- | --- |
| 真实效果 | 同一机位、相同分辨率切换 OFF / FXAA，斜边与高对比轮廓可见改善 |
| 画面内容 | 方块斜边、栅栏、树叶、远处细线、透明、粒子和手持物品 |
| GUI | 字体、聊天、准星、背包和菜单无额外模糊 |
| 原版特效 | 实体视角、受伤、水下、屏幕覆盖及实体轮廓效果顺序正确 |
| 设置 | 下一有效帧切换、重启恢复、非法值回退、未实现模式状态明确 |
| 生命周期 | 连续切换模式、resize、全屏、资源重载、换维度、退出重进及关闭 |
| 资源 | 无每帧编译、无重复日志、无持续资源增长或旧纹理引用 |
| 性能 | 相同场景记录 OFF / FXAA 帧时间和实际资源分配，不预先承诺 FPS 数字 |
| 扩展边界 | 三阶段语义、输入寿命、帧快照和失效入口清楚；FXAA 不修改投影或创建历史 |

Fabric 与 NeoForge 分别记录结果。编译成功、客户端成功启动和实际画面验证分开报告；受环境限制未执行的项目明确标注，不视为通过。

## 8. 第二阶段：原版 SSAA / SMAA

### 8.1 交付范围与模式设置

第二阶段在已完成的 FXAA 与公共控制器基础上实现两个可独立选择的空间抗锯齿模式，首先完成原版路径在 Fabric、NeoForge 上的运行验证。TAA 仍显示尚未实现；Iris / Sodium 集成在第三阶段开展。

保留 `OFF=0`、`FXAA=1`、`SMAA=2`、`TAA=3` 的已有持久化编号，已为 SSAA 追加 `SSAA=4`，不重排旧值。超采样倍率和 SMAA 质量参数随模式通过原版设置流程保存；加载配置不修改玩家画质预设。

### 8.2 SSAA：高分辨率渲染与降采样

SSAA 先以高于输出分辨率的尺寸渲染世界，再在 GUI 绘制前降采样到输出目标。单纯放大已经完成的画面不能构成 SSAA。

- 在相机 / 世界状态提取前确定本帧分辨率方案，分别记录输出尺寸、请求倍率和实际世界目标尺寸。倍率按每个轴定义，实际提供 1.5× / 2× / 3× / 4×，默认 2×；例如 1.5× 对应约 2.25 倍像素、2× 对应约 4 倍像素。
- 在控制器与环境提供者之间补充分辨率管理职责，统一创建、切换和恢复世界颜色 / 深度目标。SSAA 需要影响实际渲染尺寸，不只在 `FINAL_COLOR` 增加一个处理 pass。
- 核对世界投影、视口、透明目标、手持物品、屏幕效果、实体轮廓及原版实体后处理都使用一致的超采样尺寸；窗口 / GUI 尺寸维持输出尺寸，鼠标坐标与界面布局保持一致。
- 在世界最终颜色完成后降采样，使用匹配采样覆盖范围的低通滤波；滤波核须随倍率处理，避免仅靠一次双线性缩放造成欠采样。输出后再绘制 HUD / GUI，避免界面被降采样。
- 倍率、窗口、目标格式、世界或模式变化时在安全帧边界使相关资源失效；停用后恢复原版尺寸并释放目标。检查设备最大纹理尺寸和分配失败，保留请求设置并报告明确状态。
- 将超采样资源成本按实际颜色、深度及其它附件统计；核对截图、全屏和窗口缩放得到正确输出尺寸。当前阶段不默认叠加其它抗锯齿算法。

第三阶段须重新验证第三方管线是否允许本模组控制世界渲染尺寸；具备最终颜色输入不意味着能够支持 SSAA。

### 8.3 SMAA：空间三遍处理

第二阶段实现不使用历史与投影抖动的 **SMAA 1x**，沿用最终颜色阶段的多 pass 契约：

1. 边缘检测：使用明确的颜色 / 亮度语义生成边缘纹理，并处理阈值和局部对比适配。
2. 混合权重计算：沿边缘搜索，使用 Area / Search 查找纹理处理交叉、斜线及角点，生成混合权重。
3. 邻域混合：根据权重合成到独立输出，再安全写回主颜色，避免同一 pass 读写同一颜色附件。

引入查找纹理前核实上游来源与许可，记录资产来源和所需声明；核对纹理格式、坐标方向、采样方式与所移植着色器版本一致。边缘与权重目标按实际尺寸复用，在切换、resize、重载和关闭时正确清理。

质量参数仅影响实际存在的边缘检测和搜索逻辑。SMAA 1x 不分配历史或运动矢量；SMAA T2x 等时域变体不属于本阶段。

### 8.4 实施顺序与验收

先补齐分辨率管理与设置契约，实现和验收 SSAA；随后接入 SMAA 三遍处理与查找纹理，最后对所有已实现模式做回归。每种算法各自管理资源，并继续使用公共失败隔离与失效通知。

| 类别 | 第二阶段验收内容 |
| --- | --- |
| SSAA 真实性 | 检查实际世界颜色 / 深度目标尺寸随倍率增长，确认世界先高分辨率渲染、后降采样 |
| SMAA 完整性 | 检查边缘、权重、邻域混合三个阶段及查找纹理均实际使用，斜边、角点与细线效果正确 |
| GUI 与内容 | 相同机位对照 OFF / FXAA / SSAA / SMAA；检查字体、准星、菜单、透明、粒子和手持物品 |
| 原版后处理 | 核对实体视角、屏幕覆盖、实体轮廓与降采样 / SMAA 的先后顺序 |
| 生命周期 | 切换算法和参数、resize、全屏、资源重载、换维度、退出重进，资源无持续增长 |
| 设置兼容 | 旧模式编号保持原义，SSAA 新值与参数可重读，非法参数回退有明确行为 |
| 资源与性能 | 各倍率 / 质量档记录实际附件分配和帧时间，验证设备尺寸限制与失败恢复 |
| 双平台 | Fabric 与 NeoForge 分别完成构建、客户端运行和画面对照，不以共享实现代替运行验证 |

## 9. 第三阶段：Iris / Sodium 兼容

### 9.1 可选集成与状态检测

- 使用隔离的兼容适配器，仅在检测到对应模组和可用 API 后加载，确保未安装时可正常运行。
- 优先使用 Iris 公开 API，区分“已安装”“光影实际使用中”和“正在渲染阴影”。
- 查询失败或版本不支持时，提供明确运行状态，不将异常吞掉后假定兼容。
- 版本特定适配集中放置，不侵入 FXAA / SSAA / SMAA 算法实现。

### 9.2 Iris 策略与目标顺序

| 环境 / 策略 | 目标行为 |
| --- | --- |
| 无 Iris，或 Iris 未使用光影 | 正常执行支持的算法 |
| 光影使用中，自动策略 | 默认由光影负责抗锯齿，暂停本模组处理并展示原因 |
| 光影使用中，用户选择光影后叠加 | 按算法确认支持后，在光影最终颜色输出之后运行 FXAA / SMAA；SSAA 另需分辨率控制适配 |
| 阴影或非主视图 | 跳过最终颜色处理 |

自动为建议默认策略。Iris 公共状态查询不能可靠判断任意光影包自身是否已启用 FXAA / TAA，因此不按名称猜测，也不修改光影包设置。

FXAA / SMAA 叠加路径的目标顺序是：光影世界和最终合成 → Iris 最终颜色处理并写回有效主颜色目标 → 原版后续处理 → 本模组空间后处理 → GUI。SSAA 的高分辨率控制从世界渲染前开始，须另行确认与光影分辨率、目标重建和最终降采样的协作。

实施时必须重新核实目标 Iris 版本的顺序、目标尺寸、格式、颜色空间、Blaze3D 状态及输出有效期。对未知 HDR 或其它输出路径不直接套用原版 RGBA8 假设。

不读取或改写 Iris 内部历史和 `colortex`，不通过 mixin 优先级猜测最终合成顺序。光影开关、切包或管线重建时，通知公共层使相关资源失效。

### 9.3 Sodium 与设置入口

Sodium 可能替换原版视频设置界面，因此不能把原版 `VideoSettingsScreen` 注入当作唯一设置入口。

FXAA 参数配置已提前接入共享模组配置页面，Fabric 使用 Mod Menu，NeoForge 使用内置配置入口。第二阶段随算法加入 SSAA 倍率和 SMAA 质量，第三阶段再增加 Iris 策略及兼容状态。Sodium 0.9.2 已通过公开 Config API 注册外部页面，在其视频设置的本模组分组中提供抗锯齿入口，复用共享配置页并返回原 Sodium 页面；具体接入与验收记录见第 14 节。

第三阶段开始时核实所选 Iris / Sodium 版本在两个加载器上的依赖和 UI 变化，不将不同加载器的支持情况混为一谈。

### 9.4 兼容支持按算法分别判定

第三阶段覆盖已有 FXAA / SSAA / SMAA，并分别记录支持状态。FXAA 需要有效最终颜色；SMAA 还需确认颜色语义、采样与查找纹理处理；SSAA 需要控制实际世界分辨率及正确降采样。某个算法通过不能替代其它算法的兼容验收，无法满足输入时明确暂停，不静默切换算法。

第四阶段 TAA 需要与光影自身的 jitter、历史、深度、运动信息及解析顺序协调。第三阶段保留相应能力边界，TAA 的具体适配与验收在第四阶段另行完成。

### 9.5 第三阶段验收矩阵

| 组合 | 重点 |
| --- | --- |
| Sodium，无光影 | 分别验证 FXAA / SSAA / SMAA 实际效果、倍率 / 质量设置和 GUI 清晰度 |
| Iris 安装但光影关闭 | 分别验证三种已实现算法的运行与目标尺寸 |
| Iris 光影开启，自动策略 | 暂停原因清晰，用户请求模式不被改写 |
| Iris 光影开启，光影后叠加 | 对声明支持的 FXAA / SMAA 验证实际效果、亮度、手持物品、透明内容和 GUI |
| Iris 光影开启，SSAA | 验证分辨率控制与降采样；不支持的组合明确暂停并报告原因 |
| 光影包自身 AA 的不同设置 | 检查重复平滑，确认玩家能明确控制叠加 |
| 光影开关 / 切包 / 重载 / resize | 无旧目标引用、资源重复增长和状态污染 |
| 可选依赖缺失或版本不符 | 基础模组正常加载，支持状态明确 |

结果记录实际验证的 Minecraft、加载器、Iris、Sodium 与光影包版本；只读源码适配不等于运行验证通过。

## 10. 第四阶段：TAA

### 10.1 实现范围

在前三阶段的调度、分辨率管理及兼容能力基础上，实现传统 TAA：确定性的相机 jitter、当前 / 上一帧矩阵、历史双缓冲、重投影、遮挡与越界拒绝、历史约束和解析输出。先在原版路径完成基础闭环，再逐项扩展和验证第三方管线支持。

在世界深度仍有效的 `WORLD_RESOLVE` 阶段安排解析，实施时重新核对透明、手持物品和屏幕后处理的输入寿命与顺序。相机运动可结合深度和矩阵重建；动态实体、透明和手持物品需要明确的运动信息或单独处理策略，缺少输入时不能宣称完整重投影支持。

当前 / 上一帧实际投影、jitter、渲染尺寸和历史 epoch 必须一致。模式、采样参数、尺寸、资源版本、相机、世界 / 维度及外部管线变化时，使历史失效并从有效当前帧重新初始化。

### 10.2 采样序列与兼容

采用 [时域采样方案](TEMPORAL_SAMPLING_PLAN_ZH.md) 中的统一采样器与 jitter controller，先建立 Halton 基线，再接入 Hammersley、Owen-scrambled Sobol、Rank-1/R2 和渐进式泊松点表；验证后提供采样序列和样本数设置。序列生成与历史处理分层，GUI 投影保持稳定。

Iris / Sodium 的基础适配在第三阶段完成，TAA 仍需单独验证与光影自身 jitter、历史、深度和运动数据的协作。不把空间算法可用等同于 TAA 可用，不改写光影内部历史。SSAA 与 TAA 的组合或时域升采样属于另行确认的范围。

### 10.3 验收重点

- 固定镜头下检查斜边、远处细线和纹理的收敛与闪烁；运动镜头下检查拖影、遮挡揭露与历史残留。
- 检查动态实体、透明内容、粒子、手持物品、受伤 / 屏幕效果与稳定的 HUD / GUI。
- 切换采样序列、样本数、模式、尺寸、相机、世界和渲染管线，确认历史正确重置且无跨场景污染。
- 记录各采样组合的帧时间、历史与临时目标分配；分别验证 Fabric、NeoForge 和声明支持的第三方管线组合。

## 11. 设计依据与后续更新

原版调用顺序依据本地 Minecraft 26.2 源码缓存的只读检查。Iris 预研参考其 26.2 分支的以下固定提交，第三阶段实施前需再次核对拟支持的实际版本：

- [Iris 公开 API](https://github.com/IrisShaders/Iris/blob/4ce5bb6ddd4b964b2b63442ebfca7e101ff128d0/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java)
- [Iris GameRenderer 注入](https://github.com/IrisShaders/Iris/blob/4ce5bb6ddd4b964b2b63442ebfca7e101ff128d0/common/src/main/java/net/irisshaders/iris/mixin/MixinGameRenderer.java)
- [Iris 最终 pass](https://github.com/IrisShaders/Iris/blob/4ce5bb6ddd4b964b2b63442ebfca7e101ff128d0/common/src/main/java/net/irisshaders/iris/pipeline/FinalPassRenderer.java)

阶段完成后，应在本文记录实际落地差异、验证结果与剩余事项。第一阶段验收不依赖第二阶段完成，后续算法也不能因存在接口预留而被标记为已实现。

## 12. 第一阶段落地与验证记录

### 12.1 已实现的接入点

| 阶段 / 事件 | 实际接入 | 输入寿命与边界 |
| --- | --- | --- |
| `FRAME_PREPARE` | `GameRenderer.extract()` HEAD 建立模式、提供者与支持判定快照；`extractCamera()` TAIL 在世界提取前提供投影修改入口 | 相机状态中的世界投影可供后续算法修改；当前 FXAA 不修改投影。原版剔除视锥及相机自身的屏幕投影保持原版行为，后续 TAA 应另行协调剔除裕量和屏幕投影语义 |
| 实际世界投影 | `renderLevel()` 向 `ProjectionMatrixBuffer.getBuffer(Matrix4f)` 提交矩阵时捕获 | 拷贝已应用受伤晃动、视角摇晃和恶心效果的实际矩阵，不复用之后的手持物品投影 |
| `WORLD_RESOLVE` | `LevelRenderer.addAlwaysOnTopPass()` HEAD 按需追加 framegraph pass | 主世界颜色和世界深度尚有效；always-on-top pass 和手持物品均可能清理主深度。FXAA 不请求该阶段，因此不添加 pass 或分配世界输入 |
| `FINAL_COLOR` | 外层 `GameRenderer.render()` 调用 `FogRenderer.endFrame()` 之前 | 已完成主世界、手持物品、屏幕效果、实体轮廓和原版实体后处理；GUI 尚未绘制。每帧每阶段最多调度一次，未渲染世界时跳过 |
| 重载 / resize / 世界退出 / 关闭 | `ShaderManager.apply()` TAIL、`GameRenderer.resize/resetData/close()` | 通过统一失效原因通知算法；窗口尺寸变化在下一帧准备时也检查，阶段目标格式和尺寸按阶段记录 |

`FrameContext` 是不可变数据快照，`CameraSnapshot` 拷贝矩阵并仅向调用者提供拷贝。环境提供者、请求模式和支持判定在帧边界固定；相机快照随提取和实际渲染阶段补充。输入能力检查覆盖准备和渲染入口，未来可接入独立环境提供者，不引入 Iris / Sodium 类型。

当前能力声明不代表已经提供历史、运动矢量或时域重投影。后续算法需自行实现所需输入，并在正确阶段声明能力；准备阶段尚未提取相机时不能假定已有相机矩阵。

### 12.2 FXAA、资源与失败处理

- GLSL 实现包含亮度对比早退、水平 / 垂直方向判定、强梯度侧选择、双向边缘端点搜索、端点符号校验与子像素偏移，保留中心像素 alpha。
- FXAA 默认参数为子像素强度 `0.75`、相对边缘阈值 `0.166`、最小亮度阈值 `0.0833`、搜索步数 `5`，现可在模组配置页面调整并保存。前三项范围为 `0–1`，搜索步数为 `1–5`；搜索距离序列保留 `1、1.5、2、4、12`，按所选步数使用其前缀。不额外做 gamma 编解码，只接受原版 RGBA8 显示颜色。
- 参数通过 `FxaaConfig` uniform block 传入着色器；有效世界帧读取参数快照，仅参数改变时重建所属处理链，保留临时目标和同一组管线描述，不刷新全局管线缓存。配置页可恢复默认值，关闭时通过原版 `options.txt` 保存；非法参数恢复对应默认值。
- `fxaa.json` 使用「主颜色 → 临时目标 → 主颜色」两个 pass；输入 FXAA 时线性采样，回写时不重复过滤。通过 `PostChain.addToFrame()` 和 `FrameGraphBuilder` 提交。
- 处理链、投影缓冲和 `CrossFrameResourcePool` 由 FXAA 所有；停用、换模式、尺寸 / 格式变化、重载、换世界 / 相机时释放所属资源。执行失败时回收本次已借出的目标并恢复全局投影绑定。
- 两个 FXAA 管线描述在同一资源版本内复用相同实例，避免原版按对象身份缓存管线导致反复切换后持续增长。`PostChainMixin` 只复用 `aamod:fxaa/` 位置；其它处理链返回原管线。编译程序由 Minecraft 设备缓存所有，在原版重载 / 设备关闭时销毁，本模组不清理全局缓存。
- 加载链之前先预编译各 pass，避免第二个着色器编译失败时遗留先前 pass 的缓冲；不调用会触发全局资源恢复的 `ShaderManager.getPostChain()`。
- 一次失败后当前模式进入 `FAILED`，保留请求模式，停止每帧重试与重复日志。切换模式或成功应用资源重载后允许重试。

设置走 `Options.processOptions()` 原版读写流程；加载期间抑制画质预设副作用，非法 / 缺失的模式值回到 OFF。主动修改模式仍将预设改为自定义，调整 FXAA 参数和恢复参数默认值不改变模式或预设。原有 Fast → OFF、Fancy → SMAA、Fabulous → TAA 映射保留，因此后两种预设目前不会自动运行 FXAA。

为让运行验证成立，另外修正了已有平台桥的静态 getter：共享与 Fabric `@ExpectPlatform` getter 增加 `@get:JvmStatic`，NeoForge 增加符合 Architectury 命名的静态转发对象，保留旧包。后续 Kotlin mixin 迁移沿用；本次修正了 Kotlin `@Coerce` 未被 Mixin 识别的问题，改用精确的 `Options.FieldAccess` 描述符，并修正静态回调声明。NeoForge 主源集显式注册到 Loom，避免开发运行漏扫 Kotlin 模组入口。

### 12.3 实际验证环境与结果

验证日期：2026-11-08。Minecraft 26.2、Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2、NeoForge 26.2.0.82；Windows，OpenGL 后端。没有安装 Iris / Sodium。运行检查通过临时独立验证程序与专用测试世界完成，未把验证入口加入发布模组。

| 检查 | Fabric | NeoForge |
| --- | --- | --- |
| 构建、共享 Java mixin 编译、资源打包 | 通过 | 通过 |
| 进入世界，OFF / FXAA 实际渲染和截图 | 通过 | 通过 |
| 方块斜边、栅栏、树叶、玻璃和手持物品 | 已检查截图 | 已检查截图 |
| 设置保存 / 重读，保留画质预设 | 通过 | 保存 / 重读通过 |
| 缺失、非整数及越界配置回到 OFF | 通过 | 未单独重复，共享代码 |
| SMAA / TAA 明确未实现 | 通过 | 未单独重复，共享代码 |
| 连续 8 轮启停 FXAA | 通过，管线缓存保持 2 | 通过，管线缓存保持 2 |
| 960×540 ↔ 800×600，资源重载后恢复 | 通过 | resize 与重载通过 |
| 相机切换，苦力怕视角原版后处理 | 通过 | 苦力怕后处理与 FXAA 同时执行 |
| 视频设置界面，退出世界暂停 | 通过 | 通过 |
| HUD 心形图标实际像素对照 | 通过，图标着色像素未变化；透明背景不纳入图标对照 | 未单独重复 |
| 同一保存世界退出后重进 | 通过 | 未单独重复 |
| 全屏往返、主世界 ↔ 下界、水下与恶心屏幕效果 | 通过运行与状态检查，水下 / 恶心留有截图 | 未单独重复 |
| 损坏 FXAA 着色器时隔离失败，移除后重载恢复 | 通过，单次失败日志，未触发全局资源包回退 | 未单独重复 |
| 启用 / 停用后实际临时目标数量 | 1 / 0 | 未单独记录 |

Fabric 测试在固定机位、960×540 下对照 OFF / FXAA，并检查平均亮度差小于 `3/255`；截图可观察到玻璃、方块边缘和手持物品轮廓平滑。像素差异受动态内容影响，不能当作画质提升百分比。菜单清晰度已检查截图；文字不经过 FXAA 的依据同时来自真实调用点顺序。

资源检查读到了实际池内目标：960×540、`RGBA8_UNORM` 颜色及 `D32_FLOAT` 深度，共 4,147,200 字节附件数据（约 3.96 MiB，未计驱动对齐、两个程序和小型 uniform / 投影缓冲）。原版临时目标附带深度，不能只计算一张颜色纹理。停用和着色器加载失败后池内目标为 0。

最后一次受 VSync / 120 FPS 上限影响的采样，FXAA / OFF 各 240 帧平均约 8.429 / 8.440 ms；它只能说明该场景下仍受帧率限制，不能据此宣称 FXAA 无性能开销。GPU 独立耗时和更高分辨率性能基准尚未建立。

本地证据位于忽略的 `../../build/phase1-validation`：`fabric-validation-console.log`、`neoforge-validation-console.log`、各独立运行目录内的 `logs/latest.log` 与 `screenshots/`。这些运行证据不是发布资源。

### 12.4 已知限制与后续验收

- JDK 27 的 `--version` 成功不等于可以构建；实际脚本配置失败，当前使用进程级 JDK 25，未修改默认 Java。
- 客户端行为断言完成后，开发运行环境出现 shutdown watchdog；Fabric 最终进程非零退出，NeoForge 关闭模块后 watchdog 找不到已关闭的 Minecraft 类。另一次明确禁用 `aamod` 的 Fabric 基线也复现 `Client shutdown from post-main`，因此不能归因于 FXAA。此项作为开发运行环境问题保留，不把 `runClient` 整体报告为成功退出。
- Vulkan、其它设备 / 驱动和长期显存压力测试未执行；跨加载器共享代码不能代替所有组合的运行验证。
- 聊天 / 背包的逐像素对照、实体轮廓、受伤及其它覆盖效果还需扩大人工画质检查。未覆盖项不视为通过。
- 后续按第二阶段 SSAA / SMAA、第三阶段 Iris / Sodium、第四阶段 TAA 推进。Iris / Sodium 的检测、策略、输出顺序、可选依赖、兼容设置和组合验收全部留在第三阶段；第一阶段没有增加相应依赖。

### 12.5 FXAA 参数配置追加验收

两端共用 `AntiAliasingConfigScreen`，提供模式选择、子像素混合强度、相对边缘阈值、最小亮度阈值、边缘搜索步数及恢复默认按钮。中英文标签和悬停说明同步提供，沿用原版滚动列表、键鼠操作与关闭保存流程。Mod Menu 是 Fabric 配置入口的可选依赖；NeoForge 使用内置 `IConfigScreenFactory`。

验证日期同为 2026-11-08，JDK 25，OpenGL，未安装 Iris / Sodium；使用临时验证模组与独立测试世界。

| 检查 | Fabric | NeoForge |
| --- | --- | --- |
| 加载器实际注册的配置工厂打开共享页面 | Mod Menu 工厂通过 | 内置工厂通过 |
| 四项参数保存 / 重读，完成按钮关闭 | 通过 | 通过 |
| 真实鼠标点击浮点滑块两端，关闭后重读 | 通过 | 使用相同原版控件，未单独重复 |
| 缺失参数、NaN、Infinity、负值、越界值及损坏文本 | 对应默认值恢复通过 | 未单独重复，共享代码 |
| 恢复默认值，保留已选 FXAA 模式 | 通过实际按钮验证 | 通过实际按钮回调验证 |
| 修改参数后仍正常渲染，零阈值无除零失败 | 通过，留有画面对照截图 | 通过，留有截图 |
| 连续十次参数修改后管线缓存数量 | 保持 2 | 保持 2 |
| Kotlin 静态视频设置混入可加载 | 通过 | 通过 |

画面对照仅用于确认参数实际影响渲染，动态内容造成的像素差异不能当作画质改善比例。所有行为断言完成；客户端退出仍复现第 12.4 节记录的开发环境 shutdown watchdog，不能将 `runClient` 报告为正常退出。

本地证据位于忽略的 `../../build/fxaa-config-validation`：`fabric-console-input-final.log`、`neoforge-console-fixed.log`、两个独立运行目录的 `screenshots/` 与临时验证程序。验证代码和测试世界不打入发布 jar。

## 13. 第二阶段实现与验收记录

### 13.1 实现与配置

第二阶段实现原版 SSAA 与 SMAA 1x，沿用第一阶段 FXAA、公共控制器及双加载器配置入口。模式编号为 `OFF=0 / FXAA=1 / SMAA=2 / TAA=3 / SSAA=4`；UI 顺序为关闭、FXAA、SSAA、SMAA、TAA。TAA 保持尚未实现；没有接入 Iris / Sodium。

| 参数 | 保存键 | 编码 | 默认值 |
| --- | --- | --- | --- |
| SSAA 世界分辨率倍率 | `aamod_ssaa_scale` | 1 / 2 / 3 / 4 分别表示每轴 1.5× / 2× / 3× / 4× | 2，即 2× |
| SMAA 1x 质量预设 | `aamod_smaa_quality` | 0 / 1 / 2 / 3 分别表示低 / 中 / 高 / 极高 | 2，即高 |

参数使用共享模组配置页的原版循环控件，提供中英文说明，随原版 Options 保存、重读；缺失、损坏文本、非整数和越界值恢复对应默认值。更改在下一有效世界帧应用。现有 FXAA 默认值按钮只重置 FXAA 四项参数。加载设置保留画质预设，手动切换算法沿用原有自定义预设行为。

### 13.2 渲染与扩展边界

`RenderResolution` 保存输出尺寸、请求倍率和实际世界尺寸；世界尺寸逐轴向上取整，并在分配前检查设备最大纹理尺寸。`RenderEnvironmentProvider.planResolution` 与 `RESOLUTION_CONTROL` 能力独立于最终颜色输入，供第三阶段按管线判断 SSAA 支持。缺少准备能力时保留请求并报告 `MISSING_INPUT`，不会启动世界目标交换。

SSAA 在提取前准备颜色、深度与实体轮廓目标，在实际世界绘制前交换附件。原版天空渲染器长期持有主 RenderTarget 引用，因此实现保持主目标对象身份稳定，只交换其颜色 / 深度附件、视图和实际尺寸。原版窗口目标重建后再次核对方案，避免 resize 清理了准备阶段资源后仍使用旧目标。天空、透明与粒子目标、手持物品及原版后处理由相同高分辨率主目标驱动。

最终颜色阶段使用最多 5×5 个源 texel 的面积覆盖积分，按实际输入 / 输出尺寸计算每个像素的归一化权重，支持 1.5× 和非整数窗口尺寸。SSAA 在 GUI 前降采样，恢复附件、WindowRenderState 尺寸和全局屏幕 uniform；正常最终处理路径使用 `finally` 恢复目标，render 返回另有恢复入口。截图保持输出尺寸。

SMAA 使用官方颜色边缘检测、Area / Search 权重计算与邻域混合，最后从独立输出写回主颜色，保留中心 alpha。质量预设直接使用官方宏，High / Ultra 包含斜线与角点处理；不分配历史、运动矢量或时域 jitter。边缘与权重目标每次使用前清零。四个固定预设各有独立管线位置，避免按参数反复产生新管线身份。

共享 `SpatialPostChain` 负责配置解析、预编译、帧图、投影状态恢复和异常时归还临时资源。算法分别管理所属目标与处理链，模式、尺寸、世界、相机、资源及管线变化沿公共失效入口处理。AA 管线缓存按位置复用，四档 SMAA 共 16 条、SSAA 1 条、FXAA 2 条，上限合计 19 条；重载 / 关闭清理缓存。

### 13.3 SMAA 来源与许可

着色器与查找纹理来自官方 `iryoku/smaa` 同一固定版本。完整许可、提交标识、原始 SHA-256、DDS 到 PNG 的逐通道转换方式及官方预设值保存在随 jar 打包的 `assets/aamod/licenses/SMAA-SOURCES.md` 和 `SMAA-LICENSE.txt`。源着色器保留上游声明。

Area 160×560 的原始双字节值映射到 PNG R/G；Search 64×16 的原始单字节映射到 R，行序保持原样。已逐像素对照原始 DDS，所有像素一致。运行使用线性、clamp、无 sRGB 转换的查找采样。

临时合成图测试使用发布资源中的真实 pass 与着色器，将中间目标导出后检查：128×128 输入生成 320 个非零边缘像素、170 个非零权重像素，邻域输出产生 711 个非 0/255 的颜色通道值。两加载器结果一致，确认实际计算权重并混合；这些计数用于完整性检查，不表示画质提升百分比。

### 13.4 原版客户端验收

验证于 2026-11-08，Minecraft 26.2、进程级 JDK 25、OpenGL，未安装 Iris / Sodium。使用临时验证模组与独立世界，代码及世界均位于忽略的 `../../build/phase2-validation`，不进入发布 jar。

| 检查 | Fabric | NeoForge |
| --- | --- | --- |
| 四档 SSAA 实际颜色 / 深度尺寸 | 通过：1200×900、1600×1200、2400×1800、3200×2400 | 通过，相同输出与尺寸 |
| 四档 SMAA 预设实际渲染 | 通过，保存对照截图 | 通过，保存对照截图 |
| 真实 SMAA 中间边缘、权重与混合输出 | 通过，独立合成图 | 通过，独立合成图 |
| 参数保存 / 重读，保留预设 | 通过 | 通过，使用内置配置工厂 |
| 缺失、损坏文本、非整数、负值及越界参数 | 默认值恢复通过 | 未单独重复，共享持久化实现 |
| 算法与参数反复切换，OFF 释放所属目标 | 通过，五轮算法循环与资源检查 | 通过，四轮算法循环与资源检查 |
| 800×600 ↔ 801×603，资源重载与全屏往返 | 通过 | 通过 |
| 极佳画质透明目标、实体轮廓附件尺寸 | 通过，逐档核对 | 实体轮廓尺寸逐档检查；透明池另见 Fabric 补测 |
| 801×603、1.5× 向上取整 | 通过：实际世界与轮廓均为 1202×905 | resize 通过，未单独重复 1.5× 尺寸断言 |
| 方块、栅栏、树叶、玻璃、粒子、手持物品 | 运行及截图检查通过 | 方块、树叶、手持物品截图检查；其它内容未逐项重复 |
| HUD 心形图标像素对照 | SSAA 2× / 4×、SMAA High 的 1320 个着色像素均未变化 | 菜单 / 配置字体截图检查 |
| 原版苦力怕视角后处理 | SSAA / SMAA / FXAA 通过 | SSAA / SMAA 通过 |
| 水下与恶心屏幕效果 | SSAA / SMAA 运行通过，保留截图 | 未单独重复 |
| 主世界 ↔ 下界、同一世界退出重进 | 通过 | 通过 |
| 损坏 SSAA / SMAA 着色器后隔离失败、清理与重载恢复 | 通过，两算法分别验证 | 通过，两算法分别验证 |
| 缺少分辨率能力、设备尺寸限制、提供者抛出分配异常 | 通过，使用临时提供者注入 | 未重复故障注入 |

故障测试保留用户请求，主目标恢复原生尺寸，不逐帧重试，不触发全局资源包回退。设备限制测试将提供者上限设为 64，拒绝 1600×1200 请求；分配异常测试在规划阶段注入异常，用于验证公共失败路径，没有实际耗尽设备显存。

### 13.5 资源与帧时间

800×600 输出下，实际 SSAA 世界颜色 `RGBA8_UNORM` 与深度 `D32_FLOAT` 合计每像素 8 字节。实体轮廓目标同样包含颜色与深度，极佳画质的原版临时池在本次场景稳定保留六个同尺寸目标：

| 每轴倍率 | 世界尺寸 | SSAA 世界附件 | 轮廓附件 | 极佳画质原版池附件 |
| --- | --- | --- | --- | --- |
| 1.5× | 1200×900 | 8,640,000 B | 8,640,000 B | 51,840,000 B |
| 2× | 1600×1200 | 15,360,000 B | 15,360,000 B | 92,160,000 B |
| 3× | 2400×1800 | 34,560,000 B | 34,560,000 B | 207,360,000 B |
| 4× | 3200×2400 | 61,440,000 B | 61,440,000 B | 368,640,000 B |

这些是实际附件大小，不能把“世界附件”单列当作总显存。原生输出颜色与深度另需 3,840,000 B；轮廓与原版池包含原版已有成本，表格也不是全部新增成本。驱动对齐、其它世界资源、缓冲、云及其它条件目标未计入。极佳画质从 SSAA 切回 SMAA 后，原版池恢复六个 800×600 目标，合计 23,040,000 B。

SMAA 声明三个内部语义目标；帧图复用生命周期不重叠的边缘 / 输出物理目标，实际稳定保留两个 800×600 颜色加深度目标，合计 7,680,000 B，四个预设一致。Area / Search 的 RGBA8 查找纹理另计 362,496 B，由 Minecraft 纹理管理器缓存；OFF 清理算法处理链和临时目标，不强制删除纹理管理器拥有的共享资产。SSAA 降采样无内部临时目标。反复启停后没有出现所属目标或管线持续增长。

Fabric 固定机位每档记录 240 帧：OFF 平均 8.643 ms；SSAA 1.5× / 2× / 3× / 4× 分别 8.500 / 8.415 / 9.185 / 8.417 ms；SMAA 低 / 中 / 高 / 极高分别 8.423 / 8.434 / 8.413 / 8.432 ms。这些采样受 VSync / 帧率上限和场景负载影响，只用于确认运行并记录帧时间，不能据此宣称高倍率更快、算法没有开销或给出 GPU 性能排名。NeoForge 也逐档采样，具体原始值保留在验证日志。

### 13.6 证据与限制

- 最终 `:fabric:build :neoforge:build --offline --console=plain` 通过；两个发布 jar 均包含空间算法类、查找纹理、配置资源和 SMAA 许可，未包含临时验证代码。共享 JSON 解析与中英文 36 项翻译键同步检查通过。
- Fabric 主验收：`../../build/phase2-validation/full-fabric.log`，标记 `PHASE_TWO_FABRIC_FULL_VALIDATION_PASSED`。
- 极佳画质附件补测：`../../build/phase2-validation/attachment-audit.log`，标记 `PHASE_TWO_FABRIC_ATTACHMENT_AUDIT_PASSED`。
- NeoForge 主验收及画面补测：`../../build/phase2-validation/neoforge-final.log`，标记 `PHASE_TWO_NEOFORGE_SPATIAL_VALIDATION_PASSED`。
- 两个独立运行目录的 `screenshots/` 保存世界、菜单、特效及合成图中间输出；测试程序不打包。
- 第 12.4 节记录的开发环境 shutdown watchdog 仍存在。行为断言与构建结果分别记录，不将出现 watchdog 的 `runClient` 整体报告为正常退出；NeoForge 测试进程可能残留，需按明确的本项目验证命令行清理。
- Vulkan、其它设备 / 驱动、GPU 独立计时、实际显存耗尽、长期压力，以及聊天 / 背包 / 受伤画面的扩大人工检查仍未覆盖。第三阶段重新确认 Iris / Sodium 的输入、顺序与各算法支持；第四阶段实现 TAA。

## 14. 第三阶段部分落地：可选依赖与视频设置入口

### 14.1 本次范围与版本

本节记录前次接入 Minecraft 26.2 的 Sodium 0.9.2 与 Iris 1.11.4，覆盖 Fabric 与 NeoForge。当时只完成可选依赖声明、开发运行开关及视频设置入口；后续完成的渲染适配与支持边界见第 15 节。

版本集中在 `../../gradle.properties`：`sodium_version=0.9.2`、`iris_version=1.11.4`。开发开关 `enable_sodium` 与 `enable_iris` 默认均为 `false`；按次指定 `-Penable_sodium=true` 加入 Sodium，指定 `-Penable_iris=true` 同时加入 Iris 和其必需的 Sodium，两端行为一致。未启用时保留原版开发运行环境。

- Sodium API：`net.caffeinemc:sodium-fabric-api:0.9.2+mc26.2`，仅编译期引用其与加载器无关的公开配置接口。
- Sodium 运行时：`net.caffeinemc:sodium-fabric:0.9.2+mc26.2` / `sodium-neoforge:0.9.2+mc26.2`，来自 CaffeineMC 官方 Maven。
- Iris 运行时：`maven.modrinth:iris:1.11.4+26.2-fabric` / `1.11.4+26.2-neoforge`，来自 Modrinth Maven。Maven 版本与下载文件名的 `+mc26.2` 写法不同。
- 根工程用限定模块范围的独占 Maven 仓库，避免这些坐标先访问不相关仓库。可选模组使用 `localRuntime`，不进入发布 jar。
- Fabric 用 `suggests` 声明可选模组；NeoForge 用客户端侧 `type="optional"` 依赖及 `AFTER` 排序。未安装二者时不强制加载对应入口类。

### 14.2 视频设置适配

新增 `client/compat/sodium/SodiumConfigEntryPoint.kt`，实现 Sodium 公开 `ConfigEntryPoint`。通过双端元数据的 `sodium:config_api_user` 注册，在 `registerConfigLate` 中给本模组分组添加外部页面：

「选项 → 视频设置 → Anti Aliasing Mod → 抗锯齿」→ 现有 `AntiAliasingConfigScreen(parent)`。

本模组侧栏分组通过公开 `ModOptionsBuilder.setNonTintedIcon` 注册 `aamod:icon.png`，直接复用 `assets/aamod/icon.png`，保留图标原色；由 Sodium 负责布局和缩放，两端使用相同注册入口。

共享页面继续提供模式、FXAA 四项参数、SSAA 倍率和 SMAA 质量；关闭时保存原版选项并返回传入的 Sodium 视频设置页面。不复制参数状态，不注入 Sodium 内部 GUI，不修改 Iris 的光影入口。未安装 Sodium 时保留原版 `qualityOptions` 中的抗锯齿选项；Fabric Mod Menu 与 NeoForge 内置配置入口继续可用，Sodium 入口本身无需 Mod Menu。

API 依据为 Sodium `mc26.2-0.9.2`，提交 `6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a` 的 [Config API 用法](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/api/java/net/caffeinemc/mods/sodium/api/config/USAGE.md)。生产代码只使用公开 API；临时验证程序通过反射检查页面注册与调用结果，不随发布模组打包。

### 14.3 客户端验收

验证日期：2026-11-08。Minecraft 26.2，Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2，NeoForge 26.2.0.82，Windows OpenGL。仍使用进程级 JDK 25，系统默认 JDK 27 未修改。Iris 测试未启用光影包。

| 安装组合 | Fabric | NeoForge |
| --- | --- | --- |
| 无 Sodium / Iris | 原版抗锯齿选项存在；Mod Menu 配置入口、保存与返回通过 | 原版抗锯齿选项存在；内置配置入口、保存与返回通过 |
| 仅 Sodium | 真实鼠标点击外部页面、共享设置、保存返回、反复打开无重复通过 | 页面注册与回调、共享设置、保存返回、再次打开无重复通过 |
| Sodium + Iris | 上述检查通过；Iris Shader Packs 页面保留 | 上述检查通过；Iris Shader Packs 页面保留 |

测试将模式设为 FXAA、子像素混合设为 0.321、搜索步数设为 3，关闭后重新加载 `options.txt` 并断言值保留。Sodium 外部页面在每次返回与再次打开后均恰好注册一次。960×640、GUI 倍率 2 的截图检查覆盖双端 Sodium 页面、Iris 同装页面、共享参数页及返回结果。

证据保存在忽略的 `../../build/compat-ui-validation`：

- `fabric-vanilla.log`、`fabric-sodium.log`、`fabric-iris.log`：`FABRIC_OPTIONAL_UI_PASSED combination=vanilla|sodium|iris`。
- `neo-vanilla.log`、`neo-sodium.log`、`neo-iris.log`：`NEOFORGE_OPTIONAL_UI_PASSED combination=vanilla|sodium|iris`。
- `fabric/screenshots/` 与 `neoforge/screenshots/`：各组合的入口、配置与返回画面。
- `build.log`、`final-build.log`：默认双端构建通过；`optional-dependencies.log`：双端可选运行依赖解析通过。最终发布 jar 的入口类与已展开元数据检查通过，未内置 Sodium / Iris 类或验证程序。

本次仍出现第 12.4、13.6 节的开发环境退出问题：Fabric 功能断言后触发客户端关闭 watchdog，NeoForge 在模块关闭后出现 `ServerWatchdog` 类加载异常并可能残留进程。仅按本次验证专用参数和项目路径清理对应进程。功能断言通过与 `runClient` 正常退出分开报告。

本次未进入测试世界，也未验证启用光影时的图像或算法效果；尚无新的世界渲染兼容结论。后续继续按第 9 节完成光影状态、策略、目标顺序与 FXAA / SSAA / SMAA 分别验收，第四阶段再实现 TAA。

## 15. 第三阶段 Iris 渲染适配与验收

### 15.1 接入与生命周期

新增隔离的 `client/compat/iris/` 适配器，平台桥接只返回模组版本字符串。Iris 缺失时不加载带 Iris 类型的 `IrisApiAccess`，控制器及算法接口不引用 Iris 类型。仅启用已核实的 Iris 1.11.4 版本适配；其它版本在运行时显示暂停状态。NeoForge 的 Iris 可选依赖范围放宽至 `[0,)`，使不支持的已安装版本能到达运行时支持判定，而非阻止基础模组加载；Sodium 保留 0.9.2 最低依赖范围。

光影使用与阴影状态通过 Iris 公开 API 查询。适配器中的版本特定查询只用于确认 sRGB 输出，以及区分“已加载且启用光影包、尚无实际世界管线”的准备状态；不读取 `colortex`、光影内部历史或光影包自身 AA 配置。查询异常或链接错误记录日志并暂停，之后成功查询可恢复，不改写请求模式。

每帧固定环境、支持判定与策略；管线构建 / 销毁的可选 `@Pseudo` mixin 只递增管线修订号，下一帧使本模组资源失效。Iris `finalizeGameRendering` 返回时标记本帧有效主目标及颜色纹理；本模组在之后的既有 `GameRenderer.render` 最终颜色位置处理，验证帧号、管线修订及颜色纹理身份一致后再运行。该顺序依赖实际调用边界，不依赖 mixin 优先级。

Iris 无最终输出、发生帧内管线变化、阴影视图或不支持的颜色输出时，拒绝把该输入交给算法。包已加载但管线未创建时暂停所有算法准备，尤其防止 SSAA 在光影首帧创建前交换主附件。光影启停、切包、资源重载及 resize 沿用控制器失效路径；管线变化也清除原管线的算法失败锁，允许新管线重试。

源码核对使用 Iris 26.2 固定提交 `4ce5bb6ddd4b964b2b63442ebfca7e101ff128d0` 的 `MixinGameRenderer`、`IrisRenderingPipeline` 与 `FinalPassRenderer`，并与官方 1.11.4 发布 jar 的方法签名和字节码交叉核对。最终颜色写回主目标，颜色转换在 `renderLevel` 返回前完成，本模组随后运行并在 HUD 前结束。

### 15.2 支持矩阵与设置

| 环境 | FXAA | SMAA 1x | SSAA |
| --- | --- | --- | --- |
| 原版或仅 Sodium 0.9.2 | 支持 | 四档预设支持 | 四档倍率支持 |
| Iris 1.11.4 安装，未使用光影 | 支持 | 四档预设可用 | 四档倍率支持 |
| 光影实际使用中，自动策略 | 暂停 | 暂停 | 暂停 |
| 光影实际使用中，光影后叠加 | 支持 sRGB / RGBA8 最终输出 | 支持 sRGB / RGBA8 最终输出 | 明确暂停：未适配光影分辨率控制 |
| 非 sRGB、未知格式、输出未就绪或 API 失败 | 明确暂停 | 明确暂停 | 明确暂停 |
| 不支持的 Iris 版本 | 显示版本暂停原因 | 显示版本暂停原因 | 显示版本暂停原因 |

SSAA 的限制来自附件所有权与分辨率生命周期：现有原版路径交换主目标附件，但 Iris 维护自身目标、深度和 framebuffer 版本；本阶段不把原版交换方式直接套用于光影管线。不支持时保留 SSAA 请求，关闭光影后自动恢复。TAA 未实现，不因兼容适配而用其它算法替代。

共享配置页新增「光影包抗锯齿策略」，默认自动，另可选光影后叠加。配置页的状态文字随策略变化更新，模式提示包含具体暂停原因。策略保存为 `aamod_iris_policy`，0 / 1 分别为自动 / 光影后叠加，缺失、非法文本或越界值恢复自动。光影自身 AA 不被本模组猜测或修改，用户可显式控制叠加。

### 15.3 验证环境与方法

验证日期 2026-11-08，Minecraft 26.2，Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2，NeoForge 26.2.0.82，Sodium 0.9.2，Iris 1.11.4，Windows OpenGL，进程级 JDK 25；系统默认 JDK 27 未修改。

真实光影包为 Modrinth 官方发布的 **Complementary Reimagined r5.9.3**，版本 ID `Bqen1mJX`，下载后核验官方 SHA512。测试世界包含黑白斜边、树叶、玻璃、栅栏与手持钻石剑。临时验证代码、世界、光影包和截图均位于忽略的 `../../build/iris-validation`，不进入发布 jar。

临时 mixin 在同一帧最终颜色处理前后分别下载 GPU 图像，检查实际像素差和 RGB 总和，避免把状态标记当作图像效果证据。自动策略要求像素完全不变，叠加要求非零过滤结果，并限制整体亮度偏移；另外保存含 HUD 的画面检查菜单、手持物品及透明内容。SMAA 四档分别运行，SSAA 无光影时核对世界附件尺寸 1200×900 / 1600×1200 / 2400×1800 / 3200×2400，输出保持 800×600。

### 15.4 验收证据

- `fabric-vanilla.log`、`fabric-sodium.log`、`neo-vanilla.log`、`neo-sodium.log`：无 Iris 的两种安装组合分别验证 FXAA / SMAA / SSAA、四档 SSAA 倍率及原生输出尺寸。
- `fabric-sodium-effects.log`、`neo-sodium-effects.log`：仅 Sodium 时补测 FXAA / SMAA 同帧前后像素变化，覆盖没有 Iris 后处理的实际图像路径。
- `fabric-complete.log`、`neo-complete.log`：完整光影矩阵；包括自动暂停、FXAA / SMAA 实际叠加、SSAA 明确暂停、四档 SMAA、光影自身 FXAA / TAA 开关、非 sRGB 拒绝、真实阴影回调、切包、资源重载、resize、光影启停与重新进世界。
- `IRIS_COLOR_TAP` 记录每次处理前后改变的像素数和 RGB 总和。自动策略为 0 像素差；FXAA / SMAA 为非零过滤，整体 RGB 总和变化远小于 5% 验收上限。可检查各运行目录 `screenshots/` 中的 `*-before.png`、`*-after.png` 及 HUD / 配置页截图。
- `IRIS_INVALID_OUTPUT_REJECTED`：通过临时故障注入，验证缺失本帧最终输出及帧内管线变化均被拒绝；`query_failed` 后恢复真实 API，再次返回 ACTIVE。预期 API 故障仅在临时验证程序中注入。
- `IRIS_POLICY_PERSISTENCE_AND_DEFAULTS_PASSED`：保存重载及缺失 / 越界 / 非法文本回退；`IRIS_POLICY_UI_DYNAMIC_STATUS_PASSED`：通过实际策略按钮回调切换后，状态文字随之更新。
- `IRIS_INITIAL_SHADER_PIPELINE_AND_REJOIN_PASSED`：加载光影包但销毁世界管线，确认先进入准备暂停状态，再以 SSAA 请求重新进世界；无错误世界附件分配，随后切回 FXAA 可运行。
- `IRIS_OWNED_PIPELINE_COUNT` 与关闭状态资源检查：模式关闭后算法链与 SSAA 附件释放，反复重载和切换后本模组 GPU 管线数量保持有界。
- `fabric-version.log`、`neo-version.log`：用官方 jar 的隔离副本只把版本元数据改为 1.11.3，模拟不支持版本；两端基础模组正常加载且返回 `aamod.compat.iris.version`。该测试不宣称真实 Iris 1.11.3 已兼容，原始依赖 jar 未修改。

### 15.5 开发环境限制

NeoForge 开发条件下，Iris 的顶点格式数组替换返回单元素数组，原版开发绘制校验按 16 个槽位读取，在手持物品渲染中触发 `ArrayIndexOutOfBoundsException`。`neo-upstream-baseline.log` 明确记录 `IRIS_BASELINE_AAMOD_LOADED=false`，移除本模组后仍复现同一调用栈。为验证实际生产路径，NeoForge 临时验证模组在图形初始化前设置 `SharedConstants.IS_RUNNING_IN_IDE=false`；发布代码不修改该标记，也没有把上游校验绕过作为模组功能。

仍存在前述开发环境退出 watchdog 问题，功能断言与 `runClient` 正常退出分开记录；仅根据项目路径和专用验证参数清理残留进程。

本阶段的支持结论限定于上述版本、OpenGL、sRGB / RGBA8 输出和实际测试条件，不推广到任意光影包、HDR、其它 Iris 版本或 Vulkan。光影开启时的 SSAA、第四阶段 TAA、任意光影内部时序数据协作均未宣称支持。

### 15.6 最终交付检查

`JDK 25 + ./gradlew.bat :fabric:build :neoforge:build --offline --console=plain` 最终通过，日志为 `../../build/iris-validation/final-build.log`。发布产物仍为 `fabric/build/libs/aamod-fabric-0.1.0-alpha.jar` 与 `neoforge/build/libs/aamod-neoforge-0.1.0-alpha.jar`。

两个 jar 均包含 Iris 适配器、可选管线 hook、策略配置及 Sodium 配置入口，未内置 `net/irisshaders/`、`net/caffeinemc/` 或临时验证类。元数据无未展开占位符；发布字节码中的 `PlatformHelper.getModVersion` 分别转调 Fabric / NeoForge 平台实现。共享配置及 mixin JSON 检查通过，中英文 53 项翻译键一致，`git diff --check` 通过。

| 第 9 节验收项 | 当前证据 |
| --- | --- |
| 可选 API 隔离、缺失 / 不支持版本、查询失败 | 双端无 Iris 运行、版本元数据隔离副本、真实 API 故障注入与恢复 |
| 自动 / 叠加策略、最终输出边界、阴影跳过 | 双端真实光影、同帧 GPU 像素取样、真实阴影回调、无效输出与修订号检查 |
| 设置入口、策略及状态 | 已有 Sodium / Mod Menu / NeoForge 入口验证，加上双端策略按钮、动态文字、持久化与非法值回退 |
| 各算法与支持范围 | FXAA / SMAA 实际图像、四档 SMAA、无光影 SSAA 四档附件、光影 SSAA 暂停与关闭光影后恢复 |
| 光影自身 AA、切包 / 重载 / resize、首帧及重进世界 | 双端完整矩阵与首次管线 / 重进世界标记，关闭后的资源释放与有界管线数量 |

在上述已声明范围内完成第三阶段；保留第 15.5 节环境限制与未支持组合，后续第四阶段单独实现和验证 TAA。

### 15.7 Vulkan 下 Iris API 不可用的误暂停修复

用户已在原版 Vulkan 和 Sodium + Vulkan 环境确认抗锯齿可运行，并报告：安装 Iris 后，切换到 Vulkan 时 Iris 不暴露可用 API，本模组错误进入兼容暂停。旧逻辑仅凭模组已安装便构造 `IrisApiAccess`，其字段直接调用 `IrisApi.getInstance()`；初始化或后续查询异常被统一转换为 `aamod.compat.iris.query_failed`，随后阻止所有 AA 模式。旧初始化还会锁定该状态，没有区分后端导致的 Iris 未启用与实际光影查询故障。

修复后在构造可选适配器和校验 Iris 版本之前，从 `RenderSystem.getDevice().deviceInfo.backendName()` 读取当帧实际设备后端。原版返回 `Vulkan` 时只保留 Iris 安装状态用于配置页，状态显示「Iris：在 Vulkan 后端下未启用，使用原版渲染路径」，直接返回 `VanillaRenderEnvironment`。不调用 Iris API、不按 Iris 版本暂停，也不要求 Iris 最终颜色回调。实际后端改变时清除旧适配器和错误状态，重新选择路径；不把旧查询失败带入原版 Vulkan。

OpenGL 仍保留已验证版本适配、光影准备暂停、自动／叠加策略、SSAA／TAA 光影限制、阴影跳过、颜色／输出检查及同帧管线变化拒绝。不能把任意 API 异常都当作“光影关闭”，以免在真实光影管线仍运行时错误启用分辨率或投影控制。

独立 JVM 回归入口为 `%TEMP%/aamod-iris-backend-check/check.init.gradle` 的 `:common:verifyIrisBackendCompatibility`，使用进程内 JDK 25；`check.log` 为 `BUILD SUCCESSFUL`。检查执行实际编译的兼容类，以受控设备信息和模组元数据作为输入：无 Iris API 类的运行时通过 63 项断言，确认缺失 API、不同 Iris 版本和旧查询错误均不阻止 Vulkan；受控适配器通过另 65 项断言，覆盖不触碰 Vulkan 可选适配器、OpenGL 光影策略、准备／阴影／颜色、异常隔离与同帧管线变化。四种 AA 的支持、准备输入、SSAA 方案和捕获入口均检查到；该回归不创建 GPU 或游戏客户端，不冒充本轮重新执行了 Vulkan 画质检查，实际 Vulkan 运行结论来自用户验证。

使用 JDK 25 离线执行 `:fabric:build :neoforge:build`，双端均为 `BUILD SUCCESSFUL`。构建通过临时 init 脚本重定向归档输出，避免覆盖运行中客户端可能持有的 common 开发 jar；修复后的 `0.8.0-alpha+mc26.2` 正式 jar 已复制到各端 `build/libs/`。发布包核对通过：三项 Iris 兼容类与对应加载器生产转换结果一致，中英文各 70 个键同步，新增 Vulkan 状态、版本元数据与图标资源完整，未打入回归夹具或 Iris／Sodium 依赖；复制前后 SHA-256 一致。构建和回归日志均保留在 `%TEMP%/aamod-iris-backend-check/`。

## 16. 第四阶段 TAA 落地与验收

### 16.1 实际数据流和算法边界

`TaaMethod` 注册为模式 3，使用原有 `FRAME_PREPARE → WORLD_RESOLVE` 调度。准备阶段读取序列 / 周期，统一重置历史；相机提取时生成一次 jitter，实际世界投影提交时注入 `2*jitter/(width,height)` 的 clip translation，并记录实际投影。传送导致提取与绘制之间失效时允许补做一次准备，避免错误使用旧矩阵。

世界剔除投影保持稳定，仅扩大一像素边缘裕量；不抖动 GUI、手持物品、剔除矩阵或相机自身的屏幕投影。解析通过 `LevelRenderer.addAlwaysOnTopPass` 之前的 framegraph pass 执行，此时主世界颜色和深度尚有效；always-on-top、手持物品及之后的原版后处理不进入 TAA 历史。

历史使用两个 RGBA16F + D32 目标，并保留上一帧原始 RGBA8 颜色。每帧只从旧历史读取、向另一目标写入，再复制当前原始深度和颜色、把解析结果写回主世界目标；没有同纹理读写反馈。解析／输出为两个原生 Blaze3D `RenderPipeline`，新增逐物体运动向量另使用两个固定的剔除／双面管线，不使用直接 OpenGL 调用。

相机重投影使用 `previousProjection * previousViewRotation * translate(currentPos-previousPos) * inverse(currentProjection * currentViewRotation)`；前后矩阵包含实际摇晃 / 屏幕投影效果及对应 jitter。解析在当前 / 历史纹理采样时校正 jitter，拒绝背向、越界和深度不一致的历史；3×3 YCoCg 范围及 1.25σ 约束限制历史，原始前后帧颜色变化产生 reactive rejection，运动进一步降低历史权重，最高权重为 0.94。天空深度为 0 时只使用当前帧。

静态场景使用相机重投影；追加的逐物体运动矢量覆盖实体／方块实体模型部件动画与原版烘焙物品／方块几何。已捕获但无法匹配、新出现、半透明及自定义几何拒绝历史；FRAPI／Indigo 和 Sodium 扩展物品／方块网格当前也只提供拒绝覆盖，Sodium 下的 ModelPart 动画仍有精确对应。粒子等未接入路径仍使用深度／颜色拒绝与邻域约束，不能保证完全无拖影。运动纹理在解析前完成可见性／透明裁剪检查，数据定义和验证见 `OBJECT_MOTION_VECTORS_PLAN_ZH.md`。手持物品独立绘制，不提供它们的时域抗锯齿。没有时域升采样、TAA + SSAA 或光影内部历史协作。

### 16.2 参数、重置和兼容策略

五种序列与 8 / 16 / 32 样本共 15 个合法组合，默认 Halton + 16；细节、原创点表及频谱 / 前缀检查见 `TEMPORAL_SAMPLING_PLAN_ZH.md` 第 11 节。

`TemporalSettings` 经原版 Options 持久化；原版视频设置画质分组与共享配置页均提供两个控件，配置页有独立自动隐藏的重置按钮。顶部模式没有重置按钮。无重置时参数自身居中，有按钮时整体居中。Fast → FXAA、Fancy → SMAA、Fabulous → TAA；手动切换模式回到 Custom。

模式、序列、样本数、世界 / 维度、相机实体 / 视角、camera cut、FOV / 基础投影、尺寸、目标格式、资源重载和外部管线变化使旧历史失效。相机位置跳跃超过 8 方块或视角矩阵突变时作为 cut 处理；正常移动保持重投影，帧中断后重新初始化。OFF、退出世界或销毁时关闭所有自有目标、uniform ring 和 query pool。

| 运行组合 | TAA 行为 |
| --- | --- |
| 无 Iris 的原版 / Sodium | 运行，15 个采样组合共用同一解析逻辑 |
| Iris 1.11.4 安装、光影关闭 | 运行，含 Sodium 渲染和设置入口 |
| Iris 光影开启、自动策略 | 暂停、释放历史，保留请求模式 3 |
| Iris 光影开启、光影后叠加 | TAA 仍暂停，原因 `aamod.compat.iris.taa`；不接管光影 jitter / 深度 / 历史 |
| 关闭光影 | 重新建立 epoch 和历史，恢复 TAA |

### 16.3 运行验证与复现位置

验证环境为 Minecraft 26.2、Fabric Loader 0.19.5 / NeoForge 26.2.0.82、Sodium 0.9.2、Iris 1.11.4、Complementary Reimagined r5.9.3、JDK 25、OpenGL，显卡 RTX 5070 Laptop GPU，驱动 621.01。默认 JDK 27 不变；命令进程选择 JDK 25。

临时验证程序、隔离存档、日志和截图位于 `%TEMP%/aamod-taa-validation`，不进入发布 jar 或项目正式源码。Fabric 使用 `fabric.init.gradle` 与客户端 GameTest；NeoForge 使用 `neo.init.gradle` 与临时事件驱动验证模组。运行命令为 `:fabric:runClient` / `:neoforge:runClient -I <对应脚本> --offline --console=plain`；加 `-Penable_sodium=true` 或 `-Penable_iris=true` 选择组合。各目录 `fabric`、`fabric-sodium`、`fabric-iris`、`neo`、`neo-iris` 保存对应日志 / 截图；NeoForge 临时程序的断言标记输出到 Gradle stdout。

| 检查 | 证据与结果 |
| --- | --- |
| 五种采样序列 × 三个周期 | `TAA_SAMPLER_VALIDATION_PASSED`；确定性、值域、周期、phase 唯一性、epoch 和泊松全部前缀通过 |
| 实际 GPU 历史累积 | 64² 合成目标中心从 128 → 138，解析读回 133；确认历史确实参与 |
| 深度 / 越界 / reactive 拒绝 | 改变深度或重投影到范围外读回当前 138；大幅变色 30 → 220 读回当前 220 |
| 合成时间方差 | 120 / 136 交替的固定深度输入，32 帧取末 24 帧，输入方差 64、解析输出约 0.3316；仅证明受控输入的稳定作用 |
| 原版双端和双端仅 Sodium | 全 15 个组合 ACTIVE，历史帧数达到 8 / 16 / 32，保存同场景 OFF / TAA 截图；NeoForge 仅 Sodium 的完整 stdout 另存 `neo-sodium-final.log` |
| 双端 Iris 安装但光影关闭 | 全 15 个组合运行，重载、resize、OFF / 重启可恢复 |
| 双端真实 Complementary 启停 | `FABRIC_TAA_IRIS_SHADER_PAUSE_AND_RECOVERY_PASSED` / `NEO_TAA_IRIS_SHADER_PAUSE_AND_RECOVERY_PASSED`；自动 / 叠加暂停、具体原因、请求模式不变、历史释放、jitter 索引归零、关闭光影恢复 |
| 参数与界面 | `TAA_PERSISTENCE_PRESETS_NATIVE_QUALITY_PASSED`；保存 / 加载、缺失和非法值、画质联动与原版画质列表；`TAA_CONFIG_RESET_DYNAMIC_CENTER_PASSED` 检查顶部无重置、按钮显隐及居中坐标 |
| 生命周期 | 传送、FOV、resize、第三人称、资源重载、OFF / 重启和世界退出；NeoForge 额外重进世界及真实下界切换检查 epoch |
| 重复切换与资源实物 | `TAA_REPEATED_TEXTURE_CLOSE_PIPELINE_BOUND_PASSED cycles=12`；保留旧目标引用确认 5 张历史 / 原始颜色纹理实际关闭，OFF 分配归零，重启后 TAA 管线缓存固定为 2 |

静态场景包含斜边、栅栏、树叶、玻璃与盔甲架，保存 HUD / 手持物品和配置截图。运动补测包含亚像素位置变化、连续镜头旋转、移动实体、粒子、受伤 / 恶心投影和物品栏。截图可用于对照，最终的主观拖影 / 软化容忍度仍需玩家在实际游玩中判断，自动断言不代表所有动态场景无视觉缺陷。

NeoForge 光影验收仅使用加载器属性 `neoforge.disableGlValidation=true`，与现有 `runClientWithIris` 一致，未改 `SharedConstants.IS_RUNNING_IN_IDE`。部分隔离开发客户端在完成断言并保存世界后仍遇到既有 shutdown watchdog / 已关闭 Minecraft module 异常；运行断言通过与 Gradle 客户端进程正常退出分开报告，不将退出异常记为干净的 `runClient` 成功。Vulkan 未运行验证。

### 16.4 成本和采样对照

历史附件成本为 `2*(RGBA16F 8 + D32 4) + RGBA8 4 = 28` 字节 / 像素。960×640 历史计数 17,203,200 字节（约 16.4 MiB），1080p 历史约 55.4 MiB，4K 历史约 221.5 MiB；新增逐物体运动附件另计 16 字节 / 像素，总纹理成本为 44 字节 / 像素，1080p 约 87.0 MiB，4K 约 348.0 MiB。另有顶点／uniform ring、GPU timestamp query pool、CPU 几何快照及驱动开销。全部序列共享同一套目标，不随样本数增加纹理数量。

下面是 Fabric + Sodium、960×640、每个组合稳定后的一次取样，单位 μs。GPU timestamp 覆盖解析、历史复制与写回，CPU 为采样 / 提交时间。这些数值用于确认计时路径与数量级，包含调度噪声，不能作为独立性能基准或不同序列优劣结论。

| 序列 | N | 采样 CPU | 解析 CPU | 解析 GPU |
| --- | --- | --- | --- | --- |
| Halton | 8 | 4.4 | 120.9 | 62.080 |
| Halton | 16 | 2.1 | 81.4 | 61.920 |
| Halton | 32 | 1.9 | 73.8 | 61.760 |
| Hammersley | 8 | 1.9 | 144.0 | 61.664 |
| Hammersley | 16 | 2.2 | 107.2 | 61.728 |
| Hammersley | 32 | 2.1 | 115.3 | 62.048 |
| Sobol / Owen | 8 | 3.3 | 86.0 | 61.920 |
| Sobol / Owen | 16 | 2.6 | 81.8 | 61.696 |
| Sobol / Owen | 32 | 3.5 | 110.7 | 66.720 |
| Rank-1 / R2 | 8 | 2.9 | 120.1 | 61.152 |
| Rank-1 / R2 | 16 | 2.2 | 89.1 | 61.120 |
| Rank-1 / R2 | 32 | 2.1 | 66.0 | 61.728 |
| Progressive Poisson | 8 | 1.1 | 97.2 | 61.600 |
| Progressive Poisson | 16 | 0.8 | 63.9 | 62.656 |
| Progressive Poisson | 32 | 0.9 | 124.0 | 61.888 |

诊断可读取 `TaaMethod.jitter` 的 epoch / 索引 / 前后 jitter / resetReason，以及 `sampleCpuNanos`、`TaaResolve.historyFrames / allocatedBytes / resolveCpuNanos / resolveGpuNanos`。不支持 timestamp 时 GPU 时间为 -1，不将 CPU 提交时间冒充 GPU 执行时间。

### 16.5 最终构建与发布包检查

使用进程内 JDK 25 执行 `./gradlew.bat :fabric:build :neoforge:build --offline --console=plain`，双端 `BUILD SUCCESSFUL`。最终包为 `../../fabric/build/libs/aamod-fabric-0.3.1-alpha+mc26.2.jar` 和 `neoforge/build/libs/aamod-neoforge-0.3.1-alpha+mc26.2.jar`，保留本次工作区的版本设置。

包内确认 `client/antialiasing/taa/` 类、`TemporalSettings`、`CameraMixin`、TAA vertex / fragment shader 和图标齐全；不存在旧 `client/taa/` 类、Iris / Sodium 实现或临时验证类。TAA 字节码为 Java 25（major 69），元数据无未展开占位符，66 个中英文翻译键一致，`git diff --check` 通过。构建生成的 IDE 配置噪声已清理。

第四阶段按本节及逐物体运动向量方案明确的支持范围交付：相机／深度 TAA、已接入几何的物体运动、五种采样序列、参数与持久化、历史生命周期以及 Iris 暂停／恢复。光影内部时域协作、TAA + SSAA 和 Vulkan 的支持边界保持本节说明，不由构建成功推断额外能力。

上述工程检查后，固定设置启动曾暴露首帧 `Invalid TAA camera matrix`，随后用户反馈静止画面仍抖动；两项缺陷的修复与复核分别见第 16.6 节及时域采样方案第 13 节。2026-11-09 用户确认已接入部分视效正常，最终验收及当前版本交付见第 16.8 节。

用户目视验收采用固定设置的长期运行，由用户控制镜头与游戏操作；早期使用 TAA / Halton / 16 样本，后续保留用户实际保存的选择。自动切换矩阵与静态截图仅证明其各自覆盖的工程行为，不作为时域画质验收。观察时仅记录 epoch / resetReason / jitter 索引 / 已提交历史帧数；CPU 计数增长不等于像素历史实际参与混合。稳定配置下的问题应核对实际历史权重和拒绝路径，不能再通过频繁切参掩盖或混淆。

### 16.6 固定设置启动的相机输入检查

首轮固定观察日志记录 `Invalid TAA camera matrix`，其后持续 FAILED、`submitted=0/16`。此前自动切换模式会解除失败锁定，不能覆盖“从配置直接启动 TAA”的行为；该故障也说明实际抖动不能一概归因于历史尚未积累。

控制器现在先检查原版相机的 `initialized`、位置有限性和投影 / 视图矩阵的有限性与可逆性；在 `renderLevel` 提交实际世界投影时再次检查，覆盖提取之后叠加的受伤、摇晃等屏幕投影效果。无效输入不注入 jitter，也不作为 `CAMERA_MATRICES` 交给解析；当帧显示缺少输入，后续有效帧可自动建立历史。已有历史跨越缺失帧时沿用 `frame_gap` 重置。真正发生在有效输入后的解析异常仍由原有失败隔离处理。

修复后使用 JDK 25 重新构建双端 `0.3.1-alpha+mc26.2` 发布包，`BUILD SUCCESSFUL`。固定启动的运行结果在时域采样方案第 11.5 节记录；不再以切换采样参数的矩阵替代此项检查。

新增输入分支的独立进程验证已通过 `TAA_CAMERA_INPUT_READINESS_AND_RECOVERY_PASSED`：覆盖未初始化、奇异 / 非有限输入、有效下一帧恢复以及已有历史跨缺失帧的 `frame_gap` 重置。它不创建 GPU 设备、不连接或操作观察客户端。命令、证据及适用范围见时域采样方案第 11.5 节；后续用户画质确认见第 16.8 节。

### 16.7 后锐化与原版 F3 诊断追加

按用户要求新增 TAA 后锐化强度 0–1，默认 0 关闭，两配置入口提供滑条，Options 持久化，模组配置页有独立重置。强度快照在帧准备读取，不触发历史重置；仅最后输出 pass 进行受局部对比和范围约束的五点锐化，历史仍保存未锐化结果。锐化本身不增加原有 28 字节 / 像素的历史附件；逐物体运动附件另计。独立隐藏 GPU 检查已验证强度递增、零强度恒等、局部边界、原始 alpha 与单像素目标，实际路径和证据见时域采样方案第 11.6 节。

诊断从临时快捷栏提示迁移到正式原版 F3 条目 `aamod:taa_diagnostics`，复用原版关闭 / 叠加层 / 常显与保存机制；原版默认 / 性能预设使用叠加层。状态和历史信息为只读，不改设置或历史；不将 submitted 计数解释成像素历史采纳率。双加载器共用实现，临时观察器仅留日志。

Fabric + Sodium 实际运行通过原生 F3 条目、三态显示、独立配置保存重读及真实选项列表检查；Blaze3D 后锐化原始 RGBA 读回通过，强度 0 / 0.5 / 1 输出中心为 160 / 162 / 165，历史仍为 160。证据标记为 `TAA_F3_NATIVE_TRISTATE_PERSISTENCE_OPTIONS_PASSED` 与 `TAA_POST_SHARPEN_GPU_PASSED`，不涉及玩家设置的自动切换。后续保留用户保存的采样 / 强度供长期观察，最终用户确认见第 16.8 节。

### 16.8 用户目视验收与第四阶段完成

2026-11-09 用户明确确认：「已通过观察确认除去未接入的粒子和天气外，已接入部分的视效正常」。据此补齐时域采样方案第 9 节第 6 项的用户验收，结合双端工程、GPU 及生命周期检查，第四阶段在第 16.1／16.2 节和逐物体运动向量方案声明的支持范围内完成。粒子、天气及其它未接入路径没有因此获得精确运动向量支持；Iris 光影开启时 TAA 暂停、手持物品与 GUI 不进入历史等边界保持不变。

静止抖动修复和双端实际回归见 [时域采样方案](TEMPORAL_SAMPLING_PLAN_ZH.md) 第 13 节。最终使用 JDK 25 按工作区版本 `0.4.1-alpha+mc26.2` 重新构建两端发布包，`phase4-acceptance-build.log` 为 `BUILD SUCCESSFUL`，产物为 `../../fabric/build/libs/aamod-fabric-0.4.1-alpha+mc26.2.jar` 和 `../neoforge/build/libs/aamod-neoforge-0.4.1-alpha+mc26.2.jar`。包内 TAA／运动类与已运行验证版本一致，shader 与当前源码一致，版本、Java 25 字节码、69 个翻译键、图标和无验证类／无内置可选依赖检查通过；完整证据见时域采样方案第 14 节。

## 17. 第五阶段：粒子、天气与世界透明效果

### 17.1 状态与完整方案

状态：设计已记录，待实现。完整架构见 [第五阶段世界效果时域处理方案](TEMPORAL_EFFECTS_PLAN_ZH.md)。该方案定义最终数据流、模块契约和验收条件，不按临时方案安排实施顺序，也不把只拒绝历史或全部后置绘制作为本阶段交付。

覆盖原版普通／特殊粒子、雨雪、云、世界火焰、拴绳和参与深度测试的文字，并处理它们与水、玻璃及透明实体／物品的组合。标准路径提供可靠运动和独立历史；未知自定义路径、同层运动冲突及资源超限必须有明确的当前帧处理和诊断。

### 17.2 架构与透明合成

沿用统一帧、相机、jitter 和 epoch，在透明内容混入前取得干净世界颜色／深度。粒子实例和天气列适配器输出不可变实际绘制快照；独立效果历史保存预乘颜色、覆盖、身份与深度，最终合成颜色不写回背景历史。雨雪同时处理几何变化与纹理图案运动，前帧状态以成功渲染帧为准。

原版排序透明启用时，保留 `main`、`translucent`、`item_entity`、`particles`、`weather`、`clouds` 六目标的逐像素稳定深度排序及预乘混合。时域解析位于最终合成前，使用解析颜色和对齐的当前深度作为原版透明后处理输入；不使用历史深度决定排序、不重复合成、不把目标内多种运动平均为可靠向量。

未启用原版排序透明时，按原实际绘制顺序划分可独立表达的贡献区间，保留每个区间的入口深度、深度写入和混合类型，逐区间解析后按原顺序合成。不得把所有粒子／雨雪无条件挪到水和玻璃上方。两个后端的能力、失败行为和 framegraph 目标寿命均须显式声明。

### 17.3 资源、兼容与验收边界

效果历史池按合成域分配，统一维护所有权、内存账本、预算、局部失效和整帧成功提交。最终只锐化一次；模式、画质透明算法、尺寸、资源与外部管线变化同步失效。预算不足或未知内容的降级保留真实当前画面与原生合成，不遗漏对象，不隐式改写用户选择。

Fabric／NeoForge、原版／Sodium／Iris 无光影分别验证；Iris 光影开启时全部 TAA 继续暂停。原版六目标算法的目标内近似保持明确，不宣称无限层逐片元 OIT、任意模组渲染器、TAA + SSAA 或光影内部历史支持。

专项方案第 14 节 E01–E15 为完成门槛，包含身份与时钟、GPU 实际历史消费、天气纹理循环、覆盖消失、透明排序和混合对照、两种透明后端、资源与性能账本、双端兼容及用户固定设置长期目视验收。正常负载下必须有粒子／天气的有效历史累积；仅遮罩、全拒绝或构建通过不能标记完成。
