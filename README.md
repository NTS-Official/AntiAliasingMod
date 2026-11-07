# Anti Aliasing Mod

[**English**] | [[**简体中文**](README_ZH.md)]

## Introduction

A Minecraft mod that brings anti-aliasing (FXAA / SMAA / TAA) to the default
Blaze3D rendering abstraction.

This repository is an **Architectury-based multi-loader workspace**: one shared
codebase builds a Fabric jar and a NeoForge jar.

## Workspace layout

```
.
├── build.gradle              # root: Architectury plugin, shared Java/Kotlin config, metadata expansion
├── settings.gradle           # plugin repos + include common / fabric / neoforge
├── gradle.properties         # ALL versions and mod metadata live here
├── common/                   # loader-agnostic code + shared resources
│   └── src/main/
│       ├── kotlin/           # AAMod, AAModClient, VideoAntiAliasingSettings
│       ├── java/             # mixins + PlatformHelper (@ExpectPlatform)
│       └── resources/        # mixin configs, assets/aamod/lang, icon
├── fabric/                   # Fabric entrypoints, fabric.mod.json
│   └── src/main/
│       ├── java/             # AAModFabric, AAModFabricClient, PlatformHelperImpl
│       └── resources/
└── neoforge/                 # NeoForge entrypoints, neoforge.mods.toml
    └── src/main/
        ├── java/             # AAModNeoForge, AAModNeoForgeClient, PlatformHelperImpl
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
  `common/.../PlatformHelper.java`, then implement
  `com.nts.aamod.platform.fabric.PlatformHelperImpl` (Fabric) and
  `com.nts.aamod.aamod.platform.neoforge.PlatformHelperImpl` (NeoForge) with matching
  signatures. Architectury rewrites the stub bodies at build time to delegate
  to the platform sub-package, while callers keep referencing the stub.

## Requirements

| Item | Value |
| --- | --- |
| JDK | 25 (Gradle compiles with `--release 25`) |
| Minecraft | 26.2 |
| Gradle | 9.7.1 (wrapper included) |
| Architectury Loom | 1.17-SNAPSHOT, **`dev.architectury.loom-no-remap`** |
| architectury-plugin | 3.5-SNAPSHOT |

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

Kotlin stdlib is **not** bundled: `fabric-language-kotlin` provides it on Fabric
and `forgeRuntimeLibrary("thedarkcolour:kotlinforforge-neoforge:...")` provides
it in the NeoForge dev runtime. Entrypoints are plain Java classes, so no Kotlin
language adapter is needed in the mod metadata.

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
