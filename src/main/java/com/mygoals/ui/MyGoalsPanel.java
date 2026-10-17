package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.GoalDateFormat;
import com.mygoals.engine.GoalEngine;
import java.util.stream.Collectors;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JDialog;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;

public final class MyGoalsPanel extends PluginPanel
{
	private static final Color GREEN = new Color(102, 204, 128), GOLD = new Color(230, 184, 92);
	private final SkillIconManager icons;
	private final SpriteManager sprites;
	private final ItemManager items;
	private final BiConsumer<String, Consumer<GoalEngine>> commands;
	private final Runnable retry;
	private final JTextArea status = text("Log in to load your goals"), error = text("");
	private final JButton add = new JButton("New goal"), addGroup = new JButton("New group");
	private final JPanel creationActions = new JPanel(new GridLayout(0, 1, 0, 6));
	private final JPopupMenu settingsMenu = new JPopupMenu();
	private final DataChecksMenu dataChecks = new DataChecksMenu();
	private final JCheckBoxMenuItem groupsOption = new JCheckBoxMenuItem("Enable groups");
	private final Map<GoalDateFormat, JRadioButtonMenuItem> dateChoices = new java.util.EnumMap<>(GoalDateFormat.class);
	private boolean groupsEnabled, presentationDirty;
	private GoalDateFormat dateFormat = GoalDateFormat.AMERICAN;
	private BiConsumer<Boolean, GoalDateFormat> presentationPersistence = (group, format) ->
	{
	};
	private final Clock clock;
	private final BackupControls backups = new BackupControls(() ->
	{
		closeDetails();
		closeClock();
	});

	public void setBackupBackend(BackupControls.Backend backend)
	{
		backups.setBackend(backend);
	}

	private final JPanel today = column();
	private final JScrollPane todayScroll = scroll(today);
	private boolean todayTodoCollapsed, todayCompleteCollapsed, todayTomorrowCollapsed, goalsCompleteCollapsed, otherGoalsCollapsed = true;
	private final java.util.Set<String> collapsedTodayGroups = new java.util.HashSet<>();
	private GoalsView view;
	private final Map<String, CachedCard> cards = new HashMap<>();
	private long layoutRevision;
	private JDialog detailDialog;
	private GoalDetailsPanel detailPanel;
	private String detailGoalId, detailToken;

	public interface HistoryLoader
	{
		void load(String token, String goalId, String before, Consumer<GoalsView.HistoryPage> completed);
	}

	private HistoryLoader historyLoader;

	public void setHistoryLoader(HistoryLoader loader)
	{
		historyLoader = loader;
	}

	public interface DailyHistoryLoader
	{
		void load(String token, String goalId, String start, String end, Consumer<GoalsView.HistoryPage> completed);
	}

	private DailyHistoryLoader dailyHistoryLoader;

	public void setDailyHistoryLoader(DailyHistoryLoader loader)
	{
		dailyHistoryLoader = loader;
	}

	private java.util.function.BiConsumer<String, Integer> finishedLoader;

	public void setFinishedLoader(java.util.function.BiConsumer<String, Integer> loader)
	{
		finishedLoader = loader;
	}

	private JDialog clockDialog;
	private ManageClockPanel clockPanel;

	enum CardMode
	{
		GOAL, TODAY, TOMORROW
	}

	public void setPresentationPersistence(BiConsumer<Boolean, GoalDateFormat> persistence)
	{
		presentationPersistence = persistence;
	}

	public void setPresentationPreferences(boolean grouped, GoalDateFormat format)
	{
		if (format == null)
		{
			format = GoalDateFormat.AMERICAN;
		}
		if (groupsEnabled == grouped && dateFormat == format)
		{
			return;
		}
		boolean datesChanged = dateFormat != format;
		groupsEnabled = grouped;
		dateFormat = format;
		groupsOption.setSelected(grouped);
		refreshGroupControls();
		dateChoices.forEach((choice, item) -> item.setSelected(choice == dateFormat));
		if (datesChanged)
		{
			cards.clear();
			dataChecks.setDateFormat(format);
			backups.setDateFormat(format);
			if (detailPanel != null)
			{
				detailPanel.setDateFormat(format);
			}
			if (clockPanel != null)
			{
				clockPanel.setDateFormat(format);
			}
		}
		presentationDirty = true;
		if (view != null)
		{
			render(view);
		}
	}

	private void refreshGroupControls()
	{
		creationActions.removeAll();
		creationActions.add(add);
		if (groupsEnabled)
		{
			creationActions.add(addGroup);
		}
		addGroup.setEnabled(groupsEnabled && view != null && view.editable);
		creationActions.revalidate();
		creationActions.repaint();
	}

	private void changePresentation(boolean grouped, GoalDateFormat format)
	{
		setPresentationPreferences(grouped, format);
		presentationPersistence.accept(groupsEnabled, dateFormat);
	}

