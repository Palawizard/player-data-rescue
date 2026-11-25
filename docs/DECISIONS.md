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

Amended on 2026-11-16: M2 covers that case. The half-placed player is removed and never saved, and the connection is put back before the next attempt (ADR-PDR-010).

## ADR-PDR-006: Headless player join in the smoke test

Status: Accepted.

The production-JAR smoke test joins real players through `testmod/join-bot/JoinBot.java`, a small protocol client written for this project. It runs with the JDK's single-file source launcher (`java JoinBot.java`), has no dependency and is never part of a mod JAR (`check-release-artifacts.ps1` refuses any test file in a release JAR). It logs in to an offline-mode server as a vanilla client, goes through the configuration phase where the version has one, answers keep-alives, pings, teleports and chunk batches, stays as long as asked, and reports how the join ended. Its packet layouts come from each version's decompiled sources, and its packet ids from the decompiled sources (1.20.1) or the server's own `--reports` packet list (1.21.1, 26.3), one profile per protocol: 763 (1.20.1), 767 (1.21.1) and 777 (26.3). A new band adds a profile. The client is pinned by the repository itself: its version is the commit.

Checked on 2026-11-13: the client joined and stayed on the real dedicated server of all eight Tier A band and loader pairs (Forge and Fabric 1.20.1; Forge, Fabric and NeoForge 1.21.1 and 26.3), with network compression on.

Amended on 2026-11-16: the client also reports its position after each teleport and then every second, and on 26.3 it ends each of those client ticks with `ServerboundClientTickEndPacket`. Without the report, 1.20.1 and 1.21.1 keep tracking a teleported player at its join position, so the destination does not stay loaded; on 26.3 a second position packet in one client tick disconnects the client. The same change was first made in the copy of this client kept by Chunk Pruner. The smoke test checks it on every band by teleporting the client 1000 blocks away and filling blocks under it ([TESTING.md](TESTING.md)).

Options considered:

- mineflayer and node-minecraft-protocol (Node.js): their protocol data (`minecraft-data` 3.119.0 on npm, 2026-11-13) stops at 26.1, so they cannot join 26.3. They would also add Node.js and a package tree to the test setup.
- A real client run headless (HeadlessMC, mc-runtime-test): the most faithful, but it downloads and starts the full client and its assets for every band and loader, and mc-runtime-test listed versions up to 26.2.
- A fake connection created inside the test mod: no network, but it calls the placement code directly and skips the login and configuration listeners where the vanilla catch lives.

Why: only the in-house client covers every Tier A band today, and it exercises the real login path, including the `Couldn't place player in world` catch.

## ADR-PDR-007: Forge 1.21.1 release JAR keeps Mojang names

Status: Accepted.

The Forge 1.21.1 band sets `reobf = false` in its `minecraft` block, so ForgeGradle 6 does not reobfuscate the release JAR to SRG.

Why: Forge 52 runs on Mojang names (`server-1.21.1-official.jar`), but ForgeGradle 6 reobfuscates by default. A reobfuscated JAR calls `m_...` methods that do not exist at runtime: the M1 test mod failed with `NoSuchMethodError`. Forge's own 1.20.6 MDK (Forge 50, ForgeGradle 6.0.24 and later) sets the same flag, with the comment that Forge now uses official mappings at runtime. Checked on 2026-11-16: the release JAR with the reobfuscation failed in the smoke test as soon as it called the game, and passed with `reobf = false`. Forge 1.20.1 (47.x) runs on SRG and keeps the reobfuscation.

## ADR-PDR-008: Code shared between bands

Status: Accepted.

Code that is the same for several bands is compiled into each of them from shared source folders, like the core (ADR-001), and never copied:

- `versions/shared/java`: every band. The rescue orchestration (`RescueService`), the data source of a player file, the player and operator messages, and the mixin owner lookup.
- `versions/shared/<loader>/java`: the bands whose loader API is the same, chosen by `shared_loader` in the band's `gradle.properties`. `fabric` (Fabric Loader 0.15 and later) and `forge` (Forge 47 and 52). Forge 66 (26.3) has a static `ModList` and NeoForge 21.1 and 26.3 read mod files differently, so those bands keep their loader bridge in their own folder.
- `versions/common-<minecraft>/java`: every loader of one Minecraft version. The game calls of a join (`GameJoin`), the host data source, and the mixins whose targets and descriptors are the same on every loader of that version.

