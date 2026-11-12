# Supported versions

Player Data Rescue targets every Minecraft Java release from 1.20 to the latest stable release, on Forge, Fabric and NeoForge. Each Minecraft band and loader gets its own artifact, built and tested on its own. There is no cross-version binary.

Side: server-side only; it also works in singleplayer through the integrated server, and clients never need it.

## Policy

- A band compiles against the newest Minecraft patch in its range.
- For every loader, the metadata ranges of all bands together cover every patch from 1.20 to the latest release, with no gaps or overlaps. A band may leave out a loader that another band covers.
- A band's declared range only includes patches that passed the production-JAR smoke test.
- A band is split when the APIs the mod uses differ inside its range. Bands are defined in `versions/version-matrix.json`, which the build, smoke and release tooling all read.
- Snapshots, pre-releases and release candidates are never published. A new release is ported once its loaders have stable builds.

## Bands

The starting point is the band layout of Pala's Item Clear. It will change where this mod's APIs differ.

| Band | Compiles against | Java | Forge | Fabric | NeoForge |
|---|---|---|---|---|---|
| 1.20 to 1.20.1 | 1.20.1 | 17 | 47.x (1.20 on Forge 46.x needs its own band or a decision record) | yes | 47.1 fork |
| 1.20.2 to 1.20.4 | 1.20.4 | 17 | 49.x | yes | 20.4.x |
| 1.20.5 to 1.20.6 | 1.20.6 | 21 | 50.x | yes | 20.6.x |
| 1.21 to 1.21.4 | 1.21.1 | 21 | 52.x | yes | 21.1.x |
| 1.21.5 to 1.21.8 | 1.21.8 | 21 | split: 1.21.5 (55.x) and 1.21.6 to 1.21.8 (58.x, EventBus 7) | yes | 21.8.x |
| 1.21.9 to 1.21.10 | 1.21.10 | 21 | 60.x | yes | 21.10.x |
| 1.21.11 | 1.21.11 | 21 | 61.x | yes | 21.11.x |
| 26.1 to 26.2 | 26.2 | 25 | 65.x | yes | 26.2.0.x |
| 26.3 | 26.3 | 25 | 66.x | yes | 26.3.0.x |
| 26.4 | 26.4 | 25 | not released | snapshots only | not released |

Loader versions were checked on 2026-10-31 against the Fabric meta API, the Forge Maven and the NeoForge Maven. On 2026-11-12, 26.4 was still in snapshots, and its release date had not been announced. Re-check every row before each release.

## Priority

Tier A must ship in the first release (`0.1.0`): Forge 1.20.1, Fabric 1.20.1, all three loaders on the 1.21 band, and all three loaders on the latest release. Tier B is everything else, ported from newest to oldest, and must ship before `1.0.0`.

## Toolchains

Gradle runs on Java 21, and each band compiles through a Java toolchain (17, 21 or 25). Gradle 8.12 cannot run on Java 25, so the newest bands pin their own Gradle wrapper (9.x).

## Mappings

Every band uses Mojang official names, with Parchment allowed on obfuscated versions. Yarn is not used. From 26.1 the game is unobfuscated, and Fabric no longer needs a remapping step.

## Known API boundaries

This is a map of where code usually breaks between versions. It is not a guarantee. Every class and method is checked against the decompiled sources of the band before use.

| From | Change |
|---|---|
| 1.20.2 | Login gained a configuration phase, and player placement moved to the configuration listener. |
| 1.20.5 | Item stacks use data components. Java 21. LZ4 region compression option. NeoForge renamed its metadata file to `neoforge.mods.toml` (NeoForge 20.4 still reads `META-INF/mods.toml`). |
| 1.21 | `ResourceLocation` is built through factory methods. |
| 1.21.5 | NBT getters return `Optional` or take defaults. Forge 55 predates EventBus 7. |
| 1.21.6 | Entity persistence uses `ValueInput` and `ValueOutput`. Forge moves to EventBus 7. |
| 26.1 | Unobfuscated game, Java 25. |
| 26.2 | More renames, for example the in-game HUD class. Expect renames in every drop. |
