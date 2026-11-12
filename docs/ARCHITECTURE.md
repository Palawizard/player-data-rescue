# Architecture

This document describes what the code does and where it hooks into the game. The folder layout follows ADR-001 in [DECISIONS.md](DECISIONS.md), and the exact way the bands include the core is recorded there once settled.

## Overview

```text
core/        Pure Java: incident model and store, report rendering, suspect ranking,
             recurring-issue tracking, config. No Minecraft imports. JUnit tests.
versions/    One standalone project per band and loader. Each one holds the hooks,
             the salvage and restore code for its Minecraft version, the commands,
             and the loader adapter (entrypoint, attachment bridge, mod lookup).
testmod/     Dev-only mod that produces the failures the scenarios need. Never published.
scripts/     Build of every band, production-JAR smoke test, release artifact checks.
```

Salvage and restore call Minecraft classes, so they live in the bands. They stay as small as possible, and every decision that can be made without the game (what to quarantine, how to rank suspects, when a failure becomes recurring) goes through the core.

## Hook points

Names are Mojang names to check against each band's decompiled sources before use. When a band is verified, its exact signatures go in this table.

| Concern | 1.20.1 | 1.20.2 to 1.21.5 | 1.21.6 to 1.21.11 | 26.x |
|---|---|---|---|---|
| Where placement is called and the failure caught (`Couldn't place player in world`) | `ServerLoginPacketListenerImpl`, accepted-login path, calling `PlayerList#placeNewPlayer` | `ServerConfigurationPacketListenerImpl#handleConfigurationFinished` calling `PlayerList#placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` | to verify | to verify |
| Where the saved data is read | `PlayerList#load(ServerPlayer)`: the host reads `WorldData#getLoadedPlayerTag()`, others read `PlayerDataStorage#load` | to verify (`Optional<CompoundTag>` on newer versions) | `ValueInput`. Loading may report problems through `ProblemReporter` instead of throwing. Check which failures still throw. | to verify |
| Where an unreadable file is handled (T2) | `PlayerDataStorage#load` catches and warns | to verify: newer versions may already back up corrupted files | to verify | to verify |

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

Expected storage, to verify per band: Forge capabilities under `ForgeCaps`, NeoForge attachments under `neoforge:attachments`, Fabric attachments under `fabric:attachments`. Third-party component systems (for example `cardinal_components`) are plain top-level keys.

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
