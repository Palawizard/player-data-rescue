# Testing

Player Data Rescue handles player saves. A regression can destroy someone's data, so tests are part of every change.

## Layers

| Layer | Covers | Runs |
|---|---|---|
| Unit tests (JUnit 6) | Pure logic in the shared core | Every pull request |
| Fixture tests | Behaviour against real files produced by the game | Every pull request |
| Production-JAR smoke test | The packaged JAR on a real dedicated server for each band and loader | Every band on every pull request and every push to `dev` or `main` |
| Dev-only test mod | Deliberately triggers the failures the mod handles, so the smoke test can reproduce them | Loaded only by the smoke harness, never published |

The smoke test installs the loader's real dedicated server, drops the packaged JAR (and the dev-only test mod when a scenario needs it), boots the server, runs the scenario and checks the result in the files and logs. Dev runs (`runServer`, `runGameTestServer`) are not used as a release gate: they gave non-reproducible results across loader toolchains on Pala's Item Clear.

## Rules

- Every bug fix comes with a test that fails without the fix.
- Fixtures are real files captured from the game, kept small, each with a short note saying how it was made (Minecraft version, loader, steps).
- Tests use temporary directories and never touch a real user directory.
- Destructive operations are tested in dry-run and in real mode, and the result is compared with the expected state.
- Smoke runs longer than five minutes run in the background.

## Dev-only test mod

The test mod (mod id `pdrtest`) lives in `testmod/`: `common/` for every band, `common-<minecraft>/` for each Minecraft version, and `<minecraft>-<loader>/` for each loader. Each band compiles it as its own source set and writes `build/testmod/pdrtest-<minecraft>-<loader>.jar`, remapped like the release JAR where the loader needs it. It never goes into `build/libs`, and `check-release-artifacts.ps1` fails if a release JAR holds any of its files. Format it with `core/gradlew -p testmod spotlessApply` (CI runs `spotlessCheck`).

It reads `pdrtest.properties` from its working folder (the server folder, or the game folder of a real client) on every call, one line per player name, for example `S2Bot=attach,attachment-throws`:

| Mode | Failure |
|---|---|
| `rename-item` | After each save, the item in inventory slot 17 is renamed to `pdrtest_removed:brass_ingot`, a mod that is not installed. |
| `rename-effect` | After each save, every active effect is renamed to `pdrtest_removed:poison_cloud`. On 1.20.1, which stores effects by number, the numeric id becomes 250, and on Forge its `forge:id` gets the name. |
| `rename-vehicle` | After each save, the vehicle's entity type is renamed to `pdrtest_removed:cart`. |
| `truncate`, `truncate-both` | After each save, the player file (and with `truncate-both` its `.dat_old` copy) is cut in half. |
| `attach` | On login, the player gets the test attachment `pdrtest:broken`. Only needed on NeoForge, where `unreadable-attachment` attaches it too; on Forge and Fabric it is always saved. |
| `attachment-throws` | The test attachment's reader throws. |
| `unreadable-attachment` | The test attachment is saved in a form its reader rejects, at every save. Without the mode the reader accepts both forms, like a fixed release. |
| `throw-on-placement` | Placement throws at its end, whatever the data: from `PlayerLoggedInEvent` on Forge and NeoForge, and at the end of `PlayerList#placeNewPlayer` on Fabric, after the player was added to the player list. |
| `placement-rejects-attachment` | Placement throws at the same point, but only when that player instance read the test attachment from its saved data: a data-related T3. A placement without the attachment, such as the minimal profile, gets past it. |
| `host-data` | Fabric 1.20.1 and 1.21.1 only: while this player is online, the dedicated server writes their data into `level.dat` as `Data.Player` at each world save, as the integrated server does for its host. It produces the S8 fixture. |

The attachment is a Forge capability under `ForgeCaps`, a NeoForge data attachment under `neoforge:attachments`, and on Fabric (no Fabric API) a top-level key written and read by a mixin, the way component libraries store theirs. The renames run right after the game wrote the file: Forge's and NeoForge's `PlayerEvent.SaveToFile`, and on Fabric a mixin at the end of `PlayerDataStorage#save`. The game writes the real data first (the item, the effect and the minecart are given through RCON), so a renamed file is what a save looks like after its mod was removed. These files are produced during the smoke run and never committed.

