package com.mygoals.persistence;

import com.mygoals.domain.ProfileState;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * One running write and one replacement snapshot; no unbounded queue of stale
 * saves.
 */
public final class ProfileSaveQueue
{
	public static final class Request
	{
		public final ProfileState state;
		public final long version;
		public final Map<String, ProfileState> fragments;
		public final java.util.Set<String> originalHistory;

		public Request(ProfileState state, long version, Map<String, ProfileState> fragments)
		{
			this.state = state;
			this.version = version;
			this.fragments = Map.copyOf(fragments);
			originalHistory = java.util.Set.copyOf(state.historyFiles);
		}
	}

	private final Executor executor;

	public interface Writer
	{
		void save(ProfileState state, Map<String, ProfileState> fragments) throws Exception;
	}

	private final Writer writer;
	private final BiConsumer<Request, Exception> completed;
	private Request pending;
	private boolean writing;

	public ProfileSaveQueue(Executor executor, ProfileStateRepository repository, BiConsumer<Request, Exception> completed)
	{
		this(executor, repository::save, completed);
	}

	public ProfileSaveQueue(Executor executor, Writer writer, BiConsumer<Request, Exception> completed)
	{
		this.executor = executor;
		this.writer = writer;
		this.completed = completed;
	}

	public synchronized void offer(Request latest)
	{
		pending = latest;
		if (!writing)
		{
			writing = true;
			executor.execute(this::drain);
		}
	}

	private void drain()
	{
		while (true)
		{
			Request request;
			synchronized (this)
			{
				request = pending;
				pending = null;
				if (request == null)
				{
					writing = false;
					return;
				}
			}
			Exception failure = null;
			try
			{
				writer.save(request.state, request.fragments);
			}
			catch (Exception ex)
			{
				failure = ex;
			}
			completed.accept(request, failure);
		}
	}
}
