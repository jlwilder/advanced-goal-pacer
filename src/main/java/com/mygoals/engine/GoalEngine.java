package com.mygoals.engine;

import com.mygoals.celebration.CompletionTracker;
import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalCompatibility;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.domain.ProfileState.Activity;
import com.mygoals.domain.ProfileState.DayPlan;
import com.mygoals.domain.ProfileState.Goal;
import com.mygoals.domain.ProfileState.Group;
import com.mygoals.domain.ProfileState.Period;
import com.mygoals.tracking.MetricCatalogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Thread-confined goal engine. No client, Swing, disk, or network access.
 */
public final class GoalEngine
{
	public static final String LIVE = "Live", RECOVERED = "Recovered at login";
	private final ProfileState state;
	private final CalendarService calendar = new CalendarService();
	private final ZoneId activityZone;
	private long version;
	private long completionVersion;
	private long lastExpiryMinute = Long.MIN_VALUE;
	private Set<String> unavailableUniques = Set.of();
	private final Set<String> presentedCelebrations = new HashSet<>();
	private final Map<String, Instant> plannedThrough = new LinkedHashMap<>();

	public GoalEngine(ProfileState state, ZoneId activityZone)
	{
		this.state = state;
		this.activityZone = activityZone;
		state.celebrations.values().stream().filter(e -> e.status.equals("ANNOUNCED")).forEach(e -> presentedCelebrations.add(e.id));
		state.goals.values().forEach(goal ->
		{
			if (goal.current != null && goal.lifecycle.equals("ACTIVE"))
			{
				goal.plans.values().stream().filter(plan -> plan.periodId.equals(goal.current.id))
						.map(plan -> LocalDate.parse(plan.date)).max(LocalDate::compareTo)
						.ifPresent(day -> plannedThrough.put(goal.id, day.atStartOfDay(ZoneId.of(goal.current.zone)).toInstant()));
			}
		});
	}

	/**
	 * Owned by this engine's thread. Deep-copy mutable values before crossing
	 * threads.
	 */
	public ProfileState state()
	{
		return state;
	}

	public long version()
	{
		return version;
	}

	public long completionVersion()
	{
		return completionVersion;
	}

	public void hiscoresChecked(Instant checkedAt)
	{
		if (checkedAt.toEpochMilli() > state.lastHiScoresCheckedAt)
		{
			state.lastHiScoresCheckedAt = checkedAt.toEpochMilli();
			version++;
		}
	}

	private void completions(Map<String, CompletionTracker.Before> before, Instant now, boolean announce, boolean recovered)
	{
		if (CompletionTracker.record(state, before, now, announce, recovered, presentedCelebrations))
		{
			version++;
			completionVersion++;
		}
	}

	public void presentedCelebrations(java.util.List<String> ids)
	{
		presentedCelebrations.addAll(ids);
	}

	public void settleCelebrations(java.util.List<String> ids, boolean suppress)
	{
		for (String id : ids)
		{
			ProfileState.Celebration event = state.celebrations.get(id);
			if (event != null && event.status.equals("PENDING"))
			{
				event.status = suppress ? "SUPPRESSED" : "ANNOUNCED";
				version++;
				completionVersion++;
			}
		}
	}

	public String add(GoalSpec spec, Instant now)
	{
		spec.validate();
		requireUniqueAvailability(spec);
		requireGroup(spec.groupId);
		if (spec.trackType == GoalSpec.TrackType.EXPERIENCE && !state.xp.containsKey(spec.metricId))
		{
			throw new IllegalArgumentException("Wait for the skill XP baseline before adding a goal");
		}
		if (spec.trackType == GoalSpec.TrackType.COUNT)
		{
			MetricCatalogue.countMetric(spec.metricId);
		}
		Goal goal = new Goal();
		goal.id = UUID.randomUUID().toString();
		goal.spec = spec;
		if (spec.trackType == GoalSpec.TrackType.COUNT)
		{
			goal.countCreditedThrough = state.counts.get(spec.metricId);
		}
		goal.createdAt = now.toEpochMilli();
		state.goals.put(goal.id, goal);
		advance(goal, now);
		checkCountGoal(goal, now);
		checkUniqueGoals(now);
		completions(Map.of(), now, false, false);
		version++;
		return goal.id;
	}

