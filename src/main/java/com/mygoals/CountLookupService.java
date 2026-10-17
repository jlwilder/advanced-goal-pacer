package com.mygoals;

import com.mygoals.engine.GoalEngine;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Client-thread HiScores request budgets and results survive hops without
 * retaining the profile engine.
 */
final class CountLookupService
{
	private final MyGoalsController.Session session;
	private final MyGoalsController.Dispatcher clientThread;
	private final Clock clock;
	private final Supplier<GoalEngine> current;
	private final BooleanSupplier requestReady;
	private final Runnable save, publish;
	private final Set<String> requestedCountGoals = new java.util.HashSet<>();
	private int countFailures;
	private String countStatus = "", countRetrySource = GoalEngine.RECOVERED;

	private static final class CountLookup
	{
		CompletableFuture<Map<String, Long>> pending;
		Set<String> eligible = Set.of();
		Map<String, Long> totals;
		long next, checkedAt;
		int failures;
	}

	private final Map<String, CountLookup> countLookups = new LinkedHashMap<>();
	private CountLookup attachedLookup;

	CountLookupService(MyGoalsController.Session session, MyGoalsController.Dispatcher clientThread, Clock clock,
			Supplier<GoalEngine> current, BooleanSupplier requestReady, Runnable save, Runnable publish)
	{
		this.session = session;
		this.clientThread = clientThread;
		this.clock = clock;
		this.current = current;
		this.requestReady = requestReady;
		this.save = save;
		this.publish = publish;
	}

	String status()
	{
		return countStatus;
	}

	void detach()
	{
		requestedCountGoals.clear();
		attachedLookup = null;
		countFailures = 0;
		countStatus = "";
	}

	void requestNewGoals()
	{
		GoalEngine engine = current.get();
		if (engine != null && !requestedCountGoals.containsAll(engine.activeCountGoals()) && countFailures < 3)
		{
			request(countFailures > 0 ? countRetrySource : "HiScores refreshed", false);
		}
	}

	void request(String source, boolean manual)
	{
		GoalEngine engine = current.get();
		if (!requestReady.getAsBoolean() || engine == null)
		{
			return;
		}
		Set<String> eligible = engine.activeCountGoals();
		if (eligible.isEmpty())
		{
			if (manual)
			{
				countStatus = "";
			}
			return;
		}
		String lookupKey = session.countLookupKey();
		if (lookupKey == null)
		{
			return;
		}
		CountLookup lookup = countLookups.computeIfAbsent(lookupKey, ignored -> new CountLookup());
		attachedLookup = lookup;
		countFailures = lookup.failures;
		// Retain budgets/results across hops, but bound the number of account/endpoint
		// caches.
		if (countLookups.size() > 16)
		{
			var entries = countLookups.entrySet().iterator();
			while (countLookups.size() > 16 && entries.hasNext())
			{
				var entry = entries.next();
				if (entry.getValue() != lookup && entry.getValue().pending == null)
				{
					entries.remove();
				}
			}
		}
		if (lookup.pending != null)
		{
			eligible.stream().filter(lookup.eligible::contains).forEach(requestedCountGoals::add);
			countStatus = "Checking count totals…";
			return;
		}
		if (clock.millis() < lookup.next)
		{
			if (lookup.totals != null)
			{
				applyCounts(lookup.totals, eligible, source, lookup.checkedAt);
				requestedCountGoals.addAll(eligible);
			}
			if (manual)
			{
				countStatus = (lookup.totals == null ? "Count refresh available in " : "Using recent totals • count refresh available in ")
						+ Math.max(1, (lookup.next - clock.millis() + 999) / 1000) + "s";
			}
			return;
		}
		if (!manual && lookup.failures >= 3)
		{
			return;
		}
		if (manual)
		{
			lookup.failures = 0;
		}
		countFailures = lookup.failures;
		countRetrySource = source;
		requestedCountGoals.addAll(eligible);
		lookup.next = clock.millis() + 30_000;
		lookup.eligible = Set.copyOf(eligible);
		countStatus = "Checking count totals…";
		CompletableFuture<Map<String, Long>> future;
		try
		{
			future = session.lookupCounts();
		}
		catch (RuntimeException ex)
		{
			future = new CompletableFuture<>();
			future.completeExceptionally(ex);
		}
		lookup.pending = future;
		future.whenComplete((totals, failure) -> clientThread.invoke(() ->
		{
			lookup.pending = null;
			if (failure != null || totals == null)
			{
				lookup.failures++;
				lookup.totals = null;
				lookup.next = clock.millis() + Math.min(120_000, 30_000L * lookup.failures);
			}
			else
			{
				lookup.failures = 0;
				lookup.totals = Map.copyOf(totals);
				lookup.checkedAt = clock.millis();
			}
			GoalEngine currentEngine = current.get();
			if (attachedLookup != lookup || currentEngine == null ||
					(!java.util.Objects.equals(lookupKey, session.countLookupKey()) && !session.loading()))
			{
				return;
			}
			countFailures = lookup.failures;
			if (failure != null || totals == null)
			{
				requestedCountGoals.removeAll(lookup.eligible);
				countStatus = countFailures < 3 ? "HiScores unavailable • retry scheduled; live tracking continues"
						: "HiScores unavailable • live tracking continues; use Refresh to retry";
			}
			else
			{
				Set<String> boundary = new java.util.HashSet<>(currentEngine.activeCountGoals());
				boundary.retainAll(lookup.eligible);
				applyCounts(lookup.totals, boundary, source, lookup.checkedAt);
			}
			publish.run();
		}));
	}

	private void applyCounts(Map<String, Long> totals, Set<String> eligible, String source, long checkedAt)
	{
		GoalEngine engine = current.get();
		if (engine == null)
		{
			return;
		}
		engine.hiscoresChecked(java.time.Instant.ofEpochMilli(checkedAt));
		totals.forEach((metric, total) ->
		{
			if (total != null && total >= 0)
			{
				engine.observeCount(metric, total, false, source, eligible, clock.instant());
			}
		});
		boolean waiting = eligible.stream().map(id -> engine.state().goals.get(id)).filter(java.util.Objects::nonNull)
				.anyMatch(g -> g.countCheckpoint == null || !totals.containsKey(g.spec.metricId));
		countStatus = waiting ? "HiScores checked • some totals are unranked / unknown" : "HiScores count totals checked";
		save.run();
	}

}
