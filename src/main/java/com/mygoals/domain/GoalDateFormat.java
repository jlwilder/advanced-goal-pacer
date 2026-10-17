package com.mygoals.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Display preference only; persisted calendar dates remain ISO dates. */
public enum GoalDateFormat
{
	AMERICAN("American (MM/DD/YY)", "MM/dd/yy", "MMMM d, yyyy"),
	EUROPEAN("European (DD/MM/YY)", "dd/MM/yy", "d MMMM yyyy");

	private final String label;
	private final DateTimeFormatter shortDate, longDate, timestamp;

	GoalDateFormat(String label, String shortPattern, String longPattern)
	{
		this.label = label;
		shortDate = DateTimeFormatter.ofPattern(shortPattern, Locale.US);
		longDate = DateTimeFormatter.ofPattern(longPattern, Locale.US);
		timestamp = DateTimeFormatter.ofPattern(shortPattern + " h:mm a z", Locale.US);
	}

	public String date(LocalDate date)
	{
		return shortDate.format(date);
	}

	public String longDate(LocalDate date)
	{
		return longDate.format(date);
	}

	public String timestamp(Instant instant, ZoneId zone)
	{
		return timestamp.format(instant.atZone(zone));
	}

	@Override
	public String toString()
	{
		return label;
	}
}
