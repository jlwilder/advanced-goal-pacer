package com.mygoals.celebration;

import org.junit.Test;
import static org.junit.Assert.*;

public class CelebrationQueueTest
{
    private CelebrationQueue.Card card(String id, boolean overall)
    {
        return new CelebrationQueue.Card(id, "Goal", "Target met", overall);
    }

    @Test
    public void waitsForNativeLifecycleAndQuietInterval()
    {
        CelebrationQueue q = new CelebrationQueue();
        q.enqueue(card("a", true));
        assertNull(q.update(0, true, true).card);
        assertNull(q.update(3000, false, true).card);
        assertNull(q.update(4199, false, true).card);
        assertEquals("a", q.update(4200, false, true).card.id);
    }

    @Test
    public void preemptionHidesFireworksAndPreservesVisibleDuration()
    {
        CelebrationQueue q = new CelebrationQueue();
        q.enqueue(card("a", true));
        q.update(0, false, true);
        assertEquals(500, q.update(500, false, true).visibleMillis);
        CelebrationQueue.Frame blocked = q.update(600, true, true);
        assertNull(blocked.card);
        assertFalse(blocked.fireworks());
        q.update(5000, false, true);
        CelebrationQueue.Frame resumed = q.update(6200, false, true);
        assertEquals("a", resumed.card.id);
        assertEquals(500, resumed.visibleMillis);
    }

    @Test
    public void cardsAreFifoAndDuplicateOccurrencesDoNotReplay()
    {
        CelebrationQueue q = new CelebrationQueue();
        assertTrue(q.enqueue(card("a", false)));
        assertFalse(q.enqueue(card("a", false)));
        q.enqueue(card("b", true));
        assertEquals("a", q.update(0, false, true).card.id);
        assertEquals("b", q.update(3600, false, true).card.id);
        assertNull(q.update(9200, false, true).card);
    }

    @Test
    public void sceneLossHidesEffectsAndClearRemovesOldProfileQueue()
    {
        CelebrationQueue q = new CelebrationQueue();
        q.enqueue(card("a", true));
        q.update(0, false, true);
        assertNull(q.update(100, false, false).card);
        q.clear();
        assertNull(q.update(0, false, true).card);
    }

    @Test
    public void nativeOpeningHoldingClosingAndUnknownStateBlock()
    {
        for (int stage = 1; stage <= 5; stage++)
        {
            assertTrue(NativeNotificationState.blocks(stage, false, false, false));
        }
        assertFalse(NativeNotificationState.blocks(0, false, false, false));
        assertFalse(NativeNotificationState.blocks(6, false, false, false));
        assertTrue(NativeNotificationState.blocks(7, false, false, false));
        assertTrue(NativeNotificationState.blocks(6, true, false, false));
        assertTrue(NativeNotificationState.blocks(6, false, true, false));
        assertTrue(NativeNotificationState.blocks(6, false, false, true));
    }
}
