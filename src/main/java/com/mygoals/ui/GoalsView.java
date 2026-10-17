package com.mygoals.ui;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalCompatibility;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.TomorrowForecast;
import com.mygoals.tracking.MetricCatalogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable values only: the EDT never reads mutable engine state. */
public final class GoalsView
{
	public static final int FINISHED_PAGE_SIZE = 50;
	public final String token, status, message;
	public final String persistenceStatus, countStatus;
	public final long lastHiScoresCheckedAt;
	public final Map<Integer, Long> collectionPageChecks;
	public final boolean editable;
	public final List<Group> groups;
	public final List<Goal> goals;
	public final List<Activity> activity;
	public final Map<String, Long> xp, counts;
	public final Map<Integer, CollectionItem> collection;
	public final List<ItemActivity> itemActivity;
	public final Set<String> unavailableUniques;
	public final boolean hasOlderHistory;
	public final int finishedPage, loadedFinished;
	public final boolean hasOlderFinished, hasNewerFinished;

	public GoalsView(String token, String status, String message, boolean editable, ProfileState state, Instant now)
	{
		this(token, status, message, editable, state, now, Set.of());
	}

	public GoalsView(String token, String status, String message, boolean editable, ProfileState state, Instant now,
			Set<String> unavailableUniques)
	{
		this(token, status, message, editable, state, now, unavailableUniques, "", "");
	}

	public GoalsView(String token, String status, String message, boolean editable, ProfileState state, Instant now,
			Set<String> unavailableUniques,
			String persistenceStatus, String countStatus)
	{
		this(token, status, message, editable, state, now, unavailableUniques, persistenceStatus, countStatus, List.of(), 0);
	}

	public GoalsView(String token, String status, String message, boolean editable, ProfileState state, Instant now,
			Set<String> unavailableUniques, String persistenceStatus, String countStatus,
			List<ProfileState.Goal> finished, int finishedPage)
	{
		this.token = token;
		this.status = status;
		this.message = message;
		this.editable = editable;
		this.persistenceStatus = persistenceStatus;
		this.countStatus = countStatus;
		lastHiScoresCheckedAt = state == null ? 0 : state.lastHiScoresCheckedAt;
		collectionPageChecks = state == null ? Map.of() : Map.copyOf(state.collectionPageChecks);
		hasOlderHistory = state != null && !state.historyFiles.isEmpty();
		this.finishedPage = finishedPage;
		loadedFinished = finished.size();
		hasNewerFinished = finishedPage > 0;
		hasOlderFinished = state != null && state.finished.size() > (long) finishedPage * FINISHED_PAGE_SIZE + finished.size();
		List<Group> groups = new ArrayList<>();
		List<Goal> goals = new ArrayList<>();
		List<Activity> activity = new ArrayList<>();
		Map<String, Long> xp = new LinkedHashMap<>();
		Map<String, Long> counts = new LinkedHashMap<>();
		Map<Integer, CollectionItem> collection = new LinkedHashMap<>();
		List<ItemActivity> itemActivity = new ArrayList<>();
		if (state != null)
		{
			state.groups.forEach(g -> groups.add(new Group(g.id, g.name, g.collapsed)));
			state.goals.values().forEach(g -> goals.add(new Goal(g, state, now)));
			finished.stream().filter(g -> state.finished.containsKey(g.id) && !state.deletedGoals.contains(g.id))
					.forEach(g -> goals.add(new Goal(g, state, now)));
			state.activity.values().forEach(a ->
			{
				if (a.amount > 0)
				{
					activity.add(new Activity(a.date, a.trackType, a.metricId, a.source, a.amount, a.goalCredits.size()));
				}
			});
			xp.putAll(state.xp);
			counts.putAll(state.counts);
			state.goals.values().forEach(g ->
			{
				if (g.countCheckpoint != null)
				{
					counts.merge(g.spec.metricId, g.countCheckpoint, Math::max);
				}
			});
			state.collection.forEach((id, value) -> collection.put(id, new CollectionItem(value)));
			state.itemActivity.values().forEach(a -> itemActivity.add(new ItemActivity(a)));
		}
		this.groups = Collections.unmodifiableList(groups);
		this.goals = Collections.unmodifiableList(goals);
		this.activity = Collections.unmodifiableList(activity);
		this.xp = Collections.unmodifiableMap(xp);
		this.counts = Collections.unmodifiableMap(counts);
		this.collection = Collections.unmodifiableMap(collection);
		this.itemActivity = Collections.unmodifiableList(itemActivity);
		this.unavailableUniques = Set.copyOf(unavailableUniques);
	}

