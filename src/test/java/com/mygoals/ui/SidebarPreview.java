package com.mygoals.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import com.mygoals.engine.UniqueGoalTest;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.runelite.client.game.SkillIconManager;

/** Synthetic UI fixture only; never loaded by the plugin or stored in a player profile. */
public final class SidebarPreview
{
    public static void main(String[] args) throws Exception
    {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        com.mygoals.celebration.CompletionPreview.write(output);
        UIManager.setLookAndFeel(new FlatDarkLaf());
        Instant now = Instant.parse("2026-10-16T17:00:00Z");
        ZoneId zone = ZoneId.of("America/Chicago");
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.observe(Map.of("MINING", 100_000L, "WOODCUTTING", 2_000_000L, "AGILITY", 1_650_000L), GoalEngine.RECOVERED, now);
        String group = engine.addGroup("Skilling");
        engine.add(new GoalSpec("Mining this week", group, "MINING", GoalSpec.Mode.GAIN, 100_000, 0, GoalSpec.Cadence.WEEKLY,
            LocalDate.parse("2026-10-14"), null, zone), now);
        engine.add(new GoalSpec("Woodcutting", group, "WOODCUTTING", GoalSpec.Mode.GAIN, 100_000, 0, GoalSpec.Cadence.WEEKLY,
            LocalDate.parse("2026-10-12"), null, zone), now);
        engine.add(new GoalSpec("Level 80 Agility", null, "AGILITY", GoalSpec.Mode.LEVEL, 0, 80, GoalSpec.Cadence.ONCE,
            LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-29"), zone), now);
        engine.observe(Map.of("MINING", 120_000L, "WOODCUTTING", 2_064_250L), GoalEngine.LIVE, now);
        engine.observe(Map.of("MINING", 127_400L), GoalEngine.RECOVERED, now);
        SwingUtilities.invokeAndWait(() -> {
            try
            {
                DailyChartPreview.write(output);
                MyGoalsPanel panel = new MyGoalsPanel(new SkillIconManager(), (token, command) -> {}, () -> {});
                panel.render(new GoalsView("preview", "Synthetic character", "", true, engine.state(), now));
                panel.setSize(225, 700);
                for (int index = 0; index < 2; index++)
                {
                    for (int pass = 0; pass < 4; pass++) { layout(panel); }
                    BufferedImage image = new BufferedImage(225, 700, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                    ImageIO.write(image, "png", output.resolve(new String[]{"today", "goals"}[index] + ".png").toFile());
                }
                GoalEngine periods = new GoalEngine(new ProfileState("b".repeat(64)), zone);
                periods.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now);
                for (GoalSpec.Cadence cadence : new GoalSpec.Cadence[]{GoalSpec.Cadence.DAILY, GoalSpec.Cadence.WEEKLY, GoalSpec.Cadence.MONTHLY})
                {
                    periods.add(new GoalSpec("Mining " + cadence.name().toLowerCase(java.util.Locale.ROOT), null, "MINING", GoalSpec.Mode.GAIN, 200, 0,
                        cadence, LocalDate.parse("2026-10-16"), null, zone), now);
                }
                MyGoalsPanel periodPanel = new MyGoalsPanel(new SkillIconManager(), (token, command) -> {}, () -> {});
                periodPanel.render(new GoalsView("periods-preview", "Synthetic character", "", true, periods.state(), now));
                periodPanel.setSize(225, 820);
                for (int pass = 0; pass < 4; pass++) { layout(periodPanel); }
                BufferedImage periodImage = new BufferedImage(225, 820, BufferedImage.TYPE_INT_ARGB);
                Graphics2D periodGraphics = periodImage.createGraphics(); periodPanel.paint(periodGraphics); periodGraphics.dispose();
                ImageIO.write(periodImage, "png", output.resolve("periods.png").toFile());
                GoalEngine planning = new GoalEngine(new ProfileState("c".repeat(64)), zone);
                planning.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now);
                planning.add(new GoalSpec("Mining this week", null, "MINING", GoalSpec.Mode.GAIN, 100_000, 0,
                    GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
                planning.add(new GoalSpec("Daily mining", null, "MINING", GoalSpec.Mode.GAIN, 200, 0,
                    GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-16"), null, zone), now);
                String postponed = planning.add(new GoalSpec("Corrupted Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
                    GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
                planning.observe(Map.of("MINING", 100_250L), GoalEngine.LIVE, now); planning.postponeGoal(postponed, true, now);
                MyGoalsPanel planningPanel = new MyGoalsPanel(new SkillIconManager(), (token, command) -> {}, () -> {});
                planningPanel.render(new GoalsView("planning-preview", "Synthetic character", "", true, planning.state(), now));
                render(planningPanel, 225, 970, output.resolve("tomorrow.png"));
                JPanel editor = GoalEditor.createForm(new GoalsView("preview", "Saved", "", true, engine.state(), now), null, spec -> {}, () -> {});
                editor.setSize(560, 780);
                for (int pass = 0; pass < 4; pass++) { layout(editor); }
                Container footer = (Container) editor.getComponent(1);
                for (Component button : footer.getComponents())
                {
                    if (button.getY() < 0 || button.getY() + button.getHeight() > footer.getHeight())
                    {
                        throw new IllegalStateException("Editor action is outside its footer");
                    }
                }
                BufferedImage image = new BufferedImage(560, 780, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics(); editor.paint(graphics); graphics.dispose();
                ImageIO.write(image, "png", output.resolve("editor.png").toFile());
                GoalEngine counts = new GoalEngine(new ProfileState("c".repeat(64)), zone);
                String monsters = counts.addGroup("Monsters");
                String gauntlet = counts.add(new GoalSpec("Corrupted Gauntlet", monsters, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET",
                    GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
                counts.add(new GoalSpec("Easy clues", null, GoalSpec.TrackType.COUNT, "CLUE_SCROLL_EASY", GoalSpec.Mode.GAIN, 5, 0,
                    GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
                counts.observeCount("THE_CORRUPTED_GAUNTLET", 104, true, GoalEngine.LIVE, null, now);
                counts.observeCount("THE_CORRUPTED_GAUNTLET", 105, true, GoalEngine.LIVE, null, now);
                counts.observeCount("CLUE_SCROLL_EASY", 42, true, GoalEngine.LIVE, null, now);
                GoalsView countsView = new GoalsView("counts-preview", "Synthetic character", "", true, counts.state(), now);
                MyGoalsPanel countsPanel = new MyGoalsPanel(new SkillIconManager(), (token, command) -> {}, () -> {});
                countsPanel.render(countsView); countsPanel.setSize(225, 700);
                for (int index = 0; index < 2; index++)
                {
                    for (int pass = 0; pass < 4; pass++) { layout(countsPanel); }
                    BufferedImage screenshot = new BufferedImage(225, 700, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D context = screenshot.createGraphics(); countsPanel.paint(context); context.dispose();
                    ImageIO.write(screenshot, "png", output.resolve(new String[]{"counts-today", "counts"}[index] + ".png").toFile());
                }
                JPanel countEditor = GoalEditor.createForm(countsView, countsView.goals.stream().filter(g -> g.id.equals(gauntlet)).findFirst().orElseThrow(), spec -> {}, () -> {});
                countEditor.setSize(560, 780);
                for (int pass = 0; pass < 4; pass++) { layout(countEditor); }
                BufferedImage countForm = new BufferedImage(560, 780, BufferedImage.TYPE_INT_ARGB);
                Graphics2D countContext = countForm.createGraphics(); countEditor.paint(countContext); countContext.dispose();
                ImageIO.write(countForm, "png", output.resolve("count-editor.png").toFile());
                counts.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
                String uniques = counts.add(new GoalSpec("Corrupted Gauntlet uniques", monsters, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET",
                    GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L, 25859, 1L)), now);
                GoalsView uniquesView = new GoalsView("uniques-preview", "Synthetic character", "", true, counts.state(), now);
                MyGoalsPanel uniquePanel = new MyGoalsPanel(new SkillIconManager(), (token, command) -> {}, () -> {});
                uniquePanel.render(uniquesView);
                render(uniquePanel, 225, 780, output.resolve("uniques.png"));
                JPanel uniqueEditor = GoalEditor.createForm(uniquesView, uniquesView.goals.stream().filter(g -> g.id.equals(uniques)).findFirst().orElseThrow(), spec -> {}, () -> {});
                render(uniqueEditor, 560, 780, output.resolve("unique-editor.png"));
                GoalEngine unknown = new GoalEngine(new ProfileState("d".repeat(64)), zone);
                String pending = unknown.add(new GoalSpec("Corrupted Gauntlet uniques", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET",
                    GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L)), now);
                GoalsView unknownView = new GoalsView("unknown-preview", "Synthetic character", "", true, unknown.state(), now);
                render(GoalEditor.createForm(unknownView, unknownView.goals.get(0), spec -> {}, () -> {}), 560, 780, output.resolve("unique-editor-refresh.png"));
                JPanel detail = new JPanel(new java.awt.BorderLayout());
                detail.add(MyGoalsPanel.detailContents(uniquesView.goals.stream().filter(g -> g.id.equals(uniques)).findFirst().orElseThrow()));
                render(detail, 480, 680, output.resolve("unique-details.png"));
                JPanel xpDetail = new JPanel(new java.awt.BorderLayout()); xpDetail.add(MyGoalsPanel.detailContents(new GoalsView("preview", "Saved", "", true, engine.state(), now).goals.get(0)));
                render(xpDetail, 480, 680, output.resolve("xp-details.png"));
                JPanel deadlineDetail = new JPanel(new java.awt.BorderLayout()); deadlineDetail.add(MyGoalsPanel.detailContents(deadlineGoal()));
                render(deadlineDetail, 480, 720, output.resolve("deadline-details.png"));
                JPanel historyDetail = new JPanel(new java.awt.BorderLayout()); historyDetail.add(MyGoalsPanel.detailContents(historyGoal()));
                render(historyDetail, 480, 820, output.resolve("period-history.png"));
                GoalDetailsPanel archiveDetail = archivedHistoryDetails();
                render(archiveDetail, 480, 820, output.resolve("archived-history.png"));
                ((javax.swing.JButton) findButton(archiveDetail, "Load older periods")).doClick();
                render(archiveDetail, 480, 820, output.resolve("archived-history-loaded.png"));
                ManageClockPanel clockPreview = new ManageClockPanel(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC), () -> zone, () -> {});
                render(clockPreview, 450, 280, output.resolve("manage-clock.png"));
                GoalEngine completed = new GoalEngine(new ProfileState("e".repeat(64)), zone);
                completed.collectionSnapshot(6413, UniqueGoalTest.page(6413, Map.of(33101, 2L)), false, now);
                completed.add(new GoalSpec("Brutus", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                    GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(33101, 1L)), now);
                MyGoalsPanel completedPanel = new MyGoalsPanel(null, (token, command) -> {}, () -> {});
                completedPanel.render(new GoalsView("completed-preview", "Synthetic character", "", true, completed.state(), now));
                render(completedPanel, 225, 700, output.resolve("unique-completed.png"));
                GoalEngine milestone = new GoalEngine(new ProfileState("f".repeat(64)), zone);
                String milestoneId = milestone.add(new GoalSpec("Brutus to 1,000 KC", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
                    GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(), 1000L), now);
                milestone.observeCount("BRUTUS", 987, false, GoalEngine.RECOVERED, null, now);
                GoalsView milestoneView = new GoalsView("kc-preview", "Synthetic character", "", true, milestone.state(), now);
                render(GoalEditor.createForm(milestoneView, milestoneView.goals.get(0), spec -> {}, () -> {}), 560, 780, output.resolve("kc-editor.png"));
                MyGoalsPanel milestonePanel = new MyGoalsPanel(null, (token, command) -> {}, () -> {});
                milestonePanel.render(milestoneView);
                render(milestonePanel, 225, 700, output.resolve("kc-goal.png"));
                JPanel kcDetail = new JPanel(new java.awt.BorderLayout()); kcDetail.add(MyGoalsPanel.detailContents(milestoneView.goals.get(0)));
                render(kcDetail, 480, 680, output.resolve("kc-details.png"));
                for (JPanel displayed : new JPanel[]{panel, countsPanel, milestonePanel})
                {
                    for (String title : new String[]{"New goal", "New group"})
                    {
                        Component button = findButton(displayed, title);
                        if (button == null && title.equals("New group")) { continue; }
                        if (button == null) { throw new IllegalStateException("Missing header action: " + title); }
                        java.awt.Point location = SwingUtilities.convertPoint(button.getParent(), button.getLocation(), displayed);
                        if (location.x < 0 || location.x + button.getWidth() > displayed.getWidth()) { throw new IllegalStateException("Header action clipped: " + title); }
                    }
                    Component gear = ManageClockPanelTest.find(displayed, "goalsSettings");
                    java.awt.Point location = SwingUtilities.convertPoint(gear.getParent(), gear.getLocation(), displayed);
                    if (location.x < 0 || location.x + gear.getWidth() > displayed.getWidth()) { throw new IllegalStateException("Gear action clipped"); }
                }
            }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        System.out.println("Sidebar previews: " + output.toAbsolutePath());
    }
    static void render(JPanel panel, int width, int height, Path path) throws Exception
    {
        panel.setSize(width, height); for (int i = 0; i < 4; i++) { layout(panel); }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }
    static GoalsView.Goal deadlineGoal()
    {
        ZoneId zone = ZoneId.of("America/Chicago"); Instant start = Instant.parse("2026-10-01T17:00:00Z"), now = Instant.parse("2026-10-16T17:00:00Z");
        GoalEngine engine = new GoalEngine(new ProfileState("9".repeat(64)), zone);
        engine.observe(Map.of("AGILITY", 1_650_000L), GoalEngine.RECOVERED, start);
        engine.add(new GoalSpec("Level 80 Agility by December", null, "AGILITY", GoalSpec.Mode.LEVEL, 0, 80, GoalSpec.Cadence.ONCE,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), zone), start);
        engine.observe(Map.of("AGILITY", 1_720_500L), GoalEngine.LIVE, Instant.parse("2026-10-15T17:00:00Z"));
        engine.observe(Map.of("AGILITY", 1_722_000L), GoalEngine.LIVE, now);
        return new GoalsView("deadline-preview", "Synthetic", "", true, engine.state(), now).goals.get(0);
    }
    static GoalsView.Goal historyGoal()
    {
        ZoneId zone = ZoneId.of("America/Chicago"); Instant first = Instant.parse("2026-10-10T17:00:00Z");
        GoalEngine engine = new GoalEngine(new ProfileState("8".repeat(64)), zone);
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, first);
        engine.add(new GoalSpec("Gauntlet daily uniques", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 2, 0,
            GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-10"), null, zone, Map.of(23956, 6L)), first);
        long total = 100; engine.observeCount("THE_CORRUPTED_GAUNTLET", total, false, GoalEngine.LIVE, null, first);
        int[] daily = {2, 1, 2, 0, 1, 2, 1};
        for (int i = 0; i < daily.length; i++)
        {
            Instant day = LocalDate.parse("2026-10-10").plusDays(i).atTime(12, 0).atZone(zone).toInstant(); engine.tick(day);
            for (int count = 0; count < daily[i]; count++) { engine.observeCount("THE_CORRUPTED_GAUNTLET", ++total, true, GoalEngine.LIVE, null, day); }
        }
        return new GoalsView("history-preview", "Synthetic", "", true, engine.state(), Instant.parse("2026-10-16T17:00:00Z")).goals.get(0);
    }
    private static GoalDetailsPanel archivedHistoryDetails()
    {
        ZoneId zone = ZoneId.of("America/Chicago"); LocalDate first = LocalDate.parse("2026-01-01"), now = LocalDate.parse("2026-10-16");
        GoalEngine engine = new GoalEngine(new ProfileState("9".repeat(64)), zone);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, first.atTime(12, 0).atZone(zone).toInstant());
        String id = engine.add(new GoalSpec("Daily Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.DAILY,
            first, null, zone), first.atTime(12, 0).atZone(zone).toInstant());
        engine.observe(Map.of("MINING", 100_100L), GoalEngine.LIVE, first.atTime(12, 0).atZone(zone).toInstant());
        engine.tick(first.plusDays(1).atTime(12, 0).atZone(zone).toInstant()); engine.tick(now.atTime(12, 0).atZone(zone).toInstant());
        var fragments = com.mygoals.persistence.HistoryArchive.detach(engine.state(), now);
        var old = fragments.values().stream().filter(f -> f.goals.containsKey(id)).flatMap(f -> f.goals.get(id).history.stream()).collect(java.util.stream.Collectors.toList());
        GoalDetailsPanel panel = new GoalDetailsPanel((item, label) -> {}, (spec, label) -> GoalIcons.bind(spec, label, new SkillIconManager(), null));
        panel.setHistoryLoader((before, completed) -> completed.accept(new GoalsView.HistoryPage(old, "2026-01", false, "")));
        panel.render(new GoalsView("archive-preview", "Synthetic", "", true, engine.state(), now.atTime(12, 0).atZone(zone).toInstant()).goals.get(0), false);
        return panel;
    }

    private static void layout(Container container)
    {
        container.doLayout();
        for (Component child : container.getComponents()) { if (child instanceof Container) { layout((Container) child); } }
    }
    private static Component findButton(Container container, String text)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof javax.swing.AbstractButton && text.equals(((javax.swing.AbstractButton) child).getText())) { return child; }
            if (child instanceof Container) { Component found = findButton((Container) child, text); if (found != null) { return found; } }
        }
        return null;
    }
}
