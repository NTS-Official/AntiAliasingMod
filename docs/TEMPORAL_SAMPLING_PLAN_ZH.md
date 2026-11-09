# AntiAliasingMod 多采样时域抗锯齿方案

## 1. 目标与范围

本文包含第四阶段 TAA 的设计约定及第 11 节起的实际实现与验收记录。TAA 与五种采样序列已接入；默认 Halton + 16，Iris 光影使用中明确暂停。2026-11-09 用户确认已接入部分的视效正常，第四阶段在已声明支持范围内完成，粒子、天气等未接入路径仍保留原有限制，最终结论见第 14 节。整体路线现为五阶段，阶段边界和第四阶段运行验收见 [抗锯齿五阶段实施与扩展方案](AA_IMPLEMENTATION_PLAN_ZH.md) 第 16 节；第五阶段粒子、天气与透明效果的完整架构及原版顺序无关透明兼容见 [世界效果时域处理方案](TEMPORAL_EFFECTS_PLAN_ZH.md)，当前为设计、待实现。

本方案为 `AntiAliasingMod` 的传统 TAA 提供多种可并存的相机抖动采样方式，并允许用户在视频设置中手动切换。方案只依赖 Minecraft 的 Blaze3D 渲染抽象层，不依赖 DLSS、FSR、XeSS、NRC 或其它神经超分框架。

采样序列只影响 TAA 的全局亚像素投影抖动。FXAA、SSAA 和第二阶段的 SMAA 1x 不使用它们；没有 TAA 历史时，序列选择不应改变画面。切换序列、样本数、渲染尺寸、世界或相机投影时，必须使旧历史失效。

本方案不把“低差异”当成唯一目标。TAA 还要兼顾历史重投影后的前缀覆盖、条纹/闪烁频谱、相机移动时的稳定性和实现成本。

## 2. 不可变约定

- 每帧只生成一个全局 `(x, y)` 投影抖动，值域为 `[-0.5, +0.5)` 像素。
- 采样点注入世界投影矩阵；GUI、正交 HUD 和不应抖动的界面投影保持稳定。
- 当前帧和上一帧都保存实际使用的 jitter，运动矢量和历史重投影使用同一对值。
- 序列生成、投影矩阵注入、历史滚动和 TAA resolve 分层，不能让每种序列重复实现历史逻辑。
- 采样器必须是确定性的：相同的序列、周期、历史 epoch 和帧索引得到相同的点。
- 不允许每帧重新随机整个序列。需要扰动时，只在历史 epoch 开始时生成一次固定 rotation/scramble。
- 相机抖动不能使用逐像素蓝噪声纹理；逐像素蓝噪声属于光照、阴影或其它随机采样阶段。

## 3. 用户可选模式

第四阶段在视频设置新增一个 **TAA jitter sequence** 选项，并保留已有抗锯齿模式及第二阶段新增的 SSAA。序列选项在非 TAA 模式下可以显示但不生效，也可以在界面上标记为仅 TAA 使用。

建议的可选项如下：

| 内部值 | 显示名称 | 特征 | 初始状态 |
| --- | --- | --- | --- |
| `HALTON_23` | Halton (2, 3) | 当前基线，渐进式、便宜、最容易复现 | 默认 |
| `HAMMERSLEY` | Hammersley | 固定周期内整体覆盖均匀，适合固定 8/16 点集 | 可选 |
| `SOBOL_OWEN` | Sobol (Owen scrambled) | 前缀覆盖好，扰动后减少二进制条纹 | 可选 |
| `RANK_1_R2` | Rank-1 / R2 lattice | 计算简单、可持续生成，适合传统 TAA 实验 | 可选 |
| `POISSON_PROGRESSIVE` | Progressive Poisson blue noise | 把误差推向高频，减少规则纹理感 | 可选 |

另设 **TAA jitter samples**，初始提供 `8 / 16 / 32`。默认使用 `16`。Hammersley 和泊松模式必须使用与其资产或点集匹配的样本数；不支持的组合应在界面中禁用，而不是静默改用另一种序列。

## 4. 各采样方式的实现约定

### 4.1 Halton (2, 3)

对两个轴分别使用 base 2 和 base 3 的 radical inverse，再减去 `0.5`。索引从 1 开始，避免首个点直接落在边界。它作为默认基线，用来判断其它序列是否真的改善了拖影、闪烁和收敛。

第四阶段先将此基线接入统一接口；新的序列不能改变它的坐标方向、像素幅度或历史语义。

### 4.2 Hammersley

对于周期 `N`，使用类似下面的有限点集：

```text
u = (i + 0.5) / N
v = radicalInverse(i + 1, 2)
point = (u, v) - 0.5
```

