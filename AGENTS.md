# Repository Guide

<!-- GRAPHFLOW:BEGIN managed block — edit outside these markers only -->
## GraphFlow Context-First Rule

GraphFlow is a local code knowledge-graph + context compression MCP server (10 tools).
Before broad code exploration, implementation, debugging, review, or architecture
questions in this project:

1. Call `graphflow_context` with the task/query first.
2. Use the returned `summary`, `anchors`, and `tokenBudget` as the primary context.
3. Read full files only when anchors point there or compressed context is insufficient.
4. For multi-step or ambiguous work, call `graphflow_plan` before implementing.
5. After project changes, call `graphflow_index` to keep the graph fresh.
6. The flywheel auto-closes: pending episodes are backfilled by Claude Code
   hooks (or `graphflow_report_outcome`) by default; set GRAPHFLOW_AUTO_CAPTURE=0 to opt out.

Pass `rootDir` = this project's absolute path. Never pass your home directory, AppData,
or an unexpanded `${workspaceFolder}` placeholder — GraphFlow refuses unsafe workspace
roots. If a call answers `unsafe workspace root`, retry it without `rootDir`.

When using Cursor `CallMcpTool`, always pass `server` + `toolName` + `arguments`
(e.g. server `"graphflow"` / `"user-graphflow"`, toolName `"graphflow_context"`).

Tools: graphflow_context, graphflow_plan, graphflow_run, graphflow_report_outcome,
graphflow_insight, graphflow_index, graphflow_artifact, graphflow_skill_insights,
graphflow_skill_guide, graphflow_diagnose.

Do not scan the whole repository or read many large files before trying GraphFlow
context. Treat GraphFlow output as structured, token-saving context.
<!-- GRAPHFLOW:END -->

## Project and Source Layout

Anti Aliasing Mod (`aamod`) is an Architectury workspace targeting Minecraft 26.2
on Fabric and NeoForge. It uses Kotlin, including client mixins. The existing code exposes
OFF/FXAA/SMAA/TAA/SSAA video settings with persisted IDs 0/1/2/3/4. FXAA, SSAA and
SMAA 1x run through shared render mixins and `client/antialiasing/`; TAA remains
explicitly unimplemented. Iris/Sodium integration is phase three and TAA is phase
four. Phase three includes optional dependencies, the Sodium video settings entry,
and the isolated Iris 1.11.4 adapter. Automatic policy pauses AA with active
shaders; explicit overlay supports FXAA/SMAA on sRGB RGBA8 output. SSAA pauses with
active shaders. `AAModClient` is a lifecycle
entry, not the render hook.

- `common/src/main/kotlin/com/nts/aamod/`: shared initialization, client code,
  `client/option/` settings, `client/gui/AntiAliasingConfigScreen.kt`, and the
  `platform/PlatformHelper.kt` bridge.
- `common/src/main/kotlin/com/nts/aamod/client/mixin/option/`: video settings and
  vanilla Options persistence mixins. `client/mixin/render/`
  owns frame, depth-lifetime, final-color, post-chain pipeline reuse, and reload hooks.
- `common/src/main/kotlin/com/nts/aamod/client/antialiasing/`: controller, stage/input
  contracts, immutable camera snapshots, environment provider, resolution plans,
  shared spatial post chains, and `fxaa/`, `ssaa/`, `smaa/` implementations.
- `common/src/main/resources/`: shared mixin configurations and `assets/aamod/`,
  including `lang/en_us.json` and `lang/zh_cn.json`.
- `client/compat/sodium/SodiumConfigEntryPoint.kt`: shared Sodium public Config
  API registration, loaded through `sodium:config_api_user` metadata on both loaders.
  Keep this optional entry isolated from always-loaded client initialization.
- `client/compat/iris/`: lazy optional API access, immutable per-frame support,
  pipeline revisions, and final-output availability. `mixin/compat/IrisPipelineMixin`
  observes pipeline construction/destruction and completed color conversion.
  Keep Iris types out of the controller and algorithm contracts. An installed,
  enabled pack with no pipeline yet must pause preparation, especially SSAA.
- `fabric/src/main/kotlin/`: Fabric entrypoints and platform implementation;
  `fabric/src/main/resources/fabric.mod.json` declares entrypoints and dependencies.
- `neoforge/src/main/kotlin/`: NeoForge entrypoints and platform implementation;
  its current package prefix is `com.nts.aamod.aamod`. Preserve this distinction
  unless a task explicitly requires a coordinated package migration.
- Root `build.gradle`, `settings.gradle`, and `gradle.properties`: shared build
  configuration, included modules, versions, and metadata. Per-loader build files
  configure runtime dependencies and package the shared code.

Fabric exposes the shared configuration screen through an optional Mod Menu
entrypoint. NeoForge registers `IConfigScreenFactory` on its mod container. FXAA
parameters persist through vanilla Options; render code reads an immutable
snapshot and rebuilds only its own post chain when values change. SSAA scale and
SMAA presets also persist through vanilla Options. SSAA keeps the main RenderTarget
object stable because vanilla renderers cache it; attachments and dimensions swap
for world rendering and restore before GUI. Follow the existing resource reload,
failure isolation and device-size checks when extending these paths.

