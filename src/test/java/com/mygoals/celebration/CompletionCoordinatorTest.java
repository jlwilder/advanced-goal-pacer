package com.mygoals.celebration;

import com.mygoals.domain.ProfileState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompletionCoordinatorTest
{
    private final FakeStore store = new FakeStore();
    private boolean daily = true, overall = true;
    private final CompletionCoordinator coordinator = new CompletionCoordinator(store, () -> daily, () -> overall);

    @Test public void claimMustBeDurableBeforeAnyCardOrFireworkAppears()
    {
        store.add("goal", true, false); coordinator.update(0, false, true); assertEquals(0, store.claims);
        coordinator.update(600, false, true);
        assertNull(coordinator.frame()); assertEquals(1, store.claims);
        coordinator.update(700, false, true); coordinator.update(1900, false, true); assertNull(coordinator.frame());
        store.flush(); coordinator.update(2000, false, true); coordinator.update(3200, false, true);
        assertNotNull(coordinator.frame().card); assertTrue(coordinator.frame().fireworks()); assertEquals(1, store.claims);
    }

    @Test public void nativePreemptionAndLoadingPauseVisibleTimeAndEffects()
    {
        store.add("goal", true, false); coordinator.update(0, true, true); assertEquals(0, store.claims);
        coordinator.update(2000, false, true); coordinator.update(3200, false, true); store.flush();
        coordinator.update(3300, false, true); coordinator.update(4500, false, true); coordinator.update(5000, false, true);
        assertEquals(500, coordinator.frame().visibleMillis);
        coordinator.interrupt(5100); assertNull(coordinator.frame());
        coordinator.update(10_000, false, true); coordinator.update(11_200, false, true);
        assertEquals(500, coordinator.frame().visibleMillis);
        coordinator.update(11_300, true, false); assertNull(coordinator.frame());
        coordinator.update(12_000, false, true); coordinator.update(13_200, false, true);
        assertEquals(500, coordinator.frame().visibleMillis);
    }

    @Test public void recoveredDailiesAreGroupedAndClaimedTogetherWithoutFireworks()
    {
        store.add("one", false, true); store.add("two", false, true);
        coordinator.update(0, false, true); coordinator.update(600, false, true); assertEquals(1, store.claims);
        assertTrue(store.events.values().stream().allMatch(e -> e.status.equals("ANNOUNCED")));
        store.flush(); coordinator.update(700, false, true); coordinator.update(1900, false, true);
        assertEquals("2 goals completed", coordinator.frame().card.detail); assertFalse(coordinator.frame().fireworks());
    }

    @Test public void disabledCardsAreConsumedWithoutAReplayWhenReenabled()
    {
        daily = false; overall = false; store.add("daily", false, false); store.add("overall", true, false);
        coordinator.update(0, false, true); assertEquals(0, store.claims);
        assertTrue(store.events.values().stream().allMatch(e -> e.status.equals("SUPPRESSED")));
        daily = true; overall = true; store.flush(); coordinator.update(5000, false, true); assertNull(coordinator.frame());
    }

    @Test public void supersededDailyAndDeletedPendingGoalsAreRemovedFromTheQueue()
    {
        String dailyId = store.add("same", false, false); coordinator.update(0, false, true);
        store.events.get(dailyId).status = "SUPPRESSED"; store.add("same", true, false); store.flush();
        coordinator.update(100, false, true); coordinator.update(1300, false, true); store.flush();
        coordinator.update(1400, false, true); coordinator.update(2600, false, true);
        assertTrue(coordinator.frame().card.overall); assertEquals(1, store.claims);
        store.events.clear(); coordinator.update(2700, false, true); assertNull(coordinator.frame());
    }

    @Test public void profileChangeClearsFramesAndLateAcknowledgementsCannotReplayThem()
    {
        store.add("old", true, false); coordinator.update(0, false, true); coordinator.update(600, false, true); store.flush();
        coordinator.update(700, false, true); coordinator.update(1900, false, true); assertNotNull(coordinator.frame().card);
        store.token = null; coordinator.update(2000, false, false); assertNull(coordinator.frame());
        store.token = "new"; store.events.clear(); coordinator.update(2100, false, true); assertNull(coordinator.frame());
    }

    @Test public void blockedFramesReuseAdmissionUntilProgressOrSettingsChange()
    {
        FakeStore cached = new FakeStore() { @Override public long celebrationRevision() { return revision; } };
        CompletionCoordinator coordinator = new CompletionCoordinator(cached, () -> daily, () -> overall);
        String id = cached.add("waiting", false, false);
        coordinator.update(0, true, true);
        for (int i = 1; i <= 600; i++) { coordinator.update(i * 16L, true, true); }
        assertEquals(1, cached.pendingReads); assertEquals(0, cached.claims);
        cached.events.get(id).status = "SUPPRESSED"; cached.flush();
        coordinator.update(10_000, true, true);
        assertEquals(2, cached.pendingReads);
        coordinator.update(10_100, false, true); coordinator.update(11_300, false, true);
        assertNull(coordinator.frame()); assertEquals(0, cached.claims);
        cached.add("new", true, false); coordinator.update(12_000, true, true);
        overall = false; coordinator.update(12_001, true, true);
        assertEquals("SUPPRESSED", cached.events.get("overall:new").status);
    }

    private static class FakeStore implements CompletionCoordinator.Store
    {
        String token = "account"; int claims, pendingReads; long revision;
        final Map<String, ProfileState.Celebration> events = new LinkedHashMap<>();
        final Map<String, String> durable = new LinkedHashMap<>();
        String add(String goal, boolean overall, boolean recovered)
        {
            ProfileState.Celebration event = new ProfileState.Celebration();
            event.id = (overall ? "overall:" : "daily:") + goal; event.name = goal; event.detail = "Target reached";
            event.overall = overall; event.recovered = recovered; events.put(event.id, event); durable.put(event.id, "PENDING"); revision++; return event.id;
        }
        void flush() { events.forEach((id, event) -> durable.put(id, event.status)); revision++; }
        public String celebrationToken() { return token; }
        public List<CompletionCoordinator.Notice> pendingCelebrations()
        {
            pendingReads++;
            return events.values().stream().filter(e -> e.status.equals("PENDING") && "PENDING".equals(durable.get(e.id)))
                .map(CompletionCoordinator.Notice::new).collect(Collectors.toList());
        }
        public boolean validCelebration(String id) { return events.containsKey(id) && !events.get(id).status.equals("SUPPRESSED"); }
        public void settleCelebrations(List<String> ids, boolean suppress)
        {
            revision++;
            if (!suppress) { claims++; }
            ids.forEach(id -> { if (events.containsKey(id) && events.get(id).status.equals("PENDING")) { events.get(id).status = suppress ? "SUPPRESSED" : "ANNOUNCED"; } });
        }
        public boolean announcedCelebrations(List<String> ids) { return ids.stream().allMatch(id -> "ANNOUNCED".equals(durable.get(id))); }
    }
}
