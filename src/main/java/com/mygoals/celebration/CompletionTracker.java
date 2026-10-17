package com.mygoals.celebration;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.tracking.MetricCatalogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detect transitions at observation boundaries, never by scanning completed
 * goals at login.
 */
public final class CompletionTracker
{
	private CompletionTracker()
	{
	}

	/**
	 * Settle obsolete presentation only. The occurrence and all earned history are
	 * retained.
	 */
	public static boolean expireDailyCards(ProfileState state, Instant now)
	{
		boolean changed = false;
		Map<String, LocalDate> dates = new LinkedHashMap<>();
		for (ProfileState.Celebration event : state.celebrations.values())
		{
			if (event.overall || !event.status.equals("PENDING"))
			{
				continue;
			}
			ProfileState.Goal goal = state.goals.get(event.goalId);
			LocalDate today = goal == null ? null
					: dates.computeIfAbsent(goal.spec.zone, zone -> now.atZone(ZoneId.of(zone)).toLocalDate());
			if (today == null || LocalDate.parse(event.date).isBefore(today))
			{
				event.status = "SUPPRESSED";
				changed = true;
			}
		}
		return changed;
	}

	public static final class Before
	{
		final boolean daily, overall, eligible;
		final long progress;

		Before(ProfileState state, ProfileState.Goal goal, Instant now)
		{
			ProfileState.DayPlan plan = plan(goal, now);
			daily = plan != null && plan.credited >= plan.assigned;
			overall = goal.lifecycle.equals("ACHIEVED");
			progress = goal.current == null ? 0 : goal.current.progress();
			eligible = GoalEngine.supported(goal) && goal.lifecycle.equals("ACTIVE") &&
					(goal.spec.totalCountTarget == null || goal.countCheckpoint != null) &&
					goal.spec.uniqueTargets.keySet().stream()
							.allMatch(id -> state.collection.containsKey(id) && state.collection.get(id).quantity != null);
		}
	}

	public static Map<String, Before> capture(ProfileState state, Instant now)
	{
		Map<String, Before> result = new LinkedHashMap<>();
		state.goals.forEach((id, goal) -> result.put(id, new Before(state, goal, now)));
		return result;
	}

	public static boolean record(ProfileState state, Map<String, Before> before, Instant now, boolean announce, boolean recovered)
	{
		return record(state, before, now, announce, recovered, java.util.Set.of());
	}

	public static boolean record(ProfileState state, Map<String, Before> before, Instant now, boolean announce, boolean recovered,
			java.util.Set<String> presented)
	{
		boolean changed = false;
		for (ProfileState.Goal goal : state.goals.values())
		{
			if (!GoalEngine.supported(goal))
			{
				continue;
			}
			Before old = before.get(goal.id);
			String date = now.atZone(ZoneId.of(goal.spec.zone)).toLocalDate().toString();
			boolean overall = goal.lifecycle.equals("ACHIEVED");
			if (overall)
			{
				boolean liveFirstCount = old != null && goal.spec.totalCountTarget != null && goal.current != null
						&& goal.current.progress() > old.progress;
				boolean notify = announce && old != null && !old.overall && (old.eligible || liveFirstCount);
				changed |= add(state, goal, "overall:" + goal.id, date, true, notify, recovered, now);
				// A count message and its reward may arrive separately before the next painted
				// frame.
				ProfileState.Celebration daily = state.celebrations.get("daily:" + goal.id + ":" + date);
				if (daily != null
						&& (daily.status.equals("PENDING") || (daily.status.equals("ANNOUNCED") && !presented.contains(daily.id))))
				{
					daily.status = "SUPPRESSED";
					changed = true;
				}
			}
			ProfileState.DayPlan plan = plan(goal, now);
			if (plan != null && plan.credited >= plan.assigned)
			{
				boolean notify = announce && old != null && !old.daily && plan.assigned > 0 && !overall;
				changed |= add(state, goal, "daily:" + goal.id + ":" + date, date, false, notify, recovered, now);
			}
		}
		return changed;
	}

	private static ProfileState.DayPlan plan(ProfileState.Goal goal, Instant now)
	{
		if (goal.current == null)
		{
			return null;
		}
		String date = now.atZone(ZoneId.of(goal.current.zone)).toLocalDate().toString();
		ProfileState.DayPlan direct = goal.plans.get(goal.current.id + "@" + goal.current.zone + "@" + date);
		if (direct != null)
		{
			return direct;
		}
		return goal.plans.values().stream().filter(p -> p.date.equals(date) && p.periodId.equals(goal.current.id)).findFirst().orElse(null);
	}

	private static boolean add(ProfileState state, ProfileState.Goal goal, String id, String date, boolean overall,
			boolean notify, boolean recovered, Instant now)
	{
		if (state.celebrations.containsKey(id))
		{
			return false;
		}
		ProfileState.Celebration event = new ProfileState.Celebration();
		event.id = id;
		event.goalId = goal.id;
		event.date = overall && goal.achievedDate != null ? goal.achievedDate : date;
		event.name = goal.spec.name;
		event.overall = overall;
		event.recovered = recovered;
		event.createdAt = now.toEpochMilli();
		event.status = notify ? "PENDING" : "SUPPRESSED";
		String unit = goal.spec.trackType == GoalSpec.TrackType.EXPERIENCE ? "XP"
				: MetricCatalogue.unit(MetricCatalogue.countMetric(goal.spec.metricId));
		if (!overall)
		{
			event.detail = String.format(Locale.US, "Target: %,d %s", plan(goal, now).assigned, unit);
		}
		else if (!goal.spec.uniqueTargets.isEmpty())
		{
			event.detail = "All selected unique totals reached";
		}
		else if (goal.spec.totalCountTarget != null)
		{
			event.detail = String.format(Locale.US, "%,d lifetime %s reached", goal.spec.totalCountTarget, unit);
		}
		else if (goal.spec.mode == GoalSpec.Mode.LEVEL)
		{
			event.detail = "Level " + goal.spec.level + " reached";
		}
		else
		{
			event.detail = String.format(Locale.US, "%,d %s achieved", goal.spec.amount, unit);
		}
		state.celebrations.put(id, event);
		return true;
	}
}
