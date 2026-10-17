package com.mygoals.ui;

import com.mygoals.domain.ProfileState;
import com.mygoals.engine.GoalEngine;
import com.mygoals.engine.UniqueGoalTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.swing.JMenu;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class DataChecksMenuTest
{
    @Test public void gearShowsOnlyLatestCapturedPageWithRefreshGuidanceAndNoAccountLeak() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Instant now = Instant.parse("2026-10-16T17:00:00Z"); ZoneId zone = ZoneId.of("America/Chicago");
            GoalEngine engine = new GoalEngine(new ProfileState("a".repeat(64)), zone);
            engine.hiscoresChecked(now); engine.collectionSnapshot(605, UniqueGoalTest.page(605, Map.of(23956, 4L)), false, now);
            Instant lastCapture = now.plusSeconds(60);
            engine.collectionSnapshot(6413, UniqueGoalTest.page(6413, Map.of()), false, lastCapture);
            engine.acquire("drop", Map.of(23956, 1L), "Drop", now.plusSeconds(120));
            DataChecksMenu menu = new DataChecksMenu(); menu.render(new GoalsView("one", "Character", "", true, engine.state(), now));
            String localTime = DateTimeFormatter.ofPattern("MM/dd/yy h:mm a z", java.util.Locale.US).format(now.atZone(ZoneId.systemDefault()));
            assertEquals("HiScores: " + localTime, menu.hiscores.getText()); assertFalse(menu.hiscores.isEnabled());
            String captured = com.mygoals.domain.GoalDateFormat.AMERICAN.timestamp(lastCapture, ZoneId.systemDefault());
            assertEquals("Collection log: " + captured, menu.collection.getText());
            assertFalse(menu.collection instanceof JMenu); assertTrue(menu.collection.isEnabled());
            assertTrue(menu.collection.getToolTipText().contains("boss's page in your Collection Log"));
            assertTrue(menu.collection.getToolTipText().contains("automatically load the current counts"));
            menu.setDateFormat(com.mygoals.domain.GoalDateFormat.EUROPEAN); menu.render(new GoalsView("one", "Character", "", true, engine.state(), now));
            String europeanTime = com.mygoals.domain.GoalDateFormat.EUROPEAN.timestamp(now, ZoneId.systemDefault());
            assertEquals("HiScores: " + europeanTime, menu.hiscores.getText());
            assertEquals("Collection log: " + com.mygoals.domain.GoalDateFormat.EUROPEAN.timestamp(lastCapture, ZoneId.systemDefault()), menu.collection.getText());
            menu.render(new GoalsView("two", "Log in", "", false, null, now));
            assertEquals("HiScores: Not recorded", menu.hiscores.getText()); assertEquals("Collection log: Not recorded", menu.collection.getText());
        });
    }

}
