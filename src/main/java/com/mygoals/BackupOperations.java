package com.mygoals;

import com.google.gson.Gson;
import com.mygoals.domain.ProfileState;
import com.mygoals.persistence.BackupArchive;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.ui.BackupResult;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;

/**
 * Lengthy ZIP work has a separate bounded worker; ordinary autosaves remain
 * available.
 */
final class BackupOperations
{
	private final BackupArchive archive;
	private final Executor io;
	private final MyGoalsController.Dispatcher clientThread;
	private final Set<BackupArchive.Prepared> opened = ConcurrentHashMap.newKeySet();
	private final Set<AtomicBoolean> jobs = ConcurrentHashMap.newKeySet();
	private volatile CompletableFuture<Void> closing;
	private final ThreadPoolExecutor transfers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task ->
	{
		Thread thread = new Thread(task, "my-goals-backup-transfer");
		thread.setDaemon(true);
		return thread;
	})
	{
		@Override
		protected void terminated()
		{
			closePrepared();
			CompletableFuture<Void> done = closing;
			if (done != null)
			{
				done.complete(null);
			}
		}
	};

	BackupOperations(Gson gson, Executor io, MyGoalsController.Dispatcher clientThread)
	{
		archive = new BackupArchive(gson);
		this.io = io;
		this.clientThread = clientThread;
	}

	private void submit(Runnable action, Consumer<Exception> failed)
	{
		try
		{
			transfers.execute(action);
		}
		catch (RejectedExecutionException ex)
		{
			clientThread.invoke(() -> failed.accept(new IOException("Backup worker is busy; retry shortly.")));
		}
	}

	void export(ProfileStateRepository repository, ProfileState state, Map<String, ProfileState> fragments,
			Filepath destination, String account, long now, Consumer<BackupResult> completed)
	{
		AtomicBoolean cancelled = new AtomicBoolean();
		jobs.add(cancelled);
		AutoCloseable lease = repository.retain(state);
		submit(() ->
		{
			BackupResult reply;
			try
			{
				archive.export(destination, repository.transfers(), state, fragments, repository::historyFile, account, now, cancelled::get,
						true);
				reply = BackupResult.success("Backup exported. It includes this character's goals, progress, and complete history.");
			}
			catch (Exception ex)
			{
				reply = BackupResult.failure(error(ex));
			}
			finally
			{
				jobs.remove(cancelled);
				release(repository, lease);
			}
			BackupResult result = reply;
			clientThread.invoke(() -> completed.accept(result));
		}, ex ->
		{
			jobs.remove(cancelled);
			release(repository, lease);
			completed.accept(BackupResult.failure(error(ex)));
		});
	}

	void prepare(ProfileStateRepository repository, Filepath source, String account, String key,
			BiConsumer<BackupArchive.Prepared, BackupResult> completed)
	{
		AtomicBoolean cancelled = new AtomicBoolean();
		jobs.add(cancelled);
		submit(() ->
		{
			BackupArchive.Prepared prepared = null;
			BackupResult reply;
			try
			{
				prepared = archive.prepare(source, repository.transfers(), account, key, cancelled::get);
				if (cancelled.get())
				{
					prepared.close();
					prepared = null;
					throw new IOException("Backup operation cancelled.");
				}
				opened.add(prepared);
				reply = BackupResult.preview(prepared.id, prepared.state.goals.size() + prepared.state.finished.size(), prepared.periods,
						prepared.createdAt);
			}
			catch (Exception ex)
			{
				reply = BackupResult.failure(error(ex));
			}
			finally
			{
				jobs.remove(cancelled);
			}
			BackupArchive.Prepared loaded = prepared;
			BackupResult result = reply;
			clientThread.invoke(() -> completed.accept(loaded, result));
		}, ex ->
		{
			jobs.remove(cancelled);
			completed.accept(null, BackupResult.failure(error(ex)));
		});
	}

	void restore(RestoreOperation operation, BackupArchive.Prepared prepared, String account, long now, Consumer<Exception> completed)
	{
		// The replacing transaction stays ordered with canonical saves and logout
		// reconciliation.
		jobs.add(operation.cancelled);
		io.execute(() ->
		{
			Exception failure = null;
			try
			{
				operation.result = operation.repository.restore(prepared, operation.previous, operation.fragments, account, now,
						operation.cancelled::get);
			}
			catch (Exception ex)
			{
				failure = ex;
			}
			finally
			{
				jobs.remove(operation.cancelled);
				discard(prepared);
			}
			Exception result = failure;
			clientThread.invoke(() -> completed.accept(result));
		});
	}

	void discard(BackupArchive.Prepared prepared)
	{
		if (prepared != null)
		{
			submit(() -> close(prepared), ex ->
			{
			});
		}
	}

	void cancelTransfers()
	{
		jobs.forEach(flag -> flag.set(true));
	}

	void closeAll()
	{
		cancelTransfers();
		submit(this::closePrepared, ex ->
		{
		});
	}

	CompletableFuture<Void> stop()
	{
		if (closing != null)
		{
			return closing;
		}
		closing = new CompletableFuture<>();
		cancelTransfers();
		// Completion belongs to executor termination, so even a full queue can stop
		// cleanly.
		transfers.shutdown();
		return closing;
	}

	private void closePrepared()
	{
		for (BackupArchive.Prepared prepared : opened.toArray(new BackupArchive.Prepared[0]))
		{
			close(prepared);
		}
	}

	private void release(ProfileStateRepository repository, AutoCloseable lease)
	{
		try
		{
			lease.close();
		}
		catch (Exception ignored)
		{
		}
		try
		{
			io.execute(() ->
			{
				try
				{
					repository.cleanup();
				}
				catch (IOException ignored)
				{
				}
			});
		}
		catch (RejectedExecutionException ignored)
		{
			// Next load also cleans unreachable segments.
		}
	}

	private void close(BackupArchive.Prepared prepared)
	{
		try
		{
			prepared.close();
		}
		catch (IOException ignored)
		{
		}
		opened.remove(prepared);
	}

	static String error(Exception failure)
	{
		return failure instanceof IOException || failure instanceof IllegalArgumentException
				? failure.getMessage() == null ? "The backup operation could not be completed." : failure.getMessage()
				: "The backup operation could not be completed (" + failure.getClass().getSimpleName() + ").";
	}
}
