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
| `rename-effect` | After each save, every active effect is renamed to `pdrtest_removed:poison_cloud` (on 1.20.1, numeric id 250, and Forge's `forge:id`). |
| `rename-vehicle` | After each save, the vehicle's entity type is renamed to `pdrtest_removed:cart`. |
| `truncate`, `truncate-both` | After each save, the player file (and with `truncate-both` its `.dat_old` copy) is cut in half. |
| `attach` | On login, the player gets the test attachment `pdrtest:broken` (NeoForge only; on Forge and Fabric it is always saved). |
| `attachment-throws` | The test attachment's reader throws. |
| `unreadable-attachment` | The test attachment is saved in a form its reader rejects, at every save. Without the mode the reader accepts both forms, like a fixed release. |
| `throw-on-placement` | Placement throws, whatever the data. |

The attachment is a Forge capability under `ForgeCaps`, a NeoForge data attachment under `neoforge:attachments`, and on Fabric (no Fabric API) a top-level key written and read by a mixin, the way component libraries store theirs. The renames run right after the game wrote the file: Forge's and NeoForge's `PlayerEvent.SaveToFile`, and on Fabric a mixin at the end of `PlayerDataStorage#save`. The game writes the real data first (the item, the effect and the minecart are given through RCON), so a renamed file is what a save looks like after its mod was removed. These files are produced during the smoke run and never committed.

## Headless join client

`testmod/join-bot/JoinBot.java` joins a real player without a game client (ADR-PDR-006). It needs no build: `java JoinBot.java --port 25566 --protocol 777 --name Bot --stay-seconds 20`. Protocols: 763 (1.20.1), 767 (1.21.1), 777 (26.3). It prints `[join-bot]` lines and exits with 0 when it joined and stayed, 3 when the server disconnected it (the reason is printed), and 4 on a network error or timeout. It needs `online-mode=false`, and `white-list=false` on 26.3, whose server enables the whitelist by default.

## Failure scenarios

Unless `-SkipScenarios` is given, `prod-smoke.ps1` also installs the test mod and runs `scripts/smoke-scenarios.ps1` before stopping the server, so CI runs them for every band. Each scenario uses its own player. One or two first joins save real data and let the test mod break it, and the script checks the test mod's log line that proves it. The next join records the outcome: `invalid` (the client got the `multiplayer.disconnect.invalid_player_data` disconnect, and the log has `Couldn't place player in world`) or `joined`. The run fails when an outcome differs from the vanilla outcome on record below. Until the rescue code exists, this table is the behaviour of the game and its loader with no rescue.

Observed on 2026-11-13 on every Tier A band (Forge and Fabric 1.20.1; Forge, Fabric and NeoForge 1.21.1 and 26.3):

| Scenario | Broken data | Forge | Fabric | NeoForge |
|---|---|---|---|---|
| S1 | Inventory item from a removed mod | joined, item lost | joined, item lost | joined, item lost |
| S2 | Attachment whose reader throws | invalid | invalid | joined, attachment lost (`Failed to deserialize data attachment`) |
| S3 | Effect from a removed mod | joined, effect lost | joined, effect lost | joined, effect lost |
| S4 | Vehicle from a removed mod | joined, not mounted (`Skipping Entity with id`) | same | same |
| S5 | Truncated `.dat`, valid `.dat_old` | joined (from the sources: as a new player on 1.20.1, from `.dat_old` with a `_corrupted_` copy on 1.21.1 and 26.3) | same | same |
| S6 | Truncated `.dat` and `.dat_old` | joined as a new player | same | same |
| S11 | Placement throws for any data | invalid, at every join | invalid | invalid |
| S13 | Attachment saved in a form it cannot read back | invalid, at every join | invalid | joined, attachment lost |

"Lost" means vanilla drops the unit while loading, and the next save writes the player without it. The details per band are in [ARCHITECTURE.md](ARCHITECTURE.md), Hook points.

## Scenarios

The acceptance scenarios are listed in [SPEC.md](SPEC.md). Each one maps to at least one automated test.
