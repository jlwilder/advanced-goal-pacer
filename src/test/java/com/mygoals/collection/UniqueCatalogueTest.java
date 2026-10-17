package com.mygoals.collection;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import org.junit.Test;
import static org.junit.Assert.*;

public class UniqueCatalogueTest
{
    private final UniqueCatalogue catalogue = UniqueCatalogue.INSTANCE;

    @Test public void everyBossHasNonemptyEligibleItemsFromItsNativePages()
    {
        Set<String> bosses = Arrays.stream(HiscoreSkill.values()).filter(s -> s.getType() == HiscoreSkillType.BOSS).map(Enum::name).collect(Collectors.toSet());
        assertEquals(71, bosses.size()); assertEquals(bosses, catalogue.bosses.keySet());
        assertEquals(431, catalogue.items.size());
        catalogue.bosses.values().forEach(b -> {
            Set<Integer> nativeItems = new HashSet<>();
            b.pageIds.forEach(id -> nativeItems.addAll(catalogue.pages.get(id).itemIds));
            assertFalse(b.itemIds.isEmpty());
            assertEquals(b.itemIds.size(), new HashSet<>(b.itemIds).size());
            assertTrue(nativeItems.containsAll(b.itemIds));
            b.itemIds.forEach(id -> {
                var item = catalogue.items.get(id);
                assertNotNull(item); assertFalse(item.name.isBlank()); assertTrue(item.targetLimit >= 1);
            });
        });
    }

    @Test public void sharedPagesRespectModesAndIndividualDagannothKings()
    {
        assertTrue(catalogue.bosses.get("THE_GAUNTLET").itemIds.contains(25859));
        assertFalse(catalogue.bosses.get("THE_GAUNTLET").itemIds.contains(23859));
        assertTrue(catalogue.bosses.get("THE_CORRUPTED_GAUNTLET").itemIds.contains(23859));
        assertFalse(catalogue.bosses.get("CHAMBERS_OF_XERIC").itemIds.contains(22386));
        assertTrue(catalogue.bosses.get("CHAMBERS_OF_XERIC_CHALLENGE_MODE").itemIds.contains(22386));
        assertFalse(catalogue.bosses.get("THEATRE_OF_BLOOD").itemIds.contains(25746));
        assertFalse(catalogue.bosses.get("TOMBS_OF_AMASCUT").itemIds.contains(27255));
        assertFalse(catalogue.bosses.get("NIGHTMARE").itemIds.contains(25837));
        assertEquals(Set.of(12644, 6731, 6739, 6562), new HashSet<>(catalogue.bosses.get("DAGANNOTH_PRIME").itemIds));
        assertEquals(Set.of(12645, 6737, 6735, 6739), new HashSet<>(catalogue.bosses.get("DAGANNOTH_REX").itemIds));
        assertEquals(Set.of(12643, 6733, 6739, 6724), new HashSet<>(catalogue.bosses.get("DAGANNOTH_SUPREME").itemIds));
    }

    @Test public void mimicIncludesThirdAgeBonusRewardsAndItsEarnedCase()
    {
        var mimic = catalogue.bosses.get("MIMIC");
        assertEquals(25, mimic.itemIds.size());
        assertTrue(mimic.itemIds.containsAll(Set.of(23185, 30926, 20014)));
        assertEquals(1, catalogue.items.get(30926).targetLimit);
        assertThrows(IllegalArgumentException.class, () -> catalogue.validateTargets("MIMIC", Map.of(30926, 2L)));
        assertThrows(IllegalArgumentException.class, () -> catalogue.validateTargets("THE_GAUNTLET", Map.of(23859, 1L)));
    }

    @Test public void raidModesArePresentAndRepeatableChambersRewardsAllowMultipleTargets()
    {
        assertTrue(catalogue.bosses.keySet().containsAll(Set.of("CHAMBERS_OF_XERIC", "CHAMBERS_OF_XERIC_CHALLENGE_MODE",
            "THEATRE_OF_BLOOD", "THEATRE_OF_BLOOD_HARD_MODE", "TOMBS_OF_AMASCUT", "TOMBS_OF_AMASCUT_EXPERT")));
        catalogue.validateTargets("CHAMBERS_OF_XERIC_CHALLENGE_MODE", Map.of(24670, 3L, 22386, 2L));
        assertThrows(IllegalArgumentException.class, () -> catalogue.validateTargets("CHAMBERS_OF_XERIC", Map.of(24670, 3L)));
        catalogue.validateTargets("THEATRE_OF_BLOOD_HARD_MODE", Map.of(25742, 3L));
        assertThrows(IllegalArgumentException.class, () -> catalogue.validateTargets("THEATRE_OF_BLOOD", Map.of(25742, 1L)));
        assertTrue(catalogue.bosses.get("TOMBS_OF_AMASCUT").itemIds.contains(30893));
        catalogue.validateTargets("TOMBS_OF_AMASCUT_EXPERT", Map.of(27372, 1L, 27255, 1L, 27248, 1L));
        assertThrows(IllegalArgumentException.class, () -> catalogue.validateTargets("TOMBS_OF_AMASCUT", Map.of(27372, 1L)));
    }
}
