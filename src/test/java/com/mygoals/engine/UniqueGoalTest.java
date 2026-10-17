package com.mygoals.engine;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.persistence.StateCodec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class UniqueGoalTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    public static Map<Integer, Long> page(int page, Map<Integer, Long> overrides)
    {
        Map<Integer, Long> quantities = new LinkedHashMap<>();
        UniqueCatalogue.INSTANCE.pages.get(page).itemIds.forEach(id -> quantities.put(id, overrides.getOrDefault(id, 0L)));
        return quantities;
    }
    private GoalSpec spec(String metric, Map<Integer, Long> targets)
    {
        return new GoalSpec("Boss", null, GoalSpec.TrackType.COUNT, metric, GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY,
            LocalDate.parse("2026-10-16"), null, zone, targets);
    }
    private void snapshot(GoalEngine e, Map<Integer, Long> quantities) { e.collectionSnapshot(605, page(605, quantities), false, now); }
    private long activity(GoalEngine e) { return e.state().itemActivity.values().stream().mapToLong(a -> a.amount).sum(); }

    @Test public void collectionTotalsAreNotLifetimeActivityAndEverySelectedTotalMustBeMet()
    {
        GoalEngine e = engine(); snapshot(e, Map.of(23956, 4L, 25859, 0L));
        assertEquals(0, activity(e));
        String id = e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L, 25859, 1L)), now);
        e.acquire("a", Map.of(23956, 2L), "Drop", now);
        assertEquals("ACTIVE", e.state().goals.get(id).lifecycle);
        e.acquire("b", Map.of(25859, 1L), "Reward", now);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertEquals(0, e.state().goals.get(id).current.progress());
        assertEquals(3, activity(e));
    }

    @Test public void sharedItemUpdatesBothBossGoalsWithoutDuplicatingActivityAndSurvivesRestart()
    {
        GoalEngine e = engine(); snapshot(e, Map.of(23956, 4L));
        String normal = e.add(spec("THE_GAUNTLET", Map.of(23956, 6L)), now);
        String corrupted = e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L)), now);
        e.acquire("same", Map.of(23956, 2L), "Drop", now);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); e = new GoalEngine(codec.decode(codec.encode(e.state()), "a".repeat(64)), zone);
        e.acquire("same", Map.of(23956, 2L), "Drop", now);
        assertEquals(Long.valueOf(6), e.state().collection.get(23956).quantity);
        assertEquals("ACHIEVED", e.state().goals.get(normal).lifecycle);
        assertEquals("ACHIEVED", e.state().goals.get(corrupted).lifecycle);
        assertEquals(2, activity(e));
    }

    @Test public void unknownAndOverlappingTotalsWaitForFreshPageWithoutDoubleActivity()
    {
        GoalEngine e = engine();
        String id = e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L)), now);
        e.acquire("unknown", Map.of(23956, 1L), "Drop", now);
        assertNull(e.state().collection.get(23956).quantity);
        snapshot(e, Map.of(23956, 4L));
        assertEquals(1, activity(e));
        e.collectionSnapshot(605, page(605, Map.of(23956, 5L)), true, now);
        e.acquire("overlap", Map.of(23956, 1L), "Drop", now);
        assertEquals(Long.valueOf(4), e.state().collection.get(23956).quantity);
        assertTrue(e.state().collection.get(23956).needsSync);
        snapshot(e, Map.of(23956, 5L));
        assertEquals(2, activity(e));
        assertEquals(0, e.state().collection.get(23956).observedPending);
        snapshot(e, Map.of(23956, 6L));
        assertEquals(3, activity(e));
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertTrue(e.state().itemActivity.values().stream().anyMatch(a -> a.source.equals("Collection log refreshed") && a.amount == 1));
    }

    @Test public void nativeCapSnapshotClearsPendingWithoutInventingTotals()
    {
        GoalEngine e = engine(); snapshot(e, Map.of(23956, 249L));
        String id = e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 250L)), now);
        e.acquire("near-cap", Map.of(23956, 2L), "Drop", now);
        assertTrue(e.state().collection.get(23956).needsSync);
        assertEquals("ACTIVE", e.state().goals.get(id).lifecycle);
        snapshot(e, Map.of(23956, 250L));
        assertEquals(0, e.state().collection.get(23956).observedPending);
        assertEquals(2, activity(e)); assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
    }

    @Test public void existingOwnedTargetsFinishOnActivationAndChangedCacheBlocksCompletion()
    {
        GoalEngine e = engine(); snapshot(e, Map.of(23956, 6L));
        e.setUnavailableUniques(Set.of("THE_CORRUPTED_GAUNTLET"));
        assertThrows(IllegalArgumentException.class, () -> e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L)), now));
        e.setUnavailableUniques(Set.of());
        assertEquals("ACHIEVED", e.state().goals.get(e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L)), now)).lifecycle);
        assertThrows(IllegalArgumentException.class, () -> e.collectionSnapshot(605, Map.of(23956, 6L), false, now));
    }

    @Test public void uniqueEditsApplyNowAndGroupRemovalKeepsSelectedTargets()
    {
        GoalEngine e = engine();
        String group = e.addGroup("Bosses");
        String id = e.add(spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L)).withGroup(group), now);
        long quota = e.today(e.state().goals.get(id), now).assigned;
        e.edit(id, spec("THE_CORRUPTED_GAUNTLET", Map.of(23956, 8L)).withGroup(group), now);
        e.removeGroup(group);
        assertEquals(Map.of(23956, 8L), e.state().goals.get(id).spec.uniqueTargets);
        assertNull(e.state().goals.get(id).pending);
        assertEquals(quota, e.today(e.state().goals.get(id), now).assigned);
        e.tick(now.plusSeconds(7 * 86400));
        assertEquals(Map.of(23956, 8L), e.state().goals.get(id).spec.uniqueTargets);
    }

    @Test public void threeAncestralKitsAreAQuantityGoalRatherThanASingleUnlock()
    {
        GoalEngine e = engine(); int page = UniqueCatalogue.INSTANCE.bosses.get("CHAMBERS_OF_XERIC_CHALLENGE_MODE").pageIds.get(0);
        e.collectionSnapshot(page, page(page, Map.of(24670, 1L)), false, now);
        String id = e.add(spec("CHAMBERS_OF_XERIC_CHALLENGE_MODE", Map.of(24670, 3L)), now);
        e.acquire("second-kit", Map.of(24670, 1L), "Reward", now);
        assertEquals("ACTIVE", e.state().goals.get(id).lifecycle);
        e.acquire("third-kit", Map.of(24670, 1L), "Reward", now);
        assertEquals(Long.valueOf(3), e.state().collection.get(24670).quantity);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
    }
}
