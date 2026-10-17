package com.mygoals.ui;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalSpec;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultCaret;

/**
 * Stable-height item rows. Editing a quantity never rebuilds or scrolls the
 * form.
 */
final class UniqueSelectionPanel extends JPanel
{
	private static final Color MUTED = new Color(170, 170, 170), GREEN = new Color(102, 204, 128);
	private final GoalsView view;
	private final GoalSpec initial;
	private final BiConsumer<Integer, JLabel> icons;
	private final Map<Integer, JCheckBox> checks = new LinkedHashMap<>();
	private final Map<Integer, JSpinner> totals = new LinkedHashMap<>();
	private final JLabel selectedCount = new JLabel();
	private final JTextArea completionNotice = note("", 3);
	private String metric;

	UniqueSelectionPanel(GoalsView view, GoalSpec initial)
	{
		this(view, initial, (id, label) ->
		{
		});
	}

	UniqueSelectionPanel(GoalsView view, GoalSpec initial, BiConsumer<Integer, JLabel> icons)
	{
		this.view = view;
		this.initial = initial;
		this.icons = icons;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		setName("uniqueSelection");
	}

	void metric(String metric)
	{
		if (java.util.Objects.equals(this.metric, metric))
		{
			return;
		}
		this.metric = metric;
		removeAll();
		checks.clear();
		totals.clear();
		var boss = UniqueCatalogue.INSTANCE.bosses.get(metric);
		if (boss == null)
		{
			revalidate();
			return;
		}
		JLabel heading = new JLabel("Unique targets");
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(heading);
		JTextArea help = note("Choose items and the collection-log totals you want to reach. Every selected target must be met.", 2);
		help.setToolTipText(
				"Totals include items already logged and qualifying gains from other sources that share the same collection-log item.");
		add(help);
		completionNotice.setName("uniqueCompletionNotice");
		add(completionNotice);
		boolean needsRefresh = boss.itemIds.stream().anyMatch(
				id -> view.collection.get(id) == null || view.collection.get(id).quantity == null || view.collection.get(id).needsSync);
		if (needsRefresh)
		{
			String pages = boss.pageIds.stream().map(id -> UniqueCatalogue.INSTANCE.pages.get(id).name).distinct()
					.collect(java.util.stream.Collectors.joining(", "));
			JTextArea banner = note(
					"Refresh collection log\nOpen your own " + pages
							+ " page to verify totals. ? means unknown; * means a saved total needs verification.",
					boss.pageIds.size() > 1 ? 4 : 3);
			banner.setName("collectionRefresh");
			banner.setOpaque(true);
			banner.setBackground(new Color(65, 56, 35));
			banner.setForeground(new Color(235, 207, 143));
			banner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(112, 93, 51)),
					BorderFactory.createEmptyBorder(7, 8, 7, 8)));
			add(banner);
		}
		JPanel actions = new JPanel(new BorderLayout());
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		selectedCount.setForeground(MUTED);
		actions.add(selectedCount, BorderLayout.WEST);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
		JButton all = new JButton("Select all"), clear = new JButton("Clear");
		all.setName("selectAllUniques");
		clear.setName("clearUniques");
		all.addActionListener(e -> checks.values().forEach(c -> c.setSelected(true)));
		clear.addActionListener(e -> checks.values().forEach(c -> c.setSelected(false)));
		buttons.add(all);
		buttons.add(clear);
		actions.add(buttons, BorderLayout.EAST);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		add(actions);
		for (Integer id : boss.itemIds)
		{
			var item = UniqueCatalogue.INSTANCE.items.get(id);
			var owned = view.collection.get(id);
			Long quantity = owned == null ? null : owned.quantity;
			boolean uncertain = owned == null || owned.needsSync;
			long selected = initial != null && initial.metricId.equals(metric) ? initial.uniqueTargets.getOrDefault(id, 0L) : 0;
			long target = selected > 0 ? selected : quantity == null ? 1 : Math.min(item.targetLimit, Math.max(1, quantity + 1));
			JCheckBox check = new JCheckBox();
			check.setSelected(selected > 0);
			check.setName("unique-" + id);
			check.getAccessibleContext().setAccessibleName("Select " + item.name);
			check.setOpaque(false);
			JLabel icon = new JLabel();
			icon.setName("unique-icon-" + id);
			icon.setHorizontalAlignment(JLabel.CENTER);
			icon.setPreferredSize(new Dimension(36, 36));
			icon.setMinimumSize(new Dimension(36, 36));
			icon.setMaximumSize(new Dimension(36, 36));
			icon.setToolTipText(item.name);
			icons.accept(id, icon);
			JPanel leading = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			leading.setOpaque(false);
			leading.add(check);
			leading.add(icon);
			JLabel name = new JLabel(item.name);
			name.putClientProperty("html.disable", true);
			name.setToolTipText(item.name);
			JLabel status = new JLabel();
			status.setName("unique-status-" + id);
			JPanel description = new JPanel();
			description.setOpaque(false);
			description.setLayout(new BoxLayout(description, BoxLayout.Y_AXIS));
			description.setMinimumSize(new Dimension(0, 40));
			description.add(name);
			description.add(status);
			JSpinner total = new JSpinner(
					new SpinnerNumberModel(Long.valueOf(target), Long.valueOf(1), Long.valueOf(item.targetLimit), Long.valueOf(1)));
			total.setName("unique-total-" + id);
			total.setEditor(new JSpinner.NumberEditor(total, "#,##0"));
			total.getAccessibleContext().setAccessibleName(item.name + " target total");
			javax.swing.text.NumberFormatter formatter = (javax.swing.text.NumberFormatter) ((JSpinner.NumberEditor) total.getEditor())
					.getTextField().getFormatter();
			formatter.setValueClass(Long.class);
			formatter.setMinimum(1L);
			formatter.setMaximum((long) item.targetLimit);
			total.setToolTipText("Desired collection-log total (1–" + String.format(java.util.Locale.US, "%,d", item.targetLimit) + ").");
			JPanel targetColumn = new JPanel(new BorderLayout(0, 2));
			targetColumn.setOpaque(false);
			JLabel targetHeading = new JLabel("Target total");
			targetHeading.setForeground(MUTED);
			targetHeading.setFont(targetHeading.getFont().deriveFont(10f));
			targetColumn.add(targetHeading, BorderLayout.NORTH);
			targetColumn.add(total, BorderLayout.CENTER);
			targetColumn.setPreferredSize(new Dimension(100, 42));
			JPanel row = new JPanel(new BorderLayout(8, 0));
			row.setName("unique-row-" + id);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(62, 62, 62)),
					BorderFactory.createEmptyBorder(6, 1, 6, 1)));
			row.add(leading, BorderLayout.WEST);
			row.add(description, BorderLayout.CENTER);
			row.add(targetColumn, BorderLayout.EAST);
			row.setMinimumSize(new Dimension(0, 56));
			row.setPreferredSize(new Dimension(450, 56));
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
			Runnable update = () ->
			{
				total.setEnabled(check.isSelected());
				boolean met = check.isSelected() && quantity != null && !uncertain && quantity >= ((Number) total.getValue()).longValue();
				status.setText(quantity == null ? "Owned: ?"
						: "Owned: " + String.format(java.util.Locale.US, "%,d", quantity) + (uncertain ? " *" : met ? "  Target met" : ""));
				status.setForeground(met ? GREEN : MUTED);
				updateSummary();
				row.repaint();
				row.setBackground(check.isSelected() ? new Color(43, 52, 45) : new Color(45, 45, 45));
			};
			check.addItemListener(e -> update.run());
			total.addChangeListener(e -> update.run());
			checks.put(id, check);
			totals.put(id, total);
			add(row);
			update.run();
		}
		updateSummary();
		revalidate();
	}

	private void updateSummary()
	{
		long selected = checks.values().stream().filter(JCheckBox::isSelected).count();
		selectedCount.setText(selected + " selected");
		boolean allMet = selected > 0 && checks.entrySet().stream().filter(e -> e.getValue().isSelected()).allMatch(e ->
		{
			var owned = view.collection.get(e.getKey());
			return owned != null && owned.quantity != null && !owned.needsSync
					&& owned.quantity >= ((Number) totals.get(e.getKey()).getValue()).longValue();
		});
		completionNotice.setText(allMet
				? "Already complete: selected totals are owned. Saving ends this goal immediately. Increase a target to keep tracking."
				: "Tracking stops as soon as every selected total is reached, even if the period's kill target is unfinished.");
		completionNotice.setForeground(allMet ? new Color(235, 207, 143) : MUTED);
	}

	private static JTextArea note(String value, int rows)
	{
		JTextArea text = new JTextArea(value, rows, 0);
		text.setEditable(false);
		text.setFocusable(false);
		((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setOpaque(false);
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		return text;
	}

	Map<Integer, Long> targets() throws java.text.ParseException
	{
		Map<Integer, Long> selected = new LinkedHashMap<>();
		for (var entry : checks.entrySet())
		{
			if (!entry.getValue().isSelected())
			{
				continue;
			}
			JSpinner total = totals.get(entry.getKey());
			String input = ((JSpinner.NumberEditor) total.getEditor()).getTextField().getText().trim();
			if (!input.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)"))
			{
				throw new IllegalArgumentException("Use a whole-number target total");
			}
			total.commitEdit();
			Number value = (Number) total.getValue();
			if (value.doubleValue() != value.longValue())
			{
				throw new IllegalArgumentException("Use a whole-number target total");
			}
			selected.put(entry.getKey(), value.longValue());
		}
		if (selected.isEmpty())
		{
			throw new IllegalArgumentException("Select at least one unique");
		}
		return selected;
	}
}
