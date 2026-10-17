package com.mygoals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackupOperationsTest
{
	@Test
	public void shutdownStillDrainsAndCompletesWhenTheTransferQueueIsFull() throws Exception
	{
		BackupOperations backups = new BackupOperations(net.runelite.http.api.RuneLiteAPI.GSON, Runnable::run, Runnable::run);
		var field = BackupOperations.class.getDeclaredField("transfers");
		field.setAccessible(true);
		var worker = (ThreadPoolExecutor) field.get(backups);
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		try
		{
			worker.execute(() ->
			{
				started.countDown();
				try
				{
					assertTrue(release.await(5, TimeUnit.SECONDS));
				}
				catch (InterruptedException ex)
				{
					throw new AssertionError(ex);
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			while (worker.getQueue().remainingCapacity() > 0)
			{
				worker.execute(() ->
				{
				});
			}
			var done = backups.stop();
			assertSame(done, backups.stop());
			assertFalse(done.isDone());
			release.countDown();
			done.get(5, TimeUnit.SECONDS);
			assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
		}
		finally
		{
			release.countDown();
			backups.stop().get(5, TimeUnit.SECONDS);
		}
	}
}