	private Object[] values()
	{
		return new Object[] { token, status, message, persistenceStatus, countStatus, lastHiScoresCheckedAt, collectionPageChecks, editable,
				groups, goals, activity, xp, counts, collection, itemActivity, unavailableUniques, hasOlderHistory, finishedPage,
				loadedFinished, hasOlderFinished, hasNewerFinished };
	}

	@Override
	public boolean equals(Object other)
	{
		return other instanceof GoalsView && Arrays.deepEquals(values(), ((GoalsView) other).values());
	}

	@Override
	public int hashCode()
	{
		return Arrays.deepHashCode(values());
	}

	/**
	 * Explicit value fields keep rendering comparisons independent of DTO object
	 * identity.
	 */
	private abstract static class Value
	{
		abstract Object[] values();

		@Override
		public final boolean equals(Object other)
		{
			return other != null && getClass() == other.getClass() && Arrays.deepEquals(values(), ((Value) other).values());
		}

		@Override
		public final int hashCode()
		{
			return Arrays.deepHashCode(values());
		}
	}

	public static final class Group extends Value
	{
		public final String id, name;
		public final boolean collapsed;

		Group(String id, String name, boolean collapsed)
		{
			this.id = id;
			this.name = name;
			this.collapsed = collapsed;
		}

		@Override
		public String toString()
		{
			return name;
		}

		@Override
		Object[] values()
		{
			return new Object[] { id, name, collapsed };
		}
	}

	public static final class Goal extends Value
	{
		public final String id, lifecycle, pendingStart, periodStart, periodEnd, periodEndExclusive, achievedDate, sourceStatus, sourceHint,
				todayDate, compatibilityIssue;
		public final GoalSpec spec, editSpec;
		public final long progress, target, todayAssigned, todayCredited, trackingStartedAt;
		public final Long totalCount;
		public final boolean inToday, todayComplete, periodComplete, periodQuotaMet, archived;
		public final boolean hasOlderHistory;
		public final boolean inTomorrow, tomorrowNewPeriod;
		public final String tomorrowDate;
		public final Long tomorrowAssigned;
		public final long tomorrowRemaining, tomorrowDays;
		public final List<UniqueTarget> uniques;
		public final List<Period> periods;
		public final long uniquesMet;
		public final List<Day> days;
		public final boolean hasOlderDays;

