package com.mygoals.m0;

import com.mygoals.tracking.MetricCatalogue;
import java.util.stream.Collectors;
import net.runelite.client.hiscore.HiscoreSkillType;
import org.junit.Test;
import static org.junit.Assert.*;

public class BossPageCandidatesTest
{
    @Test
    public void everyRequiredBossHasAnExplicitAuditEntry()
    {
        assertEquals(MetricCatalogue.countMetrics().stream().filter(s -> s.getType() == HiscoreSkillType.BOSS)
            .map(Enum::name).collect(Collectors.toSet()), BossPageCandidates.load().keySet());
        assertNull("Unresolved cannot be mistaken for an empty item list", BossPageCandidates.load().get("MIMIC"));
        assertEquals(BossPageCandidates.load().get("THE_GAUNTLET"), BossPageCandidates.load().get("THE_CORRUPTED_GAUNTLET"));
    }
}