Use actual source files and build scripts to resolve discrepancies with the
English and Chinese READMEs; some documented Java paths describe an older layout.

## Build and Validation

Use JDK 25 and the checked-in Gradle wrapper (currently Gradle 9.7.1). Run commands
from the repository root. On PowerShell use `./gradlew.bat`; on Linux/macOS use
`./gradlew` with the same arguments.

The machine default is JDK 27. Actual Gradle script configuration under JDK 27
fails with `Unsupported class file major version 71`; use process-local JAVA_HOME
for JDK 25 without changing the system default. Keep Java and Kotlin API/bytecode
targets at 25. See `AA_IMPLEMENTATION_PLAN_ZH.md` for the verification record.

| Command (PowerShell) | Purpose |
| --- | --- |
| `./gradlew.bat build` | Build both loaders; equivalent to the CI build step. |
| `./gradlew.bat :fabric:build` | Build the Fabric module and its dependencies. |
| `./gradlew.bat :neoforge:build` | Build the NeoForge module and its dependencies. |
| `./gradlew.bat :fabric:build --configure-on-demand` | Build Fabric without configuring NeoForge. |
| `./gradlew.bat :fabric:runClient` | Launch the Fabric development client. |
| `./gradlew.bat :neoforge:runClient` | Launch the NeoForge development client. |
| `./gradlew.bat :fabric:runClient -Penable_sodium=true` | Launch with optional Sodium; also supported on NeoForge. |
| `./gradlew.bat :fabric:runClient -Penable_iris=true` | Launch with Iris and Sodium; also supported on NeoForge. |

Sodium and Iris are disabled in the default development runtime. Sodium's public
API and Iris are compile-only in common; optional runtime dependencies use `localRuntime`
in the loader modules and must not be bundled in release jars. Keep version and
enable switches in root `gradle.properties`.

NeoForge Iris 1.11.4 trips an upstream vertex-format array bug under development
graphics validation, reproduced with aamod absent. Shader runtime checks use
production conditions in the temporary validation mod; never disable the global
IDE/validation flag in shipped AA code. See plan section 15 for evidence.

Final artifacts are `fabric/build/libs/aamod-fabric-<version>.jar` and
`neoforge/build/libs/aamod-neoforge-<version>.jar`. `shadowJar` produces these;
`*-raw.jar` and `*-sources.jar` are not distributable mod jars.

There is currently no dedicated test suite or formatter configuration. For code
changes, build the affected loader; validate both loaders for shared/build changes.
For client settings or mixin changes, also verify behavior in a development client
when available: open Video Settings, cycle AA modes, and check logs for mixin errors.
Report which checks actually ran and any environment or dependency failures.
Documentation-only changes do not require launching Gradle or Minecraft.

## Implementation Conventions

- Keep loader APIs in their platform modules. Shared code must not import
  Fabric-specific APIs (apart from annotations) or NeoForge APIs.
- Route platform differences through `PlatformHelper` and provide matching members
  in both `PlatformHelperImpl` objects. Account for the existing NeoForge package
  prefix when changing this bridge.
- Keep rendering and Minecraft client references in client code. Preserve the
  loader entrypoints' separation between common and client initialization.
- Follow adjacent Kotlin/Java formatting and naming; keep Java interoperability
  annotations such as `@JvmStatic` and `@JvmField` where Java callers require them.
- Register mixins in the appropriate shared JSON configuration and keep class names,
  package names, target method signatures, and Java compatibility aligned.
- Static Kotlin mixin handlers use a private companion object and `@JvmStatic`.
  `Options.FieldAccess` uses an exact descriptor with visibility diagnostics
  suppressed; Kotlin emits `@Coerce` with visibility that Mixin does not read.
- Keep the NeoForge main source set registered in `loom.mods` so development
  launches discover mod entrypoints in the Kotlin output directory.
- Keep translation keys synchronized between `en_us.json` and `zh_cn.json`.
- Put shared dependency versions and metadata in root `gradle.properties`.
  Metadata templates use `mod_*` keys and expand through root `processResources`.
- Retain `dev.architectury.loom-no-remap`: this Minecraft version uses unobfuscated
  jars. Do not add Mojang mappings, `remapJar`, `modImplementation`, or
  `namedElements` patterns from older Loom workspaces.
- Keep `org.gradle.configuration-cache=false`; Architectury transformation tasks
  are incompatible with the configuration cache in this setup.
- Package shared code through each loader's `shadowCommon` configuration and
  `transformProductionFabric` / `transformProductionNeoForge` variant. Kotlin
  runtime support comes from Fabric Language Kotlin or Kotlin for Forge.

## Working Tree and Context Tools

Check `git status` before editing and preserve existing user changes. Avoid editing
generated output or local state in `build/`, `.gradle/`, `.kotlin/`, `run/`, `.idea/`,
and `.graphflow/`. Keep changes focused on the requested task.

When a GraphFlow tool named above is unavailable, state the limitation and use the
registered equivalent where possible (for example, `gf_read` for context queries).
Do not claim planning or indexing succeeded without a successful tool result.
