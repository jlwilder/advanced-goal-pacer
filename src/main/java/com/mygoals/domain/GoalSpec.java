package com.mygoals.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable command data; safe to pass from Swing to the client thread. */
public final class GoalSpec
{
	public enum TrackType
	{
		EXPERIENCE, COUNT
	}

	public enum Mode
	{
		GAIN, LEVEL
	}

	public enum Cadence
	{
		DAILY, WEEKLY, MONTHLY, ONCE
	}

	public final String name, groupId, metricId, startDate, endDate, zone;
	public final TrackType trackType;
	public final Mode mode;
	public final Cadence cadence;
	public final long amount;
	public final int level;
	public final Map<Integer, Long> uniqueTargets;
	public final Long totalCountTarget;
	private transient volatile String compatibilityIssue;

	String compatibilityIssue()
	{
		if (compatibilityIssue == null)
		{
			compatibilityIssue = GoalCompatibility.evaluate(this);
		}
		return compatibilityIssue;
	}

	public GoalSpec(String name, String groupId, String skill, Mode mode, long amount, int level,
			Cadence cadence, LocalDate start, LocalDate end, ZoneId zone)
	{
		this(name, groupId, TrackType.EXPERIENCE, skill, mode, amount, level, cadence, start, end, zone);
	}

	public GoalSpec(String name, String groupId, TrackType trackType, String metricId, Mode mode, long amount, int level,
			Cadence cadence, LocalDate start, LocalDate end, ZoneId zone)
	{
		this(name, groupId, trackType, metricId, mode, amount, level, cadence, start, end, zone, Map.of());
	}

	public GoalSpec(String name, String groupId, TrackType trackType, String metricId, Mode mode, long amount, int level,
			Cadence cadence, LocalDate start, LocalDate end, ZoneId zone, Map<Integer, Long> uniqueTargets)
	{
		this(name, groupId, trackType, metricId, mode, amount, level, cadence, start, end, zone, uniqueTargets, null);
	}

	public GoalSpec(String name, String groupId, TrackType trackType, String metricId, Mode mode, long amount, int level,
			Cadence cadence, LocalDate start, LocalDate end, ZoneId zone, Map<Integer, Long> uniqueTargets, Long totalCountTarget)
	{
		this(name, groupId, trackType, metricId, mode, amount, level, cadence, start, end, zone, uniqueTargets, totalCountTarget, true);
	}