## Headless join client

`testmod/join-bot/JoinBot.java` joins a real player without a game client (ADR-PDR-006). It needs no build: `java JoinBot.java --port 25566 --protocol 777 --name Bot --stay-seconds 20`. Protocols: 763 (1.20.1), 767 (1.21.1), 777 (26.3). It prints `[join-bot]` lines, including each system message it receives as `[join-bot] chat: <component>` (JSON on 1.20.1, network NBT on 1.21.1 and 26.3), and exits with 0 when it joined and stayed, 3 when the server disconnected it (the reason is printed), and 4 on a network error or timeout. It needs `online-mode=false`, and `white-list=false` on 26.3, whose server enables the whitelist by default.

After each teleport on 1.20.1 and 1.21.1, and then every second on every version, the client reports its position (`ServerboundMovePlayerPacket.PosRot`: ids 21 on 1.20.1, 27 on 1.21.1 and 31 on 26.3), and on 26.3 it ends each of those client ticks with `ServerboundClientTickEndPacket` (id 13). On 1.20.1 and 1.21.1 the server moves its chunk tracking only when the client reports a position, not when it accepts a teleport, so without the report a player teleported with RCON stays tracked at its join position. On 26.3 the server accepts one position per client tick and resets that limit on the tick end packet; a second position in the same tick disconnects the client with "Invalid move player packet received". On 26.3 the teleport confirmation carries the position, and the vanilla client sends no position after it (`ClientPacketListener#handleMovePlayer`); the join client does the same. The ids come from `ConnectionProtocol` (1.20.1) and from the `--reports` packet list (1.21.1, 26.3), the layout (three doubles, two floats, one flag byte) from each version's `ServerboundMovePlayerPacket`.

## Failure scenarios

Unless `-SkipScenarios` is given, `prod-smoke.ps1` also installs the test mod and runs `scripts/smoke-scenarios.ps1` before stopping the server, so CI runs them for every band. After the scenarios, the script checks the join client itself: `TpBot` joins, RCON puts it in creative mode, so that it can stand in the air at the destination (the smoke config also sets `allow-flight=true`, since the join client never falls) and teleports it 1000 blocks east of where it joined, at y 100. That far from the join position, neither the spawn chunks nor the chunks around the join position cover the destination, whatever the world's random spawn. `execute at TpBot if loaded ~ ~-1 ~` must then pass at every check, every 2 seconds from 8 to 24 seconds after the teleport, and a 16 by 16 `fill` of glass under the player must answer "Successfully filled" at the end, while the client stays connected. The ticket of the teleport itself keeps the destination loaded for a few server ticks, which take longer on a server that just started, so the check runs after the scenarios and lasts well past that ticket. Without the position report, the destination unloads on 1.20.1 and 1.21.1.

Each scenario uses its own player. One or two first joins save real data and let the test mod break it, and the script checks the test mod's log line that proves it (for example `saved attachment pdrtest:broken of S2Bot as int`). The player files at that point are copied to `build/prod-smoke/fixtures/<band>-<loader>/<scenario>/` (see Fixtures below). Each later join must log the failure the scenario causes (`pdrtest: deliberate failure: ...`, which NeoForge also logs when it skips the attachment), and records the outcome: `invalid` (the client got the `multiplayer.disconnect.invalid_player_data` disconnect, and the log has `Couldn't place player in world`) or `joined`. The script then checks the incident folders the scenario wrote under `smokeworld/playerdatarescue/<uuid>/`: their number, `report.json` (trigger, outcome, the one entry set aside and its payload file, the suspect), the files in `original/`, the wording of `report.txt`, and the message the player's client received. A rescued join must not log `Couldn't place player in world`.

Observed on 2026-11-16 on every Tier A band (Forge and Fabric 1.20.1; Forge, Fabric and NeoForge 1.21.1 and 26.3):

