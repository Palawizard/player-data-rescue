# Core test fixtures

Every file here was written by a real dedicated server with the dev-only test mod (`testmod/`, docs/TESTING.md), during the production-JAR smoke test of 2026-11-16 (`scripts/prod-smoke.ps1`). The smoke test copies the player files of each scenario right after its preparation joins to `build/prod-smoke/fixtures/<band>-<loader>/<scenario>/`; the files below were taken from there. Players are offline-mode players named `<scenario>Bot`. Values the tests expect come from the setup commands, not from the code under test.

| File | Minecraft, loader | How it was made |
|---|---|---|
| `1.20.1-fabric/S1Bot.dat` | 1.20.1, Fabric 0.20.1 | Joined; RCON `item replace entity S1Bot container.17 with minecraft:brick 12`; left. Test mod mode `rename-item` renamed slot 17 to `pdrtest_removed:brass_ingot` after the save. |
| `1.21.1-fabric/S1Bot.dat` | 1.21.1, Fabric 0.20.1 | Same as above, 1.21.1 item format (`count`). |
| `1.20.1-forge/S2Bot.dat` | 1.20.1, Forge 47.4.28 | Joined and left with mode `attach`: the test capability `pdrtest:broken` is saved under `ForgeCaps` as the int 42. |
| `1.20.1-fabric/S2Bot.dat` | 1.20.1, Fabric 0.20.1 | Same; on Fabric the test attachment is the top-level key `pdrtest:broken` (int 42). |
| `1.21.1-neoforge/S2Bot.dat` | 1.21.1, NeoForge 21.1.266 | Same; NeoForge attachment `pdrtest:broken` under `neoforge:attachments` as the int 42. |
| `26.3-fabric/S2Bot.dat` | 26.3, Fabric 0.20.1 | Same as 1.20.1 Fabric, 26.3 file layout. |
| `1.21.1-fabric/S3Bot.dat` | 1.21.1, Fabric 0.20.1 | Joined; RCON `effect give S3Bot minecraft:speed 600`; left. Mode `rename-effect` renamed the effect to `pdrtest_removed:poison_cloud`. |
| `1.21.1-forge/S4Bot.dat` | 1.21.1, Forge 52.1.20 | Joined; RCON summoned a minecart and mounted it; left. Mode `rename-vehicle` renamed the vehicle to `pdrtest_removed:cart`. |
| `1.20.1-fabric/S5Bot.dat`, `S5Bot.dat_old` | 1.20.1, Fabric 0.20.1 | Joined and left twice; the second time mode `truncate` cut `.dat` in half. `.dat_old` is the valid previous save. |
| `1.21.1-fabric/S6Bot.dat`, `S6Bot.dat_old` | 1.21.1, Fabric 0.20.1 | Joined and left twice; the second time mode `truncate-both` cut both files in half. |
| `1.20.1-forge/S12Bot.dat`, `1.21.1-forge/S12Bot.dat`, `1.21.1-neoforge/S12Bot.dat`, `26.3-forge/S12Bot.dat`, `26.3-neoforge/S12Bot.dat` | Forge 47.4.28, 52.1.20, 66.1.5; NeoForge 21.1.266, 26.3.0.102 | Joined and left with no mode: data written by the game and the loader only (plus the Forge test capability under `ForgeCaps`). |
| `1.21.1-fabric/TpBot.dat` | 1.21.1, Fabric 0.20.1 | Joined; RCON `gamemode creative TpBot`, then `execute at TpBot run tp TpBot ~1000 100 ~`, answered "Teleported TpBot to 998.500000, 100.000000, -8.500000"; left. |
| `1.20.1-fabric/level.dat`, `level.dat_old` | 1.20.1, Fabric 0.20.1 | S8Bot joined with mode `host-data`, then RCON `save-all flush`: the game's own world save wrote S8Bot's data as `Data.Player`, as the integrated server does for its host. |
| `1.21.1-fabric/level.dat`, `level.dat_old` | 1.21.1, Fabric 0.20.1 | Same on 1.21.1. |
