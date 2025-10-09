package com.dtech.kitecon.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;

/**
 * Centralized market-hours helper.
 * - Configurable open/close times and timezone
 * - Override flag to bypass after-hours gating
 * - Utility methods to query state and compute next open
 */
@Service
public class MarketHoursService {

    @Value("${data.market.tradingStart:09:00}")
    private String marketStartStr;

    @Value("${data.market.tradingEnd:15:30}")
    private String marketEndStr;

    @Value("${data.market.timezone:Asia/Kolkata}")
    private String marketTimezone;

    @Value("${data.market.overrideAfterHours:false}")
    private boolean overrideAfterHours;

    public boolean isOverrideEnabled() {
        return overrideAfterHours;
    }

    public boolean isMarketOpenNow() {
        ZoneId tz = ZoneId.of(marketTimezone);
        LocalTime start = LocalTime.parse(marketStartStr);
        LocalTime end = LocalTime.parse(marketEndStr);
        ZonedDateTime now = ZonedDateTime.now(tz);
        LocalTime lt = now.toLocalTime();
        return !lt.isBefore(start) && !lt.isAfter(end);
    }

    public boolean isAfterMarketNow() {
        ZoneId tz = ZoneId.of(marketTimezone);
        LocalTime end = LocalTime.parse(marketEndStr);
        ZonedDateTime now = ZonedDateTime.now(tz);
        LocalTime lt = now.toLocalTime();
        return lt.isAfter(end);
    }

    /**
     * Returns the next market open instant relative to "now".
     * - If called after close, returns tomorrow's open
     * - If called before open, returns today's open
     * - If called during session, returns tomorrow's open
     */
    public Instant nextMarketOpenAfterNow() {
        ZoneId tz = ZoneId.of(marketTimezone);
        ZonedDateTime now = ZonedDateTime.now(tz);
        LocalDate today = now.toLocalDate();
        LocalTime start = LocalTime.parse(marketStartStr);
        LocalTime end = LocalTime.parse(marketEndStr);
        ZonedDateTime todayStart = ZonedDateTime.of(today, start, tz);
        ZonedDateTime todayEnd = ZonedDateTime.of(today, end, tz);

        if (now.isBefore(todayStart)) {
            return todayStart.toInstant();
        }
        // during or after close -> tomorrow's start
        return todayStart.plusDays(1).toInstant();
    }
}
