package com.dtech.algo.time;

import java.time.Instant;
import java.time.ZonedDateTime;

public final class DateTimes {
    private DateTimes() {}

    public static ZonedDateTime toAppZone(Instant instant) {
        if (instant == null) return null;
        return ZonedDateTime.ofInstant(instant, ZoneIdHolder.get());
    }

    public static Instant toInstant(ZonedDateTime zdt) {
        return zdt == null ? null : zdt.toInstant();
    }
}
