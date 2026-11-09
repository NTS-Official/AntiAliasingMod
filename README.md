# Anti Aliasing Mod

[**English**] | [[**简体中文**](docs/README_ZH.md)]

## Introduction

A Minecraft mod that implements FXAA, SSAA, SMAA 1x and traditional TAA through
the default Blaze3D rendering abstraction.

This repository is an **Architectury-based multi-loader workspace**: one shared
codebase builds a Fabric jar and a NeoForge jar.

## Anti-aliasing status

- **FXAA is implemented** on the vanilla rendering path, after vanilla entity
  post effects and before HUD / GUI rendering. It filters world geometry and held
  items without filtering GUI text, menus, or the crosshair.
- **SSAA renders the world at a higher resolution**, then applies an area filter
  before the HUD. Available scales are 1.5×, 2×, 3× and 4× per axis; the default is 2×.
- **SMAA 1x uses color edge detection, Area/Search lookup textures and neighborhood
  blending**, with the official Low, Medium, High and Ultra presets. High is the default.
- **TAA uses subpixel world projection jitter and depth-based history reprojection**,
  with neighborhood constraints and color-change rejection. It resolves before held
  items; held items and GUI stay outside its history. The default is Halton (2, 3)
  with 16 samples. Entity and block-entity models, including animated model parts,
  and baked item/block geometry use per-object motion vectors.

The roadmap has five phases:

| Phase | Scope | Status |
| --- | --- | --- |
| One | Vanilla FXAA and shared extension points | Implemented; see the verification record for coverage |
| Two | Vanilla SSAA and SMAA 1x | Implemented; verification details and limits are recorded in the plan |
| Three | Iris / Sodium integration | Implemented for the versions and support boundaries below |
| Four | TAA | Implemented and accepted within the documented scope: reprojection, object motion and five sampling sequences; active Iris shaders pause TAA |
| Five | Temporal particles, weather and world effects | Designed, not implemented: independent layer histories, effect motion and compatibility with vanilla order-independent transparency; see [the full architecture](docs/plan/TEMPORAL_EFFECTS_PLAN_ZH.md) |

Rendering verification covers vanilla, Sodium, and Iris 1.11.4 with the support
boundaries below. TAA works without active shader packs; its separate runtime
verification and limitations are recorded in section 16 of the implementation plan.

Select a mode in Options → Video Settings → Anti-Aliasing. It takes effect on the
next world frame and persists in the vanilla `options.txt` as `antialiasing`:
OFF = 0, FXAA = 1, SMAA = 2, TAA = 3 and SSAA = 4.
Missing or invalid values fall back to OFF. Selecting a graphics preset also selects
its AA mode: Fast → FXAA, Fancy → SMAA, Fabulous → TAA. Manually changing the AA
mode switches the graphics preset to Custom.

### FXAA configuration

On Fabric, install **Mod Menu** and open Mods → Anti Aliasing Mod → Configure.
On NeoForge, use the built-in mod list's configuration button. Both open the
same screen with an AA mode selector and live FXAA controls.

With Sodium installed, open Options → Video Settings → Anti Aliasing Mod →
Anti-Aliasing. This opens the same configuration screen and returns to Sodium
when closed. This entry also works alongside Iris and does not require Mod Menu.
Available FXAA controls:

| Parameter | Range | Default |
| --- | --- | --- |
| Subpixel blending | 0–1 | 0.7500 |
| Relative edge threshold | 0–1 | 0.1660 |
| Minimum luma threshold | 0–1 | 0.0833 |
| Edge search steps per direction, including the first sample | 1–5 | 5 |

Tooltips explain the image quality and cost tradeoffs. Each parameter has its own
Reset button, visible only when its value differs from the default. Resetting
changes only that option and hides its button. A parameter is centered on its own
when Reset is hidden; otherwise the parameter and Reset are centered together.
The top AA mode selector is always centered and has no reset button. Its default is OFF.
Changes apply while FXAA is running without a restart or
resource reload, and persist with vanilla options when the screen closes.
The keys are `aamod_fxaa_subpixel`, `aamod_fxaa_edge_threshold`,
`aamod_fxaa_minimum_threshold`, and `aamod_fxaa_search_steps`. Missing, non-finite,
or out-of-range values fall back to each parameter's default. Mod Menu is optional
for the mod itself; without it, the vanilla video settings AA mode still works.

