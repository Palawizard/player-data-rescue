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

It reads `pdrtest.properties` from the server folder on every call, one line per player name, for example `S2Bot=attach,attachment-throws`:

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

After each teleport on 1.20.1 and 1.21.1, and then every second on every version, the client reports its position (`ServerboundMovePlayerPacket.PosRot`: ids 21 on 1.20.1, 27 on 1.21.1 and 31 on 26.3), and on 26.3 it ends each of those client ticks with `ServerboundClientTickEndPacket` (id 13). On 1.20.1 and 1.21.1 the server moves its chunk tracking only when the client reports a position, not when it accepts a teleport, so without the report a player teleported with RCON stays tracked at its join position. On 26.3 the server accepts one position per client tick and resets that limit on the tick end packet; a second position in the same tick disconnects the client with "Invalid move player packet received". On 26.3 the teleport confirmation carries the position, and the vanilla client sends no position after it (`ClientPacketListener#handleMovePlayer`); the join client does the same, otherwise the burst of teleports of a T3 retry ends in "moved too quickly" and a disconnect. The ids come from `ConnectionProtocol` (1.20.1) and from the `--reports` packet list (1.21.1, 26.3), the layout (three doubles, two floats, one flag byte) from each version's `ServerboundMovePlayerPacket`.

## Failure scenarios

Unless `-SkipScenarios` is given, `prod-smoke.ps1` also installs the test mod and runs `scripts/smoke-scenarios.ps1` before stopping the server, so CI runs them for every band.After the scenarios, the script checks the join client itself: `TpBot` joins, RCON puts it in creative mode (so it can stand in the air without being kicked for flying) and teleports it 1000 blocks east of where it joined, at y 100. That far from the join position, neither the spawn chunks nor the chunks around the join position cover the destination, whatever the world's random spawn. `execute at TpBot if loaded ~ ~-1 ~` must then pass at every check, every 2 seconds from 8 to 24 seconds after the teleport, and a 16 by 16 `fill` of glass under the player must answer "Successfully filled" at the end, while the client stays connected. The ticket of the teleport itself keeps the destination loaded for a few server ticks, which take longer on a server that just started, so the check runs after the scenarios and lasts well past that ticket. Without the position report, the destination unloads on 1.20.1 and 1.21.1.

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
| S12 | Valid data | joined, no incident; normal path 2.9 to 9.1 µs (Normal path cost below) | same | same |
| S13 | Attachment saved in a form it cannot read back | joined at both joins; one incident per join (recurring issues are M3) | same | joined, attachment lost, no incident (T4) |

"Lost" means the game drops the unit while loading, and the next save writes the player without it. Those units are trigger T4 (SPEC.md section 2), a later milestone. The details per band are in [ARCHITECTURE.md](ARCHITECTURE.md), Hook points.

For S2, an operator (`OpBot`, made operator over RCON) is online during the rescue and must receive the operator notice. Every bot speaks the vanilla protocol without this mod (it is not the real game client) and prints each system message it receives (`[join-bot] chat: ...`), which covers S9: the client connects normally and the messages decode as vanilla text components.

After the scenarios, the server is stopped and started again without the release JAR (S10): the world must load, `S12Bot` must join, the mod must log nothing, and every file under `playerdatarescue/` must keep its SHA-256.

## Normal path cost (S12)

With `-Dplayerdatarescue.timing=true`, which `prod-smoke.ps1` passes to the server when it runs the scenarios, the mod adds up the time spent in its hooks during a join, measured with `System.nanoTime()` around the body of each hook, and logs it when the login handler returns: `normal path for S12Bot: <µs> us in <n> hook call(s)`. Without the property, the timing costs one static field read per hook; the hooks themselves still run. S12 fails above 1000 µs. The hooks on a normal join are the login handler's return hook on every band, and on 1.20.1 also the read hook of `PlayerDataStorage#load`; the placement guard and the unreadable file hook only run on a failure.

Measured on 2026-11-16 in the production-JAR smoke test (`./scripts/build-all-version-bands.ps1`, Linux container, 4 cores), second join of `S12Bot`, whose data was saved by its first join:

| Band | Forge | Fabric | NeoForge |
|---|---|---|---|
| 1.20.1 (2 hook calls) | 9.1 µs | 8.6 µs | |
| 1.21.1 (1 hook call) | 2.9 µs | 7.1 µs | 4.7 µs |
| 26.3 (1 hook call) | 7.0 µs | 4.7 µs | 6.1 µs |

The measure covers the hook bodies only, not the time the game spends loading the player, which the mod does not change on a normal join. These hooks run on a cold JIT at that point, so later joins are likely cheaper.

