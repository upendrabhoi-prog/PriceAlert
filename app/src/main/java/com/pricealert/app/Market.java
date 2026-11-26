package com.pricealert.app;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Price-check window: Monday to Friday, 9:00 AM to 3:30 PM IST (no automatic checks outside it). */
public final class Market {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int OPEN = 9 * 60;
    private static final int CLOSE = 15 * 60 + 30;

    private Market() { }

    private static boolean weekend(ZonedDateTime z) {
        DayOfWeek d = z.getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public static boolean isOpen(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(IST);
        if (weekend(z)) return false;
        int m = z.getHour() * 60 + z.getMinute();
        return m >= OPEN && m <= CLOSE;
    }

    public static long nextOpen(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(IST);
        ZonedDateTime c = z.withHour(9).withMinute(0).withSecond(0).withNano(0);
        if (!c.isAfter(z)) c = c.plusDays(1);
        while (weekend(c)) c = c.plusDays(1);
        return c.toInstant().toEpochMilli();
    }

    public static long endOfToday(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(IST);
        return z.toLocalDate().plusDays(1).atStartOfDay(IST).toInstant().toEpochMilli() - 1;
    }
}
