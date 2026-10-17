package com.mygoals.domain;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.tracking.MetricCatalogue;

import net.runelite.api.Skill;
import net.runelite.client.hiscore.HiscoreSkillType;

/** Current content eligibility is independent of the durable save format. */
public final class GoalCompatibility
{
	private GoalCompatibility()
	{
	}

	public static String problem(GoalSpec spec)
	{
		return spec.compatibilityIssue();
	}

	static String evaluate(GoalSpec spec)
	{
		if (spec.trackType == GoalSpec.TrackType.EXPERIENCE)
		{
			try
			{
				if (Skill.valueOf(spec.metricId) != Skill.OVERALL)
				{
					return "";
				}
			}
			catch (IllegalArgumentException ignored)
			{
				// Retired skill: preserve its saved goal.
			}
			return "This skill is unavailable in the current client. Saved progress is preserved.";
		}
		var metric = MetricCatalogue.findCountMetric(spec.metricId);
		if (metric == null)
		{
			return "This activity is unavailable in the current client. Saved progress is preserved.";
		}
		if (spec.totalCountTarget != null && metric.getType() != HiscoreSkillType.BOSS)
		{
			return "Lifetime KC targets require a boss. Saved progress is preserved.";
		}
		if (!spec.uniqueTargets.isEmpty())
		{
			try
			{
				UniqueCatalogue.INSTANCE.validateTargets(spec.metricId, spec.uniqueTargets);
			}
			catch (IllegalArgumentException ignored)
			{
				return "Unique definitions changed. This goal is paused; its targets and history are preserved.";
			}
		}
		return "";
	}

	public static boolean supported(GoalSpec spec)
	{
		return problem(spec).isEmpty();
	}
}
