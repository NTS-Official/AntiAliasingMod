# AntiAliasingMod 多采样时域抗锯齿方案

## 1. 目标与范围

本文属于第四阶段 TAA 的设计资料，尚不代表已有可用实现。整体路线为：第一阶段原版 FXAA 与扩展预留，第二阶段 SSAA / SMAA，第三阶段 Iris / Sodium 适配，第四阶段 TAA；阶段边界见 [抗锯齿四阶段实施与扩展方案](AA_IMPLEMENTATION_PLAN_ZH.md)。

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

将采样器放在 `common/src/main/kotlin/com/nts/aamod/client/taa/`，不引入 Fabric 或 NeoForge 类型。

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
  → 渲染当前帧颜色、深度和运动矢量
  → 使用当前/上一帧矩阵重投影历史
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
