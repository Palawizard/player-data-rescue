# Functional specification

Status: draft v1. Terms in italics are defined in the glossary at the end.

## 1. Where player data comes from

| Context | Source of the joining player's data |
|---|---|
| Dedicated server, and LAN guests | `<world>/playerdata/<uuid>.dat` (gzip NBT), with `<uuid>.dat_old` as the previous save |
| Singleplayer host and LAN host | The `Data.Player` compound in `<world>/level.dat`, with `level.dat_old` |

Both sources are handled. In every flow below, the *source file* is whichever applies.

## 2. Triggers

| Id | Situation | Vanilla outcome (verify per band) |
|---|---|---|
| T1 | Reading or decoding the player data throws while the player is loaded or placed. | Disconnect with "Invalid player data". The log shows `Couldn't place player in world`. |
| T2 | The source file exists and is not empty, but cannot be parsed (truncated or corrupted gzip or NBT). | Depends on the version. The player may be created fresh, and the next save overwrites the original. |
| T3 | Code that runs during placement throws because of the player's data, for example a mod reacting to one of its attachments on login. | Same as T1. |
| T4 (later release) | The data holds an entry that the game drops without any error: an item or an effect with an unknown id, a vehicle of an unknown entity type, or, on NeoForge, an attachment that fails to decode. | The player joins. The entry is lost at the next save, and nobody is told. |

T1 to T3 are handled from the first release. T4 is planned for a later milestone (it needs its own hooks, before the game discards each entry), so until then the mod does not see these entries. What vanilla does with each of them is recorded per band in [ARCHITECTURE.md](ARCHITECTURE.md).

### Failures that do not come from the data

Some "Invalid player data" errors happen with any data, even for a brand new player. A mod's own login or serialization code fails every time. Saved data cannot fix that, so the mod treats it differently:

- When placement still fails with the *minimal profile* (§4.3), the failure is classified as *not data-related*.
- The mod restores the vanilla outcome (disconnect), keeps the incident and its backups, and writes a *diagnosis report* (§6) that says the saved data is not the cause and names the suspects (§5).
- No rescue is attempted on the next join for that player until the server restarts or the mod list changes. This avoids a rescue loop.

## 3. Rescue flow

When T1, T2 or T3 occurs for player P:

1. Backup. Copy the source file and its `_old` counterpart, unchanged, into the *incident* folder. If the copy fails, stop and fall back to vanilla behaviour, with an error in the log. The mod never continues without a backup.
2. Parse. Read the raw NBT root. If parsing fails (T2), try the `_old` file. If both fail, P starts as a new player, and the incident records "unreadable file".
3. Salvage. Rebuild P section by section (§4). Each section loads on its own. A failure quarantines that section or that single entry, and salvage continues.
4. Place. Place P in the world with the salvaged data. If placement fails, retry once with the minimal profile. If that fails too, the failure is not data-related (§2).
5. Persist. Save P's new state through the normal save path straight away, so the broken data is not read again on the next join. The original file is already in the incident folder.
6. Report. Write `report.txt` and `report.json` (§6), and log a one-line summary with the incident path.
7. Notify, if enabled (§8). P sees "Some of your saved data could not be loaded and was set aside. An admin can restore it." Online operators see the player, the number of quarantined parts, the suspects and a clickable `/pdrescue show` command.

A player is rescued at most once per join attempt.

## 4. Salvage model

### 4.1 Sections

| Section | Granularity | Notes |
|---|---|---|
| Core | whole | UUID, position, rotation, dimension, game mode, health, food, saturation, XP, air, fire, fall distance, abilities, spawn point. Invalid values are clamped to safe defaults and never quarantined. An unknown dimension sends P to the overworld spawn. |
| Inventory | per slot | Main inventory, armor, offhand, and equipment slots on versions that store them separately. |
| Ender chest | per slot | |
| Effects | per effect | |
| Recipe book, advancements, stats | whole | Advancements and stats live in their own files on most versions. Optional and isolated. |
| Attachments, capabilities, components | per key | NeoForge data attachments, Forge capabilities, Fabric data attachments, and third-party component systems stored on the player. |
| Vehicle and passengers | whole | On failure, P is placed alone at the stored position. |
| Unknown top-level keys | per key | Top-level keys that vanilla does not read and whose load throws. |

### 4.2 Quarantine entry