A mixin that differs on one loader of a version lives in that band's folder and replaces the shared one in the band's mixin config: NeoForge builds its login cookie with `createCookie(ClientInformation, ConnectionType)`. The shared folders are formatted by `versions/build.gradle` (`core/gradlew -p versions spotlessCheck`).

Why: the code of the three loaders of a Minecraft version calls the same game methods, and the orchestration calls none. One copy per version keeps the bands from drifting apart. A shared folder still compiles against each band's own game and loader, so a difference shows up as a build failure of that band.

## ADR-PDR-009: Plain Mixin injectors, no MixinExtras

Status: Accepted.

The mod uses only Mixin's own injectors and accessors: `@Inject` (with `cancellable` and, for the placement guard, `LocalCapture.CAPTURE_FAILHARD`), `@ModifyVariable`, `@Invoker` and `@Accessor`. MixinExtras is not needed, and ADR-PDR-002 stays unused.

- Placement guard: an `@Inject` at the `Logger.error` call inside the catch of the login handler (`ServerLoginPacketListenerImpl#handleAcceptedLogin` on 1.20.1, `ServerConfigurationPacketListenerImpl#handleConfigurationFinished` on 1.21.1 and 26.3). The caught exception is a captured local. When the rescue lets the player in, the handler cancels, so the game neither logs "Couldn't place player in world" nor disconnects. Otherwise the game goes on as without the mod. A second `@Inject` at the handler's `RETURN` finishes a T2 incident once the player is in.
- T2: an `@Inject` at the `Logger.warn` call of the player file read that fails (`PlayerDataStorage#load`), and on 1.20.1 a `@ModifyVariable` on the tag the read returns, to load the `.dat_old` the game does not try on that version.
- Persistence and cleanup: an `@Invoker` for `PlayerList#save`, an `@Inject` at the head of `PlayerList#save` that skips the player instances of failed attempts, and an `@Accessor` for the private netty channel of `Connection`, to put the connection back before another placement (ADR-PDR-010).

Amended on 2026-11-24: a cancellable `@Inject` at the head of `Connection#send` and two `@Inject` before and after the placement call of the login handler hold the packets of a placement until it worked, so the client never sees a failed one (ADR-PDR-012).

On Forge 1.20.1, which runs on SRG names, the mixin annotation processor of MixinGradle 0.7.38 (`org.spongepowered:mixin:0.8.5:processor`) writes `playerdatarescue.refmap.json` from the Mojang names in the source. The other bands run on Mojang names (Forge 1.21.1 since ADR-PDR-007) or are remapped by Loom, and need no refmap.

Why: an injection inside the catch wraps the whole placement, whatever throws in it, without changing the call itself, so it does not conflict with other mods that redirect or wrap `placeNewPlayer`. A `@Redirect` of that call would. `CAPTURE_FAILHARD` makes a changed local layout fail when the class loads, which the production-JAR smoke test of every band would show, rather than skip the guard.

## ADR-PDR-010: Attempts go through the game's own read

Status: Accepted.

Each rescue attempt (ADR-PDR-005) writes its candidate data where the game reads the joining player's data, then lets the game read it: the player file `<uuid>.dat` (dedicated server, LAN guest, and the 26.3 host), or on 1.20.1 and 1.21.1 the host's `Data.Player` tag that the game keeps in memory after loading `level.dat`. The original files are backed up first (SPEC.md step 1), and a candidate equal to the original is not written. When the failure is not data-related, the original is written back, so nothing is changed.

