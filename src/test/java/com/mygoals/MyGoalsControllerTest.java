package com.mygoals;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.persistence.StateCodec;
import com.mygoals.tracking.CountMessageParser;
import com.mygoals.ui.GoalsView;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.runelite.client.util.Filepath;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class MyGoalsControllerTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();
	private final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
	private final List<GoalsView> views = new ArrayList<>();
	private final FakeSession session = new FakeSession();
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T17:00:00Z"), ZoneId.of("America/Chicago"));

	private MyGoalsController controller()
	{
		return new MyGoalsController(net.runelite.http.api.RuneLiteAPI.GSON, session, callbacks::add,
				() -> Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), clock, views::add);
	}

	private GoalsView view()
	{
		return views.get(views.size() - 1);
	}

	private void pumpUntil(BooleanSupplier done) throws Exception
	{
		for (int tries = 0; !done.getAsBoolean() && tries < 20; tries++)
		{
			Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull("Expected asynchronous I/O completion", callback);
			callback.run();
		}
		assertTrue(done.getAsBoolean());
	}

	private void initialize(MyGoalsController controller) throws Exception
	{
		controller.tick();
		pumpUntil(() -> !views.isEmpty() && view().status.contains("two-tick"));
		controller.tick();
		controller.tick();
		pumpUntil(() -> view().editable);
	}

	private GoalSpec goal()
	{
		return new GoalSpec("Mine", null, "MINING", GoalSpec.Mode.GAIN, 1000, 0, GoalSpec.Cadence.DAILY,
				LocalDate.parse("2026-10-16"), null, ZoneId.of("America/Chicago"));
	}

	@Test
	public void accountSwitchRejectsOldEditorCommandsAndKeepsFilesSeparate() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			String oldToken = view().token;
			controller.command(oldToken, engine -> engine.add(goal(), clock.instant()));
			assertEquals(1, view().goals.size());
			session.key = "b".repeat(64);
			controller.tick();
			pumpUntil(() -> view().status.contains("two-tick"));
			controller.tick();
			controller.tick();
			pumpUntil(() -> view().editable);
			assertEquals(0, view().goals.size());
			controller.command(oldToken, engine -> engine.add(goal(), clock.instant()));
			assertEquals(0, view().goals.size());
			assertTrue(view().message.contains("character changed"));
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository savedA = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
		{
			assertEquals(1, savedA.load().state.goals.size());
		}
	}

	@Test
	public void stopDuringAsynchronousLoadReleasesSaveLockWithoutApplyingOldCallback() throws Exception
	{
		MyGoalsController controller = controller();
		controller.tick();
		Runnable lateLoad = callbacks.poll(5, TimeUnit.SECONDS);
		assertNotNull(lateLoad);
		controller.stop().get(5, TimeUnit.SECONDS);
		lateLoad.run();
		try (ProfileStateRepository next = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			assertTrue(next.load().writable);
		}
	}

	@Test
	public void profileRefreshDuringPendingLoadDoesNotLockOutItsOwnReload() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			controller.tick();
			Runnable staleLoad = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(staleLoad);
			controller.sessionChanged();
			controller.tick();
			staleLoad.run();
			pumpUntil(() -> view().status.contains("two-tick"));
			controller.tick();
			controller.tick();
			pumpUntil(() -> view().editable);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void regionLoadingWhileSaveLoadsDoesNotLeaveTheSidebarStuckLoading() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			controller.tick();
			Runnable loaded = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(loaded);
			session.loading = true;
			loaded.run();
			controller.tick();
			session.loading = false;
			controller.tick();
			controller.tick();
			pumpUntil(() -> view().editable);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void shutdownFlushesXpEarnedAfterLastSaveAndRegionLoadingKeepsSession() throws Exception
	{
		MyGoalsController controller = controller();
		initialize(controller);
		String token = view().token;
		controller.command(token, engine -> engine.add(goal(), clock.instant()));
		session.loading = true;
		controller.tick();
		session.loading = false;
		session.xp = 100_300;
		controller.tick();
		controller.stop().get(5, TimeUnit.SECONDS);
		try (ProfileStateRepository reloaded = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			assertEquals(300, reloaded.load().state.goals.values().iterator().next().current.live);
		}
	}

	private GoalSpec countGoal()
	{
		return new GoalSpec("Vorkath", null, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN, 10, 0, GoalSpec.Cadence.WEEKLY,
				LocalDate.parse("2026-10-16"), null, ZoneId.of("America/Chicago"));
	}

	@Test
	public void lateHiscoresCannotMutateAnotherCharacterOrLoseLiveCredit() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 100.").get(0));
			session.key = "b".repeat(64);
			controller.tick();
			session.counts.complete(Map.of("VORKATH", 105L));
			pumpUntil(() -> view().status.contains("two-tick"));
			controller.tick();
			controller.tick();
			pumpUntil(() -> view().editable);
			assertTrue(view().goals.isEmpty());
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository old = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
		{
			assertEquals(1, old.load().state.goals.values().iterator().next().current.progress());
		}
	}

	@Test
	public void requestBoundaryExcludesNewGoalsAndFailureKeepsLiveTracking() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			session.counts.complete(Map.of("VORKATH", 100L));
			pumpUntil(() -> view().countStatus.contains("count totals checked"));
			assertEquals("Get 1 kill to enable login recovery", view().goals.get(1).sourceStatus);
			controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 101.").get(0));
			controller.command(view().token, e ->
			{
			}); // Publish the coalesced event view.
			assertEquals(1, view().goals.get(0).progress);
			assertEquals(1, view().goals.get(1).progress);
			assertEquals(1, view().activity.get(0).amount);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void networkFailureLeavesUnrankedGoalsUsableForLiveCompletions() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			session.counts.completeExceptionally(new java.io.IOException("fixture"));
			pumpUntil(() -> view().countStatus.contains("HiScores unavailable"));
			assertTrue(view().editable);
			controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 1.").get(0));
			controller.command(view().token, e ->
			{
			});
			assertEquals(1, view().goals.get(0).progress);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void cacheMismatchIsAppliedBeforeLoginAdvancesAPendingUniqueRevision() throws Exception
	{
		ZoneId zone = ZoneId.of("America/Chicago");
		Instant prior = clock.instant().minusSeconds(7 * 86400);
		GoalEngine saved = new GoalEngine(new ProfileState(session.key), zone);
		saved.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, prior);
		String id = saved.add(new GoalSpec("Uniques", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 10, 0,
				GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-09"), null, zone, Map.of(23956, 6L)), prior);
		saved.state().goals.get(id).pending = new GoalSpec("Uniques", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET",
				GoalSpec.Mode.GAIN, 10, 0,
				GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-09"), null, zone, Map.of(23956, 4L));
		saved.state().goals.get(id).pendingEffectiveDate = "2026-10-16";
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			assertTrue(repository.load().writable);
			saved.state().saveSequence = 1;
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(saved.state()));
		}
		session.unavailable = Set.of("THE_CORRUPTED_GAUNTLET");
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			assertEquals("ACTIVE", view().goals.get(0).lifecycle);
			assertEquals(Map.of(23956, 4L), view().goals.get(0).spec.uniqueTargets);
			assertTrue(view().unavailableUniques.contains("THE_CORRUPTED_GAUNTLET"));
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void idleViewsAreSuppressedAndWaitingForGameBaselineDoesNotPollHiscores() throws Exception
	{
		MutableClock time = new MutableClock(clock.instant(), clock.getZone());
		MyGoalsController controller = new MyGoalsController(net.runelite.http.api.RuneLiteAPI.GSON, session, callbacks::add,
				() -> Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), time, views::add);
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), time.instant()));
			session.counts.complete(Map.of("VORKATH", 100L));
			pumpUntil(() -> view().countStatus.contains("count totals checked") && view().persistenceStatus.contains("Saved locally"));
			int published = views.size();
			for (int tick = 0; tick < 120; tick++)
			{
				time.now = time.now.plusSeconds(60);
				controller.tick();
				controller.command(view().token, e ->
				{
				}); // Force an emit past the real-time throttle.
			}
			assertEquals(published, views.size());
			assertEquals(1, session.lookups);
			assertEquals("Get 1 kill to enable login recovery", view().goals.get(0).sourceStatus);
			controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 101.").get(0));
			controller.command(view().token, e ->
			{
			});
			assertEquals(1, view().goals.get(0).progress);
			assertEquals("Live tracking + login recovery ready", view().goals.get(0).sourceStatus);
			assertTrue(views.size() > published);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void hiscoresTimestampChangesOnlyOnSuccessfulFetchAndRoutineStatusStaysOutOfHeading() throws Exception
	{
		MutableClock time = new MutableClock(clock.instant(), clock.getZone());
		MyGoalsController controller = new MyGoalsController(net.runelite.http.api.RuneLiteAPI.GSON, session, callbacks::add,
				() -> Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), time, views::add);
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), time.instant()));
			session.counts.complete(Map.of("VORKATH", 100L));
			pumpUntil(() -> view().lastHiScoresCheckedAt == time.millis());
			assertFalse(view().status.contains("Saved locally"));
			assertFalse(view().status.contains("HiScores"));
			long checkedAt = view().lastHiScoresCheckedAt;
			time.now = time.now.plusSeconds(10);
			controller.checkCounts();
			assertEquals(1, session.lookups);
			assertEquals(checkedAt, view().lastHiScoresCheckedAt);
			time.now = time.now.plusSeconds(30);
			session.counts = new CompletableFuture<>();
			controller.checkCounts();
			session.counts.completeExceptionally(new java.io.IOException("offline"));
			pumpUntil(() -> view().countStatus.startsWith("HiScores unavailable"));
			assertEquals(2, session.lookups);
			assertEquals(checkedAt, view().lastHiScoresCheckedAt);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			assertEquals(clock.millis(), repository.load().state.lastHiScoresCheckedAt);
		}
	}

	@Test
	public void completionClaimsSaveBeforePresentationAndDoNotReplayAfterRestart() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			session.xp = 101_000;
			controller.xpChanged("MINING", session.xp);
			assertTrue(controller.pendingCelebrations().isEmpty()); // I/O has not acknowledged the occurrence yet.
			pumpUntil(() -> !controller.pendingCelebrations().isEmpty());
			String id = controller.pendingCelebrations().get(0).id;
			controller.settleCelebrations(List.of(id), false);
			assertFalse(controller.announcedCelebrations(List.of(id)));
			pumpUntil(() -> controller.announcedCelebrations(List.of(id)));
			session.loading = true;
			assertNotNull(controller.celebrationToken());
			session.loading = false;
			assertTrue(controller.pendingCelebrations().isEmpty());
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		views.clear();
		callbacks.clear();
		MyGoalsController restarted = controller();
		try
		{
			initialize(restarted);
			assertTrue(restarted.pendingCelebrations().isEmpty());
		}
		finally
		{
			restarted.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void pendingCompletionAndRecoveredXpStayAccountScopedAcrossRestart() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			session.xp = 100_500;
			controller.xpChanged("MINING", session.xp);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		views.clear();
		callbacks.clear();
		session.xp = 101_000;
		MyGoalsController restarted = controller();
		try
		{
			initialize(restarted);
			pumpUntil(() -> !restarted.pendingCelebrations().isEmpty());
			assertTrue(restarted.pendingCelebrations().get(0).recovered);
			session.key = "b".repeat(64);
			assertNull(restarted.celebrationToken());
			assertTrue(restarted.pendingCelebrations().isEmpty());
		}
		finally
		{
			restarted.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void unannouncedCompletionSurvivesRestartWithoutRegeneration() throws Exception
	{
		String occurrence;
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			session.xp = 101_000;
			controller.xpChanged("MINING", session.xp);
			pumpUntil(() -> !controller.pendingCelebrations().isEmpty());
			occurrence = controller.pendingCelebrations().get(0).id;
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		views.clear();
		callbacks.clear();
		MyGoalsController restarted = controller();
		try
		{
			initialize(restarted);
			assertEquals(1, restarted.pendingCelebrations().size());
			assertEquals(occurrence, restarted.pendingCelebrations().get(0).id);
		}
		finally
		{
			restarted.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void qualifyingLootBurstSavesOneBatchAndShutdownFlushesTheLatestProgress() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			java.util.concurrent.atomic.AtomicReference<GoalEngine> engine = new java.util.concurrent.atomic.AtomicReference<>();
			controller.command(view().token, e ->
			{
				engine.set(e);
				e.add(goal(), clock.instant());
			});
			pumpUntil(() -> view().persistenceStatus.contains("Saved locally"));
			long sequence = engine.get().state().saveSequence;
			for (int i = 0; i < 20; i++)
			{
				String receipt = "burst-" + i;
				controller.collectionEvent(controller.collectionToken(),
						e -> e.acquire(receipt, Map.of(12934, 100L), "Drop", clock.instant()));
			}
			assertEquals("Events do not serialize a snapshot immediately", sequence, engine.get().state().saveSequence);
			pumpUntil(() -> engine.get().state().saveSequence > sequence && view().persistenceStatus.contains("Saved locally"));
			assertEquals(sequence + 1, engine.get().state().saveSequence);
			session.xp += 37;
			controller.xpChanged("MINING", session.xp);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			var state = repository.load().state;
			assertEquals(2000, state.itemActivity.values().iterator().next().amount);
			assertEquals(37, state.goals.values().iterator().next().current.progress());
		}
	}

	@Test
	public void hopsReuseCachedTotalsAndPreserveTheAccountEndpointCooldown() throws Exception
	{
		MutableClock time = new MutableClock(clock.instant(), clock.getZone());
		session.counts.complete(Map.of("VORKATH", 100L));
		MyGoalsController controller = new MyGoalsController(net.runelite.http.api.RuneLiteAPI.GSON, session, callbacks::add,
				() -> Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), time, views::add);
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), time.instant()));
			pumpUntil(() -> view().countStatus.contains("count totals checked"));
			assertEquals(1, session.lookups);
			for (int i = 1; i <= 3; i++)
			{
				time.now = clock.instant().plusSeconds(5L * i);
				controller.sessionChanged();
				initialize(controller);
				assertEquals(1, session.lookups);
				assertTrue(view().countStatus.contains("count totals checked"));
			}
			time.now = clock.instant().plusSeconds(31);
			controller.sessionChanged();
			initialize(controller);
			pumpUntil(() -> session.lookups == 2 && view().countStatus.contains("count totals checked"));
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aPendingLookupSurvivesAHopAndSceneLoadingWithoutDuplicateRequests() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			controller.sessionChanged();
			initialize(controller);
			assertEquals(1, session.lookups);
			session.loading = true;
			session.counts.complete(Map.of("VORKATH", 100L));
			pumpUntil(() -> view().status.contains("Loading game scene"));
			session.loading = false;
			controller.tick();
			controller.command(view().token, e ->
			{
			});
			assertEquals(1, session.lookups);
			assertTrue(view().countStatus.contains("count totals checked"));
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void disabledCompletionBacklogIsSettledInOneSaveBatch() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			java.util.concurrent.atomic.AtomicReference<GoalEngine> engine = new java.util.concurrent.atomic.AtomicReference<>();
			controller.command(view().token, e ->
			{
				engine.set(e);
				for (int i = 0; i < 20; i++)
				{
					e.add(goal(), clock.instant());
				}
			});
			session.xp += 1000;
			controller.xpChanged("MINING", session.xp);
			pumpUntil(() -> controller.pendingCelebrations().size() == 20);
			long before = engine.get().state().saveSequence;
			var coordinator = new com.mygoals.celebration.CompletionCoordinator(controller, () -> false, () -> false);
			coordinator.update(0, false, true);
			assertTrue(engine.get().state().celebrations.values().stream().allMatch(e -> e.status.equals("SUPPRESSED")));
			assertEquals(before, engine.get().state().saveSequence);
			pumpUntil(() -> engine.get().state().saveSequence > before && view().persistenceStatus.contains("Saved locally"));
			assertEquals(before + 1, engine.get().state().saveSequence);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	private com.mygoals.ui.BackupResult backupReply(Consumer<java.util.function.Consumer<com.mygoals.ui.BackupResult>> start)
			throws Exception
	{
		var reply = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
		start.accept(reply::set);
		pumpUntil(() -> reply.get() != null);
		assertEquals(reply.get().error, "", reply.get().error);
		return reply.get();
	}

	private Filepath exported(MyGoalsController controller) throws Exception
	{
		Filepath file = Filepath.Unchecked.getRooted(temporary.getRoot().toPath().resolve("backup.zip"));
		backupReply(done -> controller.exportBackup(view().token, file, done));
		return file;
	}

	private void awaitSaved(MyGoalsController controller) throws Exception
	{
		var engine = MyGoalsController.class.getDeclaredField("engine");
		engine.setAccessible(true);
		var saved = MyGoalsController.class.getDeclaredField("savedVersion");
		saved.setAccessible(true);
		long version = ((GoalEngine) engine.get(controller)).version();
		pumpUntil(() ->
		{
			try
			{
				return saved.getLong(controller) >= version;
			}
			catch (IllegalAccessException ex)
			{
				throw new AssertionError(ex);
			}
		});
	}

	private void extraGoal(MyGoalsController controller) throws Exception
	{
		controller.command(view().token, e -> e.add(new GoalSpec("Newer goal", null, "MINING", GoalSpec.Mode.GAIN, 5000, 0,
				GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, clock.getZone()), clock.instant()));
		pumpUntil(() -> view().persistenceStatus.contains("Saved locally"));
	}

	private final class IoHold implements AutoCloseable
	{
		final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

		IoHold(MyGoalsController controller) throws Exception
		{
			this(controller, false);
		}

		IoHold(MyGoalsController controller, boolean transfer) throws Exception
		{
			var field = MyGoalsController.class.getDeclaredField(transfer ? "backups" : "io");
			field.setAccessible(true);
			Object worker = field.get(controller);
			if (transfer)
			{
				var queue = BackupOperations.class.getDeclaredField("transfers");
				queue.setAccessible(true);
				worker = queue.get(worker);
			}
			var io = (java.util.concurrent.ExecutorService) worker;
			var started = new java.util.concurrent.CountDownLatch(1);
			io.execute(() ->
			{
				started.countDown();
				try
				{
					release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex)
				{
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
		}

		public void close()
		{
			release.countDown();
		}
	}

	@Test
	public void exportAndValidationNeverBlockTheAutosaveWorker() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			awaitSaved(controller);
			Filepath source = exported(controller);
			for (boolean prepare : new boolean[] { false, true })
			{
				var reply = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
				try (IoHold hold = new IoHold(controller, true))
				{
					if (prepare)
					{
						controller.prepareRestore(view().token, source, reply::set);
					}
					else
					{
						controller.exportBackup(view().token,
								Filepath.Unchecked.getRooted(temporary.getRoot().toPath().resolve("waiting.zip")), reply::set);
					}
					session.xp += 200;
					controller.xpChanged("MINING", session.xp);
					controller.command(view().token, e ->
					{
					});
					awaitSaved(controller);
					assertNull("Transfer is still held", reply.get());
					var file = temporary.getRoot().toPath().resolve(session.key).resolve("standard/state.json");
					var saved = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).decode(java.nio.file.Files.readString(file),
							session.key);
					assertEquals(prepare ? 400 : 200, saved.goals.values().iterator().next().current.progress());
				}
				pumpUntil(() -> reply.get() != null);
				assertEquals("", reply.get().error);
				if (prepare)
				{
					controller.cancelRestore(view().token, reply.get().restoreId);
				}
			}
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void finishedGoalBodiesArePagedAndDeletingOnePersistsThePurge() throws Exception
	{
		LocalDate start = LocalDate.parse("2016-01-01");
		Instant then = start.atTime(12, 0).atZone(clock.getZone()).toInstant();
		GoalEngine initial = new GoalEngine(new ProfileState(session.key), clock.getZone());
		initial.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, then);
		for (int i = 0; i < 101; i++)
		{
			initial.add(new GoalSpec("Finished " + i, null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE, start,
					start.plusDays(2), clock.getZone()), then);
		}
		initial.observe(Map.of("MINING", 1200L), GoalEngine.LIVE, then.plusSeconds(1));
		initial.settleCelebrations(new ArrayList<>(initial.state().celebrations.keySet()), true);
		initial.state().saveSequence = 1;
		try (var repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(initial.state()));
		}
		MyGoalsController controller = controller();
		String deleted;
		try
		{
			initialize(controller);
			assertTrue(view().goals.isEmpty());
			assertTrue(view().hasOlderFinished);
			controller.finished(view().token, 0);
			pumpUntil(() -> view().goals.size() == 50);
			assertEquals(0, view().finishedPage);
			assertTrue(view().hasOlderFinished);
			controller.finished(view().token, 1);
			pumpUntil(() -> view().finishedPage == 1);
			assertEquals(50, view().goals.size());
			assertTrue(view().hasNewerFinished);
			assertTrue(view().hasOlderFinished);
			controller.finished(view().token, 2);
			pumpUntil(() -> view().finishedPage == 2);
			assertEquals(1, view().goals.size());
			assertFalse(view().hasOlderFinished);
			var goal = view().goals.get(0);
			assertEquals(200, goal.progress);
			deleted = goal.id;
			var history = new java.util.concurrent.atomic.AtomicReference<GoalsView.HistoryPage>();
			controller.dailyHistory(view().token, goal.id, start.toString(), start.plusDays(1).toString(), history::set);
			pumpUntil(() -> history.get() != null);
			assertEquals(1, history.get().days.size());
			controller.command(view().token, e -> e.remove(goal.id));
			assertTrue(view().goals.isEmpty());
			var premature=new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
			controller.exportBackup(view().token,Filepath.Unchecked.getRooted(temporary.getRoot().toPath().resolve("too-early.zip")),premature::set);
			assertTrue(premature.get().error.contains("deletion"));
			awaitSaved(controller);
			exported(controller);
			controller.finished(view().token, 0);
			pumpUntil(() -> view().finishedPage == 0 && view().goals.size() == 50);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (var repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			var saved = repository.load().state;
			assertEquals(100, saved.finished.size());
			assertFalse(saved.finished.containsKey(deleted));
			assertTrue(repository.dailyHistory(deleted, start, start.plusDays(1)).isEmpty());
		}
	}

	@Test
	public void restoreReplacesGoalsAndReplaysXpCountsAndDropsObservedDuringTheTransfer() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 100.").get(0));
			Filepath backup = exported(controller);
			extraGoal(controller);
			var preview = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			var restored = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
			String oldToken = view().token;
			try (IoHold hold = new IoHold(controller))
			{
				controller.restoreBackup(oldToken, preview.restoreId, restored::set);
				assertFalse(view().editable);
				assertNull(controller.celebrationToken());
				session.xp += 250;
				controller.xpChanged("MINING", session.xp);
				controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 101.").get(0));
				controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 102.").get(0));
				controller.collectionEvent(controller.collectionToken(),
						e -> e.acquire("during-restore", Map.of(12934, 5L), "Drop", clock.instant()));
				controller.command(oldToken, e -> e.add(goal(), clock.instant()));
			}
			pumpUntil(() -> restored.get() != null);
			assertEquals("", restored.get().error);
			assertTrue(view().editable);
			assertEquals(2, view().goals.size());
			assertEquals(250, view().goals.get(0).progress);
			assertEquals(3, view().goals.get(1).progress);
			assertEquals(Long.valueOf(102), view().goals.get(1).totalCount);
			assertEquals(5, view().itemActivity.get(0).amount);
			assertNotEquals(oldToken, view().token);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), session.key))
		{
			ProfileState state = repository.load().state;
			assertEquals(3, state.goals.size());
			assertEquals(250, state.goals.values().iterator().next().current.progress());
			assertEquals(5, state.itemActivity.values().iterator().next().amount);
		}
	}

	@Test
	public void logoutBeforeRestoreCommitKeepsTheOldGoalsAndBufferedProgressInTheirAccount() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			Filepath backup = exported(controller);
			extraGoal(controller);
			var preview = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			try (IoHold hold = new IoHold(controller))
			{
				controller.restoreBackup(view().token, preview.restoreId, reply ->
				{
				});
				session.xp += 50;
				controller.xpChanged("MINING", session.xp);
				session.key = "b".repeat(64);
				controller.sessionChanged();
			}
			initialize(controller);
			assertTrue(view().goals.isEmpty());
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
		{
			ProfileState state = repository.load().state;
			assertEquals(2, state.goals.size());
			assertEquals(50, state.goals.values().iterator().next().current.progress());
		}
	}

	@Test
	public void logoutAfterCommitBeforeItsCallbackPreservesRestoredStateAndTheBufferedTail() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			pumpUntil(() -> view().persistenceStatus.contains("Saved locally"));
			Filepath backup = exported(controller);
			extraGoal(controller);
			var preview = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			controller.restoreBackup(view().token, preview.restoreId, reply -> fail("A stale callback cannot reach the new account"));
			Runnable late = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(late);
			session.xp += 75;
			controller.xpChanged("MINING", session.xp);
			session.key = "b".repeat(64);
			controller.sessionChanged();
			late.run();
			initialize(controller);
			assertTrue(view().goals.isEmpty());
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
		{
			ProfileState state = repository.load().state;
			assertEquals(1, state.goals.size());
			assertEquals(75, state.goals.values().iterator().next().current.progress());
		}
	}

	@Test
	public void cancelPreviewAndStopWithAnUndeliveredPreviewLeaveNoStagedRestore() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			Filepath backup = exported(controller);
			var preview = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			controller.cancelRestore(view().token, preview.restoreId);
			var second = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			controller.cancelRestore(view().token, second.restoreId);
			controller.prepareRestore(view().token, backup, reply -> fail("Closed client must not receive this preview"));
			Runnable late = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(late);
			controller.stop().get(5, TimeUnit.SECONDS);
			late.run();
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		var staging = temporary.getRoot().toPath().resolve(session.key).resolve("standard/transfers");
		try (var files = java.nio.file.Files.list(staging))
		{
			assertEquals(0, files.count());
		}
	}

	@Test
	public void failureToPreserveTheOldDataCancelsRestoreAndKeepsNewLiveProgress() throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			Filepath backup = exported(controller);
			extraGoal(controller);
			var preview = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			java.nio.file.Files.writeString(temporary.getRoot().toPath().resolve(session.key).resolve("standard/backups"),
					"simulated filesystem obstruction");
			var reply = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
			try (IoHold hold = new IoHold(controller))
			{
				controller.restoreBackup(view().token, preview.restoreId, reply::set);
				session.xp += 35;
				controller.xpChanged("MINING", session.xp);
			}
			pumpUntil(() -> reply.get() != null);
			assertFalse(reply.get().error.isEmpty());
			assertEquals(2, view().goals.size());
			assertEquals(35, view().goals.get(0).progress);
			assertTrue(view().editable);
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void restoreOverflowCancelsWithBoundedMemoryAndKeepsEveryObservation() throws Exception
	{
		restoreOverflow(false, false);
	}

	@Test
	public void restoreOverflowAfterCommitSupersedesTheImportWithoutLosingProgress() throws Exception
	{
		restoreOverflow(true, false);
	}

	@Test
	public void logoutDuringRestoreOverflowKeepsTheOriginalAccountProgress() throws Exception
	{
		restoreOverflow(false, true);
	}

	private void restoreOverflow(boolean afterCommit, boolean logout) throws Exception
	{
		MyGoalsController controller = controller();
		try
		{
			initialize(controller);
			controller.command(view().token, e -> e.add(goal(), clock.instant()));
			controller.command(view().token, e -> e.add(countGoal(), clock.instant()));
			controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 100.").get(0));
			Filepath backup = exported(controller);
			extraGoal(controller);
			var preview = backupReply(done -> controller.prepareRestore(view().token, backup, done));
			var reply = new java.util.concurrent.atomic.AtomicReference<com.mygoals.ui.BackupResult>();
			Runnable late = null;
			try (IoHold hold = new IoHold(controller))
			{
				controller.restoreBackup(view().token, preview.restoreId, reply::set);
				if (afterCommit)
				{
					hold.close();
					late = callbacks.poll(5, TimeUnit.SECONDS);
					assertNotNull(late);
				}
				for (int i = 0; i < 1600; i++)
				{
					controller.xpChanged("MINING", ++session.xp);
				}
				controller.countChanged(CountMessageParser.parse("Your Vorkath kill count is: 101.").get(0));
				controller.collectionEvent(controller.collectionToken(),
						e -> e.acquire("overflow-drop", Map.of(12934, 5L), "Drop", clock.instant()));
				var field = MyGoalsController.class.getDeclaredField("restoring");
				field.setAccessible(true);
				RestoreOperation operation = (RestoreOperation) field.get(controller);
				assertTrue(operation.cancelled.get());
				var events = RestoreOperation.class.getDeclaredField("events");
				events.setAccessible(true);
				assertTrue(((List<?>) events.get(operation)).size() <= RestoreOperation.MAX_BUFFERED_EVENTS);
				assertEquals(0, operation.previous.itemActivity.size()); // Worker snapshot stays immutable.
				if (logout)
				{
					session.key = "b".repeat(64);
					controller.sessionChanged();
				}
			}
			if (late != null)
			{
				late.run();
			}
			if (logout)
			{
				initialize(controller);
				assertTrue(view().goals.isEmpty());
			}
			else
			{
				pumpUntil(() -> reply.get() != null);
				assertFalse(reply.get().error.isEmpty());
				assertEquals(3, view().goals.size());
				assertEquals(1600, view().goals.get(0).progress);
				assertEquals(2, view().goals.get(1).progress);
				assertEquals(5, view().itemActivity.get(0).amount);
			}
		}
		finally
		{
			controller.stop().get(5, TimeUnit.SECONDS);
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON,
				Filepath.Unchecked.getRooted(temporary.getRoot().toPath()), "a".repeat(64)))
		{
			ProfileState saved = repository.load().state;
			assertEquals(3, saved.goals.size());
			assertEquals(1600, saved.goals.values().iterator().next().current.progress());
			assertEquals(5, saved.itemActivity.values().iterator().next().amount);
		}
	}

	private static final class MutableClock extends Clock
	{
		Instant now;
		private final ZoneId zone;

		MutableClock(Instant now, ZoneId zone)
		{
			this.now = now;
			this.zone = zone;
		}

		@Override
		public Instant instant()
		{
			return now;
		}

		@Override
		public ZoneId getZone()
		{
			return zone;
		}

		@Override
		public Clock withZone(ZoneId next)
		{
			return new MutableClock(now, next);
		}
	}

	private static final class FakeSession implements MyGoalsController.Session
	{
		String key = "a".repeat(64);
		long xp = 100_000;
		boolean loading;
		Set<String> unavailable = Set.of();
		int lookups;
		CompletableFuture<Map<String, Long>> counts = new CompletableFuture<>();

		public String readyKey()
		{
			return loading ? null : key;
		}

		public boolean loggedIn()
		{
			return !loading;
		}

		public boolean loading()
		{
			return loading;
		}

		public Map<String, Long> xpSnapshot()
		{
			return Map.of("MINING", xp);
		}

		public CompletableFuture<Map<String, Long>> lookupCounts()
		{
			lookups++;
			return counts;
		}

		public Set<String> incompatibleUniqueMetrics()
		{
			return unavailable;
		}
	}
}
