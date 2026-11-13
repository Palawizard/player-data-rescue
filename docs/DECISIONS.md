# Decisions

Architecture decision records for Player Data Rescue. Each record has a status: Proposed, Accepted or Superseded. New records are added at the end. A decision that changes gets a new record, and the old one is marked "Superseded by ADR-x".

## ADR-001: Build layout

Status: Accepted.

The project reuses the layout of Pala's Item Clear: one standalone Gradle project per band and loader under `versions/`, a `versions/version-matrix.json` file shared by the build, smoke and release tooling, and per-band Gradle wrappers. Unlike Pala's Item Clear, the 1.20.1 band is also a set of standalone projects instead of an Architectury Loom root.

The shared logic lives in a single pure-Java core that every band includes. Shared logic is never copied into a band.

How the bands include the core (settled in M0): `core/` is a standalone Gradle project with its own wrapper, where the JUnit tests and the formatting check run on Java 17. Each band adds `core/src/main/java` as a second source directory of its `main` source set, through `versions/shared/band.gradle`, and compiles it with the band's own Java release. The core classes end up in the band's JAR next to the loader code. `mod_version` and the other mod properties are read from the root `gradle.properties` by the core and by every band.

Why: the layout already ships 21 artifacts for Pala's Item Clear, with working smoke and release tooling, and avoiding Architectury keeps the runtime dependency list empty (ADR-003).

Why a source directory and not a subproject or a core JAR: the bands are separate Gradle builds on different Gradle versions (8.14 for ForgeGradle 6, 9.x for the others), so a shared subproject would need a composite build across those versions. A source directory couples nothing at build time. It also avoids jar-in-jar, shading and package relocation: each JAR holds one copy of the core, compiled for the band's Java version, and nothing else to load. The core's own build enforces this: it compiles with `--release 17` and has no Minecraft or loader class on its classpath, so a Minecraft import in the core fails there before any band builds.

## ADR-002: Pure core, thin loader code

Status: Accepted.

Logic that does not need Minecraft lives in the pure core and is unit tested. Loader code only adapts lifecycle events, commands, configuration paths and server access. Version differences stay inside the band that needs them.

## ADR-003: No runtime dependencies

Status: Accepted.

The mod ships without Architectury API, without configuration libraries and without Fabric API. It only uses the loader and the libraries bundled with Minecraft (Gson, SLF4J, and LZ4 on versions that bundle it). A library bundled inside the JAR (jar-in-jar) does not count as a runtime dependency, but each one needs its own record.

Why: a server owner or modpack maker can add the mod without pulling in anything else.

## ADR-004: Mojang names

Status: Accepted.

Every band uses Mojang official names, with Parchment allowed on obfuscated versions. Yarn is not used.

Why: from 26.1 Fabric only uses Mojang names, and a single naming scheme keeps the shared code identical across loaders.

## ADR-005: Configuration

Status: Accepted.

One file, `config/playerdatarescue.json`, read and written with the Gson bundled with Minecraft. Unknown keys are kept, missing keys get defaults. No in-game configuration screen.

## ADR-006: License

Status: Accepted.

MIT, so any modpack can include and redistribute the mod.

## ADR-007: Releases

Status: Accepted.

The version lives in `gradle.properties`, together with `CHANGELOG.md` and `.github/release-notes/v<version>.md`. `dev` is fast-forwarded to `main`, and pushing a `v<version>` tag creates the GitHub release and publishes to Modrinth and CurseForge. Both steps only happen on the maintainer's request. Details are in [release-checklist.md](release-checklist.md).

## ADR-008: Publishing tool

Status: Accepted.

`Kir-Antipov/mc-publish@v3`, one file per band and loader, followed by a job that sets the Modrinth environment through the API, as on Pala's Item Clear.

## ADR-PDR-001: Sanitize and retry instead of reimplementing player loading

Status: Accepted.

The mod never reimplements vanilla or loader player loading. It removes failing units from a copy of the saved tag and lets the normal load run on it.

Why: reimplementing loading for every band, including each loader's attachment system, would drift from vanilla behaviour.

## ADR-PDR-002: MixinExtras through jar-in-jar if needed

Status: Proposed.

If a clean guard needs `@WrapOperation`, MixinExtras is bundled jar-in-jar on every loader instead of relying on the loader to provide it.

Why: the guard must behave the same on Forge 1.20.1, where MixinExtras is not guaranteed, and jar-in-jar adds no dependency for users (ADR-003).

## ADR-PDR-003: Server-side only

Status: Accepted.

No client code, no networking, and only vanilla text components in messages. The metadata declares the mod optional on clients.

Why: any server can use it, and it still works in singleplayer through the integrated server.

## ADR-PDR-004: Failures that do not come from the data are diagnosed, not rescued

Status: Accepted.

When placement fails even with the minimal profile, the mod restores the vanilla outcome and writes a diagnosis report. A key that keeps failing becomes one recurring issue instead of a new incident at every login.

Why: on 26.3 and 1.21.11, reported cases include errors that remain after deleting both player files, or that appear on a brand new world. No saved data can fix those. Retrying would loop, and a new incident at every login would bury the useful report.

## ADR-009: Gradle JVM per project

Status: Accepted.

Each project in `versions/version-matrix.json` (every band and loader, and the core) has a `gradleJava` field: the Java version Gradle itself runs on. It is separate from `java`, the release the band compiles for. The build scripts and the CI set `JAVA_HOME` to that version before calling the project's wrapper, and the scripts refuse a Gradle 8.x project with a `gradleJava` above 24.

