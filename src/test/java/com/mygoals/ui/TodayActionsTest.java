package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class TodayActionsTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private final Instant now = Instant.now();
    private GoalSpec spec(String name, String group)
    {
        return new GoalSpec(name, group, "MINING", GoalSpec.Mode.GAIN, 700, 0, GoalSpec.Cadence.WEEKLY, now.atZone(zone).toLocalDate(), null, zone);
    }
    private GoalEngine engine()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("b".repeat(64)), zone);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now); return engine;
    }
    private GoalsView view(GoalEngine engine, String token, boolean editable, Instant when)
    {
        return new GoalsView(token, "Saved", "", editable, engine.state(), when);
    }
    private static Component find(Container parent, String name)
    {
        for (Component child : parent.getComponents())
        {
            if (name.equals(child.getName())) { return child; }
            if (child instanceof Container) { Component found = find((Container) child, name); if (found != null) { return found; } }
        }
        return null;
    }
    private static boolean contains(Container parent, String text)
    {
        for (Component child : parent.getComponents())
        {
            if (child instanceof JLabel && ((JLabel) child).getText().contains(text)) { return true; }
            if (child instanceof JTextArea && ((JTextArea) child).getText().contains(text)) { return true; }
            if (child instanceof Container && contains((Container) child, text)) { return true; }
        }
        return false;
    }
    static JMenuItem action(JPopupMenu menu, String text)
    {
        for (Component item : menu.getComponents())
        { if (item instanceof JMenuItem && text.equals(((JMenuItem) item).getText())) { return (JMenuItem) item; } }
        throw new AssertionError("Missing card action: " + text);
    }
    @Test public void postponeMenuMovesBelowCompleteUpdatesForecastAndCanBeUndoneWithoutLosingCredit() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String id = engine.add(spec("Mining", null), now);
            engine.add(new GoalSpec("Done", null, "MINING", GoalSpec.Mode.GAIN, 5, 0, GoalSpec.Cadence.DAILY, now.atZone(zone).toLocalDate(), null, zone), now);
            engine.observe(Map.of("MINING", 100_010L), GoalEngine.LIVE, now);
            MyGoalsPanel panel = new MyGoalsPanel(null, (token, command) -> command.accept(engine), () -> {});
            panel.render(view(engine, "one", true, now));
            assertNull(find(panel, "postpone-" + id));
            assertEquals("Today's Pace", ((JLabel) find((Container) find(panel, "today-" + id), "todayCardProgressLabel")).getText());
            action(panel.cardMenu(id, "one", MyGoalsPanel.CardMode.TODAY), "Defer to tomorrow").doClick(); panel.render(view(engine, "one", true, now));
            Container tomorrow = (Container) find(panel, "tomorrow"), complete = (Container) find(panel, "todayComplete");
            assertNotNull(tomorrow); assertNull(find(panel, "today-" + id)); assertNotNull(find(tomorrow, "tomorrow-" + id));
            assertTrue(tomorrow.getParent().getComponentZOrder(tomorrow) > complete.getParent().getComponentZOrder(complete));
            assertEquals("0 / 115 XP", ((JLabel) find(tomorrow, "todayCardProgress")).getText()); assertTrue(contains(tomorrow, "Today: 10 / 100 XP"));
            assertEquals("Tomorrow's Pace", ((JLabel) find(tomorrow, "todayCardProgressLabel")).getText());
            JButton tomorrowHeading = (JButton) find(tomorrow, "tomorrowHeading");
            assertTrue(tomorrowHeading.getToolTipText().contains("Tracking continues today"));
            tomorrowHeading.doClick(); assertFalse(find(tomorrow, "tomorrowBody").isVisible());
            GoalDetailsPanel details = new GoalDetailsPanel((item, label) -> {}); details.render(view(engine, "one", true, now).goals.get(0), false);
            assertTrue(((JTextArea) find(details, "detailTomorrow")).getText().contains("Tomorrow: 115 XP"));
            engine.observe(Map.of("MINING", 100_050L), GoalEngine.LIVE, now); panel.render(view(engine, "one", true, now));
            assertEquals("0 / 109 XP", ((JLabel) find((Container) find(panel, "tomorrow"), "todayCardProgress")).getText());
            assertFalse(find(panel, "tomorrowBody").isVisible()); ((JButton) find(panel, "tomorrowHeading")).doClick();
            assertNull(find(panel, "postpone-" + id));
            action(panel.cardMenu(id, "one", MyGoalsPanel.CardMode.TOMORROW), "Return to Today").doClick(); panel.render(view(engine, "one", true, now));
            assertNull(find(panel, "tomorrow")); assertNotNull(find(panel, "today-" + id));
            assertEquals(50, engine.today(engine.state().goals.get(id), now).credited);
            details.render(view(engine, "one", true, now).goals.get(0), false); assertFalse(find(details, "detailTomorrow").isVisible());
        });
    }

    @Test public void menuUsesLatestOrderPreservesGroupsAndRejectsStaleOrCrossSectionActions() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String group = engine.addGroup("Skills");
            String one = engine.add(spec("One", group), now), two = engine.add(spec("Two", group), now), three = engine.add(spec("Three", null), now);
            MyGoalsPanel panel = new MyGoalsPanel(null, (token, command) -> command.accept(engine), () -> {}); panel.render(view(engine, "one", true, now));
            Component original = find(panel, "today-" + two);
            JPopupMenu menu = panel.cardMenu(two, "one", MyGoalsPanel.CardMode.TODAY); action(menu, "Move up").doClick();
            assertEquals("Edit", ((JMenuItem) menu.getComponent(0)).getText());
            panel.render(view(engine, "one", true, now)); assertSame(original, find(panel, "today-" + two));
            assertEquals(List.of(two, one, three), new java.util.ArrayList<>(engine.state().goals.keySet()));
            assertFalse(action(panel.cardMenu(two, "one", MyGoalsPanel.CardMode.TODAY), "Move up").isEnabled());
            assertTrue(panel.moveCard(three, two, false, "one", MyGoalsPanel.CardMode.TODAY));
            panel.render(view(engine, "one", true, now)); assertEquals(group, engine.state().goals.get(two).spec.groupId);
            assertFalse(panel.moveCard(three, two, false, "one", MyGoalsPanel.CardMode.GOAL));
            panel.setPresentationPreferences(true, com.mygoals.domain.GoalDateFormat.AMERICAN);
            assertFalse(panel.moveCard(three, two, false, "one", MyGoalsPanel.CardMode.GOAL));
            engine.postponeGoal(three, true, now); panel.render(view(engine, "one", true, now));
            assertFalse(panel.moveCard(three, two, false, "one", MyGoalsPanel.CardMode.TODAY));
            panel.render(view(engine, "new-account", true, now)); assertFalse(panel.moveCard(one, two, true, "one", MyGoalsPanel.CardMode.GOAL));
            panel.render(view(engine, "new-account", false, now)); assertFalse(panel.moveCard(one, two, true, "new-account", MyGoalsPanel.CardMode.GOAL));
            JButton handle = (JButton) find(panel, "cardActions-TODAY-" + one); assertFalse(handle.isEnabled()); assertNull(handle.getTransferHandler());
            assertEquals(List.of(three, two, one), new java.util.ArrayList<>(engine.state().goals.keySet()));
        });
    }

    @Test public void tomorrowReturnsToTodayAtMidnightAndCompletionTakesPrecedence() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String id = engine.add(spec("Mining", null), now);
            MyGoalsPanel panel = new MyGoalsPanel(null, (token, command) -> {}, () -> {});
            engine.postponeGoal(id, true, now); panel.render(view(engine, "one", true, now)); assertNotNull(find(panel, "tomorrow-" + id));
            Instant next = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant(); engine.tick(next);
            panel.render(view(engine, "one", true, next)); assertNull(find(panel, "tomorrow")); assertNotNull(find(panel, "today-" + id));
            engine.postponeGoal(id, true, next); engine.observe(Map.of("MINING", 100_117L), GoalEngine.LIVE, next);
            panel.render(view(engine, "one", true, next)); assertNull(find(panel, "tomorrow"));
            assertNotNull(find((Container) find(panel, "todayComplete"), "today-" + id));
            Container completed = (Container) find(panel, "today-" + id);
            assertEquals("Target Pace", ((JLabel) find(completed, "targetCardProgressLabel")).getText());
            assertEquals("117 / 700 XP", ((JLabel) find(completed, "targetCardProgress")).getText());
            assertEquals(GoalCardStyle.GREEN, find(completed, "todayCardProgressBar").getForeground());
        });
    }
}
