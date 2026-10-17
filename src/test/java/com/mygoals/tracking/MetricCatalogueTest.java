package com.mygoals.tracking;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.hiscore.Skill;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetricCatalogueTest
{
    @Test
    public void compiledCatalogueMatchesReleaseScope() throws Exception
    {
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getResourceAsStream("/m0/expected-catalogue.json"), StandardCharsets.UTF_8))
        {
            JsonObject fixture = new JsonParser().parse(reader).getAsJsonObject();
            Set<String> expected = new TreeSet<>();
            fixture.getAsJsonArray("countMetrics").forEach(v -> expected.add(v.getAsString()));
            Set<String> actual = MetricCatalogue.countMetrics().stream().map(Enum::name).collect(Collectors.toSet());
            assertEquals("Review coverage when RuneLite's catalogue changes", expected, actual);
        }
        assertEquals(87, MetricCatalogue.countMetrics().size());
        assertEquals(71, MetricCatalogue.countMetrics().stream().filter(s -> s.getType() == HiscoreSkillType.BOSS).count());
        assertEquals(24, Arrays.stream(HiscoreSkill.values()).filter(s -> s.getType() == HiscoreSkillType.SKILL).count());
    }

    @Test
    public void countTransportUsesScoreAndPreservesUnknown()
    {
        assertEquals(Long.valueOf(42), MetricCatalogue.activityAmount(new Skill(987, 42, -1L)));
        assertNull(MetricCatalogue.activityAmount(new Skill(-1, -1, -1L)));
        assertNull(MetricCatalogue.activityAmount(null));
        assertEquals(Long.valueOf(0), MetricCatalogue.activityAmount(new Skill(-1, 0, -1L)));
    }

    @Test
    public void ratingsCannotEnterCumulativeRecoveryWithoutAudit()
    {
        assertEquals(MetricCatalogue.Semantics.SCORE_REQUIRES_AUDIT, MetricCatalogue.semantics(HiscoreSkill.PVP_ARENA_RANK));
        assertEquals(MetricCatalogue.Semantics.SCORE_REQUIRES_AUDIT, MetricCatalogue.semantics(HiscoreSkill.COLOSSEUM_GLORY));
        assertEquals(MetricCatalogue.Semantics.CUMULATIVE_COUNTER, MetricCatalogue.semantics(HiscoreSkill.VORKATH));
    }
}