FXAA can soften fine details and does not provide temporal stabilization.
The same configuration screen includes SSAA scale and SMAA quality. These persist
as `aamod_ssaa_scale` (indices 1–4 for 1.5× / 2× / 3× / 4×) and
`aamod_smaa_quality` (0–3 for Low / Medium / High / Ultra). Missing or invalid
values restore 2× and High respectively. Each also has an individual reset button.
SSAA pixel and attachment costs grow with the square of the scale; an unsupported
texture size or rendering failure preserves the requested setting and reports a
failure instead of silently selecting another algorithm. Reload resources or
change mode to retry. SMAA upstream provenance and license are bundled under
`assets/aamod/licenses/`.

Agent-run client checks use OpenGL. The user has verified AA on native Vulkan
with both vanilla rendering and Sodium. Iris is inactive on that backend, so
its optional API is not queried and its shader policies do not block native AA.
See [the implementation plan and verification record](docs/plan/AA_IMPLEMENTATION_PLAN_ZH.md)
for stage boundaries and follow-up work.

### TAA configuration and limits

The shared configuration screen and vanilla Video Settings quality section expose
TAA Jitter Sequence and TAA Jitter Samples. Sequence choices are Halton (2, 3),
Hammersley, Owen-scrambled Sobol, Rank-1 / R2 and a fixed progressive Poisson table.
Every sequence supports 8, 16 and 32 samples. Defaults are Halton and 16; shared
configuration controls have individual reset buttons.

Options persist as `aamod_taa_sequence` (0–4 in the order above) and
`aamod_taa_samples` (8 / 16 / 32). Missing or invalid values restore 0 / 16.
Changing either option clears history. Mode, size, projection/FOV, camera cut,
world/dimension, resource reload and external pipeline changes also reset history.
Only TAA jitters world rendering; culling uses a stable one-pixel guard margin.

TAA Post-Sharpening is adjustable from 0 to 1 in both configuration screens,
with an individual reset button in the shared screen. The default is 0 (off),
persisted as `aamod_taa_sharpness`; missing or invalid values restore 0.
It sharpens resolved world output with local contrast and overshoot limits.
Changing strength takes effect on the next world frame without clearing history;
sharpened output never feeds back into temporal history. Held items and GUI are unaffected.

F3 options include `taa_diagnostics` under Anti Aliasing Mod. The native Never,
In Overlay and Always On controls determine its visibility and persist in
`debug-profile.json`; the default debug profiles show it in the F3 overlay.
It reports TAA state, history epoch, submitted history count, jitter frame index,
sharpening, the last reset reason, captured vertices, matched/rejected geometry
batches and capture budget status. Submitted history and matched batches are CPU counts and do
not measure how many pixels accept history. It does not use the action bar.

TAA reconstructs static camera motion from current/previous matrices and world
depth. Captured entity and block-entity model parts and vanilla baked item/block geometry
use corresponding previous vertices for translation, rotation, scaling and model
animation. A separate motion pass checks world depth and texture alpha before
TAA consumes its vectors. New, unmatched, translucent and custom captured geometry
rejects history. FRAPI/Indigo and Sodium extended item/block meshes also receive
rejection coverage; precise vertex history for those meshes is not implemented.
ModelPart animation retains precise correspondence with Sodium. Particles, weather and other uncaptured effects retain depth/color
rejection; residual ghosting or loss of accumulation remains possible. Sky pixels
use the current frame. Held items, HUD, menus and later screen effects are not
accumulated. TAA does not increase render resolution or combine with SSAA.
History and the RGBA32F motion texture cost 44 bytes per output pixel, about
87.0 MiB at 1080p and 348.0 MiB at 4K, excluding driver overhead and vertex/uniform/query
buffers. Capture is limited to 262,144 vertices and 8,192 batches per frame;
exceeding either budget disables history blending for that frame.
See [the motion-vector implementation plan](docs/plan/OBJECT_MOTION_VECTORS_PLAN_ZH.md) for
the reference revision, coverage and validation evidence.

### Iris shader compatibility

The Iris section, policy control and live status are shown only when Iris is
installed. Without Iris, the section and Iris-specific mode tooltip are hidden.
The policy has an individual reset button and defaults to Automatic; this pauses
this mod's AA with active shaders and does not enable the shader pack's own AA.

The shared configuration screen includes **Shader Pack Anti-Aliasing**:

