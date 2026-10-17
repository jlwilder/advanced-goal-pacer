package com.mygoals;

import com.mygoals.collection.CollectionLogReader;
import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.tracking.CountMessageParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import net.runelite.api.Client;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Text;

/**
 * Client-thread adapters. Copies native payloads and never depends on Loot
 * Tracker.
 */
final class RuneLiteCollectionAdapter
{
	private final Client client;
	private final IntFunction<Integer> canonicalizer;
	private final MyGoalsController controller;
	private String token, sessionId;
	private long serial, collectionSequence, acceptedSequence;
	private int lastTransmitTick = -100, drawnTick = -100, lastAcquisitionTick = -100;
	private boolean drawPending;
	private final List<Receipt> receipts = new ArrayList<>();
	private final Map<String, Completion> completions = new HashMap<>();
	private final Map<Integer, Integer> recentItems = new HashMap<>();
	private final Map<Integer, Integer> containerUpdates = new HashMap<>();
	private final List<PetCheck> petChecks = new ArrayList<>();
	private RewardWindow inventoryWindow;
	private Chest pendingChest;

	private static final class Receipt
	{
		String id, source;
		int tick;
		Map<Integer, Long> items;
	}

	private static final class Completion
	{
		String id;
		int tick;
		Set<Integer> pets;
	}

	private static final class Chest
	{
		int container, tick;
		String family;
		Map<Integer, Long> items = Map.of();
	}

	private static final class RewardWindow
	{
		String id, metric;
		int tick, consumed;
		Map<Integer, Long> before;
		Set<Integer> possibleItems;
	}

	private static final class PetCheck
	{
		int tick;
		Set<Integer> items;
	}

	RuneLiteCollectionAdapter(Client client, ItemManager itemManager, MyGoalsController controller)
	{
		this(client, new NativeCanonicalizer(client, itemManager), controller);
	}

	RuneLiteCollectionAdapter(Client client, IntFunction<Integer> canonicalizer, MyGoalsController controller)
	{
		this.client = client;
		this.canonicalizer = canonicalizer;
		this.controller = controller;
	}

	private boolean ready()
	{
		String current = controller.collectionToken();
		if (current == null)
		{
			return false;
		}
		if (!current.equals(token))
		{
			reset();
			token = current;
			sessionId = UUID.randomUUID().toString();
		}
		return true;
	}

	/**
	 * Restore changes the goal session token without changing the live character or
	 * pending evidence.
	 */
	void rebindSameCharacter(String newToken)
	{
		token = newToken;
	}

	void npcLoot(Collection<ItemStack> stacks)
	{
		if (!ready())
		{
			return;
		}
		// The core producer reuses its collection immediately after posting.
		Map<Integer, Long> copy = new LinkedHashMap<>();
		for (ItemStack stack : stacks)
		{
			Integer id = canonicalizer.apply(stack.getId());
			if (id != null && stack.getQuantity() > 0)
			{
				copy.merge(id, (long) stack.getQuantity(), Math::addExact);
			}
		}
		enqueue("npc:" + sessionId + ":" + ++serial, copy, "Drop");
	}

	void completion(CountMessageParser.Observation observation)
	{
		if (!ready() || !observation.completion)
		{
			return;
		}
		Completion completion = new Completion();
		completion.id = observation.metricId + ":" + observation.total;
		completion.tick = client.getTickCount();
		var boss = UniqueCatalogue.INSTANCE.bosses.get(observation.metricId);
		completion.pets = new HashSet<>();
		if (boss != null)
		{
			boss.itemIds.stream().filter(id -> UniqueCatalogue.INSTANCE.items.get(id).pet).forEach(completion.pets::add);
		}
		completions.put(family(observation.metricId), completion);
	}

	void chat(String raw)
	{
		if (!ready() || client.getLocalPlayer() == null)
		{
			return;
		}
		String message = Text.removeTags(raw);
		int region = client.getLocalPlayer().getWorldLocation().getRegionID();
		if ((region == 12588 || region == 6461) && message.contains("You found some loot: "))
		{
			beginInventoryReward(0, region == 12588 ? "TEMPOROSS" : "WINTERTODT");
		}
		else if (region == 12106 && message.equals("You place the Unsired into the Font of Consumption..."))
		{
			beginInventoryReward(ItemID.ABYSSALSIRE_UNSIRED, "ABYSSAL_SIRE");
		}

		if (message.startsWith("New item added to your collection log: "))
		{
			String name = message.substring("New item added to your collection log: ".length()).trim();
			Set<Integer> pets = new HashSet<>();
			UniqueCatalogue.INSTANCE.items.values().stream().filter(i -> i.pet && i.name.equalsIgnoreCase(name))
					.forEach(i -> pets.add(i.id));
			if (!pets.isEmpty())
			{
				checkPet(pets);
			}
		}
		if (message.startsWith("You have a funny feeling"))
		{
			Set<Integer> pets = new HashSet<>();
			completions.values().stream().filter(c -> client.getTickCount() - c.tick <= 8).forEach(c -> pets.addAll(c.pets));
			if (!pets.isEmpty())
			{
				checkPet(pets);
			}
		}
	}

