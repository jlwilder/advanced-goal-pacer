package com.mygoals.engine;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.persistence.StateCodec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class TargetEditTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private GoalSpec weekly(long amount)
    {
        return new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, amount, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-09"), null, zone);
    }

    @Test public void queuedTargetFromOldBuildAppliesOnLoadWithoutChangingCreditsOrClosedHistory()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        Instant previous = now.minusSeconds(7 * 86400);
        String id = engine.add(weekly(10), previous);
        engine.observeCount("BRUTUS", 100, true, GoalEngine.LIVE, null, previous);
        engine.tick(now); engine.observeCount("BRUTUS", 102, true, GoalEngine.LIVE, null, now);
        ProfileState.Goal old = engine.state().goals.get(id);
        old.pending = weekly(100); old.pendingEffectiveDate = "2026-10-23";
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        GoalEngine loaded = new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone);
        loaded.tick(now);
        ProfileState.Goal goal = loaded.state().goals.get(id);
        assertNull(goal.pending); assertEquals(100, goal.spec.amount); assertEquals(100, goal.current.target);
        assertEquals(2, goal.current.progress()); assertEquals(2, loaded.today(goal, now).credited);
        assertEquals(15, loaded.today(goal, now).assigned); assertEquals(Long.valueOf(102), goal.countCheckpoint);
        assertEquals(10, goal.history.get(0).target); assertEquals(1, goal.history.get(0).progress());
        loaded.observeCount("BRUTUS", 103, true, GoalEngine.LIVE, null, now);
        assertEquals(3, goal.current.progress()); assertEquals(15, loaded.today(goal, now).assigned);
        assertEquals(3, loaded.today(goal, now).credited);
    }

    @Test public void deadlineTargetAndDateRecalculateTodayAndInvalidPastEndLeavesStateIntact()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
        String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0,
            GoalSpec.Cadence.ONCE, LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-18"), zone), now);
        engine.observe(Map.of("MINING", 120L), GoalEngine.LIVE, now);
        engine.edit(id, new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 200, 0,
            GoalSpec.Cadence.ONCE, LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-20"), zone), now);
        ProfileState.Goal goal = engine.state().goals.get(id);
        assertEquals(200, goal.current.target); assertEquals(20, goal.current.progress());
        assertEquals(40, engine.today(goal, now).assigned); assertEquals(20, engine.today(goal, now).credited);
        GoalSpec before = goal.spec;
        assertThrows(IllegalArgumentException.class, () -> engine.edit(id, new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 300, 0,
            GoalSpec.Cadence.ONCE, LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-15"), zone), now));
        assertEquals(before, goal.spec); assertEquals("2026-10-21", goal.current.endExclusive);
        assertEquals(40, engine.today(goal, now).assigned); assertEquals(20, goal.current.progress());
    }

    @Test public void revisedUniqueEndingConditionCompletesImmediatelyWhenItsTotalIsAlreadyOwned()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        String id = engine.add(new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L)), now);
        engine.edit(id, new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 4L)), now);
        ProfileState.Goal goal = engine.state().goals.get(id);
        assertEquals("ACHIEVED", goal.lifecycle); assertNull(goal.pending); assertEquals(0, goal.current.progress());
    }
}
