package com.mygoals;

import com.mygoals.ui.MyGoalsPanel;
import com.mygoals.ui.BackupControls;
import com.mygoals.ui.BackupResult;
import com.mygoals.celebration.CompletionCoordinator;
import com.mygoals.celebration.CompletionOverlay;
import com.mygoals.celebration.RuneLiteNotificationGate;
import com.google.inject.Provides;
import com.google.gson.Gson;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.ScriptID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreClient;
import com.mygoals.tracking.CountMessageParser;
import com.mygoals.collection.CollectionLogReader;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(name = "Advanced Goal Pacer", internalName = "mygoals", configName = "mygoals", description = "Calendar goals for experience, counts, and collection-log targets", tags = {
		"goals", "experience", "collectionlog" })
public final class MyGoalsPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private Gson gson;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ClientToolbar toolbar;
	@Inject
	private SkillIconManager skillIcons;
	@Inject
	private SpriteManager sprites;
	@Inject
	private HiscoreClient hiscores;
	@Inject
	private ItemManager items;
	@Inject
	private MyGoalsConfig config;
	@Inject
	private PluginManager plugins;
	@Inject
	private OverlayManager overlays;
	private RuneLiteCollectionAdapter collection;
	private volatile MyGoalsController runtime;
	private NavigationButton navigation;
	private MyGoalsPanel goalsPanel;
	private CompletionCoordinator completions;
	private RuneLiteNotificationGate notificationGate;
	private CompletionOverlay completionOverlay;

	@Provides
	MyGoalsConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(MyGoalsConfig.class);
	}

	@Override
	protected void startUp()
	{
		MyGoalsPanel[] panel = new MyGoalsPanel[1];
		Clock clock = Clock.systemUTC();
		MyGoalsController current = new MyGoalsController(gson, new RuneLiteSession(client, configManager, hiscores), clientThread::invoke,
				this::getPluginDirectory,
				clock, view -> SwingUtilities.invokeLater(() ->
				{
					if (runtime != null && panel[0] != null)
					{
						panel[0].render(view);
					}
				}));
		runtime = current;
		collection = new RuneLiteCollectionAdapter(client, items, current);
		RuneLiteCollectionAdapter adapter = collection;
		RuneLiteNotificationGate gate = new RuneLiteNotificationGate(client, plugins);
		notificationGate = gate;
		CompletionCoordinator coordinator = new CompletionCoordinator(current, config::dailyCards, config::overallCards);
		completions = coordinator;
		completionOverlay = new CompletionOverlay(client,
				() -> runtime == current && current.celebrationToken() != null && !gate.blocked(millis()) ? coordinator.frame() : null,
				config::fireworks);
		overlays.add(completionOverlay);
		current.beforeDetach = () ->
		{
			adapter.flushAndReset();
			coordinator.reset();
			gate.reset();
		};
		current.beforeRestore = () ->
		{
			coordinator.reset();
			gate.reset();
		};
		current.afterRestore = adapter::rebindSameCharacter;
		panel[0] = new MyGoalsPanel(skillIcons, sprites, items,
				(token, command) -> clientThread.invoke(() -> current.command(token, command)),
				() -> clientThread.invoke(current::retry), () -> clientThread.invoke(current::checkCounts), clock);
		goalsPanel = panel[0];
		panel[0].setPresentationPreferences(config.groupToday(), config.dateFormat());
		panel[0].setPresentationPersistence((group, format) ->
		{
			configManager.setConfiguration("mygoals", "groupToday", group);
			configManager.setConfiguration("mygoals", "dateFormat", format);
		});
		panel[0].setBackupBackend(new BackupControls.Backend()
		{
			public void export(String token, Filepath destination, Consumer<BackupResult> completed)
			{
				clientThread.invoke(
						() -> current.exportBackup(token, destination, reply -> SwingUtilities.invokeLater(() -> completed.accept(reply))));
			}

			public void prepare(String token, Filepath source, Consumer<BackupResult> completed)
			{
				clientThread.invoke(
						() -> current.prepareRestore(token, source, reply -> SwingUtilities.invokeLater(() -> completed.accept(reply))));
			}

			public void restore(String token, String restoreId, Consumer<BackupResult> completed)
			{
				clientThread.invoke(
						() -> current.restoreBackup(token, restoreId, reply -> SwingUtilities.invokeLater(() -> completed.accept(reply))));
			}

			public void cancel(String token, String restoreId)
			{
				clientThread.invoke(() -> current.cancelRestore(token, restoreId));
			}
		});
		panel[0].setHistoryLoader((token, goalId, before, completed) -> clientThread
				.invoke(() -> current.history(token, goalId, before, page -> SwingUtilities.invokeLater(() -> completed.accept(page)))));
		panel[0].setFinishedLoader((token, page) -> clientThread.invoke(() -> current.finished(token, page)));
		panel[0].setDailyHistoryLoader((token, goalId, start, end, completed) -> clientThread.invoke(
				() -> current.dailyHistory(token, goalId, start, end, page -> SwingUtilities.invokeLater(() -> completed.accept(page)))));
		navigation = NavigationButton.builder().tooltip("Advanced Goal Pacer")
				.icon(ImageUtil.loadImageResource(MyGoalsPlugin.class, "icon.png")).panel(panel[0]).priority(7).build();
		toolbar.addNavigation(navigation);
		clientThread.invoke(current::tick);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"mygoals".equals(event.getGroup()) || (!"groupToday".equals(event.getKey()) && !"dateFormat".equals(event.getKey())))
		{
			return;
		}
		MyGoalsPanel panel = goalsPanel;
		if (panel != null)
		{
			SwingUtilities.invokeLater(() ->
			{
				if (goalsPanel == panel)
				{
					panel.setPresentationPreferences(config.groupToday(), config.dateFormat());
				}
			});
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (runtime != null)
		{
			runtime.tick();
			if (collection != null)
			{
				collection.tick();
			}
			if (notificationGate != null)
			{
				notificationGate.checkPlugins();
			}
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (runtime != null && completions != null && notificationGate != null)
		{
			completions.update(millis(), notificationGate.blocked(millis()), notificationGate.sceneReady());
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (notificationGate != null
				&& (event.getScriptId() == ScriptID.NOTIFICATION_START || event.getScriptId() == ScriptID.NOTIFICATION_DELAY))
		{
			long now = millis();
			notificationGate.nativeStart(now);
			if (completions != null)
			{
				completions.interrupt(now);
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (runtime != null)
		{
			runtime.xpChanged(event.getSkill().name(), event.getXp());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		MyGoalsController current = runtime;
		if (current == null || (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM &&
				event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION))
		{
			return;
		}
		CountMessageParser.parse(event.getMessage()).forEach(observation ->
		{
			current.countChanged(observation);
			if (collection != null)
			{
				collection.completion(observation);
			}
		});
		if (collection != null)
		{
			collection.chat(event.getMessage());
		}
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (collection != null)
		{
			collection.npcLoot(event.getItems());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (collection != null)
		{
			collection.container(event);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (collection != null)
		{
			collection.widget(event.getGroupId());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (collection != null)
		{
			collection.menu(event);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (collection != null && event.getScriptId() == CollectionLogReader.DRAW_LOG_SCRIPT)
		{
			collection.drawn();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		MyGoalsController current = runtime;
		if (current != null)
		{
			clientThread.invoke(current::sessionChanged);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (runtime != null && (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING
				|| event.getGameState() == GameState.LOGGING_IN))
		{
			runtime.sessionChanged();
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		MyGoalsController current = runtime;
		if (current == null)
		{
			return;
		}
		CompletableFuture<Void> done = new CompletableFuture<>();
		event.waitFor(done);
		clientThread.invoke(() -> current.stop().whenComplete((result, failure) ->
		{
			if (failure == null)
			{
				done.complete(null);
			}
			else
			{
				done.completeExceptionally(failure);
			}
		}));
	}

	@Override
	protected void shutDown()
	{
		MyGoalsController current = runtime;
		runtime = null;
		if (completionOverlay != null)
		{
			overlays.remove(completionOverlay);
			completionOverlay = null;
		}
		toolbar.removeNavigation(navigation);
		MyGoalsPanel closing = goalsPanel;
		goalsPanel = null;
		if (closing != null)
		{
			SwingUtilities.invokeLater(closing::closeDialogs);
		}
		if (current != null)
		{
			clientThread.invoke(current::stop);
		}
	}

	private static long millis()
	{
		return System.nanoTime() / 1_000_000L;
	}

}
