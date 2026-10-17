package com.mygoals;

import com.google.gson.Gson;
import com.mygoals.celebration.CompletionCoordinator;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.persistence.BackupArchive;
import com.mygoals.persistence.HistoryArchive;
import com.mygoals.persistence.ProfileSaveQueue;
import com.mygoals.persistence.ProfileStateRepository;
import com.mygoals.persistence.StateSnapshot;
import com.mygoals.tracking.CountMessageParser;
import com.mygoals.ui.BackupResult;
import com.mygoals.ui.GoalsView;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.runelite.client.util.Filepath;

/**
 * One controller per enabled plugin lifetime. All mutable state belongs to the
 * client thread.
 */
final class MyGoalsController implements CompletionCoordinator.Store
{
	interface Directory
	{
		Filepath get() throws IOException;
	}

	interface Dispatcher
	{
		void invoke(Runnable action);
	}

	interface Session
	{
		String readyKey();

		boolean loggedIn();

		boolean loading();

		Map<String, Long> xpSnapshot();

		default String displayName()
		{
			return "Character";
		}

		default CompletableFuture<Map<String, Long>> lookupCounts()
		{
			return CompletableFuture.completedFuture(Map.of());
		}

		default String countLookupKey()
		{
			return readyKey();
		}

		default String backupIdentity()
		{
			return readyKey();
		}

		default Set<String> incompatibleUniqueMetrics()
		{
			return Set.of();
		}
	}

	private final Session session;
	private final Gson gson;
	private final BackupOperations backups;
	private final Dispatcher clientThread;
	private final Directory directory;
	private final Clock clock;
	private final Consumer<GoalsView> publish;
	private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(task ->
	{
		Thread thread = new Thread(task, "my-goals-local-save");
		thread.setDaemon(true);
		return thread;
	});
	private final String lifetime = UUID.randomUUID().toString();
	private String key, message = "", saveStatus = "Waiting for login";
	private long generation, savedVersion = -1, requestedVersion = -1, lastSaveNanos, lastPublish;
	private int stableTicks;
	private boolean running = true, initialized;
	private GoalEngine engine;
	private long requestedCompletionVersion;
	private final Map<String, String> durableCelebrations = new LinkedHashMap<>();
	private long durableCompletionVersion, pendingCacheVersion = -1, pendingCacheDurable = -1;
	private List<CompletionCoordinator.Notice> pendingCache = List.of();
	private GoalsView lastView;
	private ProfileStateRepository repository;
	private ProfileSaveQueue saves;
	private ScheduledFuture<?> scheduledSave;
	private final Map<String, ProfileState> pendingHistory = new LinkedHashMap<>();
	private final HistoryOperations histories;
	private long transferEpoch;
	private long pendingDeletionVersion = -1;
	private final List<AutoCloseable> restoreLeases = new java.util.ArrayList<>();
	private LocalDate historyDay;
	private long lastViewVersion = -1;
	private String lastViewDay, lastViewStatus, lastViewMessage, lastViewToken;
	private boolean lastViewEditable;
	private Set<String> lastViewUnavailable = Set.of();
	private final Set<ProfileStateRepository> opened = ConcurrentHashMap.newKeySet();
	private CompletableFuture<Void> shutdown;
	private final CountLookupService counts;
	private boolean transferBusy;
	private BackupArchive.Prepared preparedRestore;
	private RestoreOperation restoring;
	Runnable beforeDetach = () ->
	{
	};
	Runnable beforeRestore = () ->
	{
	};
	Consumer<String> afterRestore = ignored ->
	{
	};

	MyGoalsController(Gson clientGson, Session session, Dispatcher clientThread, Directory directory, Clock clock,
			Consumer<GoalsView> publish)
	{
		gson = clientGson;
		backups = new BackupOperations(clientGson, io, clientThread);
		histories = new HistoryOperations(io, clientThread, error ->
		{
			message = error;
			emit(true);
		});
		counts = new CountLookupService(session, clientThread, clock, () -> running && restoring == null ? engine : null,
				() -> initialized && java.util.Objects.equals(key, readyKey()), this::scheduleSave, () -> emit(true));
		this.session = session;
		this.clientThread = clientThread;
		this.directory = directory;
		this.clock = clock;
		this.publish = publish;
	}

