package com.mygoals;

import com.mygoals.m0.M0ProbePlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Optional maintenance launcher; never part of the Plugin Hub JAR. */
public final class MyGoalsDiagnosticsTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MyGoalsPlugin.class, M0ProbePlugin.class);
		RuneLite.main(args);
	}
}
