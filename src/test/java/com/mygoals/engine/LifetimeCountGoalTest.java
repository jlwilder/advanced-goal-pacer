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

public class LifetimeCountGoalTest
{
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    private GoalSpec spec(Long target)
    {
        return new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(), target);
    }
    private void observe(GoalEngine e, long total, boolean live)
    {
        e.observeCount("BRUTUS", total, live, live ? GoalEngine.LIVE : GoalEngine.RECOVERED, null, now);
    }

    @Test public void lifetimeTargetUsesAbsoluteCountsWhilePeriodCreditsStillExcludeOldKills()
    {
        GoalEngine e = engine(); String id = e.add(spec(1000L), now);
        assertNull(e.state().goals.get(id).countCheckpoint);
        observe(e, 987, false);
        assertEquals(0, e.state().goals.get(id).current.progress());
        observe(e, 988, true); observe(e, 988, true); observe(e, 986, false);
        assertEquals(1, e.state().goals.get(id).current.progress());
        assertEquals(Long.valueOf(988), e.state().goals.get(id).countCheckpoint);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        e = new GoalEngine(codec.decode(codec.encode(e.state()), e.state().profileKey), zone);
        assertEquals(Long.valueOf(1000), e.state().goals.get(id).spec.totalCountTarget);
        observe(e, 1000, false); observe(e, 1000, true);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertEquals(13, e.state().goals.get(id).current.progress());
        assertEquals(12, e.state().goals.get(id).current.recovered);
        assertEquals(13, e.state().activity.values().stream().mapToLong(a -> a.amount).sum());
    }

    @Test public void alreadyReachedFirstTotalCompletesWithoutManufacturingPeriodProgress()
    {
        GoalEngine e = engine(); String id = e.add(spec(1000L), now);
        observe(e, 1002, false);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertEquals(0, e.state().goals.get(id).current.progress());
        assertTrue(e.state().activity.isEmpty());
    }

    @Test public void newGoalUsesKnownTotalAndNearTargetDailyShareWithoutReusingOldCredit()
    {
        GoalEngine e = engine(); String old = e.add(spec(null), now);
        observe(e, 999, true);
        String id = e.add(spec(1000L), now);
        assertEquals(Long.valueOf(999), e.state().goals.get(id).countCheckpoint);
        assertEquals(0, e.state().goals.get(id).current.progress());
        assertEquals(1, e.today(e.state().goals.get(id), now).assigned);
        e.remove(old); observe(e, 1000, true);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertEquals(1, e.state().goals.get(id).current.progress());
        assertEquals(2, e.state().activity.values().stream().mapToLong(a -> a.amount).sum());
        String alreadyMet = e.add(spec(1000L), now);
        assertEquals("ACHIEVED", e.state().goals.get(alreadyMet).lifecycle);
        assertEquals(0, e.state().goals.get(alreadyMet).current.progress());
    }

    @Test public void existingRemoteCheckpointCanSeedLifetimeTargetButNotNewPeriodGains()
    {
        GoalEngine e = engine(); e.add(spec(null), now); observe(e, 1000, false);
        assertTrue(e.state().counts.isEmpty());
        String id = e.add(spec(1000L), now);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertEquals(0, e.state().goals.get(id).current.progress());
        assertEquals(Long.valueOf(1000), spec(1000L).withGroup("group").totalCountTarget);
        assertEquals(Long.valueOf(1000), spec(1000L).anchoredAt(LocalDate.parse("2026-10-17")).totalCountTarget);
    }

    @Test public void lifetimeTargetEditsApplyNowAndFutureGoalsWaitForActivation()
    {
        GoalEngine e = engine(); String id = e.add(spec(1000L), now);
        observe(e, 100, false); e.edit(id, spec(100L), now);
        assertNull(e.state().goals.get(id).pending);
        assertEquals("ACHIEVED", e.state().goals.get(id).lifecycle);
        assertEquals(Long.valueOf(100), e.state().goals.get(id).spec.totalCountTarget);
        String future = e.add(spec(100L).anchoredAt(LocalDate.parse("2026-10-17")), now);
        assertEquals("SCHEDULED", e.state().goals.get(future).lifecycle);
        e.tick(now.plusSeconds(86400));
        assertEquals("ACHIEVED", e.state().goals.get(future).lifecycle);
    }

    @Test public void invalidLifetimeTargetsCannotMixWithXpUniquesOrFiniteSchedules()
    {
        assertThrows(IllegalArgumentException.class, () -> spec(0L));
        assertThrows(IllegalArgumentException.class, () -> spec((long) Integer.MAX_VALUE + 1));
        assertThrows(IllegalArgumentException.class, () -> new GoalSpec("Bad", null, GoalSpec.TrackType.EXPERIENCE, "MINING", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(), 1000L));
        assertThrows(IllegalArgumentException.class, () -> new GoalSpec("Bad", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(33101, 3L), 1000L));
        assertThrows(IllegalArgumentException.class, () -> new GoalSpec("Bad", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-30"), zone, Map.of(), 1000L));
        assertThrows(IllegalArgumentException.class, () -> new GoalSpec("Bad", null, GoalSpec.TrackType.COUNT, "SOUL_WARS_ZEAL", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(), 1000L));
    }
}
