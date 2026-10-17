# Maintenance

## Code boundaries

- `MyGoalsPlugin`: RuneLite event bindings and component lifecycle.
- `MyGoalsController`: character/session ownership, observations, save coordination, and view publication.
- `GoalEngine` and `CalendarService`: pure goal, schedule, reconciliation, and frozen daily-plan behavior.
- `HistoryOperations`: bounded archive reads, completed-goal paging, and rejection of stale/deleted-goal callbacks.
- `CountLookupService`: HiScores budgets, cache, retry boundaries, and account/endpoint isolation.
- `RuneLiteCollectionAdapter` / `CollectionLogReader`: observed native loot/reward evidence and collection-page verification.
- `ProfileStateRepository`, `HistoryArchive`, `StateCodec`, and `StateSnapshot`: durable storage, migrations, archive references, deletion, and detached values.
- `BackupOperations`, `RestoreOperation`, and `BackupArchive`: transfer worker, bounded observation reconciliation, identity/checksum validation, and replacing transactions.
- `ui` and `celebration`: Swing views and local overlays. UI code receives detached values, not mutable engine state.

Canonical writes use a serial I/O worker. Export/validation use a separate bounded worker. The controller and engine belong to the client thread; Swing belongs to the EDT. Do not block either thread on file/network operations, or move game observations onto the I/O worker. No reflection, raw file APIs, game actions, or independent network clients belong in production code.

## New bosses, raids, and collection-log changes

Counts are derived from RuneLite's HiScores catalogue. Unique eligibility, native page IDs, canonical item IDs, and conservative target limits are local definitions in `src/main/resources/com/mygoals/catalogue/uniques.json`. This is not a probability/drop-rate table and is not downloaded at runtime. Native game/RuneLite services supply sprites.

1. Update the pinned RuneLite version when appropriate. Run the catalogue/source checks; missing/new metrics should fail explicitly.
2. Inspect the new activity's native collection-log page and item definitions. Distinguish regular, challenge, hard, and expert modes. Shared pages do not prove shared reward eligibility.
3. Run `tools/generate_boss_candidates.py` against the audited CS2 revision. Add `--boss-metric NEW_BOSS` when introducing a metric not yet in the runtime catalogue. Update the pin/hash and aliases when native scripts change; do not silently accept a changed source.
4. Use the optional diagnostics launcher to export **public cache definitions**, without player quantities. Supply the pinned item-definition source and full source commit hashes to `tools/generate_unique_catalogue.py`.
5. Review the generator's `exclude`, `only`, single-target, and bulk-target policies. Generate the next explicit catalogue revision. Its provenance includes the game/client versions, source commit hashes, and cache-export hash.
6. Check supported count messages and the acquisition adapter. A normal owned NPC-loot event may need no special handler; reward interfaces, pets, collection unlocks, mode-specific rewards, and interrupted/full-inventory cases need source-specific evidence.
7. Add representative source fixtures and update the expected metric/page catalogue fixtures under `src/test/resources/m0`. Run Java, generator, and native UI checks. Verify initial page totals, live acquisition, deduplication after a fresh page read, and interruption behavior in game for the new source.

The catalogue covers current boss/raid entries, including shared-page and mode-specific subsets. This is a static mapping assertion, not a claim that every boss drop has been manually reproduced. Unknown or ambiguous quantities require a Collection Log refresh. Target limits are conservative UI policy, not audited native counter caps.

Example generation command:

```sh
python3 tools/generate_unique_catalogue.py \
  --cache-export /path/to/public-cache-definitions.json \
  --item-definitions /path/to/pinned/ItemID.java \
  --source-runelite-revision FULL_40_CHARACTER_SHA \
  --source-cs2-revision FULL_40_CHARACTER_SHA \
  --catalogue-revision 3
```

## Storage invariants

Schema 10 accepts migrations from schemas 1–9. Saved-data validation is structural and independent of current content support. Keep unknown/retired metrics, old targets, and old check timestamps readable; pause unsupported goals rather than discarding them. Changing schemas requires migration and backup/restore coverage. Avoid renaming existing storage/configuration keys during cosmetic refactors.

The working state retains about 32 recent dates, active/recently finished goals, and useful latest-period summaries. Older days, periods, settled completion records, and finished goal bodies move to dated history segments. A small finished-goal/file index remains in the main state. Completed bodies load in pages of 50; period reads cover at most six recorded months, and charts request bounded date windows. Retained history never expires.

On load and restore, the I/O worker fills missed daily assignments before compaction, using the last known progress and calendar schedule. Existing assignments stay fixed, and no earned work is invented for those dates. Recovered observations still belong to their recognition date. The engine caches its latest planned day so ordinary ticks do not rescan a lifetime of plans.

Write new history segments durably before committing the state that references them. A previous-state backup and active export/restore snapshot leases protect their referenced files. Only unreachable history files may be garbage-collected. Ordinary saves must not scan/decode a lifetime of archive rows.

Deletion is the explicit exception to immutable segments. A durable intent record precedes purging goal-owned data from current, archived, pending, and managed recovery copies. Anonymous deletion IDs prevent stale writes/recovery from resurrecting records. Preserve independent account totals and other goals. Unusable diagnostic copies may be discarded during deletion. User-exported backups remain immutable snapshots and an explicit restore can intentionally bring old goals back.

Autosave has one running snapshot and one replaceable pending snapshot. Edits/rewards/completions share a fixed 500 ms window; XP/count progress normally saves around once per minute. Normal shutdown flushes. Backup preparation/export must not block these writes. Restore keeps replacing commits ordered with canonical saves and buffers at most 1,024 observations before safely falling back to the original state.

## Notifications

Cards observe native script/varc/widget lifecycle without modifying it. Native notifications and level-up/quest interfaces have priority. Preserve suppression/announcement markers and the durable-before-presenting claim: this prevents replay after restart and phantom completion cards. Keep overall completion distinct from daily/period quota completion. The Collection Log Popup Enhanced compatibility gate currently defers custom cards for the entire time that plugin is enabled.
