package com.mygoals.m0;

import com.google.gson.GsonBuilder;
import com.mygoals.celebration.CelebrationQueue;
import com.mygoals.celebration.NativeNotificationState;
import com.mygoals.tracking.MetricCatalogue;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.overlay.OverlayManager;

/** Internal developer harness. Excluded from the distributed plugin jar. */
@PluginDescriptor(name = "Advanced Goal Pacer M0 probes", internalName = "mygoals_m0", developerPlugin = true,
    description = "Read-only source diagnostics and queued celebration previews")
public final class M0ProbePlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar toolbar;
    @Inject private OverlayManager overlays;
    @Inject private PluginManager plugins;
    @Inject private HiscoreClient hiscores;
    @Inject private ScheduledExecutorService executor;

    private final CelebrationQueue queue = new CelebrationQueue();
    private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
    private NavigationButton navigation;
    private M0CompletionOverlay overlay;
    private Timer panelTimer;
    private volatile String status = "Waiting for game session";
    private volatile String observation = "No manual source checks yet";
    private volatile CelebrationQueue.Frame frame;
    private long generation;
    private long xpEvents, xpGained, lootEvents, nativeStarts, nativeDelays;
    private long recentNativeUntil;
    private long lastSummary;
    private String sessionKey;
    private volatile boolean active;
    private boolean lookupPending, popupConflict;
    private long nextLookup;
    private int stage;
    private int readyTicks;
    private boolean xpReady;
    private String latestLoot = "None";

    @Override
    protected void startUp()
    {
        active = true;
        clientThread.invoke(() -> { if (active) { resetSession(); } });
        overlay = new M0CompletionOverlay(client, () -> frame);
        overlays.add(overlay);
        // PluginManager calls startUp/shutDown on the Swing event thread.
        PluginPanel panel = new PluginPanel() {};
        panel.setLayout(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
        panel.add(new JLabel("Advanced Goal Pacer • M0 diagnostics"), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        JTextArea text = new JTextArea();
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setForeground(Color.LIGHT_GRAY);
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        button(buttons, "Inspect collection catalogue", this::inspectCatalogue);
        button(buttons, "Read opened log page", this::inspectQuantities);
        button(buttons, "Check my HiScores once", this::lookup);
        button(buttons, "Preview daily card", () -> preview(false));
        button(buttons, "Preview overall + fireworks", () -> preview(true));
        button(buttons, "Clear preview queue", () -> { queue.clear(); frame = null; });
        body.add(buttons, BorderLayout.NORTH);
        body.add(text, BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);
        navigation = NavigationButton.builder().tooltip("Advanced Goal Pacer M0").icon(icon()).panel(panel).priority(8).build();
        toolbar.addNavigation(navigation);
        panelTimer = new Timer(1000, e -> text.setText(status + "\n\n" + observation +
            "\n\nDeveloper observations only. No goal progress is recorded. Account data stays in memory."));
        panelTimer.start();
    }

    private void button(JPanel parent, String label, Runnable action)
    {
        JButton button = new JButton(label);
        button.addActionListener(e -> clientThread.invoke(() -> {
            if (!active) { return; }
            try { action.run(); }
            catch (RuntimeException ex) { observation = ex.getMessage(); }
        }));
        parent.add(button);
    }

    private void inspectCatalogue()
    {
        List<CollectionLogInspector.Page> pages = CollectionLogInspector.catalogue(client);
        long items = pages.stream().flatMap(p -> p.itemIds.stream()).distinct().count();
        var pageIds = pages.stream().map(p -> p.structId).collect(Collectors.toSet());
        var missingCandidates = BossPageCandidates.load().entrySet().stream()
            .filter(e -> e.getValue() != null && !pageIds.contains(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("kind", "candidate-cache-definitions-not-verified-eligibility");
        export.put("runeLiteVersion", RuneLiteProperties.getVersion());
        export.put("gameRevision", client.getRevision());
        export.put("missingCandidateMetrics", missingCandidates);
        export.put("pages", pages);
        observation = "Read " + pages.size() + " cache pages / " + items + " distinct item IDs. Missing page candidates: " + missingCandidates;
        // Only public cache definitions are exported. Player quantities and identity are excluded.
        executor.execute(() -> {
            try
            {
                var directory = getPluginDirectory();
                directory.createDirectories();
                directory.join("m0-catalogue.json").write(new GsonBuilder().setPrettyPrinting().create().toJson(export));
                observation = "Exported " + pages.size() + " cache pages to plugin-data/mygoals_m0/m0-catalogue.json. " +
                    "Missing candidate pages: " + missingCandidates + ". Boss eligibility and item variants still require validation.";
            }
            catch (Exception ex) { observation = "Catalogue export failed: " + ex.getClass().getSimpleName(); }
        });
    }

    private void inspectQuantities()
    {
        Map<Integer, Integer> quantities = CollectionLogInspector.visibleQuantities(client);
        observation = "Opened own-log page (display item ID → total):\n" + quantities +
            "\nUnobtained placeholders decoded as 0. Variant canonicalization remains unverified.";
    }

    private void lookup()
    {
        if (!ownSession() || client.getLocalPlayer() == null)
        {
            observation = "Log in before checking your HiScores";
            return;
        }
        long now = millis();
        if (lookupPending || now < nextLookup) { observation = "Wait for the active lookup / 30 second cooldown"; return; }
        String name = client.getLocalPlayer().getName();
        if (name == null || name.isBlank()) { observation = "Player name is not available yet"; return; }
        long token = generation;
        String key = identity();
        HiscoreEndpoint endpoint = HiscoreEndpoint.fromWorldTypes(client.getWorldType());
        lookupPending = true;
        nextLookup = now + 30_000;
        observation = "Checking " + endpoint.getName() + " HiScores…";
        hiscores.lookupAsync(name, endpoint).whenComplete((result, error) -> clientThread.invoke(() -> {
            if (!active || token != generation || !key.equals(identity()) || client.getGameState() != GameState.LOGGED_IN) { return; }
            lookupPending = false;
            if (error != null) { observation = "HiScores request failed: " + error.getClass().getSimpleName(); return; }
            if (result == null) { observation = "HiScores unavailable / player not listed; unknown is not zero"; return; }
            long available = MetricCatalogue.countMetrics().stream().filter(s -> MetricCatalogue.activityAmount(result.getSkill(s)) != null).count();
            observation = "HiScores returned " + available + " / " + MetricCatalogue.countMetrics().size() +
                " available activity values. No recovery credited: snapshot/live overlap is not yet proven.";
        }));
    }

    private void preview(boolean overall)
    {
        if (!ownSession()) { observation = "Log in to preview a card"; return; }
        checkPopupConflict();
        queue.enqueue(new CelebrationQueue.Card(UUID.randomUUID().toString(),
            overall ? "Overall goal complete" : "Today's target complete", "M0 preview • no goal credit", overall));
        observation = "Preview queued. Native cards take priority. Known independent popup conflict defers previews.";
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!ownSession() || !xpReady) { return; }
        Integer previous = xp.put(event.getSkill(), event.getXp());
        xpEvents++;
        if (previous != null && event.getXp() > previous) { xpGained += (long) event.getXp() - previous; }
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        if (!ownSession()) { return; }
        // The producer clears its mutable collection after posting. Copy before returning.
        Map<Integer, Integer> copy = new LinkedHashMap<>();
        for (ItemStack item : event.getItems()) { copy.merge(item.getId(), item.getQuantity(), Math::addExact); }
        Map<Integer, Integer> snapshot = Collections.unmodifiableMap(copy);
        lootEvents++;
        latestLoot = event.getComposition().getName() + " " + snapshot;
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event)
    {
        if (event.getScriptId() == ScriptID.NOTIFICATION_START || event.getScriptId() == ScriptID.NOTIFICATION_DELAY)
        {
            recentNativeUntil = millis() + 1500;
            if (event.getScriptId() == ScriptID.NOTIFICATION_START) { nativeStarts++; } else { nativeDelays++; }
            frame = null; // Hide immediately, including before the stage variable is updated.
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (ownSession() && !xpReady && ++readyTicks >= 2)
        {
            for (Skill skill : Skill.values())
            {
                if (skill != Skill.OVERALL) { xp.put(skill, client.getSkillExperience(skill)); }
            }
            xpReady = true;
        }
        checkPopupConflict();
    }

    private void checkPopupConflict()
    {
        popupConflict = plugins.getPlugins().stream().anyMatch(p ->
            p.getName().equalsIgnoreCase("Collection Log Popup Enhanced") && plugins.isPluginEnabled(p));
    }

    private boolean ownSession()
    {
        if (client.getGameState() != GameState.LOGGED_IN) { return false; }
        String key = identity();
        if (!key.equals(sessionKey))
        {
            resetSession();
            sessionKey = key;
        }
        return true;
    }

    private String identity()
    {
        // Ephemeral guard only. This is not the future production storage/season identity.
        return client.getAccountHash() + ":" + HiscoreEndpoint.fromWorldTypes(client.getWorldType()).name();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            resetSession();
        }
        if (event.getGameState() != GameState.LOGGED_IN) { frame = null; }
    }

    private void resetSession()
    {
        generation++;
        sessionKey = null;
        xp.clear();
        readyTicks = 0;
        xpReady = false;
        xpEvents = xpGained = lootEvents = nativeStarts = nativeDelays = 0;
        latestLoot = "None";
        lookupPending = false;
        observation = "Session reset; previous account observations cleared";
        queue.clear();
        frame = null;
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        if (!active) { frame = null; return; }
        long now = millis();
        boolean ready = client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null;
        // BeforeRender starts before the game's varc map exists. Never query it pre-login.
        stage = ready ? client.getVarcIntValue(VarClientID.NOTIFICATION_STAGE) : -1;
        boolean blocked = !ready || popupConflict || NativeNotificationState.blocks(stage,
            expandedNative(), visibleRoot(InterfaceID.LEVELUP_DISPLAY) || visibleRoot(InterfaceID.QUESTSCROLL),
            now < recentNativeUntil);
        frame = queue.update(now, blocked, ready);
        if (now - lastSummary >= 1000)
        {
            lastSummary = now;
            status = "Session: " + (ready ? "logged in" : "not ready") + "\nXP baseline: " + (xpReady ? "ready" : "waiting for two game ticks") +
                "\nXP events: " + xpEvents + " • observed gain: " + xpGained +
                "\nNPC loot events: " + lootEvents + "\nLatest loot: " + latestLoot +
                "\nNative starts/delays: " + nativeStarts + "/" + nativeDelays + " • stage: " + stage +
                "\n" + (popupConflict ? "Independent popup plugin enabled: previews deferred" : frame.status) + " • waiting: " + frame.waiting;
        }
    }

    private boolean visible(int id)
    {
        Widget widget = client.getWidget(id);
        return widget != null && !widget.isHidden() && widget.getWidth() > 0 && widget.getHeight() > 0;
    }

    private boolean expandedNative()
    {
        Widget widget = client.getWidget(InterfaceID.NotificationDisplay.CONTAINER);
        return widget != null && !widget.isHidden() && widget.getWidth() > 2 && widget.getHeight() > 2;
    }

    private boolean visibleRoot(int group)
    {
        return visible(group << 16);
    }

    static long millis() { return System.nanoTime() / 1_000_000L; }

    private static BufferedImage icon()
    {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(225, 180, 75));
        g.drawOval(3, 3, 17, 17);
        g.drawOval(7, 7, 9, 9);
        g.drawLine(12, 12, 22, 2);
        g.dispose();
        return image;
    }

    @Override
    protected void shutDown()
    {
        active = false;
        frame = null;
        clientThread.invoke(() -> { if (!active) { resetSession(); } });
        overlays.remove(overlay);
        if (panelTimer != null) { panelTimer.stop(); }
        if (navigation != null) { toolbar.removeNavigation(navigation); }
    }
}
