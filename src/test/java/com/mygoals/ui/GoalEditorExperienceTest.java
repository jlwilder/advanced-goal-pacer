package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import net.runelite.client.hiscore.HiscoreSkill;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalEditorExperienceTest
{
    private JPanel uniqueForm(boolean baseline, java.util.function.BiConsumer<Integer, JLabel> icons)
    {
        Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault();
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        if (baseline) { engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 17L, 23859, 1L)), false, now); }
        String id = engine.add(new GoalSpec("My Gauntlet goal", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.now(zone), null, zone, Map.of(23956, 20L)), now);
        GoalsView view = new GoalsView("test", "Saved", "", true, engine.state(), now);
        return GoalEditor.createForm(view, view.goals.stream().filter(g -> g.id.equals(id)).findFirst().orElseThrow(), spec -> {}, () -> {}, icons);
    }

    @Test public void quantityEditsTogglesAndLateIconsKeepScrollPositionAndRowGeometry() throws Exception
    {
        AtomicReference<JPanel> form = new AtomicReference<>(); AtomicReference<Point> position = new AtomicReference<>();
        AtomicReference<Dimension> rowSize = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = uniqueForm(true, (id, label) -> {}); form.set(panel); panel.setSize(560, 540); layout(panel);
            JScrollPane scroll = (JScrollPane) find(panel, "goalFormScroll"); Component row = find(panel, "unique-row-23956");
            int rowY = SwingUtilities.convertPoint(row.getParent(), row.getLocation(), scroll.getViewport().getView()).y;
            scroll.getViewport().setViewPosition(new Point(0, rowY - 80)); position.set(scroll.getViewport().getViewPosition());
            assertTrue(position.get().y > 0); rowSize.set(row.getSize());
            JSpinner total = (JSpinner) find(panel, "unique-total-23956"); total.setValue(21L); total.setValue(22L);
            JCheckBox check = (JCheckBox) find(panel, "unique-23956"); check.doClick(); check.doClick();
            SwingUtilities.invokeLater(() -> ((JLabel) find(panel, "unique-icon-23956")).setIcon(new ImageIcon(new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB))));
            layout(panel);
        });
        SwingUtilities.invokeAndWait(() -> {
            layout(form.get());
            assertEquals(position.get(), ((JScrollPane) find(form.get(), "goalFormScroll")).getViewport().getViewPosition());
            assertEquals(rowSize.get(), find(form.get(), "unique-row-23956").getSize());
            assertEquals(22L, ((JSpinner) find(form.get(), "unique-total-23956")).getValue());
        });
    }

    @Test public void onlyOneRefreshBannerAndEveryItemGetsAnIconSlot() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            List<Integer> requested = new ArrayList<>();
            JPanel panel = uniqueForm(false, (id, label) -> requested.add(id));
            assertEquals(5, requested.size()); assertTrue(requested.containsAll(List.of(23757, 23956, 4207, 25859, 23859)));
            assertEquals(1, namedCount(panel, "collectionRefresh"));
            assertNull(find(uniqueForm(true, (id, label) -> {}), "collectionRefresh"));
            ((JButton) find(panel, "selectAllUniques")).doClick();
            requested.forEach(id -> assertTrue(((JSpinner) find(panel, "unique-total-" + id)).isEnabled()));
            ((JButton) find(panel, "clearUniques")).doClick();
            requested.forEach(id -> { JSpinner value = (JSpinner) find(panel, "unique-total-" + id); assertFalse(value.isEnabled()); assertTrue(value.getParent().isVisible()); });
        });
    }

    @Test public void nameComesFirstGroupSharesTrackTypeRowAndScheduleControlsShareARow() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = uniqueForm(true, (id, label) -> {}); panel.setSize(560, 780); layout(panel);
            Component name = find(panel, "goalName"), type = find(panel, "trackType"), group = find(panel, "goalGroup");
            Component cadence = find(panel, "cadence"), repeat = find(panel, "repeat");
            Container body = (Container) find(panel, "goalForm");
            assertTrue(y(name, body) < y(type, body)); assertEquals(y(type, body), y(group, body));
            assertTrue(SwingUtilities.convertPoint(type.getParent(), type.getLocation(), body).x < SwingUtilities.convertPoint(group.getParent(), group.getLocation(), body).x);
            assertEquals(y(cadence, body), y(repeat, body));
            assertTrue(SwingUtilities.convertPoint(cadence.getParent(), cadence.getLocation(), body).x < SwingUtilities.convertPoint(repeat.getParent(), repeat.getLocation(), body).x);
            assertTrue(cadence.getWidth() > 180); assertTrue(repeat.getWidth() > 180);
            panel.setSize(480, 650); layout(panel);
            assertNull(find(panel, "timeZone"));
            for (String field : new String[]{"goalName", "trackType", "countMetric", "goalAmount", "cadence", "repeat", "goalGroup"})
            {
                Component input = find(panel, field); Point point = SwingUtilities.convertPoint(input.getParent(), input.getLocation(), body);
                assertTrue(field + " extends past the left edge", point.x >= body.getInsets().left);
                assertTrue(field + " extends past the right edge", point.x + input.getWidth() <= body.getWidth() - body.getInsets().right);
            }
        });
    }

    @Test public void activitySearchRequiresExplicitSelectionAndSupportsKeyboardAndNoMatches() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            ActivityPicker picker = new ActivityPicker(HiscoreSkill.THE_CORRUPTED_GAUNTLET);
            int[] changes = {0}; picker.onChange(() -> changes[0]++);
            picker.searchField().setText("easy"); assertEquals(1, picker.filteredMetrics().size());
            assertEquals(HiscoreSkill.THE_CORRUPTED_GAUNTLET, picker.selected()); assertEquals(0, changes[0]);
            picker.searchField().getActionMap().get("choose").actionPerformed(null);
            assertEquals(HiscoreSkill.CLUE_SCROLL_EASY, picker.selected()); assertEquals(1, changes[0]);
            picker.searchField().setText("no such activity"); picker.searchField().getActionMap().get("choose").actionPerformed(null);
            assertEquals(HiscoreSkill.CLUE_SCROLL_EASY, picker.selected()); assertEquals(1, changes[0]);
            picker.searchField().setText("gauntlet"); picker.searchField().getActionMap().get("next").actionPerformed(null);
            picker.searchField().getActionMap().get("choose").actionPerformed(null);
            assertEquals(HiscoreSkill.THE_GAUNTLET, picker.selected()); assertEquals(2, changes[0]);
        });
    }

    private static int y(Component child, Container parent) { return SwingUtilities.convertPoint(child.getParent(), child.getLocation(), parent).y; }
    private static int namedCount(Container parent, String name)
    {
        int count = 0; for (Component c : parent.getComponents()) { if (name.equals(c.getName())) { count++; } if (c instanceof Container) { count += namedCount((Container) c, name); } } return count;
    }
    private static Component find(Container parent, String name)
    {
        for (Component child : parent.getComponents()) { if (name.equals(child.getName())) { return child; } if (child instanceof Container) { Component found = find((Container) child, name); if (found != null) { return found; } } } return null;
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) { if (child instanceof Container) { layout((Container) child); } } }
}
