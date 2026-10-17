package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.persistence.StateCodec;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.Test;

import static org.junit.Assert.*;

public class DailyPaceSeriesTest
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

	private GoalSpec mining(long amount)
	{
		return new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, amount, 0, GoalSpec.Cadence.WEEKLY, start, null, zone);
	}

	private GoalsView.Goal view(GoalEngine engine, LocalDate date)
	{
		return new GoalsView("test", "", "", true, engine.state(), time(date)).goals.get(0);
	}

	private DailyPaceSeries.Point point(GoalsView.Goal goal, LocalDate date)
	{
		return DailyPaceSeries.build(goal, List.of(), date).get(0);
	}

	@Test
	public void deferralRaisesTheFuturePaceAndExtraWorkLowersItWithoutRewritingPastTargets()
	{
		GoalEngine engine = engine();
		String id = engine.add(mining(100), time(start));
		assertEquals(Long.valueOf(15), point(view(engine, start), start.plusDays(1)).pace);
		engine.postponeGoal(id, true, time(start));
		assertEquals(Long.valueOf(17), point(view(engine, start), start.plusDays(1)).pace);
		LocalDate tomorrow = start.plusDays(1);
		engine.tick(time(tomorrow));
		engine.postponeGoal(id, true, time(tomorrow));
		assertEquals(Long.valueOf(20), point(view(engine, tomorrow), start.plusDays(2)).pace);
		assertEquals(Long.valueOf(15), point(view(engine, tomorrow), start).pace);
		assertEquals(Long.valueOf(17), point(view(engine, tomorrow), tomorrow).pace);
		engine.observe(Map.of("MINING", 1040L), GoalEngine.LIVE, time(tomorrow));
		assertEquals(Long.valueOf(40), point(view(engine, tomorrow), tomorrow).completed);
		assertEquals(Long.valueOf(12), point(view(engine, tomorrow), start.plusDays(2)).pace);
		engine.edit(id, mining(200), time(tomorrow));
		assertEquals(Long.valueOf(15), point(view(engine, tomorrow), start).pace);
		StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
		GoalEngine reloaded = new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone);
		assertEquals(Long.valueOf(15), point(view(reloaded, tomorrow), start).pace);
	}

	@Test
	public void kcBarsShowEarnedCompletionsAndThePaceCanBeLowerThanTheBar()
	{
		GoalEngine engine = engine();
		engine.add(new GoalSpec("Vorkath", null, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN,
				10, 0, GoalSpec.Cadence.WEEKLY, start, null, zone), time(start));
		engine.observeCount("VORKATH", 100, false, GoalEngine.RECOVERED, null, time(start));
		for (long kc = 101; kc <= 105; kc++)
		{
			engine.observeCount("VORKATH", kc, true, GoalEngine.LIVE, null, time(start));
		}
		GoalsView.Goal goal = view(engine, start);
		assertEquals(Long.valueOf(5), point(goal, start).completed);
		assertEquals(Long.valueOf(2), point(goal, start).pace);
		assertEquals(Long.valueOf(1), point(goal, start.plusDays(1)).pace);
		assertNull(point(goal, start.plusDays(1)).completed);
		assertTrue(point(goal, start.plusDays(1)).projected);
	}

	@Test
	public void barsArePerGoalAndOldAndRecentRecordsAreNotDoubleCounted()
	{
		GoalEngine engine = engine();
		engine.add(mining(100), time(start));
		engine.add(mining(200), time(start));
		engine.observe(Map.of("MINING", 1050L), GoalEngine.LIVE, time(start));
		GoalsView.Goal goal = view(engine, start);
		assertEquals(Long.valueOf(50), point(goal, start).completed);
		assertEquals(Long.valueOf(50), DailyPaceSeries.build(goal, goal.days, start).get(0).completed);
		engine.tick(time(start.plusDays(3)));
		assertEquals(Long.valueOf(9), point(view(engine, start.plusDays(3)), start.plusDays(1)).pace);
		assertEquals(Long.valueOf(0), point(view(engine, start.plusDays(3)), start.plusDays(1)).completed);
	}

	@Test
	public void deadlineProjectionReachesTheEndUsingBoundedWindowsAndRepeatingQuotasReset()
	{
		GoalEngine engine = engine();
		LocalDate deadline = start.plusYears(10);
		engine.add(new GoalSpec("Long goal", null, "MINING", GoalSpec.Mode.GAIN, 100000, 0, GoalSpec.Cadence.ONCE, start, deadline, zone),
				time(start));
		var points = DailyPaceSeries.build(view(engine, start), List.of(), deadline.minusDays(27));
		assertEquals(28, points.size());
		assertEquals(deadline, points.get(27).date);
		assertNotNull(points.get(27).pace);
		GoalEngine repeating = engine();
		repeating.add(new GoalSpec("Weekly", null, "MINING", GoalSpec.Mode.GAIN, 70, 0,
				GoalSpec.Cadence.WEEKLY, start, start.plusDays(30), zone), time(start));
		assertEquals(Long.valueOf(10), point(view(repeating, start), start.plusDays(7)).pace);
	}

	@Test
	public void chartIsBelowSecondBarAndNavigatesWithoutChangingPersistedAssignments() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			GoalEngine engine = engine();
			engine.add(new GoalSpec("Deadline", null, "MINING", GoalSpec.Mode.GAIN, 10000, 0,
					GoalSpec.Cadence.ONCE, start, start.plusDays(75), zone), time(start));
			GoalDetailsPanel details = new GoalDetailsPanel((id, label) ->
			{
			});
			details.render(view(engine, start), false);
			DailyPaceChart chart = (DailyPaceChart) find(details, "dailyPaceChart");
			assertTrue(details.getComponentZOrder(chart) > details.getComponentZOrder(find(details, "periodProgress")));
			int plans = engine.state().goals.values().iterator().next().plans.size();
			((JButton) find(chart, "dailyChartEnd")).doClick();
			assertEquals(start.plusDays(75), chart.points().get(chart.points().size() - 1).date);
			assertEquals(plans, engine.state().goals.values().iterator().next().plans.size());
			chart.render(view(engine, start), GoalDateFormat.EUROPEAN);
			assertTrue(((javax.swing.JLabel) find(chart, "dailyChartRange")).getText().startsWith("03/12/26"));
		});
	}

	@Test
	public void scheduledAmountGoalsForecastWithoutInventingEarnedWork()
	{
		GoalEngine engine = engine();
		LocalDate begins = start.plusDays(2);
		engine.add(new GoalSpec("Future mining", null, "MINING", GoalSpec.Mode.GAIN, 700, 0,
				GoalSpec.Cadence.ONCE, begins, begins.plusDays(6), zone), time(start));
		GoalsView.Goal goal = view(engine, start);
		assertEquals("SCHEDULED", goal.lifecycle);
		assertEquals(Long.valueOf(100), point(goal, begins).pace);
		assertNull(point(goal, begins).completed);
	}

	@Test
	public void oldChartWindowsLoadOnceAndLiveProgressDoesNotPollHistory() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			GoalEngine engine = engine();
			String id = engine.add(mining(100), time(start));
			LocalDate today = start.plusDays(90);
			for (int i = 1; i <= 90; i++)
			{
				engine.tick(time(start.plusDays(i)));
			}
			var fragments = com.mygoals.persistence.HistoryArchive.detach(engine.state(), today);
			var archived = fragments.values().stream().filter(f -> f.goals.containsKey(id))
					.flatMap(f -> f.goals.get(id).plans.values().stream()).collect(java.util.stream.Collectors.toList());
			DailyPaceChart chart = new DailyPaceChart();
			java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
			chart.setLoader((first, end, done) ->
			{
				requests.incrementAndGet();
				var selected = archived.stream().filter(plan -> plan.date.compareTo(first) >= 0 && plan.date.compareTo(end) < 0)
						.collect(java.util.stream.Collectors.toList());
				done.accept(new GoalsView.HistoryPage(List.of(), selected, null, false, ""));
			});
			chart.render(view(engine, today), GoalDateFormat.AMERICAN);
			assertEquals(0, requests.get());
			((JButton) find(chart, "dailyChartPrevious")).doClick();
			assertEquals(1, requests.get());
			assertNotNull(chart.points().get(0).pace);
			engine.observe(Map.of("MINING", 1005L), GoalEngine.LIVE, time(today));
			chart.render(view(engine, today), GoalDateFormat.AMERICAN);
			assertEquals(1, requests.get());
		});
	}

	private static java.awt.Component find(java.awt.Container container, String name)
	{
		for (java.awt.Component child : container.getComponents())
		{
			if (name.equals(child.getName()))
			{
				return child;
			}
			if (child instanceof java.awt.Container)
			{
				var found = find((java.awt.Container) child, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}
}
