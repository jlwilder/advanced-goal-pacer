package com.mygoals;

import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.persistence.StateSnapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client-thread observations remain bounded even if the transfer worker stops
 * responding.
 */
final class RestoreOperation
{

	static final int MAX_BUFFERED_EVENTS = 1024;
	final ProfileStateRepository repository;
	final ProfileState previous;
	final Map<String, ProfileState> fragments;
	final Map<String, Long> xp, counts;
	final Set<String> unavailable;
	final String collectionToken;
	final Instant started;
	final AtomicBoolean cancelled = new AtomicBoolean();
	volatile ProfileStateRepository.Restored result;
	AutoCloseable lease;
	private final List<Consumer<GoalEngine>> events = new ArrayList<>();
	private GoalEngine fallback;

	RestoreOperation(ProfileStateRepository repository, ProfileState previous, Map<String, ProfileState> fragments,
			Map<String, Long> xp, Map<String, Long> counts, Set<String> unavailable, String token, Instant started)
	{
		this.repository = repository;
		this.previous = previous;
		this.fragments = Map.copyOf(fragments);
		this.xp = Map.copyOf(xp);
		this.counts = Map.copyOf(counts);
		this.unavailable = Set.copyOf(unavailable);
		collectionToken = token;
		this.started = started;
	}

	void observe(Consumer<GoalEngine> event)
	{
		if (fallback != null)
		{
			event.accept(fallback);
			return;
		}
		events.add(event);
		if (events.size() == MAX_BUFFERED_EVENTS)
		{
			cancelled.set(true);
			// The worker may be serializing previous. Never share mutable state with it.
			fallback = engine(StateSnapshot.copy(previous));
			events.forEach(observation -> observation.accept(fallback));
			events.clear();
		}
	}

	boolean keptPrevious()
	{
		return fallback != null || result == null;
	}

	GoalEngine reconcile()
	{
		GoalEngine reconciled;
		if (fallback != null)
		{
			reconciled = fallback;
			// Cancellation can race the disk commit. The next normal save must supersede
			// it.
			if (result != null)
			{
				reconciled.state().saveSequence = Math.max(reconciled.state().saveSequence, result.state.saveSequence);
			}
		}
		else
		{
			reconciled = engine(result == null ? previous : result.state);
			if (result != null)
			{
				reconciled.observe(xp, GoalEngine.RECOVERED, started);
				counts.forEach((metric, total) -> reconciled.observeCount(metric, total, false, GoalEngine.RECOVERED, null, started));
			}
			events.forEach(event -> event.accept(reconciled));
			events.clear();
		}
		return reconciled;
	}

	private GoalEngine engine(ProfileState state)
	{
		GoalEngine engine = new GoalEngine(state, ZoneId.systemDefault());
		engine.setUnavailableUniques(unavailable);
		return engine;
	}
}
