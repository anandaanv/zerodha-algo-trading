package com.dtech.kitecon.misc;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Centralized time helpers. Use IST for all "now" references.
 */
public final class TimeUtils {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TimeUtils() {}

    public static Instant nowIst() {
        // Anchor "now" to IST clock, then convert to Instant
        return ZonedDateTime.now(IST).toInstant();
    }
}
