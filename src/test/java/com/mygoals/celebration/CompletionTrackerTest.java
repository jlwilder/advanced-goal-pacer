package com.mygoals.celebration;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import com.mygoals.persistence.StateCodec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompletionTrackerTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private final LocalDate day = LocalDate.parse("2026-10-16");
    private final Instant now = day.atTime(12, 0).atZone(zone).toInstant();
    private GoalEngine engine()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now); return engine;
    }
    private GoalSpec xp(long amount, GoalSpec.Cadence cadence)
    { return new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, amount, 0, cadence, day, cadence == GoalSpec.Cadence.ONCE ? day.plusDays(6) : null, zone); }
    private List<ProfileState.Celebration> pending(GoalEngine engine)
    { return engine.state().celebrations.values().stream().filter(e -> e.status.equals("PENDING")).collect(Collectors.toList()); }
    private GoalSpec boss(Long kc)
    { return new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 1, 0,
        GoalSpec.Cadence.DAILY, day, null, zone, kc == null ? Map.of(23956, 6L) : Map.of(), kc); }
    private GoalEngine restart(GoalEngine engine)
    { StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); return new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone); }

    @Test public void dailyAndLongerTodaySharesCelebrateOnceAndResetOnNextLocalDay()
    {
        for (GoalSpec.Cadence cadence : new GoalSpec.Cadence[]{GoalSpec.Cadence.DAILY, GoalSpec.Cadence.WEEKLY, GoalSpec.Cadence.MONTHLY})
        {
            GoalEngine engine = engine(); String id = engine.add(xp(2100, cadence), now);
            long quota = engine.today(engine.state().goals.get(id), now).assigned;
            engine.observe(Map.of("MINING", 100_000L + quota), GoalEngine.LIVE, now);
            engine.observe(Map.of("MINING", 100_000L + quota), GoalEngine.LIVE, now);
            assertEquals(1, pending(engine).size()); assertFalse(pending(engine).get(0).overall);
            engine = restart(engine); engine.tick(now); assertEquals(1, pending(engine).size());
            engine.settleCelebrations(List.of(pending(engine).get(0).id), false);
            Instant tomorrow = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant(); engine.tick(tomorrow);
            long next = engine.today(engine.state().goals.get(id), tomorrow).assigned;
            if (next > 0)
            {
                engine.observe(Map.of("MINING", 100_000L + quota + next), GoalEngine.LIVE, tomorrow);
                assertEquals(1, pending(engine).size()); assertEquals(day.plusDays(1).toString(), pending(engine).get(0).date);
            }
        }
    }

    @Test public void blockedDailyCardsExpireAtLocalMidnightWithoutLosingProgressOrOccurrences()
    {
        GoalEngine engine = engine(); String id = engine.add(xp(100, GoalSpec.Cadence.DAILY), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        String daily = pending(engine).get(0).id;
        Instant midnight = day.plusDays(1).atStartOfDay(zone).toInstant();
        engine = restart(engine); engine.tick(midnight.minusSeconds(1));
        assertEquals("PENDING", engine.state().celebrations.get(daily).status);
        long version = engine.completionVersion(); engine.tick(midnight);
        assertTrue(engine.completionVersion() > version);
        assertEquals("SUPPRESSED", engine.state().celebrations.get(daily).status);
        assertEquals(100, engine.state().goals.get(id).history.get(0).progress());
        assertEquals(100, engine.state().activity.values().stream().mapToLong(a -> a.amount).sum());
        assertTrue(pending(restart(engine)).isEmpty());
        engine.observe(Map.of("MINING", 100_200L), GoalEngine.LIVE, midnight.plusSeconds(60));
        assertEquals(1, pending(engine).size());
        assertEquals(day.plusDays(1).toString(), pending(engine).get(0).date);
    }

    @Test public void overallCardsAndAlreadyAnnouncedHistoryDoNotExpire()
    {
        GoalEngine engine = engine(); engine.add(xp(100, GoalSpec.Cadence.ONCE), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        String overall = pending(engine).get(0).id;
        engine.tick(day.plusYears(10).atStartOfDay(zone).toInstant());
        assertEquals("PENDING", engine.state().celebrations.get(overall).status);
        engine.settleCelebrations(List.of(overall), false);
        engine.tick(day.plusYears(11).atStartOfDay(zone).toInstant());
        assertEquals("ANNOUNCED", engine.state().celebrations.get(overall).status);
    }

    @Test public void expiryUsesTheGoalsCalendarZoneAndLeavesAnnouncedDailyHistoryIntact()
    {
        GoalEngine engine = engine(); String id = engine.add(xp(100, GoalSpec.Cadence.DAILY), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        String daily = pending(engine).get(0).id;
        Instant utcMidnight = day.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        engine.tick(utcMidnight); assertEquals("PENDING", engine.state().celebrations.get(daily).status);
        engine.settleCelebrations(List.of(daily), false);
        engine.tick(day.plusDays(1).atStartOfDay(zone).toInstant());
        assertEquals("ANNOUNCED", engine.state().celebrations.get(daily).status);
        assertEquals(100, engine.state().goals.get(id).history.get(0).progress());
    }

    @Test public void overallXpAndLevelCompletionReplaceDailyAndDoNotReplayAfterRestart()
    {
        for (GoalSpec spec : List.of(xp(100, GoalSpec.Cadence.ONCE), new GoalSpec("Level 50 Mining", null, "MINING", GoalSpec.Mode.LEVEL,
            0, 50, GoalSpec.Cadence.ONCE, day, day.plusDays(6), zone)))
        {
            GoalEngine engine = engine(); engine.add(spec, now);
            long total = spec.mode == GoalSpec.Mode.LEVEL ? spec.targetXp() : 100_100L;
            engine.observe(Map.of("MINING", total), GoalEngine.LIVE, now);
            assertEquals(1, pending(engine).size()); assertTrue(pending(engine).get(0).overall);
            String event = pending(engine).get(0).id; engine.settleCelebrations(List.of(event), false);
            engine = restart(engine); engine.observe(Map.of("MINING", total), GoalEngine.RECOVERED, now);
            assertTrue(pending(engine).isEmpty()); assertEquals("ANNOUNCED", engine.state().celebrations.get(event).status);
        }
    }

    @Test public void uniqueRewardSupersedesPendingDailyAndPendingOverallSurvivesRestart()
    {
        GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        engine.add(boss(null), now); engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.LIVE, null, now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 101, true, GoalEngine.LIVE, null, now);
        assertEquals(1, pending(engine).size()); String daily = pending(engine).get(0).id;
        engine.acquire("seeds", Map.of(23956, 2L), "Reward", now);
        assertEquals("SUPPRESSED", engine.state().celebrations.get(daily).status);
        assertEquals(1, pending(engine).size()); assertTrue(pending(engine).get(0).overall);
        engine = restart(engine); engine.acquire("seeds", Map.of(23956, 2L), "Reward", now);
        assertEquals(1, pending(engine).size()); assertEquals(Long.valueOf(6), engine.state().collection.get(23956).quantity);
    }

    @Test public void delayedUniqueReplacesAClaimedDailyUntilItsFirstVisibleFrame()
    {
        GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        engine.add(boss(null), now); engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.LIVE, null, now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 101, true, GoalEngine.LIVE, null, now);
        CompletionCoordinator.Store store = new CompletionCoordinator.Store() {
            public String celebrationToken() { return "test"; }
            public List<CompletionCoordinator.Notice> pendingCelebrations() { return pending(engine).stream().map(CompletionCoordinator.Notice::new).collect(java.util.stream.Collectors.toList()); }
            public boolean validCelebration(String id) { return !engine.state().celebrations.get(id).status.equals("SUPPRESSED"); }
            public void settleCelebrations(List<String> ids, boolean suppress) { engine.settleCelebrations(ids, suppress); }
            public boolean announcedCelebrations(List<String> ids) { return ids.stream().allMatch(id -> engine.state().celebrations.get(id).status.equals("ANNOUNCED")); }
            public void presentedCelebrations(List<String> ids) { engine.presentedCelebrations(ids); }
        };
        CompletionCoordinator coordinator = new CompletionCoordinator(store, () -> true, () -> true);
        String dailyId = pending(engine).get(0).id;
        coordinator.update(0, false, true); coordinator.update(600, false, true);
        assertEquals("ANNOUNCED", engine.state().celebrations.get(dailyId).status); assertNull(coordinator.frame());
        // The real reward adapter waits two game ticks, longer than the admission window.
        engine.acquire("delayed-reward", Map.of(23956, 2L), "Reward", now.plusMillis(1200));
        var shown = new java.util.HashSet<String>();
        for (long time = 1200; time < 10_000; time += 100)
        {
            coordinator.update(time, false, true);
            if (coordinator.frame() != null && coordinator.frame().card != null) { shown.add(coordinator.frame().card.id); assertTrue(coordinator.frame().card.overall); }
        }
        assertEquals(1, shown.size()); assertEquals("SUPPRESSED", engine.state().celebrations.get(dailyId).status);
    }

    @Test public void aDailyAlreadyPresentedRemainsRecordedWhenTheOverallGoalFinishesLater()
    {
        GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        engine.add(boss(null), now); engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.LIVE, null, now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 101, true, GoalEngine.LIVE, null, now);
        String dailyId = pending(engine).get(0).id; engine.settleCelebrations(List.of(dailyId), false); engine.presentedCelebrations(List.of(dailyId));
        engine.acquire("later-reward", Map.of(23956, 2L), "Reward", now.plusSeconds(60));
        assertEquals("ANNOUNCED", engine.state().celebrations.get(dailyId).status);
        assertEquals(1, pending(engine).size()); assertTrue(pending(engine).get(0).overall);
    }

    @Test public void lifetimeKcAchievementAndRecoveryQueueOneOverall()
    {
        GoalEngine engine = engine(); engine.add(boss(102L), now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.LIVE, null, now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 102, false, GoalEngine.RECOVERED, null, now);
        assertEquals(1, pending(engine).size()); assertTrue(pending(engine).get(0).overall); assertTrue(pending(engine).get(0).recovered);
        engine = engine(); engine.add(boss(100L), now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, true, GoalEngine.LIVE, null, now);
        assertEquals(1, pending(engine).size()); assertTrue(pending(engine).get(0).overall); assertFalse(pending(engine).get(0).recovered);
    }

    @Test public void initialCollectionAndCountBaselinesAndAlreadyOwnedCreationStaySilent()
    {
        GoalEngine engine = engine(); engine.add(boss(null), now);
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 6L)), false, now);
        engine.add(boss(null), now);
        engine.add(boss(100L), now); engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.RECOVERED, null, now);
        assertTrue(pending(engine).isEmpty());
        engine.tick(now); engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        assertTrue(pending(engine).isEmpty());
    }

    @Test public void targetEditsAndZeroWorkDaysNeverCauseDelayedCelebrations()
    {
        GoalEngine engine = engine(); String id = engine.add(xp(1000, GoalSpec.Cadence.DAILY), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        engine.edit(id, xp(50, GoalSpec.Cadence.DAILY), now);
        engine.observe(Map.of("MINING", 100_101L), GoalEngine.LIVE, now);
        assertTrue(pending(engine).isEmpty());
        engine.edit(id, xp(200, GoalSpec.Cadence.DAILY), now);
        engine.observe(Map.of("MINING", 100_200L), GoalEngine.LIVE, now);
        assertTrue(pending(engine).isEmpty()); // Once per local day, even after lowering then raising the target.

        engine = engine(); engine.add(xp(100, GoalSpec.Cadence.WEEKLY), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        engine.settleCelebrations(List.of(pending(engine).get(0).id), false);
        engine.tick(day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()); assertTrue(pending(engine).isEmpty());
    }

    @Test public void knownCollectionRecoveryCelebratesButExpiryAndOldSaveCompletionDoNot()
    {
        GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        engine.add(boss(null), now); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 6L)), false, now.plusSeconds(10));
        assertEquals(1, pending(engine).size()); assertTrue(pending(engine).get(0).recovered);

        engine = engine(); engine.add(xp(100, GoalSpec.Cadence.ONCE), now);
        engine.tick(day.plusDays(8).atTime(12, 0).atZone(zone).toInstant()); assertTrue(pending(engine).isEmpty());
        engine = engine(); engine.add(xp(100, GoalSpec.Cadence.DAILY), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); var legacy = new com.google.gson.JsonParser().parse(codec.encode(engine.state())).getAsJsonObject();
        legacy.addProperty("schemaVersion", 6); legacy.remove("celebrations");
        engine = new GoalEngine(codec.decode(legacy.toString(), engine.state().profileKey), zone);
        engine.observe(Map.of("MINING", 100_110L), GoalEngine.LIVE, now); assertTrue(pending(engine).isEmpty());
    }

    @Test public void longestValidGoalNameAndDurableMarkersRoundTripAndInvalidRecordsAreRejected()
    {
        GoalEngine engine = engine(); engine.add(new GoalSpec("M".repeat(100), null, "MINING", GoalSpec.Mode.GAIN, 100, 0,
            GoalSpec.Cadence.DAILY, day, null, zone), now);
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
        engine = restart(engine); assertEquals(100, pending(engine).get(0).name.length());
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); String key = engine.state().profileKey;
        var json = new com.google.gson.JsonParser().parse(codec.encode(engine.state())).getAsJsonObject();
        String id = pending(engine).get(0).id;
        json.getAsJsonObject("celebrations").getAsJsonObject(id).addProperty("status", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString(), key));
        json.remove("celebrations"); assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString(), key));
    }
}