	void tick()
	{
		if (!running)
		{
			return;
		}
		String readyKey = readyKey();
		if (readyKey == null)
		{
			if (key != null && !session.loading())
			{
				detach();
			}
			emit(true);
			return;
		}
		if (!readyKey.equals(key))
		{
			detach();
			load(readyKey);
		}
		if (restoring != null)
		{
			emit(false);
			return;
		}
		if (engine != null)
		{
			if (!initialized)
			{
				if (++stableTicks >= 2)
				{
					engine.setUnavailableUniques(session.incompatibleUniqueMetrics());
					engine.observe(xpSnapshot(), GoalEngine.RECOVERED, clock.instant());
					initialized = true;
					scheduleSave();
					counts.request(GoalEngine.RECOVERED, false);
					emit(true); // Baseline readiness must publish even when loading an unchanged save.
				}
			}
			else
			{
				engine.observe(xpSnapshot(), GoalEngine.LIVE, clock.instant());
				if (System.nanoTime() - lastSaveNanos >= 60_000_000_000L)
				{
					scheduleSave();
				}
				counts.requestNewGoals();
			}
		}
		saveNewCompletions();
		emit(false);
	}

	void xpChanged(String skill, long xp)
	{
		if (!running || !initialized || engine == null || "OVERALL".equals(skill) || !java.util.Objects.equals(key, readyKey()))
		{
			return;
		}
		java.time.Instant now = clock.instant();
		if (restoring != null)
		{
			buffer(e -> e.observe(java.util.Collections.singletonMap(skill, xp), GoalEngine.LIVE, now));
			return;
		}
		engine.observe(java.util.Collections.singletonMap(skill, xp), GoalEngine.LIVE, now);
		saveNewCompletions();
		emit(false);
	}

	void sessionChanged()
	{
		if (!running)
		{
			return;
		}
		detach();
		emit(true);
	}

	void countChanged(CountMessageParser.Observation observation)
	{
		if (!running || !initialized || engine == null || !java.util.Objects.equals(key, readyKey()))
		{
			return;
		}
		java.time.Instant now = clock.instant();
		if (restoring != null)
		{
			buffer(e -> e.observeCount(observation.metricId, observation.total, observation.completion, GoalEngine.LIVE, null, now));
			return;
		}
		engine.observeCount(observation.metricId, observation.total, observation.completion, GoalEngine.LIVE, null, now);
		saveNewCompletions();
		emit(false);
	}

	String collectionToken()
	{
		if (restoring != null)
		{
			return restoring.collectionToken;
		}
		return running && initialized && engine != null && java.util.Objects.equals(key, readyKey()) ? token() : null;
	}

	void collectionEvent(String capturedToken, Consumer<GoalEngine> action)
	{
		String expectedToken = restoring == null ? token() : restoring.collectionToken;
		if (!running || !initialized || engine == null || !expectedToken.equals(capturedToken))
		{
			return;
		}
		if (buffer(action))
		{
			return;
		}
		action.accept(engine);
		scheduleSave();
		emit(false);
	}

	void checkCounts()
	{
		counts.request("HiScores refreshed", true);
		emit(true);
	}

	void command(String token, Consumer<GoalEngine> command)
	{
		if (!running)
		{
			return;
		}
		if (!token().equals(token) || restoring != null || !initialized || engine == null || !java.util.Objects.equals(key, readyKey()))
		{
			message = "The active character changed or is still loading. Reopen the editor after login.";
			emit(true);
			return;
		}
		try
		{
			int deletions = engine.state().deletedGoals.size();
			command.accept(engine);
			if (engine.state().deletedGoals.size() != deletions)
			{
				pendingDeletionVersion = engine.version();
				transferEpoch++;
				backups.cancelTransfers();
				discardPrepared();
				transferBusy = false;
			}
			histories.prune();
			message = "";
			scheduleSave();
			counts.requestNewGoals();
		}
		catch (IllegalArgumentException ex)
		{
			message = ex.getMessage();
		}
		emit(true);
	}

