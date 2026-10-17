package com.mygoals.persistence;

import com.mygoals.domain.ProfileState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileSaveQueueTest
{
    private ProfileSaveQueue.Request request(long sequence)
    {
        ProfileState state = new ProfileState("a".repeat(64)); state.saveSequence = sequence;
        return new ProfileSaveQueue.Request(state, sequence, Map.of());
    }

    @Test public void aSlowWriteKeepsOnlyTheLatestReplacementSnapshot() throws Exception
    {
        var io = Executors.newSingleThreadExecutor(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), saved = new CountDownLatch(2);
        List<Long> writes = java.util.Collections.synchronizedList(new ArrayList<>());
        ProfileSaveQueue queue = new ProfileSaveQueue(io, (state, fragments) -> {
            writes.add(state.saveSequence);
            if (state.saveSequence == 1) { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
        }, (request, failure) -> { assertNull(failure); saved.countDown(); });
        try
        {
            queue.offer(request(1)); assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 2; i <= 200; i++) { queue.offer(request(i)); }
            release.countDown(); assertTrue(saved.await(5, TimeUnit.SECONDS));
            io.shutdown(); assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 200L), writes);
        }
        finally { release.countDown(); io.shutdownNow(); }
    }

    @Test public void workerFailureStillDeliversTheLatestPendingSnapshot() throws Exception
    {
        List<Runnable> tasks = new ArrayList<>(); List<Long> acknowledgements = new ArrayList<>();
        ProfileSaveQueue[] queue = new ProfileSaveQueue[1];
        queue[0] = new ProfileSaveQueue(tasks::add, (state, fragments) -> {
            if (state.saveSequence == 1) { queue[0].offer(request(2)); throw new java.io.IOException("Synthetic failure"); }
        }, (request, failure) -> acknowledgements.add(failure == null ? request.version : -request.version));
        queue[0].offer(request(1)); assertEquals(1, tasks.size()); tasks.remove(0).run();
        assertEquals(List.of(-1L, 2L), acknowledgements); assertTrue(tasks.isEmpty());
    }
}
