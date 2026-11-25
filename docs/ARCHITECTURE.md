# Architecture

This document describes what the code does and where it hooks into the game. The folder layout follows ADR-001 in [DECISIONS.md](DECISIONS.md), and the exact way the bands include the core is recorded there once settled.

## Overview

```text
core/        Pure Java: incident model and store, report rendering, suspect ranking,
             recurring-issue tracking, config. No Minecraft imports. JUnit tests.
versions/    One standalone project per band and loader: the loader adapter (entrypoint,
             mod lookup) and what only that loader needs. Shared folders hold the rest
             (ADR-PDR-008): shared/java for every band, shared/<loader>/java for the
             bands with the same loader API, common-<minecraft>/java for every loader of
             one Minecraft version (the game calls and most mixins).
testmod/     Dev-only mod that produces the failures the scenarios need, and the headless
             join client of the smoke test (join-bot/). Never published. Each band builds
             its own test mod JAR from common/, common-<minecraft>/ and <minecraft>-<loader>/.
scripts/     Build of every band, production-JAR smoke test, release artifact checks.
```

Attempts and restore call Minecraft classes, so they live in the bands. They stay as small as possible, and every decision that can be made without the game goes through the core: reading and writing NBT, splitting the data into units, what to set aside next, the minimal profile, how to rank suspects, the incident files and reports, and the config.

## Hook points

Checked in M1 (2026-11-13) against the decompiled sources of every Tier A band: vanilla through Loom (the Fabric bands run vanilla code unchanged, no Fabric API), Forge 47.4.28, 52.1.20 and 66.1.5 through their patched JARs, NeoForge 21.1.266 and 26.3.0.102 through their patched sources. Names are Mojang names. A descriptor is the same on every loader of a band unless a row says otherwise.

### Placement call and catch

| Band | Method holding the catch | Call inside the try |
|---|---|---|
| 1.20.1 (Forge, Fabric) | `ServerLoginPacketListenerImpl#handleAcceptedLogin()V` | `PlayerList#getPlayerForLogin(Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/server/level/ServerPlayer;`, then `PlayerList#placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V` |
| 1.21.1 (Forge, Fabric, NeoForge) | `ServerConfigurationPacketListenerImpl#handleConfigurationFinished(Lnet/minecraft/network/protocol/configuration/ServerboundFinishConfigurationPacket;)V` | `PlayerList#getPlayerForLogin(Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;`, then `PlayerList#placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V` |
| 26.3 (Forge, Fabric, NeoForge) | same method as 1.21.1 | `PrepareSpawnTask#spawnPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/level/ServerPlayer;`, which calls `PrepareSpawnTask$Ready#spawn` (same descriptor): `new ServerPlayer(...)`, `Entity#load(Lnet/minecraft/world/level/storage/ValueInput;)V`, `PlayerList#placeNewPlayer` (same descriptor as 1.21.1), then the ender pearls and the vehicle |

- On every band the catch is `catch (Exception)`: it logs `Couldn't place player in world` at ERROR, sends a disconnect packet with `Component.translatable("multiplayer.disconnect.invalid_player_data")` and closes the connection. On 1.20.1 the packet is the game-protocol `ClientboundDisconnectPacket`; on 1.21.1 and 26.3 it is the common-protocol one, sent after the outbound protocol switched to game.
- 1.20.1 gap: when a player with the same UUID is still online, placement is delayed (`DELAY_ACCEPT`) and runs later from `tick()`, outside the try. A failure there is logged as `Failed to handle packet for {}` and disconnects with "Internal server error".
- 26.3: a throw inside `PrepareSpawnTask#start` (configuration phase) is caught by `ServerConfigurationPacketListenerImpl#startNextTask()V`, logged as `Failed to start configuration task {}` and disconnects with `multiplayer.disconnect.configuration_error`. The placement catch does not fire.

### Where the saved data is read and applied