	void retry()
	{
		if (!running)
		{
			return;
		}
		if (engine != null)
		{
			scheduleSave();
			emit(true);
			return;
		}
		detach();
		tick();
	}

	private void load(String newKey)
	{
		key = newKey;
		long token = generation;
		saveStatus = "Loading local goals…";
		message = "";
		io.execute(() ->
		{
			ProfileStateRepository loadedRepository = null;
			try
			{
				loadedRepository = new ProfileStateRepository(gson, directory.get(), newKey);
				opened.add(loadedRepository);
				ProfileStateRepository.Loaded loaded = loadedRepository.loadAndArchive(clock.instant());
				ProfileStateRepository capturedRepository = loadedRepository;
				clientThread.invoke(() ->
				{
					boolean sameLoadingSession = session.loading() && newKey.equals(key);
					if (!running || token != generation || (!newKey.equals(readyKey()) && !sameLoadingSession))
					{
						if (running)
						{
							io.execute(() -> close(capturedRepository));
						}
						return;
					}
					repository = capturedRepository;
					saves = new ProfileSaveQueue(io, capturedRepository,
							(request, failure) -> clientThread.invoke(() -> saved(token, request, failure)));
					historyDay = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate();
					saveStatus = loaded.notice;
					if (loaded.notice.startsWith("Recovered"))
					{
						message = loaded.notice;
					}
					if (loaded.state != null && loaded.writable)
					{
						engine = new GoalEngine(loaded.state, ZoneId.systemDefault());
						histories.attach(token(), loaded.state, repository);
						savedVersion = engine.version();
						loaded.state.celebrations.forEach((id, event) -> durableCelebrations.put(id, event.status));
						durableCompletionVersion++;
					}
					else
					{
						message = loaded.notice;
					}
					emit(true);
				});
			}
			catch (Exception ex)
			{
				close(loadedRepository);
				clientThread.invoke(() ->
				{
					if (running && token == generation)
					{
						saveStatus = "Local save unavailable";
						message = "Could not load goals (" + ex.getClass().getSimpleName()
								+ "). Existing files were preserved. Retry loading.";
						emit(true);
					}
				});
			}
		});
	}

	private void scheduleSave()
	{
		if (restoring != null || engine == null || repository == null || !initialized)
		{
			return;
		}
		long version = engine.version();
		if (version <= savedVersion || version == requestedVersion)
		{
			return;
		}
		requestedCompletionVersion = engine.completionVersion();
		// The first request starts a fixed batching window; more drops do not postpone
		// it.
		if (scheduledSave == null)
		{
			long token = generation;
			scheduledSave = io.schedule(() -> clientThread.invoke(() ->
			{
				if (!running || token != generation)
				{
					return;
				}
				scheduledSave = null;
				flushSave();
				emit(true);
			}), 500, TimeUnit.MILLISECONDS);
		}
	}

	private void flushSave()
	{
		if (restoring != null || engine == null || repository == null || !initialized || saves == null)
		{
			return;
		}
		long version = engine.version();
		if (version <= savedVersion || version == requestedVersion)
		{
			return;
		}
		LocalDate today = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate();
		if (!today.equals(historyDay))
		{
			pendingHistory.putAll(HistoryArchive.detach(engine.state(), today));
			historyDay = today;
		}
		ProfileState state = engine.state();
		state.saveSequence = Math.addExact(state.saveSequence, 1);
		state.savedAt = clock.millis();
		requestedVersion = version;
		requestedCompletionVersion = engine.completionVersion();
		lastSaveNanos = System.nanoTime();
		saveStatus = "Saving locally…";
		saves.offer(new ProfileSaveQueue.Request(StateSnapshot.copy(state), version, pendingHistory));
	}

