package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.JCheckBox;
import javax.swing.JSpinner;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import net.runelite.client.hiscore.HiscoreSkill;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalEditorTest
{
    @Test
    public void levelModeBuildsValidDeadlineCommandWithoutRecursiveUiEvents() throws Exception
    {
        AtomicReference<GoalSpec> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ProfileState state = new ProfileState("a".repeat(64));
            state.xp.put("MINING", 100_000L);
            JPanel form = GoalEditor.createForm(new GoalsView("test", "Saved", "", true, state, Instant.now()), null, saved::set, () -> {});
            JComboBox<?> mode = (JComboBox<?>) find(form, c -> c instanceof JComboBox && "Gain XP".equals(((JComboBox<?>) c).getItemAt(0)));
            assertNotNull(mode);
            mode.setSelectedIndex(1);
            ((JSpinner) find(form, c -> "targetLevel".equals(c.getName()))).setValue(82);
            JButton create = (JButton) find(form, c -> c instanceof JButton && "Create goal".equals(((JButton) c).getText()));
            create.doClick();
        });
        assertNotNull(saved.get());
        assertEquals(GoalSpec.Mode.LEVEL, saved.get().mode);
        assertEquals("Mining", saved.get().name);
        assertEquals(GoalSpec.Cadence.ONCE, saved.get().cadence);
        assertNotNull(saved.get().endDate);
        saved.get().validate();
    }

    @Test public void countPickerContainsEveryMetricSearchesAndSubmitsCountCommands() throws Exception
    {
        AtomicReference<GoalSpec> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JPanel form = GoalEditor.createForm(new GoalsView("test", "Saved", "", true, new ProfileState("a".repeat(64)), Instant.now()), null, saved::set, () -> {});
            JComboBox<?> type = (JComboBox<?>) find(form, c -> "trackType".equals(c.getName()));
            type.setSelectedIndex(1);
            ActivityPicker metric = (ActivityPicker) find(form, c -> "countMetric".equals(c.getName()));
            assertEquals(87, metric.filteredMetrics().size());
            JTextField search = metric.searchField();
            search.setText("clue scrolls (easy)");
            assertEquals(1, metric.filteredMetrics().size());
            assertEquals(HiscoreSkill.CLUE_SCROLL_EASY, metric.filteredMetrics().get(0));
            assertEquals(HiscoreSkill.THE_CORRUPTED_GAUNTLET, metric.selected());
            search.setText("unmatched activity");
            assertEquals(0, metric.filteredMetrics().size());
            search.setText("gauntlet");
            assertEquals(2, metric.filteredMetrics().size());
            metric.select(HiscoreSkill.THE_CORRUPTED_GAUNTLET);
            ((JComboBox<?>) find(form, c -> "repeat".equals(c.getName()))).setSelectedItem("Forever (∞)");
            ((JButton) find(form, c -> c instanceof JButton && "Create goal".equals(((JButton) c).getText()))).doClick();
        });
        assertNotNull(saved.get());
        assertEquals(GoalSpec.TrackType.COUNT, saved.get().trackType);
        assertEquals("THE_CORRUPTED_GAUNTLET", saved.get().metricId);
        assertEquals(GoalSpec.Mode.GAIN, saved.get().mode);
    }

    @Test public void editingLegacyLevelNamesKeepsCustomNamesAndRecordedProgress() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault(); LocalDate start = now.atZone(zone).toLocalDate();
            for (String original : new String[]{"99 Mining", "Mining level 99", "My mining project"})
            {
                GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
                engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, now);
                String id = engine.add(new GoalSpec(original, null, "MINING", GoalSpec.Mode.LEVEL, 0, 99, GoalSpec.Cadence.ONCE,
                    start, start.plusDays(30), zone), now);
                engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, now);
                GoalsView view = new GoalsView("test", "Character", "", true, engine.state(), now);
                AtomicReference<GoalSpec> saved = new AtomicReference<>();
                JPanel form = GoalEditor.createForm(view, view.goals.get(0), saved::set, () -> {});
                String expected = original.equals("My mining project") ? original : "Mining";
                assertEquals(expected, ((JTextField) find(form, c -> "goalName".equals(c.getName()))).getText());
                assertEquals("Displaying the shortened title does not rewrite saved data", original, engine.state().goals.get(id).spec.name);
                ((JButton) find(form, c -> "saveGoal".equals(c.getName()))).doClick();
                assertNotNull(saved.get()); engine.edit(id, saved.get(), now);
                assertEquals(expected, engine.state().goals.get(id).spec.name);
                assertEquals(100, engine.state().goals.get(id).current.progress());
            }
        });
    }

    @Test public void untilUniqueCreatesQuantityTargetsAndEditingKeepsEverySelection() throws Exception
    {
        AtomicReference<GoalSpec> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault();
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
            String groupId = engine.addGroup("Bosses");
            GoalsView view = new GoalsView("test", "Saved", "", true, engine.state(), now);
            JPanel form = GoalEditor.createForm(view, null, saved::set, () -> {});
            ((JComboBox<?>) find(form, c -> "goalGroup".equals(c.getName()))).setSelectedIndex(1);
            ((JComboBox<?>) find(form, c -> "trackType".equals(c.getName()))).setSelectedIndex(1);
            ((ActivityPicker) find(form, c -> "countMetric".equals(c.getName()))).select(HiscoreSkill.THE_CORRUPTED_GAUNTLET);
            ((JComboBox<?>) find(form, c -> "repeat".equals(c.getName()))).setSelectedIndex(2);
            ((JCheckBox) find(form, c -> "unique-23956".equals(c.getName()))).doClick();
            JSpinner quantity = (JSpinner) find(form, c -> "unique-total-23956".equals(c.getName()));
            assertEquals(5L, quantity.getValue()); quantity.setValue(6L);
            ((JCheckBox) find(form, c -> "unique-25859".equals(c.getName()))).doClick();
            // Filtering the unchanged boss must not rebuild and erase the selected quantities.
            ((ActivityPicker) find(form, c -> "countMetric".equals(c.getName()))).searchField().setText("corrupted");
            ((JButton) find(form, c -> c instanceof JButton && "Create goal".equals(((JButton) c).getText()))).doClick();
            assertNotNull(saved.get()); assertEquals(Map.of(23956, 6L, 25859, 1L), saved.get().uniqueTargets); assertNull(saved.get().endDate);
            String id = engine.add(saved.get(), now);
            GoalsView edited = new GoalsView("test", "Saved", "", true, engine.state(), now);
            JPanel edit = GoalEditor.createForm(edited, edited.goals.stream().filter(g -> g.id.equals(id)).findFirst().orElseThrow(), saved::set, () -> {}, (item, icon) -> {}, com.mygoals.domain.GoalDateFormat.AMERICAN, null, false);
            assertNull(find(edit, c -> "goalGroup".equals(c.getName())));
            ((JButton) find(edit, c -> c instanceof JButton && "Save changes".equals(((JButton) c).getText()))).doClick();
            assertEquals(Map.of(23956, 6L, 25859, 1L), saved.get().uniqueTargets); assertEquals(groupId, saved.get().groupId);
        });
    }

    @Test public void addingAnAlreadyOwnedUniqueThroughEditorImmediatelyUpdatesTodayCard() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.parse("2026-10-16T17:00:00Z"); ZoneId zone = ZoneId.of("America/Chicago");
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 17L)), false, now);
            String id = engine.add(new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(25859, 1L)), now);
            GoalsView original = new GoalsView("test", "", "", true, engine.state(), now);
            MyGoalsPanel panel = new MyGoalsPanel(null, (token, command) -> {}, () -> {}); panel.render(original);
            Component oldCard = find(panel, c -> ("today-" + id).equals(c.getName()));
            assertNotNull(oldCard); assertNull(find(panel, c -> "todayUnique-23956".equals(c.getName())));
            JPanel form = GoalEditor.createForm(original, original.goals.get(0), spec -> {
                engine.edit(id, spec, now); panel.render(new GoalsView("test", "", "", true, engine.state(), now));
            }, () -> {});
            ((JCheckBox) find(form, c -> "unique-23956".equals(c.getName()))).doClick();
            ((JSpinner) find(form, c -> "unique-total-23956".equals(c.getName()))).setValue(6L);
            ((JButton) find(form, c -> c instanceof JButton && "Save changes".equals(((JButton) c).getText()))).doClick();
            assertEquals(Map.of(25859, 1L), original.goals.get(0).spec.uniqueTargets);
            GoalsView updated = new GoalsView("test", "", "", true, engine.state(), now);
            assertEquals(Map.of(23956, 6L, 25859, 1L), updated.goals.get(0).spec.uniqueTargets);
            assertNull(updated.goals.get(0).pendingStart); assertEquals("ACTIVE", updated.goals.get(0).lifecycle);
            assertNotSame(oldCard, find(panel, c -> ("today-" + id).equals(c.getName())));
            javax.swing.JLabel armour = (javax.swing.JLabel) find(panel, c -> "todayUnique-23956".equals(c.getName()));
            assertEquals("(17/6)", armour.getText()); assertEquals(new java.awt.Color(102, 204, 128), armour.getForeground());
            assertNotNull(find(panel, c -> "todayUnique-25859".equals(c.getName())));
        });
    }

    @Test public void fractionalUniqueTotalCannotBeSilentlyTruncated() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalsView view = new GoalsView("test", "Saved", "", true, new ProfileState("a".repeat(64)), Instant.now());
            UniqueSelectionPanel panel = new UniqueSelectionPanel(view, null); panel.metric("THE_CORRUPTED_GAUNTLET");
            ((JCheckBox) find(panel, c -> "unique-23956".equals(c.getName()))).doClick();
            JSpinner quantity = (JSpinner) find(panel, c -> "unique-total-23956".equals(c.getName()));
            ((JSpinner.NumberEditor) quantity.getEditor()).getTextField().setText("6.5");
            assertThrows(IllegalArgumentException.class, panel::targets);
        });
    }

    @Test public void detailChecklistShowsOwnedRemainingAndUnknownQuantities() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault();
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            GoalSpec spec = new GoalSpec("Uniques", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.now(zone), null, zone, Map.of(23956, 6L, 25859, 1L));
            engine.add(spec, now);
            GoalsView view = new GoalsView("test", "Saved", "", true, engine.state(), now);
            Container unknown = (Container) MyGoalsPanel.detailContents(view.goals.get(0)).getViewport().getView();
            assertTrue(((javax.swing.JLabel) find(unknown, c -> "detailUniqueAmount-23956".equals(c.getName()))).getText().contains("? / 6"));
            engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L, 25859, 1L)), false, now);
            view = new GoalsView("test", "Saved", "", true, engine.state(), now);
            Container details = (Container) MyGoalsPanel.detailContents(view.goals.get(0)).getViewport().getView();
            assertEquals("4 / 6 • 2 remaining", ((javax.swing.JLabel) find(details, c -> "detailUniqueAmount-23956".equals(c.getName()))).getText());
            assertTrue(((javax.swing.JLabel) find(details, c -> "detailUniqueSummary".equals(c.getName()))).getText().contains("1/2"));
            assertNotNull(find(details, c -> c instanceof javax.swing.JLabel && "Enhanced crystal weapon seed".equals(((javax.swing.JLabel) c).getText())));
        });
    }

    @Test public void bossDefaultsToUniqueOnlyOnNewSelectionAndPreservesExistingRepeatChoices() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalsView view = new GoalsView("test", "Saved", "", true, new ProfileState("a".repeat(64)), Instant.now());
            JPanel form = GoalEditor.createForm(view, null, spec -> {}, () -> {});
            ((JComboBox<?>) find(form, c -> "trackType".equals(c.getName()))).setSelectedIndex(1);
            JComboBox<?> repeat = (JComboBox<?>) find(form, c -> "repeat".equals(c.getName()));
            ActivityPicker picker = (ActivityPicker) find(form, c -> "countMetric".equals(c.getName()));
            assertEquals("Until unique", repeat.getSelectedItem());
            repeat.setSelectedItem("Forever (∞)"); ((JTextField) find(form, c -> "goalAmount".equals(c.getName()))).setText("20");
            assertEquals("Forever (∞)", repeat.getSelectedItem());
            picker.select(HiscoreSkill.BRUTUS); assertEquals("Until unique", repeat.getSelectedItem());
            picker.select(HiscoreSkill.SOUL_WARS_ZEAL); assertEquals("Forever (∞)", repeat.getSelectedItem()); assertEquals(2, repeat.getItemCount());
            picker.select(HiscoreSkill.BRUTUS); assertEquals("Until unique", repeat.getSelectedItem());
            Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault();
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.add(new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                GoalSpec.Cadence.WEEKLY, LocalDate.now(zone), null, zone), now);
            GoalsView existing = new GoalsView("test", "Saved", "", true, engine.state(), now);
            JPanel edit = GoalEditor.createForm(existing, existing.goals.get(0), spec -> {}, () -> {});
            assertEquals("Forever (∞)", ((JComboBox<?>) find(edit, c -> "repeat".equals(c.getName()))).getSelectedItem());
        });
    }

    @Test public void lifetimeKcEditorCreatesAndPreservesAbsoluteTargetWithIndependentPeriodAmount() throws Exception
    {
        AtomicReference<GoalSpec> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault();
            ProfileState state = new ProfileState("a".repeat(64)); state.counts.put("BRUTUS", 987L);
            GoalEngine engine = new GoalEngine(state, zone);
            JPanel form = GoalEditor.createForm(new GoalsView("test", "Saved", "", true, state, now), null, saved::set, () -> {});
            ((JComboBox<?>) find(form, c -> "trackType".equals(c.getName()))).setSelectedIndex(1);
            ((ActivityPicker) find(form, c -> "countMetric".equals(c.getName()))).select(HiscoreSkill.BRUTUS);
            ((JComboBox<?>) find(form, c -> "repeat".equals(c.getName()))).setSelectedItem("Until KC");
            assertTrue(find(form, c -> "totalKcTarget".equals(c.getName())).getParent().isVisible());
            javax.swing.JTextArea help = (javax.swing.JTextArea) find(form, c -> "totalKcHelp".equals(c.getName()));
            assertTrue(help.getText().contains("987"));
            JTextField total = (JTextField) find(form, c -> "totalKcTarget".equals(c.getName()));
            total.setText("980"); assertTrue(help.getText().contains("Already reached")); total.setText("1000");
            ((JButton) find(form, c -> c instanceof JButton && "Create goal".equals(((JButton) c).getText()))).doClick();
            assertNotNull(saved.get()); assertEquals(10, saved.get().amount); assertEquals(Long.valueOf(1000), saved.get().totalCountTarget);
            assertTrue(saved.get().uniqueTargets.isEmpty()); assertNull(saved.get().endDate);
            engine.add(saved.get(), now); GoalsView existing = new GoalsView("test", "Saved", "", true, state, now);
            JPanel edit = GoalEditor.createForm(existing, existing.goals.get(0), saved::set, () -> {});
            assertEquals("Until KC", ((JComboBox<?>) find(edit, c -> "repeat".equals(c.getName()))).getSelectedItem());
            assertEquals("1000", ((JTextField) find(edit, c -> "totalKcTarget".equals(c.getName()))).getText());
            ((JButton) find(edit, c -> c instanceof JButton && "Save changes".equals(((JButton) c).getText()))).doClick();
            assertEquals(Long.valueOf(1000), saved.get().totalCountTarget);
        });
    }

    @Test public void bothDateFormatsApplyToPickersAndPreviewWithoutChangingSavedDates() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault(); LocalDate start = LocalDate.now(zone).plusDays(2), end = start.plusDays(13);
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
            engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 1000, 0, GoalSpec.Cadence.ONCE, start, end, zone), now);
            GoalsView view = new GoalsView("test", "", "", true, engine.state(), now);
            for (com.mygoals.domain.GoalDateFormat format : com.mygoals.domain.GoalDateFormat.values())
            {
                AtomicReference<GoalSpec> saved = new AtomicReference<>();
                JPanel form = GoalEditor.createForm(view, view.goals.get(0), saved::set, () -> {}, (id, icon) -> {}, format, null);
                CalendarPicker first = (CalendarPicker) find(form, c -> "startDate".equals(c.getName()));
                CalendarPicker last = (CalendarPicker) find(form, c -> "endDate".equals(c.getName()));
                assertEquals(format.date(start) + "  ▾", ((JButton) first.getComponent(0)).getText());
                assertEquals(format.date(end) + "  ▾", ((JButton) last.getComponent(0)).getText());
                javax.swing.JTextArea preview = (javax.swing.JTextArea) find(form, c -> "goalPreview".equals(c.getName()));
                assertTrue(preview.getText().contains(format.date(start) + " through " + format.date(end)));
                last.setDate(end.plusDays(1));
                assertEquals(format.date(end.plusDays(1)) + "  ▾", ((JButton) last.getComponent(0)).getText());
                ((JButton) find(form, c -> "saveGoal".equals(c.getName()))).doClick();
                assertEquals(start.toString(), saved.get().startDate); assertEquals(end.plusDays(1).toString(), saved.get().endDate);
                assertEquals(end.toString(), view.goals.get(0).spec.endDate);
            }
        });
    }

    @Test public void editorDeletesOnlyAfterConfirmationAndCompletedGoalsRemainDeletable() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean achieved : new boolean[]{false, true})
            {
                Instant now = Instant.now(); ZoneId zone = ZoneId.systemDefault(); LocalDate date = LocalDate.now(zone);
                GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone); engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
                String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE, date, date.plusDays(2), zone), now);
                if (achieved) { engine.observe(Map.of("MINING", 200L), GoalEngine.LIVE, now); }
                GoalsView view = new GoalsView("test", "", "", true, engine.state(), now);
                java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
                JPanel form = GoalEditor.createForm(view, view.goals.get(0), spec -> fail("Delete must not submit a target edit"), closed::incrementAndGet,
                    (item, icon) -> {}, com.mygoals.domain.GoalDateFormat.EUROPEAN, () -> {
                        if (!approved.get()) { return false; } engine.remove(id); return true;
                    });
                JButton save = (JButton) find(form, c -> "saveGoal".equals(c.getName())); assertEquals(!achieved, save.isEnabled());
                if (achieved) { assertFalse(find(form, c -> "goalName".equals(c.getName())).isEnabled()); save.doClick(); }
                JButton remove = (JButton) find(form, c -> "deleteGoal".equals(c.getName())); assertTrue(remove.isEnabled());
                remove.doClick(); assertTrue(engine.state().goals.containsKey(id)); assertEquals(0, closed.get());
                approved.set(true); remove.doClick(); assertFalse(engine.state().goals.containsKey(id)); assertEquals(1, closed.get());
                JPanel readOnly = GoalEditor.createForm(new GoalsView("test", "", "", false, new ProfileState("a".repeat(64)), now), view.goals.get(0), spec -> {}, () -> {},
                    (item, icon) -> {}, com.mygoals.domain.GoalDateFormat.AMERICAN, () -> { fail("Read-only deletion"); return false; });
                assertFalse(find(readOnly, c -> "deleteGoal".equals(c.getName())).isEnabled());
            }
        });
    }

    private Component find(Container parent, java.util.function.Predicate<Component> matches)
    {
        for (Component child : parent.getComponents())
        {
            if (matches.test(child)) { return child; }
            if (child instanceof Container) { Component found = find((Container) child, matches); if (found != null) { return found; } }
        }
        return null;
    }
}
