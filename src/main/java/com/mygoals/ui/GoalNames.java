package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;

import net.runelite.api.Skill;

/**
 * Keeps the level in the objective rather than repeating it in generated
 * titles.
 */
final class GoalNames
{
	private GoalNames()
	{
	}

	static String skill(GoalSpec spec)
	{
		try
		{
			return Skill.valueOf(spec.metricId).getName();
		}
		catch (IllegalArgumentException ignored)
		{
			return spec.metricId;
		}
	}

	static String level(GoalSpec spec)
	{
		return spec.level + " " + skill(spec);
	}

	static String title(GoalSpec spec)
	{
		if (spec.mode != GoalSpec.Mode.LEVEL)
		{
			return spec.name;
		}
		String skill = skill(spec);
		return spec.name.equalsIgnoreCase(level(spec)) || spec.name.equalsIgnoreCase(skill + " level " + spec.level) ? skill : spec.name;
	}
}