	void menu(MenuOptionClicked event)
	{
		if (!ready() || !event.isItemOp())
		{
			return;
		}
		int id = event.getItemId();
		// Maggot eggs use ServerNpcLoot. Adding inventory gains for Pop would double
		// them.
		if (event.getMenuOption().equals("Open") && Set.of(ItemID.WINT_REWARD_BOX, ItemID.WINT_REROLL_BOX, ItemID.TEMPOROSS_REWARD_CASKET,
				ItemID.FORGOTTEN_LOCKBOX, ItemID.YAMA_DOSSIER).contains(id))
		{
			String metric = id == ItemID.WINT_REWARD_BOX || id == ItemID.WINT_REROLL_BOX ? "WINTERTODT"
					: id == ItemID.TEMPOROSS_REWARD_CASKET ? "TEMPOROSS" : id == ItemID.FORGOTTEN_LOCKBOX ? "DOOM_OF_MOKHAIOTL" : "YAMA";
			beginInventoryReward(id, metric);
		}
	}

	void widget(int group)
	{
		if (!ready())
		{
			return;
		}
		switch (group)
		{
		case InterfaceID.BARROWS_REWARD:
			chest(InventoryID.TRAIL_REWARDINV, "BARROWS_CHESTS");
			break;
		case InterfaceID.TRAIL_REWARDSCREEN:
			String clue = completions.entrySet().stream()
					.filter(e -> e.getKey().startsWith("CLUE_SCROLL_") && client.getTickCount() - e.getValue().tick <= 3)
					.map(Map.Entry::getKey).findFirst().orElse("MIMIC");
			chest(InventoryID.TRAIL_REWARDINV, clue);
			break;
		case InterfaceID.RAIDS_REWARDS:
			chest(InventoryID.RAIDS_REWARDS, "CHAMBERS");
			break;
		case InterfaceID.TOB_CHESTS:
			chest(InventoryID.TOB_CHESTS, "THEATRE");
			break;
		case InterfaceID.TOA_CHESTS:
			chest(InventoryID.TOA_CHESTS, "TOMBS");
			break;
		case InterfaceID.PMOON_REWARD:
			chest(InventoryID.PMOON_REWARDINV, "LUNAR_CHESTS");
			break;
		case InterfaceID.COLOSSEUM_REWARD_CHEST_2:
			chest(InventoryID.COLOSSEUM_REWARDS, "SOL_HEREDIT");
			break;
		default:
			break;
		}
	}

	void container(ItemContainerChanged event)
	{
		if (!ready())
		{
			return;
		}
		int id = event.getContainerId();
		containerUpdates.put(id, client.getTickCount());
		if (id == InventoryID.COLLECTION_TRANSMIT)
		{
			collectionSequence++;
			lastTransmitTick = client.getTickCount();
		}
		if (pendingChest != null && id == pendingChest.container)
		{
			pendingChest.items = containerItems(id);
		}
		if (id == InventoryID.INV && inventoryWindow != null && client.getTickCount() - inventoryWindow.tick <= 3)
		{
			RewardWindow window = inventoryWindow;
			Map<Integer, Long> after = containerItems(InventoryID.INV);
			if (window.consumed != 0 && after.getOrDefault(window.consumed, 0L) >= window.before.getOrDefault(window.consumed, 0L))
			{
				return;
			}
			if (bankOrTradeOpen())
			{
				unverifiedReward(window);
				inventoryWindow = null;
				return;
			}
			// Consumption can arrive before the reward payload. Wait for a positive
			// change, including ordinary non-unique loot, before resolving the window.
			if (after.entrySet().stream().noneMatch(item -> item.getValue() > window.before.getOrDefault(item.getKey(), 0L)))
			{
				return;
			}
			Map<Integer, Long> gains = new LinkedHashMap<>();
			after.forEach((item, count) ->
			{
				long delta = count - window.before.getOrDefault(item, 0L);
				Integer canonical = canonicalizer.apply(item);
				if (delta > 0 && canonical != null)
				{
					gains.merge(canonical, delta, Math::addExact);
				}
			});
			inventoryWindow = null;
			if (!gains.isEmpty())
			{
				enqueue(window.id, gains, "Reward");
			}
			// A full inventory may hide more rewards on the ground. Inventory gains alone
			// cannot prove the complete receipt or the player's ground-item ownership.
			if (inventoryFull())
			{
				unverifiedReward(window);
			}
		}
	}

