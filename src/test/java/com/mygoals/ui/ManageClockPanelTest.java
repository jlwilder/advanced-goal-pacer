package com.mygoals.ui;

import java.awt.Component;
import java.awt.Container;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class ManageClockPanelTest
{
    @Test public void clockFollowsTheDetectedLocalZoneWithoutExposingSettings() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<ZoneId> local = new AtomicReference<>(ZoneId.of("America/Chicago"));
            Clock clock = Clock.fixed(Instant.parse("2026-10-17T01:05:06Z"), ZoneId.of("UTC"));
            ManageClockPanel panel = new ManageClockPanel(clock, local::get, () -> {});
            assertEquals("8:05:06 PM", ((JLabel) find(panel, "clockTime")).getText());
            assertEquals("Friday, October 16, 2026", ((JLabel) find(panel, "clockDate")).getText());
            assertTrue(((JLabel) find(panel, "clockOffset")).getText().contains("UTC-05:00"));
            local.set(ZoneId.of("Asia/Tokyo")); panel.refresh();
            assertEquals("10:05:06 AM", ((JLabel) find(panel, "clockTime")).getText());
            assertEquals("Saturday, October 17, 2026", ((JLabel) find(panel, "clockDate")).getText());
            assertEquals("Time zone: Asia/Tokyo", ((JLabel) find(panel, "clockZone")).getText());
            assertNull(find(panel, "clockTimeZone")); assertNull(find(panel, "saveClock"));
        });
    }

    @Test public void tickingClockHandlesDstAndStopsWhenClosed() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            MutableClock clock = new MutableClock(Instant.parse("2026-11-01T06:30:00Z"));
            ManageClockPanel panel = new ManageClockPanel(clock, () -> ZoneId.of("America/Chicago"), () -> {});
            panel.start(); assertTrue(panel.ticking());
            assertEquals("1:30:00 AM", ((JLabel) find(panel, "clockTime")).getText());
            assertTrue(((JLabel) find(panel, "clockOffset")).getText().contains("UTC-05:00"));
            clock.now = clock.now.plusSeconds(3600); panel.refresh();
            assertEquals("1:30:00 AM", ((JLabel) find(panel, "clockTime")).getText());
            assertTrue(((JLabel) find(panel, "clockOffset")).getText().contains("UTC-06:00"));
            assertTrue(((JLabel) find(panel, "clockRollover")).getText().contains("11/02/26, 12:00 AM"));
            panel.setDateFormat(com.mygoals.domain.GoalDateFormat.EUROPEAN);
            assertTrue(((JLabel) find(panel, "clockRollover")).getText().contains("02/11/26, 12:00 AM"));
            panel.close(); assertFalse(panel.ticking());
        });
    }

    @Test public void defaultConstructorUsesSystemZoneAndOnlyOffersClose() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger closed = new AtomicInteger();
            Clock clock = Clock.fixed(Instant.parse("2026-10-16T17:00:00Z"), ZoneId.of("UTC"));
            ManageClockPanel panel = new ManageClockPanel(clock, closed::incrementAndGet);
            assertEquals("Time zone: " + ZoneId.systemDefault().getId(), ((JLabel) find(panel, "clockZone")).getText());
            assertEquals(clock.instant().atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.US)),
                ((JLabel) find(panel, "clockTime")).getText());
            assertNull(find(panel, "saveClock")); assertNull(find(panel, "clockTimeZone"));
            ((JButton) find(panel, "closeClock")).doClick(); assertEquals(1, closed.get());
        });
    }

    static Component find(Container parent, String name)
    {
        for (Component child : parent.getComponents())
        {
            if (name.equals(child.getName())) { return child; }
            if (child instanceof Container) { Component found = find((Container) child, name); if (found != null) { return found; } }
        }
        return null;
    }
    private static final class MutableClock extends Clock
    {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
