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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalDetailsPanelTest
{
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    private GoalsView.Goal goal(GoalEngine engine) { return new GoalsView("test", "Saved", "", true, engine.state(), now).goals.get(0); }
    private GoalSpec spec(String metric, long amount, Map<Integer, Long> targets, Long total)
    {
        return new GoalSpec("My goal", null, GoalSpec.TrackType.COUNT, metric, GoalSpec.Mode.GAIN, amount, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, targets, total);
    }

    @Test public void weeklyGoalShowsCadenceAndSeparateTodayAndPeriodBars() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.add(spec("BRUTUS", 100, Map.of(), null), now);
            engine.observeCount("BRUTUS", 65, true, GoalEngine.LIVE, null, now); engine.observeCount("BRUTUS", 66, true, GoalEngine.LIVE, null, now);
            GoalDetailsPanel panel = new GoalDetailsPanel((id, label) -> {}); panel.render(goal(engine), false);
            assertEquals("2 / 15 kills", ((JLabel) find(panel, "todayProgressAmount")).getText());
            assertEquals("2 / 100 kills", ((JLabel) find(panel, "periodProgressAmount")).getText());
            assertEquals(133, ((JProgressBar) find(panel, "todayProgressBar")).getValue());
            assertEquals(20, ((JProgressBar) find(panel, "periodProgressBar")).getValue());
            assertEquals("100 kills", ((JLabel) find(panel, "detailPlanTarget")).getText());
            assertEquals("Weekly", ((JLabel) find(panel, "detailPlanCadence")).getText());
            assertTrue(panel.getComponentZOrder(find(panel, "detailPlan")) < panel.getComponentZOrder(find(panel, "todayProgress")));
            assertNull(find(panel, "goalTimeline")); assertNull(find(panel, "detailCalendar"));
            assertTrue(((JTextArea) find(panel, "detailSchedule")).getText().contains("10/16/26 → 10/23/26"));
            assertFalse(find(panel, "detailUniques").isVisible()); assertFalse(find(panel, "lifetimeProgress").isVisible());
        });
    }

    @Test public void liveUniqueUpdatesPreserveIconRowsAndShowMetAndRemainingTargets() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
            engine.add(spec("THE_CORRUPTED_GAUNTLET", 10, Map.of(23956, 6L, 25859, 1L), null), now);
            List<Integer> requested = new ArrayList<>(); List<String> goalIcons = new ArrayList<>();
            GoalDetailsPanel panel = new GoalDetailsPanel((id, label) -> requested.add(id), (spec, label) -> goalIcons.add(spec.metricId)); panel.render(goal(engine), false);
            Component row = find(panel, "detailUnique-23956"), icon = find(panel, "detailUniqueIcon-23956");
            assertEquals(2, requested.size()); assertFalse(find(panel, "detailCollectionRefresh").isVisible());
            engine.acquire("receipt", Map.of(23956, 1L, 25859, 1L), "Drop", now); panel.render(goal(engine), false);
            assertSame(row, find(panel, "detailUnique-23956")); assertSame(icon, find(panel, "detailUniqueIcon-23956")); assertEquals(2, requested.size());
            assertEquals(List.of("THE_CORRUPTED_GAUNTLET"), goalIcons);
            assertEquals("5 / 6 • 1 remaining", ((JLabel) find(panel, "detailUniqueAmount-23956")).getText());
            assertEquals("1 / 1 • Target met", ((JLabel) find(panel, "detailUniqueAmount-25859")).getText());
            assertTrue(((JLabel) find(panel, "detailUniqueSummary")).getText().contains("1/2"));
            engine.requireCollectionSync(java.util.Set.of(23956)); panel.render(goal(engine), false);
            assertTrue(find(panel, "detailCollectionRefresh").isVisible());
            assertTrue(((JLabel) find(panel, "detailUniqueAmount-23956")).getText().contains("*"));
            assertSame(icon, find(panel, "detailUniqueIcon-23956"));
        });
    }

    @Test public void uniqueAchievementDoesNotPretendTheUnfinishedKillQuotasWereMet() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 6L)), false, now);
            engine.add(spec("THE_CORRUPTED_GAUNTLET", 10, Map.of(23956, 6L), null), now);
            GoalDetailsPanel panel = new GoalDetailsPanel((id, label) -> {}); panel.render(goal(engine), false);
            assertEquals("✓ Goal achieved", ((JLabel) find(panel, "detailResult")).getText());
            assertEquals(0, ((JProgressBar) find(panel, "todayProgressBar")).getValue());
            assertEquals(0, ((JProgressBar) find(panel, "periodProgressBar")).getValue());
            assertEquals(1000, ((JProgressBar) find(panel, "detailUniqueBar-23956")).getValue());
        });
    }

    @Test public void lifetimeCountHasItsOwnBarAndUnknownTotalsStayUnknown() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.add(spec("BRUTUS", 10, Map.of(), 1000L), now);
            GoalDetailsPanel panel = new GoalDetailsPanel((id, label) -> {}); panel.render(goal(engine), false);
            assertTrue(find(panel, "lifetimeProgress").isVisible());
            assertEquals("Lifetime total not yet known", ((JLabel) find(panel, "lifetimeProgressAmount")).getText());
            engine.observeCount("BRUTUS", 999, false, GoalEngine.RECOVERED, null, now); panel.render(goal(engine), false);
            assertEquals(999, ((JProgressBar) find(panel, "lifetimeProgressBar")).getValue());
            engine.observeCount("BRUTUS", 1000, true, GoalEngine.LIVE, null, now); panel.render(goal(engine), false);
            assertEquals(1000, ((JProgressBar) find(panel, "lifetimeProgressBar")).getValue());
            assertEquals(100, ((JProgressBar) find(panel, "periodProgressBar")).getValue());
            assertEquals("1 / 10 kills", ((JLabel) find(panel, "periodProgressAmount")).getText());
        });
    }

    @Test public void compactPlanPreservesDatesDeadlineAndPendingChangesWithoutCalendar() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now);
            String id = engine.add(new GoalSpec("Mining by December", null, "MINING", GoalSpec.Mode.GAIN, 100_000, 0, GoalSpec.Cadence.WEEKLY,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), zone), now);
            GoalDetailsPanel panel = new GoalDetailsPanel((item, label) -> {}); panel.render(goal(engine), false);
            assertNull(find(panel, "goalTimeline")); assertNull(find(panel, "detailCalendar"));
            assertTrue(((JTextArea) find(panel, "detailSchedule")).getText().contains("10/15/26 → 10/22/26"));
            assertEquals("Until 12/31/26", ((JTextArea) find(panel, "detailRepeat")).getText());
            assertEquals(0, ((JProgressBar) find(panel, "periodProgressBar")).getValue());
            assertFalse(find(panel, "detailPendingChanges").isVisible());
            engine.edit(id, new GoalSpec("Mining by December", null, "MINING", GoalSpec.Mode.GAIN, 100_000, 0, GoalSpec.Cadence.MONTHLY,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), zone), now);
            panel.render(goal(engine), false);
            assertTrue(find(panel, "detailPendingChanges").isVisible());
            assertEquals("Changes scheduled for 10/22/26", ((JTextArea) find(panel, "detailPendingChanges")).getText());
        });
    }

    @Test public void detailsRetainPeriodHistoryAndLoadArchivesOnlyOnRequest() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); Instant first = Instant.parse("2026-01-01T17:00:00Z");
            engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, first);
            String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.DAILY,
                LocalDate.parse("2026-01-01"), null, zone), first);
            engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, first);
            engine.tick(first.plusSeconds(86400)); engine.tick(now);
            var fragments = com.mygoals.persistence.HistoryArchive.detach(engine.state(), LocalDate.parse("2026-10-16"));
            var old = fragments.values().stream().filter(f -> f.goals.containsKey(id))
                .flatMap(f -> f.goals.get(id).history.stream()).collect(java.util.stream.Collectors.toList());
            GoalDetailsPanel panel = new GoalDetailsPanel((item, label) -> {});
            java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
            panel.setHistoryLoader((before, done) -> {
                requests.incrementAndGet(); done.accept(new GoalsView.HistoryPage(old, "2026-01", false, ""));
            });
            panel.render(goal(engine), false);
            assertNull(find(panel, "goalTimeline")); assertTrue(find(panel, "periodHistory").isVisible()); assertEquals(0, requests.get());
            assertTrue(panel.getComponentZOrder(find(panel, "periodHistory")) > panel.getComponentZOrder(find(panel, "periodProgress")));
            ((javax.swing.JButton) find(panel, "loadOlderPeriods")).doClick();
            assertEquals(1, requests.get()); assertFalse(find(panel, "loadOlderPeriods").isVisible());
            assertEquals("1 / 288 met", ((JLabel) find(panel, "periodHistorySummary")).getText());
            panel.setDateFormat(com.mygoals.domain.GoalDateFormat.EUROPEAN);
            assertEquals("1 / 288 met", ((JLabel) find(panel, "periodHistorySummary")).getText()); assertEquals(1, requests.get());
        });
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
}
