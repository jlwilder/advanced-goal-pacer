package com.mygoals;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public final class MyGoalsPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(MyGoalsPlugin.class);
        RuneLite.main(args);
    }
}