	private void saved(long token, ProfileSaveQueue.Request request, Exception failure)
	{
		if (!running || token != generation || restoring != null)
		{
			return;
		}
		if (failure == null)
		{
			var removed = new java.util.HashSet<>(request.originalHistory);
			removed.removeAll(request.state.historyFiles);
			engine.state().historyFiles.removeAll(removed);
			if (!restoreLeases.isEmpty())
			{
				List<AutoCloseable> leases = List.copyOf(restoreLeases);
				restoreLeases.clear();
				ProfileStateRepository target = repository;
				io.execute(() ->
				{
					leases.forEach(MyGoalsController::closeLease);
					try
					{
						target.cleanup();
					}
					catch (IOException ignored)
					{
					}
				});
			}
			savedVersion = Math.max(savedVersion, request.version);
			pendingHistory.keySet().removeAll(request.fragments.keySet());
			durableCelebrations.clear();
			request.state.celebrations.forEach((id, event) -> durableCelebrations.put(id, event.status));
			durableCompletionVersion++;
			if (requestedVersion == request.version)
			{
				saveStatus = "Saved locally";
			}
		}
		else if (requestedVersion == request.version)
		{
			requestedVersion = -1;
			saveStatus = "Save failed — retry pending";
			message = "Changes remain in memory. Local save failed (" + failure.getClass().getSimpleName() + ").";
		}
		emit(true);
	}

	private void detach()
	{
		transferEpoch++;
		beforeDetach.run();
		if (scheduledSave != null)
		{
			scheduledSave.cancel(false);
			scheduledSave = null;
		}
		if (restoring == null)
		{
			flushSave();
		}
		else
		{
			RestoreOperation operation = restoring;
			operation.cancelled.set(true);
			long now = clock.millis();
			io.execute(() ->
			{
				try
				{
					GoalEngine finalEngine = operation.reconcile();
					operation.repository.saveReconciled(finalEngine.state(), operation.keptPrevious() ? operation.fragments : Map.of(),
							now);
				}
				catch (Exception ignored)
				{
					// The pre-restore copy and normal atomic backup remain available.
				}
				finally
				{
					closeLease(operation.lease);
				}
			});
			restoring = null;
		}
		discardPrepared();
		transferBusy = false;
		// Also release a load that finished on I/O but whose client callback is still
		// queued.
		// The next load runs after this cleanup, so it cannot collide with our own old
		// lock.
		io.execute(() ->
		{
			for (ProfileStateRepository open : opened.toArray(new ProfileStateRepository[0]))
			{
				close(open);
			}
			backups.closeAll();
		});
		generation++;
		pendingDeletionVersion = -1;
		key = null;
		repository = null;
		saves = null;
		engine = null;
		initialized = false;
		stableTicks = 0;
		pendingHistory.clear();
		histories.reset();
		if (!restoreLeases.isEmpty())
		{
			List<AutoCloseable> leases = List.copyOf(restoreLeases);
			restoreLeases.clear();
			io.execute(() -> leases.forEach(MyGoalsController::closeLease));
		}
		historyDay = null;
		savedVersion = requestedVersion = -1;
		durableCelebrations.clear();
		requestedCompletionVersion = 0;
		durableCompletionVersion++;
		pendingCache = List.of();
		pendingCacheVersion = pendingCacheDurable = -1;
		counts.detach();
		message = "";
		saveStatus = "Waiting for character";
	}

	CompletableFuture<Void> stop()
	{
		if (shutdown != null)
		{
			return shutdown;
		}
		detach();
		running = false;
		CompletableFuture<Void> done = new CompletableFuture<>();
		CompletableFuture<Void> transferDone = backups.stop();
		shutdown = CompletableFuture.allOf(done, transferDone);
		io.execute(() ->
		{
			for (ProfileStateRepository open : opened.toArray(new ProfileStateRepository[0]))
			{
				close(open);
			}
			done.complete(null);
		});
		io.shutdown();
		return shutdown;
	}

	private void close(ProfileStateRepository repository)
	{
		if (repository == null)
		{
			return;
		}
		try
		{
			repository.close();
		}
		catch (IOException ignored)
		{
			// Channel closes with this plugin's I/O lifetime.
		}
		opened.remove(repository);
	}

	private Map<String, Long> xpSnapshot()
	{
		return session.xpSnapshot();
	}

	private String readyKey()
	{
		return session.readyKey();
	}

	private String token()
	{
		return lifetime + ":" + generation;
	}

