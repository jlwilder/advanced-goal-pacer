package com.mygoals.collection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Public definitions only. Account quantities never enter this catalogue. */
public final class UniqueCatalogue
{
	public static final UniqueCatalogue INSTANCE = load();

	public static final class Boss
	{
		public final String metricId;
		public final List<Integer> pageIds, itemIds;

		private Boss(JsonObject row)
		{
			metricId = row.get("metricId").getAsString();
			pageIds = integers(row, "pageIds");
			itemIds = integers(row, "itemIds");
		}
	}

	public static final class Page
	{
		public final int structId, itemEnumId;
		public final String name;
		public final List<Integer> itemIds;

		private Page(JsonObject row)
		{
			structId = row.get("structId").getAsInt();
			itemEnumId = row.get("itemEnumId").getAsInt();
			name = row.get("name").getAsString();
			itemIds = integers(row, "itemIds");
		}
	}

	public static final class Item
	{
		public final int id, targetLimit;
		public final String name;
		public final boolean pet;
		public final List<Integer> pageIds;

		private Item(JsonObject row)
		{
			id = row.get("id").getAsInt();
			targetLimit = row.get("targetLimit").getAsInt();
			name = row.get("name").getAsString();
			pet = row.get("pet").getAsBoolean();
			pageIds = integers(row, "pageIds");
		}
	}

	private static final class Document
	{
		String version;
		List<Boss> bosses;
		List<Page> pages;
		List<Item> items;
	}

	public final String version;
	public final Map<String, Boss> bosses;
	public final Map<Integer, Page> pages;
	public final Map<Integer, Item> items;

	private UniqueCatalogue(Document document)
	{
		version = document.version;
		bosses = Collections.unmodifiableMap(document.bosses.stream().collect(Collectors.toMap(b -> b.metricId, b -> b, (a, b) ->
		{
			throw new IllegalArgumentException("Duplicate boss");
		}, LinkedHashMap::new)));
		pages = Collections.unmodifiableMap(document.pages.stream().collect(Collectors.toMap(p -> p.structId, p -> p, (a, b) ->
		{
			throw new IllegalArgumentException("Duplicate page");
		}, LinkedHashMap::new)));
		items = Collections.unmodifiableMap(document.items.stream().collect(Collectors.toMap(i -> i.id, i -> i, (a, b) ->
		{
			throw new IllegalArgumentException("Duplicate item");
		}, LinkedHashMap::new)));
		bosses.values().forEach(b ->
		{
			if (b.itemIds.isEmpty() || !items.keySet().containsAll(b.itemIds) || !pages.keySet().containsAll(b.pageIds))
			{
				throw new IllegalArgumentException("Incomplete boss definition");
			}
		});
	}

	public void validateTargets(String metric, Map<Integer, Long> targets)
	{
		Boss boss = bosses.get(metric);
		if (boss == null || targets.isEmpty())
		{
			throw new IllegalArgumentException("Choose a boss and at least one unique");
		}
		targets.forEach((id, total) ->
		{
			if (!boss.itemIds.contains(id) || total == null || total < 1 || total > items.get(id).targetLimit)
			{
				throw new IllegalArgumentException("Choose an eligible item and a total within the displayed target limit");
			}
		});
	}

	public String itemName(int id)
	{
		Item item = items.get(id);
		return item == null ? "Item " + id : item.name;
	}

	private static UniqueCatalogue load()
	{
		try (InputStreamReader reader = new InputStreamReader(
				UniqueCatalogue.class.getResourceAsStream("/com/mygoals/catalogue/uniques.json"), StandardCharsets.UTF_8))
		{
			// The catalogue is static public data; reading its JSON tree needs no
			// serializer.
			JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
			Document document = new Document();
			document.version = root.get("version").getAsString();
			document.bosses = new ArrayList<>();
			document.pages = new ArrayList<>();
			document.items = new ArrayList<>();
			root.getAsJsonArray("bosses").forEach(row -> document.bosses.add(new Boss(row.getAsJsonObject())));
			root.getAsJsonArray("pages").forEach(row -> document.pages.add(new Page(row.getAsJsonObject())));
			root.getAsJsonArray("items").forEach(row -> document.items.add(new Item(row.getAsJsonObject())));
			return new UniqueCatalogue(document);
		}
		catch (Exception ex)
		{
			throw new ExceptionInInitializerError(ex);
		}
	}

	private static List<Integer> integers(JsonObject row, String name)
	{
		List<Integer> result = new ArrayList<>();
		for (JsonElement value : row.getAsJsonArray(name))
		{
			result.add(value.getAsInt());
		}
		return List.copyOf(result);
	}
}
