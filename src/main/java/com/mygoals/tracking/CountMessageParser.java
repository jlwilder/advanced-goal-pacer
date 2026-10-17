package com.mygoals.tracking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.util.Text;

/**
 * Parses game messages only; callers reject player chat. No NPC death or loot
 * inference.
 */
public final class CountMessageParser
{
	private static final Pattern COUNT = Pattern.compile(
			"^Your (?:(?:completion count for|subdued|completed) )?(.+?) (?:(?:kill|harvest|completion|success) )?(?:count )?is: ?([0-9,]+)(?:\\.|$)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern LEGACY_COLOUR = Pattern.compile("@[^@\\r\\n]+@");
	private static final Pattern CLUE = Pattern.compile(
			"^You have completed ([0-9,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.$", Pattern.CASE_INSENSITIVE);
	private static final Pattern RIFTS = Pattern.compile("^Amount of Rifts you have closed: ([0-9,]+)\\.$", Pattern.CASE_INSENSITIVE);
	private static final Map<String, HiscoreSkill> BOSSES = bosses();

	public static final class Observation
	{
		public final String metricId;
		public final long total;
		public final boolean completion;

		Observation(HiscoreSkill metric, long total, boolean completion)
		{
			this.metricId = metric.name();
			this.total = total;
			this.completion = completion;
		}
	}

	public static List<Observation> parse(String raw)
	{
		if (raw == null)
		{
			return Collections.emptyList();
		}
		String message = LEGACY_COLOUR.matcher(Text.removeTags(raw)).replaceAll("").trim();
		Matcher clue = CLUE.matcher(message), count = COUNT.matcher(message), rifts = RIFTS.matcher(message);
		try
		{
			if (clue.matches())
			{
				return List.of(new Observation(HiscoreSkill.valueOf("CLUE_SCROLL_" + clue.group(2).toUpperCase(Locale.ROOT)),
						amount(clue.group(1)), true));
			}
			if (rifts.matches())
			{
				return List.of(new Observation(HiscoreSkill.RIFTS_CLOSED, amount(rifts.group(1)), false));
			}
			if (count.find() && message.toLowerCase(Locale.ROOT).contains("count"))
			{
				HiscoreSkill metric = BOSSES.get(normalize(count.group(1)));
				if (metric != null)
				{
					return List.of(new Observation(metric, amount(count.group(2)), true));
				}
			}
		}
		catch (IllegalArgumentException ex)
		{
			// Unknown or malformed values supply no evidence.
		}
		return Collections.emptyList();
	}

	private static long amount(String text)
	{
		long value = Long.parseLong(text.replace(",", ""));
		if (value < 0 || value > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("Invalid counter");
		}
		return value;
	}

	private static String normalize(String name)
	{
		return name.toLowerCase(Locale.ROOT).replaceFirst("^the ", "").replaceAll("[^a-z0-9]", "");
	}

	private static Map<String, HiscoreSkill> bosses()
	{
		Map<String, HiscoreSkill> names = new LinkedHashMap<>();
		MetricCatalogue.countMetrics().stream().filter(m -> m.getType() == HiscoreSkillType.BOSS)
				.forEach(m -> names.put(normalize(m.getName()), m));
		names.put(normalize("Barrows chest"), HiscoreSkill.BARROWS_CHESTS);
		names.put(normalize("Lunar chest"), HiscoreSkill.LUNAR_CHESTS);
		names.put(normalize("Tombs of Amascut Expert Mode"), HiscoreSkill.TOMBS_OF_AMASCUT_EXPERT);
		return Collections.unmodifiableMap(names);
	}
}