| Band | Read | Apply |
|---|---|---|
| 1.20.1 | `PlayerList#load(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/nbt/CompoundTag;`, first call of `placeNewPlayer`. Host: `WorldData#getLoadedPlayerTag()Lnet/minecraft/nbt/CompoundTag;` (level.dat `Data.Player`). Others: `PlayerDataStorage#load(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/nbt/CompoundTag;`, which applies the data-fixed tag but returns the raw tag (used for `Dimension`, game modes and `RootVehicle`). | `Entity#load(Lnet/minecraft/nbt/CompoundTag;)V`, then `readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V`. `RootVehicle` is loaded later in `placeNewPlayer`, after the player was added to the level. |
| 1.21.1 | `PlayerList#load(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Optional;`. Host: `WorldData#getLoadedPlayerTag()`. Others: `PlayerDataStorage#load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;`, which applies and returns the fixed tag. | Same as 1.20.1. |
| 26.3 | `PlayerList#loadPlayerData(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;` and `PlayerDataStorage#load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;` (read and data-fix only). The host has no `getLoadedPlayerTag` any more: its data is `playerdata/<singleplayer_uuid>.dat`. The file is read twice per join: in `PrepareSpawnTask#start(Ljava/util/function/Consumer;)V` during configuration, with no `ServerPlayer` yet, for `Dimension`, `Pos` and `Rotation` only; then in `PrepareSpawnTask$Ready#spawn`. | `Entity#load(Lnet/minecraft/world/level/storage/ValueInput;)V`, then `readAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V`. Decode problems go to a `ProblemReporter.ScopedCollector`, logged at WARN on close. Vehicle: `ServerPlayer#loadAndSpawnParentVehicle(Lnet/minecraft/world/level/storage/ValueInput;)V`, after `placeNewPlayer`. |

Loader events around the read: Forge and NeoForge fire their player loading event after the apply, except Forge 1.21.1, which fires it inside the read's own try (a listener exception there counts as an unreadable file).

### Unreadable file (T2)

- 1.20.1: `PlayerDataStorage#load` catches the read failure, logs `Failed to load player data for {}` at WARN without the exception, and returns null. The player starts fresh. There is no backup and no `.dat_old` attempt; the next save moves the broken `.dat` to `.dat_old`, and the save after that deletes it.
- 1.21.1 and 26.3: same WARN, then the file is copied to `<uuid>_corrupted_<yyyy-MM-dd_HH-mm-ss>.dat` and `.dat_old` is tried. If both fail the player starts fresh. On 26.3 this happens twice per join (two reads), so two copies can appear.
- Data-fixer exceptions are outside the read's try on every band and propagate.

### Mod data on the player

| Loader | Storage | A reader that throws |
|---|---|---|
| Forge (1.20.1, 1.21.1, 26.3) | Capabilities under `ForgeCaps`, attached by `AttachCapabilitiesEvent` in the `Entity` constructor (26.3: `AttachCapabilitiesEvent.Entities`, and no dispatcher at all when the event has no listener). Read in `Entity#load` before `readAdditionalSaveData`. | `CapabilityDispatcher#deserializeNBT` has no catch: the exception reaches the placement catch and the player is disconnected. |
| NeoForge (1.21.1, 26.3) | Data attachments under `neoforge:attachments`, read by `AttachmentHolder#deserializeAttachments` in `Entity#load`. | Caught per key and logged at ERROR, `Failed to deserialize data attachment {}. Skipping.` The player joins without it, and the key is not written back at the next save. |
| Fabric (no Fabric API) | No attachment system. Mods store plain top-level keys through their own mixins, and third-party component libraries do the same. | Whatever the mod's mixin does. A throw from `readAdditionalSaveData` reaches the placement catch. |

### What vanilla does with data from a removed mod

Checked in the sources. The smoke test checks that the player joins in each case (`docs/TESTING.md`):

| Unit | 1.20.1 | 1.21.1 | 26.3 |
|---|---|---|---|
| Inventory or ender chest item with an unknown id | Dropped silently (the registry returns air) | Dropped, ERROR `Tried to load invalid item` | Dropped, reported to the `ProblemReporter` (WARN) |
| Effect with an unknown id | Dropped silently | Dropped, ERROR log | Dropped, the valid effects are kept, WARN |
| Vehicle with an unknown entity type | Dropped, WARN `Skipping Entity with id {}` | same | same |

None of these throws, so none of them reaches the placement catch: the player joins and the unit is lost at the next save. Only an exception during the load does (a Forge capability, a Fabric mod's own key, a data-fixer or codec exception, or a mod's login code).

