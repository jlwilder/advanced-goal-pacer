package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.CalendarService;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import com.mygoals.persistence.StateCodec;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class PeriodHistoryPanelTest
{
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private Instant at(String date) { return LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant(); }
    private GoalsView.Goal snapshot(GoalEngine engine, Instant now) { return new GoalsView("test", "Saved", "", true, engine.state(), now).goals.get(0); }
    private String tooltip(JComponent strip, int cell)
    {
        return strip.getToolTipText(new MouseEvent(strip, MouseEvent.MOUSE_MOVED, 0, 0, cell * 66 + 10, 10, 0, false));
    }

    @Test public void dailyWeeklyAndMonthlyHistoryUsesEachClosedPeriodsOwnQuota() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            for (GoalSpec.Cadence cadence : new GoalSpec.Cadence[]{GoalSpec.Cadence.DAILY, GoalSpec.Cadence.WEEKLY, GoalSpec.Cadence.MONTHLY})
            {
                Instant first = at("2026-01-31"); GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
                engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, first);
                GoalSpec spec = new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, cadence, LocalDate.parse("2026-01-31"), null, zone);
                String id = engine.add(spec, first); engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, first);
                Instant second = new CalendarService().current(spec, first).endInstant(); engine.tick(second);
                GoalSpec raised = new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 200, 0, cadence, LocalDate.parse(spec.startDate), null, zone);
                engine.edit(id, raised, second); engine.observe(Map.of("MINING", 100_140L), GoalEngine.LIVE, second);
                Instant third = new CalendarService().current(spec, second).endInstant(); engine.tick(third);
                GoalsView.Goal goal = snapshot(engine, third);
                assertEquals(3, goal.periods.size()); assertTrue(goal.periods.get(0).met); assertEquals(100, goal.periods.get(0).target);
                assertFalse(goal.periods.get(1).met); assertEquals(200, goal.periods.get(1).target); assertTrue(goal.periods.get(2).current);
                PeriodHistoryPanel panel = new PeriodHistoryPanel(); panel.render(goal); assertTrue(panel.isVisible());
                assertEquals("1 / 2 met", ((JLabel) ManageClockPanelTest.find(panel, "periodHistorySummary")).getText());
                JComponent strip = (JComponent) ManageClockPanelTest.find(panel, "periodHistoryStrip");
                assertTrue(tooltip(strip, 0).contains("100 / 100 XP")); assertTrue(tooltip(strip, 1).contains("40 / 200 XP"));
                assertTrue(tooltip(strip, 2).contains("Current period"));
            }
        });
    }

    @Test public void uniqueAchievementDoesNotTurnUnmetDailyQuotasGreen() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant first = at("2026-10-10"); GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, first);
            engine.add(new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 2, 0,
                GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-10"), null, zone, Map.of(23956, 6L)), first);
            engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, false, GoalEngine.LIVE, null, first);
            engine.observeCount("THE_CORRUPTED_GAUNTLET", 102, true, GoalEngine.LIVE, null, first);
            Instant second = at("2026-10-11"); engine.observeCount("THE_CORRUPTED_GAUNTLET", 103, true, GoalEngine.LIVE, null, second);
            Instant third = at("2026-10-12"); engine.tick(third); engine.acquire("seeds", Map.of(23956, 2L), "Drop", third);
            GoalsView.Goal goal = snapshot(engine, third); assertEquals("ACHIEVED", goal.lifecycle);
            assertTrue(goal.periods.get(0).met); assertFalse(goal.periods.get(1).met); assertFalse(goal.periods.get(2).met);
            assertTrue(goal.periods.get(2).stoppedEarly); assertFalse(goal.periods.get(2).current);
            PeriodHistoryPanel panel = new PeriodHistoryPanel(); panel.render(goal);
            assertEquals("1 / 3 met", ((JLabel) ManageClockPanelTest.find(panel, "periodHistorySummary")).getText());
            JComponent strip = (JComponent) ManageClockPanelTest.find(panel, "periodHistoryStrip");
            assertTrue(tooltip(strip, 0).contains("Quota met")); assertTrue(tooltip(strip, 1).contains("Quota not met"));
            assertTrue(tooltip(strip, 2).contains("goal ended early"));
        });
    }

    @Test public void missedPeriodsRecordNoCreditedWorkAndSavedHistorySurvivesRestart() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone); Instant first = at("2026-10-01");
            engine.add(new GoalSpec("Boss", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-01"), null, zone), first);
            Instant later = at("2026-10-22"); engine.tick(later);
            StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); GoalEngine loaded = new GoalEngine(codec.decode(codec.encode(engine.state()), engine.state().profileKey), zone);
            assertEquals(snapshot(engine, later).periods, snapshot(loaded, later).periods);
            PeriodHistoryPanel panel = new PeriodHistoryPanel(); panel.render(snapshot(loaded, later));
            assertEquals("0 / 3 met", ((JLabel) ManageClockPanelTest.find(panel, "periodHistorySummary")).getText());
            JComponent strip = (JComponent) ManageClockPanelTest.find(panel, "periodHistoryStrip");
            assertTrue(tooltip(strip, 1).contains("Quota not met")); assertEquals(4, loaded.state().goals.values().iterator().next().history.size() + 1);
        });
    }

    @Test public void archivesLoadOnlyOnRequestAndFailuresCanBeRetried() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, at("2026-01-01"));
            String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0,
                GoalSpec.Cadence.DAILY, LocalDate.parse("2026-01-01"), null, zone), at("2026-01-01"));
            engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, at("2026-01-01"));
            engine.tick(at("2026-01-02")); engine.tick(at("2026-10-16"));
            var fragments = com.mygoals.persistence.HistoryArchive.detach(engine.state(), LocalDate.parse("2026-10-16"));
            var old = fragments.values().stream().filter(f -> f.goals.containsKey(id)).flatMap(f -> f.goals.get(id).history.stream()).collect(java.util.stream.Collectors.toList());
            GoalsView.Goal goal = snapshot(engine, at("2026-10-16")); assertTrue(goal.hasOlderHistory);
            PeriodHistoryPanel panel = new PeriodHistoryPanel();
            java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<GoalsView.HistoryPage>> completion = new java.util.concurrent.atomic.AtomicReference<>();
            panel.setHistoryLoader((before, done) -> { requests.incrementAndGet(); completion.set(done); }); panel.render(goal);
            var button = (javax.swing.JButton) ManageClockPanelTest.find(panel, "loadOlderPeriods");
            assertTrue(button.isVisible()); assertEquals(0, requests.get());
            button.doClick(); assertEquals(1, requests.get()); assertFalse(button.isEnabled());
            completion.get().accept(new GoalsView.HistoryPage(java.util.List.of(), null, true, "Synthetic read error"));
            assertTrue(button.isEnabled()); assertTrue(button.isVisible());
            button.doClick(); completion.get().accept(new GoalsView.HistoryPage(old, "2026-01", false, ""));
            assertFalse(button.isVisible());
            assertEquals("1 / 288 met", ((JLabel) ManageClockPanelTest.find(panel, "periodHistorySummary")).getText());
            panel.render(goal); assertEquals("1 / 288 met", ((JLabel) ManageClockPanelTest.find(panel, "periodHistorySummary")).getText());
            assertEquals(2, requests.get());
        });
    }
}
