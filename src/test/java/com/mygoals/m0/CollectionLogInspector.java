package com.mygoals.m0;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/** Read-only, current-cache probes. None of these observations credits a goal. */
final class CollectionLogInspector
{
    // Current CS2 collection_draw_tabs_all / collection_draw_list / collection_draw_log.
    private static final int[] ROOTS = {471, 472, 473, 474, 475};
    private static final String[] TABS = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
    private static final int PAGE_ENUM = 683, PAGE_NAME = 689, ITEM_ENUM = 690;

    static final class Page
    {
        final String tab;
        final int structId;
        final String name;
        final int itemEnumId;
        final List<Integer> itemIds;

        Page(String tab, int structId, String name, int itemEnumId, Set<Integer> items)
        {
            this.tab = tab;
            this.structId = structId;
            this.name = name;
            this.itemEnumId = itemEnumId;
            this.itemIds = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    static List<Page> catalogue(Client client)
    {
        requireOwnSession(client);
        List<Page> result = new ArrayList<>();
        Set<Integer> visited = new LinkedHashSet<>();
        for (int tab = 0; tab < ROOTS.length; tab++)
        {
            StructComposition root = client.getStructComposition(ROOTS[tab]);
            int[] pages = intValues(client, root.getIntValue(PAGE_ENUM));
            for (int pageId : pages)
            {
                if (pageId <= 0 || !visited.add(pageId)) { throw new IllegalStateException("Invalid/duplicate collection page"); }
                StructComposition page = client.getStructComposition(pageId);
                String name = page.getStringValue(PAGE_NAME);
                if (name == null || name.isBlank()) { throw new IllegalStateException("Collection page name missing"); }
                int itemEnum = page.getIntValue(ITEM_ENUM);
                Set<Integer> items = new LinkedHashSet<>();
                for (int item : intValues(client, itemEnum))
                {
                    if (item <= 0) { throw new IllegalStateException("Invalid collection item"); }
                    items.add(item);
                }
                result.add(new Page(TABS[tab], pageId, name, itemEnum, items));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static int[] intValues(Client client, int id)
    {
        if (id <= 0) { throw new IllegalStateException("Missing collection enum"); }
        EnumComposition definition = client.getEnum(id);
        int[] values = definition.getIntVals();
        if (values == null || values.length == 0 || values.length > 2048)
        {
            throw new IllegalStateException("Unexpected collection enum size");
        }
        return values.clone();
    }

    static Map<Integer, Integer> visibleQuantities(Client client)
    {
        requireOwnSession(client);
        Widget contents = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        if (contents == null || contents.isHidden() || contents.getDynamicChildren() == null)
        {
            throw new IllegalStateException("Open your collection log and select a page first");
        }
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (Widget child : contents.getDynamicChildren())
        {
            if (child == null || child.getItemId() <= 0) { continue; }
            result.put(child.getItemId(), decodeQuantity(child.getOpacity(), child.getItemQuantity()));
        }
        if (result.isEmpty()) { throw new IllegalStateException("No collection items visible"); }
        return Collections.unmodifiableMap(result);
    }

    static int decodeQuantity(int opacity, int displayedQuantity)
    {
        // Native script displays quantity 1 for unobtained entries, dimmed to opacity 175.
        if (opacity == 175) { return 0; }
        if (opacity != 0 || displayedQuantity < 1) { throw new IllegalStateException("Unknown collection quantity representation"); }
        return displayedQuantity;
    }

    private static void requireOwnSession(Client client)
    {
        if (client.getGameState() != GameState.LOGGED_IN) { throw new IllegalStateException("Log in first"); }
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) != 0)
        {
            throw new IllegalStateException("Another player's house log is not your baseline");
        }
    }
}
