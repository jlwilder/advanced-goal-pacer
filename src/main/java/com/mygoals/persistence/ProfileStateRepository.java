package com.mygoals.persistence;

import com.google.gson.Gson;
import com.mygoals.domain.ProfileState;
import com.mygoals.domain.GoalPurge;
import com.mygoals.celebration.CompletionTracker;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Collectors;
import net.runelite.client.util.Filepath;

/**
 * Canonical disk operations use the serial I/O executor; snapshot leases never
 * wait for disk.
 */
public final class ProfileStateRepository implements AutoCloseable
{
	public static final class Loaded
	{
		public final ProfileState state;
		public final String notice;
		public final boolean writable;

		Loaded(ProfileState state, String notice, boolean writable)
		{
			this.state = state;
			this.notice = notice;
			this.writable = writable;
		}
	}

	private final Filepath directory;
	private final String key;
	private final StateCodec codec;
	private final BackupArchive backups;
	private final Gson gson;
	private final java.util.Set<String> deleted = new java.util.LinkedHashSet<>();
	private final java.util.Set<String> emptyFiles = new java.util.HashSet<>();
	private final java.util.Map<String, java.util.Set<String>> leases = new java.util.concurrent.ConcurrentHashMap<>();
	private boolean sweepRequired;
	private int cleanupPasses;

	private static final class Deletions
	{
		long sequence;
		java.util.Set<String> ids = new java.util.LinkedHashSet<>();
	}

	private boolean currentValid, writable = true;
	private long writtenSequence = -1;
	private FileChannel lockChannel;
	private FileLock lock;
	private List<String> historyFiles = List.of();
	private final java.util.Set<String> checkedHistory = new java.util.HashSet<>();

	public ProfileStateRepository(Gson clientGson, Filepath pluginRoot, String profileKey)
	{
		if (profileKey == null || !profileKey.matches("[a-f0-9]{64}"))
		{
			throw new IllegalArgumentException("Invalid opaque profile key");
		}
		this.key = profileKey;
		this.directory = pluginRoot.joinSegment(profileKey).joinSegment("standard");
		codec = new StateCodec(clientGson);
		backups = new BackupArchive(clientGson);
		gson = clientGson.newBuilder().setPrettyPrinting().create();
	}

	public synchronized Loaded load() throws IOException
	{
		directory.createDirectories();
		if (lock == null)
		{
			lockChannel = directory.join("state.lock").openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			try
			{
				lock = lockChannel.tryLock();
			}
			catch (OverlappingFileLockException ex)
			{
				lock = null;
			}
			if (lock == null)
			{
				lockChannel.close();
				return new Loaded(null, "This profile's save is open in another client. Close it, then retry loading", false);
			}
			// No transfer can own these staging files before this repository's first load.
			if (transfers().exists())
			{
				transfers().deleteRecursively();
			}
		}
		Filepath current = directory.join("state.json"), backup = directory.join("state.backup.json");
		if (!current.exists() && !backup.exists())
		{
			return new Loaded(new ProfileState(key), "Local save ready", true);
		}
		RuntimeException invalid = null;
		if (current.exists())
		{
			try
			{
				ProfileState state = codec.decode(read(current), key);
				adoptDeletions(state);
				checkHistory(state);
				currentValid = true;
				writtenSequence = state.saveSequence;
				historyFiles = List.copyOf(state.historyFiles);
				return new Loaded(state, "Loaded local save", true);
			}
			catch (StateCodec.FutureSchema future)
			{
				writable = false;
				return new Loaded(null, future.getMessage(), false);
			}
			catch (RuntimeException ex)
			{
				invalid = ex;
			}
		}
		if (backup.exists())
		{
			try
			{
				ProfileState state = codec.decode(read(backup), key);
				adoptDeletions(state);
				checkHistory(state);
				if (current.exists())
				{
					current.copyTo(directory.join("state.invalid-" + UUID.randomUUID() + ".json"));
				}
				currentValid = false;
				writtenSequence = state.saveSequence;
				historyFiles = List.copyOf(state.historyFiles);
				return new Loaded(state, "Recovered previous save; invalid file preserved", true);
			}
			catch (RuntimeException ex)
			{
				invalid = ex;
			}
		}
		writable = false;
		return new Loaded(null, "Could not validate the save or backup. Files preserved; editing disabled" +
				(invalid == null ? "" : " (" + invalid.getClass().getSimpleName() + ")"), false);
	}

