package com.mygoals.engine;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import net.runelite.api.Experience;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalEngineTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private Instant at(String day) { return LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant(); }
    private GoalSpec gain(long amount, GoalSpec.Cadence cadence, String start, String end, String group)
    {
        return new GoalSpec("Mining", group, "MINING", GoalSpec.Mode.GAIN, amount, 0, cadence,
            LocalDate.parse(start), end == null ? null : LocalDate.parse(end), zone);
    }
    private GoalEngine engine()
    {
        GoalEngine e = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        e.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, at("2026-10-16"));
        return e;
    }

    @Test
    public void todayQuotaIsFrozenAndOvercompletionReducesTomorrow()
    {
        GoalEngine e = engine();
        String id = e.add(gain(100_000, GoalSpec.Cadence.WEEKLY, "2026-10-16", null, null), at("2026-10-16"));
        ProfileState.Goal goal = e.state().goals.get(id);
        assertEquals(14286, e.today(goal, at("2026-10-16")).assigned);
        e.observe(Map.of("MINING", 140_000L), GoalEngine.LIVE, at("2026-10-16"));
        assertEquals(14286, e.today(goal, at("2026-10-16")).assigned);
        assertEquals(40_000, e.today(goal, at("2026-10-16")).credited);
        e.tick(at("2026-10-17"));
        assertEquals(10_000, e.today(goal, at("2026-10-17")).assigned);
    }

    @Test
    public void oneObservationCreditsTwoGoalsButOnlyOneActivityAmount()
    {
        GoalEngine e = engine();
        String one = e.add(gain(1000, GoalSpec.Cadence.DAILY, "2026-10-16", null, null), at("2026-10-16"));
        String two = e.add(gain(2000, GoalSpec.Cadence.WEEKLY, "2026-10-16", null, null), at("2026-10-16"));
        e.observe(Map.of("MINING", 100_300L), GoalEngine.LIVE, at("2026-10-16"));
        e.observe(Map.of("MINING", 100_300L), GoalEngine.LIVE, at("2026-10-16"));
        assertEquals(300, e.state().goals.get(one).current.progress());
        assertEquals(300, e.state().goals.get(two).current.progress());
        assertEquals(1, e.state().activity.size());
        ProfileState.Activity row = e.state().activity.values().iterator().next();
        assertEquals(300, row.amount);
        assertEquals(2, row.goalCredits.size());
    }

    @Test
    public void loginGainsGoToRecognitionDayAndOpenPeriodOnce()
    {
        GoalEngine e = engine();
        String id = e.add(gain(1000, GoalSpec.Cadence.DAILY, "2026-10-16", null, null), at("2026-10-16"));
        e.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, at("2026-10-16"));
        e.observe(Map.of("MINING", 100_800L), GoalEngine.RECOVERED, at("2026-10-18"));
        e.observe(Map.of("MINING", 100_800L), GoalEngine.RECOVERED, at("2026-10-18"));
        ProfileState.Goal goal = e.state().goals.get(id);
        assertEquals(100, goal.history.get(0).progress());
        assertEquals(2, goal.history.size());
        assertEquals("2026-10-17", goal.history.get(1).start);
        assertEquals(0, goal.history.get(1).progress()); // A missed schedule records no earned work.
        assertEquals("2026-10-18", goal.current.start);
        assertEquals(700, goal.current.recovered);
        assertTrue(e.state().activity.values().stream().anyMatch(a -> a.date.equals("2026-10-18") && a.source.equals(GoalEngine.RECOVERED) && a.amount == 700));
    }

    @Test
    public void futureGoalBaselinesAtActivationInsteadOfCreditingEarlierOfflineXp()
    {
        GoalEngine e = engine();
        String id = e.add(gain(1000, GoalSpec.Cadence.DAILY, "2026-10-18", null, null), at("2026-10-16"));
        e.observe(Map.of("MINING", 120_000L), GoalEngine.RECOVERED, at("2026-10-18"));
        ProfileState.Goal goal = e.state().goals.get(id);
        assertEquals(Long.valueOf(120_000), goal.baselineXp);
        assertEquals(0, goal.current.progress());
        e.observe(Map.of("MINING", 120_400L), GoalEngine.LIVE, at("2026-10-18"));
        assertEquals(400, goal.current.progress());
    }

    @Test
    public void backwardsXpCannotCreateAReboundGain()
    {
        GoalEngine e = engine();
        String id = e.add(gain(1000, GoalSpec.Cadence.DAILY, "2026-10-16", null, null), at("2026-10-16"));
        e.observe(Map.of("MINING", 90_000L), GoalEngine.RECOVERED, at("2026-10-16"));
        e.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, at("2026-10-16"));
        assertEquals(100, e.state().goals.get(id).current.progress());
    }

    @Test
    public void targetUpdatesNowWhileCadenceWaitsForNextRepeatingPeriodAndKeepsHistory()
    {
        GoalEngine e = engine();
        String id = e.add(gain(1000, GoalSpec.Cadence.DAILY, "2026-10-16", null, null), at("2026-10-16"));
        ProfileState.Goal goal = e.state().goals.get(id);
        e.observe(Map.of("MINING", 100_300L), GoalEngine.LIVE, at("2026-10-16"));
        e.edit(id, gain(2000, GoalSpec.Cadence.WEEKLY, "2026-10-16", null, null), at("2026-10-16"));
        assertEquals(2000, goal.current.target);
        assertEquals(2000, e.today(goal, at("2026-10-16")).assigned);
        e.tick(at("2026-10-17"));
        assertEquals(2000, goal.current.target);
        assertEquals("2026-10-17", goal.current.start);
        assertEquals("2026-10-24", goal.current.endExclusive);
        assertEquals(2000, goal.history.get(0).target);
        assertEquals(300, goal.history.get(0).progress());
    }

    @Test
    public void removingGroupPreservesGoalAndItsFrozenPlan()
    {
        GoalEngine e = engine();
        String group = e.addGroup("Skilling");
        String id = e.add(gain(1000, GoalSpec.Cadence.WEEKLY, "2026-10-16", null, group), at("2026-10-16"));
        e.observe(Map.of("MINING", 100_300L), GoalEngine.LIVE, at("2026-10-16"));
        e.removeGroup(group);
        assertNull(e.state().goals.get(id).spec.groupId);
        assertEquals(300, e.state().goals.get(id).current.progress());
        assertEquals(143, e.today(e.state().goals.get(id), at("2026-10-16")).assigned);
    }

    @Test
    public void levelFormulaMatchesRuneLiteAndDeadlineEndsWithoutFalseAchievement()
    {
        for (int level = 1; level <= 99; level++) { assertEquals(Experience.getXpForLevel(level), GoalSpec.xpForLevel(level)); }
        GoalEngine e = engine();
        String id = e.add(new GoalSpec("Level", null, "MINING", GoalSpec.Mode.LEVEL, 0, 60, GoalSpec.Cadence.ONCE,
            LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-18"), zone), at("2026-10-16"));
        e.tick(at("2026-10-19"));
        assertEquals("ENDED", e.state().goals.get(id).lifecycle);
        assertNull(e.state().goals.get(id).achievedDate);
    }

    @Test
    public void monthlyTargetRevisionPreservesOriginalDay31Anchor()
    {
        GoalEngine e = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        e.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, at("2026-02-15"));
        String id = e.add(gain(1000, GoalSpec.Cadence.MONTHLY, "2026-01-31", null, null), at("2026-02-15"));
        e.edit(id, gain(2000, GoalSpec.Cadence.MONTHLY, "2026-01-31", null, null), at("2026-02-15"));
        assertNull(e.state().goals.get(id).pendingEffectiveDate);
        assertEquals(2000, e.state().goals.get(id).current.target);
        e.tick(at("2026-03-01"));
        assertEquals("2026-02-28", e.state().goals.get(id).current.start);
        assertEquals("2026-03-31", e.state().goals.get(id).current.endExclusive);
        assertEquals(2000, e.state().goals.get(id).current.target);
    }
}
