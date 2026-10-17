package com.mygoals.persistence;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StorageLifecycleTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();
	private final String key = "a".repeat(64), account = "b".repeat(64);
	private final ZoneId zone = ZoneId.of("America/Chicago");
	private final LocalDate first = LocalDate.parse("2016-01-01"), today = LocalDate.parse("2026-10-17");
	private final Instant now = today.atTime(12, 0).atZone(zone).toInstant();
	private final StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
	private final BackupArchive archive = new BackupArchive(net.runelite.http.api.RuneLiteAPI.GSON);

	private Filepath root()
	{
		return Filepath.Unchecked.getRooted(temporary.getRoot().toPath());
	}

	private Path profile()
	{
		return temporary.getRoot().toPath().resolve(key).resolve("standard");
	}

	private ProfileStateRepository repository()
	{
		return new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key);
	}

	private ProfileState seed()
	{
		Instant start = first.atTime(12, 0).atZone(zone).toInstant();
		GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
		engine.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, start);
		for (String name : new String[] { "Delete me", "Keep me" })
		{
			engine.add(
					new GoalSpec(name, null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.ONCE, first, first.plusDays(2), zone),
					start);
		}
		engine.observe(Map.of("MINING", 1200L), GoalEngine.LIVE, start.plusSeconds(3600));
		engine.settleCelebrations(new java.util.ArrayList<>(engine.state().celebrations.keySet()), true);
		engine.state().saveSequence = 1;
		return engine.state();
	}

	private ProfileState create(ProfileStateRepository repository) throws Exception
	{
		repository.load();
		repository.save(codec.encode(seed()));
		return repository.loadAndArchive(now).state;
	}

	@Test
	public void oldFinishedGoalsMoveOutOfHotStateButKeepAllDetailsAndDailyHistory() throws Exception
	{
		try (ProfileStateRepository repository = repository())
		{
			ProfileState state = create(repository);
			assertTrue(state.goals.isEmpty());
			assertEquals(2, state.finished.size());
			assertTrue(state.celebrations.isEmpty());
			assertFalse(codec.encode(state).contains("uniqueTargets"));
			for (var ref : state.finished.values())
			{
				var goal = repository.finishedGoal(ref);
				assertEquals("ACHIEVED", goal.lifecycle);
				assertEquals(200, goal.current.progress());
				assertEquals(1, repository.dailyHistory(ref.id, first, first.plusDays(1)).size());
				assertEquals(34, repository.dailyHistory(ref.id, first, first.plusDays(1)).get(0).assigned);
			}
			var loaded = repository.load().state;
			assertEquals(state.finished.size(), loaded.finished.size());
		}
	}

	@Test
	public void repeatedRestoreAndFailedImportGarbageAreCollectedWithoutRemovingLiveBackupOrLeasedFiles() throws Exception
	{
		try (ProfileStateRepository repository = repository())
		{
			ProfileState state = create(repository);
			Filepath exported = root().join("user.zip");
			archive.export(exported, root().join("scratch"), state, Map.of(), repository::historyFile, account, now.toEpochMilli(),
					() -> false, true);
			AutoCloseable lease = repository.retain(state);
			Set<String> leased = Set.copyOf(state.historyFiles);
			for (int i = 0; i < 3; i++)
			{
				try (var prepared = archive.prepare(exported, root().join("scratch"), account, key))
				{
					state = repository.restore(prepared, state, Map.of(), account, now.toEpochMilli() + i, () -> false).state;
				}
				for (var ref : state.finished.values())
				{
					assertEquals(ref.id, repository.finishedGoal(ref).id);
				}
			}
			String orphan = "2016-01-" + UUID.randomUUID() + ".json";
			Files.writeString(profile().resolve("history").resolve(orphan), "orphan import bytes");
			repository.cleanup();
			assertFalse(Files.exists(profile().resolve("history").resolve(orphan)));
			for (String file : leased)
			{
				assertTrue(repository.historyFile(file).exists());
			}
			lease.close();
			repository.cleanup();
			Set<String> expected = new HashSet<>(state.historyFiles);
			expected.addAll(codec.decode(Files.readString(profile().resolve("state.backup.json")), key).historyFiles);
			try (var files = Files.list(profile().resolve("history")))
			{
				assertEquals(expected, files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
			}
		}
	}

	@Test
	public void deletionPurgesColdHotHistoryAttributionRecoveryAndAutomaticCopiesButKeepsOtherHistory() throws Exception
	{
		try (ProfileStateRepository repository = repository())
		{
			ProfileState state = create(repository);
			String deleted = state.finished.values().stream().filter(ref -> ref.name.equals("Delete me")).findFirst().orElseThrow().id;
			String kept = state.finished.values().stream().filter(ref -> ref.name.equals("Keep me")).findFirst().orElseThrow().id;
			Filepath userExport = root().join("user.zip");
			archive.export(userExport, root().join("scratch"), state, Map.of(), repository::historyFile, account, now.toEpochMilli(),
					() -> false, true);
			try (var prepared = archive.prepare(userExport, root().join("scratch"), account, key))
			{
				state = repository.restore(prepared, state, Map.of(), account, now.toEpochMilli(), () -> false).state;
			}
			Files.copy(profile().resolve("state.json"), profile().resolve("state.invalid-" + UUID.randomUUID() + ".json"));
			Files.copy(profile().resolve("state.json"), profile().resolve("state.backup.pending.json"));
			Path abandoned=profile().resolve("history").resolve("2016-01-"+UUID.randomUUID()+".json"); Files.writeString(abandoned,"interrupted import");
			GoalEngine engine = new GoalEngine(state, zone);
			engine.remove(deleted);
			state.saveSequence++;
			repository.save(state, Map.of());
			repository.cleanup();
			assertFalse(Files.exists(abandoned));
			assertTrue(repository.history(deleted, null).periods.isEmpty());
			assertTrue(repository.dailyHistory(deleted, first, first.plusDays(1)).isEmpty());
			assertEquals(200, repository.finishedGoal(state.finished.get(kept)).current.progress());
			try (var files = Files.walk(profile()))
			{
				for (Path file : files
						.filter(path -> path.toString().endsWith(".json") && !path.getFileName().toString().startsWith("deletions"))
						.collect(java.util.stream.Collectors.toList()))
				{
					ProfileState snapshot = codec.decode(Files.readString(file), key);
					assertFalse(snapshot.goals.containsKey(deleted));
					assertFalse(snapshot.finished.containsKey(deleted));
					assertTrue(snapshot.celebrations.values().stream().noneMatch(event -> event.goalId.equals(deleted)));
					assertTrue(snapshot.activity.values().stream().allMatch(
							activity -> activity.goalCredits.keySet().stream().noneMatch(credit -> credit.startsWith(deleted + ":"))));
				}
			}
			try (var copies = Files.list(profile().resolve("backups")))
			{
				for (Path copy : copies.collect(java.util.stream.Collectors.toList()))
				{
					try (var prepared = archive.prepare(Filepath.Unchecked.getRooted(copy), root().join("scratch"), account, key))
					{
						assertFalse(prepared.state.finished.containsKey(deleted));
						assertTrue(prepared.state.finished.containsKey(kept));
						for (String name : prepared.state.historyFiles)
						{
							var fragment = archive.readHistory(prepared, name, key);
							assertFalse(fragment.goals.containsKey(deleted));
							assertTrue(fragment.celebrations.values().stream().noneMatch(e -> e.goalId.equals(deleted)));
							assertTrue(fragment.activity.values().stream()
									.allMatch(a -> a.goalCredits.keySet().stream().noneMatch(c -> c.startsWith(deleted + ":"))));
						}
					}
				}
			}
			// A user-owned export remains an intentional point-in-time copy.
			try (var prepared = archive.prepare(userExport, root().join("scratch"), account, key))
			{
				assertTrue(prepared.state.finished.containsKey(deleted));
			}
		}
		try (var reopened = repository())
		{
			ProfileState state = reopened.loadAndArchive(now).state;
			assertEquals(1, state.finished.size());
		}
	}

	@Test
	public void interruptedDeletionResumesFromDurableIntentWithoutResurrectingTheGoal() throws Exception
	{
		String deleted;
		try (ProfileStateRepository repository = repository())
		{
			ProfileState state = create(repository);
			deleted = state.finished.keySet().iterator().next();
			Files.writeString(profile().resolve("deletions.json"),
					"{\"sequence\":" + (state.saveSequence + 1) + ",\"ids\":[\"" + deleted + "\"]}");
		}
		try (ProfileStateRepository repository = repository())
		{
			ProfileState recovered = repository.loadAndArchive(now).state;
			assertFalse(recovered.finished.containsKey(deleted));
			assertTrue(recovered.deletedGoals.contains(deleted));
			assertTrue(repository.dailyHistory(deleted, first, first.plusDays(1)).isEmpty());
		}
	}

	@Test
	public void schemaNineMigrationPreservesTheWholeExistingState()
	{
		ProfileState original = seed();
		var json = new com.google.gson.JsonParser().parse(codec.encode(original)).getAsJsonObject();
		json.addProperty("schemaVersion", 9);
		json.remove("finished");
		json.remove("deletedGoals");
		ProfileState migrated = codec.decode(json.toString(), key);
		assertEquals(codec.encode(original), codec.encode(migrated));
		var copy = StateSnapshot.copy(migrated);
		copy.deletedGoals.add(UUID.randomUUID().toString());
		assertTrue(migrated.deletedGoals.isEmpty());
	}

	@Test
	public void pendingFragmentsAndRecoveryBackupCannotResurrectADeletedGoal() throws Exception
	{
		String deleted;
		try (var repository = repository())
		{
			repository.load();
			ProfileState state = seed();
			repository.save(codec.encode(state));
			deleted = state.goals.keySet().iterator().next();
			Map<String, ProfileState> pending = HistoryArchive.detach(state, today);
			new GoalEngine(state, zone).remove(deleted);
			state.saveSequence++;
			repository.save(state, pending);
			assertTrue(repository.dailyHistory(deleted, first, first.plusDays(1)).isEmpty());
			Files.writeString(profile().resolve("state.json"), "{broken state with " + deleted);
		}
		try (var recovered = repository())
		{
			ProfileState state = recovered.loadAndArchive(now).state;
			assertFalse(state.goals.containsKey(deleted));
			assertFalse(state.finished.containsKey(deleted));
			assertEquals(1, state.finished.size());
			assertTrue(recovered.dailyHistory(deleted, first, first.plusDays(1)).isEmpty());
			try (var files = Files.list(profile()))
			{
				assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("state.invalid-")));
			}
		}
	}
}
