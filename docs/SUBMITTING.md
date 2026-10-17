# Manual Plugin Hub submission

This repository is prepared for **Advanced Goal Pacer 1.0.0**. Nothing in the preparation workflow publishes a repository, pushes code, uploads a release, or opens a pull request.

1. Review `runelite-plugin.properties`, including author `jlwilder93`. Keep `build=standard`, the production entry point, and matching Gradle/plugin versions.
2. Run the commands in [DEVELOPMENT.md](DEVELOPMENT.md), including `verifyRelease`, then complete the live regression checks relevant to your final changes.
3. Create your **public** GitHub repository, such as `advanced-goal-pacer`, and publish the prepared release branch. Alternatively, initialize a fresh public repository from the source ZIP to omit the historical development commits. Do not copy `.local`, build output, developer homes, sessions, or backup ZIPs into source control.
4. Record the full commit SHA from the repository that you actually publish: `git rev-parse HEAD`. A new repository created from a source ZIP has a different commit SHA from this local repository.
5. Fork [runelite/plugin-hub](https://github.com/runelite/plugin-hub), create a submission branch, and add `plugins/advanced-goal-pacer` with:

```properties
repository=https://github.com/YOUR_ACCOUNT/advanced-goal-pacer.git
commit=FULL_40_CHARACTER_PUBLISHED_COMMIT_SHA
```

6. Open one pull request to the Hub. Use the description below, check its CI/API-review results, and update the same pull request when fixing review feedback. Hub maintainers determine acceptance. The Hub builds from your public source commit; you do not submit the local JAR as the Hub artifact.

## Suggested pull-request description

Advanced Goal Pacer adds calendar-based XP, level, HiScores count, and boss-unique goals with a daily agenda, deferral forecasts, progress charts, and optional completion cards.

Tracking uses native XP/count/loot/reward observations and verified player-owned Collection Log page reads. Official HiScores lookups support login recovery with cooldowns and bounded retries. The plugin performs no game actions. Unknown or ambiguous item quantities require a fresh Collection Log page visit.

Per-character goals and daily/period history are saved locally. Older history loads on demand, backups are explicit and character-bound, and deletion purges goal-owned data from managed storage. No plugin server, credentials, analytics, or extra runtime dependencies are used. Completion cards observe native notification priority and render local overlays.

Validation: Java 11 build/tests, API contracts, release-JAR inspection, deterministic catalogue generator tests, and isolated native UI checks. User testing covers normal tracking/editor flows; static all-boss catalogue coverage does not imply every drop source has been manually reproduced.

## Future updates

Commit tested source updates to the public repository, bump the plugin version, and update the Hub manifest's commit SHA in a new pull request. Follow the [official Hub submission instructions](https://github.com/runelite/plugin-hub#submitting-a-plugin) and [current API restrictions](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features#forbidden-language-features).
