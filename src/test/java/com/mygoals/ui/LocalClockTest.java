package com.mygoals.ui;

import com.google.gson.JsonParser;
import com.mygoals.domain.GoalSpec;
import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.persistence.StateCodec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalClockTest
{
    private final Instant now = Instant.parse("2026-10-16T17:00:00Z");
    private final ZoneId zone = ZoneId.of("America/Chicago");

    @Test public void newGoalsUseLocalZoneAndIgnoreLegacyPreferenceWhileExistingCalendarsAndCreditSurvive() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            ZoneId existingZone = ZoneId.of(ZoneId.systemDefault().getId().equals("Europe/London") ? "America/Chicago" : "Europe/London");
            engine.observe(Map.of("MINING", 100_000L), GoalEngine.RECOVERED, now);
            String id = engine.add(new GoalSpec("Mining", null, "MINING", GoalSpec.Mode.GAIN, 700, 0, GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, existingZone), now);
            engine.observe(Map.of("MINING", 100_050L), GoalEngine.LIVE, now);
            engine.postponeGoal(id, true, now); engine.state().clockZone = "Asia/Tokyo";
            StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); ProfileState loaded = codec.decode(codec.encode(engine.state()), engine.state().profileKey);
            assertEquals("Asia/Tokyo", loaded.clockZone); assertEquals(existingZone.getId(), loaded.goals.get(id).spec.zone);
            assertEquals(50, loaded.goals.get(id).current.progress()); assertTrue(loaded.goals.get(id).plans.values().iterator().next().postponed);
            GoalsView snapshot = new GoalsView("test", "Saved", "", true, loaded, now);
            List<GoalSpec> created = new ArrayList<>(), edited = new ArrayList<>();
            JPanel newGoal = GoalEditor.createForm(snapshot, null, created::add, () -> {});
            JPanel existing = GoalEditor.createForm(snapshot, snapshot.goals.get(0), edited::add, () -> {});
            assertNull(ManageClockPanelTest.find(newGoal, "timeZone")); assertNull(ManageClockPanelTest.find(existing, "timeZone"));
            button(newGoal, "Create goal").doClick(); button(existing, "Save changes").doClick();
            assertEquals(ZoneId.systemDefault().getId(), created.get(0).zone); assertEquals(existingZone.getId(), edited.get(0).zone);
        });
    }

    @Test public void schemaFiveAndSixRemainCompatibleWithLegacyClockFieldsAndSavedGoalZones()
    {
        GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
        engine.add(new GoalSpec("Boss", null, GoalSpec.TrackType.COUNT, "BRUTUS", GoalSpec.Mode.GAIN, 10, 0,
            GoalSpec.Cadence.WEEKLY, LocalDate.parse("2026-10-16"), null, ZoneId.of("Europe/London")), now);
        StateCodec codec = new StateCodec(net.runelite.http.api.RuneLiteAPI.GSON); var old = new JsonParser().parse(codec.encode(engine.state())).getAsJsonObject();
        old.addProperty("schemaVersion", 5); old.remove("clockZone");
        ProfileState migrated = codec.decode(old.toString(), engine.state().profileKey);
        assertEquals(10, migrated.schemaVersion); assertEquals(ZoneId.systemDefault().getId(), migrated.clockZone);
        assertEquals("Europe/London", migrated.goals.values().iterator().next().spec.zone);
        old.addProperty("schemaVersion", 6);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(old.toString(), engine.state().profileKey));
        old.addProperty("clockZone", "Invalid/Time_Zone");
        assertThrows(java.time.DateTimeException.class, () -> codec.decode(old.toString(), engine.state().profileKey));
    }

    private static JButton button(Container parent, String text)
    {
        for (Component child : parent.getComponents())
        {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) { return (JButton) child; }
            if (child instanceof Container) { JButton found = button((Container) child, text); if (found != null) { return found; } }
        }
        return null;
    }
}