	private GoalSpec(String name, String groupId, TrackType trackType, String metricId, Mode mode, long amount, int level,
			Cadence cadence, LocalDate start, LocalDate end, ZoneId zone, Map<Integer, Long> uniqueTargets, Long totalCountTarget,
			boolean current)
	{
		this.name = Objects.requireNonNull(name).trim();
		this.groupId = groupId;
		this.trackType = Objects.requireNonNull(trackType);
		this.metricId = Objects.requireNonNull(metricId);
		this.mode = Objects.requireNonNull(mode);
		this.amount = mode == Mode.GAIN ? amount : 0;
		this.level = mode == Mode.LEVEL ? level : 0;
		this.cadence = Objects.requireNonNull(cadence);
		this.startDate = Objects.requireNonNull(start).toString();
		this.endDate = end == null ? null : end.toString();
		this.zone = Objects.requireNonNull(zone).getId();
		this.uniqueTargets = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(uniqueTargets)));
		this.totalCountTarget = totalCountTarget;
		if (current)
		{
			validate();
		}
		else
		{
			validateStored();
		}
	}

	public void validate()
	{
		validateStored();
		String problem = GoalCompatibility.problem(this);
		if (!problem.isEmpty())
		{
			throw new IllegalArgumentException(problem);
		}
	}

	/**
	 * Storage rules stay stable when the game's current content definitions change.
	 */
	public void validateStored()
	{
		if (name == null || name.isBlank() || name.length() > 100)
		{
			throw new IllegalArgumentException("Use a goal name of 1–100 characters");
		}
		Objects.requireNonNull(trackType, "Choose a track type");
		Objects.requireNonNull(uniqueTargets, "Missing unique targets");
		if (metricId == null || !metricId.matches("[A-Z][A-Z_0-9]{0,99}"))
		{
			throw new IllegalArgumentException("Choose a skill or activity");
		}
		Objects.requireNonNull(mode, "Choose a goal mode");
		Objects.requireNonNull(cadence, "Choose a schedule");
		LocalDate start = LocalDate.parse(startDate);
		LocalDate end = endDate == null ? null : LocalDate.parse(endDate);
		ZoneId.of(zone);
		if (end != null && end.isBefore(start))
		{
			throw new IllegalArgumentException("End date must be on or after start date");
		}
		if (cadence == Cadence.ONCE && end == null)
		{
			throw new IllegalArgumentException("Choose an end date for a custom range");
		}
		if (mode == Mode.LEVEL && (trackType != TrackType.EXPERIENCE || level < 2 || level > 99 || cadence != Cadence.ONCE))
		{
			throw new IllegalArgumentException("Level goals need a level from 2–99 and a deadline");
		}
		long maximum = trackType == TrackType.EXPERIENCE ? 200_000_000L : Integer.MAX_VALUE;
		if (mode == Mode.GAIN && (amount <= 0 || amount > maximum))
		{
			throw new IllegalArgumentException("Enter a whole target from 1–" + maximum);
		}
		if (!uniqueTargets.isEmpty())
		{
			if (trackType != TrackType.COUNT || cadence == Cadence.ONCE || end != null)
			{
				throw new IllegalArgumentException("Repeat until unique needs a repeating boss goal with no end date");
			}
			uniqueTargets.forEach((id, total) ->
			{
				if (id == null || id <= 0 || total == null || total < 1 || total > Integer.MAX_VALUE)
				{
					throw new IllegalArgumentException("Invalid saved unique target");
				}
			});
		}
		if (totalCountTarget != null)
		{
			if (trackType != TrackType.COUNT || cadence == Cadence.ONCE || end != null || !uniqueTargets.isEmpty() ||
					totalCountTarget < 1 || totalCountTarget > Integer.MAX_VALUE)
			{
				throw new IllegalArgumentException("Until KC needs a repeating boss goal and a lifetime total from 1–" + Integer.MAX_VALUE);
			}
		}
	}

	public GoalSpec withGroup(String group)
	{
		return new GoalSpec(name, group, trackType, metricId, mode, amount, level, cadence,
				LocalDate.parse(startDate), endDate == null ? null : LocalDate.parse(endDate), ZoneId.of(zone), uniqueTargets,
				totalCountTarget, false);
	}

	public GoalSpec anchoredAt(LocalDate start)
	{
		return new GoalSpec(name, groupId, trackType, metricId, mode, amount, level, cadence,
				start, endDate == null ? null : LocalDate.parse(endDate), ZoneId.of(zone), uniqueTargets, totalCountTarget);
	}

	public long targetXp()
	{
		if (mode == Mode.GAIN)
		{
			return amount;
		}
		return xpForLevel(level);
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof GoalSpec))
		{
			return false;
		}
		GoalSpec spec = (GoalSpec) other;
		return amount == spec.amount && level == spec.level && trackType == spec.trackType && mode == spec.mode && cadence == spec.cadence
				&&
				Objects.equals(name, spec.name) && Objects.equals(groupId, spec.groupId) && Objects.equals(metricId, spec.metricId) &&
				Objects.equals(startDate, spec.startDate) && Objects.equals(endDate, spec.endDate) && Objects.equals(zone, spec.zone)
				&& uniqueTargets.equals(spec.uniqueTargets) &&
				Objects.equals(totalCountTarget, spec.totalCountTarget);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(name, groupId, trackType, metricId, mode, amount, level, cadence, startDate, endDate, zone, uniqueTargets,
				totalCountTarget);
	}

	public static long xpForLevel(int level)
	{
		if (level < 1 || level > 99)
		{
			throw new IllegalArgumentException("Real level must be 1–99");
		}
		long points = 0;
		for (int current = 1; current < level; current++)
		{
			points += (long) Math.floor(current + 300 * Math.pow(2, current / 7.0));
		}
		return points / 4;
	}
}
