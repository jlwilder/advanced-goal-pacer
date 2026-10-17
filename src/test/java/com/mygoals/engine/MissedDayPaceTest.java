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

public class MissedDayPaceTest
{
	private final ZoneId zone = ZoneId.of("America/Chicago");
	private final LocalDate start = LocalDate.parse("2026-10-16");

	private Instant time(LocalDate date)
	{
		return date.atTime(12, 0).atZone(zone).toInstant();
	}

	private GoalEngine engine()
	{
		GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
		engine.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, time(start));
		return engine;
	}

	private GoalSpec weekly(long amount)
	{
		return new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, amount, 0,
				GoalSpec.Cadence.WEEKLY, start, null, zone);
	}

	private ProfileState.DayPlan plan(GoalEngine engine, String id, LocalDate date)
	{
		return engine.state().goals.get(id).plans.values().stream().filter(p -> p.date.equals(date.toString()))
				.findFirst().orElseThrow();
	}

	@Test
	public void missedPaceRisesAndRecoveryIsCreditedOnlyOnReturnWithoutChangingPastAssignments()
	{
		GoalEngine engine = engine();
		String id = engine.add(weekly(100), time(start));
		engine.observe(Map.of("MINING", 1020L), GoalEngine.LIVE, time(start));
		StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
		engine = new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone);
		LocalDate returned = start.plusDays(4);
		engine.observe(Map.of("MINING", 1050L), GoalEngine.RECOVERED, time(returned));
		assertEquals(15, plan(engine, id, start).assigned);
		assertEquals(14, plan(engine, id, start.plusDays(1)).assigned);
		assertEquals(16, plan(engine, id, start.plusDays(2)).assigned);
		assertEquals(20, plan(engine, id, start.plusDays(3)).assigned);
		assertEquals(0, plan(engine, id, start.plusDays(3)).credited);
		assertEquals(27, plan(engine, id, returned).assigned);
		assertEquals(30, plan(engine, id, returned).credited);
		engine.edit(id, weekly(200), time(returned));
		assertEquals(20, plan(engine, id, start.plusDays(3)).assigned);
		int records = engine.state().goals.get(id).plans.size();
		engine.tick(time(returned));
		assertEquals(records, engine.state().goals.get(id).plans.size());
	}

	@Test
	public void completelyMissedWeeksResetTheirOwnQuotasAndDoNotCreateCelebrations()
	{
		GoalEngine engine = engine();
		String id = engine.add(weekly(70), time(start));
		engine.observe(Map.of("MINING", 1010L), GoalEngine.LIVE, time(start));
		int celebrations = engine.state().celebrations.size();
		engine.tick(time(start.plusDays(16)));
		assertEquals(17, engine.state().goals.get(id).plans.size());
		assertEquals(60, plan(engine, id, start.plusDays(6)).assigned);
		assertEquals(10, plan(engine, id, start.plusDays(7)).assigned);
		assertEquals(70, plan(engine, id, start.plusDays(13)).assigned);
		assertEquals(10, plan(engine, id, start.plusDays(14)).assigned);
		assertEquals(14, plan(engine, id, start.plusDays(16)).assigned);
		assertEquals(celebrations, engine.state().celebrations.size());
	}

	@Test
	public void pendingCadenceChangesApplyAtTheOriginalPeriodBoundary()
	{
		GoalEngine engine = engine();
		String id = engine.add(weekly(70), time(start));
		engine.edit(id, new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 70, 0,
				GoalSpec.Cadence.DAILY, start, null, zone), time(start));
		engine.tick(time(start.plusDays(9)));
		assertEquals(70, plan(engine, id, start.plusDays(6)).assigned);
		assertEquals(70, plan(engine, id, start.plusDays(7)).assigned);
		assertEquals(70, plan(engine, id, start.plusDays(8)).assigned);
		assertEquals(GoalSpec.Cadence.DAILY, engine.state().goals.get(id).spec.cadence);
	}

	@Test
	public void calendarDaysAcrossDstAreRecordedOnceAndStopAtTheDeadline()
	{
		GoalEngine engine = engine();
		LocalDate first = LocalDate.parse("2026-10-30");
		String id = engine.add(new GoalSpec("Deadline", null, "MINING", GoalSpec.Mode.GAIN, 50, 0,
				GoalSpec.Cadence.ONCE, first, first.plusDays(4), zone), time(first));
		engine.tick(time(first.plusDays(8)));
		assertEquals("ENDED", engine.state().goals.get(id).lifecycle);
		assertEquals(5, engine.state().goals.get(id).plans.size());
		assertEquals(13, plan(engine, id, first.plusDays(1)).assigned);
		assertEquals(17, plan(engine, id, first.plusDays(2)).assigned);
		assertEquals(25, plan(engine, id, first.plusDays(3)).assigned);
		assertEquals(50, plan(engine, id, first.plusDays(4)).assigned);
	}

	@Test
	public void countPaceUsesTheLifetimeRemainderWithoutInventingOfflineKills()
	{
		GoalEngine engine = engine();
		GoalSpec spec = new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 100, 0,
				GoalSpec.Cadence.WEEKLY, start, null, zone, Map.of(), 1000L);
		String old = engine.add(spec, time(start));
		engine.observeCount("BRUTUS", 990, false, GoalEngine.RECOVERED, null, time(start));
		String id = engine.add(spec, time(start));
		engine.remove(old);
		engine.tick(time(start.plusDays(4)));
		assertEquals(2, plan(engine, id, start.plusDays(1)).assigned);
		assertEquals(2, plan(engine, id, start.plusDays(2)).assigned);
		assertEquals(3, plan(engine, id, start.plusDays(3)).assigned);
		assertEquals(4, plan(engine, id, start.plusDays(4)).assigned);
		assertTrue(engine.state().goals.get(id).plans.values().stream().allMatch(p -> p.credited == 0));
		engine.observeCount("BRUTUS", 992, true, GoalEngine.LIVE, null, time(start.plusDays(4)));
		assertEquals(1, plan(engine, id, start.plusDays(4)).credited);
		assertEquals(3, plan(engine, id, start.plusDays(3)).assigned);
	}

	@Test
	public void monthEndAnchorsResetThePaceWithoutDriftingAfterFebruary()
	{
		GoalEngine engine = engine();
		LocalDate first = LocalDate.parse("2026-01-31");
		String id = engine.add(new GoalSpec("Monthly", null, "MINING", GoalSpec.Mode.GAIN, 310, 0,
				GoalSpec.Cadence.MONTHLY, first, null, zone), time(first));
		engine.tick(time(LocalDate.parse("2026-03-02")));
		assertEquals(310, plan(engine, id, LocalDate.parse("2026-02-27")).assigned);
		assertEquals(10, plan(engine, id, LocalDate.parse("2026-02-28")).assigned);
		assertEquals(11, plan(engine, id, LocalDate.parse("2026-03-01")).assigned);
		assertEquals("2026-03-31", engine.state().goals.get(id).current.endExclusive);
	}

	@Test
	public void scheduledGoalsDoNotInventTrackingBeforeTheyActuallyStart()
	{
		GoalEngine engine = engine();
		String id = engine.add(new GoalSpec("Future", null, "MINING", GoalSpec.Mode.GAIN, 70, 0,
				GoalSpec.Cadence.WEEKLY, start.plusDays(2), null, zone), time(start));
		engine.observe(Map.of("MINING", 1500L), GoalEngine.RECOVERED, time(start.plusDays(5)));
		assertEquals(1, engine.state().goals.get(id).plans.size());
		assertEquals(0, plan(engine, id, start.plusDays(5)).credited);
		assertEquals(1500, engine.state().goals.get(id).baselineXp.longValue());
	}
}