	public void edit(String id, GoalSpec spec, Instant now)
	{
		spec.validate();
		requireUniqueAvailability(spec);
		requireGroup(spec.groupId);
		Goal goal = goal(id);
		if (goal.spec.trackType != spec.trackType || !goal.spec.metricId.equals(spec.metricId) || goal.spec.mode != spec.mode)
		{
			throw new IllegalArgumentException("Create a new goal to change its skill or mode");
		}
		advance(goal, now);
		if (goal.lifecycle.equals("ACHIEVED") || goal.lifecycle.equals("ENDED"))
		{
			throw new IllegalArgumentException("This goal has finished; create another goal to track new work");
		}
		if (goal.current != null && goal.spec.cadence != GoalSpec.Cadence.ONCE)
		{
			if (spec.cadence == GoalSpec.Cadence.ONCE)
			{
				throw new IllegalArgumentException("Create a new goal for a custom range");
			}
			editRepeating(goal, spec, now);
		}
		else
		{
			if (goal.current != null && spec.cadence != GoalSpec.Cadence.ONCE)
			{
				throw new IllegalArgumentException("Create a new goal to change a custom range into a repeating goal");
			}
			if (goal.trackingStartedAt != null && (!goal.spec.startDate.equals(spec.startDate) || !goal.spec.zone.equals(spec.zone)))
			{
				throw new IllegalArgumentException("An active deadline goal keeps its start date and time zone");
			}
			if (goal.current != null && calendar.current(spec, now) == null)
			{
				throw new IllegalArgumentException("Choose an end date that includes today");
			}
			goal.spec = spec;
			goal.revision++;
			if (goal.current != null)
			{
				goal.current.endExclusive = LocalDate.parse(spec.endDate).plusDays(1).toString();
				goal.current.target = target(goal);
				recalculateToday(goal, now);
				if (goal.current.progress() >= goal.current.target)
				{
					achieve(goal, now);
				}
			}
		}
		checkCountGoal(goal, now);
		checkUniqueGoals(now);
		completions(Map.of(), now, false, false);
		version++;
	}

	private boolean targetsChanged(GoalSpec before, GoalSpec after)
	{
		return before.amount != after.amount || before.level != after.level || !Objects.equals(before.endDate, after.endDate) ||
				!before.uniqueTargets.equals(after.uniqueTargets) || !Objects.equals(before.totalCountTarget, after.totalCountTarget);
	}

	private void editRepeating(Goal goal, GoalSpec spec, Instant now)
	{
		GoalSpec old = goal.spec;
		GoalSpec current = new GoalSpec(spec.name, spec.groupId, old.trackType, old.metricId, old.mode, spec.amount, spec.level,
				old.cadence,
				LocalDate.parse(old.startDate), spec.endDate == null ? null : LocalDate.parse(spec.endDate), ZoneId.of(old.zone),
				spec.uniqueTargets, spec.totalCountTarget);
		CalendarService.Window window = calendar.current(current, now);
		if (window == null)
		{
			throw new IllegalArgumentException("Choose an end date that includes today");
		}
		boolean scheduleChanged = spec.cadence != old.cadence || !spec.zone.equals(old.zone);
		// A shortened final period has no following period to reschedule.
		boolean hasNextPeriod = spec.endDate == null || !LocalDate.parse(spec.endDate).isBefore(window.endExclusive);
		GoalSpec pending = scheduleChanged && hasNextPeriod ? spec.anchoredAt(window.endExclusive) : null;
		boolean recalculate = targetsChanged(old, current);
		goal.spec = current;
		goal.pending = pending;
		goal.pendingEffectiveDate = pending == null ? null : window.endExclusive.toString();
		if (recalculate)
		{
			goal.current.target = target(goal);
			goal.current.endExclusive = window.endExclusive.toString();
			if (current.totalCountTarget != null && goal.countCheckpoint == null)
			{
				goal.countCheckpoint = knownCount(current.metricId);
			}
			recalculateToday(goal, now);
		}
	}

