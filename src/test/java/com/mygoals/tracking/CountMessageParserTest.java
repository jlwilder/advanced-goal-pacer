package com.mygoals.tracking;

import org.junit.Test;
import static org.junit.Assert.*;

public class CountMessageParserTest
{
    private void matches(String text, String metric, long total, boolean completion)
    {
        var observations = CountMessageParser.parse(text);
        assertEquals(text, 1, observations.size());
        assertEquals(metric, observations.get(0).metricId);
        assertEquals(total, observations.get(0).total);
        assertEquals(completion, observations.get(0).completion);
    }

    @Test public void bossAndModeMessagesMapToIndependentHiscoreMetrics()
    {
        matches("Your Vorkath kill count is: <col=ff0000>1,234</col>.", "VORKATH", 1234, true);
        matches("Your Brutus kill count is: <col=ff0000>65</col>.", "BRUTUS", 65, true);
        matches("Your Corrupted Gauntlet completion count is: <col=ff0000>104</col>.", "THE_CORRUPTED_GAUNTLET", 104, true);
        matches("Your Gauntlet completion count is: <col=ff0000>7</col>.", "THE_GAUNTLET", 7, true);
        matches("Your completion count for Chambers of Xeric is: <col=ff0000>80</col>.", "CHAMBERS_OF_XERIC", 80, true);
        matches("Your Theatre of Blood: Hard Mode completion count is: <col=ff0000>4</col>.", "THEATRE_OF_BLOOD_HARD_MODE", 4, true);
        matches("Your Tombs of Amascut: Expert Mode completion count is: <col=ff0000>12</col>.", "TOMBS_OF_AMASCUT_EXPERT", 12, true);
        matches("Your Barrows chest count is: <col=ff0000>33</col>.", "BARROWS_CHESTS", 33, true);
        matches("Your subdued Wintertodt count is: <col=ff0000>90</col>.", "WINTERTODT", 90, true);
    }

    @Test public void cluesUseTheirTierAndRiftInspectionIsNotANewCompletion()
    {
        matches("You have completed <col=ff0000>1</col> easy Treasure Trail.", "CLUE_SCROLL_EASY", 1, true);
        matches("You have completed 123 master Treasure Trails.", "CLUE_SCROLL_MASTER", 123, true);
        matches("Amount of Rifts you have closed: <col=ff0000>75</col>.", "RIFTS_CLOSED", 75, false);
    }

    @Test public void nativeAndLegacyColoursAndCompactCountsHaveTheSameMeaning()
    {
        matches("Your Vorkath kill count is: @red@1,234</col>.", "VORKATH", 1234, true);
        matches("Your <col=abcdef>Brutus</col> kill count is:<col=ff0000>65</col>.", "BRUTUS", 65, true);
        matches("Your @normal@Corrupted Gauntlet@normal@ completion count is: @highlight@104</col>.", "THE_CORRUPTED_GAUNTLET", 104, true);
        matches("You have completed @red@123</col> master Treasure Trails.", "CLUE_SCROLL_MASTER", 123, true);
        matches("Amount of Rifts you have closed: @red@75</col>.", "RIFTS_CLOSED", 75, false);
        assertTrue(CountMessageParser.parse("Other player's Vorkath kill count is: @red@99</col>.").isEmpty());
    }

    @Test public void unsupportedNpcsOtherPlayersAndMalformedValuesDoNotProduceProgress()
    {
        assertTrue(CountMessageParser.parse("Your Goblin kill count is: 99.").isEmpty());
        assertTrue(CountMessageParser.parse("Other player's Vorkath kill count is: 99.").isEmpty());
        assertTrue(CountMessageParser.parse("Your Vorkath is: 99.").isEmpty());
        assertTrue(CountMessageParser.parse("Your Vorkath kill count is: 999999999999999999999.").isEmpty());
    }
}