	void drawn()
	{
		if (!ready())
		{
			return;
		}
		drawnTick = client.getTickCount();
		drawPending = true;
	}

	void tick()
	{
		if (!ready())
		{
			return;
		}
		int tick = client.getTickCount();
		if (drawPending && tick > drawnTick)
		{
			if (collectionSequence > acceptedSequence)
			{
				CollectionLogReader.Snapshot snapshot = CollectionLogReader.read(client);
				if (snapshot != null)
				{
					boolean overlap = lastTransmitTick <= lastAcquisitionTick + 2 && snapshot.quantities.keySet().stream()
							.anyMatch(id -> recentItems.getOrDefault(id, -100) >= lastTransmitTick - 2);
					Instant observedAt = Instant.now();
					controller.collectionEvent(token, e -> e.collectionSnapshot(snapshot.pageId, snapshot.quantities, overlap, observedAt));
					acceptedSequence = collectionSequence;
					drawPending = false;
				}
			}
			if (tick - drawnTick > 3)
			{
				drawPending = false;
			}
		}
		if (pendingChest != null && tick - pendingChest.tick >= 2)
		{
			Chest chest = pendingChest;
			pendingChest = null;
			Completion completion = completions.get(chest.family);
			if (completion != null && tick - completion.tick <= 300
					&& containerUpdates.getOrDefault(chest.container, -100) >= completion.tick)
			{
				enqueue("chest:" + completion.id, qualifying(chest.items), "Reward", chest.tick);
			}
			else if (!chest.items.isEmpty())
			{
				controller.collectionEvent(token, e -> e.requireCollectionSync(chest.items.keySet()));
			}
		}
		for (var iterator = receipts.iterator(); iterator.hasNext();)
		{
			Receipt receipt = iterator.next();
			if (tick - receipt.tick < 2)
			{
				continue;
			}
			apply(receipt);
			iterator.remove();
		}
		petChecks.removeIf(check ->
		{
			if (tick - check.tick < 5)
			{
				return false;
			}
			Set<Integer> uncertain = new HashSet<>(check.items);
			uncertain.removeIf(id -> recentItems.getOrDefault(id, -100) >= check.tick - 3);
			if (!uncertain.isEmpty())
			{
				controller.collectionEvent(token, e -> e.requireCollectionSync(uncertain));
			}
			return true;
		});
		if (inventoryWindow != null && tick - inventoryWindow.tick > 3)
		{
			unverifiedReward(inventoryWindow);
			inventoryWindow = null;
		}
		recentItems.entrySet().removeIf(entry -> tick - entry.getValue() > 12);
	}

	void flushAndReset()
	{
		// Captured evidence belongs to the old token even if the live character has
		// changed.
		if (pendingChest != null)
		{
			Completion completion = completions.get(pendingChest.family);
			if (completion != null && pendingChest.tick - completion.tick <= 300
					&& containerUpdates.getOrDefault(pendingChest.container, -100) >= completion.tick)
			{
				Map<Integer, Long> items = qualifying(pendingChest.items);
				Instant observedAt = Instant.now();
				if (!items.isEmpty())
				{
					controller.collectionEvent(token, e -> e.acquire("chest:" + completion.id, items, "Reward", observedAt));
				}
			}
		}
		for (Receipt receipt : receipts)
		{
			apply(receipt);
		}
		if (inventoryWindow != null)
		{
			unverifiedReward(inventoryWindow);
		}
		reset();
	}

	private void enqueue(String id, Map<Integer, Long> items, String source)
	{
		enqueue(id, items, source, client.getTickCount());
	}

	private void enqueue(String id, Map<Integer, Long> items, String source, int observedTick)
	{
		if (items.isEmpty())
		{
			return;
		}
		Receipt receipt = new Receipt();
		receipt.id = id;
		receipt.items = Map.copyOf(items);
		receipt.source = source;
		receipt.tick = observedTick;
		receipts.add(receipt);
		lastAcquisitionTick = receipt.tick;
		items.keySet().forEach(item -> recentItems.put(item, receipt.tick));
	}

	private void apply(Receipt receipt)
	{
		Instant observedAt = Instant.now();
		controller.collectionEvent(token, e -> e.acquire(receipt.id, receipt.items, receipt.source, observedAt));
	}

	private void chest(int container, String family)
	{
		pendingChest = new Chest();
		pendingChest.container = container;
		pendingChest.family = family;
		pendingChest.tick = client.getTickCount();
		pendingChest.items = containerItems(container);
	}