| Scenario | Broken data | Forge | Fabric | NeoForge |
|---|---|---|---|---|
| S1 | Inventory item from a removed mod | joined, item lost, no incident (T4) | same | same |
| S2 | Attachment whose reader throws | joined; one incident, T1, `attachment.pdrtest:broken` set aside, suspect pdrtest | joined; same with `key.pdrtest:broken` | joined, attachment lost (`Failed to deserialize data attachment`), no incident (T4) |
| S3 | Effect from a removed mod | joined, effect lost, no incident (T4) | same | same |
| S4 | Vehicle from a removed mod | joined, not mounted, no incident (T4) | same | same |
| S5 | Truncated `.dat`, valid `.dat_old` | joined with `.dat_old`; one incident, T2, `previous_save`, both files in `original/` | same | same |
| S6 | Truncated `.dat` and `.dat_old` | joined as a new player; one incident, T2, `unreadable_file`, both files in `original/` | same | same |
| S11 | Placement throws for any data, saved data present | invalid at both joins; one incident at the first: T3, the half-placed player removed, the minimal profile placed and failing on the test mod, `not_data_related`, suspect pdrtest, original file back; the second join tries no rescue | same | same |
| S11 (new player) | Placement throws, no saved data | invalid; one incident, T3, `not_data_related` with no original files, suspect pdrtest | same | same |
| S12 | Valid data | joined, no incident; normal path under 100 µs on 2026-11-24, with the packet hold (Normal path cost below) | same | same |
| S13 | Attachment saved in a form it cannot read back | joined at both joins; one incident per join (recurring issues are M3) | same | joined, attachment lost, no incident (T4) |
| S15 (2026-11-24) | Placement fails only when the data holds the test attachment | joined with one login packet; one incident, T3, `minimal_profile`, `section.ForgeCaps` set aside with the other sections, suspect pdrtest | joined; same with `key.pdrtest:broken` | joined; same with `section.neoforge:attachments` |
| S16 (2026-11-25) | S15, then the placement accepts the attachment again; `section.Inventory` (12 bricks in slot 17) restored offline twice by RCON | joined after each restore; the 12 bricks back in slot 17 once, the second restore answers `Already restored`, still 12 bricks; the entry `restored`; one `restore_backup` incident with `original/` | same | same |

"Lost" means the game drops the unit while loading, and the next save writes the player without it. Those units are trigger T4 (SPEC.md section 2), a later milestone. The details per band are in [ARCHITECTURE.md](ARCHITECTURE.md), Hook points.

For S2, an operator (`OpBot`, made operator over RCON) is online during the rescue and must receive the operator notice. Every bot speaks the vanilla protocol without this mod (it is not the real game client) and prints each system message it receives (`[join-bot] chat: ...`), which covers S9: the client connects normally and the messages decode as vanilla text components.

