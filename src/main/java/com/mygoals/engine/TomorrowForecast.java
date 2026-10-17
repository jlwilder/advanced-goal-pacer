package com.mygoals.engine;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Read-only estimate. Never creates or freezes a future daily assignment. */
public final class TomorrowForecast
{
	public final String date;
	public final Long assigned;
	public final long remaining, days;
	public final boolean newPeriod;

	private TomorrowForecast(LocalDate date, Long assigned, long remaining, long days, boolean newPeriod)
	{
		this.date = date.toString();
		this.assigned = assigned;
		this.remaining = remaining;
		this.days = days;
		this.newPeriod = newPeriod;
	}

	public static TomorrowForecast forGoal(ProfileState.Goal goal, Instant now)
	{
		ProfileState.Period period = goal.current;
		ZoneId zone = ZoneId.of(period.zone);
		LocalDate tomorrow = now.atZone(zone).toLocalDate().plusDays(1);
		LocalDate end = LocalDate.parse(period.endExclusive);
		long days = ChronoUnit.DAYS.between(tomorrow, end);
		long remaining = Math.max(0, period.target - period.progress());
		boolean newPeriod = days <= 0;
		if (newPeriod)
		{
			GoalSpec next = goal.pending == null ? goal.spec : goal.pending;
			Instant boundary = tomorrow.atStartOfDay(zone).toInstant();
			// A deferred time-zone change can start slightly after the previous zone's
			// midnight.
			Instant start = LocalDate.parse(next.startDate).atStartOfDay(ZoneId.of(next.zone)).toInstant();
			CalendarService.Window window = new CalendarService().current(next, boundary.isBefore(start) ? start : boundary);
			if (window == null)
			{
				return new TomorrowForecast(tomorrow, null, remaining, 0, false);
			}
			tomorrow = (boundary.isBefore(start) ? start : boundary).atZone(window.zone).toLocalDate();
			days = ChronoUnit.DAYS.between(tomorrow, window.endExclusive);
			remaining = next.amount; // Level goals always use one finite period.
		}
		if (goal.spec.totalCountTarget != null && goal.countCheckpoint != null)
		{
			remaining = Math.min(remaining, Math.max(0, goal.spec.totalCountTarget - goal.countCheckpoint));
		}
		return new TomorrowForecast(tomorrow, CalendarService.share(remaining, days), remaining, days, newPeriod);
	}
}