A placement that fails after the game added the player (T3) leaves a half-placed player in the player list and the level. Before the next attempt, that player is removed with `PlayerList#remove`, the game's own leave path, and every player instance of a failed attempt is marked so that `PlayerList#save` skips it: neither that removal nor the game's later disconnect saves its half-loaded state over the data being rescued. The connection is put back too, since the game never places a player twice on one connection: on 1.20.1 Forge's packet filters are removed, and on 1.21.1 and 26.3 a handler runs the protocol setup task of the next placement in place of the one the end of configuration left (ARCHITECTURE.md, Cleanup after a failed placement). Checked on 2026-11-16: without this, the second placement failed on every Tier A band except Fabric 1.20.1.

Why: the game's read path includes the data fixers and the loaders' own events around the read (Forge's and NeoForge's player loading events, which Forge 1.21.1 fires inside the read itself). Feeding the tag through any other way would skip some of them on some band. On 26.3, Forge and NeoForge fire that event outside the read, in `PrepareSpawnTask$Ready#spawn`, so an attempt that only loads fires it itself, with the same arguments. Writing the file costs a few small writes on a rare event, and the backup is already on disk.

## ADR-PDR-011: Real client checks through portablemc

Status: Accepted.

The checks that need the real game client (whether the client survives a T3 retry on the same connection, and the player message and the operator notice as the client shows them) run the client with portablemc 5.0.6, a command-line launcher, with an offline account. `scripts/real-client.ps1` holds the launcher calls, and `prod-smoke.ps1 -RealClient` replays S2, S15 and S11 with the vanilla client of the band on its dedicated server, with a vanilla operator client online. The launcher is pinned by version: the scripts refuse any other `portablemc --version`. It is installed by hand outside the repository and found through `-Launcher` or `PDR_PORTABLEMC`, and it never goes into a mod JAR. The client opens a game window, so these checks run on a desktop session, not in CI.

Options considered, checked on 2026-11-24:

- HeadlessMC (headlesshq/headlessmc): its latest release is 3.0.0-RC9, a pre-release, and its README limits offline accounts to running the game headless in CI pipelines.
- portablemc (mindstorm38/portablemc): release 5.0.6 of 2026-10-18, a single binary for Linux, macOS and Windows, signed with the project's PGP key. Offline accounts are a plain option (`-u <name>`), it installs the vanilla client and the Fabric, Forge and NeoForge clients of every Tier A band, and it starts the game straight into a server (`--join-server`) or a singleplayer world (`--join-world`) through the game's quick play arguments. It reports the game's process id (`launched <pid>` with `--output machine`), so a script can close that window and no other.

Why: portablemc covers the three Tier A Minecraft versions and the three loaders with a stable release, and offline accounts are a supported use. The window is closed the way a player closes it (the window's close message on Windows, Hyprland's window close dispatcher on Linux, checked on Hyprland 0.57.2), so the game stops as it would for a player. Elsewhere the script sends the game `SIGTERM` instead.

## ADR-PDR-012: The T3 retry stays on the same connection, and the client only sees the placement that worked

Status: Accepted.

When a placement fails because of the data (T3), the rescue places the minimal profile on the same connection, right away, as ADR-PDR-010 describes. The connection holds every packet a placement sends until the placement returns, and drops the packets of a placement that failed, so the client gets one login packet: the one of the placement that worked. The other way, decided on 2026-11-24 in case a real client could not take the retry, was a clean disconnect followed by the rescue at the next join.

Checked on 2026-11-24 with the vanilla client of each Tier A band, started by portablemc (ADR-PDR-011), on S15 (SPEC.md): a placement that fails only when the saved data holds the test mod's attachment.

- Without the hold, the client got the login packet of the failed placement, then the one of the minimal profile. The vanilla client of 1.20.1, 1.21.1 and 26.3 makes a new level at each login packet but keeps its local player, which stays in the first level, where no chunk ever arrives. The player stopped moving: lifted 3 blocks by RCON after the rescue, the vanilla 1.21.1 client stayed in the air, and on screen its position shook. The client also confirmed the teleports of both placements, and the new game listener disconnected it with "Invalid move player packet received" when a confirmation of the failed placement carried the id it was waiting for (seen on the vanilla 1.21.1 client before a first fix that moved the teleport ids, which the hold replaces).
- The removal of the half-placed player sends packets to the same connection. When the hold ended before that removal, those packets reached a vanilla 1.20.1 client that had no level yet (`NullPointerException` in `handleRemoveEntities`, on the Forge 1.20.1 server). The hold now ends after the removal.
- With the hold, on every Tier A band, the vanilla client stays connected for 30 seconds after the rescue, falls back when lifted, shows a chat message sent after that time and the player message, and the server lists it as online; the join client of S15 gets one login packet. Results per band are in TESTING.md, Real client checks.

