package com.mygoals.persistence;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.ui.GoalsView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HistoryArchiveTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();
	private final String key = "a".repeat(64);
	private final ZoneId zone = ZoneId.of("America/Chicago");
	private final LocalDate day = LocalDate.parse("2026-10-16");

	private Filepath root()
	{
		return Filepath.Unchecked.getRooted(temporary.getRoot().toPath());
	}

	private Path path(String name)
	{
		return temporary.getRoot().toPath().resolve(key).resolve("standard").resolve(name);
	}

	private ProfileState seed(int days)
	{
		GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
		LocalDate start = day.minusDays(days);
		engine.observe(Map.of("MINING", 100_000L), GoalEngine.LIVE, start.atTime(12, 0).atZone(zone).toInstant());
		engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, GoalSpec.Cadence.DAILY,
				start, null, zone), start.atTime(12, 0).atZone(zone).toInstant());
		for (int i = 0; i <= days; i++)
		{
			LocalDate date = start.plusDays(i);
			engine.observe(Map.of("MINING", 100_100L + 100L * i), GoalEngine.LIVE, date.atTime(12, 0).atZone(zone).toInstant());
			engine.settleCelebrations(engine.state().celebrations.keySet().stream().collect(java.util.stream.Collectors.toList()), false);
			var item = new ProfileState.ItemActivity();
			item.date = date.toString();
			item.itemId = 12934;
			item.amount = 10;
			item.source = "Drop";
			engine.state().itemActivity.put(date + ":12934:Drop", item);
		}
		engine.state().saveSequence = 1;
		return engine.state();
	}

	@Test
	public void dailyChartLoadsOnlyItsDateWindowAndPreservesOldPaceAndActualWork() throws Exception
	{
		ProfileState source = seed(100);
		String id = source.goals.keySet().iterator().next();
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(source));
			ProfileState hot = repository.loadAndArchive(day).state;
			String unrelated = hot.historyFiles.stream().filter(name -> name.startsWith("2026-09")).findFirst().orElseThrow();
			Files.writeString(path("history/" + unrelated), "unreadable unrelated history");
			LocalDate first = day.minusDays(98);
			var days = repository.dailyHistory(id, first, first.plusDays(5));
			assertEquals(5, days.size());
			assertTrue(days.stream().allMatch(plan -> plan.assigned == 100 && plan.credited == 100));
			assertTrue(repository.dailyHistory(java.util.UUID.randomUUID().toString(), first, first.plusDays(5)).isEmpty());
			assertThrows(IllegalArgumentException.class, () -> repository.dailyHistory(id, first, first.plusDays(10000)));
		}
	}

	@Test
	public void multiYearOfflinePaceIsFilledBeforeCompactionAndOldDatesRemainLoadable() throws Exception
	{
		LocalDate first = day.minusDays(730);
		GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
		engine.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, first.atTime(12, 0).atZone(zone).toInstant());
		String id = engine.add(new GoalSpec("Long goal", null, "MINING", GoalSpec.Mode.GAIN, 10000, 0,
				GoalSpec.Cadence.ONCE, first, day.plusDays(100), zone), first.atTime(12, 0).atZone(zone).toInstant());
		engine.state().saveSequence = 1;
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(engine.state()));
			ProfileState hot = repository.loadAndArchive(day).state;
			assertTrue(hot.goals.get(id).plans.size() <= 32);
			assertTrue(hot.historyFiles.size() >= 24);
			var old = repository.dailyHistory(id, first, first.plusDays(5));
			assertEquals(5, old.size());
			assertTrue(old.stream().allMatch(p -> p.credited == 0 && p.assigned == 13));
			var recent = hot.goals.get(id).plans.values().stream().max(java.util.Comparator.comparing(p -> p.date)).orElseThrow();
			assertTrue(recent.assigned > 90);
			assertEquals(hot.historyFiles, repository.loadAndArchive(day).state.historyFiles);
		}
	}

	@Test
	public void existingSchemaSevenAddsOnlyArchiveMetadataAndPreservesCompletionMarkers()
	{
		ProfileState source = seed(45);
		StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
		var document = new com.google.gson.JsonParser().parse(codec.encode(source)).getAsJsonObject();
		document.addProperty("schemaVersion", 7);
		document.remove("historyFiles");
		document.getAsJsonObject("goals").entrySet().forEach(e -> e.getValue().getAsJsonObject().remove("archivedPeriods"));
		ProfileState migrated = codec.decode(document.toString(), key);
		assertEquals(10, migrated.schemaVersion);
		assertTrue(migrated.historyFiles.isEmpty());
		assertEquals(0, migrated.goals.values().iterator().next().archivedPeriods);
		assertEquals(source.activity.size(), migrated.activity.size());
		assertEquals(source.goals.values().iterator().next().history.size(), migrated.goals.values().iterator().next().history.size());
		assertEquals(source.celebrations.size(), migrated.celebrations.size());
		assertTrue(migrated.celebrations.values().stream().allMatch(e -> e.status.equals("ANNOUNCED")));
	}

	@Test
	public void fiveYearsMigrateWithoutLossAndOldPeriodsLoadInPages() throws Exception
	{
		ProfileState source = seed(1825);
		String id = source.goals.keySet().iterator().next();
		long xp = source.activity.values().stream().mapToLong(a -> a.amount).sum();
		int periodCount = source.goals.get(id).history.size();
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(source));
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			ProfileState hot = repository.loadAndArchive(day).state;
			assertTrue(hot.activity.size() <= 32);
			assertTrue(hot.itemActivity.size() <= 32);
			assertTrue(hot.goals.get(id).history.size() <= 33);
			assertTrue(hot.goals.get(id).plans.size() <= 32);
			assertTrue(hot.celebrations.size() <= 32);
			assertFalse(hot.historyFiles.isEmpty());
			assertTrue(new GoalsView("test", "", "", true, hot, day.atStartOfDay(zone).toInstant()).goals.get(0).hasOlderHistory);
			long retainedXp = hot.activity.values().stream().mapToLong(a -> a.amount).sum();
			long retainedItems = hot.itemActivity.values().stream().mapToLong(a -> a.amount).sum();
			for (String name : hot.historyFiles)
			{
				ProfileState fragment = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON)
						.decode(Files.readString(path("history/" + name)), key);
				retainedXp += fragment.activity.values().stream().mapToLong(a -> a.amount).sum();
				retainedItems += fragment.itemActivity.values().stream().mapToLong(a -> a.amount).sum();
			}
			assertEquals(xp, retainedXp);
			assertEquals(1826L * 10, retainedItems);
			var ids = new HashSet<String>();
			String before = null;
			int pages = 0;
			boolean more;
			do
			{
				var page = repository.history(id, before);
				pages++;
				before = page.before;
				more = page.more;
				page.periods.forEach(p -> assertTrue("A period must appear only once", ids.add(p.id)));
			} while (more && pages < 20);
			assertTrue(pages > 1);
			assertFalse(more);
			assertEquals(hot.goals.get(id).archivedPeriods, ids.size());
			assertEquals(periodCount, ids.size() + hot.goals.get(id).history.size());
			int references = hot.historyFiles.size();
			hot.saveSequence++;
			repository.save(hot, Map.of());
			assertEquals(references, repository.load().state.historyFiles.size());
		}
	}

	@Test
	public void ordinaryLoadDoesNotDecodeArchivesAndAnArchiveReadFailurePreservesTracking() throws Exception
	{
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(seed(90)));
			ProfileState hot = repository.loadAndArchive(day).state;
			String mostRecent = hot.historyFiles.stream().max(String::compareTo).get();
			Files.writeString(path("history/" + mostRecent), "{broken archive");
		}
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			ProfileState hot = repository.loadAndArchive(day).state;
			assertNotNull(hot);
			assertEquals(1, hot.goals.size());
			assertThrows(RuntimeException.class, () -> repository.history(hot.goals.keySet().iterator().next(), null));
			hot.saveSequence++;
			repository.save(hot, Map.of());
			assertEquals(1, repository.load().state.goals.size());
		}
	}

	@Test
	public void legacyPendingCardsCompactAtLoginAndAllHistoryRemainsAvailable() throws Exception
	{
		ProfileState state = seed(180);
		state.celebrations.values().forEach(e -> e.status = "PENDING");
		long xp = state.activity.values().stream().mapToLong(a -> a.amount).sum();
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(state));
			ProfileState hot = repository.loadAndArchive(day.atTime(12, 0).atZone(zone).toInstant()).state;
			assertEquals(1, hot.celebrations.values().stream().filter(e -> e.status.equals("PENDING")).count());
			assertTrue(hot.celebrations.size() <= 32);
			var ids = new HashSet<>(hot.celebrations.keySet());
			long retained = hot.activity.values().stream().mapToLong(a -> a.amount).sum();
			for (String name : hot.historyFiles)
			{
				ProfileState fragment = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON)
						.decode(Files.readString(path("history/" + name)), key);
				fragment.celebrations.forEach((id, event) ->
				{
					assertTrue(ids.add(id));
					assertEquals("SUPPRESSED", event.status);
				});
				retained += fragment.activity.values().stream().mapToLong(a -> a.amount).sum();
			}
			assertEquals(181, ids.size());
			assertEquals(xp, retained);
			assertEquals(1, repository.load().state.celebrations.values().stream().filter(e -> e.status.equals("PENDING")).count());
		}
	}

	@Test
	public void stateCannotCommitAReferenceBeforeItsArchiveAndRetryDoesNotDuplicateIt() throws Exception
	{
		ProfileState state = seed(60);
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(state));
			var fragments = HistoryArchive.detach(state, day);
			state.saveSequence++;
			assertThrows(IllegalArgumentException.class, () -> repository.save(state, Map.of()));
			assertTrue(repository.load().state.historyFiles.isEmpty());
			repository.save(state, fragments);
			state.saveSequence++;
			repository.save(state, fragments);
			assertEquals(fragments.size(), repository.load().state.historyFiles.size());
			try (var files = Files.list(path("history")))
			{
				assertEquals(fragments.size(), files.filter(p -> p.toString().endsWith(".json")).count());
			}
		}
	}

	@Test
	public void snapshotsDoNotShareMutableProgressWithTheClient()
	{
		ProfileState state = seed(2);
		ProfileState copy = StateSnapshot.copy(state);
		String id = state.goals.keySet().iterator().next();
		state.goals.get(id).current.live += 99;
		state.goals.get(id).history.get(0).live += 99;
		state.activity.values().iterator().next().goalCredits.clear();
		state.goals.get(id).plans.values().iterator().next().credited += 99;
		state.celebrations.values().iterator().next().status = "SUPPRESSED";
		assertEquals(100, copy.goals.get(id).current.live);
		assertEquals(100, copy.goals.get(id).history.get(0).live);
		assertFalse(copy.activity.values().iterator().next().goalCredits.isEmpty());
		assertEquals(100, copy.goals.get(id).plans.values().iterator().next().credited);
		assertEquals("ANNOUNCED", copy.celebrations.values().iterator().next().status);
	}

	@Test
	public void snapshotWritesRejectOtherProfilesAndUnsafeArchiveReferences() throws Exception
	{
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			ProfileState other = new ProfileState("b".repeat(64));
			other.saveSequence = 1;
			assertThrows(IllegalArgumentException.class, () -> repository.save(other, Map.of()));
			ProfileState state = new ProfileState(key);
			state.saveSequence = 1;
			state.historyFiles.add("../other.json");
			assertThrows(IllegalArgumentException.class, () -> repository.save(state, Map.of()));
			assertFalse(Files.exists(path("state.json")));
		}
	}

	@Test
	public void backwardClockWorkRemainsAdditiveAcrossImmutableSegments() throws Exception
	{
		ProfileState state = seed(90);
		long before = state.activity.values().stream().mapToLong(a -> a.amount).sum();
		try (ProfileStateRepository repository = new ProfileStateRepository(net.runelite.http.api.RuneLiteAPI.GSON, root(), key))
		{
			repository.load();
			repository.save(new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).encode(state));
			state = repository.loadAndArchive(day).state;
			var extra = new ProfileState.Activity();
			extra.date = day.minusDays(60).toString();
			extra.metricId = "MINING";
			extra.source = "Live";
			extra.trackType = GoalSpec.TrackType.EXPERIENCE;
			extra.amount = 25;
			state.activity.put(extra.date + ":EXPERIENCE:MINING:Live", extra);
			var fragments = HistoryArchive.detach(state, day);
			state.saveSequence++;
			repository.save(state, fragments);
			long total = state.activity.values().stream().mapToLong(a -> a.amount).sum();
			for (String name : state.historyFiles)
			{
				total += new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON).decode(Files.readString(path("history/" + name)),
						key).activity.values().stream().mapToLong(a -> a.amount).sum();
			}
			assertEquals(before + 25, total);
		}
	}
}