	private void beginInventoryReward(int consumed, String metric)
	{
		// Opening an item and its loot chat can describe the same receipt.
		if (inventoryWindow != null && metric.equals(inventoryWindow.metric) && client.getTickCount() - inventoryWindow.tick <= 3)
		{
			return;
		}
		if (inventoryWindow != null)
		{
			unverifiedReward(inventoryWindow);
			inventoryWindow = null;
		}
		var boss = UniqueCatalogue.INSTANCE.bosses.get(metric);
		if (boss == null)
		{
			return;
		}
		RewardWindow window = new RewardWindow();
		window.id = "reward:" + sessionId + ":" + ++serial;
		window.tick = client.getTickCount();
		window.metric = metric;
		window.consumed = consumed;
		window.before = containerItems(InventoryID.INV);
		window.possibleItems = Set.copyOf(boss.itemIds);
		if (bankOrTradeOpen())
		{
			unverifiedReward(window);
			return;
		}
		inventoryWindow = window;
	}

	private void unverifiedReward(RewardWindow window)
	{
		controller.collectionEvent(token, e -> e.requireCollectionSync(window.possibleItems));
	}

	private boolean inventoryFull()
	{
		var contents = client.getItemContainer(InventoryID.INV);
		if (contents == null)
		{
			return false;
		}
		int occupied = 0;
		for (var item : contents.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				occupied++;
			}
		}
		return occupied >= 28;
	}

	private Map<Integer, Long> containerItems(int container)
	{
		var contents = client.getItemContainer(container);
		Map<Integer, Long> items = new LinkedHashMap<>();
		if (contents != null)
		{
			for (var item : contents.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					items.merge(item.getId(), (long) item.getQuantity(), Math::addExact);
				}
			}
		}
		return items;
	}

	private Map<Integer, Long> qualifying(Map<Integer, Long> items)
	{
		Map<Integer, Long> result = new LinkedHashMap<>();
		items.forEach((item, count) ->
		{
			Integer canonical = canonicalizer.apply(item);
			if (canonical != null && count > 0)
			{
				result.merge(canonical, count, Math::addExact);
			}
		});
		return result;
	}

	private static final class NativeCanonicalizer implements IntFunction<Integer>
	{
		private final Client client;
		private final ItemManager itemManager;
		private Map<Integer, Integer> aliases;

		NativeCanonicalizer(Client client, ItemManager itemManager)
		{
			this.client = client;
			this.itemManager = itemManager;
		}

		@Override
		public Integer apply(int id)
		{
			if (aliases == null)
			{
				Map<Integer, Integer> values = new HashMap<>();
				Set<Integer> ambiguous = new HashSet<>();
				for (int raw : UniqueCatalogue.INSTANCE.items.keySet())
				{
					int replacement = client.getEnum(3721).getIntValue(raw);
					int body = client.getEnum(2108).getIntValue(replacement > 0 ? replacement : raw);
					for (int alias : new int[] { raw, replacement, body })
					{
						if (alias <= 0)
						{
							continue;
						}
						Integer prior = values.putIfAbsent(alias, raw);
						if (prior != null && prior != raw)
						{
							ambiguous.add(alias);
						}
					}
				}
				ambiguous.forEach(values::remove);
				aliases = Map.copyOf(values);
			}
			var definition = itemManager.getItemComposition(id);
			if (definition.getNote() != -1)
			{
				id = definition.getLinkedNoteId();
			}
			return aliases.get(id);
		}
	}

	private boolean bankOrTradeOpen()
	{
		var bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
		var trade = client.getWidget(InterfaceID.Trademain.UNIVERSE);
		return (bank != null && !bank.isHidden()) || (trade != null && !trade.isHidden());
	}

	private void checkPet(Set<Integer> items)
	{
		PetCheck check = new PetCheck();
		check.items = Set.copyOf(items);
		check.tick = client.getTickCount();
		petChecks.add(check);
	}

	private static String family(String metric)
	{
		if (metric.startsWith("CHAMBERS_OF_XERIC"))
		{
			return "CHAMBERS";
		}
		if (metric.startsWith("THEATRE_OF_BLOOD"))
		{
			return "THEATRE";
		}
		if (metric.startsWith("TOMBS_OF_AMASCUT"))
		{
			return "TOMBS";
		}
		if (metric.equals("THE_GAUNTLET") || metric.equals("THE_CORRUPTED_GAUNTLET"))
		{
			return "GAUNTLET";
		}
		return metric;
	}

	private void reset()
	{
		token = null;
		sessionId = null;
		receipts.clear();
		completions.clear();
		recentItems.clear();
		containerUpdates.clear();
		petChecks.clear();
		collectionSequence = acceptedSequence = 0;
		lastTransmitTick = drawnTick = lastAcquisitionTick = -100;
		drawPending = false;
		inventoryWindow = null;
		pendingChest = null;
	}
}
