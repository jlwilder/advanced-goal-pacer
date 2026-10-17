package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.GoalSpec;
import com.mygoals.tracking.MetricCatalogue;

import java.awt.Component;
import java.awt.Dimension;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import net.runelite.client.hiscore.HiscoreSkillType;

import static com.mygoals.ui.GoalCardStyle.*;

/**
 * Agenda metadata and shared pace states. Assignment arithmetic remains in the
 * engine.
 */
final class TodayCardBody extends JPanel
{
	TodayCardBody(GoalsView.Goal goal, boolean tomorrow, boolean unavailable, BiConsumer<Integer, JLabel> icons)
	{
		this(goal, tomorrow, unavailable, icons, false, GoalDateFormat.AMERICAN);
	}

	TodayCardBody(GoalsView.Goal goal, boolean tomorrow, boolean unavailable, BiConsumer<Integer, JLabel> icons, boolean summary,
			GoalDateFormat dates)
	{
		setName("todayCardBody");
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		if (!goal.compatibilityIssue.isEmpty())
		{
			javax.swing.JTextArea warning = new javax.swing.JTextArea(goal.compatibilityIssue);
			warning.setName("cardCompatibilityWarning");
			warning.setEditable(false);
			warning.setOpaque(false);
			warning.setLineWrap(true);
			warning.setWrapStyleWord(true);
			warning.setForeground(GOLD);
			warning.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(warning);
			add(Box.createVerticalStrut(6));
		}
		String unit = unit(goal.spec), quota = amount(goal.target, unit);
		String objective = goal.spec.mode == GoalSpec.Mode.LEVEL ? GoalNames.level(goal.spec)
				: goal.spec.totalCountTarget != null ? compact(goal.spec.totalCountTarget) + " KC" : quota;
		String cadence = cadence(goal.spec.cadence);
		LocalDate date = LocalDate.parse(tomorrow && goal.tomorrowDate != null ? goal.tomorrowDate : goal.todayDate);
		long days = goal.spec.endDate == null ? 0
				: Math.max(0, ChronoUnit.DAYS.between(date, LocalDate.parse(goal.spec.endDate).plusDays(1)));
		JLabel target = !goal.uniques.isEmpty() ? value(quota + " / " + cadence, "todayPlanTarget")
				: goal.spec.totalCountTarget != null
						? value(goal.totalCount == null ? "? KC left"
								: compact(Math.max(0, goal.spec.totalCountTarget - goal.totalCount)) + " KC left", "todayPlanTarget")
						: value(goal.spec.endDate == null ? "Indefinitely" : days + (days == 1 ? " day" : " days"), "todayPlanTarget");
		target.setToolTipText(!goal.uniques.isEmpty() ? format(goal.target) + " " + unit + " / " + cadence
				: goal.spec.totalCountTarget != null
						? (goal.totalCount == null ? "?" : format(goal.totalCount)) + " / " + format(goal.spec.totalCountTarget)
								+ " lifetime KC • no deadline"
						: goal.spec.endDate == null ? "No end date"
								: "Through " + dates.date(LocalDate.parse(goal.spec.endDate)) + " (" + goal.spec.zone + ")");
		JComponent objectiveValue;
		if (!goal.uniques.isEmpty())
		{
			objectiveValue = uniques(goal, unavailable, icons);
		}
		else
		{
			JLabel plan = value(
					objective + (goal.spec.cadence != GoalSpec.Cadence.ONCE && goal.spec.totalCountTarget == null ? " / " + cadence : ""),
					"todayPlanGoal");
			agendaNote(plan);
			plan.setForeground(getForeground());
			objectiveValue = plan;
		}
		add(Box.createVerticalStrut(4));
		add(field("Goal", objectiveValue, !goal.uniques.isEmpty()));
		add(Box.createVerticalStrut(4));
		agendaNote(target);
		target.setForeground(getForeground());
		JPanel targetValues = column();
		targetValues.add(target);
		if (goal.spec.totalCountTarget != null)
		{
			JLabel schedule = label(quota + " / " + cadence);
			schedule.setName("todayPlanCadence");
			agendaNote(schedule);
			targetValues.add(schedule);
		}
		add(field("Target", targetValues));
		add(Box.createVerticalStrut(10));
		if (!summary)
		{
			Long assignment = tomorrow ? goal.tomorrowAssigned : Long.valueOf(goal.todayAssigned);
			long credited = tomorrow ? 0 : goal.todayCredited;
			boolean met = !tomorrow && goal.inToday && credited >= goal.todayAssigned;
			String progressUnit = unitFor(assignment == null ? 0 : assignment, unit);
			String progress = assignment == null ? "Ends today"
					: assignment == 0 ? "No work due"
							: (met ? "✓ " : "") + compact(credited) + " / " + compact(assignment) + " " + progressUnit;
			String tooltip = assignment == null ? "The goal has no assignment tomorrow."
					: format(credited) + " / " + format(assignment) + " " + progressUnit +
							(tomorrow ? " planned for tomorrow" : " credited today");
			add(pace(tomorrow ? "Tomorrow's Pace" : "Today's Pace", progress, tooltip, !tomorrow && goal.todayComplete,
					fraction(credited, assignment, met), "todayCard"));
			add(Box.createVerticalStrut(8));
		}
		String targetUnit = unitFor(goal.target, unit);
		String targetProgress = (goal.periodQuotaMet ? "✓ " : "") + compact(goal.progress) + " / " + compact(goal.target) + " "
				+ targetUnit;
		String targetTooltip = format(goal.progress) + " / " + format(goal.target) + " " + targetUnit +
				(goal.spec.cadence == GoalSpec.Cadence.ONCE ? " toward this goal" : " this period")
				+ (goal.periodQuotaMet ? " • Target met" : " • Target in progress");
		boolean targetGreen = goal.periodQuotaMet || goal.lifecycle.equals("ACHIEVED") || !summary && goal.todayComplete;
		add(pace("Target Pace", targetProgress, targetTooltip, targetGreen,
				fraction(goal.progress, goal.target, goal.periodQuotaMet), "targetCard"));
		if (tomorrow && goal.todayCredited > 0)
		{
			JLabel earned = label("Today: " + compact(goal.todayCredited) + " / " + amount(goal.todayAssigned, unit));
			earned.setName("todayCreditedBeforePostpone");
			earned.setForeground(MUTED);
			add(Box.createVerticalStrut(4));
			add(earned);
		}
		if (goal.lifecycle.equals("ACHIEVED"))
		{
			JLabel complete = label("✓ Goal achieved");
			complete.setForeground(GREEN);
			add(Box.createVerticalStrut(4));
			add(complete);
		}
		else if (tomorrow && goal.tomorrowNewPeriod)
		{
			JLabel next = label("New period tomorrow");
			next.setForeground(MUTED);
			add(Box.createVerticalStrut(4));
			add(next);
		}
		if (goal.lifecycle.equals("SCHEDULED") || goal.lifecycle.equals("ENDED"))
		{
			JLabel state = label(
					goal.lifecycle.equals("SCHEDULED") ? "Starts " + dates.date(LocalDate.parse(goal.spec.startDate)) : "Schedule ended");
			state.setName("cardScheduleState");
			state.setForeground(MUTED);
			add(Box.createVerticalStrut(4));
			add(state);
		}
		if (goal.pendingStart != null)
		{
			JLabel pending = label("Changes scheduled for " + dates.date(LocalDate.parse(goal.pendingStart)));
			pending.setForeground(MUTED);
			pending.setName("cardPendingChanges");
			add(Box.createVerticalStrut(4));
			add(pending);
		}
	}