	private static final class CachedCard
	{
		final GoalsView.Goal goal;
		final boolean editable, unavailable;
		final JPanel panel;

		CachedCard(GoalsView.Goal goal, GoalsView snapshot, JPanel panel)
		{
			this.goal = goal;
			this.editable = snapshot.editable;
			this.unavailable = snapshot.unavailableUniques.contains(goal.spec.metricId);
			this.panel = panel;
		}
	}

	public MyGoalsPanel(SkillIconManager icons, BiConsumer<String, Consumer<GoalEngine>> commands, Runnable retry)
	{
		this(icons, null, commands, retry, () ->
		{
		});
	}

	public MyGoalsPanel(SkillIconManager icons, SpriteManager sprites, BiConsumer<String, Consumer<GoalEngine>> commands, Runnable retry,
			Runnable checkCounts)
	{
		this(icons, sprites, null, commands, retry, checkCounts);
	}

	public MyGoalsPanel(SkillIconManager icons, SpriteManager sprites, ItemManager items, BiConsumer<String, Consumer<GoalEngine>> commands,
			Runnable retry, Runnable checkCounts)
	{
		this(icons, sprites, items, commands, retry, checkCounts, Clock.systemUTC());
	}

	public MyGoalsPanel(SkillIconManager icons, SpriteManager sprites, ItemManager items, BiConsumer<String, Consumer<GoalEngine>> commands,
			Runnable retry, Runnable checkCounts, Clock clock)
	{
		super(false);
		this.icons = icons;
		this.sprites = sprites;
		this.items = items;
		this.commands = commands;
		this.retry = retry;
		this.clock = clock;
		setLayout(new BorderLayout(0, 6));
		setBorder(BorderFactory.createEmptyBorder(8, 5, 6, 5));
		// RuneLite grows its frame to the selected panel's minimum height. Wrapped
		// text has no usable width on first open, so let our inner panes scroll
		// instead of allowing that provisional text height to resize the client.
		setMinimumSize(new Dimension(PANEL_WIDTH, 0));
		setPreferredSize(new Dimension(PANEL_WIDTH, 0));
		JPanel header = new JPanel(new BorderLayout(0, 5));
		JLabel title = label("Advanced Goal Pacer");
		title.setName("pluginTitle");
		title.setForeground(GREEN);
		JPanel heading = new JPanel(new BorderLayout());
		JButton settings = new JButton(new GearIcon());
		settings.setName("goalsSettings");
		settings.setToolTipText("Advanced Goal Pacer options");
		settings.getAccessibleContext().setAccessibleName("Advanced Goal Pacer options");
		settings.setMargin(new java.awt.Insets(4, 5, 4, 5));
		settingsMenu.setName("goalsSettingsMenu");
		settings.setComponentPopupMenu(settingsMenu);
		JMenuItem refresh = new JMenuItem("Refresh");
		refresh.setName("refreshGoals");
		refresh.setToolTipText(
				"<html>Save current progress or retry loading local goals,<br>and fetch updated HiScores counts (30-second cooldown).</html>");
		refresh.addActionListener(e ->
		{
			retry.run();
			checkCounts.run();
		});
		JMenuItem clockAction = new JMenuItem("View clock");
		clockAction.setName("manageClock");
		clockAction.setToolTipText("View your computer's local date, time, and time zone.");
		clockAction.addActionListener(e -> openClock());
		settingsMenu.add(refresh);
		settingsMenu.addSeparator();
		groupsOption.setName("groupToday");
		groupsOption.setToolTipText("Show goal groups. Hiding groups keeps their names and assignments.");
		groupsOption.addActionListener(e -> changePresentation(groupsOption.isSelected(), dateFormat));
		settingsMenu.add(groupsOption);
		JMenu dateOptions = new JMenu("Date format");
		dateOptions.setName("dateFormat");
		ButtonGroup dateSelection = new ButtonGroup();
		for (GoalDateFormat format : GoalDateFormat.values())
		{
			JRadioButtonMenuItem choice = new JRadioButtonMenuItem(format.toString(), format == dateFormat);
			choice.setName("dateFormat-" + format.name());
			choice.addActionListener(e -> changePresentation(groupsEnabled, format));
			dateSelection.add(choice);
			dateChoices.put(format, choice);
			dateOptions.add(choice);
		}
		settingsMenu.add(dateOptions);
		settingsMenu.addSeparator();
		settingsMenu.add(backups.exportItem());
		settingsMenu.add(backups.restoreItem());
		settingsMenu.addSeparator();
		settingsMenu.add(clockAction);
		settingsMenu.addSeparator();
		settingsMenu.add(dataChecks.hiscores);
		settingsMenu.add(dataChecks.collection);
		settings.addActionListener(e -> settingsMenu.show(settings,
				Math.min(0, settings.getWidth() - settingsMenu.getPreferredSize().width), settings.getHeight()));
		heading.add(title, BorderLayout.WEST);
		heading.add(settings, BorderLayout.EAST);
		header.add(heading, BorderLayout.NORTH);
		JPanel information = column();
		information.add(status);
		information.add(error);
		information.add(backups);
		header.add(information, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		add.setName("newGoal");
		addGroup.setName("newGroup");
		SidebarStyle.creation(add, false);
		SidebarStyle.creation(addGroup, true);
		creationActions.setOpaque(false);
		creationActions.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		refreshGroupControls();
		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.add(creationActions, BorderLayout.NORTH);
		content.add(todayScroll, BorderLayout.CENTER);
		add(content, BorderLayout.CENTER);
		error.setForeground(new Color(244, 140, 126));
		add.addActionListener(e ->
		{
			GoalsView opening = view;
			if (opening != null && opening.editable)
			{
				GoalEditor.open(this, opening, null, items, dateFormat, groupsEnabled,
						spec -> commands.accept(opening.token, engine -> engine.add(spec, Instant.now())), null);
			}
		});
		addGroup.addActionListener(e ->
		{
			GoalsView opening = view;
			if (opening == null || !opening.editable || !groupsEnabled)
			{
				return;
			}
			String name = JOptionPane.showInputDialog(this, "Group name", "New group", JOptionPane.PLAIN_MESSAGE);
			if (name != null)
			{
				commands.accept(opening.token, engine -> engine.addGroup(name));
			}
		});
		add.setEnabled(false);
		addGroup.setEnabled(false);
	}

	public void render(GoalsView view)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			throw new IllegalStateException("Sidebar updates belong to Swing");
		}
		GoalsView previous = this.view;
		this.view = view;
		backups.render(view);
		dataChecks.render(view);
		boolean accountChanged = previous == null || !Objects.equals(previous.token, view.token);
		if (accountChanged)
		{
			settingsMenu.setVisible(false);
		}
		if (clockDialog != null && accountChanged)
		{
			closeClock();
		}
		if (detailDialog != null)
		{
			GoalsView.Goal selected = currentGoal(detailGoalId, detailToken);
			if (accountChanged || selected == null)
			{
				closeDetails();
			}
			else
			{
				detailPanel.render(selected, view.unavailableUniques.contains(selected.spec.metricId));
			}
		}
		boolean goalsChanged = presentationDirty || accountChanged || previous.editable != view.editable
				|| !previous.groups.equals(view.groups) ||
				!previous.goals.equals(view.goals) || !previous.unavailableUniques.equals(view.unavailableUniques) ||
				previous.finishedPage != view.finishedPage || previous.loadedFinished != view.loadedFinished ||
				previous.hasOlderFinished != view.hasOlderFinished || previous.hasNewerFinished != view.hasNewerFinished ||
				!previous.message.equals(view.message);
		presentationDirty = false;
		// Ready views start with the character name; retain only any actionable notice
		// below it.
		String panelStatus = view.editable ? view.status.substring(view.status.indexOf('\n') + 1) : view.status;
		if (view.editable && !view.status.contains("\n"))
		{
			panelStatus = "";
		}
		if (!status.getText().equals(panelStatus))
		{
			status.setText(panelStatus);
		}
		status.setVisible(!panelStatus.isBlank());
		if (!error.getText().equals(view.message))
		{
			error.setText(view.message);
		}
		error.setVisible(!view.message.isBlank());
		add.setEnabled(view.editable);
		addGroup.setEnabled(view.editable && groupsEnabled);
		if (!goalsChanged)
		{
			return;
		}
		if (accountChanged)
		{
			cards.clear();
			todayTodoCollapsed = false;
			todayCompleteCollapsed = false;
			todayTomorrowCollapsed = false;
			goalsCompleteCollapsed = false;
			otherGoalsCollapsed = true;
			collapsedTodayGroups.clear();
		}
		cards.entrySet().removeIf(entry -> view.goals.stream().noneMatch(g -> entry.getValue().goal.id.equals(g.id)));
		int scroll = todayScroll.getVerticalScrollBar().getValue();
		renderToday(view);
		revalidate();
		repaint();
		long revision = ++layoutRevision;
		SwingUtilities.invokeLater(() ->
		{
			if (layoutRevision != revision)
			{
				return;
			}
			todayScroll.getVerticalScrollBar().setValue(accountChanged ? 0 : scroll);
		});
	}

	private void renderToday(GoalsView view)
	{
		today.removeAll();
		List<GoalsView.Goal> scheduled = view.goals.stream().filter(g -> g.inToday && !g.lifecycle.equals("ACHIEVED"))
				.collect(Collectors.toList());
		List<GoalsView.Goal> remaining = scheduled.stream().filter(g -> !g.todayComplete && !g.inTomorrow).collect(Collectors.toList());
		JPanel todo = column();
		todo.setName("todayTodo");
		String todoCaption = "To-Do";
		JButton todoHeading = sectionHeading((todayTodoCollapsed ? "▸ " : "▾ ") + todoCaption, SidebarStyle.TODO);
		SidebarStyle.agendaSection(todoHeading);
		todoHeading.setName("toggleTodayTodo");
		todoHeading.setHorizontalAlignment(JButton.LEFT);
		todoHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
		todoHeading.setForeground(GOLD);
		todo.add(todoHeading);
		JPanel todoBody = column();
		todoBody.setName("todayTodoBody");
		todoBody.setVisible(!todayTodoCollapsed);
		appendTodayCards(todoBody, remaining, CardMode.TODAY, "TODO", view);
		if (remaining.isEmpty())
		{
			todoBody.add(text(scheduled.isEmpty() ? "No assignments for today."
					: scheduled.stream().anyMatch(g -> g.inTomorrow) ? "Remaining work is in Tomorrow." : "All done for today."));
		}
		todo.add(todoBody);
		todoHeading.addActionListener(e ->
		{
			todayTodoCollapsed = todoBody.isVisible();
			todoBody.setVisible(!todayTodoCollapsed);
			todoHeading.setText((todayTodoCollapsed ? "▸ " : "▾ ") + todoCaption);
			todo.revalidate();
		});
		today.add(todo);
		List<GoalsView.Goal> complete = scheduled.stream().filter(g -> g.todayComplete).collect(Collectors.toList());
		if (!complete.isEmpty())
		{
			today.add(completedSection(complete, true, view));
		}
		List<GoalsView.Goal> postponed = scheduled.stream().filter(g -> g.inTomorrow).collect(Collectors.toList());
		if (!postponed.isEmpty())
		{
			JPanel section = column();
			section.setName("tomorrow");
			section.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
			JButton heading = sectionHeading((todayTomorrowCollapsed ? "▸ " : "▾ ") + "Tomorrow", SidebarStyle.TOMORROW);
			SidebarStyle.agendaSection(heading);
			heading.setName("tomorrowHeading");
			heading.setHorizontalAlignment(JButton.LEFT);
			heading.setToolTipText("Returns to Today next day. Tracking continues today.");
			heading.setForeground(GOLD);
			section.add(heading);
			JPanel body = column();
			body.setName("tomorrowBody");
			body.setVisible(!todayTomorrowCollapsed);
			appendTodayCards(body, postponed, CardMode.TOMORROW, "TOMORROW", view);
			section.add(body);
			today.add(section);
			heading.addActionListener(e ->
			{
				todayTomorrowCollapsed = body.isVisible();
				body.setVisible(!todayTomorrowCollapsed);
				heading.setText((todayTomorrowCollapsed ? "▸ " : "▾ ") + "Tomorrow");
				section.revalidate();
			});
		}
		List<GoalsView.Goal> achieved = view.goals.stream().filter(g -> g.lifecycle.equals("ACHIEVED")).collect(Collectors.toList());
		if (!achieved.isEmpty() || view.hasOlderFinished || view.hasNewerFinished)
		{
			today.add(completedSection(achieved, false, view));
		}
		List<GoalsView.Goal> other = view.goals.stream().filter(g -> !g.inToday && !g.lifecycle.equals("ACHIEVED"))
				.collect(Collectors.toList());
		if (!other.isEmpty())
		{
			JPanel section = column();
			section.setName("otherGoals");
			section.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
			JButton heading = sectionHeading((otherGoalsCollapsed ? "▸ " : "▾ ") + "Other Goals", SidebarStyle.TOMORROW);
			heading.setName("toggleOtherGoals");
			heading.setHorizontalAlignment(JButton.LEFT);
			SidebarStyle.agendaSection(heading);
			heading.setToolTipText("Future-dated goals and goals whose schedules have ended.");
			section.add(heading);
			JPanel body = column();
			body.setName("otherGoalsBody");
			body.setVisible(!otherGoalsCollapsed);
			appendTodayCards(body, other, CardMode.GOAL, "OTHER", view);
			section.add(body);
			today.add(section);
			heading.addActionListener(e ->
			{
				otherGoalsCollapsed = body.isVisible();
				body.setVisible(!otherGoalsCollapsed);
				heading.setText((otherGoalsCollapsed ? "▸ " : "▾ ") + "Other Goals");
				section.revalidate();
			});
		}
	}

	private void appendTodayCards(JPanel parent, List<GoalsView.Goal> entries, CardMode mode, String sectionId, GoalsView snapshot)
	{
		if (!groupsEnabled)
		{
			entries.forEach(goal -> parent.add(card(goal, mode, snapshot)));
			return;
		}
		for (GoalsView.Group group : snapshot.groups)
		{
			List<GoalsView.Goal> members = entries.stream().filter(goal -> Objects.equals(goal.spec.groupId, group.id))
					.collect(Collectors.toList());
			if (members.isEmpty())
			{
				continue;
			}
			String key = sectionId + "-" + group.id;
			boolean collapsed = collapsedTodayGroups.contains(key);
			JPanel section = column();
			section.setName("todayGroup-" + key);
			String caption = group.name;
			JButton heading = customGroupHeading((collapsed ? "▸ " : "▾ ") + caption);
			SidebarStyle.smallText(heading, 1f);
			heading.putClientProperty("html.disable", true);
			heading.setAlignmentX(Component.LEFT_ALIGNMENT);
			heading.setHorizontalAlignment(JButton.LEFT);
			heading.setName("toggleTodayGroup-" + key);
			JPanel headingRow = new JPanel(new BorderLayout(3, 0));
			headingRow.setOpaque(false);
			headingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			JButton menu = new JButton("⋮");
			menu.setName("groupActions-" + key);
			menu.setEnabled(snapshot.editable);
			menu.setContentAreaFilled(false);
			menu.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
			menu.setToolTipText("Group options");
			menu.addActionListener(e -> groupMenu(group, snapshot).show(menu, 0, menu.getHeight()));
			headingRow.add(heading, BorderLayout.CENTER);
			headingRow.add(menu, BorderLayout.EAST);
			JPanel body = column();
			body.setVisible(!collapsed);
			section.add(headingRow);
			section.add(body);
			heading.addActionListener(e ->
			{
				if (view == null || !Objects.equals(view.token, snapshot.token))
				{
					return;
				}
				boolean hide = body.isVisible();
				body.setVisible(!hide);
				if (hide)
				{
					collapsedTodayGroups.add(key);
				}
				else
				{
					collapsedTodayGroups.remove(key);
				}
				heading.setText((hide ? "▸ " : "▾ ") + caption);
				section.revalidate();
			});
			members.forEach(goal -> body.add(card(goal, mode, snapshot)));
			parent.add(section);
		}
		List<GoalsView.Goal> ungrouped = entries.stream().filter(goal -> goal.spec.groupId == null ||
				snapshot.groups.stream().noneMatch(group -> group.id.equals(goal.spec.groupId))).collect(Collectors.toList());
		if (!ungrouped.isEmpty() && !snapshot.groups.isEmpty())
		{
			parent.add(label("Ungrouped"));
		}
		ungrouped.forEach(goal -> parent.add(card(goal, mode, snapshot)));
	}

	private JPanel completedSection(List<GoalsView.Goal> complete, boolean daily, GoalsView snapshot)
	{
		JPanel section = column();
		section.setName(daily ? "todayComplete" : "goalsComplete");
		section.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		boolean collapsed = daily ? todayCompleteCollapsed : goalsCompleteCollapsed;
		String caption = daily ? "On Pace" : "Completed Goals";
		JButton heading = sectionHeading((collapsed ? "▸ " : "▾ ") + caption, SidebarStyle.COMPLETE);
		SidebarStyle.agendaSection(heading);
		heading.setName(daily ? "toggleTodayComplete" : "toggleGoalsComplete");
		heading.setHorizontalAlignment(JButton.LEFT);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		heading.setForeground(GREEN);
		section.add(heading);
		JPanel body = column();
		body.setVisible(!collapsed);
		if (daily)
		{
			appendTodayCards(body, complete, CardMode.TODAY, "ON_PACE", snapshot);
		}
		else
		{
			appendTodayCards(body, complete, CardMode.GOAL, "COMPLETED", snapshot);
		}
		section.add(body);
		if (!daily && finishedLoader != null)
		{
			JPanel pages = new JPanel(new java.awt.GridLayout(1, 0, 4, 0));
			pages.setOpaque(false);
			pages.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (snapshot.hasNewerFinished)
			{
				JButton newer = new JButton("Newer");
				newer.setName("newerFinishedGoals");
				newer.setToolTipText("Load newer completed goals");
				newer.addActionListener(e -> finishedLoader.accept(snapshot.token, snapshot.finishedPage - 1));
				pages.add(newer);
			}
			if (snapshot.hasOlderFinished)
			{
				JButton older = new JButton(
						snapshot.loadedFinished == 0 && snapshot.finishedPage == 0 ? "Load completed history" : "Older");
				older.setToolTipText("Load up to 50 older completed goals");
				older.setName("olderFinishedGoals");
				older.addActionListener(
						e -> finishedLoader.accept(snapshot.token, snapshot.finishedPage + (snapshot.loadedFinished > 0 ? 1 : 0)));
				pages.add(older);
			}
			body.add(pages);
		}
		heading.addActionListener(e ->
		{
			boolean hide = body.isVisible();
			body.setVisible(!hide);
			if (daily)
			{
				todayCompleteCollapsed = hide;
			}
			else
			{
				goalsCompleteCollapsed = hide;
			}
			heading.setText((hide ? "▸ " : "▾ ") + caption);
			section.revalidate();
		});
		return section;
	}

	private JPanel card(GoalsView.Goal goal, CardMode mode, GoalsView snapshot)
	{
		String key = goal.id + ":" + mode;
		CachedCard cached = cards.get(key);
		if (cached != null && cached.goal.equals(goal) && cached.editable == snapshot.editable &&
				cached.unavailable == snapshot.unavailableUniques.contains(goal.spec.metricId))
		{
			return cached.panel;
		}
		JPanel panel = createCard(goal, mode, snapshot);
		cards.put(key, new CachedCard(goal, snapshot, panel));
		return panel;
	}

	private GoalsView.Goal currentGoal(String id, String token)
	{
		if (view == null || !Objects.equals(view.token, token))
		{
			return null;
		}
		return view.goals.stream().filter(g -> g.id.equals(id)).findFirst().orElse(null);
	}

	private JPanel createCard(GoalsView.Goal goal, CardMode mode, GoalsView snapshot)
	{
		boolean daily = mode != CardMode.GOAL, tomorrow = mode == CardMode.TOMORROW;
		JPanel card = column();
		card.setOpaque(false);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(57, 57, 57)), BorderFactory.createEmptyBorder(10, 0, 10, 0)));
		card.setName((tomorrow ? "tomorrow-" : daily ? "today-" : "goal-") + goal.id);
		String displayName = GoalNames.title(goal.spec);
		JButton title = new JButton(displayName);
		title.setName("cardTitle-" + mode + "-" + goal.id);
		title.setToolTipText(displayName);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.putClientProperty("html.disable", true);
		title.setHorizontalAlignment(JButton.LEFT);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		title.setContentAreaFilled(false);
		title.setMinimumSize(new Dimension(0, title.getPreferredSize().height));
		JLabel icon = label("");
		icon.setName("cardIcon-" + mode + "-" + goal.id);
		icon.setPreferredSize(new Dimension(27, 28));
		icon.setVerticalAlignment(JLabel.CENTER);
		icon.setToolTipText(displayName);
		GoalIcons.bind(goal.spec, icon, icons, sprites);
		Runnable openDetails = () ->
		{
			GoalsView.Goal latest = currentGoal(goal.id, snapshot.token);
			if (latest != null)
			{
				details(latest);
			}
		};
		title.addActionListener(e -> openDetails.run());
		JPanel heading = new JPanel(new BorderLayout(3, 0));
		heading.setOpaque(false);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton handle = new JButton("⋮");
		handle.setName("cardActions-" + mode + "-" + goal.id);
		handle.getAccessibleContext().setAccessibleName("Actions for " + displayName);
		handle.setMargin(new java.awt.Insets(0, 6, 2, 6));
		handle.setEnabled(snapshot.editable);
		handle.setContentAreaFilled(false);
		handle.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
		handle.setToolTipText("Edit, reorder, or defer this goal");
		handle.addActionListener(e -> cardMenu(goal.id, snapshot.token, mode).show(handle, 0, handle.getHeight()));
		heading.add(icon, BorderLayout.WEST);
		heading.add(title, BorderLayout.CENTER);
		heading.add(handle, BorderLayout.EAST);
		card.add(heading);
		card.add(new TodayCardBody(goal, tomorrow, snapshot.unavailableUniques.contains(goal.spec.metricId),
				(id, label) -> GoalIcons.bindItem(id, label, items), !daily, dateFormat));
		makeDetailsClickable(card, openDetails);
		JPanel padded = new JPanel(new BorderLayout());
		padded.setAlignmentX(Component.LEFT_ALIGNMENT);
		padded.setOpaque(false);
		padded.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		padded.add(card);
		return padded;
	}

	static String tomorrowHint(GoalsView.Goal goal, String unit)
	{
		return tomorrowHint(goal, unit, GoalDateFormat.AMERICAN);
	}

	static String tomorrowHint(GoalsView.Goal goal, String unit, GoalDateFormat dates)
	{
		if (goal.tomorrowAssigned == null)
		{
			return "No days remain before the deadline. Return to Today to finish.";
		}
		return (goal.tomorrowNewPeriod ? "New period\n" : "") + format(goal.tomorrowRemaining) + " " + unit +
				" remaining\n" + goal.tomorrowDays + (goal.tomorrowDays == 1 ? " day" : " days") + " from "
				+ dates.date(LocalDate.parse(goal.tomorrowDate));
	}

	private void postpone(String id, String token, boolean postponed)
	{
		GoalsView.Goal latest = currentGoal(id, token);
		if (latest != null && view.editable && latest.inToday && !latest.todayComplete)
		{
			commands.accept(token, engine -> engine.postponeGoal(id, postponed, Instant.now()));
		}
	}

	/**
	 * Reordering stays within the visible section and preserves group membership.
	 */
	private boolean sameSection(GoalsView.Goal first, GoalsView.Goal second, CardMode mode)
	{
		if (groupsEnabled && !Objects.equals(first.spec.groupId, second.spec.groupId))
		{
			return false;
		}
		if (mode == CardMode.GOAL)
		{
			return (!first.inToday || first.lifecycle.equals("ACHIEVED")) &&
					(!second.inToday || second.lifecycle.equals("ACHIEVED"))
					&& first.lifecycle.equals("ACHIEVED") == second.lifecycle.equals("ACHIEVED");
		}
		if (first.lifecycle.equals("ACHIEVED") || second.lifecycle.equals("ACHIEVED"))
		{
			return false;
		}
		if (mode == CardMode.TOMORROW)
		{
			return first.inTomorrow && second.inTomorrow;
		}
		return first.inToday && second.inToday && !first.inTomorrow && !second.inTomorrow && first.todayComplete == second.todayComplete;
	}

	JPopupMenu cardMenu(String id, String token, CardMode mode)
	{
		JPopupMenu menu = new JPopupMenu();
		GoalsView.Goal latest = currentGoal(id, token);
		if (latest == null || !view.editable)
		{
			return menu;
		}
		JMenuItem edit = menu.add("Edit");
		edit.setName("editGoal-" + id);
		edit.addActionListener(e -> editGoal(id, token));
		if (latest.archived)
		{
			return menu;
		}
		menu.addSeparator();
		List<GoalsView.Goal> peers = view.goals.stream().filter(g -> !g.archived && sameSection(latest, g, mode))
				.collect(Collectors.toList());
		int index = peers.indexOf(latest);
		javax.swing.JMenuItem up = menu.add("Move up"), down = menu.add("Move down");
		up.setEnabled(index > 0);
		down.setEnabled(index >= 0 && index < peers.size() - 1);
		if (up.isEnabled())
		{
			up.addActionListener(e -> moveCard(id, peers.get(index - 1).id, false, token, mode));
		}
		if (down.isEnabled())
		{
			down.addActionListener(e -> moveCard(id, peers.get(index + 1).id, true, token, mode));
		}
		if (mode != CardMode.GOAL && latest.inToday && !latest.todayComplete && !latest.lifecycle.equals("ACHIEVED"))
		{
			menu.addSeparator();
			menu.add(latest.inTomorrow ? "Return to Today" : "Defer to tomorrow")
					.addActionListener(e -> postpone(id, token, !latest.inTomorrow));
		}
		return menu;
	}

	private void editGoal(String id, String token)
	{
		GoalsView opening = view;
		GoalsView.Goal latest = currentGoal(id, token);
		if (latest == null || !opening.editable)
		{
			return;
		}
		GoalEditor.open(this, opening, latest, items, dateFormat, groupsEnabled,
				spec -> commands.accept(opening.token, engine -> engine.edit(latest.id, spec, Instant.now())),
				() ->
				{
					if (currentGoal(id, token) != null && view.editable)
					{
						commands.accept(token, engine -> engine.remove(id));
					}
				});
	}

	boolean moveCard(String id, String target, boolean after, String token, CardMode mode)
	{
		GoalsView.Goal source = currentGoal(id, token), destination = currentGoal(target, token);
		if (source == null || destination == null || source.archived || destination.archived || id.equals(target) || !view.editable
				|| !sameSection(source, destination, mode))
		{
			return false;
		}
		commands.accept(token, engine -> engine.reorderGoal(id, target, after));
		return true;
	}

	private JPopupMenu groupMenu(GoalsView.Group group, GoalsView snapshot)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.add("Rename").addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this, "Group name", group.name);
			if (name != null)
			{
				commands.accept(snapshot.token, engine -> engine.renameGroup(group.id, name));
			}
		});
		menu.add("Move up").addActionListener(e -> commands.accept(snapshot.token, engine -> engine.reorderGroup(group.id, -1)));
		menu.add("Move down").addActionListener(e -> commands.accept(snapshot.token, engine -> engine.reorderGroup(group.id, 1)));
		menu.add("Remove group; keep goals")
				.addActionListener(e -> commands.accept(snapshot.token, engine -> engine.removeGroup(group.id)));
		return menu;
	}

	private void details(GoalsView.Goal goal)
	{
		if (detailDialog != null && detailDialog.isDisplayable() && goal.id.equals(detailGoalId))
		{
			detailDialog.toFront();
			return;
		}
		closeDetails();
		GoalDetailsPanel panel = new GoalDetailsPanel((id, label) ->
		{
			if (items != null)
			{
				var image = items.getImage(id);
				if (image != null)
				{
					image.addTo(label);
				}
			}
		}, (spec, label) -> GoalIcons.bind(spec, label, icons, sprites));
		panel.setDateFormat(dateFormat);
		panel.render(goal, view.unavailableUniques.contains(goal.spec.metricId));
		String openingToken = view.token;
		if (dailyHistoryLoader != null)
		{
			panel.setDailyHistoryLoader((start, end, completed) -> dailyHistoryLoader.load(openingToken, goal.id, start, end, page ->
			{
				if (detailPanel == panel && Objects.equals(detailToken, openingToken))
				{
					completed.accept(page);
				}
			}));
		}
		if (historyLoader != null)
		{
			panel.setHistoryLoader((before, completed) -> historyLoader.load(openingToken, goal.id, before, page ->
			{
				if (detailPanel == panel && Objects.equals(detailToken, openingToken))
				{
					completed.accept(page);
				}
			}));
		}
		JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Goal details", Dialog.ModalityType.MODELESS);
		dialog.setName("goalDetailsDialog");
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.add(detailScroll(panel));
		int available = getGraphicsConfiguration() == null ? 680 : getGraphicsConfiguration().getBounds().height - 100;
		dialog.setMinimumSize(new Dimension(410, 480));
		dialog.setSize(480, Math.min(680, Math.max(480, available)));
		detailDialog = dialog;
		detailPanel = panel;
		detailGoalId = goal.id;
		detailToken = view.token;
		dialog.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent event)
			{
				if (detailDialog == dialog)
				{
					detailDialog = null;
					detailPanel = null;
					detailGoalId = null;
					detailToken = null;
				}
			}
		});
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	static JScrollPane detailContents(GoalsView.Goal goal)
	{
		return detailContents(goal, (spec, label) -> GoalIcons.bind(spec, label, new SkillIconManager(), null));
	}

	static JScrollPane detailContents(GoalsView.Goal goal, BiConsumer<GoalSpec, JLabel> icons)
	{
		GoalDetailsPanel panel = new GoalDetailsPanel((id, label) ->
		{
		}, icons);
		panel.render(goal, false);
		return detailScroll(panel);
	}

	private static JScrollPane detailScroll(GoalDetailsPanel panel)
	{
		JScrollPane scroll = new JScrollPane(panel);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(18);
		scroll.setPreferredSize(new Dimension(460, 620));
		return scroll;
	}

	public void closeDetails()
	{
		JDialog dialog = detailDialog;
		detailDialog = null;
		detailPanel = null;
		detailGoalId = null;
		detailToken = null;
		if (dialog != null)
		{
			dialog.dispose();
		}
	}

	private void openClock()
	{
		if (clockDialog != null)
		{
			clockDialog.toFront();
			return;
		}
		ManageClockPanel panel = new ManageClockPanel(clock, this::closeClock);
		panel.setDateFormat(dateFormat);
		JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "View clock", Dialog.ModalityType.MODELESS);
		dialog.setName("manageClockDialog");
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.add(panel);
		dialog.setMinimumSize(new Dimension(400, 280));
		dialog.setSize(450, 310);
		clockDialog = dialog;
		clockPanel = panel;
		dialog.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent event)
			{
				panel.close();
				if (clockDialog == dialog)
				{
					clockDialog = null;
					clockPanel = null;
				}
			}
		});
		dialog.setLocationRelativeTo(this);
		panel.start();
		dialog.setVisible(true);
	}

	private void closeClock()
	{
		JDialog dialog = clockDialog;
		ManageClockPanel panel = clockPanel;
		clockDialog = null;
		clockPanel = null;
		if (panel != null)
		{
			panel.close();
		}
		if (dialog != null)
		{
			dialog.dispose();
		}
	}

	public void closeDialogs()
	{
		settingsMenu.setVisible(false);
		closeDetails();
		closeClock();
	}

	private static void makeDetailsClickable(Component component, Runnable open)
	{
		if (component instanceof AbstractButton)
		{
			return;
		}
		component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1)
				{
					open.run();
				}
			}
		});
		if (component instanceof java.awt.Container)
		{
			for (Component child : ((java.awt.Container) component).getComponents())
			{
				makeDetailsClickable(child, open);
			}
		}
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	private static JScrollPane scroll(JPanel content)
	{
		JPanel north = new ScrollBody(content);
		JScrollPane pane = new JScrollPane(north);
		pane.setBorder(null);
		pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		pane.getVerticalScrollBar().setUnitIncrement(16);
		return pane;
	}

	private static final class ScrollBody extends JPanel implements Scrollable
	{
		ScrollBody(JPanel content)
		{
			super(new BorderLayout());
			add(content, BorderLayout.NORTH);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return Math.max(16, visible.height - 16);
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

	private static JLabel label(String value)
	{
		JLabel label = new JLabel(value);
		label.putClientProperty("html.disable", true);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JButton sectionHeading(String title, javax.swing.Icon icon)
	{
		JButton heading = groupHeading(title);
		heading.setIcon(icon);
		heading.setIconTextGap(6);
		heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
		return heading;
	}

	private static JButton groupHeading(String title)
	{
		return new JButton(title)
		{
			@Override
			public Dimension getMinimumSize()
			{
				return new Dimension(0, super.getMinimumSize().height);
			}
		};
	}

	private static JButton customGroupHeading(String title)
	{
		JButton heading = groupHeading(title);
		SidebarStyle.group(heading);
		return heading;
	}

	private static JTextArea text(String value)
	{
		JTextArea text = new JTextArea(value);
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setOpaque(false);
		text.setFocusable(false);
		((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		return text;
	}

	private static String format(long amount)
	{
		return String.format(java.util.Locale.US, "%,d", amount);
	}

}
