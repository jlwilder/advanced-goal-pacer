package com.mygoals;

import com.mygoals.collection.FakeCollectionClient;
import com.mygoals.collection.UniqueCatalogue;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.UniqueGoalTest;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.tracking.CountMessageParser;
import com.mygoals.ui.GoalsView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.runelite.api.Item;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RuneLiteCollectionAdapterTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    private final List<GoalsView> views = new ArrayList<>();
    private final FakeSession session = new FakeSession();
    private final FakeCollectionClient fake = new FakeCollectionClient();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T17:00:00Z"), ZoneId.of("America/Chicago"));
    private final MyGoalsController controller = new MyGoalsController(net.runelite.http.api.RuneLiteAPI.GSON, session, callbacks::add, () -> Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), clock, views::add);
    private final RuneLiteCollectionAdapter adapter = new RuneLiteCollectionAdapter(fake.client, id -> UniqueCatalogue.INSTANCE.items.containsKey(id) ? id : null, controller);
    private GoalsView view() { return views.get(views.size() - 1); }
    private void pump(BooleanSupplier done) throws Exception
    {
        for (int i = 0; !done.getAsBoolean() && i < 20; i++)
        {
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
        }
        assertTrue(done.getAsBoolean());
    }
    private void initialize() throws Exception
    {
        controller.beforeDetach = adapter::flushAndReset;
        controller.tick(); pump(() -> !views.isEmpty() && view().status.contains("two-tick"));
        controller.tick(); controller.tick(); pump(() -> view().editable);
    }
    private void baseline(int page, Map<Integer, Long> amounts)
    {
        controller.collectionEvent(controller.collectionToken(), e -> e.collectionSnapshot(page, UniqueGoalTest.page(page, amounts), false, clock.instant()));
    }
    private ProfileState state()
    {
        ProfileState[] state = new ProfileState[1]; controller.collectionEvent(controller.collectionToken(), e -> state[0] = e.state()); return state[0];
    }
    private void transmit(int id) { adapter.container(new ItemContainerChanged(id, fake.container(id))); }
    private void tick(int number) { fake.tick = number; adapter.tick(); }

    @Test public void pendingNativeLootSurvivesTheSameCharacterRestoreTokenChange() throws Exception
    {
        try
        {
            initialize(); controller.afterRestore = adapter::rebindSameCharacter; baseline(605, Map.of(23956, 4L));
            Filepath file = Filepath.Unchecked.getRooted(temporary.getRoot().toPath().resolve("backup.zip"));
            var exported = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
            controller.exportBackup(view().token, file, exported::set); pump(() -> exported.get() != null); assertEquals("", exported.get().error);
            var preview = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
            controller.prepareRestore(view().token, file, preview::set); pump(() -> preview.get() != null); assertEquals("", preview.get().error);
            fake.tick = 10; adapter.npcLoot(List.of(new ItemStack(23956, 1)));
            var restored = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
            controller.restoreBackup(view().token, preview.get().restoreId, restored::set); tick(11);
            pump(() -> restored.get() != null); assertEquals("", restored.get().error);
            assertEquals(Long.valueOf(4), state().collection.get(23956).quantity);
            tick(12); assertEquals(Long.valueOf(5), state().collection.get(23956).quantity);
            tick(13); assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void reusableNpcPayloadIsCopiedAndLogoutFlushesOnlyItsOriginalCharacter() throws Exception
    {
        try
        {
            initialize(); baseline(605, Map.of(23956, 4L));
            List<ItemStack> payload = new ArrayList<>(); payload.add(new ItemStack(23956, 1));
            adapter.npcLoot(payload); payload.clear();
            session.key = "b".repeat(64); controller.tick();
            pump(() -> view().status.contains("two-tick")); controller.tick(); controller.tick(); pump(() -> view().editable);
            assertTrue(state().collection.isEmpty());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
        try (ProfileStateRepository original = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
        {
            var saved = original.load().state;
            assertEquals(Long.valueOf(5), saved.collection.get(23956).quantity);
            assertEquals(1, saved.itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
    }

    @Test public void staleRedrawCannotOverwriteLiveDropAndFreshPageDoesNotReplayIt() throws Exception
    {
        try
        {
            initialize(); fake.page(605, Map.of(23956, 4L));
            transmit(InventoryID.COLLECTION_TRANSMIT); adapter.drawn(); tick(11);
            adapter.npcLoot(List.of(new ItemStack(23956, 1))); tick(13);
            assertEquals(Long.valueOf(5), state().collection.get(23956).quantity);
            adapter.drawn(); tick(14); assertEquals(Long.valueOf(5), state().collection.get(23956).quantity);
            fake.tick = 20; fake.page(605, Map.of(23956, 5L)); transmit(InventoryID.COLLECTION_TRANSMIT); adapter.drawn(); tick(21);
            assertFalse(state().collection.get(23956).needsSync);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void overlappingPageAndDropRequireAnotherFreshRead() throws Exception
    {
        try
        {
            initialize(); baseline(605, Map.of(23956, 4L));
            fake.page(605, Map.of(23956, 5L)); transmit(InventoryID.COLLECTION_TRANSMIT); adapter.drawn();
            adapter.npcLoot(List.of(new ItemStack(23956, 1))); tick(11); tick(12);
            assertTrue(state().collection.get(23956).needsSync);
            assertEquals(Long.valueOf(4), state().collection.get(23956).quantity);
            fake.tick = 20; transmit(InventoryID.COLLECTION_TRANSMIT); adapter.drawn(); tick(21);
            assertEquals(Long.valueOf(5), state().collection.get(23956).quantity);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void chestReopenAndCachedPriorChestCannotManufactureRewards() throws Exception
    {
        try
        {
            initialize(); int page = UniqueCatalogue.INSTANCE.bosses.get("BARROWS_CHESTS").pageIds.get(0);
            baseline(page, Map.of(4708, 0L));
            adapter.completion(CountMessageParser.parse("Your Barrows chest count is: 50.").get(0));
            fake.containers.put(InventoryID.TRAIL_REWARDINV, new Item[]{new Item(4708, 1)});
            transmit(InventoryID.TRAIL_REWARDINV); adapter.widget(InterfaceID.BARROWS_REWARD); tick(12);
            assertEquals(Long.valueOf(1), state().collection.get(4708).quantity);
            adapter.widget(InterfaceID.BARROWS_REWARD); tick(14);
            assertEquals(Long.valueOf(1), state().collection.get(4708).quantity);
            fake.tick = 20; adapter.completion(CountMessageParser.parse("Your Barrows chest count is: 51.").get(0));
            adapter.widget(InterfaceID.BARROWS_REWARD); tick(22);
            assertEquals(Long.valueOf(1), state().collection.get(4708).quantity);
            assertTrue(state().collection.get(4708).needsSync);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void arbitraryInventoryGainsNeverCountAsAcquisitions() throws Exception
    {
        try
        {
            initialize(); baseline(605, Map.of(23956, 4L));
            fake.containers.put(InventoryID.INV, new Item[]{new Item(23956, 2)}); transmit(InventoryID.INV); tick(15);
            assertEquals(Long.valueOf(4), state().collection.get(23956).quantity); assertTrue(state().itemActivity.isEmpty());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void overflowWithoutInventoryGainsRequestsOnlyTheRelevantBossPage() throws Exception
    {
        try
        {
            initialize(); baseline(504, Map.of(6739, 0L)); baseline(605, Map.of(23956, 4L));
            fake.region = 6461; Item[] full = new Item[28]; java.util.Arrays.fill(full, new Item(526, 1));
            fake.containers.put(InventoryID.INV, full);
            adapter.chat("You found some loot: Dragon axe."); transmit(InventoryID.INV); tick(15);
            assertEquals(Long.valueOf(0), state().collection.get(6739).quantity);
            assertTrue(state().collection.get(6739).needsSync);
            assertFalse(state().collection.get(23956).needsSync); assertTrue(state().itemActivity.isEmpty());
            baseline(504, Map.of(6739, 1L));
            assertEquals(Long.valueOf(1), state().collection.get(6739).quantity);
            assertFalse(state().collection.get(6739).needsSync);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void commonRewardWithRoomDoesNotInvalidateKnownUniques() throws Exception
    {
        try
        {
            initialize(); baseline(504, Map.of(6739, 0L)); fake.region = 6461;
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 100)});
            adapter.chat("You found some loot: Coins.");
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 200)}); transmit(InventoryID.INV); tick(15);
            assertFalse(state().collection.get(6739).needsSync); assertTrue(state().itemActivity.isEmpty());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void observedUniqueWithRoomUpdatesWithoutAnAdditionalPageRead() throws Exception
    {
        try
        {
            initialize(); baseline(504, Map.of(6739, 0L)); fake.region = 6461;
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 100)});
            adapter.chat("You found some loot: Dragon axe.");
            adapter.chat("You found some loot: Dragon axe."); // Duplicate hints retain the same receipt window.
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 100), new Item(6739, 1)});
            transmit(InventoryID.INV); tick(12); tick(15);
            assertEquals(Long.valueOf(1), state().collection.get(6739).quantity);
            assertFalse(state().collection.get(6739).needsSync);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void partialFullInventoryRewardAndFreshLogReconcileWithoutDoubleCredit() throws Exception
    {
        try
        {
            initialize(); baseline(504, Map.of(6739, 0L)); fake.region = 6461;
            Item[] before = new Item[27]; java.util.Arrays.fill(before, new Item(526, 1));
            fake.containers.put(InventoryID.INV, before); adapter.chat("You found some loot: Dragon axes.");
            Item[] after = new Item[28]; java.util.Arrays.fill(after, new Item(526, 1));
            after[26] = new Item(6739, 1); after[27] = new Item(995, 100);
            fake.containers.put(InventoryID.INV, after); transmit(InventoryID.INV); tick(12);
            assertTrue(state().collection.get(6739).needsSync);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
            baseline(504, Map.of(6739, 2L));
            assertEquals(Long.valueOf(2), state().collection.get(6739).quantity);
            assertFalse(state().collection.get(6739).needsSync);
            assertEquals(2, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    @Test public void logoutPreservesUnresolvedRewardVerificationForItsOriginalCharacter() throws Exception
    {
        try
        {
            initialize(); baseline(504, Map.of(6739, 0L)); fake.region = 6461;
            adapter.chat("You found some loot: Dragon axe.");
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
        try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
            Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
        {
            var saved = repository.load().state;
            assertTrue(saved.collection.get(6739).needsSync);
            assertEquals(Long.valueOf(0), saved.collection.get(6739).quantity);
        }
    }

    @Test public void anInventoryRemovalBeforeTheRewardPayloadDoesNotSwallowTheUnique() throws Exception
    {
        try
        {
            initialize(); baseline(504, Map.of(6739, 0L)); fake.region = 6461;
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 100), new Item(526, 1)});
            adapter.chat("You found some loot: Dragon axe.");
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 100)}); transmit(InventoryID.INV);
            fake.containers.put(InventoryID.INV, new Item[]{new Item(995, 100), new Item(6739, 1)}); transmit(InventoryID.INV);
            tick(12); tick(15);
            assertEquals(Long.valueOf(1), state().collection.get(6739).quantity);
            assertFalse(state().collection.get(6739).needsSync);
            assertEquals(1, state().itemActivity.values().stream().mapToLong(a -> a.amount).sum());
        }
        finally { controller.stop().get(5, TimeUnit.SECONDS); }
    }

    private static final class FakeSession implements MyGoalsController.Session
    {
        String key = "a".repeat(64);
        public String readyKey() { return key; }
        public boolean loggedIn() { return true; }
        public boolean loading() { return false; }
        public Map<String, Long> xpSnapshot() { return Map.of("MINING", 100000L); }
    }
}
