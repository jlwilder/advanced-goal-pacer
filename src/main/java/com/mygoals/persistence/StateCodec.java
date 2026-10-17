package com.mygoals.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class StateCodec
{
	private final Gson gson;

	public StateCodec(Gson clientGson)
	{
		gson = clientGson.newBuilder().serializeNulls().setPrettyPrinting().create();
	}

	public static final class FutureSchema extends IllegalArgumentException
	{
		FutureSchema()
		{
			super("This save uses a newer Advanced Goal Pacer format; update the plugin before editing it");
		}
	}

	public String encode(ProfileState state)
	{
		validate(state, state.profileKey);
		return gson.toJson(state) + "\n";
	}

	public ProfileState decode(String json, String expectedProfile)
	{
		JsonObject document = new JsonParser().parse(json).getAsJsonObject();
		if (!document.has("schemaVersion"))
		{
			throw new IllegalArgumentException("Missing save schema");
		}
		int schema = document.get("schemaVersion").getAsInt();
		if (schema > 10)
		{
			throw new FutureSchema();
		}
		if (schema == 1)
		{
			migrateXpSave(document);
		}
		else if (schema < 2 || schema > 10)
		{
			throw new IllegalArgumentException("Unsupported save schema");
		}
		if (document.get("schemaVersion").getAsInt() == 2)
		{
			migrateCollectionSave(document);
		}
		if (document.get("schemaVersion").getAsInt() == 3)
		{
			migrateCountTargetSave(document);
		}
		if (document.get("schemaVersion").getAsInt() == 4)
		{
			migrateDayPlanSave(document);
		}
		if (document.get("schemaVersion").getAsInt() == 5)
		{
			document.addProperty("clockZone", ZoneId.systemDefault().getId());
			document.addProperty("schemaVersion", 6);
		}
		if (document.get("schemaVersion").getAsInt() == 6)
		{
			document.add("celebrations", new JsonObject());
			document.addProperty("schemaVersion", 7);
		}
		if (document.get("schemaVersion").getAsInt() == 7)
		{
			document.add("historyFiles", new com.google.gson.JsonArray());
			document.getAsJsonObject("goals").entrySet().forEach(e -> e.getValue().getAsJsonObject().addProperty("archivedPeriods", 0));
			document.addProperty("schemaVersion", 8);
		}
		if (document.get("schemaVersion").getAsInt() == 8)
		{
			// Item verification times also advance on loot; they cannot prove a page was
			// opened.
			document.addProperty("lastHiScoresCheckedAt", 0);
			document.add("collectionPageChecks", new JsonObject());
			document.addProperty("schemaVersion", 9);
		}
		if (document.get("schemaVersion").getAsInt() == 9)
		{
			document.add("finished", new JsonObject());
			document.add("deletedGoals", new com.google.gson.JsonArray());
			document.addProperty("schemaVersion", 10);
		}
		// Required fields cannot silently become empty collections through Gson's
		// defaults.
		for (String required : new String[] { "profileKey", "scope", "clockZone", "saveSequence", "savedAt", "lastHiScoresCheckedAt",
				"collectionPageChecks", "goals", "finished", "deletedGoals", "groups", "xp", "counts", "activity", "collection",
				"itemActivity", "acquisitions",
				"celebrations", "historyFiles" })
		{
			if (!document.has(required) || document.get(required).isJsonNull())
			{
				throw new IllegalArgumentException("Incomplete save: " + required);
			}
		}
		document.getAsJsonArray("groups").forEach(g -> require(g.getAsJsonObject(), "id", "name", "collapsed"));
		document.getAsJsonObject("goals").entrySet().forEach(entry ->
		{
			JsonObject goal = entry.getValue().getAsJsonObject();
			require(goal, "id", "revision", "spec", "pending", "pendingEffectiveDate", "createdAt", "archivedPeriods", "trackingStartedAt",
					"baselineXp", "countCheckpoint", "countCreditedThrough", "countLiveBoundary", "lifecycle", "achievedDate", "current",
					"history", "plans");
			requireSpec(goal.getAsJsonObject("spec"));
			if (!goal.get("pending").isJsonNull())
			{
				requireSpec(goal.getAsJsonObject("pending"));
			}
			if (!goal.get("current").isJsonNull())
			{
				requirePeriod(goal.getAsJsonObject("current"));
			}
			goal.getAsJsonArray("history").forEach(p -> requirePeriod(p.getAsJsonObject()));
			goal.getAsJsonObject("plans").entrySet()
					.forEach(p -> require(p.getValue().getAsJsonObject(), "date", "periodId", "assigned", "credited", "postponed"));
		});
		document.getAsJsonObject("activity").entrySet()
				.forEach(a -> require(a.getValue().getAsJsonObject(), "date", "trackType", "metricId", "source", "amount", "goalCredits"));
		document.getAsJsonObject("collection").entrySet()
				.forEach(a -> require(a.getValue().getAsJsonObject(), "quantity", "observedPending", "verifiedAt", "needsSync", "source"));
		document.getAsJsonObject("itemActivity").entrySet()
				.forEach(a -> require(a.getValue().getAsJsonObject(), "date", "itemId", "source", "amount"));
		document.getAsJsonObject("celebrations").entrySet().forEach(a -> require(a.getValue().getAsJsonObject(), "id", "goalId", "date",
				"name", "detail", "status", "overall", "recovered", "createdAt"));
		ProfileState state = gson.fromJson(document, ProfileState.class);
		validate(state, expectedProfile);
		return state;
	}

	private void validate(ProfileState state, String expectedProfile)
	{
		if (state == null || state.schemaVersion != 10 || !expectedProfile.equals(state.profileKey) ||
				!expectedProfile.matches("[a-f0-9]{64}") || !"standard".equals(state.scope) || state.saveSequence < 0 || state.savedAt < 0
				|| state.lastHiScoresCheckedAt < 0)
		{
			throw new IllegalArgumentException("Save profile, scope or version mismatch");
		}
		ZoneId.of(state.clockZone);
		if (state.goals == null || state.finished == null || state.deletedGoals == null || state.groups == null || state.xp == null
				|| state.counts == null || state.activity == null ||
				state.collection == null || state.itemActivity == null || state.acquisitions == null || state.celebrations == null
				|| state.historyFiles == null || state.collectionPageChecks == null)
		{
			throw new IllegalArgumentException("Incomplete save collections");
		}
		state.collectionPageChecks.forEach((id, checkedAt) ->
		{
			if (id == null || id <= 0 || checkedAt == null || checkedAt <= 0)
			{
				throw new IllegalArgumentException("Invalid collection-log check");
			}
		});
		Set<String> history = new HashSet<>();
		for (String name : state.historyFiles)
		{
			if (!HistoryArchive.validName(name) || !history.add(name))
			{
				throw new IllegalArgumentException("Invalid history reference");
			}
		}
		Set<String> groups = new HashSet<>();
		for (ProfileState.Group group : state.groups)
		{
			uuid(group.id);
			if (!groups.add(group.id) || group.name == null || group.name.isBlank() || group.name.length() > 80)
			{
				throw new IllegalArgumentException("Invalid saved group");
			}
		}
		state.xp.forEach((skill, amount) ->
		{
			skill(skill);
			if (amount == null || amount < 0 || amount > 200_000_000L)
			{
				throw new IllegalArgumentException("Invalid saved XP baseline");
			}
		});
		state.counts.forEach((metric, amount) ->
		{
			metric(GoalSpec.TrackType.COUNT, metric);
			count(amount);
		});
		state.deletedGoals.forEach(this::uuid);
		state.finished.forEach((id, ref) ->
		{
			uuid(id);
			if (ref == null || !id.equals(ref.id) || state.goals.containsKey(id) || state.deletedGoals.contains(id) ||
					ref.name == null || ref.name.isBlank() || ref.name.length() > 100 ||
					!Set.of("ACHIEVED", "ENDED").contains(ref.lifecycle) || !state.historyFiles.contains(ref.file)
					|| !HistoryArchive.validName(ref.file))
			{
				throw new IllegalArgumentException("Invalid finished goal index");
			}
			metric(ref.trackType, ref.metricId);
			LocalDate.parse(ref.date);
			ZoneId.of(ref.zone);
		});
		state.goals.forEach((id, goal) ->
		{
			uuid(id);
			if (goal == null || !id.equals(goal.id) || state.deletedGoals.contains(id) || goal.revision < 1 || goal.createdAt < 0
					|| goal.archivedPeriods < 0
					|| goal.spec == null || goal.history == null || goal.plans == null)
			{
				throw new IllegalArgumentException("Invalid saved goal");
			}
			goal.spec.validateStored();
			metric(goal.spec.trackType, goal.spec.metricId);
			if (goal.spec.groupId != null && !groups.contains(goal.spec.groupId))
			{
				throw new IllegalArgumentException("Missing saved group");
			}
			if (goal.pending != null)
			{
				LocalDate.parse(goal.pendingEffectiveDate);
				goal.pending.validateStored();
				if (goal.spec.trackType != goal.pending.trackType || !goal.spec.metricId.equals(goal.pending.metricId)
						|| goal.spec.mode != goal.pending.mode ||
						(goal.pending.groupId != null && !groups.contains(goal.pending.groupId)))
				{
					throw new IllegalArgumentException("Invalid pending goal revision");
				}
			}
			else if (goal.pendingEffectiveDate != null)
			{
				throw new IllegalArgumentException("Incomplete pending revision");
			}
			if (goal.countCheckpoint != null)
			{
				count(goal.countCheckpoint);
			}
			if (goal.countCreditedThrough != null)
			{
				count(goal.countCreditedThrough);
			}
			if (goal.spec.trackType == GoalSpec.TrackType.EXPERIENCE
					&& (goal.countCheckpoint != null || goal.countCreditedThrough != null || goal.countLiveBoundary))
			{
				throw new IllegalArgumentException("XP goal has count state");
			}
			if (!Set.of("SCHEDULED", "ACTIVE", "ACHIEVED", "ENDED").contains(goal.lifecycle))
			{
				throw new IllegalArgumentException("Unknown saved lifecycle");
			}
			if ((goal.baselineXp == null) != (goal.trackingStartedAt == null) ||
					(goal.baselineXp != null && (goal.baselineXp < 0 || goal.baselineXp > 200_000_000L)))
			{
				throw new IllegalArgumentException("Incomplete activation baseline");
			}
			if (goal.current != null)
			{
				period(goal.current);
			}
			goal.history.forEach(this::period);
			goal.plans.forEach((key, plan) ->
			{
				LocalDate.parse(plan.date);
				if (plan.periodId == null || plan.assigned < 0 || plan.credited < 0)
				{
					throw new IllegalArgumentException("Invalid saved daily plan");
				}
			});
		});
		state.activity.forEach((key, activity) ->
		{
			LocalDate.parse(activity.date);
			metric(activity.trackType, activity.metricId);
			if (activity.amount < 0 || activity.goalCredits == null ||
					!("Live".equals(activity.source) || "Recovered at login".equals(activity.source)
							|| "HiScores refreshed".equals(activity.source)))
			{
				throw new IllegalArgumentException("Invalid saved activity");
			}
			activity.goalCredits.forEach((goal, amount) ->
			{
				if (amount == null || amount < 0)
				{
					throw new IllegalArgumentException("Invalid activity credit");
				}
			});
		});
		state.collection.forEach((id, quantity) ->
		{
			item(id);
			if (quantity == null || quantity.observedPending < 0 || quantity.verifiedAt < 0 ||
					(quantity.quantity != null && (quantity.quantity < 0 || quantity.quantity > Integer.MAX_VALUE)) ||
					!Set.of("Unknown", "Drop", "Reward", "Pet", "Collection log refreshed").contains(quantity.source))
			{
				throw new IllegalArgumentException("Invalid collection quantity");
			}
		});
		state.itemActivity.forEach((key, activity) ->
		{
			item(activity.itemId);
			LocalDate.parse(activity.date);
			if (activity.amount < 1 || !Set.of("Drop", "Reward", "Pet", "Collection log refreshed").contains(activity.source))
			{
				throw new IllegalArgumentException("Invalid item activity");
			}
		});
		if (state.acquisitions.size() > 1024)
		{
			throw new IllegalArgumentException("Too many retained acquisition markers");
		}
		state.acquisitions.forEach((id, time) ->
		{
			if (id == null || id.isBlank() || id.length() > 200 || time == null || time < 0)
			{
				throw new IllegalArgumentException("Invalid acquisition marker");
			}
		});
		state.celebrations.forEach((id, event) ->
		{
			if (event == null)
			{
				throw new IllegalArgumentException("Missing completion record");
			}
			uuid(event.goalId);
			LocalDate.parse(event.date);
			String expected = event.overall ? "overall:" + event.goalId : "daily:" + event.goalId + ":" + event.date;
			if (!expected.equals(id) || !id.equals(event.id) || event.createdAt < 0 || event.name == null || event.name.isBlank() ||
					event.name.length() > 100 || event.detail == null || event.detail.length() > 200 ||
					!Set.of("PENDING", "ANNOUNCED", "SUPPRESSED").contains(event.status))
			{
				throw new IllegalArgumentException("Invalid completion record");
			}
		});
	}

	private void period(ProfileState.Period period)
	{
		if (period == null || period.id == null || period.revision < 1 || period.target < 0 || period.live < 0 || period.recovered < 0)
		{
			throw new IllegalArgumentException("Invalid saved period");
		}
		if (!LocalDate.parse(period.endExclusive).isAfter(LocalDate.parse(period.start)))
		{
			throw new IllegalArgumentException("Invalid period dates");
		}
		ZoneId.of(period.zone);
		period.progress();
	}

	private void uuid(String id)
	{
		UUID.fromString(id);
	}

	private void require(JsonObject object, String... names)
	{
		for (String name : names)
		{
			if (!object.has(name))
			{
				throw new IllegalArgumentException("Incomplete save field: " + name);
			}
		}
	}

	private void requireSpec(JsonObject spec)
	{
		require(spec, "name", "groupId", "trackType", "metricId", "startDate", "endDate", "zone", "mode", "cadence", "amount", "level",
				"uniqueTargets", "totalCountTarget");
	}

	private void requirePeriod(JsonObject period)
	{
		require(period, "id", "start", "endExclusive", "zone", "revision", "target", "live", "recovered", "closed");
	}

	private void skill(String skill)
	{
		if (skill == null || !skill.matches("[A-Z][A-Z_0-9]{0,99}") || skill.equals("OVERALL"))
		{
			throw new IllegalArgumentException("Choose an individual skill");
		}
	}

	private void metric(GoalSpec.TrackType type, String id)
	{
		if (type == GoalSpec.TrackType.EXPERIENCE)
		{
			skill(id);
		}
		else if (type == GoalSpec.TrackType.COUNT)
		{
			if (id == null || !id.matches("[A-Z][A-Z_0-9]{0,99}"))
			{
				throw new IllegalArgumentException("Invalid saved activity");
			}
		}
		else
		{
			throw new IllegalArgumentException("Missing track type");
		}
	}

	private void count(Long value)
	{
		if (value == null || value < 0 || value > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("Invalid saved count");
		}
	}

	private void item(Integer id)
	{
		if (id == null || id <= 0)
		{
			throw new IllegalArgumentException("Invalid collection item");
		}
	}

	private void migrateDayPlanSave(JsonObject document)
	{
		require(document, "goals");
		document.getAsJsonObject("goals").entrySet().forEach(entry ->
		{
			JsonObject goal = entry.getValue().getAsJsonObject();
			require(goal, "plans");
			goal.getAsJsonObject("plans").entrySet().forEach(p -> p.getValue().getAsJsonObject().addProperty("postponed", false));
		});
		document.addProperty("schemaVersion", 5);
	}

	private void migrateCountTargetSave(JsonObject document)
	{
		require(document, "goals");
		document.getAsJsonObject("goals").entrySet().forEach(entry ->
		{
			JsonObject goal = entry.getValue().getAsJsonObject();
			goal.getAsJsonObject("spec").add("totalCountTarget", com.google.gson.JsonNull.INSTANCE);
			require(goal, "pending");
			if (!goal.get("pending").isJsonNull())
			{
				goal.getAsJsonObject("pending").add("totalCountTarget", com.google.gson.JsonNull.INSTANCE);
			}
		});
		document.addProperty("schemaVersion", 4);
	}

	private void migrateCollectionSave(JsonObject document)
	{
		require(document, "goals", "counts", "activity");
		document.getAsJsonObject("goals").entrySet().forEach(entry ->
		{
			JsonObject goal = entry.getValue().getAsJsonObject();
			goal.getAsJsonObject("spec").add("uniqueTargets", new JsonObject());
			require(goal, "pending");
			if (!goal.get("pending").isJsonNull())
			{
				goal.getAsJsonObject("pending").add("uniqueTargets", new JsonObject());
			}
		});
		document.add("collection", new JsonObject());
		document.add("itemActivity", new JsonObject());
		document.add("acquisitions", new JsonObject());
		document.addProperty("schemaVersion", 3);
	}

	private void migrateXpSave(JsonObject document)
	{
		// A missing old field remains an error; migration never supplies missing player
		// progress.
		require(document, "goals", "activity", "xp");
		document.getAsJsonObject("goals").entrySet().forEach(entry ->
		{
			JsonObject goal = entry.getValue().getAsJsonObject();
			migrateSpec(goal.getAsJsonObject("spec"));
			require(goal, "pending");
			if (!goal.get("pending").isJsonNull())
			{
				migrateSpec(goal.getAsJsonObject("pending"));
			}
			goal.add("countCheckpoint", com.google.gson.JsonNull.INSTANCE);
			goal.add("countCreditedThrough", com.google.gson.JsonNull.INSTANCE);
			goal.addProperty("countLiveBoundary", false);
		});
		JsonObject migratedActivity = new JsonObject();
		document.getAsJsonObject("activity").entrySet().forEach(entry ->
		{
			JsonObject activity = entry.getValue().getAsJsonObject();
			require(activity, "skill", "date", "source");
			activity.add("metricId", activity.remove("skill"));
			activity.addProperty("trackType", "EXPERIENCE");
			migratedActivity.add(activity.get("date").getAsString() + ":EXPERIENCE:" + activity.get("metricId").getAsString() + ":"
					+ activity.get("source").getAsString(), activity);
		});
		document.add("activity", migratedActivity);
		document.add("counts", new JsonObject());
		document.addProperty("schemaVersion", 2);
	}

	private void migrateSpec(JsonObject spec)
	{
		require(spec, "skill");
		spec.add("metricId", spec.remove("skill"));
		spec.addProperty("trackType", "EXPERIENCE");
	}
}
