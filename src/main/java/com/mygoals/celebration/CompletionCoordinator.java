package com.mygoals.celebration;

import com.mygoals.domain.ProfileState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Client-thread presentation admission. A durable claim precedes the first
 * visible frame.
 */
public final class CompletionCoordinator
{
	public static final class Notice
	{
		public final String id, name, detail, date;
		public final boolean overall, recovered;

		public Notice(ProfileState.Celebration event)
		{
			id = event.id;
			name = event.name;
			detail = event.detail;
			date = event.date;
			overall = event.overall;
			recovered = event.recovered;
		}
	}

	public interface Store
	{
		String celebrationToken();

		List<Notice> pendingCelebrations();

		boolean validCelebration(String id);

		void settleCelebrations(List<String> ids, boolean suppress);

		boolean announcedCelebrations(List<String> ids);

		/**
		 * A negative revision requests uncached admission checks (useful for external
		 * stores).
		 */
		default long celebrationRevision()
		{
			return -1;
		}

		default void presentedCelebrations(List<String> ids)
		{
		}
	}

	private static final class Batch
	{
		final CelebrationQueue.Card card;
		final List<String> ids;
		boolean claimed;

		Batch(CelebrationQueue.Card card, List<String> ids)
		{
			this.card = card;
			this.ids = ids;
		}
	}

	private final Store store;
	private final BooleanSupplier daily, overall;
	private final CelebrationQueue queue = new CelebrationQueue();
	private final Map<String, Batch> batches = new LinkedHashMap<>();
	private String token;
	private volatile CelebrationQueue.Frame frame;
	private long revision = Long.MIN_VALUE;
	private boolean dailyEnabled, overallEnabled;

	public CompletionCoordinator(Store store, BooleanSupplier daily, BooleanSupplier overall)
	{
		this.store = store;
		this.daily = daily;
		this.overall = overall;
	}

	public void update(long now, boolean blocked, boolean ready)
	{
		String nextToken = store.celebrationToken();
		if (!java.util.Objects.equals(token, nextToken))
		{
			reset();
			token = nextToken;
		}
		if (token == null)
		{
			frame = null;
			return;
		}
		long nextRevision = store.celebrationRevision();
		boolean nextDaily = daily.getAsBoolean(), nextOverall = overall.getAsBoolean();
		if (nextRevision < 0 || revision != nextRevision || dailyEnabled != nextDaily || overallEnabled != nextOverall)
		{
			revision = nextRevision;
			dailyEnabled = nextDaily;
			overallEnabled = nextOverall;
			refresh(now);
		}
		CelebrationQueue.Frame candidate = queue.update(now, blocked, ready);
		frame = null;
		if (candidate.card != null || candidate.status.equals("Ready"))
		{
			batches.entrySet().removeIf(entry -> entry.getValue().claimed &&
					(candidate.card == null || !entry.getKey().equals(candidate.card.id)));
		}
		if (candidate.card == null)
		{
			return;
		}
		Batch batch = batches.get(candidate.card.id);
		if (!batch.claimed)
		{
			batch.claimed = true;
			store.settleCelebrations(batch.ids, false);
			queue.update(now, true, ready);
			return;
		}
		if (!store.announcedCelebrations(batch.ids))
		{
			queue.update(now, true, ready);
			return;
		}
		store.presentedCelebrations(batch.ids);
		frame = candidate;
	}

	private void refresh(long now)
	{
		List<String> suppressed = new ArrayList<>();
		batches.entrySet().removeIf(entry ->
		{
			Batch batch = entry.getValue();
			boolean enabled = batch.card.overall ? overallEnabled : dailyEnabled;
			if (!enabled || batch.ids.stream().anyMatch(id -> !store.validCelebration(id)))
			{
				queue.cancel(entry.getKey());
				if (!enabled)
				{
					suppressed.addAll(batch.ids);
				}
				return true;
			}
			return false;
		});
		var covered = batches.values().stream().flatMap(b -> b.ids.stream()).collect(Collectors.toSet());
		List<Notice> recoveredDaily = new ArrayList<>();
		for (Notice notice : store.pendingCelebrations())
		{
			if (covered.contains(notice.id))
			{
				continue;
			}
			if (!(notice.overall ? overallEnabled : dailyEnabled))
			{
				suppressed.add(notice.id);
				continue;
			}
			if (!notice.overall && notice.recovered)
			{
				recoveredDaily.add(notice);
				continue;
			}
			add(new CelebrationQueue.Card(notice.id, notice.overall ? "Goal complete!" : "Daily target complete!", notice.name,
					context(notice), notice.overall), List.of(notice.id), now);
		}
		if (recoveredDaily.size() == 1)
		{
			Notice notice = recoveredDaily.get(0);
			add(new CelebrationQueue.Card(notice.id, "Daily target complete!", notice.name, context(notice), false), List.of(notice.id),
					now);
		}
		else if (!recoveredDaily.isEmpty())
		{
			List<String> ids = recoveredDaily.stream().map(n -> n.id).collect(Collectors.toList());
			add(new CelebrationQueue.Card(String.join(",", ids), "Daily targets complete!", recoveredDaily.size() + " goals completed",
					"Progress recovered at login or refresh", false), ids, now);
		}
		if (!suppressed.isEmpty())
		{
			store.settleCelebrations(suppressed.stream().distinct().collect(Collectors.toList()), true);
		}
	}

	private void add(CelebrationQueue.Card card, List<String> ids, long now)
	{
		// Leave a game-tick-sized admission window for the count and its associated
		// reward to coalesce.
		if (queue.enqueue(card, now + 600))
		{
			batches.put(card.id, new Batch(card, List.copyOf(ids)));
		}
	}

	private static String context(Notice notice)
	{
		return notice.detail + (notice.date == null ? "" : " · " + notice.date) + (notice.recovered ? " · Recovered" : "");
	}

	public CelebrationQueue.Frame frame()
	{
		return frame;
	}

	public void interrupt(long now)
	{
		frame = null;
		queue.update(now, true, false);
	}

	public void reset()
	{
		frame = null;
		queue.clear();
		batches.clear();
		token = null;
		revision = Long.MIN_VALUE;
	}
}