Hammersley 是固定 `N` 的点集，不应被描述成无限渐进序列。它适合完整走完固定 8 或 16 个 phase 的 TAA，但改变 `N` 会改变整套点集。改变样本数、窗口尺寸或序列后要重置历史。

由于第一轴规则等分，必须检查斜边、细线和横向运动中的方向性条纹。必要时可以使用每个历史 epoch 固定一次的二维 rotation，但不能每帧重新 rotation。

### 4.3 Sobol (Owen scrambled)

使用二维 Sobol direction numbers，并在一个历史 epoch 内固定 Owen scramble。scramble seed 从确定性的历史 epoch 派生，不能使用每帧变化的随机种子。

不把未扰动 Sobol 作为玩家默认选项。未扰动版本的二进制结构可能在屏幕上形成轴向条纹；Owen scramble 的目的是破坏可见结构，同时保留低差异前缀的优点。

如果未来把同一采样器扩展到光源或路径采样，必须给不同用途分配独立维度和 seed，不能把 camera jitter 与其它随机量直接复用。

### 4.4 Rank-1 / R2 lattice

使用一个经过频谱测试的生成向量，例如黄金比例相关的 R2 向量，按帧索引计算 `frac((i + 0.5) * generator + rotation)`。rotation 在历史 epoch 内保持不变。

Rank-1 的优点是实现小、CPU 成本低、可以不依赖固定点表。缺点是规则格状结构会在频域留下尖峰；生成向量选择不当时，某些周期、分辨率或运动方向会出现斜向条纹和重复鬼影。实现前应为 `N = 8 / 16 / 32` 分别做频谱和最小距离检查，不能只凭公式选一个常数。

### 4.5 Progressive Poisson blue noise

泊松蓝噪声使用预计算的、经过渐进性检查的二维点表。运行期只按 `i` 读取点，并在历史 epoch 开始时应用固定 rotation。禁止在客户端启动时或每帧重新运行泊松生成器。

普通泊松点集只保证完整点集的最小距离，前几个点可能覆盖很差。因此资产必须验证每个前缀，而不只是验证完整的 16 或 32 点集合。没有通过前缀测试的点表不能作为 TAA 序列发布。

这里的“蓝噪声”是时间上的全局 jitter 点表，不是逐像素蓝噪声纹理。真正的逐像素蓝噪声或 STBN 应留给阴影、反射、光源候选和其它随机采样。

## 5. 代码结构草案

将采样器放在 `../common/src/main/kotlin/com/nts/aamod/client/antialiasing/taa`，不引入 Fabric 或 NeoForge 类型。

建议拆成以下职责：

```text
TemporalJitterSequence
  ├─ Halton23Sequence
  ├─ HammersleySequence
  ├─ SobolOwenSequence
  ├─ Rank1R2Sequence
  └─ ProgressivePoissonSequence

TemporalJitterController
  ├─ 当前序列和样本数
  ├─ frameIndex / historyEpoch
  ├─ 当前与上一帧 jitter
  ├─ rotation / scramble seed
  └─ reset 原因

TaaProjectionBridge
  ├─ 世界投影注入
  ├─ 非抖动投影保留
  └─ 当前/上一帧矩阵和 jitter 提供给 resolve
```

`TemporalJitterSequence` 只负责根据 `(index, sampleCount, epochSeed)` 返回样本。它不操作历史纹理、不改变 TAA 权重，也不直接访问 Minecraft 客户端对象。

`TemporalJitterController` 负责在以下事件发生时递增 `historyEpoch` 并清除历史：

- 序列切换；
- jitter 样本数切换；
- 窗口、渲染尺寸或视场角改变；
- 世界、维度或相机传送；
- TAA 从关闭重新启用；
- 投影矩阵或渲染目标格式改变。

视频设置中的 `OptionInstance` 只保存用户选择。实际 TAA pass 每帧读取 controller 的当前快照，避免设置界面直接修改渲染线程状态。

## 6. UI 和持久化

在 `VideoAntiAliasingSettings` 中新增：

- `JITTER_SEQUENCE`：序列枚举值；
- `JITTER_SAMPLES`：8、16、32；
- 统一的序列名称、tooltip 和有效组合检查。

`VideoSettingScreenMixin` 将两个选项添加到质量设置中。语言文件增加中英文名称和说明，tooltip 必须说明：切换序列会清空 TAA 历史，短时间内会重新收敛。

选项必须和 Minecraft `Options` 的持久化生命周期接通。不能只保存到 `VideoAntiAliasingSettings` 的静态字段，也不能依赖未被调用的临时文件读写函数。启动时读取用户选择，关闭设置或保存选项时写回；非法或缺失值回到 `HALTON_23 + 16`。

## 7. TAA 数据流