	private void recalculateToday(Goal goal, Instant now)
	{
		DayPlan plan = today(goal, now);
		long remainingBeforeToday = Math.max(0, goal.current.target - goal.current.progress() + plan.credited);
		if (goal.spec.totalCountTarget != null && goal.countCheckpoint != null)
		{
			remainingBeforeToday = Math.min(remainingBeforeToday,
					Math.max(0, goal.spec.totalCountTarget - goal.countCheckpoint + plan.credited));
		}
		long days = ChronoUnit.DAYS.between(LocalDate.parse(plan.date), LocalDate.parse(goal.current.endExclusive));
		plan.assigned = CalendarService.share(remainingBeforeToday, days);
	}

	public void remove(String id)
	{
		if (!state.goals.containsKey(id) && !state.finished.containsKey(id))
		{
			throw new IllegalArgumentException("Goal no longer exists");
		}
		state.deletedGoals.add(id);
		com.mygoals.domain.GoalPurge.strip(state, Set.of(id));
		plannedThrough.remove(id);
		completionVersion++;
		version++;
	}

	/** Map order is the canonical card order, shared by both sidebar views. */
	public void reorderGoal(String id, String targetId, boolean after)
	{
		Goal moving = goal(id);
		goal(targetId);
		if (id.equals(targetId))
		{
			return;
		}
		Map<String, Goal> ordered = new LinkedHashMap<>();
		state.goals.forEach((key, value) ->
		{
			if (key.equals(id))
			{
				return;
			}
			if (key.equals(targetId) && !after)
			{
				ordered.put(id, moving);
			}
			ordered.put(key, value);
			if (key.equals(targetId) && after)
			{
				ordered.put(id, moving);
			}
		});
		if (new java.util.ArrayList<>(state.goals.keySet()).equals(new java.util.ArrayList<>(ordered.keySet())))
		{
			return;
		}
		state.goals = ordered;
		version++;
	}

	/**
	 * Postponing changes today's placement only; observations continue to earn
	 * progress.
	 */
	public void postponeGoal(String id, boolean postponed, Instant now)
	{
		Goal goal = goal(id);
		advance(goal, now);
		if (!goal.lifecycle.equals("ACTIVE") || goal.current == null)
		{
			throw new IllegalArgumentException("This goal has no active assignment today");
		}
		DayPlan plan = today(goal, now);
		if (postponed && plan.credited >= plan.assigned)
		{
			throw new IllegalArgumentException("Today's assignment is already complete");
		}
		if (plan.postponed != postponed)
		{
			plan.postponed = postponed;
			version++;
		}
	}

	public String addGroup(String name)
	{
		Group group = new Group(UUID.randomUUID().toString(), groupName(name));
		state.groups.add(group);
		version++;
		return group.id;
	}

	public void renameGroup(String id, String name)
	{
		group(id).name = groupName(name);
		version++;
	}

	public void collapseGroup(String id)
	{
		Group group = group(id);
		group.collapsed = !group.collapsed;
		version++;
	}

	public void reorderGroup(String id, int direction)
	{
		int from = state.groups.indexOf(group(id));
		int to = Math.max(0, Math.min(state.groups.size() - 1, from + Integer.signum(direction)));
		java.util.Collections.swap(state.groups, from, to);
		version++;
	}

	public void removeGroup(String id)
	{
		state.groups.remove(group(id));
		for (Goal goal : state.goals.values())
		{
			if (Objects.equals(goal.spec.groupId, id))
			{
				goal.spec = goal.spec.withGroup(null);
			}
			if (goal.pending != null && Objects.equals(goal.pending.groupId, id))
			{
				goal.pending = goal.pending.withGroup(null);
			}
		}
		version++;
	}

