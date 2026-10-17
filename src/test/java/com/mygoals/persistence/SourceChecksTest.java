package com.mygoals.persistence;

import com.google.gson.JsonParser;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.*;

public class SourceChecksTest
{
	private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
	private final String key = "a".repeat(64);

	@Test
	public void sourceTimesSurviveSnapshotsAndReloadWithoutMistakingLootForPageReads()
	{
		GoalEngine engine = new GoalEngine(new ProfileState(key), ZoneId.of("America/Chicago"));
		engine.hiscoresChecked(now);
		engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
		engine.acquire("drop", Map.of(23956, 1L), "Drop", now.plusSeconds(60));
		assertEquals(now.toEpochMilli(), (long) engine.state().collectionPageChecks.get(605));
		assertEquals(now.plusSeconds(60).toEpochMilli(), engine.state().collection.get(23956).verifiedAt);
		engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 5L)), true, now.plusSeconds(120));
		assertEquals(now.toEpochMilli(), (long) engine.state().collectionPageChecks.get(605));
		ProfileState snapshot = StateSnapshot.copy(engine.state());
		engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 5L)), false, now.plusSeconds(180));
		assertEquals(now.toEpochMilli(), (long) snapshot.collectionPageChecks.get(605));
		StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
		ProfileState reloaded = codec.decode(codec.encode(snapshot), key);
		assertEquals(now.toEpochMilli(), reloaded.lastHiScoresCheckedAt);
		assertEquals(Map.of(605, now.toEpochMilli()), reloaded.collectionPageChecks);
		reloaded.collectionPageChecks.put(999999, now.toEpochMilli());
		assertEquals(reloaded.collectionPageChecks, codec.decode(codec.encode(reloaded), key).collectionPageChecks);
		reloaded.collectionPageChecks.put(-1, now.toEpochMilli());
		assertThrows(IllegalArgumentException.class, () -> codec.encode(reloaded));
	}

	@Test
	public void schemaEightKeepsProgressAndDoesNotInventEarlierCheckTimes()
	{
		GoalEngine engine = new GoalEngine(new ProfileState(key), ZoneId.of("America/Chicago"));
		engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
		StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
		var old = new JsonParser().parse(codec.encode(engine.state())).getAsJsonObject();
		old.addProperty("schemaVersion", 8);
		old.remove("lastHiScoresCheckedAt");
		old.remove("collectionPageChecks");
		ProfileState reloaded = codec.decode(old.toString(), key);
		assertEquals(10, reloaded.schemaVersion);
		assertEquals(0, reloaded.lastHiScoresCheckedAt);
		assertTrue(reloaded.collectionPageChecks.isEmpty());
		assertEquals(Long.valueOf(4), reloaded.collection.get(23956).quantity);
		assertEquals(now.toEpochMilli(), reloaded.collection.get(23956).verifiedAt);
	}
}
