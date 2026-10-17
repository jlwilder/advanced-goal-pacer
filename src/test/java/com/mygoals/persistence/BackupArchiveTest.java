package com.mygoals.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.tracking.ProfileIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BackupArchiveTest
{
	private final BackupArchive backups = new BackupArchive(net.runelite.http.api.RuneLiteAPI.GSON);
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();
	private final String account = ProfileIdentity.backupKey(123), key = ProfileIdentity.key("original-install", 123);
	private final String otherKey = ProfileIdentity.key("new-install", 123);
	private final LocalDate day = LocalDate.parse("2026-10-16");
	private final ZoneId zone = ZoneId.of("America/Chicago");
	private final long now = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();

	private Filepath root()
	{
		return Filepath.Unchecked.getRooted(temporary.getRoot().toPath());
	}

	private Filepath file(String name)
	{
		return root().join(name);
	}

	private Path path(String profile, String name)
	{
		return temporary.getRoot().toPath().resolve(profile).resolve("standard").resolve(name);
	}

	private ProfileState seed(String profile, String name)
	{
		GoalEngine engine = new GoalEngine(new ProfileState(profile), zone);
		LocalDate start = day.minusDays(70);
		engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, start.atTime(12, 0).atZone(zone).toInstant());
		String group = engine.addGroup("Skilling");
		engine.collapseGroup(group);
		engine.add(new GoalSpec(name, group, "MINING", GoalSpec.Mode.GAIN, 500, 0, GoalSpec.Cadence.DAILY, start, null, zone),
				start.atTime(12, 0).atZone(zone).toInstant());
		for (int i = 0; i <= 70; i++)
		{
			engine.observe(Map.of("MINING", 100_100L + i * 100L), GoalEngine.LIVE,
					start.plusDays(i).atTime(12, 0).atZone(zone).toInstant());
		}
		engine.state().saveSequence = 1;
		return engine.state();
	}

	private ProfileStateRepository source() throws Exception
	{
		ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key);
		repository.load();
		repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(seed(key, "Mining")));
		repository.loadAndArchive(day);
		return repository;
	}

	private void export(ProfileStateRepository repository, Filepath destination) throws Exception
	{
		backups.export(destination, file("scratch"), repository.load().state, Map.of(), repository::historyFile, account, now, () -> false,
				true);
	}

	private Map<String, byte[]> entries(Filepath zipFile) throws Exception
	{
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(zipFile.openInputStream()))
		{
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null)
			{
				result.put(entry.getName(), zip.readAllBytes());
			}
		}
		return result;
	}

	private void zip(Filepath destination, Map<String, byte[]> contents) throws Exception
	{
		try (ZipOutputStream zip = new ZipOutputStream(destination.openOutputStream()))
		{
			for (var entry : contents.entrySet())
			{
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
	}

	private JsonObject json(byte[] bytes)
	{
		return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private byte[] bytes(JsonObject object)
	{
		return object.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String digest(byte[] bytes) throws Exception
	{
		StringBuilder result = new StringBuilder();
		for (byte octet : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
		{
			result.append(String.format("%02x", octet & 255));
		}
		return result.toString();
	}

	@Test
	public void retiredSkillAndItsArchivedHistoryCanBeExportedAndRestored() throws Exception
	{
		StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
		ProfileState retired = codec.decode(codec.encode(seed(key, "Older skill")).replace("MINING", "RETIRED_SKILL"), key);
		try (ProfileStateRepository source = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			source.load();
			source.save(codec.encode(retired));
			source.loadAndArchive(day);
			int segments = source.load().state.historyFiles.size();
			assertTrue(segments > 0);
			export(source, file("retired.zip"));
			try (BackupArchive.Prepared prepared = backups.prepare(file("retired.zip"), file("restore-scratch"), account, key))
			{
				ProfileState restored = source.restore(prepared, source.load().state, Map.of(), account, now, () -> false).state;
				assertEquals(segments, restored.historyFiles.size());
				var goal = restored.goals.values().iterator().next();
				assertEquals("RETIRED_SKILL", goal.spec.metricId);
				assertFalse(GoalEngine.supported(goal));
				assertFalse(source.history(goal.id, null).periods.isEmpty());
				assertEquals(retired.goals.values().iterator().next().current.progress(), goal.current.progress());
			}
		}
	}

	@Test
	public void fullHistoryRoundTripWorksOnAnotherInstallationAndPreservesThePreviousData() throws Exception
	{
		try (ProfileStateRepository source = source())
		{
			export(source, file("backup.zip"));
			var files = entries(file("backup.zip"));
			assertEquals(source.load().state.historyFiles.size() + 2, files.size());
			assertTrue(
					files.keySet().stream().allMatch(n -> n.equals("manifest.json") || n.equals("state.json") || n.startsWith("history/")));
			assertFalse(key.equals(otherKey));
			try (BackupArchive.Prepared prepared = backups.prepare(file("backup.zip"), file("staging"), account, otherKey);
					ProfileStateRepository target = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), otherKey))
			{
				assertEquals(otherKey, prepared.state.profileKey);
				assertTrue(prepared.periods > 60);
				target.load();
				ProfileState old = seed(otherKey, "Previous goal");
				target.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(old));
				var result = target.restore(prepared, old, Map.of(), account, now, () -> false);
				assertEquals("Mining", result.state.goals.values().iterator().next().spec.name);
				assertTrue(result.state.groups.get(0).collapsed);
				assertTrue(result.state.saveSequence > old.saveSequence);
				assertFalse(result.state.historyFiles.equals(source.load().state.historyFiles));
				for (String name : result.state.historyFiles)
				{
					assertEquals(otherKey, new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON)
							.decode(Files.readString(path(otherKey, "history/" + name)), otherKey).profileKey);
				}
				try (var previous = backups.prepare(Filepath.Unchecked.getRooted(path(otherKey, "backups/" + result.previousBackup)),
						file("previous-staging"), account, otherKey))
				{
					assertEquals("Previous goal", previous.state.goals.values().iterator().next().spec.name);
				}
				assertEquals("Mining", target.load().state.goals.values().iterator().next().spec.name);
			}
		}
	}

	@Test
	public void exportIncludesUnsavedProgressAndUnwrittenHistoryWithoutModifyingTheLiveSave() throws Exception
	{
		try (ProfileStateRepository repository = source())
		{
			ProfileState state = repository.load().state;
			String original = Files.readString(path(key, "state.json"));
			state.goals.values().iterator().next().current.live += 25;
			var pending = HistoryArchive.detach(state, day.plusDays(1));
			assertFalse(pending.isEmpty());
			backups.export(file("dirty.zip"), file("scratch"), state, pending, repository::historyFile, account, now, () -> false, true);
			assertEquals(original, Files.readString(path(key, "state.json")));
			try (var prepared = backups.prepare(file("dirty.zip"), file("staging"), account, key))
			{
				assertEquals(125, prepared.state.goals.values().iterator().next().current.live);
				assertEquals(state.historyFiles.size(), prepared.state.historyFiles.size());
			}
		}
	}

	@Test
	public void exportNeverOverwritesAnExistingBackup() throws Exception
	{
		file("keep.zip").write("keep this file");
		try (ProfileStateRepository repository = source())
		{
			assertThrows(IOException.class, () -> export(repository, file("keep.zip")));
		}
		assertEquals("keep this file", Files.readString(temporary.getRoot().toPath().resolve("keep.zip")));
	}

	@Test
	public void wrongCharactersIncompleteFilesAndChangedBytesAreRejected() throws Exception
	{
		try (ProfileStateRepository repository = source())
		{
			export(repository, file("good.zip"));
			assertThrows(IOException.class,
					() -> backups.prepare(file("good.zip"), file("staging"), ProfileIdentity.backupKey(456), otherKey));
			var files = entries(file("good.zip"));
			files.remove(files.keySet().stream().filter(n -> n.startsWith("history/")).findFirst().get());
			zip(file("missing.zip"), files);
			assertThrows(IOException.class, () -> backups.prepare(file("missing.zip"), file("staging"), account, key));
			files = entries(file("good.zip"));
			files.put("state.json", "changed bytes".getBytes(StandardCharsets.UTF_8));
			zip(file("changed.zip"), files);
			assertThrows(IOException.class, () -> backups.prepare(file("changed.zip"), file("staging"), account, key));
		}
	}

	@Test
	public void unsafePathsUnknownEntriesAndFutureOrIncompleteManifestsAreRejected() throws Exception
	{
		try (ProfileStateRepository repository = source())
		{
			export(repository, file("good.zip"));
			for (String invalid : new String[] { "../escape.json", "/absolute.json", "history/../../escape.json", "session", "state.lock" })
			{
				var files = entries(file("good.zip"));
				files.put(invalid, new byte[0]);
				zip(file("invalid.zip"), files);
				assertThrows(IOException.class, () -> backups.prepare(file("invalid.zip"), file("staging"), account, key));
			}
			for (String missing : new String[] { "format", "version", "account", "scope", "files", "createdAt" })
			{
				var files = entries(file("good.zip"));
				var manifest = json(files.get("manifest.json"));
				manifest.remove(missing);
				files.put("manifest.json", bytes(manifest));
				zip(file("invalid.zip"), files);
				assertThrows(IOException.class, () -> backups.prepare(file("invalid.zip"), file("staging"), account, key));
			}
			var files = entries(file("good.zip"));
			var manifest = json(files.get("manifest.json"));
			manifest.addProperty("version", 99);
			files.put("manifest.json", bytes(manifest));
			zip(file("invalid.zip"), files);
			assertThrows(IOException.class, () -> backups.prepare(file("invalid.zip"), file("staging"), account, key));
			assertFalse(Files.exists(temporary.getRoot().toPath().resolve("escape.json")));
			try (var pending = Files.list(temporary.getRoot().toPath().resolve("staging")))
			{
				assertEquals(0, pending.count());
			}
		}
	}

	@Test
	public void aFutureStateSchemaIsRejectedEvenWhenTheChecksumMatches() throws Exception
	{
		try (ProfileStateRepository repository = source())
		{
			export(repository, file("good.zip"));
			var files = entries(file("good.zip"));
			var state = json(files.get("state.json"));
			state.addProperty("schemaVersion", 99);
			files.put("state.json", bytes(state));
			var manifest = json(files.get("manifest.json"));
			manifest.getAsJsonObject("files").addProperty("state.json", digest(files.get("state.json")));
			files.put("manifest.json", bytes(manifest));
			zip(file("future.zip"), files);
			assertThrows(IOException.class, () -> backups.prepare(file("future.zip"), file("staging"), account, key));
		}
	}

	@Test
	public void cancellationAndChangedStagingLeaveTheCurrentProfileUntouched() throws Exception
	{
		try (ProfileStateRepository repository = source())
		{
			export(repository, file("good.zip"));
			ProfileState old = repository.load().state;
			String unchanged = Files.readString(path(key, "state.json"));
			try (var prepared = backups.prepare(file("good.zip"), file("staging"), account, key))
			{
				assertThrows(IOException.class, () -> repository.restore(prepared, old, Map.of(), account, now, () -> true));
				assertEquals(unchanged, Files.readString(path(key, "state.json")));
			}
			try (var prepared = backups.prepare(file("good.zip"), file("staging"), account, key))
			{
				prepared.history(prepared.state.historyFiles.get(0)).write("changed after validation");
				assertThrows(IOException.class, () -> repository.restore(prepared, old, Map.of(), account, now, () -> false));
				assertEquals(unchanged, Files.readString(path(key, "state.json")));
			}
		}
	}

	@Test
	public void duplicateEntriesAndOversizedExpansionAreRejected() throws Exception
	{
		// Two distinct ZIP names become duplicates by replacing their equal-length
		// filename bytes.
		var files = new LinkedHashMap<String, byte[]>();
		files.put("state.json", "{}".getBytes(StandardCharsets.UTF_8));
		files.put("other.json", "{}".getBytes(StandardCharsets.UTF_8));
		zip(file("dupe.zip"), files);
		Path duplicate = temporary.getRoot().toPath().resolve("dupe.zip");
		byte[] raw = Files.readAllBytes(duplicate), from = "other.json".getBytes(StandardCharsets.UTF_8),
				to = "state.json".getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i <= raw.length - from.length; i++)
		{
			boolean match = true;
			for (int j = 0; j < from.length; j++)
			{
				match &= raw[i + j] == from[j];
			}
			if (match)
			{
				System.arraycopy(to, 0, raw, i, to.length);
			}
		}
		Files.write(duplicate, raw);
		assertThrows(IOException.class, () -> backups.prepare(file("dupe.zip"), file("staging"), account, key));
		try (ZipOutputStream zip = new ZipOutputStream(file("large.zip").openOutputStream()))
		{
			zip.putNextEntry(new ZipEntry("state.json"));
			byte[] zeros = new byte[8192];
			for (long count = 0; count <= BackupArchive.MAX_ENTRY_BYTES; count += zeros.length)
			{
				zip.write(zeros);
			}
			zip.closeEntry();
		}
		assertThrows(IOException.class, () -> backups.prepare(file("large.zip"), file("staging"), account, key));
	}
}