	private void saveNewCompletions()
	{
		if (engine != null && engine.completionVersion() != requestedCompletionVersion)
		{
			scheduleSave();
		}
	}

	@Override
	public String celebrationToken()
	{
		return running && restoring == null && initialized && engine != null
				&& (java.util.Objects.equals(key, readyKey()) || session.loading()) ? token() : null;
	}

	@Override
	public List<CompletionCoordinator.Notice> pendingCelebrations()
	{
		if (celebrationToken() == null)
		{
			return List.of();
		}
		if (pendingCacheVersion == engine.completionVersion() && pendingCacheDurable == durableCompletionVersion)
		{
			return pendingCache;
		}
		pendingCacheVersion = engine.completionVersion();
		pendingCacheDurable = durableCompletionVersion;
		pendingCache = engine.state().celebrations.values().stream().filter(e -> e.status.equals("PENDING") &&
				"PENDING".equals(durableCelebrations.get(e.id))
				&& (engine.state().goals.containsKey(e.goalId) || engine.state().finished.containsKey(e.goalId)))
				.map(CompletionCoordinator.Notice::new).collect(java.util.stream.Collectors.toList());
		return pendingCache;
	}

	@Override
	public boolean validCelebration(String id)
	{
		if (celebrationToken() == null)
		{
			return false;
		}
		ProfileState.Celebration event = engine.state().celebrations.get(id);
		ProfileState.Goal goal = event == null ? null : engine.state().goals.get(event.goalId);
		if (goal == null && event != null && engine.state().finished.containsKey(event.goalId))
		{
			return event.overall && !event.status.equals("SUPPRESSED");
		}
		return goal != null && GoalEngine.supported(goal) && !event.status.equals("SUPPRESSED") && (event.overall ||
				!LocalDate.parse(event.date).isBefore(clock.instant().atZone(ZoneId.of(goal.spec.zone)).toLocalDate()));
	}

	@Override
	public long celebrationRevision()
	{
		return engine == null ? 0 : engine.version() + durableCompletionVersion;
	}

	@Override
	public void settleCelebrations(List<String> ids, boolean suppress)
	{
		if (celebrationToken() == null)
		{
			return;
		}
		engine.settleCelebrations(ids, suppress);
		scheduleSave(); // Rendering admission never builds a sidebar view; the next tick publishes it.
	}

	@Override
	public boolean announcedCelebrations(List<String> ids)
	{
		return celebrationToken() != null && ids.stream().allMatch(id -> "ANNOUNCED".equals(durableCelebrations.get(id)) &&
				validCelebration(id) && engine.state().celebrations.get(id).status.equals("ANNOUNCED"));
	}

	private void emit(boolean force)
	{
		long now = System.nanoTime();
		if (!force && now - lastPublish < 250_000_000L)
		{
			return;
		}
		lastPublish = now;
		String status, currentSaveStatus = saveStatus;
		boolean editable = running && restoring == null && initialized && engine != null && java.util.Objects.equals(key, readyKey());
		if (!session.loggedIn())
		{
			status = session.loading() ? "Loading game scene…" : "Log in to a standard world to manage goals";
		}
		else if (key == null)
		{
			status = "Waiting for a standard RuneScape profile";
		}
		else if (restoring != null)
		{
			status = "Restoring backup…";
		}
		else if (engine == null)
		{
			status = saveStatus;
		}
		else if (!initialized)
		{
			status = "Waiting for the two-tick XP baseline";
		}
		else
		{
			if (engine.version() > savedVersion && engine.version() != requestedVersion &&
					(saveStatus.equals("Saved locally") || saveStatus.equals("Loaded local save")))
			{
				currentSaveStatus = "Tracking progress • autosave pending";
			}
			status = session.displayName() + "\n" + currentSaveStatus;
			if (!counts.status().isEmpty())
			{
				status += "\n" + counts.status();
			}
		}
		long version = engine == null ? -1 : engine.version() + histories.revision();
		String dateKey = Long.toString(clock.millis() / 60_000);
		Set<String> unavailable = initialized ? session.incompatibleUniqueMetrics() : Set.of();
		String currentToken = token();
		if (lastView != null && version == lastViewVersion && dateKey.equals(lastViewDay) &&
				currentToken.equals(lastViewToken) && status.equals(lastViewStatus) && message.equals(lastViewMessage) &&
				editable == lastViewEditable && unavailable.equals(lastViewUnavailable))
		{
			return;
		}
		lastViewVersion = version;
		lastViewDay = dateKey;
		lastViewToken = currentToken;
		lastViewStatus = status;
		lastViewMessage = message;
		lastViewEditable = editable;
		lastViewUnavailable = Set.copyOf(unavailable);
		String heading = editable ? session.displayName() : status;
		if (editable && saveStatus.startsWith("Save failed"))
		{
			heading += "\n" + saveStatus;
		}
		GoalsView next = new GoalsView(currentToken, heading, message, editable, engine == null ? null : engine.state(), clock.instant(),
				unavailable, currentSaveStatus, counts.status(), histories.finishedGoals(), histories.page());
		if (next.equals(lastView))
		{
			return;
		}
		lastView = next;
		publish.accept(next);
	}