	public void observe(Map<String, Long> totals, String source, Instant now)
	{
		if (!LIVE.equals(source) && !RECOVERED.equals(source))
		{
			throw new IllegalArgumentException("Unknown XP source");
		}
		Map<String, Long> deltas = new LinkedHashMap<>();
		Set<String> alreadyStarted = new HashSet<>();
		for (Goal goal : state.goals.values())
		{
			if (goal.trackingStartedAt != null)
			{
				alreadyStarted.add(goal.id);
			}
		}
		for (Map.Entry<String, Long> entry : totals.entrySet())
		{
			long value = entry.getValue();
			if (value < 0 || value > 200_000_000L)
			{
				throw new IllegalArgumentException("Invalid real skill XP");
			}
			Long old = state.xp.get(entry.getKey());
			if (old != null && value < old)
			{
				continue;
			} // Keep the last checkpoint; never manufacture a rebound gain.
			if (old == null || value != old)
			{
				state.xp.put(entry.getKey(), value);
				version++;
			}
			if (old != null && value > old)
			{
				deltas.put(entry.getKey(), value - old);
			}
		}
		tick(now); // Activate future goals using the complete current snapshot, before crediting
					// any deltas.
		Map<String, CompletionTracker.Before> completionBefore = CompletionTracker.capture(state, now);
		for (Map.Entry<String, Long> delta : deltas.entrySet())
		{
			Activity activity = null;
			for (Goal goal : state.goals.values())
			{
				if (!supported(goal) || goal.spec.trackType != GoalSpec.TrackType.EXPERIENCE || !alreadyStarted.contains(goal.id)
						|| !goal.spec.metricId.equals(delta.getKey()) || !goal.lifecycle.equals("ACTIVE"))
				{
					continue;
				}
				Period period = goal.current;
				if (period == null)
				{
					continue;
				}
				if (LIVE.equals(source))
				{
					period.live = Math.addExact(period.live, delta.getValue());
				}
				else
				{
					period.recovered = Math.addExact(period.recovered, delta.getValue());
				}
				DayPlan plan = today(goal, now);
				plan.credited = Math.addExact(plan.credited, delta.getValue());
				if (activity == null)
				{
					activity = activity(GoalSpec.TrackType.EXPERIENCE, delta.getKey(), source, now);
				}
				activity.goalCredits.merge(goal.id + ":" + period.id, delta.getValue(), Math::addExact);
				if (goal.spec.cadence == GoalSpec.Cadence.ONCE && period.progress() >= period.target)
				{
					achieve(goal, now);
				}
				version++;
			}
			// One underlying activity row, independent of the number of goals receiving it.
			if (activity != null)
			{
				activity.amount = Math.addExact(activity.amount, delta.getValue());
			}
		}
		completions(completionBefore, now, true, !LIVE.equals(source));
	}