- **Automatic** is the default. While an Iris shader pack is in use, this mod
  pauses its AA and keeps your requested mode. Shader pack settings are unchanged.
- **Apply after shaders** enables FXAA or SMAA after Iris final color conversion
  and vanilla world effects, before the HUD. It can duplicate the pack's own AA.
- **SSAA and TAA with active shaders are unsupported** and pause with a reason.
  This also applies to TAA under Apply after shaders: the adapter cannot coordinate
  the pack's jitter, depth and history. Without active shaders, all four algorithms
  work normally, including when Iris is installed.

The OpenGL shader adapter supports **Iris 1.11.4**, sRGB output and an RGBA8 main
target. On that path, other Iris versions, other output color spaces, missing
final output, and API failures pause processing with an explicit status.
On native Vulkan, the mod checks the actual device backend before loading the
Iris adapter, skips Iris API/version checks and uses the native rendering path.
An installed but inactive Iris therefore does not block AA. Shadow passes are excluded.
Shader pack changes and pipeline rebuilds invalidate this mod's resources;
pipeline preparation also pauses processing to protect first-frame resolution.
The policy persists as `aamod_iris_policy` (0 = Automatic, 1 = Apply after shaders).
Missing or invalid values restore Automatic. The status updates in the config
screen; the AA mode tooltip includes the current pause reason.

Runtime checks use Complementary Reimagined r5.9.3 on both loaders. NeoForge's
development-only graphics validation exposes an Iris vertex-format array bug
that also reproduces without this mod. NeoForge shader checks therefore use
the loader property `neoforge.disableGlValidation=true` for phase-four validation,
as does `runClientWithIris`; the global IDE flag stays unchanged. Phase-three
validation used a temporary production-mode fixture. See the verification record
for evidence and the separate development-client shutdown limitation.

## Workspace layout

```
.
├── build.gradle              # root: Architectury plugin, shared Java/Kotlin config, metadata expansion
├── settings.gradle           # plugin repos + include common / fabric / neoforge
├── gradle.properties         # ALL versions and mod metadata live here
├── common/                   # loader-agnostic code + shared resources
│   └── src/main/
│       ├── kotlin/           # Kotlin code and mixins, AA renderer, PlatformHelper
│       └── resources/        # mixin configs, assets/aamod/lang, icon
├── fabric/                   # Fabric entrypoints, fabric.mod.json
│   └── src/main/
│       ├── kotlin/           # AAModFabric, AAModFabricClient, PlatformHelperImpl
│       └── resources/
└── neoforge/                 # NeoForge entrypoints, neoforge.mods.toml
    └── src/main/
        ├── kotlin/           # AAModNeoForge, AAModNeoForgeClient, PlatformHelperImpl
        └── resources/META-INF/
```

Rules of thumb:

- **Never** import `net.fabricmc.*` (except annotations) or `net.neoforged.*`
  from `common/`. Loader-specific code belongs in `fabric/` or `neoforge/`.
- Shared assets, lang files and mixin configs live in `common/src/main/resources`.
  They are bundled into both platform jars by the `shadowCommon` configuration.
- `fabric/build.gradle` and `neoforge/build.gradle` consume two variants of
  `common`: a plain project reference (compile/dev runtime) and
  `transformProductionFabric` / `transformProductionNeoForge` (packaged output).
- Platform differences go through `@ExpectPlatform`: declare a static stub in
  `common/.../PlatformHelper.kt`, then implement
  `com.nts.aamod.platform.fabric.PlatformHelperImpl` (Fabric) and
  `com.nts.aamod.platform.neoforge.PlatformHelperImpl` (NeoForge) with matching
  signatures. A static forwarding bridge preserves the existing NeoForge package.
  Architectury rewrites the stub bodies at build time to delegate
  to the platform sub-package, while callers keep referencing the stub.

## Requirements

| Item | Value |
| --- | --- |
| JDK | 25 (Gradle compiles with `--release 25`) |
| Minecraft | 26.2 |
| Gradle | 9.7.1 (wrapper included) |
| Architectury Loom | 1.17-SNAPSHOT, **`dev.architectury.loom-no-remap`** |
| architectury-plugin | 3.5-SNAPSHOT |

The local default JDK 27 remains unchanged. Running the wrapper with JDK 27
reports its version successfully, but the actual build configuration fails in
Groovy with `Unsupported class file major version 71`. Builds and client checks
currently use a process-local JDK 25. Java release, Kotlin JVM target, and Kotlin
JDK API release remain 25. In PowerShell, set `$env:JAVA_HOME` to your JDK 25
installation before invoking the wrapper.

