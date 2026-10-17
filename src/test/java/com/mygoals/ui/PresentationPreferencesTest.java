package com.mygoals.ui;

import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import com.mygoals.persistence.StateCodec;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class PresentationPreferencesTest
{
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    private GoalsView view(GoalEngine engine, Instant at) { return new GoalsView("test", "Character", "", true, engine.state(), at); }
    private MyGoalsPanel panel(GoalEngine engine) { return new MyGoalsPanel(null, (token, command) -> command.accept(engine), () -> {}); }
    private GoalSpec xp(String name, String group, long amount, GoalSpec.Cadence cadence)
    { return new GoalSpec(name, group, "MINING", GoalSpec.Mode.GAIN, amount, 0, cadence, LocalDate.parse("2026-10-16"), null, zone); }
    private static Component find(Container parent, String name) { return ManageClockPanelTest.find(parent, name); }
    private static String label(Container parent, String name) { return ((JLabel) find(parent, name)).getText(); }
    private static JPopupMenu menu(MyGoalsPanel panel) { return ((JButton) find(panel, "goalsSettings")).getComponentPopupMenu(); }
    private static boolean contains(Container parent, String text)
    {
        for (Component child : parent.getComponents())
        {
            if (child instanceof JTextArea && ((JTextArea) child).getText().contains(text)) { return true; }
            if (child instanceof Container && contains((Container) child, text)) { return true; }
        }
        return false;
    }

    @Test public void groupingUsesNamedGroupsAcrossTodayCompleteAndTomorrowAndRestrictsReordering() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            String skills = engine.addGroup("S".repeat(80)), bosses = engine.addGroup("Bosses");
            String one = engine.add(xp("Mining", skills, 700, GoalSpec.Cadence.WEEKLY), now);
            String deferred = engine.add(xp("Later", skills, 1400, GoalSpec.Cadence.WEEKLY), now);
            engine.add(xp("Daily", skills, 5, GoalSpec.Cadence.DAILY), now);
            String boss = engine.add(new GoalSpec("Brutus", bosses, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
            engine.add(xp("Ungrouped", null, 700, GoalSpec.Cadence.WEEKLY), now);
            engine.observe(Map.of("MINING", 110L), GoalEngine.LIVE, now); engine.postponeGoal(deferred, true, now);
            MyGoalsPanel panel = panel(engine); panel.render(view(engine, now));
            assertNull(find(panel, "todayGroup-TODO-" + skills)); assertNull(find(panel, "manageGroup-" + skills)); assertNull(find(panel, "newGroup"));
            List<String> saved = new ArrayList<>(); panel.setPresentationPersistence((group, format) -> saved.add(group + ":" + format.name()));
            long version = engine.version(); ((JCheckBoxMenuItem) find(menu(panel), "groupToday")).doClick();
            assertEquals(version, engine.version()); assertEquals(List.of("true:AMERICAN"), saved);
            assertNotNull(find(panel, "todayGroup-TODO-" + skills));
            assertNotNull(find(panel, "newGroup"));
            assertEquals("Enable groups", ((JCheckBoxMenuItem) find(menu(panel), "groupToday")).getText());
            assertNotNull(find(panel, "groupActions-TODO-" + bosses));
            assertEquals("▾ Bosses", ((JButton) find(panel, "toggleTodayGroup-TODO-" + bosses)).getText());
            assertNotNull(find((Container) find(panel, "todayComplete"), "todayGroup-ON_PACE-" + skills));
            assertNotNull(find((Container) find(panel, "tomorrow"), "todayGroup-TOMORROW-" + skills));
            panel.setSize(225, 700); for (int pass = 0; pass < 4; pass++) { layout(panel); }
            Container longGroup = (Container) find(panel, "todayGroup-TODO-" + skills);
            assertTrue("Long group names must fit inside the sidebar", longGroup.getWidth() <= 225 && longGroup.getComponent(0).getWidth() <= longGroup.getWidth());
            JButton newGoal = (JButton) find(panel, "newGoal"), newGroup = (JButton) find(panel, "newGroup");
            assertEquals(newGoal.getWidth(), newGroup.getWidth()); assertTrue(newGroup.getY() >= newGoal.getY() + newGoal.getHeight());
            assertNotNull(newGroup.getIcon()); assertNotNull(((JButton) find(panel, "toggleTodayGroup-TODO-" + bosses)).getIcon());
            JButton todoHeading = (JButton) find(panel, "toggleTodayTodo"), paceHeading = (JButton) find(panel, "toggleTodayComplete");
            assertEquals(todoHeading.getParent().getWidth(), todoHeading.getWidth());
            assertEquals(paceHeading.getParent().getWidth(), paceHeading.getWidth());
            assertTrue(find(panel, "toggleTodayGroup-TODO-" + bosses).getWidth() < todoHeading.getWidth());
            assertFalse(panel.moveCard(one, boss, false, "test", MyGoalsPanel.CardMode.TODAY));
            JButton collapse = (JButton) find(panel, "toggleTodayGroup-TODO-" + skills); collapse.doClick(); panel.render(view(engine, now));
            assertFalse(((Container) find(panel, "todayGroup-TODO-" + skills)).getComponent(1).isVisible());
            assertEquals(version, engine.version()); assertFalse(engine.state().groups.stream().filter(g -> g.id.equals(skills)).findFirst().orElseThrow().collapsed);
            assertTrue(((Container) find(panel, "todayGroup-ON_PACE-" + skills)).getComponent(1).isVisible());
            assertTrue(((Container) find(panel, "todayGroup-TOMORROW-" + skills)).getComponent(1).isVisible());
            engine.observe(Map.of("MINING", 111L), GoalEngine.LIVE, now); panel.render(view(engine, now));
            assertFalse(((Container) find(panel, "todayGroup-TODO-" + skills)).getComponent(1).isVisible());
            assertTrue(((Container) find(panel, "todayGroup-ON_PACE-" + skills)).getComponent(1).isVisible());
            ((JButton) find(panel, "toggleTodayGroup-ON_PACE-" + skills)).doClick();
            ((JButton) find(panel, "toggleTodayGroup-TODO-" + skills)).doClick();
            assertTrue(((Container) find(panel, "todayGroup-TODO-" + skills)).getComponent(1).isVisible());
            assertFalse(((Container) find(panel, "todayGroup-ON_PACE-" + skills)).getComponent(1).isVisible());
            assertTrue(((Container) find(panel, "todayGroup-TOMORROW-" + skills)).getComponent(1).isVisible());
            JPopupMenu cardMenu = panel.cardMenu(deferred, "test", MyGoalsPanel.CardMode.TOMORROW);
            assertEquals("Return to Today", TodayActionsTest.action(cardMenu, "Return to Today").getText());
            String stateBeforeHiding = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(engine.state());
            Component managedCard = find(panel, "today-" + one);
            ((JCheckBoxMenuItem) find(menu(panel), "groupToday")).doClick(); assertNull(find(panel, "todayGroup-TODO-" + skills));
            assertNull(find(panel, "manageGroup-" + skills)); assertNull(find(panel, "newGroup"));
            assertSame(managedCard, find(panel, "today-" + one)); assertEquals(stateBeforeHiding, new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(engine.state()));
            ((JCheckBoxMenuItem) find(menu(panel), "groupToday")).doClick();
            assertNotNull(find(panel, "todayGroup-TODO-" + skills)); assertNotNull(find(panel, "newGroup"));
            assertEquals(stateBeforeHiding, new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(engine.state()));
            assertFalse(((Container) find(panel, "todayGroup-ON_PACE-" + skills)).getComponent(1).isVisible());
            assertEquals("Defer to tomorrow", TodayActionsTest.action(panel.cardMenu(one, "test", MyGoalsPanel.CardMode.TODAY), "Defer to tomorrow").getText());
        });
    }

    @Test public void datePreferenceReformatsCurrentViewsAndCanBeReappliedWithoutChangingGoalData() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 700, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), LocalDate.parse("2026-12-31"), zone), now);
            MyGoalsPanel panel = panel(engine); panel.render(view(engine, now));
            assertTrue(((JLabel) find(panel, "todayPlanTarget")).getToolTipText().contains("12/31/26")); assertFalse(contains(panel, "Active"));
            List<GoalDateFormat> saved = new ArrayList<>(); panel.setPresentationPersistence((group, format) -> saved.add(format));
            JMenu dates = (JMenu) find(menu(panel), "dateFormat"); long version = engine.version();
            ((JRadioButtonMenuItem) find(dates.getPopupMenu(), "dateFormat-EUROPEAN")).doClick();
            assertEquals(List.of(GoalDateFormat.EUROPEAN), saved); assertEquals(version, engine.version());
            assertTrue(((JLabel) find(panel, "todayPlanTarget")).getToolTipText().contains("31/12/26"));
            MyGoalsPanel reopened = panel(engine); reopened.setPresentationPreferences(false, saved.get(0)); reopened.render(view(engine, now));
            assertTrue(((JLabel) find(reopened, "todayPlanTarget")).getToolTipText().contains("31/12/26"));
            GoalDetailsPanel details = new GoalDetailsPanel((id, icon) -> {}); details.render(view(engine, now).goals.get(0), false);
            details.setDateFormat(GoalDateFormat.EUROPEAN);
            assertTrue(((JTextArea) find(details, "detailSchedule")).getText().contains("16/10/26 → 23/10/26"));
            assertNull(find(details, "goalTimeline")); assertNull(find(details, "detailCalendar"));
        });
    }

    @Test public void metPeriodKeepsItsExactOrExceededTotalsAcrossDaysAndRestartsThenResetsNextPeriod() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.collectionSnapshot(6413, UniqueGoalTest.page(6413, Map.of()), false, now);
            String boss = engine.add(new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(33101, 1L)), now);
            engine.observeCount("BRUTUS", 100, true, GoalEngine.LIVE, null, now); engine.observeCount("BRUTUS", 113, true, GoalEngine.LIVE, null, now);
            engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now); String mining = engine.add(xp("Mining", null, 100, GoalSpec.Cadence.MONTHLY), now);
            engine.observe(Map.of("MINING", 200L), GoalEngine.LIVE, now);
            MyGoalsPanel panel = panel(engine); panel.render(view(engine, now)); assertPeriod(panel, boss, "✓ 14 / 10 KC"); assertPeriod(panel, mining, "✓ 100 / 100 XP");
            assertEquals("ACTIVE", engine.state().goals.get(boss).lifecycle); assertEquals(Long.valueOf(0), engine.state().collection.get(33101).quantity);
            StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); engine = new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone);
            Instant nextDay = now.plusSeconds(86400); engine.tick(nextDay); panel.render(view(engine, nextDay));
            assertPeriod(panel, boss, "✓ 14 / 10 KC"); assertPeriod(panel, mining, "✓ 100 / 100 XP");
            assertEquals(0, view(engine, nextDay).goals.get(0).todayCredited); assertEquals(0, view(engine, nextDay).goals.get(0).todayAssigned);
            assertEquals(4, panel.cardMenu(boss, "test", MyGoalsPanel.CardMode.TODAY).getComponentCount());
            Instant nextWeek = now.plusSeconds(7 * 86400); engine.tick(nextWeek); panel.render(view(engine, nextWeek));
            assertNull(find((Container) find(panel, "todayComplete"), "today-" + boss));
            Container active = (Container) find(panel, "today-" + boss); assertEquals("Today's Pace", label(active, "todayCardProgressLabel"));
            assertEquals("0 / 2 KC", label(active, "todayCardProgress")); assertPeriod(panel, mining, "✓ 100 / 100 XP");
        });
    }

    private static void assertPeriod(MyGoalsPanel panel, String id, String amount)
    {
        Container complete = (Container) find(panel, "todayComplete"), card = (Container) find(complete, "today-" + id);
        assertNotNull(card); assertEquals("Target Pace", label(card, "targetCardProgressLabel")); assertEquals(amount, label(card, "targetCardProgress"));
        assertEquals(1000, ((JProgressBar) find(card, "targetCardProgressBar")).getValue());
    }

    private static void layout(Container parent)
    {
        parent.doLayout();
        for (Component child : parent.getComponents()) { if (child instanceof Container) { layout((Container) child); } }
    }
}
