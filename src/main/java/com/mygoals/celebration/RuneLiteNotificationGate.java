package com.mygoals.celebration;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.PluginManager;

/**
 * Read-only native lifecycle gate. No writes to game scripts, widgets, varcs,
 * or actor effects.
 */
public final class RuneLiteNotificationGate
{
	private final Client client;
	private final PluginManager plugins;
	private long recentUntil;
	private boolean popupConflict;

	public RuneLiteNotificationGate(Client client, PluginManager plugins)
	{
		this.client = client;
		this.plugins = plugins;
	}

	public void nativeStart(long now)
	{
		recentUntil = now + 1500;
	}

	public void reset()
	{
		recentUntil = 0;
	}

	public void checkPlugins()
	{
		popupConflict = plugins.getPlugins().stream()
				.anyMatch(p -> p.getName().equalsIgnoreCase("Collection Log Popup Enhanced") && plugins.isPluginEnabled(p));
	}

	public boolean sceneReady()
	{
		return client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null && client.getScene() != null &&
				client.getViewportWidth() > 0 && client.getViewportHeight() > 0;
	}

	public boolean blocked(long now)
	{
		// BeforeRender is posted before the varc map exists during startup.
		if (!sceneReady())
		{
			return true;
		}
		Widget widget = client.getWidget(InterfaceID.NotificationDisplay.CONTAINER);
		boolean expanded = widget != null && !widget.isHidden() && widget.getWidth() > 2 && widget.getHeight() > 2;
		return popupConflict || NativeNotificationState.blocks(client.getVarcIntValue(VarClientID.NOTIFICATION_STAGE), expanded,
				visible(InterfaceID.LEVELUP_DISPLAY << 16) || visible(InterfaceID.QUESTSCROLL << 16), now < recentUntil);
	}

	private boolean visible(int id)
	{
		Widget widget = client.getWidget(id);
		return widget != null && !widget.isHidden() && widget.getWidth() > 0 && widget.getHeight() > 0;
	}
}