		Goal(ProfileState.Goal goal, ProfileState state, Instant now)
		{
			id = goal.id;
			archived = state.finished.containsKey(id);
			spec = goal.spec;
			editSpec = goal.pending == null ? spec : goal.pending;
			compatibilityIssue = GoalCompatibility.supported(spec) ? GoalCompatibility.problem(editSpec) : GoalCompatibility.problem(spec);
			hasOlderHistory = goal.archivedPeriods > 0;
			hasOlderDays = !state.historyFiles.isEmpty();
			lifecycle = goal.lifecycle;
			achievedDate = goal.achievedDate;
			trackingStartedAt = goal.trackingStartedAt == null ? 0 : goal.trackingStartedAt;
			totalCount = goal.countCheckpoint;
			var metric = spec.trackType == GoalSpec.TrackType.COUNT ? MetricCatalogue.findCountMetric(spec.metricId) : null;
			boolean needsGameCount = metric != null && MetricCatalogue.requiresGameBaseline(metric);
			if (!compatibilityIssue.isEmpty())
			{
				sourceStatus = compatibilityIssue;
				sourceHint = compatibilityIssue;
			}
			else if (spec.trackType == GoalSpec.TrackType.EXPERIENCE)
			{
				sourceStatus = "";
				sourceHint = "";
			}
			else
			{
				if (lifecycle.equals("ACHIEVED") || lifecycle.equals("ENDED"))
				{
					sourceStatus = "";
				}
				else if (lifecycle.equals("SCHEDULED"))
				{
					sourceStatus = "Count tracking starts on " + spec.startDate;
				}
				else if (spec.metricId.equals("LEAGUE_POINTS"))
				{
					sourceStatus = "Seasonal profile required";
				}
				else if (goal.countLiveBoundary)
				{
					sourceStatus = "Live tracking + login recovery ready";
				}
				else if (needsGameCount)
				{
					sourceStatus = MetricCatalogue.baselineAction(metric);
				}
				else
				{
					sourceStatus = goal.countCheckpoint == null ? "First HiScores total needed" : "HiScores / total snapshots";
				}
				String help = MetricCatalogue.trackingHelp(metric);
				sourceHint = lifecycle.equals("ACTIVE") && needsGameCount && !goal.countLiveBoundary
						? "Live tracking is ready. This goal needs an in-game count observed after activation before later login gains can be recovered safely. "
								+
								"Waiting or refreshing HiScores cannot establish that game baseline. Collection-log item totals are separate from completion counts. "
								+ help
						: help;
			}
			ProfileState.Period period = goal.current != null ? goal.current
					: goal.history.isEmpty() ? null : goal.history.get(goal.history.size() - 1);
			pendingStart = goal.pendingEffectiveDate;
			periodStart = period == null ? null : period.start;
			periodEndExclusive = period == null ? null : period.endExclusive;
			periodEnd = period == null ? null : LocalDate.parse(period.endExclusive).minusDays(1).toString();
			progress = period == null ? 0 : period.progress();
			target = period == null ? spec.mode == GoalSpec.Mode.GAIN ? spec.amount : 0 : period.target;
			String today = now.atZone(ZoneId.of(spec.zone)).toLocalDate().toString();
			todayDate = today;
			ProfileState.DayPlan plan = period == null ? null
					: goal.plans.values().stream()
							.filter(p -> p.date.equals(today) && p.periodId.equals(period.id)).findFirst().orElse(null);
			todayAssigned = plan == null ? 0 : plan.assigned;
			todayCredited = plan == null ? 0 : plan.credited;
			inToday = compatibilityIssue.isEmpty() && plan != null
					&& (lifecycle.equals("ACTIVE") || (lifecycle.equals("ACHIEVED") && today.equals(achievedDate)));
			periodQuotaMet = period != null && progress >= target;
			todayComplete = inToday && (lifecycle.equals("ACHIEVED") || periodQuotaMet || todayCredited >= todayAssigned);
			periodComplete = lifecycle.equals("ACHIEVED") || periodQuotaMet;
			inTomorrow = inToday && !todayComplete && plan.postponed;
			TomorrowForecast forecast = inTomorrow ? TomorrowForecast.forGoal(goal, now) : null;
			tomorrowAssigned = forecast == null ? null : forecast.assigned;
			tomorrowDate = forecast == null ? null : forecast.date;
			tomorrowRemaining = forecast == null ? 0 : forecast.remaining;
			tomorrowDays = forecast == null ? 0 : forecast.days;
			tomorrowNewPeriod = forecast != null && forecast.newPeriod;
			List<Period> periods = new ArrayList<>(goal.history.size() + 1);
			goal.history.forEach(p -> periods.add(new Period(p, false, false)));
			if (goal.current != null)
			{
				boolean active = lifecycle.equals("ACTIVE");
				periods.add(new Period(goal.current, active, !active && !goal.current.closed));
			}
			this.periods = Collections.unmodifiableList(periods);
			days = goal.plans.values().stream().map(Day::new).collect(java.util.stream.Collectors.toUnmodifiableList());
			List<UniqueTarget> targets = new ArrayList<>();
			spec.uniqueTargets.forEach((id, amount) -> targets.add(new UniqueTarget(id, amount, state.collection.get(id))));
			uniques = Collections.unmodifiableList(targets);
			uniquesMet = targets.stream().filter(t -> t.met).count();
		}

		@Override
		Object[] values()
		{
			return new Object[] { id, lifecycle, pendingStart, periodStart, periodEnd, periodEndExclusive, achievedDate, sourceStatus,
					sourceHint, todayDate,
					spec, editSpec, progress, target, todayAssigned, todayCredited, trackingStartedAt, totalCount, inToday, todayComplete,
					archived,
					periodComplete, periodQuotaMet,
					inTomorrow, tomorrowAssigned, tomorrowDate, tomorrowRemaining, tomorrowDays, tomorrowNewPeriod, uniques, uniquesMet,
					periods, days, hasOlderHistory, hasOlderDays, compatibilityIssue };
		}
	}

