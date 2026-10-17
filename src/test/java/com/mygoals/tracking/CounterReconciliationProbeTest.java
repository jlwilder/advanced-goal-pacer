package com.mygoals.tracking;

import org.junit.Test;
import static org.junit.Assert.*;

public class CounterReconciliationProbeTest
{
    @Test
    public void firstSnapshotDoesNotCreditLifetimeTotals()
    {
        CounterReconciliationProbe p = new CounterReconciliationProbe();
        assertEquals(CounterReconciliationProbe.Result.BASELINE, p.observeSnapshot(100000, true));
        assertEquals(0, p.getRecoveredCredit());
    }

    @Test
    public void delayedRemoteAdvanceAcknowledgesLiveCreditInPieces()
    {
        CounterReconciliationProbe p = new CounterReconciliationProbe();
        p.observeSnapshot(100, true);
        p.recordLive(10);
        p.observeSnapshot(105, true);
        assertEquals(5, p.getUnacknowledgedLive());
        assertEquals(0, p.getRecoveredCredit());
        p.observeSnapshot(110, true);
        assertEquals(0, p.getUnacknowledgedLive());
        p.observeSnapshot(114, true);
        p.observeSnapshot(114, true);
        assertEquals(10, p.getLiveCredit());
        assertEquals(4, p.getRecoveredCredit());
    }

    @Test
    public void ambiguousSnapshotCannotAdvanceCheckpointOrClearDebt()
    {
        CounterReconciliationProbe p = new CounterReconciliationProbe();
        p.observeSnapshot(100, true);
        p.recordLive(2);
        assertEquals(CounterReconciliationProbe.Result.DEFERRED, p.observeSnapshot(108, false));
        assertEquals(Long.valueOf(100), p.getRemoteCheckpoint());
        assertEquals(2, p.getUnacknowledgedLive());
        assertEquals(0, p.getRecoveredCredit());
    }

    @Test
    public void backwardsSnapshotDoesNotResetProgress()
    {
        CounterReconciliationProbe p = new CounterReconciliationProbe();
        p.observeSnapshot(100, true);
        p.recordLive(2);
        assertEquals(CounterReconciliationProbe.Result.STALE, p.observeSnapshot(99, true));
        assertEquals(2, p.getUnacknowledgedLive());
        assertEquals(Long.valueOf(100), p.getRemoteCheckpoint());
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeSnapshotIsNotZero() { new CounterReconciliationProbe().observeSnapshot(-1, true); }

    @Test
    public void firstLateSnapshotCannotEraseUnmatchedLiveCredit()
    {
        CounterReconciliationProbe p = new CounterReconciliationProbe();
        p.recordLive(10);
        assertEquals(CounterReconciliationProbe.Result.DEFERRED, p.observeSnapshot(105, true));
        assertNull(p.getRemoteCheckpoint());
        assertEquals(10, p.getUnacknowledgedLive());
        assertEquals(0, p.getRecoveredCredit());
    }
}
