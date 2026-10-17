package com.mygoals.persistence;

import com.mygoals.domain.ProfileState;

/**
 * Detached values for the I/O worker. GoalSpec is immutable; every mutable DTO
 * is copied.
 */
public final class StateSnapshot
{
	private StateSnapshot()
	{
	}

	public static ProfileState copy(ProfileState source)
	{
		ProfileState result = new ProfileState(source.profileKey);
		result.schemaVersion = source.schemaVersion;
		result.clockZone = source.clockZone;
		result.scope = source.scope;
		result.saveSequence = source.saveSequence;
		result.savedAt = source.savedAt;
		result.lastHiScoresCheckedAt = source.lastHiScoresCheckedAt;
		result.collectionPageChecks.putAll(source.collectionPageChecks);
		source.groups.forEach(g ->
		{
			var c = new ProfileState.Group(g.id, g.name);
			c.collapsed = g.collapsed;
			result.groups.add(c);
		});
		source.goals.forEach((id, g) -> result.goals.put(id, goal(g)));
		source.finished.forEach((id, f) ->
		{
			var c = new ProfileState.Finished();
			c.id = f.id;
			c.name = f.name;
			c.metricId = f.metricId;
			c.trackType = f.trackType;
			c.lifecycle = f.lifecycle;
			c.date = f.date;
			c.file = f.file;
			c.zone = f.zone;
			result.finished.put(id, c);
		});
		result.deletedGoals.addAll(source.deletedGoals);
		result.xp.putAll(source.xp);
		result.counts.putAll(source.counts);
		result.acquisitions.putAll(source.acquisitions);
		result.historyFiles.addAll(source.historyFiles);
		source.activity.forEach((id, a) ->
		{
			var c = new ProfileState.Activity();
			c.date = a.date;
			c.metricId = a.metricId;
			c.source = a.source;
			c.trackType = a.trackType;
			c.amount = a.amount;
			c.goalCredits.putAll(a.goalCredits);
			result.activity.put(id, c);
		});
		source.itemActivity.forEach((id, a) ->
		{
			var c = new ProfileState.ItemActivity();
			c.date = a.date;
			c.source = a.source;
			c.itemId = a.itemId;
			c.amount = a.amount;
			result.itemActivity.put(id, c);
		});
		source.collection.forEach((id, a) ->
		{
			var c = new ProfileState.CollectionQuantity();
			c.quantity = a.quantity;
			c.observedPending = a.observedPending;
			c.verifiedAt = a.verifiedAt;
			c.needsSync = a.needsSync;
			c.source = a.source;
			result.collection.put(id, c);
		});
		source.celebrations.forEach((id, a) ->
		{
			var c = new ProfileState.Celebration();
			c.id = a.id;
			c.goalId = a.goalId;
			c.date = a.date;
			c.name = a.name;
			c.detail = a.detail;
			c.status = a.status;
			c.overall = a.overall;
			c.recovered = a.recovered;
			c.createdAt = a.createdAt;
			result.celebrations.put(id, c);
		});
		return result;
	}

	public static ProfileState.Goal goal(ProfileState.Goal g)
	{
		var c = new ProfileState.Goal();
		c.id = g.id;
		c.revision = g.revision;
		c.spec = g.spec;
		c.pending = g.pending;
		c.pendingEffectiveDate = g.pendingEffectiveDate;
		c.createdAt = g.createdAt;
		c.archivedPeriods = g.archivedPeriods;
		c.trackingStartedAt = g.trackingStartedAt;
		c.baselineXp = g.baselineXp;
		c.countCheckpoint = g.countCheckpoint;
		c.countCreditedThrough = g.countCreditedThrough;
		c.countLiveBoundary = g.countLiveBoundary;
		c.lifecycle = g.lifecycle;
		c.achievedDate = g.achievedDate;
		c.current = g.current == null ? null : period(g.current);
		g.history.forEach(p -> c.history.add(period(p)));
		g.plans.forEach((id, p) ->
		{
			var plan = new ProfileState.DayPlan();
			plan.date = p.date;
			plan.periodId = p.periodId;
			plan.assigned = p.assigned;
			plan.credited = p.credited;
			plan.postponed = p.postponed;
			c.plans.put(id, plan);
		});
		return c;
	}

	public static ProfileState.Period period(ProfileState.Period p)
	{
		var c = new ProfileState.Period();
		c.id = p.id;
		c.start = p.start;
		c.endExclusive = p.endExclusive;
		c.zone = p.zone;
		c.revision = p.revision;
		c.target = p.target;
		c.live = p.live;
		c.recovered = p.recovered;
		c.closed = p.closed;
		return c;
	}
}