Current values: 21 for the ForgeGradle 6 bands (Forge 1.20.1 and 1.21.1, on Gradle 8.14), 21 for the core, and 25 for every other band (Gradle 9.x).

Why: running every project on one Java version does not work. Gradle 8.14 runs on Java 24 at most, and ForgeGradle 6 needs Gradle 8.x. Fabric Loom 1.18 and later declare that Gradle must run on Java 25, and from Minecraft 26.1 Fabric requires Java 25 for the Gradle JVM. A single Java 21 runtime would have kept the Fabric bands on Loom 1.17 and blocked any Minecraft version that needs a newer Loom.

## ADR-010: Declared Minecraft range

Status: Accepted.

Each band records two ranges in `versions/version-matrix.json`. `bandRange` is every Minecraft patch the band is meant to cover. `declaredRange` is what the band's metadata (`fabric.mod.json`, `mods.toml`, `neoforge.mods.toml`) actually accepts, through `minecraft_version_range` in the band's `gradle.properties`. `declaredRange` starts at the compile target and grows toward `bandRange` one patch at a time, as each patch passes the production-JAR smoke test on every loader of the band. `scripts/build-all-version-bands.ps1` fails when a band's `gradle.properties` and the matrix disagree.

Consequence: until the other patches are smoke tested, a release only installs on the compile target of each band (1.20.1, 1.21.1 and 26.3 for the Tier A bands).

Why: a loader refuses a JAR outside its declared range, which is a clear message for the player. Declaring an untested patch instead risks a crash or, worse for this mod, a silent failure on a version it was never run on.

## ADR-PDR-005: Each load attempt uses a new ServerPlayer

Status: Accepted.

A rescue never runs the load a second time on the `ServerPlayer` whose load failed. Each attempt (salvaged data, then the minimal profile) builds a new `ServerPlayer` the way the band's login flow does, and the failed instance is dropped before it is added to the player list or a level:

- 1.20.1: `new ServerPlayer(MinecraftServer, ServerLevel, GameProfile)`. Not `PlayerList#getPlayerForLogin`, which also disconnects any online player with the same UUID.
- 1.21.1: `new ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)`, as `PlayerList#getPlayerForLogin` does.
- 26.3: the same constructor, as `PrepareSpawnTask$Ready#spawn` does. The task stays in its ready state after a spawn, so the spawn level and position chosen in configuration are reused.

Why: checked in M1 in the sources of every Tier A band and loader (ARCHITECTURE.md, Hook points). `Entity#load` sets fields one by one, so a load that throws part-way leaves a mixed state. Several parts add to what is already there instead of replacing it: active effects on 1.20.1 and 1.21.1, attribute modifiers, the recipe book, NeoForge attachments (added or replaced per key, never cleared) and Forge capabilities (changed in place). Loading the salvaged copy on top of that would keep parts of the data that was just set aside. A new instance also gets fresh Forge capabilities, because Forge attaches them in the `Entity` constructor.

Consequences:

- The read happens before the player joins the player list or a level on every band, so dropping the failed instance leaves nothing behind. Stats and advancements are cached per UUID in `PlayerList` and are shared by the new instance, which is what the login flow does too.
- Construction events (`EntityConstructing` on Forge and NeoForge, `AttachCapabilitiesEvent` on Forge) fire once per attempt. Mods see one more construction, as when a player reconnects.
- A failure inside `PlayerList#placeNewPlayer` (T3) can happen after the player was added to the player list. Cleaning up that case before the retry is not checked yet: M2 must cover it with the test mod's placement failure.

## ADR-PDR-006: Headless player join in the smoke test

Status: Accepted.

The production-JAR smoke test joins real players through `testmod/join-bot/JoinBot.java`, a small protocol client written for this project. It runs with the JDK's single-file source launcher (`java JoinBot.java`), has no dependency and is never part of a mod JAR (`check-release-artifacts.ps1` refuses any test file in a release JAR). It logs in to an offline-mode server as a vanilla client, goes through the configuration phase where the version has one, answers keep-alives, pings, teleports and chunk batches, stays as long as asked, and reports how the join ended. Its packet ids and layouts come from each version's decompiled sources (1.20.1) and from the server's own `--reports` packet list (1.21.1, 26.3), one profile per protocol: 763 (1.20.1), 767 (1.21.1) and 777 (26.3). A new band adds a profile. The client is pinned by the repository itself: its version is the commit.

Checked on 2026-11-13: the client joined and stayed on the real dedicated server of all eight Tier A band and loader pairs (Forge and Fabric 1.20.1; Forge, Fabric and NeoForge 1.21.1 and 26.3), with network compression on.

Options considered:

- mineflayer and node-minecraft-protocol (Node.js): their protocol data (`minecraft-data` 3.119.0 on npm, 2026-11-13) stops at 26.1, so they cannot join 26.3. They would also add Node.js and a package tree to the test setup.
- A real client run headless (HeadlessMC, mc-runtime-test): the most faithful, but it downloads and starts the full client and its assets for every band and loader, and mc-runtime-test listed versions up to 26.2.
- A fake connection created inside the test mod: no network, but it calls the placement code directly and skips the login and configuration listeners where the vanilla catch lives.

Why: only the in-house client covers every Tier A band today, and it exercises the real login path, including the `Couldn't place player in world` catch. The same client lets Chunk Pruner build fixture worlds that need a player nearby (`InhabitedTime` only grows near players), with its own copy.
