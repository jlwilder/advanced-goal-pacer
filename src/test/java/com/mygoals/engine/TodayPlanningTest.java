package com.mygoals.engine;

import com.google.gson.JsonParser;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.persistence.StateCodec;
import com.mygoals.ui.GoalsView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class TodayPlanningTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private Instant at(String date) { return LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant(); }
    private GoalEngine engine()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, at("2026-10-16")); return engine;
    }
    private GoalSpec gain(long amount, GoalSpec.Cadence cadence, String start, String end)
    {
        return new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, amount, 0, cadence, LocalDate.parse(start),
            end == null ? null : LocalDate.parse(end), zone);
    }
    private GoalsView.Goal view(GoalEngine engine, String id, Instant now)
    {
        return new GoalsView("test", "Saved", "", true, engine.state(), now).goals.stream().filter(g -> g.id.equals(id)).findFirst().orElseThrow();
    }
    private GoalEngine restart(GoalEngine engine)
    {
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); return new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone);
    }

    @Test public void savedCardOrderPreservesProgressGroupsAndObjectIdentity()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String group = engine.addGroup("Skills");
        String one = engine.add(gain(100, GoalSpec.Cadence.WEEKLY, "2026-10-16", null).withGroup(group), now);
        String two = engine.add(gain(200, GoalSpec.Cadence.WEEKLY, "2026-10-16", null), now);
        String three = engine.add(gain(300, GoalSpec.Cadence.WEEKLY, "2026-10-16", null), now);
        engine.observe(Map.of("MINING", 100_010L), GoalEngine.LIVE, now);
        ProfileState.Goal original = engine.state().goals.get(three);
        engine.reorderGoal(three, one, false);
        assertEquals(List.of(three, one, two), new ArrayList<>(engine.state().goals.keySet()));
        assertSame(original, engine.state().goals.get(three)); assertEquals(10, original.current.progress());
        assertEquals(group, engine.state().goals.get(one).spec.groupId);
        long version = engine.version(); engine.reorderGoal(three, one, false); engine.reorderGoal(three, three, false);
        assertEquals(version, engine.version());
        engine.reorderGoal(three, two, true);
        assertEquals(List.of(one, two, three), new ArrayList<>(restart(engine).state().goals.keySet()));
        assertThrows(IllegalArgumentException.class, () -> engine.reorderGoal(one, "removed", false));
    }

    @Test public void postponePreservesTodaysCreditAndForecastRecalculatesWithoutFreezingTomorrow()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String id = engine.add(gain(100, GoalSpec.Cadence.WEEKLY, "2026-10-14", null), now);
        engine.observe(Map.of("MINING", 100_010L), GoalEngine.LIVE, now);
        var goal = engine.state().goals.get(id); var today = engine.today(goal, now);
        assertEquals(20, today.assigned); assertEquals(10, today.credited);
        engine.postponeGoal(id, true, now); long version = engine.version();
        var postponed = view(engine, id, now);
        assertTrue(postponed.inTomorrow); assertEquals(Long.valueOf(23), postponed.tomorrowAssigned);
        assertEquals(90, postponed.tomorrowRemaining); assertEquals(4, postponed.tomorrowDays);
        assertEquals(1, goal.plans.size()); assertEquals(version, engine.version());
        engine.observe(Map.of("MINING", 100_015L), GoalEngine.LIVE, now);
        assertEquals(Long.valueOf(22), view(engine, id, now).tomorrowAssigned);
        assertEquals(20, today.assigned); assertEquals(15, today.credited); assertEquals(1, goal.plans.size());
        engine.postponeGoal(id, false, now); assertFalse(view(engine, id, now).inTomorrow);
        assertEquals(15, today.credited);
    }

    @Test public void postponementSurvivesRestartAndExpiresOnNextCalendarDay()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String id = engine.add(gain(100, GoalSpec.Cadence.WEEKLY, "2026-10-14", null), now);
        engine.postponeGoal(id, true, now); GoalEngine loaded = restart(engine);
        loaded.tick(now); assertTrue(view(loaded, id, now).inTomorrow);
        Instant nextDay = LocalDate.parse("2026-10-17").atStartOfDay(zone).toInstant(); loaded.tick(nextDay);
        assertFalse(view(loaded, id, nextDay).inTomorrow); assertEquals(25, view(loaded, id, nextDay).todayAssigned);
        assertEquals(2, loaded.state().goals.get(id).plans.size());
        engine.tick(nextDay); assertFalse(view(engine, id, nextDay).inTomorrow);
    }

    @Test public void continuedLiveOrRecoveredWorkCanCompleteAPostponedAssignment()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String id = engine.add(gain(100, GoalSpec.Cadence.WEEKLY, "2026-10-14", null), now);
        engine.postponeGoal(id, true, now);
        engine.observe(Map.of("MINING", 100_020L), GoalEngine.RECOVERED, now);
        assertTrue(view(engine, id, now).todayComplete); assertFalse(view(engine, id, now).inTomorrow);
        assertEquals(20, view(engine, id, now).progress);
        assertThrows(IllegalArgumentException.class, () -> engine.postponeGoal(id, true, now));
    }

    @Test public void dailyAndWeeklyBoundaryPreviewsUseANewPeriodWithoutRollingOverMissedQuota()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String daily = engine.add(gain(100, GoalSpec.Cadence.DAILY, "2026-10-16", null), now);
        String weekly = engine.add(gain(700, GoalSpec.Cadence.WEEKLY, "2026-10-10", null), now);
        engine.observe(Map.of("MINING", 100_010L), GoalEngine.LIVE, now);
        for (String id : List.of(daily, weekly)) { engine.postponeGoal(id, true, now); }
        assertEquals(Long.valueOf(100), view(engine, daily, now).tomorrowAssigned);
        assertEquals(Long.valueOf(100), view(engine, weekly, now).tomorrowAssigned);
        assertTrue(view(engine, weekly, now).tomorrowNewPeriod);
        engine.tick(at("2026-10-17"));
        assertEquals(100, view(engine, daily, at("2026-10-17")).todayAssigned);
        assertFalse(view(engine, daily, at("2026-10-17")).inTomorrow);
        assertEquals(10, engine.state().goals.get(weekly).history.get(0).progress());
    }

    @Test public void fixedDeadlineEndingTodayHasNoInventedTomorrowAndRejectsAfterExpiry()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        for (GoalSpec.Cadence cadence : List.of(GoalSpec.Cadence.ONCE, GoalSpec.Cadence.DAILY))
        {
            String id = engine.add(gain(100, cadence, "2026-10-16", "2026-10-16"), now);
            engine.postponeGoal(id, true, now); assertNull(view(engine, id, now).tomorrowAssigned);
            assertTrue(view(engine, id, now).inTomorrow);
            engine.tick(at("2026-10-17")); assertEquals("ENDED", engine.state().goals.get(id).lifecycle);
            assertFalse(view(engine, id, at("2026-10-17")).inTomorrow);
            assertThrows(IllegalArgumentException.class, () -> engine.postponeGoal(id, true, at("2026-10-17")));
        }
    }

    @Test public void calendarForecastHandlesMonthEndAndDstAndDeferredSchedule()
    {
        GoalEngine engine = engine(); Instant monthEnd = at("2026-02-27");
        String month = engine.add(gain(310, GoalSpec.Cadence.MONTHLY, "2026-01-31", null), monthEnd);
        engine.postponeGoal(month, true, monthEnd);
        assertTrue(view(engine, month, monthEnd).tomorrowNewPeriod);
        assertEquals(31, view(engine, month, monthEnd).tomorrowDays);
        assertEquals(Long.valueOf(10), view(engine, month, monthEnd).tomorrowAssigned);
        Instant dstEve = at("2026-10-31");
        String weekly = engine.add(gain(70, GoalSpec.Cadence.WEEKLY, "2026-10-31", null), dstEve);
        engine.postponeGoal(weekly, true, dstEve);
        assertEquals(6, view(engine, weekly, dstEve).tomorrowDays); assertEquals(Long.valueOf(12), view(engine, weekly, dstEve).tomorrowAssigned);
        engine.tick(LocalDate.parse("2026-11-01").atStartOfDay(zone).toInstant());
        assertFalse(view(engine, weekly, at("2026-11-01")).inTomorrow);
        String daily = engine.add(gain(700, GoalSpec.Cadence.DAILY, "2026-10-16", null), at("2026-10-16"));
        engine.edit(daily, gain(700, GoalSpec.Cadence.WEEKLY, "2026-10-16", null), at("2026-10-16"));
        engine.postponeGoal(daily, true, at("2026-10-16"));
        assertEquals(Long.valueOf(100), view(engine, daily, at("2026-10-16")).tomorrowAssigned);
    }

    @Test public void countPreviewCapsAtLifetimeTargetAndKeepsNativeBaseline()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String id = engine.add(new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 100, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(), 1000L), now);
        engine.observeCount("BRUTUS", 990, false, GoalEngine.LIVE, null, now);
        engine.postponeGoal(id, true, now);
        assertEquals(Long.valueOf(2), view(engine, id, now).tomorrowAssigned);
        assertTrue(engine.state().goals.get(id).countLiveBoundary); assertEquals(0, engine.state().goals.get(id).current.progress());
        engine.observeCount("BRUTUS", 1000, true, GoalEngine.LIVE, null, now);
        assertEquals("ACHIEVED", view(engine, id, now).lifecycle); assertFalse(view(engine, id, now).inTomorrow);
    }

    @Test public void schemaFourMigrationPreservesOrderAndQuotasAndNewSchemaRequiresPostponedField()
    {
        GoalEngine engine = engine(); Instant now = at("2026-10-16");
        String one = engine.add(gain(100, GoalSpec.Cadence.DAILY, "2026-10-16", null), now);
        String two = engine.add(gain(200, GoalSpec.Cadence.WEEKLY, "2026-10-16", null), now);
        engine.reorderGoal(two, one, false); engine.observe(Map.of("MINING", 100_010L), GoalEngine.LIVE, now);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); var old = new JsonParser().parse(codec.encode(engine.state())).getAsJsonObject();
        old.addProperty("schemaVersion", 4);
        old.getAsJsonObject("goals").entrySet().forEach(entry -> entry.getValue().getAsJsonObject().getAsJsonObject("plans").entrySet()
            .forEach(p -> p.getValue().getAsJsonObject().remove("postponed")));
        ProfileState migrated = codec.decode(old.toString(), engine.state().profileKey);
        assertEquals(10, migrated.schemaVersion); assertEquals(List.of(two, one), new ArrayList<>(migrated.goals.keySet()));
        assertFalse(migrated.goals.get(one).plans.values().iterator().next().postponed);
        assertEquals(100, migrated.goals.get(one).plans.values().iterator().next().assigned);
        assertEquals(10, migrated.goals.get(one).current.progress());
        old.addProperty("schemaVersion", 5);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(old.toString(), engine.state().profileKey));
    }
}