A failing unit (a slot, an effect, an attachment key or a whole section) becomes a quarantine entry:

- `id`: short and stable, for example `inv.17`, `ender.3`, `effect.examplemod:poison_cloud`, `attachment.examplemod:mana`.
- `section`, and `slot` when relevant.
- `raw`: the original NBT of the unit, as SNBT and as binary NBT.
- `error`: exception class, message, the first 15 stack frames and the "Caused by" chain.
- `suspects`: a list of `{ modId, reason, confidence }` (§5).
- `status`: `quarantined`, `restored`, `dismissed` or `restore_failed`.

### 4.3 Minimal profile

The vanilla core only: position (world spawn if invalid), health 20, food 20 and the stored game mode. Every other section is quarantined whole. It is used only when salvage followed by placement fails.

### 4.4 Recurring failures

A mod that saves data it cannot read back breaks every login. Each join would then create a new incident for the same key. To keep this readable:

- A unit key that is quarantined for the same player on `recurringThreshold` consecutive joins (default 3), or for `recurringThreshold` different players, becomes a *recurring issue*.
- A recurring issue has one record under `<world>/playerdatarescue/recurring/<key>.json`, holding the key, the suspected mod, the affected players, the first and last occurrence, a counter, and the first and the latest payload.
- Later failures for that key update the record instead of creating new incidents. Other failing units of the same join still create an incident as usual.
- Operators are told once per server start: "Mod X saves player data it cannot load back. That data is set aside at every login until X is fixed. Report it to X with the file below."
- The record is cleared by `/pdrescue dismiss recurring <key>`, or automatically when the key loads successfully again.

## 5. Suspected mod attribution

| Signal | Confidence |
|---|---|
| The namespace of the failing registry id (item, effect, attachment key, entity type), when no installed mod has that namespace | High: "mod not installed" |
| The same namespace, when the mod is installed | High: "the mod's data failed to load" |
| The first stack frame whose class belongs to an installed mod JAR, skipping this mod, the loader, Minecraft and the JDK | Medium |
| A Minecraft frame changed by a mixin whose owning mod can be found | Low |

At most three suspects, without duplicates. Reports say "most likely", never "caused by".

## 6. Incident storage and reports

```
<world>/playerdatarescue/
  <uuid>/
    <yyyyMMdd-HHmmss>/          one incident
      original/                 untouched copies of the source files
      quarantine.json           entries (§4.2) without binary payloads
      quarantine/<entry-id>.nbt binary payload of each entry
      report.txt
      report.json
  recurring/
    <key>.json                  recurring issues (§4.4)
```

- The folder lives inside the world, so world backups include it.
- `report.txt` starts with one sentence saying what happened. It then gives the player name and UUID, the date, the Minecraft, loader and mod versions, and the trigger. Next comes the list of kept and set-aside parts in plain words (for example "Slot 17: 12 brass ingots (create)"), followed by the suspects and the next step (for example "reinstall create, then run `/pdrescue restore <player> <incident>`"). The raw errors are in an appendix.
- A *diagnosis report* (not data-related failures, §2) has the same layout, but says that the saved data is not the cause and that nothing was changed.
- `report.json` holds the same content with a schema version.
- Retention keeps the latest `maxIncidentsPerPlayer` incidents (default 20). Incidents that still have quarantined entries are never pruned automatically.

## 7. Commands

The root command is `/pdrescue`. The permission level is configurable (default 3). The console can run every command.

| Command | Effect |
|---|---|
| `/pdrescue list [player]` | List incidents and recurring issues, with counts and status. |
| `/pdrescue show <player> <incident>` | Print the summary and the entries, each with a clickable restore action. |
| `/pdrescue restore <player> <incident> [entry\|all]` | Re-apply entries (§7.1). |
| `/pdrescue dismiss <player> <incident> [entry\|all]` | Mark entries as dismissed. The data stays on disk. |
| `/pdrescue dismiss recurring <key>` | Close a recurring issue. |
| `/pdrescue export <player> <incident>` | Print the incident folder path for bug reports. |

Player arguments accept online players. For `list`, `show` and `export`, they also accept offline players by name or UUID.

### 7.1 Restore