```text
用户选择序列 / 样本数
  → Options 持久化
  → TemporalJitterController 检查是否需要 reset
  → 生成本帧全局 jitter
  → 注入世界投影矩阵
  → 渲染当前帧颜色和深度，捕获实体／方块实体实际顶点的前后帧对应
  → 光栅化运动向量、前帧预期深度与有效性
  → 动态像素使用物体向量，静态像素使用相机矩阵重投影历史
  → 邻域限制、历史权重和 resolve
  → 提交新的历史纹理
```

所有序列共享同一套历史重投影和 resolve。比较序列时不能同时改变历史权重、锐化、曝光、解析度或运动矢量算法，否则无法判断差异来自采样序列还是其它参数。

## 8. 验证矩阵

### 工程检查

- 每个序列在 `8 / 16 / 32` 样本下可重复生成；
- 所有点在 `[-0.5, +0.5)` 内；
- 周期切换、序列切换和历史 epoch 变化不会复用旧 jitter；
- Hammersley/泊松点表的样本数与表长度一致；
- Sobol scramble 在同一 epoch 内稳定，重置后可复现；
- 世界切换、窗口 resize 和 TAA 重启都会清除历史；
- 非 TAA 模式不注入投影 jitter；
- Fabric 与 NeoForge 使用同一份 common 采样代码。

### 场景检查

至少准备以下场景：

- 静态高对比细线、栅栏、树叶和斜边；
- 亚像素移动的方块边缘和几何体；
- 快速横向移动、镜头旋转和远近缩放；
- 透明、粒子、发光和带高频纹理的材质；
- camera cut、传送、窗口 resize 和渲染尺寸切换；
- 世界重载以及 TAA 关闭后重新开启。

关注条纹、闪烁、拖影、重影、颗粒感和收敛速度。统计数据可用于定位，但最终的画面质量仍由用户在相同场景、相同历史权重下手动确认。

### 性能记录

记录序列生成的 CPU 时间、TAA resolve GPU 时间、历史纹理占用和蓝噪声资源读取成本。预期普通序列生成的 CPU 差异很小；泊松表主要增加资源管理和纹理读取成本。不要用“生成器更快”直接推断最终帧率更高。

## 9. 推荐默认值和实施顺序

默认使用 `Halton (2, 3) + 16 samples`，确保有稳定的兼容基线。

实施顺序建议为：

1. 先抽象 `TemporalJitterSequence` 和 `TemporalJitterController`，保持 Halton 画面不变。
2. 接入视频设置、持久化和统一历史 reset。
3. 加入 Hammersley 和 Rank-1/R2，完成固定样本数与频谱对照。
4. 加入 Owen-scrambled Sobol，验证短前缀、camera cut 和快速移动。
5. 加入经过前缀验证的 Progressive Poisson blue-noise 表。
6. 在相同 TAA resolve 参数下完成跨加载器和用户目视验收。

这个衍生物不需要为神经超分保留 provider phase 协商。`TAA jitter samples` 完全由用户设置控制，但仍要把序列索引、历史 epoch 和样本数变化写入诊断信息，方便复现不同序列的画面问题。

## 10. 不做的事情

- 不把逐像素蓝噪声直接写入全局投影矩阵；
- 不在每帧重新生成随机泊松点集；
- 不把未扰动 Sobol 设为默认；
- 不在改变 Hammersley 点集后继续复用旧历史；
- 不为了比较序列同时改变 TAA resolve 权重、锐化或运动矢量；
- 不引入 DLSS、FSR、XeSS、NRC 或 RTX SDK 作为运行时前置。

## 11. 第四阶段实际实现记录

### 11.1 职责映射

实现位于 common，与 Fabric / NeoForge 共用：

| 文件 | 职责 |
| --- | --- |
| `client/antialiasing/taa/TemporalJitterSequence.kt` | 五种确定性采样器；纯函数 `(index, sampleCount, epochSeed)`，返回像素偏移 |
| `client/antialiasing/taa/TemporalJitterController.kt` | epoch、帧索引、前后帧 jitter 和重置原因 |
| `client/antialiasing/taa/TaaMethod.kt` | 设置快照、投影注入、相机 cut / FOV 判定、实际矩阵重投影和历史生命周期 |
| `client/antialiasing/taa/TaaResolve.kt` | 原生 Blaze3D 管线、历史双缓冲、深度 / 原始颜色保存、解析输出与计时 |
| `client/option/TemporalSettings.kt` | `OptionInstance`、默认值、组合验证和 Options 持久化 |
| `client/mixin/render/CameraMixin.kt` | 仅 TAA 使用稳定的一像素剔除裕量；不向剔除矩阵注入 jitter |

代码使用一个枚举统一实现五个采样器；投影桥接职责由既有 `GameRendererMixin`、controller 和 `TaaMethod` 分担，没有新增只负责转调的 `TaaProjectionBridge`。`VideoAntiAliasingSettings` 保留模式职责，新增参数集中在 `TemporalSettings`。世界实际投影才带 jitter；相机提取投影、手持物品和 GUI 投影不抖动。

