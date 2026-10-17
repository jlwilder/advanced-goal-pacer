package com.mygoals.persistence;

import com.mygoals.domain.GoalSpec;
import com.mygoals.engine.GoalEngine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.time.LocalDate;
import com.mygoals.domain.ProfileState;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class CountMigrationTest
{
    @Test public void schemaTwoCountSaveRetainsAbsoluteCheckpointPendingEditsAndFrozenToday()
    {
        ZoneId zone = ZoneId.of("America/Chicago"); Instant now = Instant.parse("2026-10-16T17:00:00Z");
        GoalEngine e = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        String group = e.addGroup("Monsters"); e.collapseGroup(group);
        String id = e.add(new GoalSpec("Vorkath", group, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN, 7, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
        e.observeCount("VORKATH", 104, true, GoalEngine.LIVE, null, now);
        e.state().goals.get(id).pending = new GoalSpec("Vorkath", group, GoalSpec.TrackType.COUNT, "VORKATH", GoalSpec.Mode.GAIN, 14, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone);
        e.state().goals.get(id).pendingEffectiveDate = "2026-10-23";
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        var old = new JsonParser().parse(codec.encode(e.state())).getAsJsonObject();
        old.addProperty("schemaVersion", 2); old.remove("collection"); old.remove("itemActivity"); old.remove("acquisitions");
        var oldGoal = old.getAsJsonObject("goals").getAsJsonObject(id);
        oldGoal.getAsJsonObject("spec").remove("uniqueTargets"); oldGoal.getAsJsonObject("pending").remove("uniqueTargets");
        var migrated = codec.decode(old.toString(), "a".repeat(64));
        assertEquals(10, migrated.schemaVersion); assertTrue(migrated.groups.get(0).collapsed);
        var goal = migrated.goals.get(id);
        assertEquals(Long.valueOf(104), goal.countCheckpoint); assertEquals(Long.valueOf(104), goal.countCreditedThrough);
        assertTrue(goal.countLiveBoundary); assertEquals(1, goal.current.progress());
        assertEquals(1, goal.plans.values().iterator().next().assigned);
        assertEquals(14, goal.pending.amount); assertTrue(goal.pending.uniqueTargets.isEmpty());
        assertTrue(migrated.collection.isEmpty()); assertEquals(1, migrated.activity.values().iterator().next().amount);
        var reloaded = new GoalEngine(codec.decode(codec.encode(migrated), migrated.profileKey), zone);
        reloaded.observeCount("VORKATH", 105, true, GoalEngine.LIVE, null, now);
        assertEquals(2, reloaded.state().goals.get(id).current.progress());
    }

    @Test public void existingXpSaveRetainsGoalsGroupsProgressQuotasAndActivity() throws Exception
    {
        String json;
        try (var stream = getClass().getResourceAsStream("/persistence/xp-schema-1.json"))
        {
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        var state = codec.decode(json, "a".repeat(64));
        assertEquals(10, state.schemaVersion);
        assertEquals(7, state.saveSequence);
        assertTrue(state.groups.get(0).collapsed);
        var goal = state.goals.values().iterator().next();
        assertEquals(GoalSpec.TrackType.EXPERIENCE, goal.spec.trackType);
        assertEquals("MINING", goal.spec.metricId);
        assertEquals(267, goal.current.progress());
        assertEquals(200, goal.plans.values().iterator().next().assigned);
        assertEquals(267, state.activity.values().iterator().next().amount);
        var engine = new GoalEngine(codec.decode(codec.encode(state), state.profileKey), ZoneId.of("America/Chicago"));
        engine.observe(Map.of("MINING", 100300L), GoalEngine.LIVE, Instant.parse("2026-10-16T18:00:00Z"));
        assertEquals(300, engine.state().activity.values().iterator().next().amount);
        assertEquals(1, engine.state().activity.size());
        assertEquals(200, engine.state().goals.get(goal.id).plans.values().iterator().next().assigned);
    }

    @Test public void schemaThreeRetainsUniqueQuantitiesAndPendingTargetsAndRequiresNewFieldAfterMigration()
    {
        ZoneId zone = ZoneId.of("America/Chicago"); Instant now = Instant.parse("2026-10-16T17:00:00Z");
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.collectionSnapshot(605, com.mygoals.engine.UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
        GoalSpec spec = new GoalSpec("Gauntlet", null, GoalSpec.TrackType.COUNT, "THE_CORRUPTED_GAUNTLET", GoalSpec.Mode.GAIN, 7, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone, Map.of(23956, 6L));
        String id = engine.add(spec, now);
        engine.observeCount(spec.metricId, 104, true, GoalEngine.LIVE, null, now);
        engine.state().goals.get(id).pending = new GoalSpec("Gauntlet", null, spec.trackType, spec.metricId, spec.mode, 14, 0, spec.cadence,
            LocalDate.parse(spec.startDate), null, zone, Map.of(23956, 8L));
        engine.state().goals.get(id).pendingEffectiveDate = "2026-10-23";
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        var old = new JsonParser().parse(codec.encode(engine.state())).getAsJsonObject(); old.addProperty("schemaVersion", 3);
        var goal = old.getAsJsonObject("goals").getAsJsonObject(id);
        goal.getAsJsonObject("spec").remove("totalCountTarget"); goal.getAsJsonObject("pending").remove("totalCountTarget");
        var migrated = codec.decode(old.toString(), engine.state().profileKey);
        assertEquals(10, migrated.schemaVersion); assertEquals(Long.valueOf(4), migrated.collection.get(23956).quantity);
        assertEquals(Map.of(23956, 6L), migrated.goals.get(id).spec.uniqueTargets);
        assertEquals(Map.of(23956, 8L), migrated.goals.get(id).pending.uniqueTargets);
        assertNull(migrated.goals.get(id).spec.totalCountTarget); assertNull(migrated.goals.get(id).pending.totalCountTarget);
        assertEquals(1, migrated.goals.get(id).current.progress()); assertEquals(1, migrated.goals.get(id).plans.values().iterator().next().assigned);
        old.addProperty("schemaVersion", 4);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(old.toString(), engine.state().profileKey));
    }
}
