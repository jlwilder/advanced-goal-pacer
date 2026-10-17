package com.mygoals.collection;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/** Narrow native API fixture. No game data, account or network access. */
public final class FakeCollectionClient
{
    public int tick = 10, foreign, body;
    public int region = -1;
    public boolean hidden;
    public Widget[] children;
    public String title;
    public final Map<Integer, Integer> replacements = new HashMap<>();
    public final Map<Integer, Item[]> containers = new HashMap<>();
    public final Client client = proxy(Client.class, call -> {
        switch (call.name)
        {
            case "getGameState": return GameState.LOGGED_IN;
            case "getTickCount": return tick;
            case "getLocalPlayer": return region < 0 ? null : proxy(Player.class, c -> c.name.equals("getWorldLocation") ?
                new WorldPoint((region >> 8) * 64, (region & 255) * 64, 0) : null);
            case "getVarbitValue": return (int) call.args[0] == VarbitID.COLLECTION_POH_HOST_BOOK_OPEN ? foreign : (int) call.args[0] == VarbitID.COLLECTION_PLAYER_BODYTYPE ? body : 0;
            case "getWidget":
                if ((int) call.args[0] == InterfaceID.Collection.HEADER_TEXT) { return proxy(Widget.class, c -> c.name.equals("getDynamicChildren") ? new Widget[]{proxy(Widget.class, t -> t.name.equals("getText") ? title : null)} : null); }
                if ((int) call.args[0] == InterfaceID.Collection.ITEMS_CONTENTS) { return proxy(Widget.class, c -> c.name.equals("getDynamicChildren") ? children : c.name.equals("isHidden") ? hidden : null); }
                return null;
            case "getEnum": return proxy(EnumComposition.class, c -> c.name.equals("getIntValue") ? replacements.getOrDefault((int) call.args[0] * 100000 + (int) c.args[0], -1) : null);
            case "getItemContainer":
                Item[] items = containers.get((int) call.args[0]);
                return items == null ? null : proxy(ItemContainer.class, c -> c.name.equals("getItems") ? items : null);
            default: return null;
        }
    });

    public void page(int id, Map<Integer, Long> quantities)
    {
        var page = UniqueCatalogue.INSTANCE.pages.get(id); title = page.name;
        children = page.itemIds.stream().map(item -> item(item, quantities.getOrDefault(item, 0L))).toArray(Widget[]::new);
    }
    public Widget item(int id, long amount)
    {
        return proxy(Widget.class, call -> call.name.equals("getItemId") ? id : call.name.equals("getOpacity") ? amount == 0 ? 175 : 0 : call.name.equals("getItemQuantity") ? (int) Math.max(1, amount) : null);
    }
    public ItemContainer container(int id)
    {
        return proxy(ItemContainer.class, c -> c.name.equals("getItems") ? containers.getOrDefault(id, new Item[0]) : null);
    }
    public static final class Call
    {
        public final String name;
        public final Object[] args;
        Call(String name, Object[] args) { this.name = name; this.args = args; }
    }
    @SuppressWarnings("unchecked") public static <T> T proxy(Class<T> type, Function<Call, Object> handler)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            Object value = handler.apply(new Call(method.getName(), args));
            if (value != null || !method.getReturnType().isPrimitive()) { return value; }
            if (method.getReturnType() == boolean.class) { return false; }
            if (method.getReturnType() == long.class) { return 0L; }
            if (method.getReturnType() == void.class) { return null; }
            return 0;
        });
    }
}