五种模式均支持 8 / 16 / 32。泊松使用同一 32 点渐进表的对应前缀；Hammersley 按所选周期重新计算点集。Sobol 采用二维 Gray code direction numbers 和按原始前缀哈希的二叉 Owen permutation；同一 epoch 固定。Rank-1 / R2 采用 `(0.7548776662466927, 0.5698402909980532)`，与泊松一样仅在 epoch 更换时更新 rotation。

保存键为 `aamod_taa_sequence` 和 `aamod_taa_samples`，默认 0 / 16；缺失与越界输入通过实际 Options 加载检查。原版画质设置和共享配置页都有两个选项；共享配置页有独立、恢复默认后隐藏的重置按钮。切换序列 / 周期不改其它解析参数。

### 11.2 原创点表的来源与前缀检查

32 点表由本项目离线生成，无外部资产：使用种子 `0x7357a11` 的 32 位 LCG，递推 `state = (1664525 * state + 1013904223) mod 2^32`，输出 `state / 2^32`。首点取两次输出；随后每点生成 64 个二维候选，选择到已有点的最小环面距离最大的候选，坐标保留 10 位小数。客户端只读该表，不运行生成器，也不读取逐像素蓝噪声纹理。

对每个前缀 `n=2..32` 检查环面最小间距 `>=0.4/sqrt(n)` 和 32×32 探针网格的覆盖半径 `<=1.5/sqrt(n)`，全部通过。这是本项目的渐进覆盖门槛，不是对所有分辨率或运动方向的无条纹证明。

| 泊松前缀 | 最小距离 | 网格覆盖半径 |
| --- | --- | --- |
| 2 | 0.638456 | 0.491498 |
| 4 | 0.425163 | 0.331394 |
| 8 | 0.271318 | 0.266478 |
| 16 | 0.178228 | 0.235219 |
| 32 | 0.128392 | 0.149501 |

### 11.3 空间频谱与间距对照

固定 epoch seed=23；低频能量为 `kx,ky ∈ [-2,2]` 中除 DC 外 24 个频点的平均归一化功率：`mean(|sum(exp(2πi(kx*x+ky*y)))|²/N²)`。它描述点集空间低频结构，不能直接代表运动画面的时间频谱，也不据此声称任一序列画质最佳。

| 序列 | N | 最小环面距离 | 低频能量 |
| --- | --- | --- | --- |
| Halton | 8 | 0.167244 | 0.098454 |
| Halton | 16 | 0.160580 | 0.027695 |
| Halton | 32 | 0.096947 | 0.009569 |
| Hammersley | 8 | 0.225347 | 0.107946 |
| Hammersley | 16 | 0.112673 | 0.032208 |
| Hammersley | 32 | 0.056337 | 0.001833 |
| Sobol / Owen | 8 | 0.184298 | 0.053634 |
| Sobol / Owen | 16 | 0.102851 | 0.031834 |
| Sobol / Owen | 32 | 0.051908 | 0.033075 |
| Rank-1 / R2 | 8 | 0.271368 | 0.066123 |
| Rank-1 / R2 | 16 | 0.172171 | 0.006924 |
| Rank-1 / R2 | 32 | 0.141010 | 0.002911 |
| Progressive Poisson | 8 | 0.271318 | 0.097301 |
| Progressive Poisson | 16 | 0.178228 | 0.014651 |
| Progressive Poisson | 32 | 0.128392 | 0.004093 |

全部 15 个组合通过值域、确定性、周期、无重复 phase 检查；控制器重置清零索引并更换 Sobol scramble。运行验证中的每种组合都累积至所选样本数，并保留同一世界的 OFF 与 TAA 截图。计时和实际动态内容限制见主方案第 16 节。泊松点保存在 Kotlin 常量表中，成本为 CPU 表访问，没有额外蓝噪声纹理读取。

### 11.4 用户目视验收状态

用户最初确认尚未进行目视验收，并明确要求在固定一套设置下长时间亲自观察；2026-11-09 已确认已接入部分视效正常，最终记录见第 14 节。早期观察方案为 TAA / Halton (2, 3) / 16 样本；后续观察沿用用户实际保存的选择，不由代理强制恢复早期默认值。保持其余画质、分辨率和世界不变，不自动切换模式 / 序列 / 样本数，不自动截图、传送、重载或退出。镜头与游戏操作由用户控制。固定的是配置，每帧正常的 jitter phase 仍按序推进。

第 9 节第 6 项中的用户画质确认已由上述反馈补齐，范围限定为已接入部分。短时间切换和单帧截图不能验收时域闪烁、运动拖影或稳定收敛；已有自动矩阵仅作为工程检查。只读状态可记录 epoch、重置原因、jitter 索引、解析帧号与已提交历史计数，以区分持续积累和反复重置；`historyFrames` 不证明像素实际采纳了历史，仍需结合真实解析权重 / 拒绝路径排查。观察期间不为采集证据更改配置或打断用户。