### Hooks used by the rescue (M2)

Checked on 2026-11-16 in the same sources as above, for every Tier A band and loader. All are plain Mixin injectors (ADR-PDR-009).

| Band | Placement guard | Unreadable file (T2) | Save and connection |
|---|---|---|---|
| 1.20.1 | `@Inject` before `Logger#error(String, Throwable)` in `ServerLoginPacketListenerImpl#handleAcceptedLogin()V`, cancellable, locals captured (`Component`, `ServerPlayer`, `Exception`); `@Inject` at its `RETURN` | `@Inject` before `Logger#warn(String, Object)` in `PlayerDataStorage#load(Player)`; `@ModifyVariable` on the first load of its `CompoundTag` local, to hand over `.dat_old` | `@Inject` at the head of `PlayerList#save(ServerPlayer)V`, cancellable; `@Invoker` of the same method; `@Accessor` of the private field `Connection#channel`; `@Inject` at the head of `Connection#send(Packet, PacketSendListener)V`, cancellable, and `@Inject` before and after the call to `ServerLoginPacketListenerImpl#placeNewPlayer(ServerPlayer)V` in the login handler, to hold the packets of a placement; its `RETURN` hook releases anything still held |
| 1.21.1 | the same injections in `ServerConfigurationPacketListenerImpl#handleConfigurationFinished(ServerboundFinishConfigurationPacket)V` (captured local: the `Exception`) | `@Inject` before `Logger#warn(String, Object)` in `PlayerDataStorage#load(Player, String)` | as 1.20.1, with `Connection#send(Packet, PacketSendListener, boolean)V` and the call to `PlayerList#placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)V` |
| 26.3 | as 1.21.1 | `@Inject` before `Logger#warn(String, Object)` in `PlayerDataStorage#load(NameAndId, String)` | as 1.20.1, with `Connection#send(Packet, ChannelFutureListener, boolean)V` and the call to `PrepareSpawnTask#spawnPlayer(Connection, CommonListenerCookie)` |

The guard mixin of 1.21.1 and 26.3 extends `ServerCommonPacketListenerImpl` to reach `server`, `connection` and `createCookie`. NeoForge has its own copy (`NeoForgeConfigurationMixin`), because its listener builds the cookie with `createCookie(ClientInformation, ConnectionType)`; Forge and Fabric use `createCookie(ClientInformation)`.

Each attempt of a rescue builds a new player:

- 1.20.1: `new ServerPlayer(MinecraftServer, ServerLevel, GameProfile)`, then `PlayerList#load(ServerPlayer)` (load only) or `PlayerList#placeNewPlayer(Connection, ServerPlayer)`.
- 1.21.1: `PlayerList#getPlayerForLogin(GameProfile, ClientInformation)`, which only calls the constructor on this version, then `PlayerList#load(ServerPlayer)` or `PlayerList#placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)`.
- 26.3: load only builds `new ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)` and loads `PlayerList#loadPlayerData(NameAndId)` through `TagValueInput`, as `PrepareSpawnTask$Ready#spawn` does. On 26.3 the loader's player loading event is not part of the read: `Ready#spawn` fires it right after `ServerPlayer#load`, Forge only when the player has saved data (`ForgeEventFactory.firePlayerLoadingEvent(Player, File, String)`) and NeoForge every time (`EventHooks.firePlayerLoadingEvent(Player, PlayerList, String)`), so load only fires it the same way (`PlayerLoadingEvent`, set by each loader's entrypoint). Placement calls `PrepareSpawnTask#spawnPlayer` again. The spawn level and position are chosen earlier, in `PrepareSpawnTask#start` during configuration, from the original data: an unknown dimension gives the respawn level (or the overworld) with the stored coordinates, as without the mod. A rescue cannot move the player on 26.3, so the 26.3 band does not check the dimension (SPEC.md section 4.1), and a position the core values reset is written to the data but the player still spawns where the configuration put them.

### Cleanup after a failed placement (T3)

