package com.dtech.algo.time;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Central holder for the application ZoneId.
 * Default is UTC; can be overridden at startup by configuration.
 */
public final class ZoneIdHolder {
    private static volatile ZoneId zoneId = ZoneOffset.UTC;

    private ZoneIdHolder() {}

    public static ZoneId get() {
        return zoneId;
    }

    public static void set(ZoneId newZone) {
        if (newZone != null) {
            zoneId = newZone;
        }
    }
}