固定观察客户端位于 `%TEMP%/aamod-taa-observation/client`，使用 Fabric + Sodium、无 Iris；启动脚本为上级目录的 `observe.init.gradle`。临时 `taa_observer` 每秒显示 ACTIVE 状态、epoch、已提交历史计数和 jitter 帧索引，并在 `logs/latest.log` 记录前后 jitter、重置原因及准备 / 投影 / 解析帧号。它不改任何设置，不加载自动 GameTest，也不截图、传送或自动退出。该观察器不随发布 jar 打包。该会话曾保留供用户长时间观察；后续代理工作不得为继续自动矩阵而关闭或重启此会话。追加运动向量时只读日志确认此客户端已在 2026-11-09 09:34:06 记录 `Stopping!`，本轮没有操作它，也没有擅自重新启动。

### 11.5 固定设置启动故障与输入检查

首轮固定设置启动在世界首帧报 `Invalid TAA camera matrix`，随后持续 FAILED、`submitted=0/16`，并非历史仍在暖机。修复在相机提取和实际世界投影提交处检查输入是否已初始化、有限且可逆；缺失时跳过当帧 jitter / resolve，后续有效帧自动恢复。该处理不清除真实解析异常的失败锁定，也不通过自动切换模式恢复。

临时观察器增设只读投影探针，记录无效 / 恢复转换、基础矩阵有限性以及原版 `hurtTime` / `hurtDuration`，用于确认发生故障的具体阶段。该探针原样返回实际投影，不改动相机、采样配置、历史或 shader。原版受伤倾斜在 `hurtTime == 0 && hurtDuration == 0` 时存在产生非有限矩阵的路径，但本轮探针未记录无效投影转换，不能将其认定为前次故障的确定来源。

修复后保持 TAA / Halton / 16 从配置直接启动，无需切换模式：日志 00:24:49 为 ACTIVE、`submitted=3/16`，00:24:50 达到 `16/16`；至 00:25:43 的 55 条只读状态记录中，epoch 一直为 10，phaseFrame 增至 5295，准备 / 投影 / 解析帧号一致增长，没有 TAA FAILED 或矩阵异常。此结果证明该次固定启动的提交链持续工作，并非不断重置或尚未暖机；它不量化真实场景逐像素的历史采纳率，也不替代用户的长期目视验收。客户端保持运行，后续操作由用户控制。

新增输入检查另以独立 Java 进程直接调用实际控制器验证，不创建窗口或 GPU 设备，也不连接观察客户端：未初始化相机、奇异基础投影、非有限相机位置不暴露相机输入；非有限 / 奇异实际世界投影不注入 jitter，不进入 FAILED；下一有效帧恢复投影准备；已有历史跨越缺失帧时以 `frame_gap` 清空历史。检查输出 `TAA_CAMERA_INPUT_READINESS_AND_RECOVERY_PASSED uninitialized singular nan_position nan_world frame_gap`，且历史 GPU 分配始终为 0。临时入口为 `%TEMP%/aamod-taa-observation/camera-check.init.gradle` 的 `:fabric:verifyTaaCameraInputs`，结果保存在 `camera-check.log`，使用 JDK 25，`BUILD SUCCESSFUL`。反射构造的帧与历史只存在于此独立进程，不修改正在运行的游戏状态；此检查证明输入分支和恢复控制流，不冒充真实世界 GPU 解析或画质验收。

### 11.6 可调后锐化与 F3 诊断

用户追加要求可调后锐化。`TemporalSettings.sharpness` 提供 0–1 滑条，默认 0 关闭，保存键为 `aamod_taa_sharpness`；缺失、非有限或越界值回退 0。原版视频设置画质分组和共享配置页均提供，后者沿用独立自动隐藏重置按钮和动态居中。帧准备读取强度快照，强度变化不改变采样序列或 epoch。

`taa_sharpen.fsh` 替代原有最终复制 shader，复用相同输出 pass 与 `TemporalConfig` uniform 的 `Settings.z`，不增加全屏 pass 或历史纹理。它从未锐化的解析历史读取中心和上下左右五点，提取局部高频，按局部对比衰减增益，并限制到局部颜色上下界；alpha 保持中心值。强度为 0 时只复制中心。解析历史与上一帧原始颜色在锐化输出前保存，因此不递归锐化历史；手持物品和 GUI 仍在之后绘制。