	/**
	 * Absolute totals only. Remote callers capture eligible goal IDs at request
	 * time.
	 */
	public void observeCount(String metric, long total, boolean completion, String source, Set<String> eligibleGoals, Instant now)
	{
		if (total < 0 || total > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("Invalid count total");
		}
		if (!LIVE.equals(source) && !RECOVERED.equals(source) && !"HiScores refreshed".equals(source))
		{
			throw new IllegalArgumentException("Unknown count source");
		}
		var definition = MetricCatalogue.findCountMetric(metric);
		if (definition == null)
		{
			return;
		} // Preserve obsolete snapshots without interpreting them as current game data.
		if (completion && total == 0)
		{
			return;
		}
		if (definition == net.runelite.client.hiscore.HiscoreSkill.LEAGUE_POINTS)
		{
			return;
		}
		tick(now);
		Map<String, CompletionTracker.Before> completionBefore = CompletionTracker.capture(state, now);
		Activity activity = null;
		long underlying = 0;
		for (Goal goal : state.goals.values())
		{
			if (!supported(goal) || goal.spec.trackType != GoalSpec.TrackType.COUNT || !goal.spec.metricId.equals(metric)
					|| !goal.lifecycle.equals("ACTIVE") ||
					(eligibleGoals != null && !eligibleGoals.contains(goal.id)))
			{
				continue;
			}
			Long before = goal.countCheckpoint;
			long delta;
			if (completion && !MetricCatalogue.bestScore(definition) && !goal.countLiveBoundary)
			{
				// A delayed first HiScores baseline must neither add old lifetime work nor
				// swallow
				// the first observed completion. A previously credited absolute total covers
				// it.
				delta = total == 0 || (goal.countCreditedThrough != null && total <= goal.countCreditedThrough) ? 0 : 1;
				goal.countLiveBoundary = true;
			}
			else if (!LIVE.equals(source) && MetricCatalogue.requiresGameBaseline(definition) && !goal.countLiveBoundary)
			{
				// HiScores has no capture timestamp. A lagging initial snapshot advancing again
				// cannot prove that its backlog happened after this goal was created.
				delta = 0;
			}
			else
			{
				delta = before == null ? 0
						: Math.max(0, total - Math.max(before, goal.countCreditedThrough == null ? 0 : goal.countCreditedThrough));
			}
			if (LIVE.equals(source) && !goal.countLiveBoundary)
			{
				goal.countLiveBoundary = true;
				version++;
			}
			if (before == null || total > before)
			{
				goal.countCheckpoint = total;
				version++;
			}
			if (completion)
			{
				version++;
			}
			if (delta == 0)
			{
				checkCountGoal(goal, now);
				continue;
			}
			goal.countCreditedThrough = goal.countCreditedThrough == null ? total : Math.max(goal.countCreditedThrough, total);
			Period period = goal.current;
			if (LIVE.equals(source))
			{
				period.live = Math.addExact(period.live, delta);
			}
			else
			{
				period.recovered = Math.addExact(period.recovered, delta);
			}
			DayPlan plan = today(goal, now);
			plan.credited = Math.addExact(plan.credited, delta);
			if (activity == null)
			{
				activity = activity(GoalSpec.TrackType.COUNT, metric, source, now);
			}
			activity.goalCredits.merge(goal.id + ":" + period.id, delta, Math::addExact);
			underlying = Math.max(underlying, delta);
			if (goal.spec.cadence == GoalSpec.Cadence.ONCE && period.progress() >= period.target)
			{
				achieve(goal, now);
			}
			checkCountGoal(goal, now);
			version++;
		}
		if (activity != null)
		{
			Long credited = state.counts.get(metric);
			long newActivity = credited == null ? underlying : Math.min(underlying, Math.max(0, total - credited));
			activity.amount = Math.addExact(activity.amount, newActivity);
			state.counts.merge(metric, total, Math::max);
		}
		completions(completionBefore, now, true, !LIVE.equals(source));
	}

	public Set<String> activeCountGoals()
	{
		Set<String> ids = new HashSet<>();
		for (Goal goal : state.goals.values())
		{
			if (supported(goal) && goal.spec.trackType == GoalSpec.TrackType.COUNT && goal.lifecycle.equals("ACTIVE") &&
					!goal.spec.metricId.equals("LEAGUE_POINTS"))
			{
				ids.add(goal.id);
			}
		}
		return ids;
	}

	public void tick(Instant now)
	{
		long minute = Math.floorDiv(now.getEpochSecond(), 60);
		if (minute != lastExpiryMinute)
		{
			lastExpiryMinute = minute;
			if (CompletionTracker.expireDailyCards(state, now))
			{
				version++;
				completionVersion++;
			}
		}
		for (Goal goal : state.goals.values())
		{
			advance(goal, now);
			checkCountGoal(goal, now);
		}
		checkUniqueGoals(now);
		completions(Map.of(), now, false, false);
	}

	public void setUnavailableUniques(Set<String> metrics)
	{
		unavailableUniques = Set.copyOf(metrics);
	}

	private void requireUniqueAvailability(GoalSpec spec)
	{
		if (!spec.uniqueTargets.isEmpty() && unavailableUniques.contains(spec.metricId))
		{
			throw new IllegalArgumentException(
					"Collection definitions changed. Update the plugin before creating or revising these unique targets.");
		}
	}