When placement throws after the game added the player (a mod's login code, or the test mod at the end of `placeNewPlayer`), the player is in the player list and the level, and the connection's listener is the new game listener. The client got nothing: the connection holds every packet a placement sends until the placement returns (`HeldPackets`, implemented on `Connection` by each band's `ConnectionMixin` around the core's `PacketHold`, which has its unit tests). A packet the game sends after the placement call can still throw while the held ones go out: that counts as a failed placement too, and the packets sent before it have reached the client, so the retry is then a second login for it. `Connection#send` only queues a packet on the channel's event loop, so this needs a packet the game itself refuses. Before the next attempt the rescue:

1. marks that `ServerPlayer` instance as a failed attempt, so `PlayerList#save` skips it from then on;
2. removes it with `PlayerList#remove(ServerPlayer)`, the game's leave path: it leaves the level, the player list and the boss bars, the cached stats and advancements of its UUID are dropped (the next instance reads them again), the other players get the tab list removal, and Forge and NeoForge fire their logged-out event;
3. drops the packets held since the placement started. The removal sends packets to this connection too (the entities the half-placed player saw are removed from its view): the vanilla client, which never got a login packet, has no level yet and failed on them (`NullPointerException` in `handleRemoveEntities`, vanilla 1.20.1 client on the Forge server, 2026-11-24). When the rescue stops before this cleanup (the rescue is off, no rescue is tried until a restart, or saved files exist but cannot be read), nothing is dropped: the game's disconnect sends the held packets first, as without this mod. After the cleanup, a client that is not let in only gets the disconnect (S11, checked with the vanilla client);
4. puts the connection back so that one more placement can run on it, which the game never does itself:
   - 1.20.1: Forge 47 adds its packet filters `forge:vanilla_filter` and `forge:forge_fixes` to the channel at each placement (`NetworkFilters#injectIfNecessary`) and throws when they are already there, so they are removed first. Fabric has no such handlers.
   - 1.21.1 and 26.3: placement sets the game protocol up with `Connection#setupInboundProtocol`, whose task only the `UnconfiguredPipelineHandler.Inbound` handler left by the end of configuration can run, and the first placement consumed it. A small outbound handler (`InboundReset`) takes its place: on the channel's event loop it removes the decoder of the failed placement and lets the task put the new one there. The client is already in the game protocol and keeps sending packets, so the decoder is swapped in one step, never left out;
5. lets the next attempt build a new player and place it on the same connection, with its packets held the same way: the client gets the login packet of the placement that worked, and only that one.

Why the packets are held: the game never sends a second login packet on one connection, and the vanilla client does not take one. On 1.20.1, 1.21.1 and 26.3, `ClientPacketListener#handleLogin` makes a new client level but keeps the local player of the first login, which still belongs to the old level, where no chunk ever arrives. `LocalPlayer#tick` only runs when its own level has the chunk, so the player stops moving and sends no more positions: on screen it is stuck and shakes, while the server sees nothing wrong (2026-11-24, vanilla 1.21.1 client: lifted 3 blocks after the rescue, it stayed in the air). The confirmations of the failed placement's teleports also reached the new listener, which disconnected the client with "Invalid move player packet received" when one carried the id it was waiting for. Neither can happen when the client never sees the failed placement.

A disconnect sent during a placement ends the hold, with the packets before it sent first, so a player kicked during placement still gets the reason. When nothing is held, the hook in `Connection#send` costs its callback object and one volatile read.

When the last attempt fails too, the game's own catch disconnects the client, and the disconnect removes the last instance again through `PlayerList#remove`. Because both instances are marked, neither removal saves a half-loaded state over the player's file, which the rescue has put back to the original by then. Checked by S11 on every band: its diagnosis must rest on the test mod's own failure in the minimal profile's placement, not on an error of the retry. Without step 4, the retry failed on every band but Fabric 1.20.1 (`Duplicate handler name: forge:vanilla_filter`, or `unsupported message type: UnconfiguredPipelineHandler$$Lambda`).

### Hooks of the commands and the restore (M3)

Checked on 2026-11-24 in the same sources, for every Tier A band.

| Band | Commands | Before the player's data is read |
|---|---|---|
| 1.20.1 | `@Inject` at the `TAIL` of `Commands#<init>(Commands$CommandSelection, CommandBuildContext)`, `@Shadow` of `dispatcher` | `@Inject` at the `HEAD` of `ServerLoginPacketListenerImpl#placeNewPlayer(ServerPlayer)V`, after `PlayerList#getPlayerForLogin` has disconnected and saved a player still online under the same profile; its `RETURN` ends the restore, also for a placement delayed to `tick` that works; a delayed placement that throws is outside the login handler's catch, and the next join settles its record (checked with `javap` on the 1.20.1 Mojang-named JAR on 2026-11-25) |
| 1.21.1 | same constructor | `@Inject` before `PlayerList#getPlayerForLogin(GameProfile, ClientInformation)` in `ServerConfigurationPacketListenerImpl#handleConfigurationFinished`, after the method has moved to the server thread (NeoForge's copy of the guard mixin has the same hook) |
| 26.3 | same constructor | `@Inject` at the `HEAD` of `PrepareSpawnTask#start(Ljava/util/function/Consumer;)V`, which reads the player file first, during configuration (`@Shadow` of `server` and `nameAndId`). The task can start on a network thread once the code of conduct is accepted (`handleAcceptCodeOfConduct` does not move to the server thread), so the restore runs through `MinecraftServer#executeBlocking`. The duplicate login check only comes in `handleConfigurationFinished`, so the restore is skipped while a player of the same profile is online (checked with `javap` on 2026-11-25) |

Permission checks and clickable text differ by version and live in each `common-<minecraft>` folder (`Texts`): `CommandSourceStack#hasPermission(int)` and `new ClickEvent(Action.RUN_COMMAND, ...)` on 1.20.1 and 1.21.1; the `Commands.LEVEL_*` permission checks on `CommandSourceStack#permissions()` and `ClickEvent.RunCommand` on 26.3. Restored stacks with no free slot are decoded with `ItemStack.of(CompoundTag)` (1.20.1), `ItemStack.parse(HolderLookup.Provider, Tag)` (1.21.1) or `ItemStack.CODEC` with `RegistryOps` (26.3), and spawned as an `ItemEntity` with no pickup delay at the player's position.

## Restore

The core holds the restore (`core/restore`): `Restorer` runs the commands (queue, dismiss), `RestoreJoin` the join side (settle a past record, merge the queue, decide each entry from where the join ended), `RestoreMerge` puts entries into a copy of the player's data and says which tags each entry put there, and `RestoreQueue` and `RestorePending` are the queue file and the record of entries on their way in. The bands only call it from the hooks above (`RestoreService`): they hash and read the player's data, write the merged data where the game reads it (`DataSource#write`, as an attempt does), put the data back with the same `DataSource` when the player does not get in, and drop the stacks that found no slot. The backup incident and the record are written before the data changes. Every rule that keeps an entry restorable in one place only is tested in the core (`RestoreJoinTest`, `RestoreBackupTest`, `RestorerTest`).

## Rescue strategy

1. Guard the placement with an injection inside the catch of the login handler (ADR-PDR-009). On a failure, hand over to the rescue flow; when it lets the player in, the handler returns there. The normal path is unchanged apart from two small hooks (S12).
2. Detect T2 at the read site: at the warning the game logs when it cannot parse the file, back up both files before any save can overwrite them. On 1.20.1, also hand the readable `.dat_old` to the load; 1.21.1 and 26.3 read it themselves. The incident is written once the player is in.
3. Back up the source files (SPEC.md step 1), then classify: load the original data into a new player without placing it. A throw there is T1; otherwise the failure came from placement (T3).
4. Sanitize, then retry (T1). Reset invalid core values, then load again into a new player each time, setting aside the units the failure points at: the units whose namespace is a suspected mod (SPEC.md section 5), and if none matches, every opaque unit left (mod attachments and capabilities, unknown top-level keys). At most 8 loads. The mod never reimplements vanilla loading (ADR-PDR-001): each attempt goes through the game's own read (ADR-PDR-010). Units with a known decoder (items, effects, the vehicle) do not throw on the Tier A bands; the game drops the unknown ones itself, which is trigger T4.
5. Place the salvaged data. If that fails, or the failure was at placement, place the minimal profile and set every other section aside whole. If that fails too, the failure is not data-related: put the original back, write the diagnosis report, let the game disconnect the player, and try no rescue for that player until the server restarts. A player with no saved data at all who cannot join gets the diagnosis straight away.

## Units of mod data

The core splits the saved tag into units (`PlayerData`), whatever the loader: Forge capabilities are the keys of `ForgeCaps`, NeoForge data attachments the keys of `neoforge:attachments`, and on Fabric (no Fabric API, so no attachment system) mod data is every top-level key that the game and the loaders do not write themselves (`VanillaKeys`, taken from the save methods of 1.20.1, 1.21.1 and 26.3 and checked against files those servers saved). Inventory, ender chest and equipment slots, effects, the vehicle and the recipe book are units too. Restoring a unit (M3) will go through the game's decoder for that key.

## Attribution

- Namespaces: compare the namespace of each unit set aside (registry id or key) with the loader's mod list: "mod not installed" or "the mod's data failed to load".
- Stack frames, innermost cause first: find the mod file that contains each frame's class file, through the loader's mod list (Fabric `ModContainer#findPath`, Forge and NeoForge 21.1 `IModFile#findResource`, NeoForge 26.3 `JarContents#containsFile`). The first frame of a mod that is not this mod, a loader or Minecraft is the medium-confidence suspect.
- Mixin frames: Mixin marks every method it merges into a game class with `@MixinMerged`, which names the mixin class; the mod file holding that class owns it. Low confidence.
- During salvage, the medium and high suspects of a failed load choose the units to set aside next (Rescue strategy).

## Persistence

- Atomic writes everywhere: write `*.tmp`, flush, then move. The originals are copied before anything else is written.
- Incident IO runs on the server thread. It is rare and small, and it keeps ordering simple.
- Folder names use the server's local time with a counter suffix when needed. Times inside JSON files are UTC ISO-8601.

## Mixins

- The placement guard and its return hook, the T2 hooks, the save skip and save invoker of `PlayerList`, an accessor for the netty channel of `Connection`, and the packet hold (`Connection#send` and the two injections around the placement call), listed per band under Hook points. Plain Mixin injectors and accessors only, no MixinExtras (ADR-PDR-009).
- No `@Overwrite` and no `@Redirect`.
- Forge 1.20.1 runs on SRG names and ships a refmap written by the mixin annotation processor; the other bands run on Mojang names or are remapped by Loom.

## Performance and safety

- On the normal path the mod runs, with no IO and no reflection: the return hook of the login handler, on 1.20.1 the read hook of `PlayerDataStorage#load`, and the packet hold (a hold and a release around the placement call, and a release in the return hook). Their measured cost is in [TESTING.md](TESTING.md), Normal path cost. Each packet the server sends also costs, when nothing is held, a callback object and one volatile read.
- Restore is limited to operators and the console.
- The mod only runs the game's own decoders on quarantined data, never anything else.
- A single entry's payload is capped at 2 MiB. Larger units are recorded as "too large" and kept in `original/` only.

## Answered questions

- Retrying the load on the same `ServerPlayer` is not safe: a partial load adds to effects (1.20.1 and 1.21.1), attribute modifiers, the recipe book, NeoForge attachments and Forge capabilities instead of replacing them. Each attempt builds a new `ServerPlayer` (ADR-PDR-005).
- The smoke test joins real players through a headless client in `testmod/join-bot/` (ADR-PDR-006, [TESTING.md](TESTING.md)).
- A player half-registered by a failed placement is removed before the next attempt and never saved (Cleanup after a failed placement above, ADR-PDR-010).
- A data-related T3 ends with the player in the world: S15 places the minimal profile after a failed placement on every band, with the join client and with the vanilla client (ADR-PDR-012).

## Open questions

- The units that vanilla drops without an exception (S1, S3, S4, and S2 and S13 on NeoForge) are trigger T4 in [SPEC.md](SPEC.md), a later milestone with its own hooks.
- The 1.20.1 placement that waits for a player with the same UUID to leave (`DELAY_ACCEPT`) runs outside the guarded try, so a failure there is not rescued (Hook points, 1.20.1 gap).
- The report's next step and the player message name `/pdrescue restore`, and SPEC.md section 3 asks for a clickable `/pdrescue show` in the operator message. The commands come with M3; until then the operator message has no link.
