package com.mygoals.celebration;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuneLiteNotificationGateTest
{
    private boolean ready;
    private int stage, varcReads;
    private final Map<Integer, Widget> widgets = new HashMap<>();
    private final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (proxy, method, args) -> {
        switch (method.getName())
        {
            case "getGameState": return ready ? GameState.LOGGED_IN : GameState.LOGIN_SCREEN;
            case "getLocalPlayer": return ready ? empty(Player.class) : null;
            case "getScene": return ready ? empty(Scene.class) : null;
            case "getViewportWidth": return 800;
            case "getViewportHeight": return 600;
            case "getWidget": return widgets.get((Integer) args[0]);
            case "getVarcIntValue": varcReads++; return stage;
            default: throw new AssertionError("Unexpected client call: " + method.getName());
        }
    });
    private final RuneLiteNotificationGate gate = new RuneLiteNotificationGate(client, null);
    private static <T> T empty(Class<T> type)
    { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> null)); }
    private Widget widget(int height, boolean hidden)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, a) -> {
            switch (m.getName()) { case "isHidden": return hidden; case "getWidth": return 178; case "getHeight": return height; default: throw new AssertionError(m.getName()); }
        });
    }
    @Test public void startupAndLogoutNeverReadUninitializedNativeVars()
    {
        assertTrue(gate.blocked(0)); assertEquals(0, varcReads);
        ready = true; assertFalse(gate.blocked(0)); assertEquals(1, varcReads);
        ready = false; assertTrue(gate.blocked(100)); assertEquals(1, varcReads);
    }
    @Test public void scriptStartStagesWidgetsAndModalsAllBlockUntilClear()
    {
        ready = true; gate.nativeStart(0); assertTrue(gate.blocked(1499)); assertFalse(gate.blocked(1500));
        for (stage = 1; stage <= 5; stage++) { assertTrue(gate.blocked(1600)); }
        stage = 7; assertTrue(gate.blocked(1600)); stage = 0;
        widgets.put(InterfaceID.NotificationDisplay.CONTAINER, widget(100, false)); assertTrue(gate.blocked(1600));
        widgets.put(InterfaceID.NotificationDisplay.CONTAINER, widget(2, false)); assertFalse(gate.blocked(1600));
        widgets.put(InterfaceID.NotificationDisplay.CONTAINER, widget(100, true)); assertFalse(gate.blocked(1600));
        for (int group : new int[]{InterfaceID.LEVELUP_DISPLAY, InterfaceID.QUESTSCROLL})
        {
            widgets.put(group << 16, widget(100, false)); assertTrue(gate.blocked(1600)); widgets.remove(group << 16);
        }
        assertFalse(gate.blocked(1600));
    }
}
