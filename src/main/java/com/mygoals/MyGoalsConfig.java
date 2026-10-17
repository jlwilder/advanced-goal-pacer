package com.mygoals;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import com.mygoals.domain.GoalDateFormat;

@ConfigGroup("mygoals")
public interface MyGoalsConfig extends Config
{
	@ConfigItem(keyName = "dailyCards", name = "Today completion cards", position = 0, description = "Show a card once when a goal's Today assignment is completed.")
	default boolean dailyCards()
	{
		return true;
	}

	@ConfigItem(keyName = "overallCards", name = "Goal completion cards", position = 1, description = "Show a card when an overall XP, level, unique, or lifetime count goal is achieved.")
	default boolean overallCards()
	{
		return true;
	}

	@ConfigItem(keyName = "fireworks", name = "Goal completion fireworks", position = 2, description = "Show a brief local overlay firework effect with overall goal completion cards.")
	default boolean fireworks()
	{
		return true;
	}

	@ConfigItem(keyName = "groupToday", name = "Enable groups", position = 3, hidden = true, description = "Show named goal groups; hiding groups preserves names and assignments.")
	default boolean groupToday()
	{
		return false;
	}

	@ConfigItem(keyName = "dateFormat", name = "Date format", position = 4, hidden = true, description = "Display American or European calendar dates, including the year.")
	default GoalDateFormat dateFormat()
	{
		return GoalDateFormat.AMERICAN;
	}
}
