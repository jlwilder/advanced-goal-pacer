package com.mygoals.collection;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Reads a complete, visible, player-owned page after its native draw. Never
 * opens pages.
 */
public final class CollectionLogReader
{
	public static final int DRAW_LOG_SCRIPT = 2732;
	// Native collection page parameters / display replacements from the pinned CS2
	// catalogue.
	private static final int PAGE_NAME_PARAM = 689;
	private static final int PAGE_ITEMS_PARAM = 690;
	private static final int DISPLAY_ITEM_REPLACEMENTS_ENUM = 3721;
	private static final int BODYTYPE_ITEM_REPLACEMENTS_ENUM = 2108;
	private static final int UNOBTAINED_ITEM_OPACITY = 175;

	public static final class Snapshot
	{
		public final int pageId;
		public final Map<Integer, Long> quantities;

		public Snapshot(int pageId, Map<Integer, Long> quantities)
		{
			this.pageId = pageId;
			this.quantities = Map.copyOf(quantities);
		}
	}

	public static Set<String> incompatibleMetrics(Client client)
	{
		Set<Integer> invalid = new HashSet<>();
		for (var page : UniqueCatalogue.INSTANCE.pages.values())
		{
			try
			{
				var struct = client.getStructComposition(page.structId);
				int[] values = client.getEnum(struct.getIntValue(PAGE_ITEMS_PARAM)).getIntVals();
				if (!page.name.equals(struct.getStringValue(PAGE_NAME_PARAM)) || values == null
						|| !Arrays.equals(values, page.itemIds.stream().mapToInt(Integer::intValue).toArray()))
				{
					invalid.add(page.structId);
				}
			}
			catch (RuntimeException ex)
			{
				invalid.add(page.structId);
			}
		}
		Set<String> metrics = new HashSet<>();
		UniqueCatalogue.INSTANCE.bosses.values().forEach(b ->
		{
			if (b.pageIds.stream().anyMatch(invalid::contains))
			{
				metrics.add(b.metricId);
			}
		});
		return Set.copyOf(metrics);
	}

	public static Snapshot read(Client client)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) != 0)
		{
			return null;
		}
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		Widget contents = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (header == null || contents == null || header.isHidden() || contents.isHidden() || header.getDynamicChildren() == null
				|| contents.getDynamicChildren() == null)
		{
			return null;
		}
		String pageName = Arrays.stream(header.getDynamicChildren()).filter(w -> w != null && w.getText() != null)
				.map(w -> Text.removeTags(w.getText()))
				.filter(n -> UniqueCatalogue.INSTANCE.pages.values().stream().anyMatch(p -> p.name.equals(n))).findFirst().orElse(null);
		var page = UniqueCatalogue.INSTANCE.pages.values().stream().filter(p -> p.name.equals(pageName)).findFirst().orElse(null);
		Widget[] children = contents.getDynamicChildren();
		if (page == null || children.length != page.itemIds.size())
		{
			return null;
		}
		Map<Integer, Long> quantities = new LinkedHashMap<>();
		for (int index = 0; index < children.length; index++)
		{
			Widget child = children[index];
			int raw = page.itemIds.get(index);
			int expected = replacement(client, DISPLAY_ITEM_REPLACEMENTS_ENUM, raw);
			if (client.getVarbitValue(VarbitID.COLLECTION_PLAYER_BODYTYPE) == 1)
			{
				expected = replacement(client, BODYTYPE_ITEM_REPLACEMENTS_ENUM, expected);
			}
			if (child == null || child.getItemId() != expected)
			{
				return null;
			}
			try
			{
				quantities.put(raw, decodeQuantity(child.getOpacity(), child.getItemQuantity()));
			}
			catch (IllegalArgumentException ex)
			{
				return null;
			}
		}
		return new Snapshot(page.structId, quantities);
	}

	private static int replacement(Client client, int enumId, int item)
	{
		int replacement = client.getEnum(enumId).getIntValue(item);
		return replacement > 0 ? replacement : item;
	}

	public static long decodeQuantity(int opacity, int displayedQuantity)
	{
		if (opacity == UNOBTAINED_ITEM_OPACITY && displayedQuantity == 1)
		{
			return 0;
		}
		if (opacity == 0 && displayedQuantity > 0)
		{
			return displayedQuantity;
		}
		throw new IllegalArgumentException("Unknown collection quantity representation");
	}
}
