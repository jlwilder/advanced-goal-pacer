package com.mygoals.persistence;

import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalRestartTest
{
    @Test
    public void restartPreservesFrozenQuotaAndRecoversOnlyNewXp()
    {
        Instant now = Instant.parse("2026-10-16T17:00:00Z");
        ZoneId zone = ZoneId.of("America/Chicago");
        String key = "a".repeat(64);
        GoalEngine engine = new GoalEngine(new ProfileState(key), zone);
        engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now);
        String group = engine.addGroup("Skilling");
        String goal = engine.add(new GoalSpec("Mine", group, "MINING", GoalSpec.Mode.GAIN, 100_000, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, zone), now);
        engine.observe(Map.of("MINING", 110_000L), GoalEngine.LIVE, now);
        engine.collapseGroup(group);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        GoalEngine restarted = new GoalEngine(codec.decode(codec.encode(engine.state()), key), zone);
        restarted.observe(Map.of("MINING", 117_400L), GoalEngine.RECOVERED, now.plusSeconds(100));
        restarted.observe(Map.of("MINING", 117_400L), GoalEngine.RECOVERED, now.plusSeconds(101));
        ProfileState.Goal restored = restarted.state().goals.get(goal);
        assertTrue(restarted.state().groups.get(0).collapsed);
        assertEquals(14_286, restarted.today(restored, now).assigned);
        assertEquals(17_400, restarted.today(restored, now).credited);
        assertEquals(10_000, restored.current.live);
        assertEquals(7400, restored.current.recovered);
        assertEquals(2, restarted.state().activity.size());
    }
    @Test(expected = IllegalArgumentException.class)
    public void saveFromAnotherCharacterIsRejected()
    {
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON);
        codec.decode(codec.encode(new ProfileState("a".repeat(64))), "b".repeat(64));
    }
}
