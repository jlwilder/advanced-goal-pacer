package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class TodayCardBodyTest
{
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private GoalEngine engine() { return new GoalEngine(new ProfileState("a".repeat(64)), zone); }
    private GoalsView.Goal goal(GoalEngine engine, String id, Instant at)
    { return new GoalsView("test", "Saved", "", true, engine.state(), at).goals.stream().filter(g -> g.id.equals(id)).findFirst().orElseThrow(); }
    private String text(TodayCardBody card, String name)
    {
        java.awt.Component value = ManageClockPanelTest.find(card, name);
        return value instanceof JLabel ? ((JLabel) value).getText() : ((javax.swing.JTextArea) value).getText();
    }
    private TodayCardBody body(GoalsView.Goal goal) { return new TodayCardBody(goal, false, false, (id, label) -> {}); }
    private GoalSpec count(String metric, Map<Integer, Long> items, Long total)
    { return new GoalSpec("Count", null, GoalSpec.TrackType.COUNT, metric, GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, items, total); }

    @Test public void xpAndLevelGoalsShareDeadlineDaysAndUseTheirActualAssignments() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.observe(Map.of("MINING", 13_000_000L), GoalEngine.LIVE, now);
            String xp = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100_000, 0, GoalSpec.Cadence.WEEKLY,
                LocalDate.parse("2026-10-16"), LocalDate.parse("2026-12-31"), zone), now);
            String level = engine.add(new GoalSpec("99 Mining", null, "MINING", GoalSpec.Mode.LEVEL, 0, 99, GoalSpec.Cadence.ONCE,
                LocalDate.parse("2026-10-16"), LocalDate.parse("2026-12-31"), zone), now);
            TodayCardBody xpCard = body(goal(engine, xp, now)), levelCard = body(goal(engine, level, now));
            assertEquals("100k XP / week", text(xpCard, "todayPlanGoal")); assertNull(ManageClockPanelTest.find(xpCard, "todayPlanCadence"));
            assertEquals("99 Mining", text(levelCard, "todayPlanGoal")); assertEquals("0 / 448 XP", text(levelCard, "todayCardProgress"));
            assertNull(ManageClockPanelTest.find(levelCard, "todayPlanCadence"));
            for (TodayCardBody card : List.of(xpCard, levelCard))
            {
                assertEquals("77 days", text(card, "todayPlanTarget"));
                assertEquals("Today's Pace", text(card, "todayCardProgressLabel"));
                assertEquals("Goal:", text(card, "todayPlanGoalLabel")); assertEquals("Target:", text(card, "todayPlanTargetLabel"));
                assertEquals("Target Pace", text(card, "targetCardProgressLabel"));
                assertNull(ManageClockPanelTest.find(card, "todayPlanDays")); assertNull(ManageClockPanelTest.find(card, "todayPlanPace"));
            }
            engine.observe(Map.of("MINING", 13_010_000L), GoalEngine.LIVE, now);
            xpCard = body(goal(engine, xp, now));
            assertEquals("10k / 14,286 XP", text(xpCard, "todayCardProgress"));
            assertEquals(699, ((JProgressBar) ManageClockPanelTest.find(xpCard, "todayCardProgressBar")).getValue());
            assertEquals("10k / 100k XP", text(xpCard, "targetCardProgress"));
        });
    }

    @Test public void bossMilestonesAndActivityCountsUseTheSameFieldsWithoutInventingAnEndDate() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String boss = engine.add(count("BRUTUS", Map.of(), 1000L), now);
            engine.observeCount("BRUTUS", 987, false, GoalEngine.RECOVERED, null, now);
            String clues = engine.add(count("CLUE_SCROLL_EASY", Map.of(), null), now);
            TodayCardBody bossCard = body(goal(engine, boss, now)), clueCard = body(goal(engine, clues, now));
            assertEquals("1k KC", text(bossCard, "todayPlanGoal")); assertEquals("0 / 2 KC", text(bossCard, "todayCardProgress"));
            assertEquals("10 KC / week", text(bossCard, "todayPlanCadence"));
            assertEquals("10 clues / week", text(clueCard, "todayPlanGoal")); assertEquals("0 / 2 clues", text(clueCard, "todayCardProgress"));
            assertEquals("13 KC left", text(bossCard, "todayPlanTarget")); assertEquals("Indefinitely", text(clueCard, "todayPlanTarget"));
            for (TodayCardBody card : List.of(bossCard, clueCard))
            { assertNull(ManageClockPanelTest.find(card, "todayPlanDays")); }
        });
    }

    @Test public void uniquesShowSelectedTargetsIncludingMetTotalsAndPreserveActualProgress() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L, 25859, 1L)), false, now);
            String id = engine.add(count("THE_CORRUPTED_GAUNTLET", Map.of(23956, 6L, 25859, 1L), null), now);
            List<Integer> requested = new ArrayList<>();
            TodayCardBody card = new TodayCardBody(goal(engine, id, now), false, false, (item, label) -> requested.add(item));
            assertNotNull(ManageClockPanelTest.find(card, "todayPlanGoal")); assertEquals("10 KC / week", text(card, "todayPlanTarget")); assertEquals(List.of(23956, 25859), requested);
            assertEquals("(4/6)", text(card, "todayUnique-23956"));
            assertEquals(new java.awt.Color(230, 184, 92), ManageClockPanelTest.find(card, "todayUnique-23956").getForeground()); assertEquals("(1/1)", text(card, "todayUnique-25859"));
            JLabel quantity = (JLabel) ManageClockPanelTest.find(card, "todayUnique-23956");
            assertEquals(JLabel.RIGHT, quantity.getHorizontalTextPosition()); assertEquals(JLabel.CENTER, quantity.getVerticalTextPosition());
            card.setSize(217, card.getPreferredSize().height);
            for (int pass = 0; pass < 4; pass++) { layout(card); }
            JLabel caption = (JLabel) ManageClockPanelTest.find(card, "todayPlanGoalLabel");
            int captionY = SwingUtilities.convertPoint(caption, 0, caption.getBaseline(caption.getWidth(), caption.getHeight()), card).y;
            int quantityY = SwingUtilities.convertPoint(quantity, 0, quantity.getBaseline(quantity.getWidth(), quantity.getHeight()), card).y;
            assertEquals("Goal caption and unique quantities share a text baseline", captionY, quantityY);
            engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L, 25859, 0L)), false, now);
            card = body(goal(engine, id, now)); assertEquals("(0/1)", text(card, "todayUnique-25859"));
            engine.state().collection.get(23956).needsSync = true;
            assertEquals("(?/6)", text(body(goal(engine, id, now)), "todayUnique-23956"));
            engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 6L, 25859, 1L)), false, now);
            card = body(goal(engine, id, now)); assertEquals("(6/6)", text(card, "todayUnique-23956"));
            assertEquals(new java.awt.Color(102, 204, 128), ManageClockPanelTest.find(card, "todayUnique-23956").getForeground()); assertEquals("(1/1)", text(card, "todayUnique-25859"));
            assertEquals("0 / 10 KC", text(card, "targetCardProgress")); assertEquals("Target Pace", text(card, "targetCardProgressLabel"));
            assertNull(ManageClockPanelTest.find(card, "todayCompleteBadge"));
            assertEquals(0, ((JProgressBar) ManageClockPanelTest.find(card, "targetCardProgressBar")).getValue());
            assertEquals(new java.awt.Color(102, 204, 128), ManageClockPanelTest.find(card, "targetCardProgressBar").getForeground());
        });
    }

    @Test public void tomorrowUsesForecastAndCalendarDaysIncludingDstAndLastDay() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant start = Instant.parse("2026-10-31T17:00:00Z"); GoalEngine engine = engine(); engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, start);
            String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE,
                LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-01"), zone), start);
            engine.observe(Map.of("MINING", 110L), GoalEngine.LIVE, start); engine.postponeGoal(id, true, start);
            TodayCardBody tomorrow = new TodayCardBody(goal(engine, id, start), true, false, (item, label) -> {});
            assertEquals("1 day", text(tomorrow, "todayPlanTarget")); assertEquals("0 / 90 XP", text(tomorrow, "todayCardProgress"));
            assertEquals("Tomorrow's Pace", text(tomorrow, "todayCardProgressLabel"));
            for (String time : List.of("2026-11-01T06:30:00Z", "2026-11-01T07:30:00Z"))
            { Instant at = Instant.parse(time); engine.tick(at); assertEquals("1 day", text(body(goal(engine, id, at)), "todayPlanTarget")); }
            engine.postponeGoal(id, true, Instant.parse("2026-11-01T07:30:00Z"));
            tomorrow = new TodayCardBody(goal(engine, id, Instant.parse("2026-11-01T07:30:00Z")), true, false, (item, label) -> {});
            assertEquals("0 days", text(tomorrow, "todayPlanTarget")); assertEquals("Ends today", text(tomorrow, "todayCardProgress"));
        });
    }

    @Test public void completeSectionUsesGreenTargetPaceWithActualPartialTotalsAndNoRedundantBadge() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String boss = engine.add(count("BRUTUS", Map.of(), null), now);
            engine.observeCount("BRUTUS", 100, true, GoalEngine.LIVE, null, now); engine.observeCount("BRUTUS", 101, true, GoalEngine.LIVE, null, now);
            TodayCardBody card = body(goal(engine, boss, now));
            assertEquals("Target Pace", text(card, "targetCardProgressLabel")); assertEquals("2 / 10 KC", text(card, "targetCardProgress"));
            assertEquals("Today's Pace", text(card, "todayCardProgressLabel")); assertEquals("✓ 2 / 2 KC", text(card, "todayCardProgress"));
            assertEquals(200, ((JProgressBar) ManageClockPanelTest.find(card, "targetCardProgressBar")).getValue());
            assertNull(ManageClockPanelTest.find(card, "todayCompleteBadge"));
            assertEquals(new java.awt.Color(102, 204, 128), ManageClockPanelTest.find(card, "todayCardProgressBar").getForeground());
            assertFalse(goal(engine, boss, now).periodQuotaMet);
            engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            String mining = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100_000, 0, GoalSpec.Cadence.WEEKLY,
                LocalDate.parse("2026-10-16"), null, zone), now);
            engine.observe(Map.of("MINING", 16_556L), GoalEngine.LIVE, now); card = body(goal(engine, mining, now));
            assertEquals("Target Pace", text(card, "targetCardProgressLabel")); assertEquals("16,456 / 100k XP", text(card, "targetCardProgress"));
            assertEquals(164, ((JProgressBar) ManageClockPanelTest.find(card, "targetCardProgressBar")).getValue());
            assertNull(ManageClockPanelTest.find(card, "todayCompleteBadge"));
            assertEquals(new java.awt.Color(102, 204, 128), ManageClockPanelTest.find(card, "todayCardProgressBar").getForeground());
            assertFalse(text(card, "targetCardProgress").startsWith("✓"));
            String overall = engine.add(new GoalSpec("Mining goal", null, "MINING", GoalSpec.Mode.GAIN, 1000, 0, GoalSpec.Cadence.ONCE,
                LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-22"), zone), now);
            engine.observe(Map.of("MINING", 16_699L), GoalEngine.LIVE, now); card = body(goal(engine, overall, now));
            assertEquals("Target Pace", text(card, "targetCardProgressLabel")); assertEquals("143 / 1k XP", text(card, "targetCardProgress"));
            assertEquals(143, ((JProgressBar) ManageClockPanelTest.find(card, "targetCardProgressBar")).getValue());
            assertNull(ManageClockPanelTest.find(card, "todayCompleteBadge"));
            assertEquals(new java.awt.Color(102, 204, 128), ManageClockPanelTest.find(card, "todayCardProgressBar").getForeground());
            Instant nextDay = now.plusSeconds(86400); engine.tick(nextDay); card = body(goal(engine, boss, nextDay));
            assertEquals("Today's Pace", text(card, "todayCardProgressLabel")); assertEquals("0 / 2 KC", text(card, "todayCardProgress"));
            assertNull(ManageClockPanelTest.find(card, "todayCompleteBadge")); assertEquals(2, goal(engine, boss, nextDay).progress);
        });
    }

    @Test public void largeCountValuesStayInsideTheirColumnsInANarrowSidebar() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = engine(); String id = engine.add(new GoalSpec("Large count", null, GoalSpec.TrackType.COUNT, "BRUTUS",
                GoalSpec.Mode.GAIN, Integer.MAX_VALUE, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
            TodayCardBody card = body(goal(engine, id, now)); card.setSize(199, card.getPreferredSize().height);
            for (int pass = 0; pass < 4; pass++) { layout(card); }
            for (String name : List.of("todayPlanGoal", "todayPlanTarget", "todayCardProgress"))
            {
                java.awt.Component value = ManageClockPanelTest.find(card, name);
                assertTrue(name, value.getX() >= 0 && value.getX() + value.getWidth() <= value.getParent().getWidth());
            }
            assertEquals("2,147,483,647 KC / week", ((JLabel) ManageClockPanelTest.find(card, "todayPlanGoal")).getToolTipText());
        });
    }

    private static void layout(java.awt.Container parent)
    {
        parent.doLayout();
        for (java.awt.Component child : parent.getComponents()) { if (child instanceof java.awt.Container) { layout((java.awt.Container) child); } }
    }
}