独立隐藏 OpenGL 上下文直接编译项目实际 vertex / sharpen shader，RGBA16F 输入、RGBA8 输出，强度 0 / 0.5 / 1 的过渡边中心读回 160 / 162 / 165；恒色、零强度恒等、alpha=119、所有像素局部边界及 1×1 尺寸检查通过。标记 `TAA_POST_SHARPEN_SHADER_GPU_PASSED`，日志 `%TEMP%/aamod-taa-observation/sharpen-shader-check.log`。最初客户端临时检查使用原版 `Screenshot.takeScreenshot` 读回；原版强制把 alpha 设为 255，故其 alpha 断言失败不证明 shader 改写 alpha。后续原始 RGBA 读回单独核对集成路径；临时验证不保存截图，也不修改用户世界的采样或锐化设置。

用户随后要求将快捷栏上方的数据改入 F3。正式代码新增 `TaaDebugEntry`，通过原版条目枚举与查找入口接入 `aamod:taa_diagnostics`，位于本模组分类。`DebugScreenEntryList` 的原版配置预设默认置为 `IN_OVERLAY`；用户选择使用原版 `NEVER / IN_OVERLAY / ALWAYS_ON` 及 `debug-profile.json` 保存重读，加载自定义配置不会覆盖用户选择。诊断读取控制器快照，显示状态、epoch、submitted、phaseFrame、锐化强度和 last reset；`last reset` 明确表示最近一次原因，submitted 不表示 GPU 历史采纳率。临时观察器移除 `setOverlayMessage` 调用，仅保留日志，不再占用快捷栏提示。

用户继续追加逐物体运动矢量，方案和实际验证记录集中在 `OBJECT_MOTION_VECTORS_PLAN_ZH.md`。新增真实顶点对应、ModelPart 动画、独立运动纹理及 TAA 消费；原有序列、周期、锐化和 F3 三态保持原来的语义。F3 另显示捕获／匹配／拒绝批次与预算，匹配批次仍是 CPU 诊断。运动附件增加 16 字节／像素，历史附件仍为 28；当前总纹理成本为 44 字节／像素。后续用户观察须使用包含此改动的新构建，不能将旧进程视为已加载运动向量；本轮没有重启既有观察进程。

Fabric + Sodium 运行检查输出 `TAA_F3_NATIVE_TRISTATE_PERSISTENCE_OPTIONS_PASSED`：原版枚举 / 查找均返回本模组条目；在独立 `DebugScreenEntryList` 和临时目录中检查关闭 / 叠加层 / 常显在 F3 开关前后的启用列表、各状态保存重读，以及性能预设回到默认叠加层；离屏构造的真实 `DebugOptionsScreen` 包含该行。检查没有修改玩家实际 F3 选择，也没有自动打开设置页。

同次原生 Blaze3D 运行检查输出 `TAA_POST_SHARPEN_GPU_PASSED baseline=160 medium=162 maximum=165 history=160 flat identity alpha bounds one_pixel`：小型独立目标通过项目实际 resolve / output 管线，原始 RGBA 读回验证 alpha=119；强度 1 的显示输出为 165，直接复制未锐化历史读回仍为 160。日志保存在 `%TEMP%/aamod-taa-observation/f3-client-launch.log`。恢复的客户端沿用玩家已保存的渐进泊松 / 16 / 锐化约 0.597，不强制回到早期 Halton 观察设置；保持运行供用户继续操作，未执行切参矩阵或保存截图。

## 12. 第四阶段交付验收核对

本节分别记录工程检查和用户画质确认，不以构建、计数增长或合成图通过替代用户确认。2026-11-09 追加运动向量、静止抖动修复及用户反馈后的最终核对如下：

