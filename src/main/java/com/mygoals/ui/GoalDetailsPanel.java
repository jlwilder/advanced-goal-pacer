package com.mygoals.ui;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.GoalSpec;
import com.mygoals.tracking.MetricCatalogue;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.text.DefaultCaret;

/**
 * Read-only live detail view. Progress updates retain item image bindings and
 * scroll position.
 */
final class GoalDetailsPanel extends JPanel implements Scrollable
{
	private static final Color CARD = new Color(45, 45, 45), GREEN = new Color(102, 204, 128), GOLD = new Color(230, 184, 92),
			MUTED = new Color(175, 175, 175);
	private final JTextArea name = note(""), schedule = note(""), repeat = note(""), verification = note(""), pendingChanges = note(""),
			tomorrow = note("");
	private final JLabel subject = label(""), result = label(""), uniqueSummary = label(""), goalIcon = label(""), planTarget = label(""),
			planCadence = label(""), repeatCaption = label("Repeat");
	private final ProgressBlock today = new ProgressBlock("todayProgress"), period = new ProgressBlock("periodProgress"),
			lifetime = new ProgressBlock("lifetimeProgress");
	private final PeriodHistoryPanel history = new PeriodHistoryPanel();
	private final DailyPaceChart dailyChart = new DailyPaceChart();
	private final JPanel uniques = column(), uniqueRows = column();
	private final Map<Integer, UniqueRow> rows = new LinkedHashMap<>();
	private final BiConsumer<Integer, JLabel> icons;
	private final BiConsumer<GoalSpec, JLabel> goalIcons;
	private GoalsView.Goal rendered;
	private boolean unavailable;
	private GoalDateFormat dateFormat = GoalDateFormat.AMERICAN, renderedFormat;

	void setDateFormat(GoalDateFormat format)
	{
		if (dateFormat == format)
		{
			return;
		}
		dateFormat = format;
		history.setDateFormat(format);
		if (rendered != null)
		{
			render(rendered, unavailable);
		}
	}

	GoalDetailsPanel(BiConsumer<Integer, JLabel> icons)
	{
		this(icons, (spec, label) ->
		{
		});
	}