	private static void agendaNote(JLabel label)
	{
		label.setForeground(MUTED);
		SidebarStyle.smallText(label, 1f);
	}

	private static int fraction(long credited, Long assignment, boolean met)
	{
		return assignment == null || assignment == 0 ? met ? 1000 : 0 : (int) Math.min(1000, credited * 1000 / assignment);
	}

	private static JComponent uniques(GoalsView.Goal goal, boolean unavailable, BiConsumer<Integer, JLabel> icons)
	{
		List<GoalsView.UniqueTarget> selected = goal.uniques.stream().sorted(java.util.Comparator.comparing(t -> !unavailable && t.met))
				.collect(Collectors.toList());
		JPanel target = column();
		target.setName("todayPlanGoal");
		int available = net.runelite.client.ui.PluginPanel.PANEL_WIDTH - net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH -
				10 - fieldLabelWidth() - 6;
		JPanel row = null;
		int columns = 0, width = 0;
		for (GoalsView.UniqueTarget item : selected.stream().limit(4).collect(Collectors.toList()))
		{
			boolean known = !unavailable && item.quantity != null && !item.needsSync;
			boolean met = !unavailable && item.met;
			JLabel icon = label("(" + (known ? compact(item.quantity) : "?") + "/" + compact(item.target) + ")");
			icon.setName("todayUnique-" + item.itemId);
			icon.setForeground(met ? GREEN : known ? GOLD : MUTED);
			SidebarStyle.smallText(icon, 1f);
			icon.setHorizontalAlignment(JLabel.LEFT);
			icon.setHorizontalTextPosition(JLabel.RIGHT);
			icon.setVerticalTextPosition(JLabel.CENTER);
			icon.setIconTextGap(2);
			int itemWidth = Math.min(available,
					24 + (icon.getText().isEmpty() ? 0 : 2 + icon.getFontMetrics(icon.getFont()).stringWidth(icon.getText())));
			Dimension size = new Dimension(itemWidth, 26);
			icon.setPreferredSize(size);
			icon.setMinimumSize(size);
			icon.setMaximumSize(size);
			icon.setToolTipText(item.name + ": " +
					(known ? format(item.quantity) + " / " + format(item.target)
							+ (met ? " • Target met" : " • " + format(item.target - item.quantity) + " still needed")
							: "open the collection-log page to verify your " + format(item.target) + " target total"));
			icons.accept(item.itemId, icon);
			if (row == null || columns == 3 || width + 4 + itemWidth > available)
			{
				if (row != null)
				{
					target.add(Box.createVerticalStrut(3));
				}
				row = new JPanel();
				row.setOpaque(false);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
				target.add(row);
				columns = 0;
				width = 0;
			}
			if (columns > 0)
			{
				row.add(Box.createHorizontalStrut(4));
				width += 4;
			}
			row.add(icon);
			columns++;
			width += itemWidth;
		}
		if (selected.size() > 4)
		{
			JLabel more = label("+" + (selected.size() - 4) + " more");
			more.setForeground(MUTED);
			target.add(more);
		}
		return target;
	}

