package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import javax.swing.JMenuItem;

/**
 * Read-only source timestamps. Opening the menu never requests data or reads
 * disk.
 */
final class DataChecksMenu
{
	final JMenuItem hiscores = new JMenuItem("HiScores: Not recorded");
	final JMenuItem collection = new JMenuItem("Collection log: Not recorded");
	private GoalDateFormat dateFormat = GoalDateFormat.AMERICAN;

	void setDateFormat(GoalDateFormat format)
	{
		dateFormat = format;
	}

	DataChecksMenu()
	{
		hiscores.setName("lastHiScoresCheck");
		hiscores.setEnabled(false);
		collection.setName("collectionLogChecks");
		collection.setToolTipText(
				"<html>If a collection-log count looks inaccurate,<br>open that boss's page in your Collection Log.<br>Advanced Goal Pacer will automatically load the current counts.</html>");
		collection.getAccessibleContext().setAccessibleDescription(
				"If a collection-log count looks inaccurate, open that boss's page in your Collection Log. Advanced Goal Pacer will automatically load the current counts.");
	}

	void render(GoalsView next)
	{
		hiscores.setText("HiScores: " + time(next.lastHiScoresCheckedAt)
				+ (next.countStatus.startsWith("HiScores unavailable") ? " (unavailable)" : ""));
		hiscores.setToolTipText("Last successful check. " + next.countStatus);
		collection
				.setText("Collection log: " + time(next.collectionPageChecks.values().stream().mapToLong(Long::longValue).max().orElse(0)));
	}

	private String time(long timestamp)
	{
		return timestamp <= 0 ? "Not recorded" : dateFormat.timestamp(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
	}
}