## Fixtures

The core tests (`core/src/test`) read real player files and `level.dat` files from `core/src/test/resources/fixtures/`. They were saved by the dedicated servers of the smoke test, with the test mod; `fixtures/README.md` says how each one was made. The smoke test copies the player files of each scenario after its preparation joins to `build/prod-smoke/fixtures/<band>-<loader>/<scenario>/` (and the S8 `level.dat` on Fabric 1.20.1 and 1.21.1), so a fixture can be made again with one smoke run.

## Real client checks

Some results depend on the real game client, which the join client only imitates: whether the client takes a second login packet on one connection (the T3 retry, ADR-PDR-012), how it shows the player message and the operator notice, and the singleplayer host of S8. These checks start the real client with portablemc 5.0.6 and an offline account (ADR-PDR-011). The client opens a game window, so they run on a desktop session and not in CI; each script closes the windows it opened.

Setup, once:

1. Download portablemc 5.0.6 from its GitHub release page (`mindstorm38/portablemc`), check the archive with the project's PGP key, and unpack it outside the repository.
2. Set `PDR_PORTABLEMC` to the `portablemc` binary (or pass `-Launcher`), and optionally `PDR_CLIENT_ROOT` to a folder for the game versions, libraries and assets (about 1.5 GB for the three vanilla clients; the default is `build/real-client/.minecraft`).
3. Build the bands: `./scripts/build-all-version-bands.ps1 -SkipSmoke`.

Dedicated server, vanilla client of the band: `./scripts/prod-smoke.ps1 -BandId <band> -Loader <loader> -SkipBuild -RealClient`. After the join client scenarios, a real client joins as an operator (`OpClient`) and stays, then real clients replay two scenarios:

- S2 (`S2Client`, Forge and Fabric; on NeoForge the attachment is skipped by the game, T4): the client must show the player message.
- S15 (`S15Client`): the failed placement sends a first login packet and the minimal profile a second one on the same connection. The client must stay connected for 30 seconds after the rescue, show a chat message sent after that time, be in the player list, show the player message, and log no disconnect, packet error or crash.

`OpClient` must show the operator notice of each rescue. Both clients are closed through their window, as a player closes them.

## Manual check: S8, singleplayer host

The integrated server only exists with a real game client, so S8 is checked by hand. The core part is covered by `HostDataTest` on a real `level.dat` whose `Data.Player` holds the test attachment.

Setup, once per band (Fabric 1.20.1 and Fabric 1.21.1; Forge 1.20.1 and Forge 1.21.1 work the same way with their test mod):

1. Build the band: `./scripts/build-all-version-bands.ps1 -SkipSmoke -OnlyBands 1.20-1.20.1 -OnlyLoaders fabric` (or `1.21-1.21.4`).
2. Install the matching client profile in the official launcher: Minecraft 1.20.1 (or 1.21.1) with Fabric Loader 0.20.1, in a fresh game directory.
3. Put two JARs in its `mods` folder: `versions/1.20.1-fabric/build/libs/player-data-rescue-1.20.1-fabric-0.1.0.jar` and `versions/1.20.1-fabric/build/testmod/pdrtest-1.20.1-fabric.jar`.

Steps:

1. Start the game and create a singleplayer world named `S8` (creative, flat). Note your player name (shown in the multiplayer screen or the launcher).
2. Leave the world. The test mod has saved its attachment `pdrtest:broken` in your host data (`saves/S8/level.dat`, `Data.Player`).
3. Close the game. In the game directory, create `pdrtest.properties` with one line: `<your name>=attachment-throws`.
4. Start the game and open the world `S8`.
5. Expected: the world opens and you are in it, at the position where you left, with your inventory. The chat shows "Some of your saved data could not be loaded and was set aside. An admin can restore it."
6. Close the game. Check `saves/S8/playerdatarescue/<your uuid>/<date>/`: `original/` holds `level.dat` and `level.dat_old`; `report.txt` lists "Mod data under the key pdrtest:broken (pdrtest)" with pdrtest as most likely, and `quarantine/key.pdrtest_broken.nbt` exists. On Forge the attachment is a capability: the line is "Attachment pdrtest:broken (pdrtest)" and the file `quarantine/attachment.pdrtest_broken.nbt`.
7. Remove `pdrtest.properties`, open the world again: no new incident folder is created.

Without the mod (control): the same steps with only the test mod in `mods` end at step 4 with "Failed to load" or a return to the title screen, and the log shows `Couldn't place player in world`.

## Scenarios

The acceptance scenarios are listed in [SPEC.md](SPEC.md). Each one maps to at least one automated test.