The ways a restore join can end that a dedicated server does not produce on demand (a join that fails after the restore wrote the data, a rescue that sets a restored stack aside again, two incidents with the same entry id, a crash between the write and the join's end, stacks with no free slot) are covered by the core tests `RestoreJoinTest`, `RestoreBackupTest` and `RestorerTest`, on the real player files of S1Bot (Fabric 1.20.1), S2Bot (Forge 1.20.1) and S4Bot (Forge 1.21.1): none of them may leave the same items restorable twice.

After the scenarios, the server is stopped and started again without the release JAR (S10): the world must load, `S12Bot` must join, the mod must log nothing, and every file under `playerdatarescue/` must keep its SHA-256.

## Normal path cost (S12)

With `-Dplayerdatarescue.timing=true`, which `prod-smoke.ps1` passes to the server when it runs the scenarios, the mod adds up the time spent in its hooks during a join, measured with `System.nanoTime()` around the body of each hook, and logs it when the login handler returns: `normal path for S12Bot: <µs> us in <n> hook call(s)`. Without the property, the timing costs one static field read per hook; the hooks themselves still run. S12 fails above 1000 µs. The hooks on a normal join are the login handler's return hook on every band, and on 1.20.1 also the read hook of `PlayerDataStorage#load`; the placement guard and the unreadable file hook only run on a failure. The hold and the two releases of the placement's packets are timed too (three more hook calls). A release sends the packets the game queued during the placement, so its time includes the game's own sends (chunks and entities in view, more with a larger view distance): the measure is an upper bound of the mod's cost, which is a list entry per held packet and a second pass through `Connection#send`. Each packet the server sends also goes through the hold check of `Connection#send`, which is left out of the measure: when nothing is held it is a callback object and one volatile field read, and timing it would cost more than it does.

Measured on 2026-11-24 in the production-JAR smoke test (`./scripts/prod-smoke.ps1 -SkipBuild -RealClient`, one band at a time, Linux laptop), second join of `S12Bot`, whose data was saved by its first join:

| Band | Forge | Fabric | NeoForge |
|---|---|---|---|
| 1.20.1 (5 hook calls) | 54.9 µs | 77.6 µs | |
| 1.21.1 (4 hook calls) | 15.6 µs | 28.4 µs | 82.1 µs |
| 26.3 (4 hook calls) | 18.5 µs | 24.7 µs | 35.0 µs |

Before the packet hold (2026-11-16, Linux container, 4 cores, 2 hook calls on 1.20.1 and 1 elsewhere), the same join took 2.9 to 9.1 µs. The difference is mostly the release, which sends the game's own packets of the placement.

The measure covers the hook bodies only, not the time the game spends loading the player, which the mod does not change on a normal join. These hooks run on a cold JIT at that point, so later joins are likely cheaper.

## Fixtures

The core tests (`core/src/test`) read real player files and `level.dat` files from `core/src/test/resources/fixtures/`. They were saved by the dedicated servers of the smoke test, with the test mod; `fixtures/README.md` says how each one was made. The smoke test copies the player files of each scenario after its preparation joins to `build/prod-smoke/fixtures/<band>-<loader>/<scenario>/` (and the S8 `level.dat` on Fabric 1.20.1 and 1.21.1), so a fixture can be made again with one smoke run.

## Real client checks

Some results depend on the real game client, which the join client only imitates: whether the client survives the T3 retry, which places the player a second time on one connection (ADR-PDR-012), and how it shows the player message and the operator notice. These checks start the real client with portablemc 5.0.6 and an offline account (ADR-PDR-011). The client opens a game window, so they run on a desktop session and not in CI; the script closes the windows it opened.

Setup, once:

1. Download portablemc 5.0.6 from its GitHub release page (`mindstorm38/portablemc`), check the archive with the project's PGP key, and unpack it outside the repository.
2. Set `PDR_PORTABLEMC` to the `portablemc` binary (or pass `-Launcher`), and optionally `PDR_CLIENT_ROOT` to a folder for the game versions, libraries and assets (about 1.5 GB for the three vanilla clients; the default is `build/real-client/.minecraft`).
3. Build the bands: `./scripts/build-all-version-bands.ps1 -SkipSmoke`.

Dedicated server, vanilla client of the band: `./scripts/prod-smoke.ps1 -BandId <band> -Loader <loader> -SkipBuild -RealClient`. After the join client scenarios, a real client joins as an operator (`OpClient`) and stays, then real clients replay three scenarios:

- S2 (`S2Client`, Forge and Fabric; on NeoForge the attachment is skipped by the game, T4): the client must show the player message.
- S15 (`S15Client`): the placement fails, and the minimal profile is placed on the same connection. The client must stay connected for 30 seconds after the rescue, show a chat message sent after that time, fall back when RCON lifts it 3 blocks (the server sees its height drop within 5 seconds), be in the player list, show the player message, and log no disconnect, packet error or crash.
- S11 (`S11Client`), two joins: at the first every placement fails, so the client never gets a login packet, only the game's disconnect; at the second no rescue is tried, and the disconnect lets out the packets the connection held. Each time the client must show "Invalid player data" and log no packet error or crash. The 1.20.1 client shows the reason without logging it, so on 1.20.1 the server's `lost connection: Invalid player data` line is the proof (the screen was checked by eye on Forge 1.20.1 on 2026-11-24).

S2 and S15 check the fall. A client that got a second login packet passed every other check but this one: its player stayed in the air, and shook on screen (ARCHITECTURE.md, Cleanup after a failed placement).

`OpClient` must show the operator notice of each rescue. Every client is closed through its window, as a player closes it (on Windows and Hyprland; elsewhere the script sends the game `SIGTERM`).

Observed on 2026-11-24 with portablemc 5.0.6, on every Tier A band (Forge and Fabric 1.20.1; Forge, Fabric and NeoForge 1.21.1 and 26.3), on the code of this change: S15 placed the player twice on the server, its join client got one login packet, and the vanilla client stayed 30 seconds, fell 3 blocks back after the lift, showed the chat marker and the player message, and logged no error (the vanilla client's own count of login packets is not read: the fall is its proof); S11 disconnected the vanilla client twice with "Invalid player data", shown in its log on 1.21.1 and 26.3, and no packet error; S2 passed on Forge and Fabric; `OpClient` showed every operator notice; S10 passed after the run. Before the packet hold, on Fabric 1.21.1, S15 passed every other check while a lift by RCON showed its player stuck in the air.

## S8 with the real client: singleplayer host

The integrated server only exists with a real game client. `HostDataTest` covers the core part on a real `level.dat` whose `Data.Player` holds the test attachment; this check runs the whole path on one band, with the setup of Real client checks above:

`./scripts/real-client-singleplayer.ps1 -BandId <band> -Loader <loader>`

1. The band's dedicated server makes an empty world (`prod-smoke.ps1 -SkipScenarios`), copied into the client's game folder as the singleplayer world `S8`.
2. The client opens it with the release JAR and the test mod: the host player is created, and the test mod saves its attachment `pdrtest:broken` in the host data when the window closes.
3. Control: a copy of that world, opened with the test mod only while the attachment's reader throws (`attachment-throws`), must fail as the game does without the mod (`Couldn't place player in world`).
4. With both JARs and the same mode: the host joins, the chat shows the player message, and one incident is written: trigger T1, outcome rescued, only the attachment set aside with its payload file, pdrtest among the suspects, and the host data in `original/` (`level.dat` and `level.dat_old` on 1.20.1 and 1.21.1, the host's player file and its `.dat_old` on 26.3).
5. Opened again with a reader that works: the host joins and no new incident is written.

No opening may leave a crash report, also one written while the game closes. Each window is closed as a player closes it, so the world is saved: the script does it on Windows and on Hyprland; elsewhere it stops the game with `SIGTERM`, which was not tried for S8. On NeoForge the game skips the unreadable attachment and logs it (T4): the control and the rescue opening both join, and no incident is expected.

Two Forge problems made the client fail this check before the workarounds below:

- A Forge mod file without `pack.mcmeta` has no valid pack: the dedicated servers of the three Forge bands log "Missing metadata in pack", and Forge 1.20.1 also records a loading warning, so its client stops on a "failed to load a valid ResourcePackInfo" screen at each start and a singleplayer world never opens by itself. The Forge JARs and test mods now carry one (`pack_format` as in each Forge MDK), and `check-release-artifacts.ps1` requires it.
- Forge 1.21.1 (52.1.20) starts its game event bus only after the client has opened a quick play world (`ClientModLoader.completeModLoading` runs after `Minecraft#onResourceLoadFinished`). That world's `AddReloadListenerEvent` is lost, the loot modifier manager is never created, and the first loot roll (a leaf decaying, water breaking a block) crashes the integrated server with "Can not retrieve LootModifierManager until resources have loaded once". It happened in 9 of 12 openings, with or without any mod. A world opened from the title screen and a dedicated server are not affected. Forge 47 also holds the bus until `completeModLoading`, but calls it before it prepares the quick play world. The Forge 1.21.1 test mod starts the bus at client setup, during mod loading: 8 openings in a row then had no crash. The Forge 26.3 test mod has no such workaround, and its S8 openings had no crash.

Observed on 2026-11-25 with portablemc 5.0.6, on every Tier A band, on the code of this change: S8 passed on Forge and Fabric 1.20.1, Forge, Fabric and NeoForge 1.21.1, and Forge, Fabric and NeoForge 26.3. The control failed with `Couldn't place player in world` on Forge and Fabric, and logged `Failed to deserialize data attachment` and joined on NeoForge.

## Scenarios

The acceptance scenarios are listed in [SPEC.md](SPEC.md). Each one maps to at least one automated test.
