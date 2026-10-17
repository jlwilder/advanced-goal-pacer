package com.mygoals.tracking;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.hiscore.Skill;

public final class MetricCatalogue
{
	private static final List<HiscoreSkill> COUNTS = Collections.unmodifiableList(Arrays.stream(HiscoreSkill.values())
			.filter(s -> s.getType() == HiscoreSkillType.ACTIVITY || s.getType() == HiscoreSkillType.BOSS).collect(Collectors.toList()));
	private static final java.util.Map<String, HiscoreSkill> BY_ID = COUNTS.stream()
			.collect(Collectors.toUnmodifiableMap(Enum::name, metric -> metric));

	public enum Semantics
	{
		CUMULATIVE_COUNTER, SCORE_REQUIRES_AUDIT
	}

	private MetricCatalogue()
	{
	}

	public static List<HiscoreSkill> countMetrics()
	{
		return COUNTS;
	}

	public static Semantics semantics(HiscoreSkill metric)
	{
		switch (metric)
		{
		case PVP_ARENA_RANK:
		case COLOSSEUM_GLORY:
		case LAST_MAN_STANDING:
		case COLLECTIONS_LOGGED:
		case LEAGUE_POINTS:
			return Semantics.SCORE_REQUIRES_AUDIT;
		default:
			return Semantics.CUMULATIVE_COUNTER;
		}
	}

	public static Long activityAmount(Skill value)
	{
		// RuneLite's activity transport stores score in level; rank is leaderboard
		// position.
		return value == null || value.getLevel() < 0 ? null : (long) value.getLevel();
	}

	public static HiscoreSkill countMetric(String id)
	{
		HiscoreSkill metric = findCountMetric(id);
		if (metric == null)
		{
			throw new IllegalArgumentException("Choose a HiScores activity");
		}
		return metric;
	}

	public static HiscoreSkill findCountMetric(String id)
	{
		return BY_ID.get(id);
	}

	public static String unit(String id)
	{
		HiscoreSkill metric = findCountMetric(id);
		return metric == null ? "counts" : unit(metric);
	}

	public static String name(String id)
	{
		HiscoreSkill metric = findCountMetric(id);
		return metric == null ? id : metric.getName();
	}

	public static boolean bestScore(HiscoreSkill metric)
	{
		return metric == HiscoreSkill.LAST_MAN_STANDING || metric == HiscoreSkill.PVP_ARENA_RANK || metric == HiscoreSkill.COLOSSEUM_GLORY;
	}

	public static boolean requiresGameBaseline(HiscoreSkill metric)
	{
		return metric.getType() == HiscoreSkillType.BOSS || metric == HiscoreSkill.RIFTS_CLOSED ||
				(metric.name().startsWith("CLUE_SCROLL_") && metric != HiscoreSkill.CLUE_SCROLL_ALL);
	}

	public static String unit(HiscoreSkill metric)
	{
		if (metric.name().startsWith("CLUE_SCROLL_"))
		{
			return "clues";
		}
		if (bestScore(metric) || metric == HiscoreSkill.LEAGUE_POINTS)
		{
			return "points";
		}
		switch (metric)
		{
		case SOUL_WARS_ZEAL:
			return "Zeal";
		case RIFTS_CLOSED:
			return "rifts";
		case COLLECTIONS_LOGGED:
			return "slots";
		case BARROWS_CHESTS:
		case LUNAR_CHESTS:
			return "chests";
		case THE_GAUNTLET:
		case THE_CORRUPTED_GAUNTLET:
		case CHAMBERS_OF_XERIC:
		case CHAMBERS_OF_XERIC_CHALLENGE_MODE:
		case THEATRE_OF_BLOOD:
		case THEATRE_OF_BLOOD_HARD_MODE:
		case TOMBS_OF_AMASCUT:
		case TOMBS_OF_AMASCUT_EXPERT:
		case TEMPOROSS:
		case WINTERTODT:
			return "completions";
		default:
			return "kills";
		}
	}

	public static String trackingHelp(HiscoreSkill metric)
	{
		if (metric == HiscoreSkill.LEAGUE_POINTS)
		{
			return "League Points needs a seasonal profile. Standard-world tracking is unavailable.";
		}
		if (bestScore(metric))
		{
			return "Tracks improvements above the highest observed score. Drops and rebounds below that best add no progress. Updates from HiScores at login.";
		}
		if (metric.getType() == HiscoreSkillType.BOSS
				|| (metric.name().startsWith("CLUE_SCROLL_") && metric != HiscoreSkill.CLUE_SCROLL_ALL))
		{
			return "Updates from supported game count messages. Login recovery starts after the first game count establishes your baseline. The first live completion counts once; HiScores may lag.";
		}
		if (metric == HiscoreSkill.RIFTS_CLOSED)
		{
			return "Check your closed-rift total in game to establish a baseline. Later checks and HiScores at login can update progress.";
		}
		return "Updates from HiScores at login or Check counts. The first available total establishes a baseline. Unranked totals remain unknown.";
	}

	public static String baselineAction(HiscoreSkill metric)
	{
		if (metric == HiscoreSkill.RIFTS_CLOSED)
		{
			return "Check your rift total in game";
		}
		switch (unit(metric))
		{
		case "clues":
			return "Complete 1 clue to enable login recovery";
		case "chests":
			return "Open 1 chest to enable login recovery";
		case "completions":
			return "Complete 1 run to enable login recovery";
		default:
			return "Get 1 kill to enable login recovery";
		}
	}
}