	static String unit(GoalSpec spec)
	{
		if (spec.trackType == GoalSpec.TrackType.EXPERIENCE)
		{
			return "XP";
		}
		var metric = MetricCatalogue.findCountMetric(spec.metricId);
		return metric != null && metric.getType() == HiscoreSkillType.BOSS ? "KC" : MetricCatalogue.unit(spec.metricId);
	}

	static String compact(long amount)
	{
		return amount >= 1_000_000 && amount % 1_000_000 == 0 ? (amount / 1_000_000) + "m"
				: amount >= 1000 && amount % 1000 == 0 ? (amount / 1000) + "k" : format(amount);
	}

	private static String amount(long amount, String unit)
	{
		return compact(amount) + " " + unitFor(amount, unit);
	}

	private static String unitFor(long amount, String unit)
	{
		return amount == 1 && unit.endsWith("s") ? unit.substring(0, unit.length() - 1) : unit;
	}

	private static String format(long amount)
	{
		return String.format(java.util.Locale.US, "%,d", amount);
	}

	private static String cadence(GoalSpec.Cadence cadence)
	{
		switch (cadence)
		{
		case DAILY:
			return "day";
		case WEEKLY:
			return "week";
		case MONTHLY:
			return "month";
		default:
			return "total";
		}
	}
}