	GoalDetailsPanel(BiConsumer<Integer, JLabel> icons, BiConsumer<GoalSpec, JLabel> goalIcons)
	{
		this.icons = icons;
		this.goalIcons = goalIcons;
		setName("goalDetails");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		name.setName("detailGoalName");
		name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, 18f));
		name.setForeground(GOLD);
		name.setRows(2);
		schedule.setRows(2);
		repeat.setRows(2);
		pendingChanges.setRows(1);
		verification.setRows(3);
		subject.setName("detailSubject");
		schedule.setName("detailSchedule");
		repeat.setName("detailRepeat");
		result.setName("detailResult");
		subject.setForeground(MUTED);
		result.setForeground(GREEN);
		JPanel header = new JPanel(new BorderLayout(10, 0));
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		goalIcon.setName("detailGoalIcon");
		goalIcon.setPreferredSize(new Dimension(34, 40));
		goalIcon.setVerticalAlignment(JLabel.TOP);
		goalIcon.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		JPanel title = column();
		title.setOpaque(false);
		title.add(name);
		title.add(subject);
		header.add(goalIcon, BorderLayout.WEST);
		header.add(title, BorderLayout.CENTER);
		add(header);
		add(Box.createVerticalStrut(10));
		add(goalPlan());
		add(result);
		add(Box.createVerticalStrut(10));
		add(today);
		tomorrow.setName("detailTomorrow");
		tomorrow.setForeground(GOLD);
		tomorrow.setRows(3);
		add(tomorrow);
		add(Box.createVerticalStrut(8));
		add(period);
		add(Box.createVerticalStrut(8));
		add(dailyChart);
		add(Box.createVerticalStrut(8));
		add(lifetime);
		uniques.setName("detailUniques");
		uniques.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		uniqueSummary.setName("detailUniqueSummary");
		uniqueSummary.setFont(uniqueSummary.getFont().deriveFont(java.awt.Font.BOLD));
		verification.setName("detailCollectionRefresh");
		verification.setForeground(new Color(235, 207, 143));
		uniques.add(uniqueSummary);
		uniques.add(Box.createVerticalStrut(6));
		uniques.add(verification);
		uniques.add(uniqueRows);
		JTextArea explanation = note(
				"Targets use your collection-log totals, including items already owned. All selected totals must be reached to finish this goal.");
		explanation.setRows(3);
		explanation.setForeground(MUTED);
		uniques.add(explanation);
		add(uniques);
		add(Box.createVerticalStrut(12));
		add(history);
	}

	private JPanel goalPlan()
	{
		JPanel plan = column();
		plan.setName("detailPlan");
		plan.setBackground(CARD);
		plan.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(65, 65, 65)),
				BorderFactory.createEmptyBorder(10, 10, 8, 10)));
		JLabel heading = label("Goal plan");
		heading.setForeground(MUTED);
		plan.add(heading);
		plan.add(Box.createVerticalStrut(5));
		JPanel target = new JPanel(new BorderLayout(8, 0));
		target.setOpaque(false);
		target.setAlignmentX(Component.LEFT_ALIGNMENT);
		planTarget.setName("detailPlanTarget");
		planTarget.setFont(planTarget.getFont().deriveFont(java.awt.Font.BOLD, 17f));
		planTarget.setForeground(GOLD);
		planCadence.setName("detailPlanCadence");
		planCadence.setOpaque(true);
		planCadence.setBackground(new Color(63, 63, 63));
		planCadence.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7));
		target.add(planTarget, BorderLayout.WEST);
		target.add(planCadence, BorderLayout.EAST);
		plan.add(target);
		plan.add(Box.createVerticalStrut(9));
		plan.add(planRow(label("Dates"), schedule));
		plan.add(Box.createVerticalStrut(4));
		plan.add(planRow(repeatCaption, repeat));
		pendingChanges.setName("detailPendingChanges");
		pendingChanges.setForeground(MUTED);
		plan.add(pendingChanges);
		return plan;
	}

	void setHistoryLoader(BiConsumer<String, java.util.function.Consumer<GoalsView.HistoryPage>> loader)
	{
		history.setHistoryLoader(loader);
	}

	void setDailyHistoryLoader(DailyPaceChart.Loader loader)
	{
		dailyChart.setLoader(loader);
	}

	private static JPanel planRow(JLabel caption, JTextArea value)
	{
		JPanel row = new JPanel(new BorderLayout(10, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		caption.setForeground(MUTED);
		caption.setVerticalAlignment(JLabel.TOP);
		caption.setPreferredSize(new Dimension(58, caption.getPreferredSize().height));
		row.add(caption, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		return row;
	}

	void render(GoalsView.Goal goal, boolean unavailable)
	{
		if (goal.equals(rendered) && this.unavailable == unavailable && renderedFormat == dateFormat)
		{
			return;
		}
		if (rendered == null || rendered.spec.trackType != goal.spec.trackType || !rendered.spec.metricId.equals(goal.spec.metricId))
		{
			goalIcon.setIcon(null);
			goalIcons.accept(goal.spec, goalIcon);
		}
		this.rendered = goal;
		this.unavailable = unavailable;
		renderedFormat = dateFormat;
		String unit = goal.spec.trackType == GoalSpec.TrackType.EXPERIENCE ? "XP" : MetricCatalogue.unit(goal.spec.metricId);
		String metric = goal.spec.trackType == GoalSpec.TrackType.EXPERIENCE ? GoalNames.skill(goal.spec)
				: MetricCatalogue.name(goal.spec.metricId);
		String cadence = cadence(goal.spec.cadence);
		name.setText(GoalNames.title(goal.spec));
		subject.setText(metric);
		goalIcon.setToolTipText(metric);
		history.render(goal);
		dailyChart.render(goal, dateFormat);
		planTarget.setText(goal.spec.mode == GoalSpec.Mode.LEVEL ? "Reach level " + goal.spec.level : format(goal.target) + " " + unit);
		planCadence.setText(cadence);
		repeatCaption.setText(goal.spec.cadence == GoalSpec.Cadence.ONCE ? "Schedule" : "Repeat");
		schedule.setRows(goal.spec.cadence == GoalSpec.Cadence.ONCE ? 2 : 1);
		repeat.setRows(!goal.uniques.isEmpty() || goal.spec.cadence == GoalSpec.Cadence.ONCE ? 2 : 1);
		schedule.setText(goal.periodStart == null ? "Starts " + dateFormat.date(LocalDate.parse(goal.spec.startDate)) : range(goal));
		schedule.setToolTipText(goal.periodStart != null && goal.spec.cadence != GoalSpec.Cadence.ONCE
				? "Runs until midnight at the start of " + dateFormat.date(LocalDate.parse(goal.periodEndExclusive)) + " (" + goal.spec.zone
						+ ")."
				: null);
		repeat.setText(!goal.uniques.isEmpty()
				? "Until selected unique totals are met (" + goal.uniquesMet + "/" + goal.uniques.size() + ")"
				: goal.spec.totalCountTarget != null ? "Until " + format(goal.spec.totalCountTarget) + " total KC"
						: goal.spec.cadence == GoalSpec.Cadence.ONCE ? "Through " + dateFormat.date(LocalDate.parse(goal.spec.endDate))
								: goal.spec.endDate == null ? "∞ · No end date"
										: "Until " + dateFormat.date(LocalDate.parse(goal.spec.endDate)));
		boolean achieved = goal.lifecycle.equals("ACHIEVED");
		result.setVisible(achieved || goal.lifecycle.equals("ENDED"));
		result.setText(achieved ? "✓ Goal achieved" : "Schedule ended");
		result.setForeground(achieved ? GREEN : MUTED);
		today.update("Today", goal.todayCredited, goal.todayAssigned, unit, goal.inToday,
				achieved ? "Goal achieved • no further work required"
						: goal.periodQuotaMet ? "Period quota complete"
								: goal.todayComplete ? "Today's assignment complete"
										: goal.inTomorrow ? "Deferred to tomorrow • progress still counts today" : null,
				"No assignment today");
		tomorrow.setVisible(goal.inTomorrow);
		tomorrow.setText(
				goal.inTomorrow
						? (goal.tomorrowAssigned == null ? "Goal ends today" : "Tomorrow: " + format(goal.tomorrowAssigned) + " " + unit) +
								"\n" + MyGoalsPanel.tomorrowHint(goal, unit, dateFormat)
						: "");
		period.update(goal.spec.cadence == GoalSpec.Cadence.ONCE ? "Goal progress" : cadence + " goal", goal.progress, goal.target, unit,
				goal.periodStart != null,
				goal.periodComplete && goal.progress >= goal.target ? "Target reached"
						: goal.spec.mode == GoalSpec.Mode.LEVEL ? "XP earned toward level " + goal.spec.level : null,
				"Tracking has not started");
		lifetime.setVisible(goal.spec.totalCountTarget != null);
		if (goal.spec.totalCountTarget != null)
		{
			lifetime.update("Lifetime KC", goal.totalCount == null ? 0 : goal.totalCount, goal.spec.totalCountTarget, "KC",
					goal.totalCount != null,
					achieved ? "Lifetime target reached" : null, "Lifetime total not yet known");
		}
		uniques.setVisible(!goal.uniques.isEmpty());
		uniqueSummary.setText("Uniques being hunted • " + goal.uniquesMet + "/" + goal.uniques.size() + " targets met");
		List<Integer> ids = goal.uniques.stream().map(t -> t.itemId).collect(Collectors.toList());
		if (!new ArrayList<>(rows.keySet()).equals(ids))
		{
			uniqueRows.removeAll();
			rows.clear();
			goal.uniques.forEach(target ->
			{
				UniqueRow row = new UniqueRow(target.itemId);
				rows.put(target.itemId, row);
				uniqueRows.add(row);
			});
		}
		goal.uniques.forEach(target -> rows.get(target.itemId).update(target));
		boolean needsVerification = goal.uniques.stream().anyMatch(t -> t.quantity == null || t.needsSync);
		verification.setVisible(!goal.compatibilityIssue.isEmpty() || unavailable || needsVerification);
		String pages = goal.uniques.stream().map(t -> UniqueCatalogue.INSTANCE.items.get(t.itemId)).filter(java.util.Objects::nonNull)
				.flatMap(t -> t.pageIds.stream()).distinct()
				.map(id -> UniqueCatalogue.INSTANCE.pages.get(id).name).collect(Collectors.joining(", "));
		verification.setText(!goal.compatibilityIssue.isEmpty() ? goal.compatibilityIssue
				: unavailable ? "Collection definitions changed. Update the plugin to verify these targets."
						: "Open your collection-log page to verify totals: " + pages
								+ ". ? means unknown; * marks a saved total needing verification.");
		pendingChanges.setVisible(goal.pendingStart != null);
		pendingChanges
				.setText(goal.pendingStart == null ? "" : "Changes scheduled for " + dateFormat.date(LocalDate.parse(goal.pendingStart)));
		revalidate();
		repaint();
	}

	private static final class ProgressBlock extends JPanel
	{
		final JLabel heading = label(""), amount = label("");
		final JProgressBar bar = bar();
		final JTextArea remaining = note("");

		ProgressBlock(String name)
		{
			setName(name);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(CARD);
			setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(65, 65, 65)),
					BorderFactory.createEmptyBorder(9, 10, 9, 10)));
			heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
			amount.setFont(amount.getFont().deriveFont(java.awt.Font.BOLD, 14f));
			amount.setName(name + "Amount");
			bar.setName(name + "Bar");
			remaining.setName(name + "Remaining");
			remaining.setForeground(MUTED);
			remaining.setRows(1);
			add(heading);
			add(Box.createVerticalStrut(4));
			add(amount);
			add(Box.createVerticalStrut(4));
			add(bar);
			add(Box.createVerticalStrut(4));
			add(remaining);
		}

		void update(String title, long current, long target, String unit, boolean known, String message, String unknown)
		{
			boolean complete = known && current >= target;
			heading.setText(title);
			amount.setText(known ? (complete ? "✓ " : "") + format(current) + " / " + format(target) + " " + unit : unknown);
			amount.setForeground(complete ? GREEN : GOLD);
			bar.setValue(!known ? 0 : target == 0 ? 1000 : (int) Math.min(1000, current * 1000 / target));
			bar.setForeground(complete ? GREEN : GOLD);
			remaining.setText(message != null ? message : !known ? "" : format(Math.max(0, target - current)) + " " + unit + " remaining");
		}
	}

	private final class UniqueRow extends JPanel
	{
		final JLabel title = label(""), amount = label("");
		final JProgressBar progress = bar();

		UniqueRow(int id)
		{
			setName("detailUnique-" + id);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setLayout(new BorderLayout(10, 0));
			setBackground(CARD);
			setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			JLabel icon = new JLabel();
			icon.setName("detailUniqueIcon-" + id);
			icon.setPreferredSize(new Dimension(36, 40));
			icon.setHorizontalAlignment(JLabel.CENTER);
			icons.accept(id, icon);
			add(icon, BorderLayout.WEST);
			JPanel values = column();
			values.setOpaque(false);
			title.setToolTipText(UniqueCatalogue.INSTANCE.itemName(id));
			amount.setName("detailUniqueAmount-" + id);
			progress.setName("detailUniqueBar-" + id);
			values.add(title);
			values.add(amount);
			values.add(Box.createVerticalStrut(4));
			values.add(progress);
			add(values, BorderLayout.CENTER);
		}

		void update(GoalsView.UniqueTarget target)
		{
			title.setText(target.name);
			title.setForeground(target.met ? GREEN : getForeground());
			amount.setText((target.quantity == null ? "?" : format(target.quantity) + (target.needsSync ? " *" : "")) + " / "
					+ format(target.target) +
					(target.met ? " • Target met"
							: target.quantity == null || target.needsSync ? ""
									: " • " + format(Math.max(0, target.target - target.quantity)) + " remaining"));
			amount.setForeground(target.met ? GREEN : target.quantity == null || target.needsSync ? MUTED : GOLD);
			progress.setValue(target.quantity == null ? 0 : (int) Math.min(1000, target.quantity * 1000 / target.target));
			progress.setForeground(target.met ? GREEN : target.needsSync ? MUTED : GOLD);
		}
	}

	private static JProgressBar bar()
	{
		JProgressBar bar = new JProgressBar(0, 1000);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setBorderPainted(false);
		bar.setPreferredSize(new Dimension(180, 9));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 9));
		return bar;
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		return panel;
	}

	private static JLabel label(String value)
	{
		JLabel label = new JLabel(value);
		label.putClientProperty("html.disable", true);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JTextArea note(String value)
	{
		JTextArea text = new JTextArea(value);
		text.setEditable(false);
		text.setFocusable(false);
		text.setOpaque(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setBorder(BorderFactory.createEmptyBorder());
		((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		return text;
	}

	private static String format(long value)
	{
		return String.format(java.util.Locale.US, "%,d", value);
	}

	private static String cadence(GoalSpec.Cadence cadence)
	{
		switch (cadence)
		{
		case DAILY:
			return "Daily";
		case WEEKLY:
			return "Weekly";
		case MONTHLY:
			return "Monthly";
		default:
			return "Custom range";
		}
	}

	private String range(GoalsView.Goal goal)
	{
		String start = dateFormat.date(LocalDate.parse(goal.periodStart));
		if (goal.spec.cadence == GoalSpec.Cadence.DAILY)
		{
			return start;
		}
		return start + " → "
				+ dateFormat.date(LocalDate.parse(goal.spec.cadence == GoalSpec.Cadence.ONCE ? goal.periodEnd : goal.periodEndExclusive)) +
				(goal.spec.cadence == GoalSpec.Cadence.ONCE ? " (inclusive)" : "");
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return new Dimension(440, 600);
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
	{
		return 18;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
	{
		return Math.max(18, visible.height - 36);
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}
}