- P must be online. For an offline player, the restore is queued and runs at the next join.
- Inventory and ender chest entries go back into their original slot if it is empty, otherwise into the first free slot of the same container, otherwise they drop at P's feet with no pickup delay, and the drop is logged.
- Effects are applied with their remaining duration.
- Attachments are decoded again by the loader for that key. If the key already exists on P, the entry becomes `restore_failed` with the reason "already present". Entries are never merged.
- Vehicles cannot be restored in v1. The payload is kept for manual recovery.
- A restored entry becomes `restored`, and restoring it again does nothing. This is what prevents item duplication.
- A failed restore marks the entry `restore_failed` with the new error and keeps the payload.

## 8. Configuration (`config/playerdatarescue.json`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `notifyPlayer` | `true` | Message to the rescued player. |
| `notifyOperators` | `true` | Summary to online operators. |
| `commandPermissionLevel` | `3` | Permission level for `/pdrescue`. |
| `maxIncidentsPerPlayer` | `20` | Retention for fully handled incidents. |
| `useDatOldFallback` | `true` | Try the `_old` file when the main file cannot be parsed. |
| `rescuePlacementFailures` | `true` | Handle T3. |
| `recurringThreshold` | `3` | Joins or players before a failure becomes a recurring issue. |
| `logFullStackTraces` | `false` | Put full traces in the log. They are always in `report.txt`. |

## 9. Acceptance scenarios

Each scenario maps to automated tests: fixture tests, or the production-JAR smoke test with the dev-only test mod (see [TESTING.md](TESTING.md)).

S1, S3, S4 and S7, and S2 on NeoForge, depend on T4. They are checked in the T4 milestone. Until then, the smoke test checks the vanilla outcome of these scenarios instead (the player joins and the entry is lost).

| Id | Given | When | Then |
|---|---|---|---|
| S1 | P has an item from mod X in slot 17, and X is removed. | P joins. | P joins with every other slot intact. Slot 17 is quarantined with the suspect X (not installed). The original files are backed up and a report is written. |
| S2 | P has an attachment whose decoder throws (test mod). | P joins. | P joins. Only that attachment is quarantined, with the test mod as suspect. |
| S3 | P has an effect with an unknown id. | P joins. | P joins without that effect, and the entry is quarantined. |
| S4 | P logged out riding an entity type that no longer exists. | P joins. | P is placed at the stored position, not mounted. The vehicle section is quarantined. |
| S5 | `<uuid>.dat` is truncated and `.dat_old` is valid. | P joins. | P joins with the `.dat_old` data. Both originals are backed up. The trigger is T2. |
| S6 | Both files are unreadable. | P joins. | P joins as a new player. The originals are kept and the report says "unreadable file". |
| S7 | S1 happened, then X is reinstalled. | An operator runs `restore all`. | The slot 17 item comes back once. A second `restore all` changes nothing. |
| S8 | Singleplayer world whose host data in `level.dat` has S2's broken attachment (Forge or Fabric). | The world opens. | Same as S2, with the `level.dat` copies in `original/`. |
| S9 | Dedicated server with the mod, client without it. | Any scenario. | The client connects normally. Messages use vanilla text components only. |
| S10 | The mod is removed after incidents were recorded. | The world loads. | The world loads normally, and the `playerdatarescue/` folder is ignored. |
| S11 | The test mod throws on every placement, whatever the data. | P joins twice. | Both joins end with the vanilla disconnect. One incident and one diagnosis report are written, saying the data is not the cause and naming the test mod. The second join does not retry a rescue. Nothing loops or crashes. |
| S12 | Valid player data. | P joins. | No incident and no file written. The extra work on the normal path is under 1 ms. |
| S13 | The test mod saves an attachment it cannot read back. | P joins and leaves four times. | The first joins create incidents. From the third consecutive failure the key becomes a recurring issue: one record, operators told once, and no new incident for that key. |
| S14 | S13, then the test mod is switched to a fixed behaviour. | P joins. | The attachment loads, and the recurring record is closed automatically. |

## Glossary

- *Source file*: the file that holds P's saved data (§1).
- *Incident*: one rescue or diagnosis for one player, stored in its own folder.
- *Quarantine entry*: a unit of data that failed to load, kept raw with its error.
- *Salvage*: loading data section by section and keeping every unit that loads.
- *Minimal profile*: the vanilla core only (§4.3).
- *Not data-related*: a failure that happens even with the minimal profile (§2).
- *Diagnosis report*: the report written for a failure that is not data-related.
- *Recurring issue*: one key failing again and again (§4.4).
