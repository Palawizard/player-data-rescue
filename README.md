# Player Data Rescue

### Lets players back into the world when their saved data no longer loads

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20%20to%2026.3-62B47A?logo=minecraft&logoColor=white)](docs/SUPPORT.md)
[![Loaders](https://img.shields.io/badge/Loaders-Forge%20%7C%20Fabric%20%7C%20NeoForge-2d6a4f)](docs/SUPPORT.md)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

In development. There is no release yet.

---

## What it does

After a mod is updated, removed or rolled back, a player's save can hold mod data that throws while it loads, for example a Forge capability or a Fabric mod's own key. A save can also be truncated after a crash. Minecraft then refuses the connection with "Invalid player data", or starts the player from scratch, and the usual fix is to delete the player's file, which wipes their inventory and position.

With Player Data Rescue installed, the server backs up the player's file first, loads every part that still works, sets the broken parts aside, and lets the player in. Admins get a report that lists what was set aside and the mods that most likely caused it. A command restores the set-aside parts later, for example once the missing mod is back.

When the failure does not come from the saved data at all (a mod that crashes on every login, even with a fresh player), the mod does not try to rescue anything. It writes a report that names the likely mod instead.

- Server-side only. Clients do not need it, and it also works in singleplayer.
- Adds no blocks, items or registry entries, so it can be added to or removed from an existing world.
- No dependencies beyond the loader.

---

## Installation

1. Download the JAR that matches your Minecraft version band and loader from the releases page (see [docs/SUPPORT.md](docs/SUPPORT.md)).
2. Put it in the server's `mods` folder.
3. Start the server once to create `config/playerdatarescue.json`.

---

## Usage

Rescues happen automatically. Each rescue writes a folder under `<world>/playerdatarescue/<player uuid>/` with the original files, the set-aside parts and a plain-text report.

| Command | Purpose |
| --- | --- |
| `/pdrescue list [player]` | List rescues |
| `/pdrescue show <player> <incident>` | Show what was set aside and why |
| `/pdrescue restore <player> <incident> [entry\|all]` | Put set-aside parts back |
| `/pdrescue dismiss <player> <incident> [entry\|all]` | Mark parts as handled |
| `/pdrescue export <player> <incident>` | Print the folder path for a bug report |

Commands require permission level 3 by default. The exact behaviour is in [docs/SPEC.md](docs/SPEC.md).

---

## Documentation

- [docs/SPEC.md](docs/SPEC.md): behaviour and acceptance scenarios
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): code layout and hook points
- [docs/SUPPORT.md](docs/SUPPORT.md): supported versions and loaders
- [docs/TESTING.md](docs/TESTING.md): test strategy
- [docs/DECISIONS.md](docs/DECISIONS.md): decision records
- [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/release-checklist.md](docs/release-checklist.md)

---

## License

Distributed under the [MIT License](LICENSE).