	/**
	 * Initial migration/compaction is entirely on I/O, before the game thread
	 * receives state.
	 */
	public synchronized Loaded loadAndArchive(LocalDate today) throws IOException
	{
		return loadAndArchive(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
	}

	public synchronized Loaded loadAndArchive(Instant now) throws IOException
	{
		Loaded loaded = load();
		if (loaded.state != null && loaded.writable)
		{
			boolean resumed = new com.mygoals.engine.GoalEngine(loaded.state, ZoneId.systemDefault()).resumeMissedDays(now);
			boolean settled = CompletionTracker.expireDailyCards(loaded.state, now);
			LocalDate today = now.atZone(ZoneId.systemDefault()).toLocalDate();
			Map<String, ProfileState> fragments = HistoryArchive.detach(loaded.state, today);
			if (resumed || settled || !fragments.isEmpty() || sweepRequired)
			{
				loaded.state.saveSequence = Math.addExact(loaded.state.saveSequence, 1);
				save(loaded.state, fragments);
			}
			try
			{
				cleanup();
			}
			catch (IOException ignored)
			{
				// Retry unreachable-file cleanup on the next commit.
			}
		}
		return loaded;
	}

	public synchronized void save(String json) throws IOException
	{
		if (!writable || lock == null || !lock.isValid())
		{
			throw new IOException("Save is read-only until its load error is resolved");
		}
		ProfileState state = codec.decode(json, key);
		save(state, Map.of());
	}

	/**
	 * The snapshot belongs exclusively to this worker; JSON never runs on the game
	 * thread.
	 */
	public synchronized void save(ProfileState state, Map<String, ProfileState> fragments) throws IOException
	{
		if (!writable || lock == null || !lock.isValid())
		{
			throw new IOException("Save is read-only until its load error is resolved");
		}
		if (!key.equals(state.profileKey))
		{
			throw new IllegalArgumentException("Save profile mismatch");
		}
		if (state.saveSequence <= writtenSequence)
		{
			return;
		}
		deleted.addAll(state.deletedGoals);
		state.deletedGoals.addAll(deleted);
		GoalPurge.strip(state, deleted);
		if (!deleted.isEmpty() && (sweepRequired || !journalMatches(deleted)))
		{
			Deletions intent = new Deletions();
			intent.sequence = state.saveSequence;
			intent.ids.addAll(deleted);
			Filepath pending = directory.join("deletions.pending.json");
			writeDurable(pending, gson.toJson(intent));
			replace(pending, directory.join("deletions.json"));
			sweepRequired = true;
			cleanupPasses = 2;
			// Drop abandoned imports before decoding survivors; protect this transaction's
			// new files as well.
			try (AutoCloseable lease = retain(state))
			{
				cleanup();
			}
			catch (IOException | RuntimeException ex)
			{
				throw ex;
			}
			catch (Exception ex)
			{
				throw new IOException(ex);
			}
			purgeOwnedCopies();
			sweepRequired = false;
		}
		state.historyFiles.removeIf(emptyFiles::contains);
		Filepath archive = directory.join("history");
		if (!fragments.isEmpty())
		{
			archive.createDirectories();
		}
		for (Map.Entry<String, ProfileState> entry : fragments.entrySet())
		{
			if (!state.historyFiles.contains(entry.getKey()) && emptyFiles.contains(entry.getKey()))
			{
				continue;
			}
			if (!HistoryArchive.validName(entry.getKey()) || !state.historyFiles.contains(entry.getKey())
					|| !key.equals(entry.getValue().profileKey))
			{
				throw new IOException("Invalid history segment");
			}
			Filepath target = archive.joinSegment(entry.getKey());
			// Segments are immutable, so retrying a failed state commit cannot count rows
			// twice.
			if (!target.exists())
			{
				Filepath pending = archive.joinSegment(entry.getKey() + ".pending");
				ProfileState fragment = StateSnapshot.copy(entry.getValue());
				GoalPurge.strip(fragment, deleted);
				if (emptyHistory(fragment))
				{
					emptyFiles.add(entry.getKey());
					state.historyFiles.remove(entry.getKey());
					continue;
				}
				writeDurable(pending, codec.encode(fragment));
				replace(pending, target);
			}
		}
		checkHistory(state);
		if (!state.historyFiles.equals(historyFiles))
		{
			cleanupPasses = 2;
		}
		writeState(state, codec.encode(state));
		if (cleanupPasses > 0)
		{
			try
			{
				cleanup();
				cleanupPasses--;
			}
			catch (IOException ignored)
			{
				// A committed save stays successful; cleanup retries later.
			}
		}
	}

	private void checkHistory(ProfileState state)
	{
		for (String name : state.historyFiles)
		{
			if (!HistoryArchive.validName(name)
					|| (!checkedHistory.contains(name) && !directory.join("history").joinSegment(name).exists()))
			{
				throw new IllegalArgumentException("A saved history segment is missing; files were preserved");
			}
			checkedHistory.add(name);
		}
	}

	private void writeState(ProfileState state, String json) throws IOException
	{
		if (state.saveSequence <= writtenSequence)
		{
			return;
		}
		directory.createDirectories();
		Filepath current = directory.join("state.json"), temp = directory.join("state.pending.json");
		writeDurable(temp, json);
		if (currentValid && current.exists())
		{
			Filepath backupTemp = directory.join("state.backup.pending.json");
			ProfileState old = codec.decode(read(current), key);
			GoalPurge.strip(old, deleted);
			old.deletedGoals.addAll(deleted);
			old.historyFiles.removeIf(emptyFiles::contains);
			writeDurable(backupTemp, codec.encode(old));
			replace(backupTemp, directory.join("state.backup.json"));
		}
		replace(temp, current);
		currentValid = true;
		writtenSequence = state.saveSequence;
		historyFiles = List.copyOf(state.historyFiles);
	}

	public static final class HistoryPage
	{
		public final List<ProfileState.Period> periods;
		public final String before;
		public final boolean more;

		HistoryPage(List<ProfileState.Period> periods, String before, boolean more)
		{
			this.periods = List.copyOf(periods);
			this.before = before;
			this.more = more;
		}
	}

	/**
	 * Read at most six recorded months. Older segments are never decoded during
	 * ordinary play.
	 */
	public synchronized HistoryPage history(String goalId, String before) throws IOException
	{
		UUID.fromString(goalId);
		if (before != null)
		{
			java.time.YearMonth.parse(before);
		}
		List<String> months = historyFiles.stream().map(n -> n.substring(0, 7)).distinct()
				.filter(m -> before == null || m.compareTo(before) < 0).sorted(java.util.Comparator.reverseOrder())
				.collect(Collectors.toList());
		List<String> selected = months.stream().limit(6).collect(Collectors.toList());
		Map<String, ProfileState.Period> periods = new java.util.LinkedHashMap<>();
		for (String name : historyFiles)
		{
			if (!selected.contains(name.substring(0, 7)))
			{
				continue;
			}
			ProfileState fragment = codec.decode(read(directory.join("history").joinSegment(name)), key);
			ProfileState.Goal goal = fragment.goals.get(goalId);
			if (goal != null)
			{
				goal.history.forEach(p -> periods.put(p.id, p));
			}
		}
		List<ProfileState.Period> result = new ArrayList<>(periods.values());
		result.sort(java.util.Comparator.comparing(p -> p.start));
		return new HistoryPage(result, selected.isEmpty() ? before : selected.get(selected.size() - 1), months.size() > selected.size());
	}

	/**
	 * Chart reads only the visible date window, never the account's whole history.
	 */
	public synchronized List<ProfileState.DayPlan> dailyHistory(String goalId, LocalDate start, LocalDate end) throws IOException
	{
		UUID.fromString(goalId);
		long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
		if (days < 1 || days > 56)
		{
			throw new IllegalArgumentException("Choose a bounded daily history window");
		}
		String firstMonth = java.time.YearMonth.from(start).toString(), lastMonth = java.time.YearMonth.from(end.minusDays(1)).toString();
		Map<String, ProfileState.DayPlan> result = new java.util.LinkedHashMap<>();
		for (String name : historyFiles)
		{
			String month = name.substring(0, 7);
			if (month.compareTo(firstMonth) < 0 || month.compareTo(lastMonth) > 0)
			{
				continue;
			}
			ProfileState fragment = codec.decode(read(directory.join("history").joinSegment(name)), key);
			ProfileState.Goal goal = fragment.goals.get(goalId);
			if (goal == null)
			{
				continue;
			}
			goal.plans.forEach((id, plan) ->
			{
				LocalDate date = LocalDate.parse(plan.date);
				if (!date.isBefore(start) && date.isBefore(end))
				{
					result.put(id, plan);
				}
			});
		}
		return List.copyOf(result.values());
	}

	private static String read(Filepath file) throws IOException
	{
		StringBuilder result = new StringBuilder();
		try (BufferedReader reader = file.openBufferedReader())
		{
			char[] buffer = new char[8192];
			int length;
			while ((length = reader.read(buffer)) != -1)
			{
				result.append(buffer, 0, length);
			}
		}
		return result.toString();
	}

	public Filepath transfers()
	{
		return directory.join("transfers");
	}

	public synchronized ProfileState.Goal finishedGoal(ProfileState.Finished ref) throws IOException
	{
		if (deleted.contains(ref.id))
		{
			throw new IOException("This goal was deleted");
		}
		ProfileState fragment = codec.decode(read(historyFile(ref.file)), key);
		ProfileState.Goal goal = fragment.goals.get(ref.id);
		if (goal == null)
		{
			throw new IOException("Finished goal data is unavailable");
		}
		return goal;
	}

	public AutoCloseable retain(ProfileState state)
	{
		String id = UUID.randomUUID().toString();
		leases.put(id, java.util.Set.copyOf(state.historyFiles));
		return () -> leases.remove(id);
	}

	/**
	 * Only unreachable blobs are removed; live and previous-save references never
	 * expire.
	 */
	public synchronized void cleanup() throws IOException
	{
		var keep = new java.util.HashSet<String>();
		for (String file : List.of("state.json", "state.backup.json"))
		{
			Filepath path = directory.join(file);
			if (path.exists())
			{
				try
				{
					keep.addAll(codec.decode(read(path), key).historyFiles);
				}
				catch (RuntimeException ex)
				{
					return;
				} // Unknown references: preserve files until recovery resolves them.
			}
		}
		leases.values().forEach(keep::addAll);
		Filepath history = directory.join("history");
		if (!history.exists())
		{
			return;
		}
		try (var files = history.walk(1))
		{
			for (Filepath file : files.collect(Collectors.toList()))
			{
				String name = file.getFileName();
				if (HistoryArchive.validName(name) && !keep.contains(name))
				{
					file.deleteIfExists();
					checkedHistory.remove(name);
				}
				else if (name.endsWith(".json.pending") && HistoryArchive.validName(name.substring(0, name.length() - 8)))
				{
					file.deleteIfExists();
				}
			}
		}
	}

	private boolean journalMatches(java.util.Set<String> ids) throws IOException
	{
		Filepath file = directory.join("deletions.json");
		return file.exists() && gson.fromJson(read(file), Deletions.class).ids.equals(ids);
	}

	private void adoptDeletions(ProfileState state) throws IOException
	{
		deleted.clear();
		deleted.addAll(state.deletedGoals);
		Filepath file = directory.join("deletions.json");
		if (file.exists())
		{
			Deletions intent = gson.fromJson(read(file), Deletions.class);
			if (intent == null || intent.ids == null || intent.sequence < 0)
			{
				throw new IOException("Invalid deletion recovery record");
			}
			intent.ids.forEach(UUID::fromString);
			if (state.saveSequence < intent.sequence)
			{
				deleted.addAll(intent.ids);
				sweepRequired = true;
			}
		}
		state.deletedGoals.addAll(deleted);
		GoalPurge.strip(state, deleted);
	}

	private void purgeOwnedCopies() throws IOException
	{
		Filepath history = directory.join("history");
		if (history.exists())
		{
			try (var files = history.walk(1))
			{
				for (Filepath file : files.collect(Collectors.toList()))
				{
					if (!HistoryArchive.validName(file.getFileName()))
					{
						continue;
					}
					ProfileState fragment = codec.decode(read(file), key);
					if (GoalPurge.strip(fragment, deleted))
					{
						Filepath pending = history.joinSegment(file.getFileName() + ".pending");
						writeDurable(pending, codec.encode(fragment));
						replace(pending, file);
					}
					if (emptyHistory(fragment))
					{
						emptyFiles.add(file.getFileName());
					}
				}
			}
		}
		try (var files = directory.walk(1))
		{
			for (Filepath file : files.collect(Collectors.toList()))
			{
				if (!file.getFileName().matches("state\\.invalid-[a-f0-9-]+\\.json") &&
						!List.of("state.pending.json", "state.backup.pending.json").contains(file.getFileName()))
				{
					continue;
				}
				ProfileState diagnostic;
				try
				{
					diagnostic = codec.decode(read(file), key);
				}
				catch (RuntimeException invalid)
				{
					file.deleteIfExists();
					continue;
				} // A damaged diagnostic cannot be safely redacted.
				if (GoalPurge.strip(diagnostic, deleted))
				{
					Filepath pending = directory.joinSegment(file.getFileName() + ".pending");
					writeDurable(pending, codec.encode(diagnostic));
					replace(pending, file);
				}
			}
		}
		Filepath automatic = directory.join("backups");
		if (automatic.exists())
		{
			try (var files = automatic.walk(1))
			{
				for (Filepath file : files.collect(Collectors.toList()))
				{
					if (file.getFileName().matches("before-restore-[0-9]+-[a-f0-9-]+\\.zip"))
					{
						backups.purgeManaged(file, deleted);
					}
					else if (file.getFileName().matches("purge-[a-zA-Z0-9-]+\\.zip"))
					{
						file.deleteIfExists();
					}
				}
			}
		}
	}

	private static boolean emptyHistory(ProfileState state)
	{
		return state.goals.isEmpty() && state.finished.isEmpty() && state.activity.isEmpty() && state.itemActivity.isEmpty() &&
				state.celebrations.isEmpty() && state.xp.isEmpty() && state.counts.isEmpty() && state.collection.isEmpty()
				&& state.acquisitions.isEmpty();
	}

	public Filepath historyFile(String name)
	{
		if (!HistoryArchive.validName(name))
		{
			throw new IllegalArgumentException("Invalid history name");
		}
		return directory.join("history").joinSegment(name);
	}

	public static final class Restored
	{
		public final ProfileState state;
		public final String previousBackup;
		public final String previousBackupLocation;

		Restored(ProfileState state, Filepath previousBackup)
		{
			this.state = state;
			this.previousBackup = previousBackup.getFileName();
			this.previousBackupLocation = previousBackup.toString();
		}
	}

	/**
	 * New segment names leave every existing history file and the previous state
	 * backup intact.
	 */
	public synchronized Restored restore(BackupArchive.Prepared prepared, ProfileState previous, Map<String, ProfileState> pending,
			String account, long now, java.util.function.BooleanSupplier cancelled) throws IOException
	{
		if (!writable || lock == null || !lock.isValid() || !key.equals(previous.profileKey) || !key.equals(prepared.state.profileKey))
		{
			throw new IOException("This profile is not available for restore.");
		}
		Filepath backupDirectory = directory.join("backups");
		backupDirectory.createDirectories();
		Filepath preserved = backupDirectory.joinSegment("before-restore-" + now + "-" + UUID.randomUUID() + ".zip");
		// Preserve bytes even if an old history segment is damaged; validating the
		// selected restore is separate.
		backups.export(preserved, transfers(), previous, pending, this::historyFile, account, now, cancelled, false);
		ProfileState restored = prepared.state;
		List<String> imported = new ArrayList<>();
		Map<String, String> renamed = new java.util.HashMap<>();
		cleanupPasses = 2; // Includes imports abandoned before the state-file commit.
		directory.join("history").createDirectories();
		for (String oldName : restored.historyFiles)
		{
			if (cancelled.getAsBoolean())
			{
				throw new IOException("Restore cancelled before replacing local goals.");
			}
			ProfileState fragment = backups.readHistory(prepared, oldName, key);
			String name = oldName.substring(0, 7) + "-" + UUID.randomUUID() + ".json";
			writeDurable(historyFile(name), codec.encode(fragment));
			imported.add(name);
			renamed.put(oldName, name);
		}
		restored.historyFiles = imported;
		restored.finished.values().forEach(ref -> ref.file = renamed.get(ref.file));
		new com.mygoals.engine.GoalEngine(restored, ZoneId.systemDefault()).resumeMissedDays(Instant.ofEpochMilli(now));
		Map<String, ProfileState> compacted = HistoryArchive.detach(restored,
				java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate());
		restored.celebrations.values().stream().filter(e -> e.status.equals("PENDING")).forEach(e -> e.status = "SUPPRESSED");
		restored.saveSequence = Math.addExact(Math.max(writtenSequence, Math.max(previous.saveSequence, restored.saveSequence)), 1);
		restored.savedAt = now;
		if (cancelled.getAsBoolean())
		{
			throw new IOException("Restore cancelled before replacing local goals.");
		}
		// An explicit restore may intentionally recover goals from an older exported
		// backup.
		deleted.clear();
		deleted.addAll(restored.deletedGoals);
		Deletions intent = new Deletions();
		intent.sequence = restored.saveSequence;
		intent.ids.addAll(deleted);
		Filepath journal = directory.join("deletions.pending.json");
		writeDurable(journal, gson.toJson(intent));
		replace(journal, directory.join("deletions.json"));
		sweepRequired = false;
		try (AutoCloseable lease = retain(previous))
		{
			save(restored, compacted);
		}
		catch (IOException | RuntimeException ex)
		{
			throw ex;
		}
		catch (Exception ex)
		{
			throw new IOException(ex);
		}
		// No fallible cleanup after the commit: callers must always receive the
		// committed state.
		return new Restored(restored, preserved);
	}

	public synchronized void saveReconciled(ProfileState state, Map<String, ProfileState> fragments, long now) throws IOException
	{
		state.saveSequence = Math.addExact(Math.max(writtenSequence, state.saveSequence), 1);
		state.savedAt = now;
		save(state, fragments);
	}

	private static void writeDurable(Filepath path, String content) throws IOException
	{
		ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
		try (FileChannel channel = path.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING))
		{
			while (bytes.hasRemaining())
			{
				channel.write(bytes);
			}
			channel.force(true);
		}
	}

	private static void replace(Filepath source, Filepath destination) throws IOException
	{
		try
		{
			source.moveTo(destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			source.moveTo(destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public synchronized void close() throws IOException
	{
		if (lock != null)
		{
			lock.release();
			lock = null;
		}
		if (lockChannel != null)
		{
			lockChannel.close();
			lockChannel = null;
		}
	}
}
