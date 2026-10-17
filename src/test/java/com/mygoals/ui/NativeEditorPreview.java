package com.mygoals.ui;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.GoalDateFormat;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.AsyncBufferedImage;

/** Standalone logged-out visual check. Only synthetic goals and public native item sprites are exported. */
@PluginDescriptor(name = "Advanced Goal Pacer native UI preview", hidden = true)
public final class NativeEditorPreview extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private net.runelite.client.plugins.PluginManager plugins;
    @Inject private ItemManager itemManager;
    @Inject private SkillIconManager skillIcons;
    @Inject private SpriteManager sprites;
    @Inject private net.runelite.client.ui.ClientToolbar toolbar;
    @Inject private net.runelite.client.config.ConfigManager configManager;
    private final Map<Integer, AsyncBufferedImage> images = new LinkedHashMap<>();
    private final AtomicInteger loaded = new AtomicInteger();
    private final long deadline = System.nanoTime() + 120_000_000_000L;

    public static void main(String[] args) throws Exception
    {
        System.setProperty("mygoals.nativePreviewOutput", args[0]);
        ExternalPluginManager.loadBuiltin(com.mygoals.MyGoalsPlugin.class, NativeEditorPreview.class);
        RuneLite.main(Arrays.copyOfRange(args, 1, args.length));
    }

    @Override protected void startUp()
    {
        clientThread.invokeLater(() -> {
            if (System.nanoTime() > deadline) { System.err.println("Native editor preview timed out before item sprites loaded"); System.exit(1); }
            if (client.getGameState() != GameState.LOGIN_SCREEN) { return false; }
            if (plugins.getPlugins().stream().noneMatch(p -> p instanceof com.mygoals.MyGoalsPlugin && plugins.isPluginActive(p))) { return false; }
            if (images.isEmpty())
            {
                for (int id : new int[]{23757, 23956, 4207, 25859, 23859})
                {
                    AsyncBufferedImage image = itemManager.getImage(id);
                    if (image == null) { throw new IllegalStateException("Item sprite unavailable"); }
                    images.put(id, image); image.onLoaded(loaded::incrementAndGet);
                }
            }
            if (loaded.get() < images.size()) { return false; }
            if (sprites.getSprite(HiscoreSkill.THE_CORRUPTED_GAUNTLET.getSpriteId(), 0) == null) { return false; }
            try
            {
                Path output = Path.of(System.getProperty("mygoals.nativePreviewOutput")); Files.createDirectories(output);
                com.mygoals.celebration.CompletionPreview.write(output);
                for (var image : images.values())
                {
                    boolean visible = false;
                    for (int y = 0; y < image.getHeight(); y++) { for (int x = 0; x < image.getWidth(); x++) { visible |= (image.getRGB(x, y) >>> 24) != 0; } }
                    if (!visible) { throw new IllegalStateException("An item sprite was empty"); }
                }
                SwingUtilities.invokeAndWait(() -> {
                    try
                    {
                        DailyChartPreview.write(output);
                        verifySidebarWindowSize(output);
                        verifyDailyAgenda(output);
                        verifyFinishedHistory(output);
                        verifyPresentationPreferences(output);
                        for (boolean baseline : new boolean[]{true, false})
                        {
                            Instant now = Instant.parse("2026-10-16T17:00:00Z"); ZoneId zone = ZoneId.of("America/Chicago");
                            GoalEngine engine = new GoalEngine(new ProfileState("e".repeat(64)), zone);
                            if (baseline) { engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 17L, 23859, 1L)), false, now); }
                            engine.add(new GoalSpec("Corrupted Gauntlet uniques", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
                                GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 20L, 25859, 1L)), now);
                            GoalsView view = new GoalsView("native-preview", "Synthetic", "", true, engine.state(), now);
                            SidebarPreview.render(GoalEditor.createForm(view, view.goals.get(0), spec -> {}, () -> {}, (id, label) -> images.get(id).addTo(label)),
                                560, 820, output.resolve(baseline ? "unique-editor-native.png" : "unique-editor-refresh-native.png"));
                            GoalDetailsPanel details = new GoalDetailsPanel((id, label) -> images.get(id).addTo(label),
                                (spec, label) -> GoalIcons.bind(spec, label, skillIcons, sprites)); details.render(view.goals.get(0), false);
                            javax.swing.JPanel frame = new javax.swing.JPanel(new java.awt.BorderLayout()); frame.add(new javax.swing.JScrollPane(details));
                            SidebarPreview.render(frame, 480, 680, output.resolve(baseline ? "unique-details-native.png" : "unique-details-refresh-native.png"));
                            if (baseline)
                            {
                                MyGoalsPanel sidebar = new MyGoalsPanel(skillIcons, sprites, itemManager, (token, command) -> {}, () -> {}, () -> {}, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
                                sidebar.render(view);
                                javax.swing.JLabel remainingSeed = (javax.swing.JLabel) find(sidebar, "todayUnique-23956");
                                if (remainingSeed.getIcon() == null || remainingSeed.getIcon().getIconWidth() != 24 || !remainingSeed.getText().equals("(17/20)") ||
                                    remainingSeed.getHorizontalTextPosition() != javax.swing.JLabel.RIGHT || !((javax.swing.JLabel) find(sidebar, "todayUnique-25859")).getText().equals("(0/1)"))
                                { throw new IllegalStateException("Today unique icon or inline current/target quantity missing"); }
                                SidebarPreview.render(sidebar, 225, 700, output.resolve("today-unified-native.png"));
                                java.awt.Component card = find(sidebar, "today-" + view.goals.get(0).id);
                                card.dispatchEvent(new java.awt.event.MouseEvent(card, java.awt.event.MouseEvent.MOUSE_CLICKED,
                                    System.currentTimeMillis(), 0, 4, 4, 1, false, java.awt.event.MouseEvent.BUTTON1));
                                javax.swing.JDialog dialog = Arrays.stream(java.awt.Window.getWindows()).filter(w -> "goalDetailsDialog".equals(w.getName()) && w.isVisible())
                                    .map(w -> (javax.swing.JDialog) w).findFirst().orElseThrow();
                                if (dialog.getModalityType() != java.awt.Dialog.ModalityType.MODELESS) { throw new IllegalStateException("Details blocked game interaction"); }
                                if (((javax.swing.JLabel) find(dialog, "detailGoalIcon")).getIcon() == null) { throw new IllegalStateException("Boss header icon missing"); }
                                engine.observeCount("THE_CORRUPTED_GAUNTLET", 104, true, GoalEngine.LIVE, null, now);
                                sidebar.render(new GoalsView("native-preview", "Synthetic", "", true, engine.state(), now));
                                String amount = ((javax.swing.JLabel) find(dialog, "todayProgressAmount")).getText();
                                if (!amount.equals("1 / 2 completions")) { throw new IllegalStateException("Open details did not update"); }
                                engine.postponeGoal(view.goals.get(0).id, true, now);
                                sidebar.render(new GoalsView("native-preview", "Synthetic", "", true, engine.state(), now));
                                javax.swing.JTextArea forecast = (javax.swing.JTextArea) find(dialog, "detailTomorrow");
                                if (!forecast.isVisible() || !forecast.getText().contains("Tomorrow: 2 completions")) { throw new IllegalStateException("Postponed details did not update"); }
                                if (find(sidebar, "tomorrow-" + view.goals.get(0).id) == null) { throw new IllegalStateException("Tomorrow card missing"); }
                                SidebarPreview.render(sidebar, 225, 640, output.resolve("tomorrow-native.png"));
                                sidebar.render(new GoalsView("different-character", "Synthetic", "", true, engine.state(), now));
                                if (dialog.isDisplayable()) { throw new IllegalStateException("Character switch kept the details open"); }
                                java.awt.Component secondCard = find(sidebar, "tomorrow-" + view.goals.get(0).id);
                                secondCard.dispatchEvent(new java.awt.event.MouseEvent(secondCard, java.awt.event.MouseEvent.MOUSE_CLICKED,
                                    System.currentTimeMillis(), 0, 4, 4, 1, false, java.awt.event.MouseEvent.BUTTON1));
                                sidebar.closeDetails();
                                if (Arrays.stream(java.awt.Window.getWindows()).anyMatch(w -> "goalDetailsDialog".equals(w.getName()) && w.isDisplayable()))
                                { throw new IllegalStateException("Details disposal left a window open"); }
                                menuAction(sidebar, "manageClock").doClick();
                                javax.swing.JDialog clockDialog = Arrays.stream(java.awt.Window.getWindows()).filter(w -> "manageClockDialog".equals(w.getName()) && w.isVisible())
                                    .map(w -> (javax.swing.JDialog) w).findFirst().orElseThrow();
                                ManageClockPanel clockPanel = (ManageClockPanel) find(clockDialog, "manageClockPanel");
                                if (clockDialog.getModalityType() != java.awt.Dialog.ModalityType.MODELESS || !clockPanel.ticking())
                                { throw new IllegalStateException("Clock was blocking or not ticking"); }
                                if (find(clockPanel, "clockTimeZone") != null || find(clockPanel, "saveClock") != null)
                                { throw new IllegalStateException("Local clock exposed a time-zone setting"); }
                                SidebarPreview.render(clockPanel, 450, 280, output.resolve("manage-clock-native.png"));
                                sidebar.render(new GoalsView("clock-account-switch", "Synthetic", "", true, engine.state(), now));
                                if (clockDialog.isDisplayable() || clockPanel.ticking()) { throw new IllegalStateException("Character switch kept the clock running"); }
                                menuAction(sidebar, "manageClock").doClick(); sidebar.closeDialogs();
                                if (Arrays.stream(java.awt.Window.getWindows()).anyMatch(w -> "manageClockDialog".equals(w.getName()) && w.isDisplayable()))
                                { throw new IllegalStateException("Clock disposal left a window open"); }
                                if (((javax.swing.JLabel) find(sidebar, "cardIcon-TOMORROW-" + view.goals.get(0).id)).getIcon() == null)
                                { throw new IllegalStateException("Sidebar boss icon missing"); }
                                SidebarPreview.render(sidebar, 225, 700, output.resolve("card-layout-native.png"));
                            }
                        }
                        ActivityPicker picker = new ActivityPicker(HiscoreSkill.THE_CORRUPTED_GAUNTLET); picker.searchField().setText("gauntlet");
                        SidebarPreview.render(picker.popupContents(), 430, 290, output.resolve("activity-search-native.png"));
                        javax.swing.JPanel deadlineDetails = new javax.swing.JPanel(new java.awt.BorderLayout());
                        deadlineDetails.add(MyGoalsPanel.detailContents(SidebarPreview.deadlineGoal(), (spec, label) -> GoalIcons.bind(spec, label, skillIcons, sprites)));
                        SidebarPreview.render(deadlineDetails, 480, 760, output.resolve("deadline-details-native.png"));
                        if (find(deadlineDetails, "goalTimeline") != null || !((javax.swing.JTextArea) find(deadlineDetails, "detailRepeat")).getText().contains("12/31/26"))
                        { throw new IllegalStateException("Compact deadline plan missing or calendar still present"); }
                        if (((javax.swing.JLabel) find(deadlineDetails, "detailGoalIcon")).getIcon() == null) { throw new IllegalStateException("Skill header icon missing"); }
                        GoalDetailsPanel history = new GoalDetailsPanel((id, label) -> images.get(id).addTo(label),
                            (spec, label) -> GoalIcons.bind(spec, label, skillIcons, sprites)); history.render(SidebarPreview.historyGoal(), false);
                        javax.swing.JPanel historyFrame = new javax.swing.JPanel(new java.awt.BorderLayout()); historyFrame.add(new javax.swing.JScrollPane(history));
                        SidebarPreview.render(historyFrame, 480, 820, output.resolve("period-history-native.png"));
                    }
                    catch (Exception ex) { throw new IllegalStateException(ex); }
                });
                System.out.println("Native UI preview: first-open/sidebar-tab window sizing, production plugin/config/overlay startup, completion card painter, five item sprites, native boss/skill headers, editor/details panels, Tomorrow card, card click, nonblocking live updates, and character-switch/disposal checks passed: " + output);
                System.exit(0);
            }
            catch (Exception ex)
            {
                Throwable cause = ex;
                while (cause.getCause() != null) { cause = cause.getCause(); }
                System.err.println("Native editor preview failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage()); System.exit(1);
            }
            return true;
        });
    }

    private void verifySidebarWindowSize(Path output) throws Exception
    {
        java.awt.Window frame = SwingUtilities.getWindowAncestor(client.getCanvas());
        if (frame == null) { throw new IllegalStateException("Native RuneLite frame missing"); }
        toolbar.openPanel(null);
        if (frame instanceof java.awt.Frame) { ((java.awt.Frame) frame).setExtendedState(java.awt.Frame.NORMAL); }
        frame.setSize(Math.max(1000, frame.getWidth()), 600); frame.invalidate(); frame.validate();
        int height = frame.getHeight(), canvasHeight = client.getCanvas().getHeight();
        Instant now = Instant.parse("2026-10-16T17:00:00Z"); ZoneId zone = ZoneId.of("America/Chicago");
        GoalEngine engine = new GoalEngine(new ProfileState("f".repeat(64)), zone);
        engine.hiscoresChecked(now);
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        engine.observe(Map.of("MINING", 100L), GoalEngine.LIVE, now);
        for (int index = 0; index < 30; index++)
        {
            engine.add(new GoalSpec("Mining goal " + index, null, "MINING", GoalSpec.Mode.GAIN, 100, 0,
                GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-16"), null, zone), now);
        }
        MyGoalsPanel panel = new MyGoalsPanel(skillIcons, (token, command) -> {}, () -> {});
        panel.render(new GoalsView("sizing-preview", "Synthetic character", "", true, engine.state(), now));
        net.runelite.client.ui.NavigationButton button = net.runelite.client.ui.NavigationButton.builder().tooltip("Advanced Goal Pacer sizing preview")
            .icon(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)).panel(panel).build();
        toolbar.addNavigation(button);
        // Toolbar registration is queued even on Swing. Process it before opening
        // the tab so the size and popup checks exercise a displayed panel.
        java.awt.SecondaryLoop registered = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        SwingUtilities.invokeLater(registered::exit);
        if (!registered.enter()) { throw new IllegalStateException("Could not complete sidebar registration"); }
        try
        {
            toolbar.openPanel(button); frame.invalidate(); frame.validate();
            if (!panel.isShowing()) { throw new IllegalStateException("Sizing preview panel was not displayed"); }
            if (frame.getHeight() != height || client.getCanvas().getHeight() != canvasHeight)
            { throw new IllegalStateException("First sidebar open resized the game vertically: " + height + " -> " + frame.getHeight()); }
            javax.swing.JButton gear = (javax.swing.JButton) find(panel, "goalsSettings");
            gear.doClick();
            javax.swing.JPopupMenu popup = gear.getComponentPopupMenu();
            if (!popup.isVisible() || popup.getComponentCount() != 12 || find(popup, "lastHiScoresCheck") == null || find(popup, "collectionLogChecks") == null)
            { throw new IllegalStateException("Gear did not open the tools and data-check menu"); }
            java.awt.image.BufferedImage menuImage = new java.awt.image.BufferedImage(popup.getWidth(), popup.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D menuGraphics = menuImage.createGraphics(); popup.paint(menuGraphics); menuGraphics.dispose();
            javax.imageio.ImageIO.write(menuImage, "png", output.resolve("settings-menu-native.png").toFile());
            javax.swing.JMenuItem collection = (javax.swing.JMenuItem) find(popup, "collectionLogChecks");
            if (collection instanceof javax.swing.JMenu || !collection.isEnabled() || !collection.getToolTipText().contains("automatically load the current counts"))
            { throw new IllegalStateException("Collection-log capture timestamp or hover reminder unavailable"); }
            javax.swing.JToolTip tip = collection.createToolTip(); tip.setTipText(collection.getToolTipText());
            javax.swing.JPanel tipFrame = new javax.swing.JPanel(new java.awt.BorderLayout()); tipFrame.add(tip);
            SidebarPreview.render(tipFrame, tip.getPreferredSize().width, tip.getPreferredSize().height, output.resolve("collection-reminder-native.png"));
            popup.setVisible(false);
            if (find(panel, "manageGoalsTab") != null || !find(panel, "newGoal").isShowing())
            { throw new IllegalStateException("Unified Today still has navigation or hides goal creation"); }
            frame.invalidate(); frame.validate();
            gear.doClick();
            if (!popup.isVisible()) { throw new IllegalStateException("Gear unavailable in Today"); }
            popup.setVisible(false);
            if (frame.getHeight() != height || client.getCanvas().getHeight() != canvasHeight)
            { throw new IllegalStateException("Today resized the game vertically"); }
            Files.writeString(output.resolve("sidebar-window-sizing.txt"), "Unified Today and goal creation preserve frame height " + height +
                " and canvas height " + canvasHeight + "; 30 synthetic goals scroll within the sidebar.\n");
        }
        finally { toolbar.removeNavigation(button); panel.closeDialogs(); }
    }

    private static javax.swing.JMenuItem menuAction(MyGoalsPanel panel, String name)
    {
        javax.swing.JButton gear = (javax.swing.JButton) find(panel, "goalsSettings");
        return (javax.swing.JMenuItem) find(gear.getComponentPopupMenu(), name);
    }

    private void verifyFinishedHistory(Path output) throws Exception
    {
        ZoneId zone=ZoneId.of("America/Chicago"); LocalDate first=LocalDate.parse("2016-01-01");
        Instant then=first.atTime(12,0).atZone(zone).toInstant(), now=Instant.parse("2026-10-17T17:00:00Z");
        GoalEngine engine=new GoalEngine(new ProfileState("e".repeat(64)),zone); String group=engine.addGroup("Old group");
        engine.observe(Map.of("MINING",1000L),GoalEngine.LIVE,then);
        for (int i=0;i<101;i++) { engine.add(new GoalSpec("Completed mining "+i,group,"MINING",GoalSpec.Mode.GAIN,100,0,GoalSpec.Cadence.ONCE,first,first.plusDays(2),zone),then); }
        engine.observe(Map.of("MINING",1200L),GoalEngine.LIVE,then.plusSeconds(1));
        engine.settleCelebrations(new java.util.ArrayList<>(engine.state().celebrations.keySet()),true);
        var records=new java.util.ArrayList<>(engine.state().goals.values());
        com.mygoals.persistence.HistoryArchive.detach(engine.state(),now.atZone(zone).toLocalDate()); engine.state().groups.clear();
        MyGoalsPanel panel=new MyGoalsPanel(skillIcons,sprites,itemManager,(token,command)->{},()->{},()->{});
        panel.setPresentationPreferences(true,GoalDateFormat.AMERICAN); AtomicInteger page=new AtomicInteger(-1);
        panel.setFinishedLoader((token,number)->page.set(number));
        panel.render(new GoalsView("finished-ui","Synthetic","",true,engine.state(),now));
        var load=(javax.swing.JButton)find(panel,"olderFinishedGoals");
        if (load==null || !load.getText().equals("Load completed history")) { throw new IllegalStateException("Missing lazy completed history loader"); }
        SidebarPreview.render(panel,225,700,output.resolve("finished-history-load-native.png")); load.doClick();
        if (page.get()!=0) { throw new IllegalStateException("Wrong first completed page"); }
        panel.render(new GoalsView("finished-ui","Synthetic","",true,engine.state(),now,java.util.Set.of(),"","",records.subList(50,100),1));
        ((javax.swing.JButton)find(panel,"newerFinishedGoals")).doClick();
        if (page.get()!=0) { throw new IllegalStateException("Wrong newer completed page"); }
        ((javax.swing.JButton)find(panel,"olderFinishedGoals")).doClick();
        if (page.get()!=2) { throw new IllegalStateException("Wrong older completed page"); }
        panel.render(new GoalsView("finished-ui","Synthetic","",true,engine.state(),now,java.util.Set.of(),"","",records.subList(100,101),2));
        String id=records.get(100).id;
        if (find(panel,"goal-"+id)==null || find(panel,"olderFinishedGoals")!=null || panel.cardMenu(id,"finished-ui",MyGoalsPanel.CardMode.GOAL).getComponentCount()!=1)
        { throw new IllegalStateException("Archived goal lost its card after removing its group, or offered live reordering"); }
        GoalsView view=new GoalsView("finished-ui","Synthetic","",true,engine.state(),now,java.util.Set.of(),"","",records.subList(100,101),2);
        AtomicInteger deleted=new AtomicInteger();
        var editor=GoalEditor.createForm(view,view.goals.get(0),spec->{ throw new AssertionError("Finished goals are read-only"); },()->{},(item,label)->{},GoalDateFormat.AMERICAN,()->{ deleted.incrementAndGet(); return true; });
        ((javax.swing.JButton)find(editor,"deleteGoal")).doClick();
        if (deleted.get()!=1) { throw new IllegalStateException("Archived goal cannot be deleted"); }
        SidebarPreview.render(panel,225,700,output.resolve("finished-history-native.png"));
        var card=find(panel,"goal-"+id);
        if (card.getWidth()<200 || SwingUtilities.convertPoint(card,0,0,panel).x>10)
        { throw new IllegalStateException("Completed page navigation compressed or indented the cards"); }
        panel.closeDialogs();
    }

    private void verifyDailyAgenda(Path output) throws Exception
    {
        Instant now = Instant.parse("2026-10-17T17:00:00Z"); ZoneId zone = ZoneId.of("America/Chicago");
        GoalEngine engine = new GoalEngine(new ProfileState("f".repeat(64)), zone);
        String skilling = engine.addGroup("Skilling"), bosses = engine.addGroup("Bosses");
        engine.observe(Map.of("SLAYER", GoalSpec.xpForLevel(99) - 834_287L, "MINING", GoalSpec.xpForLevel(92) - 303_992L), GoalEngine.LIVE, now);
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 17L, 25859, 0L, 23859, 0L)), false, now);
        String slayer = engine.add(new GoalSpec("99 Slayer", skilling, "SLAYER", GoalSpec.Mode.LEVEL, 0, 99, GoalSpec.Cadence.ONCE,
            LocalDate.parse("2026-10-17"), LocalDate.parse("2026-12-31"), zone), now);
        String gauntlet = engine.add(new GoalSpec("Corrupted Gauntlet", bosses, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L, 25859, 1L, 23859, 1L)), now);
        String mining = engine.add(new GoalSpec("92 Mining", skilling, "MINING", GoalSpec.Mode.LEVEL, 0, 92, GoalSpec.Cadence.ONCE,
            LocalDate.parse("2026-10-17"), LocalDate.parse("2026-11-29"), zone), now);
        engine.postponeGoal(mining, true, now);
        MyGoalsPanel panel = new MyGoalsPanel(skillIcons, sprites, itemManager, (token, command) -> {}, () -> {}, () -> {});
        SidebarPreview.render(panel, 225, 220, output.resolve("creation-logged-out-native.png"));
        panel.setPresentationPreferences(true, GoalDateFormat.AMERICAN);
        panel.render(new GoalsView("daily-agenda", "Synthetic", "", true, engine.state(), now));
        java.awt.Container today = (java.awt.Container) find(panel, "today-" + slayer);
        java.awt.Container tomorrow = (java.awt.Container) find(panel, "tomorrow-" + mining);
        if (!((javax.swing.JLabel) find(today, "todayCardProgressLabel")).getText().equals("Today's Pace") ||
            !((javax.swing.JLabel) find(tomorrow, "todayCardProgressLabel")).getText().equals("Tomorrow's Pace"))
        { throw new IllegalStateException("Daily agenda lost Today/Tomorrow pace states"); }
        for (String name : new String[]{"todayPlanTarget", "todayCardProgressLabel", "todayCardProgress"})
        {
            if (!find(today, name).getFont().equals(net.runelite.client.ui.FontManager.getRunescapeSmallFont()))
            { throw new IllegalStateException("Agenda text resized a pixel font instead of using RuneLite's small face"); }
        }
        if (!((javax.swing.JButton) find(panel, "cardTitle-TODAY-" + slayer)).getText().equals("Slayer") ||
            !((javax.swing.JLabel) find(today, "todayPlanGoal")).getText().equals("99 Slayer"))
        { throw new IllegalStateException("Level title and objective were not separated"); }
        java.awt.Container uniqueRow = null;
        for (int id : new int[]{23956, 25859, 23859})
        {
            javax.swing.JLabel item = (javax.swing.JLabel) find((java.awt.Container) find(panel, "today-" + gauntlet), "todayUnique-" + id);
            if (item.getIcon() == null || item.getIcon().getIconWidth() != 24) { throw new IllegalStateException("Daily agenda is missing a native item icon"); }
            if (uniqueRow != null && uniqueRow != item.getParent()) { throw new IllegalStateException("Three selected item icons did not fit on one row"); }
            uniqueRow = item.getParent();
        }
        SidebarPreview.render(panel, 225, 850, output.resolve("daily-agenda-native.png"));
        java.awt.Container bossCard = (java.awt.Container) find(panel, "today-" + gauntlet);
        javax.swing.JLabel goalLabel = (javax.swing.JLabel) find(bossCard, "todayPlanGoalLabel");
        javax.swing.JLabel quantity = (javax.swing.JLabel) find(bossCard, "todayUnique-23956");
        int labelBaseline = javax.swing.SwingUtilities.convertPoint(goalLabel, 0, goalLabel.getBaseline(goalLabel.getWidth(), goalLabel.getHeight()), bossCard).y;
        int quantityBaseline = javax.swing.SwingUtilities.convertPoint(quantity, 0, quantity.getBaseline(quantity.getWidth(), quantity.getHeight()), bossCard).y;
        if (labelBaseline != quantityBaseline)
        { throw new IllegalStateException("Unique quantity and Goal label did not share a text baseline"); }
        if (!((javax.swing.JLabel) find(panel, "pluginTitle")).getText().equals("Advanced Goal Pacer") ||
            !find(panel, "pluginTitle").getForeground().equals(GoalCardStyle.GREEN) ||
            !find(panel, "newGoal").getForeground().equals(GoalCardStyle.GREEN))
        { throw new IllegalStateException("Plugin branding or green creation action did not update"); }
        if (javax.swing.SwingUtilities.convertPoint(find(today, "todayCardProgressBar"), 0, 0, today).y >=
            javax.swing.SwingUtilities.convertPoint(find(today, "targetCardProgressBar"), 0, 0, today).y)
        { throw new IllegalStateException("Target Pace did not appear below Today's Pace"); }
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, true, GoalEngine.LIVE, null, now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 101, true, GoalEngine.LIVE, null, now);
        panel.render(new GoalsView("daily-agenda", "Synthetic", "", true, engine.state(), now));
        java.awt.Container complete = (java.awt.Container) find((java.awt.Container) find(panel, "todayComplete"), "today-" + gauntlet);
        if (!((javax.swing.JLabel) find(complete, "targetCardProgressLabel")).getText().equals("Target Pace") ||
            !((javax.swing.JLabel) find(complete, "targetCardProgress")).getText().equals("2 / 10 KC") ||
            !find(complete, "targetCardProgressBar").getForeground().equals(GoalCardStyle.GREEN))
        { throw new IllegalStateException("On Pace did not switch to green period progress"); }
        SidebarPreview.render(panel, 225, 900, output.resolve("daily-agenda-on-pace-native.png"));
        panel.setPresentationPreferences(false, GoalDateFormat.AMERICAN);
        SidebarPreview.render(panel, 225, 850, output.resolve("daily-agenda-ungrouped-native.png"));
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 17L, 25859, 1L, 23859, 1L)), false, now);
        panel.render(new GoalsView("daily-agenda", "Synthetic", "", true, engine.state(), now));
        java.awt.Component completed = find(panel, "goalsComplete"), tomorrowSection = find(panel, "tomorrow");
        if (find(panel, "today-" + gauntlet) != null || find(panel, "goal-" + gauntlet) == null ||
            completed.getParent().getComponentZOrder(completed) <= completed.getParent().getComponentZOrder(tomorrowSection) ||
            !TodayActionsTest.action(panel.cardMenu(gauntlet, "daily-agenda", MyGoalsPanel.CardMode.GOAL), "Edit").isEnabled())
        { throw new IllegalStateException("Overall goal did not move below Tomorrow with an Edit menu action"); }
        SidebarPreview.render(panel, 225, 900, output.resolve("completed-goals-native.png"));
        panel.closeDialogs();
    }

    private void verifyPresentationPreferences(Path output) throws Exception
    {
        Instant now = Instant.parse("2026-10-16T17:00:00Z"); ZoneId zone = ZoneId.of("America/Chicago");
        GoalEngine engine = new GoalEngine(new ProfileState("d".repeat(64)), zone);
        String bosses = engine.addGroup("Bosses"), skills = engine.addGroup("Skilling");
        engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 17L)), false, now);
        String gauntlet = engine.add(new GoalSpec("Corrupted Gauntlet", bosses, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(25859, 1L)), now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 100, true, GoalEngine.LIVE, null, now);
        engine.observeCount("THE_CORRUPTED_GAUNTLET", 101, true, GoalEngine.LIVE, null, now);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, now);
        engine.add(new GoalSpec("Mining", skills, "MINING", GoalSpec.Mode.GAIN, 10_000, 0, GoalSpec.Cadence.WEEKLY,
            LocalDate.parse("2026-10-16"), null, zone), now);
        engine.observe(Map.of("MINING", 101_656L), GoalEngine.LIVE, now);
        MyGoalsPanel panel = new MyGoalsPanel(skillIcons, sprites, itemManager, (token, command) -> {}, () -> {}, () -> {});
        panel.render(new GoalsView("preferences", "Synthetic", "", true, engine.state(), now));
        engine.edit(gauntlet, new GoalSpec("Corrupted Gauntlet", bosses, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L, 25859, 1L)), now);
        panel.render(new GoalsView("preferences", "Synthetic", "", true, engine.state(), now));
        if (!((javax.swing.JLabel) find(panel, "todayUnique-23956")).getText().equals("(17/6)") ||
            !((javax.swing.JLabel) find(panel, "targetCardProgressLabel")).getText().equals("Target Pace") || find(panel, "todayCompleteBadge") != null)
        { throw new IllegalStateException("Edited unique selection or completed-card presentation is stale"); }
        String oldGroup = configManager.getConfiguration("mygoals", "groupToday"), oldDates = configManager.getConfiguration("mygoals", "dateFormat");
        panel.setPresentationPersistence((group, format) -> {
            configManager.setConfiguration("mygoals", "groupToday", group); configManager.setConfiguration("mygoals", "dateFormat", format);
        });
        try
        {
            menuAction(panel, "groupToday").doClick();
            javax.swing.JMenu dateMenu = (javax.swing.JMenu) menuAction(panel, "dateFormat");
            ((javax.swing.JMenuItem) find(dateMenu.getPopupMenu(), "dateFormat-EUROPEAN")).doClick();
            var saved = configManager.getConfig(com.mygoals.MyGoalsConfig.class);
            if (!saved.groupToday() || saved.dateFormat() != GoalDateFormat.EUROPEAN || find(panel, "todayGroup-ON_PACE-" + bosses) == null)
            { throw new IllegalStateException("Display preferences did not persist through RuneLite configuration"); }
            SidebarPreview.render(panel, 225, 700, output.resolve("today-grouped-period-native.png"));
            SidebarPreview.render(panel, 225, 700, output.resolve("today-date-format-native.png"));
            java.util.concurrent.atomic.AtomicReference<Throwable> editorFailure = new java.util.concurrent.atomic.AtomicReference<>();
            javax.swing.Timer inspectEditor = new javax.swing.Timer(150, event -> {
                javax.swing.JDialog editor = null;
                try
                {
                    editor = Arrays.stream(java.awt.Window.getWindows()).filter(w -> "goalEditorDialog".equals(w.getName()) && w.isVisible())
                        .map(w -> (javax.swing.JDialog) w).findFirst().orElseThrow();
                    CalendarPicker start = (CalendarPicker) find(editor, "startDate");
                    if (!((javax.swing.JButton) start.getComponent(0)).getText().equals("16/10/26  ▾") || find(editor, "deleteGoal") == null)
                    { throw new IllegalStateException("Edit did not inherit the date preference or expose deletion"); }
                    SidebarPreview.render((javax.swing.JPanel) editor.getContentPane(), 560, 820, output.resolve("edit-european-native.png"));
                }
                catch (Throwable failure) { editorFailure.set(failure); }
                finally { if (editor != null) { editor.dispose(); } }
            });
            inspectEditor.setRepeats(false); inspectEditor.start();
            TodayActionsTest.action(panel.cardMenu(gauntlet, "preferences", MyGoalsPanel.CardMode.TODAY), "Edit").doClick();
            if (editorFailure.get() != null) { throw new IllegalStateException("Native editor verification failed", editorFailure.get()); }
            menuAction(panel, "groupToday").doClick();
            if (find(panel, "newGroup") != null || find(panel, "manageGroup-" + bosses) != null ||
                !engine.state().goals.get(gauntlet).spec.groupId.equals(bosses) || engine.state().groups.size() != 2)
            { throw new IllegalStateException("Hiding groups changed assignments or left group controls visible"); }
            SidebarPreview.render(panel, 225, 700, output.resolve("today-groups-hidden-native.png"));
        }
        finally
        {
            if (oldGroup == null) { configManager.unsetConfiguration("mygoals", "groupToday"); } else { configManager.setConfiguration("mygoals", "groupToday", oldGroup); }
            if (oldDates == null) { configManager.unsetConfiguration("mygoals", "dateFormat"); } else { configManager.setConfiguration("mygoals", "dateFormat", oldDates); }
            panel.closeDialogs();
        }
    }

    private static java.awt.Component find(java.awt.Container parent, String name)
    {
        for (java.awt.Component child : parent.getComponents())
        {
            if (name.equals(child.getName())) { return child; }
            if (child instanceof java.awt.Container) { java.awt.Component found = find((java.awt.Container) child, name); if (found != null) { return found; } }
        }
        return null;
    }
}