	@Override
	public void presentedCelebrations(List<String> ids)
	{
		if (celebrationToken() != null)
		{
			engine.presentedCelebrations(ids);
		}
	}

	private boolean buffer(Consumer<GoalEngine> event)
	{
		if (restoring == null)
		{
			return false;
		}
		restoring.observe(event);
		return true;
	}

	private boolean transferAllowed(String capturedToken, Consumer<BackupResult> completed)
	{
		if (!running || !token().equals(capturedToken) || restoring != null || !initialized || engine == null || repository == null
				|| !java.util.Objects.equals(key, readyKey()))
		{
			completed.accept(BackupResult.failure("Log in to the same character and reopen Advanced Goal Pacer."));
			return false;
		}
		if (transferBusy || preparedRestore != null)
		{
			completed.accept(BackupResult.failure("Finish or cancel the current backup operation first."));
			return false;
		}
		if (pendingDeletionVersion > savedVersion)
		{
			completed.accept(BackupResult.failure("Wait for the goal deletion to finish saving, then try again."));
			return false;
		}
		return true;
	}

	void exportBackup(String capturedToken, Filepath destination, Consumer<BackupResult> completed)
	{
		if (!transferAllowed(capturedToken, completed))
		{
			return;
		}
		transferBusy = true;
		ProfileState state = StateSnapshot.copy(engine.state());
		state.savedAt = clock.millis();
		Map<String, ProfileState> fragments = Map.copyOf(pendingHistory);
		ProfileStateRepository target = repository;
		String account = session.backupIdentity();
		long captured = generation, epoch = transferEpoch, now = clock.millis();
		backups.export(target, state, fragments, destination, account, now, result ->
		{
			if (running && captured == generation && epoch == transferEpoch)
			{
				transferBusy = false;
				completed.accept(result);
			}
		});
	}

	void prepareRestore(String capturedToken, Filepath source, Consumer<BackupResult> completed)
	{
		if (!transferAllowed(capturedToken, completed))
		{
			return;
		}
		transferBusy = true;
		ProfileStateRepository target = repository;
		String account = session.backupIdentity(), targetKey = key;
		long captured = generation, epoch = transferEpoch;
		backups.prepare(target, source, account, targetKey, (loaded, result) ->
		{
			if (!running || captured != generation || epoch != transferEpoch
					|| (!java.util.Objects.equals(key, readyKey()) && !session.loading()))
			{
				if (loaded != null && running)
				{
					backups.discard(loaded);
				}
				// stop() queues cleanup after preparation, including a late client callback.
				if (running && captured == generation && epoch == transferEpoch)
				{
					transferBusy = false;
					completed.accept(BackupResult.failure("The character changed. Choose the backup again after login."));
				}
				return;
			}
			transferBusy = false;
			preparedRestore = loaded;
			completed.accept(result);
		});
	}

	void cancelRestore(String capturedToken, String restoreId)
	{
		if (preparedRestore != null && preparedRestore.id.equals(restoreId))
		{
			discardPrepared();
		}
	}

