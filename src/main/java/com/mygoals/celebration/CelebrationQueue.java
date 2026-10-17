package com.mygoals.celebration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** A client-thread-owned queue. Updates use elapsed monotonic milliseconds. */
public final class CelebrationQueue
{
	public static final long QUIET_MILLIS = 1200;

	public static final class Card
	{
		public final String id;
		public final String title;
		public final String detail;
		public final String context;
		public final boolean overall;

		public Card(String id, String title, String detail, boolean overall)
		{
			this(id, title, detail, "", overall);
		}

		public Card(String id, String title, String detail, String context, boolean overall)
		{
			this.id = id;
			this.title = title;
			this.detail = detail;
			this.context = context;
			this.overall = overall;
		}

		public long durationMillis()
		{
			return overall ? 5600 : 3600;
		}
	}

	public static final class Frame
	{
		public final Card card;
		public final long visibleMillis;
		public final int waiting;
		public final String status;

		private Frame(Card card, long visibleMillis, int waiting, String status)
		{
			this.card = card;
			this.visibleMillis = visibleMillis;
			this.waiting = waiting;
			this.status = status;
		}

		public boolean fireworks()
		{
			return card != null && card.overall && visibleMillis < 2300;
		}
	}

	private final Deque<Card> pending = new ArrayDeque<>();
	private final Set<String> seen = new HashSet<>();
	private final java.util.Map<String, Long> readyAt = new java.util.HashMap<>();
	private Card current;
	private long visibleMillis;
	private long clearSince = -1;
	private long lastNow = -1;
	private boolean wasBlocked;

	public boolean enqueue(Card card)
	{
		return enqueue(card, 0);
	}

	public boolean enqueue(Card card, long earliestMillis)
	{
		if (card == null || card.id == null)
		{
			throw new IllegalArgumentException("A card needs an occurrence ID");
		}
		if (!seen.add(card.id))
		{
			return false;
		}
		pending.addLast(card);
		readyAt.put(card.id, earliestMillis);
		return true;
	}

	private Card next(long now)
	{
		Card card = pending.peekFirst();
		if (card == null || readyAt.get(card.id) > now)
		{
			return null;
		}
		readyAt.remove(card.id);
		return pending.pollFirst();
	}

	public Frame update(long now, boolean blocked, boolean ready)
	{
		if (now < 0 || (lastNow >= 0 && now < lastNow))
		{
			throw new IllegalArgumentException("Clock must be monotonic");
		}
		long delta = lastNow < 0 ? 0 : now - lastNow;
		lastNow = now;
		if (!ready || blocked)
		{
			wasBlocked = true;
			clearSince = -1;
			return new Frame(null, 0, pending.size() + (current == null ? 0 : 1),
					ready ? "Waiting for native cards" : "Waiting for game session");
		}
		if (wasBlocked)
		{
			if (clearSince < 0)
			{
				clearSince = now;
			}
			if (now - clearSince < QUIET_MILLIS)
			{
				return new Frame(null, 0, pending.size() + (current == null ? 0 : 1), "Waiting for native cards to clear");
			}
			wasBlocked = false;
			delta = 0;
		}
		if (current == null)
		{
			current = next(now);
			visibleMillis = 0;
			delta = 0;
		}
		if (current != null)
		{
			visibleMillis += delta;
			if (visibleMillis >= current.durationMillis())
			{
				current = next(now);
				visibleMillis = 0;
			}
		}
		return new Frame(current, visibleMillis, pending.size(), current == null ? "Ready" : "Showing Advanced Goal Pacer card");
	}

	public void clear()
	{
		pending.clear();
		seen.clear();
		readyAt.clear();
		current = null;
		visibleMillis = 0;
		clearSince = -1;
		lastNow = -1;
		wasBlocked = false;
	}

	public void cancel(String id)
	{
		pending.removeIf(card -> card.id.equals(id));
		if (current != null && current.id.equals(id))
		{
			current = null;
			visibleMillis = 0;
		}
		seen.remove(id);
		readyAt.remove(id);
	}
}
