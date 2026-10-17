package com.mygoals.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import com.mygoals.persistence.StateCodec;
import com.mygoals.persistence.StateSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import javax.swing.SwingUtilities;

import org.junit.Test;

import static org.junit.Assert.*;

public class CatalogueCompatibilityTest
{
	private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
	private final ZoneId zone = ZoneId.of("America/Chicago");
	private final String key = "a".repeat(64);
	private final StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);

	private ProfileState saved(String oldMetric, int oldItem, long target)
	{
		GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
		engine.observe(Map.of("MINING", 1000L), GoalEngine.LIVE, now);
		engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 1000, 0,
				GoalSpec.Cadence.DAILY, LocalDate.parse("2026-10-16"), null, zone), now);
		engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
		engine.add(new GoalSpec("Old boss", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN,
				10, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L)), now);
		engine.acquire("drop", Map.of(23956, 1L), "Drop", now);
		String json = codec.encode(engine.state()).replace("THE_CORRUPTED_GAUNTLET", oldMetric).replace("23956", Integer.toString(oldItem));
		JsonObject document = new JsonParser().parse(json).getAsJsonObject();
		JsonObject boss = document.getAsJsonObject("goals").entrySet().stream()
				.map(e -> e.getValue().getAsJsonObject())
				.filter(g -> g.getAsJsonObject("spec").get("name").getAsString().equals("Old boss")).findFirst().orElseThrow();
		boss.getAsJsonObject("spec").getAsJsonObject("uniqueTargets").addProperty(Integer.toString(oldItem), target);
		document.getAsJsonObject("collectionPageChecks").addProperty("999999", now.toEpochMilli());
		return codec.decode(document.toString(), key);
	}

	@Test
	public void retiredContentRoundTripsWithoutBlockingXpOrLosingTargetsAndActivity() throws Exception
	{
		for (String metric : new String[] { "THE_CORRUPTED_GAUNTLET", "RETIRED_BOSS" })
		{
			ProfileState state = saved(metric, 999999, 6);
			GoalEngine engine = new GoalEngine(StateSnapshot.copy(codec.decode(codec.encode(state), key)), zone);
			engine.observe(Map.of("MINING", 1250L), GoalEngine.LIVE, now.plusSeconds(60));
			assertEquals(250, engine.state().goals.values().iterator().next().current.progress());
			ProfileState.Goal boss = engine.state().goals.values().stream().filter(g -> g.spec.name.equals("Old boss")).findFirst()
					.orElseThrow();
			assertFalse(GoalEngine.supported(boss));
			assertEquals("ACTIVE", boss.lifecycle);
			assertEquals(Long.valueOf(6), boss.spec.uniqueTargets.get(999999));
			assertEquals(1, engine.state().itemActivity.size());
			assertTrue(engine.state().collectionPageChecks.containsKey(999999));
			String group = engine.addGroup("Old group");
			boss.spec = boss.spec.withGroup(group);
			engine.removeGroup(group); // Unrelated management must not revalidate obsolete content.
			GoalsView view = new GoalsView("token", "", "", true, engine.state(), now);
			GoalsView.Goal goal = view.goals.get(1);
			assertFalse(goal.inToday);
			assertFalse(goal.compatibilityIssue.isEmpty());
			assertEquals("Item 999999", goal.uniques.get(0).name);
			SwingUtilities.invokeAndWait(() ->
			{
				assertNotNull(GoalEditor.createForm(view, goal, spec -> fail("A retired goal cannot be submitted"), () ->
				{
				}));
				new TodayCardBody(goal, false, false, (id, label) ->
				{
				});
				new GoalDetailsPanel((id, label) ->
				{
				}).render(goal, false);
			});
			assertThrows(IllegalArgumentException.class, boss.spec::validate);
			codec.decode(codec.encode(engine.state()), key);
		}
	}

	@Test
	public void reducedEligibilityLimitPausesTheGoalRatherThanRejectingItsSavedTarget()
	{
		ProfileState state = saved("THE_CORRUPTED_GAUNTLET", 23956, 500);
		GoalEngine engine = new GoalEngine(state, zone);
		engine.tick(now.plusSeconds(86400));
		ProfileState.Goal boss = state.goals.values().stream().filter(g -> g.spec.name.equals("Old boss")).findFirst().orElseThrow();
		assertFalse(GoalEngine.supported(boss));
		assertEquals(Long.valueOf(500), boss.spec.uniqueTargets.get(23956));
		codec.decode(codec.encode(state), key);
	}

	@Test
	public void structuralCorruptionStillFailsValidation()
	{
		assertThrows(IllegalArgumentException.class, () -> saved("INVALID/../METRIC", 23956, 6));
		assertThrows(IllegalArgumentException.class, () -> saved("THE_CORRUPTED_GAUNTLET", -1, 6));
		assertThrows(IllegalArgumentException.class, () -> saved("THE_CORRUPTED_GAUNTLET", 23956, 0));
	}
}
