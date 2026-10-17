package com.mygoals.domain;

import java.util.Set;

/**
 * Removes goal-owned records, retaining independent account activity totals.
 */
public final class GoalPurge
{
	private GoalPurge()
	{
	}

	public static boolean strip(ProfileState state, Set<String> ids)
	{
		boolean changed = state.goals.keySet().removeAll(ids);
		changed |= state.finished.keySet().removeAll(ids);
		changed |= state.celebrations.values().removeIf(event -> ids.contains(event.goalId));
		for (var activity : state.activity.values())
		{
			changed |= activity.goalCredits.keySet().removeIf(key -> key.length() > 36 && ids.contains(key.substring(0, 36)));
		}
		return changed;
	}
}
