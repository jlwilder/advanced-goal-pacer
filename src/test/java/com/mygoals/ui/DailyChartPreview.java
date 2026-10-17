package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Synthetic chart fixtures; never reads a player's data. */
final class DailyChartPreview
{
	static void write(Path output) throws Exception
	{
		ZoneId zone = ZoneId.of("America/Chicago");
		LocalDate start = LocalDate.parse("2026-10-12");
		GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
		engine.observe(Map.of("MINING", 100000L), GoalEngine.LIVE, start.atTime(12, 0).atZone(zone).toInstant());
		String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 10000, 0, GoalSpec.Cadence.ONCE,
				start, start.plusDays(9), zone), start.atTime(12, 0).atZone(zone).toInstant());
		engine.observe(Map.of("MINING", 100800L), GoalEngine.LIVE, start.atTime(14, 0).atZone(zone).toInstant());
		for (int day = 1; day <= 3; day++)
		{
			var time = start.plusDays(day).atTime(12, 0).atZone(zone).toInstant();
			engine.tick(time);
			engine.postponeGoal(id, true, time);
		}
		var now = start.plusDays(4).atTime(12, 0).atZone(zone).toInstant();
		engine.tick(now);
		engine.observe(Map.of("MINING", 104300L), GoalEngine.LIVE, now);
		GoalsView.Goal goal = new GoalsView("preview", "", "", true, engine.state(), now).goals.get(0);
		GoalDetailsPanel details = new GoalDetailsPanel((item, label) ->
		{
		});
		details.render(goal, false);
		SidebarPreview.render(details, 480, 1060, output.resolve("daily-chart-xp-details.png"));
		DailyPaceChart chart = new DailyPaceChart();
		chart.render(goal, com.mygoals.domain.GoalDateFormat.AMERICAN);
		SidebarPreview.render(chart, 440, 360, output.resolve("daily-chart-xp.png"));
		GoalEngine kc = new GoalEngine(new ProfileState("b".repeat(64)), zone);
		kc.add(new GoalSpec("Vorkath", null, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN, 10, 0,
				GoalSpec.Cadence.WEEKLY, start.plusDays(4), null, zone), now);
		kc.observeCount("VORKATH", 100, false, GoalEngine.RECOVERED, null, now);
		for (int total = 101; total <= 105; total++)
		{
			kc.observeCount("VORKATH", total, true, GoalEngine.LIVE, null, now);
		}
		chart = new DailyPaceChart();
		chart.render(new GoalsView("preview", "", "", true, kc.state(), now).goals.get(0), com.mygoals.domain.GoalDateFormat.AMERICAN);
		SidebarPreview.render(chart, 440, 360, output.resolve("daily-chart-kc.png"));
		GoalEngine offline = new GoalEngine(new ProfileState("c".repeat(64)), zone);
		var began = start.atTime(12, 0).atZone(zone).toInstant();
		offline.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, began);
		offline.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 70, 0,
				GoalSpec.Cadence.WEEKLY, start, null, zone), began);
		offline.observe(Map.of("MINING", 1010L), GoalEngine.LIVE, began);
		offline.observe(Map.of("MINING", 1015L), GoalEngine.RECOVERED, now);
		chart = new DailyPaceChart();
		chart.render(new GoalsView("preview", "", "", true, offline.state(), now).goals.get(0),
				com.mygoals.domain.GoalDateFormat.AMERICAN);
		SidebarPreview.render(chart, 440, 360, output.resolve("daily-chart-offline.png"));
	}
}
