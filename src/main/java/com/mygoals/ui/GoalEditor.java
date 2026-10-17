package com.mygoals.ui;

import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.GoalSpec;
import com.mygoals.engine.CalendarService;
import com.mygoals.tracking.MetricCatalogue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;

import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;

final class GoalEditor
{
	static void open(Component parent, GoalsView view, GoalsView.Goal existing, ItemManager items, GoalDateFormat dateFormat,
			boolean groupsEnabled,
			Consumer<GoalSpec> submit, Runnable delete)
	{
		JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), existing == null ? "New goal" : "Edit goal",
				Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setName("goalEditorDialog");
		BooleanSupplier confirmDelete = delete == null ? null : () ->
		{
			if (JOptionPane.showConfirmDialog(dialog, "Permanently delete this goal and all its history?", "Delete goal",
					JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
			{
				return false;
			}
			delete.run();
			return true;
		};
		dialog.add(createForm(view, existing, submit, dialog::dispose, (id, label) ->
		{
			if (items != null)
			{
				var image = items.getImage(id);
				if (image != null)
				{
					image.addTo(label);
				}
			}
		}, dateFormat, confirmDelete, groupsEnabled));
		int available = parent.getGraphicsConfiguration() == null ? 820 : parent.getGraphicsConfiguration().getBounds().height - 100;
		dialog.setMinimumSize(new Dimension(480, 480));
		dialog.setSize(new Dimension(560, Math.min(820, Math.max(480, available))));
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}

	static JPanel createForm(GoalsView view, GoalsView.Goal existing, Consumer<GoalSpec> submit, Runnable close)
	{
		return createForm(view, existing, submit, close, (id, label) ->
		{
		});
	}

	static JPanel createForm(GoalsView view, GoalsView.Goal existing, Consumer<GoalSpec> submit, Runnable close,
			BiConsumer<Integer, JLabel> icons)
	{
		return createForm(view, existing, submit, close, icons, GoalDateFormat.AMERICAN, null);
	}

	static JPanel createForm(GoalsView view, GoalsView.Goal existing, Consumer<GoalSpec> submit, Runnable close,
			BiConsumer<Integer, JLabel> icons, GoalDateFormat dateFormat, BooleanSupplier delete)
	{
		return createForm(view, existing, submit, close, icons, dateFormat, delete, true);
	}

	static JPanel createForm(GoalsView view, GoalsView.Goal existing, Consumer<GoalSpec> submit, Runnable close,
			BiConsumer<Integer, JLabel> icons, GoalDateFormat dateFormat, BooleanSupplier delete, boolean groupsEnabled)
	{
		JPanel form = new EditorBody();
		form.setName("goalForm");
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		if (existing != null && !existing.compatibilityIssue.isEmpty())
		{
			form.add(text(existing.spec.name));
			JTextArea warning = text(existing.compatibilityIssue
					+ " Update the plugin before editing this goal. You can still view its saved progress and history.");
			warning.setName("goalCompatibilityWarning");
			form.add(warning);
			JButton cancel = new JButton("Close");
			cancel.setName("closeGoalEditor");
			cancel.addActionListener(e -> close.run());
			form.add(cancel);
			if (delete != null)
			{
				JButton remove = new JButton("Delete goal");
				remove.setName("deleteGoal");
				remove.setEnabled(view.editable);
				remove.addActionListener(e ->
				{
					if (delete.getAsBoolean())
					{
						close.run();
					}
				});
				form.add(remove);
			}
			return form;
		}
		GoalSpec initial = existing == null ? null : existing.editSpec;
		JComboBox<String> trackType = new JComboBox<>(new String[] { "Experience", "Count" });
		trackType.setName("trackType");
		trackType.setSelectedIndex(initial != null && initial.trackType == GoalSpec.TrackType.COUNT ? 1 : 0);
		trackType.setEnabled(existing == null);
		JTextField name = new JTextField(initial == null ? "" : GoalNames.title(initial));
		name.setName("goalName");
		JComboBox<GoalsView.Group> group = new JComboBox<>();
		group.setName("goalGroup");
		group.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus)
			{
				super.getListCellRendererComponent(list, value, index, selected, focus);
				putClientProperty("html.disable", true);
				return this;
			}
		});
		group.addItem(new GoalsView.Group(null, "Ungrouped", false));
		view.groups.forEach(group::addItem);
		for (int i = 0; initial != null && i < group.getItemCount(); i++)
		{
			if (java.util.Objects.equals(initial.groupId, group.getItemAt(i).id))
			{
				group.setSelectedIndex(i);
			}
		}
		JComboBox<Skill> skill = new JComboBox<>(Arrays.stream(Skill.values()).filter(s -> s != Skill.OVERALL)
				.sorted(Comparator.comparing(Skill::getName)).toArray(Skill[]::new));
		skill.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus)
			{
				super.getListCellRendererComponent(list, value, index, selected, focus);
				setText(value == null ? "" : ((Skill) value).getName());
				return this;
			}
		});
		skill.setSelectedItem(
				initial == null || initial.trackType == GoalSpec.TrackType.COUNT ? Skill.MINING : Skill.valueOf(initial.metricId));
		skill.setEnabled(existing == null);
		ActivityPicker metric = new ActivityPicker(
				initial != null && initial.trackType == GoalSpec.TrackType.COUNT ? HiscoreSkill.valueOf(initial.metricId)
						: HiscoreSkill.THE_CORRUPTED_GAUNTLET);
		metric.setEnabled(existing == null);
		JPanel countRow = section("Activity or boss", metric);
		JPanel skillRow = section("Skill", skill);
		JComboBox<String> mode = new JComboBox<>(new String[] { "Gain XP", "Reach a level" });
		mode.setName("targetMode");
		mode.setSelectedIndex(initial != null && initial.mode == GoalSpec.Mode.LEVEL ? 1 : 0);
		mode.setEnabled(existing == null);
		JPanel modeRow = section("Target mode", mode);
		JPanel skillAndMode = pair(skillRow, modeRow);
		JTextField amount = new JTextField(initial == null ? "10000" : Long.toString(initial.amount));
		amount.setName("goalAmount");
		JSpinner level = new JSpinner(new SpinnerNumberModel(initial == null ? 60 : Math.max(2, initial.level), 2, 99, 1));
		level.setName("targetLevel");
		JPanel amountRow = section("XP amount", amount), levelRow = section("Target level", level);
		JLabel amountHeading = (JLabel) amountRow.getComponent(0);
		JComboBox<String> cadence = new JComboBox<>(new String[] { "Daily", "Weekly", "Monthly", "Custom date range" });
		cadence.setName("cadence");
		cadence.setSelectedIndex(initial == null ? 1 : initial.cadence.ordinal());
		ZoneId savedZone = initial == null ? ZoneId.systemDefault() : ZoneId.of(initial.zone);
		LocalDate today = LocalDate.now(savedZone);
		CalendarPicker start = new CalendarPicker(initial == null ? today : LocalDate.parse(initial.startDate), dateFormat);
		CalendarPicker end = new CalendarPicker(
				initial == null || initial.endDate == null ? today.plusDays(13) : LocalDate.parse(initial.endDate), dateFormat);
		start.setName("startDate");
		end.setName("endDate");
		JCheckBox hasEnd = new JCheckBox("Set an end date", initial != null && initial.endDate != null);
		JComboBox<String> repeat = new JComboBox<>(new String[] { "Forever (∞)", "Until end date", "Until unique", "Until KC" });
		repeat.setName("repeat");
		repeat.setSelectedIndex(initial != null && initial.totalCountTarget != null ? 3
				: initial != null && !initial.uniqueTargets.isEmpty() ? 2 : hasEnd.isSelected() ? 1 : 0);
		JPanel repeatRow = section("Repeat", repeat);
		JTextField totalKc = new JTextField(
				initial == null || initial.totalCountTarget == null ? "1000" : initial.totalCountTarget.toString());
		totalKc.setName("totalKcTarget");
		JTextArea kcHelp = text("");
		kcHelp.setName("totalKcHelp");
		JPanel kcTargetRow = section("Lifetime KC target", totalKc);
		kcTargetRow.add(kcHelp);
		UniqueSelectionPanel uniqueSelection = new UniqueSelectionPanel(view, initial, icons);
		JSpinner days = new JSpinner(
				new SpinnerNumberModel((int) Math.max(1, ChronoUnit.DAYS.between(start.getDate(), end.getDate()) + 1), 1, 365_000, 1));
		JPanel endRow = section("End date (inclusive)", end), daysRow = section("Calendar days", days);
		JPanel endAndDays = pair(endRow, daysRow);
		if (existing != null && existing.trackingStartedAt != 0)
		{
			start.setEnabled(false);
			if (existing.spec.cadence == GoalSpec.Cadence.ONCE)
			{
				cadence.setEnabled(false);
			}
		}
		JTextArea help = text(existing == null ? "Tracking begins when this goal activates. Past dates do not add earlier progress."
				: existing.spec.cadence == GoalSpec.Cadence.ONCE
						? "The deadline and target update now. Today recalculates and keeps your earned progress."
						: "Targets and end conditions update now, and Today recalculates with your progress kept. Cadence changes begin next period.");
		JTextArea preview = text("");
		preview.setName("goalPreview");
		JTextArea source = text("");
		JTextArea error = text("");
		error.setName("goalError");
		error.setVisible(false);
		error.setForeground(new java.awt.Color(245, 130, 115));
		boolean[] syncing = { false };
		boolean[] rebuilding = { false };
		String[] lastCountMetric = { initial != null && initial.trackType == GoalSpec.TrackType.COUNT ? initial.metricId : null };
		Runnable changed = () ->
		{
			if (rebuilding[0])
			{
				return;
			}
			boolean isCount = trackType.getSelectedIndex() == 1;
			boolean isLevel = !isCount && mode.getSelectedIndex() == 1;
			skillAndMode.setVisible(!isCount);
			countRow.setVisible(isCount);
			HiscoreSkill selectedMetric = metric.selected();
			boolean canUseUnique = isCount && selectedMetric != null && UniqueCatalogue.INSTANCE.bosses.containsKey(selectedMetric.name());
			boolean canUseKc = isCount && selectedMetric != null && selectedMetric.getType() == HiscoreSkillType.BOSS;
			rebuilding[0] = true;
			String selection = (String) repeat.getSelectedItem();
			java.util.List<String> choices = new java.util.ArrayList<>(java.util.List.of("Forever (∞)", "Until end date"));
			if (canUseUnique)
			{
				choices.add("Until unique");
			}
			if (canUseKc)
			{
				choices.add("Until KC");
			}
			if (repeat.getItemCount() != choices.size())
			{
				repeat.setModel(new DefaultComboBoxModel<>(choices.toArray(new String[0])));
			}
			if (initial == null && canUseUnique && !selectedMetric.name().equals(lastCountMetric[0]))
			{
				selection = "Until unique";
			}
			repeat.setSelectedItem(choices.contains(selection) ? selection : "Forever (∞)");
			lastCountMetric[0] = isCount && selectedMetric != null ? selectedMetric.name() : null;
			rebuilding[0] = false;
			uniqueSelection.metric(canUseUnique ? selectedMetric.name() : null);
			String unit = isCount && selectedMetric != null ? MetricCatalogue.unit(selectedMetric) : "XP";
			amountHeading.setText(isCount
					? (selectedMetric != null && MetricCatalogue.bestScore(selectedMetric) ? "Best score improvement (" : "Amount (") + unit
							+ ")"
					: "XP amount");
			source.setVisible(isCount);
			source.setText(isCount && selectedMetric != null ? MetricCatalogue.trackingHelp(selectedMetric) : "");
			amount.setEnabled(!isLevel);
			level.setEnabled(isLevel);
			amountRow.setVisible(!isLevel);
			levelRow.setVisible(isLevel);
			if (isLevel)
			{
				if (cadence.getSelectedIndex() != 3)
				{
					cadence.setSelectedIndex(3);
				}
				hasEnd.setSelected(true);
			}
			cadence.setEnabled(!isLevel && !(existing != null && existing.spec.cadence == GoalSpec.Cadence.ONCE));
			if (cadence.getSelectedIndex() == 3)
			{
				hasEnd.setSelected(true);
			}
			boolean repeating = !isLevel && cadence.getSelectedIndex() != 3;
			repeatRow.setVisible(repeating);
			if (repeating)
			{
				hasEnd.setSelected("Until end date".equals(repeat.getSelectedItem()));
			}
			uniqueSelection.setVisible(repeating && "Until unique".equals(repeat.getSelectedItem()));
			boolean untilKc = repeating && "Until KC".equals(repeat.getSelectedItem());
			kcTargetRow.setVisible(untilKc);
			Long known = isCount && selectedMetric != null ? view.counts.get(selectedMetric.name()) : null;
			String kcMessage = "Current total: " + (known == null ? "unknown" : String.format(java.util.Locale.US, "%,d", known)) +
					". Ends when your lifetime total reaches the target.";
			try
			{
				if (known != null && known >= Long.parseLong(totalKc.getText().trim()))
				{
					kcMessage = "Already reached: saving ends this goal immediately. Increase the total to keep tracking.";
				}
			}
			catch (NumberFormatException ignored)
			{
			}
			kcHelp.setText(kcMessage);
			hasEnd.setVisible(false);
			hasEnd.setEnabled(!isLevel && cadence.getSelectedIndex() != 3);
			end.setEnabled(hasEnd.isSelected());
			days.setEnabled(hasEnd.isSelected());
			endRow.setVisible(hasEnd.isSelected());
			daysRow.setVisible(hasEnd.isSelected());
			endAndDays.setVisible(hasEnd.isSelected());
			form.revalidate();
			try
			{
				long target = isLevel
						? Math.max(0,
								GoalSpec.xpForLevel((Integer) level.getValue())
										- view.xp.getOrDefault(((Skill) skill.getSelectedItem()).name(), 0L))
						: Long.parseLong(amount.getText().trim());
				if (existing != null && !isLevel && cadence.getSelectedIndex() == existing.spec.cadence.ordinal())
				{
					target = Math.max(0, target - existing.progress);
				}
				GoalSpec candidate = new GoalSpec("Preview", null, isCount ? GoalSpec.TrackType.COUNT : GoalSpec.TrackType.EXPERIENCE,
						isCount ? selectedMetric.name() : ((Skill) skill.getSelectedItem()).name(),
						isLevel ? GoalSpec.Mode.LEVEL : GoalSpec.Mode.GAIN, isLevel ? 0 : Long.parseLong(amount.getText().trim()),
						(Integer) level.getValue(),
						GoalSpec.Cadence.values()[cadence.getSelectedIndex()], start.getDate(), hasEnd.isSelected() ? end.getDate() : null,
						savedZone);
				Instant when = Instant.now();
				if (when.isBefore(start.getDate().atStartOfDay(ZoneId.of(candidate.zone)).toInstant()))
				{
					when = start.getDate().atStartOfDay(ZoneId.of(candidate.zone)).toInstant();
				}
				CalendarService.Window window = new CalendarService().current(candidate, when);
				if (window == null)
				{
					preview.setText("This schedule has already ended.");
				}
				else
				{
					long remainingDays = ChronoUnit.DAYS.between(when.atZone(window.zone).toLocalDate(), window.endExclusive);
					preview.setText(String.format(java.util.Locale.US,
							"%,d %s remaining • about %,d %s/day\nCalendar period: %s through %s", target, unit,
							CalendarService.share(target, remainingDays), unit, dateFormat.date(window.start),
							dateFormat.date(window.endExclusive.minusDays(1))));
				}
			}
			catch (RuntimeException ex)
			{
				preview.setText("Choose a valid amount and calendar range to preview the pace.");
			}
		};
		mode.addActionListener(e -> changed.run());
		repeat.addActionListener(e -> changed.run());
		trackType.addActionListener(e ->
		{
			if (existing == null && trackType.getSelectedIndex() == 1 && amount.getText().equals("10000"))
			{
				amount.setText("10");
			}
			changed.run();
		});
		metric.onChange(changed);
		cadence.addActionListener(e -> changed.run());
		skill.addActionListener(e -> changed.run());
		level.addChangeListener(e -> changed.run());
		hasEnd.addActionListener(e -> changed.run());
		amount.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}
		});
		totalKc.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}
		});
		Runnable datesChanged = () ->
		{
			if (!syncing[0])
			{
				syncing[0] = true;
				days.setValue((int) Math.max(1, Math.min(365_000, ChronoUnit.DAYS.between(start.getDate(), end.getDate()) + 1)));
				syncing[0] = false;
			}
			changed.run();
		};
		start.onChange(datesChanged);
		end.onChange(datesChanged);
		days.addChangeListener(e ->
		{
			if (syncing[0])
			{
				return;
			}
			syncing[0] = true;
			end.setDate(start.getDate().plusDays((Integer) days.getValue() - 1));
			syncing[0] = false;
			changed.run();
		});
		row(form, "Goal name (optional)", name);
		form.add(groupsEnabled ? pair(section("Track type", trackType), section("Group", group)) : section("Track type", trackType));
		form.add(skillAndMode);
		form.add(countRow);
		form.add(amountRow);
		form.add(levelRow);
		form.add(pair(section("Cadence", cadence), repeatRow));
		form.add(section(existing != null && existing.spec.cadence != GoalSpec.Cadence.ONCE ? "Tracking start (preserved)" : "Start date",
				start));
		form.add(endAndDays);
		form.add(preview);
		form.add(kcTargetRow);
		form.add(uniqueSelection);
		JPanel explanations = new JPanel();
		explanations.setLayout(new BoxLayout(explanations, BoxLayout.Y_AXIS));
		explanations.setAlignmentX(Component.LEFT_ALIGNMENT);
		explanations.add(help);
		explanations.add(source);
		explanations.setVisible(false);
		JButton details = new JButton("▸ Tracking details");
		details.setName("trackingDetails");
		details.setAlignmentX(Component.LEFT_ALIGNMENT);
		details.setContentAreaFilled(false);
		details.setBorder(BorderFactory.createEmptyBorder(5, 0, 8, 0));
		details.addActionListener(e ->
		{
			explanations.setVisible(!explanations.isVisible());
			details.setText(explanations.isVisible() ? "▾ Tracking details" : "▸ Tracking details");
			form.revalidate();
		});
		form.add(details);
		form.add(explanations);
		JPanel actions = new JPanel();
		JButton save = new JButton(existing == null ? "Create goal" : "Save changes"), cancel = new JButton("Cancel");
		boolean canEdit = view.editable
				&& (existing == null || !existing.lifecycle.equals("ACHIEVED") && !existing.lifecycle.equals("ENDED"));
		save.setName("saveGoal");
		save.setEnabled(canEdit);
		save.setVisible(canEdit);
		if (!canEdit)
		{
			cancel.setText("Close");
		}
		save.addActionListener(e ->
		{
			if (!canEdit)
			{
				return;
			}
			try
			{
				level.commitEdit();
				days.commitEdit();
				boolean isCount = trackType.getSelectedIndex() == 1, isLevel = !isCount && mode.getSelectedIndex() == 1;
				Skill selectedSkill = (Skill) skill.getSelectedItem();
				HiscoreSkill selectedMetric = metric.selected();
				if (isCount && selectedMetric == null)
				{
					throw new IllegalArgumentException("Choose a HiScores activity");
				}
				if (isCount && selectedMetric == HiscoreSkill.LEAGUE_POINTS)
				{
					throw new IllegalArgumentException("League Points requires seasonal profile support in a later build");
				}
				boolean untilUnique = isCount && cadence.getSelectedIndex() != 3 && "Until unique".equals(repeat.getSelectedItem());
				boolean untilKc = isCount && cadence.getSelectedIndex() != 3 && "Until KC".equals(repeat.getSelectedItem());
				if (untilUnique && view.unavailableUniques.contains(selectedMetric.name()))
				{
					throw new IllegalArgumentException("Collection definitions changed; update the plugin before using these targets");
				}
				GoalSpec spec = new GoalSpec(
						name.getText().isBlank()
								? isCount ? selectedMetric.getName() : isLevel ? selectedSkill.getName() : selectedSkill.getName() + " XP"
								: name.getText(),
						((GoalsView.Group) group.getSelectedItem()).id, isCount ? GoalSpec.TrackType.COUNT : GoalSpec.TrackType.EXPERIENCE,
						isCount ? selectedMetric.name() : selectedSkill.name(), isLevel ? GoalSpec.Mode.LEVEL : GoalSpec.Mode.GAIN,
						isLevel ? 0 : Long.parseLong(amount.getText().trim()), (Integer) level.getValue(),
						GoalSpec.Cadence.values()[cadence.getSelectedIndex()],
						start.getDate(), hasEnd.isSelected() ? end.getDate() : null, savedZone,
						untilUnique ? uniqueSelection.targets() : Map.of(),
						untilKc ? Long.valueOf(totalKc.getText().trim()) : null);
				submit.accept(spec);
				close.run();
			}
			catch (Exception ex)
			{
				error.setText(ex instanceof NumberFormatException ? "Enter a whole amount without commas or decimals."
						: ex instanceof java.text.ParseException ? "Enter a whole number within the field's allowed range."
								: ex.getMessage());
				error.setVisible(true);
				error.getParent().revalidate();
			}
		});
		cancel.setName("closeGoalEditor");
		cancel.addActionListener(e -> close.run());
		actions.add(save);
		actions.add(cancel);
		changed.run();
		if (!canEdit)
		{
			disableInputs(form);
		}
		JPanel contents = new JPanel(new BorderLayout());
		JScrollPane scroll = new JScrollPane(form);
		scroll.setName("goalFormScroll");
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(18);
		JPanel footer = new JPanel(new BorderLayout());
		footer.setName("editorFooter");
		footer.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
		JPanel actionBar = new JPanel(new BorderLayout());
		actionBar.add(actions, BorderLayout.EAST);
		if (existing != null && delete != null)
		{
			JButton remove = new JButton("Delete goal");
			remove.setName("deleteGoal");
			remove.setEnabled(view.editable);
			remove.addActionListener(e ->
			{
				if (delete.getAsBoolean())
				{
					close.run();
				}
			});
			actionBar.add(remove, BorderLayout.WEST);
		}
		footer.add(error, BorderLayout.CENTER);
		footer.add(actionBar, BorderLayout.SOUTH);
		contents.add(scroll, BorderLayout.CENTER);
		contents.add(footer, BorderLayout.SOUTH);
		return contents;
	}

	private static void disableInputs(Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if ((child instanceof AbstractButton && !"trackingDetails".equals(child.getName())) || child instanceof JComboBox
					|| child instanceof JTextField || child instanceof JSpinner)
			{
				child.setEnabled(false);
			}
			if (child instanceof Container)
			{
				disableInputs((Container) child);
			}
		}
	}

	private static void row(JPanel form, String label, Component input)
	{
		JLabel heading = new JLabel(label);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		form.add(heading);
		if (input instanceof javax.swing.JComponent)
		{
			((javax.swing.JComponent) input).setAlignmentX(Component.LEFT_ALIGNMENT);
			input.setMinimumSize(new Dimension(0, Math.max(24, input.getPreferredSize().height)));
			input.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(24, input.getPreferredSize().height)));
		}
		form.add(input);
		form.add(javax.swing.Box.createVerticalStrut(7));
	}

	private static JPanel section(String label, Component input)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		row(section, label, input);
		return section;
	}

	private static JPanel pair(JPanel left, JPanel right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 12, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(left);
		row.add(right);
		row.setMinimumSize(new Dimension(0, row.getPreferredSize().height));
		return row;
	}

	private static JTextArea text(String value)
	{
		JTextArea text = new JTextArea(value);
		text.setEditable(false);
		text.setOpaque(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setRows(2);
		text.setFocusable(false);
		((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		return text;
	}

	private static final class EditorBody extends JPanel implements Scrollable
	{
		public Dimension getPreferredScrollableViewportSize()
		{
			return new Dimension(530, 680);
		}

		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 18;
		}

		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return Math.max(18, visible.height - 36);
		}

		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