| 方案要求 | 当前证据 | 状态 |
| --- | --- | --- |
| 五种序列、8／16／32、确定性／值域／周期、epoch 扰动及泊松前缀 | `TemporalJitterSequence`／`TemporalJitterController`；`fabric-effects-final.log` 的 `TAA_SAMPLER_VALIDATION_PASSED`，前缀与频谱记录见第 11.2／11.3 节 | 工程检查通过 |
| 世界实际投影注入、相机重投影、GUI／手持物品隔离、统一解析和历史 | `CameraMixin`／`TaaMethod`／`TaaResolve`；原有双端运行记录与追加双端运动 GPU 检查；新解析明确绑定 `MotionSampler` | 已实现并运行检查 |
| 模式／序列／周期／尺寸／世界／投影／资源变更的历史失效与恢复 | 控制器失效路径、相机输入独立检查及第 16 节历史运行记录；新增运动资源实物关闭检查见下一行 | 工程检查通过，适用范围按各日志区分 |
| 新运动附件、ring buffer 与拥有者历史清理 | `%TEMP%/aamod-motion-check/motion-lifecycle.log`：独立 Fabric + Iris／Sodium 无光影世界的 OFF／重启、resize、资源重载和退出；6 张纹理、9 个缓冲实物关闭，TAA 管线固定为 4 | 追加工程检查通过 |
| Options 保存／重读／缺失非法值回退、双配置入口与画质联动 | `TemporalSettings`、Options／视频设置 mixin 和共享配置页；`TAA_PERSISTENCE_PRESETS_NATIVE_QUALITY_PASSED`／`TAA_CONFIG_RESET_DYNAMIC_CENTER_PASSED` | 工程检查通过 |
| 可调后锐化与不递归锐化历史 | 实际 shader／Blaze3D 原始 RGBA 读回；`TAA_POST_SHARPEN_GPU_PASSED`，证据见第 11.6 节 | 工程检查通过 |
| 原版 F3 关闭／叠加层／常显与持久化 | `TaaDebugEntry` 与原版条目接入；`TAA_F3_NATIVE_TRISTATE_PERSISTENCE_OPTIONS_PASSED`，证据见第 11.6 节 | 工程检查通过 |
| 逐物体运动、模型动画、共享模型隔离及 GPU 实际消费 | `OBJECT_MOTION_VECTORS_PLAN_ZH.md` 第 7 节；最终双端 `fabric-motion-verified.log`／`neo-iris-verified.log`。FRAPI／Sodium 扩展网格采用拒绝覆盖，其精确向量尚未实现 | 在文档明确范围内通过 |
| 原版／Sodium／Iris 关闭光影运行，开启光影暂停及关闭恢复 | 当前双端无光影运动运行记录；原有 Complementary 启停断言。TAA 不接管光影内部 jitter／历史 | 按既定支持策略交付 |
| CPU 采样时间、解析 GPU 时间及内存成本记录 | 第 16.4 节为原有解析取样；运动附件加入后纹理成本 44 字节／像素，解析时间不包含运动 pass；Sodium 标准顶点写入成本未做独立基准 | 已记录测量范围和限制 |
| 双端发布包与资源／元数据完整性 | 第 14 节 `phase4-acceptance-build.log`／`phase4-acceptance-artifacts.log`；两端 `0.4.1-alpha+mc26.2` 包的 TAA 类与运行验证版本一致，shader 与当前源码一致，无临时检查和 Iris／Sodium 实现，Java 25 与翻译检查通过 | 构建及包检查通过 |
| 第 9 节第 6 项：同一套设置下由用户长期观察闪烁、条纹、拖影、软化与收敛 | 第 13 节修复后，用户于 2026-11-09 确认「除去未接入的粒子和天气外，已接入部分的视效正常」；保留其明确排除的范围 | 已接入部分通过用户目视验收 |

第四阶段在本方案及逐物体运动向量方案声明的支持范围内完成工程检查和用户目视验收。粒子、天气及其它未接入路径不因此获得精确运动向量或完整画质支持；新增支持时仍需单独实现与验收。

## 13. 静止画面持续抖动修复

用户在 2026-11-09 报告：镜头完全静止仍有轻微抖动，五种序列与各样本数都存在，后锐化强度为 0。该问题进入实际缺陷排查，不归因于历史尚未暖机，也不视为用户已通过验收。

### 13.1 重现和原因

隔离隐藏 OpenGL 上下文执行项目实际 `taa_fullscreen.vsh` 与 `taa_resolve.fsh`，对固定竖边、斜边、高频条纹和亚像素细条按真实 jitter 相位光栅采样，深度和相机固定，重投影仅包含前后 jitter 差。每个序列／周期独立运行 256 帧，前 128 帧暖机、后 128 帧统计，无世界、菜单、截图、配置切换或历史 reset。它是受控 GPU 重现，不等同于用户世界的逐像素测量。

旧解析直接比较前后帧原始颜色。即使相机和物体不动，不同 jitter 采样覆盖也会改变高对比边缘的颜色；旧固定阈值把该误差当成真实变化，显著降低历史权重。此外，邻域统计在去抖动后的双线性位置取样，使限制历史的范围随相位变化。单独关闭 reactive 的诊断变体使 Halton／16 竖边 RMS 从 0.113590 降至 0.006303，确认该分支是主要原因；正式代码没有关闭 reactive。

### 13.2 实际修复

`taa_resolve.fsh` 现在从原始像素格的 3×3 texel 计算当前邻域范围、均值与方差，避免双线性预混合收窄历史范围。另统计重投影位置前帧原始 3×3 邻域方差，对原始颜色差扣除前后局部标准差最大值的两倍，再应用原有 reactive 阈值。这样容纳静态边缘的采样不确定性；均匀区域的真实变色、可检测的局部突现仍触发拒绝。保持 YCoCg／1.25σ 历史裁剪、深度／越界／无效动态标记拒绝、运动权重、历史上限和后锐化逻辑。

