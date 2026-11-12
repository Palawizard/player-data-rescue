# Contributing to Player Data Rescue

Thanks for your interest. This file covers branches, commits, pull requests and the checks a change must pass. The behaviour contract is in [docs/SPEC.md](docs/SPEC.md) and the code layout in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Language

Code, identifiers, comments, commit messages, pull requests, docs and in-game text are in English. In-game text goes through translation keys (`assets/playerdatarescue/lang/en_us.json` is the source).

## Branches

`main` holds released code and `dev` is the integration branch. They are the only long-lived branches.

Work happens on a branch named `type/short-description` in kebab-case, created from an up-to-date `dev`. Examples: `feat/quarantine-store`, `fix/forge-1201-login-hook`, `build/add-26-3-band`, `docs/update-support-matrix`.

Merged branches are deleted automatically, except `main` and `dev`.

## Commits

Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
type(scope): description
```

- Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`.
- Scopes are optional, for example `core`, `fabric`, `forge`, `neoforge`, `config`, `cmd` or a band such as `mc-1.21.1`.
- The description is imperative, lowercase, without a final period, and the whole line is 72 characters at most.
- Write a body only when the reason for the change is not obvious from the diff.
- One commit per logical change. Formatting-only changes get their own `style` commit.
- Breaking changes add `!` after the type or scope and a `BREAKING CHANGE:` footer.
- No `Co-authored-by` trailers, tool signatures or generated-by footers.

## Pull requests

- Open pull requests against `dev`. The title follows the commit format.
- Pull requests into `dev` are merged with a merge commit, so the individual commits stay in history. Squash and rebase merges are disabled.
- CI must be green before review. Nothing is pushed directly to `dev` or `main`.
- The description says what changed, why, and which bands and loaders were tested.

## Checks

A change is ready for review when:

1. Every band in [docs/SUPPORT.md](docs/SUPPORT.md) still builds.
2. The unit tests of the shared core pass, and new logic has tests based on real fixtures.
3. The production-JAR smoke test passes on a real dedicated server for every band and loader the change touches (see [docs/TESTING.md](docs/TESTING.md)).
4. Formatting passes (Spotless).
5. New user-facing text has translation keys.
6. The docs that describe the changed behaviour are updated in the same pull request.

### Commands

The scripts run on PowerShell 7 (`pwsh`, Windows, Linux or macOS) and on Windows PowerShell 5.1, from the repository root. They need JDK 17, 21 and 25 installed. Each project runs Gradle on the Java version set by `gradleJava` in `versions/version-matrix.json` and compiles with the band's own toolchain. To run a band's wrapper by hand, set `JAVA_HOME` to that version first.

```text
./scripts/build-all-version-bands.ps1 -SkipSmoke                 core tests, formatting, every band
./scripts/build-all-version-bands.ps1                            the same, then the smoke test of every band
./scripts/prod-smoke.ps1 -BandId 1.21-1.21.4 -Loader neoforge    one band and loader on a real server
./scripts/prod-smoke-all.ps1 -SkipBuild                          every band and loader on a real server
./scripts/check-release-artifacts.ps1 -SkipBuild                 contents of the built JARs
```

Band ids and loaders are listed in `versions/version-matrix.json`. Server installs are cached in `build/prod-smoke`, and `prod-smoke-all.ps1` skips a JAR that already passed. To fix formatting, run `./gradlew spotlessApply` in `core/` or in a band folder.

## Releases

Releases are cut from `dev` and promoted to `main`. The steps are in [docs/release-checklist.md](docs/release-checklist.md).

## Code style

- Java at the version each band requires (17, 21 or 25). The shared core compiles on Java 17.
- Formatting with Spotless and palantir-java-format. `.editorconfig` sets UTF-8, LF and 4-space indentation.
- No wildcard imports, no commented-out code. Comments explain a reason that the code cannot show.
- Files stay under 500 lines where possible and never exceed 800.
- Logging goes through the loader's SLF4J logger. No `System.out`, no swallowed exceptions.
- No runtime dependency beyond the loader. See ADR-003 in [docs/DECISIONS.md](docs/DECISIONS.md).