Why: the player gets in at once, as if the first placement had worked, and the client never sees a state the game itself never produces. The disconnect-and-rejoin option would cost the player a second join for the same result. The cost on the normal path is a hold and a release around the placement call and a release in the return hook, and, for each packet the server sends while nothing is held, a callback object and one volatile read.

## ADR-PDR-013: Restores go through the next join's read

Status: Accepted.

`/pdrescue restore` only queues entries (SPEC.md section 7.1). At the player's next join, before the game reads the data, the mod backs up the source files into a new incident, puts the queued entries into the data, and lets the game's own read decode them. The join's result decides each entry's status. A value that a restored entry replaces is kept as a quarantine entry of that backup incident. Decided by the maintainer on 2026-11-24, for online and offline players alike.

The command `/pdrescue` is registered by a mixin at the end of the `Commands` constructor on every loader, since Fabric has no command event without Fabric API, and Forge and NeoForge fire theirs from that same constructor. The hook that applies a queue runs where the game is about to build and load the player: before `PlayerList#getPlayerForLogin` in the login handler on 1.20.1 and 1.21.1, and at the head of `PrepareSpawnTask#start` on 26.3, whose first read of the player file happens there, during configuration. On a join with nothing queued it is one set lookup.

Options considered:

- Restore an online player at once, through a decoder per kind of entry. Items and effects have game decoders, but a Fabric mod's key has none without Fabric API, and a Forge capability or a NeoForge attachment would need each loader's internals. ADR-PDR-001 rules out rebuilding those reads.
- Disconnect an online player so that the next join applies the restore: the same result as the chosen way, but it throws the player out. The chosen way tells them to join again instead.
- Refuse an attachment whose key the player already has, as the first SPEC said. Forge writes every capability at each save and a Fabric mod writes its key at each save, so while the mod is installed the key is always there and such a restore would always fail. The value is replaced instead and kept aside.

Why: the game's read already handles every kind of entry, with the data fixers and the loader events around it (ADR-PDR-010), so a restored entry is decoded exactly as it would have been. The backup incident keeps the data from before the restore, and the replaced values stay restorable. Since a restored entry is never queued again, restoring twice cannot duplicate items.

Amended on 2026-11-25 (the set-aside case decided by the maintainer):

- An entry the join's rescue sets aside again is now in the new incident, so it gets the new status `set_aside_again` and is never queued again from its first incident. It was `restore_failed` and could be queued again, which let a restore of both incidents bring the same items back twice. `restore_failed` stays for entries that never reached the data, or whose data was put back.
- When the player does not get in, the data is put back as it was before the restore. The rescue's own put-back writes the data it read, which is the merged data, so the entries would have stayed in the file while being marked as failed.
- The entries on their way in are recorded on disk (`restore-pending.dat`) before the merged data is written, with a hash of the player file. A join that never reports its result (a crash, or a client that leaves during configuration on 26.3) is settled at the next join from that hash, instead of from memory.
- On 1.20.1 the hook moves from before `PlayerList#getPlayerForLogin` to the head of `ServerLoginPacketListenerImpl#placeNewPlayer(ServerPlayer)`. `getPlayerForLogin` disconnects and saves a player who is still online under the same profile, and that save replaced the merged data.
- Each entry is followed by its incident and entry id, and the rescue's verdict is read from the tags the entry put into the data, not from the entry id: a stack restored to another slot, or two incidents with the same entry id, are decided apart.
- When the data is put back, or was never written, the values the backup incident kept as replaced are in the player's data, so they are marked `restored` there instead of staying restorable from the backup. Two queued entries on the same key do not go back at the same join.
