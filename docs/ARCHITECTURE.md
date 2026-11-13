# Architecture

This document describes what the code does and where it hooks into the game. The folder layout follows ADR-001 in [DECISIONS.md](DECISIONS.md), and the exact way the bands include the core is recorded there once settled.

## Overview

```text
core/        Pure Java: incident model and store, report rendering, suspect ranking,
             recurring-issue tracking, config. No Minecraft imports. JUnit tests.
versions/    One standalone project per band and loader. Each one holds the hooks,
             the salvage and restore code for its Minecraft version, the commands,
             and the loader adapter (entrypoint, attachment bridge, mod lookup).
testmod/     Dev-only mod that produces the failures the scenarios need, and the headless
             join client of the smoke test (join-bot/). Never published. Each band builds
             its own test mod JAR from common/, common-<minecraft>/ and <band>-<loader>/.
scripts/     Build of every band, production-JAR smoke test, release artifact checks.
```

Salvage and restore call Minecraft classes, so they live in the bands. They stay as small as possible, and every decision that can be made without the game (what to quarantine, how to rank suspects, when a failure becomes recurring) goes through the core.

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

Checked in the sources and reproduced by the smoke test (`docs/TESTING.md`):

| Unit | 1.20.1 | 1.21.1 | 26.3 |
|---|---|---|---|
| Inventory or ender chest item with an unknown id | Dropped silently (the registry returns air) | Dropped, ERROR `Tried to load invalid item` | Dropped, reported to the `ProblemReporter` (WARN) |
| Effect with an unknown id | Dropped silently | Dropped, ERROR log | Dropped, the valid effects are kept, WARN |
| Vehicle with an unknown entity type | Dropped, WARN `Skipping Entity with id {}` | same | same |

None of these throws, so none of them reaches the placement catch: the player joins and the unit is lost at the next save. Only an exception during the load does (a Forge capability, a Fabric mod's own key, a data-fixer or codec exception, or a mod's login code).

## Rescue strategy

1. Guard the placement call with one small mixin per band. On a failure that comes from data (T1 or T3), hand over to the rescue flow. The normal path is unchanged apart from the guard.
2. Detect T2 at the read site. If the source file is not empty but the read failed or returned nothing, mark the join as T2 and back up the file before any save can overwrite it.
3. Sanitize, then retry. Build a copy of the root tag without the failing units, then let vanilla run the real load on that copy. The mod never reimplements vanilla loading (ADR-PDR-001).
   - Units with a known decoder (inventory and ender chest stacks, effects, the dimension key, the vehicle's entity type) are checked one by one with the server's registry access. Failing units are stripped and quarantined.
   - Opaque units (attachments, capabilities, components, unknown top-level keys) are isolated by retrying. Load, and on failure use the attribution signals (SPEC §5) to pick the most likely key, strip it, quarantine it and retry. If no key stands out, strip the whole opaque section. At most 8 attempts.
4. If placement still fails, try the minimal profile. If that fails too, classify the failure as not data-related, restore the vanilla outcome and write the diagnosis report.

## Attachment bridge

Each loader stores mod data on the player differently. Each band's loader adapter implements one interface:

```java
interface PlayerExtraData {
    Set<String> rootKeys();
    List<Unit> split(CompoundTag root);
    void strip(CompoundTag root, Unit unit);
    RestoreResult apply(ServerPlayer player, Unit unit);
}
```

Storage, checked in M1 (see "Mod data on the player" above): Forge capabilities under `ForgeCaps`, NeoForge attachments under `neoforge:attachments`. Without Fabric API, Fabric has no attachment system, so Fabric mods and third-party component systems (for example `cardinal_components`) store plain top-level keys.

## Attribution

- Namespaces: compare each failing registry id's namespace with the loader's mod list.
- Stack frames: map each frame's class to its code source (the JAR), then the JAR to a mod id through the loader's mod list. Skip this mod, the loader, Minecraft and the JDK.
- Mixin frames: when a Minecraft frame's method name carries a mixin handler prefix, find the owning mixin config, then its mod. Low confidence.

## Persistence

- Atomic writes everywhere: write `*.tmp`, flush, then move. The originals are copied before anything else is written.
- Incident IO runs on the server thread. It is rare and small, and it keeps ordering simple.
- Folder names use the server's local time with a counter suffix when needed. Times inside JSON files are UTC ISO-8601.

## Mixins

- Only the placement guard and the T2 detection, at most one mixin per concern and per band.
- No `@Overwrite`. Prefer `@WrapOperation`, otherwise `@Inject` on the narrowest target.
- MixinExtras may be bundled jar-in-jar if needed (ADR-PDR-002).

## Performance and safety

- The normal path adds only the guard: no IO, no reflection at join time.
- Restore is limited to operators and the console.
- The mod only runs the game's own decoders on quarantined data, never anything else.
- A single entry's payload is capped at 2 MiB. Larger units are recorded as "too large" and kept in `original/` only.

## Open questions

- Is it safe to retry the load on the same `ServerPlayer` instance? A partial first load may leave attachments half set. If not, each retry builds a fresh `ServerPlayer` the way the login flow does. To answer in M1 on all three loaders, then record an ADR.
- How does the smoke test drive a real player join headlessly? Options include a dev-only test mod that triggers the load and placement path with a fake connection, or a protocol bot. To settle in M1 and record in [TESTING.md](TESTING.md).
- The rescue flow starts on a failure (T1, T3), but vanilla never fails on S1, S3 and S4: an item, an effect or a vehicle from a removed mod is dropped without an exception on every Tier A band, and NeoForge skips an attachment whose reader throws. Catching the placement failure alone cannot rescue those units. To decide before M2: detect them before vanilla drops them (a check of the known-decoder units on every join), or change those scenarios.
