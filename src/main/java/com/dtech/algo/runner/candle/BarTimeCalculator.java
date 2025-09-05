package com.dtech.algo.runner.candle;

import com.dtech.algo.series.Interval;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Utility class for calculating bar (candle) time boundaries based on tick timestamps and intervals.
 * This class is responsible for determining the end time of a bar based on market conventions.
 */
@Component
@Log4j2
public class BarTimeCalculator {

    /**
     * Calculates the end time of a bar based on tick timestamp and interval.
     * This implementation follows market standard bar time boundaries.
     * 
     * @param tickTime The timestamp of the tick
     * @param interval The interval of the bar series
     * @return The end time of the bar containing this tick
     */
    public ZonedDateTime calculateBarEndTime(ZonedDateTime tickTime, Interval interval) {
        if (tickTime == null) {
            log.warn("Received null tick time in calculateBarEndTime");
            return null;
        }

        // First truncate to the start of the day to get a clean reference point
        ZonedDateTime dayStart = tickTime.truncatedTo(ChronoUnit.DAYS);

        // Market standard opening at 9:15 AM IST
        ZonedDateTime marketOpen = dayStart.withHour(9).withMinute(15).withSecond(0);

        // Market standard closing at 3:30 PM IST
        ZonedDateTime marketClose = dayStart.withHour(15).withMinute(30).withSecond(0);

        // If tick is before market open or after market close, adjust accordingly
        if (tickTime.isBefore(marketOpen)) {
            // For pre-market ticks, the bar end time is the market open time
            tickTime = marketOpen;
        } else if (tickTime.isAfter(marketClose)) {
            // For after-market ticks, the bar belongs to the next trading day
            // For now, we'll use the next day's market open (this could be refined further)
            return marketOpen.plusDays(1);
        }

        switch (interval) {
            case OneMinute:
                // Ceiling to the next minute boundary
                int minute = tickTime.getMinute();
                return tickTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);

            case ThreeMinute:
                // Floor to 3-minute boundary, then add 3 minutes
                int minute3 = tickTime.getMinute();
                int minuteFloor3 = (minute3 / 3) * 3;
                return tickTime.truncatedTo(ChronoUnit.MINUTES)
                        .withMinute(minuteFloor3).plusMinutes(3);

            case FiveMinute:
                // Floor to 5-minute boundary, then add 5 minutes
                int minute5 = tickTime.getMinute();
                int minuteFloor5 = (minute5 / 5) * 5;
                return tickTime.truncatedTo(ChronoUnit.MINUTES)
                        .withMinute(minuteFloor5).plusMinutes(5);

            case FifteenMinute:
                // Floor to 15-minute boundary, then add 15 minutes
                int minute15 = tickTime.getMinute();
                int minuteFloor15 = (minute15 / 15) * 15;
                return tickTime.truncatedTo(ChronoUnit.MINUTES)
                        .withMinute(minuteFloor15).plusMinutes(15);

            case ThirtyMinute:
                // Floor to 30-minute boundary, then add 30 minutes
                int minute30 = tickTime.getMinute();
                int minuteFloor30 = (minute30 / 30) * 30;
                return tickTime.truncatedTo(ChronoUnit.MINUTES)
                        .withMinute(minuteFloor30).plusMinutes(30);

            case OneHour:
                // Floor to hour boundary, then add 1 hour
                return tickTime.truncatedTo(ChronoUnit.HOURS).plusHours(1);

            case Day:
                // For daily bars, end time is the market close time
                return marketClose;

            case Week:
                // For weekly bars, end time is Friday market close
                int dayOfWeek = tickTime.getDayOfWeek().getValue(); // 1 (Monday) to 7 (Sunday)
                int daysToFriday = (dayOfWeek <= 5) ? (5 - dayOfWeek) : (5 + 7 - dayOfWeek);
                return marketClose.plusDays(daysToFriday);

            default:
                log.warn("Unknown interval: {}, defaulting to daily", interval);
                return marketClose; // Default to daily
        }
    }
}
