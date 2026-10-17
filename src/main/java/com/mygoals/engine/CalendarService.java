package com.mygoals.engine;

import com.mygoals.domain.GoalSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class CalendarService
{
	public static final class Window
	{
		public final LocalDate start, endExclusive;
		public final ZoneId zone;

		Window(LocalDate start, LocalDate endExclusive, ZoneId zone)
		{
			this.start = start;
			this.endExclusive = endExclusive;
			this.zone = zone;
		}

		public Instant startInstant()
		{
			return start.atStartOfDay(zone).toInstant();
		}

		public Instant endInstant()
		{
			return endExclusive.atStartOfDay(zone).toInstant();
		}
	}

	public Window current(GoalSpec spec, Instant now)
	{
		ZoneId zone = ZoneId.of(spec.zone);
		LocalDate today = now.atZone(zone).toLocalDate();
		LocalDate anchor = LocalDate.parse(spec.startDate);
		LocalDate finalDate = spec.endDate == null ? null : LocalDate.parse(spec.endDate);
		if (today.isBefore(anchor) || (finalDate != null && today.isAfter(finalDate)))
		{
			return null;
		}
		LocalDate start, end;
		switch (spec.cadence)
		{
		case DAILY:
			start = today;
			end = today.plusDays(1);
			break;
		case WEEKLY:
			start = anchor.plusWeeks(ChronoUnit.DAYS.between(anchor, today) / 7);
			end = start.plusWeeks(1);
			break;
		case MONTHLY:
			long months = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(today));
			if (anchor.plusMonths(months).isAfter(today))
			{
				months--;
			}
			start = anchor.plusMonths(months);
			end = anchor.plusMonths(months + 1); // Preserve Jan 31 rather than drifting to Mar 28.
			break;
		default:
			start = anchor;
			end = finalDate.plusDays(1);
		}
		if (finalDate != null && finalDate.plusDays(1).isBefore(end))
		{
			end = finalDate.plusDays(1);
		}
		return new Window(start, end, zone);
	}

	public static long share(long remaining, long days)
	{
		if (remaining < 0 || days <= 0)
		{
			throw new IllegalArgumentException("Invalid remaining work or calendar days");
		}
		return remaining / days + (remaining % days == 0 ? 0 : 1);
	}
}
