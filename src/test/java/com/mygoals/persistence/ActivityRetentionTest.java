package com.mygoals.persistence;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityRetentionTest
{
    @Test public void dailyTotalsRemainIndefinitelyAcrossTicksAndRestarts()
    {
        ZoneId zone = ZoneId.of("America/Chicago");
        String key = "a".repeat(64);
        LocalDate start = LocalDate.parse("2026-10-16");
        Instant first = start.atTime(12, 0).atZone(zone).toInstant();
        GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, first);
        String mining = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 1000, 0,
            GoalSpec.Cadence.DAILY, start, null, zone), first);
        String boss = engine.add(new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET",
            GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY, start, null, zone), first);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.LIVE, null, first);
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, first);
        engine.observe(Map.of("MINING", 100_300L), GoalEngine.LIVE, first);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 102, true, GoalEngine.LIVE, null, first);
        engine.acquire("seed", Map.of(23956, 2L), "Reward", first);

        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        String originalActivity = codec.encode(engine.state());
        // Cross both the former one-year cutoff and a potential five-year cutoff.
        for (int years : new int[]{1, 5, 10})
        {
            Instant later = start.plusYears(years).atTime(12, 0).atZone(zone).toInstant();
            engine.tick(later);
            engine = new GoalEngine(codec.decode(codec.encode(engine.state()), key), zone);
            engine.observe(Map.of("MINING", 100_300L), GoalEngine.RECOVERED, later);
            engine.observeCount("THE_CORRUPTED_GAUNTLET", 102, false, GoalEngine.RECOVERED, null, later);
            engine.acquire("seed", Map.of(23956, 2L), "Reward", later);

            assertEquals(2, engine.state().activity.size());
            assertTrue(engine.state().activity.values().stream().allMatch(a -> a.date.equals(start.toString())));
            assertEquals(300, engine.state().activity.values().stream().filter(a -> a.metricId.equals("MINING")).mapToLong(a -> a.amount).sum());
            assertEquals(2, engine.state().activity.values().stream().filter(a -> a.metricId.equals("THE_CORRUPTED_GAUNTLET")).mapToLong(a -> a.amount).sum());
            assertTrue(engine.state().activity.values().stream().allMatch(a -> a.goalCredits.size() == 1));
            assertEquals(1, engine.state().itemActivity.size());
            ProfileState.ItemActivity items = engine.state().itemActivity.values().iterator().next();
            assertEquals(start.toString(), items.date); assertEquals(2, items.amount); assertEquals("Reward", items.source);
            assertEquals(Long.valueOf(6), engine.state().collection.get(23956).quantity);
            assertEquals(Long.valueOf(100_300), engine.state().xp.get("MINING"));
            assertEquals(Long.valueOf(102), engine.state().goals.get(boss).countCheckpoint);
            assertEquals(300, engine.state().goals.get(mining).history.get(0).progress());
            assertEquals(2, engine.state().goals.get(boss).history.get(0).progress());
            ProfileState original = codec.decode(originalActivity, key);
            assertEquals(original.activity.keySet(), engine.state().activity.keySet());
            assertEquals(original.itemActivity.keySet(), engine.state().itemActivity.keySet());
        }
    }
}
