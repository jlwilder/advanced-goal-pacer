package com.mygoals.engine;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.persistence.StateCodec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class CountGoalTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    private String add(GoalEngine e, String metric, GoalSpec.Cadence cadence, String start)
    {
        return e.add(new GoalSpec(metric, null, GoalSpec.TrackType.COUNT, metric, GoalSpec.Mode.GAIN, 10, 0, cadence,
            LocalDate.parse(start), null, zone), now);
    }
    private void observe(GoalEngine e, String metric, long total, boolean completion, String source)
    {
        e.observeCount(metric, total, completion, source, null, now);
    }

    @Test public void firstLiveCompletionExcludesLifetimeTotalAndCreditsBothGoalsOnce()
    {
        GoalEngine e = engine();
        String daily = add(e, "THE_CORRUPTED_GAUNTLET", GoalSpec.Cadence.DAILY, "2026-10-16");
        String weekly = add(e, "THE_CORRUPTED_GAUNTLET", GoalSpec.Cadence.WEEKLY, "2026-10-16");
        observe(e, "THE_CORRUPTED_GAUNTLET", 104, true, GoalEngine.LIVE);
        observe(e, "THE_CORRUPTED_GAUNTLET", 104, true, GoalEngine.LIVE);
        assertEquals(1, e.state().goals.get(daily).current.progress());
        assertEquals(1, e.state().goals.get(weekly).current.progress());
        assertEquals(1, e.state().activity.values().iterator().next().amount);
        assertEquals(2, e.state().activity.values().iterator().next().goalCredits.size());
        assertEquals(2, e.today(e.state().goals.get(weekly), now).assigned);
    }

    @Test public void recreatedBrutusGoalStartsFreshAndCountsNewKillsWithoutOldCredit() throws Exception
    {
        GoalEngine e = engine();
        String old = add(e, "BRUTUS", GoalSpec.Cadence.WEEKLY, "2026-10-16");
        for (long total = 55; total <= 64; total++) { observe(e, "BRUTUS", total, true, GoalEngine.LIVE); }
        assertEquals(10, e.state().goals.get(old).current.progress());
        e.remove(old);
        String fresh = add(e, "BRUTUS", GoalSpec.Cadence.WEEKLY, "2026-10-16");
        observe(e, "BRUTUS", 64, false, GoalEngine.RECOVERED);
        assertEquals(0, e.state().goals.get(fresh).current.progress());
        observe(e, "BRUTUS", 64, true, GoalEngine.LIVE);
        assertEquals(0, e.state().goals.get(fresh).current.progress());
        observe(e, "BRUTUS", 65, true, GoalEngine.LIVE);
        observe(e, "BRUTUS", 65, true, GoalEngine.LIVE);
        assertEquals(1, e.state().goals.get(fresh).current.progress());
        assertEquals(11, e.state().activity.values().stream().mapToLong(a -> a.amount).sum());
        assertEquals("ACTIVE", e.state().goals.get(fresh).lifecycle);
    }

    @Test public void delayedBaselineCannotSwallowFirstCompletionOrCreditOldLaggedGains()
    {
        GoalEngine e = engine();
        String id = add(e, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-16");
        observe(e, "VORKATH", 100, false, GoalEngine.RECOVERED);
        assertEquals(0, e.state().goals.get(id).current.progress());
        observe(e, "VORKATH", 100, true, GoalEngine.LIVE);
        observe(e, "VORKATH", 100, true, GoalEngine.LIVE);
        assertEquals(1, e.state().goals.get(id).current.progress());
        GoalEngine second = engine();
        String other = add(second, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-16");
        observe(second, "VORKATH", 80, false, GoalEngine.RECOVERED);
        observe(second, "VORKATH", 100, true, GoalEngine.LIVE);
        assertEquals(1, second.state().goals.get(other).current.progress());
    }

    @Test public void staleAndLaterRemoteTotalsCannotDuplicateLiveCompletionsAfterRestart()
    {
        GoalEngine e = engine();
        String id = add(e, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-16");
        observe(e, "VORKATH", 100, true, GoalEngine.LIVE);
        observe(e, "VORKATH", 101, true, GoalEngine.LIVE);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        e = new GoalEngine(codec.decode(codec.encode(e.state()), "a".repeat(64)), zone);
        observe(e, "VORKATH", 99, false, GoalEngine.RECOVERED);
        observe(e, "VORKATH", 101, false, GoalEngine.RECOVERED);
        observe(e, "VORKATH", 104, false, GoalEngine.RECOVERED);
        observe(e, "VORKATH", 104, true, GoalEngine.LIVE);
        observe(e, "VORKATH", 104, false, GoalEngine.RECOVERED);
        assertEquals(5, e.state().goals.get(id).current.progress());
        assertEquals(3, e.state().goals.get(id).current.recovered);
        assertEquals(5, e.state().activity.values().stream().mapToLong(a -> a.amount).sum());
    }

    @Test public void remoteRequestCannotBaselineAGoalCreatedAfterItStarted()
    {
        GoalEngine e = engine();
        String first = add(e, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-16");
        Set<String> eligible = e.activeCountGoals();
        String later = add(e, "VORKATH", GoalSpec.Cadence.WEEKLY, "2026-10-16");
        e.observeCount("VORKATH", 100, false, GoalEngine.RECOVERED, eligible, now);
        assertNotNull(e.state().goals.get(first).countCheckpoint);
        assertNull(e.state().goals.get(later).countCheckpoint);
        observe(e, "VORKATH", 101, true, GoalEngine.LIVE);
        assertEquals(1, e.state().goals.get(first).current.progress());
        assertEquals(1, e.state().goals.get(later).current.progress());
    }

    @Test public void scoreDropsAndReboundsBelowPriorBestCannotManufactureGains()
    {
        GoalEngine e = engine();
        String id = add(e, "PVP_ARENA_RANK", GoalSpec.Cadence.DAILY, "2026-10-16");
        observe(e, "PVP_ARENA_RANK", 2000, false, GoalEngine.RECOVERED);
        observe(e, "PVP_ARENA_RANK", 1900, false, GoalEngine.RECOVERED);
        observe(e, "PVP_ARENA_RANK", 2000, false, GoalEngine.RECOVERED);
        observe(e, "PVP_ARENA_RANK", 2005, false, GoalEngine.RECOVERED);
        assertEquals(5, e.state().goals.get(id).current.progress());
    }

    @Test public void laggedInitialHiscoresCannotTurnOldBacklogIntoNewBossProgress()
    {
        GoalEngine e = engine();
        String id = add(e, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-16");
        observe(e, "VORKATH", 80, false, GoalEngine.RECOVERED);
        observe(e, "VORKATH", 99, false, "HiScores refreshed");
        assertEquals(0, e.state().goals.get(id).current.progress());
        observe(e, "VORKATH", 100, true, GoalEngine.LIVE);
        observe(e, "VORKATH", 103, false, GoalEngine.RECOVERED);
        assertEquals(4, e.state().goals.get(id).current.progress());
        assertEquals(3, e.state().goals.get(id).current.recovered);
    }

    @Test public void futureGoalsAndNewPeriodsUseTheirRecognitionDay()
    {
        GoalEngine e = engine();
        String id = add(e, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-17");
        observe(e, "VORKATH", 100, true, GoalEngine.LIVE);
        assertNull(e.state().goals.get(id).countCheckpoint);
        e.observeCount("VORKATH", 101, true, GoalEngine.LIVE, null, now.plusSeconds(86400));
        assertEquals(1, e.state().goals.get(id).current.progress());
        e.observeCount("VORKATH", 104, false, GoalEngine.RECOVERED, null, now.plusSeconds(3 * 86400));
        assertEquals("2026-10-19", e.state().goals.get(id).current.start);
        assertEquals(3, e.state().goals.get(id).current.recovered);
        assertEquals(2, e.state().goals.get(id).history.size());
        assertEquals("2026-10-18", e.state().goals.get(id).history.get(1).start);
        assertEquals(0, e.state().goals.get(id).history.get(1).progress());
    }

    @Test public void finiteCountsAchieveAndRepeatingEditsKeepCheckpointAndTodaysQuota()
    {
        GoalEngine e = engine();
        GoalSpec initial = new GoalSpec("Vorkath", null, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN, 1, 0,
            GoalSpec.Cadence.ONCE, LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-20"), zone);
        String finite = e.add(initial, now);
        String repeating = add(e, "VORKATH", GoalSpec.Cadence.DAILY, "2026-10-16");
        observe(e, "VORKATH", 100, true, GoalEngine.LIVE);
        assertEquals("ACHIEVED", e.state().goals.get(finite).lifecycle);
        e.edit(repeating, new GoalSpec("Vorkath", null, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN, 20, 0,
            GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-16"), null, zone), now);
        assertEquals(20, e.state().goals.get(repeating).current.target);
        assertEquals(20, e.today(e.state().goals.get(repeating), now).assigned);
        e.observeCount("VORKATH", 101, true, GoalEngine.LIVE, null, now.plusSeconds(86400));
        assertEquals(20, e.state().goals.get(repeating).current.target);
        assertEquals(1, e.state().goals.get(repeating).current.progress());
        assertEquals(Long.valueOf(101), e.state().goals.get(repeating).countCheckpoint);
        assertEquals(1, e.state().goals.get(finite).current.progress());
    }
}