	public static final class Period extends Value
	{
		public final String id, start, endExclusive, zone;
		public final long target, progress;
		public final boolean current, stoppedEarly, met;

		Period(ProfileState.Period period, boolean current, boolean stoppedEarly)
		{
			id = period.id;
			start = period.start;
			endExclusive = period.endExclusive;
			zone = period.zone;
			target = period.target;
			progress = period.progress();
			this.current = current;
			this.stoppedEarly = stoppedEarly;
			met = progress >= target;
		}

		@Override
		Object[] values()
		{
			return new Object[] { id, start, endExclusive, zone, target, progress, current, stoppedEarly, met };
		}
	}

	public static final class HistoryPage
	{
		public final List<Period> periods;
		public final List<Day> days;
		public final String before, error;
		public final boolean more;

		public HistoryPage(List<ProfileState.Period> periods, String before, boolean more, String error)
		{
			this(periods, List.of(), before, more, error);
		}

		public HistoryPage(List<ProfileState.Period> periods, List<ProfileState.DayPlan> days, String before, boolean more, String error)
		{
			this.periods = periods.stream().map(p -> new Period(p, false, false)).collect(java.util.stream.Collectors.toUnmodifiableList());
			this.days = days.stream().map(Day::new).collect(java.util.stream.Collectors.toUnmodifiableList());
			this.before = before;
			this.more = more;
			this.error = error;
		}
	}

	public static final class Day extends Value
	{
		public final String date, periodId;
		public final long assigned, credited;
		public final boolean deferred;

		Day(ProfileState.DayPlan plan)
		{
			date = plan.date;
			periodId = plan.periodId;
			assigned = plan.assigned;
			credited = plan.credited;
			deferred = plan.postponed;
		}

		@Override
		Object[] values()
		{
			return new Object[] { date, periodId, assigned, credited, deferred };
		}
	}

	public static final class Activity extends Value
	{
		public final String date, metricId, source;
		public final GoalSpec.TrackType trackType;
		public final long amount;
		public final int goalPeriods;

		Activity(String date, GoalSpec.TrackType trackType, String metricId, String source, long amount, int goalPeriods)
		{
			this.date = date;
			this.trackType = trackType;
			this.metricId = metricId;
			this.source = source;
			this.amount = amount;
			this.goalPeriods = goalPeriods;
		}

		@Override
		Object[] values()
		{
			return new Object[] { date, metricId, source, trackType, amount, goalPeriods };
		}
	}

	public static final class CollectionItem extends Value
	{
		public final Long quantity;
		public final boolean needsSync;
		public final String source;

		CollectionItem(ProfileState.CollectionQuantity item)
		{
			quantity = item.quantity;
			needsSync = item.needsSync;
			source = item.source;
		}

		@Override
		Object[] values()
		{
			return new Object[] { quantity, needsSync, source };
		}
	}

	public static final class UniqueTarget extends Value
	{
		public final int itemId;
		public final String name;
		public final Long quantity;
		public final long target;
		public final boolean needsSync, met;

		UniqueTarget(int id, long target, ProfileState.CollectionQuantity quantity)
		{
			itemId = id;
			name = UniqueCatalogue.INSTANCE.itemName(id);
			this.target = target;
			this.quantity = quantity == null ? null : quantity.quantity;
			needsSync = quantity == null || quantity.needsSync;
			met = this.quantity != null && !needsSync && this.quantity >= target;
		}

		@Override
		Object[] values()
		{
			return new Object[] { itemId, name, quantity, target, needsSync, met };
		}
	}

	public static final class ItemActivity extends Value
	{
		public final int itemId;
		public final String name, date, source;
		public final long amount;

		ItemActivity(ProfileState.ItemActivity item)
		{
			itemId = item.itemId;
			name = UniqueCatalogue.INSTANCE.itemName(itemId);
			date = item.date;
			source = item.source;
			amount = item.amount;
		}

		@Override
		Object[] values()
		{
			return new Object[] { itemId, name, date, source, amount };
		}
	}
}