该处理增加每像素 9 次前帧原始颜色读取，无新增纹理或全屏 pass；未做完整 GPU 性能基准。对高频动态纹理或粒子的拒绝仍有近似性，不能保证所有动态场景无拖影。未更改采样序列、周期、jitter 幅度或玩家设置。

### 13.3 工程验证和交付

临时入口为 `%TEMP%/aamod-taa-jitter/StaticJitterCheck.java` 与 `run-check.ps1`，使用进程内 JDK 25 和隐藏 OpenGL 上下文；对照日志 `baseline.log`，最终日志 `fixed-final.log`。60 个组合（5 序列 × 3 周期 × 4 图形）的时间 RMS 均下降超过 50%，最小降幅约 59.8%。样例为归一化颜色值：

| 图形，16 样本 | Halton 修复前 → 后 | 渐进泊松修复前 → 后 |
| --- | --- | --- |
| 竖边 | 0.113590 → 0.006912 | 0.110225 → 0.007508 |
| 斜边 | 0.096316 → 0.005556 | 0.091872 → 0.005899 |
| 高频条纹 | 0.175085 → 0.008613 | 0.172844 → 0.011208 |
| 亚像素细条 | 0.185799 → 0.034628 | 0.183430 → 0.022297 |

`TAA_JITTER_REJECTION_GUARD_PASSED` 另覆盖有效历史混合、动态无效标记、错误深度、越界、整片变色、局部突现、恒色与 alpha 保持。真实 Blaze3D 管线在双端 Iris／Sodium、光影关闭的独立客户端回归：`fabric-regression.log` 与 `neo-regression.log` 正常成功退出，12 项运动 GPU、四项实际解析消费、真实 ModelPart／共享拥有者、未匹配扩展网格拒绝、预算和世界捕获检查通过。

双端发布构建通过（`build.log`）。运行中的开发客户端持有 common 开发 jar，因此通过临时 `isolated-archives.init.gradle` 将归档生成到独立临时目录；核对包内 shader 与源码一致、没有验证类或内置 Iris／Sodium 后，将最终两端发布 jar 更新至常规 `fabric/build/libs`／`neoforge/build/libs`。版本仍为 `0.3.1-alpha+mc26.2`。没有关闭、重启、重载或切换玩家客户端，运行中进程不自动加载新修复。

这些数据证明已重现并减轻采样误拒绝，仍存在有限的残余采样波动，工程数据本身不能推断用户世界的画质已可接受。修复后的用户观察结论及最终交付见第 14 节。

## 14. 用户目视验收与第四阶段完成记录

2026-11-09，用户在自行观察后明确确认：「已通过观察确认除去未接入的粒子和天气外，已接入部分的视效正常」。此反馈作为第 9 节第 6 项的用户画质验收依据，结合第 12 节工程核对，第四阶段在方案明确的支持范围内完成。没有将该反馈扩大为任意采样设置、加载器、设备或全部动态效果的逐项人工验收，也没有追加自动切参或截图替代用户观察。

粒子和天气尚未接入专用运动历史；其它未接入路径、半透明和不可靠几何的保守拒绝策略，以及 FRAPI／Sodium 扩展网格仅提供拒绝覆盖的边界，继续按逐物体运动向量方案第 2 节说明。Iris 光影开启时 TAA 暂停，手持物品与 GUI 不进入历史，TAA + SSAA、光影内部时域协作及 Vulkan 支持均未新增。

最终交付沿用工作区当前版本 `0.4.1-alpha+mc26.2`。进程内 JDK 25 执行 `:fabric:build :neoforge:build --offline --console=plain -I "$env:TEMP/aamod-taa-jitter/isolated-archives.init.gradle"`，`phase4-acceptance-build.log` 为 `BUILD SUCCESSFUL`。归档先输出到临时目录，核对后复制至常规发布路径，避免覆盖运行中客户端持有的开发 jar：

| 加载器 | 发布包 | 大小 |
| --- | --- | --- |
| Fabric | `../fabric/build/libs/aamod-fabric-0.4.1-alpha+mc26.2.jar` | 1,351,651 字节 |
| NeoForge | `../neoforge/build/libs/aamod-neoforge-0.4.1-alpha+mc26.2.jar` | 1,353,025 字节 |

`%TEMP%/aamod-taa-jitter/phase4-acceptance-artifacts.log` 记录两包的 SHA-256 和 `AA_PHASE4_ARTIFACT_VERIFIED`。两端各 41 个 TAA／运动 mixin 类与已运行验证的 `0.3.1-alpha+mc26.2` 包逐字节一致，字节码 major 69；五个 TAA shader 和两份语言资源与当前源码一致，包含静止抖动修复，69 个翻译键一致，元数据版本与图标路径正确，无未展开占位符、临时验证类或内置 Iris／Sodium 实现。此前运行和 GPU 结果按原日志保留，本次重新构建及包检查没有冒充新增客户端运行检查。
