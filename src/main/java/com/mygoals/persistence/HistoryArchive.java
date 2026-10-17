package com.mygoals.persistence;

import com.mygoals.domain.ProfileState;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Moves old, settled values out of the working set. Nothing is discarded or
 * expired.
 */
public final class HistoryArchive
{
	private HistoryArchive()
	{
	}

	public static boolean validName(String name)
	{
		return name != null && name.matches("[0-9]{4}-(0[1-9]|1[0-2])-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.json");
	}

	/**
	 * Ownership of the removed DTOs transfers to these immutable-by-contract
	 * fragments.
	 */
	public static Map<String, ProfileState> detach(ProfileState state, LocalDate today)
	{
		LocalDate cutoff = today.minusDays(31);
		Map<String, ProfileState> months = new LinkedHashMap<>();
		java.util.function.Function<String, ProfileState> month = date -> months.computeIfAbsent(date.substring(0, 7), ignored ->
		{
			ProfileState fragment = new ProfileState(state.profileKey);
			state.groups.forEach(g -> fragment.groups.add(new ProfileState.Group(g.id, g.name)));
			return fragment;
		});
		state.activity.entrySet().removeIf(e ->
		{
			if (!LocalDate.parse(e.getValue().date).isBefore(cutoff))
			{
				return false;
			}
			month.apply(e.getValue().date).activity.put(e.getKey(), e.getValue());
			return true;
		});
		state.itemActivity.entrySet().removeIf(e ->
		{
			if (!LocalDate.parse(e.getValue().date).isBefore(cutoff))
			{
				return false;
			}
			month.apply(e.getValue().date).itemActivity.put(e.getKey(), e.getValue());
			return true;
		});
		var goals = state.goals.entrySet().iterator();
		while (goals.hasNext())
		{
			var entry = goals.next();
			String id = entry.getKey();
			var goal = entry.getValue();
			java.util.function.Function<String, ProfileState.Goal> archivedGoal = date -> month.apply(date).goals.computeIfAbsent(id,
					ignored ->
					{
						// Copy only metadata here; copying a lifetime of history defeats bounded
						// snapshots.
						var g = new ProfileState.Goal();
						g.id = id;
						g.spec = goal.spec;
						g.revision = goal.revision;
						g.createdAt = goal.createdAt;
						g.lifecycle = "ENDED";
						return g;
					});
			// Retain the latest recorded period for the ended-goal card's summary.
			ProfileState.Period last = goal.history.isEmpty() ? null : goal.history.get(goal.history.size() - 1);
			goal.history.removeIf(p ->
			{
				if (p == last || !p.closed || !LocalDate.parse(p.endExclusive).isBefore(cutoff))
				{
					return false;
				}
				archivedGoal.apply(p.endExclusive).history.add(p);
				goal.archivedPeriods = Math.addExact(goal.archivedPeriods, 1);
				return true;
			});
			goal.plans.entrySet().removeIf(e ->
			{
				if (!LocalDate.parse(e.getValue().date).isBefore(cutoff))
				{
					return false;
				}
				archivedGoal.apply(e.getValue().date).plans.put(e.getKey(), e.getValue());
				return true;
			});
			String finished = goal.achievedDate == null ? goal.spec.endDate : goal.achievedDate;
			if ((goal.lifecycle.equals("ACHIEVED") || goal.lifecycle.equals("ENDED")) && finished != null
					&& LocalDate.parse(finished).isBefore(cutoff))
			{
				ProfileState fragment = new ProfileState(state.profileKey);
				state.groups.forEach(g -> fragment.groups.add(new ProfileState.Group(g.id, g.name)));
				fragment.goals.put(id, goal);
				String file = finished.substring(0, 7) + "-" + UUID.randomUUID() + ".json";
				months.put(file, fragment); // Already a unique segment, separate from monthly aggregate buckets.
				state.finished.put(id, new ProfileState.Finished(goal, file));
				goals.remove();
			}

		}
		state.celebrations.entrySet().removeIf(e ->
		{
			var event = e.getValue();
			if (event.status.equals("PENDING") || !LocalDate.parse(event.date).isBefore(cutoff))
			{
				return false;
			}
			month.apply(event.date).celebrations.put(e.getKey(), event);
			return true;
		});
		Map<String, ProfileState> files = new LinkedHashMap<>();
		months.forEach((date, fragment) ->
		{
			String name = validName(date) ? date : date + "-" + UUID.randomUUID() + ".json";
			files.put(name, fragment);
			state.historyFiles.add(name);
		});
		return files;
	}
}
