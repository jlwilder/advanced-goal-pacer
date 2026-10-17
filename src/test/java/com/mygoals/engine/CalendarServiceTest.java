package com.mygoals.engine;

import com.mygoals.domain.GoalSpec;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class CalendarServiceTest
{
    private final CalendarService calendar = new CalendarService();
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private GoalSpec spec(GoalSpec.Cadence cadence, String start, String end)
    {
        return new GoalSpec("Goal", null, "MINING", GoalSpec.Mode.GAIN, 100, 0, cadence,
            LocalDate.parse(start), end == null ? null : LocalDate.parse(end), zone);
    }
    private CalendarService.Window window(GoalSpec spec, String day)
    {
        return calendar.current(spec, LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant());
    }
    @Test
    public void dailyWindowsFollowBothDaylightSavingTransitions()
    {
        GoalSpec spec = spec(GoalSpec.Cadence.DAILY, "2026-01-01", null);
        CalendarService.Window spring = window(spec, "2026-03-08"), fall = window(spec, "2026-11-01");
        assertEquals(23, Duration.between(spring.startInstant(), spring.endInstant()).toHours());
        assertEquals(25, Duration.between(fall.startInstant(), fall.endInstant()).toHours());
    }
    @Test
    public void monthlyAnchorReturnsTo31AfterFebruary()
    {
        GoalSpec spec = spec(GoalSpec.Cadence.MONTHLY, "2026-01-31", null);
        assertEquals(LocalDate.parse("2026-02-28"), window(spec, "2026-03-30").start);
        assertEquals(LocalDate.parse("2026-03-31"), window(spec, "2026-03-30").endExclusive);
        assertEquals(LocalDate.parse("2026-03-31"), window(spec, "2026-03-31").start);
    }
    @Test
    public void weeklyAnchorAndFinalPartialPeriodUseInclusiveDates()
    {
        GoalSpec spec = spec(GoalSpec.Cadence.WEEKLY, "2026-10-14", "2026-10-23");
        assertEquals(LocalDate.parse("2026-10-14"), window(spec, "2026-10-20").start);
        assertEquals(LocalDate.parse("2026-10-24"), window(spec, "2026-10-23").endExclusive);
        assertNull(window(spec, "2026-10-24"));
    }
    @Test
    public void oneDayRangeAndCeilingArithmeticAreSafe()
    {
        CalendarService.Window w = window(spec(GoalSpec.Cadence.ONCE, "2026-10-16", "2026-10-16"), "2026-10-16");
        assertEquals(1, java.time.temporal.ChronoUnit.DAYS.between(w.start, w.endExclusive));
        assertEquals(2, CalendarService.share(9, 5));
        assertEquals(Long.MAX_VALUE / 2 + 1, CalendarService.share(Long.MAX_VALUE, 2));
    }
}