	public void collectionSnapshot(int pageId, Map<Integer, Long> totals, boolean overlapsAcquisition, Instant now)
	{
		var page = UniqueCatalogue.INSTANCE.pages.get(pageId);
		if (page == null || !totals.keySet().equals(new HashSet<>(page.itemIds))
				|| totals.values().stream().anyMatch(v -> v == null || v < 0 || v > Integer.MAX_VALUE))
		{
			throw new IllegalArgumentException("Incomplete collection page");
		}
		tick(now);
		Map<String, CompletionTracker.Before> completionBefore = CompletionTracker.capture(state, now);
		if (!overlapsAcquisition)
		{
			Long previousCheck = state.collectionPageChecks.put(pageId, now.toEpochMilli());
			if (!Objects.equals(previousCheck, now.toEpochMilli()))
			{
				version++;
			}
		}
		totals.forEach((id, total) ->
		{
			if (!UniqueCatalogue.INSTANCE.items.containsKey(id))
			{
				return;
			}
			var quantity = state.collection.computeIfAbsent(id, ignored -> new ProfileState.CollectionQuantity());
			if (overlapsAcquisition)
			{
				quantity.needsSync = true;
				version++;
				return;
			}
			long advance = quantity.quantity == null ? 0 : Math.max(0, total - quantity.quantity);
			long acknowledged = Math.min(advance, quantity.observedPending);
			quantity.observedPending = 0; // A fresh game-server page covers every earlier receipt, including native caps.
			long recovered = advance - acknowledged;
			boolean changed = !Objects.equals(quantity.quantity, total) || quantity.needsSync || quantity.verifiedAt != now.toEpochMilli()
					|| !quantity.source.equals("Collection log refreshed");
			quantity.quantity = total;
			quantity.needsSync = false;
			quantity.verifiedAt = now.toEpochMilli();
			quantity.source = "Collection log refreshed";
			if (recovered > 0)
			{
				itemActivity(id, recovered, "Collection log refreshed", now);
			}
			if (changed || recovered > 0)
			{
				version++;
			}
		});
		checkUniqueGoals(now);
		completions(completionBefore, now, true, true);
	}

	/**
	 * One source supplies each acquisition. The marker and quantities save
	 * atomically.
	 */
	public void acquire(String eventId, Map<Integer, Long> items, String source, Instant now)
	{
		if (eventId == null || eventId.isBlank() || eventId.length() > 200 || state.acquisitions.containsKey(eventId))
		{
			return;
		}
		if (!Set.of("Drop", "Reward", "Pet").contains(source)
				|| items.values().stream().anyMatch(v -> v == null || v < 1 || v > Integer.MAX_VALUE))
		{
			throw new IllegalArgumentException("Invalid qualifying acquisition");
		}
		tick(now);
		Map<String, CompletionTracker.Before> completionBefore = CompletionTracker.capture(state, now);
		state.acquisitions.put(eventId, now.toEpochMilli());
		while (state.acquisitions.size() > 1024)
		{
			state.acquisitions.remove(state.acquisitions.keySet().iterator().next());
		}
		items.forEach((id, amount) ->
		{
			var item = UniqueCatalogue.INSTANCE.items.get(id);
			if (item == null)
			{
				return;
			}
			var quantity = state.collection.computeIfAbsent(id, ignored -> new ProfileState.CollectionQuantity());
			if (quantity.quantity == null || quantity.needsSync || quantity.quantity + amount > item.targetLimit)
			{
				quantity.observedPending = Math.addExact(quantity.observedPending, amount);
				quantity.needsSync = true;
			}
			else
			{
				quantity.quantity = Math.addExact(quantity.quantity, amount);
				quantity.verifiedAt = now.toEpochMilli();
				quantity.source = source;
			}
			itemActivity(id, amount, source, now);
		});
		version++;
		checkUniqueGoals(now);
		completions(completionBefore, now, true, false);
	}

	public void requireCollectionSync(Set<Integer> ids)
	{
		for (Integer id : ids)
		{
			if (UniqueCatalogue.INSTANCE.items.containsKey(id))
			{
				state.collection.computeIfAbsent(id, ignored -> new ProfileState.CollectionQuantity()).needsSync = true;
				version++;
			}
		}
	}

	private void itemActivity(int id, long amount, String source, Instant now)
	{
		String date = now.atZone(activityZone).toLocalDate().toString();
		var row = state.itemActivity.computeIfAbsent(date + ":" + id + ":" + source, ignored ->
		{
			var activity = new ProfileState.ItemActivity();
			activity.date = date;
			activity.itemId = id;
			activity.source = source;
			return activity;
		});
		row.amount = Math.addExact(row.amount, amount);
	}