	void restoreBackup(String capturedToken, String restoreId, Consumer<BackupResult> completed)
	{
		if (!running || !token().equals(capturedToken) || restoring != null || !initialized || engine == null ||
				!java.util.Objects.equals(key, readyKey()) || preparedRestore == null || !preparedRestore.id.equals(restoreId))
		{
			completed.accept(BackupResult.failure("The character or backup changed. Choose the backup again."));
			return;
		}
		beforeRestore.run();
		if (scheduledSave != null)
		{
			scheduledSave.cancel(false);
			scheduledSave = null;
		}
		flushSave();
		Map<String, Long> counts = new LinkedHashMap<>(engine.state().counts);
		engine.state().goals.values().forEach(g ->
		{
			if (g.countCheckpoint != null)
			{
				counts.merge(g.spec.metricId, g.countCheckpoint, Math::max);
			}
		});
		RestoreOperation operation = new RestoreOperation(repository, StateSnapshot.copy(engine.state()), pendingHistory,
				xpSnapshot(), counts, session.incompatibleUniqueMetrics(), token(), clock.instant());
		operation.lease = repository.retain(operation.previous);
		BackupArchive.Prepared prepared = preparedRestore;
		preparedRestore = null;
		String account = session.backupIdentity();
		long captured = generation, now = clock.millis();
		restoring = operation;
		transferBusy = true;
		emit(true);
		backups.restore(operation, prepared, account, now, error -> finishRestore(operation, captured, error, completed));
	}

	private void finishRestore(RestoreOperation operation, long captured, Exception failure, Consumer<BackupResult> completed)
	{
		if (!running || captured != generation || restoring != operation)
		{
			return;
		}
		if (!java.util.Objects.equals(key, readyKey()) && !session.loading())
		{
			detach();
			emit(true);
			return;
		}
		engine = operation.reconcile();
		pendingDeletionVersion = -1;
		restoreLeases.add(operation.lease);
		restoring = null;
		transferBusy = false;
		generation++;
		histories.attach(token(), engine.state(), repository);
		// Same account: retain native rewards still waiting for their correlation
		// window.
		afterRestore.accept(token());
		long currentGeneration = generation;
		saves = new ProfileSaveQueue(io, repository,
				(request, error) -> clientThread.invoke(() -> saved(currentGeneration, request, error)));
		savedVersion = requestedVersion = -1;
		requestedCompletionVersion = -1;
		pendingHistory.clear();
		if (operation.keptPrevious())
		{
			pendingHistory.putAll(operation.fragments);
		}
		historyDay = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate();
		counts.detach();
		durableCelebrations.clear();
		durableCompletionVersion++;
		pendingCacheVersion = pendingCacheDurable = -1;
		saveStatus = operation.keptPrevious() ? "Restore failed; current goals kept" : "Backup restored";
		message = "";
		scheduleSave();
		// Deliver before the new view token so the initiating dialog can display its
		// result.
		if (operation.keptPrevious() && failure == null)
		{
			failure = new IOException("Restore cancelled because tracking was busy. All observed progress was kept; try restoring again.");
		}
		if (failure == null)
		{
			completed.accept(BackupResult.success(
					"Backup restored. A copy of your previous data was saved locally as:\n" + operation.result.previousBackupLocation));
		}
		else
		{
			completed.accept(BackupResult.failure(BackupOperations.error(failure) + "\nYour previous goals were kept."));
		}
		emit(true);
	}

	private void discardPrepared()
	{
		BackupArchive.Prepared prepared = preparedRestore;
		preparedRestore = null;
		backups.discard(prepared);
	}

	private static void closeLease(AutoCloseable lease)
	{
		if (lease != null)
		{
			try
			{
				lease.close();
			}
			catch (Exception ignored)
			{
			}
		}
	}

	void finished(String capturedToken, int page)
	{
		histories.finished(capturedToken, page);
	}

	void dailyHistory(String capturedToken, String goalId, String start, String end, Consumer<GoalsView.HistoryPage> completed)
	{
		histories.daily(capturedToken, goalId, start, end, completed);
	}

	void history(String capturedToken, String goalId, String before, Consumer<GoalsView.HistoryPage> completed)
	{
		histories.periods(capturedToken, goalId, before, completed);
	}
}
