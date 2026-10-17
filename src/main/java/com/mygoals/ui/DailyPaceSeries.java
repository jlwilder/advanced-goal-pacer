package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.engine.CalendarService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only, bounded chart projection. Forecasts never replace persisted daily
 * assignments.
 */
final class DailyPaceSeries
{
	static final int WINDOW_DAYS = 28;

	static final class Point
	{
		final LocalDate date;
		final Long completed, pace;
		final boolean projected, deferred;

		Point(LocalDate date, Long completed, Long pace, boolean projected, boolean deferred)
		{
			this.date = date;
			this.completed = completed;
			this.pace = pace;
			this.projected = projected;
			this.deferred = deferred;
		}
	}

	static LocalDate first(GoalsView.Goal goal)
	{
		return goal.trackingStartedAt == 0 ? LocalDate.parse(goal.spec.startDate)
				: java.time.Instant.ofEpochMilli(goal.trackingStartedAt).atZone(ZoneId.of(goal.spec.zone)).toLocalDate();
	}

	static LocalDate last(GoalsView.Goal goal)
	{
		if (goal.achievedDate != null)
		{
			return LocalDate.parse(goal.achievedDate);
		}
		if (goal.spec.endDate != null)
		{
			return LocalDate.parse(goal.spec.endDate);
		}
		if (goal.periodEnd != null)
		{
			return LocalDate.parse(goal.periodEnd);
		}
		LocalDate start = LocalDate.parse(goal.spec.startDate);
		CalendarService.Window window = new CalendarService().current(goal.spec, start.atStartOfDay(ZoneId.of(goal.spec.zone)).toInstant());
		return window == null ? start : window.endExclusive.minusDays(1);
	}

	static List<Point> build(GoalsView.Goal goal, Collection<GoalsView.Day> archived, LocalDate start)
	{
		Map<String, GoalsView.Day> records = new LinkedHashMap<>();
		archived.forEach(day -> records.put(day.periodId + "@" + day.date, day));
		goal.days.forEach(day -> records.put(day.periodId + "@" + day.date, day));
		LocalDate today = LocalDate.parse(goal.todayDate), last = last(goal);
		List<Point> points = new ArrayList<>();
		for (int index = 0; index < WINDOW_DAYS && !start.plusDays(index).isAfter(last); index++)
		{
			LocalDate date = start.plusDays(index);
			long assigned = 0, credited = 0;
			boolean known = false, deferred = false;
			for (GoalsView.Day day : records.values())
			{
				if (!day.date.equals(date.toString()))
				{
					continue;
				}
				known = true;
				assigned = Math.addExact(assigned, day.assigned);
				credited = Math.addExact(credited, day.credited);
				deferred |= day.deferred;
			}
			boolean future = date.isAfter(today);
			points.add(new Point(date, known ? Long.valueOf(credited) : null,
					known ? Long.valueOf(assigned) : future ? forecast(goal, date) : null,
					future && !known, deferred));
		}
		return List.copyOf(points);
	}

	private static Long forecast(GoalsView.Goal goal, LocalDate date)
	{
		if (!goal.compatibilityIssue.isEmpty() || goal.lifecycle.equals("ACHIEVED") || goal.lifecycle.equals("ENDED"))
		{
			return null;
		}
		LocalDate tomorrow = LocalDate.parse(goal.todayDate).plusDays(1);
		if (goal.periodEndExclusive != null && date.isBefore(LocalDate.parse(goal.periodEndExclusive)))
		{
			long remaining = Math.max(0, goal.target - goal.progress);
			if (goal.spec.totalCountTarget != null && goal.totalCount != null)
			{
				remaining = Math.min(remaining, Math.max(0, goal.spec.totalCountTarget - goal.totalCount));
			}
			// The forecast assumes today's outstanding assignment is done unless deferred.
			if (goal.inToday && !goal.inTomorrow)
			{
				remaining = Math.max(0, remaining - Math.max(0, goal.todayAssigned - goal.todayCredited));
			}
			return share(remaining, tomorrow, LocalDate.parse(goal.periodEndExclusive), date);
		}
		GoalSpec spec = goal.pendingStart != null && !date.isBefore(LocalDate.parse(goal.pendingStart)) ? goal.editSpec : goal.spec;
		if (spec.mode == GoalSpec.Mode.LEVEL)
		{
			return null;
		}
		CalendarService.Window window = new CalendarService().current(spec, date.atTime(12, 0).atZone(ZoneId.of(spec.zone)).toInstant());
		return window == null ? null : share(spec.amount, window.start, window.endExclusive, date);
	}

	private static Long share(long remaining, LocalDate first, LocalDate end, LocalDate date)
	{
		long days = ChronoUnit.DAYS.between(first, end), offset = ChronoUnit.DAYS.between(first, date);
		if (days <= 0 || offset < 0 || offset >= days)
		{
			return null;
		}
		return remaining / days + (offset < remaining % days ? 1 : 0);
	}
}