	private void checkUniqueGoals(Instant now)
	{
		for (Goal goal : state.goals.values())
		{
			if (!supported(goal) || !goal.lifecycle.equals("ACTIVE") || goal.spec.uniqueTargets.isEmpty()
					|| unavailableUniques.contains(goal.spec.metricId))
			{
				continue;
			}
			boolean met = goal.spec.uniqueTargets.entrySet().stream().allMatch(target ->
			{
				var quantity = state.collection.get(target.getKey());
				return quantity != null && quantity.quantity != null && !quantity.needsSync && quantity.quantity >= target.getValue();
			});
			if (met)
			{
				achieve(goal, now);
				version++;
			}
		}
	}

	private void checkCountGoal(Goal goal, Instant now)
	{
		if (supported(goal) && goal.lifecycle.equals("ACTIVE") && goal.spec.totalCountTarget != null && goal.countCheckpoint != null &&
				goal.countCheckpoint >= goal.spec.totalCountTarget)
		{
			achieve(goal, now);
			version++;
		}
	}

	/**
	 * Fill offline dates before storage compaction, while state still belongs to
	 * the I/O worker.
	 */
	public boolean resumeMissedDays(Instant now)
	{
		long before = version;
		state.goals.values().forEach(goal -> missedDays(goal, now));
		return before != version;
	}

	private void missedDays(Goal goal, Instant now)
	{
		if (!supported(goal) || !goal.lifecycle.equals("ACTIVE") || goal.current == null)
		{
			return;
		}
		Instant last = plannedThrough.get(goal.id);
		while (last != null && goal.lifecycle.equals("ACTIVE") && goal.current != null)
		{
			ZoneId zone = ZoneId.of(goal.spec.zone);
			Instant next = last.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
			Instant today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
			if (!next.isBefore(today))
			{
				break;
			}
			// No observations are attributed to missed days. Remaining work is spread
			// across fewer days, and each resulting assignment is then frozen normally.
			advanceCurrent(goal, next);
			Instant updated = plannedThrough.get(goal.id);
			if (updated == null || !updated.isAfter(last))
			{
				break;
			}
			last = updated;
		}
	}

	private void advance(Goal goal, Instant now)
	{
		missedDays(goal, now);
		advanceCurrent(goal, now);
	}

	private void advanceCurrent(Goal goal, Instant now)
	{
		if (!supported(goal))
		{
			return;
		}
		if (goal.lifecycle.equals("ACHIEVED") || goal.lifecycle.equals("ENDED"))
		{
			return;
		}
		if (goal.current != null && goal.pending != null && targetsChanged(goal.spec, goal.pending) &&
				now.isBefore(LocalDate.parse(goal.current.endExclusive).atStartOfDay(ZoneId.of(goal.current.zone)).toInstant()))
		{
			// Promote target edits saved by older builds while preserving any deferred
			// calendar change.
			editRepeating(goal, goal.pending, now);
			version++;
		}
		if (goal.current != null
				&& !now.isBefore(LocalDate.parse(goal.current.endExclusive).atStartOfDay(ZoneId.of(goal.current.zone)).toInstant()))
		{
			goal.current.closed = true;
			goal.history.add(goal.current);
			goal.current = null;
			if (goal.pending != null)
			{
				goal.spec = goal.pending;
				goal.pending = null;
				goal.pendingEffectiveDate = null;
				goal.revision++;
			}
			version++;
		}
		CalendarService.Window window = calendar.current(goal.spec, now);
		if (window == null)
		{
			String next = now.atZone(ZoneId.of(goal.spec.zone)).toLocalDate().isBefore(LocalDate.parse(goal.spec.startDate)) ? "SCHEDULED"
					: "ENDED";
			if (!next.equals(goal.lifecycle))
			{
				goal.lifecycle = next;
				plannedThrough.remove(goal.id);
				version++;
			}
			return;
		}
		if (goal.trackingStartedAt == null)
		{
			Long currentXp = goal.spec.trackType == GoalSpec.TrackType.COUNT ? 0L : state.xp.get(goal.spec.metricId);
			if (currentXp == null)
			{
				return;
			}
			goal.baselineXp = currentXp;
			if (goal.spec.trackType == GoalSpec.TrackType.COUNT)
			{
				goal.countCreditedThrough = state.counts.get(goal.spec.metricId);
			}
			if (goal.spec.totalCountTarget != null)
			{
				goal.countCheckpoint = knownCount(goal.spec.metricId);
			}
			goal.trackingStartedAt = now.toEpochMilli();
			version++;
		}
		goal.lifecycle = "ACTIVE";
		if (goal.current == null)
		{
			Period period = new Period();
			period.id = window.start + ":" + goal.revision;
			period.start = window.start.toString();
			period.endExclusive = window.endExclusive.toString();
			period.zone = goal.spec.zone;
			period.revision = goal.revision;
			period.target = target(goal);
			goal.current = period;
			version++;
		}
		today(goal, now);
		if (goal.spec.cadence == GoalSpec.Cadence.ONCE && goal.current.target == 0)
		{
			achieve(goal, now);
		}
	}

