package com.mygoals.tracking;

/**
 * Test-only arithmetic proof. A caller must establish snapshot overlap from source evidence;
 * network arrival order alone does not establish it. Not connected to live goal credit.
 */
public final class CounterReconciliationProbe
{
    public enum Result { BASELINE, RECONCILED, DEFERRED, STALE }

    private Long remoteCheckpoint;
    private long unacknowledgedLive;
    private long liveCredit;
    private long recoveredCredit;

    public void recordLive(long amount)
    {
        if (amount <= 0) { throw new IllegalArgumentException("Live amount must be positive"); }
        long nextLive = Math.addExact(liveCredit, amount);
        long nextPending = Math.addExact(unacknowledgedLive, amount);
        liveCredit = nextLive;
        unacknowledgedLive = nextPending;
    }

    public Result observeSnapshot(long value, boolean overlapProven)
    {
        if (value < 0) { throw new IllegalArgumentException("Unknown is not a zero snapshot"); }
        if (!overlapProven) { return Result.DEFERRED; }
        if (remoteCheckpoint == null)
        {
            // A first, potentially delayed snapshot cannot prove which already-credited
            // live events it contains. Establish the baseline before accepting live deltas.
            if (unacknowledgedLive != 0) { return Result.DEFERRED; }
            remoteCheckpoint = value;
            return Result.BASELINE;
        }
        if (value < remoteCheckpoint) { return Result.STALE; }
        long advance = value - remoteCheckpoint;
        long acknowledged = Math.min(advance, unacknowledgedLive);
        long nextRecovered = Math.addExact(recoveredCredit, advance - acknowledged);
        unacknowledgedLive -= acknowledged;
        recoveredCredit = nextRecovered;
        remoteCheckpoint = value;
        return Result.RECONCILED;
    }

    public long getUnacknowledgedLive() { return unacknowledgedLive; }
    public long getLiveCredit() { return liveCredit; }
    public long getRecoveredCredit() { return recoveredCredit; }
    public Long getRemoteCheckpoint() { return remoteCheckpoint; }
}
