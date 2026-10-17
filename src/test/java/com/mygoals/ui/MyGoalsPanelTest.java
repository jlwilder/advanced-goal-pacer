package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class MyGoalsPanelTest
{
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    private GoalSpec brutus(Map<Integer, Long> uniques)
    {
        return new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, uniques);
    }
    private GoalsView view(GoalEngine engine, String status, Instant when) { return new GoalsView("test", status, "", true, engine.state(), when); }
    private MyGoalsPanel panel() { return new MyGoalsPanel(null, (token, command) -> {}, () -> {}); }

    @Test public void sidebarFitsExistingGameHeightBeforeFirstLayoutAndScrollsLongGoalLists() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            MyGoalsPanel panel = panel();
            assertFitsFixedGameHeight(panel);
            GoalEngine engine = engine();
            engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            for (int index = 0; index < 30; index++)
            {
                engine.add(new GoalSpec(index == 0 ? "A".repeat(100) : "Mining goal " + index, null, "MINING", GoalSpec.Mode.GAIN, 100, 0,
                    GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-16"), null, zone), now);
            }
            panel.render(view(engine, "Character", now));
            assertNull(find(panel, c -> c instanceof JTextArea && visible(c) && "Character".equals(((JTextArea) c).getText())));
            panel.render(view(engine, "Character\nSave failed: storage unavailable", now));
            assertNotNull(find(panel, c -> c instanceof JTextArea && visible(c) && "Save failed: storage unavailable".equals(((JTextArea) c).getText())));
            panel.render(view(engine, "Character", now));
            assertFitsFixedGameHeight(panel);
            panel.setSize(panel.getPreferredSize().width, 503);
            for (int pass = 0; pass < 4; pass++) { layout(panel); }
            assertFitsFixedGameHeight(panel);
            javax.swing.JScrollPane today = (javax.swing.JScrollPane) find(panel, c -> c instanceof javax.swing.JScrollPane && visible(c));
            assertTrue("Today keeps a usable viewport", today.getViewport().getHeight() > 0);
            assertEquals("Long Today titles must not widen the viewport", today.getViewport().getWidth(), today.getViewport().getView().getWidth());
            Component longTitle = find((Container) today.getViewport().getView(), c -> c instanceof javax.swing.JButton && "A".repeat(100).equals(((javax.swing.JButton) c).getText()));
            assertTrue("Long titles must fit inside their card", longTitle.getParent().getWidth() <= today.getViewport().getWidth());
            assertTrue("Goals scroll inside the sidebar", today.getViewport().getView().getPreferredSize().height > today.getViewport().getHeight());
            assertTrue(visible(find(panel, c -> "newGoal".equals(c.getName()))));
            assertNull(find(panel, c -> "manageGoalsTab".equals(c.getName())));
        });
    }

    private static void assertFitsFixedGameHeight(MyGoalsPanel panel)
    {
        assertTrue("Sidebar minimum height must fit the game: " + panel.getWrappedPanel().getMinimumSize(), panel.getWrappedPanel().getMinimumSize().height <= 503);
        assertTrue("Sidebar preferred height must fit the game: " + panel.getWrappedPanel().getPreferredSize(), panel.getWrappedPanel().getPreferredSize().height <= 503);
        assertEquals(net.runelite.client.ui.PluginPanel.PANEL_WIDTH + net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH, panel.getWrappedPanel().getPreferredSize().width);
    }

    private static void layout(Container parent)
    {
        parent.doLayout();
        for (Component child : parent.getComponents()) { if (child instanceof Container) { layout((Container) child); } }
    }

    @Test public void idleTicksSaveStatusAndOtherGoalsKeepUnchangedCardComponents() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine();
            String id = engine.add(brutus(Map.of()), now);
            engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.DAILY,
                LocalDate.parse("2026-10-16"), null, zone), now);
            MyGoalsPanel panel = panel(); panel.render(view(engine, "Saved", now));
            Component card = find(panel, c -> ("today-" + id).equals(c.getName()));
            for (int tick = 0; tick < 40; tick++)
            {
                GoalsView next = view(engine, "Saved", now.plusSeconds(tick));
                assertEquals(view(engine, "Saved", now), next);
                assertEquals(view(engine, "Saved", now).hashCode(), next.hashCode());
                panel.render(next); assertSame(card, find(panel, c -> ("today-" + id).equals(c.getName())));
            }
            panel.render(view(engine, "Saving locally…", now));
            panel.render(view(engine, "Saved locally", now));
            assertSame(card, find(panel, c -> ("today-" + id).equals(c.getName())));
            engine.observe(Map.of("MINING", 130L), GoalEngine.LIVE, now);
            panel.render(view(engine, "Saved locally", now));
            assertSame(card, find(panel, c -> ("today-" + id).equals(c.getName())));
            engine.observeCount("BRUTUS", 65, true, GoalEngine.LIVE, null, now);
            panel.render(view(engine, "Saved locally", now));
            assertNotSame(card, find(panel, c -> ("today-" + id).equals(c.getName())));
            assertNotNull(find(panel, c -> c instanceof JLabel && "1 / 10 KC".equals(((JLabel) c).getText())));
            panel.render(new GoalsView("different-account", "Saved", "", true, null, now));
            assertNull(find(panel, c -> ("today-" + id).equals(c.getName())));
        });
    }

    @Test public void ownedUniqueEndsGoalWithoutFalselyCompletingKillQuota() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine();
            engine.collectionSnapshot(6413, UniqueGoalTest.page(6413, Map.of(33101, 2L)), false, now);
            String id = engine.add(brutus(Map.of(33101, 1L)), now);
            engine.observeCount("BRUTUS", 65, true, GoalEngine.LIVE, null, now);
            assertEquals("ACHIEVED", engine.state().goals.get(id).lifecycle);
            assertEquals(0, engine.state().goals.get(id).current.progress());
            MyGoalsPanel panel = panel(); panel.render(view(engine, "Saved", now));
            Container card = (Container) find(panel, c -> ("goal-" + id).equals(c.getName()));
            assertNotNull(find(card, c -> c instanceof JLabel && "✓ Goal achieved".equals(((JLabel) c).getText())));
            assertNotNull(find(card, c -> c instanceof JLabel && "Target Pace".equals(((JLabel) c).getText())));
            assertTrue(TodayActionsTest.action(panel.cardMenu(id, "test", MyGoalsPanel.CardMode.GOAL), "Edit").isEnabled());
            assertNotNull(find(card, c -> c instanceof JLabel && "0 / 10 KC".equals(((JLabel) c).getText())));
            assertNull(find(card, c -> c instanceof JLabel && "✓ 0 / 10 KC".equals(((JLabel) c).getText())));
            assertEquals(0, ((JProgressBar) find(card, c -> c instanceof JProgressBar)).getValue());
            assertTrue(view(engine, "Saved", now).goals.get(0).inToday);
            panel.render(view(engine, "Saved", now.plusSeconds(86400)));
            assertNull(find(panel, c -> ("today-" + id).equals(c.getName())));
            assertNotNull(find(panel, c -> ("goal-" + id).equals(c.getName())));
            // A higher total keeps a newly created goal active and new kills count normally.
            engine.remove(id);
            String next = engine.add(brutus(Map.of(33101, 3L)), now);
            engine.observeCount("BRUTUS", 66, true, GoalEngine.LIVE, null, now);
            assertEquals("ACTIVE", engine.state().goals.get(next).lifecycle);
            assertEquals(1, engine.state().goals.get(next).current.progress());
        });
    }

    @Test public void uniqueEditorWarnsImmediatelyWhenEverySelectedTotalIsAlreadyOwned() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine();
            engine.collectionSnapshot(6413, UniqueGoalTest.page(6413, Map.of(33101, 2L)), false, now);
            UniqueSelectionPanel selection = new UniqueSelectionPanel(view(engine, "Saved", now), null);
            selection.metric("BRUTUS");
            javax.swing.JCheckBox check = (javax.swing.JCheckBox) find(selection, c -> "unique-33101".equals(c.getName()));
            javax.swing.JSpinner total = (javax.swing.JSpinner) find(selection, c -> "unique-total-33101".equals(c.getName()));
            JTextArea notice = (JTextArea) find(selection, c -> "uniqueCompletionNotice".equals(c.getName()));
            check.doClick(); assertEquals(3L, total.getValue());
            assertFalse(notice.getText().contains("Already complete"));
            total.setValue(1L); assertTrue(notice.getText().contains("Saving ends this goal immediately"));
            total.setValue(3L); assertFalse(notice.getText().contains("Already complete"));
            check.doClick(); assertFalse(notice.getText().contains("Already complete"));
        });
    }

    @Test public void oneViewSeparatesDailyPaceFromOverallCompletionAndKeepsCompletedGoalsAcrossDays() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine();
            engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            String mining = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.DAILY,
                LocalDate.parse("2026-10-16"), null, zone), now);
            String boss = engine.add(brutus(Map.of()), now);
            String overall = engine.add(new GoalSpec("Overall", null, "MINING", GoalSpec.Mode.GAIN, 5, 0, GoalSpec.Cadence.ONCE,
                LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-22"), zone), now);
            String deferred = engine.add(new GoalSpec("Later", null, "MINING", GoalSpec.Mode.GAIN, 700, 0, GoalSpec.Cadence.WEEKLY,
                LocalDate.parse("2026-10-16"), null, zone), now);
            engine.postponeGoal(deferred, true, now);
            MyGoalsPanel panel = panel(); panel.render(view(engine, "Saved", now));
            assertNull(find(panel, c -> "todayTab".equals(c.getName()) || "manageGoalsTab".equals(c.getName())));
            assertTrue(visible(find(panel, c -> "newGoal".equals(c.getName()))));
            assertNull(find(panel, c -> c instanceof JTextArea && ((JTextArea) c).getText().contains("Development build")));
            Container todo = (Container) find(panel, c -> "todayTodo".equals(c.getName()));
            assertNotNull(find(todo, c -> ("today-" + mining).equals(c.getName())));
            assertNotNull(find(todo, c -> ("today-" + boss).equals(c.getName())));
            javax.swing.JButton todoToggle = (javax.swing.JButton) find(todo, c -> "toggleTodayTodo".equals(c.getName()));
            assertEquals("▾ To-Do", todoToggle.getText()); assertNotNull(todoToggle.getIcon()); todoToggle.doClick();
            assertFalse(find(todo, c -> "todayTodoBody".equals(c.getName())).isVisible());
            assertNull(find(panel, c -> c instanceof JTextArea && ((JTextArea) c).getText().contains("enable login recovery")));
            engine.observe(Map.of("MINING", 110L), GoalEngine.LIVE, now);
            engine.observeCount("BRUTUS", 65, true, GoalEngine.LIVE, null, now);
            engine.observeCount("BRUTUS", 66, true, GoalEngine.LIVE, null, now);
            panel.render(view(engine, "Saved", now));
            Container todayDone = (Container) find(panel, c -> "todayComplete".equals(c.getName()));
            Container goalsDone = (Container) find(panel, c -> "goalsComplete".equals(c.getName()));
            todo = (Container) find(panel, c -> "todayTodo".equals(c.getName()));
            assertTrue(todayDone.getParent().getComponentZOrder(todo) < todayDone.getParent().getComponentZOrder(todayDone));
            assertEquals("▸ To-Do", ((javax.swing.JButton) find(todo, c -> "toggleTodayTodo".equals(c.getName()))).getText());
            assertFalse(find(todo, c -> "todayTodoBody".equals(c.getName())).isVisible());
            assertTrue(todayDone.getComponent(1).isVisible());
            assertEquals("▾ On Pace", ((javax.swing.JButton) find(todayDone, c -> "toggleTodayComplete".equals(c.getName()))).getText());
            assertEquals("▾ Completed Goals", ((javax.swing.JButton) find(goalsDone, c -> "toggleGoalsComplete".equals(c.getName()))).getText());
            assertNotNull(find(todayDone, c -> ("today-" + mining).equals(c.getName())));
            assertNotNull(find(todayDone, c -> ("today-" + boss).equals(c.getName())));
            assertNotNull(find(goalsDone, c -> ("goal-" + overall).equals(c.getName())));
            assertNull(find(goalsDone, c -> ("goal-" + mining).equals(c.getName())));
            assertNull(find(goalsDone, c -> ("goal-" + boss).equals(c.getName())));
            Component tomorrow = find(panel, c -> "tomorrow".equals(c.getName()));
            assertTrue(goalsDone.getParent().getComponentZOrder(tomorrow) < goalsDone.getParent().getComponentZOrder(goalsDone));
            javax.swing.JButton toggle = (javax.swing.JButton) find(panel, c -> "toggleTodayComplete".equals(c.getName())); toggle.doClick();
            assertEquals("▸ On Pace", toggle.getText());
            assertFalse(todayDone.getComponent(1).isVisible());
            engine.observeCount("BRUTUS", 67, true, GoalEngine.LIVE, null, now);
            panel.render(view(engine, "Saved", now));
            assertFalse(((Container) find(panel, c -> "todayComplete".equals(c.getName()))).getComponent(1).isVisible());
            Instant next = now.plusSeconds(7 * 86400); engine.tick(next); panel.render(view(engine, "Saved", next));
            assertNull(find(panel, c -> "todayComplete".equals(c.getName())));
            assertNotNull(find(panel, c -> ("goal-" + overall).equals(c.getName())));
            assertNotNull(find(panel, c -> ("today-" + boss).equals(c.getName())));
        });
    }

    @Test public void lifetimeKcGoalShowsTotalAndMovesToCompleteIndependentlyOfPeriodQuota() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine();
            GoalSpec spec = new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(), 1000L);
            String id = engine.add(spec, now);
            engine.observeCount("BRUTUS", 999, false, GoalEngine.RECOVERED, null, now);
            MyGoalsPanel panel = panel(); panel.render(view(engine, "Saved", now));
            Container managed = (Container) find(panel, c -> ("today-" + id).equals(c.getName()));
            assertEquals("1k KC", ((JLabel) find(managed, c -> "todayPlanGoal".equals(c.getName()))).getText());
            assertEquals("1 KC left", ((JLabel) find(managed, c -> "todayPlanTarget".equals(c.getName()))).getText());
            assertEquals("Target Pace", ((JLabel) find(managed, c -> "targetCardProgressLabel".equals(c.getName()))).getText());
            assertNull(find(managed, c -> c instanceof javax.swing.JButton && "Delete".equals(((javax.swing.JButton) c).getText())));
            engine.observeCount("BRUTUS", 1000, true, GoalEngine.LIVE, null, now); panel.render(view(engine, "Saved", now));
            Container done = (Container) find(panel, c -> "goalsComplete".equals(c.getName()));
            assertNotNull(find(done, c -> ("goal-" + id).equals(c.getName())));
            assertNotNull(find(done, c -> c instanceof JLabel && "0 KC left".equals(((JLabel) c).getText())));
            assertNotNull(find(done, c -> c instanceof JLabel && "1 / 10 KC".equals(((JLabel) c).getText())));
        });
    }

    @Test public void futureAndEndedGoalsStayAccessibleWithoutBeingMarkedCompleted() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            String ended = engine.add(new GoalSpec("Missed", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE,
                LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16"), zone), now);
            String future = engine.add(new GoalSpec("Future", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE,
                LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-25"), zone), now);
            Instant next = now.plusSeconds(86400); engine.tick(next);
            MyGoalsPanel panel = panel(); panel.render(view(engine, "Character", next));
            Container other = (Container) find(panel, c -> "otherGoals".equals(c.getName()));
            assertNotNull(find(other, c -> ("goal-" + ended).equals(c.getName())));
            assertNotNull(find(other, c -> ("goal-" + future).equals(c.getName())));
            assertFalse(find(other, c -> "otherGoalsBody".equals(c.getName())).isVisible());
            ((javax.swing.JButton) find(other, c -> "toggleOtherGoals".equals(c.getName()))).doClick();
            assertTrue(find(other, c -> "otherGoalsBody".equals(c.getName())).isVisible());
            assertNull(find(panel, c -> "goalsComplete".equals(c.getName())));
            assertTrue(TodayActionsTest.action(panel.cardMenu(ended, "test", MyGoalsPanel.CardMode.GOAL), "Edit").isEnabled());
            assertEquals("ENDED", engine.state().goals.get(ended).lifecycle);
            assertEquals("SCHEDULED", engine.state().goals.get(future).lifecycle);
        });
    }

    @Test public void gearToolsAndCreationAreAvailableInTheSingleView() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> actions = new java.util.ArrayList<>();
            MyGoalsPanel panel = new MyGoalsPanel(null, null, (token, command) -> {}, () -> actions.add("local"), () -> actions.add("hiscores"));
            panel.render(view(engine(), "Saved", now));
            javax.swing.JButton create = (javax.swing.JButton) find(panel, c -> c instanceof javax.swing.JButton && "New goal".equals(((javax.swing.JButton) c).getText()));
            javax.swing.JButton gear = (javax.swing.JButton) find(panel, c -> "goalsSettings".equals(c.getName()));
            assertTrue(visible(gear)); assertNotNull(gear.getIcon()); assertNotNull(gear.getAccessibleContext().getAccessibleName());
            javax.swing.JPopupMenu menu = gear.getComponentPopupMenu();
            assertEquals(java.util.List.of("Refresh", "Enable groups", "Date format", "Export backup", "Restore backup", "View clock", "HiScores: Not recorded", "Collection log: Not recorded"),
                java.util.Arrays.stream(menu.getComponents()).filter(c -> c instanceof javax.swing.JMenuItem)
                    .map(c -> ((javax.swing.JMenuItem) c).getText()).collect(java.util.stream.Collectors.toList()));
            assertNull(find(panel, c -> "manageClock".equals(c.getName()) || "exportBackup".equals(c.getName()) || "restoreBackup".equals(c.getName())));
            javax.swing.JMenuItem clock = (javax.swing.JMenuItem) find(menu, c -> "manageClock".equals(c.getName()));
            assertEquals("View clock", clock.getText()); assertTrue(clock.isEnabled());
            assertTrue(visible(create));
            assertTrue(visible(gear)); assertSame(menu, gear.getComponentPopupMenu());
            assertNull(find(panel, c -> "manageGoalsTab".equals(c.getName())));
            assertNull(find(panel, c -> c instanceof javax.swing.AbstractButton && "Check counts".equals(((javax.swing.AbstractButton) c).getText())));
            javax.swing.JMenuItem refresh = (javax.swing.JMenuItem) find(menu, c -> "refreshGoals".equals(c.getName()));
            assertTrue(refresh.getToolTipText().contains("local goals")); assertTrue(refresh.getToolTipText().contains("HiScores"));
            refresh.doClick(); assertEquals(java.util.List.of("local", "hiscores"), actions);
            panel.render(new GoalsView("logged-out", "Log in to load your goals", "", false, null, now));
            assertTrue(refresh.isEnabled()); assertTrue(clock.isEnabled());
            assertFalse(find(menu, c -> "exportBackup".equals(c.getName())).isEnabled());
            assertFalse(find(menu, c -> "restoreBackup".equals(c.getName())).isEnabled());
        });
    }

    @Test public void editedWeeklyTargetImmediatelyUpdatesTodayAndReopensItsCompletedAssignment() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String id = engine.add(brutus(Map.of()), now);
            engine.observeCount("BRUTUS", 65, true, GoalEngine.LIVE, null, now);
            engine.observeCount("BRUTUS", 66, true, GoalEngine.LIVE, null, now);
            MyGoalsPanel panel = panel(); panel.render(view(engine, "Saved", now));
            assertNotNull(find(panel, c -> "todayComplete".equals(c.getName())));
            engine.edit(id, new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 100, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
            panel.render(view(engine, "Saved", now));
            assertNull(find(panel, c -> "todayComplete".equals(c.getName())));
            assertNotNull(find(panel, c -> c instanceof JLabel && "2 / 15 KC".equals(((JLabel) c).getText())));
            assertNotNull(find(panel, c -> c instanceof JLabel && "2 / 100 KC".equals(((JLabel) c).getText())));
            assertEquals(2, engine.state().goals.get(id).current.progress()); assertNull(engine.state().goals.get(id).pending);
        });
    }

    private static boolean visible(Component component)
    {
        for (Component current = component; current != null; current = current.getParent()) { if (!current.isVisible()) { return false; } }
        return true;
    }

    private static Component find(Container parent, Predicate<Component> matches)
    {
        for (Component child : parent.getComponents())
        {
            if (matches.test(child)) { return child; }
            if (child instanceof Container) { Component found = find((Container) child, matches); if (found != null) { return found; } }
        }
        return null;
    }
}
