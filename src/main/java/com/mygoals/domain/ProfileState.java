package com.mygoals.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable DTOs. Dates and zones use stable text so serialization needs no JDK
 * reflection.
 */
public final class ProfileState
{
	public int schemaVersion = 10;
	/**
	 * Legacy schema-6 field, retained for save compatibility; the UI uses the
	 * computer's local zone.
	 */
	public String clockZone = java.time.ZoneId.systemDefault().getId();
	public String profileKey, scope = "standard";
	public long saveSequence, savedAt;
	/** Successful source checks, independent of live loot/count observations. */
	public long lastHiScoresCheckedAt;
	public Map<Integer, Long> collectionPageChecks = new LinkedHashMap<>();
	public Map<String, Goal> goals = new LinkedHashMap<>();
	/**
	 * Small index only; finished goal bodies live in immutable history segments.
	 */
	public Map<String, Finished> finished = new LinkedHashMap<>();
	/**
	 * Anonymous deletion markers prevent stale writes or crash recovery restoring
	 * purged goals.
	 */
	public java.util.Set<String> deletedGoals = new java.util.LinkedHashSet<>();
	public List<Group> groups = new ArrayList<>();
	public Map<String, Long> xp = new LinkedHashMap<>();
	public Map<String, Long> counts = new LinkedHashMap<>();
	public Map<String, Activity> activity = new LinkedHashMap<>();
	public Map<Integer, CollectionQuantity> collection = new LinkedHashMap<>();
	public Map<String, ItemActivity> itemActivity = new LinkedHashMap<>();
	public Map<String, Long> acquisitions = new LinkedHashMap<>();
	public Map<String, Celebration> celebrations = new LinkedHashMap<>();
	/**
	 * Immutable history segments, committed before the state file that references
	 * them.
	 */
	public List<String> historyFiles = new ArrayList<>();

	public ProfileState(String profileKey)
	{
		this.profileKey = profileKey;
	}

	public static final class Group
	{
		public String id, name;
		public boolean collapsed;

		public Group(String id, String name)
		{
			this.id = id;
			this.name = name;
		}
	}

	public static final class Goal
	{
		public String id;
		public int revision = 1;
		public GoalSpec spec, pending;
		public String pendingEffectiveDate;
		public long createdAt;
		public long archivedPeriods;
		public Long trackingStartedAt, baselineXp;
		public Long countCheckpoint, countCreditedThrough;
		public boolean countLiveBoundary;
		public String lifecycle = "SCHEDULED", achievedDate;
		public Period current;
		public List<Period> history = new ArrayList<>();
		public Map<String, DayPlan> plans = new LinkedHashMap<>();
	}

	public static final class Finished
	{
		public String id, name, metricId, lifecycle, date, file, zone;
		public GoalSpec.TrackType trackType;

		public Finished()
		{
		}

		public Finished(Goal goal, String file)
		{
			id = goal.id;
			name = goal.spec.name;
			metricId = goal.spec.metricId;
			trackType = goal.spec.trackType;
			lifecycle = goal.lifecycle;
			date = goal.achievedDate == null ? goal.spec.endDate : goal.achievedDate;
			zone = goal.spec.zone;
			this.file = file;
		}
	}

	public static final class Period
	{
		public String id, start, endExclusive, zone;
		public int revision;
		public long target, live, recovered;
		public boolean closed;

		public long progress()
		{
			return Math.addExact(live, recovered);
		}
	}

	public static final class DayPlan
	{
		public String date, periodId;
		public long assigned, credited;
		public boolean postponed;
	}

	public static final class Activity
	{
		public String date, metricId, source;
		public GoalSpec.TrackType trackType;
		public long amount;
		public Map<String, Long> goalCredits = new LinkedHashMap<>();
	}

	public static final class CollectionQuantity
	{
		public Long quantity;
		public long observedPending, verifiedAt;
		public boolean needsSync;
		public String source = "Unknown";
	}

	public static final class ItemActivity
	{
		public String date, source;
		public int itemId;
		public long amount;
	}

	/**
	 * Durable occurrence and presentation state, saved atomically with the credited
	 * work.
	 */
	public static final class Celebration
	{
		public String id, goalId, date, name, detail, status = "PENDING";
		public boolean overall, recovered;
		public long createdAt;
	}
}