	public static boolean supported(Goal goal)
	{
		return GoalCompatibility.supported(goal.spec) && (goal.pending == null || GoalCompatibility.supported(goal.pending));
	}

	public DayPlan today(Goal goal, Instant now)
	{
		LocalDate day = now.atZone(ZoneId.of(goal.current.zone)).toLocalDate();
		String key = goal.current.id + "@" + goal.current.zone + "@" + day;
		DayPlan plan = goal.plans.get(key);
		if (plan == null)
		{
			plan = new DayPlan();
			plan.date = day.toString();
			plan.periodId = goal.current.id;
			long days = ChronoUnit.DAYS.between(day, LocalDate.parse(goal.current.endExclusive));
			long remaining = Math.max(0, goal.current.target - goal.current.progress());
			if (goal.spec.totalCountTarget != null && goal.countCheckpoint != null)
			{
				remaining = Math.min(remaining, Math.max(0, goal.spec.totalCountTarget - goal.countCheckpoint));
			}
			plan.assigned = CalendarService.share(remaining, days);
			goal.plans.put(key, plan);
			version++;
		}
		Instant date = day.atStartOfDay(ZoneId.of(goal.current.zone)).toInstant();
		plannedThrough.merge(goal.id, date, (before, next) -> before.isAfter(next) ? before : next);
		return plan;
	}

	private long target(Goal goal)
	{
		return goal.spec.mode == GoalSpec.Mode.LEVEL ? Math.max(0, goal.spec.targetXp() - goal.baselineXp) : goal.spec.amount;
	}

	private Long knownCount(String metric)
	{
		Long known = state.counts.get(metric);
		for (Goal existing : state.goals.values())
		{
			if (existing.spec.metricId.equals(metric) && existing.countCheckpoint != null)
			{
				known = known == null ? existing.countCheckpoint : Math.max(known, existing.countCheckpoint);
			}
		}
		return known;
	}

	private void achieve(Goal goal, Instant now)
	{
		goal.lifecycle = "ACHIEVED";
		plannedThrough.remove(goal.id);
		goal.achievedDate = now.atZone(ZoneId.of(goal.spec.zone)).toLocalDate().toString();
	}

	private Activity activity(GoalSpec.TrackType trackType, String metric, String source, Instant now)
	{
		String day = now.atZone(activityZone).toLocalDate().toString();
		return state.activity.computeIfAbsent(day + ":" + trackType + ":" + metric + ":" + source, key ->
		{
			Activity activity = new Activity();
			activity.date = day;
			activity.trackType = trackType;
			activity.metricId = metric;
			activity.source = source;
			return activity;
		});
	}

	private Goal goal(String id)
	{
		Goal goal = state.goals.get(id);
		if (goal == null)
		{
			throw new IllegalArgumentException("Goal no longer exists");
		}
		return goal;
	}

	private Group group(String id)
	{
		return state.groups.stream().filter(g -> g.id.equals(id)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Group no longer exists"));
	}

	private void requireGroup(String id)
	{
		if (id != null)
		{
			group(id);
		}
	}

	private String groupName(String name)
	{
		if (name == null || name.isBlank() || name.trim().length() > 80)
		{
			throw new IllegalArgumentException("Use a group name of 1–80 characters");
		}
		return name.trim();
	}
}