> **Why `loom-no-remap`?** Since Minecraft 26.1 the game jar is no longer
> obfuscated, so Mojang does not publish mappings for 26.2. The regular
> `dev.architectury.loom` still requires a non-empty `mappings` configuration
> and will fail with `Failed to find official mojang mappings for 26.2`.
> The `loom-no-remap` variant skips remapping entirely: no `mappings`
> dependency, no `remapJar`, no `mod*` dependency prefixes, and `namedElements`
> is replaced by a plain `project(":common")` reference.
> `shadowJar` becomes the final artifact and `jar` is marked `-raw`.

## Build

```bash
./gradlew build                 # both loaders
./gradlew :fabric:build         # Fabric only
./gradlew :neoforge:build       # NeoForge only
./gradlew :fabric:runClient     # Fabric dev client
./gradlew :neoforge:runClient   # NeoForge dev client
```

Final jars land in `fabric/build/libs/` and `neoforge/build/libs/`, named
`aamod-fabric-<version>.jar` / `aamod-neoforge-<version>.jar`. The
`*-raw.jar` intermediates are not distributable artifacts.

> Tip: add/remove loaders by editing `enabled_platforms` in `gradle.properties`
> and adding the matching `include` in `settings.gradle`.
>
> **Two gotchas:** (1) `org.gradle.configuration-cache` must stay `false` —
> Architectury's `TransformingTask` holds a Gson instance that Gradle cannot
> serialize into the configuration cache. (2) The NeoForge module needs
> `maven.neoforged.net`; to build Fabric alone without configuring it, use
> `./gradlew :fabric:build --configure-on-demand`.

## Runtime dependencies

| Loader | Required mods |
| --- | --- |
| Fabric | Fabric API, fabric-language-kotlin, Architectury API |
| NeoForge | Kotlin for Forge, Architectury API |

**Sodium and Iris are optional.** The integration targets Sodium 0.9.2 and
Iris 1.11.4 for Minecraft 26.2 on both loaders. Install their loader-specific
jars separately; neither mod nor the Sodium API is bundled in the AA mod jar.
The video settings integration uses Sodium's public Config API. Rendering support
is described in the Iris compatibility section; it does not include every shader
pack, graphics backend or future Iris version.

Development clients omit both mods by default. Versions and switches live in
`gradle.properties`; enable them per invocation (use `gradlew.bat` on Windows):

```bash
./gradlew :fabric:runClient -Penable_sodium=true
./gradlew :fabric:runClient -Penable_iris=true
./gradlew :neoforge:runClient -Penable_sodium=true
./gradlew :neoforge:runClient -Penable_iris=true
```

To launch with Iris and Sodium without specifying properties, use
`./gradlew :fabric:runClientWithIris` or `./gradlew :neoforge:runClientWithIris`.
These tasks inherit the regular client configuration and add both mods only to
their own runtime classpath. Generated IntelliJ configurations invoke the Gradle task.
The NeoForge task also sets `neoforge.disableGlValidation=true` to avoid the Iris
development draw-validation crash. Regular `runClient` keeps its validation settings.

`enable_iris=true` also adds Sodium, which Iris requires. These switches affect
only the development runtime, not the dependencies bundled in published jars.

Kotlin stdlib is **not** bundled: `fabric-language-kotlin` provides it on Fabric
and `localRuntime("thedarkcolour:kotlinforforge-neoforge:...")` provides
it in the NeoForge dev runtime. Entrypoints are written in Kotlin; Fabric uses
its default constructor entrypoints and NeoForge uses JavaFML.

If you do not need Architectury's unified events/registry/network API, you can
drop the `dev.architectury:architectury*` dependencies and the `architectury`
entries in `fabric.mod.json` / `neoforge.mods.toml`; the Architectury **build
toolchain** and `@ExpectPlatform` still work without the runtime API.

## Metadata templating

`fabric.mod.json` and `META-INF/neoforge.mods.toml` are expanded by
`processResources` using values from `gradle.properties` (see the `metadata`
map in the root `build.gradle`). Use `mod_*` keys — plain `name` / `group`
collide with Gradle's built-in `Project.name` / `Project.group`.

## License

GPL-3.0. See [LICENSE](LICENSE).
