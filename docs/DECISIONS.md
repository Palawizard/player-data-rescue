# Decisions

Architecture decision records for Player Data Rescue. Each record has a status: Proposed, Accepted or Superseded. New records are added at the end. A decision that changes gets a new record, and the old one is marked "Superseded by ADR-x".

## ADR-001: Build layout

Status: Accepted.

The project reuses the layout of Pala's Item Clear: one standalone Gradle project per band and loader under `versions/`, a `versions/version-matrix.json` file shared by the build, smoke and release tooling, and per-band Gradle wrappers. Unlike Pala's Item Clear, the 1.20.1 band is also a set of standalone projects instead of an Architectury Loom root.

The shared logic lives in a single pure-Java core that every band includes. How the bands include it (a source directory or a subproject) is settled in M0 and recorded here. Shared logic is never copied into a band.

Why: the layout already ships 21 artifacts for Pala's Item Clear, with working smoke and release tooling, and avoiding Architectury keeps the runtime dependency list empty (ADR-003).

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
