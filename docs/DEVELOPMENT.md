# Development and validation

The project targets Java 11 and pins RuneLite 1.13.2, matching the current Plugin Hub target. It uses the standard Hub build and has no additional runtime dependencies. The public plugin entry point is `com.mygoals.MyGoalsPlugin`. Existing `mygoals` configuration/storage identifiers are intentionally stable across the display-name change.

## Build

```sh
./gradlew --no-daemon check launcherCheck verifyRelease uiPreview
python3 -m unittest discover -s tools/tests -v
```

On Windows, set `JAVA_HOME` to a JDK 11 installation, or put its path in the ignored `.local/java-home.txt` file. Use `tools/gradle.ps1` in place of `./gradlew`. This helper keeps build output, Gradle project caches, and the private developer home outside synced source folders under `%LOCALAPPDATA%/MyGoals/development/<checkout-key>/`.

`verifyRelease` inspects the actual JAR for Java 11 bytecode, release metadata, a packaged license, the matching Hub/sidebar icon, development/private payloads, and restricted API patterns. These are local preflight checks, not a replacement for Hub review. The BSD license is a source resource so it also survives the Hub replacing this project's Gradle files.

CI runs the tests/package checks against the pinned client and the current version in the official Hub's `runelite.version`. A scheduled run detects API/catalogue drift. Catalogue generator tests check provenance and deterministic output.

## Run a development client

```sh
./gradlew --no-daemon run
```

The default launcher loads only Advanced Goal Pacer. It uses a separate developer home and does not replace an installed RuneLite client. On Windows, `tools/start-my-goals.bat` uses the same setup. Close the previous development client before restarting; running clients retain loaded classes.

For a Jagex account, follow RuneLite's official [development login guide](https://github.com/runelite/plugin-hub#creating-new-plugins). Keep login material in the private developer home, outside this repository. `runDiagnostics` optionally loads the old source-inspection panel for catalogue maintenance. Its code, arithmetic probes, cache export helpers, and UI fixtures are test sources and are excluded from the release JAR.

## UI checks

`uiPreview` renders the real Swing components with synthetic data. `nativeUiPreview` opens an isolated, logged-out client, verifies real item/skill/boss sprites and window sizing, tests details/editor interactions, renders completion effects and screenshots, and exits. It has its own preview home, separate from the ordinary developer session. No player goals are used for those captures.

The offline source-contract report is under `build/reports/api/source-contract.json`. UI captures are under `build/reports/ui/`, or the external build directory printed by the Windows helper.

## Release regression checklist

- Create/edit XP, level, count, unique, and lifetime-KC goals; verify Today and detail views agree.
- Defer/return a goal repeatedly, progress it, restart, and cross a local day boundary. Historical chart pace must stay frozen; future pace may change.
- Observe live XP/counts, then log in after additional gains. Initial/unranked/decreasing snapshots must not add false progress.
- Seed collection quantities, receive a supported reward, and refresh the page. Quantities must not double-count.
- Complete daily and overall targets. Check native-notification priority, popup-plugin conflicts, and card/fireworks settings.
- Export/restore with other plugins enabled. Progress observed during restore must be preserved.
- Delete a throwaway goal and restart. Its own history must stay deleted; other goals and account totals must remain.
- Browse old completed goals and old charts. Loading must stay bounded and failed reads must remain retryable.

Keep tests, native fixtures, and maintenance tools: they protect calendar, account isolation, reconciliation, deletion, and migration behavior. Development artifacts belong outside `src/main`.
