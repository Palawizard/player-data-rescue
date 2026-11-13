# Release checklist

Releases follow the same flow as Pala's Item Clear: the version lives in the repository, and pushing a `v<version>` tag publishes everything.

## Before the release

1. On `dev`, move the `CHANGELOG.md` entries from `Unreleased` to the new version.
2. Write `.github/release-notes/v<version>.md`.
3. Bump `mod_version` in the root `gradle.properties`. The core and every band read it from there.
4. Run the full checks: build of every band, unit tests, `check-release-artifacts`, and the production-JAR smoke test on every band and loader (`prod-smoke-all`).
5. Open a pull request from `dev` to `main` so CI runs on the exact commit to release.

## Release

6. Fast-forward `main` to `dev` (`git push origin dev:main`), only on the maintainer's explicit request.
7. Tag the same commit `v<version>` and push the tag, only on the maintainer's explicit request.

## What the tag does

- The `release` job checks out the repository, checks that the tag matches `mod_version` and that the release notes file exists, then creates the GitHub release with every JAR (`release/**/*.jar`).
- The `publish-mods` job publishes one file per band and loader to Modrinth and CurseForge with `Kir-Antipov/mc-publish@v3`. The version string is `<version>+<band>-<loader>`. Game versions are passed one per line.
- The `finalize-modrinth-metadata` job sets each new Modrinth version's environment (server only) through the Modrinth API, because mc-publish cannot set it.

## Store setup (once)

- Create the Modrinth and CurseForge projects.
- Store their ids in the repository variables `MODRINTH_ID` and `CURSEFORGE_ID`.
- Set the secrets `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN` with `gh secret set`. Never commit them. The CurseForge upload token comes from `legacy.curseforge.com/account/api-tokens`.
- Modrinth categories must describe the mod accurately. A mod that changes world or player state is not an optimization mod.

## Notes

- CurseForge rejects a file it has already received, even after deletion. A new upload always needs a new version.
- Each loader gets its own file. CurseForge rejects multi-loader bundles.
